package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/** Applies individual fixes as a batch and records affected PSI elements. */
final class Fxml2BatchQuickFixSupport {
    private Fxml2BatchQuickFixSupport() {}

    static void apply(@NotNull LocalQuickFix fix, @NotNull Project project,
                      CommonProblemDescriptor @NotNull [] descriptors,
                      @NotNull List<PsiElement> ignoredElements, @Nullable Runnable refresh) {
        Arrays.stream(descriptors).filter(ProblemDescriptor.class::isInstance)
                .map(ProblemDescriptor.class::cast).forEach(descriptor -> {
                    fix.applyFix(project, descriptor);
                    PsiElement element = descriptor.getPsiElement();
                    if (element != null) ignoredElements.add(element);
                });
        if (refresh != null) refresh.run();
    }
}
