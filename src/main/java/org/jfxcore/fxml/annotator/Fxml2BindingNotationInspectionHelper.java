package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.BatchQuickFix;
import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.actions.ConvertBindingNotationIntention;

import java.util.ArrayList;
import java.util.List;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser.ParsedExpression;

/**
 * Shared logic and quick-fixes for
 * {@link Fxml2BindingNotationInspectionToLongForm} and
 * {@link Fxml2BindingNotationInspectionToShortForm}.
 */
final class Fxml2BindingNotationInspectionHelper {

    private Fxml2BindingNotationInspectionHelper() {}

    static ProblemDescriptor @Nullable [] checkAll(
            @NotNull PsiFile file,
            @NotNull InspectionManager manager,
            boolean isOnTheFly,
            boolean wantCompact) {

        if (!Fxml2FileType.isFxml2(file)) return null;

        List<ProblemDescriptor> problems = new ArrayList<>();

        for (XmlAttributeValue attrVal : PsiTreeUtil.findChildrenOfType(file, XmlAttributeValue.class)) {
            String value = attrVal.getValue();

            ParsedExpression expr = Fxml2BindingExpressionParser.parseExpression(value);
            if (expr == null) continue;

            boolean isCompact = ConvertBindingNotationIntention.isCompactNotation(expr);
            if (wantCompact != isCompact) continue; // already in target form

            LocalQuickFix fix = wantCompact ? new ToLongFormFix() : new ToShortFormFix();

            problems.add(manager.createProblemDescriptor(
                    attrVal,
                    wantCompact
                            ? "Binding expression uses compact notation"
                            : "Binding expression uses long-form notation",
                    fix,
                    ProblemHighlightType.WEAK_WARNING,
                    isOnTheFly));
        }

        return problems.isEmpty() ? null : problems.toArray(ProblemDescriptor.EMPTY_ARRAY);
    }

    // -----------------------------------------------------------------------
    // Quick-fixes
    // -----------------------------------------------------------------------

    static final class ToLongFormFix implements LocalQuickFix, BatchQuickFix {

        @Override
        public @NotNull String getName() { return "Convert to long-form binding notation"; }

        @Override
        public @NotNull String getFamilyName() { return getName(); }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement elem = descriptor.getPsiElement();
            if (!(elem instanceof XmlAttributeValue attrVal)) return;
            if (!(attrVal.getParent() instanceof XmlAttribute attr)) return;

            ParsedExpression expr = Fxml2BindingExpressionParser.parseExpression(attrVal.getValue());
            if (expr == null || !ConvertBindingNotationIntention.isCompactNotation(expr)) return;

            attr.setValue(ConvertBindingNotationIntention.toLongForm(expr.kind(), expr.path()));
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

    static final class ToShortFormFix implements LocalQuickFix, BatchQuickFix {

        @Override
        public @NotNull String getName() { return "Convert to short-form binding notation"; }

        @Override
        public @NotNull String getFamilyName() { return getName(); }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement elem = descriptor.getPsiElement();
            if (!(elem instanceof XmlAttributeValue attrVal)) return;
            if (!(attrVal.getParent() instanceof XmlAttribute attr)) return;

            ParsedExpression expr = Fxml2BindingExpressionParser.parseExpression(attrVal.getValue());
            if (expr == null || ConvertBindingNotationIntention.isCompactNotation(expr)) return;

            attr.setValue(ConvertBindingNotationIntention.toShortForm(expr.kind(), expr.path()));
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
