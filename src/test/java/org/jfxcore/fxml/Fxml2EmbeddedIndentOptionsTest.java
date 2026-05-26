// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.application.options.CodeStyle;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that pressing Tab inside embedded FXML markup uses the XML indent size,
 * not the Java indent size.
 *
 * <p>Implementation under test: {@link org.jfxcore.fxml.lang.Fxml2EmbeddedIndentHandlers}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class Fxml2EmbeddedIndentOptionsTest extends Fxml2TestBase {

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

    /** Set XML indent to 2 and ensure Java indent stays at the default 4. */
    @BeforeEach
    void configureIndentSizes() {
        CodeStyleSettings settings = CodeStyle.getSettings(getFixture().getProject());
        var xmlOpts = settings.getCommonSettings(XMLLanguage.INSTANCE).getIndentOptions();
        if (xmlOpts != null) {
            xmlOpts.INDENT_SIZE = 2;
        }
        // Java indent size is left at its default value of 4.
    }

    /**
     * Verifies that pressing Tab at column 0 inside embedded FXML markup inserts 2 spaces
     * (the XML indent size) instead of 4 spaces (the Java indent size).
     *
     * <p>How the assertion works:<br>
     * {@code TabAction.insertTabAtCaret} computes the number of spaces as
     * {@code tabSize: columnNumber % max(1, tabSize)}.  With {@code columnNumber = 0}
     * this simplifies to {@code tabSize}.  So with XML indent = 2 the result is 2 spaces,
     * and with Java indent = 4 the result would be 4 spaces: which is incorrect for XML content.
     */
    @Test
    void tabInEmbeddedMarkupUsesXmlIndentNotJavaIndent() {
        // The caret is positioned at column 0 on the otherwise-empty line between
        // <StackPane> and </StackPane>.  After configureByText strips the <caret>
        // marker, that line is empty, so the caret is at column 0.
        getFixture().configureByText("TestView.java",
                """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane>
                <caret>
                    </StackPane>
                \""")
                public class TestView {
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);

        // Run the highlighting pass so that the language-injection infrastructure
        // registers the embedded FXML XML fragment.  This is required before
        // TabAction can obtain the injected EditorWindow from InjectedLanguageUtil.
        getFixture().doHighlighting();

        // Simulate the user pressing the Tab key.
        getFixture().performEditorAction("EditorTab");

        // Inspect the resulting document text.
        String text = getFixture().getEditor().getDocument().getText();

        // With Fxml2EmbeddedIndentOptionsProvider in place the caret line must now
        // contain exactly 2 spaces (XML indent=2).
        // Without the fix TabAction would use JavaFileType's indent (4 spaces).
        boolean hasTwoSpaceIndent  = text.contains("\n  \n");
        boolean hasFourSpaceIndent = text.contains("\n    \n");

        assertTrue(hasTwoSpaceIndent,
                "Expected Tab to insert 2 spaces (XML indent) inside embedded FXML markup.\n" +
                (hasFourSpaceIndent
                        ? "4 spaces were inserted instead - the Java indent was used.\n"
                        : "") +
                "Document text:\n" + text);
    }

    /**
     * Verifies that pressing Shift+Tab on a line inside embedded FXML markup removes
     * 2 spaces (the XML indent size) instead of 4 spaces (the Java indent size).
     *
     * <p>The line under test starts with 4 spaces (two XML indent levels).  After
     * Shift+Tab with XML indent = 2, exactly 2 spaces should be removed, leaving 2.
     * Without the fix ({@link org.jfxcore.fxml.lang.Fxml2EmbeddedIndentHandlers.UnindentHandler}),
     * the Java indent size of 4 would be used and all 4 spaces would be stripped.
     */
    @Test
    void shiftTabInEmbeddedMarkupUsesXmlIndentNotJavaIndent() {
        // The caret is inside "    <Button/>" (4 leading spaces = 2 XML indent levels).
        // Shift+Tab should remove exactly 2 spaces (XML indent), leaving "  <Button/>".
        // Without the fix the Java indent of 4 would be used, leaving "<Button/>".
        getFixture().configureByText("TestView.java",
                """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane>
                    <Bu<caret>tton/>
                    </StackPane>
                \""")
                public class TestView {
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);

        // Trigger injection.
        getFixture().doHighlighting();

        // Simulate Shift+Tab.
        getFixture().performEditorAction("EditorUnindentSelection");

        String text = getFixture().getEditor().getDocument().getText();

        // With the fix: "    <Button/>" -> "  <Button/>" (removed 2 spaces, XML indent).
        // Without the fix: "    <Button/>" -> "<Button/>" (removed 4 spaces, Java indent).
        boolean hasTwoSpaceIndent  = text.contains("  <Button/>");
        boolean hasNoIndent        = text.contains("\n<Button/>");

        assertTrue(hasTwoSpaceIndent,
                "Expected Shift+Tab to remove 2 spaces (XML indent) inside embedded FXML markup.\n" +
                (hasNoIndent
                        ? "All 4 spaces were removed instead - the Java indent was used.\n"                        : "") +
                "Document text:\n" + text);
    }
}
