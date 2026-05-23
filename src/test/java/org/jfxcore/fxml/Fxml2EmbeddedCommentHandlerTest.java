// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.injected.editor.EditorWindow;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiDocumentManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that "Comment with Line Comment" (Ctrl+/) and "Comment with Block Comment"
 * (Ctrl+Shift+/) use XML comment syntax when the caret/selection is inside embedded
 * FXML2 markup in a {@code @ComponentView} text block.
 *
 * <p>Without the fix, "Comment with Line Comment" inserts {@code //} (Java) instead of
 * {@code <!-- ... -->} (XML), because the platform's {@code CommentByLineCommentHandler}
 * redirects to the host (Java) file whenever the injection host element's text starts
 * with {@code "}: which is always the case for Java text blocks.
 *
 * <p>Implementation under test:
 * {@link org.jfxcore.fxml.lang.Fxml2EmbeddedCommentHandlers.LineCommentAction} and
 * {@link org.jfxcore.fxml.lang.Fxml2EmbeddedCommentHandlers.BlockCommentAction}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class Fxml2EmbeddedCommentHandlerTest extends Fxml2TestBase {

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
    // Helper: always return the host (Java) document text
    // -----------------------------------------------------------------------

    /**
     * Returns the text of the host Java document.
     *
     * <p>When the caret is inside an embedded FXML2 fragment the test fixture's
     * {@code getEditor()} may return the injected {@code EditorWindow}.  Its document
     * contains the synthetic XML wrapper with namespace URIs that include {@code ://},
     * which would confuse assertions about Java-style {@code //} comments.
     * This helper always unwraps to the host editor's document.
     */
    private String getHostText() {
        Editor editor = getFixture().getEditor();
        Document doc = editor instanceof EditorWindow ew
                ? ew.getDelegate().getDocument()
                : editor.getDocument();
        // Fall back to PSI-based lookup if the editor document is stale.
        Document psiDoc = PsiDocumentManager.getInstance(getFixture().getProject())
                .getDocument(getFixture().getFile());
        return (psiDoc != null ? psiDoc : doc).getText();
    }

    // -----------------------------------------------------------------------
    // Line comment: comment
    // -----------------------------------------------------------------------

    /**
     * Verifies that triggering "Comment with Line Comment" when the caret is on an
     * XML tag line inside embedded FXML2 wraps the line content in {@code <!-- ... -->}.
     *
     * <p>Without the fix the action inserts {@code //} (Java line comment).
     */
    @Test
    void lineCommentOnFxml2LineAddsXmlComment() {
        getFixture().configureByText("TestView.java",
                """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane>
                      <VBox><caret>
                      </VBox>
                    </StackPane>
                \""")
                public class TestView {
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();
        getFixture().performEditorAction("CommentByLineComment");

        String text = getHostText();

        assertTrue(text.contains("<!--<VBox>-->"),
                "Expected XML comment '<!--<VBox>-->' but got:\n" + text);
        // Java line comment would be "//      <VBox>" (at column 0).
        // Check no line in the FXML region starts with "//".
        assertFalse(hasJavaLineComment(text),
                "Should NOT contain Java line comment '//' in:\n" + text);
    }

    /**
     * Verifies that triggering "Comment with Line Comment" on a multi-line selection
     * inside embedded FXML2 wraps every selected line in {@code <!-- ... -->}.
     */
    @Test
    void lineCommentOnMultipleFxml2LinesAddsXmlComments() {
        getFixture().configureByText("TestView.java",
                """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane>
                      <selection><VBox>
                        <Label/>
                      </VBox></selection>
                    </StackPane>
                \""")
                public class TestView {
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();
        getFixture().performEditorAction("CommentByLineComment");

        String text = getHostText();

        assertTrue(text.contains("<!--<VBox>-->"),
                "Expected '<!--<VBox>-->' in:\n" + text);
        assertTrue(text.contains("<!--<Label/>-->"),
                "Expected '<!--<Label/>-->' in:\n" + text);
        assertTrue(text.contains("<!--</VBox>-->"),
                "Expected '<!--</VBox>-->' in:\n" + text);
        assertFalse(hasJavaLineComment(text),
                "Should NOT contain Java line comment '//' in:\n" + text);
    }

    // -----------------------------------------------------------------------
    // Line comment: uncomment
    // -----------------------------------------------------------------------

    /**
     * Verifies that triggering "Comment with Line Comment" a second time on an already
     * XML-commented line removes the {@code <!-- ... -->} markers (toggle / uncomment).
     */
    @Test
    void lineCommentOnAlreadyCommentedFxml2LineUncomments() {
        getFixture().configureByText("TestView.java",
                """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane>
                      <!-- <VBox> --><caret>
                      </VBox>
                    </StackPane>
                \""")
                public class TestView {
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();
        getFixture().performEditorAction("CommentByLineComment");

        String text = getHostText();

        assertTrue(text.contains("<VBox>"),
                "Expected '<VBox>' to be restored after uncommenting in:\n" + text);
        assertFalse(text.contains("<!-- <VBox> -->"),
                "Comment markers should have been removed in:\n" + text);
    }

    /**
     * Verifies symmetric toggle: comment -> uncomment on a multi-line selection.
     */
    @Test
    void lineCommentTogglesSymmetricallyForMultipleLines() {
        getFixture().configureByText("TestView.java",
                """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane>
                      <selection><!-- <VBox> -->
                      <!-- <Label/> --></selection>
                    </StackPane>
                \""")
                public class TestView {
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();
        getFixture().performEditorAction("CommentByLineComment");

        String text = getHostText();

        assertTrue(text.contains("<VBox>"), "Expected '<VBox>' to be restored in:\n" + text);
        assertTrue(text.contains("<Label/>"), "Expected '<Label/>' to be restored in:\n" + text);
        assertFalse(text.contains("<!-- <VBox> -->"),
                "Comment markers should have been removed from <VBox> in:\n" + text);
    }

    // -----------------------------------------------------------------------
    // Line comment: Java code is still commented with //
    // -----------------------------------------------------------------------

    /**
     * Verifies that "Comment with Line Comment" on a Java code line (outside embedded
     * FXML2) still inserts {@code //}: the plugin must not break Java commenting.
     */
    @Test
    void lineCommentOnJavaCodeProducesSlashSlash() {
        getFixture().configureByText("TestView.java",
                """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane/>
                \""")
                public class TestView {
                    protected void initializeComponent() {}<caret>
                    public TestView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();
        getFixture().performEditorAction("CommentByLineComment");

        String text = getHostText();

        assertTrue(hasJavaLineComment(text),
                "Expected Java line comment '//' for Java code in:\n" + text);
    }

    // -----------------------------------------------------------------------
    // Line comment: mixed Java + FXML2 selection
    // -----------------------------------------------------------------------

    /**
     * Verifies that when a selection spans both FXML2 lines and Java/Kotlin lines,
     * ALL lines (FXML2 and Java alike) receive {@code //} comments.
     *
     * <p>Using {@code //} for the entire mixed selection is required for symmetry:
     * the Java structural lines (e.g. {@code @ComponentView("""}) are the glue that
     * makes IntelliJ recognize the text block content as FXML2.  Once those lines are
     * commented out the FXML2 injection disappears, so a subsequent un-comment action
     * would no longer see any FXML2 content.  By using {@code //} for everything in the
     * mixed case, both the comment and the un-comment pass operate identically.
     */
    @Test
    void lineCommentOnMixedSelectionUsesJavaCommentsForAll() {
        // The selection starts on an FXML2 line and ends on a Java line.
        getFixture().configureByText("TestView.java",
                """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane>
                      <selection><VBox/>
                    </StackPane>
                \""")
                public class TestView {</selection>
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();
        getFixture().performEditorAction("CommentByLineComment");

        String text = getHostText();

        // All lines, including the FXML2 line, must receive Java "//" comments.
        assertFalse(text.contains("<!--"),
                "FXML2 line must NOT get XML comment in a mixed selection; got:\n" + text);
        assertTrue(hasJavaLineComment(text),
                "Expected Java line comment '//' for all lines in:\n" + text);
    }

    /**
     * Verifies that comment+uncomment on a mixed Java+FXML2 selection is fully symmetric.
     *
     * <p>The test simulates the result that the platform's Java commenter would produce
     * for the mixed selection (all lines prefixed with {@code // }), then verifies that
     * a second invocation of "Comment with Line Comment" on those same lines un-comments
     * everything back to the original content.  Because the FXML2 injection is broken
     * by the {@code //} on the structural Java lines, the un-comment pass must use the
     * platform's Java un-commenter (not our FXML2-aware handler).
     */
    @Test
    void lineCommentOnMixedSelectionIsReversible() {
        // Simulate the state AFTER the platform's Java commenter has run on a mixed
        // selection spanning an FXML2 line and structural Java lines.
        // The platform prefixes each line with "// " at column 0.
        getFixture().configureByText("TestView.java",
                """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane>
                <selection>      // <VBox/>
                    // </StackPane>
                // \""")
                // public class TestView {</selection>
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        // Uncomment: the FXML2 injection is gone (structural lines are commented out),
        // so all lines must be treated as Java and un-prefixed with "//".
        getFixture().performEditorAction("CommentByLineComment");

        String text = getHostText();

        assertTrue(text.contains("<VBox/>"),
                "Expected '<VBox/>' to be restored after uncomment in:\n" + text);
        assertFalse(text.lines()
                        .map(String::stripLeading)
                        .anyMatch(l -> l.startsWith("// ")),
                "All '// ' prefixes should be removed after uncomment in:\n" + text);
    }

    // -----------------------------------------------------------------------
    // Block comment
    // -----------------------------------------------------------------------

    /**
     * Verifies that "Comment with Block Comment" (Ctrl+Shift+/) wraps the selection in
     * {@code <!--} / {@code -->} when the caret is inside embedded FXML2 markup.
     */
    @Test
    void blockCommentOnFxml2SelectionAddsXmlBlockComment() {
        getFixture().configureByText("TestView.java",
                """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane>
                      <selection><VBox>
                        <Label/>
                      </VBox></selection>
                    </StackPane>
                \""")
                public class TestView {
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();
        getFixture().performEditorAction("CommentByBlockComment");

        String text = getHostText();

        assertTrue(text.contains("<!--"),
                "Expected XML block comment '<!--' in:\n" + text);
        assertTrue(text.contains("-->"),
                "Expected XML block comment '-->' in:\n" + text);
        assertFalse(text.contains("/*"),
                "Should NOT contain Java block comment '/*' in:\n" + text);
    }

    /**
     * Verifies that "Comment with Block Comment" on an already-XML-block-commented
     * selection removes the {@code <!--} / {@code -->} wrappers (toggle / uncomment).
     */
    @Test
    void blockCommentOnAlreadyCommentedFxml2SelectionUncomments() {
        getFixture().configureByText("TestView.java",
                """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane>
                      <selection><!-- <VBox>
                        <Label/>
                      </VBox> --></selection>
                    </StackPane>
                \""")
                public class TestView {
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();
        getFixture().performEditorAction("CommentByBlockComment");

        String text = getHostText();

        assertTrue(text.contains("<VBox>"),
                "Expected '<VBox>' to be restored in:\n" + text);
        assertFalse(text.contains("<!--"),
                "Block comment markers should have been removed in:\n" + text);
    }

    /**
     * Verifies that pressing "Comment with Block Comment" a second time with the
     * selection on the <em>inner content</em> (without the {@code <!--} / {@code -->}
     * markers) removes the existing comment instead of adding another layer.
     *
     * <p>This is the typical state after the first press: IntelliJ leaves the caret's
     * selection on the inner content without expanding it to include the newly inserted
     * markers.
     */
    @Test
    void blockCommentTogglesOnSecondPressWithInnerSelection() {
        // "<!--" and "-->" are NOT inside the selection; only the inner content is.
        getFixture().configureByText("TestView.java",
                """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane>
                      <!--<selection><VBox>
                        <Label/>
                      </VBox></selection>-->
                    </StackPane>
                \""")
                public class TestView {
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();
        getFixture().performEditorAction("CommentByBlockComment");

        String text = getHostText();

        assertTrue(text.contains("<VBox>"),
                "Expected '<VBox>' to be restored in:\n" + text);
        assertFalse(text.contains("<!--"),
                "Second press should remove the comment, not add another layer:\n" + text);
    }

    /**
     * Verifies that pressing "Comment with Block Comment" twice on a selection of
     * <em>indented</em> FXML2 lines preserves the original indentation exactly.
     *
     * <p>The {@code uncommentBlock} implementation must not strip any character immediately
     * after {@code <!--} when that character is part of the original indentation.  Only an
     * extra formatting space that was inserted by the commenter itself may be stripped on
     * uncomment.
     */
    @Test
    void blockCommentRoundTripPreservesIndentation() {
        // The three <Button> lines each start with 4 spaces of indentation.
        getFixture().configureByText("TestView.java",
                """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane>
                      <VBox>
                        <selection>    <Label text="a"/>
                        <Label text="b"/>
                        <Label text="c"/></selection>
                      </VBox>
                    </StackPane>
                \""")
                public class TestView {
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        // Capture original text before any commenting.
        String original = getHostText();

        // First press: adds block comment.
        getFixture().performEditorAction("CommentByBlockComment");

        // Second press: must restore the original text exactly.
        getFixture().performEditorAction("CommentByBlockComment");

        String text = getHostText();
        assertEquals(original, text,
                "Block comment round-trip must restore the original text exactly");
    }

    /**
     * Verifies that pressing "Comment with Block Comment" twice on an FXML2 selection
     * performs a full round-trip: the first press wraps the selection in
     * {@code <!--} / {@code -->}, and the second press removes the markers, restoring
     * the original content.
     *
     * <p>The block-comment shortcut must be idempotent: pressing the shortcut a second
     * time on a selection that was already block-commented must remove the comment markers,
     * regardless of where IntelliJ repositions the selection boundaries after the first press.
     */
    @Test
    void blockCommentIsReversibleByPressingShortcutTwice() {
        getFixture().configureByText("TestView.java",
                """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane>
                      <selection><VBox>
                        <Label/>
                      </VBox></selection>
                    </StackPane>
                \""")
                public class TestView {
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        // First press: adds block comment.
        getFixture().performEditorAction("CommentByBlockComment");

        String afterFirst = getHostText();
        assertTrue(afterFirst.contains("<!--"),
                "Expected XML block comment after first press in:\n" + afterFirst);
        assertFalse(afterFirst.contains("<!--<!--"),
                "Should not have nested comments after first press in:\n" + afterFirst);

        // Second press: must remove the block comment, not add another layer.
        getFixture().performEditorAction("CommentByBlockComment");

        String text = getHostText();
        assertTrue(text.contains("<VBox>"),
                "Expected '<VBox>' to be restored after second press in:\n" + text);
        assertFalse(text.contains("<!--"),
                "Second press should remove the comment in:\n" + text);
    }

    /**
     * Verifies that "Comment with Block Comment" on a selection spanning both FXML2 lines
     * and Java/Kotlin code falls back to Java {@code /* }&#42;{@code /} block comments.
     *
     * <p>Using XML {@code <!--} ... {@code -->} for a mixed selection would be incorrect
     * because the Java structural lines (e.g. the {@code @ComponentView} annotation line)
     * must also be commented out; XML comment syntax is not valid outside the FXML2 injection.
     */
    @Test
    void blockCommentOnMixedFxml2AndJavaSelectionUsesJavaBlockComment() {
        // Selection starts inside FXML2 and ends in Java code.
        getFixture().configureByText("TestView.java",
                """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane>
                      <selection><VBox/>
                    </StackPane>
                \""")
                public class TestView {</selection>
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();
        getFixture().performEditorAction("CommentByBlockComment");

        String text = getHostText();

        assertTrue(text.contains("/*") && text.contains("*/"),
                "Expected Java block comment '/* */' for mixed selection in:\n" + text);
        assertFalse(text.contains("<!--"),
                "Should NOT contain XML block comment '<!--' in a mixed selection:\n" + text);
    }

    /**
     * Verifies that "Comment with Block Comment" on Java code (outside embedded FXML2)
     * still inserts Java block comments: the plugin must not break Java block commenting.
     */
    @Test
    void blockCommentOnJavaCodeProducesJavaBlockComment() {
        getFixture().configureByText("TestView.java",
                """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane/>
                \""")
                public class TestView {
                    protected void <selection>initializeComponent</selection>() {}
                    public TestView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();
        getFixture().performEditorAction("CommentByBlockComment");

        String text = getHostText();

        assertTrue(text.contains("/*") && text.contains("*/"),
                "Expected Java block comment '/* */' for Java code in:\n" + text);
        assertFalse(text.contains("<!--"),
                "Should NOT contain XML block comment '<!--' in:\n" + text);
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when any line in the Java host document starts with {@code //}
     * (after stripping leading whitespace), indicating a Java-style line comment was added
     * to FXML content.
     *
     * <p>This deliberately does NOT trigger on {@code ://} (URL schemes) or on XML
     * comments ({@code <!-- ... -->}).
     */
    private static boolean hasJavaLineComment(String text) {
        return text.lines().anyMatch(line -> line.stripLeading().startsWith("//"));
    }
}
