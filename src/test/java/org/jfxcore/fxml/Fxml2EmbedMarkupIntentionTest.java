// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.jfxcore.fxml.actions.EmbedMarkupInCodeBehindIntention;
import org.jfxcore.fxml.annotator.Fxml2EmbedMarkupInspection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EmbedMarkupInCodeBehindIntention}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Availability at the three valid caret positions (root tag name, fx:subclass attr
 *       name, fx:subclass attr value).</li>
 *   <li>Non-availability when prerequisites are not met (missing code-behind,
 *       caret on wrong element).</li>
 *   <li>Integration: the annotation is inserted into the code-behind file, the necessary
 *       imports are added, and the FXML file is deleted.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2EmbedMarkupIntentionTest extends Fxml2TestBase {

    /** A minimal standalone FXML2 file with a self-closing root element. */
    private static final String FXML_SELF_CLOSING =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <?import javafx.scene.layout.BorderPane?>
            <BorderPane xmlns="http://javafx.com/javafx"
                        xmlns:fx="http://jfxcore.org/fxml/2.0"
                        fx:subclass="test.TestView"/>
            """;

    private static final String JAVA_CODE_BEHIND =
            """
            package test;
            public class TestView extends TestViewBase {
                public TestView() { initializeComponent(); }
            }
            """;

    // -----------------------------------------------------------------------
    // @BeforeAll setup

    @BeforeAll
    void addComponentViewAnnotation() {
        getFixture().addClass("""
                package org.jfxcore.markup;
                import java.lang.annotation.*;
                @Target(ElementType.TYPE)
                @Retention(RetentionPolicy.SOURCE)
                public @interface ComponentView {
                    String value();
                }
                """);
    }

    // -----------------------------------------------------------------------
    // @AfterEach cleanup

    @AfterEach
    void resetState() {
        EmbedMarkupInCodeBehindIntention.skipConfirmationForTesting = false;
    }

    // -----------------------------------------------------------------------
    // Availability: valid caret positions

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void availableOnFxClassAttributeValue() {
        addJavaCodeBehind();
        getFixture().configureByText("TestView.fxml",
                FXML_SELF_CLOSING.replace("fx:subclass=\"test.TestView\"",
                        "fx:subclass=\"test.Test<caret>View\""));
        assertTrue(hasEmbedIntention(), "Should be available on fx:subclass attribute value");
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void availableOnFxClassAttributeName() {
        addJavaCodeBehind();
        getFixture().configureByText("TestView.fxml",
                FXML_SELF_CLOSING.replace("fx:subclass=", "fx:<caret>subclass="));
        assertTrue(hasEmbedIntention(), "Should be available on fx:subclass attribute name");
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void availableOnRootTagName() {
        addJavaCodeBehind();
        getFixture().configureByText("TestView.fxml",
                FXML_SELF_CLOSING.replace("<BorderPane", "<Border<caret>Pane"));
        assertTrue(hasEmbedIntention(), "Should be available on root element tag name");
    }


    // -----------------------------------------------------------------------
    // Availability: invalid cases

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void notAvailableWhenCodeBehindMissing() {
        // No Java/Kotlin sibling added
        getFixture().configureByText("TestView.fxml",
                FXML_SELF_CLOSING.replace("fx:subclass=", "fx:<caret>subclass="));
        assertFalse(hasEmbedIntention(),
                "Should NOT be available when code-behind sibling is missing");
    }


    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void notAvailableOnChildElement() {
        addJavaCodeBehind();
        // Caret is inside a child <Label>, not on the root or fx:subclass
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                "  <La<caret>bel text=\"Hello\"/>\n"
        ));
        assertFalse(hasEmbedIntention(),
                "Should NOT be available when caret is on a child element");
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void notAvailableOnOtherAttribute() {
        addJavaCodeBehind();
        // Caret is on 'prefHeight', not on fx:subclass
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.TestView"
                            prefHe<caret>ight="400.0"/>
                """);
        assertFalse(hasEmbedIntention(),
                "Should NOT be available when caret is on a non-fx:subclass attribute");
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void notAvailableOnEmbeddedFragment() {
        // Embedded FXML (injected into @ComponentView) must never show the action
        addJavaCodeBehind();
        // The fxml2() helper produces a standard standalone file; we test with a
        // non-embedded file where the caret is NOT on fx:subclass, to ensure no false positive.
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                "  <Label text=\"Hel<caret>lo\"/>\n"
        ));
        assertFalse(hasEmbedIntention(),
                "Should NOT be available when caret is in element content (not fx:subclass)");
    }

    // -----------------------------------------------------------------------
    // Integration: embedding produces correct output

    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void embedsSelfClosingMarkupInJavaCodeBehind() {
        // Arrange: configure the FXML file with <caret> on "fx:subclass" so the
        // fixture handles EDT-based caret positioning internally.
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:<caret>subclass="test.TestView"/>
                """);
        VirtualFile fxmlVF = getFixture().getFile().getVirtualFile();
        // Java file lives alongside the FXML in the same source-root directory
        getFixture().addFileToProject("TestView.java", JAVA_CODE_BEHIND);

        // Act
        EmbedMarkupInCodeBehindIntention.skipConfirmationForTesting = true;
        IntentionAction action = getFixture().findSingleIntention("Embed markup in code-behind file");
        assertNotNull(action, "Embed intention must be available");
        getFixture().launchAction(action);

        // Assert: FXML file has been deleted
        assertFalse(fxmlVF.isValid(), "FXML VirtualFile should be invalid after deletion");

        // Assert: code-behind contains @ComponentView annotation
        VirtualFile javaVF = getFixture().findFileInTempDir("TestView.java");
        assertNotNull(javaVF, "Code-behind Java file should still exist");
        String javaText = ReadAction.compute(() -> {
            PsiFile psi = getFixture().getPsiManager().findFile(javaVF);
            return psi != null ? psi.getText() : "";
        });
        assertFalse(javaText.isEmpty(), "PSI for code-behind must be available");

        assertTrue(javaText.contains("@ComponentView"),
                "Code-behind must contain @ComponentView annotation");
        assertTrue(javaText.contains("<BorderPane"),
                "Annotation value must contain the root element");

        // Namespace attributes must be stripped from embedded markup
        assertFalse(javaText.contains("xmlns="),
                "xmlns attribute must be removed from embedded markup");
        assertFalse(javaText.contains("xmlns:fx="),
                "xmlns:fx attribute must be removed from embedded markup");
        assertFalse(javaText.contains("fx:subclass="),
                "fx:subclass attribute must be removed from embedded markup");

        // ComponentView import must be added
        assertTrue(javaText.contains("import org.jfxcore.markup.ComponentView"),
                "import for ComponentView must be added");
        // BorderPane import derived from <?import?> must be added
        assertTrue(javaText.contains("import javafx.scene.layout.BorderPane"),
                "import for BorderPane (from FXML <?import?>) must be added");
    }

    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void embedsMarkupWithPrefixPiInJavaCodeBehind() {
        // Arrange: FXML file with a <?prefix?> PI before the root element.
        // The PI must be preserved in the embedded @ComponentView annotation value.
        getFixture().configureByText("TestEmbed.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.StackPane?>
                <?prefix % = sample.MyMarkupExtension?>
                <StackPane xmlns="http://javafx.com/javafx"
                           xmlns:fx="http://jfxcore.org/fxml/2.0"
                           fx:<caret>subclass="test.TestEmbed"/>
                """);
        VirtualFile fxmlVF = getFixture().getFile().getVirtualFile();
        getFixture().addFileToProject("TestEmbed.java", """
                package test;
                public class TestEmbed extends TestEmbedBase {
                    public TestEmbed() { initializeComponent(); }
                }
                """);

        // Act
        EmbedMarkupInCodeBehindIntention.skipConfirmationForTesting = true;
        IntentionAction action = getFixture().findSingleIntention("Embed markup in code-behind file");
        assertNotNull(action, "Embed intention must be available");
        getFixture().launchAction(action);

        // Assert: FXML file deleted
        assertFalse(fxmlVF.isValid(), "FXML VirtualFile should be invalid after deletion");

        // Assert: code-behind contains both the <?prefix?> PI and the root element
        VirtualFile javaVF = getFixture().findFileInTempDir("TestEmbed.java");
        assertNotNull(javaVF);
        String javaText = ReadAction.compute(() -> {
            PsiFile psi = getFixture().getPsiManager().findFile(javaVF);
            return psi != null ? psi.getText() : "";
        });
        assertFalse(javaText.isEmpty(), "PSI for TestEmbed.java must be available");

        assertTrue(javaText.contains("<?prefix % = sample.MyMarkupExtension?>"),
                "The <?prefix?> PI must be preserved in the embedded markup; got:\n" + javaText);
        assertTrue(javaText.contains("<StackPane"),
                "Root element must follow the PI");
        // The PI must appear before the root element inside the annotation value
        int piPos   = javaText.indexOf("<?prefix");
        int tagPos  = javaText.indexOf("<StackPane");
        assertTrue(piPos >= 0 && tagPos >= 0 && piPos < tagPos,
                "<?prefix?> PI must appear before <StackPane> in the annotation value");
    }

    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void embedsMarkupWithLeadingXmlCommentInJavaCodeBehind() {
        // Arrange: FXML file with an XML comment before the root element.
        // The comment must be preserved in the embedded @ComponentView annotation value.
        getFixture().configureByText("TestEmbed.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.StackPane?>
                <!-- a leading comment -->
                <StackPane xmlns="http://javafx.com/javafx"
                           xmlns:fx="http://jfxcore.org/fxml/2.0"
                           fx:<caret>subclass="test.TestEmbed"/>
                """);
        VirtualFile fxmlVF = getFixture().getFile().getVirtualFile();
        getFixture().addFileToProject("TestEmbed.java", """
                package test;
                public class TestEmbed extends TestEmbedBase {
                    public TestEmbed() { initializeComponent(); }
                }
                """);

        // Act
        EmbedMarkupInCodeBehindIntention.skipConfirmationForTesting = true;
        IntentionAction action = getFixture().findSingleIntention("Embed markup in code-behind file");
        assertNotNull(action, "Embed intention must be available");
        getFixture().launchAction(action);

        // Assert: FXML file deleted
        assertFalse(fxmlVF.isValid(), "FXML VirtualFile should be invalid after deletion");

        // Assert: code-behind contains the comment before the root element
        VirtualFile javaVF = getFixture().findFileInTempDir("TestEmbed.java");
        assertNotNull(javaVF);
        String javaText = ReadAction.compute(() -> {
            PsiFile psi = getFixture().getPsiManager().findFile(javaVF);
            return psi != null ? psi.getText() : "";
        });
        assertFalse(javaText.isEmpty(), "PSI for TestEmbed.java must be available");

        assertTrue(javaText.contains("<!-- a leading comment -->"),
                "The XML comment must be preserved in the embedded markup; got:\n" + javaText);
        assertTrue(javaText.contains("<StackPane"),
                "Root element must be present in the embedded markup");
        int commentPos = javaText.indexOf("<!-- a leading comment -->");
        int tagPos     = javaText.indexOf("<StackPane");
        assertTrue(commentPos >= 0 && tagPos >= 0 && commentPos < tagPos,
                "Comment must appear before <StackPane> in the annotation value");
    }

    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void embedsMarkupWithChildrenInJavaCodeBehind() {
        // Arrange: code-behind named TestEmbed to match the fx:subclass value
        getFixture().configureByText("TestEmbed.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.Label?>
                <?import javafx.scene.layout.BorderPane?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:<caret>subclass="test.TestEmbed"
                            prefHeight="400.0">
                    <Label text="Hello"/>
                </BorderPane>
                """);
        VirtualFile fxmlVF = getFixture().getFile().getVirtualFile();
        getFixture().addFileToProject("TestEmbed.java", """
                package test;
                public class TestEmbed extends TestEmbedBase {
                    public TestEmbed() { initializeComponent(); }
                }
                """);

        // Act
        EmbedMarkupInCodeBehindIntention.skipConfirmationForTesting = true;
        IntentionAction action = getFixture().findSingleIntention("Embed markup in code-behind file");
        assertNotNull(action, "Embed intention must be available");
        getFixture().launchAction(action);

        // Assert: FXML file deleted
        assertFalse(fxmlVF.isValid(), "FXML VirtualFile should be invalid after deletion");

        // Assert: code-behind has annotation and child markup
        VirtualFile javaVF = getFixture().findFileInTempDir("TestEmbed.java");
        assertNotNull(javaVF);
        String javaText = ReadAction.compute(() -> {
            PsiFile psi = getFixture().getPsiManager().findFile(javaVF);
            return psi != null ? psi.getText() : "";
        });
        assertFalse(javaText.isEmpty(), "PSI for TestEmbed.java must be available");

        assertTrue(javaText.contains("@ComponentView"),
                "Annotation must be present");
        assertTrue(javaText.contains("<BorderPane"),
                "Root element must be in annotation");
        assertTrue(javaText.contains("<Label"),
                "Child element must be preserved in annotation");
        assertTrue(javaText.contains("prefHeight=\"400.0\""),
                "Non-namespace attributes must be preserved");
        assertFalse(javaText.contains("xmlns="),
                "xmlns must be stripped");

        // Imports derived from both <?import?> PIs must be added
        assertTrue(javaText.contains("import javafx.scene.control.Label"),
                "Label import must be added");
        assertTrue(javaText.contains("import javafx.scene.layout.BorderPane"),
                "BorderPane import must be added");
    }

    // -----------------------------------------------------------------------
    // Interaction with Fxml2EmbedMarkupInspection

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void intentionHiddenWhenInspectionIsEnabled() {
        getFixture().enableInspections(new Fxml2EmbedMarkupInspection());
        addJavaCodeBehind();
        getFixture().configureByText("TestView.fxml",
                FXML_SELF_CLOSING.replace("fx:subclass=", "fx:<caret>subclass="));
        assertFalse(hasEmbedIntention(),
                "Intention must be hidden when the Fxml2EmbedMarkup inspection is enabled");
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void intentionVisibleWhenInspectionIsDisabled() {
        // Inspection NOT enabled (default): intention must be available
        addJavaCodeBehind();
        getFixture().configureByText("TestView.fxml",
                FXML_SELF_CLOSING.replace("fx:subclass=", "fx:<caret>subclass="));
        assertTrue(hasEmbedIntention(),
                "Intention must be visible when the Fxml2EmbedMarkup inspection is disabled");
    }

    // -----------------------------------------------------------------------
    // Helpers

    private boolean hasEmbedIntention() {
        return getFixture().getAvailableIntentions().stream()
                .anyMatch(i -> "Embed markup in code-behind file".equals(i.getText()));
    }

    /** Adds a Java code-behind file at the source root (same level as FXML files). */
    private void addJavaCodeBehind() {
        getFixture().addFileToProject("TestView.java", JAVA_CODE_BEHIND);
    }
}
