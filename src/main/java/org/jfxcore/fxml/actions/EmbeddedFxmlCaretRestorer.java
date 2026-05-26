package org.jfxcore.fxml.actions;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Utility for deferring host-editor caret restoration after a PSI mutation has been applied
 * to an embedded (injected) FXML fragment.
 *
 * <p>When PSI elements inside an injected language fragment are mutated (e.g. via
 * {@link com.intellij.psi.xml.XmlAttribute#setValue} or
 * {@link com.intellij.psi.xml.XmlTag#setName}), IntelliJ's EditorWindowImpl caret-resync
 * machinery repositions the host editor's caret to the injection boundary after the write
 * action commits.  The methods in this class counteract that by scheduling a caret move to
 * the desired host-document offset via a chain of deferred callbacks that run after both
 * the PSI commit and the resync.  A one-shot {@link CaretListener} guards against any
 * further resync that fires even later.
 */
public final class EmbeddedFxmlCaretRestorer {

    private EmbeddedFxmlCaretRestorer() {}

    /**
     * Schedules a deferred caret restoration to {@code targetHostOffset} in the host editor
     * of {@code hostFile}.
     *
     * <p>Must be called from within a write action (e.g. from
     * {@link com.intellij.codeInspection.LocalQuickFix#applyFix} or
     * {@link com.intellij.codeInsight.intention.IntentionAction#invoke}) while
     * {@code hostFile} is still a valid open file.
     *
     * <p>Does nothing when no host editor is open or when {@code targetHostOffset} is
     * negative.
     *
     * @param project          the current project
     * @param hostFile         the host Java/Kotlin file that embeds the FXML fragment
     * @param targetHostOffset the desired caret position in the host document after the fix;
     *                         pass {@code -1} to suppress caret restoration
     */
    public static void scheduleRestore(
            @NotNull Project project,
            @NotNull PsiFile hostFile,
            int targetHostOffset) {

        if (targetHostOffset < 0) return;

        Editor hostEditor = findHostEditor(project, hostFile);
        if (hostEditor == null) return;

        // Schedule through invokeLater so the write action has fully released the write lock
        // before we ask the EDT to commit and resync carets.
        ApplicationManager.getApplication().invokeLater(() ->
                PsiDocumentManager.getInstance(project).performLaterWhenAllCommitted(
                        () -> ApplicationManager.getApplication().invokeLater(() -> {
                            hostEditor.getCaretModel().moveToOffset(targetHostOffset);

                            // Guard against a further EditorWindowImpl resync that fires after
                            // the nested invokeLater and would move the caret to the injection
                            // boundary.
                            CaretListener[] guard = {null};
                            guard[0] = new CaretListener() {
                                @Override
                                public void caretPositionChanged(@NotNull CaretEvent e) {
                                    hostEditor.getCaretModel().removeCaretListener(guard[0]);
                                    if (Math.abs(hostEditor.getCaretModel().getOffset()
                                            - targetHostOffset) > 2) {
                                        ApplicationManager.getApplication().invokeLater(
                                                () -> hostEditor.getCaretModel()
                                                        .moveToOffset(targetHostOffset));
                                    }
                                }
                            };
                            hostEditor.getCaretModel().addCaretListener(guard[0]);
                            // Auto-remove the guard on the next EDT cycle if no rogue move fired.
                            ApplicationManager.getApplication().invokeLater(
                                    () -> hostEditor.getCaretModel().removeCaretListener(guard[0]));
                        })));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static @Nullable Editor findHostEditor(@NotNull Project project, @NotNull PsiFile hostFile) {
        var vf = hostFile.getVirtualFile();
        if (vf == null) return null;
        for (var fe : FileEditorManager.getInstance(project).getAllEditors(vf)) {
            if (fe instanceof TextEditor te) return te.getEditor();
        }
        return null;
    }
}
