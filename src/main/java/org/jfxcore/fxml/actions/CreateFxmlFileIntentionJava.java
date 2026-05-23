// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.actions;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.lang.Fxml2EmbedMarkupUtil;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;

/**
 * "Create FXML file" intention for Java source files.
 *
 * <p>Available when the caret is on or inside a {@code @ComponentView} annotation
 * on a Java class (but not inside the annotation value text block).
 * Registered with {@code <language>JAVA</language>} in {@code plugin.xml}.
 */
public final class CreateFxmlFileIntentionJava extends CreateFxmlFileIntention {

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        if (!(file instanceof PsiJavaFile)) return false;
        PsiAnnotation annotation = getJavaAnnotation(editor, file);
        if (annotation == null) return false;
        PsiClass hostClass = PsiTreeUtil.getParentOfType(annotation, PsiClass.class);
        if (hostClass == null) return false;
        String fqn = hostClass.getQualifiedName();
        if (fqn == null) return false;
        if (Fxml2EmbedMarkupUtil.extractMarkupFromJavaAnnotation(annotation) == null) return false;
        if (fxmlSiblingExists(file.getVirtualFile(), StringUtil.getShortName(fqn))) return false;
        // Hide when the inspection is enabled: its quickfix already covers this action
        return isInspectionDisabled(project, file);
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
                    if (!(file instanceof PsiJavaFile javaFile)) return;
                    PsiAnnotation annotation = getJavaAnnotation(editor, file);
                    if (annotation == null) return;
                    Fxml2EmbedMarkupUtil.applyExtractJava(project, javaFile, annotation);
                },
                file);
    }

    // -----------------------------------------------------------------------
    // Helpers

    /**
     * Returns the {@code @ComponentView} annotation when the caret is on or inside it
     * (but not inside the annotation's value literal), or {@code null} otherwise.
     */
    private static @Nullable PsiAnnotation getJavaAnnotation(
            @Nullable Editor editor, @NotNull PsiFile file) {
        if (editor == null) return null;
        PsiElement element = file.findElementAt(editor.getCaretModel().getOffset());
        if (element == null) return null;

        PsiAnnotation annotation = PsiTreeUtil.getParentOfType(
                element, PsiAnnotation.class, /* strict= */ false);
        if (annotation == null) return null;

        // Exclude: caret is inside the annotation value literal (the text block)
        PsiAnnotationMemberValue value = annotation.findAttributeValue("value");
        if (PsiTreeUtil.isAncestor(value, element, /* strict= */ false)) return null;

        if (!Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN.equals(annotation.getQualifiedName())) {
            return null;
        }
        // Must be on a type declaration (class, interface, enum, record)
        if (PsiTreeUtil.getParentOfType(annotation, PsiClass.class) == null) return null;
        return annotation;
    }
}
