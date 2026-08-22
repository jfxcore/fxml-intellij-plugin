package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalInspectionToolSession;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.codeinsight.Fxml2SetResourceMediaTypeFix;
import org.jfxcore.fxml.resource.Fxml2ResourceEntry;
import org.jfxcore.fxml.resource.Fxml2ResourcePayloadLanguage;

/**
 * Reports a {@code <?resource ?>} declaration whose media type does not agree with the file
 * extension of its name.
 *
 * <p>The media type is what the editor reads to decide how to edit a payload, and the file
 * extension is what a reader reads to decide what the payload is.  When the two disagree, one of
 * them is wrong; when the media type is missing altogether, writing it makes the intent explicit
 * and stops the editor from having to infer it.
 *
 * <p>Both cases are advisory, because neither changes what the compiler does: apart from the
 * charset, the media type is informational.  Deriving CSS from a {@code .css} name is an
 * inference the editor makes and the markup language does not, so the inspection that suggests
 * writing it down is what keeps that inference honest.
 */
public final class Fxml2ResourceMediaTypeInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,
                                                   boolean isOnTheFly,
                                                   @NotNull LocalInspectionToolSession session) {
        return Fxml2ResourceInspectionSupport.visitDeclarations(holder, entry -> {
            if (!entry.isValid()) return;

            Fxml2ResourcePayloadLanguage implied =
                    Fxml2ResourcePayloadLanguage.ofExtension(entry.name().extension());
            if (implied == null || implied == Fxml2ResourcePayloadLanguage.PLAIN_TEXT) return;

            String message = messageFor(entry, implied);
            if (message == null) return;

            Fxml2ResourceInspectionSupport.report(
                    holder, entry,
                    entry.declaration().hasExplicitMediaType()
                            ? entry.declaration().mediaTypeSpan().toTextRange()
                            : entry.declaration().nameSpan().toTextRange(),
                    message,
                    ProblemHighlightType.WEAK_WARNING,
                    new LocalQuickFix[] {
                            new Fxml2SetResourceMediaTypeFix(entry.name().value(), implied.canonicalMediaType())});
        });
    }

    /**
     * Returns what to report about {@code entry}'s media type, or {@code null} when it agrees with
     * the extension and there is nothing to say.
     */
    private static @Nullable String messageFor(@NotNull Fxml2ResourceEntry entry,
                                               @NotNull Fxml2ResourcePayloadLanguage implied) {
        String name = entry.name().value();

        if (!entry.declaration().hasExplicitMediaType()) {
            return "Resource '" + name + "' has no media type; its name implies "
                    + implied.canonicalMediaType();
        }

        Fxml2ResourcePayloadLanguage declared =
                Fxml2ResourcePayloadLanguage.ofMediaType(entry.declaration().effectiveMediaType());
        if (declared == implied) return null;

        return "Media type of resource '" + name + "' does not match its file extension, which implies "
                + implied.canonicalMediaType();
    }
}
