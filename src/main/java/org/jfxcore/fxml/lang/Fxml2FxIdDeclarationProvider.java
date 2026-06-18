package org.jfxcore.fxml.lang;

import com.intellij.model.Symbol;
import com.intellij.model.psi.PsiSymbolDeclaration;
import com.intellij.model.psi.PsiSymbolDeclarationProvider;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.ElementManipulators;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;

import java.util.Collection;
import java.util.List;

/**
 * Registers each {@code fx:id} attribute value in an FXML file as a
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
 * <p>The declared {@link Symbol} is an {@link FxIdSymbol} that navigates to the
 * code-behind field (for proper hover documentation), provides documentation from
 * the field quick-doc, and supports the symbol-based "Show Usages" path via
 * {@link Fxml2FxIdUsageSearcher}.
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
         * Returns an {@link FxIdSymbol} for this declaration.
         * The symbol navigates to the code-behind field (for proper hover documentation),
         * provides field quick-documentation, and participates in the symbol-based
         * "Show Usages" path via {@link Fxml2FxIdUsageSearcher}.
         */
        @Override
        public @NotNull Symbol getSymbol() {
            return FxIdSymbol.of(symbolElement);
        }
    }
}
