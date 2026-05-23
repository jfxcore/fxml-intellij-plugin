package org.jfxcore.fxml.descriptors;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlElement;
import com.intellij.psi.xml.XmlTag;
import com.intellij.xml.impl.BasicXmlAttributeDescriptor;
import com.intellij.xml.impl.XmlAttributeDescriptorEx;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2PropertyNameUtil;
import org.jfxcore.fxml.resolve.Fxml2PropertyResolver;

import java.util.Arrays;
import java.util.List;

/**
 * {@link com.intellij.xml.XmlAttributeDescriptor} for a regular (non-fx:) XML attribute.
 * {@link #getDeclaration()} returns the setter, getter, {@code xProperty()} method, or
 * {@code @NamedArg} constructor parameter, enabling Ctrl+click navigation to the property
 * or constructor-parameter definition.
 *
 * <p>Implements {@link XmlAttributeDescriptorEx} so that
 * {@link com.intellij.psi.impl.source.xml.XmlAttributeReference#handleElementRename}
 * calls {@link #handleTargetRename} rather than using the raw new accessor name directly.
 * This ensures that renaming e.g. {@code setFormatter} to {@code setFormatter2} updates
 * the attribute name to {@code formatter2} (the property name), not {@code setFormatter2}.
 */
public final class Fxml2PropertyAttributeDescriptor extends BasicXmlAttributeDescriptor
        implements XmlAttributeDescriptorEx {

    private final String name;
    private final @Nullable PsiClass ownerClass;
    private final @Nullable XmlTag tag;

    public Fxml2PropertyAttributeDescriptor(@NotNull String name, @Nullable PsiClass ownerClass) {
        this(name, ownerClass, null);
    }

   public Fxml2PropertyAttributeDescriptor(@NotNull String name, @Nullable PsiClass ownerClass,
                                              @Nullable XmlTag tag) {
        this.name = name;
        this.ownerClass = ownerClass;
        this.tag = tag;
    }

    /** Returns the owner class for this property attribute, or null if not available. */
    @Nullable
    public PsiClass getOwnerClass() {
        return ownerClass;
    }


    @Override
    public @Nullable PsiElement getDeclaration() {
        if (ownerClass == null) return null;
        // Collect sibling attribute names from the tag so that @NamedArg constructor
        // selection picks the right constructor.
        List<String> siblings = tag != null
                ? Arrays.stream(tag.getAttributes()).map(XmlAttribute::getLocalName).toList()
                : List.of();
        return Fxml2PropertyResolver.resolveInstanceProperty(ownerClass, name,
                siblings, bindingKind());
    }

    /** Parses the binding kind from this attribute's value, or null if it's a plain value. */
    private @Nullable Kind bindingKind() {
        if (tag == null) return null;
        XmlAttribute attr = tag.getAttribute(name);
        if (attr == null) return null;
        String value = attr.getValue();
        if (value == null) return null;
        Fxml2BindingExpressionParser.ParsedExpression expr =
                Fxml2BindingExpressionParser.parseExpression(value);
        return expr != null ? expr.kind() : null;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getName(PsiElement context) {
        return name;
    }

    @Override
    public void init(PsiElement element) {}

    @Override
    public boolean isRequired() {
        return false;
    }

    @Override
    public boolean isFixed() {
        return false;
    }

    @Override
    public boolean hasIdType() {
        return false;
    }

    @Override
    public boolean hasIdRefType() {
        return false;
    }

    @Override
    public @Nullable String getDefaultValue() {
        return null;
    }

    @Override
    public boolean isEnumerated() {
        return false;
    }

    @Override
    public String @Nullable [] getEnumeratedValues() {
        return null;
    }

    @Override
    public @Nullable String validateValue(XmlElement context, String value) {
        return null;
    }

    /**
     * Converts the new accessor name to the corresponding property name so that
     * {@link com.intellij.psi.impl.source.xml.XmlAttributeReference#handleElementRename}
     * renames the XML attribute to the property name rather than the raw accessor name.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code setFormatter2} -> {@code formatter2}</li>
     *   <li>{@code getFormatter2} / {@code isFormatter2} -> {@code formatter2}</li>
     *   <li>{@code formatter2Property} -> {@code formatter2}</li>
     *   <li>{@code formatter2} (plain field name) -> {@code formatter2}</li>
     * </ul>
     */
    @Override
    public @NotNull String handleTargetRename(@NotNull String newTargetName) {
        return Fxml2PropertyNameUtil.toPropertyName(newTargetName);
    }

    @Override
    public String toString() {
        return (ownerClass != null ? ownerClass.getName() + "#" : "?#") + name;
    }
}
