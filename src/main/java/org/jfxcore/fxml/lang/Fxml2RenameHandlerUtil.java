// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.refactoring.rename.PsiElementRenameHandler;
import com.intellij.refactoring.rename.RenameDialog;
import com.intellij.refactoring.rename.RenameProcessor;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

/**
 * Shared utility for FXML2 rename handlers.
 */
final class Fxml2RenameHandlerUtil {

    private Fxml2RenameHandlerUtil() {}

    /**
     * Performs the rename: in unit-test mode reads the new name from {@code dataContext} and
     * runs a headless {@link RenameProcessor}; otherwise shows a {@link RenameDialog}.
     */
    static void doRename(@NotNull Project project,
                         @NotNull Editor editor,
                         @NotNull PsiElement toRename,
                         @NotNull DataContext dataContext) {
        if (ApplicationManager.getApplication().isUnitTestMode()) {
            String newName = Objects.requireNonNull(PsiElementRenameHandler.DEFAULT_NAME.getData(dataContext),
                    "PsiElementRenameHandler.DEFAULT_NAME not set in test DataContext");
            new RenameProcessor(project, toRename, newName, false, false).run();
        } else {
            new RenameDialog(project, toRename, null, editor).show();
        }
    }
}
