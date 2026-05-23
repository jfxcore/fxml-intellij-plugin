package org.jfxcore.fxml.annotator;

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.BatchQuickFix;
import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.QuickFix;
import com.intellij.codeInspection.XmlSuppressableInspectionTool;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.codeinsight.Fxml2AddImportFix;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;
import org.jfxcore.fxml.resolve.Fxml2TypeArgumentInferrer;

import java.util.ArrayList;
import java.util.List;

/**
 * Reports element tags that resolve to generic classes when {@code fx:typeArguments}
 * is not specified.
 *
 * <p>When a generic class is instantiated without {@code fx:typeArguments}, the compiler
 * treats it as a raw type. From the compiler documentation:
 * <blockquote>
 *   If this attribute is omitted on a generic class, it is used as a raw type.
 *   Note that using raw types affects compile-time type safety and should be avoided.
 * </blockquote>
 *
 * <p>The inspection issues a {@code WARNING} on the opening tag of any generic-class
 * instantiation that lacks a {@code fx:typeArguments} attribute. When surrounding
 * context fixes every type variable on the tag's class, the warning carries an
 * "Infer fx:typeArguments" quick-fix that writes the inferred value into the tag
 * (see {@link Fxml2TypeArgumentInferrer}).
 */
public final class Fxml2RawTypeInspection extends XmlSuppressableInspectionTool {

    private static final String FIX_FAMILY = "Infer fx:typeArguments";

    @Override
    public ProblemDescriptor @Nullable [] checkFile(
            @NotNull PsiFile file, @NotNull InspectionManager manager, boolean isOnTheFly) {
        if (!Fxml2FileType.isFxml2(file)) {
            return null;
        }
        XmlFile xmlFile = (XmlFile) file;

        List<ProblemDescriptor> problems = new ArrayList<>();

        for (XmlTag tag : PsiTreeUtil.findChildrenOfType(xmlFile, XmlTag.class)) {
            // Only check plain class-instantiation tags (no dot in name, not in fx: namespace).
            String localName = tag.getLocalName();
            if (localName.isEmpty() || localName.contains(".")) continue;
            if (Fxml2ImportResolver.FXML2_NAMESPACE.equals(tag.getNamespace())) continue;

            // Resolve to a class.
            PsiClass tagClass = Fxml2ImportResolver.resolve(localName, xmlFile);
            if (tagClass == null) continue;

            // Check if the class has type parameters.
            PsiTypeParameter[] typeParams = tagClass.getTypeParameters();
            if (typeParams.length == 0) continue;

            // Check if fx:typeArguments is present on this tag.
            boolean hasTypeArguments = tag.getAttribute("typeArguments",
                    Fxml2ImportResolver.FXML2_NAMESPACE) != null;
            if (hasTypeArguments) continue;

            // Compute inference once per offending tag. The fix is attached only when
            // every type variable is bound to a concrete, non-wildcard type.
            Fxml2TypeArgumentInferrer.InferenceResult inferred =
                    Fxml2TypeArgumentInferrer.infer(tag, tagClass);
            LocalQuickFix fix = inferred != null
                    ? new InferTypeArgumentsFix(inferred.renderedArgs(), inferred.importsToAdd())
                    : null;

            problems.add(manager.createProblemDescriptor(
                    tag,
                    "Generic class '" + localName + "' is used as a raw type; " +
                    "consider adding fx:typeArguments",
                    fix,
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    isOnTheFly));
        }

        return problems.isEmpty() ? null : problems.toArray(ProblemDescriptor.EMPTY_ARRAY);
    }

    /**
     * Writes a pre-computed {@code fx:typeArguments} value onto the offending tag and
     * inserts any {@code <?import?>} processing instructions needed for the rendered
     * value's simple names to resolve.
     *
     * <p>The rendered value and the import list are captured at inspection time so that
     * {@code applyFix} is deterministic and does not re-resolve.  Implements
     * {@link BatchQuickFix} so "Fix all in file" and scope-wide application are
     * available from Alt+Enter.
     */
    static final class InferTypeArgumentsFix implements LocalQuickFix, BatchQuickFix {

        private final String renderedArgs;
        private final List<String> importsToAdd;

        InferTypeArgumentsFix(@NotNull String renderedArgs, @NotNull List<String> importsToAdd) {
            this.renderedArgs = renderedArgs;
            this.importsToAdd = List.copyOf(importsToAdd);
        }

        @Override
        public @NotNull String getName() {
            return "Infer fx:typeArguments=\"" + renderedArgs + "\"";
        }

        @Override
        public @NotNull String getFamilyName() { return FIX_FAMILY; }

        @Override
        public @NotNull PsiElement getElementToMakeWritable(@NotNull PsiFile file) {
            InjectedLanguageManager ilm = InjectedLanguageManager.getInstance(file.getProject());
            if (ilm.isInjectedFragment(file)) {
                PsiElement host = file.getContext();
                if (host != null) return host;
            }
            return file;
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project, @NotNull ProblemDescriptor previewDescriptor) {
            return IntentionPreviewInfo.EMPTY;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement elem = descriptor.getPsiElement();
            if (!(elem instanceof XmlTag tag)) return;
            PsiFile containing = tag.getContainingFile();
            if (containing instanceof XmlFile xmlFile) {
                for (String fqn : importsToAdd) {
                    Fxml2AddImportFix.doInsertImportPsi(project, xmlFile, fqn);
                }
            }
            // The fxml2 namespace prefix is determined by the file's own xmlns:fx mapping;
            // setAttribute(local, ns, value) handles the prefix lookup.
            tag.setAttribute("typeArguments", Fxml2ImportResolver.FXML2_NAMESPACE, renderedArgs);
        }

        @Override
        public void applyFix(
                @NotNull Project project,
                CommonProblemDescriptor @NotNull [] descriptors,
                @NotNull List<PsiElement> psiElementsToIgnore,
                @Nullable Runnable refreshViews) {
            // IntelliJ invokes this method on a single representative fix instance
            // (the one attached to the first descriptor) but passes the descriptors of
            // every problem in scope.  Each tag has its own inferred arguments and
            // import set baked into its own fix, so we must dispatch back to the fix
            // attached to each descriptor rather than reusing `this`.
            for (CommonProblemDescriptor descriptor : descriptors) {
                if (!(descriptor instanceof ProblemDescriptor pd)) continue;
                InferTypeArgumentsFix perDescriptorFix = findOwnFix(pd);
                if (perDescriptorFix == null) continue;
                perDescriptorFix.applyFix(project, pd);
                PsiElement elem = pd.getPsiElement();
                if (elem != null) psiElementsToIgnore.add(elem);
            }
            if (refreshViews != null) refreshViews.run();
        }

        private static @Nullable InferTypeArgumentsFix findOwnFix(@NotNull ProblemDescriptor pd) {
            QuickFix<?>[] fixes = pd.getFixes();
            if (fixes == null) return null;
            for (QuickFix<?> fix : fixes) {
                if (fix instanceof InferTypeArgumentsFix infer) return infer;
            }
            return null;
        }
    }
}
