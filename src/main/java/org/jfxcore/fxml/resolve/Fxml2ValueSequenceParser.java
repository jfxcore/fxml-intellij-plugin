package org.jfxcore.fxml.resolve;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Splits an attribute value into the sequence of values it denotes.
 *
 * <p>When the target of an attribute is a collection, an array, or a type that is implicitly
 * constructed from named arguments, the attribute value is a comma-separated list whose items
 * are resolved independently.  An item is either a literal value or a value-producing markup
 * extension:
 *
 * <pre>{@code
 * <Polygon points="0, 0, 50, 100"/>
 * <GridPane padding="10, $rightInset, 10, {StaticResource leftInset}"/>
 * }</pre>
 *
 * <p>Commas that occur inside a nested construct do not separate items.  The parser therefore
 * tracks brace, parenthesis and bracket nesting as well as single-quoted string literals, so
 * that {@code "{StaticResource greeting; formatArguments=Jane, Doe}, 2"} is two items and
 * {@code "$format('a, b'), 2"} is two items as well.
 *
 * <p>Markup extensions in prefix notation have no closing delimiter.  Once such an item opens a
 * parameter section with {@code ';'}, all remaining text belongs to that item, so
 * {@code "%greeting; formatArguments=Jane, Doe"} is a single item.  To continue the outer
 * sequence after a parameterized markup extension, the brace form has to be used.
 *
 * <p>Whether a value is a sequence at all depends on the target: for a target that is neither a
 * collection, an array, nor implicitly constructible, a comma carries no special meaning and the
 * whole value is a single literal.  {@link Fxml2ValueTargetResolver} makes that decision, and this
 * parser is only invoked for sequence targets.
 */
public final class Fxml2ValueSequenceParser {

    private Fxml2ValueSequenceParser() {}

    /** Whether an item supplies its value literally or through a markup extension. */
    public enum ItemKind {
        /** A literal value, converted to the required item type. */
        LITERAL,
        /** A markup extension, resolved against the required item type. */
        MARKUP_EXTENSION
    }

    /**
     * A single item of a value sequence.
     *
     * @param text   the item text with surrounding whitespace stripped
     * @param offset offset of the first character of {@code text} within the parsed value
     * @param kind   how the item supplies its value
     */
    public record ValueItem(@NotNull String text, int offset, @NotNull ItemKind kind) {
        /** Whether this item is a markup extension rather than a literal value. */
        public boolean isMarkupExtension() {
            return kind == ItemKind.MARKUP_EXTENSION;
        }
    }

    /**
     * Splits {@code value} into its top-level items.  Blank items are skipped, so a trailing
     * comma does not produce an empty item.
     *
     * @param value          the raw attribute value (without the surrounding quotes)
     * @param prefixMappings prefix-char to extension FQN map for the current FXML file, used to
     *                       recognize prefix-shorthand items such as {@code %greeting}
     * @return the items in source order; empty when {@code value} is blank
     */
    public static @NotNull List<ValueItem> split(
            @Nullable String value,
            @NotNull Map<Character, String> prefixMappings) {

        List<ValueItem> items = new ArrayList<>();
        if (value == null || value.isBlank()) return items;

        int depth = 0;
        int start = 0;
        int i = 0;
        boolean inStringLiteral = false;
        // Set once the current item opens a parameter section, which consumes the remaining text.
        boolean greedyItem = false;

        while (i < value.length()) {
            char c = value.charAt(i);

            if (inStringLiteral) {
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == '\'') inStringLiteral = false;
                i++;
                continue;
            }

            switch (c) {
                case '\'' -> inStringLiteral = true;
                case '{', '(', '[' -> depth++;
                case '}', ')', ']' -> { if (depth > 0) depth--; }
                case ';' -> {
                    // A parameter section of a prefix-notation markup extension is greedy: it has
                    // no closing delimiter, so every following comma belongs to the extension.
                    if (depth == 0 && isPrefixNotation(value, start, prefixMappings)) {
                        greedyItem = true;
                    }
                }
                case ',', '\n', '\r' -> {
                    // A line break separates items just like a comma, so that a long sequence can
                    // be spread over several lines.
                    if (depth == 0 && !greedyItem) {
                        addItem(items, value, start, i, prefixMappings);
                        start = i + 1;
                    }
                }
                default -> { }
            }
            i++;
        }

        addItem(items, value, start, value.length(), prefixMappings);
        return items;
    }

    /**
     * Returns {@code true} when the item starting at {@code start} is a markup extension in
     * prefix notation, i.e. one without a closing delimiter.  The brace forms {@code {Ext ...}},
     * {@code ${...}}, {@code #{...}} and {@code >{...}} are delimited and therefore excluded.
     */
    private static boolean isPrefixNotation(
            @NotNull String value, int start, @NotNull Map<Character, String> prefixMappings) {

        int begin = start;
        while (begin < value.length() && Character.isWhitespace(value.charAt(begin))) begin++;
        if (begin >= value.length()) return false;
        String text = value.substring(begin);
        if (!Fxml2BindingExpressionParser.looksLikeBindingExpression(text, prefixMappings)) return false;
        char first = text.charAt(0);
        if (first == '{') return false;
        // "${", "#{" and ">{" open a delimited expression; a bare prefix character does not.
        return text.length() < 2 || text.charAt(1) != '{';
    }

    private static void addItem(
            @NotNull List<ValueItem> items,
            @NotNull String value,
            int start,
            int end,
            @NotNull Map<Character, String> prefixMappings) {

        int begin = start;
        while (begin < end && Character.isWhitespace(value.charAt(begin))) begin++;
        int finish = end;
        while (finish > begin && Character.isWhitespace(value.charAt(finish - 1))) finish--;
        if (begin >= finish) return;

        String text = value.substring(begin, finish);
        ItemKind kind = Fxml2BindingExpressionParser.looksLikeBindingExpression(text, prefixMappings)
                ? ItemKind.MARKUP_EXTENSION
                : ItemKind.LITERAL;
        items.add(new ValueItem(text, begin, kind));
    }
}
