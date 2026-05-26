// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.actions;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.LowPriorityAction;
import com.intellij.lang.ImportOptimizer;
import com.intellij.lang.LanguageImportStatements;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Base class for the "Optimize Imports (FXML/2)" intention action.
 *
 * <p>IntelliJ's built-in "Optimize imports" action bypasses the
 * {@code lang.importOptimizer} extension point, calling the built-in Java/Kotlin
 * optimizer directly.  For files with {@code @ComponentView} annotations this removes
 * imports that are only referenced in embedded FXML markup.
 *
 * <p>This action uses {@link LanguageImportStatements#forFile} so that
 * {@code Fxml2EmbeddedJavaImportOptimizer} / {@code Fxml2EmbeddedKotlinImportOptimizer}
 * (both registered with {@code order="first"}) are selected instead.
 *
 * <p>Two concrete subclasses, {@link Fxml2OptimizeImportsActionJava} and
 * {@link Fxml2OptimizeImportsActionKotlin}, are registered in {@code plugin.xml} and
 * {@code fxml2-with-kotlin.xml} respectively, each with its own {@code <language>} tag,
 * so that IntelliJ does not flag them as duplicate intention registrations.
 */
public abstract class Fxml2OptimizeImportsAction implements IntentionAction, LowPriorityAction {

    @Override
    public @NotNull String getText() {
        return "Optimize Imports (FXML/2)";
    }

    @Override
    public @NotNull String getFamilyName() {
        return getText();
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
        if (file == null) return;
        Set<ImportOptimizer> optimizers = LanguageImportStatements.INSTANCE.forFile(file);
        // Collect write-phase runnables during the read phase (processFile() reads PSI state).
        List<Runnable> writeRunnables = new ArrayList<>();
        for (ImportOptimizer optimizer : optimizers) {
            for (PsiFile psiFile : file.getViewProvider().getAllFiles()) {
                if (optimizer.supports(psiFile)) {
                    writeRunnables.add(optimizer.processFile(psiFile));
                    break;
                }
            }
        }
        if (writeRunnables.isEmpty()) return;
        WriteCommandAction.runWriteCommandAction(project, getText(), null, () -> {
            for (Runnable r : writeRunnables) {
                r.run();
            }
        }, file);
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }
}
