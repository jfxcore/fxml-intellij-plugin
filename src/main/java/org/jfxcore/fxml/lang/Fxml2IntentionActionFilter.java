package org.jfxcore.fxml.lang;

import com.intellij.codeInsight.daemon.impl.IntentionActionFilter;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Suppresses XML intention actions that are not applicable to FXML files:
 * <ul>
 *   <li>"Convert text to CDATA" ({@code TextToCDataIntention}): CDATA sections are not
 *       used in FXML markup.</li>
 *   <li>"Insert namespace prefix" ({@code AddSchemaPrefixIntention}): FXML files use a
 *       fixed namespace structure managed by the FXML compiler; schema-prefix editing
 *       does not apply.</li>
 * </ul>
 *
 * <p>For <em>embedded</em> FXML (injected from a {@code @ComponentView} annotation), the
 * following additional intentions are suppressed:
 * <ul>
 *   <li>{@code CreateCodeBehindIntention}: the annotated class is already the code-behind.</li>
 * </ul>
 *
 * <p>{@code AddImportForClassReferenceIntention} is <em>not</em> suppressed for embedded
 * FXML: it adds a Java {@code import} to the host file instead of an XML {@code <?import?>}
 * processing instruction.
 */
public final class Fxml2IntentionActionFilter implements IntentionActionFilter {

    private static final Set<String> SUPPRESSED_CLASS_NAMES = Set.of(
            "com.intellij.codeInsight.daemon.impl.analysis.TextToCDataIntention",
            "com.intellij.codeInsight.daemon.impl.analysis.AddSchemaPrefixIntention"
    );

    /** Intentions suppressed only in embedded (injected) FXML, not in standalone files. */
    private static final Set<String> EMBEDDED_SUPPRESSED_CLASS_NAMES = Set.of(
            "org.jfxcore.fxml.actions.CreateCodeBehindIntention"
    );

    @Override
    public boolean accept(@NotNull IntentionAction intentionAction, @Nullable PsiFile psiFile) {
        if (psiFile == null) return true;
        if (!Fxml2FileType.isFxml2(psiFile)) return true;
        String className = unwrap(intentionAction).getClass().getName();
        // Suppress actions that never apply to any FXML file.
        if (SUPPRESSED_CLASS_NAMES.contains(className)) return false;
        // For embedded FXML, suppress additional non-applicable intentions.
        return !Fxml2EmbeddedUtil.isEmbeddedFxml2(psiFile)
                || !EMBEDDED_SUPPRESSED_CLASS_NAMES.contains(className);
    }

    /** Unwraps {@code IntentionActionDelegate} wrappers to get the real action class. */
    private static @NotNull IntentionAction unwrap(@NotNull IntentionAction action) {
        // IntentionActionWrapper implements IntentionActionDelegate and has getDelegate()
        if (action instanceof com.intellij.codeInsight.intention.IntentionActionDelegate delegate) {
            IntentionAction inner = delegate.getDelegate();
            if (inner != action) return unwrap(inner);
        }
        return action;
    }
}
