package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.resource.Fxml2ResourceEntry;
import org.jfxcore.fxml.resource.Fxml2ResourceModel;
import org.jfxcore.fxml.resource.Fxml2ResourceProblem;
import org.jfxcore.fxml.resource.Fxml2ResourceProblemKind;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Shared plumbing for the inspections that report on {@code <?resource ?>} declarations.
 *
 * <p>All of them do the same three things: decide whether the file is an FXML/2 document at all,
 * visit every declaration of that document exactly once, and turn a diagnostic's span into a range
 * the platform can highlight.  The declarations of a document are read from
 * {@link Fxml2ResourceModel}, which caches them per file.
 *
 * <p>Visiting is anchored on the file rather than on each processing instruction, because the
 * embedded document form has no processing-instruction PSI to visit: its declarations live in the
 * raw text of a Java or Kotlin literal, which the injected XML view does not contain.
 */
final class Fxml2ResourceInspectionSupport {

    private Fxml2ResourceInspectionSupport() {}

    /**
     * Returns a visitor that calls {@code report} once for every declaration of the FXML/2
     * document being inspected, or an empty visitor when the file is not one.
     */
    static @NotNull PsiElementVisitor visitDeclarations(@NotNull ProblemsHolder holder,
                                                        @NotNull Consumer<Fxml2ResourceEntry> report) {
        XmlFile file = fxml2FileOf(holder.getFile());
        if (file == null) return PsiElementVisitor.EMPTY_VISITOR;

        return new PsiElementVisitor() {
            private boolean visited;

            @Override
            public void visitFile(@NotNull PsiFile visitedFile) {
                if (visited) return;
                visited = true;

                Fxml2ResourceModel.of(file).entries().forEach(report);
            }
        };
    }

    /** Returns {@code file} as an FXML/2 document, or {@code null} when it is not one. */
    static @Nullable XmlFile fxml2FileOf(@NotNull PsiFile file) {
        return file instanceof XmlFile xmlFile && Fxml2FileType.isFxml2(xmlFile) ? xmlFile : null;
    }

    /** Reports {@code problem} as an error on the element that carries {@code entry}'s declaration. */
    static void report(@NotNull ProblemsHolder holder,
                       @NotNull Fxml2ResourceEntry entry,
                       @NotNull Fxml2ResourceProblem problem,
                       @NotNull LocalQuickFix @NotNull ... fixes) {
        report(holder, entry, problem.span().toTextRange(), problem.message(),
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING, fixes);
    }

    /**
     * Reports {@code message} on {@code range}, which is relative to {@code entry}'s anchor.
     *
     * <p>Diagnostic spans are already relative to the anchor, and the platform wants a range
     * relative to the element the problem is registered on, so the two agree and the span needs no
     * translation.  An empty or out-of-bounds span is widened to the whole anchor, because the
     * platform cannot highlight nothing.
     */
    static void report(@NotNull ProblemsHolder holder,
                       @NotNull Fxml2ResourceEntry entry,
                       @NotNull TextRange range,
                       @NotNull String message,
                       @NotNull ProblemHighlightType highlightType,
                       @NotNull LocalQuickFix @NotNull ... fixes) {
        PsiElement anchor = entry.anchor();
        TextRange anchorRange = TextRange.from(0, anchor.getTextLength());
        TextRange intersection = range.isEmpty() ? null : range.intersection(anchorRange);

        holder.registerProblem(anchor, message, highlightType,
                intersection == null || intersection.isEmpty() ? anchorRange : intersection,
                fixes);
    }

    /** Returns the diagnostics of {@code entry} that are of one of {@code kinds}. */
    static @NotNull List<Fxml2ResourceProblem> problemsOf(@NotNull Fxml2ResourceEntry entry,
                                                          @NotNull Set<Fxml2ResourceProblemKind> kinds) {
        return entry.problems().stream().filter(problem -> kinds.contains(problem.kind())).toList();
    }
}
