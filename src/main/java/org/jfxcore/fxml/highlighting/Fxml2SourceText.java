package org.jfxcore.fxml.highlighting;

import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Decoded XML text with boundaries mapped back to the containing PSI file. */
public final class Fxml2SourceText {
    private final String text;
    private final List<Integer> boundaries;

    private Fxml2SourceText(String text, List<Integer> boundaries) {
        this.text = text;
        this.boundaries = List.copyOf(boundaries);
    }

    public static @NotNull Fxml2SourceText decode(@NotNull String raw, int fileOffset) {
        StringBuilder decoded = new StringBuilder();
        List<Integer> offsets = new ArrayList<>();
        offsets.add(fileOffset);
        for (int i = 0; i < raw.length();) {
            int end = raw.charAt(i) == '&' ? raw.indexOf(';', i + 1) : -1;
            String entity = end >= 0 ? entity(raw.substring(i + 1, end)) : null;
            if (entity != null) {
                for (int j = 0; j < entity.length(); j++) {
                    decoded.append(entity.charAt(j));
                    offsets.add(fileOffset + (j + 1 == entity.length() ? end + 1 : i));
                }
                i = end + 1;
            } else {
                decoded.append(raw.charAt(i++));
                offsets.add(fileOffset + i);
            }
        }
        return new Fxml2SourceText(decoded.toString(), offsets);
    }

    private static String entity(String name) {
        return switch (name) {
            case "lt" -> "<";
            case "gt" -> ">";
            case "amp" -> "&";
            case "quot" -> "\"";
            case "apos" -> "'";
            default -> numericEntity(name);
        };
    }

    private static String numericEntity(String name) {
        if (!name.startsWith("#")) return null;
        try {
            boolean hex = name.startsWith("#x");
            int codePoint = Integer.parseInt(name.substring(hex ? 2 : 1), hex ? 16 : 10);
            return Character.isValidCodePoint(codePoint) ? new String(Character.toChars(codePoint)) : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public @NotNull String text() {
        return text;
    }

    public @NotNull TextRange range(int start, int end) {
        return new TextRange(boundaries.get(start), boundaries.get(end));
    }

    public @NotNull Fxml2SourceText slice(int start, int end) {
        return new Fxml2SourceText(text.substring(start, end), boundaries.subList(start, end + 1));
    }
}
