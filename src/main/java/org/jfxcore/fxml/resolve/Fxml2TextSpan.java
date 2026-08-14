package org.jfxcore.fxml.resolve;

import org.jetbrains.annotations.NotNull;

/**
 * A span of text, used by the parsers that split a value into the parts it consists of.
 *
 * @param start the first offset of the span
 * @param end   the offset after the last one of the span
 */
record Fxml2TextSpan(int start, int end) {

    /** Returns the span between {@code start} and {@code end} without its surrounding whitespace. */
    static @NotNull Fxml2TextSpan trimmed(@NotNull String text, int start, int end) {
        int begin = start;
        while (begin < end && Character.isWhitespace(text.charAt(begin))) begin++;
        int finish = end;
        while (finish > begin && Character.isWhitespace(text.charAt(finish - 1))) finish--;
        return new Fxml2TextSpan(begin, finish);
    }

    /** Whether the span contains no characters. */
    boolean isEmpty() {
        return start >= end;
    }

    /** Returns the text of the span. */
    @NotNull String textOf(@NotNull String text) {
        return text.substring(start, end);
    }
}
