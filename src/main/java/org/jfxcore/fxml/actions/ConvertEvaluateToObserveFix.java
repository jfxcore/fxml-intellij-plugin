package org.jfxcore.fxml.actions;

import com.intellij.codeInsight.intention.FileModifier;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser.ParsedExpression;
import org.jfxcore.fxml.resolve.Fxml2ExpressionParser;

/**
 * Quickfix that turns a one-time evaluation into an observable binding.
 *
 * <p>A one-time evaluation with an {@code :element} or {@code :parent} context selector reads the
 * context element while that element may still be uninitialized, so its result depends on element
 * initialization order.  An observable binding re-evaluates whenever the source changes and is
 * therefore independent of initialization order.
 *
 * <p>The fix applies to all three notations of a one-time evaluation:
 * <ul>
 *   <li>compact: {@code $(:element.width * 2)} -> {@code ${:element.width * 2}}</li>
 *   <li>attribute markup extension: {@code {fx:Evaluate source=:parent.width}}
 *       -> {@code {fx:Observe source=:parent.width}}</li>
 *   <li>element markup extension: {@code <fx:Evaluate source=":parent.width"/>}
 *       -> {@code <fx:Observe source=":parent.width"/>}</li>
 * </ul>
 *
 * <p>Because the compact observable notation encloses the expression in curly braces, a pair of
 * parentheses that surrounds the whole expression becomes superfluous and is removed.
 */
public final class ConvertEvaluateToObserveFix implements LocalQuickFix {

    /** The place the reported one-time evaluation is written in. */
    public sealed interface Site {

        /**
         * The evaluation is one item of an attribute value.
         *
         * @param range the range of the item within the value of the attribute
         */
        record AttributeValueItem(@NotNull TextRange range) implements Site {}

        /** The evaluation is the source of an {@code fx:Evaluate} element. */
        record EvaluateElement() implements Site {}
    }

    private static final String EVALUATE_KEYWORD = "Evaluate";
    private static final String OBSERVE_KEYWORD = "Observe";

    /** The site holds no file-related state, so the fix can be applied to a preview copy. */
    @FileModifier.SafeFieldForPreview
    private final Site site;

    public ConvertEvaluateToObserveFix(@NotNull Site site) {
        this.site = site;
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Convert to observable binding";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiElement element = descriptor.getPsiElement();
        if (element == null) return;
        switch (site) {
            case Site.EvaluateElement ignored -> {
                XmlTag tag = enclosingEvaluateTag(element);
                if (tag != null) tag.setName(observeTagName(tag));
            }
            case Site.AttributeValueItem(TextRange range) -> {
                if (!(element instanceof XmlAttributeValue value)) return;
                if (!(value.getParent() instanceof XmlAttribute attribute)) return;
                String text = value.getValue();
                if (range.getEndOffset() > text.length()) return;
                String converted = toObserveNotation(range.substring(text));
                if (converted == null) return;
                attribute.setValue(text.substring(0, range.getStartOffset())
                        + converted
                        + text.substring(range.getEndOffset()));
            }
        }
    }

    /** Returns the {@code fx:Evaluate} tag whose source is written in {@code element}. */
    private static @Nullable XmlTag enclosingEvaluateTag(@NotNull PsiElement element) {
        if (element instanceof XmlAttributeValue value
                && value.getParent() instanceof XmlAttribute attribute) {
            return attribute.getParent();
        }
        return element.getParent() instanceof XmlTag tag ? tag : null;
    }

    /** Returns the {@code fx:Observe} tag name that matches the prefix of the given tag. */
    private static @NotNull String observeTagName(@NotNull XmlTag tag) {
        String prefix = tag.getNamespacePrefix();
        return prefix.isEmpty() ? OBSERVE_KEYWORD : prefix + ":" + OBSERVE_KEYWORD;
    }

    /**
     * Converts an attribute value that holds a one-time evaluation into the equivalent
     * observable binding, or returns {@code null} when the value is not a one-time evaluation.
     */
    public static @Nullable String toObserveNotation(@NotNull String attributeValue) {
        ParsedExpression expression = Fxml2BindingExpressionParser.parseExpression(attributeValue);
        if (expression == null) return null;
        boolean content = expression.kind() == Kind.EVALUATE_CONTENT;
        if (expression.kind() != Kind.EVALUATE && !content) return null;

        if (ConvertBindingNotationIntention.isCompactNotation(expression)) {
            String path = unwrapEnclosingGroup(expression.path());
            return content ? "${.." + path + "}" : "${" + path + "}";
        }

        // Long form: only the intrinsic keyword changes, so any secondary parameters are kept.
        int keywordStart = attributeValue.indexOf(EVALUATE_KEYWORD);
        if (keywordStart < 0 || keywordStart >= expression.pathOffset()) return null;
        return attributeValue.substring(0, keywordStart)
                + OBSERVE_KEYWORD
                + attributeValue.substring(keywordStart + EVALUATE_KEYWORD.length());
    }

    /**
     * Removes a pair of parentheses that encloses the entire expression, since the curly braces
     * of the observable notation already group the expression.
     */
    private static @NotNull String unwrapEnclosingGroup(@NotNull String path) {
        String trimmed = path.trim();
        Fxml2ExpressionParser.Expression expression;
        try {
            expression = Fxml2ExpressionParser.parse(trimmed);
        } catch (Fxml2ExpressionParser.ParseException ignored) {
            return path;
        }
        if (!(expression instanceof Fxml2ExpressionParser.GroupedExpression grouped)
                || grouped.span().start() != 0
                || grouped.span().end() != trimmed.length()) {
            return path;
        }
        Fxml2ExpressionParser.Span inner = grouped.expression().span();
        return trimmed.substring(inner.start(), inner.end()).trim();
    }
}
