// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationOrUsageHandler2;
import com.intellij.find.usages.api.SearchTarget;
import com.intellij.find.usages.api.UsageSearchParameters;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.testFramework.EdtTestUtil;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.lang.Fxml2NamespaceUrlReference;
import org.jfxcore.fxml.lang.Fxml2ResourceDeclarationElement;
import org.jfxcore.fxml.lang.Fxml2ResourceDeclarationProvider;
import org.jfxcore.fxml.lang.Fxml2ResourceFindUsagesHandlerFactory;
import org.jfxcore.fxml.lang.Fxml2ResourceHighlightUsagesHandlerFactory;
import org.jfxcore.fxml.lang.Fxml2ResourceNameReference;
import org.jfxcore.fxml.lang.Fxml2ResourceUsageSearcher;
import org.jfxcore.fxml.resource.Fxml2ResourceEntry;
import org.jfxcore.fxml.resource.Fxml2ResourceModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that an {@code @name} or {@code {ClassPathResource name}} usage resolves to the
 * {@code <?resource ?>} declaration that declares it.
 *
 * <p>Resolution follows the runtime's lookup order: an embedded resource first, an external file
 * second.  What is tested here is the first half, including the cases where embedded lookup is not
 * performed at all.
 *
 * <p>Implementation under test: {@link Fxml2ResourceNameReference} and
 * {@link Fxml2ResourceDeclarationElement}.
 */
