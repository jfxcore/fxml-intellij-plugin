package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.InspectionManager;
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
import org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2ExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;

import java.util.ArrayList;
import java.util.List;

/** Reports one-time evaluations whose context can depend on element initialization order. */
public final class Fxml2EvaluateInitializationOrderInspection
        extends XmlSuppressableInspectionTool {

    @Override
    public ProblemDescriptor @Nullable [] checkFile(
            @NotNull PsiFile file,
            @NotNull InspectionManager manager,
            boolean isOnTheFly) {
        if (!Fxml2FileType.isFxml2(file)) return null;
        XmlFile xmlFile = (XmlFile) file;
        List<ProblemDescriptor> problems = new ArrayList<>();
        for (XmlAttributeValue value : PsiTreeUtil.findChildrenOfType(
                xmlFile, XmlAttributeValue.class)) {
            checkValue(value, manager, isOnTheFly, problems);
        }
        return problems.isEmpty() ? null : problems.toArray(ProblemDescriptor.EMPTY_ARRAY);
    }

    private static void checkValue(
            @NotNull XmlAttributeValue value,
            @NotNull InspectionManager manager,
            boolean isOnTheFly,
            @NotNull List<ProblemDescriptor> problems) {
        String expressionText;
        int expressionOffset;
        Object parsed = Fxml2BindingExpressionParser.parse(value.getValue());
        if (parsed instanceof Fxml2BindingExpressionParser.ParsedExpression expression
                && expression.kind() == Kind.EVALUATE) {
            expressionText = expression.path();
            expressionOffset = expression.pathOffset();
        } else if (isEvaluateSourceAttribute(value)) {
            expressionText = value.getValue();
            expressionOffset = 0;
        } else {
            return;
        }

        Fxml2ExpressionParser.Expression expression;
        try {
            expression = Fxml2ExpressionParser.parse(expressionText);
        } catch (Fxml2ExpressionParser.ParseException ignored) {
            return;
        }

        List<Fxml2ExpressionParser.ContextSelectorExpression> selectors = new ArrayList<>();
        collectRiskySelectors(expression, selectors);
        for (Fxml2ExpressionParser.ContextSelectorExpression selector : selectors) {
            String selectorName = selector.kind() == Fxml2ExpressionParser.ContextSelectorKind.ELEMENT
                    ? ":element" : ":parent";
            int start = 1 + expressionOffset + selector.span().start();
            TextRange range = TextRange.create(start, start + selectorName.length());
            problems.add(manager.createProblemDescriptor(
                    value,
                    range,
                    "Evaluate with '" + selectorName
                            + "' may depend on element initialization order",
                    ProblemHighlightType.WEAK_WARNING,
                    isOnTheFly));
        }
    }

    private static boolean isEvaluateSourceAttribute(@NotNull XmlAttributeValue value) {
        PsiElement parent = value.getParent();
        if (!(parent instanceof XmlAttribute attribute)
                || !("source".equals(attribute.getLocalName())
                     || "source".equals(attribute.getName()))) return false;
        if (!(attribute.getParent() instanceof XmlTag tag)) return false;
        return "fx:Evaluate".equals(tag.getName())
                || ("Evaluate".equals(tag.getLocalName())
                    && Fxml2ImportResolver.isFxml2Namespace(tag.getNamespace()));
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
