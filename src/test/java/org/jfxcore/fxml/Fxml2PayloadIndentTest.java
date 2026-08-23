// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import org.jfxcore.fxml.lang.Fxml2PayloadIndent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies the indentation a line opened inside a resource payload receives. */
class Fxml2PayloadIndentTest {

    /** A line that does not open a block is continued at its own indentation. */
    @Test
    void continuedLineKeepsIndentation() {
        assertEquals("    ", Fxml2PayloadIndent.of("    -fx-text-fill: red;", 2).text());
    }

    /** A line that opens a block is continued one step further in. */
    @Test
    void blockStartAddsOneStep() {
        assertEquals("      ", Fxml2PayloadIndent.of("    .my-style {", 2).text());
    }

    /** Trailing whitespace after a block start does not hide it. */
    @Test
    void trailingWhitespaceDoesNotHideBlockStart() {
        assertEquals("  ", Fxml2PayloadIndent.of("{   ", 2).text());
    }

    /** A line holding only the declaration prefix opens the payload one step in. */
    @Test
    void declarationLineOpensPayloadOneStep() {
        assertEquals("  ", Fxml2PayloadIndent.of("<?resource styles.css text/css:", 2, true).text());
    }

    /** A line inside the payload that neither opens a block nor the payload keeps its column. */
    @Test
    void unindentedLineIsContinuedAtColumnZero() {
        assertEquals("", Fxml2PayloadIndent.of("-fx-text-fill: red;", 2).text());
    }

    /** An indentation is never negative. */
    @Test
    void negativeWidthIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Fxml2PayloadIndent(-1));
    }
}
