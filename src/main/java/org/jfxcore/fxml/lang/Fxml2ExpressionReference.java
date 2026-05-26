package org.jfxcore.fxml.lang;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A soft, always-unresolved {@link PsiReference} covering an entire FXML binding
 * expression in an XML attribute value (e.g. {@code $vm.message},
 * {@code {fx:Observe vm.message}}).
 *
 * <p>Because {@link #isSoft()} returns {@code true}, IntelliJ treats a {@code null}
 * result from {@link #resolve()} as "OK: this reference is intentionally unresolved"
 * and does <em>not</em> report a "Cannot resolve symbol" error.
 *
 * <p>The reference covers the entire value text (minus the surrounding quotes) so that
 * no sub-range is left unaccounted for and no other reference provider can attach a
 * hard reference to the same range.
 */
public final class Fxml2ExpressionReference extends PsiReferenceBase<XmlAttributeValue> {

    public Fxml2ExpressionReference(@NotNull XmlAttributeValue element) {
        // getRangeInElement covers the text inside the quotes of the attribute value.
        super(element, innerRange(element), /* soft= */ true);
    }

    /** Compute the range inside the XmlAttributeValue that excludes the surrounding quotes. */
    private static TextRange innerRange(@NotNull XmlAttributeValue element) {
        String text = element.getText(); // includes surrounding quotes
        if (text.length() >= 2) {
            char first = text.charAt(0);
            if (first == '"' || first == '\'') {
                return new TextRange(1, text.length() - 1);
            }
        }
        return new TextRange(0, text.length());
    }

    /** Always returns {@code null}: the expression is handled by the FXML compiler, not the IDE. */
    @Override
    public @Nullable PsiElement resolve() {
        return null;
    }

    /** Soft: resolving to {@code null} is not an error. */
    @Override
    public boolean isSoft() {
        return true;
    }
}
