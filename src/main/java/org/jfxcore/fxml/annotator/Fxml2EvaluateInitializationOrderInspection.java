package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.XmlSuppressableInspectionTool;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.actions.ConvertEvaluateToObserveFix;
import org.jfxcore.fxml.actions.ConvertEvaluateToObserveFix.Site;
import org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.resolve.Fxml2AttributeValueItems;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser.MarkupExtensionExpression;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser.ParsedExpression;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser.PrefixShorthandExpression;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser.SecondaryParam;
import org.jfxcore.fxml.resolve.Fxml2ExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;
import org.jfxcore.fxml.resolve.Fxml2MarkupExtensionContentParser;
import org.jfxcore.fxml.resolve.Fxml2MarkupExtensionContentParser.NamedParameter;
import org.jfxcore.fxml.resolve.Fxml2MarkupExtensionContentParser.PositionalValue;
import org.jfxcore.fxml.resolve.Fxml2MarkupExtensionContentParser.Section;
import org.jfxcore.fxml.resolve.Fxml2ValueSequenceParser;
import org.jfxcore.fxml.resolve.Fxml2ValueSequenceParser.ValueItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports one-time evaluations whose context can depend on element initialization order.
 *
 * <p>A one-time evaluation is reported wherever it can appear:
 * <ul>
 *   <li>as the value of a property attribute, in compact ({@code $:parent.width}) as well as in
 *       markup-extension notation ({@code {fx:Evaluate :parent.width}}),</li>
 *   <li>as one item of a value list, which resolves its items one by one,</li>
 *   <li>in value-supplier position inside another markup extension, including the prefix notation
 *       of a markup extension and the secondary parameters of a binding, and</li>
 *   <li>as the {@code source} attribute of an {@code fx:Evaluate} element.</li>
 * </ul>
 *
 * <p>A one-time evaluation is not reported where a comma is part of a literal value: the items of
 * an attribute value are determined by the target it assigns to, so a value that is a single
 * literal is never split into items.
 */
