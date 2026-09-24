// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import org.jfxcore.fxml.lang.Fxml2IndentStep;
import org.jfxcore.fxml.lang.Fxml2PayloadIndent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the indentation a line opened inside a resource payload receives.
 *
 * <p>The two steps are deliberately different widths throughout, so that every assertion says
 * which of them the line advanced by: markup steps place the payload, and the payload language
 * shapes what is written inside it.
 */
class Fxml2PayloadIndentTest {

    /** The step markup nests in. */
    private static final Fxml2IndentStep MARKUP = new Fxml2IndentStep(2);

    /** The step the payload language nests in. */
    private static final Fxml2IndentStep PAYLOAD = new Fxml2IndentStep(4);

    /** A line that does not open a block is continued at its own indentation. */
    @Test
    void continuedLineKeepsIndentation() {
        assertEquals("    ", indentAfter("    -fx-text-fill: red;"));
    }

    /** A line that opens a block is continued one payload step further in. */
    @Test
    void blockStartAddsOnePayloadStep() {
        assertEquals("        ", indentAfter("    .my-style {"));
    }

    /** Trailing whitespace after a block start does not hide it. */
    @Test
    void trailingWhitespaceDoesNotHideBlockStart() {
        assertEquals("    ", indentAfter("{   "));
    }

    /** A line holding only the declaration prefix opens the payload one markup step in. */
    @Test
    void declarationLineOpensPayloadOneMarkupStep() {
        assertEquals("  ", Fxml2PayloadIndent.of("<?resource styles.css text/css:", MARKUP, PAYLOAD, true).text());
    }

    /** A line inside the payload that neither opens a block nor the payload keeps its column. */
    @Test
    void unindentedLineIsContinuedAtColumnZero() {
        assertEquals("", indentAfter("-fx-text-fill: red;"));
    }

    /** An indentation is never negative. */
    @Test
    void negativeWidthIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Fxml2PayloadIndent(-1));
    }

    private static String indentAfter(String linePrefix) {
        return Fxml2PayloadIndent.of(linePrefix, MARKUP, PAYLOAD, false).text();
    }
}
