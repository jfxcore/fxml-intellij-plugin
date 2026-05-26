package org.jfxcore.fxml.descriptors;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlElement;
import com.intellij.xml.impl.BasicXmlAttributeDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2PropertyResolver;

/**
 * {@link com.intellij.xml.XmlAttributeDescriptor} for a <em>static</em> property attribute
 * in FXML, e.g. {@code GridPane.rowIndex="2"}.
 *
 * <p>The class part ({@code GridPane}) is resolved to a {@link PsiClass} by the caller;
 * {@link #getDeclaration()} delegates to {@link Fxml2PropertyResolver#resolveStaticProperty}
 * to find the static setter.
 */
public final class Fxml2StaticPropertyAttributeDescriptor extends BasicXmlAttributeDescriptor {

    /** Full attribute name as it appears in XML, e.g. {@code "GridPane.rowIndex"}. */
    private final @NotNull String fullName;
    /** The class that declares the static property (e.g. {@code GridPane}). */
    private final @Nullable PsiClass declaringClass;
    /** The simple property name (e.g. {@code "rowIndex"}). */
    private final @NotNull String propertyName;

    public Fxml2StaticPropertyAttributeDescriptor(
            @NotNull String fullName,
            @Nullable PsiClass declaringClass,
            @NotNull String propertyName) {
        this.fullName = fullName;
        this.declaringClass = declaringClass;
        this.propertyName = propertyName;
    }

    public @Nullable PsiClass getDeclaringClass() {
        return declaringClass;
    }

    /** Resolves the static property accessor: used by the annotator and reference provider. */
    public @Nullable PsiElement resolveProperty() {
        if (declaringClass == null) return null;
        return Fxml2PropertyResolver.resolveStaticProperty(declaringClass, propertyName);
    }

    /**
     * Returns {@code null} intentionally so that the XML layer's {@code XmlAttributeReference}
     * (which covers the whole name) does not resolve and does not outcompete the per-segment
     * references emitted by {@link org.jfxcore.fxml.lang.Fxml2ReferenceContributor}.
     */
    @Override
    public @Nullable PsiElement getDeclaration() {
        return null;
    }

    @Override
    public String getName() {
        return fullName;
    }

    @Override
    public String getName(PsiElement context) {
        return fullName;
    }

    @Override
    public void init(PsiElement element) {}

    @Override
    public boolean isRequired() { return false; }

    @Override
    public boolean isFixed() { return false; }

    @Override
    public boolean hasIdType() { return false; }

    @Override
    public boolean hasIdRefType() { return false; }

    @Override
    public @Nullable String getDefaultValue() { return null; }

    @Override
    public boolean isEnumerated() { return false; }

    @Override
    public String @Nullable [] getEnumeratedValues() { return null; }

    @Override
    public @Nullable String validateValue(XmlElement context, String value) { return null; }

    @Override
    public String toString() {
        return "static:" + (declaringClass != null ? declaringClass.getName() + "#" : "?#") + propertyName;
    }
}
