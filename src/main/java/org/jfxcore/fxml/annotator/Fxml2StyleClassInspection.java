package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiReference;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.lang.Fxml2CssUtil;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.lang.Fxml2StyleClassReference;

/**
 * Inspection that warns when a {@code styleClass} token cannot be resolved to any
 * {@code .name} CSS class selector in {@code .css} files on the project scope.
 *
 * <p>Operates at the {@link XmlAttributeValue} level, iterating each
 * {@link Fxml2StyleClassReference} placed there by {@code StyleClassReferenceProvider}.
 * Problems are registered with the per-class-name sub-range (the reference's
 * {@code rangeInElement}) so the warning underline is tight, covering only the unknown class name
 * is highlighted, not the whole attribute value.
 */
public final class Fxml2StyleClassInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new PsiElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (!(element instanceof XmlAttributeValue attrVal)) return;
                if (!(attrVal.getContainingFile() instanceof XmlFile xmlFile)) return;
                if (!Fxml2FileType.isFxml2(xmlFile)) return;
                if (!(attrVal.getParent() instanceof XmlAttribute attr)) return;
                if (!Fxml2CssUtil.isStyleClassAttribute(attr)) return;

                for (PsiReference ref : attrVal.getReferences()) {
                    if (!(ref instanceof Fxml2StyleClassReference svr)) continue;
                    if (svr.resolve() == null) {
                        holder.registerProblem(attrVal, ref.getRangeInElement(),
                                "CSS class '" + ref.getRangeInElement().substring(attrVal.getText()) + "' cannot be resolved");
                    }
                }
            }
        };
    }
}
