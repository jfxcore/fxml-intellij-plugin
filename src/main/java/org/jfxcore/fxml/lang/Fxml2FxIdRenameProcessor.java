package org.jfxcore.fxml.lang;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiReference;
import com.intellij.psi.XmlRecursiveElementVisitor;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.refactoring.rename.RenamePsiElementProcessor;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Rename processor for {@code fx:id} attribute values in FXML 2.0 files.
 *
 * <p>When an {@code fx:id} value is renamed, this processor:
 * <ol>
 *   <li>Updates the {@code fx:id} attribute value itself (via the standard
 *       {@link com.intellij.psi.ElementManipulator}).</li>
 *   <li>Collects all binding-path segment references in the same FXML 2.0 file that
 *       reference the same {@code fx:id}, so the rename framework calls
 *       {@link PsiReference#handleElementRename} on each to update the binding
 *       expression in-place.</li>
 * </ol>
 */
public final class Fxml2FxIdRenameProcessor extends RenamePsiElementProcessor {

    @Override
    public boolean canProcessElement(@NotNull PsiElement element) {
        if (!(element instanceof XmlAttributeValue attrVal)) return false;
        if (!(attrVal.getParent() instanceof XmlAttribute attr)) return false;
        if (!"id".equals(attr.getLocalName())) return false;
        if (!Fxml2ImportResolver.FXML2_NAMESPACE.equals(attr.getNamespace())) return false;
        if (!(attrVal.getContainingFile() instanceof XmlFile xmlFile)) return false;
        return Fxml2FileType.isFxml2(xmlFile);
    }

    /**
     * Returns all binding-path segment references in the same FXML2 file that reference
     * this {@code fx:id}.  The rename framework will call
     * {@link PsiReference#handleElementRename} on each, updating the binding expression.
     *
     * <p>The {@code fx:id} attribute value itself is <em>not</em> included: it is
     * renamed directly by the IntelliJ rename framework via the element manipulator,
     * avoiding any double-update.
     */
    @Override
    public @NotNull Collection<PsiReference> findReferences(
            @NotNull PsiElement element,
            @NotNull SearchScope searchScope,
            boolean searchInCommentsAndStrings) {

        XmlAttributeValue attrVal = (XmlAttributeValue) element;
        String idName = attrVal.getValue();
        if (idName.isBlank()) {
            return super.findReferences(element, searchScope, searchInCommentsAndStrings);
        }

        XmlFile xmlFile = (XmlFile) attrVal.getContainingFile();
        List<PsiReference> refs = new ArrayList<>();

        xmlFile.accept(new XmlRecursiveElementVisitor() {
            @Override
            public void visitXmlAttributeValue(@NotNull XmlAttributeValue val) {
                super.visitXmlAttributeValue(val);
                // Skip the fx:id declaration itself: renamed via RenameUtil.doRename.
                if (val == attrVal) return;
                for (PsiReference ref : val.getReferences()) {
                    if (!(ref instanceof Fxml2BindingSegmentReference segRef)) continue;
                    // Only consider segments whose canonical text matches the fx:id name.
                    if (!idName.equals(segRef.getCanonicalText())) continue;
                    // Verify this segment's resolved LightFieldBuilder navigates to our attrVal.
                    PsiElement resolved = segRef.resolve();
                    if (!(resolved instanceof PsiField field)) continue;
                    PsiElement navEl = field.getNavigationElement();
                    if (attrVal.getManager().areElementsEquivalent(navEl, attrVal)) {
                        refs.add(segRef);
                    }
                }
            }
        });

        return refs;
    }
}
