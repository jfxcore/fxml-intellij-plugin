package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalInspectionToolSession;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.codeinsight.Fxml2ResourceDeclarationFix;
import org.jfxcore.fxml.resource.Fxml2ResourceUsages;
import org.jfxcore.fxml.lang.Fxml2FileType;

/**
 * Reports a {@code <?resource ?>} declaration that nothing in its document refers to.
 *
 * <p>An unreferenced declaration is not a correctness problem: the compiler materializes the
 * resource whether or not the document loads it, and code outside the document can load it by
 * name.  It is, however, almost always an oversight, and it costs build output and class-path
 * space, which is why it is reported as a weak warning rather than as a warning.
 *
 * <p>Usages counted include the payloads of the document's other resources, because a stylesheet
 * may pull in another one with an {@code @import}.
 */
public final class Fxml2UnusedResourceInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,
                                                   boolean isOnTheFly,
                                                   @NotNull LocalInspectionToolSession session) {
        var file = Fxml2FileType.asFxml2(holder.getFile());
        if (file == null) return PsiElementVisitor.EMPTY_VISITOR;
        return Fxml2ResourceInspectionSupport.visitDeclarations(holder, entry -> {
            if (!entry.isValid() || Fxml2ResourceUsages.isUsed(entry, file)) return;

            Fxml2ResourceInspectionSupport.report(
                    holder, entry,
                    entry.declaration().nameSpan().toTextRange(),
                    "Embedded resource '" + entry.name().value() + "' is never used in this document",
                    ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                    Fxml2ResourceDeclarationFix.remove(entry.name()));
        });
    }
}
