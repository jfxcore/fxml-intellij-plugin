// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.application.options.CodeStyle;
import com.intellij.codeInsight.intention.impl.QuickEditAction;
import com.intellij.codeInsight.intention.impl.QuickEditHandler;
import com.intellij.injected.editor.EditorWindow;
import com.intellij.lang.Language;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.testFramework.EdtTestUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies how an FXML/2 fragment behaves when it is opened in a fragment editor of its own
 * ("Edit CSS fragment" and its siblings), for the payload of a {@code <?resource ?>} declaration
 * in a standalone document and in markup embedded in a {@code @ComponentView} annotation, and for
 * the embedded markup itself.
 *
 * <p>Two properties make such an editor an edit of the document rather than of a copy: what is
 * typed in it is indented in the step the document is written in, and what it writes is spliced
 * back into the document unchanged, leaving every declaration around it as it was.
 *
 * <p>Implementation under test: {@link org.jfxcore.fxml.lang.Fxml2FragmentIndentOptionsProvider}
 * and {@link org.jfxcore.fxml.lang.Fxml2InjectedFileChangesHandlerProvider}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class Fxml2FragmentEditorTest extends Fxml2TestBase {

    /** The step configured project-wide, which no markup and no payload below is written in. */
    private static final int CONFIGURED_STEP = 4;

    /** The step a rule of the host directory contributes, which every payload below is written in. */
    private static final int DIRECTORY_RULE_STEP = 2;

    /** The column an annotation value places the markup at. */
    private static final int ANNOTATION_INDENT = 8;

    /** Undoes the registration of the directory rule after each test. */
    private Disposable ruleDisposable;

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

    /**
     * Markup and JSON are configured project-wide with a step nothing below is written in, and a
     * rule of the directory the markup lives in asks for the step everything below is written in,
     * so the column a typed line lands at says which of the two the fragment editor followed.
     */
    @BeforeEach
    void useDirectoryRule() {
        List<Language> languages = List.of(XMLLanguage.INSTANCE, jsonLanguage());
        for (Language language : languages) {
            CommonCodeStyleSettings.IndentOptions options = CodeStyle.getSettings(getFixture().getProject())
                    .getCommonSettings(language).getIndentOptions();
            assertNotNull(options, language.getID() + " has indent options");
            options.INDENT_SIZE = CONFIGURED_STEP;
        }

        ruleDisposable = Fxml2DirectoryCodeStyleRule.install(DIRECTORY_RULE_STEP, languages);
    }

    @AfterEach
    void unregisterDirectoryRule() {
        if (ruleDisposable != null) {
            Disposer.dispose(ruleDisposable);
            ruleDisposable = null;
        }
    }

    /**
     * A line opened in a fragment editor is indented in the step that applies where the markup
     * lives.
     *
     * <p>Because the fragment is edited in a temporary file without a path, standard code style
     * lookups cannot resolve the local layout rules. This test verifies that the editor falls back
     * to the parent document's indentation step rather than the project-wide default.
     */
    @Test
    void typingInTheFragmentEditorFollowsTheStepOfTheDocument() {
        configureEmbedded("""
                <?resource data.json application/json:
                  {
                    "a": 1,<caret>
                    "b": 2
                  }
                ?>
                <BorderPane/>""");

        openFragmentEditor();
        assertEquals(" ".repeat(ANNOTATION_INDENT + 2 * DIRECTORY_RULE_STEP), indentAfterEnter());
    }

    /** What the fragment editor writes is spliced back into the annotation value exactly as written. */
    @Test
    void editingAnEmbeddedPayloadKeepsTheDocumentLayout() {
        configureEmbedded("""
                <?resource data.json application/json:
                  {
                    "a": 1,<caret>
                    "b": 2
                  }
                ?>
                <BorderPane/>""");

        openFragmentEditor();
        getFixture().performEditorAction("EditorEnter");

        assertEquals(embeddedSource("""
                <?resource data.json application/json:
                  {
                    "a": 1,
                %s
                    "b": 2
                  }
                ?>
                <BorderPane/>""".formatted(" ".repeat(2 * DIRECTORY_RULE_STEP))), hostText());
    }

    /** A payload of a standalone document is written back the same way. */
    @Test
    void editingAPayloadOfAStandaloneDocumentKeepsTheDocumentLayout() {
        getFixture().configureByText("TestView.fxml", standaloneSource("""
                <?resource data.json application/json:
                  {
                    "a": 1,<caret>
                    "b": 2
                  }
                ?>"""));

        openFragmentEditor();
        getFixture().performEditorAction("EditorEnter");

        assertEquals(standaloneSource("""
                <?resource data.json application/json:
                  {
                    "a": 1,
                %s
                    "b": 2
                  }
                ?>""".formatted(" ".repeat(2 * DIRECTORY_RULE_STEP))), hostText());
    }

    /**
     * Editing the markup of an annotation value leaves the resource declarations it carries
     * untouched, payloads included: the markup fragment skips the payloads, and what it writes
     * back reaches only the markup between them.
     */
    @Test
    void editingEmbeddedMarkupKeepsTheDeclarationsItCarries() {
        configureEmbedded("""
                <?resource data.json application/json:
                  {
                    "a": 1
                  }
                ?>
                <BorderPane>
                  <BorderPane/><caret>
                </BorderPane>""");

        openFragmentEditor();
        getFixture().performEditorAction("EditorEnter");

        assertEquals(embeddedSource("""
                <?resource data.json application/json:
                  {
                    "a": 1
                  }
                ?>
                <BorderPane>
                  <BorderPane/>
                %s
                </BorderPane>""".formatted(" ".repeat(DIRECTORY_RULE_STEP))), hostText());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** The document the fragment opened by {@link #openFragmentEditor} belongs to. */
    private PsiFile hostFileOfFragment;

    /** Opens the fragment the caret sits in in an editor of its own, as "Edit fragment" does. */
    private void openFragmentEditor() {
        EdtTestUtil.runInEdtAndWait(this::doOpenFragmentEditor);
    }

    private void doOpenFragmentEditor() {
        Editor editor = getFixture().getEditor();
        Editor hostEditor = editor instanceof EditorWindow window ? window.getDelegate() : editor;
        int offsetInFragment = editor.getCaretModel().getOffset();
        hostFileOfFragment = ReadAction.compute(() -> InjectedLanguageManager.getInstance(getFixture().getProject())
                .getTopLevelFile(getFixture().getFile()));

        QuickEditHandler handler = WriteCommandAction.runWriteCommandAction(
                getFixture().getProject(),
                (Computable<QuickEditHandler>)() -> new QuickEditAction().invokeImpl(
                        getFixture().getProject(), hostEditor, hostFileOfFragment));

        VirtualFile fragment = handler.getNewFile().getVirtualFile();
        assertNotNull(fragment, "the fragment is edited in a file of its own");
        getFixture().openFileInEditor(fragment);
        getFixture().getEditor().getCaretModel().moveToOffset(offsetInFragment);
    }

    /** Presses Enter and returns the whitespace the caret line starts with. */
    private String indentAfterEnter() {
        getFixture().performEditorAction("EditorEnter");
        return ReadAction.compute(() -> {
            Editor editor = getFixture().getEditor();
            var document = editor.getDocument();
            int line = document.getLineNumber(editor.getCaretModel().getOffset());
            String text = document.getText().substring(
                    document.getLineStartOffset(line), document.getLineEndOffset(line));
            int end = 0;
            while (end < text.length() && text.charAt(end) == ' ') ++end;
            return text.substring(0, end);
        });
    }

    /** Returns the text of the document the fragment belongs to. */
    private String hostText() {
        return ReadAction.compute(() -> hostFileOfFragment.getText());
    }

    private void configureEmbedded(String markup) {
        getFixture().configureByText("MainView.java", embeddedSource(markup));
    }

    /** Returns the source of a Java class carrying {@code markup} in its annotation value. */
    private static String embeddedSource(String markup) {
        return """
                import org.jfxcore.markup.ComponentView;

                @ComponentView(\"""
                %s\""")
                public class MainView {
                }
                """.formatted(markup.indent(ANNOTATION_INDENT));
    }

    /** Returns an FXML/2 document whose prolog carries {@code declarations}. */
    private static String standaloneSource(String declarations) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                %s
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"/>
                """.formatted(declarations);
    }

    private static Language jsonLanguage() {
        Language json = Language.findLanguageByID("JSON");
        assertNotNull(json, "JSON is bundled with every IDE the plugin runs in");
        return json;
    }
}
