// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.actions;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtImportDirective;
import org.jfxcore.fxml.lang.Fxml2EmbedMarkupUtil;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;

/**
 * "Create FXML file" intention for Kotlin source files.
 *
 * <p>Available when the caret is on or inside a {@code @ComponentView} annotation
 * on a Kotlin class/object (but not inside the annotation value triple-quoted string).
 * Registered with {@code <language>kotlin</language>} in {@code plugin.xml}.
 *
 * <p>The entire class is guarded against {@link NoClassDefFoundError} so that the
 * plugin continues to work when the Kotlin plugin is not installed.
 */
public final class CreateFxmlFileIntentionKotlin extends CreateFxmlFileIntention {

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        try {
            if (!(file instanceof KtFile)) return false;
            KtAnnotationEntry annotEntry = getKotlinAnnotationEntry(editor, file);
            if (annotEntry == null) return false;
            KtClassOrObject ktClass = PsiTreeUtil.getParentOfType(annotEntry, KtClassOrObject.class);
            if (ktClass == null) return false;
            String simpleName = ktClass.getName();
            if (simpleName == null) return false;
            var args = annotEntry.getValueArguments();
            if (args.isEmpty()) return false;
            var argExpr = args.getFirst().getArgumentExpression();
            if (Fxml2EmbedMarkupUtil.extractMarkupFromKotlinExpression(argExpr) == null) return false;
            if (fxmlSiblingExists(file.getVirtualFile(), simpleName)) return false;
            // Hide when the inspection is enabled: its quickfix already covers this action
            return isInspectionDisabled(project, file);
        } catch (NoClassDefFoundError ignored) {
            return false;
        }
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file)
            throws IncorrectOperationException {
        if (isCancelled(project)) return;
        WriteCommandAction.runWriteCommandAction(
                project,
                "Create FXML File",
                null,
                () -> {
                    PsiDocumentManager.getInstance(project).commitAllDocuments();
                    try {
                        if (!(file instanceof KtFile ktFile)) return;
                        KtAnnotationEntry annotEntry = getKotlinAnnotationEntry(editor, file);
                        if (annotEntry == null) return;
                        Fxml2EmbedMarkupUtil.applyExtractKotlin(project, ktFile, annotEntry);
                    } catch (NoClassDefFoundError ignored) {}
                },
                file);
    }

    // -----------------------------------------------------------------------
    // Helpers

    /**
     * Returns the {@code @ComponentView} {@link KtAnnotationEntry} when the caret is on or
     * inside it (but not inside the annotation's string expression), or {@code null} otherwise.
     */
    private static @Nullable KtAnnotationEntry getKotlinAnnotationEntry(
            @Nullable Editor editor, @NotNull PsiFile file) {
        try {
            if (editor == null) return null;
            PsiElement element = file.findElementAt(editor.getCaretModel().getOffset());
            if (element == null) return null;

            KtAnnotationEntry annotEntry = PsiTreeUtil.getParentOfType(
                    element, KtAnnotationEntry.class, /* strict= */ false);
            if (annotEntry == null) return null;

            // Exclude: caret is inside the annotation's string expression value
            var args = annotEntry.getValueArguments();
            if (!args.isEmpty()) {
                var argExpr = args.getFirst().getArgumentExpression();
                if (PsiTreeUtil.isAncestor(argExpr, element, /* strict= */ false)) return null;
            }

            // Quick name check (avoids scanning imports unnecessarily)
            var shortName = annotEntry.getShortName();
            if (shortName == null || !"ComponentView".equals(shortName.getIdentifier())) return null;

            // Confirm the annotation is org.jfxcore.markup.ComponentView via import scan
            if (!(file instanceof KtFile ktFile)) return null;
            if (!isMarkupAnnotationImported(ktFile)) return null;

            // Must be on a class/object/interface
            if (PsiTreeUtil.getParentOfType(annotEntry, KtClassOrObject.class) == null) return null;
            return annotEntry;
        } catch (NoClassDefFoundError ignored) {
            return null;
        }
    }

    /**
     * Returns {@code true} when the Kotlin file explicitly imports
     * {@code org.jfxcore.markup.ComponentView} (directly or via a wildcard).
     */
    private static boolean isMarkupAnnotationImported(@NotNull KtFile ktFile) {
        try {
            for (KtImportDirective imp : ktFile.getImportDirectives()) {
                var fqName = imp.getImportedFqName();
                if (fqName == null) continue;
                String fqStr = fqName.asString();
                if (Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN.equals(fqStr)) return true;
                if (imp.isAllUnder() && "org.jfxcore.markup".equals(fqStr)) return true;
            }
            return false;
        } catch (NoClassDefFoundError ignored) {
            return false;
        }
    }
}
