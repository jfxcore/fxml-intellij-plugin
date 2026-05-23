package org.jfxcore.fxml.lang;

import com.intellij.psi.ElementManipulators;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2BindingPathResolver;

/**
 * Self-reference for the value of an {@code fx:id} attribute
 * (e.g. the {@code myButton1} text inside {@code fx:id="myButton1"}).
 *
 * <p>{@link #resolve()} returns {@code null}: the fx:id value is a <em>declaration</em>,
 * not a reference to something else. Returning null keeps this as a soft/dangling reference
 * that produces neither a "cannot resolve" error nor a
 * navigation target that would override the {@link Fxml2FxIdDeclarationProvider} declaration.
 *
 * <p>The primary purpose of this reference is to serve as the highlight-usages anchor:
 * {@link #isReferenceTo} returns {@code true} for all in-file binding-expression uses
 * and code-behind field references, so they all light up together with the declaration.
 *
 * <p>The reference is soft so IntelliJ does not show a "cannot resolve" error.
 */
public final class Fxml2FxIdReference extends PsiReferenceBase<XmlAttributeValue> {

    public Fxml2FxIdReference(@NotNull XmlAttributeValue element) {
        super(element, ElementManipulators.getValueTextRange(element), /* soft= */ true);
    }

    /**
     * Returns {@code null}: the fx:id value is a declaration, not a use-site reference.
     * Navigation from the declaration is handled by {@link Fxml2FxIdDeclarationProvider}
     * which triggers "Show Usages" via the Symbol/Declaration API.
     */
    @Override
    public @Nullable PsiElement resolve() {
        return null;
    }

    /**
     * Returns {@code true} when {@code element} is a synthetic field ({@link PsiField})
     * whose navigation element resolves back to this {@link XmlAttributeValue}.
     *
     * <p>This is needed for highlight-usages triggered from a binding use site: IntelliJ
     * resolves the use-site reference to a {@code LightFieldBuilder}, then asks every
     * reference in the file whether it {@code isReferenceTo} that {@code LightFieldBuilder}.
     * By matching on the navigation-element identity we tie all segment references (uses)
     * to the same logical declaration element.
     *
     * <p>Note: we do NOT return {@code true} for {@code element == getElement()} (the
     * {@link XmlAttributeValue} itself).  The declaration is not a "usage" of itself -
     * it is highlighted separately via the {@link Fxml2FxIdDeclarationProvider} declaration,
     * and returning {@code true} here would cause it to appear in the "Find Usages" results.
     */
    @Override
    public boolean isReferenceTo(@NotNull PsiElement element) {
        // Indirect match: element is a LightFieldBuilder pointing at our XmlAttributeValue.
        if (element instanceof PsiField field) {
            PsiElement navEl = field.getNavigationElement();
            if (getElement().getManager().areElementsEquivalent(navEl, getElement())) return true;
            // nav element may be the parent XmlAttribute rather than the XmlAttributeValue
            if (navEl instanceof XmlAttribute attr) {
                XmlAttributeValue val = attr.getValueElement();
                if (val != null && getElement().getManager().areElementsEquivalent(val, getElement())) return true;
            }
            // Real Java field from generated base class: nav element = itself.
            // Match when the field name equals our value AND the FXML file's fx:subclass
            // is or extends the field's containing class.
            if (navEl == field) {
                String idName = getElement().getValue();
                if (idName.equals(field.getName())) {
                    PsiClass containing = field.getContainingClass();
                    if (containing != null
                            && getElement().getContainingFile() instanceof XmlFile xmlFile) {
                        PsiClass codeBehind = Fxml2BindingPathResolver.resolveCodeBehindClass(xmlFile);
                        if (InheritanceUtil.isInheritorOrSelf(codeBehind, containing, true)) {
                            return true;
                        }
                    }
                }
            }
        }
        return super.isReferenceTo(element);
    }

    @Override
    public Object @NotNull [] getVariants() {
        return EMPTY_ARRAY;
    }
}
