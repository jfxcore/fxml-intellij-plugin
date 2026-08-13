package org.jfxcore.fxml.resolve;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
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
     * Returns {@code true} when the target of {@code attrVal} takes a sequence of
     * {@code itemCount} values rather than a single value.
     */
    private static boolean isSequence(
            @NotNull XmlAttributeValue attrVal, @NotNull XmlFile xmlFile, int itemCount) {

        if (!(attrVal.getParent() instanceof XmlAttribute attr)) return false;
        String attrName = attr.getName();
        if (Fxml2XmlUtil.isNonPropertyAttribute(attrName) || attrName.contains(".")) return false;
        if (!(attr.getParent() instanceof XmlTag tag)) return false;
        if (!(tag.getDescriptor() instanceof Fxml2ClassTagDescriptor descriptor)) return false;

        PsiClass ownerClass = descriptor.getPsiClass();
        if (ownerClass == null) return false;

        PsiSubstitutor substitutor =
                Fxml2AttributeValueResolver.buildTagTypeSubstitutor(ownerClass, tag, xmlFile);
        PsiType propType = Fxml2AttributeValueResolver.substitutedPropertyType(
                ownerClass, attrName, Fxml2NamedArgResolver.collectAttributeNames(tag), substitutor);
        if (propType == null) return false;

        return !(Fxml2ValueTargetResolver.resolveTarget(propType, itemCount, xmlFile.getResolveScope())
                instanceof Fxml2ValueTargetResolver.Scalar);
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
