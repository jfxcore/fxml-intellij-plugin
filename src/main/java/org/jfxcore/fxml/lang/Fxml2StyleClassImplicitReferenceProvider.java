package org.jfxcore.fxml.lang;

import com.intellij.model.Symbol;
import com.intellij.model.psi.ImplicitReferenceProvider;
import com.intellij.model.psi.PsiSymbolReference;
import com.intellij.model.psi.PsiSymbolService;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlToken;
import com.intellij.psi.xml.XmlTokenType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;

/**
 * Bridges {@link Fxml2StyleClassReference} (a legacy {@link com.intellij.psi.PsiPolyVariantReference})
 * into IntelliJ's {@link PsiSymbolReference} infrastructure so that:
 * <ul>
 *   <li>Ctrl+hover underlines <em>only</em> the class name under the cursor (tight range)</li>
 *   <li>Ctrl+click shows the "Choose Target" popup when the selector exists in multiple CSS files</li>
 * </ul>
 *
 * <p>This EP is called for each element from the leaf up.  We intercept
 * {@code XML_ATTRIBUTE_VALUE_TOKEN} tokens and their parent {@link XmlAttributeValue}
 * elements, find the {@link Fxml2StyleClassReference} whose range contains
 * {@code offsetInElement}, and return a tight {@link PsiSymbolReference} for it.
 *
 * <p>Going through {@code allReferencesAround -> fromTargetData -> TargetGTDActionData}
 * gives correct tight hover ranges <em>and</em> a multi-symbol navigation chooser without
 * any custom {@code GotoDeclarationHandler}.
 */
@SuppressWarnings("UnstableApiUsage")
public final class Fxml2StyleClassImplicitReferenceProvider implements ImplicitReferenceProvider {

    @Override
    public @Nullable PsiSymbolReference getImplicitReference(
            @NotNull PsiElement element, int offsetInElement) {

        // Determine the XmlAttributeValue and the offset relative to it.
        XmlAttributeValue attrVal;
        int relOffsetInAttr;

        if (element instanceof XmlToken token
                && token.getTokenType() == XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN
                && token.getParent() instanceof XmlAttributeValue av) {
            attrVal = av;
            // Token starts after the opening quote, so add 1 to map into attrVal space.
            relOffsetInAttr = 1 + offsetInElement;
        } else if (element instanceof XmlAttributeValue av) {
            attrVal = av;
            relOffsetInAttr = offsetInElement;
        } else {
            return null;
        }

        if (!(attrVal.getContainingFile() instanceof XmlFile xmlFile)) return null;
        if (!Fxml2FileType.isFxml2(xmlFile)) return null;
        if (!(attrVal.getParent() instanceof XmlAttribute attr)) return null;
        if (!Fxml2CssUtil.isStyleClassAttribute(attr)) return null;

        for (PsiReference ref : attrVal.getReferences()) {
            if (!(ref instanceof Fxml2StyleClassReference svr)) continue;
            if (!ref.getRangeInElement().containsOffset(relOffsetInAttr)) continue;

            TextRange absoluteRange = ref.getRangeInElement()
                    .shiftRight(attrVal.getTextRange().getStartOffset());
            return new StyleClassSymbolReference(element, absoluteRange, svr);
        }
        return null;
    }

    // -----------------------------------------------------------------------

    private static final class StyleClassSymbolReference implements PsiSymbolReference {

        private final @NotNull PsiElement myElement;
        private final @NotNull TextRange myAbsoluteRange;
        private final @NotNull Fxml2StyleClassReference myDelegate;

        StyleClassSymbolReference(
                @NotNull PsiElement element,
                @NotNull TextRange absoluteRange,
                @NotNull Fxml2StyleClassReference delegate) {
            myElement       = element;
            myAbsoluteRange = absoluteRange;
            myDelegate      = delegate;
        }

        @Override
        public @NotNull PsiElement getElement() {
            return myElement;
        }

        @Override
        public @NotNull TextRange getRangeInElement() {
            return myAbsoluteRange.shiftLeft(myElement.getTextRange().getStartOffset());
        }

        @Override
        public @NotNull TextRange getAbsoluteRange() {
            return myAbsoluteRange;
        }

        @Override
        public @NotNull Collection<? extends Symbol> resolveReference() {
            return Arrays.stream(myDelegate.multiResolve(false))
                    .map(ResolveResult::getElement)
                    .filter(Objects::nonNull)
                    .map(el -> PsiSymbolService.getInstance().asSymbol(el))
                    .toList();
        }
    }
}
