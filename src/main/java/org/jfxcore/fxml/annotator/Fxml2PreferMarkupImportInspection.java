// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.annotator;

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.BatchQuickFix;
import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;

import java.util.List;

/**
 * Inspection that reports Java or Kotlin import statements which are exclusively used
 * inside embedded FXML2 markup ({@code @ComponentView} annotation values) and offers a
 * quick-fix to replace them with {@code <?import?>} processing instructions inside the
 * markup.
 *
 * <h2>Rationale</h2>
 * When FXML2 markup is embedded in a Java or Kotlin file via {@code @ComponentView}, the
 * host file must carry Java/Kotlin-level import statements for every class referenced in
 * the markup. In many projects it is preferable to keep markup-only dependencies expressed
 * as {@code <?import?>} PIs within the markup itself rather than as top-level code imports,
 * so that the host file's import section only reflects classes that are actually used
 * in the code.
 *
 * <h2>Conditions for reporting</h2>
 * An import is reported when:
 * <ol>
 *   <li>It is a non-static, non-wildcard exact import.</li>
 *   <li>The host Java file's own analysis considers it redundant
 *       (IntelliJ would normally flag it as "Unused import" without this plugin's
 *       suppressor).</li>
 *   <li>At least one {@code @ComponentView}-annotated class in the file uses the
 *       imported class inside its embedded markup.</li>
 *   <li>None of the {@code @ComponentView} markup blocks in the file already contain a
 *       {@code <?import?>} for the same fully-qualified name (no duplicates).</li>
 * </ol>
 *
 * <p>Disabled by default; opt in via
 * <em>Settings -> Editor -> Inspections -> FXML2</em>.
 * When enabled, <em>Fix all in file</em> and <em>Fix all in scope</em> are available
 * to batch-convert all such imports in one step.
 */
public final class Fxml2PreferMarkupImportInspection extends LocalInspectionTool {

    /** Short name used for {@link com.intellij.codeInsight.daemon.HighlightDisplayKey} lookups. */
    public static final String SHORT_NAME = "Fxml2PreferMarkupImport";

    @Override
    public @NotNull PsiElementVisitor buildVisitor(
            @NotNull ProblemsHolder holder, boolean isOnTheFly) {
        PsiFile file = holder.getFile();

        if (file instanceof PsiJavaFile javaFile) {
            return buildJavaVisitor(holder, javaFile);
        }

        if (file.getName().endsWith(".kt")) {
            try {
                return buildKotlinVisitor(holder, file);
            } catch (NoClassDefFoundError ignored) {
                return PsiElementVisitor.EMPTY_VISITOR;
            }
        }

        return PsiElementVisitor.EMPTY_VISITOR;
    }

    // -----------------------------------------------------------------------
    // Java visitor
    // -----------------------------------------------------------------------

    private static @NotNull PsiElementVisitor buildJavaVisitor(
            @NotNull ProblemsHolder holder,
            @NotNull PsiJavaFile javaFile) {

        // Bail out early if the file has no @ComponentView classes.
        boolean hasMarkupClass = false;
        for (PsiClass cls : javaFile.getClasses()) {
            if (cls.hasAnnotation(Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN)) {
                hasMarkupClass = true;
                break;
            }
        }
        if (!hasMarkupClass) return PsiElementVisitor.EMPTY_VISITOR;

        return new JavaElementVisitor() {
            @Override
            public void visitImportStatement(@NotNull PsiImportStatement importStmt) {
                if (!Fxml2ImportPlacementInspectionHelper.isJavaImportOnlyUsedInMarkup(
                        importStmt, javaFile)) {
                    return;
                }

                String fqn = Fxml2ImportPlacementInspectionHelper.getFqn(importStmt);
                if (fqn == null) return;

                // Ensure none of the annotated markup blocks already have this import.
                for (PsiClass cls :
                        Fxml2ImportPlacementInspectionHelper.getMarkupClassesInJavaFile(javaFile)) {
                    if (Fxml2ImportPlacementInspectionHelper.markupAlreadyContainsImport(cls, fqn)) {
                        return; // already present as markup import
                    }
                }

                holder.registerProblem(
                        importStmt,
                        "Import '" + fqn + "' is only used in embedded FXML2 markup; "
                                + "consider <?import " + fqn + "?> instead",
                        ProblemHighlightType.WEAK_WARNING,
                        new MoveToMarkupFix());
            }
        };
    }

