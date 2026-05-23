package org.jfxcore.fxml.descriptors;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlTag;
import com.intellij.xml.XmlAttributeDescriptor;
import com.intellij.xml.XmlElementDescriptor;
import com.intellij.xml.XmlElementsGroup;
import com.intellij.xml.XmlNSDescriptor;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2PropertyResolver;

/**
 * Describes an XML child-tag that represents a property in "short element notation" per the
 * <a href="https://jfxcore.github.io/fxml-compiler/property-notation.html">FXML 2.0 spec</a>.
 *
 * <p>Examples:
 * <pre>{@code
 *   <!-- Instance property tag on Button -->
 *   <text>Hello</text>
 *
 *   <!-- Static property tag  GridPane.rowIndex -->
 *   <GridPane.rowIndex>0</GridPane.rowIndex>
 * }</pre>
 *
 * <p>{@link #getDeclaration()} resolves to the setter/getter/propertyGetter method on the
 * enclosing class (or the static setter on the named class for static properties), enabling
 * Ctrl+click navigation.
 */
public final class Fxml2PropertyTagDescriptor implements XmlElementDescriptor {

    /** The class that owns the property (the parent element's resolved class, or the static class). */
    private final @Nullable PsiClass ownerClass;
    /** The local property name (without any class prefix). */
    private final @NotNull String propertyName;
    /** The full markup name as it appears in XML (may be "ClassName.propName" for static props). */
    private final @NotNull String markupName;
    /** Whether this is a static property (e.g. {@code GridPane.rowIndex}). */
    private final boolean isStatic;

    /**
     * Creates an instance-property tag descriptor.
     *
     * @param ownerClass   the class that declares the property
     * @param propertyName the simple property name (e.g. {@code "text"})
     */
    public Fxml2PropertyTagDescriptor(@Nullable PsiClass ownerClass, @NotNull String propertyName) {
        this.ownerClass = ownerClass;
        this.propertyName = propertyName;
        this.markupName = propertyName;
        this.isStatic = false;
    }

    /**
     * Creates a property tag descriptor with an explicit markup name (used for both static
     * and qualified-instance property notations).
     *
     * @param ownerClass   for static props: the class that declares the static setter (e.g. {@code GridPane});
     *                     for qualified instance props: the parent element's class (e.g. {@code Button})
     * @param propertyName the simple property name (e.g. {@code "rowIndex"} or {@code "text"})
     * @param markupName   the full XML name as written in the file (e.g. {@code "GridPane.rowIndex"})
     * @param isStatic     {@code true} for static property notation, {@code false} for qualified instance notation
     */
    public Fxml2PropertyTagDescriptor(
            @Nullable PsiClass ownerClass,
            @NotNull String propertyName,
            @NotNull String markupName,
            boolean isStatic) {
        this.ownerClass = ownerClass;
        this.propertyName = propertyName;
        this.markupName = markupName;
        this.isStatic = isStatic;
    }


    // -----------------------------------------------------------------------
    // XmlElementDescriptor contract
    // -----------------------------------------------------------------------

    @Override
    public String getQualifiedName() {
        return markupName;
    }

    @Override
    public String getDefaultName() {
        return markupName;
    }

    public boolean isStatic() { return isStatic; }
    public @Nullable PsiClass getOwnerClass() { return ownerClass; }
    public @NotNull String getPropertyName() { return propertyName; }

    @Override
    public @Nullable PsiElement getDeclaration() {
        if (ownerClass == null) return null;
        if (isStatic) {
            return Fxml2PropertyResolver.resolveStaticProperty(ownerClass, propertyName);
        }
        return Fxml2PropertyResolver.resolveInstanceProperty(ownerClass, propertyName);
    }

    @Override
    public XmlElementDescriptor[] getElementsDescriptors(XmlTag context) {
        return XmlElementDescriptor.EMPTY_ARRAY;
    }

    @Override
    public @NotNull XmlElementDescriptor getElementDescriptor(XmlTag childTag, XmlTag contextTag) {
        return new Fxml2ClassTagDescriptor(childTag.getLocalName(), childTag);
    }

    @Override
    public XmlAttributeDescriptor[] getAttributesDescriptors(@Nullable XmlTag context) {
        return XmlAttributeDescriptor.EMPTY;
    }

    @Override
    public @Nullable XmlAttributeDescriptor getAttributeDescriptor(@NonNls String attributeName,
                                                                     @Nullable XmlTag context) {
        return null;
    }

    @Override
    public @Nullable XmlAttributeDescriptor getAttributeDescriptor(XmlAttribute attribute) {
        return null;
    }

    @Override
    public @Nullable XmlNSDescriptor getNSDescriptor() { return null; }

    @Override
    public @Nullable XmlElementsGroup getTopGroup() { return null; }

    @Override
    public int getContentType() { return CONTENT_TYPE_ANY; }

    @Override
    public @Nullable String getDefaultValue() { return null; }

    @Override
    public @NonNls String getName(PsiElement context) { return markupName; }

    @Override
    public @NonNls String getName() { return markupName; }

    @Override
    public void init(PsiElement element) {}

    @Override
    public String toString() {
        String prefix = isStatic ? "static-prop:" : "prop:";
        String owner = ownerClass != null ? ownerClass.getName() + "#" : "?#";
        return "<" + prefix + owner + markupName + ">";
    }
}
