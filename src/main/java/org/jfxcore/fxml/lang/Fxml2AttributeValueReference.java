package org.jfxcore.fxml.lang;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A {@link com.intellij.psi.PsiReference} on a literal XML attribute value
 * (e.g. {@code "CENTER"} in {@code alignment="CENTER"}) that navigates to the
 * corresponding PSI element (enum constant, static field, Color field, etc.).
 *
 * <p>Normally created as a <em>soft</em> reference so that an unresolvable value does not
 * produce a generic "Cannot resolve" IDE error; {@link org.jfxcore.fxml.annotator.Fxml2AttributeAnnotator}
 * produces the diagnostics with compiler-matching messages.
 *
 * <p>Boolean operator references ({@code !} / {@code !!}) are created with {@code soft=false}
 * so that they win the
 * {@link com.intellij.psi.impl.source.resolve.reference.impl.PsiMultiReference} tie-breaking
 * against the adjacent soft binding-notation reference (whose range ends at the same offset
 * where the operator begins).
 */
public final class Fxml2AttributeValueReference extends PsiReferenceBase<XmlAttributeValue> {

    private final @Nullable PsiElement myDeclaration;

    /**
     * Constructs a soft reference (default). Use this for literal value references (enum
     * constants, static fields, etc.) where an unresolved value should not produce an error.
     */
    public Fxml2AttributeValueReference(
            @NotNull XmlAttributeValue element,
            @NotNull TextRange rangeInElement,
            @Nullable PsiElement declaration) {
        this(element, rangeInElement, declaration, /* soft= */ true);
    }

    /**
     * Full constructor that lets callers control the soft/hard classification.
     *
     * <p>Use {@code soft=false} for operator references (e.g. {@code !} / {@code !!}) where
     * the declaration is always non-null and navigation should win the
     * {@link com.intellij.psi.impl.source.resolve.reference.impl.PsiMultiReference} tie-breaking
     * against adjacent soft references (e.g. the binding-notation reference whose range ends
     * exactly at the operator's start position).
     */
    public Fxml2AttributeValueReference(
            @NotNull XmlAttributeValue element,
            @NotNull TextRange rangeInElement,
            @Nullable PsiElement declaration,
            boolean soft) {
        super(element, rangeInElement, soft);
        this.myDeclaration = declaration;
    }

    @Override
    public @Nullable PsiElement resolve() {
        return myDeclaration;
    }
}
