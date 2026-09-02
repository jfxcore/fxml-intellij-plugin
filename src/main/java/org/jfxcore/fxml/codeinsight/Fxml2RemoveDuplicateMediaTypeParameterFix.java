package org.jfxcore.fxml.codeinsight;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2TextSpan;
import org.jfxcore.fxml.resource.Fxml2ResourceEntry;
import org.jfxcore.fxml.resource.Fxml2ResourceProblem;
import org.jfxcore.fxml.resource.Fxml2ResourceProblemKind;

/**
 * Removes the repeated occurrence of a media-type parameter that is declared more than once.
 *
 * <p>Only the later occurrence is removed, because the earlier one is the one every other reading
 * of the declaration already uses.  The removal takes the {@code ;} separator in front of the
 * parameter with it, along with the whitespace around it, so that the remaining media type is
 * still well formed.
 */
public final class Fxml2RemoveDuplicateMediaTypeParameterFix implements LocalQuickFix {

    private final String declaredName;
    private final String parameterName;

    public Fxml2RemoveDuplicateMediaTypeParameterFix(@NotNull String declaredName,
                                                     @NotNull String parameterName) {
        this.declaredName = declaredName;
        this.parameterName = parameterName;
    }

    @Override
    public @NotNull String getName() {
        return "Remove duplicate media type parameter '" + parameterName + "'";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Remove duplicate media type parameter";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        Fxml2ResourceEntry entry =
                Fxml2ResourceDeclarationEditor.findDeclaration(descriptor.getPsiElement(), declaredName);
        if (entry == null) return;

        Fxml2TextSpan parameterSpan = duplicateParameterSpan(entry);
        if (parameterSpan == null) return;

        String anchorText = entry.anchor().getText();
        int start = parameterSpan.start();
        int end = Math.min(parameterSpan.end(), anchorText.length());
        if (start < 0 || start >= end) return;

        // Take the separator that introduces the parameter, and the whitespace around it.
        while (start > 0 && isHorizontalWhitespace(anchorText.charAt(start - 1))) --start;
        if (start > 0 && anchorText.charAt(start - 1) == ';') --start;
        while (start > 0 && isHorizontalWhitespace(anchorText.charAt(start - 1))) --start;

        Fxml2ResourceDeclarationEditor.replace(project, entry, new Fxml2TextSpan(start, end), "");
    }

    /**
     * Returns the span of the repeated parameter, found again at the moment the fix is applied.
     *
     * <p>Recomputing rather than remembering the span keeps the fix free of state that could go
     * stale between the inspection run and the fix, which is also what lets the platform render a
     * preview of it.
     */
    private @Nullable Fxml2TextSpan duplicateParameterSpan(@NotNull Fxml2ResourceEntry entry) {
        return entry.problems().stream()
                .filter(problem -> problem.kind() == Fxml2ResourceProblemKind.DUPLICATE_MEDIA_TYPE_PARAMETER)
                .filter(problem -> parameterName.equals(String.valueOf(problem.arguments().getFirst())))
                .map(Fxml2ResourceProblem::span)
                .findFirst()
                .orElse(null);
    }

    private static boolean isHorizontalWhitespace(char character) {
        return character == ' ' || character == '\t';
    }
}
