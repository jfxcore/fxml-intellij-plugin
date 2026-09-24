package org.jfxcore.fxml.lang;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiInvalidElementAccessException;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiReference;
import com.intellij.psi.XmlRecursiveElementVisitor;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;

/** Matches FXML references against declarations checked by unused-symbol inspections. */
final class Fxml2ImplicitUsageReferences {
    private Fxml2ImplicitUsageReferences() {}

    static boolean isReferencedInXmlFile(@NotNull PsiElement element, @NotNull XmlFile xmlFile) {
        boolean[] found = {false};
        Fxml2PropertyAttributeSearcher.collectMatchingAttributes(
                xmlFile, element, ref -> { found[0] = true; return false; });
        if (found[0]) return true;

        xmlFile.accept(new XmlRecursiveElementVisitor() {
            @Override
            public void visitXmlAttributeValue(@NotNull XmlAttributeValue attrValue) {
                if (found[0]) return;
                for (PsiReference ref : attrValue.getReferences()) {
                    if (!(ref instanceof Fxml2BindingSegmentReference)
                            && !(ref instanceof Fxml2AttributeValueReference)) continue;
                    PsiElement resolved = ref.resolve();
                    if (resolved == null) continue;
                    PsiManager manager = element.getManager();
                    if (manager.areElementsEquivalent(element, resolved)) {
                        found[0] = true;
                        return;
                    }
                    try {
                        PsiElement navigation = resolved.getNavigationElement();
                        if (navigation != null && navigation != resolved
                                && manager.areElementsEquivalent(element, navigation)) {
                            found[0] = true;
                            return;
                        }
                    } catch (PsiInvalidElementAccessException ignored) {
                        // The reference was invalidated during resolution.
                    }
                }
            }
        });
        return found[0];
    }
}
