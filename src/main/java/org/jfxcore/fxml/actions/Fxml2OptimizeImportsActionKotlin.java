// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.actions;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtFile;

/**
 * "Optimize Imports (FXML2)" intention for Kotlin source files.
 *
 * <p>Available when the caret is inside the import section of a Kotlin file that has at
 * least one {@code @ComponentView}-annotated class.
 * Registered with {@code <language>kotlin</language>} in {@code fxml2-with-kotlin.xml}.
 *
 * <p>All Kotlin PSI access is guarded against {@link NoClassDefFoundError} so that the
 * plugin continues to work when the Kotlin plugin is not installed.
 */
public final class Fxml2OptimizeImportsActionKotlin extends Fxml2OptimizeImportsAction {

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        try {
            if (editor == null || !(file instanceof KtFile ktFile)) return false;
            if (!hasMarkupAnnotation(ktFile)) return false;
            var importList = ktFile.getImportList();
            return importList != null
                    && importList.getTextRange().containsOffset(editor.getCaretModel().getOffset());
        } catch (NoClassDefFoundError ignored) {
            return false;
        }
    }

    private static boolean hasMarkupAnnotation(@NotNull KtFile ktFile) {
        for (KtClassOrObject ktClass :
                PsiTreeUtil.findChildrenOfType(ktFile, KtClassOrObject.class)) {
            for (KtAnnotationEntry entry : ktClass.getAnnotationEntries()) {
                var shortName = entry.getShortName();
                if (shortName != null
                        && "ComponentView".equals(shortName.getIdentifier())) {
                    return true;
                }
            }
        }
        return false;
    }
}
