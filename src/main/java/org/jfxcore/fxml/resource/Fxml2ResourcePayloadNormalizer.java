package org.jfxcore.fxml.resource;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the raw text between a resource declaration's colon and its {@code ?>} terminator into
 * the resource content, and back again.
 *
 * <p>Normalization exists so that the FXML indentation used to lay a declaration out does not
 * become part of the resource.  The rules are applied in this order:
 *
 * <ol>
 *   <li>If the colon is followed only by spaces or tabs and then a line break, that indentation
 *       and line break are removed.</li>
 *   <li>If the final line break is followed only by spaces or tabs before {@code ?>}, that line
 *       break and indentation are removed.</li>
 *   <li>The longest identical leading run of spaces and tabs shared by every non-blank content
 *       line is removed from those lines.  A blank line loses at most that same run and keeps any
 *       whitespace beyond it.</li>
 * </ol>
 *
 * <p>A same-line payload is preserved exactly, including the space immediately after the colon
 * and immediately before {@code ?>}.  Line endings are assumed to be normalized to {@code \n}
 * already, which is what the platform guarantees for document text.
 *
 * <p>Normalization is offset-preserving in the sense that every character of the result remembers
 * where it came from, so a diagnostic about a character in the content can be highlighted at its
 * position in the source.  {@link #reindent} performs the inverse operation, which is what lets
 * markup move between a standalone document and an annotation value without changing the resource.
 */
public final class Fxml2ResourcePayloadNormalizer {

    private Fxml2ResourcePayloadNormalizer() {}

    /**
     * Normalizes the raw payload occupying {@code [start, end)} of {@code source}.
     *
     * @param source the text the payload is part of
     * @param start  the first offset of the raw payload, immediately after the colon
     * @param end    the offset after the last one of the raw payload, immediately before {@code ?>}
     * @return the resource content, mapped back onto {@code source}
     */
    public static @NotNull Fxml2ResourcePayload normalize(@NotNull String source, int start, int end) {
        MappedText payload = new MappedText(source, start, end);

        int opening = 0;
        boolean removedOpeningLine = false;
        while (opening < payload.length() && isHorizontalWhitespace(payload.charAt(opening))) {
            ++opening;
        }

        if (opening < payload.length() && payload.charAt(opening) == '\n') {
            payload.remove(0, opening + 1);
            removedOpeningLine = true;
        }

        int lastLineBreak = payload.lastLineBreak();
        if (lastLineBreak >= 0 && isHorizontalWhitespace(payload, lastLineBreak + 1, payload.length())) {
            payload.remove(lastLineBreak, payload.length());
        }

        if (!removedOpeningLine && payload.lastLineBreak() < 0) {
            return payload.toPayload();
        }

        List<Line> lines = splitLines(payload);
        String commonIndent = commonIndentOf(payload, lines);
        if (commonIndent.isEmpty()) {
            return payload.toPayload();
        }

        for (int i = lines.size() - 1; i >= 0; --i) {
            Line line = lines.get(i);
            int length = strippableLength(payload, line, commonIndent);
            if (length > 0) {
                payload.remove(line.start(), line.start() + length);
            }
        }

        return payload.toPayload();
    }

    /**
     * Returns the longest leading run of spaces and tabs shared by every non-blank line, which is
     * the indentation normalization removes.  Returns an empty string when there is none.
     */
    public static @NotNull String commonIndentOf(@NotNull String content) {
        MappedText text = new MappedText(content, 0, content.length());
        return commonIndentOf(text, splitLines(text));
    }

    /**
     * Returns the raw payload text that lays {@code content} out at {@code indent}, that is, the
     * text a declaration would carry between its colon and its {@code ?>} terminator.
     *
     * <p>Content that contains no line break is written on the declaration line unchanged, which
     * preserves it exactly, leading and trailing spaces included.  Multi-line content is written
     * one line per content line, each non-blank line prefixed with {@code indent}; blank lines
     * stay blank so that reindenting never introduces trailing whitespace.
     *
     * <p>{@code normalize(reindent(content, indent))} is {@code content} for every indentation
     * consisting of spaces and tabs, and for every content that {@link #normalize} can produce.
     * Multi-line content whose lines all share a leading run of spaces or tabs is outside that
     * set: such content is not expressible in a declaration at all, because normalization would
     * strip the shared run again.
     *
     * @param content the resource content
     * @param indent  the indentation to lay the content out at
     */
    public static @NotNull String reindent(@NotNull String content, @NotNull String indent) {
        if (content.indexOf('\n') < 0) {
            return content;
        }

        StringBuilder result = new StringBuilder("\n");
        int start = 0;
        while (start <= content.length()) {
            int lineBreak = content.indexOf('\n', start);
            int lineEnd = lineBreak < 0 ? content.length() : lineBreak;
            if (lineEnd > start) {
                result.append(indent).append(content, start, lineEnd);
            }
            result.append('\n');
            if (lineBreak < 0) break;
            start = lineBreak + 1;
        }

        return result.append(indent).toString();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Returns the number of characters normalization removes from {@code line}. */
    private static int strippableLength(@NotNull MappedText text, @NotNull Line line, @NotNull String commonIndent) {
        if (!isHorizontalWhitespace(text, line.start(), line.end())) {
            return commonIndent.length();
        }

        // A blank line loses at most the common indent, and keeps any whitespace beyond it.
        int length = 0;
        while (length < commonIndent.length()
                && line.start() + length < line.end()
                && text.charAt(line.start() + length) == commonIndent.charAt(length)) {
            ++length;
        }

        return length;
    }

    private static @NotNull String commonIndentOf(@NotNull MappedText text, @NotNull List<Line> lines) {
        String commonIndent = null;

        for (Line line : lines) {
            if (isHorizontalWhitespace(text, line.start(), line.end())) continue;

            int indentEnd = line.start();
            while (indentEnd < line.end() && isHorizontalWhitespace(text.charAt(indentEnd))) {
                ++indentEnd;
            }

            String indent = text.substring(line.start(), indentEnd);
            commonIndent = commonIndent == null ? indent : commonPrefix(commonIndent, indent);
            if (commonIndent.isEmpty()) break;
        }

        return commonIndent == null ? "" : commonIndent;
    }

    private static @NotNull List<Line> splitLines(@NotNull MappedText text) {
        List<Line> lines = new ArrayList<>();
        int start = 0;

        for (int i = 0; i <= text.length(); ++i) {
            if (i == text.length() || text.charAt(i) == '\n') {
                lines.add(new Line(start, i));
                start = i + 1;
            }
        }

        return lines;
    }

    private static @NotNull String commonPrefix(@NotNull String left, @NotNull String right) {
        int limit = Math.min(left.length(), right.length());
        int index = 0;
        while (index < limit && left.charAt(index) == right.charAt(index)) {
            ++index;
        }

        return left.substring(0, index);
    }

    private static boolean isHorizontalWhitespace(char character) {
        return character == ' ' || character == '\t';
    }

    private static boolean isHorizontalWhitespace(@NotNull MappedText text, int start, int end) {
        for (int i = start; i < end; ++i) {
            if (!isHorizontalWhitespace(text.charAt(i))) return false;
        }
        return true;
    }

    /** A line of the payload, in the coordinates of the text it was split from. */
    private record Line(int start, int end) {}

    /**
     * A mutable slice of the source text that remembers, for every character it still holds, the
     * offset that character has in the source.  Removing a range is the only mutation, which is
     * all normalization needs.
     */
    private static final class MappedText {

        private final char[] characters;
        private final int[] sourceOffsets;
        private int length;

        MappedText(@NotNull String source, int start, int end) {
            this.length = end - start;
            this.characters = new char[length];
            this.sourceOffsets = new int[length + 1];
            for (int i = 0; i < length; ++i) {
                characters[i] = source.charAt(start + i);
                sourceOffsets[i] = start + i;
            }
            sourceOffsets[length] = end;
        }

        int length() {
            return length;
        }

        char charAt(int index) {
            return characters[index];
        }

        @NotNull String substring(int start, int end) {
            return new String(characters, start, end - start);
        }

        /** Returns the offset of the last line break, or {@code -1} when the text has none. */
        int lastLineBreak() {
            for (int i = length - 1; i >= 0; --i) {
                if (characters[i] == '\n') return i;
            }
            return -1;
        }

        void remove(int start, int end) {
            int removed = end - start;
            if (removed <= 0) return;
            System.arraycopy(characters, end, characters, start, length - end);
            System.arraycopy(sourceOffsets, end, sourceOffsets, start, length - end + 1);
            length -= removed;
        }

        @NotNull Fxml2ResourcePayload toPayload() {
            int[] offsets = new int[length + 1];
            System.arraycopy(sourceOffsets, 0, offsets, 0, length + 1);
            return new Fxml2ResourcePayload(new String(characters, 0, length), offsets);
        }
    }
}
