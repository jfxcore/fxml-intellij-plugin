package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalInspectionToolSession;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.codeinsight.Fxml2MakeResourceNamePortableFix;
import org.jfxcore.fxml.codeinsight.Fxml2RemoveDuplicateMediaTypeParameterFix;
import org.jfxcore.fxml.resource.Fxml2ResourceEntry;
import org.jfxcore.fxml.resource.Fxml2ResourceProblem;
import org.jfxcore.fxml.resource.Fxml2ResourceProblemKind;

import java.util.EnumSet;
import java.util.Set;

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
 *   <li>a media type that does not follow the {@code type/subtype} grammar;</li>
 *   <li>a media type that declares the same parameter twice;</li>
 *   <li>a {@code charset} parameter naming a charset that is unknown or illegal;</li>
 *   <li>a payload character the selected charset cannot encode.</li>
 * </ul>
 *
 * <p>Collisions between two declarations are reported by
 * {@link Fxml2DuplicateResourceInspection} instead, so that they can be enabled and suppressed
 * independently of a declaration's own well-formedness.
 */
public final class Fxml2ResourceDeclarationInspection extends LocalInspectionTool {

    /** The diagnostics this inspection is responsible for. */
    private static final Set<Fxml2ResourceProblemKind> REPORTED_KINDS = EnumSet.of(
            Fxml2ResourceProblemKind.INVALID_DECLARATION,
            Fxml2ResourceProblemKind.MISSING_NAME,
            Fxml2ResourceProblemKind.INVALID_NAME,
            Fxml2ResourceProblemKind.INVALID_MEDIA_TYPE,
            Fxml2ResourceProblemKind.DUPLICATE_MEDIA_TYPE_PARAMETER,
            Fxml2ResourceProblemKind.UNSUPPORTED_CHARSET,
            Fxml2ResourceProblemKind.UNREPRESENTABLE_CHARACTER);

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,
                                                   boolean isOnTheFly,
                                                   @NotNull LocalInspectionToolSession session) {
        return Fxml2ResourceInspectionSupport.visitDeclarations(holder, entry -> {
            for (Fxml2ResourceProblem problem : Fxml2ResourceInspectionSupport.problemsOf(entry, REPORTED_KINDS)) {
                Fxml2ResourceInspectionSupport.report(holder, entry, problem, fixesFor(entry, problem));
            }
        });
    }

    /**
     * Returns the fixes offered for {@code problem}.
     *
     * <p>A fix is offered only where the repair is mechanical.  An unportable name has one nearest
     * portable spelling, and a repeated media-type parameter has one occurrence that is redundant;
     * a malformed grammar or an unsupported charset needs a decision the user has to make, so those
     * are reported without a fix.
     */
    private static @NotNull LocalQuickFix @NotNull [] fixesFor(@NotNull Fxml2ResourceEntry entry,
                                                               @NotNull Fxml2ResourceProblem problem) {
        return switch (problem.kind()) {
            case INVALID_NAME -> Fxml2MakeResourceNamePortableFix.isApplicable(entry)
                    ? new LocalQuickFix[] {new Fxml2MakeResourceNamePortableFix(entry.name().value())}
                    : LocalQuickFix.EMPTY_ARRAY;

            case DUPLICATE_MEDIA_TYPE_PARAMETER -> new LocalQuickFix[] {
                    new Fxml2RemoveDuplicateMediaTypeParameterFix(
                            entry.name().value(), String.valueOf(problem.arguments().getFirst()))};

            default -> LocalQuickFix.EMPTY_ARRAY;
        };
    }
}
