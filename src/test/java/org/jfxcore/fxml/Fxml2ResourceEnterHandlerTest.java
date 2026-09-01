// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.application.options.CodeStyle;
import com.intellij.lang.Language;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies that pressing Enter inside the CSS payload of a {@code <?resource ?>} declaration
 * continues the indentation of the stylesheet fragment instead of falling back to the
 * indentation the enclosing FXML document would produce.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class Fxml2ResourceEnterHandlerTest extends Fxml2TestBase {

    /** The step the JSON payloads below nest in, deliberately wider than the markup step. */
    private static final int JSON_INDENT_SIZE = 4;

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

    /** The document is written with the two-space indentation FXML documents use by default. */
    @BeforeEach
    void configureXmlIndent() {
        CodeStyleSettings settings = CodeStyle.getSettings(getFixture().getProject());
        var xmlOptions = settings.getCommonSettings(XMLLanguage.INSTANCE).getIndentOptions();
        if (xmlOptions != null) xmlOptions.INDENT_SIZE = 2;
    }

    /** Enter after a declaration keeps the indentation of the line it was typed on. */
    @Test
    void enterAfterDeclarationKeepsIndent() {
        configure("""
                <?resource styles.css text/css:
                  .my-style {
                    -fx-text-fill: darkorange;<caret>
                    -fx-pref-width: 1100;
                  }?>""");

        assertEquals("    ", indentAfterEnter());
    }

    /** Enter right after the declaration prefix opens the payload one step in. */
    @Test
    void enterAfterDeclarationPrefixIndentsOneStep() {
        configure("""
                <?resource styles.css text/css:<caret>
                  .my-style {
                    -fx-text-fill: darkorange;
                  }?>""");

        assertEquals("  ", indentAfterEnter());
    }

    /** Enter after the opening brace of a rule indents one step further. */
    @Test
    void enterAfterRuleStartIndentsOneStep() {
        configure("""
                <?resource styles.css text/css:
                  .my-style {<caret>
                    -fx-text-fill: darkorange;
                  }?>""");

        assertEquals("    ", indentAfterEnter());
    }

    /** Enter after the first declaration of a rule keeps the declaration indentation. */
    @Test
    void enterAfterFirstDeclarationKeepsIndent() {
        configure("""
                <?resource styles.css text/css:
                  .my-style {
                    -fx-text-fill: darkorange;<caret>
                  }?>""");

        assertEquals("    ", indentAfterEnter());
    }

    /** Enter in the markup of the document is left to the FXML formatter. */
    @Test
    void enterInMarkupIsNotHandled() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?resource styles.css text/css:.root { -fx-base: black; }?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0">
                  <BorderPane><caret></BorderPane>
                </BorderPane>
                """);

        assertEquals("    ", indentAfterEnter());
    }

    /**
     * A block opened inside a payload advances by the step of the payload language, which is not
     * the step markup nests in.
     */
    @Test
    void blockStartAdvancesByThePayloadStep() {
        useWideJsonIndent();
        configure("""
                <?resource data.json application/json:
                  {<caret>
                  }?>""");

        assertEquals("      ", indentAfterEnter());
    }

    /**
     * The line that opens a payload advances by the markup step instead: what follows it is
     * placed as markup places it, whatever the payload language is indented in.
     */
    @Test
    void payloadStartAdvancesByTheMarkupStep() {
        useWideJsonIndent();
        configure("""
                <?resource data.json application/json:<caret>
                  {
                  }?>""");

        assertEquals("  ", indentAfterEnter());
    }

    /** A payload embedded in a {@code @ComponentView} annotation indents the same way. */
    @Test
    void enterInEmbeddedPayloadKeepsIndent() {
        configureEmbedded("""
                <?resource styles.css text/css:
                  .my-style {
                    -fx-text-fill: darkorange;<caret>
                  }?>
                <BorderPane stylesheets="@styles.css"/>""");

        assertEquals("            ", indentAfterEnter());
    }

    /** A rule opened in an embedded payload indents one step further. */
    @Test
    void enterAfterEmbeddedRuleStartIndentsOneStep() {
        configureEmbedded("""
                <?resource styles.css text/css:
                  .my-style {<caret>
                    -fx-text-fill: darkorange;
                  }?>
                <BorderPane stylesheets="@styles.css"/>""");

        assertEquals("            ", indentAfterEnter());
    }

    /** A closing brace typed on a new line aligns with the line carrying its opening brace. */
    @Test
    void embeddedClosingBraceAlignsWithItsOpeningBrace() {
        configureEmbedded("""
                <?resource styles.css text/css: .my-style {
                  -fx-text-fill: darkorange;<caret>

                <BorderPane stylesheets="@styles.css"/>""");

        getFixture().performEditorAction("EditorEnter");
        getFixture().type('}');

        assertEquals("        }", com.intellij.openapi.application.ReadAction.compute(this::caretLine));
    }

    /** A payload brace keeps the indentation of its opener in a conventionally indented text block. */
    @Test
    void embeddedClosingBraceKeepsHostAndMarkupIndentation() {
        getFixture().configureByText("MainView.java", """
                import org.jfxcore.markup.ComponentView;

                @ComponentView(\"""
                    <?resource first.css text/css: .first {
                      -fx-animated: true;<caret>

                    <?resource second.css text/css:
                      .second {
                        -fx-text-fill: darkorange;
                      }
                    ?>
                    <BorderPane stylesheets="@second.css"/>
                    \""")
                public class MainView {
                }
                """);

        getFixture().performEditorAction("EditorEnter");
        getFixture().type('}');

        assertEquals("    }", com.intellij.openapi.application.ReadAction.compute(this::caretLine));
    }

    /** A brace also preserves the markup indentation between the host and an own-line payload. */
    @Test
    void ownLinePayloadClosingBraceKeepsHostAndMarkupIndentation() {
        configureEmbedded("""
                <?resource data.json application/json:
                  {<caret>

                <BorderPane/>""");

        getFixture().performEditorAction("EditorEnter");
        getFixture().type('}');

        assertEquals("          }", com.intellij.openapi.application.ReadAction.compute(this::caretLine));
    }

    /** Kotlin raw strings preserve the actual indentation of the payload opener as well. */
    @Test
    void kotlinEmbeddedClosingBraceKeepsHostIndentation() {
        getFixture().configureByText("MainView.kt", """
                import org.jfxcore.markup.ComponentView

                @ComponentView(\"""
                      <?resource styles.css text/css: .style {
                        -fx-text-fill: darkorange;<caret>
                \""")
                class MainView
                """);

        getFixture().performEditorAction("EditorEnter");
        getFixture().type('}');

        assertEquals("      }", com.intellij.openapi.application.ReadAction.compute(this::caretLine));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Gives the JSON payloads above a step wider than the markup step, so that the column a line
     * lands on says which of the two steps placed it.
     */
    private void useWideJsonIndent() {
        Language json = Language.findLanguageByID("JSON");
        assertNotNull(json, "JSON is bundled with every IDE the plugin runs in");

        var options = CodeStyle.getSettings(getFixture().getProject())
                .getCommonSettings(json).getIndentOptions();
        assertNotNull(options, "JSON has indent options");
        options.INDENT_SIZE = JSON_INDENT_SIZE;
    }

    /** Writes {@code markup} into the {@code @ComponentView} annotation of a Java class. */
    private void configureEmbedded(String markup) {
        getFixture().configureByText("MainView.java", """
                import org.jfxcore.markup.ComponentView;

                @ComponentView(\"""
                %s\""")
                public class MainView {
                }
                """.formatted(markup.indent(8)));
    }

    private void configure(String declarations) {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                %s
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"/>
                """.formatted(declarations));
    }

    /** Presses Enter and returns the whitespace the caret line starts with. */
    private String indentAfterEnter() {
        getFixture().performEditorAction("EditorEnter");
        return com.intellij.openapi.application.ReadAction.compute(this::caretLineIndent);
    }

    /** Returns the whitespace the caret line of the host document starts with. */
    private String caretLineIndent() {
        String text = caretLine();
        int i = 0;
        while (i < text.length() && text.charAt(i) == ' ') i++;
        return text.substring(0, i);
    }

    /** Returns the complete line carrying the caret in the host document. */
    private String caretLine() {
        var editor = getFixture().getEditor();
        var document = editor.getDocument();
        int line = document.getLineNumber(editor.getCaretModel().getOffset());
        return document.getText().substring(
                document.getLineStartOffset(line), document.getLineEndOffset(line));
    }
}
