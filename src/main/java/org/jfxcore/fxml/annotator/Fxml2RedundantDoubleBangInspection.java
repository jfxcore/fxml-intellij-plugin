package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.BatchQuickFix;
import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.XmlSuppressableInspectionTool;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser.ContextSelector;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser.ParsedExpression;
import org.jfxcore.fxml.resolve.Fxml2BindingPathResolver;
import org.jfxcore.fxml.resolve.Fxml2BindingPathResolver.Segment;

import java.util.ArrayList;

/**
 * Reports {@code !!} (double-bang / BOOLIFY) operators that are applied to a
 * {@code Boolean}-typed binding path and are therefore a compile-time no-op.
 *
 * <p>When the resolved type of the last path segment is {@code java.lang.Boolean},
 * the FXML compiler skips any wrapping call and passes the observable directly to
 * the target property.  The {@code !!} prefix is therefore redundant and should be
 * removed to keep the expression clean.
 *
 * <p>Examples:
 * <pre>
 *   disable="${!!active}" : 'active' is a BooleanProperty  -> !! is a no-op  (WARN)
 *   visible="${!!count}"  : 'count' is an IntegerProperty  -> !! maps to isNotZero (OK)
 *   visible="${!!item}"   : 'item' is an Object            -> !! maps to isNotNull  (OK)
 * </pre>
 *
 * <p>Enabled by default at WEAK_WARNING level (grey underline).
 * Suppressable via {@code <!-- suppress Fxml2RedundantDoubleBang -->}.
 */
public final class Fxml2RedundantDoubleBangInspection extends XmlSuppressableInspectionTool {

    @Override
    public ProblemDescriptor @Nullable [] checkFile(
            @NotNull PsiFile file, @NotNull InspectionManager manager, boolean isOnTheFly) {
        if (!Fxml2FileType.isFxml2(file)) return null;
        final XmlFile xmlFile = (XmlFile) file;

        List<ProblemDescriptor> problems = new ArrayList<>();

        for (XmlAttributeValue attrVal : PsiTreeUtil.findChildrenOfType(xmlFile, XmlAttributeValue.class)) {
            checkAttributeValue(attrVal, xmlFile, manager, isOnTheFly, problems);
        }

        return problems.isEmpty() ? null : problems.toArray(ProblemDescriptor.EMPTY_ARRAY);
    }

    private static void checkAttributeValue(
            @NotNull XmlAttributeValue attrVal,
            @NotNull XmlFile xmlFile,
            @NotNull InspectionManager manager,
            boolean isOnTheFly,
            @NotNull List<ProblemDescriptor> problems) {

        String rawValue = attrVal.getValue();
        if (rawValue.isBlank()) return;

        ParsedExpression expr = Fxml2BindingExpressionParser.parseExpression(rawValue);
        if (expr == null) return;

        // Only fire for the BOOLIFY (double bang !!) operator.
        if (expr.operatorLength() != 2) return;

        // The parent chain must be attribute -> tag.
        if (!(attrVal.getParent() instanceof XmlAttribute)) return;
        if (!(((XmlAttribute) attrVal.getParent()).getParent() instanceof XmlTag contextTag)) return;

        // Resolve the binding path to determine the result type.
        String strippedPath = expr.strippedPath();
        ContextSelector selector = Fxml2BindingExpressionParser.parseContextSelector(strippedPath);
        String pathForResolution = selector != null ? selector.remainingPath() : strippedPath;

        PsiClass startClass = Fxml2BindingPathResolver.resolveStartClass(selector, contextTag, xmlFile);
        if (startClass == null) return;

        GlobalSearchScope scope = xmlFile.getResolveScope();
        List<Segment> segments = pathForResolution.isEmpty()
                ? List.of()
                : Fxml2BindingPathResolver.resolve(pathForResolution, startClass, scope,
                        expr.kind(), xmlFile);

        if (segments.isEmpty()) return;
        PsiClass resultType = segments.getLast().resultType();
        if (resultType == null) return;

        // Only warn when the result type is Boolean, since !! is a no-op in that case.
        // For Number types, !! maps to BooleanBindings.isNotZero; for other types to
        // isNotNull are both real transformations, not no-ops.
        if (!"java.lang.Boolean".equals(resultType.getQualifiedName())) return;

        // Highlight exactly the '!!' token within the attribute value element text.
        // +1 because XmlAttributeValue.getText() starts with the opening quote character.
        int opStartInElem = 1 + expr.pathOffset();
        int opEndInElem   = opStartInElem + 2;
        TextRange range = TextRange.create(opStartInElem, opEndInElem);

        problems.add(manager.createProblemDescriptor(
                attrVal,
                range,
                "'!!' is redundant on a Boolean binding",
                ProblemHighlightType.WEAK_WARNING,
                isOnTheFly,
                new RemoveDoubleBangFix()));
    }

    // -----------------------------------------------------------------------
    // Quick-fix
    // -----------------------------------------------------------------------

    /**
     * Removes the {@code !!} prefix from the binding path, e.g.
     * {@code ${!!active}} -> {@code ${active}}.
     */
    static final class RemoveDoubleBangFix implements LocalQuickFix, BatchQuickFix {

        @Override
        public @NotNull String getName() { return "Remove redundant '!!'"; }

        @Override
        public @NotNull String getFamilyName() { return "Remove redundant '!!'"; }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement elem = descriptor.getPsiElement();
            if (!(elem instanceof XmlAttributeValue attrVal)) return;
            if (!(attrVal.getParent() instanceof XmlAttribute attr)) return;

            String currentValue = attrVal.getValue();
            ParsedExpression expr = Fxml2BindingExpressionParser.parseExpression(currentValue);
            if (expr == null || expr.operatorLength() != 2) return;

            int pathOff = expr.pathOffset();
            if (pathOff + 2 > currentValue.length()) return;
            if (!currentValue.startsWith("!!", pathOff)) return;

            // Remove the '!!' at pathOff; everything else stays in place.
            String newValue = currentValue.substring(0, pathOff) + currentValue.substring(pathOff + 2);
            attr.setValue(newValue);
        }

        @Override
        public void applyFix(
                @NotNull Project project,
                CommonProblemDescriptor @NotNull [] descriptors,
                @NotNull List<PsiElement> psiElementsToIgnore,
                @Nullable Runnable refreshViews) {
            for (CommonProblemDescriptor descriptor : descriptors) {
                if (descriptor instanceof ProblemDescriptor pd) {
                    applyFix(project, pd);
                    PsiElement elem = pd.getPsiElement();
                    if (elem != null) psiElementsToIgnore.add(elem);
                }
            }
            if (refreshViews != null) refreshViews.run();
        }
    }
}
