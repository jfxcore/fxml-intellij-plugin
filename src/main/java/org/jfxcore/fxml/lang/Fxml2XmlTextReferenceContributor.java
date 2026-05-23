package org.jfxcore.fxml.lang;

import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlToken;
import com.intellij.psi.xml.XmlTokenType;
import com.intellij.psi.xml.XmlText;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.descriptors.Fxml2ClassTagDescriptor;
import org.jfxcore.fxml.descriptors.Fxml2PropertyTagDescriptor;
import org.jfxcore.fxml.resolve.Fxml2AttributeValueResolver;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;
import org.jfxcore.fxml.resolve.Fxml2XmlUtil;

import static com.intellij.patterns.PlatformPatterns.virtualFile;

/**
 * Provides references for literal text inside property element tags, e.g.
 * {@code MULTIPLE} inside {@code <selectionModel.selectionMode>MULTIPLE</selectionModel.selectionMode>}.
 *
 * <p>Registered for {@link XmlToken} elements (specifically {@code XML_DATA_CHARACTERS}) rather
 * than {@link com.intellij.psi.xml.XmlText}, because {@code XmlTokenImpl} is the actual
 * {@code HintedReferenceHost} that calls {@code ReferenceProvidersRegistry.getReferencesFromProviders}
 *: {@code XmlTextImpl} does not call it at all.
 */
public final class Fxml2XmlTextReferenceContributor extends PsiReferenceContributor {

    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(
                PlatformPatterns.psiElement(XmlToken.class)
                        .withElementType(XmlTokenType.XML_DATA_CHARACTERS)
                        .inVirtualFile(virtualFile().ofType(Fxml2FileType.INSTANCE)),
                new XmlDataCharactersProvider(),
                PsiReferenceRegistrar.DEFAULT_PRIORITY);
    }

    private static final class XmlDataCharactersProvider extends PsiReferenceProvider {

        @Override
        public PsiReference @NotNull [] getReferencesByElement(
                @NotNull PsiElement element, @NotNull ProcessingContext context) {

            if (!(element instanceof XmlToken token)) return PsiReference.EMPTY_ARRAY;
            if (token.getTokenType() != XmlTokenType.XML_DATA_CHARACTERS) return PsiReference.EMPTY_ARRAY;
            if (!(token.getContainingFile() instanceof XmlFile xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2FileType.isFxml2(xmlFile)) return PsiReference.EMPTY_ARRAY;

            String text = token.getText().trim();
            if (text.isBlank()) return PsiReference.EMPTY_ARRAY;
            if (Fxml2BindingExpressionParser.looksLikeBindingExpression(text)) return PsiReference.EMPTY_ARRAY;

            // Parent must be XmlText, grandparent must be a property XmlTag
            if (!(token.getParent() instanceof XmlText xmlText)) return PsiReference.EMPTY_ARRAY;
            if (!(xmlText.getParent() instanceof XmlTag propTag)) return PsiReference.EMPTY_ARRAY;

            // Also guard on the full XmlText value: the XML lexer may split "{fx:foo bar}" into
            // multiple tokens; the individual token "bar}" alone doesn't look like a binding expression.
            if (Fxml2BindingExpressionParser.looksLikeBindingExpression(xmlText.getValue().trim()))
                return PsiReference.EMPTY_ARRAY;

            // Skip class tags, only handle property tags
            if (Fxml2ImportResolver.resolve(propTag.getLocalName(), xmlFile) != null) return PsiReference.EMPTY_ARRAY;

            // --- Static property element tag (e.g. <VBox.vgrow>ALWAYS</VBox.vgrow>) ---
            if (propTag.getDescriptor() instanceof Fxml2PropertyTagDescriptor ptd && ptd.isStatic()) {
                PsiClass ownerClass = ptd.getOwnerClass();
                if (ownerClass == null) return PsiReference.EMPTY_ARRAY;
                Fxml2AttributeValueResolver.Result result =
                        Fxml2AttributeValueResolver.resolveStatic(ownerClass, ptd.getPropertyName(), text);
                if (result.declaration() == null) return PsiReference.EMPTY_ARRAY;
                return singleDeclReference(element, token.getTextLength(), result.declaration());
            }

            // --- Instance property element tag (e.g. <selectionModel.selectionMode>MULTIPLE</...>) ---
            XmlTag classTag = Fxml2XmlUtil.findEnclosingClassTag(propTag, xmlFile);
            if (classTag == null) return PsiReference.EMPTY_ARRAY;

            if (!(classTag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd)) return PsiReference.EMPTY_ARRAY;
            PsiClass ownerClass = cd.getPsiClass();
            if (ownerClass == null) return PsiReference.EMPTY_ARRAY;

            String propertyPath = Fxml2XmlUtil.buildPropertyPath(propTag, classTag);
            if (propertyPath == null) return PsiReference.EMPTY_ARRAY;

            Object[] chain = Fxml2XmlUtil.resolveChainedPropertyOwner(ownerClass, propertyPath);
            if (chain == null) return PsiReference.EMPTY_ARRAY;
            PsiClass finalClass = (PsiClass) chain[0];
            String lastProp = (String) chain[1];

            GlobalSearchScope scope = xmlFile.getResolveScope();
            Fxml2AttributeValueResolver.Result result =
                    Fxml2AttributeValueResolver.resolve(finalClass, lastProp, text, scope);
            if (result.declaration() == null) return PsiReference.EMPTY_ARRAY;
            return singleDeclReference(element, token.getTextLength(), result.declaration());
        }
    }

    /** Returns a single-element reference array that resolves to {@code decl}. */
    private static PsiReference @NotNull [] singleDeclReference(
            @NotNull PsiElement element, int length, @NotNull PsiElement decl) {
        TextRange range = new TextRange(0, length);
        return new PsiReference[]{
                new PsiReferenceBase<>(element, range, true) {
                    @Override public @NotNull PsiElement resolve() { return decl; }
                }
        };
    }
}
