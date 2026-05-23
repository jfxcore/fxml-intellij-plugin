package org.jfxcore.fxml;

import com.intellij.lang.ImportOptimizer;
import com.intellij.lang.LanguageImportStatements;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.codeInsight.lookup.LookupElement;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtImportDirective;
import org.jfxcore.fxml.annotator.Fxml2FxAttributeInspection;
import org.jfxcore.fxml.annotator.Fxml2InitializeComponentInspection;
import org.jfxcore.fxml.annotator.Fxml2UnusedImportsInspection;
import org.jfxcore.fxml.codeinsight.Fxml2EmbeddedKotlinImportOptimizer;
import org.jfxcore.fxml.lang.Fxml2BindingSegmentReference;
import org.jfxcore.fxml.lang.Fxml2EmbeddedImplicitUsageProvider;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.lang.Fxml2FxIdFindUsagesHandlerFactory;
import org.jfxcore.fxml.lang.Fxml2KotlinUnusedImportSuppressor;
import org.jfxcore.fxml.lang.Fxml2KotlinUnusedSymbolSuppressor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for embedded FXML2 support via the {@code @ComponentView} annotation in <em>Kotlin</em>
 * source files.
 *
 * <p>Each test configures a Kotlin file with a class annotated with
 * {@code @org.jfxcore.markup.ComponentView}. The language injector
 * ({@link org.jfxcore.fxml.lang.Fxml2KotlinMarkupAnnotationInjector}) injects
 * XML into the annotation's string value, and the plugin's full feature set
 * (diagnostics, navigation, code completion, find usages, rename) should
 * work on the injected fragment: exactly as for Java embedded markup.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class Fxml2KotlinEmbeddedMarkupTest extends Fxml2TestBase {

    /** Add the {@code @ComponentView} annotation class to the test project classpath. */
    @BeforeAll
    void addMarkupAnnotation() {
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
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a minimal Kotlin file content with {@code @ComponentView} applied to a class in
     * package {@code test}, with the standard {@code javafx.scene.layout.*} and
     * {@code javafx.scene.control.*} imports.
     *
     * @param cls        simple class name, e.g. {@code "EmbeddedView"}
     * @param markupBody the XML to embed (placed verbatim in the triple-quoted string)
     */
    private static String kotlinWithMarkup(String cls, String markupBody) {
        return "package test\n"
                + "import org.jfxcore.markup.ComponentView\n"
                + "import javafx.scene.layout.*\n"
                + "import javafx.scene.control.*\n"
                + "@ComponentView(\"\"\"\n"
                + markupBody
                + "\"\"\")\n"
                + "open class " + cls + " {\n"
                + "    protected fun initializeComponent() {}\n"
                + "    init { initializeComponent() }\n"
                + "}\n";
    }


    /**
     * Finds the first class in the currently configured file that carries the
     * {@code @ComponentView} annotation and returns it as a {@link PsiClass}.
     *
     * <p>For Java files the class is a {@code PsiClass} directly. For Kotlin files the
     * class is a {@code KtClassOrObject}; this method obtains the corresponding
     * {@code KtLightClass} from {@link org.jetbrains.kotlin.asJava.KotlinAsJavaSupport}.
     */
    private PsiClass findMarkupClass() {
        // Java path: PsiClass instances are direct children of the PSI tree
        for (PsiClass c : PsiTreeUtil.findChildrenOfType(
                getFixture().getFile(), PsiClass.class)) {
            if (c.hasAnnotation(Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN)) return c;
        }

        // Kotlin path: KtClassOrObject instances are the Kotlin PSI representation
        try {
            for (var ktClass : PsiTreeUtil.findChildrenOfType(
                    getFixture().getFile(),
                    org.jetbrains.kotlin.psi.KtClassOrObject.class)) {
                for (var annotEntry : ktClass.getAnnotationEntries()) {
                    var shortName = annotEntry.getShortName();
                    if (shortName != null && "ComponentView".equals(shortName.getIdentifier())) {
                        return org.jetbrains.kotlin.asJava.KotlinAsJavaSupport
                                .getInstance(ktClass.getProject())
                                .getLightClass(ktClass);
                    }
                }
            }
        } catch (NoClassDefFoundError ignored) {
            // Kotlin plugin not available: skip
        }

        return null;
    }

    /**
     * Returns the injected {@link XmlFile} for the currently configured host file,
     * or {@code null} if the injection has not been computed yet.
     */
    private XmlFile getInjectedXmlFile() {
        return ReadAction.compute(() -> {
            PsiClass cls = findMarkupClass();
            return cls != null ? Fxml2EmbeddedUtil.getInjectedXmlFile(cls) : null;
        });
    }

    // -----------------------------------------------------------------------
    // Injection fires and file is recognized as embedded FXML2
    // -----------------------------------------------------------------------

    /**
     * The Kotlin language injector must fire and the resulting injected {@link XmlFile}
     * must pass both {@link Fxml2EmbeddedUtil#isEmbeddedFxml2} and
     * {@link Fxml2FileType#isFxml2}.
     */
    @Test
    void injectedXmlFileIsRecognizedAsFxml2() {
        getFixture().configureByText("EmbeddedView.kt",
                kotlinWithMarkup("EmbeddedView", "    <StackPane/>\n"));

        getFixture().checkHighlighting(false, false, false);

        ReadAction.run(() -> {
            XmlFile xmlFile = getInjectedXmlFile();
            assertNotNull(xmlFile, "Injected XmlFile must be present after highlighting");
            assertTrue(Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile),
                    "Injected file must be recognized as embedded FXML2 via wrapper namespace");
            assertTrue(Fxml2FileType.isFxml2(xmlFile),
                    "isFxml2(PsiFile) must return true for injected FXML2 Kotlin fragment");
        });
    }

    // -----------------------------------------------------------------------
    // Import resolution / tag name resolution
    // -----------------------------------------------------------------------

    /**
     * Directly verifies that {@link org.jfxcore.fxml.resolve.Fxml2ImportResolver#parseImports}
     * returns a non-empty list and that {@code StackPane} resolves for the injected XML file.
     */
    @Test
    void importResolutionWorksForKotlinInjectedFile() {
        getFixture().configureByText("EmbeddedViewDiag.kt",
                kotlinWithMarkup("EmbeddedViewDiag", "    <StackPane/>\n"));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = getInjectedXmlFile();
            assertNotNull(xmlFile, "Injected XmlFile must be present");

            var imports = org.jfxcore.fxml.resolve.Fxml2ImportResolver.parseImports(xmlFile);
            assertFalse(imports.isEmpty(),
                    "parseImports must return non-empty for Kotlin embedded FXML2. "
                    + "isInjected: " + InjectedLanguageManager.getInstance(getFixture().getProject())
                            .isInjectedFragment(xmlFile)
                    + ", rootName: " + (xmlFile.getRootTag() != null
                            ? xmlFile.getRootTag().getName() : "null-root"));

            var resolved = org.jfxcore.fxml.resolve.Fxml2ImportResolver.resolve(
                    "StackPane", xmlFile);
            assertNotNull(resolved,
                    "Fxml2ImportResolver.resolve('StackPane') must return non-null. imports=" + imports);
        });
    }

    /**
     * A class tag whose class is covered by a Kotlin wildcard import must resolve -
     * no "Cannot resolve symbol" error.
     */
    @Test
    void tagNamesResolveToJavaClasses() {
        getFixture().configureByText("EmbeddedView.kt",
                kotlinWithMarkup("EmbeddedView",
                        """
                            <StackPane>
                              <Button text="hello"/>
                            </StackPane>
                        """));

        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * An unknown element tag must produce a "Cannot resolve symbol" error.
     */
    @Test
    void unknownTagProducesError() {
        getFixture().configureByText("EmbeddedView.kt",
                kotlinWithMarkup("EmbeddedView", "    <UnknownWidget123/>\n"));

        var errors = getFixture().doHighlighting(HighlightSeverity.ERROR);
        boolean hasUnresolved = errors.stream().anyMatch(
                h -> h.getDescription() != null
                     && h.getDescription().contains("UnknownWidget123"));
        assertTrue(hasUnresolved,
                "Expected a 'Cannot resolve symbol UnknownWidget123' error in Kotlin embedded FXML2");
    }

    /**
     * An unresolvable class name inside {@code <fx:context>} element notation in embedded
     * Kotlin FXML2 must produce a "Cannot resolve symbol" error (same as standalone FXML2
     * and Java embedded FXML2).
     */
    @Test
    void unresolvableClassInsideFxContextElementProducesError() {
        getFixture().configureByText("EmbeddedView.kt", kotlinWithMarkup("EmbeddedView", """
                    <StackPane>
                      <fx:context>
                        <CannotResolveMe123/>
                      </fx:context>
                    </StackPane>
                """));

        var errors = getFixture().doHighlighting(HighlightSeverity.ERROR);
        assertTrue(errors.stream().anyMatch(
                h -> h.getDescription() != null && h.getDescription().contains("CannotResolveMe123")),
                "An unresolvable class inside <fx:context> must produce a 'Cannot resolve symbol' error "
                + "in Kotlin embedded FXML2. Errors: " + errors);
    }

    // -----------------------------------------------------------------------
    // Multi-dollar string interpolation ($$""") support
    // -----------------------------------------------------------------------

    /**
     * FXML2 markup placed in a Kotlin multi-dollar raw string ($$""") must be injected and
     * validated the same way as markup in a regular raw string (""").  An unresolvable tag
     * name must produce an error.
     */
    @Test
    void multiDollarStringMarkupInjectionReportsUnresolvableTag() {
        getFixture().configureByText("MultiDollarView.kt", """
                package test
                import org.jfxcore.markup.ComponentView
                import javafx.scene.layout.*
                @ComponentView($$""\"
                    <UnknownTag999/>
                ""\")
                open class MultiDollarView {
                    protected fun initializeComponent() {}
                    init { initializeComponent() }
                }
                """);

        var errors = getFixture().doHighlighting(HighlightSeverity.ERROR);
        assertTrue(errors.stream().anyMatch(
                h -> h.getDescription() != null && h.getDescription().contains("UnknownTag999")),
                "Markup in a $$\\\"\\\"\\\" string must be injected and produce errors for "
                + "unresolvable tags. Errors: " + errors);
    }

    /**
     * A class that exists in the same Kotlin package but is not explicitly imported (neither
     * via {@code <?import?>} in the FXML nor via a Kotlin {@code import} statement) must be
     * flagged as unresolved when used inside {@code <fx:context>}.  Same-package accessibility
     * in Kotlin does not substitute for an explicit FXML or host-file import.
     */
    @Test
    void samePackageClassWithoutImportInsideFxContextProducesError() {
        getFixture().addClass("""
                package test;
                public class FxContextSamePkg {}
                """);

        getFixture().configureByText("SamePkgFxContext.kt",
                "package test\n"
                + "import org.jfxcore.markup.ComponentView\n"
                + "import javafx.scene.layout.*\n"
                // No import for test.FxContextSamePkg — same-package class, no explicit import
                + "@ComponentView(\"\"\"\n"
                + "    <StackPane>\n"
                + "      <fx:context>\n"
                + "        <FxContextSamePkg/>\n"
                + "      </fx:context>\n"
                + "    </StackPane>\n"
                + "\"\"\")\n"
                + "open class SamePkgFxContext {\n"
                + "    protected fun initializeComponent() {}\n"
                + "    init { initializeComponent() }\n"
                + "}\n");

        var errors = getFixture().doHighlighting(HighlightSeverity.ERROR);
        assertTrue(errors.stream().anyMatch(
                h -> h.getDescription() != null && h.getDescription().contains("FxContextSamePkg")),
                "A same-package class without an explicit import must be flagged as unresolved "
                + "inside <fx:context>. Errors: " + errors);
    }

    /**
     * An unresolvable class inside {@code <fx:context>} in a Kotlin multi-dollar raw string
     * ($$""") must produce an error, mirroring the regular raw string (""") behavior.
     */
    @Test
    void multiDollarStringMarkupInjectionReportsUnresolvableFxContextClass() {
        getFixture().configureByText("MultiDollarFxContext.kt", """
                package test
                import org.jfxcore.markup.ComponentView
                import javafx.scene.layout.*
                @ComponentView($$""\"
                    <StackPane>
                      <fx:context>
                        <CannotResolveMe999/>
                      </fx:context>
                    </StackPane>
                ""\")
                open class MultiDollarFxContext {
                    protected fun initializeComponent() {}
                    init { initializeComponent() }
                }
                """);

        var errors = getFixture().doHighlighting(HighlightSeverity.ERROR);
        assertTrue(errors.stream().anyMatch(
                h -> h.getDescription() != null && h.getDescription().contains("CannotResolveMe999")),
                "An unresolvable class inside <fx:context> in a $$\\\"\\\"\\\" string must produce "
                + "a 'Cannot resolve symbol' error. Errors: " + errors);
    }

    // -----------------------------------------------------------------------
    // Wrapper root invisibility
    // -----------------------------------------------------------------------

    /**
     * The synthetic {@code <fxml2:embedded>} wrapper root must produce no spurious errors.
     */
    @Test
    void wrapperRootIsInvisibleToAnnotators() {
        getFixture().configureByText("EmbeddedView.kt",
                kotlinWithMarkup("EmbeddedView", "    <StackPane/>\n"));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // fx:subclass forbidden in embedded FXML2
    // -----------------------------------------------------------------------

    /**
     * {@code fx:subclass} is not allowed in embedded FXML2 from Kotlin.
     */
    @Test
    void fxClassForbiddenInKotlinEmbeddedMarkup() {
        getFixture().enableInspections(new Fxml2FxAttributeInspection());

        getFixture().configureByText("EmbeddedViewFxClass.kt",
                kotlinWithMarkup("EmbeddedViewFxClass", """
                        <StackPane fx:subclass="test.EmbeddedViewFxClass">
                        </StackPane>
                    """));

        var errors = getFixture().doHighlighting(HighlightSeverity.ERROR);
        boolean hasFxClassError = errors.stream().anyMatch(
                h -> h.getDescription() != null
                     && h.getDescription().toLowerCase().contains("fx:subclass")
                     && h.getDescription().toLowerCase().contains("embedded"));
        assertTrue(hasFxClassError,
                "Expected a specific error about fx:subclass being forbidden in Kotlin embedded FXML2. "
                + "Errors found: " + errors.stream()
                        .filter(h -> h.getDescription() != null)
                        .map(h -> "\"" + h.getDescription() + "\"")
                        .toList());
    }

    // -----------------------------------------------------------------------
    // initializeComponent inspection
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2InitializeComponentInspection} must warn when a {@code @ComponentView}-annotated
     * Kotlin class does NOT call {@code initializeComponent()}.
     */
    @Test
    void initializeComponentInspectionFiresWhenCallMissing() {
        getFixture().enableInspections(new Fxml2InitializeComponentInspection());

        getFixture().configureByText("BadView.kt", """
                package test
                import org.jfxcore.markup.ComponentView
                import javafx.scene.layout.*
                @ComponentView(\"""
                    <StackPane/>
                    \""")
                open class BadView {
                    protected fun initializeComponent() {}
                    // Intentionally missing init { initializeComponent() }
                }
                """);

        var warnings = getFixture().doHighlighting(HighlightSeverity.WARNING);
        boolean hasMissingInit = warnings.stream().anyMatch(
                h -> h.getDescription() != null
                     && h.getDescription().contains("initializeComponent"));
        assertTrue(hasMissingInit,
                "Inspection must warn about missing initializeComponent() call in Kotlin");
    }

    /**
     * {@link Fxml2InitializeComponentInspection} must NOT warn when the
     * {@code @ComponentView}-annotated Kotlin class does call {@code initializeComponent()}.
     */
    @Test
    void initializeComponentInspectionNoWarningWhenCallPresent() {
        getFixture().enableInspections(new Fxml2InitializeComponentInspection());

        getFixture().configureByText("GoodView.kt",
                kotlinWithMarkup("GoodView", "    <StackPane/>\n"));

        // kotlinWithMarkup() already includes init { initializeComponent() }
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Unused-import inspection suppressed
    // -----------------------------------------------------------------------

    /**
     * The "unused import" inspection must NOT fire for embedded FXML2 in Kotlin.
     */
    @Test
    void unusedImportInspectionSuppressed() {
        getFixture().enableInspections(new Fxml2UnusedImportsInspection());

        getFixture().configureByText("EmbeddedView.kt",
                kotlinWithMarkup("EmbeddedView", "    <StackPane/>\n"));

        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // getHostClass / code-behind detection
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2EmbeddedUtil#getHostClass} must return the Kotlin class (as a light class)
     * that carries the {@code @ComponentView} annotation.
     */
    @Test
    void getHostClassReturnsAnnotatedKotlinClass() {
        getFixture().configureByText("EmbeddedView.kt",
                kotlinWithMarkup("EmbeddedView3Kt", "    <StackPane/>\n"));

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = getInjectedXmlFile();
            assertNotNull(xmlFile);
            PsiClass hostClass = Fxml2EmbeddedUtil.getHostClass(xmlFile);
            assertNotNull(hostClass, "getHostClass must return the @ComponentView-annotated Kotlin class");
            assertEquals("test.EmbeddedView3Kt", hostClass.getQualifiedName(),
                    "getHostClass must return the class with FQN test.EmbeddedView3Kt");
        });
    }

    /**
     * {@link Fxml2InitializeComponentInspection#isCodeBehindClass} must return
     * {@code true} for a Kotlin class annotated with {@code @ComponentView}.
     */
    @Test
    void isCodeBehindClassReturnsTrueForMarkupAnnotatedKotlinClass() {
        getFixture().configureByText("EmbeddedView.kt",
                kotlinWithMarkup("EmbeddedView4Kt", "    <StackPane/>\n"));

        ReadAction.run(() -> {
            PsiClass cls = findMarkupClass();
            assertNotNull(cls);
            assertTrue(
                    Fxml2InitializeComponentInspection.isCodeBehindClass(
                            cls, getFixture().getProject()),
                    "@ComponentView-annotated Kotlin class must be recognized as a code-behind class");
        });
    }

    // -----------------------------------------------------------------------
    // Wrapper root predicate
    // -----------------------------------------------------------------------

    /**
     * The {@link Fxml2EmbeddedUtil#isWrapperRoot} predicate must return {@code true}
     * for the synthetic wrapper root and {@code false} for user markup tags.
     */
    @Test
    void wrapperRootPredicateWorks() {
        getFixture().configureByText("EmbeddedView.kt",
                kotlinWithMarkup("EmbeddedView", "    <StackPane><Button/></StackPane>\n"));

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = getInjectedXmlFile();
            assertNotNull(xmlFile);
            XmlTag root = xmlFile.getRootTag();
            assertNotNull(root);
            assertTrue(Fxml2EmbeddedUtil.isWrapperRoot(root),
                    "Root of Kotlin injected file must be the synthetic wrapper root");

            XmlTag userRoot = root.findFirstSubTag("StackPane");
            assertNotNull(userRoot, "StackPane must be a sub-tag of the wrapper root");
            assertFalse(Fxml2EmbeddedUtil.isWrapperRoot(userRoot),
                    "User markup tags must NOT be the wrapper root");
        });
    }

    // -----------------------------------------------------------------------
    // Binding path resolution
    // -----------------------------------------------------------------------

    /**
     * A binding path that references a field on the Kotlin code-behind class must
     * resolve without an error.
     */
    @Test
    void bindingPathResolvesToHostClassField() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.StringProperty;
                import javafx.beans.property.SimpleStringProperty;
                public class ViewModelKt5 {
                    public final StringProperty message = new SimpleStringProperty();
                }
                """);

        getFixture().configureByText("EmbeddedView5.kt", """
                package test
                import org.jfxcore.markup.ComponentView
                import javafx.scene.layout.*
                import javafx.scene.control.*
                @ComponentView(\"""
                    <StackPane>
                      <Label text="{fx:Observe vm.message}"/>
                    </StackPane>
                    \""")
                open class EmbeddedView5 {
                    val vm = ViewModelKt5()
                    protected fun initializeComponent() {}
                    init { initializeComponent() }
                }
                """);

        // No binding-path errors expected.
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A binding path that references an unknown field must produce an error.
     */
    @Test
    void unboundBindingPathProducesError() {
        getFixture().configureByText("EmbeddedView6.kt", """
                package test
                import org.jfxcore.markup.ComponentView
                import javafx.scene.layout.*
                import javafx.scene.control.*
                @ComponentView(\"""
                    <StackPane>
                      <Label text="{fx:Observe nonExistentField}"/>
                    </StackPane>
                    \""")
                open class EmbeddedView6 {
                    protected fun initializeComponent() {}
                    init { initializeComponent() }
                }
                """);

        var errors = getFixture().doHighlighting(HighlightSeverity.ERROR);
        boolean hasUnresolved = errors.stream().anyMatch(
                h -> h.getDescription() != null
                     && h.getDescription().contains("nonExistentField"));
        assertTrue(hasUnresolved,
                "Expected an unresolved-binding error for 'nonExistentField' in Kotlin embedded FXML2");
    }

    // -----------------------------------------------------------------------
    // fx:id navigation: field -> embedded fx:id
    // -----------------------------------------------------------------------

    /**
     * {@code ReferencesSearch} on a field that corresponds to an {@code fx:id} in Kotlin
     * embedded markup must find the attribute value in the injected XML file.
     */
    @Test
    void fxIdFieldNavigatesToEmbeddedFxIdInKotlin() {
        getFixture().addClass("""
                package test;
                import javafx.scene.control.Button;
                public abstract class KtEmbeddedViewBase extends javafx.scene.layout.StackPane {
                    public Button myKtBtn;
                    protected void initializeComponent() {}
                }
                """);

        getFixture().configureByText("KtEmbeddedView.kt", """
                package test
                import org.jfxcore.markup.ComponentView
                import javafx.scene.layout.*
                import javafx.scene.control.*
                @ComponentView(\"""
                    <StackPane>
                      <Button fx:id="myKtBtn"/>
                    </StackPane>
                    \""")
                class KtEmbeddedView : KtEmbeddedViewBase() {
                    init { initializeComponent() }
                }
                """);

        // Trigger injection
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass base = com.intellij.psi.JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.KtEmbeddedViewBase",
                            com.intellij.psi.search.GlobalSearchScope.projectScope(
                                    getFixture().getProject()));
            assertNotNull(base, "KtEmbeddedViewBase must be found");
            PsiField field = base.findFieldByName("myKtBtn", false);
            assertNotNull(field, "Field 'myKtBtn' must exist");

            var refs = ReferencesSearch.search(field,
                    com.intellij.psi.search.GlobalSearchScope.projectScope(
                            getFixture().getProject())).findAll();
            boolean foundInEmbedded = refs.stream().anyMatch(
                    ref -> ref.getElement() instanceof XmlAttributeValue attrVal
                           && attrVal.getContainingFile() instanceof XmlFile xmlFile
                           && Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile));
            assertTrue(foundInEmbedded,
                    "ReferencesSearch on 'myKtBtn' must find the fx:id in Kotlin embedded FXML2.\n"
                    + "References found: " + refs.stream()
                            .map(r -> r.getElement().getContainingFile().getName()
                                      + "@" + r.getElement().getClass().getSimpleName())
                            .toList());
        });
    }

    // -----------------------------------------------------------------------
    // Code completion in Kotlin embedded FXML2
    // -----------------------------------------------------------------------

    /**
     * Tag-name completion inside a Kotlin embedded FXML2 string must suggest class names
     * reachable through the Kotlin file's import declarations.
     */
    @Test
    void codeCompletionInKotlinEmbeddedFxml2() {
        getFixture().configureByText("ComplViewKt.kt", """
                package test
                import org.jfxcore.markup.ComponentView
                import javafx.scene.layout.*
                import javafx.scene.control.*
                @ComponentView(\"""
                    <StackPane>
                      <But<caret>/>
                    </StackPane>
                    \""")
                open class ComplViewKt {
                    protected fun initializeComponent() {}
                    init { initializeComponent() }
                }
                """);

        LookupElement[] completions = getFixture().completeBasic();
        assertNotNull(completions,
                "completeBasic() must return results inside Kotlin embedded FXML2 tag position");

        boolean hasButton = Arrays.stream(completions)
                .anyMatch(c -> "Button".equals(c.getLookupString()));
        assertTrue(hasButton,
                "Completion must suggest 'Button' from the import in Kotlin embedded FXML2. Got: "
                + Arrays.stream(completions)
                        .map(LookupElement::getLookupString)
                        .limit(30)
                        .toList());
    }

    // -----------------------------------------------------------------------
    // Find Usages on fx:id in Kotlin embedded FXML2
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2FxIdFindUsagesHandlerFactory} invoked on the {@code fx:id} attribute value
     * in Kotlin embedded FXML2 must return a handler whose primary elements include both the
     * {@link XmlAttributeValue} and the code-behind field.
     */
    @Test
    void fxIdFindUsagesInKotlinEmbeddedFxml2() {
        getFixture().addClass("""
                package test;
                import javafx.scene.control.Button;
                public abstract class KtFuBase extends javafx.scene.layout.StackPane {
                    public Button myKtFuBtn;
                    protected void initializeComponent() {}
                }
                """);

        getFixture().configureByText("KtFuView.kt", """
                package test
                import org.jfxcore.markup.ComponentView
                import javafx.scene.layout.*
                import javafx.scene.control.*
                @ComponentView(\"""
                    <StackPane>
                      <Button fx:id="myKtFuBtn"/>
                      <Button disable="${myKtFuBtn.disabled}"/>
                    </StackPane>
                    \""")
                class KtFuView : KtFuBase() {
                    init { initializeComponent() }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = getInjectedXmlFile();
            assertNotNull(xmlFile);

            XmlAttributeValue fxIdVal = null;
            for (var tag : PsiTreeUtil.findChildrenOfType(xmlFile, XmlTag.class)) {
                XmlAttribute idAttr = tag.getAttribute("fx:id");
                if (idAttr != null && "myKtFuBtn".equals(idAttr.getValue())) {
                    fxIdVal = idAttr.getValueElement();
                    break;
                }
            }
            assertNotNull(fxIdVal, "fx:id='myKtFuBtn' not found in Kotlin injected XML");

            var factory = new Fxml2FxIdFindUsagesHandlerFactory();
            assertTrue(factory.canFindUsages(fxIdVal),
                    "Factory must accept XmlAttributeValue from Kotlin embedded FXML2");

            var handler = factory.createFindUsagesHandler(fxIdVal, false);
            assertNotNull(handler, "Handler must be created for Kotlin embedded fx:id");

            List<PsiElement> primaries = Arrays.asList(handler.getPrimaryElements());
            assertTrue(primaries.contains(fxIdVal),
                    "Primaries must include the fx:id XmlAttributeValue from Kotlin embedded FXML2");
            assertTrue(primaries.stream()
                            .anyMatch(e -> e instanceof PsiField f && "myKtFuBtn".equals(f.getName())),
                    "Primaries must include the code-behind field 'myKtFuBtn'.\nPrimaries: "
                    + primaries.stream()
                            .map(p -> p.getClass().getSimpleName() + "(" + p.getText() + ")")
                            .toList());
        });
    }

    // -----------------------------------------------------------------------
    // Binding segment navigation in Kotlin embedded FXML2
    // -----------------------------------------------------------------------

    /**
     * A binding segment that references an {@code fx:id} name inside Kotlin embedded FXML2
     * must resolve to the code-behind field, and the field's navigation element must point
     * to the {@link XmlAttributeValue} of the {@code fx:id} in the injected XML file.
     */
    @Test
    void fxIdGotoDeclarationFromBindingInKotlin() {
        getFixture().addClass("""
                package test;
                import javafx.scene.control.Button;
                public abstract class KtBindNavBase extends javafx.scene.layout.StackPane {
                    public Button myKtNavBtn;
                    protected void initializeComponent() {}
                }
                """);

        getFixture().configureByText("KtBindNavView.kt", """
                package test
                import org.jfxcore.markup.ComponentView
                import javafx.scene.layout.*
                import javafx.scene.control.*
                @ComponentView(\"""
                    <StackPane>
                      <Button fx:id="myKtNavBtn"/>
                      <Button disable="${myKtNavBtn.disabled}"/>
                    </StackPane>
                    \""")
                class KtBindNavView : KtBindNavBase() {
                    init { initializeComponent() }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = getInjectedXmlFile();
            assertNotNull(xmlFile, "Injected XML must be present");

            // Find fx:id="myKtNavBtn" attribute value
            XmlAttributeValue fxIdVal = null;
            for (var tag : PsiTreeUtil.findChildrenOfType(xmlFile, XmlTag.class)) {
                XmlAttribute idAttr = tag.getAttribute("fx:id");
                if (idAttr != null && "myKtNavBtn".equals(idAttr.getValue())) {
                    fxIdVal = idAttr.getValueElement();
                    break;
                }
            }
            final XmlAttributeValue finalFxIdVal = fxIdVal;
            assertNotNull(finalFxIdVal, "fx:id='myKtNavBtn' not found in Kotlin injected XML");

            // Find the binding attribute value containing "myKtNavBtn"
            XmlAttributeValue bindingAttrVal = null;
            for (var attrVal : PsiTreeUtil.findChildrenOfType(xmlFile, XmlAttributeValue.class)) {
                if (attrVal.getText().contains("myKtNavBtn.disabled")) {
                    bindingAttrVal = attrVal;
                    break;
                }
            }
            assertNotNull(bindingAttrVal,
                    "Binding attribute containing 'myKtNavBtn.disabled' not found in Kotlin injected XML");

            // The first segment must have a Fxml2BindingSegmentReference
            Fxml2BindingSegmentReference segRef = Arrays.stream(bindingAttrVal.getReferences())
                    .filter(r -> r instanceof Fxml2BindingSegmentReference s
                            && "myKtNavBtn".equals(s.getCanonicalText()))
                    .map(r -> (Fxml2BindingSegmentReference) r)
                    .findFirst().orElse(null);
            assertNotNull(segRef,
                    "No Fxml2BindingSegmentReference for 'myKtNavBtn' in Kotlin embedded FXML2 binding");

            PsiElement resolved = segRef.resolve();
            assertNotNull(resolved,
                    "Binding segment 'myKtNavBtn' must resolve to a non-null element");
            assertInstanceOf(PsiField.class, resolved,
                    "Binding segment must resolve to a PsiField, got "
                    + resolved.getClass().getSimpleName());

            assertEquals(finalFxIdVal, resolved.getNavigationElement(),
                    "Binding segment 'myKtNavBtn' must navigate to the fx:id XmlAttributeValue "
                    + "in the Kotlin embedded FXML2 file");
        });
    }

    // -----------------------------------------------------------------------
    // Fxml2KotlinUnusedImportSuppressor
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2KotlinUnusedImportSuppressor#isSuppressedFor} must return {@code true}
     * for wildcard imports whose package covers class names used in the embedded FXML2 markup.
     */
    @Test
    void kotlinUnusedImportSuppressorSuppressesMarkupWildcardImports() {
        getFixture().configureByText("SupprView1.kt", """
                package test
                import org.jfxcore.markup.ComponentView
                import javafx.scene.layout.*
                import javafx.scene.control.*
                @ComponentView(\"""
                    <StackPane>
                      <Button text="hello"/>
                    </StackPane>
                    \""")
                open class SupprView1 {
                    protected fun initializeComponent() {}
                    init { initializeComponent() }
                }
                """);

        // Trigger injection so the injected XML file is available.
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            KtFile ktFile = (KtFile) getFixture().getFile();
            List<KtImportDirective> imports = ktFile.getImportDirectives();

            KtImportDirective layoutImport = imports.stream()
                    .filter(imp -> {
                        var fqName = imp.getImportedFqName();
                        return fqName != null && fqName.asString().startsWith("javafx.scene.layout");
                    }).findFirst().orElse(null);
            assertNotNull(layoutImport, "javafx.scene.layout.* import must exist");

            KtImportDirective controlImport = imports.stream()
                    .filter(imp -> {
                        var fqName = imp.getImportedFqName();
                        return fqName != null && fqName.asString().startsWith("javafx.scene.control");
                    }).findFirst().orElse(null);
            assertNotNull(controlImport, "javafx.scene.control.* import must exist");

            var suppressor = new Fxml2KotlinUnusedImportSuppressor();
            assertTrue(suppressor.isSuppressedFor(layoutImport, "UnusedImport"),
                    "javafx.scene.layout.* must be suppressed (StackPane is used in embedded FXML2)");
            assertTrue(suppressor.isSuppressedFor(controlImport, "UnusedImport"),
                    "javafx.scene.control.* must be suppressed (Button is used in embedded FXML2)");
        });
    }

    /**
     * {@link Fxml2KotlinUnusedImportSuppressor#isSuppressedFor} must return {@code false}
     * for imports whose classes are NOT referenced in the embedded FXML2 markup.
     */
    @Test
    void kotlinUnusedImportSuppressorDoesNotSuppressUnrelatedImports() {
        getFixture().configureByText("SupprView2.kt", """
                package test
                import org.jfxcore.markup.ComponentView
                import javafx.scene.layout.*
                import javafx.scene.control.*
                import javafx.beans.property.*
                @ComponentView(\"""
                    <StackPane/>
                    \""")
                open class SupprView2 {
                    protected fun initializeComponent() {}
                    init { initializeComponent() }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            KtFile ktFile = (KtFile) getFixture().getFile();
            List<KtImportDirective> imports = ktFile.getImportDirectives();

            // javafx.beans.property.* is NOT used in the embedded FXML2 (no class from that package).
            KtImportDirective beansImport = imports.stream()
                    .filter(imp -> {
                        var fqName = imp.getImportedFqName();
                        return fqName != null && fqName.asString().startsWith("javafx.beans.property");
                    }).findFirst().orElse(null);
            assertNotNull(beansImport, "javafx.beans.property.* import must exist");

            var suppressor = new Fxml2KotlinUnusedImportSuppressor();
            assertFalse(suppressor.isSuppressedFor(beansImport, "UnusedImport"),
                    "javafx.beans.property.* must NOT be suppressed (not used in embedded FXML2)");
        });
    }

    /**
     * {@link Fxml2KotlinUnusedImportSuppressor#isSuppressedFor} must return {@code true}
     * for a specific (non-wildcard) import when that class is directly referenced in the
     * embedded FXML2 markup.
     */
    @Test
    void kotlinUnusedImportSuppressorSuppressesMarkupSpecificImports() {
        getFixture().configureByText("SupprView3.kt", """
                package test
                import org.jfxcore.markup.ComponentView
                import javafx.scene.layout.StackPane
                import javafx.scene.control.Button
                @ComponentView(\"""
                    <StackPane>
                      <Button text="hello"/>
                    </StackPane>
                    \""")
                open class SupprView3 {
                    protected fun initializeComponent() {}
                    init { initializeComponent() }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            KtFile ktFile = (KtFile) getFixture().getFile();
            List<KtImportDirective> imports = ktFile.getImportDirectives();

            KtImportDirective stackPaneImport = imports.stream()
                    .filter(imp -> {
                        var fqName = imp.getImportedFqName();
                        return fqName != null && "javafx.scene.layout.StackPane".equals(fqName.asString());
                    }).findFirst().orElse(null);
            assertNotNull(stackPaneImport, "javafx.scene.layout.StackPane import must exist");

            KtImportDirective buttonImport = imports.stream()
                    .filter(imp -> {
                        var fqName = imp.getImportedFqName();
                        return fqName != null && "javafx.scene.control.Button".equals(fqName.asString());
                    }).findFirst().orElse(null);
            assertNotNull(buttonImport, "javafx.scene.control.Button import must exist");

            var suppressor = new Fxml2KotlinUnusedImportSuppressor();
            assertTrue(suppressor.isSuppressedFor(stackPaneImport, "UnusedImport"),
                    "import javafx.scene.layout.StackPane must be suppressed (StackPane used in markup)");
            assertTrue(suppressor.isSuppressedFor(buttonImport, "UnusedImport"),
                    "import javafx.scene.control.Button must be suppressed (Button used in markup)");
        });
    }

    /**
     * An aliased Kotlin import ({@code import foo.Bar as Baz}) must NOT be
     * suppressed by {@link Fxml2KotlinUnusedImportSuppressor}.
     *
     * <p>The compiler ignores aliased imports when collecting FXML2 imports because it
     * cannot forward an alias to the Java annotation processor.  The plugin must match
     * this behavior: if the import is aliased it is never "needed" by the FXML2 markup.
     */
    @Test
    void kotlinUnusedImportSuppressorDoesNotSuppressAliasedImport() {
        getFixture().configureByText("SupprAlias.kt", """
                package test
                import org.jfxcore.markup.ComponentView
                import javafx.scene.layout.StackPane as SP
                import javafx.scene.control.*
                @ComponentView(\"""
                    <StackPane/>
                    \""")
                open class SupprAlias {
                    protected fun initializeComponent() {}
                    init { initializeComponent() }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            KtFile ktFile = (KtFile) getFixture().getFile();
            List<KtImportDirective> imports = ktFile.getImportDirectives();

            // "import javafx.scene.layout.StackPane as SP": aliased import
            KtImportDirective aliasedImport = imports.stream()
                    .filter(imp -> {
                        var fqName = imp.getImportedFqName();
                        return fqName != null
                                && "javafx.scene.layout.StackPane".equals(fqName.asString())
                                && imp.getAliasName() != null;
                    }).findFirst().orElse(null);
            assertNotNull(aliasedImport,
                    "Aliased import 'import javafx.scene.layout.StackPane as SP' must exist");

            var suppressor = new Fxml2KotlinUnusedImportSuppressor();
            assertFalse(suppressor.isSuppressedFor(aliasedImport, "UnusedImport"),
                    "Aliased import must NOT be suppressed - the compiler ignores aliased imports");
        });
    }

    /**
     * A non-class Kotlin import (top-level function) must NOT be suppressed by
     * {@link Fxml2KotlinUnusedImportSuppressor}.
     *
     * <p>FXML2 markup can only reference classes; imports of top-level functions or
     * properties are irrelevant to the markup and must not be protected from the
     * {@code UnusedImport} inspection.
     */
    @Test
    void kotlinUnusedImportSuppressorDoesNotSuppressNonClassImport() {
        // javafx.collections.FXCollections.observableArrayList is a static method, not a class.
        getFixture().configureByText("SupprNonClass.kt", """
                package test
                import org.jfxcore.markup.ComponentView
                import javafx.scene.layout.*
                import javafx.collections.FXCollections
                @ComponentView(\"""
                    <StackPane/>
                    \""")
                open class SupprNonClass {
                    protected fun initializeComponent() {}
                    init { initializeComponent() }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            KtFile ktFile = (KtFile) getFixture().getFile();
            List<KtImportDirective> imports = ktFile.getImportDirectives();

            // FXCollections is NOT a tag referenced in the embedded markup -> must not be suppressed.
            KtImportDirective fxCollImport = imports.stream()
                    .filter(imp -> {
                        var fqName = imp.getImportedFqName();
                        return fqName != null
                                && "javafx.collections.FXCollections".equals(fqName.asString());
                    }).findFirst().orElse(null);
            assertNotNull(fxCollImport, "import javafx.collections.FXCollections must exist");

            var suppressor = new Fxml2KotlinUnusedImportSuppressor();
            assertFalse(suppressor.isSuppressedFor(fxCollImport, "UnusedImport"),
                    "Non-class import must NOT be suppressed (FXCollections is not used as an FXML2 tag)");
        });
    }

    // -----------------------------------------------------------------------
    // Fxml2EmbeddedKotlinImportOptimizer
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2EmbeddedKotlinImportOptimizer#supports} must return {@code true} for a
     * Kotlin file that contains a class annotated with {@code @ComponentView}.
     */
    @Test
    void embeddedKotlinImportOptimizerSupports() {
        getFixture().configureByText("KtOptiView.kt",
                kotlinWithMarkup("KtOptiView", "    <StackPane/>\n"));

        ReadAction.run(() -> {
            var optimizer = new Fxml2EmbeddedKotlinImportOptimizer();
            assertTrue(optimizer.supports(getFixture().getFile()),
                    "Fxml2EmbeddedKotlinImportOptimizer must support Kotlin files with @ComponentView");
        });
    }

    /**
     * {@link Fxml2EmbeddedKotlinImportOptimizer} must add back Kotlin imports that are
     * needed by the embedded FXML2 markup but were removed (e.g. by the built-in Kotlin
     * import optimizer, which does not understand that class names inside string literals are
     * "used").
     *
     * <p>The test simulates this by:
     * <ol>
     *   <li>Setting up a Kotlin file whose imports cover the embedded markup's class tags.</li>
     *   <li>Calling {@link Fxml2EmbeddedKotlinImportOptimizer#processFile} (read phase: captures
     *       which imports are needed).</li>
     *   <li>Manually removing one of those imports inside a write action (simulating what the
     *       built-in Kotlin optimizer would do).</li>
     *   <li>Running the optimizer's {@link Runnable} (write phase: should add it back).</li>
     *   <li>Verifying the import is present again.</li>
     * </ol>
     */
    @Test
    void embeddedKotlinImportOptimizerAddsBackRemovedImport() {
        getFixture().configureByText("KtOptiView2.kt", """
                package test
                import org.jfxcore.markup.ComponentView
                import javafx.scene.layout.*
                import javafx.scene.control.*
                @ComponentView(\"""
                    <StackPane>
                      <Button text="hello"/>
                    </StackPane>
                    \""")
                open class KtOptiView2 {
                    protected fun initializeComponent() {}
                    init { initializeComponent() }
                }
                """);

        // Trigger injection so the injected XML file is available.
        getFixture().doHighlighting();

        var optimizer = new Fxml2EmbeddedKotlinImportOptimizer();
        KtFile ktFile = (KtFile) getFixture().getFile();

        // Read phase: capture which imports the embedded markup needs.
        Runnable task = ReadAction.compute(() -> optimizer.processFile(ktFile));

        // Simulate Kotlin optimizer removing the javafx.scene.control.* import.
        WriteCommandAction.runWriteCommandAction(getFixture().getProject(), () -> {
            var importList = ktFile.getImportList();
            if (importList != null) {
                for (KtImportDirective imp : importList.getImports()) {
                    var fqName = imp.getImportedFqName();
                    if (fqName != null && fqName.asString().startsWith("javafx.scene.control")) {
                        imp.delete();
                        break;
                    }
                }
            }
        });

        // Verify the import was actually removed.
        assertFalse(ReadAction.compute(() -> getFixture().getFile().getText())
                        .contains("javafx.scene.control"),
                "Setup: import must be removed before running the optimizer");

        // Write phase: the optimizer's Runnable should add the import back.
        WriteCommandAction.runWriteCommandAction(getFixture().getProject(), task);

        // Verify the import is present again.
        String text = ReadAction.compute(() -> getFixture().getFile().getText());
        assertTrue(text.contains("javafx.scene.control"),
                "Optimizer must add back the javafx.scene.control import that is used in "
                + "embedded Kotlin FXML2.\nActual source:\n" + text);
    }

    /**
     * {@link Fxml2EmbeddedKotlinImportOptimizer} must NOT add imports that are NOT used
     * in the embedded FXML2 markup. If an import was removed by the Kotlin optimizer and
     * the embedded markup doesn't need it, the optimizer must leave it removed.
     */
    @Test
    void embeddedKotlinImportOptimizerDoesNotAddUnneededImport() {
        getFixture().configureByText("KtOptiView3.kt", """
                package test
                import org.jfxcore.markup.ComponentView
                import javafx.scene.layout.*
                import javafx.scene.control.*
                import javafx.beans.property.*
                @ComponentView(\"""
                    <StackPane/>
                    \""")
                open class KtOptiView3 {
                    protected fun initializeComponent() {}
                    init { initializeComponent() }
                }
                """);

        getFixture().doHighlighting();

        var optimizer = new Fxml2EmbeddedKotlinImportOptimizer();
        KtFile ktFile = (KtFile) getFixture().getFile();

        // Read phase.
        Runnable task = ReadAction.compute(() -> optimizer.processFile(ktFile));

        // Simulate Kotlin optimizer removing all three imports.
        WriteCommandAction.runWriteCommandAction(getFixture().getProject(), () -> {
            var importList = ktFile.getImportList();
            if (importList != null) {
                for (KtImportDirective imp : List.copyOf(importList.getImports())) {
                    var fqName = imp.getImportedFqName();
                    if (fqName != null) {
                        String fqStr = fqName.asString();
                        if (fqStr.startsWith("javafx.scene.control")
                                || fqStr.startsWith("javafx.beans.property")
                                || fqStr.startsWith("javafx.scene.layout")) {
                            imp.delete();
                        }
                    }
                }
            }
        });

        // Write phase.
        WriteCommandAction.runWriteCommandAction(getFixture().getProject(), task);

        String text = ReadAction.compute(() -> getFixture().getFile().getText());

        // layout.* must be restored (StackPane is used).
        assertTrue(text.contains("javafx.scene.layout"),
                "Optimizer must restore layout.* (used in embedded FXML2).\nActual:\n" + text);

        // control.* must NOT be restored (not used in embedded markup).
        assertFalse(text.contains("javafx.scene.control"),
                "Optimizer must NOT restore control.* (unused in embedded FXML2).\nActual:\n" + text);

        // beans.* must NOT be restored (not used in embedded markup).
        assertFalse(text.contains("javafx.beans.property"),
                "Optimizer must NOT restore beans.* (unused in embedded FXML2).\nActual:\n" + text);
    }

    /**
     * {@link LanguageImportStatements#forFile} must include
     * {@link Fxml2EmbeddedKotlinImportOptimizer} in its result set for a Kotlin file that
     * contains a class annotated with {@code @ComponentView}, and must NOT include the
     * built-in Kotlin optimizer (since our optimizer is registered with {@code order="first"}
     * and {@code forFile()} stops at the first supporting optimizer per language).
     *
     * <p>This verifies the {@code order="first"} registration in {@code fxml2-with-kotlin.xml}:
     * {@code forFile()} picks the <em>first</em> supporting optimizer and stops (see the
     * {@code break} inside {@link LanguageImportStatements#forFile}), so if our optimizer is
     * not {@code order="first"} the built-in optimizer is selected instead and FXML-needed
     * imports are incorrectly removed by "Optimize Imports".
     */
    @Test
    void languageImportStatementsForFileReturnsKotlinOptimizer() {
        getFixture().configureByText("KtOptiForFile.kt",
                kotlinWithMarkup("KtOptiForFile", "    <StackPane/>\n"));

        ReadAction.run(() -> {
            KtFile ktFile = (KtFile) getFixture().getFile();
            Set<ImportOptimizer> optimizers = LanguageImportStatements.INSTANCE.forFile(ktFile);
            assertFalse(optimizers.isEmpty(),
                    "LanguageImportStatements.forFile() must return at least one optimizer "
                    + "for a @ComponentView Kotlin file");
            boolean hasOurs = optimizers.stream()
                    .anyMatch(o -> o instanceof Fxml2EmbeddedKotlinImportOptimizer);
            assertTrue(hasOurs,
                    "LanguageImportStatements.forFile() must include Fxml2EmbeddedKotlinImportOptimizer "
                    + "(order=\"first\") for @ComponentView Kotlin files. "
                    + "Actual optimizers: " + optimizers.stream()
                            .map(o -> o.getClass().getSimpleName()).toList());
        });
    }

    /**
     * End-to-end test: the full "Optimize Imports" pipeline (exactly as IntelliJ runs it -
     * {@link LanguageImportStatements#forFile} -> {@code processFile()} -> {@code run()}) must
     * <em>not</em> remove Kotlin imports that are needed by the embedded FXML2 markup.
     *
     * <p>The full "Optimize Imports" pipeline must not remove Kotlin imports that are
     * needed by embedded FXML2 markup.  The plugin's Kotlin import optimizer must be
     * selected instead of the built-in one; if the built-in optimizer runs, it removes
     * all markup-referenced imports because it is unaware of the embedded XML content.
     */
    @Test
    void optimizeImportsPipelinePreservesFxmlNeededKotlinImports() {
        getFixture().configureByText("KtOptiE2E.kt", """
                package test
                import org.jfxcore.markup.ComponentView
                import javafx.scene.layout.*
                import javafx.scene.control.*
                @ComponentView(\"""
                    <StackPane>
                      <Button text="hello"/>
                    </StackPane>
                    \""")
                open class KtOptiE2E {
                    protected fun initializeComponent() {}
                    init { initializeComponent() }
                }
                """);

        // Trigger injection so the injected XML file is available.
        getFixture().doHighlighting();

        KtFile ktFile = (KtFile) getFixture().getFile();

        // Simulate exactly what IntelliJ does during "Optimize Imports":
        // LanguageImportStatements.forFile() picks (per language) the first supporting
        // optimizer, then calls processFile() to get the write runnable, then runs it.
        List<Runnable> tasks = ReadAction.compute(() -> {
            Set<ImportOptimizer> optimizers = LanguageImportStatements.INSTANCE.forFile(ktFile);
            return optimizers.stream()
                    .map(opt -> opt.processFile(ktFile))
                    .collect(java.util.stream.Collectors.toList());
        });
        WriteCommandAction.runWriteCommandAction(getFixture().getProject(),
                () -> tasks.forEach(Runnable::run));

        String text = ReadAction.compute(() -> getFixture().getFile().getText());

        // Both wildcard imports must still be present: StackPane and Button are both used.
        assertTrue(text.contains("javafx.scene.layout"),
                "Optimize-imports pipeline must preserve layout.* (StackPane is used in "
                + "embedded FXML2 markup).\nActual source:\n" + text);
        assertTrue(text.contains("javafx.scene.control"),
                "Optimize-imports pipeline must preserve control.* (Button is used in "
                + "embedded FXML2 markup).\nActual source:\n" + text);
    }

    // -----------------------------------------------------------------------
    // imports preserved after class rename (Kotlin)
    // -----------------------------------------------------------------------

    /**
     * Renaming a {@code @ComponentView}-annotated Kotlin class (Shift+F6) must NOT remove
     * imports that are needed by the embedded FXML2 markup.
     *
     * <p>Kotlin's {@code KotlinOptimizeImportsRefactoringHelper} runs after every refactoring
     * and removes imports that its {@code analyzeImports()} considers unused.  Since that
     * analysis does not look inside {@code @ComponentView} string literals, it falsely
     * classifies FXML-needed Kotlin imports as redundant and deletes them.
     *
     * <p>The fix is {@link org.jfxcore.fxml.codeinsight.Fxml2EmbeddedKotlinRefactoringHelper},
     * a {@code RefactoringHelper} registered with {@code order="last"} that captures
     * FXML-needed imports in its read phase and restores them after all other helpers.
     */
    @Test
    void importsPreservedAfterKotlinClassRename() {
        getFixture().configureByText("KtRenImportsView.kt", """
                package test
                import org.jfxcore.markup.ComponentView
                import javafx.scene.layout.*
                import javafx.scene.control.*
                @ComponentView(\"""
                    <StackPane>
                      <Button text="hello"/>
                    </StackPane>
                    \""")
                open class KtRenImportsView {
                    protected fun initializeComponent() {}
                    init { initializeComponent() }
                }
                """);

        // Trigger injection so import resolution is available.
        getFixture().doHighlighting();

        // Find the Kotlin class.
        org.jetbrains.kotlin.psi.KtClassOrObject ktClass = ReadAction.compute(() -> {
            for (var c : com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                    getFixture().getFile(),
                    org.jetbrains.kotlin.psi.KtClassOrObject.class)) {
                for (var annotation : c.getAnnotationEntries()) {
                    var shortName = annotation.getShortName();
                    if (shortName != null && "ComponentView".equals(shortName.getIdentifier())) {
                        return c;
                    }
                }
            }
            return null;
        });
        assertNotNull(ktClass, "Must find @ComponentView Kotlin class");

        // Rename the Kotlin class.  KotlinOptimizeImportsRefactoringHelper would strip
        // FXML-needed imports without Fxml2EmbeddedKotlinRefactoringHelper protecting them.
        com.intellij.openapi.application.ApplicationManager.getApplication()
                .invokeAndWait(() -> getFixture().renameElement(ktClass, "KtRenImportsView2"));

        // Read the source of the renamed file.
        String text = ReadAction.compute(() -> {
            // After Kotlin class rename the file is also renamed; the VirtualFile follows.
            // Use PsiManager to locate the renamed file via the project scope.
            PsiClass renamed = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.KtRenImportsView2",
                            GlobalSearchScope.projectScope(getFixture().getProject()));
            if (renamed == null) {
                // Fallback: read from the current editor file (works when file not renamed)
                return getFixture().getFile().getText();
            }
            com.intellij.psi.PsiFile f = renamed.getContainingFile();
            return f != null ? f.getText() : getFixture().getFile().getText();
        });

        assertTrue(text.contains("javafx.scene.layout"),
                "import javafx.scene.layout.* must survive Kotlin class rename "
                + "(StackPane is used in embedded FXML2).\nActual source:\n" + text);
        assertTrue(text.contains("javafx.scene.control"),
                "import javafx.scene.control.* must survive Kotlin class rename "
                + "(Button is used in embedded FXML2).\nActual source:\n" + text);
    }

    // -----------------------------------------------------------------------
    // ImplicitUsageProvider: view-model members referenced via
    //                  multi-segment binding path must not be reported as unused
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2EmbeddedImplicitUsageProvider} must recognize a field in a
     * non-{@code @ComponentView} Java class as implicitly used when it is referenced
     * from a <em>Kotlin</em> {@code @ComponentView} class's embedded markup via a
     * multi-segment binding path (e.g. {@code {fx:Observe vm.labelText}}).
     */
    @Test
    void implicitUsageProviderSuppressesUnusedWarningForViewModelFieldFromKotlinView() {
        // The view-model: a plain Java POJO, no @ComponentView.
        getFixture().addClass("""
                package test;
                public class KtLabelViewModel {
                    public String labelText = "initial";
                }
                """);

        // The Kotlin view: carries @ComponentView and references vm.labelText.
        getFixture().configureByText("KtLabelView.kt", """
                package test
                import org.jfxcore.markup.ComponentView
                import javafx.scene.layout.*
                import javafx.scene.control.*
                @ComponentView(\"""
                    <StackPane>
                      <Label text="{fx:Observe vm.labelText}"/>
                    </StackPane>
                    \""")
                open class KtLabelView {
                    @JvmField
                    var vm: KtLabelViewModel = KtLabelViewModel()
                    protected fun initializeComponent() {}
                    init { initializeComponent() }
                }
                """);

        // Trigger injection so the embedded XML is available to the provider.
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            var vmCls = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.KtLabelViewModel",
                            GlobalSearchScope.projectScope(getFixture().getProject()));
            assertNotNull(vmCls, "Must find KtLabelViewModel class");

            PsiField labelTextField = vmCls.findFieldByName("labelText", false);
            assertNotNull(labelTextField, "Must find field 'labelText' on KtLabelViewModel");

            var provider = new Fxml2EmbeddedImplicitUsageProvider();

            assertTrue(provider.isImplicitUsage(labelTextField),
                    "isImplicitUsage must return true for a view-model field bound via "
                    + "vm.labelText in a Kotlin @ComponentView class's embedded FXML2 markup");
            assertTrue(provider.isImplicitRead(labelTextField),
                    "isImplicitRead must return true for a view-model field bound via "
                    + "vm.labelText in a Kotlin @ComponentView class's embedded FXML2 markup");
        });
    }

    // -----------------------------------------------------------------------
    // Fxml2KotlinUnusedSymbolSuppressor: context class property used in embedded FXML2
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2KotlinUnusedSymbolSuppressor#isSuppressedFor} must return {@code true}
     * for a Kotlin property that is only referenced via a binding path originating from
     * an {@code <fx:context>} element in another class's embedded FXML2 markup.
     *
     * <p>In practice: a context class such as {@code MyContext} has a {@code val vm}
     * property that is never directly referenced in Java/Kotlin code, but is bound in
     * the view's markup as {@code ${vm}}.  K2's unused-symbol analysis reports it as
     * "Property 'vm' is never used" because {@link
     * com.intellij.codeInsight.daemon.ImplicitUsageProvider} is never consulted for
     * {@code KtProperty} nodes.  The suppressor must close this gap by checking
     * embedded FXML2 markup for a binding-segment reference that resolves (via
     * {@code getNavigationElement()}) to the Kotlin property.
     */
    @Test
    void kotlinUnusedSymbolSuppressorSuppressesContextClassPropertyUsedInEmbeddedFxml() {
        // Context class: holds the vm property referenced in the view markup.
        // It is NOT annotated with @ComponentView.
        var ctxFile = getFixture().addFileToProject("test/CtxClassKt.kt", """
                package test
                import javafx.beans.property.ObjectProperty
                import javafx.beans.property.SimpleObjectProperty
                class CtxClassKt {
                    val vm: ObjectProperty<String> = SimpleObjectProperty("initial")
                }
                """);

        // View class: uses CtxClassKt via fx:context and references vm in a binding.
        // The annotation uses the multi-dollar raw string ($$""") so that the FXML2
        // expression ${vm} is preserved verbatim and is not parsed as a Kotlin string
        // template.
        getFixture().configureByText("CtxViewKt.kt", """
                package test
                import org.jfxcore.markup.ComponentView
                import javafx.scene.layout.*
                import javafx.scene.control.*
                import test.CtxClassKt
                @ComponentView($$""\"
                    <StackPane>
                      <fx:context>
                        <CtxClassKt/>
                      </fx:context>
                      <Label text="${vm}"/>
                    </StackPane>
                    ""\")
                open class CtxViewKt {
                    protected fun initializeComponent() {}
                    init { initializeComponent() }
                }
                """);

        // Trigger injection so the embedded XML and its references are available.
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            // Locate the KtProperty vm in CtxClassKt.
            org.jetbrains.kotlin.psi.KtProperty vmProp = null;
            for (var ktClass : PsiTreeUtil.findChildrenOfType(
                    ctxFile, org.jetbrains.kotlin.psi.KtClassOrObject.class)) {
                for (var decl : ktClass.getDeclarations()) {
                    if (decl instanceof org.jetbrains.kotlin.psi.KtProperty p
                            && "vm".equals(p.getName())) {
                        vmProp = p;
                        break;
                    }
                }
            }
            assertNotNull(vmProp, "KtProperty 'vm' must be found in CtxClassKt");

            var suppressor = new Fxml2KotlinUnusedSymbolSuppressor();
            assertTrue(suppressor.isSuppressedFor(vmProp, "unused"),
                    "isSuppressedFor must return true for KtProperty 'vm' in a context class "
                    + "that is only referenced via an embedded FXML2 binding "
                    + "(fx:context scenario). K2 reports it as unused because ImplicitUsageProvider "
                    + "is not consulted for KtProperty nodes.");
        });
    }

    /**
     * {@link ReferencesSearch} (global scope) on a field in a non-{@code @ComponentView}
     * class must find the binding-path segment in a <em>Kotlin</em> {@code @ComponentView}
     * class's embedded FXML2 markup.
     */
    @Test
    void findUsagesOnViewModelFieldFindsKotlinEmbeddedMarkupBinding() {
        getFixture().addClass("""
                package test;
                public class KtFindVm {
                    public String vmLabel = "hello";
                }
                """);

        getFixture().configureByText("KtFindVmView.kt", """
                package test
                import org.jfxcore.markup.ComponentView
                import javafx.scene.layout.*
                import javafx.scene.control.*
                @ComponentView(\"""
                    <StackPane>
                      <Label text="{fx:Observe vm.vmLabel}"/>
                    </StackPane>
                    \""")
                open class KtFindVmView {
                    @JvmField
                    var vm: KtFindVm = KtFindVm()
                    protected fun initializeComponent() {}
                    init { initializeComponent() }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            var vmCls = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.KtFindVm",
                            GlobalSearchScope.projectScope(getFixture().getProject()));
            assertNotNull(vmCls, "Must find KtFindVm class");

            PsiField vmLabelField = vmCls.findFieldByName("vmLabel", false);
            assertNotNull(vmLabelField, "Must find field 'vmLabel' on KtFindVm");

            var refs = ReferencesSearch.search(
                            vmLabelField,
                            GlobalSearchScope.projectScope(getFixture().getProject()))
                    .findAll();

            boolean foundInEmbedded = refs.stream().anyMatch(
                    ref -> ref instanceof Fxml2BindingSegmentReference
                           && ref.getElement().getContainingFile() instanceof XmlFile xmlFile
                           && Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile));
            assertTrue(foundInEmbedded,
                    "ReferencesSearch on KtFindVm.vmLabel must find the binding segment "
                    + "'vmLabel' in the Kotlin @ComponentView class's embedded FXML2 markup.\n"
                    + "Found refs: " + refs.stream()
                            .map(r -> r.getClass().getSimpleName()
                                      + " in " + r.getElement().getContainingFile().getName())
                            .toList());
        });
    }
}
