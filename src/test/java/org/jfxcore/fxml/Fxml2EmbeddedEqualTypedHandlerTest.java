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
 * Verifies that typing {@code =} after an attribute name in embedded FXML markup
 * inserts double quotes ({@code =""}) rather than single quotes ({@code =''}).
 *
 * <p>Implementation under test:
 * {@link org.jfxcore.fxml.lang.Fxml2EmbeddedEqualTypedHandler}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class Fxml2EmbeddedEqualTypedHandlerTest extends Fxml2TestBase {

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
     * Typing {@code =} after an attribute name in embedded FXML must insert double
     * quotes and place the caret between them ({@code item=""<caret>}).
     * <p>
     * Before the fix, IntelliJ's default XML typed handler would see that the context
     * text starts with {@code '"'} (the opening {@code """} of the Java text block) and
     * therefore insert single quotes ({@code item=''}).
     */
    @Test
    void typingEqualAfterAttributeNameInsertsDoubleQuotes() {
        getFixture().configureByText("TestView.java",
                """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <VBox>
                        <TextField text="hello" maxWidth<caret>/>
                      </VBox>
                    </StackPane>
                \""")
                public class TestView {
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();
        getFixture().type('=');

        String text = getFixture().getEditor().getDocument().getText();

        assertTrue(text.contains("maxWidth=\"\""),
                "Typing '=' after attribute name in embedded FXML must insert double quotes.\n"
                + "Expected 'maxWidth=\"\"' but document was:\n" + text);
    }

    /**
     * Typing {@code ="} (equals then double-quote) letter by letter must produce
     * {@code maxWidth=""} with the caret between the quotes: not {@code maxWidth="""}.
     * <p>
     * When {@code =} is typed the handler auto-inserts {@code =""} and records the
     * between-quotes offset.  When the user then types {@code "}, the handler detects
     * the recorded offset and swallows the keystroke so no extra quote is appended.
     */
    @Test
    void typingEqualThenDoubleQuoteDoesNotProduceExtraQuote() {
        getFixture().configureByText("TestView.java",
                """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <VBox>
                        <TextField text="hello" maxWidth<caret>/>
                      </VBox>
                    </StackPane>
                \""")
                public class TestView {
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();
        getFixture().type('=');
        getFixture().type('"');

        String text = getFixture().getEditor().getDocument().getText();

        assertTrue(text.contains("maxWidth=\"\""),
                "Typing '=\"' should still yield maxWidth=\"\" (closing quote swallowed).\n"
                + "Document was:\n" + text);
        assertFalse(text.contains("maxWidth=\"\"\""),
                "Typing '=\"' must NOT produce triple quotes maxWidth=\"\"\".\n"
                + "Document was:\n" + text);
    }
}
