package org.jfxcore.fxml.resolve;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Shared lexical boundaries for quoted text, identifiers, and structural punctuation. */
public final class Fxml2TextScanner {
    private Fxml2TextScanner() {}

    public enum Kind {
        OPEN_BRACE, CLOSE_BRACE, OPEN_PARENTHESIS, CLOSE_PARENTHESIS,
        OPEN_BRACKET, CLOSE_BRACKET, OPEN_ANGLE, CLOSE_ANGLE,
        COMMA, SEMICOLON, LINE_BREAK, DOT, ASSIGNMENT, OPERATOR, STRING
    }

    public record Token(@NotNull Kind kind, @NotNull Fxml2TextSpan span) {}

    public record QuotedText(@NotNull Fxml2TextSpan span, boolean closed,
                             @NotNull List<Fxml2TextSpan> escapes) {
        public QuotedText {
            escapes = List.copyOf(escapes);
        }
    }

    private static final List<String> OPERATORS = List.of(
            "!==", "===", "!=", "==", "<=", ">=", "&&", "||", "!!", "::", "..");

    public static boolean isQuote(char character) {
        return character == '\'' || character == '"';
    }

    /** Reads a quoted string, retaining partial strings and complete escape spans. */
    public static @NotNull QuotedText quoted(@NotNull String text, int start) {
        char quote = text.charAt(start);
        if (!isQuote(quote)) throw new IllegalArgumentException("Quote expected");
        List<Fxml2TextSpan> escapes = new ArrayList<>();
        int end = start + 1;
        while (end < text.length()) {
            char character = text.charAt(end++);
            if (character == '\\') {
                if (end < text.length()) escapes.add(new Fxml2TextSpan(end - 1, ++end));
            } else if (character == quote) {
                return new QuotedText(new Fxml2TextSpan(start, end), true, escapes);
            }
        }
        return new QuotedText(new Fxml2TextSpan(start, end), false, escapes);
    }

    public static int identifierEnd(@NotNull String text, int start, int limit) {
        if (start >= limit || !Character.isJavaIdentifierStart(text.charAt(start))) return start;
        int end = start + 1;
        while (end < limit && Character.isJavaIdentifierPart(text.charAt(end))) end++;
        return end;
    }

    public static boolean isIdentifier(@NotNull String text) {
        return !text.isEmpty() && identifierEnd(text, 0, text.length()) == text.length();
    }

    /** Returns dotted name parts, excluding any namespace prefix. */
    public static @NotNull List<Fxml2TextSpan> nameParts(@NotNull String text) {
        List<Fxml2TextSpan> parts = new ArrayList<>();
        int start = text.indexOf(':') + 1;
        for (String part : text.substring(start).split("\\.", -1)) {
            parts.add(new Fxml2TextSpan(start, start + part.length()));
            start += part.length() + 1;
        }
        return List.copyOf(parts);
    }

    /** Returns structural tokens, treating quoted contents as one opaque token. */
    public static @NotNull List<Token> scan(@NotNull String text) {
        List<Token> tokens = new ArrayList<>();
        for (int start = 0; start < text.length();) {
            char character = text.charAt(start);
            int end = start + 1;
            Kind kind;
            if (isQuote(character)) {
                end = quoted(text, start).span().end();
                kind = Kind.STRING;
            } else {
                kind = switch (character) {
                    case '{' -> Kind.OPEN_BRACE;
                    case '}' -> Kind.CLOSE_BRACE;
                    case '(' -> Kind.OPEN_PARENTHESIS;
                    case ')' -> Kind.CLOSE_PARENTHESIS;
                    case '[' -> Kind.OPEN_BRACKET;
                    case ']' -> Kind.CLOSE_BRACKET;
                    case '<' -> Kind.OPEN_ANGLE;
                    case '>' -> Kind.CLOSE_ANGLE;
                    case ',' -> Kind.COMMA;
                    case ';' -> Kind.SEMICOLON;
                    case '\n', '\r' -> Kind.LINE_BREAK;
                    case '.' -> Kind.DOT;
                    case '=' -> Kind.ASSIGNMENT;
                    case '+', '-', '*', '/', '!', '&', '|', ':' -> Kind.OPERATOR;
                    default -> null;
                };
                if (kind != null) {
                    for (String operator : OPERATORS) {
                        if (text.startsWith(operator, start)) {
                            end = start + operator.length();
                            kind = Kind.OPERATOR;
                            break;
                        }
                    }
                }
            }
            if (kind != null) tokens.add(new Token(kind, new Fxml2TextSpan(start, end)));
            start = end;
        }
        return List.copyOf(tokens);
    }
}
