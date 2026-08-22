package org.jfxcore.fxml.codeinsight;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resolve.Fxml2TextSpan;
import org.jfxcore.fxml.resource.Fxml2ResourceEntry;

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
    private final Fxml2TextSpan parameterSpan;

    public Fxml2RemoveDuplicateMediaTypeParameterFix(@NotNull String declaredName,
                                                     @NotNull String parameterName,
                                                     @NotNull Fxml2TextSpan parameterSpan) {
        this.declaredName = declaredName;
        this.parameterName = parameterName;
        this.parameterSpan = parameterSpan;
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

    private static boolean isHorizontalWhitespace(char character) {
        return character == ' ' || character == '\t';
    }
}
