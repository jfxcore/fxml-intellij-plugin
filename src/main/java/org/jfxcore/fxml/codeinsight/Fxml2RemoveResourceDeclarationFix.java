package org.jfxcore.fxml.codeinsight;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resolve.Fxml2TextSpan;
import org.jfxcore.fxml.resource.Fxml2ResourceEntry;

/**
 * Deletes a {@code <?resource ?>} declaration, along with the line it sits on when it has that
 * line to itself.
 *
 * <p>Taking the line with it is what makes the result look like the declaration was never there:
 * removing only the instruction would leave the indentation in front of it and the line break
 * after it behind as a blank line.
 */
public final class Fxml2RemoveResourceDeclarationFix implements LocalQuickFix {

    private final String declaredName;

    public Fxml2RemoveResourceDeclarationFix(@NotNull String declaredName) {
        this.declaredName = declaredName;
    }

    @Override
    public @NotNull String getName() {
        return "Remove embedded resource '" + declaredName + "'";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Remove embedded resource declaration";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        Fxml2ResourceEntry entry =
                Fxml2ResourceDeclarationEditor.findDeclaration(descriptor.getPsiElement(), declaredName);
        if (entry == null) return;

        Fxml2ResourceDeclarationEditor.replace(project, entry, withOwnLine(entry), "");
    }

    /**
     * Returns the span to delete: the declaration, plus its leading indentation and trailing line
     * break when nothing else shares the line with it.
     *
     * <p>The decision is made on the declaring file's text rather than on the anchor's, because in
     * a standalone document the anchor is the processing instruction itself and therefore carries
     * none of the surrounding whitespace the decision turns on.  The result is converted back to
     * anchor coordinates, which is what the declaration editor works in.
     */
    private static @NotNull Fxml2TextSpan withOwnLine(@NotNull Fxml2ResourceEntry entry) {
        String text = entry.declaringFile().getText();
        int anchorStart = entry.anchor().getTextRange().getStartOffset();
        Fxml2TextSpan instruction = entry.declaration().instruction().instruction().shifted(anchorStart);

        int start = instruction.start();
        while (start > 0 && isHorizontalWhitespace(text.charAt(start - 1))) --start;
        boolean startsTheLine = start == 0 || text.charAt(start - 1) == '\n';

        int end = Math.min(instruction.end(), text.length());
        while (end < text.length() && isHorizontalWhitespace(text.charAt(end))) ++end;
        boolean endsTheLine = end == text.length() || text.charAt(end) == '\n';

        Fxml2TextSpan deleted = startsTheLine && endsTheLine
                ? new Fxml2TextSpan(start, end < text.length() ? end + 1 : end)
                : instruction;

        return deleted.shifted(-anchorStart);
    }

    private static boolean isHorizontalWhitespace(char character) {
        return character == ' ' || character == '\t';
    }
}
