package org.jfxcore.fxml.resolve;

import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NotNull;

/**
 * A span of text, used by the parsers that split a value into the parts it consists of.
 *
 * @param start the first offset of the span
 * @param end   the offset after the last one of the span
 */
public record Fxml2TextSpan(int start, int end) {

    /** Returns the span between {@code start} and {@code end} without its surrounding whitespace. */
    public static @NotNull Fxml2TextSpan trimmed(@NotNull String text, int start, int end) {
        int begin = start;
        while (begin < end && Character.isWhitespace(text.charAt(begin))) begin++;
        int finish = end;
        while (finish > begin && Character.isWhitespace(text.charAt(finish - 1))) finish--;
        return new Fxml2TextSpan(begin, finish);
    }

    /** Whether the span contains no characters. */
    public boolean isEmpty() {
        return start >= end;
    }

    /** The number of characters the span covers. */
    public int length() {
        return Math.max(0, end - start);
    }

    /** Returns the span shifted by {@code delta}, for mapping a span into another coordinate space. */
    public @NotNull Fxml2TextSpan shifted(int delta) {
        return new Fxml2TextSpan(start + delta, end + delta);
    }

    /** Returns the span as the {@link TextRange} the platform's highlighting and PSI APIs expect. */
    public @NotNull TextRange toTextRange() {
        return TextRange.create(start, Math.max(start, end));
    }

    /** Returns the text of the span. */
    public @NotNull String textOf(@NotNull String text) {
        return text.substring(start, end);
    }
}
