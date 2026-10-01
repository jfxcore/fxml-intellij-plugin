package org.jfxcore.fxml.highlighting;

import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.TreeMap;

/** Builds disjoint spans, allowing a more specific role to replace part of a broader one. */
final class Fxml2SemanticSpans {
    private final TreeMap<Integer, Fxml2SemanticSpan> spans = new TreeMap<>();

    void add(@NotNull TextRange range, @NotNull Fxml2SemanticRole role) {
        if (range.isEmpty()) return;
        var entry = spans.floorEntry(range.getStartOffset());
        if (entry == null || entry.getValue().range().getEndOffset() <= range.getStartOffset()) {
            entry = spans.ceilingEntry(range.getStartOffset());
        }
        while (entry != null && entry.getKey() < range.getEndOffset()) {
            Fxml2SemanticSpan previous = entry.getValue();
            spans.remove(entry.getKey());
            TextRange old = previous.range();
            if (old.getStartOffset() < range.getStartOffset()) {
                spans.put(old.getStartOffset(), new Fxml2SemanticSpan(
                        new TextRange(old.getStartOffset(), range.getStartOffset()), previous.role()));
            }
            if (old.getEndOffset() > range.getEndOffset()) {
                spans.put(range.getEndOffset(), new Fxml2SemanticSpan(
                        new TextRange(range.getEndOffset(), old.getEndOffset()), previous.role()));
            }
            entry = spans.ceilingEntry(range.getStartOffset());
        }
        spans.put(range.getStartOffset(), new Fxml2SemanticSpan(range, role));
    }

    Fxml2SemanticRole roleAt(int offset) {
        var entry = spans.floorEntry(offset);
        return entry != null && offset < entry.getValue().range().getEndOffset()
                ? entry.getValue().role() : null;
    }

    @NotNull List<Fxml2SemanticSpan> result() {
        return List.copyOf(spans.values());
    }
}
