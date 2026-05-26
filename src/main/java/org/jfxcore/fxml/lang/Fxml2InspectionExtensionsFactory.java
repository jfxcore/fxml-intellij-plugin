// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.GlobalInspectionContext;
import com.intellij.codeInspection.HTMLComposer;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ex.GlobalInspectionContextEx;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.codeInspection.ex.Tools;
import com.intellij.codeInspection.lang.GlobalInspectionContextExtension;
import com.intellij.codeInspection.lang.HTMLComposerExtension;
import com.intellij.codeInspection.lang.InspectionExtensionsFactory;
import com.intellij.codeInspection.lang.RefManagerExtension;
import com.intellij.codeInspection.reference.RefManager;
import com.intellij.codeInspection.ui.ReportedProblemFilter;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiImportStaticStatement;
import com.intellij.psi.PsiJavaFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * {@link InspectionExtensionsFactory} that suppresses false-positive "Unused import"
 * problems for imports that are actually needed by embedded FXML markup.
 *
 * <h2>Problem</h2>
 * {@link com.intellij.codeInspection.unusedImport.UnusedImportInspection} is a
 * {@link com.intellij.codeInspection.GlobalSimpleInspectionTool}.  When IntelliJ's
 * "Inspect Code" action runs global-simple tools, it passes {@code filterSuppressed=false}
 * to the internal {@code BatchModeDescriptorsUtil.addProblemDescriptors},
 * which means the registered {@link com.intellij.codeInspection.InspectionSuppressor}
 * ({@link Fxml2JavaUnusedImportSuppressor}) is <em>never consulted</em>.
 *
 * <h2>Solution</h2>
 * This factory creates a {@link GlobalInspectionContextExtension} whose
 * {@link GlobalInspectionContextExtension#performPreRunActivities performPreRunActivities}
 * hook fires <em>before</em> any inspections run.  It installs a
 * {@link ReportedProblemFilter} on the {@link GlobalInspectionContextEx} that is consulted
 * by {@link com.intellij.codeInspection.DefaultInspectionToolResultExporter#addProblemElement}
 * as each problem is recorded.  The filter returns {@code false} (suppress) for any
 * {@link PsiImportStatement} problem element that is actually needed by embedded FXML
 * markup, preventing those descriptors from ever entering the "active problems" map.
 *
 * <p>Registered via {@code codeInspection.InspectionExtension} in {@code plugin.xml}.
 */
public final class Fxml2InspectionExtensionsFactory extends InspectionExtensionsFactory {

    @Override
    public GlobalInspectionContextExtension<?> createGlobalInspectionContextExtension() {
        return new Fxml2GlobalInspectionContextExtension();
    }

    @Override
    public @Nullable RefManagerExtension<?> createRefManagerExtension(@NotNull RefManager refManager) {
        return null;
    }

    @Override
    public @Nullable HTMLComposerExtension<?> createHTMLComposerExtension(HTMLComposer composer) {
        return null;
    }

    @Override
    public boolean isToCheckMember(@NotNull PsiElement element, @NotNull String id) {
        return true;
    }

    @Override
    public @Nullable String getSuppressedInspectionIdsIn(@NotNull PsiElement element) {
        return null;
    }

    // -----------------------------------------------------------------------

    private static final class Fxml2GlobalInspectionContextExtension
            implements GlobalInspectionContextExtension<Fxml2GlobalInspectionContextExtension> {

        private static final Key<Fxml2GlobalInspectionContextExtension> KEY =
                Key.create("org.jfxcore.fxml.Fxml2GlobalInspectionContextExtension");

        @Override
        public @NotNull Key<Fxml2GlobalInspectionContextExtension> getID() {
            return KEY;
        }

        /**
         * Called before any inspections run.  Installs a {@link ReportedProblemFilter} that
         * suppresses "Unused import" problems whose import is needed by embedded FXML markup.
         *
         * <p>The filter is checked inside
         * {@link com.intellij.codeInspection.DefaultInspectionToolResultExporter#addProblemElement}
         * as each problem descriptor is stored, which is the correct moment for
         * {@link com.intellij.codeInspection.GlobalSimpleInspectionTool} problems (they are
         * added during the file-processing loop, <em>after</em> the {@code performPostRunActivities}
         * hook has already fired: making post-run suppression too late).
         */
        @Override
        public void performPreRunActivities(
                @NotNull List<Tools> globalTools,
                @NotNull List<Tools> localTools,
                @NotNull GlobalInspectionContext context) {

            if (!(context instanceof GlobalInspectionContextEx ctxEx)) return;

            // Chain with any previously installed filter so we don't replace existing ones.
            ReportedProblemFilter existing = ctxEx.getReportedProblemFilter();
            ctxEx.setReportedProblemFilter((refElement, descriptors) -> {
                // Delegate to any pre-existing filter first.
                if (existing != null && !existing.shouldReportProblem(refElement, descriptors)) {
                    return false;
                }
                // For each descriptor, if the problem element is an import statement that
                // FXML actually needs, suppress the problem.
                // Guard: skip problems from preference inspections (Fxml2PreferMarkupImportInspection)
                // that intentionally flag markup-needed imports for user action.
                for (CommonProblemDescriptor descriptor : descriptors) {
                    if (!(descriptor instanceof ProblemDescriptor pd)) continue;
                    if (!(pd.getPsiElement() instanceof PsiImportStatement importStmt)) continue;
                    String desc = pd.getDescriptionTemplate();
                    if (desc.contains("embedded FXML markup")) continue;
                    if (isImportNeededByFxml2(importStmt)) {
                        return false; // suppress the unused-import problem for FXML-needed import
                    }
                }
                return true; // allow
            });
        }

        @Override
        public void performPostRunActivities(
                @NotNull List<InspectionToolWrapper<?, ?>> inspections,
                @NotNull GlobalInspectionContext context) {
            // Global simple tools (e.g. UnusedImportInspection) add their problems during
            // the file-processing loop, which runs AFTER this hook fires.  Suppression is
            // therefore handled in performPreRunActivities() via a ReportedProblemFilter.
        }

        @Override
        public void cleanup() {
            // nothing to clean up
        }

        // -------------------------------------------------------------------

        /**
         * Returns {@code true} if the given import statement is needed by the embedded
         * FXML markup of any {@code @ComponentView}-annotated class in the same Java file.
         */
        private static boolean isImportNeededByFxml2(@NotNull PsiImportStatement importStmt) {
            if (importStmt instanceof PsiImportStaticStatement) return false;
            if (!(importStmt.getContainingFile() instanceof PsiJavaFile javaFile)) return false;

            return Fxml2ImportUtil.isImportNeededInJavaFile(importStmt, javaFile);
        }
    }
}
