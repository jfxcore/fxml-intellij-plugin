// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.application.options.CodeStyle;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies how reformatting treats a {@code <?resource ?>} declaration.
 *
 * <p>The payload of a declaration is resource content, not markup: it is laid out by its author,
 * and reindenting it would change the resource.  Reformatting therefore reproduces a declaration
 * exactly as written and only decides where the declaration as a whole starts, which is also what
 * makes reformatting a well-formatted document leave it unchanged.
 *
 * <p>The rule is independent of the payload language and of the code style that language is
 * written in elsewhere: the declaration shares its lines with the markup it is written in, so the
 * markup indentation is what the declaration is placed at, and nothing inside it is rewritten.
 *
 * <p>Implementation under test: {@link org.jfxcore.fxml.lang.Fxml2FormattingModelBuilder} and, for
 * embedded markup, {@code Fxml2EmbedMarkupUtil.formatXmlContent}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class Fxml2ResourceFormattingTest extends Fxml2TestBase {

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

    /** Markup is indented in steps of two, which is what the samples below are written in. */
    @BeforeEach
    void useTwoSpaceMarkupIndent() {
        CodeStyleSettings settings = CodeStyle.getSettings(getFixture().getProject());
        CommonCodeStyleSettings.IndentOptions xmlOptions =
                settings.getCommonSettings(XMLLanguage.INSTANCE).getIndentOptions();
        if (xmlOptions != null) xmlOptions.INDENT_SIZE = 2;
    }

    // -----------------------------------------------------------------------
    // Standalone documents
    // -----------------------------------------------------------------------

    /** A payload that starts on the declaration line keeps its layout. */
    @Test
    void payloadStartingOnTheDeclarationLineIsKept() {
        assertFxmlUnchanged("""
                <?resource inline text/css: .rule {
                  -fx-text-fill: darkorange;
                }?>""");
    }

    /** A payload that starts on its own line keeps its layout. */
    @Test
    void payloadStartingOnItsOwnLineIsKept() {
        assertFxmlUnchanged("""
                <?resource styles.css text/css:
                  .rule {
                    -fx-text-fill: darkorange;
                    -fx-pref-width: 1100;
                  }
                ?>""");
    }

    /** A payload laid out in steps of its own is not brought into the markup steps. */
    @Test
    void payloadLaidOutInStepsOfItsOwnIsKept() {
        assertFxmlUnchanged("""
                <?resource styles.css text/css:
                      .rule {
                              -fx-text-fill: darkorange;
                      }
                ?>""");
    }

    /** A payload of a media type with no layout of its own is kept character for character. */
    @Test
    void payloadOfATextResourceIsKept() {
        assertFxmlUnchanged("<?resource greeting.txt:   Hello   from a resource   ?>");
    }

    /** Several declarations in a row each keep their own layout. */
    @Test
    void everyDeclarationKeepsItsOwnLayout() {
        assertFxmlUnchanged("""
                <?resource inline text/css: .rule {
                  -fx-animated: true;
                }?>

                <?resource styles.css text/css:
                  .rule {
                    -fx-text-fill: darkorange;
                  }
                ?>

                <?resource greeting.txt:Hello from a resource?>""");
    }

    /** Markup around a declaration is still formatted. */
    @Test
    void markupAroundADeclarationIsFormatted() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.Label?>
                <?resource styles.css text/css:
                  .rule {
                    -fx-text-fill: darkorange;
                  }
                ?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0">
                <center>
                <Label text="Hello"/>
                </center>
                </BorderPane>
                """);
        reformat();

        assertEquals("""
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.Label?>
                <?resource styles.css text/css:
                  .rule {
                    -fx-text-fill: darkorange;
                  }
                ?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0">
                  <center>
                    <Label text="Hello"/>
                  </center>
                </BorderPane>
                """, getFixture().getFile().getText());
    }

    /** Moving a declaration to its own indentation moves the whole payload with it. */
    @Test
    void aMovedDeclarationTakesItsPayloadAlong() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                   <?resource styles.css text/css:
                     .rule {
                       -fx-text-fill: darkorange;
                     }
                   ?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"/>
                """);
        reformat();

        assertEquals("""
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?resource styles.css text/css:
                  .rule {
                    -fx-text-fill: darkorange;
                  }
                ?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"/>
                """, getFixture().getFile().getText());
    }

    // -----------------------------------------------------------------------
    // Markup embedded in an annotation value
    // -----------------------------------------------------------------------

    /** A declaration embedded in an annotation value keeps its payload layout as well. */
    @Test
    void payloadOfEmbeddedMarkupIsKept() {
        getFixture().configureByText("TestView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.BorderPane;
                @ComponentView(\"""
                    <?resource styles.css text/css:
                      .rule {
                        -fx-text-fill: darkorange;
                      }
                    ?>
                    <?resource inline text/css: .other {
                      -fx-animated: true;
                    }?>
                    <BorderPane/>
                    \""")
                public class TestView {
                }
                """);
        reformat();

        assertEquals("""
                package test;

                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.BorderPane;

                @ComponentView(\"""
                    <?resource styles.css text/css:
                      .rule {
                        -fx-text-fill: darkorange;
                      }
                    ?>
                    <?resource inline text/css: .other {
                      -fx-animated: true;
                    }?>
                    <BorderPane/>
                \""")
                public class TestView {
                }
                """, getFixture().getFile().getText());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Asserts that reformatting a document whose prolog carries {@code declarations} leaves the
     * document unchanged.
     */
    private void assertFxmlUnchanged(String declarations) {
        String document = """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                %s
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"/>
                """.formatted(declarations);

        getFixture().configureByText("TestView.fxml", document);
        reformat();

        assertEquals(document, getFixture().getFile().getText());
    }

    /** Reformats the whole configured file, as Ctrl+Alt+L does. */
    private void reformat() {
        // The daemon computes the injections the formatter and the post-format processor rely on;
        // in the IDE it runs continuously, in a test it has to be requested.
        getFixture().doHighlighting();

        WriteCommandAction.runWriteCommandAction(getFixture().getProject(), () ->
                CodeStyleManager.getInstance(getFixture().getProject())
                        .reformatText(getFixture().getFile(),
                                      0, getFixture().getFile().getTextLength()));
    }
}
