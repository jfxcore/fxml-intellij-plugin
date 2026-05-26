package org.jfxcore.fxml.lang;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jetbrains.annotations.NotNull;

/**
 * A soft {@link com.intellij.psi.PsiReference} placed on the binding-notation prefix
 * of an FXML attribute value (e.g. the {@code $} in {@code $vm.foo}, the {@code ${} in
 * {@code ${vm.foo}}, the {@code {fx:Observe} in {@code {fx:Observe vm.foo}}).
 *
 * <p>{@link #resolve()} returns a {@link Fxml2NamespaceUrlReference.UrlNavigationTarget}
 * that opens the compiled-expressions documentation page in the system browser, so
 * Ctrl+click on a binding-notation prefix navigates to the online language reference.
 *
 * <p>The documented binding-notation table:
 * <pre>
 *   $x  / {fx:Evaluate x}: one-time evaluation
 *   ${x} / {fx:Observe x}: unidirectional binding
 *   &gt;{x} / {fx:Push x}: reverse binding
 *   #{x} / {fx:Synchronize x}: bidirectional binding
 * </pre>
 */
public final class Fxml2BindingNotationReference extends PsiReferenceBase<XmlAttributeValue> {

    /**
     * The online documentation URL for compiled binding expressions.
     * All notation kinds ({@code $}, {@code ${}}, {@code >{}}, {@code #{}})
     * share the same documentation page.
     */
    static final String EXPRESSION_DOCS_URL =
            "https://jfxcore.github.io/fxml-compiler/markup-extension/expression.html#compiled-expressions";

    /** The kind of binding this notation represents. */
    public enum Kind {
        /** One-time evaluation: {@code $x} / {@code {fx:Evaluate x}} */
        EVALUATE,
        /** Unidirectional binding: {@code ${x}} / {@code {fx:Observe x}} */
        OBSERVE,
        /** Reverse binding: {@code >{x}} / {@code {fx:Push x}} */
        PUSH,
        /** Bidirectional binding: {@code #{x}} / {@code {fx:Synchronize x}} */
        SYNCHRONIZE,
        /** One-time content assignment: {@code $..x} / {@code {fx:Evaluate ..x}} */
        EVALUATE_CONTENT,
        /** Unidirectional content binding: {@code ${..x}} / {@code {fx:Observe ..x}} */
        OBSERVE_CONTENT,
        /** Reverse content binding: {@code >{..x}} / {@code {fx:Push ..x}} */
        PUSH_CONTENT,
        /** Bidirectional content binding: {@code #{..x}} / {@code {fx:Synchronize ..x}} */
        SYNCHRONIZE_CONTENT
    }

    public Fxml2BindingNotationReference(
            @NotNull XmlAttributeValue element,
            @NotNull TextRange rangeInElement) {
        super(element, rangeInElement, /* soft= */ true);
    }

    @Override
    public @NotNull PsiElement resolve() {
        return new Fxml2NamespaceUrlReference.UrlNavigationTarget(getElement(), EXPRESSION_DOCS_URL);
    }

    @Override
    public boolean isSoft() {
        return true;
    }
}
