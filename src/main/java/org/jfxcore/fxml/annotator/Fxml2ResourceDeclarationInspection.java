package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalInspectionToolSession;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.codeinsight.Fxml2ResourceDeclarationFix;
import org.jfxcore.fxml.resource.Fxml2ResourceEntry;
import org.jfxcore.fxml.resource.Fxml2ResourceProblem;

/**
 * Validates {@code <?resource ?>} declarations against the markup language's grammar.
 *
 * <p>Every diagnostic the compiler reports for a declaration is reported here, on the same span,
 * so that a document the editor accepts is a document the compiler accepts.  Reported problems:
 *
 * <ul>
 *   <li>a declaration that does not follow the {@code <?resource name [media-type]:content?>}
 *       grammar, including a missing content separator and an unterminated quote;</li>
 *   <li>a missing resource name;</li>
 *   <li>a name that is not a portable file name;</li>
 *   <li>two declarations in one document that declare the same resource;</li>
 *   <li>a media type that does not follow the {@code type/subtype} grammar;</li>
 *   <li>a media type that declares the same parameter twice;</li>
 *   <li>a {@code charset} parameter naming a charset that is unknown or illegal;</li>
 *   <li>a payload character the selected charset cannot encode.</li>
 * </ul>
 *
 * <p>All of these are error-level validation of one directive against one grammar, which is why
 * they are one inspection: the advisory diagnostics about a declaration, its media type and
 * whether it is used, are configured separately because they have different severities.
 */
public final class Fxml2ResourceDeclarationInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,
                                                   boolean isOnTheFly,
                                                   @NotNull LocalInspectionToolSession session) {
        return Fxml2ResourceInspectionSupport.visitDeclarations(holder, entry -> {
            for (Fxml2ResourceProblem problem : entry.problems()) {
                Fxml2ResourceInspectionSupport.report(holder, entry, problem, fixesFor(entry, problem));
            }
        });
    }

    /**
     * Returns the fixes offered for {@code problem}.
     *
     * <p>A fix is offered only where the repair is mechanical.  An unportable name has one nearest
     * portable spelling, and a repeated media-type parameter has one occurrence that is redundant;
     * a malformed grammar, a name collision, or an unsupported charset needs a decision the user
     * has to make, so those are reported without a fix.
     */
    private static @NotNull LocalQuickFix @NotNull [] fixesFor(@NotNull Fxml2ResourceEntry entry,
                                                               @NotNull Fxml2ResourceProblem problem) {
        return switch (problem.kind()) {
            case INVALID_NAME -> !entry.name().toPortable().equals(entry.name())
                    ? new LocalQuickFix[] {Fxml2ResourceDeclarationFix.makePortable(entry.name())}
                    : LocalQuickFix.EMPTY_ARRAY;

            case DUPLICATE_MEDIA_TYPE_PARAMETER -> problem.duplicateParameter() == null
                    ? LocalQuickFix.EMPTY_ARRAY
                    : new LocalQuickFix[] {Fxml2ResourceDeclarationFix.removeParameter(
                            entry.name(), problem.duplicateParameter().name())};

            default -> LocalQuickFix.EMPTY_ARRAY;
        };
    }
}
