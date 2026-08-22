package org.jfxcore.fxml.resource;

import org.jetbrains.annotations.NotNull;

/**
 * Writes the parts of a media type back into declaration text.
 *
 * <p>Kept separate from {@link Fxml2ResourceMediaType} so that the value type stays a plain
 * carrier and the escaping rules have a single, testable home.
 */
final class Fxml2MediaTypeWriter {

    /** The characters a media-type token may consist of. */
    private static final String TOKEN_CHARACTERS = "!#$%&'*+-.^_`|~";

    private Fxml2MediaTypeWriter() {}

    /** Returns {@code true} when {@code character} may appear in an unquoted media-type token. */
    static boolean isTokenCharacter(char character) {
        return character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9'
                || TOKEN_CHARACTERS.indexOf(character) >= 0;
    }

    /** Returns {@code true} when {@code text} is a non-empty media-type token. */
    static boolean isToken(@NotNull String text) {
        if (text.isEmpty()) return false;
        for (int i = 0; i < text.length(); ++i) {
            if (!isTokenCharacter(text.charAt(i))) return false;
        }
        return true;
    }

    /** Returns {@code value} written as a parameter value, quoting and escaping it when needed. */
    static @NotNull String writeParameterValue(@NotNull String value) {
        if (isToken(value)) return value;

        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); ++i) {
            char character = value.charAt(i);
            if (character == '"' || character == '\\') result.append('\\');
            result.append(character);
        }

        return result.append('"').toString();
    }
}
