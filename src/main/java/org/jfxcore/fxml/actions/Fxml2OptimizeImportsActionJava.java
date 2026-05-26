// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.actions;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiJavaFile;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;

/**
 * "Optimize Imports (FXML/2)" intention for Java source files.
 *
 * <p>Available when the caret is inside the import section of a Java file that has at
 * least one {@code @ComponentView}-annotated class.
 * Registered with {@code <language>JAVA</language>} in {@code plugin.xml}.
 */
public final class Fxml2OptimizeImportsActionJava extends Fxml2OptimizeImportsAction {

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        if (editor == null || !(file instanceof PsiJavaFile javaFile)) return false;
        if (!hasMarkupAnnotation(javaFile)) return false;
        PsiImportList importList = javaFile.getImportList();
        return importList != null
                && importList.getTextRange().containsOffset(editor.getCaretModel().getOffset());
    }

    private static boolean hasMarkupAnnotation(@NotNull PsiJavaFile javaFile) {
        for (PsiClass cls : javaFile.getClasses()) {
            if (cls.hasAnnotation(Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN)) return true;
        }
        return false;
    }
}
