package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalInspectionToolSession;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resource.Fxml2ResourceProblem;
import org.jfxcore.fxml.resource.Fxml2ResourceProblemKind;

/** Reports resource names that collide within one FXML/2 document. */
public final class Fxml2DuplicateResourceInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,
                                                   boolean isOnTheFly,
                                                   @NotNull LocalInspectionToolSession session) {
        return Fxml2ResourceInspectionSupport.visitDeclarations(holder, entry -> {
            for (Fxml2ResourceProblem problem : entry.problems()) {
                if (problem.kind() == Fxml2ResourceProblemKind.DUPLICATE_DECLARATION) {
                    Fxml2ResourceInspectionSupport.report(holder, entry, problem);
                }
            }
        });
    }
}
