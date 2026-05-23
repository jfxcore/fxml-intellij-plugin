package org.jfxcore.fxml;

import com.intellij.codeInsight.navigation.impl.GTDActionData;
import com.intellij.codeInsight.navigation.impl.NavigationActionResult;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.annotator.Fxml2StyleClassInspection;
import org.jfxcore.fxml.lang.CssSelectorElement;
import org.jfxcore.fxml.lang.Fxml2StyleClassReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Collection;
import java.util.List;

import static com.intellij.codeInsight.navigation.impl.GtdKt.gotoDeclaration;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CSS class-selector navigation and the unresolved styleClass inspection.
 */
@SuppressWarnings("UnstableApiUsage")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2StyleClassTest extends Fxml2TestBase {

    private static final String CSS = """
            .mystyle1 {
              -fx-stroke: black;
            }
            .elevated-1 {
              -fx-border-width: 1;
            }
            """;

    /** CSS mimicking AtlantaFX: .accent appears in multiple compound selectors. */
    private static final String CSS_MULTI_COMPOUND = """
            .text.accent {
              -fx-fill: blue;
            }
            .label.accent {
              -fx-text-fill: blue;
            }
            .notification.accent {
              -fx-border-color: blue;
            }
            """;

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

    @BeforeEach
    void setup() {
        getFixture().enableInspections(new Fxml2StyleClassInspection());
    }

    // -----------------------------------------------------------------------
    // Navigation (reference resolution)
    // -----------------------------------------------------------------------

    @Test
    void knownStyleClassResolvesToCssSelectorElement() {
        getFixture().addFileToProject("style.css", CSS);
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label styleClass="myst<caret>yle1"/>
                """
        ));
        PsiElement resolved = resolveStyleClassAtCaret();
        assertNotNull(resolved, "mystyle1 should resolve to a CssSelectorElement");
        assertInstanceOf(CssSelectorElement.class, resolved);
        assertEquals("mystyle1", ((CssSelectorElement) resolved).getName());
    }

    @Test
    void secondTokenInCommaListResolvesToCssSelectorElement() {
        getFixture().addFileToProject("style.css", CSS);
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label styleClass="mystyle1, elevat<caret>ed-1"/>
                """
        ));
        PsiElement resolved = resolveStyleClassAtCaret();
        assertNotNull(resolved, "elevated-1 should resolve to a CssSelectorElement");
        assertInstanceOf(CssSelectorElement.class, resolved);
        assertEquals("elevated-1", ((CssSelectorElement) resolved).getName());
    }

    @Test
    void unknownStyleClassResolvesToNull() {
        getFixture().addFileToProject("style.css", CSS);
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label styleClass="does-not-<caret>exist"/>
                """
        ));
        PsiElement resolved = resolveStyleClassAtCaret();
        assertNull(resolved, "does-not-exist should not resolve when not in any CSS file");
    }

    @Test
    void bindingExpressionIsNotResolved() {
        getFixture().addFileToProject("style.css", CSS);
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label styleClass="${vm.cssClass}"/>
                """
        ));
        ReadAction.run(() -> {
            XmlAttributeValue attrVal = PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), getFixture().getCaretOffset(), XmlAttributeValue.class, false);
            if (attrVal == null) return;
            for (PsiReference ref : attrVal.getReferences()) {
                assertFalse(ref instanceof Fxml2StyleClassReference,
                        "No Fxml2StyleClassReference expected for binding expression");
            }
        });
    }

    // -----------------------------------------------------------------------
    // Selector scoring: prefer compound selector matching the tag type
    // -----------------------------------------------------------------------

    @Test
    void accentOnLabelPrefersLabelCompoundSelector() {
        getFixture().addFileToProject("theme.css", CSS_MULTI_COMPOUND);
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label styleClass="acc<caret>ent"/>
                """
        ));
        PsiElement resolved = resolveStyleClassAtCaret();
        assertNotNull(resolved);
        assertInstanceOf(CssSelectorElement.class, resolved);
        String fileText = resolved.getContainingFile().getText();
        int offset = resolved.getTextOffset();
        int lineEnd = fileText.indexOf('\n', offset);
        if (lineEnd < 0) lineEnd = fileText.length();
        String line = fileText.substring(fileText.lastIndexOf('\n', offset - 1) + 1, lineEnd);
        assertTrue(line.contains(".label.accent"),
                "Expected .label.accent for a Label tag, but got line: " + line);
    }

    // -----------------------------------------------------------------------
    // Multi-file: polyvariant reference returns all matching CSS files
    // -----------------------------------------------------------------------

    @Test
    void multipleFilesProduceMultipleResolveResults() {
        getFixture().addFileToProject("style-a.css", ".mystyle1 { -fx-fill: red; }");
        getFixture().addFileToProject("style-b.css", ".mystyle1 { -fx-fill: blue; }");
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label styleClass="myst<caret>yle1"/>
                """
        ));
        ResolveResult[] results = multiResolveAtCaret();
        assertTrue(results.length >= 2,
                "Expected >=2 resolve results for a class in two CSS files, got " + results.length);
        for (ResolveResult r : results) {
            assertInstanceOf(CssSelectorElement.class, r.getElement());
        }
    }

    // -----------------------------------------------------------------------
    // Hover range: the highlight must cover ONLY the hovered class name,
    // not the entire "accent, elevated-1" token.
    // This is verified via declaredReferencedData which is the same path
    // IntelliJ's CtrlMouseHandler uses.
    // -----------------------------------------------------------------------

    @Test
    void hoverHighlightCoversOnlyTheHoveredToken() {
        getFixture().addFileToProject("style.css", CSS);
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label styleClass="myst<caret>yle1, elevated-1"/>
                """
        ));
        TextRange highlightRange = ReadAction.compute(() -> {
            int offset = getFixture().getCaretOffset();
            GTDActionData data = gotoDeclaration(getFixture().getFile(), offset);
            if (data == null) return null;
            var ctrlMouseData = data.ctrlMouseData();
            if (ctrlMouseData == null) return null;
            List<TextRange> ranges = ctrlMouseData.getRanges();
            return ranges.isEmpty() ? null : ranges.getFirst();
        });
        assertNotNull(highlightRange, "Expected a highlight range");
        int length = highlightRange.getLength();
        assertEquals(8, length,
                "Hover highlight should cover only 'mystyle1' (8 chars), but covered " + length + " chars: " + highlightRange);
    }

    @Test
    void hoverHighlightCoversSecondTokenWhenCaretIsOnIt() {
        getFixture().addFileToProject("style.css", CSS);
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label styleClass="mystyle1, elev<caret>ated-1"/>
                """
        ));
        TextRange highlightRange = ReadAction.compute(() -> {
            int offset = getFixture().getCaretOffset();
            GTDActionData data = gotoDeclaration(getFixture().getFile(), offset);
            if (data == null) return null;
            var ctrlMouseData = data.ctrlMouseData();
            if (ctrlMouseData == null) return null;
            List<TextRange> ranges = ctrlMouseData.getRanges();
            return ranges.isEmpty() ? null : ranges.getFirst();
        });
        assertNotNull(highlightRange, "Expected a highlight range for elevated-1");
        assertEquals(10, highlightRange.getLength(),
                "Hover highlight should cover only 'elevated-1' (10 chars), but covered " + highlightRange.getLength());
    }

    // -----------------------------------------------------------------------
    // Multi-file navigation produces MultipleTargets (chooser popup)
    // -----------------------------------------------------------------------

    @Test
    void multiFileNavigationProducesMultipleTargets() {
        getFixture().addFileToProject("style-a.css", ".mystyle1 { -fx-fill: red; }");
        getFixture().addFileToProject("style-b.css", ".mystyle1 { -fx-fill: blue; }");
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label styleClass="myst<caret>yle1"/>
                """
        ));
        NavigationActionResult result = ReadAction.compute(() -> {
            int offset = getFixture().getCaretOffset();
            GTDActionData data = gotoDeclaration(getFixture().getFile(), offset);
            if (data == null) return null;
            return data.result();
        });
        assertNotNull(result, "Expected navigation result for mystyle1 in two files");
        assertInstanceOf(NavigationActionResult.MultipleTargets.class, result,
                "Expected MultipleTargets chooser for two CSS files, got: " + result);
    }

    // -----------------------------------------------------------------------
    // Inspection (unresolved warning)
    // -----------------------------------------------------------------------

    @Test
    void unknownStyleClassProducesWarning() {
        getFixture().addFileToProject("style.css", CSS);
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label styleClass="<warning descr="CSS class 'no-such-class' cannot be resolved">no-such-class</warning>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    @Test
    void knownStyleClassProducesNoWarning() {
        getFixture().addFileToProject("style.css", CSS);
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label styleClass="mystyle1"/>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    @Test
    void onlyUnknownTokenInListProducesWarning() {
        getFixture().addFileToProject("style.css", CSS);
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label styleClass="mystyle1, <warning descr="CSS class 'ghost' cannot be resolved">ghost</warning>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    @Test
    void noCssFilesProducesWarningForEveryToken() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label styleClass="<warning descr="CSS class 'mystyle1' cannot be resolved">mystyle1</warning>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    // -----------------------------------------------------------------------
    // Find Usages from CSS -> fxml2 (reverse direction)
    // -----------------------------------------------------------------------

    /**
     * Find Usages on a {@link CssSelectorElement} must find the matching
     * {@code styleClass} attribute value in a standalone FXML2 file.
     */
    @Test
    void findUsagesFromCssFindsStandaloneFxml2Usage() {
        getFixture().addFileToProject("style.css", CSS);
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label styleClass="myst<caret>yle1"/>
                """
        ));

        // Resolve to the CssSelectorElement
        CssSelectorElement cssSel = ReadAction.compute(() -> {
            PsiElement el = resolveStyleClassAtCaret();
            return el instanceof CssSelectorElement c ? c : null;
        });
        assertNotNull(cssSel, "mystyle1 should resolve to a CssSelectorElement");

        // Find Usages in project scope: should find the styleClass reference in TestView.fxml
        Collection<PsiReference> refs = ReadAction.compute(() ->
                ReferencesSearch.search(cssSel,
                        GlobalSearchScope.projectScope(getFixture().getProject())).findAll());

        assertTrue(refs.stream().anyMatch(r -> r instanceof Fxml2StyleClassReference),
                "Expected at least one Fxml2StyleClassReference in Find Usages results.\n"
                + "Found " + refs.size() + " refs of types: "
                + refs.stream().map(r -> r.getClass().getSimpleName()).toList());
    }

    /**
     * Find Usages on a {@link CssSelectorElement} must find the matching
     * {@code styleClass} attribute value in embedded FXML2 (&#64;ComponentView annotation).
     */
    @Test
    void findUsagesFromCssFindsEmbeddedFxml2Usage() {
        getFixture().addFileToProject("style.css", CSS);
        // Standalone fxml2 resolves the CssSelectorElement via caret resolution
        getFixture().configureByText("AnchorView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label styleClass="myst<caret>yle1"/>
                """
        ));

        CssSelectorElement cssSel = ReadAction.compute(() -> {
            PsiElement el = resolveStyleClassAtCaret();
            return el instanceof CssSelectorElement c ? c : null;
        });
        assertNotNull(cssSel);

        // Add a class with embedded fxml2 that also uses the same style class
        getFixture().addClass("""
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <Label styleClass="mystyle1"/>
                \""")
                public class EmbeddedStyleView extends javafx.scene.layout.StackPane {
                    protected void initializeComponent() {}
                    public EmbeddedStyleView() { initializeComponent(); }
                }
                """);

        Collection<PsiReference> refs = ReadAction.compute(() ->
                ReferencesSearch.search(cssSel,
                        GlobalSearchScope.projectScope(getFixture().getProject())).findAll());

        boolean foundEmbedded = refs.stream().anyMatch(r ->
                r instanceof Fxml2StyleClassReference
                && ReadAction.compute(() -> r.getElement().getContainingFile().getName()).endsWith(".java"));
        assertTrue(foundEmbedded,
                "Expected Fxml2StyleClassReference in embedded fxml2 (Java file). Found "
                + refs.size() + " refs of types: "
                + refs.stream().map(r -> r.getClass().getSimpleName()).toList());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private @Nullable PsiElement resolveStyleClassAtCaret() {
        return ReadAction.compute(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue attrVal = PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            if (attrVal == null) return null;
            int relOffset = offset - attrVal.getTextRange().getStartOffset();
            for (PsiReference ref : attrVal.getReferences()) {
                if (!(ref instanceof Fxml2StyleClassReference)) continue;
                if (ref.getRangeInElement().containsOffset(relOffset)) {
                    return ref.resolve();
                }
            }
            return null;
        });
    }

    private ResolveResult[] multiResolveAtCaret() {
        return ReadAction.compute(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue attrVal = PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            if (attrVal == null) return ResolveResult.EMPTY_ARRAY;
            int relOffset = offset - attrVal.getTextRange().getStartOffset();
            for (PsiReference ref : attrVal.getReferences()) {
                if (!(ref instanceof Fxml2StyleClassReference svr)) continue;
                if (ref.getRangeInElement().containsOffset(relOffset)) {
                    return svr.multiResolve(false);
                }
            }
            return ResolveResult.EMPTY_ARRAY;
        });
    }
}