public final class Fxml2EvaluateInitializationOrderInspection
        extends XmlSuppressableInspectionTool {

    /** Guards against pathological nesting of markup extensions. */
    private static final int MAX_NESTING_DEPTH = 16;

    @Override
    public ProblemDescriptor @Nullable [] checkFile(
            @NotNull PsiFile file,
            @NotNull InspectionManager manager,
            boolean isOnTheFly) {
        if (!Fxml2FileType.isFxml2(file)) return null;
        XmlFile xmlFile = (XmlFile) file;
        Map<Character, String> prefixMappings = Fxml2ImportResolver.parsePrefixMappings(xmlFile);
        List<ProblemDescriptor> problems = new ArrayList<>();
        for (XmlAttributeValue value : PsiTreeUtil.findChildrenOfType(
                xmlFile, XmlAttributeValue.class)) {
            checkValue(value, xmlFile, prefixMappings, manager, isOnTheFly, problems);
        }
        return problems.isEmpty() ? null : problems.toArray(ProblemDescriptor.EMPTY_ARRAY);
    }

    // -----------------------------------------------------------------------
    // Attribute values

    private static void checkValue(
            @NotNull XmlAttributeValue value,
            @NotNull XmlFile xmlFile,
            @NotNull Map<Character, String> prefixMappings,
            @NotNull InspectionManager manager,
            boolean isOnTheFly,
            @NotNull List<ProblemDescriptor> problems) {

        // The source of an fx:Evaluate element is the expression itself rather than a value that
        // could hold items or nested markup extensions.
        if (isEvaluateSourceAttribute(value)) {
            reportSelectors(value, value.getValue(), 1, new Site.EvaluateElement(),
                    manager, isOnTheFly, problems);
            return;
        }

        for (ValueItem item : Fxml2AttributeValueItems.resolveItems(value, xmlFile)) {
            scanFragment(value, item.text(), item.offset(), prefixMappings, 0,
                    manager, isOnTheFly, problems);
        }
    }

    /**
     * Scans one item of a value for a one-time evaluation, descending into the markup extension
     * the item invokes.
     *
     * @param offset offset of {@code text} within the value of {@code value}
     */
    private static void scanFragment(
            @NotNull XmlAttributeValue value,
            @NotNull String text,
            int offset,
            @NotNull Map<Character, String> prefixMappings,
            int depth,
            @NotNull InspectionManager manager,
            boolean isOnTheFly,
            @NotNull List<ProblemDescriptor> problems) {

        if (depth > MAX_NESTING_DEPTH || text.isBlank()) return;
        Object parsed = Fxml2BindingExpressionParser.parse(text, prefixMappings);

        switch (parsed) {
            case ParsedExpression expression when isEvaluate(expression.kind()) ->
                    reportSelectors(value, expression.path(),
                            1 + offset + expression.pathOffset(),
                            new Site.AttributeValueItem(
                                    TextRange.from(offset, text.length())),
                            manager, isOnTheFly, problems);
            case ParsedExpression expression -> {
                for (SecondaryParam param : expression.params()) {
                    if (param.pathOffset() < 0) continue;
                    scanItems(value, param.path(), offset + param.pathOffset(),
                            prefixMappings, depth + 1, manager, isOnTheFly, problems);
                }
            }
            case MarkupExtensionExpression extension ->
                    scanExtensionContent(value, text, offset, contentStart(text, extension),
                            prefixMappings, depth, manager, isOnTheFly, problems);
            case PrefixShorthandExpression shorthand -> {
                if (shorthand.paramsPart() != null && shorthand.paramsOffset() >= 0) {
                    scanExtensionContent(value, text, offset, shorthand.paramsOffset(),
                            prefixMappings, depth, manager, isOnTheFly, problems);
                }
            }
            case null, default -> { }
        }
    }

    /**
     * Scans the sections of the content of a markup extension, which begins at
     * {@code contentStart} within {@code text}.
     */
    private static void scanExtensionContent(
            @NotNull XmlAttributeValue value,
            @NotNull String text,
            int offset,
            int contentStart,
            @NotNull Map<Character, String> prefixMappings,
            int depth,
            @NotNull InspectionManager manager,
            boolean isOnTheFly,
            @NotNull List<ProblemDescriptor> problems) {

        if (contentStart < 0 || contentStart > text.length()) return;
        int contentEnd = text.endsWith("}") ? text.length() - 1 : text.length();
        if (contentEnd <= contentStart) return;
        String content = text.substring(contentStart, contentEnd);

        for (Section section : Fxml2MarkupExtensionContentParser.parse(content)) {
            String sectionText;
            int sectionOffset;
            switch (section) {
                case NamedParameter parameter -> {
                    sectionText = parameter.value();
                    sectionOffset = parameter.valueOffset();
                }
                case PositionalValue positional -> {
                    sectionText = positional.text();
                    sectionOffset = positional.offset();
                }
            }
            scanItems(value, sectionText, offset + contentStart + sectionOffset,
                    prefixMappings, depth + 1, manager, isOnTheFly, problems);
        }
    }

    /** Splits a fragment into the items it supplies and scans each of them. */
    private static void scanItems(
            @NotNull XmlAttributeValue value,
            @NotNull String text,
            int offset,
            @NotNull Map<Character, String> prefixMappings,
            int depth,
            @NotNull InspectionManager manager,
            boolean isOnTheFly,
            @NotNull List<ProblemDescriptor> problems) {

        for (ValueItem item : Fxml2ValueSequenceParser.split(text, prefixMappings)) {
            scanFragment(value, item.text(), offset + item.offset(), prefixMappings, depth,
                    manager, isOnTheFly, problems);
        }
    }

    /**
     * Returns the offset within {@code text} at which the content of the markup extension begins,
     * which follows the extension name and its type argument, if any.
     */
    private static int contentStart(@NotNull String text,
                                    @NotNull MarkupExtensionExpression extension) {
        int index = extension.nameOffset() + extension.extensionName().length();
        if (!extension.hasTypeArg()) return index;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        if (index >= text.length() || text.charAt(index) != '<') return index;
        int depth = 0;
        while (index < text.length()) {
            char c = text.charAt(index++);
            if (c == '<') depth++;
            else if (c == '>' && --depth == 0) break;
        }
        return index;
    }

    // -----------------------------------------------------------------------
    // fx:Evaluate elements

    /** Whether the value is the {@code source} attribute of an {@code fx:Evaluate} element. */
    private static boolean isEvaluateSourceAttribute(@NotNull XmlAttributeValue value) {
        PsiElement parent = value.getParent();
        if (!(parent instanceof XmlAttribute attribute)
                || !("source".equals(attribute.getLocalName())
                     || "source".equals(attribute.getName()))) return false;
        return attribute.getParent() instanceof XmlTag tag && isEvaluateTag(tag);
    }

    /** Whether the tag is the {@code fx:Evaluate} intrinsic. */
    private static boolean isEvaluateTag(@NotNull XmlTag tag) {
        return "fx:Evaluate".equals(tag.getName())
                || ("Evaluate".equals(tag.getLocalName())
                    && Fxml2ImportResolver.isFxml2Namespace(tag.getNamespace()));
    }

    // -----------------------------------------------------------------------
    // Reporting

    private static boolean isEvaluate(@NotNull Kind kind) {
        return kind == Kind.EVALUATE || kind == Kind.EVALUATE_CONTENT;
    }

    /**
     * Reports every context selector of {@code expressionText} whose value depends on element
     * initialization order.
     *
     * @param base offset of {@code expressionText} within {@code problemElement}
     */
    private static void reportSelectors(
            @NotNull PsiElement problemElement,
            @NotNull String expressionText,
            int base,
            @NotNull Site site,
            @NotNull InspectionManager manager,
            boolean isOnTheFly,
            @NotNull List<ProblemDescriptor> problems) {

        Fxml2ExpressionParser.Expression expression;
        try {
            expression = Fxml2ExpressionParser.parse(expressionText);
        } catch (Fxml2ExpressionParser.ParseException ignored) {
            return;
        }

        List<Fxml2ExpressionParser.ContextSelectorExpression> selectors = new ArrayList<>();
        collectRiskySelectors(expression, selectors);
        if (selectors.isEmpty()) return;

        LocalQuickFix fix = new ConvertEvaluateToObserveFix(site);
        for (Fxml2ExpressionParser.ContextSelectorExpression selector : selectors) {
            String selectorName = selector.kind() == Fxml2ExpressionParser.ContextSelectorKind.ELEMENT
                    ? ":element" : ":parent";
            int start = base + selector.span().start();
            TextRange range = TextRange.create(start, start + selectorName.length());
            problems.add(manager.createProblemDescriptor(
                    problemElement,
                    range,
                    "Evaluate with '" + selectorName
                            + "' may depend on element initialization order",
                    ProblemHighlightType.WEAK_WARNING,
                    isOnTheFly,
                    fix));
        }
    }

    private static void collectRiskySelectors(
            @NotNull Fxml2ExpressionParser.Expression expression,
            @NotNull List<Fxml2ExpressionParser.ContextSelectorExpression> result) {
        switch (expression) {
            case Fxml2ExpressionParser.ContextSelectorExpression selector -> {
                if (selector.kind() == Fxml2ExpressionParser.ContextSelectorKind.ELEMENT
                        || selector.kind() == Fxml2ExpressionParser.ContextSelectorKind.PARENT) {
                    result.add(selector);
                }
            }
            case Fxml2ExpressionParser.MemberExpression member ->
                    collectRiskySelectors(member.receiver(), result);
            case Fxml2ExpressionParser.AttachedPropertyExpression attached -> {
                if (attached.receiver() != null) collectRiskySelectors(attached.receiver(), result);
            }
            case Fxml2ExpressionParser.InvocationExpression invocation -> {
                collectRiskySelectors(invocation.target(), result);
                invocation.arguments().forEach(argument -> collectRiskySelectors(argument, result));
            }
            case Fxml2ExpressionParser.UnaryExpression unary ->
                    collectRiskySelectors(unary.operand(), result);
            case Fxml2ExpressionParser.BinaryExpression binary -> {
                collectRiskySelectors(binary.left(), result);
                collectRiskySelectors(binary.right(), result);
            }
            case Fxml2ExpressionParser.GroupedExpression grouped ->
                    collectRiskySelectors(grouped.expression(), result);
            default -> { }
        }
    }
}
