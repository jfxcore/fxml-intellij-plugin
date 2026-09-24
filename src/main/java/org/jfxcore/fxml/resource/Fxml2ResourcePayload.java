package org.jfxcore.fxml.resource;

import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resolve.Fxml2TextSpan;

/**
 * The content of an embedded resource, together with the mapping back onto the source text it was
 * normalized from.
 *
 * <p>The mapping is what lets a diagnostic about a character of the content be highlighted at the
 * position that character occupies in the document, even though normalization has removed the
 * declaration's layout indentation in between.
 *
 * <p>The source offset table has one entry for every content offset plus one for the end of the
 * content. It remains private so callers use the range and offset mapping operations.
 */
public final class Fxml2ResourcePayload {

    private final String text;
    private final int[] sourceOffsets;

    public Fxml2ResourcePayload(@NotNull String text, int @NotNull [] sourceOffsets) {
        if (sourceOffsets.length != text.length() + 1) {
            throw new IllegalArgumentException("sourceOffsets must have one entry per content offset plus one");
        }
        this.text = text;
        this.sourceOffsets = sourceOffsets.clone();
    }

    /** Returns the normalized resource content. */
    public @NotNull String text() {
        return text;
    }

    /** Returns the source offset of the content character at {@code offset}. */
    public int sourceOffset(int offset) {
        return sourceOffsets[Math.clamp(offset, 0, text.length())];
    }

    /** Returns the span in the source that the content range {@code [start, end)} came from. */
    public @NotNull Fxml2TextSpan sourceSpanOf(int start, int end) {
        return new Fxml2TextSpan(sourceOffset(start), sourceOffset(end));
    }

    /** Returns {@code true} when the content is empty. */
    public boolean isEmpty() {
        return text.isEmpty();
    }
}
