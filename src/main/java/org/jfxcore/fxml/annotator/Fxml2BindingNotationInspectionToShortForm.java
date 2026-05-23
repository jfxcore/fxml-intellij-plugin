package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.XmlSuppressableInspectionTool;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reports FXML 2.0 binding expressions that use long-form {@code {fx:...}}
 * markup-extension notation and offers a quick-fix to convert them to the
 * equivalent compact notation ({@code $source}, {@code ${source}},
 * {@code >{source}}, {@code #{source}}, etc.).
 *
 * <p>Disabled by default; opt in via
 * <em>Settings -> Editor -> Inspections -> FXML 2.0</em>.
 * When enabled, <em>Fix all in file</em> and <em>Fix all in scope</em>
 * become available to batch-convert an entire file or project at once.
 */
public final class Fxml2BindingNotationInspectionToShortForm extends XmlSuppressableInspectionTool {

    @Override
    public ProblemDescriptor @Nullable [] checkFile(
            @NotNull PsiFile file, @NotNull InspectionManager manager, boolean isOnTheFly) {
        return Fxml2BindingNotationInspectionHelper.checkAll(file, manager, isOnTheFly, false);
    }
}
