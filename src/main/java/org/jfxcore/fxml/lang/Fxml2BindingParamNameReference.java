package org.jfxcore.fxml.lang;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A soft reference placed on a secondary-parameter keyword of a bidirectional binding
 * expression: the {@code format} or {@code converter} keyword of a string-conversion
 * parameter (e.g. the {@code format} in {@code #{value; format=path.to.format}}), or the
 * {@code inverseMethod} keyword of an inverse-method parameter (e.g. the
 * {@code inverseMethod} in {@code #{value; inverseMethod=path.to.method}}).
 *
 * <p>{@link #resolve()} returns a {@link Fxml2NamespaceUrlReference.UrlNavigationTarget}
 * that opens the documentation page describing that parameter in the system browser, so
 * Ctrl+click on the keyword navigates to the online language reference.  The target page
 * depends on the parameter: {@code format}/{@code converter} open the string-conversion
 * page, while {@code inverseMethod} opens the bidirectional-function-binding section of the
 * function-expressions page.
 */
public final class Fxml2BindingParamNameReference extends PsiReferenceBase<XmlAttributeValue> {

    /** Documentation page for the {@code format=} and {@code converter=} parameters. */
    public static final String CONVERSION_DOCS_URL =
            "https://jfxcore.github.io/fxml-compiler/markup-extension/expression/conversion.html";

    /** Documentation page (and anchor) for the {@code inverseMethod=} parameter. */
    public static final String INVERSE_METHOD_DOCS_URL =
            "https://jfxcore.github.io/fxml-compiler/markup-extension/expression/function.html"
                    + "#bidirectional-function-binding-with-inverse-method";

    /**
     * Returns the documentation URL for the given secondary-parameter keyword, or
     * {@code null} when the name is not a recognized binding parameter.
     */
    public static @Nullable String docUrlForParam(@NotNull String paramName) {
        return switch (paramName) {
            case "inverseMethod" -> INVERSE_METHOD_DOCS_URL;
            case "format", "converter" -> CONVERSION_DOCS_URL;
            default -> null;
        };
    }

    private final @NotNull String docsUrl;

    public Fxml2BindingParamNameReference(
            @NotNull XmlAttributeValue element,
            @NotNull TextRange rangeInElement,
            @NotNull String docsUrl) {
        super(element, rangeInElement, /* soft= */ true);
        this.docsUrl = docsUrl;
    }

    @Override
    public @NotNull PsiElement resolve() {
        return new Fxml2NamespaceUrlReference.UrlNavigationTarget(getElement(), docsUrl);
    }

    @Override
    public boolean isSoft() {
        return true;
    }
}
