package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalInspectionToolSession;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resource.Fxml2ResourceProblem;
import org.jfxcore.fxml.resource.Fxml2ResourceProblemKind;

import java.util.EnumSet;
import java.util.Set;

/**
 * Reports two {@code <?resource ?>} declarations in one document that declare the same resource.
 *
 * <p>Collision is case-insensitive, because the resource file the compiler writes is named
 * case-insensitively: {@code value.txt} and {@code Value.txt} would end up as the same file.  The
 * message names the position of the earlier declaration, which is the one that stays in effect.
 *
 * <p>Declarations are scoped to the entire document, so a declaration inside the root element
 * collides with one in the prolog just as two in the prolog would.
 */
public final class Fxml2DuplicateResourceInspection extends LocalInspectionTool {

    private static final Set<Fxml2ResourceProblemKind> REPORTED_KINDS =
            EnumSet.of(Fxml2ResourceProblemKind.DUPLICATE_DECLARATION);

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,
                                                   boolean isOnTheFly,
                                                   @NotNull LocalInspectionToolSession session) {
        return Fxml2ResourceInspectionSupport.visitDeclarations(holder, entry -> {
            for (Fxml2ResourceProblem problem : Fxml2ResourceInspectionSupport.problemsOf(entry, REPORTED_KINDS)) {
                Fxml2ResourceInspectionSupport.report(holder, entry, problem);
            }
        });
    }
}
