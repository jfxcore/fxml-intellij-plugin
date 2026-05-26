// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.BatchQuickFix;
import com.intellij.codeInspection.CleanupLocalInspectionTool;
import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.lang.Fxml2EmbedMarkupUtil;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.jfxcore.fxml.lang.Fxml2FileType;

import java.util.List;

/**
 * Inspection that reports standalone FXML files which have a code-behind sibling
 * and offers a quick-fix to embed the markup into the code-behind class as a
 * {@code @ComponentView} annotation.
 *
 * <p>Disabled by default; opt in via
 * <em>Settings -> Editor -> Inspections -> FXML/2</em>.
 * When enabled, <em>Fix all in file</em> and <em>Fix all in scope</em>
 * become available to batch-embed FXML files into their code-behind classes.
 * The inspection also participates in <em>Code -> Code Cleanup</em>
 * (via {@link CleanupLocalInspectionTool}) which offers a scope selector
 * covering files, directories, modules, or the whole project.
 *
 * <p>This is the inspection counterpart of
 * {@link org.jfxcore.fxml.actions.EmbedMarkupInCodeBehindIntention}:
 * the intention remains available without enabling the inspection, while the
 * inspection additionally enables scope-wide batch fixes.
 */
public final class Fxml2EmbedMarkupInspection extends LocalInspectionTool
        implements CleanupLocalInspectionTool {

    @Override
    public ProblemDescriptor @Nullable [] checkFile(
            @NotNull PsiFile file, @NotNull InspectionManager manager, boolean isOnTheFly) {

        if (!(file instanceof XmlFile xmlFile)) return null;
        if (!Fxml2FileType.isFxml2(file)) return null;
        if (Fxml2EmbeddedUtil.isEmbeddedFxml2(file)) return null;

        var xmlDoc = xmlFile.getDocument();
        if (xmlDoc == null) return null;
        XmlTag rootTag = xmlDoc.getRootTag();
        if (rootTag == null) return null;

        String fxClass = rootTag.getAttributeValue("fx:subclass");
        if (StringUtil.isEmptyOrSpaces(fxClass)) return null;

        String simpleName = StringUtil.getShortName(fxClass);
        VirtualFile fxmlVF = file.getVirtualFile();
        if (Fxml2EmbedMarkupUtil.findCodeBehindFile(fxmlVF, simpleName) == null) return null;

        // Highlight the fx:subclass attribute value as the problem site
        XmlAttribute fxClassAttr = rootTag.getAttribute("fx:subclass");
        PsiElement element = (fxClassAttr != null && fxClassAttr.getValueElement() != null)
                ? fxClassAttr.getValueElement()
                : rootTag;

        return new ProblemDescriptor[] {
            manager.createProblemDescriptor(
                element,
                "Markup can be embedded in code-behind file",
                new EmbedMarkupFix(),
                ProblemHighlightType.WEAK_WARNING,
                isOnTheFly)
        };
    }

    // -----------------------------------------------------------------------
    // Quick-fix
    // -----------------------------------------------------------------------

    static final class EmbedMarkupFix implements LocalQuickFix, BatchQuickFix {

        @Override
        public @NotNull String getName() {
            return "Embed markup in code-behind file";
        }

        @Override
        public @NotNull String getFamilyName() {
            return getName();
        }

        /**
         * This fix deletes the FXML file and rewrites the code-behind, with changes that span
         * multiple files and cannot be represented as a single-file diff preview.
         */
        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project, @NotNull ProblemDescriptor previewDescriptor) {
            return IntentionPreviewInfo.EMPTY;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiFile file = descriptor.getPsiElement().getContainingFile();
            if (!(file instanceof XmlFile xmlFile)) return;
            Fxml2EmbedMarkupUtil.applyEmbed(project, xmlFile);
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
                PsiFile file = elem.getContainingFile();
                if (!(file instanceof XmlFile xmlFile)) continue;
                psiElementsToIgnore.add(elem);
                Fxml2EmbedMarkupUtil.applyEmbed(project, xmlFile);
            }
            if (refreshViews != null) refreshViews.run();
        }
    }
}
