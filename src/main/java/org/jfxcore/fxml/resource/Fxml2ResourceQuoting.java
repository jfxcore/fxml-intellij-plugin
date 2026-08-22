package org.jfxcore.fxml.resource;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * How a resource name is written in a {@code <?resource ?>} declaration.
 *
 * <p>Quoting is a property of the declaration text only; it never becomes part of the logical
 * name.  A name has to be quoted when it contains a character the unquoted form cannot express,
 * which is any XML whitespace character.
 */
public enum Fxml2ResourceQuoting {

    /** The name is written without quotes: {@code <?resource styles.css:...?>}. */
    UNQUOTED('\0'),

    /** The name is written in single quotes: {@code <?resource 'dark theme.css':...?>}. */
    SINGLE('\''),

    /** The name is written in double quotes: {@code <?resource "dark theme.css":...?>}. */
    DOUBLE('"');

    private final char quoteCharacter;

    Fxml2ResourceQuoting(char quoteCharacter) {
        this.quoteCharacter = quoteCharacter;
    }

    /** Returns the quoting style introduced by {@code character}, or {@code null} if it is not a quote. */
    public static @Nullable Fxml2ResourceQuoting of(char character) {
        return switch (character) {
            case '\'' -> SINGLE;
            case '"' -> DOUBLE;
            default -> null;
        };
    }

    /** Returns the quoting style needed to write {@code name}, preferring the unquoted form. */
    public static @NotNull Fxml2ResourceQuoting required(@NotNull String name) {
        if (!needsQuoting(name)) return UNQUOTED;
        return name.indexOf('"') < 0 ? DOUBLE : SINGLE;
    }

    /**
     * Returns {@code true} when {@code name} cannot be written without quotes, which is the case
     * for an empty name and for any name containing XML whitespace or a colon.
     */
    public static boolean needsQuoting(@NotNull String name) {
        if (name.isEmpty()) return true;
        for (int i = 0; i < name.length(); ++i) {
            char ch = name.charAt(i);
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == ':') return true;
        }
        return false;
    }

    /** Returns {@code name} written in this quoting style. */
    public @NotNull String write(@NotNull String name) {
        return this == UNQUOTED ? name : quoteCharacter + name + quoteCharacter;
    }
}
