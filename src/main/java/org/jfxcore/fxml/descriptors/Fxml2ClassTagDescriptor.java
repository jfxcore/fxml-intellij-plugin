package org.jfxcore.fxml.descriptors;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.xml.XmlAttributeDescriptor;
import com.intellij.xml.XmlElementDescriptor;
import com.intellij.xml.XmlElementsGroup;
import com.intellij.xml.XmlNSDescriptor;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.lang.Fxml2ElementDescriptorProvider;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;
import org.jfxcore.fxml.resolve.Fxml2PropertyResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes an FXML 2.0 XML element that maps to a Java class.
 * {@link #getDeclaration()} returns the {@link PsiClass} so IntelliJ uses it
 * for Ctrl+click (Go to Declaration) navigation.
 */
public final class Fxml2ClassTagDescriptor implements XmlElementDescriptor {

    private final String name;
    private final @Nullable PsiClass psiClass;

    public Fxml2ClassTagDescriptor(@NotNull String tagName, @NotNull XmlTag tag) {
        this.name = tagName;
        this.psiClass = (tag.getContainingFile() instanceof XmlFile f)
                ? Fxml2ImportResolver.resolve(tagName, f) : null;
    }

    public Fxml2ClassTagDescriptor(@NotNull String tagName, @Nullable PsiClass psiClass) {
        this.name = tagName;
        this.psiClass = psiClass;
    }

    public @Nullable PsiClass getPsiClass() {
        return psiClass;
    }

    // -----------------------------------------------------------------------
    // Static factory: attribute descriptor routing
    // -----------------------------------------------------------------------

    /**
     * Returns the appropriate {@link XmlAttributeDescriptor} for {@code attributeName} on a tag
     * whose owner class is {@code ownerClass}.
     */
    public static @NotNull XmlAttributeDescriptor attributeDescriptorFor(
            @NotNull String attributeName,
            @Nullable PsiClass ownerClass,
            @Nullable XmlTag context) {

        if (attributeName.equals("xmlns") || attributeName.startsWith("xmlns:")) {
            return new Fxml2PropertyAttributeDescriptor(attributeName, null);
        }

        if (attributeName.startsWith("fx:")) {
            return new Fxml2FxAttributeDescriptor(attributeName, context);
        }

        int dot = attributeName.lastIndexOf('.');
        if (dot > 0) {
            String className = attributeName.substring(0, dot);
            String propName  = attributeName.substring(dot + 1);
            if (!propName.isEmpty() && context != null) {
                XmlFile xmlFile = (XmlFile) context.getContainingFile();
                PsiClass staticClass = Fxml2ImportResolver.resolve(className, xmlFile);
                if (staticClass != null) {
                    return new Fxml2StaticPropertyAttributeDescriptor(attributeName, staticClass, propName);
                }
            }
            return new Fxml2PropertyAttributeDescriptor(attributeName, null);
        }

        if (ownerClass == null) {
            return new Fxml2PropertyAttributeDescriptor(attributeName, null);
        }
        return new Fxml2PropertyAttributeDescriptor(attributeName, ownerClass, context);
    }

    // -----------------------------------------------------------------------
    // XmlElementDescriptor contract
    // -----------------------------------------------------------------------

    @Override
    public String getQualifiedName() {
        return psiClass != null ? psiClass.getQualifiedName() : name;
    }

    @Override
    public String getDefaultName() {
        return name;
    }

    @Override
    public XmlElementDescriptor[] getElementsDescriptors(XmlTag context) {
        return XmlElementDescriptor.EMPTY_ARRAY;
    }

    @Override
    public @Nullable XmlElementDescriptor getElementDescriptor(XmlTag childTag, XmlTag contextTag) {
        if (!(childTag.getContainingFile() instanceof XmlFile xmlFile)) return null;
        return Fxml2ElementDescriptorProvider.descriptorFor(childTag, xmlFile);
    }

    @Override
    public XmlAttributeDescriptor[] getAttributesDescriptors(@Nullable XmlTag context) {
        List<XmlAttributeDescriptor> result = new ArrayList<>();

        // fx: intrinsic attributes: always offered for any class tag.
        // The full list mirrors Fxml2FxAttributeInspection.KNOWN_INTRINSICS.
        for (String intrinsic : List.of("id", "context", "typeArguments", "factory",
                "value", "constant", "itemType")) {
            result.add(new Fxml2FxAttributeDescriptor("fx:" + intrinsic, context));
        }
        // Root-only attributes (fx:subclass, fx:classModifier, etc.) are also offered as
        // attribute name completions: the inspection will flag them if placed on non-root tags.
        for (String rootIntrinsic : List.of("subclass", "classModifier", "classParameters",
                "className")) {
            result.add(new Fxml2FxAttributeDescriptor("fx:" + rootIntrinsic, context));
        }

        if (psiClass == null) return result.toArray(XmlAttributeDescriptor.EMPTY);

        // Instance property attributes
        for (String propName : Fxml2PropertyResolver.getAllPropertyNames(psiClass)) {
            result.add(new Fxml2PropertyAttributeDescriptor(propName, psiClass, context));
        }

        // Static property attributes from imports (e.g. VBox.vgrow, GridPane.rowIndex)
        if (context != null && context.getContainingFile() instanceof XmlFile xmlFile) {
            for (String importFqn : Fxml2ImportResolver.parseImports(xmlFile)) {
                if (importFqn.endsWith(".*")) continue;
                String simpleName = importFqn.substring(importFqn.lastIndexOf('.') + 1);
                PsiClass importedClass = Fxml2ImportResolver.resolve(simpleName, xmlFile);
                if (importedClass == null) continue;
                for (String staticProp : Fxml2PropertyResolver.getAllStaticPropertyNames(importedClass)) {
                    String fullName = simpleName + "." + staticProp;
                    result.add(new Fxml2StaticPropertyAttributeDescriptor(fullName, importedClass, staticProp));
                }
            }
        }

        return result.toArray(XmlAttributeDescriptor.EMPTY);
    }

    @Override
    public @Nullable XmlAttributeDescriptor getAttributeDescriptor(@NonNls String attributeName,
                                                                    @Nullable XmlTag context) {
        if (attributeName == null || attributeName.isEmpty()) return null;
        return attributeDescriptorFor(attributeName, psiClass, context);
    }

    @Override
    public @Nullable XmlAttributeDescriptor getAttributeDescriptor(XmlAttribute attribute) {
        return getAttributeDescriptor(attribute.getName(), attribute.getParent());
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
    public PsiElement getDeclaration() { return psiClass; }

    @Override
    public @NonNls String getName(PsiElement context) { return name; }

    @Override
    public @NonNls String getName() { return name; }

    @Override
    public void init(PsiElement element) {}

    @Override
    public String toString() {
        return "Fxml2ClassTagDescriptor(" + name + ")";
    }
}
