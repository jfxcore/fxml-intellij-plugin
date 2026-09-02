package org.jfxcore.fxml.lang;

import com.intellij.lang.documentation.DocumentationMarkup;
import com.intellij.model.Pointer;
import com.intellij.openapi.util.TextRange;
import com.intellij.platform.backend.documentation.DocumentationResult;
import com.intellij.platform.backend.documentation.DocumentationTarget;
import com.intellij.platform.backend.documentation.DocumentationTargetProvider;
import com.intellij.platform.backend.presentation.TargetPresentation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.xml.util.XmlStringUtil;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resource.Fxml2ResourceName;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows the CSS rule a style class is declared by when the cursor is on one of the class names of
 * a {@code styleClass} attribute value: the same preview a declaration gets in the language its
 * declaration site is written in, for a declaration site the plugin models itself.
 *
 * <p>A style class is declared by a class selector, which the plugin resolves to a
 * {@link CssSelectorElement} in a {@code .css} file or in a stylesheet written into a
 * {@code <?resource ?>} declaration of the document. Neither declaration site carries
 * documentation of its own, so the rule text is rendered here, followed by the stylesheet the
 * rule is written in: the name of the embedded resource, or the file name of the stylesheet.
 *
 * <p>One target is produced per resolved selector, so that a class declared by several
 * stylesheets shows the rule of each of them.
 */
@SuppressWarnings("UnstableApiUsage")
public final class Fxml2StyleClassDocumentationTargetProvider implements DocumentationTargetProvider {

    @Override
    public @NotNull List<? extends @NotNull DocumentationTarget> documentationTargets(
            @NotNull PsiFile file, int offset) {

        List<DocumentationTarget> targets = new ArrayList<>();
        for (CssSelectorElement selector : resolveSelectorsAt(file, offset)) {
            targets.add(new CssSelectorDocumentationTarget(
                    selector.getName(), documentationHtmlOf(selector), locationOf(selector)));
        }
        return targets;
    }

    /**
     * Returns the class selectors that declare the style class at {@code offset}, or an empty list
     * when the offset is on no style-class name of an FXML/2 document.
     */
    public static @NotNull List<CssSelectorElement> resolveSelectorsAt(@NotNull PsiFile file, int offset) {
        Fxml2AttributeValueAtOffset position = Fxml2AttributeValueAtOffset.find(file, offset);
        if (position == null) return List.of();

        XmlAttributeValue attributeValue = position.attributeValue();
        if (!(attributeValue.getParent() instanceof XmlAttribute attribute)) return List.of();
        if (!Fxml2CssUtil.isStyleClassAttribute(attribute)) return List.of();

        int offsetInAttributeValue = position.offsetInAttributeValue();
        List<CssSelectorElement> selectors = new ArrayList<>();
        for (PsiReference reference : attributeValue.getReferences()) {
            if (!(reference instanceof Fxml2StyleClassReference styleClassReference)) continue;

            // Only the class name under the cursor is documented, so that hovering over one name
            // of a list of style classes does not show the rules of its neighbors.
            TextRange nameRange = styleClassReference.getRangeInElement();
            if (offsetInAttributeValue < nameRange.getStartOffset()
                    || offsetInAttributeValue > nameRange.getEndOffset()) continue;

            for (ResolveResult result : styleClassReference.multiResolve(false)) {
                PsiElement resolved = result.getElement();
                if (resolved instanceof CssSelectorElement selector) selectors.add(selector);
            }
        }
        return selectors;
    }

    /**
     * Returns the documentation shown for {@code selector}: the source text of the rule it is
     * written in, followed by the stylesheet that rule is written in.
     */
    public static @NotNull String documentationHtmlOf(@NotNull CssSelectorElement selector) {
        return DocumentationMarkup.DEFINITION_START
                + "<pre>" + XmlStringUtil.escapeString(Fxml2CssUtil.ruleTextOf(selector)) + "</pre>"
                + DocumentationMarkup.DEFINITION_END
                + DocumentationMarkup.CONTENT_START
                + XmlStringUtil.escapeString(locationOf(selector))
                + DocumentationMarkup.CONTENT_END;
    }

    /** Returns the stylesheet {@code selector} is written in, as it is shown to the user. */
    private static @NotNull String locationOf(@NotNull CssSelectorElement selector) {
        Fxml2ResourceName resourceName = Fxml2CssUtil.embeddedResourceNameOf(selector);
        return resourceName != null
                ? resourceName.value()
                : selector.getContainingFile().getName();
    }

    // -----------------------------------------------------------------------

    /**
     * A {@link DocumentationTarget} that renders the source text of a CSS rule and the stylesheet
     * it is written in.
     *
     * <p>The rendered documentation is held rather than the selector it was read from: the
     * selector represents a span of file text and is recreated by every resolve, so there is no
     * element to point at across a reparse.
     */
    private record CssSelectorDocumentationTarget(@NotNull String className,
                                                  @NotNull String html,
                                                  @NotNull String location) implements DocumentationTarget {

        @Override
        public @NotNull Pointer<CssSelectorDocumentationTarget> createPointer() {
            CssSelectorDocumentationTarget target = this;
            return () -> target;
        }

        @Override
        public @NotNull TargetPresentation computePresentation() {
            return TargetPresentation.builder("." + className)
                    .locationText(location)
                    .presentation();
        }

        @Override
        public @NotNull DocumentationResult computeDocumentation() {
            return DocumentationResult.documentation(html);
        }
    }
}
