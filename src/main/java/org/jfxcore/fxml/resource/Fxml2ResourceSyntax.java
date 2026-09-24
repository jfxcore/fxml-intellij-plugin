package org.jfxcore.fxml.resource;

import org.jetbrains.annotations.NotNull;

/** Character and range predicates shared by the resource grammar and its editors. */
public final class Fxml2ResourceSyntax {

    private Fxml2ResourceSyntax() {}

    public static boolean isHorizontalWhitespace(char character) {
        return character == ' ' || character == '\t';
    }

    public static boolean isXmlWhitespace(char character) {
        return isHorizontalWhitespace(character) || character == '\n' || character == '\r';
    }

    public static boolean isHorizontalWhitespace(@NotNull CharSequence text, int start, int end) {
        for (int index = start; index < end; ++index) {
            if (!isHorizontalWhitespace(text.charAt(index))) return false;
        }

        return true;
    }

    public static boolean isWhitespace(@NotNull CharSequence text, int start, int end) {
        for (int index = start; index < end; ++index) {
            if (!Character.isWhitespace(text.charAt(index))) return false;
        }

        return true;
    }
}
