package org.jfxcore.fxml.resolve;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.descriptors.Fxml2ClassTagDescriptor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Shared XML/PSI helper utilities used across inspection, reference-contributor,
 * and other language-service classes.
 */
public final class Fxml2XmlUtil {

    private Fxml2XmlUtil() {}

    /**
     * Returns {@code true} when {@code attrName} is a namespace declaration ({@code xmlns},
     * {@code xmlns:*}), an intrinsic FXML/2 attribute ({@code fx:*}), or an XML reserved
     * attribute ({@code xml:*}, e.g. {@code xml:space}).  Such attributes are not property
     * attributes and should be skipped by value-resolution and inspection logic.
     */
    public static boolean isNonPropertyAttribute(@NotNull String attrName) {
        return attrName.equals("xmlns")
                || attrName.startsWith("xmlns:")
                || attrName.startsWith("fx:")
                || attrName.startsWith("xml:");
    }

    /**
     * Builds the canonical "invalid value" error message used by the inspection.
     * <ul>
     *   <li>When the property type is {@code Insets}, mirrors the compiler's
     *       {@code "'value' is not a valid value for propertyName"} form.</li>
     *   <li>When {@code typeName} is known, produces {@code "Cannot coerce 'value' to Type"}.</li>
     *   <li>Otherwise produces {@code "Invalid value 'value' for property 'propName'"}.</li>
     * </ul>
     *
     * @param value    the literal attribute/text value that was rejected
     * @param propName the simple property name (no class prefix)
     * @param propType the resolved property {@link PsiType}, or {@code null} if unknown
     */
    public static @NotNull String buildCoercionErrorMessage(
            @NotNull String value,
            @NotNull String propName,
            @Nullable PsiType propType) {
        String typeName = propType != null ? propType.getPresentableText() : null;
        if ("Insets".equals(typeName)) {
            return "'" + value + "' is not a valid value for " + propName;
        }
        if (typeName != null) {
            return "Cannot coerce '" + value + "' to " + typeName;
        }
        return "Invalid value '" + value + "' for property '" + propName + "'";
    }

    /**
     * Walks up the tag hierarchy from {@code propTag} to find the nearest ancestor tag
     * that resolves to a Java class (either via {@link Fxml2ImportResolver#resolve} or by
     * having a {@link Fxml2ClassTagDescriptor}).
     *
     * @return the nearest ancestor class tag, or {@code null} if none is found
     */
    public static @Nullable XmlTag findEnclosingClassTag(
            @NotNull XmlTag propTag, @NotNull XmlFile xmlFile) {
        XmlTag cur = propTag.getParentTag();
        while (cur != null) {
            if (Fxml2ImportResolver.resolve(cur.getLocalName(), xmlFile) != null) return cur;
            if (cur.getDescriptor() instanceof Fxml2ClassTagDescriptor) return cur;
            cur = cur.getParentTag();
        }
        return null;
    }

    /**
     * Builds the dot-separated property path from {@code classTag} down to (and including)
     * {@code propTag}'s local name. For example, for {@code <selectionModel.selectionMode>}
     * directly inside {@code <SomeListView>}, returns {@code "selectionModel.selectionMode"}.
     *
     * @return the dotted path, or {@code null} if {@code classTag} is not an ancestor of
     *         {@code propTag}
     */
    public static @Nullable String buildPropertyPath(
            @NotNull XmlTag propTag, @NotNull XmlTag classTag) {
        Deque<String> parts = new ArrayDeque<>();
        XmlTag cur = propTag;
        while (cur != null && cur != classTag) {
            parts.addFirst(cur.getLocalName());
            cur = cur.getParentTag();
        }
        if (cur == null) return null;
        return String.join(".", parts);
    }

    /**
     * Walks a dot-separated property chain on {@code ownerClass} using
     * {@link Fxml2AttributeValueResolver#propertyType} (which correctly unwraps
     * {@code ObservableValue<T>}) and returns {@code [finalOwnerClass, lastPropertyName]},
     * or {@code null} if any segment fails to resolve.
     *
     * <p>For a plain (non-dotted) name the array is {@code [ownerClass, name]}.
     * For {@code "selectionModel.selectionMode"} on {@code SomeListView} it returns
     * {@code [MultipleSelectionModel, "selectionMode"]}.
     */
    public static Object @Nullable [] resolveChainedPropertyOwner(
            @NotNull PsiClass ownerClass, @NotNull String dottedName) {
        String[] parts = dottedName.split("\\.", -1);
        PsiClass current = ownerClass;
        for (int i = 0; i < parts.length - 1; i++) {
            PsiType type = Fxml2AttributeValueResolver.propertyType(current, parts[i], List.of());
            if (type == null) return null;
            current = PsiUtil.resolveClassInType(type);
            if (current == null) return null;
        }
        return new Object[]{ current, parts[parts.length - 1] };
    }
}
