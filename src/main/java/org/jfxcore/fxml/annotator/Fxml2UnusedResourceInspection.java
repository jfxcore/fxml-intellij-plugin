package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalInspectionToolSession;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.codeinsight.Fxml2RemoveResourceDeclarationFix;
import org.jfxcore.fxml.resource.Fxml2ResourceEntry;
import org.jfxcore.fxml.resource.Fxml2ResourceUsageScanner;

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
        return Fxml2ResourceInspectionSupport.visitDeclarations(holder, entry -> {
            if (!entry.isValid() || isUsed(entry)) return;

            Fxml2ResourceInspectionSupport.report(
                    holder, entry,
                    entry.declaration().nameSpan().toTextRange(),
                    "Embedded resource '" + entry.name().value() + "' is never used in this document",
                    ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                    new Fxml2RemoveResourceDeclarationFix(entry.name().value()));
        });
    }

    /**
     * Returns {@code true} when anything in the declaring file refers to {@code entry}'s resource.
     *
     * <p>The whole file is scanned rather than just the markup, so that a usage in a sibling
     * resource payload counts, and so that both document forms are covered by the same scan: in
     * embedded markup the declaration and its usages live in the same Java or Kotlin literal.
     */
    private static boolean isUsed(@NotNull Fxml2ResourceEntry entry) {
        String text = entry.declaringFile().getText();
        int anchorStart = entry.anchor().getTextRange().getStartOffset();

        return Fxml2ResourceUsageScanner.isUsed(
                text,
                entry.declaration().instruction().instruction().shifted(anchorStart),
                entry.name().value());
    }
}
