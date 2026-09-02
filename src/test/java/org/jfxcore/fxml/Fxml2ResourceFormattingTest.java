// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.application.options.CodeStyle;
import com.intellij.openapi.Disposable;
import com.intellij.lang.Language;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how reformatting treats a {@code <?resource ?>} declaration.
 *
 * <p>A payload is a document of the language its media type names, written inside markup, so two
 * code styles meet in a declaration and each governs what it owns.  The content is formatted in
 * the code style of the payload language; where the declaration and its payload sit is decided by
 * the markup indentation, one step in from the declaration for a payload on its own lines.  The
 * The layout chosen by the author, such as having the payload on the declaration line or below it,
 * and having the terminator attached or on its own line, is preserved, ensuring that reformatting a
 * well-formatted document leaves it unchanged.
 *
 * <p>A payload this IDE cannot format is left exactly as written, such as a media type with no backing language,
 * a language this edition does not bundle (for example, CSS in IntelliJ IDEA Community), or content
 * that does not parse yet. The tests use JSON for the payloads that get formatted, because it is
 * bundled with every edition and therefore yields consistent results across environments.
 *
 * <p>Implementation under test: {@link org.jfxcore.fxml.lang.Fxml2FormattingModelBuilder}, which
 * keeps the declaration out of the markup formatter's reach, and
 * {@link org.jfxcore.fxml.lang.Fxml2ResourcePayloadFormattingProcessor}, which rewrites the
 * payload afterwards.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class Fxml2ResourceFormattingTest extends Fxml2TestBase {

    /** Undoes the registration of a directory rule after the test that registered it. */
    private Disposable ruleDisposable;

    @AfterEach
    void unregisterDirectoryRule() {
        if (ruleDisposable != null) {
            Disposer.dispose(ruleDisposable);
            ruleDisposable = null;
        }
    }

    /** The step a rule of the host directory contributes, distinct from every configured step. */
    private static final int DIRECTORY_RULE_STEP = 2;

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

    /** Markup and JSON are both indented in steps of two, which the samples below are written in. */
    @BeforeEach
    void useTwoSpaceIndents() {
        setMarkupIndent(2);
        setJsonIndent(2);
    }

    // -----------------------------------------------------------------------
    // Payloads that are left as written
    // -----------------------------------------------------------------------

    /** A payload of a media type with no language behind it is kept character for character. */
    @Test
    void payloadWithoutALanguageIsKept() {
        assertFxmlUnchanged("<?resource greeting.txt:   Hello   from a resource   ?>");
    }

    /** A multi-line plain-text payload keeps every line as written. */
    @Test
    void multilinePayloadWithoutALanguageIsKept() {
        assertFxmlUnchanged("""
                <?resource greeting.txt:
                      Hello
                              from a resource
                ?>""");
    }

    /** A payload that does not parse is left alone rather than rearranged while it is written. */
    @Test
    void payloadThatDoesNotParseIsKept() {
        assertFxmlUnchanged("""
                <?resource data.json application/json:
                  {"a":  1,
                ?>""");
    }

    // -----------------------------------------------------------------------
    // Payloads that are formatted
    // -----------------------------------------------------------------------

    /** An unformatted payload is formatted, and placed one markup step in from the declaration. */
    @Test
    void unformattedPayloadIsFormatted() {
        assertFxmlBecomes("""
                <?resource data.json application/json:
                {"a":1,
                      "b":2}
                ?>""", """
                <?resource data.json application/json:
                  {
                    "a": 1,
                    "b": 2
                  }
                ?>""");
    }

    /** A payload that continues the declaration line keeps doing so. */
    @Test
    void payloadOnTheDeclarationLineKeepsItsForm() {
        assertFxmlBecomes("""
                <?resource inline application/json: {"a":1}?>""", """
                <?resource inline application/json: {
                  "a": 1
                }?>""");
    }

    /** A formatted payload is already in its final shape, so reformatting leaves it alone. */
    @Test
    void formattedPayloadIsKept() {
        assertFxmlUnchanged("""
                <?resource data.json application/json:
                  {
                    "a": 1
                  }
                ?>

                <?resource inline application/json: {
                  "a": 1
                }?>""");
    }

    /** The payload is formatted in the code style of its own language, not in the markup style. */
    @Test
    void payloadIsFormattedInTheCodeStyleOfItsOwnLanguage() {
        setJsonIndent(4);

        assertFxmlBecomes("""
                <?resource data.json application/json:
                {"a":{"b":1}}
                ?>""", """
                <?resource data.json application/json:
                  {
                      "a": {
                          "b": 1
                      }
                  }
                ?>""");
    }

    /** Where the payload is placed follows the markup indentation, not the payload code style. */
    @Test
    void payloadIsPlacedAtTheMarkupIndentation() {
        setMarkupIndent(6);

        assertFxmlBecomes("""
                <?resource data.json application/json:
                {"a":1}
                ?>""", """
                <?resource data.json application/json:
                      {
                        "a": 1
                      }
                ?>""");
    }

    // -----------------------------------------------------------------------
    // The declaration within its document
    // -----------------------------------------------------------------------

    /** Markup around a declaration is formatted as markup. */
    @Test
    void markupAroundADeclarationIsFormatted() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.Label?>
                <?resource greeting.txt:
                      Hello
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
                <?resource greeting.txt:
                      Hello
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
                   <?resource greeting.txt:
                     Hello
                         from a resource
                   ?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"/>
                """);
        reformat();

        assertEquals("""
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?resource greeting.txt:
                  Hello
                      from a resource
                ?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"/>
                """, getFixture().getFile().getText());
    }

    /** Every declaration of a document is formatted, each in the shape it is written in. */
    @Test
    void everyDeclarationIsFormatted() {
        assertFxmlBecomes("""
                <?resource inline application/json: {"a":1}?>

                <?resource data.json application/json:
                {"b":2}
                ?>

                <?resource greeting.txt:Hello   from a resource?>""", """
                <?resource inline application/json: {
                  "a": 1
                }?>

                <?resource data.json application/json:
                  {
                    "b": 2
                  }
                ?>

                <?resource greeting.txt:Hello   from a resource?>""");
    }

    // -----------------------------------------------------------------------
    // The steps a payload is indented in
    // -----------------------------------------------------------------------

    /**
     * A payload that starts on a line of its own is placed one markup step in from its
     * declaration, and nests from there in the steps of its own language.
     */
    @Test
    void ownLinePayloadNestsTheMarkupStepAndThePayloadStep() {
        setMarkupIndent(2);
        setJsonIndent(4);

        assertFxmlBecomes("""
                <?resource data.json application/json:
                {"a":{"b":1}}
                ?>""", """
                <?resource data.json application/json:
                  {
                      "a": {
                          "b": 1
                      }
                  }
                ?>""");
    }

    /**
     * A payload that continues the declaration line is already placed by the declaration, so its
     * remaining lines are anchored at the declaration and nest in payload steps alone.
     */
    @Test
    void sameLinePayloadNestsInPayloadStepsAlone() {
        setMarkupIndent(2);
        setJsonIndent(4);

        assertFxmlBecomes("""
                <?resource inline application/json: {"a":{"b":1}}?>""", """
                <?resource inline application/json: {
                    "a": {
                        "b": 1
                    }
                }?>""");
    }

    // -----------------------------------------------------------------------
    // Markup embedded in an annotation value
    // -----------------------------------------------------------------------

    /** A declaration embedded in an annotation value is formatted the same way. */
    @Test
    void payloadOfEmbeddedMarkupIsFormatted() {
        getFixture().configureByText("TestView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.BorderPane;
                @ComponentView(\"""
                    <?resource data.json application/json:
                    {"a":1,
                       "b":2}
                    ?>
                    <?resource greeting.txt:   Hello   ?>
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
                    <?resource data.json application/json:
                      {
                        "a": 1,
                        "b": 2
                      }
                    ?>
                    <?resource greeting.txt:   Hello   ?>
                    <BorderPane/>
                \""")
                public class TestView {
                }
                """, getFixture().getFile().getText());
    }

    /**
     * Embedded markup indents a payload the same way, on top of the column the annotation value
     * places its lines at.
     */
    @Test
    void embeddedPayloadNestsInsideTheAnnotationValue() {
        setMarkupIndent(2);
        setJsonIndent(4);

        getFixture().configureByText("TestView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.BorderPane;
                @ComponentView(\"""
                    <?resource data.json application/json:
                    {"a":1}
                    ?>
                    <?resource inline application/json: {"a":1}?>
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
                    <?resource data.json application/json:
                      {
                          "a": 1
                      }
                    ?>
                    <?resource inline application/json: {
                        "a": 1
                    }?>
                    <BorderPane/>
                \""")
                public class TestView {
                }
                """, getFixture().getFile().getText());
    }

    // -----------------------------------------------------------------------
    // The rules of the directory the markup lives in
    // -----------------------------------------------------------------------

    /**
     * A rule that applies where the markup lives decides both steps, over the code style
     * configured for markup and for the payload language.
     *
     * <p>This is how an {@code .editorconfig} section reaches a document.  The rule is contributed
     * by a modifier registered for this test rather than by an {@code .editorconfig} file, so that
     * the assertion holds wherever the suite runs; what it asserts is that a reformat asks for the
     * steps that apply to the file it is formatting, whichever rule they come from.
     */
    @Test
    void aRuleForTheDirectoryDecidesBothSteps() {
        setMarkupIndent(8);
        setJsonIndent(8);
        registerDirectoryRule();

        assertFxmlBecomes("""
                <?resource data.json application/json:
                {"a":{"b":1}}
                ?>""", """
                <?resource data.json application/json:
                  {
                    "a": {
                      "b": 1
                    }
                  }
                ?>""");
    }

    /** Markup embedded in an annotation value is formatted with the same rule. */
    @Test
    void aRuleForTheDirectoryDecidesTheStepsOfEmbeddedMarkup() {
        setMarkupIndent(8);
        setJsonIndent(8);
        registerDirectoryRule();

        getFixture().configureByText("TestView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.BorderPane;
                @ComponentView(\"""
                    <?resource data.json application/json:
                    {"a":{"b":1}}
                    ?>
                    <BorderPane/>
                    \""")
                public class TestView {
                }
                """);
        reformat();

        assertTrue(getFixture().getFile().getText().contains("""
                <?resource data.json application/json:
                  {
                    "a": {
                      "b": 1
                    }
                  }
                ?>""".indent(4)),
                "The rule of the host directory must decide both steps; got:\n"
                + getFixture().getFile().getText());
    }

    // -----------------------------------------------------------------------
    // Reformatting invoked from inside a payload
    // -----------------------------------------------------------------------

    /**
     * A payload is an editable fragment of its own language, so the caret can sit in it when a
     * reformat is invoked.  The request is answered by the document the declaration is written in
     * rather than by the payload fragment alone: where a payload sits is a markup decision, and a
     * fragment formatted in isolation knows nothing of the declaration that carries it.
     */
    @Test
    void reformattingFromInsideAPayloadFormatsTheDocument() {
        getFixture().configureByText("TestView.fxml", document("""
                <?resource data.json application/json:
                {"a":1,
                      "b":2}
                ?>"""));
        reformatPayloadFragment();

        assertEquals(document("""
                <?resource data.json application/json:
                  {
                    "a": 1,
                    "b": 2
                  }
                ?>"""), getFixture().getFile().getText());
    }

    /** The same holds for a payload of markup embedded in an annotation value. */
    @Test
    void reformattingFromInsideAnEmbeddedPayloadFormatsTheAnnotationValue() {
        getFixture().configureByText("TestView.java", """
                package test;

                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.BorderPane;

                @ComponentView(\"""
                    <?resource data.json application/json:
                    {"a":1,
                          "b":2}
                    ?>
                    <BorderPane/>
                \""")
                public class TestView {
                }
                """);
        reformatPayloadFragment();

        assertEquals("""
                package test;

                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.BorderPane;

                @ComponentView(\"""
                    <?resource data.json application/json:
                      {
                        "a": 1,
                        "b": 2
                      }
                    ?>
                    <BorderPane/>
                \""")
                public class TestView {
                }
                """, getFixture().getFile().getText());
    }

    /** Reformats the payload fragment of the configured file, as Ctrl+Alt+L does with the caret in it. */
    private void reformatPayloadFragment() {
        warmUpInjections();

        PsiFile payload = ReadAction.compute(this::findPayloadFragment);
        assertNotNull(payload, "the payload is an injected fragment");
        WriteCommandAction.runWriteCommandAction(getFixture().getProject(), () ->
                CodeStyleManager.getInstance(getFixture().getProject())
                        .reformatText(payload, 0, payload.getTextLength()));
    }

    /** Returns the injected fragment holding the JSON payload of the configured file. */
    private PsiFile findPayloadFragment() {
        InjectedLanguageManager manager = InjectedLanguageManager.getInstance(getFixture().getProject());
        for (PsiLanguageInjectionHost host :
                PsiTreeUtil.findChildrenOfType(getFixture().getFile(), PsiLanguageInjectionHost.class)) {
            List<Pair<PsiElement, TextRange>> injected = manager.getInjectedPsiFiles(host);
            if (injected == null) continue;
            for (var pair : injected) {
                if (pair.first instanceof PsiFile file && file.getLanguage() == jsonLanguage()) return file;
            }
        }
        return null;
    }

    /**
     * Registers a rule that gives markup and JSON a step of {@link #DIRECTORY_RULE_STEP} for every
     * file, standing in for an {@code .editorconfig} section that covers both.
     */
    private void registerDirectoryRule() {
        ruleDisposable = Fxml2DirectoryCodeStyleRule.install(
                DIRECTORY_RULE_STEP, List.of(XMLLanguage.INSTANCE, jsonLanguage()));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Sets the indentation step markup is written in. */
    private void setMarkupIndent(int indentSize) {
        CodeStyleSettings settings = CodeStyle.getSettings(getFixture().getProject());
        CommonCodeStyleSettings.IndentOptions options =
                settings.getCommonSettings(XMLLanguage.INSTANCE).getIndentOptions();
        assertNotNull(options, "XML has indent options");
        options.INDENT_SIZE = indentSize;
    }

    /** Sets the indentation step the JSON payloads below are formatted in. */
    private void setJsonIndent(int indentSize) {
        Language json = jsonLanguage();

        CodeStyleSettings settings = CodeStyle.getSettings(getFixture().getProject());
        CommonCodeStyleSettings.IndentOptions options = settings.getCommonSettings(json).getIndentOptions();
        assertNotNull(options, "JSON has indent options");
        options.INDENT_SIZE = indentSize;
    }

    private static @NotNull Language jsonLanguage() {
        Language json = Language.findLanguageByID("JSON");
        assertNotNull(json, "JSON is bundled with every IDE the plugin runs in");
        return json;
    }

    /**
     * Asserts that reformatting a document whose prolog carries {@code declarations} leaves the
     * document unchanged, which is also asserted for every {@code assertFxmlBecomes} result.
     */
    private void assertFxmlUnchanged(String declarations) {
        assertFxmlBecomes(declarations, declarations);
    }

    /**
     * Asserts that reformatting a document whose prolog carries {@code declarations} produces
     * {@code expected}, and that reformatting the result again changes nothing.
     */
    private void assertFxmlBecomes(String declarations, String expected) {
        getFixture().configureByText("TestView.fxml", document(declarations));
        reformat();
        assertEquals(document(expected), getFixture().getFile().getText());

        reformat();
        assertEquals(document(expected), getFixture().getFile().getText(), "reformatting again changes nothing");
    }

    private static String document(String declarations) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                %s
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"/>
                """.formatted(declarations);
    }

    /** Reformats the whole configured file, as Ctrl+Alt+L does. */
    private void reformat() {
        warmUpInjections();

        WriteCommandAction.runWriteCommandAction(getFixture().getProject(), () ->
                CodeStyleManager.getInstance(getFixture().getProject())
                        .reformatText(getFixture().getFile(),
                                      0, getFixture().getFile().getTextLength()));
    }

    /**
     * Computes the injections the formatter of an annotation value relies on.
     *
     * <p>In the IDE the daemon computes them in the background; a test has to ask.  Asking for the
     * injections rather than running the daemon keeps highlighting results computed before the
     * reformat from being applied to the document after it.
     */
    private void warmUpInjections() {
        ReadAction.run(() -> {
            InjectedLanguageManager manager = InjectedLanguageManager.getInstance(getFixture().getProject());
            for (PsiLanguageInjectionHost host :
                    PsiTreeUtil.findChildrenOfType(getFixture().getFile(), PsiLanguageInjectionHost.class)) {
                manager.getInjectedPsiFiles(host);
            }
        });
    }
}
