// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.actions;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Abstract base for the "Create FXML file" intention action.
 *
 * <p>The reverse of {@link EmbedMarkupInCodeBehindIntention}: extracts the markup from a
 * {@code @ComponentView} annotation on a Java or Kotlin class, writes it to a new sibling
 * {@code ClassName.fxml} file (with {@code xmlns} namespace declarations and {@code fx:subclass}
 * restored on the root element), and removes the annotation from the source file.
 *
 * <p>Concrete subclasses handle language-specific logic:
 * <ul>
 *   <li>{@link CreateFxmlFileIntentionJava}: registers for {@code JAVA} files</li>
 *   <li>{@link CreateFxmlFileIntentionKotlin}: registers for {@code Kotlin} files</li>
 * </ul>
 *
 * <h2>Prerequisites (enforced by each subclass)</h2>
 * <ul>
 *   <li>The caret is inside a {@code @ComponentView} annotation on a type declaration.</li>
 *   <li>The annotation has a non-empty string value (text block / triple-quoted string).</li>
 *   <li>No sibling {@code ClassName.fxml} file already exists.</li>
 * </ul>
 *
 * <p>A confirmation dialog is shown before any changes are made. In unit tests set
 * {@link #skipConfirmationForTesting} to {@code true} to bypass the dialog.
 */
public abstract class CreateFxmlFileIntention implements IntentionAction, PriorityAction {

    /**
     * When {@code true}, the confirmation dialog is suppressed and the action proceeds
     * immediately on invocation. Intended exclusively for unit tests.
     *
     * <p>Always reset to {@code false} in an {@code @AfterEach} / {@code try-finally} block.
     */
    @SuppressWarnings("StaticNonFinalField") // intentionally mutable, test injection point
    public static volatile boolean skipConfirmationForTesting = false;

    // -----------------------------------------------------------------------
    // IntentionAction identity

    @Override
    public final @NotNull String getText() {
        return "Create FXML file";
    }

    @Override
    public final @NotNull String getFamilyName() {
        return "Create FXML file";
    }

    @Override
    public final boolean startInWriteAction() {
        // We manage our own WriteCommandAction inside invoke()
        return false;
    }

    @Override
    public @NotNull IntentionPreviewInfo generatePreview(
            @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        return new IntentionPreviewInfo.Html(
                "Creates a new <code>.fxml</code> file from the embedded markup " +
                "and removes the <code>@ComponentView</code> annotation.");
    }

    @Override
    public final @NotNull Priority getPriority() {
        return Priority.NORMAL;
    }

    // -----------------------------------------------------------------------
    // Shared helpers

    /**
     * Returns {@code true} when a {@code ClassName.fxml} sibling already exists next to {@code file}.
     */
    static boolean fxmlSiblingExists(
            @Nullable VirtualFile file, @NotNull String simpleName) {
        if (file == null) return false;
        VirtualFile parent = file.getParent();
        if (parent == null) return false;
        return parent.findChild(simpleName + ".fxml") != null;
    }

    /**
     * Returns {@code true} when the {@code Fxml2CreateFxmlFile} inspection is <em>not</em>
     * currently enabled in the project's active inspection profile for {@code file}.
     *
     * <p>When the inspection is enabled its quick-fix is already offered in the editor;
     * subclasses should call this in {@code isAvailable()} and return its value directly
     * to avoid showing the same action twice.
     */
    static boolean isInspectionDisabled(@NotNull Project project, @NotNull PsiFile file) {
        HighlightDisplayKey key = HighlightDisplayKey.findOrRegister(
                "Fxml2CreateFxmlFile", "Fxml2CreateFxmlFile");
        return !InspectionProjectProfileManager.getInstance(project)
                .getCurrentProfile().isToolEnabled(key, file);
    }

    /**
     * Shows the confirmation dialog and returns {@code true} if the user cancelled
     * (or closed) the dialog, {@code false} if the user confirmed.
     * Always returns {@code false} when {@link #skipConfirmationForTesting} is set.
     */
    static boolean isCancelled(@NotNull Project project) {
        if (skipConfirmationForTesting) return false;
        int result = Messages.showOkCancelDialog(
                project,
                """
                        This will create a new FXML file with the embedded markup \
                        and remove the @ComponentView annotation from the class.

                        Proceed?""",
                "Create FXML File",
                "Create",
                "Cancel",
                Messages.getQuestionIcon());
        return result != Messages.OK;
    }
}
