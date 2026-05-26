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
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlProcessingInstruction;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.lang.Fxml2EmbedMarkupUtil;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;

import java.util.List;

/**
 * Inspection that reports {@code <?import?>} processing instructions inside embedded
 * FXML markup ({@code @ComponentView} annotation values) that could be moved to the
 * host Java or Kotlin file as regular import statements.
 *
 * <h2>Rationale</h2>
 * When FXML markup is embedded in a Java or Kotlin file via {@code @ComponentView}, the
 * user may choose to place class imports either as {@code <?import?>} PIs inside the
 * markup or as top-level code imports in the host file. Some projects prefer all imports
 * to live in the host file's import section so that the IDE's import management (import
 * optimizer, auto-import, etc.) covers every class reference uniformly.
 *
 * <h2>Conditions for reporting</h2>
 * A {@code <?import?>} PI inside an embedded markup block is reported when:
 * <ol>
 *   <li>The host file has at least one {@code @ComponentView}-annotated class whose
 *       injected FXML fragment contains the PI.</li>
 *   <li>The PI imports a specific (non-wildcard) fully-qualified class name.</li>
 *   <li>The host Java or Kotlin file does not already have an import for that FQN
 *       (neither an exact match nor a covering wildcard).</li>
 *   <li>No conflicting import with the same simple name but a different FQN exists in
 *       the host file.</li>
 * </ol>
 *
 * <p>Problems are reported on the {@code @ComponentView} annotation element in the host
 * Java or Kotlin file.  This keeps the problem descriptor's PSI element in the host
 * file's virtual file so that quick-fixes remain applicable when invoked from the host
 * file's editor context.
 *
 * <p>Disabled by default; opt in via
 * <em>Settings -> Editor -> Inspections -> FXML/2</em>.
 * When enabled, <em>Fix all in file</em> and <em>Fix all in scope</em> are available.
 */
public final class Fxml2PreferCodeImportInspection extends LocalInspectionTool {

    /** Short name used for {@link com.intellij.codeInsight.daemon.HighlightDisplayKey} lookups. */
    public static final String SHORT_NAME = "Fxml2PreferCodeImport";

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

