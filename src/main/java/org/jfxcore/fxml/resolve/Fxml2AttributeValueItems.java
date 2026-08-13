package org.jfxcore.fxml.resolve;

import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2ValueSequenceParser.ItemKind;
import org.jfxcore.fxml.resolve.Fxml2ValueSequenceParser.ValueItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Splits the value of an attribute into the items it assigns.
 *
 * <p>Combines the two questions that decide what an attribute value contains: whether its target
 * takes a sequence of values ({@link Fxml2ValueTargetResolver}), and where the items of that
 * sequence begin and end ({@link Fxml2ValueSequenceParser}).  A value whose target takes a single
 * value is reported as one item spanning the whole value, so that consumers can treat every
 * attribute value uniformly as a list of items.
 */
public final class Fxml2AttributeValueItems {

    private Fxml2AttributeValueItems() {}

    /**
     * Returns the items of {@code attrVal}.
     *
     * <p>The result has a single item covering the whole value when the target takes one value or
     * when the owning tag does not resolve to a class.
     *
     * @return the items in source order, empty when the value is blank
     */
    public static @NotNull List<ValueItem> resolveItems(
            @NotNull XmlAttributeValue attrVal, @NotNull XmlFile xmlFile) {
        return resolveTypedItems(attrVal, xmlFile).stream().map(TypedItem::item).toList();
    }

    /**
     * An item of a value sequence together with the type it has to be converted to.
     *
     * @param requiredType the type the item has to be converted to, or {@code null} when it cannot
     *                     be determined, which includes an item beyond the last one the target
     *                     accepts
     */
    public record TypedItem(@NotNull ValueItem item, @Nullable PsiType requiredType) {}

