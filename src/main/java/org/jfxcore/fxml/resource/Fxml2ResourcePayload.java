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
 * @param text          the normalized resource content
 * @param sourceOffsets for each content offset {@code i}, the offset of that character in the
 *                      source; the array has one extra entry mapping the end of the content
 */
public record Fxml2ResourcePayload(@NotNull String text, int @NotNull [] sourceOffsets) {

    public Fxml2ResourcePayload {
        if (sourceOffsets.length != text.length() + 1) {
            throw new IllegalArgumentException("sourceOffsets must have one entry per content offset plus one");
        }
        sourceOffsets = sourceOffsets.clone();
    }

    @Override
    public int @NotNull [] sourceOffsets() {
        return sourceOffsets.clone();
    }

    /** Returns the source offset of the content character at {@code offset}. */
    public int sourceOffset(int offset) {
        return sourceOffsets[Math.max(0, Math.min(offset, text.length()))];
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
