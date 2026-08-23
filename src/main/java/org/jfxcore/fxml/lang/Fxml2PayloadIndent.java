// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import org.jetbrains.annotations.NotNull;

/**
 * The indentation a new line receives when it is opened inside the payload of a
 * {@code <?resource ?>} declaration.
 *
 * <p>The payload is written inline in the FXML/2 document, so the line a payload is continued on
 * carries the indentation of the line the caret sits on, one step further in when that line opens
 * a block or the payload itself.  The rule is deliberately independent of the payload language: it
 * holds for a stylesheet as well as for any other block-structured payload, and it never reindents
 * text that is already written.
 *
 * @param width the number of spaces the new line starts with
 */
public record Fxml2PayloadIndent(int width) {

    /** The character a block-opening line ends with. */
    private static final char BLOCK_START = '{';

    public Fxml2PayloadIndent {
        if (width < 0) throw new IllegalArgumentException("width must not be negative: " + width);
    }

    /**
     * Returns the indentation for the line opened after {@code linePrefix}.
     *
     * @param linePrefix the text of the caret line up to the caret
     * @param indentSize the width of one indentation step
     */
    public static @NotNull Fxml2PayloadIndent of(@NotNull CharSequence linePrefix, int indentSize) {
        return of(linePrefix, indentSize, false);
    }

    /**
     * Returns the indentation for the line opened after {@code linePrefix}.
     *
     * @param linePrefix the text of the caret line up to the caret
     * @param indentSize the width of one indentation step
     * @param opensPayload whether the payload of the declaration starts on the caret line, which
     *                     indents its body one step in from the declaration
     */
    public static @NotNull Fxml2PayloadIndent of(@NotNull CharSequence linePrefix,
                                                 int indentSize,
                                                 boolean opensPayload) {
        int leading = 0;
        while (leading < linePrefix.length() && linePrefix.charAt(leading) == ' ') {
            leading++;
        }

        String trimmed = linePrefix.toString().stripTrailing();
        boolean opensBlock = !trimmed.isEmpty() && trimmed.charAt(trimmed.length() - 1) == BLOCK_START;
        return new Fxml2PayloadIndent(opensBlock || opensPayload
                ? leading + Math.max(indentSize, 0)
                : leading);
    }

    /** Returns the indentation as the whitespace a line starts with. */
    public @NotNull String text() {
        return " ".repeat(width);
    }
}