    /**
     * Returns the items of {@code attrVal} together with the type each of them has to be converted
     * to, which is the item type of a collected target and the parameter type of an implicitly
     * constructed one.
     *
     * @return the items in source order, empty when the value is blank
     */
    public static @NotNull List<TypedItem> resolveTypedItems(
            @NotNull XmlAttributeValue attrVal, @NotNull XmlFile xmlFile) {

        String rawValue = attrVal.getValue();
        if (rawValue.isBlank()) return List.of();

        Map<Character, String> prefixMappings = Fxml2ImportResolver.parsePrefixMappings(xmlFile);
        List<ValueItem> items = Fxml2ValueSequenceParser.split(rawValue, prefixMappings);
        Fxml2ValueTargetResolver.ValueTarget target = resolveTarget(attrVal, xmlFile, items.size());
        if (target == null || target instanceof Fxml2ValueTargetResolver.Scalar) {
            PsiType type = target instanceof Fxml2ValueTargetResolver.Scalar(PsiType scalarType)
                    ? scalarType
                    : null;
            return List.of(new TypedItem(wholeValue(rawValue, prefixMappings), type));
        }

        List<TypedItem> typed = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            typed.add(new TypedItem(items.get(i), itemTypeAt(target, i)));
        }
        return typed;
    }

    /**
     * Returns {@code true} when any item of {@code attrVal} is a markup extension, so the value
     * does not denote a literal that spans the whole value.
     */
    public static boolean hasMarkupExtensionItem(
            @NotNull XmlAttributeValue attrVal, @NotNull XmlFile xmlFile) {
        return resolveItems(attrVal, xmlFile).stream().anyMatch(ValueItem::isMarkupExtension);
    }

    /**
     * The item a caret is placed in.
     *
     * @param text         the item text up to the caret
     * @param requiredType the type this item has to be converted to, or {@code null} when it
     *                     cannot be determined
     */
    public record CaretItem(@NotNull String text, @Nullable PsiType requiredType) {}

    /**
     * Returns the item of a value sequence that the caret is placed in, so that a caret after a
     * separator is resolved against the item it starts rather than against the whole value.
     *
     * @param fullValue       the whole attribute value
     * @param valueBeforeCaret the part of the value up to the caret
     * @return the item at the caret, or {@code null} when the target takes a single value and the
     *         whole value therefore applies
     */
    public static @Nullable CaretItem resolveCaretItem(
            @NotNull XmlAttributeValue attrVal,
            @NotNull XmlFile xmlFile,
            @NotNull String fullValue,
            @NotNull String valueBeforeCaret) {

        Map<Character, String> prefixMappings = Fxml2ImportResolver.parsePrefixMappings(xmlFile);
        List<ValueItem> items = Fxml2ValueSequenceParser.split(fullValue, prefixMappings);
        Fxml2ValueTargetResolver.ValueTarget target = resolveTarget(attrVal, xmlFile, items.size());
        if (target == null || target instanceof Fxml2ValueTargetResolver.Scalar) return null;

        // The caret is at the end of the truncated text, so its item is the last one there.
        List<ValueItem> typed = Fxml2ValueSequenceParser.split(valueBeforeCaret, prefixMappings);
        String text = typed.isEmpty() ? "" : typed.getLast().text();

        // The caret is in the item that follows the ones already completed. An empty item at the
        // caret is not reported by the parser, so the index is derived from the separators typed.
        int index = typed.isEmpty() ? 0 : typed.size() - 1;
        if (endsWithSeparator(valueBeforeCaret)) {
            index = typed.size();
            text = "";
        }
        return new CaretItem(text, itemTypeAt(target, index));
    }

    /** Returns {@code true} when the text ends with an item separator, so a new item begins. */
    private static boolean endsWithSeparator(@NotNull String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) continue;
            return c == ',';
        }
        return false;
    }

    /** The type required for the item at {@code index} of {@code target}. */
    private static @Nullable PsiType itemTypeAt(
            @NotNull Fxml2ValueTargetResolver.ValueTarget target, int index) {
        return switch (target) {
            case Fxml2ValueTargetResolver.Items(PsiType itemType) -> itemType;
            case Fxml2ValueTargetResolver.Arguments(List<PsiParameter> parameters) ->
                    index < parameters.size() ? parameters.get(index).getType() : null;
            case Fxml2ValueTargetResolver.Scalar(PsiType type) -> type;
        };
    }

    /**
     * Resolves the target of the property {@code attrVal} belongs to, for a value of
     * {@code itemCount} items.
     *
     * @return the target, or {@code null} when the owning tag does not resolve to a class or when
     *         the property type cannot be determined
     */
    private static Fxml2ValueTargetResolver.@Nullable ValueTarget resolveTarget(
            @NotNull XmlAttributeValue attrVal, @NotNull XmlFile xmlFile, int itemCount) {

        PsiType propType = resolvePropertyType(attrVal, xmlFile);
        if (propType == null) return null;
        return Fxml2ValueTargetResolver.resolveTarget(propType, itemCount, xmlFile.getResolveScope());
    }

    /**
     * Returns the type the value of {@code attrVal} is assigned to, for all three attribute forms:
     * a plain property name, a static property ({@code GridPane.margin}) and a chained property
     * ({@code labelFor.text}).
     *
     * @return the type, or {@code null} when the attribute is not a property attribute or its type
     *         cannot be determined
     */
    public static @Nullable PsiType resolvePropertyType(
            @NotNull XmlAttributeValue attrVal, @NotNull XmlFile xmlFile) {

        return attrVal.getParent() instanceof XmlAttribute attr
                ? Fxml2AttributeValueResolver.attributeTargetType(attr, xmlFile)
                : null;
    }

    /** The whole value as a single item, preserving its leading and trailing whitespace offsets. */
    private static @NotNull ValueItem wholeValue(
            @NotNull String rawValue, @NotNull Map<Character, String> prefixMappings) {

        int begin = 0;
        while (begin < rawValue.length() && Character.isWhitespace(rawValue.charAt(begin))) begin++;
        String text = rawValue.substring(begin).stripTrailing();
        ItemKind kind = Fxml2BindingExpressionParser.looksLikeBindingExpression(text, prefixMappings)
                ? ItemKind.MARKUP_EXTENSION
                : ItemKind.LITERAL;
        return new ValueItem(text, begin, kind);
    }
}
