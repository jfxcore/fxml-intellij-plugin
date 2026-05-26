package org.jfxcore.fxml;

import com.intellij.application.options.CodeStyle;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.JavaRecursiveElementWalkingVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.xml.XmlFile;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for the embedded FXML "Reformat Code" feature.
 *
 * <p>After a reformat on a Java file that contains a {@code @ComponentView} text-block
 * annotation, the embedded XML should use:
 * <ul>
 *   <li>The XML indent size from the project's XML code-style settings (set to 2 in all
 *       tests below).</li>
 *   <li>A base indent of {@code annotationColumn + javaIndentSize} (= 0 + 4 = 4 for a
 *       top-level annotation with the default Java indent of 4).</li>
 * </ul>
 *
 * <p>Implementation under test:
 * {@link org.jfxcore.fxml.lang.Fxml2EmbeddedMarkupFormattingProcessor}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class Fxml2EmbeddedMarkupFormattingTest extends Fxml2TestBase {

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

    /** Set XML indent to 2 before each test (Java indent stays at the default 4). */
    @BeforeEach
    void configureXmlIndent() {
        CodeStyleSettings settings = CodeStyle.getSettings(getFixture().getProject());
        var xmlOpts = settings.getCommonSettings(XMLLanguage.INSTANCE).getIndentOptions();
        if (xmlOpts != null) xmlOpts.INDENT_SIZE = 2;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Reformats the currently-configured file using the standard code-style manager. */
    private void reformat() {
        // Trigger the daemon so that injections and type resolution are computed before
        // reformatting.  In the real IDE the daemon runs continuously in the background;
        // in tests we must request it explicitly so that the PostFormatProcessor can
        // find the injected XmlFile for the @ComponentView annotation.
        getFixture().doHighlighting();

        WriteCommandAction.runWriteCommandAction(getFixture().getProject(), () ->
                CodeStyleManager.getInstance(getFixture().getProject())
                        .reformatText(getFixture().getFile(),
                                      0, getFixture().getFile().getTextLength()));
    }

    /**
     * Reformats only {@code [selStart, selEnd]} in the current file, simulating what
     * IntelliJ does when the user presses Ctrl+Alt+L with a text selection.
     *
     * <p>The editor's {@code SelectionModel} is set to the requested range inside the
     * write action (which runs on EDT) so that the selection is in place when
     * {@link org.jfxcore.fxml.lang.Fxml2EmbeddedMarkupFormattingProcessor} queries it
     * during post-processing.  The selection is cleared again after the write command
     * completes so it does not leak into subsequent tests.
     */
    private void reformatSelection(int selStart, int selEnd) {
        getFixture().doHighlighting();

        WriteCommandAction.runWriteCommandAction(getFixture().getProject(), () -> {
            // Set the selection before reformatting (we are on the EDT inside the write
            // command). The PostFormatProcessor will query this selection to determine
            // which FXML lines to reformat.
            getFixture().getEditor().getSelectionModel().setSelection(selStart, selEnd);

            CodeStyleManager.getInstance(getFixture().getProject())
                    .reformatText(getFixture().getFile(), selStart, selEnd);

            // Clear the selection so it does not affect subsequent tests.
            getFixture().getEditor().getSelectionModel().removeSelection();
        });
    }

    /** Configures a Java file with a top-level @ComponentView annotation. */
    private void configure(String markupBody) {
        getFixture().configureByText("TestView.java",
                """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                """ + markupBody + """
                \""")
                public class TestView {
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);
    }

    // -----------------------------------------------------------------------
    // Completely wrong indentation (markup at column 0, no nesting indent)
    // -----------------------------------------------------------------------

    @Test
    void wrongIndentationIsFixed() {
        configure("""
                <StackPane>
                <VBox>
                <Button text="OK"/>
                </VBox>
                </StackPane>
                """);

        reformat();

        String text = getFixture().getFile().getText();
        // The text block content must start at the expected indent levels:
        // <StackPane> at 4 spaces, <VBox> at 6 spaces, <Button> at 8 spaces.
        assertContainsLine(text, "    <StackPane>");
        assertContainsLine(text, "      <VBox>");
        assertContainsLine(text, "        <Button text=\"OK\"/>");
        assertContainsLine(text, "      </VBox>");
        assertContainsLine(text, "    </StackPane>");
    }

    // -----------------------------------------------------------------------
    // Already-correct markup remains unchanged (idempotency)
    // -----------------------------------------------------------------------

    @Test
    void alreadyCorrectMarkupIsIdempotent() {
        // Content lines are indented 4 extra spaces relative to the closing """,
        // so the resulting string starts with "    <StackPane>" etc.
        configure("""
                    <StackPane>
                      <VBox>
                        <Button text="OK"/>
                      </VBox>
                    </StackPane>
                """);

        // First reformat: Java formatter + our PostFormatProcessor both run.
        // The Java formatter may change the class body (expand inline {}, add blank lines).
        // Our processor ensures the XML content has the correct indentation.
        reformat();
        String after1 = getFixture().getFile().getText();

        // Second reformat: now the whole file is already in the post-format state.
        // The Java formatter should not change the class body (already formatted).
        // Our processor should produce the exact same XML content as after the first reformat.
        reformat();
        String after2 = getFixture().getFile().getText();

        // The XML content is correct in both after1 and after2.
        assertContainsLine(after1, "    <StackPane>");
        assertContainsLine(after1, "      <VBox>");
        assertContainsLine(after1, "        <Button text=\"OK\"/>");
        // Verify true idempotency: second reformat == first reformat.
        assertEquals(after1, after2, "Reformatting already-correct markup should be a no-op on second pass");
    }

    // -----------------------------------------------------------------------
    // Over-indented markup is corrected
    // -----------------------------------------------------------------------

    @Test
    void overIndentedMarkupIsFixed() {
        // Content lines are indented 16 extra spaces relative to the closing """,
        // so the resulting string starts with "                <StackPane>" etc.
        configure("""
                                <StackPane>
                                    <VBox>
                                        <Button text="OK"/>
                                    </VBox>
                                </StackPane>
                """);

        reformat();

        String text = getFixture().getFile().getText();
        assertContainsLine(text, "    <StackPane>");
        assertContainsLine(text, "      <VBox>");
        assertContainsLine(text, "        <Button text=\"OK\"/>");
    }

    // -----------------------------------------------------------------------
    // Correct indent with multi-level nesting
    // -----------------------------------------------------------------------

    @Test
    void multiLevelNesting() {
        configure("""
                <StackPane>
                <VBox>
                <HBox>
                <Button text="OK"/>
                </HBox>
                </VBox>
                </StackPane>
                """);

        reformat();

        String text = getFixture().getFile().getText();
        // base=4, VBox at 6, HBox at 8, Button at 10
        assertContainsLine(text, "    <StackPane>");
        assertContainsLine(text, "      <VBox>");
        assertContainsLine(text, "        <HBox>");
        assertContainsLine(text, "          <Button text=\"OK\"/>");
        assertContainsLine(text, "        </HBox>");
        assertContainsLine(text, "      </VBox>");
        assertContainsLine(text, "    </StackPane>");
    }

    // -----------------------------------------------------------------------
    // Custom XML indent size (4 instead of 2)
    // -----------------------------------------------------------------------

    @Test
    void customXmlIndentFour() {
        // Override XML indent to 4 for this test
        CodeStyleSettings settings = CodeStyle.getSettings(getFixture().getProject());
        var xmlOpts = settings.getCommonSettings(XMLLanguage.INSTANCE).getIndentOptions();
        if (xmlOpts != null) xmlOpts.INDENT_SIZE = 4;

        configure("""
                <StackPane>
                <VBox>
                <Button text="OK"/>
                </VBox>
                </StackPane>
                """);

        reformat();

        String text = getFixture().getFile().getText();
        // base=4, VBox at 8, Button at 12
        assertContainsLine(text, "    <StackPane>");
        assertContainsLine(text, "        <VBox>");
        assertContainsLine(text, "            <Button text=\"OK\"/>");
    }

    // -----------------------------------------------------------------------
    // Attributes with binding expressions are preserved verbatim
    // -----------------------------------------------------------------------

    @Test
    void attributesWithBindingExpressionsPreserved() {
        configure("""
                <StackPane>
                <VBox spacing="10" alignment="CENTER">
                <TextField text="{fx:Synchronize model.statusText}" maxWidth="200"/>
                <Label text="{fx:Observe model.displayText}"/>
                </VBox>
                </StackPane>
                """);

        reformat();

        String text = getFixture().getFile().getText();
        assertContainsLine(text, "    <StackPane>");
        assertContainsLine(text, "      <VBox spacing=\"10\" alignment=\"CENTER\">");
        assertContainsLine(text, "        <TextField text=\"{fx:Synchronize model.statusText}\" maxWidth=\"200\"/>");
        assertContainsLine(text, "        <Label text=\"{fx:Observe model.displayText}\"/>");
        assertContainsLine(text, "      </VBox>");
    }

    // -----------------------------------------------------------------------
    // Self-closing root element
    // -----------------------------------------------------------------------

    @Test
    void selfClosingRoot() {
        configure("<Button text=\"OK\"/>\n");

        reformat();

        String text = getFixture().getFile().getText();
        assertContainsLine(text, "    <Button text=\"OK\"/>");
    }

    // -----------------------------------------------------------------------
    // Mixed indentation: one element at column 0, siblings correctly indented
    // -----------------------------------------------------------------------

    @Test
    void mixedIndentationIsNormalized() {
        // One element is deliberately at column 0 (bad indentation); the formatter
        // must move it to the correct column while keeping the other elements in place.
        // The closing """ is at column 0 so the common indent is 0 and all leading
        // spaces in the text block are preserved as-is.
        configure("""
    <StackPane>
      <VBox spacing="10" alignment="CENTER">
        <TextField text="{fx:Synchronize model.statusText}" maxWidth="200"/>
        <Label text="{fx:Observe model.displayText}"/>
<Button text="Say hello" Command.onAction="$model.actionCommand"/>
      </VBox>
    </StackPane>
""");

        reformat();

        String text = getFixture().getFile().getText();
        // The element that was at column 0 must now be at the correct indent (8 spaces)
        assertContainsLine(text,
                "        <Button text=\"Say hello\" Command.onAction=\"$model.actionCommand\"/>");
        // Correctly-indented siblings must be unchanged
        assertContainsLine(text, "    <StackPane>");
        assertContainsLine(text, "      <VBox spacing=\"10\" alignment=\"CENTER\">");
    }

    // -----------------------------------------------------------------------
    // Partial selection: only selected lines are reformatted
    // -----------------------------------------------------------------------

    /**
     * Verifies that when the user reformats with a selection, <em>only</em> the lines
     * that fall within the selection are reformatted.  Lines outside the selection must
     * remain exactly as they were, even if they have wrong indentation.
     *
     * <p>Scenario: all five FXML lines start at column 0 (wrong).  The selection covers
     * only the {@code <VBox>} and {@code <Button>} lines.  After a partial reformat:
     * <ul>
     *   <li>{@code <VBox>} and {@code <Button>} must be at the correct indent (6 and 8
     *       spaces respectively).</li>
     *   <li>{@code <StackPane>}, {@code </VBox>}, and {@code </StackPane>} must still
     *       be at column 0: the formatter must not touch them.</li>
     * </ul>
     */
    @Test
    void partialSelectionOnlyReformatsSelectedLines() {
        // All lines have wrong indentation (column 0).
        configure("""
                <StackPane>
                <VBox>
                <Button text="OK"/>
                </VBox>
                </StackPane>
                """);

        String initialText = getFixture().getFile().getText();

        // Select only the <VBox> and <Button> lines.
        // The selection ends just before </VBox> so that </VBox> is NOT selected.
        int selStart = initialText.indexOf("<VBox>");
        int selEnd   = initialText.indexOf("</VBox>"); // exclusive: selection stops before </VBox>

        reformatSelection(selStart, selEnd);

        String result = getFixture().getFile().getText();

        // Selected lines must be reformatted:
        //   base indent = annotationColumn(0) + javaIndentSize(4) = 4
        //   <VBox>   at XML nesting level 1: 4 + 1*2 = 6 spaces
        //   <Button> at XML nesting level 2: 4 + 2*2 = 8 spaces
        assertContainsLine(result, "      <VBox>");
        assertContainsLine(result, "        <Button text=\"OK\"/>");

        // Unselected lines must remain at column 0 (not reformatted).
        assertContainsLine(result, "<StackPane>");
        assertContainsLine(result, "</VBox>");
        assertContainsLine(result, "</StackPane>");
    }

    /**
     * When FXML content has mixed indentation (not all at column 0), the closing
     * {@code """} is at 8 spaces, and only the first three content lines are selected
     * (the {@code <StackPane>}, {@code <VBox>}, and {@code <TextField>} elements),
     *
     * <p>Expected result:
     * <ul>
     *   <li>The three selected lines must receive correct XML indentation.</li>
     *   <li>The remaining lines ({@code <Label>}, {@code <Button>}, {@code </VBox>},
     *       {@code </StackPane>}) must remain exactly as they were.</li>
     * </ul>
     */
    @Test
    void partialSelectionWithMixedIndentationAndNonZeroClosingDelimiter() {
        // The host class follows the standard @ComponentView structure.
        // Closing """ is at 8 spaces; content has mixed indentation.
        getFixture().configureByText("TestView.java",
                "package test;\n"
                + "import org.jfxcore.markup.ComponentView;\n"
                + "import javafx.scene.layout.*;\n"
                + "import javafx.scene.control.*;\n"
                + "@ComponentView(\"\"\"\n"
                + "            <StackPane>\n"                  // 12 spaces, line 10 (1-based)
                + "        <VBox spacing=\"10\">\n"            // 8 spaces  - line 11
                + "                    <TextField/>\n"         // 20 spaces, line 12
                + "                    <Label/>\n"             // 20 spaces, line 13
                + "                    <Button/>\n"            // 20 spaces, line 14
                + "                </VBox>\n"                  // 16 spaces, line 15
                + "            </StackPane>\n"                 // 12 spaces, line 16
                + "        \"\"\")\n"                          // 8 spaces before closing """
                + "public class TestView {\n"
                + "    protected void initializeComponent() {}\n"
                + "    public TestView() { initializeComponent(); }\n"
                + "}\n");

        String initialText = getFixture().getFile().getText();

        // Select lines 10-12 (1-based): <StackPane>, <VBox ...>, <TextField/>.
        // selStart = start of line 10 = start of "            <StackPane>"
        // selEnd   = start of line 13 = start of "                    <Label/>"
        int selStart = initialText.indexOf("            <StackPane>");
        int selEnd   = initialText.indexOf("                    <Label/>");

        reformatSelection(selStart, selEnd);

        String result = getFixture().getFile().getText();

        // Selected lines must be reformatted to correct XML indentation.
        // annotationColumn=0, javaIndentSize=4 -> base=4
        // <StackPane> at level 0:  4 + 0*2 = 4 spaces
        // <VBox>      at level 1:  4 + 1*2 = 6 spaces
        // <TextField> at level 2:  4 + 2*2 = 8 spaces
        assertContainsLine(result, "    <StackPane>");
        assertContainsLine(result, "      <VBox spacing=\"10\">");
        assertContainsLine(result, "        <TextField/>");

        // Unselected lines must remain EXACTLY as they were (not reformatted).
        assertContainsLine(result, "                    <Label/>");
        assertContainsLine(result, "                    <Button/>");
        assertContainsLine(result, "                </VBox>");
        assertContainsLine(result, "            </StackPane>");
    }

    /**
     * Same as {@link #partialSelectionOnlyReformatsSelectedLines} but simulates the
     * scenario where {@link com.intellij.codeInsight.actions.ReformatCodeAction}
     * (which has {@code setInjectedContext(true)}) receives the <em>injected</em>
     * XML {@link PsiFile} rather than the Java host: because the user's caret was
     * inside the FXML injection when they pressed Ctrl+Alt+L.
     *
     * <p>In this path {@code CodeStyleManager.reformatText} is called with the
     * injected file, so {@code beforeReformatText} fires for the injected file.
     * Our {@link org.jfxcore.fxml.lang.Fxml2ReformatSnapshotTracker} must detect
     * this and capture the snapshot from the host file, and
     * {@link org.jfxcore.fxml.lang.Fxml2EmbeddedMarkupFormattingProcessor}
     * must use the snapshot to apply partial changes.
     */
    @Test
    void partialSelectionViaInjectedFileOnlyReformatsSelectedLines() {
        // All lines have wrong indentation (column 0).
        configure("""
                <StackPane>
                <VBox>
                <Button text="OK"/>
                </VBox>
                </StackPane>
                """);

        getFixture().doHighlighting();

        String initialText = getFixture().getFile().getText();

        // Select only the <VBox> and <Button> lines (in HOST document coordinates).
        int selStart = initialText.indexOf("<VBox>");
        int selEnd   = initialText.indexOf("</VBox>"); // exclusive: selection stops before </VBox>

        // Get the injected XML file (simulates the cursor being inside the injection).
        XmlFile injectedFile = getInjectedXmlFile();
        assertNotNull(injectedFile, "Injected file should be present after doHighlighting()");

        final int injectedLength = injectedFile.getTextLength();

        WriteCommandAction.runWriteCommandAction(getFixture().getProject(), () -> {
            // Set the selection on the HOST editor: this is what captureEditorSelection reads
            // when beforeReformatText fires for the injected file.
            getFixture().getEditor().getSelectionModel().setSelection(selStart, selEnd);

            // Simulate: ReformatCodeAction (setInjectedContext=true) calls
            // reformatText on the INJECTED file, not the Java host file.
            CodeStyleManager.getInstance(getFixture().getProject())
                    .reformatText(injectedFile, 0, injectedLength);

            getFixture().getEditor().getSelectionModel().removeSelection();
        });

        String result = getFixture().getFile().getText();

        // Selected lines must be reformatted:
        //   base indent = annotationColumn(0) + javaIndentSize(4) = 4
        //   <VBox>   at XML nesting level 1: 4 + 1*2 = 6 spaces
        //   <Button> at XML nesting level 2: 4 + 2*2 = 8 spaces
        assertContainsLine(result, "      <VBox>");
        assertContainsLine(result, "        <Button text=\"OK\"/>");

        // Unselected lines must remain at column 0 (not reformatted).
        assertContainsLine(result, "<StackPane>");
        assertContainsLine(result, "</VBox>");
        assertContainsLine(result, "</StackPane>");
    }

    // -----------------------------------------------------------------------
    // Processing instruction before root element
    // -----------------------------------------------------------------------

    /**
     * Verifies that a processing instruction (e.g. {@code <?prefix?>}) that precedes the
     * root element receives the same indentation as the root element after reformatting.
     *
     * <p>In standalone fxml2, both a document-level PI and the root element are at column 0.
     * The embedded equivalent must place both at {@code baseIndent} (annotationColumn +
     * javaIndentSize = 0 + 4 = 4 spaces).
     */
    @Test
    void processingInstructionBeforeRootHasSameIndentAsRootElement() {
        configure("""
                <?prefix % = SomeExtension?>
                <StackPane>
                <VBox>
                <Button text="OK"/>
                </VBox>
                </StackPane>
                """);

        reformat();

        String text = getFixture().getFile().getText();
        // The PI and the root element must both be at baseIndent (4 spaces).
        assertContainsLine(text, "    <?prefix % = SomeExtension?>");
        assertContainsLine(text, "    <StackPane>");
        assertContainsLine(text, "      <VBox>");
        assertContainsLine(text, "        <Button text=\"OK\"/>");
        assertContainsLine(text, "      </VBox>");
        assertContainsLine(text, "    </StackPane>");
    }

    /**
     * Verifies that an XML comment that precedes the root element receives the same
     * indentation as the root element after reformatting.
     *
     * <p>In standalone FXML, a document-level comment and the root element are both at
     * column 0. The embedded equivalent must place both at {@code baseIndent}
     * (annotationColumn + javaIndentSize = 0 + 4 = 4 spaces).
     */
    @Test
    void xmlCommentBeforeRootHasSameIndentAsRootElement() {
        configure("""
                <!-- a leading comment -->
                <StackPane>
                <VBox>
                <Button text="OK"/>
                </VBox>
                </StackPane>
                """);

        reformat();

        String text = getFixture().getFile().getText();
        // Both the comment and the root element must be at baseIndent (4 spaces).
        assertContainsLine(text, "    <!-- a leading comment -->");
        assertContainsLine(text, "    <StackPane>");
        assertContainsLine(text, "      <VBox>");
        assertContainsLine(text, "        <Button text=\"OK\"/>");
        assertContainsLine(text, "      </VBox>");
        assertContainsLine(text, "    </StackPane>");
    }

    // -----------------------------------------------------------------------
    // Helper assertions
    // -----------------------------------------------------------------------

    private static void assertContainsLine(@NotNull String text, @NotNull String expectedLine) {
        boolean found = false;
        for (String line : text.split("\n")) {
            if (line.equals(expectedLine)) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new AssertionError(
                    "Expected to find line:\n  [" + expectedLine + "]\n"
                    + "in text:\n" + text);
        }
    }

    /**
     * Returns the embedded FXML injected {@link XmlFile} contained in the current
     * test fixture's Java file, or {@code null} if no such injection is found.
     *
     * <p>Must be called after {@link com.intellij.testFramework.fixtures.CodeInsightTestFixture#doHighlighting()}
     * so that the language injection framework has had a chance to create the injected fragment.
     */
    private @Nullable XmlFile getInjectedXmlFile() {
        return com.intellij.openapi.application.ApplicationManager.getApplication()
                .runReadAction((com.intellij.openapi.util.Computable<XmlFile>) () -> {
                    PsiFile javaFile = getFixture().getFile();

                    // Find the @ComponentView text-block literal that hosts the injection.
                    final PsiLiteralExpression[] ref = {null};
                    javaFile.accept(new JavaRecursiveElementWalkingVisitor() {
                        @Override
                        public void visitLiteralExpression(@NotNull PsiLiteralExpression expr) {
                            super.visitLiteralExpression(expr);
                            if (expr.isTextBlock()) ref[0] = expr;
                        }
                    });
                    PsiLiteralExpression literal = ref[0];
                    if (literal == null) return null;

                    var injectedList = InjectedLanguageManager.getInstance(javaFile.getProject())
                            .getInjectedPsiFiles(literal);
                    if (injectedList == null || injectedList.isEmpty()) return null;

                    PsiFile injected = injectedList.getFirst().first.getContainingFile();
                    if (!(injected instanceof XmlFile xml)) return null;
                    return Fxml2EmbeddedUtil.isEmbeddedFxml2(xml) ? xml : null;
                });
    }
}
