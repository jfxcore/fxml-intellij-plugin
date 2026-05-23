package org.jfxcore.fxml.lang;

import com.intellij.model.Symbol;
import com.intellij.model.psi.PsiSymbolDeclaration;
import com.intellij.model.psi.PsiSymbolDeclarationProvider;
import com.intellij.model.psi.PsiSymbolService;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.ElementManipulators;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resolve.Fxml2BindingPathResolver;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;

import java.util.Collection;
import java.util.List;

/**
 * Registers each {@code fx:id} attribute value in an FXML 2.0 file as a
 * {@link PsiSymbolDeclaration} in IntelliJ's Symbol/Declaration API.
 *
 * <p>This is what makes IntelliJ treat the {@code fx:id} value as a
 * <em>declaration site</em> rather than a reference.  When the user
 * Ctrl+clicks on it, IntelliJ invokes the "Go To Declaration Or Usages"
 * action which, because a declaration is found at the cursor, switches
 * to "Show Usages" mode instead of "Choose Declaration".  The result is the
 * proper usages popup (with filter buttons, full-line context preview, and
 * grouping) rather than the bare "Choose Declaration" list.
 *
 * <p>The declared {@link Symbol} wraps the code-behind {@link PsiField} when available
 * (so that ctrl-hover shows proper field documentation), falling back to the
 * {@link XmlAttributeValue} itself.  {@link Fxml2FxIdFindUsagesHandlerFactory} is
 * registered to accept both the field and the attribute value so that the usages popup
 * works in either case.
 */
@SuppressWarnings("UnstableApiUsage")
public final class Fxml2FxIdDeclarationProvider implements PsiSymbolDeclarationProvider {

    @Override
    public @NotNull Collection<? extends PsiSymbolDeclaration> getDeclarations(
            @NotNull PsiElement element, int offsetInElement) {

        // The platform walks the PSI tree bottom-up and applies two filters to each
        // declaration returned:
        //   (a) element === it.declaringElement  (Java identity)
        //   (b) offsetInElement < 0 || it.rangeInDeclaringElement.containsOffset(offsetInElement)
        //
        // Strategy:
        //   * Called with a child token (leaf under cursor): return a declaration whose
        //     declaringElement IS that token, with range [0, token.textLength].
        //   * Called with XmlAttributeValue: return a declaration with range = value text
        //     range [1, n-1] (no quotes), for correct visual underline and highlight range.

        XmlAttributeValue attrVal;
        boolean calledWithToken = false;

        if (element instanceof XmlAttributeValue av) {
            attrVal = av;
        } else if (element.getParent() instanceof XmlAttributeValue av) {
            attrVal = av;
            calledWithToken = true;
        } else {
            return List.of();
        }

        if (!(attrVal.getContainingFile() instanceof XmlFile xmlFile)) return List.of();
        if (!Fxml2FileType.isFxml2(xmlFile)) return List.of();
        if (!(attrVal.getParent() instanceof XmlAttribute attr)) return List.of();
        if (!"id".equals(attr.getLocalName())) return List.of();
        if (!Fxml2ImportResolver.FXML2_NAMESPACE.equals(attr.getNamespace())) return List.of();
        if (attrVal.getValue().isBlank()) return List.of();

        if (calledWithToken) {
            return List.of(new FxIdDeclaration(element,
                    TextRange.from(0, element.getTextLength()), attrVal));
        }

        TextRange valueRange = ElementManipulators.getValueTextRange(attrVal);
        return List.of(new FxIdDeclaration(attrVal, valueRange, attrVal));
    }

    /**
     * Returns the code-behind field corresponding to this {@code fx:id} value, or the
     * {@link XmlAttributeValue} itself as fallback.
     * <p>
     * The field is used as the symbol element so that ctrl-hover shows proper field
     * documentation ("Button myButton1") instead of generic XML attribute documentation.
     * {@link Fxml2FxIdFindUsagesHandlerFactory} is registered to also accept {@link PsiField}
     * elements that correspond to fx:id declarations, so the usages popup still works.
     */
    static @NotNull PsiElement resolveFieldOrFallback(@NotNull XmlAttributeValue attrVal) {
        if (!(attrVal.getContainingFile() instanceof XmlFile xmlFile)) return attrVal;
        String idName = attrVal.getValue();
        if (idName.isBlank()) return attrVal;
        PsiClass codeBehind = Fxml2BindingPathResolver.resolveCodeBehindClass(xmlFile);
        if (codeBehind == null) return attrVal;
        PsiField field = codeBehind.findFieldByName(idName, /* checkSuperClasses */ true);
        return field != null ? field : attrVal;
    }

    // -----------------------------------------------------------------------

    /**
     * @param declaringElement        PSI element used for the identity filter (element === declaringElement)
     * @param rangeInDeclaringElement range for visual highlight/underline
     * @param symbolElement           the {@link XmlAttributeValue} carrying the symbol
     */
    private record FxIdDeclaration(
            @NotNull PsiElement declaringElement,
            @NotNull TextRange rangeInDeclaringElement,
            @NotNull XmlAttributeValue symbolElement
    ) implements PsiSymbolDeclaration {

        @Override
        public @NotNull PsiElement getDeclaringElement() { return declaringElement; }

        @Override
        public @NotNull TextRange getRangeInDeclaringElement() { return rangeInDeclaringElement; }

        /**
         * Returns a symbol wrapping the code-behind {@link PsiField} when available,
         * falling back to the {@link XmlAttributeValue} itself.
         * Using the field as the symbol element makes ctrl-hover show proper field
         * documentation ("Button myButton1") rather than generic XML attribute docs.
         */
        @Override
        public @NotNull Symbol getSymbol() {
            PsiElement symbolPsi = resolveFieldOrFallback(symbolElement);
            return PsiSymbolService.getInstance().asSymbol(symbolPsi);
        }
    }
}
