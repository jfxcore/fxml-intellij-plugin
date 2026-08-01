package org.jfxcore.fxml.resolve;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses type-argument lists as they appear in {@code fx:typeArguments} attribute values
 * and in the type-argument section of markup extensions ({@code {GenericExt<String> ...}}).
 *
 * <p>The compiler's {@code TypeTokenizer}/{@code TypeFormatter} accept nested type arguments,
 * so a single argument may itself be parameterized, e.g.
 * {@code fx:typeArguments="javafx.util.Pair&lt;String, String&gt;"} is <em>one</em> argument,
 * not two.  Splitting such a value on plain commas therefore both miscounts the arity and
 * produces bogus class names ({@code "Pair<String"}, {@code "String>"}).
 *
 * <p>All methods accept the raw attribute text, which may use either literal angle brackets
 * ({@code <}, {@code >} — legal inside an XML attribute value) or the escaped entity forms
 * ({@code &lt;}, {@code &gt;}).
 */
public final class Fxml2TypeArgumentParser {

    private Fxml2TypeArgumentParser() {}

    /**
     * A top-level type argument.
     *
     * @param text   the argument text with surrounding whitespace stripped, including any
     *               nested type arguments (e.g. {@code "javafx.util.Pair<String, String>"})
     * @param offset offset of the first character of {@code text} within the parsed string
     */
    public record TypeArg(@NotNull String text, int offset) {
        /** The argument's raw type name, i.e. everything before its own type arguments. */
        public @NotNull String rawName() {
            return Fxml2TypeArgumentParser.rawName(text);
        }
    }

    /**
     * A single type-name occurrence at any nesting depth.
     *
     * @param name   the (possibly qualified) type name, without type arguments
     * @param offset offset of the first character of {@code name} within the parsed string
     */
    public record TypeName(@NotNull String name, int offset) {}

    /**
     * Splits a type-argument list into its top-level arguments, ignoring commas that occur
     * inside nested type arguments.  Blank arguments are skipped.
     *
     * @param text the type-argument list (without the enclosing angle brackets)
     * @return the top-level arguments in source order
     */
    public static @NotNull List<TypeArg> splitTopLevel(@Nullable String text) {
        List<TypeArg> result = new ArrayList<>();
        if (text == null || text.isBlank()) return result;

        int depth = 0;
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            int bracket = bracketAt(text, i);
            if (bracket != 0) {
                depth += bracket > 0 ? 1 : -1;
                i += bracketLength(text, i);
                continue;
            }
            if (text.charAt(i) == ',' && depth == 0) {
                addArg(result, text, start, i);
                start = i + 1;
            }
            i++;
        }
        addArg(result, text, start, text.length());
        return result;
    }

    /**
     * Returns the raw type name of a type argument, i.e. everything before its own type
     * arguments ({@code "Map<K, V>"} -> {@code "Map"}).  Wildcards and array suffixes are
     * left untouched.
     */
    public static @NotNull String rawName(@NotNull String typeArg) {
        for (int i = 0; i < typeArg.length(); i++) {
            if (bracketAt(typeArg, i) > 0) {
                return typeArg.substring(0, i).stripTrailing();
            }
        }
        return typeArg;
    }

    /**
     * Collects every type-name occurrence in a type-argument list, at any nesting depth,
     * together with its offset.  For {@code "javafx.util.Pair<String, java.lang.Integer>"}
     * this yields {@code Pair}, {@code String} and {@code java.lang.Integer} (the qualified
     * name is reported as a single occurrence; callers split it into segments themselves).
     *
     * <p>Escaped entities ({@code &lt;}, {@code &gt;}, {@code &amp;}) and wildcards
     * ({@code ?}) are not reported as type names.
     *
     * @param text       the type-argument list (without the enclosing angle brackets)
     * @param baseOffset value added to every reported offset
     */
    public static @NotNull List<TypeName> allTypeNames(@Nullable String text, int baseOffset) {
        List<TypeName> result = new ArrayList<>();
        if (text == null || text.isEmpty()) return result;

        int i = 0;
        while (i < text.length()) {
            int entity = entityLength(text, i);
            if (entity > 0) {
                i += entity;
                continue;
            }
            char c = text.charAt(i);
            if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < text.length()
                        && (Character.isJavaIdentifierPart(text.charAt(i)) || text.charAt(i) == '.')) {
                    i++;
                }
                String name = text.substring(start, i);
                // A trailing dot is a syntax error; don't report it as part of the name.
                while (name.endsWith(".")) name = name.substring(0, name.length() - 1);
                if (!name.isEmpty() && !isKeyword(name)) {
                    result.add(new TypeName(name, baseOffset + start));
                }
                continue;
            }
            i++;
        }
        return result;
    }

    /**
     * Returns {@code true} when every type-argument list in {@code text} is terminated.
     * An unterminated list is a syntax error ({@code UNEXPECTED_END_OF_TYPE_DECLARATION});
     * a surplus closing bracket is reported as unbalanced as well.
     */
    public static boolean isBalanced(@Nullable String text) {
        if (text == null) return true;
        int depth = 0;
        int i = 0;
        while (i < text.length()) {
            int bracket = bracketAt(text, i);
            if (bracket != 0) {
                depth += bracket > 0 ? 1 : -1;
                if (depth < 0) return false;
                i += bracketLength(text, i);
                continue;
            }
            i++;
        }
        return depth == 0;
    }

    /**
     * Finds the offset of the bracket closing the type-argument list that starts at
     * {@code from} (the first character after the opening bracket), honoring nesting and
     * both the literal and the escaped bracket forms.
     *
     * @return the offset of the closing bracket, or {@code -1} if there is none
     */
    public static int findClosingBracket(@NotNull String text, int from) {
        int depth = 1;
        int i = from;
        while (i < text.length()) {
            int bracket = bracketAt(text, i);
            if (bracket > 0) {
                depth++;
            } else if (bracket < 0 && --depth == 0) {
                return i;
            }
            i += bracket != 0 ? bracketLength(text, i) : 1;
        }
        return -1;
    }

    private static void addArg(@NotNull List<TypeArg> result, @NotNull String text, int start, int end) {
        int s = start;
        while (s < end && Character.isWhitespace(text.charAt(s))) s++;
        int e = end;
        while (e > s && Character.isWhitespace(text.charAt(e - 1))) e--;
        if (s < e) {
            result.add(new TypeArg(text.substring(s, e), s));
        }
    }

    /** {@code 1} for an opening bracket at {@code i}, {@code -1} for a closing one, {@code 0} otherwise. */
    private static int bracketAt(@NotNull String text, int i) {
        char c = text.charAt(i);
        if (c == '<') return 1;
        if (c == '>') return -1;
        if (c == '&') {
            if (text.startsWith("&lt;", i)) return 1;
            if (text.startsWith("&gt;", i)) return -1;
        }
        return 0;
    }

    /** Length in characters of the bracket at {@code i} (1 for literal, 4 for an entity). */
    private static int bracketLength(@NotNull String text, int i) {
        return text.charAt(i) == '&' ? 4 : 1;
    }

    /** Length of the XML entity starting at {@code i}, or {@code 0} if there is none. */
    private static int entityLength(@NotNull String text, int i) {
        if (text.charAt(i) != '&') return 0;
        if (text.startsWith("&lt;", i) || text.startsWith("&gt;", i)) return 4;
        if (text.startsWith("&amp;", i)) return 5;
        return 0;
    }

    /** Java keywords that may legally appear in a type argument but are not type names. */
    private static boolean isKeyword(@NotNull String name) {
        return switch (name) {
            case "extends", "super" -> true;
            default -> false;
        };
    }
}