        return new JavaElementVisitor() {
            @Override
            public void visitClass(@NotNull PsiClass psiClass) {
                if (!psiClass.hasAnnotation(Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN)) return;

                XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(psiClass);
                if (xmlFile == null) return;

                var doc = xmlFile.getDocument();
                if (doc == null) return;
                XmlTag wrapperRoot = doc.getRootTag();
                if (wrapperRoot == null || !Fxml2EmbeddedUtil.isWrapperRoot(wrapperRoot)) return;

                PsiAnnotation annotation =
                        psiClass.getAnnotation(Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN);
                if (annotation == null) return;

                for (PsiElement child : wrapperRoot.getChildren()) {
                    if (child instanceof XmlTag) break; // imports precede element content
                    if (!(child instanceof XmlProcessingInstruction pi)) continue;

                    String target = Fxml2ImportResolver.getImportTarget(pi);
                    if (target == null) continue;
                    target = target.trim();
                    if (target.endsWith(".*")) continue; // wildcards not handled here

                    String fqn = target;

                    boolean alreadyImported =
                            Fxml2ImportPlacementInspectionHelper.javaFileAlreadyImports(fqn, javaFile);
                    boolean hasConflict =
                            Fxml2ImportPlacementInspectionHelper.javaFileHasConflictingSimpleName(fqn, javaFile);
                    if (alreadyImported || hasConflict) continue;

                    // Report on the annotation element (host Java file) so that
                    // QuickFixWrapper.isAvailable() passes when the fix is invoked
                    // from the host file's editor context.
                    holder.registerProblem(
                            annotation,
                            "<?import " + fqn + "?> can be moved to the host file's import section",
                            ProblemHighlightType.WEAK_WARNING,
                            new MoveToCodeFix(psiClass, fqn));
                }
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
            public void visitClassOrObject(
                    @NotNull org.jetbrains.kotlin.psi.KtClassOrObject ktClass) {
                boolean hasAnnotation = ktClass.getAnnotationEntries().stream().anyMatch(e -> {
                    var sn = e.getShortName();
                    return sn != null && "ComponentView".equals(sn.getIdentifier());
                });
                if (!hasAnnotation) return;

                var lightClass = org.jetbrains.kotlin.asJava.KotlinAsJavaSupport
                        .getInstance(ktFile.getProject())
                        .getLightClass(ktClass);
                if (lightClass == null) return;

                XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(lightClass);
                if (xmlFile == null) return;

                var doc = xmlFile.getDocument();
                if (doc == null) return;
                XmlTag wrapperRoot = doc.getRootTag();
                if (wrapperRoot == null || !Fxml2EmbeddedUtil.isWrapperRoot(wrapperRoot)) return;

                org.jetbrains.kotlin.psi.KtAnnotationEntry annotEntry = null;
                for (var e : ktClass.getAnnotationEntries()) {
                    var sn = e.getShortName();
                    if (sn != null && "ComponentView".equals(sn.getIdentifier())) {
                        annotEntry = e;
                        break;
                    }
                }
                if (annotEntry == null) return;

                for (PsiElement child : wrapperRoot.getChildren()) {
                    if (child instanceof XmlTag) break;
                    if (!(child instanceof XmlProcessingInstruction pi)) continue;

                    String target = Fxml2ImportResolver.getImportTarget(pi);
                    if (target == null) continue;
                    target = target.trim();
                    if (target.endsWith(".*")) continue;

                    String fqn = target;

                    boolean alreadyImported =
                            Fxml2ImportPlacementInspectionHelper.kotlinFileAlreadyImports(fqn, ktFile);
                    boolean hasConflict =
                            Fxml2ImportPlacementInspectionHelper.kotlinFileHasConflictingSimpleName(fqn, ktFile);
                    if (alreadyImported || hasConflict) continue;

                    holder.registerProblem(
                            annotEntry,
                            "<?import " + fqn + "?> can be moved to the host file's import section",
                            ProblemHighlightType.WEAK_WARNING,
                            new MoveToCodeFix(lightClass, fqn));
                }
            }
        };
    }

    // -----------------------------------------------------------------------
    // Quick-fix
    // -----------------------------------------------------------------------

    /**
     * Quick-fix that moves a {@code <?import?>} PI from embedded FXML markup to the
     * host Java or Kotlin file as a regular import statement.
     *
     * <p>The fix:
     * <ol>
     *   <li>Adds the import to the host Java or Kotlin file via
     *       {@link Fxml2EmbedMarkupUtil#addJavaImport} or
     *       {@link Fxml2EmbedMarkupUtil#addKotlinImport}.</li>
     *   <li>Removes the {@code <?import?>} PI from the embedded markup.</li>
     * </ol>
     *
     * <p>No change is made if the host file already contains the import (idempotent).
     * The fix stores a {@link SmartPsiElementPointer} to the {@code @ComponentView}-annotated
     * class and the FQN, and re-locates the PI in the injected XML at apply-time.
     */
    static final class MoveToCodeFix implements LocalQuickFix, BatchQuickFix {

        private final @NotNull SmartPsiElementPointer<PsiClass> myClassPointer;
        private final @NotNull String myFqn;

        MoveToCodeFix(@NotNull PsiClass psiClass, @NotNull String fqn) {
            this.myClassPointer = SmartPointerManager.createPointer(psiClass);
            this.myFqn = fqn;
        }

        @Override
        public @NotNull String getName() {
            return "Move to Java/Kotlin import";
        }

        @Override
        public @NotNull String getFamilyName() {
            return getName();
        }