@SuppressWarnings({"SameParameterValue", "UnstableApiUsage"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class Fxml2ResourceReferenceTest extends Fxml2TestBase {

    /** The markup extension the {@code @} prefix notation is shorthand for. */
    @BeforeAll
    void addClassPathResource() {
        getFixture().addClass("""
                package org.jfxcore.markup.resource;
                import javafx.beans.DefaultProperty;
                import javafx.beans.NamedArg;
                @DefaultProperty("value")
                public final class ClassPathResource {
                    public ClassPathResource(@NamedArg("value") String value) {}
                }
                """);
        getFixture().addClass("""
                package org.jfxcore.markup.resource;
                import javafx.beans.DefaultProperty;
                import javafx.beans.NamedArg;
                @DefaultProperty("key")
                public final class StaticResource {
                    public StaticResource(@NamedArg("key") String key,
                                          @NamedArg("formatArguments") Object... arguments) {}
                }
                """);
    }

    /** The prefix notation resolves to the declaration of the named resource. */
    @Test
    void prefixNotationResolvesToTheDeclaration() {
        configure("""
                <?resource styles.css text/css:.root { -fx-base: black; }?>
                """, """
                  <BorderPane stylesheets="@sty<caret>les.css"/>
                """);

        assertEquals("styles.css", resolvedResourceName());
    }

    /** The markup extension notation resolves the same way the prefix notation does. */
    @Test
    void markupExtensionNotationResolvesToTheDeclaration() {
        configure("""
                <?resource styles.css text/css:.root { -fx-base: black; }?>
                """, """
                  <BorderPane stylesheets="{ClassPathResource sty<caret>les.css}"/>
                """);

        assertEquals("styles.css", resolvedResourceName());
    }

    /** A name declared with quotes resolves from a quoted usage, interior spaces included. */
    @Test
    void quotedNameResolvesFromAQuotedUsage() {
        configure("""
                <?resource "dark theme.css" text/css:.root { -fx-base: black; }?>
                """, """
                  <BorderPane stylesheets="@'dark th<caret>eme.css'"/>
                """);

        assertEquals("dark theme.css", resolvedResourceName());
    }

    /** Matching is exact in case: the runtime would not resolve a differently cased name either. */
    @Test
    void nameMatchingIsCaseSensitive() {
        configure("""
                <?resource Styles.css text/css:.root { -fx-base: black; }?>
                """, """
                  <BorderPane stylesheets="@sty<caret>les.css"/>
                """);

        assertNull(embeddedReferenceAtCaret(), "a differently cased name does not name this resource");
    }

    /** A name containing a path separator can only be external, so embedded lookup is skipped. */
    @Test
    void nameWithAPathSeparatorIsNotResolvedAsEmbedded() {
        configure("""
                <?resource styles.css text/css:.root { -fx-base: black; }?>
                """, """
                  <BorderPane stylesheets="@theme/sty<caret>les.css"/>
                """);

        assertNull(embeddedReferenceAtCaret());
    }

    /** An absolute name is resolved against the class loader, never as an embedded resource. */
    @Test
    void absoluteNameIsNotResolvedAsEmbedded() {
        configure("""
                <?resource styles.css text/css:.root { -fx-base: black; }?>
                """, """
                  <BorderPane stylesheets="@/sty<caret>les.css"/>
                """);

        assertNull(embeddedReferenceAtCaret());
    }

    /** Renaming the declaration rewrites every usage of the old name. */
    @Test
    void renamingTheDeclarationUpdatesUsages() {
        configure("""
                <?resource styles.css text/css:.root { -fx-base: black; }?>
                """, """
                  <BorderPane stylesheets="@sty<caret>les.css"/>
                """);

        renameAtCaret("theme.css");

        String text = ReadAction.compute(() -> getFixture().getFile().getText());
        assertTrue(text.contains("<?resource theme.css text/css:"), "the declaration is renamed: " + text);
        assertTrue(text.contains("stylesheets=\"@theme.css\""), "the usage is renamed: " + text);
    }

    /** Renaming to a name that needs quotes writes the quotes on both sides. */
    @Test
    void renamingToANameWithSpacesAddsQuoting() {
        configure("""
                <?resource styles.css text/css:.root { -fx-base: black; }?>
                """, """
                  <BorderPane stylesheets="@sty<caret>les.css"/>
                """);

        renameAtCaret("dark theme.css");

        String text = ReadAction.compute(() -> getFixture().getFile().getText());
        assertTrue(text.contains("<?resource \"dark theme.css\" text/css:"),
                "the declaration is quoted: " + text);
        assertTrue(text.contains("stylesheets=\"@'dark theme.css'\""), "the usage is quoted: " + text);
    }

    /** Find Usages on a declaration discovers every resource reference in its document. */
    @Test
    void referencesSearchFromDeclarationFindsPrefixAndLongFormUsages() {
        configure("""
                <?resource styles.css text/css:.root { -fx-base: black; }?>
                """, """
                  <BorderPane stylesheets="@styles.css">
                    <BorderPane stylesheets="{ClassPathResource styles.css}"/>
                  </BorderPane>
                """);

        ReadAction.run(() -> {
            Fxml2ResourceNameReference usage = findResourceReferences().getFirst();
            PsiElement declaration = usage.resolve();
            assertNotNull(declaration);
            var references = ReferencesSearch.search(
                    declaration, new LocalSearchScope(getFixture().getFile())).findAll();

            assertEquals(2, references.stream()
                    .filter(Fxml2ResourceNameReference.class::isInstance)
                    .count());
        });
    }

    /** The declaration token is a valid starting point for the Find Usages action. */
    @Test
    void findUsagesStartsAtTheDeclarationName() {
        configure("""
                <?resource sty<caret>les.css text/css:.root { -fx-base: black; }?>
                """, """
                  <BorderPane stylesheets="@styles.css"/>
                """);

        ReadAction.run(() -> {
            PsiElement leaf = getFixture().getFile().findElementAt(getFixture().getCaretOffset());
            assertNotNull(leaf);
            Fxml2ResourceFindUsagesHandlerFactory factory =
                    new Fxml2ResourceFindUsagesHandlerFactory();
            assertTrue(factory.canFindUsages(leaf));
            var handler = factory.createFindUsagesHandler(leaf, false);
            assertNotNull(handler);
            assertInstanceOf(Fxml2ResourceDeclarationElement.class,
                    handler.getPsiElement());
        });
    }

    /** Identifier highlighting links a declaration name with its use site in either direction. */
    @Test
    void identifierHighlightingLinksDeclarationAndUsage() {
        assertResourceIdentifierHighlights("styles.css", """
                <?resource sty<caret>les.css text/css:.root { -fx-base: black; }?>
                """);
        assertResourceIdentifierHighlights("styles.css", """
                <?resource styles.css text/css:.root { -fx-base: black; }?>
                """);
    }

    /** Find Usages on a declaration reaches a usage written as a nested format argument. */
    @Test
    void referencesSearchFromDeclarationFindsNestedFormatArgumentUsage() {
        configure("""
                <?resource fallback.txt:Hello from an embedded resource?>
                """, """
                  <BorderPane accessibleText="%greeting; formatArguments=Jane, Doe, @fallback.txt"/>
                """);

        ReadAction.run(() -> {
            var references = ReferencesSearch.search(
                    declarationElement("fallback.txt"),
                    new LocalSearchScope(getFixture().getFile())).findAll();

            assertEquals(1, references.stream()
                    .filter(Fxml2ResourceNameReference.class::isInstance)
                    .count(), "the nested format argument is found as a usage");
        });
    }

    /**
     * The declaration name is a symbol declaration, which is what turns the Ctrl+click gesture
     * into Show Usages instead of leaving it with nowhere to go.
     */
    @Test
    void declarationNameIsRegisteredAsSymbolDeclaration() {
        configure("""
                <?resource sty<caret>les.css text/css:.root { -fx-base: black; }?>
                """, """
                  <BorderPane stylesheets="@styles.css"/>
                """);

        ReadAction.run(() -> {
            PsiElement leaf = getFixture().getFile().findElementAt(getFixture().getCaretOffset());
            assertNotNull(leaf);
            int offsetInElement = getFixture().getCaretOffset() - leaf.getTextRange().getStartOffset();

            var declarations = new Fxml2ResourceDeclarationProvider()
                    .getDeclarations(leaf, offsetInElement);
            assertEquals(1, declarations.size());
            var declaration = declarations.iterator().next();
            assertSame(leaf, declaration.getDeclaringElement());
            assertNotNull(declaration.getSymbol());
        });

        assertShowsUsages();
    }

    /**
     * The declaration provider is asked about every element around the cursor, including those
     * that lie after the name or do not contain it at all, and answers each without failing.
     */
    @Test
    void declarationProviderToleratesEveryElementOfTheDocument() {
        configure("""
                <?resource styles.css text/css:.root { -fx-base: black; }?>
                <?resource fallback.txt:Hello from an embedded resource?>
                """, """
                  <BorderPane stylesheets="@styles.css"/>
                """);

        ReadAction.run(() -> {
            var provider = new Fxml2ResourceDeclarationProvider();
            PsiFile file = getFixture().getFile();
            int declaringElements = 0;

            for (int offset = 0; offset < file.getTextLength(); offset++) {
                PsiElement leaf = file.findElementAt(offset);
                if (leaf == null) continue;

                for (PsiElement element = leaf; element != null && element != file;
                     element = element.getParent()) {
                    declaringElements += provider.getDeclarations(
                            element, offset - element.getTextRange().getStartOffset()).size();
                }
            }

            assertTrue(declaringElements > 0, "the names are still reported as declarations");
        });
    }

    /** The gesture behaves the same on a declaration whose media type is implied. */
    @Test
    void gestureOnAPlainTextDeclarationShowsUsages() {
        configure("""
                <?resource fall<caret>back.txt:Hello from an embedded resource?>
                """, """
                  <BorderPane accessibleText="%greeting; formatArguments=Jane, Doe, @fallback.txt"/>
                """);

        assertShowsUsages();
    }

    /**
     * The Ctrl+click gesture on either declaration of a two-resource document reaches its use
     * sites, whether the use site is a stylesheet reference or a nested format argument.
     */
    @Test
    void declarationNamesReachTheirUseSites() {
        String prolog = """
                <?resource styles.css text/css:.my-style { -fx-text-fill: darkorange; }?>
                <?resource fallback.txt:Hello from an embedded resource?>
                """;
        String body = """
                  <BorderPane stylesheets="@styles.css"
                              accessibleText="%greeting; formatArguments=Jane, Doe, @fallback.txt"/>
                """;

        assertEquals(1, useSiteCountFromDeclarationOf("styles.css", prolog, body));
        assertEquals(1, useSiteCountFromDeclarationOf("fallback.txt", prolog, body));
    }

    /** A resource used as a nested format argument has the same navigation and highlighting. */
    @Test
    void nestedFormatArgumentLinksToItsResourceDeclaration() {
        configure("""
                <?resource fallback.txt:Hello from an embedded resource?>
                """, """
                  <BorderPane accessibleText="%greeting; formatArguments=Jane, Doe, @fall<caret>back.txt"/>
                """);

        assertEquals("fallback.txt", resolvedResourceName());
        assertResourceHighlightsAtCurrentCaret("fallback.txt");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * A synthetic element, such as the navigation target a documentation link resolves to, reports
     * a containing file but occupies no range in it.  The platform offers such an element to both
     * the declaration provider and the find-usages factory, and neither may fail on it.
     */
    @Test
    void syntheticElementWithoutRange_declaresNothing() {
        configure("""
                <?resource styles.css text/css:
                    .root { -fx-font-size: 1.1em; }
                ?>
                """, "");

        ReadAction.run(() -> {
            PsiFile file = getFixture().getFile();
            PsiElement synthetic =
                    new Fxml2NamespaceUrlReference.UrlNavigationTarget(file, "https://example.invalid/");

            assertTrue(new Fxml2ResourceDeclarationProvider().getDeclarations(synthetic, 0).isEmpty(),
                    "a synthetic element declares no resource name");
            assertFalse(new Fxml2ResourceFindUsagesHandlerFactory().canFindUsages(synthetic),
                    "a synthetic element starts no resource find-usages request");
        });
    }

    /**
     * Runs the platform rename action on the element at the caret.
     *
     * <p>Renaming needs write-intent read access, which the JUnit test worker thread does not
     * have; running it on the EDT grants it.
     */
    private void renameAtCaret(String newName) {
        EdtTestUtil.runInEdtAndWait(() -> getFixture().renameElementAtCaret(newName));
    }

    private void configure(String prolog, String body) {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                %s<BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0">
                %s</BorderPane>
                """.formatted(prolog, body));
    }

    /** Returns the name of the declaration the reference at the caret resolves to. */
    private String resolvedResourceName() {
        return ReadAction.compute(() -> {
            Fxml2ResourceNameReference reference = embeddedReferenceAtCaret();
            assertNotNull(reference, "the usage carries an embedded resource reference");

            PsiElement target = reference.resolve();
            return assertInstanceOf(Fxml2ResourceDeclarationElement.class, target).getName();
        });
    }

    /** Returns the embedded resource reference under the caret, or {@code null} when there is none. */
    private Fxml2ResourceNameReference embeddedReferenceAtCaret() {
        return ReadAction.compute(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue value = PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            if (value == null) return null;

            int relative = offset - value.getTextRange().getStartOffset();
            for (PsiReference reference : value.getReferences()) {
                if (reference instanceof Fxml2ResourceNameReference resourceReference
                        && reference.getRangeInElement().containsOffset(relative)) {
                    return resourceReference;
                }
            }
            return null;
        });
    }

    /** Asserts that the Ctrl+click gesture at the caret lists use sites rather than navigating. */
    private void assertShowsUsages() {
        assertEquals(
                GotoDeclarationOrUsageHandler2.GTDUOutcome.SU,
                ReadAction.compute(() ->
                        GotoDeclarationOrUsageHandler2.testGTDUOutcome(
                                getFixture().getEditor(), getFixture().getFile(), getFixture().getCaretOffset())),
                "Ctrl+click on a resource declaration shows its usages");
    }

    /**
     * Returns the number of use sites Ctrl+click on the declaration of {@code name} would list.
     *
     * <p>The declaration is looked up the way the platform does it, through the symbol declaration
     * at the offset of the name, so that a gap in the declaration provider fails this assertion.
     */
    private long useSiteCountFromDeclarationOf(String name, String prolog, String body) {
        configure(prolog, body);

        return ReadAction.compute(() -> {
            int offset = ReadAction.compute(() -> declarationElement(name).getTextOffset());
            PsiElement leaf = getFixture().getFile().findElementAt(offset);
            assertNotNull(leaf);

            var declarations = new Fxml2ResourceDeclarationProvider()
                    .getDeclarations(leaf, offset - leaf.getTextRange().getStartOffset());
            assertEquals(1, declarations.size(), "'" + name + "' is a declaration site");

            var symbol = (SearchTarget)declarations.iterator().next().getSymbol();
            return new Fxml2ResourceUsageSearcher()
                    .collectImmediateResults(new TestUsageSearchParameters(
                            symbol, getFixture().getProject(),
                            new LocalSearchScope(getFixture().getFile())))
                    .size();
        });
    }

    /** Returns the declaration element of the named embedded resource in the configured file. */
    private Fxml2ResourceDeclarationElement declarationElement(String name) {
        Fxml2ResourceEntry entry = Fxml2ResourceModel
                .of((XmlFile)getFixture().getFile()).resolve(name);
        assertNotNull(entry, "the document declares '" + name + "'");
        return new Fxml2ResourceDeclarationElement(entry);
    }

    private java.util.List<Fxml2ResourceNameReference> findResourceReferences() {
        return ReadAction.compute(() -> PsiTreeUtil.findChildrenOfType(
                        getFixture().getFile(), XmlAttributeValue.class).stream()
                .flatMap(value -> java.util.Arrays.stream(value.getReferences()))
                .filter(Fxml2ResourceNameReference.class::isInstance)
                .map(Fxml2ResourceNameReference.class::cast)
                .toList());
    }

    private void assertResourceIdentifierHighlights(String expectedName, String prolog) {
        String body = prolog.contains("<caret>")
                ? "  <BorderPane stylesheets=\"@styles.css\"/>\n"
                : "  <BorderPane stylesheets=\"@sty<caret>les.css\"/>\n";
        configure(prolog, body);
        assertResourceHighlightsAtCurrentCaret(expectedName);
    }

    private void assertResourceHighlightsAtCurrentCaret(String expectedName) {
        getFixture().doHighlighting();
        java.util.List<String> highlighted = ReadAction.compute(() -> {
            var handler = new Fxml2ResourceHighlightUsagesHandlerFactory()
                    .createHighlightUsagesHandler(getFixture().getEditor(), getFixture().getFile());
            assertNotNull(handler);
            computeUsages(handler);
            return handler.getReadUsages().stream()
                    .map(range -> getFixture().getEditor().getDocument().getText(range))
                    .toList();
        });
        assertEquals(java.util.List.of(expectedName, expectedName), highlighted);
    }

    private static <T extends PsiElement> void computeUsages(HighlightUsagesHandlerBase<T> handler) {
        handler.computeUsages(handler.getTargets());
    }

    /** The search parameters the platform would build for a symbol-native Show Usages request. */
    @SuppressWarnings("UnstableApiUsage")
    private record TestUsageSearchParameters(@NotNull SearchTarget target,
                                             @NotNull Project project,
                                             @NotNull SearchScope searchScope)
            implements UsageSearchParameters {

        @Override
        public @NotNull SearchTarget getTarget() { return target; }

        @Override
        public @NotNull Project getProject() { return project; }

        @Override
        public @NotNull SearchScope getSearchScope() { return searchScope; }

        @Override
        public boolean areValid() { return true; }
    }
}
