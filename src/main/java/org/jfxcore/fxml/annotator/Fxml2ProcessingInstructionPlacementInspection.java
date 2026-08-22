package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalInspectionToolSession;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlProcessingInstruction;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlTokenType;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;

import java.util.Set;

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

    /** The processing-instruction targets that are read only from the document's direct children. */
    private static final Set<String> PROLOG_ONLY_TARGETS = Set.of("import", "prefix");

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
                String target = targetOf(instruction);
                if (target == null || !PROLOG_ONLY_TARGETS.contains(target)) return;
                if (!isInsideElement(instruction)) return;

                holder.registerProblem(
                        instruction,
                        "<?" + target + "?> inside an element is ignored; it is read only from the document prolog",
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
            }
        };
    }

    /** Returns the target of {@code instruction}, or {@code null} when it has none. */
    private static String targetOf(@NotNull XmlProcessingInstruction instruction) {
        ASTNode name = instruction.getNode().findChildByType(XmlTokenType.XML_NAME);
        return name == null ? null : name.getText();
    }

    /**
     * Returns {@code true} when {@code instruction} is nested in an element rather than being a
     * direct child of the document.
     *
     * <p>In embedded markup the synthetic wrapper element the injector adds around the user's
     * markup is not an element the user wrote, so an instruction directly inside it is in the
     * document prolog as far as the user is concerned.
     */
    private static boolean isInsideElement(@NotNull XmlProcessingInstruction instruction) {
        XmlTag tag = PsiTreeUtil.getParentOfType(instruction, XmlTag.class);
        return tag != null && !Fxml2EmbeddedUtil.isWrapperRoot(tag);
    }
}
