// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that pressing Enter between attributes of an XML tag inside embedded FXML2 markup
 * produces a new line aligned with the first attribute of that tag: matching the behavior
 * of standalone FXML2 files.
 *
 * <p>Implementation under test:
 * {@link org.jfxcore.fxml.lang.Fxml2EmbeddedIndentHandlers.EnterHandler}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class Fxml2EmbeddedEnterHandlerTest extends Fxml2TestBase {

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
     * Verifies that pressing Enter between two attributes of an XML tag in embedded FXML2
     * markup places the next attribute at the same column as the first attribute.
     *
     * <p>Setup: {@code <TextField text="hello" <caret>maxWidth="200"/>}
     *
     * <p>{@code <TextField} is at column 8 (8 leading spaces).
     * {@code text=} starts at column&nbsp;19 (8&nbsp;+&nbsp;{@code "<TextField ".length()} = 8+11).
     * {@code maxWidth} starts at column&nbsp;32 (19&nbsp;+&nbsp;{@code "text=\"hello\" ".length()} = 19+13).
     *
     * <p>After pressing Enter before {@code maxWidth}, the new line must have exactly
     * 19&nbsp;spaces so that {@code maxWidth} aligns with {@code text=}.
     *
     * <p>Without the fix, the Java formatter's indent-adjustment step would be used and would
     * produce 8&nbsp;spaces (aligning {@code maxWidth} with {@code <TextField}).
     */
    @Test
    void enterBetweenAttributesAlignsWithFirstAttribute() {
        getFixture().configureByText("TestView.java",
                """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <VBox>
                        <TextField text="hello" <caret>maxWidth="200"/>
                      </VBox>
                    </StackPane>
                \""")
                public class TestView {
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();
        getFixture().performEditorAction("EditorEnter");

        String text = getFixture().getEditor().getDocument().getText();

        // Column of "text=" in the host document:
        //   8 spaces (tag indent) + "<TextField " (11 chars) = 19
        // -> the new line must start with exactly 19 spaces before "maxWidth".
        boolean correct = text.contains("\n" + " ".repeat(19) + "maxWidth");
        boolean wrong8  = text.contains("\n" + " ".repeat(8)  + "maxWidth");

        assertTrue(correct,
                "Expected Enter between attributes to place 'maxWidth' at column 19 "
                + "(aligned with first attribute 'text=').\n"
                + (wrong8 ? "Got 8-space indent instead (aligned with '<TextField' tag).\n" : "")
                + "Document text:\n" + text);
        assertFalse(wrong8,
                "New line must NOT align with the tag indent (8 spaces);\n"
                + "it must align with the first attribute 'text=' (19 spaces).\n"
                + "Document text:\n" + text);
    }

    /**
     * Verifies that pressing Enter inside an attribute VALUE does NOT trigger the
     * attribute-alignment handler.
     *
     * <p>With the caret inside the value of {@code text}, a normal Java-style newline
     * should be produced (not an attribute-aligned one). The result must not be
     * 19-space-aligned, and the document must still contain both attribute fragments.
     */
    @Test
    void enterInsideAttributeValueIsNotHandled() {
        getFixture().configureByText("TestView.java",
                """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <VBox>
                        <TextField text="hel<caret>lo" maxWidth="200"/>
                      </VBox>
                    </StackPane>
                \""")
                public class TestView {
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();
        getFixture().performEditorAction("EditorEnter");

        String text = getFixture().getEditor().getDocument().getText();

        // Attribute-alignment handler must not fire; the result should NOT be
        // 19-space-aligned for "lo" (which is the continuation of the attribute value).
        assertFalse(text.contains("\n" + " ".repeat(19) + "lo"),
                "Attribute-alignment handler must not fire inside an attribute value.\n"
                + "Document text:\n" + text);
    }

    /**
     * Verifies that pressing Enter before the first attribute of an XML tag does NOT
     * trigger the attribute-alignment handler (no previous attribute to align with).
     */
    @Test
    void enterBeforeFirstAttributeIsNotHandled() {
        getFixture().configureByText("TestView.java",
                """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <VBox>
                        <TextField <caret>text="hello" maxWidth="200"/>
                      </VBox>
                    </StackPane>
                \""")
                public class TestView {
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();
        getFixture().performEditorAction("EditorEnter");

        String text = getFixture().getEditor().getDocument().getText();

        // The handler must not fire when there is no previous attribute;
        // the result must NOT be 19-space-aligned for "text=".
        assertFalse(text.contains("\n" + " ".repeat(19) + "text"),
                "Attribute-alignment handler must not fire before the first attribute.\n"
                + "Document text:\n" + text);
    }
}
