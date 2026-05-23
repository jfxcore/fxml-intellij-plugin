// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.BatchQuickFix;
import com.intellij.codeInspection.CleanupLocalInspectionTool;
import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.lang.Fxml2EmbedMarkupUtil;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;

import java.util.List;

/**
 * Inspection that reports Java and Kotlin classes annotated with
 * {@code @ComponentView} when no sibling FXML file exists, and offers a quick-fix
 * to extract the embedded markup into a standalone {@code .fxml} file.
 *
 * <p>Disabled by default; opt in via
 * <em>Settings -> Editor -> Inspections -> FXML2</em>.
 * When enabled, <em>Fix all in file</em> and <em>Fix all in scope</em>
 * become available to batch-create FXML files from embedded markup.
 * The inspection also participates in <em>Code -> Code Cleanup</em>
 * (via {@link CleanupLocalInspectionTool}) which offers a scope selector
 * covering files, directories, modules, or the whole project.
 *
 * <p>This is the inspection counterpart of
 * {@link org.jfxcore.fxml.actions.CreateFxmlFileIntention}: the intention remains
 * available without enabling the inspection, while the inspection additionally
 * enables scope-wide batch fixes.
 */
public final class Fxml2CreateFxmlFileInspection extends LocalInspectionTool
        implements CleanupLocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(
            @NotNull ProblemsHolder holder, boolean isOnTheFly) {
        PsiFile file = holder.getFile();
        String name = file.getName();

        if (name.endsWith(".java")) {
            return new JavaElementVisitor() {
                @Override
                public void visitAnnotation(@NotNull PsiAnnotation annotation) {
                    checkJavaAnnotation(annotation, holder);
                }
            };
        }

        if (name.endsWith(".kt")) {
            try {
                return new org.jetbrains.kotlin.psi.KtVisitorVoid() {
                    @Override
                    public void visitAnnotationEntry(
                            @NotNull org.jetbrains.kotlin.psi.KtAnnotationEntry annotEntry) {
                        checkKotlinAnnotationEntry(annotEntry, holder);
                    }
                };
            } catch (NoClassDefFoundError ignored) {
                return PsiElementVisitor.EMPTY_VISITOR;
            }
        }

        return PsiElementVisitor.EMPTY_VISITOR;
    }

    // -----------------------------------------------------------------------
    // Java handling
    // -----------------------------------------------------------------------

    private static void checkJavaAnnotation(
            @NotNull PsiAnnotation annotation, @NotNull ProblemsHolder holder) {
        if (!Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN.equals(annotation.getQualifiedName())) return;

        PsiClass hostClass = PsiTreeUtil.getParentOfType(annotation, PsiClass.class);
        if (hostClass == null) return;
        String fqn = hostClass.getQualifiedName();
        if (fqn == null) return;

        if (Fxml2EmbedMarkupUtil.extractMarkupFromJavaAnnotation(annotation) == null) return;

        String simpleName = StringUtil.getShortName(fqn);
        VirtualFile hostVF = holder.getFile().getVirtualFile();
        if (fxmlSiblingExists(hostVF, simpleName)) return;

        holder.registerProblem(
                annotation,
                "Embedded markup can be extracted to a standalone FXML file",
                ProblemHighlightType.WEAK_WARNING,
                new CreateFxmlJavaFix());
    }

    // -----------------------------------------------------------------------
    // Kotlin handling
    // -----------------------------------------------------------------------

    private static void checkKotlinAnnotationEntry(
            @NotNull org.jetbrains.kotlin.psi.KtAnnotationEntry annotEntry,
            @NotNull ProblemsHolder holder) {
        try {
            // Quick name check to avoid import scanning for every annotation
            var shortName = annotEntry.getShortName();
            if (shortName == null || !"ComponentView".equals(shortName.getIdentifier())) return;

            // Confirm the annotation is org.jfxcore.markup.ComponentView via the import list
            PsiFile file = annotEntry.getContainingFile();
            if (!(file instanceof org.jetbrains.kotlin.psi.KtFile ktFile)) return;
            if (!isMarkupAnnotationImported(ktFile)) return;

            // Must be on a class/object
            var ktClass = PsiTreeUtil.getParentOfType(
                    annotEntry, org.jetbrains.kotlin.psi.KtClassOrObject.class);
            if (ktClass == null) return;

            // Markup must be extractable
            var args = annotEntry.getValueArguments();
            if (args.isEmpty()) return;
            var argExpr = args.getFirst().getArgumentExpression();
            if (Fxml2EmbedMarkupUtil.extractMarkupFromKotlinExpression(argExpr) == null) return;

            // No sibling FXML file must already exist
            String simpleName = ktClass.getName();
            if (simpleName == null) return;
            VirtualFile hostVF = holder.getFile().getVirtualFile();
            if (fxmlSiblingExists(hostVF, simpleName)) return;

            holder.registerProblem(
                    annotEntry,
                    "Embedded markup can be extracted to a standalone FXML file",
                    ProblemHighlightType.WEAK_WARNING,
                    new CreateFxmlKotlinFix());
        } catch (NoClassDefFoundError ignored) {
            // Kotlin plugin not present at runtime, skip
        }
    }

    private static boolean isMarkupAnnotationImported(
            @NotNull org.jetbrains.kotlin.psi.KtFile ktFile) {
        try {
            for (var imp : ktFile.getImportDirectives()) {
                if (!imp.isValidImport()) continue;
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

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when a {@code simpleName.fxml} file exists alongside {@code hostFile}.
     */
    static boolean fxmlSiblingExists(@Nullable VirtualFile hostFile, @NotNull String simpleName) {
        if (hostFile == null) return false;
        VirtualFile parent = hostFile.getParent();
        if (parent == null) return false;
        return parent.findChild(simpleName + ".fxml") != null;
    }

    // -----------------------------------------------------------------------
    // Quick-fixes
    // -----------------------------------------------------------------------

    static final class CreateFxmlJavaFix implements LocalQuickFix, BatchQuickFix {

        @Override
        public @NotNull String getName() {
            return "Create FXML file";
        }

        @Override
        public @NotNull String getFamilyName() {
            return getName();
        }

        /**
         * This fix creates a new FXML file and removes an annotation, with changes that span
         * multiple files and cannot be represented as a single-file diff preview.
         */
        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project, @NotNull ProblemDescriptor previewDescriptor) {
            return IntentionPreviewInfo.EMPTY;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement elem = descriptor.getPsiElement();
            if (!(elem instanceof PsiAnnotation annotation)) return;
            PsiFile file = elem.getContainingFile();
            if (!(file instanceof PsiJavaFile javaFile)) return;
            Fxml2EmbedMarkupUtil.applyExtractJava(project, javaFile, annotation);
        }

        /** Batch variant: processes all descriptors in a single write action. */
        @Override
        public void applyFix(@NotNull Project project,
                             CommonProblemDescriptor @NotNull [] descriptors,
                             @NotNull List<PsiElement> psiElementsToIgnore,
                             @Nullable Runnable refreshViews) {
            for (CommonProblemDescriptor descriptor : descriptors) {
                if (!(descriptor instanceof ProblemDescriptor pd)) continue;
                PsiElement elem = pd.getPsiElement();
                if (!elem.isValid()) continue;
                if (!(elem instanceof PsiAnnotation annotation)) continue;
                PsiFile file = elem.getContainingFile();
                if (!(file instanceof PsiJavaFile javaFile)) continue;
                psiElementsToIgnore.add(annotation);
                Fxml2EmbedMarkupUtil.applyExtractJava(project, javaFile, annotation);
            }
            if (refreshViews != null) refreshViews.run();
        }
    }

    static final class CreateFxmlKotlinFix implements LocalQuickFix, BatchQuickFix {

        @Override
        public @NotNull String getName() {
            return "Create FXML file";
        }

        @Override
        public @NotNull String getFamilyName() {
            return getName();
        }

        /**
         * This fix creates a new FXML file and removes an annotation, with changes that span
         * multiple files and cannot be represented as a single-file diff preview.
         */
        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project, @NotNull ProblemDescriptor previewDescriptor) {
            return IntentionPreviewInfo.EMPTY;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            try {
                PsiElement elem = descriptor.getPsiElement();
                if (!(elem instanceof org.jetbrains.kotlin.psi.KtAnnotationEntry annotEntry)) return;
                PsiFile file = elem.getContainingFile();
                Fxml2EmbedMarkupUtil.applyExtractKotlin(project, file, annotEntry);
            } catch (NoClassDefFoundError ignored) {
                // Kotlin plugin not present at runtime
            }
        }

        /** Batch variant: processes all descriptors in a single write action. */
        @Override
        public void applyFix(@NotNull Project project,
                             CommonProblemDescriptor @NotNull [] descriptors,
                             @NotNull List<PsiElement> psiElementsToIgnore,
                             @Nullable Runnable refreshViews) {
            try {
                for (CommonProblemDescriptor descriptor : descriptors) {
                    if (!(descriptor instanceof ProblemDescriptor pd)) continue;
                    PsiElement elem = pd.getPsiElement();
                    if (!elem.isValid()) continue;
                    if (!(elem instanceof org.jetbrains.kotlin.psi.KtAnnotationEntry annotEntry)) continue;
                    PsiFile file = elem.getContainingFile();
                    psiElementsToIgnore.add(annotEntry);
                    Fxml2EmbedMarkupUtil.applyExtractKotlin(project, file, annotEntry);
                }
            } catch (NoClassDefFoundError ignored) {
                // Kotlin plugin not present at runtime
            }
            if (refreshViews != null) refreshViews.run();
        }
    }
}
