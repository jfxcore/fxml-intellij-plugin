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
 * a block or the payload itself.  Which step that is depends on what the line opens: a line that
 * opens the payload is followed by content placed as markup, so it advances by the markup step,
 * while a line that opens a block inside the payload advances by the step of the payload language.
 * The rule is otherwise independent of the payload language: it holds for a stylesheet as well as
 * for any other block-structured payload, and it never reindents text that is already written.
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
     * @param linePrefix   the text of the caret line up to the caret
     * @param markupStep   the step markup is indented in, which a payload starting on its own line
     *                     is placed at
     * @param payloadStep  the step the payload language is indented in
     * @param opensPayload whether the payload of the declaration starts on the caret line, which
     *                     indents its body one markup step in from the declaration
     */
    public static @NotNull Fxml2PayloadIndent of(@NotNull CharSequence linePrefix,
                                                 @NotNull Fxml2IndentStep markupStep,
                                                 @NotNull Fxml2IndentStep payloadStep,
                                                 boolean opensPayload) {
        int leading = 0;
        while (leading < linePrefix.length() && linePrefix.charAt(leading) == ' ') {
            leading++;
        }

        if (opensPayload) return new Fxml2PayloadIndent(leading + markupStep.width());

        String trimmed = linePrefix.toString().stripTrailing();
        boolean opensBlock = !trimmed.isEmpty() && trimmed.charAt(trimmed.length() - 1) == BLOCK_START;
        return new Fxml2PayloadIndent(opensBlock ? leading + payloadStep.width() : leading);
    }

    /** Returns the indentation as the whitespace a line starts with. */
    public @NotNull String text() {
        return " ".repeat(width);
    }
}
