package org.jfxcore.fxml;

import com.intellij.codeInsight.daemon.impl.analysis.XmlUnusedNamespaceInspection;
import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jfxcore.fxml.lang.Fxml2NamespaceImplicitUsageProvider;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Fxml2NamespaceImplicitUsageProvider}: the provider that suppresses the
 * platform's false-positive "Namespace declaration is never used" warning
 * ({@link XmlUnusedNamespaceInspection}) for the FXML/2 namespace declaration
 * {@code xmlns:fx="http://jfxcore.org/fxml/2.0"}.
 *
 * <h3>Why is this needed?</h3>
 * <p>An FXML/2 document is classified as FXML/2 purely by the presence of the
 * {@code http://jfxcore.org/fxml/2.0} namespace URI (see {@code Fxml2FileTypeOverrider} and
 * {@code Fxml2FileType#isFxml2}). When a document binds only through expressions such as
 * {@code ${...}} and never references an {@code fx:} intrinsic, the {@code fx} prefix has no
 * textual usage, so the platform's {@link XmlUnusedNamespaceInspection} reports the
 * declaration as unused and offers to remove it.
 *
 * <p>Removing the declaration would delete the only occurrence of the namespace URI, causing
 * the IDE to stop recognizing the file as FXML/2 altogether. The declaration is therefore
 * load-bearing and must not be flagged as unused.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2NamespaceImplicitUsageTest extends Fxml2TestBase {

    /**
     * A standalone FXML/2 document that declares {@code xmlns:fx} but uses only a binding
     * expression (no {@code fx:} intrinsic). The {@code fx} prefix has no textual usage.
     */
    private static final String NO_FX_INTRINSIC = """
            <?xml version="1.0" encoding="UTF-8"?>
            <?import javafx.scene.control.Button?>
            <?import javafx.scene.layout.VBox?>
            <VBox xmlns="http://javafx.com/javafx"
                  xmlns:fx="http://jfxcore.org/fxml/2.0">
              <Button text="${String.format('Width: %.0f', width)}"/>
            </VBox>
            """;

    // -----------------------------------------------------------------------
    // Provider unit test
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2NamespaceImplicitUsageProvider#isImplicitUsage} must return {@code true}
     * for the {@code xmlns:fx="http://jfxcore.org/fxml/2.0"} namespace declaration, even when
     * no {@code fx:} intrinsic is referenced in the document.
     */
    @Test
    void providerMarksFxml2NamespaceDeclarationAsUsed() {
        XmlFile file = (XmlFile) getFixture().configureByText("NoFxIntrinsic.fxml", NO_FX_INTRINSIC);

        ReadAction.run(() -> {
            XmlTag root = file.getRootTag();
            assertNotNull(root, "Root tag must exist");

            XmlAttribute fxNamespace = root.getAttribute("xmlns:fx");
            assertNotNull(fxNamespace, "xmlns:fx declaration must exist");
            assertTrue(fxNamespace.isNamespaceDeclaration(),
                    "xmlns:fx must be a namespace declaration");

            var provider = new Fxml2NamespaceImplicitUsageProvider();

            assertTrue(provider.isImplicitUsage(fxNamespace),
                    "isImplicitUsage must return true for the FXML/2 namespace declaration: "
                    + "the declaration is the document's only marker that classifies the file "
                    + "as FXML/2, so it must never be flagged as unused.");
        });
    }

    /**
     * The provider must NOT mark an unrelated namespace declaration as implicitly used; only
     * the FXML/2 namespace declaration is load-bearing for file classification. (The default
     * {@code xmlns} JavaFX declaration is exercised here: it is referenced by the root tag and
     * so is never reported as unused by the platform anyway, but the provider must not claim
     * it regardless.)
     */
    @Test
    void providerDoesNotMarkUnrelatedNamespaceDeclarationAsUsed() {
        XmlFile file = (XmlFile) getFixture().configureByText("Unrelated.fxml", NO_FX_INTRINSIC);

        ReadAction.run(() -> {
            XmlTag root = file.getRootTag();
            assertNotNull(root, "Root tag must exist");

            XmlAttribute javafxNamespace = root.getAttribute("xmlns");
            assertNotNull(javafxNamespace, "default xmlns declaration must exist");
            assertTrue(javafxNamespace.isNamespaceDeclaration(),
                    "xmlns must be a namespace declaration");

            var provider = new Fxml2NamespaceImplicitUsageProvider();

            assertFalse(provider.isImplicitUsage(javafxNamespace),
                    "isImplicitUsage must return false for a namespace declaration other than "
                    + "the FXML/2 namespace");
        });
    }

    // -----------------------------------------------------------------------
    // Namespace-matching semantics (mirror the fxml2 compiler)
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2ImportResolver#isFxml2Namespace} must mirror the compiler's
     * {@code FxmlNamespace.FXML.equalsIgnoreCase}: it accepts only the exact FXML/2 URI or a
     * single trailing slash, and rejects versioned/sub-path forms, which the compiler does
     * not recognize for the FXML/2 namespace.
     */
    @Test
    void fxml2NamespaceMatchingIsStrictLikeCompiler() {
        assertTrue(Fxml2ImportResolver.isFxml2Namespace("http://jfxcore.org/fxml/2.0"),
                "Exact FXML/2 URI must match");
        assertTrue(Fxml2ImportResolver.isFxml2Namespace("http://jfxcore.org/fxml/2.0/"),
                "Single trailing slash must match (compiler equalsIgnoreCase accepts it)");

        assertFalse(Fxml2ImportResolver.isFxml2Namespace("http://jfxcore.org/fxml/2.0/21"),
                "Sub-path must NOT match: the compiler matches the FXML/2 namespace with "
                + "equalsIgnoreCase, which rejects sub-paths");
        assertFalse(Fxml2ImportResolver.isFxml2Namespace("http://jfxcore.org/fxml/1.0"),
                "A different version must not match");
        assertFalse(Fxml2ImportResolver.isFxml2Namespace("http://javafx.com/javafx"),
                "The JavaFX namespace must not match the FXML/2 predicate");
        assertFalse(Fxml2ImportResolver.isFxml2Namespace(null), "null must not match");
    }

    /**
     * {@link Fxml2ImportResolver#isJavaFxNamespace} must mirror the compiler's
     * {@code FxmlNamespace.JAVAFX.isParentOf}: it accepts the exact URI, a trailing slash, and
     * versioned sub-paths such as {@code http://javafx.com/javafx/21}.
     */
    @Test
    void javaFxNamespaceMatchingAcceptsVersionSubPathsLikeCompiler() {
        assertTrue(Fxml2ImportResolver.isJavaFxNamespace("http://javafx.com/javafx"),
                "Exact JavaFX URI must match");
        assertTrue(Fxml2ImportResolver.isJavaFxNamespace("http://javafx.com/javafx/"),
                "Trailing slash must match");
        assertTrue(Fxml2ImportResolver.isJavaFxNamespace("http://javafx.com/javafx/21"),
                "Versioned sub-path must match (compiler isParentOf accepts it)");

        assertFalse(Fxml2ImportResolver.isJavaFxNamespace("http://javafx.com/javafxx"),
                "A URI that is not delimited by '/' after the base must not match");
        assertFalse(Fxml2ImportResolver.isJavaFxNamespace("http://jfxcore.org/fxml/2.0"),
                "The FXML/2 namespace must not match the JavaFX predicate");
        assertFalse(Fxml2ImportResolver.isJavaFxNamespace(null), "null must not match");
    }

    // -----------------------------------------------------------------------
    // Inspection integration test
    // -----------------------------------------------------------------------

    /**
     * With {@link XmlUnusedNamespaceInspection} enabled, the FXML/2 namespace declaration must
     * not be highlighted as "never used", because {@link Fxml2NamespaceImplicitUsageProvider}
     * marks it as implicitly used.
     */
    @Test
    void unusedNamespaceInspectionDoesNotFlagFxml2Namespace() {
        getFixture().enableInspections(new XmlUnusedNamespaceInspection());
        getFixture().configureByText("NoFxIntrinsicHighlight.fxml", NO_FX_INTRINSIC);
        // The document text contains no <warning>/<error> markers: any highlight from
        // XmlUnusedNamespaceInspection on the xmlns:fx declaration would fail this check.
        getFixture().checkHighlighting(true, false, false);
    }
}