        /**
         * This fix modifies both the embedded XML content (PI deletion) and the host
         * Java/Kotlin source file (import addition), spanning multiple logical documents.
         * No single-file diff preview is possible.
         */
        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project, @NotNull ProblemDescriptor previewDescriptor) {
            return IntentionPreviewInfo.EMPTY;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiClass psiClass = myClassPointer.getElement();
            if (psiClass == null || !psiClass.isValid()) return;
            applyToClass(project, psiClass, myFqn);
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
                applyFix(project, pd);
            }
            if (refreshViews != null) refreshViews.run();
        }

        /**
         * Looks up the {@code <?import fqn?>} PI inside the current injected XML for
         * {@code psiClass}, adds the import to the host file, and removes the PI.
         */
        private static void applyToClass(
                @NotNull Project project,
                @NotNull PsiClass psiClass,
                @NotNull String fqn) {

            XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(psiClass);
            if (xmlFile == null) return;

            var xmlDoc = xmlFile.getDocument();
            if (xmlDoc == null) return;
            XmlTag wrapperRoot = xmlDoc.getRootTag();
            if (wrapperRoot == null) return;

            // Locate the PI for fqn.
            XmlProcessingInstruction piToDelete = null;
            for (PsiElement child : wrapperRoot.getChildren()) {
                if (child instanceof XmlTag) break;
                if (!(child instanceof XmlProcessingInstruction pi)) continue;
                String target = Fxml2ImportResolver.getImportTarget(pi);
                if (target != null && fqn.equals(target.trim())) {
                    piToDelete = pi;
                    break;
                }
            }
            if (piToDelete == null) return;

            PsiFile hostFile = psiClass.getContainingFile();

            // Add the import to the host file. After PSI modification, commit the document
            // so that the subsequent direct document manipulation (PI deletion) does not
            // conflict with postponed PSI reformatting.
            if (hostFile instanceof PsiJavaFile javaFile) {
                if (Fxml2ImportPlacementInspectionHelper.javaFileAlreadyImports(fqn, javaFile)) return;
                if (Fxml2ImportPlacementInspectionHelper.javaFileHasConflictingSimpleName(fqn, javaFile)) return;
                Fxml2EmbedMarkupUtil.addJavaImport(project, javaFile, fqn);
                // Flush postponed PSI formatting to unblock the host document for direct edits.
                var hostDoc = PsiDocumentManager.getInstance(project).getDocument(javaFile);
                if (hostDoc != null) {
                    PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(hostDoc);
                }
            } else {
                if (Fxml2ImportPlacementInspectionHelper.kotlinFileAlreadyImports(fqn, hostFile)) return;
                if (Fxml2ImportPlacementInspectionHelper.kotlinFileHasConflictingSimpleName(fqn, hostFile)) return;
                Fxml2EmbedMarkupUtil.addKotlinImport(project, hostFile, fqn);
                var hostDoc = PsiDocumentManager.getInstance(project).getDocument(hostFile);
                if (hostDoc != null) {
                    PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(hostDoc);
                }
            }

            // Re-locate the PI after committing PSI changes (the document text has shifted
            // due to the new import line, so all injected-PSI offsets may have changed).
            xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(psiClass);
            if (xmlFile == null) return;
            xmlDoc = xmlFile.getDocument();
            if (xmlDoc == null) return;
            wrapperRoot = xmlDoc.getRootTag();
            if (wrapperRoot == null) return;

            piToDelete = null;
            for (PsiElement child : wrapperRoot.getChildren()) {
                if (child instanceof XmlTag) break;
                if (!(child instanceof XmlProcessingInstruction pi)) continue;
                String target = Fxml2ImportResolver.getImportTarget(pi);
                if (target != null && fqn.equals(target.trim())) {
                    piToDelete = pi;
                    break;
                }
            }
            if (piToDelete == null) return;

            // Remove the <?import?> PI from the embedded markup.
            Fxml2ImportPlacementInspectionHelper.deleteMarkupImport(piToDelete, project);
        }
    }
}
