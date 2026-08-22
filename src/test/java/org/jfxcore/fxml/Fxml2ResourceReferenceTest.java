// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.testFramework.EdtTestUtil;
import org.jfxcore.fxml.lang.Fxml2ResourceDeclarationElement;
import org.jfxcore.fxml.lang.Fxml2ResourceNameReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

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
}
