package org.jfxcore.fxml.lang;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jetbrains.annotations.NotNull;

/**
 * A soft reference placed on the {@code format} or {@code converter} keyword in a
 * bidirectional binding expression (e.g. the {@code format} in
 * {@code #{value; format=path.to.format}}).
 *
 * <p>{@link #resolve()} returns a {@link Fxml2NamespaceUrlReference.UrlNavigationTarget}
 * that opens the string-conversion documentation page in the system browser, so
 * Ctrl+click on either keyword navigates to the online language reference.
 */
public final class Fxml2ConversionParamNameReference extends PsiReferenceBase<XmlAttributeValue> {

    public static final String CONVERSION_DOCS_URL =
            "https://jfxcore.github.io/fxml-compiler/markup-extension/expression/conversion.html";

    public Fxml2ConversionParamNameReference(
            @NotNull XmlAttributeValue element,
            @NotNull TextRange rangeInElement) {
        super(element, rangeInElement, /* soft= */ true);
    }

    @Override
    public @NotNull PsiElement resolve() {
        return new Fxml2NamespaceUrlReference.UrlNavigationTarget(getElement(), CONVERSION_DOCS_URL);
    }

    @Override
    public boolean isSoft() {
        return true;
    }
}