    // -----------------------------------------------------------------------
    // Kotlin visitor
    // -----------------------------------------------------------------------

    private static @NotNull PsiElementVisitor buildKotlinVisitor(
            @NotNull ProblemsHolder holder,
            @NotNull PsiFile ktFile) {
        return new org.jetbrains.kotlin.psi.KtVisitorVoid() {
            @Override
            public void visitImportDirective(
                    @NotNull org.jetbrains.kotlin.psi.KtImportDirective importDirective) {
                if (!Fxml2ImportPlacementInspectionHelper.isKotlinImportOnlyUsedInMarkup(
                        importDirective, ktFile)) {
                    return;
                }

                var fqName = importDirective.getImportedFqName();
                if (fqName == null) return;
                String fqn = fqName.asString();

                // Ensure none of the annotated markup blocks already have this import.
                for (PsiClass cls :
                        Fxml2ImportPlacementInspectionHelper.getMarkupClassesInKotlinFile(ktFile)) {
                    if (Fxml2ImportPlacementInspectionHelper.markupAlreadyContainsImport(cls, fqn)) {
                        return;
                    }
                }

                holder.registerProblem(
                        importDirective,
                        "Import '" + fqn + "' is only used in embedded FXML2 markup; "
                                + "consider <?import " + fqn + "?> instead",
                        ProblemHighlightType.WEAK_WARNING,
                        new MoveToMarkupFix());
            }
        };
    }

    // -----------------------------------------------------------------------
    // Quick-fix
    // -----------------------------------------------------------------------

    /**
     * Quick-fix that moves a Java or Kotlin import statement to a {@code <?import?>}
     * processing instruction in every {@code @ComponentView} markup block of the same
     * source file.
     *
     * <p>The fix:
     * <ol>
     *   <li>Inserts {@code <?import fqn?>} into every embedded FXML2 markup block found
     *       in the same source file (so that each block remains self-contained).</li>
     *   <li>Deletes the original Java or Kotlin import statement.</li>
     * </ol>
     *
     * <p>Skips blocks that already contain the import (guards against duplicates).
     * Skips the entire fix when a conflicting import with the same simple name but a
     * different FQN exists in any markup block.
     */
    static final class MoveToMarkupFix implements LocalQuickFix, BatchQuickFix {

        @Override
        public @NotNull String getName() {
            return "Move to markup <?import?>";
        }

        @Override
        public @NotNull String getFamilyName() {
            return getName();
        }

        /**
         * This fix modifies both the host Java/Kotlin source file (import deletion) and the
         * embedded XML content (import insertion), spanning multiple logical documents.
         * No single-file diff preview is possible.
         */
        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project, @NotNull ProblemDescriptor previewDescriptor) {
            return IntentionPreviewInfo.EMPTY;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            applyToElement(project, descriptor.getPsiElement());
        }

        @Override
        public void applyFix(
                @NotNull Project project,
                CommonProblemDescriptor @NotNull [] descriptors,
                @NotNull List<PsiElement> psiElementsToIgnore,
                @Nullable Runnable refreshViews) {
            for (CommonProblemDescriptor descriptor : descriptors) {
                if (!(descriptor instanceof ProblemDescriptor pd)) continue;
                PsiElement elem = pd.getPsiElement();
                if (elem == null || !elem.isValid()) continue;
                psiElementsToIgnore.add(elem);
                applyToElement(project, elem);
            }
            if (refreshViews != null) refreshViews.run();
        }

