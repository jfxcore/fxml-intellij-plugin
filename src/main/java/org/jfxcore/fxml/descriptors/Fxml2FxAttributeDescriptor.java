package org.jfxcore.fxml.descriptors;

import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlElement;
import com.intellij.xml.impl.BasicXmlAttributeDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@link com.intellij.xml.XmlAttributeDescriptor} for fx:-namespace intrinsic attributes
 * such as {@code fx:id}, {@code fx:subclass}, {@code fx:context}, {@code fx:typeArguments}.
 *
 * <p>The {@code declaration} is the {@code xmlns:fx} attribute on the root tag (or the
 * containing tag), kept non-null so that IntelliJ's {@code XmlAttributeReference.isSoft()}
 * returns {@code false} only when we intend it to: and {@code resolve()} returns non-null,
 * preventing the spurious "Attribute X is not allowed here" from {@code XmlHighlightVisitor}.
 */
public final class Fxml2FxAttributeDescriptor extends BasicXmlAttributeDescriptor {

    private final @NotNull String name;
    private final @Nullable PsiElement declaration;

    public Fxml2FxAttributeDescriptor(@NotNull String name, @Nullable PsiElement declaration) {
        this.name = name;
        this.declaration = declaration;
    }

    @Override
    public @Nullable PsiElement getDeclaration() {
        return declaration;
    }

    @Override
    public @NotNull String getName() {
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
        return "fx:id".equals(name);
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

    @Override
    public String toString() {
        return name;
    }
}
