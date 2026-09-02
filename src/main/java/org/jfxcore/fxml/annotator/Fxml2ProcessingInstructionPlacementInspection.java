package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalInspectionToolSession;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlProcessingInstruction;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.lang.Fxml2ProcessingInstructionTarget;

/**
 * Reports an {@code <?import?>} or {@code <?prefix?>} processing instruction written inside an
 * element.
 *
 * <p>XML permits a processing instruction anywhere in element content, and the markup language
 * makes use of that: a {@code <?resource ?>} declaration is scoped to the whole document and may
 * appear before, inside, or after the root element.  Import and prefix declarations are not: they
 * are read only from the document's direct children.  An import written inside a tag therefore
 * parses cleanly and is then silently ignored, which is exactly the kind of mistake that is hard
 * to see and easy to make once declarations inside elements have become legal at all.
 */
public final class Fxml2ProcessingInstructionPlacementInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,
                                                   boolean isOnTheFly,
                                                   @NotNull LocalInspectionToolSession session) {
        XmlFile file = Fxml2ResourceInspectionSupport.fxml2FileOf(holder.getFile());
        if (file == null) return PsiElementVisitor.EMPTY_VISITOR;

        return new PsiElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (element instanceof XmlProcessingInstruction instruction) {
                    check(instruction);
                }
            }

            private void check(@NotNull XmlProcessingInstruction instruction) {
                Fxml2ProcessingInstructionTarget target =
                        Fxml2ProcessingInstructionTarget.of(instruction);
                if (target == null) return;
                if (target.isReadInside(Fxml2ProcessingInstructionTarget.enclosingElement(instruction))) return;

                holder.registerProblem(
                        instruction,
                        "<?" + target.targetName()
                                + "?> inside an element is ignored; it is read only from the document prolog",
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
            }
        };
    }
}
