package org.jfxcore.fxml.lang;

import com.intellij.openapi.util.TextRange;
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
 * A soft {@link com.intellij.psi.PsiReference} for one segment of an fxml2 binding path
 * (e.g. {@code viewModel} or {@code someCommand} in {@code $viewModel.someCommand}).
 * Enables Ctrl+click navigation to the property declaration.
 * Kept soft so that unresolved segments do not produce a generic "Cannot resolve" error;
 * {@link org.jfxcore.fxml.annotator.Fxml2AttributeAnnotator} provides the diagnostics.
 *
 * <p>For segments that resolve to an {@code fx:id}-injected synthetic field
 * (a {@code LightFieldBuilder} whose navigation element is an {@link XmlAttributeValue}),
 * {@link #isReferenceTo} is overridden so that "highlight usages" from the definition site
 * ({@code fx:id="myButton1"}) also lights up this binding-expression occurrence.
 */
public final class Fxml2BindingSegmentReference extends PsiReferenceBase<XmlAttributeValue> {

    private final @Nullable PsiElement myDeclaration;

    public Fxml2BindingSegmentReference(
            @NotNull XmlAttributeValue element,
            @NotNull TextRange rangeInElement,
            @Nullable PsiElement declaration) {
        super(element, rangeInElement, /* soft= */ true);
        this.myDeclaration = declaration;
    }

    @Override
    public @Nullable PsiElement resolve() {
        return myDeclaration;
    }

    /**
     * In addition to the standard resolve-based check, returns {@code true} when this
     * segment was resolved from an {@code fx:id}-injected synthetic field and {@code element}
     * is either:
     * <ul>
     *   <li>the corresponding {@link XmlAttributeValue} declaration (highlight from definition),</li>
     *   <li>another synthetic {@link PsiField} whose navigation element is the same
     *       {@link XmlAttributeValue} (highlight from another use site), or</li>
     *   <li>a real Java {@link PsiField} from a compiler-generated base class that corresponds
     *       to the same {@code fx:id}, i.e. same field name and the FXML file's {@code fx:subclass}
     *       is or extends the field's containing class (highlight from declaration when the symbol
     *       wraps the real field).</li>
     * </ul>
     *
     * <p>Cases 1 and 2 cover both directions of "highlight usages" for synthetic fields.
     * Case 3 handles the situation where {@link Fxml2FxIdDeclarationProvider} returns a symbol
     * wrapping the real Java field (for hover documentation), and the platform therefore passes
     * that real field to {@code isReferenceTo} when the cursor is on the {@code fx:id} declaration.
     */
    @Override
    public boolean isReferenceTo(@NotNull PsiElement element) {
        if (myDeclaration instanceof PsiField myField) {
            PsiElement myNav = navigationElement(myField);
            if (myNav == null) return super.isReferenceTo(element);

            // Case 1: element is the XmlAttributeValue declaration (highlight from definition).
            if (element instanceof XmlAttributeValue
                    && getElement().getManager().areElementsEquivalent(myNav, element)) {
                return true;
            }
            // Case 2: element is another LightFieldBuilder for the same fx:id (highlight from use).
            if (element instanceof PsiField otherField) {
                PsiElement otherNav = navigationElement(otherField);
                if (otherNav != null && getElement().getManager().areElementsEquivalent(myNav, otherNav)) {
                    return true;
                }
                // Case 3: element is a real Java field (nav element = itself) from a
                // compiler-generated base class that corresponds to this fx:id.
                // The platform passes this when the declaration symbol wraps the real field
                // (for hover documentation) and highlight-usages fires from the declaration site.
                if (otherNav == null && isRealFieldForSameFxId(otherField, myNav)) {
                    return true;
                }
            }
        }
        return super.isReferenceTo(element);
    }

    /**
     * Returns {@code true} when {@code realField} is a real Java field (not synthetic -
     * its nav element is itself) that corresponds to the same {@code fx:id} as this segment:
     * same field name, and the FXML file containing {@code fxIdNav} declares an
     * {@code fx:subclass} that is {@code realField}'s containing class or a subclass of it.
     */
    private static boolean isRealFieldForSameFxId(
            @NotNull PsiField realField, @NotNull PsiElement fxIdNav) {
        // Name must match.
        if (!realField.getName().equals(
                fxIdNav instanceof XmlAttributeValue av ? av.getValue() : null)) {
            return false;
        }
        PsiClass realContaining = realField.getContainingClass();
        if (realContaining == null) return false;
        // The fxIdNav must be in an XmlFile with a matching fx:subclass.
        if (!(fxIdNav.getContainingFile() instanceof XmlFile xmlFile)) return false;
        PsiClass codeBehind = Fxml2BindingPathResolver.resolveCodeBehindClass(xmlFile);
        return InheritanceUtil.isInheritorOrSelf(codeBehind, realContaining, true);
    }

    /**
     * Returns the effective navigation element of a synthetic fx:id field:
     * the {@link XmlAttributeValue} if the navigation element is an {@link XmlAttributeValue},
     * or the value element of the parent {@link XmlAttribute} if the navigation element is
     * an {@link XmlAttribute}.  Returns {@code null} if neither applies (i.e. the field is
     * not an fx:id synthetic field).
     */
    private static @Nullable PsiElement navigationElement(@NotNull PsiField field) {
        PsiElement navEl = field.getNavigationElement();
        if (navEl instanceof XmlAttributeValue) return navEl;
        if (navEl instanceof XmlAttribute attr) return attr.getValueElement();
        return null;
    }

    @Override
    public boolean isSoft() {
        return true;
    }
}
