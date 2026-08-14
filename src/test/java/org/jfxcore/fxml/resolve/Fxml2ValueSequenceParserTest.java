package org.jfxcore.fxml.resolve;

import org.jfxcore.fxml.resolve.Fxml2ValueSequenceParser.ItemKind;
import org.jfxcore.fxml.resolve.Fxml2ValueSequenceParser.ValueItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Fxml2ValueSequenceParserTest {

    private static final Map<Character, String> PREFIXES = Map.of(
            '%', "org.jfxcore.markup.StaticResource",
            '@', "org.jfxcore.markup.ClassPathResource");

    private static List<ValueItem> split(String value) {
        return Fxml2ValueSequenceParser.split(value, PREFIXES);
    }

    private static List<String> texts(String value) {
        return split(value).stream().map(ValueItem::text).toList();
    }

    @Test
    void blankValueHasNoItems() {
        assertTrue(split("").isEmpty());
        assertTrue(split("   ").isEmpty());
    }

    @Test
    void literalItemsAreSplitAtCommasAndTrimmed() {
        assertEquals(List.of("0", "0", "50", "100"), texts("0, 0, 50,100"));
    }

    @Test
    void itemOffsetsPointAtTheItemText() {
        List<ValueItem> items = split("0, $inset");
        assertEquals(0, items.get(0).offset());
        assertEquals(3, items.get(1).offset());
    }

    @Test
    void trailingAndEmptyItemsArePreserved() {
        assertEquals(List.of("1", "2", ""), texts("1, 2,"));
        assertEquals(List.of("1", "", "2"), texts("1, , 2"));
    }

    @Test
    void markupExtensionItemsAreDistinguishedFromLiterals() {
        List<ValueItem> items = split("10, $inset, %greeting");
        assertEquals(ItemKind.LITERAL, items.get(0).kind());
        assertEquals(ItemKind.MARKUP_EXTENSION, items.get(1).kind());
        assertEquals(ItemKind.MARKUP_EXTENSION, items.get(2).kind());
    }

    @Test
    void escapedPrefixIsALiteralItem() {
        List<ValueItem> items = split("\\%greeting, 2");
        assertEquals(ItemKind.LITERAL, items.getFirst().kind());
        assertEquals(List.of("\\%greeting", "2"), texts("\\%greeting, 2"));
    }

    @Test
    void commasInsideBracesDoNotSplitItems() {
        assertEquals(List.of("{StaticResource greeting; formatArguments=Jane, Doe}", "@fallback.txt"),
                texts("{StaticResource greeting; formatArguments=Jane, Doe}, @fallback.txt"));
    }

    @Test
    void commasInsideDelimitedExpressionsDoNotSplitItems() {
        assertEquals(List.of("${format(a, b)}", "2"), texts("${format(a, b)}, 2"));
    }

    @Test
    void commasInsideFunctionArgumentsDoNotSplitItems() {
        assertEquals(List.of("$format(a, b)", "2"), texts("$format(a, b), 2"));
    }

    @Test
    void commasInsideTypeArgumentsDoNotSplitItems() {
        assertEquals(List.of("$Type<T, U>(value)", "2"),
                texts("$Type<T, U>(value), 2"));
    }

    @Test
    void commasInsideStringLiteralsDoNotSplitItems() {
        assertEquals(List.of("$format('a, b')", "2"), texts("$format('a, b'), 2"));
    }

    @Test
    void undelimitedExpressionEndsAtTheItemSeparator() {
        assertEquals(List.of("$source", "2"), texts("$source, 2"));
    }

    @Test
    void parameterSectionOfPrefixNotationConsumesTheRemainingItems() {
        assertEquals(List.of("%greeting; formatArguments=Jane, Doe, @fallback.txt"),
                texts("%greeting; formatArguments=Jane, Doe, @fallback.txt"));
    }

    @Test
    void lineBreakWithoutCommaRemainsPartOfLiteralItem() {
        assertEquals(List.of("1\n2"), texts("1\n2"));
    }

    @Test
    void lineBreakAfterCommaIsSeparatorLayout() {
        assertEquals(List.of("1", "2", "3"), texts("1,\n2,\r\n3"));
    }

    @Test
    void parameterSectionOfDelimitedNotationDoesNotConsumeTheRemainingItems() {
        assertEquals(List.of("${source; format=f}", "2"), texts("${source; format=f}, 2"));
    }
}