        private static void applyToElement(@NotNull Project project, @Nullable PsiElement element) {
            if (element == null || !element.isValid()) return;

            String fqn;
            PsiFile hostFile = element.getContainingFile();

            if (element instanceof PsiImportStatement javaImport) {
                fqn = javaImport.getQualifiedName();
                if (fqn == null) return;
                if (!(hostFile instanceof PsiJavaFile javaFile)) return;

                PsiDocumentManager psiDocManager = PsiDocumentManager.getInstance(project);
                Document hostDoc = psiDocManager.getDocument(javaFile);

                // In a "Fix all in file" batch run, the previous iteration's PSI delete()
                // leaves the host document locked with deferred reformatting. Flush it now
                // so that Document.insertString() inside insertIntoAllMarkupBlocks won't
                // throw "Document is locked by write PSI operations". Also commit so that
                // the PSI-to-host offset mapping used by computeMarkupInsertPoint is fresh.
                if (hostDoc != null) {
                    psiDocManager.doPostponedOperationsAndUnblockDocument(hostDoc);
                    psiDocManager.commitDocument(hostDoc);
                }

                // Capture a smart pointer before inserting the PI: insertIntoAllMarkupBlocks
                // writes directly to the host document via Document.insertString, which
                // leaves the PSI uncommitted. We must commit and re-resolve before deleting.
                SmartPsiElementPointer<PsiImportStatement> importPtr =
                        SmartPointerManager.createPointer(javaImport);

                List<PsiClass> markupClasses =
                        Fxml2ImportPlacementInspectionHelper.getMarkupClassesInJavaFile(javaFile);
                insertIntoAllMarkupBlocks(markupClasses, fqn, project);

                // Commit the host document so PSI is in sync again before the PSI delete.
                if (hostDoc != null) {
                    psiDocManager.commitDocument(hostDoc);
                }

                PsiImportStatement resolvedImport = importPtr.getElement();
                if (resolvedImport != null && resolvedImport.isValid()) {
                    resolvedImport.delete();
                }

            } else {
                // Kotlin path
                try {
                    if (!(element instanceof org.jetbrains.kotlin.psi.KtImportDirective ktImport)) return;
                    var fqName = ktImport.getImportedFqName();
                    if (fqName == null) return;
                    fqn = fqName.asString();

                    PsiDocumentManager psiDocManager = PsiDocumentManager.getInstance(project);
                    Document hostDoc = psiDocManager.getDocument(hostFile);

                    // Same batch-fix unblock as in the Java path above.
                    if (hostDoc != null) {
                        psiDocManager.doPostponedOperationsAndUnblockDocument(hostDoc);
                        psiDocManager.commitDocument(hostDoc);
                    }

                    SmartPsiElementPointer<org.jetbrains.kotlin.psi.KtImportDirective> importPtr =
                            SmartPointerManager.createPointer(ktImport);

                    List<PsiClass> markupClasses =
                            Fxml2ImportPlacementInspectionHelper.getMarkupClassesInKotlinFile(hostFile);
                    insertIntoAllMarkupBlocks(markupClasses, fqn, project);

                    if (hostDoc != null) {
                        psiDocManager.commitDocument(hostDoc);
                    }

                    org.jetbrains.kotlin.psi.KtImportDirective resolvedImport = importPtr.getElement();
                    if (resolvedImport != null && resolvedImport.isValid()) {
                        resolvedImport.delete();
                    }
                } catch (NoClassDefFoundError ignored) {}
            }
        }

        private static void insertIntoAllMarkupBlocks(
                @NotNull List<PsiClass> markupClasses,
                @NotNull String fqn,
                @NotNull Project project) {
            Fxml2ImportPlacementInspectionHelper.insertFqnIntoAllMarkupBlocks(
                    markupClasses, fqn, project);
        }
    }
}
