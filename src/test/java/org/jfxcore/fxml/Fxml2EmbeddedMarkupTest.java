package org.jfxcore.fxml;

import com.intellij.analysis.AnalysisScope;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl;
import com.intellij.codeInspection.ex.InspectionManagerEx;
import com.intellij.codeInspection.ex.InspectionProfileImpl;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.codeInspection.ex.Tools;
import com.intellij.codeInspection.unusedImport.UnusedImportInspection;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtilBase;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.InspectionsKt;
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl;
import com.intellij.util.ui.UIUtil;
import java.util.Collection;
import java.util.Objects;
import org.jfxcore.fxml.annotator.Fxml2FxAttributeInspection;
import org.jfxcore.fxml.annotator.Fxml2InitializeComponentInspection;
import org.jfxcore.fxml.annotator.Fxml2UnusedImportsInspection;
import org.jfxcore.fxml.lang.Fxml2ImportUtil;
import org.jfxcore.fxml.codeinsight.Fxml2EmbeddedJavaImportOptimizer;
import org.jfxcore.fxml.lang.Fxml2BindingSegmentReference;
import org.jfxcore.fxml.lang.Fxml2EmbeddedImplicitUsageProvider;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.lang.Fxml2FxIdFindUsagesHandlerFactory;
import com.intellij.psi.PsiMethod;
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
 * Tests for embedded FXML support via the {@code @ComponentView} annotation.
 *
 * <p>Each test configures a Java file with a class annotated with
 * {@code @org.jfxcore.markup.ComponentView}. The language injector
 * ({@link org.jfxcore.fxml.lang.Fxml2MarkupAnnotationInjector}) injects
 * XML into the annotation's string value, and the plugin's full feature set
 * (diagnostics, navigation, code completion, find usages, rename) should
 * work on the injected fragment.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class Fxml2EmbeddedMarkupTest extends Fxml2TestBase {

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
     * Builds a minimal Java file content with {@code @ComponentView} applied to a class in
     * package {@code test}, with the standard {@code javafx.scene.layout.*} and
     * {@code javafx.scene.control.*} imports.
     *
     * @param cls        simple class name, e.g. {@code "EmbeddedView"}
     * @param markupBody the XML to embed (placed verbatim in the text block)
     */
    private static String javaWithMarkup(String cls, String markupBody) {
        return "package test;\n"
                + "import org.jfxcore.markup.ComponentView;\n"
                + "import javafx.scene.layout.*;\n"
                + "import javafx.scene.control.*;\n"
                + "@ComponentView(\"\"\"\n"
                + markupBody
                + "\"\"\")\n"
                + "public class " + cls + " {\n"
                + "    protected void initializeComponent() {}\n"
                + "    public " + cls + "() { initializeComponent(); }\n"
                + "}\n";
    }


    /**
     * Finds the first {@link PsiClass} in the currently configured file that carries
     * the {@code @ComponentView} annotation.
     */
    private PsiClass findMarkupClass() {
        for (PsiClass c : PsiTreeUtil.findChildrenOfType(
                getFixture().getFile(), PsiClass.class)) {
            if (c.hasAnnotation(Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN)) return c;
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

    /**
     * Finds the single available registered intention matching {@code hint} at the caret,
     * gathering intentions directly on the injected fragment.
     *
     * <p>Use this for registered {@code IntentionAction}s (whose availability is recomputed on
     * the injected file). Annotator-attached quick fixes are cached on the host daemon's
     * highlight infos and are found via {@code findSingleIntention}.
     *
     * @return the matching intention, or {@code null} if not exactly one starts with the hint
     */
    private IntentionAction findInjectedAddImportIntention() {
        String hint = "Add import for class reference";
        getFixture().doHighlighting();
        Project project = getFixture().getProject();
        Editor hostEditor = InjectedLanguageEditorUtil.getTopLevelEditor(getFixture().getEditor());

        Editor injectedEditor = ReadAction.compute(() -> {
            PsiFile hostFile = PsiUtilBase.getPsiFileInEditor(hostEditor, project);
            return hostFile == null ? null
                    : InjectedLanguageEditorUtil.getEditorForInjectedLanguageNoCommit(hostEditor, hostFile);
        });
        if (injectedEditor == null) return null;
        PsiFile injectedFile = ReadAction.compute(() -> PsiUtilBase.getPsiFileInEditor(injectedEditor, project));
        if (injectedFile == null) return null;

        List<IntentionAction> matches =
                CodeInsightTestFixtureImpl.getAvailableIntentions(injectedEditor, injectedFile).stream()
                        .filter(a -> a.getText().startsWith(hint))
                        .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    // -----------------------------------------------------------------------
    // Diagnostic: import resolution on injected XML
    // -----------------------------------------------------------------------

    /**
     * Directly verifies that {@link org.jfxcore.fxml.resolve.Fxml2ImportResolver#parseImports}
     * returns a non-empty list and that {@code StackPane} resolves for the injected XML file.
     * This pinpoints whether the import-resolution path is working at all for embedded FXML.
     */
    @Test
    void importResolutionWorksForInjectedFile() {
        getFixture().configureByText("EmbeddedViewDiag.java",
                javaWithMarkup("EmbeddedViewDiag", "    <StackPane/>\n"));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = getInjectedXmlFile();
            assertNotNull(xmlFile, "Injected XmlFile must be present");

            var imports = org.jfxcore.fxml.resolve.Fxml2ImportResolver.parseImports(xmlFile);
            assertFalse(imports.isEmpty(),
                    "parseImports must return non-empty for embedded FXML. " +
                    "Prolog: " + (xmlFile.getDocument() != null ? xmlFile.getDocument().getProlog() : "null-doc") +
                    ", isInjected: " + InjectedLanguageManager.getInstance(getFixture().getProject()).isInjectedFragment(xmlFile) +
                    ", rootName: " + (xmlFile.getRootTag() != null ? xmlFile.getRootTag().getName() : "null-root"));

            var resolved = org.jfxcore.fxml.resolve.Fxml2ImportResolver.resolve("StackPane", xmlFile);
            assertNotNull(resolved,
                    "Fxml2ImportResolver.resolve('StackPane') must return non-null. imports=" + imports);
        });
    }

    /**
     * Verifies that the injection prefix does not contain any {@code <?import?>}
     * processing instructions.
     *
     * <p>The prefix must be stable (import-independent) so that adding or removing a
     * Java import does not cause IntelliJ to recreate the injected PSI file.  A changing
     * prefix would invalidate the injected file, which combined with the completion
     * framework's cached Java-copy retaining a reference to the old injected instance
     * triggers an {@code AssertionError} in
     * {@code CompletionInitializationUtil.setOriginalFile}.  Import data is instead
     * resolved at use-time from the host Java file's import list.
     */
    @Test
    void injectionPrefixContainsNoImportPIs() {
        getFixture().configureByText("EmbeddedViewPrefixStability.java",
                javaWithMarkup("EmbeddedViewPrefixStability", "    <StackPane/>\n"));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = getInjectedXmlFile();
            assertNotNull(xmlFile, "Injected XmlFile must be present");

            // The prolog must NOT contain any <?import?> PIs.
            var prolog = xmlFile.getDocument() != null ? xmlFile.getDocument().getProlog() : null;
            if (prolog != null) {
                var importPIs = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                        prolog, com.intellij.psi.xml.XmlProcessingInstruction.class);
                for (var pi : importPIs) {
                    var node = pi.getNode();
                    var nameNode = node.findChildByType(com.intellij.psi.xml.XmlTokenType.XML_NAME);
                    assertFalse(nameNode != null && "import".equals(nameNode.getText()),
                            "Injection prefix must not contain <?import?> PIs (found: " + pi.getText() + "). " +
                            "The prefix must be stable to avoid completion stale-reference errors.");
                }
            }

            // Import resolution must still work via the fallback (host Java file).
            var imports = org.jfxcore.fxml.resolve.Fxml2ImportResolver.parseImports(xmlFile);
            assertFalse(imports.isEmpty(),
                    "parseImports must return non-empty even without <?import?> PIs in the prefix");
            var resolved = org.jfxcore.fxml.resolve.Fxml2ImportResolver.resolve("StackPane", xmlFile);
            assertNotNull(resolved, "StackPane must resolve via the host-file import fallback");
        });
    }

    // -----------------------------------------------------------------------
    // isFxml2() recognition
    // -----------------------------------------------------------------------

    /**
     * The language injector must fire and the resulting injected {@link XmlFile}
     * must pass both {@link Fxml2EmbeddedUtil#isEmbeddedFxml2} and
     * {@link Fxml2FileType#isFxml2}.
     */
    @Test
    void injectedXmlFileIsRecognizedAsFxml2() {
        getFixture().configureByText("EmbeddedView.java",
                javaWithMarkup("EmbeddedView", "    <StackPane/>\n"));

        // checkHighlighting triggers injector and daemon; then the injected file is available.
        getFixture().checkHighlighting(false, false, false);

        ReadAction.run(() -> {
            XmlFile xmlFile = getInjectedXmlFile();
            assertNotNull(xmlFile, "Injected XmlFile must be present after highlighting");
            assertTrue(Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile),
                    "Injected file must be recognized as embedded FXML via wrapper namespace");
            assertTrue(Fxml2FileType.isFxml2(xmlFile),
                    "isFxml2(PsiFile) must return true for injected FXML fragment");
        });
    }

    // -----------------------------------------------------------------------
    // Wrapper root invisibility
    // -----------------------------------------------------------------------

    /**
     * The synthetic {@code <fxml2:embedded>} wrapper root must not produce any
     * spurious "cannot resolve" or other errors: it must be completely invisible
     * to the plugin's annotators.
     */
    @Test
    void wrapperRootIsInvisibleToAnnotators() {
        // A minimal valid embedded markup: only the wrapper root + one known class tag.
        getFixture().configureByText("EmbeddedView.java",
                javaWithMarkup("EmbeddedView", "    <StackPane/>\n"));

        // No errors expected. If the wrapper root were not guarded, the annotators would
        // report "Cannot resolve symbol 'embedded'" on the wrapper element.
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Import resolution / tag name resolution
    // -----------------------------------------------------------------------

    /**
     * A class tag whose class is covered by a Java wildcard import (e.g.
     * {@code import javafx.scene.control.*}) must resolve correctly -
     * no "Cannot resolve symbol" error.
     */
    @Test
    void tagNamesResolveToJavaClasses() {
        // StackPane and Button are covered by the auto-added "import javafx.scene.layout.*"
        // and "import javafx.scene.control.*" in javaWithMarkup().
        getFixture().configureByText("EmbeddedView.java",
                javaWithMarkup("EmbeddedView",
                        """
                            <StackPane>
                              <Button text="hello"/>
                            </StackPane>
                        """));

        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * An element tag whose class cannot be resolved through the Java imports must
     * produce a "Cannot resolve symbol" error, exactly as in a standalone FXML file.
     */
    @Test
    void unknownTagProducesError() {
        getFixture().configureByText("EmbeddedView.java",
                javaWithMarkup("EmbeddedView",
                        "    <UnknownWidget123/>\n"));

        var errors = getFixture().doHighlighting(HighlightSeverity.ERROR);
        boolean hasUnresolved = errors.stream().anyMatch(
                h -> h.getDescription() != null
                     && h.getDescription().contains("UnknownWidget123"));
        assertTrue(hasUnresolved,
                "Expected a 'Cannot resolve symbol UnknownWidget123' error in embedded FXML");
    }

    // -----------------------------------------------------------------------
    // 18: fx:subclass forbidden in embedded FXML
    // -----------------------------------------------------------------------

    /**
     * {@code fx:subclass} is not allowed in embedded FXML: the code-behind class
     * is always the Java class carrying the {@code @ComponentView} annotation.
     * {@link Fxml2FxAttributeInspection} must report a specific error when it
     * appears on any element of the embedded markup.
     */
    @Test
    void fxClassForbiddenInEmbeddedMarkup() {
        getFixture().enableInspections(new Fxml2FxAttributeInspection());

        // Place fx:subclass on the user's root element: the compiler forbids this.
        getFixture().configureByText("EmbeddedViewFxClass.java",
                javaWithMarkup("EmbeddedViewFxClass", """
                        <StackPane fx:subclass="test.EmbeddedViewFxClass">
                        </StackPane>
                    """));

        var errors = getFixture().doHighlighting(HighlightSeverity.ERROR);
        boolean hasFxClassError = errors.stream().anyMatch(
                h -> h.getDescription() != null
                     && h.getDescription().toLowerCase().contains("fx:subclass")
                     && h.getDescription().toLowerCase().contains("embedded"));
        assertTrue(hasFxClassError,
                "Expected a specific error about fx:subclass being forbidden in embedded FXML. "
                + "Errors found: " + errors.stream()
                        .filter(h -> h.getDescription() != null)
                        .map(h -> "\"" + h.getDescription() + "\"")
                        .toList());
    }

    /**
     * The {@link Fxml2FxAttributeInspection} must NOT produce a spurious error
     * for the synthetic {@code fx:subclass} attribute that the language injector
     * places on the wrapper {@code <fxml2:embedded>} root.
     */
    @Test
    void fxClassOnWrapperRootProducesNoError() {
        getFixture().enableInspections(new Fxml2FxAttributeInspection());

        // Valid embedded markup: no fx:subclass written by the user.
        getFixture().configureByText("EmbeddedViewNoFxClass.java",
                javaWithMarkup("EmbeddedViewNoFxClass", "    <StackPane/>\n"));

        // No errors expected from the inspection.
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code fx:context} on the user's root element in embedded FXML must not produce
     * an "Unexpected intrinsic: context" error.  The injector wraps the user's markup in
     * a synthetic {@code <fxml2:embedded>} element, which means the user's root tag is the
     * first child of the document root, not the document root itself.  The inspection must
     * treat the first child of the wrapper as the effective root when deciding whether a
     * root-only intrinsic such as {@code fx:context} is permitted.
     */
    @Test
    void fxContextOnEmbeddedRootProducesNoError() {
        getFixture().enableInspections(new Fxml2FxAttributeInspection());

        getFixture().configureByText("EmbeddedViewFxContext.java",
                javaWithMarkup("EmbeddedViewFxContext",
                        "    <StackPane fx:context=\"$someContext\"/>\n"));

        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Code-behind class detection
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2InitializeComponentInspection} must warn when a {@code @ComponentView}-annotated
     * class does NOT call {@code initializeComponent()}.
     */
    @Test
    void initializeComponentInspectionFiresWhenCallMissing() {
        getFixture().enableInspections(new Fxml2InitializeComponentInspection());

        // Class without any initializeComponent() call
        getFixture().configureByText("BadView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane/>
                    \""")
                public class BadView {
                    public BadView() {
                        // intentionally missing initializeComponent()
                    }
                }
                """);

        var warnings = getFixture().doHighlighting(HighlightSeverity.WARNING);
        boolean hasMissingInit = warnings.stream().anyMatch(
                h -> h.getDescription() != null
                     && h.getDescription().contains("initializeComponent"));
        assertTrue(hasMissingInit,
                "Inspection must warn about missing initializeComponent() call");
    }

    /**
     * {@link Fxml2InitializeComponentInspection} must NOT warn when the
     * {@code @ComponentView}-annotated class does call {@code initializeComponent()}.
     */
    @Test
    void initializeComponentInspectionNoWarningWhenCallPresent() {
        getFixture().enableInspections(new Fxml2InitializeComponentInspection());

        getFixture().configureByText("GoodView.java",
                javaWithMarkup("GoodView", "    <StackPane/>\n"));

        // javaWithMarkup() already includes initializeComponent() in the constructor.
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Unused-import inspection suppressed
    // -----------------------------------------------------------------------

    /**
     * The "unused import" inspection must NOT fire for embedded FXML: the
     * {@code <?import?>} PIs in the injected prefix come from the host Java imports
     * and are not user-editable.
     */
    @Test
    void unusedImportInspectionSuppressed() {
        getFixture().enableInspections(new Fxml2UnusedImportsInspection());

        // The Java file imports javafx.scene.control.* but only uses StackPane from layout.
        // The injector will synthesise <?import javafx.scene.control.*?> in the injected doc.
        // The unused-import inspection should NOT report it as unused.
        getFixture().configureByText("EmbeddedView.java",
                javaWithMarkup("EmbeddedView", "    <StackPane/>\n"));

        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A Java import that is only referenced in a {@code <?prefix X = ClassName?>}
     * processing instruction inside the embedded FXML markup must not be reported as
     * "Unused import statement" by IntelliJ's built-in Java highlight pass.
     *
     * <p>This tests {@link org.jfxcore.fxml.lang.Fxml2JavaUnusedImportHighlightFilter}
     * in combination with {@link org.jfxcore.fxml.annotator.Fxml2UnusedImportsInspection#collectUsedSimpleNames}.
     */
    @Test
    void javaImportUsedOnlyByPrefixDeclaration_noUnusedImportWarning() {
        getFixture().addClass("package test; public class MyExtension {}");
        // The Java file imports test.MyExtension, which is only referenced in the
        // <?prefix ^ = MyExtension?> PI and nowhere in plain Java code.
        getFixture().configureByText("EmbeddedView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                import test.MyExtension;
                @ComponentView(""\"
                    <?prefix ^ = MyExtension?>
                    <StackPane/>
                ""\")
                public class EmbeddedView {
                    protected void initializeComponent() {}
                    public EmbeddedView() { initializeComponent(); }
                }
                """);

        List<HighlightInfo> highlights = getFixture().doHighlighting();

        List<String> unusedImports = highlights.stream()
                .filter(h -> "Unused import statement".equals(h.getDescription()))
                .map(h -> h.getDescription() + " @ " + h.getStartOffset())
                .toList();

        assertTrue(unusedImports.isEmpty(),
                "Expected no 'Unused import statement' for import used by <?prefix?> PI, "
                        + "but got: " + unusedImports);
    }

    /**
     * Ctrl+click on the class name in a {@code <?prefix ^ = ClassName?>} processing
     * instruction inside embedded FXML must navigate to the named class.
     */
    @Test
    void ctrlClickOnClassNameInEmbeddedPrefixDeclaration_navigatesToClass() {
        getFixture().addClass("package test; public class MyExtension {}");
        getFixture().configureByText("EmbeddedView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                import test.MyExtension;
                @ComponentView(""\"
                    <?prefix ^ = MyExtension?>
                    <StackPane/>
                ""\")
                public class EmbeddedView {
                    protected void initializeComponent() {}
                    public EmbeddedView() { initializeComponent(); }
                }
                """);

        // Trigger injection.
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = getInjectedXmlFile();
            assertNotNull(xmlFile, "Injected XmlFile must be present");

            // Find the <?prefix?> PI in the injected XML.
            com.intellij.psi.xml.XmlProcessingInstruction pi = null;
            for (com.intellij.psi.xml.XmlProcessingInstruction candidate :
                    PsiTreeUtil.findChildrenOfType(xmlFile, com.intellij.psi.xml.XmlProcessingInstruction.class)) {
                if (candidate.getText().contains("prefix")
                        && candidate.getText().contains("MyExtension")) {
                    pi = candidate;
                    break;
                }
            }
            assertNotNull(pi, "Could not find <?prefix?> PI in injected XML");

            String piText = pi.getText();
            int classNameOffset = piText.indexOf("MyExtension");
            assertTrue(classNameOffset >= 0, "Class name not found in PI text: " + piText);

            PsiReference ref = pi.findReferenceAt(classNameOffset + 5);
            assertNotNull(ref, "No reference found on class name in embedded <?prefix?> PI");

            PsiElement resolved = ref.resolve();
            assertNotNull(resolved, "Reference should resolve to MyExtension class");
            assertInstanceOf(PsiClass.class, resolved);
            assertEquals("test.MyExtension", ((PsiClass) resolved).getQualifiedName(),
                    "Class name in embedded <?prefix?> PI should navigate to MyExtension");
        });
    }

    /**
     * IntelliJ's built-in Java "Unused import statement" warning must NOT fire for
     * imports that are only referenced inside the embedded FXML markup (i.e. inside the
     * {@code @ComponentView} string literal), because those imports are needed to resolve
     * the class tags in the markup.
     *
     * <p>This tests {@link org.jfxcore.fxml.lang.Fxml2JavaUnusedImportHighlightFilter}.
     */
    @Test
    void javaBuiltinUnusedImportWarningNotShownForFxml2NeededImports() {
        // The Java file imports both javafx.scene.layout.* and javafx.scene.control.*.
        // Only StackPane (from layout) appears in the FXML markup.
        // javafx.scene.control.* is also synthesised into the injected markup as
        // <?import javafx.scene.control.*?>: so the injector considers it "needed",
        // but the plain Java code does not reference any control class directly.
        // Before this fix, javafx.scene.control.* (and any specific-import variant)
        // would receive an "Unused import statement" warning from Java's highlighting pass.
        getFixture().configureByText("EmbeddedView.java",
                javaWithMarkup("EmbeddedView", "    <StackPane/>\n"));

        List<HighlightInfo> highlights = getFixture().doHighlighting();

        // No "Unused import statement" highlight should be present on any of the imports
        // that are preserved by the injector (layout.* and control.*).
        List<String> unusedImportDescriptions = highlights.stream()
                .filter(h -> "Unused import statement".equals(h.getDescription()))
                .map(h -> h.getDescription() + " @ offset " + h.getStartOffset())
                .toList();

        assertTrue(unusedImportDescriptions.isEmpty(),
                "Expected no 'Unused import statement' for imports needed by embedded FXML markup, "
                        + "but got: " + unusedImportDescriptions);
    }

    /**
     * The Java built-in {@code UNUSED_IMPORT} inspection must NOT report false positives
     * when run as a <em>batch</em> "Inspect Code" inspection (i.e. through
     * {@code GlobalInspectionContextImpl}).
     *
     * <p>The import {@code javafx.scene.control.*} is not directly used in Java code, but
     * it is needed by the embedded FXML markup. Without
     * {@link org.jfxcore.fxml.lang.Fxml2InspectionExtensionsFactory}, the batch run
     * would report it as "Unused import".
     *
     * <p>This tests {@link org.jfxcore.fxml.lang.Fxml2InspectionExtensionsFactory}.
     */
    @Test
    @SuppressWarnings("UnstableApiUsage")
    void batchUnusedImportInspectionNotReportedForFxml2NeededImports() {
        // The Java file imports both javafx.scene.layout.* (used by StackPane in markup)
        // and javafx.scene.control.* (injected into the doc but not directly used in Java).
        getFixture().configureByText("EmbeddedView.java",
                javaWithMarkup("EmbeddedView", "    <StackPane/>\n"));

        // Warm up the injection so FXML PSI is present during batch inspection.
        getFixture().doHighlighting();

        Project project = getFixture().getProject();

        InspectionProfileImpl.INIT_INSPECTIONS = true;
        try {
            // Build a minimal profile with only UNUSED_IMPORT enabled so we can isolate results.
            InspectionProfileImpl profile = new InspectionProfileImpl("Fxml2BatchTestProfile");
            InspectionsKt.disableAllTools(profile);
            profile.enableTool(UnusedImportInspection.SHORT_NAME, project);

            // Run the batch inspection entirely on the EDT (mirrors GlobalInspectionContextTest).
            EdtTestUtil.runInEdtAndWait(() -> {
                GlobalInspectionContextImpl context =
                        ((InspectionManagerEx) InspectionManager.getInstance(project))
                                .createNewGlobalContext();
                context.setExternalProfile(profile);

                AnalysisScope scope = new AnalysisScope(getFixture().getFile());
                context.doInspections(scope);
                UIUtil.dispatchAllInvocationEvents();

                Tools tools = context.getTools().get(UnusedImportInspection.SHORT_NAME);
                assertNotNull(tools, "UNUSED_IMPORT tool should be present in the context");

                InspectionToolWrapper<?, ?> wrapper = tools.getTool();
                Collection<CommonProblemDescriptor> problems =
                        context.getPresentation(wrapper).getProblemDescriptors();

                // Both imports (layout.* and control.*) are needed by the embedded FXML markup.
                // The Fxml2InspectionExtensionsFactory hook must have suppressed them.
                assertTrue(problems.isEmpty(),
                        "Expected no batch 'Unused import' problems for FXML-needed imports, "
                                + "but got: " + problems.stream()
                                .map(CommonProblemDescriptor::getDescriptionTemplate)
                                .toList());

                context.cleanup();
            });
        } finally {
            InspectionProfileImpl.INIT_INSPECTIONS = false;
        }
    }

    /**
     * A Java import for a class referenced only in a {@code converter=} parameter path
     * inside embedded FXML markup must not be reported as "Unused import statement" by
     * IntelliJ's built-in Java highlight pass.
     *
     * <p>The converter class is not used anywhere in plain Java code; it appears as the
     * qualifier in {@code converter=BindingConverter.INSTANCE} inside the markup annotation
     * value. Without the fix, {@code collectClassNamesFromBindingValue} only inspects the
     * primary binding path and never sees the class name in the secondary parameter path.
     *
     * <p>This tests the paramPath branch of
     * {@link Fxml2UnusedImportsInspection#collectUsedSimpleNames}.
     */
    @Test
    void javaImportUsedOnlyByConverterParamPath_reportedAsNeededByMarkup() {
        // Converter is in a separate package so the import is non-trivial.
        getFixture().addClass("""
                package converters;
                import javafx.util.StringConverter;
                public class BindingConverter extends StringConverter<String> {
                    public static final BindingConverter INSTANCE = new BindingConverter();
                    @Override public String toString(String object) { return object; }
                    @Override public String fromString(String string) { return string; }
                }
                """);

        // The Java file imports converters.BindingConverter, referenced only in the
        // converter= path inside the embedded FXML markup, not in plain Java code.
        getFixture().configureByText("ConverterImportView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                import converters.BindingConverter;
                @ComponentView(""\"
                    <TextField text="#{text; converter=BindingConverter.INSTANCE}"/>
                ""\")
                public class ConverterImportView {
                    public String text = "hello";
                    protected void initializeComponent() {}
                    public ConverterImportView() { initializeComponent(); }
                }
                """);
        getFixture().doHighlighting();

        // Verify that the import is recognized as needed by the FXML markup.
        // Without the fix, collectUsedSimpleNames() ignores the converter= path, so
        // isImportNeededByXmlFile() returns false for converters.BindingConverter, and
        // the import is flagged as "Unused import statement" in the real IDE.
        ReadAction.run(() -> {
            XmlFile xmlFile = getInjectedXmlFile();
            assertNotNull(xmlFile, "Injected XmlFile must be present");

            Set<String> usedNames = Fxml2UnusedImportsInspection.collectUsedSimpleNames(xmlFile);
            assertTrue(usedNames.contains("BindingConverter"),
                    "collectUsedSimpleNames() must include 'BindingConverter' from converter= path. "
                    + "Found: " + usedNames);

            boolean needed = Fxml2ImportUtil.isImportNeededByXmlFile(
                    "converters.BindingConverter", false, xmlFile);
            assertTrue(needed,
                    "isImportNeededByXmlFile() must return true for a class referenced in "
                    + "converter= path, otherwise the import is incorrectly flagged as unused.");
        });
    }

    // -----------------------------------------------------------------------
    // Intention actions suppressed for embedded FXML
    // -----------------------------------------------------------------------

    /**
     * {@link org.jfxcore.fxml.actions.CreateCodeBehindIntention} must NOT be offered inside embedded FXML -
     * the annotated class is already the code-behind.
     */
    @Test
    void createCodeBehindIntentionSuppressed() {
        getFixture().configureByText("EmbeddedView.java",
                javaWithMarkup("EmbeddedView", "    <StackPane/>\n"));

        // Trigger highlighting so the injector runs.
        getFixture().doHighlighting();

        // Get the injected file and check that the intention is not available on it.
        XmlFile injected = getInjectedXmlFile();
        assertNotNull(injected, "Injected XML must be present");

        ReadAction.run(() -> {
            // Verify that isEmbeddedFxml2 returns true: this is the prerequisite for
            // Fxml2IntentionActionFilter to suppress CreateCodeBehindIntention.
            assertTrue(Fxml2EmbeddedUtil.isEmbeddedFxml2(injected),
                    "isEmbeddedFxml2 must be true so that CreateCodeBehindIntention is suppressed");
        });
    }

    // -----------------------------------------------------------------------
    // fx:id navigation: Java field -> embedded fx:id
    // -----------------------------------------------------------------------

    /**
     * Ctrl+click on a Java field that corresponds to an {@code fx:id} in embedded markup
     * must navigate to the {@link XmlAttributeValue} in the injected XML file.
     */
    @Test
    void fxIdFieldNavigatesToEmbeddedFxId() {
        // Base class with the injected field
        getFixture().addClass("""
                package test;
                import javafx.scene.control.Button;
                public abstract class EmbeddedViewBase extends javafx.scene.layout.StackPane {
                    public Button myBtn;
                    protected void initializeComponent() {}
                }
                """);

        getFixture().configureByText("EmbeddedView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <Button fx:id="myBtn"/>
                    </StackPane>
                    \""")
                public class EmbeddedView extends EmbeddedViewBase {
                    public EmbeddedView() { initializeComponent(); }
                }
                """);

        // Trigger injection
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass base = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.EmbeddedViewBase",
                            GlobalSearchScope.projectScope(
                                    getFixture().getProject()));
            assertNotNull(base, "EmbeddedViewBase must be found");
            PsiField field = base.findFieldByName("myBtn", false);
            assertNotNull(field, "Field 'myBtn' must exist on EmbeddedViewBase");

            // Verify via ReferencesSearch (which uses Fxml2FxIdFieldSearcher) that the
            // fx:id in the embedded FXML is found as a reference to the field.
            var refs = ReferencesSearch.search(field,
                    GlobalSearchScope.projectScope(
                            getFixture().getProject())).findAll();
            boolean foundInEmbedded = refs.stream().anyMatch(
                    ref -> ref.getElement() instanceof XmlAttributeValue attrVal
                           && attrVal.getContainingFile() instanceof XmlFile xmlFile
                           && Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile));
            assertTrue(foundInEmbedded,
                    "ReferencesSearch on 'myBtn' must find the fx:id in embedded FXML.\n"
                    + "References found: " + refs.stream()
                            .map(r -> r.getElement().getContainingFile().getName()
                                      + "@" + r.getElement().getClass().getSimpleName())
                            .toList());
        });
    }

    /**
     * {@link Fxml2FxIdFindUsagesHandlerFactory} must locate the {@link XmlAttributeValue}
     * of {@code fx:id="myBtn"} in the embedded FXML when starting from the Java field.
     */
    @Test
    void fxIdFindUsagesHandlerFindsEmbeddedFxId() {
        getFixture().addClass("""
                package test;
                import javafx.scene.control.Button;
                public abstract class EmbeddedViewBase2 extends javafx.scene.layout.StackPane {
                    public Button myBtn2;
                    protected void initializeComponent() {}
                }
                """);

        getFixture().configureByText("EmbeddedView2.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <Button fx:id="myBtn2"/>
                    </StackPane>
                    \""")
                public class EmbeddedView2 extends EmbeddedViewBase2 {
                    public EmbeddedView2() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass base = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.EmbeddedViewBase2",
                            GlobalSearchScope.projectScope(
                                    getFixture().getProject()));
            assertNotNull(base);
            PsiField field = base.findFieldByName("myBtn2", false);
            assertNotNull(field);

            var factory = new Fxml2FxIdFindUsagesHandlerFactory();
            assertTrue(factory.canFindUsages(field),
                    "Factory must accept a PsiField that has an fx:id in embedded FXML");

            var handler = factory.createFindUsagesHandler(field, false);
            assertNotNull(handler,
                    "Factory must create a handler for a field with embedded fx:id");

            // The primary elements must include an XmlAttributeValue from the injected file.
            PsiElement[] primaries = handler.getPrimaryElements();
            boolean hasEmbeddedAttrVal = Arrays.stream(primaries).anyMatch(
                    p -> p instanceof XmlAttributeValue attrVal
                         && attrVal.getContainingFile() instanceof XmlFile xmlFile
                         && Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile));
            assertTrue(hasEmbeddedAttrVal,
                    "Handler primaries must include the fx:id XmlAttributeValue in embedded FXML.\n"
                    + "Primaries: " + Arrays.stream(primaries)
                            .map(p -> p.getClass().getSimpleName()
                                      + " in " + p.getContainingFile().getName())
                            .toList());
        });
    }

    // -----------------------------------------------------------------------
    // getHostClass / code-behind detection
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2EmbeddedUtil#getHostClass} must return the Java class that carries
     * the {@code @ComponentView} annotation.
     */
    @Test
    void getHostClassReturnsAnnotatedClass() {
        getFixture().configureByText("EmbeddedView.java",
                javaWithMarkup("EmbeddedView3", "    <StackPane/>\n"));

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = getInjectedXmlFile();
            assertNotNull(xmlFile);
            PsiClass hostClass = Fxml2EmbeddedUtil.getHostClass(xmlFile);
            assertNotNull(hostClass, "getHostClass must return the @ComponentView-annotated class");
            assertEquals("test.EmbeddedView3", hostClass.getQualifiedName(),
                    "getHostClass must return the class with FQN test.EmbeddedView3");
        });
    }

    /**
     * {@link Fxml2InitializeComponentInspection#isCodeBehindClass} must return
     * {@code true} for a class annotated with {@code @ComponentView}, even without a
     * corresponding {@code .fxml} file.
     */
    @Test
    void isCodeBehindClassReturnsTrueForMarkupAnnotated() {
        getFixture().configureByText("EmbeddedView.java",
                javaWithMarkup("EmbeddedView4", "    <StackPane/>\n"));

        ReadAction.run(() -> {
            PsiClass cls = findMarkupClass();
            assertNotNull(cls);
            assertTrue(
                    Fxml2InitializeComponentInspection.isCodeBehindClass(
                            cls, getFixture().getProject()),
                    "@ComponentView-annotated class must be recognized as a code-behind class");
        });
    }

    // -----------------------------------------------------------------------
    // Wrapper root: descriptor provider returns null
    // -----------------------------------------------------------------------

    /**
     * The {@link Fxml2EmbeddedUtil#isWrapperRoot} predicate must return {@code true}
     * for the synthetic {@code <fxml2:embedded>} root tag and {@code false} for all
     * user markup tags.
     */
    @Test
    void wrapperRootPredicateWorks() {
        getFixture().configureByText("EmbeddedView.java",
                javaWithMarkup("EmbeddedView", "    <StackPane><Button/></StackPane>\n"));

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = getInjectedXmlFile();
            assertNotNull(xmlFile);
            XmlTag root = xmlFile.getRootTag();
            assertNotNull(root);
            assertTrue(Fxml2EmbeddedUtil.isWrapperRoot(root),
                    "Root of injected file must be the synthetic wrapper root");

            // The user markup root is the first child sub-tag.
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
     * A binding path that references a field on the code-behind class must resolve
     * without an error, e.g. {@code {fx:Observe model.text}} where {@code model} is a field
     * on the host class.
     */
    @Test
    void bindingPathResolvesToHostClassField() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.StringProperty;
                import javafx.beans.property.SimpleStringProperty;
                public class ViewModel5 {
                    public final StringProperty statusText = new SimpleStringProperty();
                }
                """);

        getFixture().configureByText("EmbeddedView5.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <Label text="{fx:Observe model.statusText}"/>
                    </StackPane>
                    \""")
                public class EmbeddedView5 {
                    public final ViewModel5 model = new ViewModel5();
                    protected void initializeComponent() {}
                    public EmbeddedView5() { initializeComponent(); }
                }
                """);

        // No binding-path errors expected.
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A binding path that references an unknown field must produce a
     * "cannot be resolved" error on the unresolvable segment.
     */
    @Test
    void unboundBindingPathProducesError() {
        getFixture().configureByText("EmbeddedView6.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <Label text="{fx:Observe nonExistentField}"/>
                    </StackPane>
                    \""")
                public class EmbeddedView6 {
                    protected void initializeComponent() {}
                    public EmbeddedView6() { initializeComponent(); }
                }
                """);

        var errors = getFixture().doHighlighting(HighlightSeverity.ERROR);
        boolean hasUnresolved = errors.stream().anyMatch(
                h -> h.getDescription() != null
                     && h.getDescription().contains("nonExistentField"));
        assertTrue(hasUnresolved,
                "Expected an unresolved-binding error for 'nonExistentField' in embedded FXML");
    }

    /**
     * A binding segment that references an {@code fx:id} name inside embedded FXML must
     * resolve to the code-behind field, and that field's {@link PsiElement#getNavigationElement()}
     * must return the {@link XmlAttributeValue} of the {@code fx:id} in the <em>injected</em>
     * XML file: exactly as it does for standalone FXML files.
     */
    @Test
    void fxIdGotoDeclarationFromBinding() {
        getFixture().addClass("""
                package test;
                import javafx.scene.control.Button;
                public abstract class BindNavBase extends javafx.scene.layout.StackPane {
                    public Button myNavBtn;
                    protected void initializeComponent() {}
                }
                """);

        getFixture().configureByText("BindNavView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <Button fx:id="myNavBtn"/>
                      <Button disable="${myNavBtn.disabled}"/>
                    </StackPane>
                    \""")
                public class BindNavView extends BindNavBase {
                    public BindNavView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = getInjectedXmlFile();
            assertNotNull(xmlFile, "Injected XML must be present");

            // Find fx:id="myNavBtn" attribute value in the injected file.
            XmlAttributeValue fxIdVal = null;
            for (var tag : PsiTreeUtil.findChildrenOfType(xmlFile, XmlTag.class)) {
                XmlAttribute idAttr = tag.getAttribute("fx:id");
                if (idAttr != null && "myNavBtn".equals(idAttr.getValue())) {
                    fxIdVal = idAttr.getValueElement();
                    break;
                }
            }
            final XmlAttributeValue finalFxIdVal = fxIdVal;
            assertNotNull(finalFxIdVal, "fx:id='myNavBtn' not found in injected XML");

            // Find the binding attribute value that contains "myNavBtn".
            XmlAttributeValue bindingAttrVal = null;
            for (var attrVal : PsiTreeUtil.findChildrenOfType(xmlFile, XmlAttributeValue.class)) {
                if (attrVal.getText().contains("myNavBtn.disabled")) {
                    bindingAttrVal = attrVal;
                    break;
                }
            }
            assertNotNull(bindingAttrVal,
                    "Binding attribute value containing 'myNavBtn.disabled' not found in injected XML");

            // The first segment of the binding path must have a Fxml2BindingSegmentReference.
            Fxml2BindingSegmentReference segRef = Arrays.stream(bindingAttrVal.getReferences())
                    .filter(r -> r instanceof Fxml2BindingSegmentReference s
                            && "myNavBtn".equals(s.getCanonicalText()))
                    .map(r -> (Fxml2BindingSegmentReference) r)
                    .findFirst().orElse(null);
            assertNotNull(segRef,
                    "No Fxml2BindingSegmentReference for 'myNavBtn' in embedded FXML binding");

            // Resolving the segment reference must yield a PsiField.
            PsiElement resolved = segRef.resolve();
            assertNotNull(resolved, "Binding segment 'myNavBtn' must resolve to a non-null element");
            assertInstanceOf(PsiField.class, resolved,
                    "Binding segment must resolve to a PsiField, got "
                    + resolved.getClass().getSimpleName());

            // The field's navigation element must be the fx:id XmlAttributeValue
            // in the injected FXML file.
            assertEquals(finalFxIdVal, resolved.getNavigationElement(),
                    "Binding segment 'myNavBtn' must navigate to the fx:id XmlAttributeValue "
                    + "in the injected embedded FXML file");
        });
    }

    // -----------------------------------------------------------------------
    // fxIdFindUsages: Find Usages on fx:id value in embedded FXML
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2FxIdFindUsagesHandlerFactory} invoked on the {@code fx:id} attribute value
     * in embedded FXML must return a handler whose primary elements include both the
     * {@link XmlAttributeValue} and the code-behind field: same as for standalone FXML.
     */
    @Test
    void fxIdFindUsages() {
        getFixture().addClass("""
                package test;
                import javafx.scene.control.Button;
                public abstract class FuBase extends javafx.scene.layout.StackPane {
                    public Button myFuBtn;
                    protected void initializeComponent() {}
                }
                """);

        getFixture().configureByText("FuView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <Button fx:id="myFuBtn"/>
                      <Button disable="${myFuBtn.disabled}"/>
                    </StackPane>
                    \""")
                public class FuView extends FuBase {
                    public FuView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = getInjectedXmlFile();
            assertNotNull(xmlFile);

            // Find fx:id="myFuBtn" in the injected XML.
            XmlAttributeValue fxIdVal = null;
            for (var tag : PsiTreeUtil.findChildrenOfType(xmlFile, XmlTag.class)) {
                XmlAttribute idAttr = tag.getAttribute("fx:id");
                if (idAttr != null && "myFuBtn".equals(idAttr.getValue())) {
                    fxIdVal = idAttr.getValueElement();
                    break;
                }
            }
            assertNotNull(fxIdVal, "fx:id='myFuBtn' not found in injected XML");

            var factory = new Fxml2FxIdFindUsagesHandlerFactory();
            assertTrue(factory.canFindUsages(fxIdVal),
                    "Factory must accept XmlAttributeValue from embedded FXML");

            var handler = factory.createFindUsagesHandler(fxIdVal, false);
            assertNotNull(handler, "Handler must be created for embedded fx:id");

            List<PsiElement> primaries = Arrays.asList(handler.getPrimaryElements());
            assertTrue(primaries.contains(fxIdVal),
                    "Primaries must include the fx:id XmlAttributeValue from embedded FXML");
            assertTrue(primaries.stream()
                            .anyMatch(e -> e instanceof PsiField f && "myFuBtn".equals(f.getName())),
                    "Primaries must include the code-behind field 'myFuBtn'.\nPrimaries: "
                    + primaries.stream()
                            .map(p -> p.getClass().getSimpleName() + "(" + p.getText() + ")")
                            .toList());
        });
    }

    // -----------------------------------------------------------------------
    // codeCompletion: tag name completion in embedded FXML
    // -----------------------------------------------------------------------

    /**
     * Tag-name completion inside an embedded FXML string must suggest class names that
     * are reachable through the host Java file's import declarations: the same set that
     * the {@code Fxml2CompletionContributor} suggests in standalone FXML files.
     */
    @Test
    void codeCompletionInEmbeddedFxml2() {
        // The <caret> marker is placed inside a partial element tag name so that
        // tag-name completion fires.  The fixture strips <caret> and positions
        // the editor caret there; completeBasic() then delegates to the injected
        // XML language's completion contributors.
        getFixture().configureByText("ComplView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <But<caret>/>
                    </StackPane>
                    \""")
                public class ComplView {
                    protected void initializeComponent() {}
                    public ComplView() { initializeComponent(); }
                }
                """);

        LookupElement[] completions = getFixture().completeBasic();
        assertNotNull(completions,
                "completeBasic() must return results inside embedded FXML tag position");

        boolean hasButton = Arrays.stream(completions)
                .anyMatch(c -> "Button".equals(c.getLookupString()));
        assertTrue(hasButton,
                "Completion must suggest 'Button' from the javafx.scene.control.* import "
                + "in embedded FXML. Got: "
                + Arrays.stream(completions)
                        .map(LookupElement::getLookupString)
                        .limit(30)
                        .toList());
    }

    // -----------------------------------------------------------------------
    // attributeNameCompletionInEmbeddedFxml2UsesDoubleQuotes
    // -----------------------------------------------------------------------

    /**
     * Attribute-name completion in embedded FXML must insert {@code =""} (double quotes),
     * not {@code =''} (single quotes).
     *
     * <p>{@code XmlAttributeInsertHandler} checks
     * {@code file.getContext().getText().startsWith("\"")} and, if true, switches to single
     * quotes.  For embedded FXML the context element is the host Java text block
     * ({@code """..."""}), which also starts with {@code '"'}.  The plugin's insert handler
     * must therefore always use double quotes, ignoring the context text.
     */
    @Test
    void attributeNameCompletionInEmbeddedFxml2UsesDoubleQuotes() {
        // Place the caret at a plain attribute-name position inside embedded FXML.
        // 'minH' uniquely matches 'minHeight' on TextField, so completion auto-inserts.
        getFixture().configureByText("AttrInsertTest.java", javaWithMarkup("AttrInsertView",
                """
                    <StackPane>
                      <TextField maxWidth="200" minH<caret>/>
                    </StackPane>
                """));

        LookupElement[] items = getFixture().completeBasic();

        // If there's only one match it gets auto-inserted (items == null).
        // If multiple items are returned we must select one manually.
        if (items != null) {
            LookupElement minHeightItem = Arrays.stream(items)
                    .filter(e -> "minHeight".equals(e.getLookupString()))
                    .findFirst().orElse(null);
            assertNotNull(minHeightItem,
                    "Expected 'minHeight' in completion items, got: "
                    + Arrays.stream(items).map(LookupElement::getLookupString)
                            .limit(20).toList());
            var activeLookup = LookupManager.getActiveLookup(getFixture().getEditor());
            assertNotNull(activeLookup, "Expected active lookup after completeBasic()");
            activeLookup.setCurrentItem(minHeightItem);
            getFixture().finishLookup(Lookup.NORMAL_SELECT_CHAR);
        }

        String docText = getFixture().getEditor().getDocument().getText();
        assertTrue(docText.contains("minHeight=\"\""),
                "Attribute name completion in embedded FXML must insert =\"\" (double quotes). "
                + "Document text:\n" + docText);
        assertFalse(docText.contains("minHeight=''"),
                "Attribute name completion in embedded FXML must NOT insert ='' (single quotes). "
                + "Document text:\n" + docText);
    }

    // -----------------------------------------------------------------------
    // renameInEmbeddedMarkup: rename fx:id propagates to Java literal
    // -----------------------------------------------------------------------

    /**
     * Renaming an {@code fx:id} attribute value in the injected FXML must propagate
     * the change back through the injection host to the Java string literal.
     */
    @Test
    void renameInEmbeddedMarkup() {
        getFixture().addClass("""
                package test;
                import javafx.scene.control.Button;
                public abstract class RenBase extends javafx.scene.layout.StackPane {
                    public Button myRenBtn;
                    protected void initializeComponent() {}
                }
                """);

        getFixture().configureByText("RenView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <Button fx:id="myRenBtn"/>
                    </StackPane>
                    \""")
                public class RenView extends RenBase {
                    public RenView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        // Obtain the fx:id XmlAttributeValue from the injected file.
        XmlAttributeValue fxIdVal = ReadAction.compute(() -> {
            XmlFile xmlFile = getInjectedXmlFile();
            if (xmlFile == null) return null;
            for (var tag : PsiTreeUtil.findChildrenOfType(xmlFile, XmlTag.class)) {
                XmlAttribute idAttr = tag.getAttribute("fx:id");
                if (idAttr != null && "myRenBtn".equals(idAttr.getValue())) {
                    return idAttr.getValueElement();
                }
            }
            return null;
        });
        assertNotNull(fxIdVal, "fx:id='myRenBtn' must be found in the injected XML file");

        // Rename via the fixture.  Editing injected PSI propagates back to the host
        // Java string literal through IntelliJ's injection write-through mechanism.
        // Must be executed on the EDT so that JavaFxRenameAttributeProcessor.canProcessElement
        // has the implicit read-intent lock it requires to access PSI.
        com.intellij.openapi.application.ApplicationManager.getApplication()
                .invokeAndWait(() -> getFixture().renameElement(fxIdVal, "myRenBtnRenamed"));

        // The Java source must now contain the new name.
        String javaText = getFixture().getFile().getText();
        assertTrue(javaText.contains("myRenBtnRenamed"),
                "Java source must contain the renamed fx:id value 'myRenBtnRenamed' after rename.\n"
                + "Actual source:\n" + javaText);
    }

    // -----------------------------------------------------------------------
    // AddImportForClassReferenceIntention in embedded FXML
    // -----------------------------------------------------------------------

    /**
     * When the user writes a fully-qualified class name inside embedded FXML markup
     * (e.g. {@code <javafx.scene.control.Button/>}) and the class is not yet imported
     * in the host Java file, {@code AddImportForClassReferenceIntention} must:
     * <ol>
     *   <li>Be available at the caret position.</li>
     *   <li>Insert a Java {@code import} statement into the host Java file when invoked.</li>
     *   <li>Shorten the FQN element tag to its simple name in the embedded markup.</li>
     * </ol>
     */
    @Test
    void addImportIntentionInsertsJavaImport() {
        // Java file imports layout.* but NOT control.*: Button is unresolved.
        // The caret is placed on the class-name segment of the FQN element tag.
        getFixture().configureByText("AddImportView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane>
                      <javafx.scene.control.Butto<caret>n text="hello"/>
                    </StackPane>
                    \""")
                public class AddImportView {
                    protected void initializeComponent() {}
                    public AddImportView() { initializeComponent(); }
                }
                """);

        // Trigger injection so the XML PSI (and the tag reference) is available.
        getFixture().doHighlighting();

        // The intention should now be available at the caret position.
        IntentionAction action = findInjectedAddImportIntention();
        assertNotNull(action,
                "AddImportForClassReferenceIntention must be available inside embedded FXML "
                + "when the caret is on an unimported FQN class name.");

        // Invoke it.
        getFixture().launchAction(action);

        // The host Java file must now import javafx.scene.control.Button.
        // Note: getFixture().getFile() returns the injected XML when the caret is inside the
        // injection; we must navigate to the Java file explicitly via JavaPsiFacade.
        String javaText = ReadAction.compute(() -> {
            PsiClass cls = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.AddImportView",
                            GlobalSearchScope.projectScope(
                                    getFixture().getProject()));
            if (cls == null) return "";
            com.intellij.psi.PsiFile f = cls.getContainingFile();
            return f != null ? f.getText() : "";
        });

        boolean hasImport = javaText.contains("import javafx.scene.control.Button;")
                || javaText.contains("import javafx.scene.control.*;");
        assertTrue(hasImport,
                "Java file must contain a Button import after invoking AddImportForClassReferenceIntention.\n"
                + "Actual source:\n" + javaText);

        // The embedded markup string in the Java file must no longer contain the FQN.
        // (The FQN cleanup renamed <javafx.scene.control.Button> -> <Button> in the literal.)
        assertFalse(javaText.contains("<javafx.scene.control.Button"),
                "Embedded markup must use simple name 'Button' after the intention runs, "
                + "not the FQN.\nActual source:\n" + javaText);
    }

    /**
     * When the user writes a fully-qualified project-source class name as an embedded FXML
     * element tag (e.g. {@code <test.CustomLabel/>}) and the class is not yet imported in the
     * host Java file, {@code AddImportForClassReferenceIntention} must:
     * <ol>
     *   <li>Be available at the caret position on the class-name segment.</li>
     *   <li>Insert a Java {@code import} statement into the host Java file when invoked.</li>
     *   <li>Shorten the FQN element tag to its simple name in the embedded markup.</li>
     * </ol>
     *
     * <p>Applies to project-source classes (not just library classes), since both must be
     * explicitly imported to allow use of the simple class name in FXML markup.
     */
    @Test
    void addImportIntentionInsertsJavaImportForProjectSourceClass() {
        getFixture().addClass("""
                package test;
                import javafx.scene.control.Label;
                public class ImportTestLabel extends Label {
                    public ImportTestLabel() {}
                }
                """);

        getFixture().configureByText("ImportSourceView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane>
                      <test.ImportTest<caret>Label/>
                    </StackPane>
                    \""")
                public class ImportSourceView {
                    protected void initializeComponent() {}
                    public ImportSourceView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        IntentionAction action = findInjectedAddImportIntention();
        assertNotNull(action,
                "AddImportForClassReferenceIntention must be available for a project-source class "
                + "used as a FQN element tag in embedded FXML.");

        getFixture().launchAction(action);

        String javaText = ReadAction.compute(() -> {
            PsiClass cls = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.ImportSourceView",
                            GlobalSearchScope.projectScope(getFixture().getProject()));
            if (cls == null) return "";
            com.intellij.psi.PsiFile f = cls.getContainingFile();
            return f != null ? f.getText() : "";
        });

        assertTrue(javaText.contains("import test.ImportTestLabel;"),
                "Java file must contain the project-source class import after invoking "
                + "AddImportForClassReferenceIntention.\nActual source:\n" + javaText);
        assertFalse(javaText.contains("<test.ImportTestLabel"),
                "Embedded markup must use simple name after the intention runs.\n"
                + "Actual source:\n" + javaText);
    }

    /**
     * When an unresolved simple class name is used as a tag in embedded FXML
     * (e.g. {@code <ImportTestLabel/>} without a corresponding Java import), the annotator
     * must attach an "Add import" quick fix to the "Cannot resolve symbol" error.
     * Applying the fix must insert a Java {@code import} statement into the host Java file.
     */
    @Test
    void addImportQuickfixAvailableForUnresolvedSimpleNameInEmbeddedFxml2() {
        getFixture().addClass("""
                package test;
                import javafx.scene.control.Label;
                public class SimpleNameImportLabel extends Label {
                    public SimpleNameImportLabel() {}
                }
                """);

        getFixture().configureByText("SimpleNameView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane>
                      <SimpleNameImportLa<caret>bel/>
                    </StackPane>
                    \""")
                public class SimpleNameView {
                    protected void initializeComponent() {}
                    public SimpleNameView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        // The "Add import for 'SimpleNameImportLabel'" quick fix must be available.
        IntentionAction action = getFixture().findSingleIntention(
                "Add import for 'SimpleNameImportLabel'");
        assertNotNull(action,
                "Fxml2AddImportFix must be attached to the 'Cannot resolve symbol' error "
                + "for an unresolved simple class name in embedded FXML.");

        getFixture().launchAction(action);

        String javaText = ReadAction.compute(() -> {
            PsiClass cls = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.SimpleNameView",
                            GlobalSearchScope.projectScope(getFixture().getProject()));
            if (cls == null) return "";
            com.intellij.psi.PsiFile f = cls.getContainingFile();
            return f != null ? f.getText() : "";
        });

        assertTrue(javaText.contains("import test.SimpleNameImportLabel;"),
                "Java file must contain the import for the class resolved via the 'Add import' quick fix in embedded FXML.\n"
                + "Actual source:\n" + javaText);
    }

    // -----------------------------------------------------------------------
    // imports preserved after class rename
    // -----------------------------------------------------------------------

    /**
     * Renaming a {@code @ComponentView}-annotated Java class (Shift+F6) must NOT remove
     * imports that are needed by the embedded FXML markup.
     *
     * <p>Root cause: IntelliJ's {@code OptimizeImportsRefactoringHelper} (a
     * {@code RefactoringHelper} registered for all refactoring operations) calls
     * {@code JavaCodeStyleManager.findRedundantImports()} after every rename.
     * That method only examines Java code references and is blind to class names
     * referenced inside the {@code @ComponentView} string-literal annotation value -
     * so it marks FXML-needed imports as redundant and deletes them.
     *
     * <p>The fix is {@link org.jfxcore.fxml.codeinsight.Fxml2EmbeddedJavaRefactoringHelper},
     * a {@code RefactoringHelper} registered with {@code order="last"} that captures
     * FXML-needed imports in its read phase and restores them after all other helpers run.
     */
    @Test
    void importsPreservedAfterClassRename() {
        getFixture().configureByText("RenImportsView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <Button text="hello"/>
                    </StackPane>
                    \""")
                public class RenImportsView {
                    protected void initializeComponent() {}
                    public RenImportsView() { initializeComponent(); }
                }
                """);

        // Trigger injection so import resolution (injected XML file) is available.
        getFixture().doHighlighting();

        // Find the @ComponentView class before rename.
        PsiClass cls = ReadAction.compute(this::findMarkupClass);
        assertNotNull(cls, "Must find @ComponentView class");

        // Rename the class.  This triggers OptimizeImportsRefactoringHelper, which
        // calls findRedundantImports() and would strip FXML-needed imports without
        // Fxml2EmbeddedJavaRefactoringHelper protecting them.
        com.intellij.openapi.application.ApplicationManager.getApplication()
                .invokeAndWait(() -> getFixture().renameElement(cls, "RenImportsView2"));

        // Locate the renamed class and read its source.
        String text = ReadAction.compute(() -> {
            PsiClass renamed = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.RenImportsView2",
                            GlobalSearchScope.projectScope(getFixture().getProject()));
            if (renamed == null) return "(class not found)";
            com.intellij.psi.PsiFile f = renamed.getContainingFile();
            return f != null ? f.getText() : "(no file)";
        });

        assertTrue(text.contains("javafx.scene.layout"),
                "import javafx.scene.layout.* must survive class rename "
                + "(StackPane is used in embedded FXML).\nActual source:\n" + text);
        assertTrue(text.contains("javafx.scene.control"),
                "import javafx.scene.control.* must survive class rename "
                + "(Button is used in embedded FXML).\nActual source:\n" + text);
    }

    // -----------------------------------------------------------------------
    // tag rename uses simple name and updates the Java import
    // -----------------------------------------------------------------------

    /**
     * Renaming a class that is referenced as an XML tag in embedded FXML markup must:
     * <ol>
     *   <li>Keep the simple (unqualified) tag name, not substitute the fully-qualified name.</li>
     *   <li>Update the Java {@code import} statement to the new fully-qualified name.</li>
     * </ol>
     *
     * <p>Failure mode without the fix: the tag becomes {@code <package.NewName/>} and the
     * original import is removed without a replacement.
     */
    @Test
    void tagRenameKeepsSimpleNameAndUpdatesImport() {
        // Set up a custom label class used by its simple name in the embedded markup.
        getFixture().addClass("""
                package test;
                import javafx.scene.control.Label;
                public class RenTagLabel extends Label {}
                """);

        getFixture().configureByText("RenTagView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import test.RenTagLabel;
                @ComponentView(\"""
                    <StackPane>
                      <RenTagLabel/>
                    </StackPane>
                    \""")
                public class RenTagView {
                    protected void initializeComponent() {}
                    public RenTagView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        // Locate RenTagLabel so we can rename it.
        PsiClass labelClass = ReadAction.compute(() ->
                JavaPsiFacade.getInstance(getFixture().getProject())
                        .findClass("test.RenTagLabel",
                                GlobalSearchScope.projectScope(getFixture().getProject())));
        assertNotNull(labelClass, "Must find test.RenTagLabel before rename");

        // Rename RenTagLabel -> RenTagLabel2.
        com.intellij.openapi.application.ApplicationManager.getApplication()
                .invokeAndWait(() -> getFixture().renameElement(labelClass, "RenTagLabel2"));

        // Read the resulting source of the host Java file.
        String text = ReadAction.compute(() -> {
            PsiClass hostCls = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.RenTagView",
                            GlobalSearchScope.projectScope(getFixture().getProject()));
            if (hostCls == null) return "(RenTagView class not found)";
            com.intellij.psi.PsiFile f = hostCls.getContainingFile();
            return f != null ? f.getText() : "(no file)";
        });

        // The tag must use the simple name, not the FQN.
        // The FQN may still appear as an import statement, so we check specifically
        // that no element tag in the embedded markup uses a qualified name.
        assertFalse(text.contains("<test.RenTagLabel2"),
                "Tag must not use the FQN '<test.RenTagLabel2' in the markup after rename. "
                + "The simple name must be used.\nActual source:\n" + text);
        assertTrue(text.contains("RenTagLabel2"),
                "Source must contain the new simple name 'RenTagLabel2' after rename.\n"
                + "Actual source:\n" + text);

        // The import must be updated to the new FQN.
        assertTrue(text.contains("import test.RenTagLabel2"),
                "import must be updated to 'import test.RenTagLabel2;' after rename.\n"
                + "Actual source:\n" + text);
        assertFalse(text.contains("import test.RenTagLabel;"),
                "Old import 'import test.RenTagLabel;' must be removed after rename.\n"
                + "Actual source:\n" + text);
    }

    // -----------------------------------------------------------------------
    // Fxml2EmbeddedJavaImportOptimizer
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2EmbeddedJavaImportOptimizer#supports} must return {@code true} for a Java
     * file that contains a class annotated with {@code @ComponentView}.
     */
    @Test
    void embeddedJavaImportOptimizerSupports() {
        getFixture().configureByText("OptiView.java",
                javaWithMarkup("OptiView", "    <StackPane/>\n"));

        ReadAction.run(() -> {
            var optimizer = new Fxml2EmbeddedJavaImportOptimizer();
            assertTrue(optimizer.supports(getFixture().getFile()),
                    "Fxml2EmbeddedJavaImportOptimizer must support Java files with @ComponentView");
        });
    }

    /**
     * {@link Fxml2EmbeddedJavaImportOptimizer} must add back Java imports that are needed
     * by the embedded FXML markup but were removed (e.g. by IntelliJ's built-in Java import
     * optimizer, which does not understand that class names inside string literals are "used").
     *
     * <p>The test simulates this by:
     * <ol>
     *   <li>Setting up a Java file whose imports cover the embedded markup's class tags.</li>
     *   <li>Calling {@link Fxml2EmbeddedJavaImportOptimizer#processFile} (read phase: captures
     *       which imports are needed).</li>
     *   <li>Manually removing one of those imports inside a write action (simulating what the
     *       built-in Java optimizer would do).</li>
     *   <li>Running the optimizer's {@link Runnable} (write phase: should add it back).</li>
     *   <li>Verifying the import is present again.</li>
     * </ol>
     */
    @Test
    void embeddedJavaImportOptimizerAddsBackRemovedImport() {
        // Java file imports both layout.* and control.*; the embedded markup uses both.
        getFixture().configureByText("OptiView2.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <Button text="hello"/>
                    </StackPane>
                    \""")
                public class OptiView2 {
                    protected void initializeComponent() {}
                    public OptiView2() { initializeComponent(); }
                }
                """);

        // Trigger injection so the injected XML file (and its <?import?> PIs) is available.
        getFixture().doHighlighting();

        var optimizer = new Fxml2EmbeddedJavaImportOptimizer();
        PsiJavaFile javaFile = (PsiJavaFile) getFixture().getFile();

        // Read phase: capture which imports the embedded markup needs.
        Runnable task = ReadAction.compute(() -> optimizer.processFile(javaFile));

        // Simulate Java optimizer removing the javafx.scene.control.* import.
        WriteCommandAction.runWriteCommandAction(getFixture().getProject(), () -> {
            PsiImportList importList = javaFile.getImportList();
            if (importList != null) {
                for (PsiImportStatement imp : importList.getImportStatements()) {
                    if ("javafx.scene.control".equals(imp.getQualifiedName())) {
                        imp.delete();
                        break;
                    }
                }
            }
        });

        // Verify the import was actually removed.
        assertFalse(ReadAction.compute(() -> getFixture().getFile().getText())
                        .contains("import javafx.scene.control"),
                "Setup: import must be removed before running the optimizer");

        // Write phase: the optimizer's Runnable should add the import back.
        WriteCommandAction.runWriteCommandAction(getFixture().getProject(), task);

        // Verify the import is present again.
        String text = ReadAction.compute(() -> getFixture().getFile().getText());
        assertTrue(text.contains("javafx.scene.control"),
                "Optimizer must add back the javafx.scene.control import that is used in "
                + "embedded FXML.\nActual source:\n" + text);
    }

    /**
     * {@link Fxml2EmbeddedJavaImportOptimizer} must NOT add imports that are NOT used
     * in the embedded FXML markup.  If an import was removed by the Java optimizer and
     * the embedded markup doesn't need it, the optimizer must leave it removed.
     */
    @Test
    void embeddedJavaImportOptimizerDoesNotAddUnneededImport() {
        // Java file has THREE imports; the embedded markup only uses StackPane (layout.*).
        // control.* and beans.* are not referenced in the markup.
        getFixture().configureByText("OptiView3.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                import javafx.beans.property.*;
                @ComponentView(\"""
                    <StackPane/>
                    \""")
                public class OptiView3 {
                    protected void initializeComponent() {}
                    public OptiView3() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        var optimizer = new Fxml2EmbeddedJavaImportOptimizer();
        PsiJavaFile javaFile = (PsiJavaFile) getFixture().getFile();

        // Read phase.
        Runnable task = ReadAction.compute(() -> optimizer.processFile(javaFile));

        // Simulate Java optimizer removing ALL three imports.
        WriteCommandAction.runWriteCommandAction(getFixture().getProject(), () -> {
            PsiImportList importList = javaFile.getImportList();
            if (importList != null) {
                for (PsiImportStatement imp : importList.getImportStatements().clone()) {
                    String qn = imp.getQualifiedName();
                    if ("javafx.scene.control".equals(qn) || "javafx.beans.property".equals(qn)
                            || "javafx.scene.layout".equals(qn)) {
                        imp.delete();
                    }
                }
            }
        });

        // Write phase.
        WriteCommandAction.runWriteCommandAction(getFixture().getProject(), task);

        String text = ReadAction.compute(() -> getFixture().getFile().getText());

        // layout.* must be restored (StackPane is used).
        assertTrue(text.contains("javafx.scene.layout"),
                "Optimizer must restore layout.* (used in embedded FXML).\nActual:\n" + text);

        // control.* must NOT be restored (not used in embedded markup).
        assertFalse(text.contains("javafx.scene.control"),
                "Optimizer must NOT restore control.* (unused in embedded FXML).\nActual:\n" + text);

        // beans.* must NOT be restored (not used in embedded markup).
        assertFalse(text.contains("javafx.beans.property"),
                "Optimizer must NOT restore beans.* (unused in embedded FXML).\nActual:\n" + text);
    }

    /**
     * {@link Fxml2EmbeddedJavaImportOptimizer} must add missing imports even when the
     * import list was already stripped <em>before</em> the read phase runs, and the
     * injected XmlFile IS available (injection was computed, e.g. via doHighlighting).
     *
     * <p>This exercises the {@code xmlFile != null} branch of the fallback strategy, where
     * {@code collectUsedSimpleNames(xmlFile)} is used with {@link com.intellij.psi.search.PsiShortNamesCache}
     * as the class-resolution fallback.
     */
    @Test
    void embeddedJavaImportOptimizerFallbackWhenImportsPreStripped() {
        // Java file whose FXML-needed imports have already been removed before the read phase.
        getFixture().configureByText("CopiedView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                @ComponentView(\"""
                    <StackPane>
                      <Button text="hello"/>
                    </StackPane>
                    \""")
                public class CopiedView {
                    protected void initializeComponent() {}
                    public CopiedView() { initializeComponent(); }
                }
                """);

        // Ensure injection is computed so the XML PSI is available.
        getFixture().doHighlighting();

        var optimizer = new Fxml2EmbeddedJavaImportOptimizer();
        PsiJavaFile javaFile = (PsiJavaFile) getFixture().getFile();

        // Read phase with imports already stripped (fallback must kick in).
        Runnable task = ReadAction.compute(() -> optimizer.processFile(javaFile));

        // Write phase: the fallback Runnable should add the missing imports.
        WriteCommandAction.runWriteCommandAction(getFixture().getProject(), task);

        String text = ReadAction.compute(() -> getFixture().getFile().getText());

        // Both StackPane (layout) and Button (control) must be imported.
        assertTrue(text.contains("javafx.scene.layout"),
                "Fallback must add a javafx.scene.layout import for StackPane.\nActual:\n" + text);
        assertTrue(text.contains("javafx.scene.control"),
                "Fallback must add a javafx.scene.control import for Button.\nActual:\n" + text);
    }

    /**
     * {@link Fxml2EmbeddedJavaImportOptimizer} must add missing imports even when the
     * import list was already stripped AND the injected XmlFile has NOT been computed yet.
     *
     * <p>This is the exact copy (F5) flow: {@code CopyClassesHandler} calls
     * {@code removeRedundantImports()} before {@code OptimizeImportsProcessor}, and the
     * newly-copied file has never been opened so language injection has never been computed.
     * The fallback must parse the raw {@code @ComponentView} annotation string value directly
     * with a regex to find class names, then resolve them via
     * {@link com.intellij.psi.search.PsiShortNamesCache}.
     */
    @Test
    void embeddedJavaImportOptimizerFallbackWithoutInjection() {
        // Java file whose FXML-needed imports have been removed AND injection not triggered.
        getFixture().configureByText("CopiedView2.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                @ComponentView(\"""
                    <StackPane>
                      <Button text="hello"/>
                    </StackPane>
                    \""")
                public class CopiedView2 {
                    protected void initializeComponent() {}
                    public CopiedView2() { initializeComponent(); }
                }
                """);

        // Deliberately do NOT call doHighlighting(): injection must not have been computed.

        var optimizer = new Fxml2EmbeddedJavaImportOptimizer();
        PsiJavaFile javaFile = (PsiJavaFile) getFixture().getFile();

        // Read phase: xmlFile will be null -> annotation-string parsing fallback must kick in.
        Runnable task = ReadAction.compute(() -> optimizer.processFile(javaFile));

        // Write phase: imports must be added via the annotation-string-parsing fallback.
        WriteCommandAction.runWriteCommandAction(getFixture().getProject(), task);

        String text = ReadAction.compute(() -> getFixture().getFile().getText());

        // Both StackPane (layout) and Button (control) must be imported.
        assertTrue(text.contains("javafx.scene.layout"),
                "Copy-flow fallback must add javafx.scene.layout import for StackPane.\nActual:\n" + text);
        assertTrue(text.contains("javafx.scene.control"),
                "Copy-flow fallback must add javafx.scene.control import for Button.\nActual:\n" + text);
    }

    // -----------------------------------------------------------------------
    // ImplicitUsageProvider: no false-positive "Field 'X' is never used"
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2EmbeddedImplicitUsageProvider} must recognize a field as implicitly
     * used when the field's containing class carries a {@code @ComponentView} annotation
     * and the embedded FXML markup references the field in a binding expression
     * (e.g. {@code {fx:Observe vm.text}}).
     *
     * <p>IntelliJ's unused-field analysis uses a word-index search that never reaches
     * injected language fragments.  Without this provider, binding-segment references
     * inside embedded FXML are invisible to the analysis and the field is falsely
     * reported as "never used" even though Ctrl+click correctly navigates to its usages.
     */
    @Test
    void implicitUsageProviderSuppressesUnusedFieldWarningForEmbeddedBinding() {
        getFixture().addClass("""
                package test;
                public class ImplicitVm {
                    public String text = "hello";
                }
                """);

        getFixture().configureByText("ImplicitView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <Label text="{fx:Observe vm.text}"/>
                    </StackPane>
                    \""")
                public class ImplicitView {
                    public ImplicitVm vm = new ImplicitVm();
                    protected void initializeComponent() {}
                    public ImplicitView() { initializeComponent(); }
                }
                """);

        // Run the daemon so that the language injection is computed.
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass cls = findMarkupClass();
            assertNotNull(cls, "Must find @ComponentView class");
            PsiField vmField = cls.findFieldByName("vm", false);
            assertNotNull(vmField, "Must find field 'vm'");

            var provider = new Fxml2EmbeddedImplicitUsageProvider();

            assertTrue(provider.isImplicitUsage(vmField),
                    "isImplicitUsage must return true for a field referenced in embedded FXML binding");
            assertTrue(provider.isImplicitRead(vmField),
                    "isImplicitRead must return true for a field referenced in embedded FXML binding");
        });
    }

    /**
     * {@link Fxml2EmbeddedImplicitUsageProvider} must NOT suppress the unused-field
     * warning for a field that is defined in an {@code @ComponentView} class but is
     * <em>not</em> referenced anywhere in the embedded FXML markup.
     */
    @Test
    void implicitUsageProviderDoesNotSuppressGenuinelyUnusedField() {
        getFixture().addClass("""
                package test;
                public class UnusedVm {
                    public String text = "hello";
                }
                """);

        getFixture().configureByText("UnusedFieldView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane/>
                    \""")
                public class UnusedFieldView {
                    public UnusedVm neverReferenced = new UnusedVm();
                    protected void initializeComponent() {}
                    public UnusedFieldView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass cls = findMarkupClass();
            assertNotNull(cls, "Must find @ComponentView class");
            PsiField field = cls.findFieldByName("neverReferenced", false);
            assertNotNull(field, "Must find field 'neverReferenced'");

            var provider = new Fxml2EmbeddedImplicitUsageProvider();

            assertFalse(provider.isImplicitUsage(field),
                    "isImplicitUsage must return false for a field NOT referenced in embedded FXML");
            assertFalse(provider.isImplicitRead(field),
                    "isImplicitRead must return false for a field NOT referenced in embedded FXML");
        });
    }

    // -----------------------------------------------------------------------
    // ImplicitUsageProvider: package-private static fields
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2EmbeddedImplicitUsageProvider} must recognize a package-private static field
     * as implicitly used when the embedded FXML markup references it in a binding expression
     * (e.g. {@code $staticText}).
     *
     * <p>The field must not be flagged as "never used" even when it has package-private
     * visibility and no explicit Java-side references.
     */
    @Test
    void implicitUsageProviderSuppressesUnusedWarningForPackagePrivateStaticField() {
        getFixture().configureByText("StaticFieldView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <Label text="$staticText"/>
                    </StackPane>
                    \""")
                public class StaticFieldView {
                    static final String staticText = "hello";
                    protected void initializeComponent() {}
                    public StaticFieldView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass cls = findMarkupClass();
            assertNotNull(cls, "Must find @ComponentView class");
            PsiField field = cls.findFieldByName("staticText", false);
            assertNotNull(field, "Must find field 'staticText'");

            var provider = new Fxml2EmbeddedImplicitUsageProvider();

            assertTrue(provider.isImplicitUsage(field),
                    "isImplicitUsage must return true for a package-private static field referenced in embedded FXML binding");
            assertTrue(provider.isImplicitRead(field),
                    "isImplicitRead must return true for a package-private static field referenced in embedded FXML binding");
        });
    }

    // -----------------------------------------------------------------------
    // ImplicitUsageProvider: property-accessor methods (vmProperty() pattern)
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2EmbeddedImplicitUsageProvider} must recognize a property-accessor method
     * (the {@code fooProperty()} convention) as implicitly used when the embedded FXML markup
     * references the property via its short name in a binding expression.
     *
     * <p>Example: a private {@code StringProperty message} with public {@code messageProperty()},
     * and embedded markup {@code {fx:Observe message}}.  The binding segment {@code "message"}
     * resolves to {@code messageProperty()}, not to the private field.  Without this fix the
     * public {@code messageProperty()} method would be falsely reported as
     * "Method is never used".
     */
    @Test
    void implicitUsageProviderSuppressesUnusedMethodWarningForPropertyAccessor() {
        getFixture().configureByText("MethodImplicitView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.beans.property.*;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <Label text="{fx:Observe message}"/>
                    </StackPane>
                    \""")
                public class MethodImplicitView {
                    private final StringProperty message = new SimpleStringProperty("hello");
                    public StringProperty messageProperty() { return message; }
                    public String getMessage() { return message.get(); }
                    public void setMessage(String v) { message.set(v); }
                    protected void initializeComponent() {}
                    public MethodImplicitView() { initializeComponent(); }
                }
                """);

        // Trigger language injection so the injected XML is available to the provider.
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass cls = findMarkupClass();
            assertNotNull(cls, "Must find @ComponentView class");
            PsiMethod[] methods = cls.findMethodsByName("messageProperty", false);
            assertTrue(methods.length > 0, "Must find method 'messageProperty'");
            PsiMethod messagePropertyMethod = methods[0];

            var provider = new Fxml2EmbeddedImplicitUsageProvider();

            assertTrue(provider.isImplicitUsage(messagePropertyMethod),
                    "isImplicitUsage must return true for messageProperty() referenced as "
                    + "'message' in embedded FXML binding");
            assertTrue(provider.isImplicitRead(messagePropertyMethod),
                    "isImplicitRead must return true for messageProperty() referenced as "
                    + "'message' in embedded FXML binding");
        });
    }

    /**
     * {@link Fxml2EmbeddedImplicitUsageProvider} must NOT suppress the unused-method
     * warning for a method in an {@code @ComponentView} class that is not referenced
     * anywhere in the embedded FXML markup.
     */
    @Test
    void implicitUsageProviderDoesNotSuppressGenuinelyUnusedMethod() {
        getFixture().configureByText("UnusedMethodView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.beans.property.*;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane/>
                    \""")
                public class UnusedMethodView {
                    private final StringProperty unused = new SimpleStringProperty("hello");
                    public StringProperty unusedProperty() { return unused; }
                    protected void initializeComponent() {}
                    public UnusedMethodView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass cls = findMarkupClass();
            assertNotNull(cls, "Must find @ComponentView class");
            PsiMethod[] methods = cls.findMethodsByName("unusedProperty", false);
            assertTrue(methods.length > 0, "Must find method 'unusedProperty'");
            PsiMethod unusedPropertyMethod = methods[0];

            var provider = new Fxml2EmbeddedImplicitUsageProvider();

            assertFalse(provider.isImplicitUsage(unusedPropertyMethod),
                    "isImplicitUsage must return false for a method NOT referenced in embedded FXML");
            assertFalse(provider.isImplicitRead(unusedPropertyMethod),
                    "isImplicitRead must return false for a method NOT referenced in embedded FXML");
        });
    }

    // -----------------------------------------------------------------------
    // ImplicitUsageProvider: view-model members referenced via multi-segment
    //         binding path (e.g. model.displayText) must not be reported as unused
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2EmbeddedImplicitUsageProvider} must recognize a field in a
     * <em>non-{@code @ComponentView}</em> class (e.g. a view model) as implicitly used
     * when it is referenced from another class's embedded FXML markup via a multi-segment
     * binding path such as {@code {fx:Observe model.displayText}}.
     *
     * <p>The annotated view class carries {@code @ComponentView} and has a field
     * {@code public ViewModel model}. {@code ViewModel.displayText} is bound in the markup
     * as {@code {fx:Observe model.displayText}}.
     * Without the cross-class search path, {@code displayText} would be falsely reported
     * as "Field 'displayText' is never used".
     */
    @Test
    void implicitUsageProviderSuppressesUnusedWarningForViewModelField() {
        // The view-model class: no @ComponentView, just a plain POJO with a field.
        getFixture().addClass("""
                package test;
                public class LabelViewModel {
                    public String displayText = "initial";
                }
                """);

        // The view class: carries @ComponentView and references model.displayText.
        getFixture().configureByText("LabelView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <Label text="{fx:Observe model.displayText}"/>
                    </StackPane>
                    \""")
                public class LabelView {
                    public LabelViewModel model = new LabelViewModel();
                    protected void initializeComponent() {}
                    public LabelView() { initializeComponent(); }
                }
                """);

        // Trigger injection so the embedded XML is available to the provider.
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            // Obtain the view-model class from the project.
            var vmCls = com.intellij.psi.JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.LabelViewModel",
                            GlobalSearchScope.projectScope(getFixture().getProject()));
            assertNotNull(vmCls, "Must find LabelViewModel class");

            PsiField displayTextField = vmCls.findFieldByName("displayText", false);
            assertNotNull(displayTextField, "Must find field 'displayText' on LabelViewModel");

            var provider = new Fxml2EmbeddedImplicitUsageProvider();

            assertTrue(provider.isImplicitUsage(displayTextField),
                    "isImplicitUsage must return true for a view-model field bound via "
                    + "model.displayText in another class's embedded FXML markup");
            assertTrue(provider.isImplicitRead(displayTextField),
                    "isImplicitRead must return true for a view-model field bound via "
                    + "model.displayText in another class's embedded FXML markup");
        });
    }

    /**
     * {@link ReferencesSearch} (global scope) on a field in a non-{@code @ComponentView}
     * class must find the binding-path segment in another class's embedded FXML markup.
     *
     * <p>This is the "Find Usages" direction: performing "Find Usages" on
     * {@code MainViewModel.labelText} must discover {@code {fx:Observe vm.labelText}} in
     * {@code MainView}'s embedded markup.
     */
    @Test
    void findUsagesOnViewModelFieldFindsEmbeddedMarkupBinding() {
        getFixture().addClass("""
                package test;
                public class FindVm {
                    public String vmLabel = "hello";
                }
                """);

        getFixture().configureByText("FindVmView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <Label text="{fx:Observe vm.vmLabel}"/>
                    </StackPane>
                    \""")
                public class FindVmView {
                    public FindVm vm = new FindVm();
                    protected void initializeComponent() {}
                    public FindVmView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            var vmCls = com.intellij.psi.JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.FindVm",
                            GlobalSearchScope.projectScope(getFixture().getProject()));
            assertNotNull(vmCls, "Must find FindVm class");

            PsiField vmLabelField = vmCls.findFieldByName("vmLabel", false);
            assertNotNull(vmLabelField, "Must find field 'vmLabel' on FindVm");

            var refs = ReferencesSearch.search(
                            vmLabelField,
                            GlobalSearchScope.projectScope(getFixture().getProject()))
                    .findAll();

            boolean foundInEmbedded = refs.stream().anyMatch(
                    ref -> ref instanceof Fxml2BindingSegmentReference
                           && ref.getElement().getContainingFile() instanceof XmlFile xmlFile
                           && Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile));
            assertTrue(foundInEmbedded,
                    "ReferencesSearch on FindVm.vmLabel must find the binding segment "
                    + "'vmLabel' in the embedded FXML markup of FindVmView.\n"
                    + "Found refs: " + refs.stream()
                            .map(r -> r.getClass().getSimpleName()
                                      + " in " + r.getElement().getContainingFile().getName())
                            .toList());
        });
    }

    // -----------------------------------------------------------------------
    // Fxml2BindingSegmentSearcher: Find Usages on property accessor
    //        finds binding segments in embedded FXML markup
    // -----------------------------------------------------------------------

    /**
     * {@link ReferencesSearch} on a property-accessor method (e.g. {@code messageProperty()})
     * must find the binding-path segment {@code "message"} in the embedded FXML markup via
     * {@link org.jfxcore.fxml.lang.Fxml2BindingSegmentSearcher}'s global-search path.
     *
     * <p>The word-based {@code CachesBasedRefSearcher} cannot find these usages because the
     * binding segment text (e.g. {@code "message"}) differs from the method name
     * (e.g. {@code "messageProperty"}) and the markup lives in an injected fragment that
     * is not indexed.
     */
    @Test
    void findUsagesOnPropertyAccessorMethodFindsEmbeddedMarkupBinding() {
        getFixture().configureByText("FindAccessorView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.beans.property.*;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <Label text="{fx:Observe message}"/>
                    </StackPane>
                    \""")
                public class FindAccessorView {
                    private final StringProperty message = new SimpleStringProperty("hello");
                    public StringProperty messageProperty() { return message; }
                    protected void initializeComponent() {}
                    public FindAccessorView() { initializeComponent(); }
                }
                """);

        // Ensure the injection is computed.
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass cls = findMarkupClass();
            assertNotNull(cls, "Must find @ComponentView class");
            PsiMethod[] methods = cls.findMethodsByName("messageProperty", false);
            assertTrue(methods.length > 0, "Must find method 'messageProperty'");
            PsiMethod messagePropertyMethod = methods[0];

            var refs = ReferencesSearch.search(
                            messagePropertyMethod,
                            GlobalSearchScope.projectScope(getFixture().getProject()))
                    .findAll();

            boolean foundInEmbedded = refs.stream().anyMatch(
                    ref -> ref instanceof Fxml2BindingSegmentReference
                           && ref.getElement().getContainingFile() instanceof XmlFile xmlFile
                           && Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile));
            assertTrue(foundInEmbedded,
                    "ReferencesSearch on messageProperty() must find the 'message' binding "
                    + "segment in embedded FXML markup.\nFound refs: "
                    + refs.stream()
                            .map(r -> r.getClass().getSimpleName()
                                      + " in " + r.getElement().getContainingFile().getName())
                            .toList());
        });
    }

    // -----------------------------------------------------------------------
    // fxml <?import?> inside embedded FXML
    // -----------------------------------------------------------------------

    /**
     * An embedded FXML markup may contain {@code <?import?>} processing instructions in
     * addition to (or instead of) Java imports. The tag must resolve correctly when only a
     * fxml {@code <?import?>} PI covers the class: no Java import for that class.
     *
     * <p>{@link org.jfxcore.fxml.resolve.Fxml2ImportResolver#parseImports} must scan
     * both the XML prolog (synthesised Java imports) and any user-written
     * {@code <?import?>} processing instructions placed inside the wrapper root of the
     * injected document.
     */
    @Test
    void fxmlImportInsideEmbeddedMarkupResolvesTag() {
        // Java file has NO import for javafx.scene.control.Button.
        // The embedded markup supplies it via <?import?>.
        getFixture().configureByText("FxmlImportView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <?import javafx.scene.control.Button?>
                    <StackPane>
                      <Button text="hello"/>
                    </StackPane>
                    \""")
                public class FxmlImportView {
                    protected void initializeComponent() {}
                    public FxmlImportView() { initializeComponent(); }
                }
                """);

        // No "cannot resolve" errors expected: Button is covered by the fxml import.
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Mixed case: both Java imports and fxml {@code <?import?>} PIs are present. Each
     * covers a different class; both classes must resolve correctly.
     */
    @Test
    void fxmlImportAndJavaImportMixedCaseBothResolve() {
        // Java file imports layout.* (covers StackPane).
        // Embedded markup additionally imports control.Button via <?import?>.
        getFixture().configureByText("MixedImportView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <?import javafx.scene.control.Button?>
                    <StackPane>
                      <Button text="hello"/>
                    </StackPane>
                    \""")
                public class MixedImportView {
                    protected void initializeComponent() {}
                    public MixedImportView() { initializeComponent(); }
                }
                """);

        // Both StackPane (Java import) and Button (fxml import) must resolve.
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A fxml {@code <?import?>} PI inside embedded markup that covers only a wildcard
     * package must resolve all classes in that package.
     */
    @Test
    void fxmlWildcardImportInsideEmbeddedMarkupResolvesTag() {
        getFixture().configureByText("FxmlWildcardImportView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <?import javafx.scene.control.*?>
                    <StackPane>
                      <Button text="hello"/>
                      <Label text="world"/>
                    </StackPane>
                    \""")
                public class FxmlWildcardImportView {
                    protected void initializeComponent() {}
                    public FxmlWildcardImportView() { initializeComponent(); }
                }
                """);

        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * An unused fxml {@code <?import?>} PI inside embedded FXML must be flagged by
     * {@link Fxml2UnusedImportsInspection}, just as in a standalone FXML file.
     *
     * <p>The synthesised Java-import PIs in the prolog must NOT be flagged (they are not
     * user-editable).
     */
    @Test
    void unusedFxmlImportInsideEmbeddedMarkupIsFlagged() {
        getFixture().enableInspections(new Fxml2UnusedImportsInspection());

        // fxml import for Button is present but Button is never used in the markup.
        getFixture().configureByText("UnusedFxmlImportView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <?import javafx.scene.control.Button?>
                    <StackPane/>
                    \""")
                public class UnusedFxmlImportView {
                    protected void initializeComponent() {}
                    public UnusedFxmlImportView() { initializeComponent(); }
                }
                """);

        var warnings = getFixture().doHighlighting(HighlightSeverity.WARNING);
        boolean hasUnused = warnings.stream().anyMatch(
                h -> h.getDescription() != null
                     && h.getDescription().contains("Unused import"));
        assertTrue(hasUnused,
                "Expected 'Unused import' warning for <?import javafx.scene.control.Button?> "
                + "inside embedded FXML when Button is not used in the markup.\n"
                + "Warnings: " + warnings.stream()
                        .filter(Objects::nonNull)
                        .map(HighlightInfo::getDescription)
                        .toList());
    }

    /**
     * A used fxml {@code <?import?>} PI inside embedded FXML must NOT be flagged as unused.
     */
    @Test
    void usedFxmlImportInsideEmbeddedMarkupIsNotFlagged() {
        getFixture().enableInspections(new Fxml2UnusedImportsInspection());

        getFixture().configureByText("UsedFxmlImportView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <?import javafx.scene.control.Button?>
                    <StackPane>
                      <Button text="hello"/>
                    </StackPane>
                    \""")
                public class UsedFxmlImportView {
                    protected void initializeComponent() {}
                    public UsedFxmlImportView() { initializeComponent(); }
                }
                """);

        // The Button fxml import must NOT be reported as unused.
        var warnings = getFixture().doHighlighting(HighlightSeverity.WARNING);
        boolean hasUnused = warnings.stream().anyMatch(
                h -> h.getDescription() != null
                     && h.getDescription().contains("Unused import"));
        assertFalse(hasUnused,
                "Expected NO 'Unused import' warning for <?import javafx.scene.control.Button?> "
                + "inside embedded FXML when Button is used in the markup.\n"
                + "Warnings: " + warnings.stream()
                        .filter(Objects::nonNull)
                        .map(HighlightInfo::getDescription)
                        .toList());
    }

    /**
     * Verifies that {@link org.jfxcore.fxml.resolve.Fxml2ImportResolver#parseImports} returns
     * the merged list of Java imports (prolog) and user-written fxml imports (inside wrapper
     * root) for an embedded FXML file.
     */
    @Test
    void parseImportsReturnsMergedImportsForEmbeddedFxml() {
        getFixture().configureByText("MergedImportsView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <?import javafx.scene.control.Button?>
                    <StackPane/>
                    \""")
                public class MergedImportsView {
                    protected void initializeComponent() {}
                    public MergedImportsView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = getInjectedXmlFile();
            assertNotNull(xmlFile, "Injected XmlFile must be present");

            var imports = org.jfxcore.fxml.resolve.Fxml2ImportResolver.parseImports(xmlFile);
            assertFalse(imports.isEmpty(), "parseImports must return non-empty imports");

            // Java wildcard import must be present (from host Java file)
            assertTrue(imports.contains("javafx.scene.layout.*"),
                    "parseImports must contain Java wildcard import 'javafx.scene.layout.*'. "
                    + "Got: " + imports);

            // fxml exact import must also be present (user-written inside wrapper root)
            assertTrue(imports.contains("javafx.scene.control.Button"),
                    "parseImports must contain fxml import 'javafx.scene.control.Button' from "
                    + "inside the embedded markup. Got: " + imports);
        });
    }

    // -----------------------------------------------------------------------
    // Fxml2BindingSegmentSearcher: identifier-under-caret highlighting
    //        from a Java declaration site finds binding segments in embedded markup
    // -----------------------------------------------------------------------

    /**
     * When the cursor is on a property-accessor method in the host Java file
     * (e.g. {@code messageProperty()} in a {@code @ComponentView} class), identifier
     * highlighting must also light up the corresponding binding-path segments in the
     * embedded FXML markup (e.g. the {@code "message"} segment in
     * {@code {fx:Observe message}}).
     *
     * <p>This is verified by calling {@link ReferencesSearch} with a
     * {@link LocalSearchScope} that contains only the host Java file: the same scope
     * IntelliJ's {@code IdentifierHighlighterPass} uses.  Without the
     * {@code walkEmbeddedXmlInHostFile} code path the binding segments would never be
     * found, because:
     * <ul>
     *   <li>The word-based {@code CachesBasedRefSearcher} looks for the literal word
     *       {@code "messageProperty"} and finds nothing in the XML.</li>
     *   <li>The injected XML document is not included in the {@code LocalSearchScope}
     *       constructed by the identifier highlighter for the host Java file.</li>
     * </ul>
     */
    @Test
    void identifierHighlightingFromJavaDeclarationFindsEmbeddedBindingSegments() {
        getFixture().configureByText("HighlightAccessorView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.beans.property.*;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <Label text="{fx:Observe message}"/>
                    </StackPane>
                    \""")
                public class HighlightAccessorView {
                    private final StringProperty message = new SimpleStringProperty("hello");
                    public StringProperty messageProperty() { return message; }
                    protected void initializeComponent() {}
                    public HighlightAccessorView() { initializeComponent(); }
                }
                """);

        // Ensure the injection is computed.
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass cls = findMarkupClass();
            assertNotNull(cls, "Must find @ComponentView class");
            PsiMethod[] methods = cls.findMethodsByName("messageProperty", false);
            assertTrue(methods.length > 0, "Must find method 'messageProperty'");
            PsiMethod messagePropertyMethod = methods[0];

            // Simulate what IdentifierHighlighterPass does: LocalSearchScope(host Java file only).
            var refs = ReferencesSearch.search(
                            messagePropertyMethod,
                            new LocalSearchScope(getFixture().getFile()))
                    .findAll();

            boolean foundInEmbedded = refs.stream().anyMatch(
                    ref -> ref instanceof Fxml2BindingSegmentReference
                           && ref.getElement().getContainingFile() instanceof XmlFile xmlFile
                           && Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile));
            assertTrue(foundInEmbedded,
                    "ReferencesSearch with LocalSearchScope(host Java file) on messageProperty() "
                    + "must find the 'message' binding segment in the embedded FXML markup "
                    + "(identifier-under-caret highlighting from Java declaration site).\n"
                    + "Found refs: "
                     + refs.stream()
                             .map(r -> r.getClass().getSimpleName()
                                       + " in " + r.getElement().getContainingFile().getName())
                             .toList());
        });
    }

    // -----------------------------------------------------------------------
    // ImplicitUsageProvider + ReferencesSearch: class/constructor used as tag
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2EmbeddedImplicitUsageProvider} must recognize a {@link PsiClass} as
     * implicitly used when the embedded FXML markup uses it as an XML element tag
     * (e.g. {@code <test.CustomLabel/>}).
     *
     * <p>A class that has no explicit Java-side usages but is referenced as an XML element
     * tag in embedded FXML markup must not be flagged as "never used".
     */
    @Test
    void implicitUsageProviderSuppressesUnusedWarningForClassUsedAsTag() {
        getFixture().addClass("""
                package test;
                import javafx.scene.control.Label;
                public class CustomLabel extends Label {
                    public CustomLabel() {}
                }
                """);

        getFixture().configureByText("TagView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane>
                      <test.CustomLabel/>
                    </StackPane>
                    \""")
                public class TagView {
                    protected void initializeComponent() {}
                    public TagView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass customLabel = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.CustomLabel",
                            GlobalSearchScope.projectScope(getFixture().getProject()));
            assertNotNull(customLabel, "Must find class CustomLabel");

            var provider = new Fxml2EmbeddedImplicitUsageProvider();

            assertTrue(provider.isImplicitUsage(customLabel),
                    "isImplicitUsage must return true for a class used as XML element tag "
                    + "in embedded FXML markup");
            assertTrue(provider.isImplicitRead(customLabel),
                    "isImplicitRead must return true for a class used as XML element tag "
                    + "in embedded FXML markup");
        });
    }

    /**
     * {@link Fxml2EmbeddedImplicitUsageProvider} must recognize the no-arg constructor of a
     * class as implicitly used when the class is used as an XML element tag in embedded FXML.
     *
     * <p>When a class is instantiated via an XML element tag in embedded FXML, its no-arg
     * constructor must not be flagged as "never used" even if it has no explicit Java-side
     * callers.
     */
    @Test
    void implicitUsageProviderSuppressesUnusedWarningForConstructorOfClassUsedAsTag() {
        getFixture().addClass("""
                package test;
                import javafx.scene.control.Label;
                public class CtorLabel extends Label {
                    public CtorLabel() {}
                }
                """);

        getFixture().configureByText("CtorTagView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane>
                      <test.CtorLabel/>
                    </StackPane>
                    \""")
                public class CtorTagView {
                    protected void initializeComponent() {}
                    public CtorTagView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass ctorLabel = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.CtorLabel",
                            GlobalSearchScope.projectScope(getFixture().getProject()));
            assertNotNull(ctorLabel, "Must find class CtorLabel");

            com.intellij.psi.PsiMethod[] ctors = ctorLabel.getConstructors();
            assertTrue(ctors.length > 0, "Must find constructor of CtorLabel");
            com.intellij.psi.PsiMethod ctor = ctors[0];

            var provider = new Fxml2EmbeddedImplicitUsageProvider();

            assertTrue(provider.isImplicitUsage(ctor),
                    "isImplicitUsage must return true for a constructor whose class is used "
                    + "as XML element tag in embedded FXML markup");
            assertTrue(provider.isImplicitRead(ctor),
                    "isImplicitRead must return true for a constructor whose class is used "
                    + "as XML element tag in embedded FXML markup");
        });
    }

    // -----------------------------------------------------------------------
    // 21: ImplicitUsageProvider + ReferencesSearch: class/constructor used as markup extension
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2EmbeddedImplicitUsageProvider} must recognize a {@link PsiClass} as
     * implicitly used when embedded FXML markup uses it as a markup extension in an
     * attribute value (e.g. {@code text="{MyExt value=foo}"}).
     *
     * <p>A markup extension class that has no explicit Java-side instantiation but is
     * referenced as {@code {ClassName ...}} in embedded FXML must not be flagged as
     * "never used".
     */
    @Test
    void implicitUsageProviderSuppressesUnusedWarningForMarkupExtensionClass() {
        getFixture().addClass("""
                package test;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                public class SimpleExt implements MarkupExtension.Supplier<String> {
                    @Override public String get(MarkupContext ctx) { return "x"; }
                }
                """);

        getFixture().configureByText("ExtView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                import test.SimpleExt;
                @ComponentView(\"""
                    <StackPane>
                      <Label text="{SimpleExt}"/>
                    </StackPane>
                    \""")
                public class ExtView {
                    protected void initializeComponent() {}
                    public ExtView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass simpleExt = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.SimpleExt",
                            GlobalSearchScope.projectScope(getFixture().getProject()));
            assertNotNull(simpleExt, "Must find class SimpleExt");

            var provider = new Fxml2EmbeddedImplicitUsageProvider();

            assertTrue(provider.isImplicitUsage(simpleExt),
                    "isImplicitUsage must return true for a class used as markup extension "
                    + "in embedded FXML attribute value");
            assertTrue(provider.isImplicitRead(simpleExt),
                    "isImplicitRead must return true for a class used as markup extension "
                    + "in embedded FXML attribute value");
        });
    }

    /**
     * {@link Fxml2EmbeddedImplicitUsageProvider} must recognize the constructor of a
     * markup extension class as implicitly used when embedded FXML markup invokes that
     * class via markup extension attribute notation.
     *
     * <p>The FXML compiler instantiates the markup extension class via its constructor;
     * the constructor must not be flagged as "never used" even if no Java code explicitly
     * calls it.
     */
    @Test
    void implicitUsageProviderSuppressesUnusedWarningForMarkupExtensionConstructor() {
        getFixture().addClass("""
                package test;
                import javafx.beans.NamedArg;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                public class ParamExt implements MarkupExtension.Supplier<String> {
                    private final String value;
                    public ParamExt(@NamedArg("value") String value) { this.value = value; }
                    @Override public String get(MarkupContext ctx) { return value; }
                }
                """);

        getFixture().configureByText("ParamExtView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                import test.ParamExt;
                @ComponentView(\"""
                    <StackPane>
                      <Label text="{ParamExt value=hello}"/>
                    </StackPane>
                    \""")
                public class ParamExtView {
                    protected void initializeComponent() {}
                    public ParamExtView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass paramExt = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.ParamExt",
                            GlobalSearchScope.projectScope(getFixture().getProject()));
            assertNotNull(paramExt, "Must find class ParamExt");

            com.intellij.psi.PsiMethod[] ctors = paramExt.getConstructors();
            assertTrue(ctors.length > 0, "Must find constructor of ParamExt");
            com.intellij.psi.PsiMethod ctor = ctors[0];

            var provider = new Fxml2EmbeddedImplicitUsageProvider();

            assertTrue(provider.isImplicitUsage(ctor),
                    "isImplicitUsage must return true for a constructor whose class is used "
                    + "as a markup extension in embedded FXML");
            assertTrue(provider.isImplicitRead(ctor),
                    "isImplicitRead must return true for a constructor whose class is used "
                    + "as a markup extension in embedded FXML");
        });
    }

    /**
     * {@link org.jfxcore.fxml.lang.Fxml2EmbeddedClassTagSearcher} must report a reference
     * from the embedded FXML attribute value back to the markup extension class when "Find
     * Usages" is invoked on the class.
     *
     * <p>A markup extension class used solely via {@code {ClassName ...}} in another class's
     * embedded FXML markup must appear as a usage when "Find Usages" is invoked on the class.
     */
    @Test
    void embeddedClassTagSearcherFindsUsageOfMarkupExtensionClass() {
        getFixture().addClass("""
                package test;
                import javafx.beans.NamedArg;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                public class FindExt implements MarkupExtension.Supplier<String> {
                    private final String value;
                    public FindExt(@NamedArg("value") String value) { this.value = value; }
                    @Override public String get(MarkupContext ctx) { return value; }
                }
                """);

        getFixture().configureByText("FindExtView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                import test.FindExt;
                @ComponentView(\"""
                    <StackPane>
                      <Label text="{FindExt value=bar}"/>
                    </StackPane>
                    \""")
                public class FindExtView {
                    protected void initializeComponent() {}
                    public FindExtView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass findExt = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.FindExt",
                            GlobalSearchScope.projectScope(getFixture().getProject()));
            assertNotNull(findExt, "Must find class FindExt");

            var refs = ReferencesSearch.search(
                            findExt,
                            GlobalSearchScope.projectScope(getFixture().getProject()))
                    .findAll();

            boolean foundInEmbedded = refs.stream().anyMatch(
                    ref -> ref.getElement().getContainingFile() instanceof XmlFile xmlFile
                           && Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile));

            assertTrue(foundInEmbedded,
                    "ReferencesSearch on FindExt must find a usage in the embedded FXML "
                    + "attribute value (markup extension notation).\n"
                    + "Found refs: "
                    + refs.stream()
                            .map(r -> r.getClass().getSimpleName()
                                      + " in " + r.getElement().getContainingFile().getName())
                            .toList());
        });
    }

    /**
     * {@link org.jfxcore.fxml.lang.Fxml2EmbeddedClassTagSearcher} must report a
     * {@code PsiReference} from the embedded FXML element tag back to the target
     * {@link PsiClass} when "Find Usages" is invoked on the class.
     *
     * <p>A class used solely as an element tag in another class's embedded FXML markup
     * must appear as a usage when "Find Usages" is invoked on that class.
     */
    @Test
    void embeddedClassTagSearcherFindsUsageOfClassUsedAsTag() {
        getFixture().addClass("""
                package test;
                import javafx.scene.control.Label;
                public class SearchLabel extends Label {
                    public SearchLabel() {}
                }
                """);

        getFixture().configureByText("SearchTagView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane>
                      <test.SearchLabel/>
                    </StackPane>
                    \""")
                public class SearchTagView {
                    protected void initializeComponent() {}
                    public SearchTagView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass searchLabel = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.SearchLabel",
                            GlobalSearchScope.projectScope(getFixture().getProject()));
            assertNotNull(searchLabel, "Must find class SearchLabel");

            var refs = ReferencesSearch.search(
                            searchLabel,
                            GlobalSearchScope.projectScope(getFixture().getProject()))
                    .findAll();

            boolean foundInEmbedded = refs.stream().anyMatch(
                    ref -> ref.getElement().getContainingFile() instanceof XmlFile xmlFile
                           && Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile));

            assertTrue(foundInEmbedded,
                    "ReferencesSearch on SearchLabel must find a usage in the embedded FXML tag.\n"
                    + "Found refs: "
                    + refs.stream()
                            .map(r -> r.getClass().getSimpleName()
                                      + " in " + r.getElement().getContainingFile().getName())
                            .toList());
        });
    }

    /**
     * {@link org.jfxcore.fxml.lang.Fxml2EmbeddedClassTagSearcher} must report a
     * {@code PsiReference} when "Find Usages" is invoked on a <b>constructor</b> of a class
     * used as a markup extension in embedded FXML markup.
     *
     * <p>When a class is instantiated via a markup extension expression
     * (e.g. {@code {ExtClass value=foo}}) in a {@code @ComponentView} text-block, its
     * constructor is implicitly called by the FXML runtime.  Invoking "Find Usages"
     * on that constructor must therefore discover the embedded markup as a usage site.
     */
    @Test
    void embeddedClassTagSearcherFindsUsageOfMarkupExtensionConstructor() {
        getFixture().addClass("""
                package test;
                import javafx.beans.NamedArg;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                public class CtorExt implements MarkupExtension.Supplier<String> {
                    private final String value;
                    public CtorExt(@NamedArg("value") String value) { this.value = value; }
                    @Override public String get(MarkupContext ctx) { return value; }
                }
                """);

        getFixture().configureByText("CtorExtView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                import test.CtorExt;
                @ComponentView(\"""
                    <StackPane>
                      <Label text="{CtorExt value=bar}"/>
                    </StackPane>
                    \""")
                public class CtorExtView {
                    protected void initializeComponent() {}
                    public CtorExtView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass ctorExt = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.CtorExt",
                            GlobalSearchScope.projectScope(getFixture().getProject()));
            assertNotNull(ctorExt, "Must find class CtorExt");

            PsiMethod constructor = ctorExt.getConstructors()[0];

            var refs = ReferencesSearch.search(
                            constructor,
                            GlobalSearchScope.projectScope(getFixture().getProject()))
                    .findAll();

            boolean foundInEmbedded = refs.stream().anyMatch(
                    ref -> ref.getElement().getContainingFile() instanceof XmlFile xmlFile
                           && Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile));

            assertTrue(foundInEmbedded,
                    "ReferencesSearch on CtorExt constructor must find a usage in the embedded "
                    + "FXML attribute value (markup extension notation).\n"
                    + "Found refs: "
                    + refs.stream()
                            .map(r -> r.getClass().getSimpleName()
                                      + " in " + r.getElement().getContainingFile().getName())
                            .toList());
        });
    }

    /**
     * When "Find Usages" is invoked on a constructor in the IDE, IntelliJ routes the
     * search through {@link MethodReferencesSearch}, not {@link ReferencesSearch}.
     * {@link org.jfxcore.fxml.lang.Fxml2EmbeddedClassTagSearcher} handles only
     * {@link ReferencesSearch}, so without a dedicated {@link MethodReferencesSearch}
     * extension, embedded element-tag usages of the class are not found when "Find Usages"
     * is invoked on the constructor.
     *
     * <p>This test verifies that {@code MethodReferencesSearch} on the constructor of a
     * class used as an XML element tag in embedded FXML finds the tag as a usage site.
     */
    @Test
    void findUsagesViaMethodSearchOnConstructorFindsEmbeddedElementTag() {
        getFixture().addClass("""
                package test;
                import javafx.scene.control.Label;
                public class TagSearchLabel extends Label {
                    public TagSearchLabel() {}
                }
                """);

        getFixture().configureByText("TagSearchView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane>
                      <test.TagSearchLabel/>
                    </StackPane>
                    \""")
                public class TagSearchView {
                    protected void initializeComponent() {}
                    public TagSearchView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass tagSearchLabel = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.TagSearchLabel",
                            GlobalSearchScope.projectScope(getFixture().getProject()));
            assertNotNull(tagSearchLabel, "Must find class TagSearchLabel");

            PsiMethod ctor = tagSearchLabel.getConstructors()[0];

            var refs = MethodReferencesSearch.search(
                            ctor,
                            GlobalSearchScope.projectScope(getFixture().getProject()),
                            /* checkAccessScope= */ true)
                    .findAll();

            boolean foundInEmbedded = refs.stream().anyMatch(
                    ref -> ref.getElement().getContainingFile() instanceof XmlFile xmlFile
                           && Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile));
            assertTrue(foundInEmbedded,
                    "MethodReferencesSearch on TagSearchLabel() must find the embedded "
                    + "<test.TagSearchLabel/> element tag.\nFound refs: "
                    + refs.stream()
                            .map(r -> r.getClass().getSimpleName()
                                      + " in " + r.getElement().getContainingFile().getName())
                            .toList());
        });
    }

    /**
     * When "Find Usages" is invoked on a markup extension constructor in the IDE, IntelliJ
     * routes the search through {@link MethodReferencesSearch}, not {@link ReferencesSearch}.
     * Without a dedicated {@link MethodReferencesSearch} extension, embedded markup extension
     * usages are not found when "Find Usages" is invoked on the constructor.
     *
     * <p>This test verifies that {@code MethodReferencesSearch} on the constructor of a
     * markup extension class finds the {@code {ClassName ...}} attribute value as a usage site.
     */
    @Test
    void findUsagesViaMethodSearchOnConstructorFindsEmbeddedMarkupExtension() {
        getFixture().addClass("""
                package test;
                import javafx.beans.NamedArg;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                public class ExtSearchExt implements MarkupExtension.Supplier<String> {
                    private final String value;
                    public ExtSearchExt(@NamedArg("value") String value) { this.value = value; }
                    @Override public String get(MarkupContext ctx) { return value; }
                }
                """);

        getFixture().configureByText("ExtSearchView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                import test.ExtSearchExt;
                @ComponentView(\"""
                    <StackPane>
                      <Label text="{ExtSearchExt value=hello}"/>
                    </StackPane>
                    \""")
                public class ExtSearchView {
                    protected void initializeComponent() {}
                    public ExtSearchView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass extSearchExt = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.ExtSearchExt",
                            GlobalSearchScope.projectScope(getFixture().getProject()));
            assertNotNull(extSearchExt, "Must find class ExtSearchExt");

            PsiMethod ctor = extSearchExt.getConstructors()[0];

            var refs = MethodReferencesSearch.search(
                            ctor,
                            GlobalSearchScope.projectScope(getFixture().getProject()),
                            /* checkAccessScope= */ true)
                    .findAll();

            boolean foundInEmbedded = refs.stream().anyMatch(
                    ref -> ref.getElement().getContainingFile() instanceof XmlFile xmlFile
                           && Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile));
            assertTrue(foundInEmbedded,
                    "MethodReferencesSearch on ExtSearchExt constructor must find the embedded "
                    + "{ExtSearchExt value=hello} markup extension.\nFound refs: "
                    + refs.stream()
                            .map(r -> r.getClass().getSimpleName()
                                      + " in " + r.getElement().getContainingFile().getName())
                            .toList());
        });
    }
}
