package org.jfxcore.fxml.resolve;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.descriptors.Fxml2ClassTagDescriptor;
import org.jfxcore.fxml.resolve.Fxml2ValueSequenceParser.ItemKind;
import org.jfxcore.fxml.resolve.Fxml2ValueSequenceParser.ValueItem;

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
     * <p>The result has a single item covering the whole value when the target takes one value,
     * when the owning tag does not resolve to a class, or when the attribute names a static or
     * chained property, whose target is not resolved here.
     *
     * @return the items in source order, empty when the value is blank
     */
    public static @NotNull List<ValueItem> resolveItems(
            @NotNull XmlAttributeValue attrVal, @NotNull XmlFile xmlFile) {

        String rawValue = attrVal.getValue();
        if (rawValue.isBlank()) return List.of();

        Map<Character, String> prefixMappings = Fxml2ImportResolver.parsePrefixMappings(xmlFile);
        List<ValueItem> items = Fxml2ValueSequenceParser.split(rawValue, prefixMappings);
        return isSequence(attrVal, xmlFile, items.size())
                ? items
                : List.of(wholeValue(rawValue, prefixMappings));
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
     * Returns {@code true} when the target of {@code attrVal} takes a sequence of
     * {@code itemCount} values rather than a single value.
     */
    private static boolean isSequence(
            @NotNull XmlAttributeValue attrVal, @NotNull XmlFile xmlFile, int itemCount) {
        Fxml2ValueTargetResolver.ValueTarget target = resolveTarget(attrVal, xmlFile, itemCount);
        return target != null && !(target instanceof Fxml2ValueTargetResolver.Scalar);
    }

    /**
     * Resolves the target of the property {@code attrVal} belongs to, for a value of
     * {@code itemCount} items.
     *
     * @return the target, or {@code null} when the owning tag does not resolve to a class, when
     *         the attribute names a static or chained property, whose target is not resolved
     *         here, or when the property type cannot be determined
     */
    private static Fxml2ValueTargetResolver.@Nullable ValueTarget resolveTarget(
            @NotNull XmlAttributeValue attrVal, @NotNull XmlFile xmlFile, int itemCount) {

        if (!(attrVal.getParent() instanceof XmlAttribute attr)) return null;
        String attrName = attr.getName();
        if (Fxml2XmlUtil.isNonPropertyAttribute(attrName) || attrName.contains(".")) return null;
        if (!(attr.getParent() instanceof XmlTag tag)) return null;
        if (!(tag.getDescriptor() instanceof Fxml2ClassTagDescriptor descriptor)) return null;

        PsiClass ownerClass = descriptor.getPsiClass();
        if (ownerClass == null) return null;

        PsiSubstitutor substitutor =
                Fxml2AttributeValueResolver.buildTagTypeSubstitutor(ownerClass, tag, xmlFile);
        PsiType propType = Fxml2AttributeValueResolver.substitutedPropertyType(
                ownerClass, attrName, Fxml2NamedArgResolver.collectAttributeNames(tag), substitutor);
        if (propType == null) return null;

        return Fxml2ValueTargetResolver.resolveTarget(propType, itemCount, xmlFile.getResolveScope());
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
