package org.jfxcore.fxml.lang;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A <em>soft</em>, <em>poly-variant</em> reference on a single CSS class-name token inside
 * a {@code styleClass} attribute value.
 *
 * <p>Anchored on the enclosing {@link XmlAttributeValue} with a tight {@code rangeInElement}
 * that covers only the class-name characters (e.g. {@code [1,7)} for {@code "accent"} in
 * {@code "accent, elevated-1"}).
 *
 * <p>Navigation and hover highlighting are handled by
 * {@link Fxml2StyleClassImplicitReferenceProvider}, which wraps one of these references in a
 * {@link com.intellij.model.psi.PsiSymbolReference} fed into IntelliJ's
 * {@code allReferencesAround} pipeline.  That pipeline uses the tight
 * {@code rangeInElement} for the hover underline and produces a "Choose Target" popup when
 * multiple CSS files match.
 *
 * <p>Implements {@link PsiPolyVariantReference} so that
 * {@link Fxml2StyleClassImplicitReferenceProvider} can call
 * {@link #multiResolve} to obtain all matching {@link CssSelectorElement}s across all CSS
 * files.
 *
 * <p>The reference is soft so that an unresolvable token does not produce a generic
 * "Cannot resolve" IDE error: {@code Fxml2StyleClassInspection} handles the warning.
 */
@SuppressWarnings("UnstableApiUsage")
public final class Fxml2StyleClassReference extends PsiReferenceBase<XmlAttributeValue>
        implements PsiPolyVariantReference {

    private final @NotNull String myClassName;
    private final @NotNull XmlFile myXmlFile;
    /** Lowercase simple class name of the owner tag (e.g. {@code "notification"}), or null. */
    private final @Nullable String myCssTypeName;

    public Fxml2StyleClassReference(
            @NotNull XmlAttributeValue element,
            @NotNull TextRange rangeInElement,
            @NotNull String className,
            @NotNull XmlFile xmlFile,
            @Nullable String cssTypeName) {
        super(element, rangeInElement, /* soft= */ true);
        this.myClassName   = className;
        this.myXmlFile     = xmlFile;
        this.myCssTypeName = cssTypeName;
    }

    /** Returns the CSS class name this reference targets (without the leading dot). */
    public @NotNull String getClassName() {
        return myClassName;
    }

    // -----------------------------------------------------------------------
    // PsiPolyVariantReference: used by GotoDeclarationHandler for multi-file chooser
    // -----------------------------------------------------------------------

    @Override
    public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
        GlobalSearchScope scope = GlobalSearchScope.allScope(myXmlFile.getProject());
        List<CssSelectorElement> all = Fxml2CssUtil.findAllCssSelectorElements(
                myClassName, myCssTypeName, myXmlFile.getProject(), scope);
        if (all.isEmpty()) return ResolveResult.EMPTY_ARRAY;
        return all.stream()
                .map(el -> (ResolveResult) new SimpleResolveResult(el))
                .toArray(ResolveResult[]::new);
    }

    // -----------------------------------------------------------------------
    // PsiReference.resolve(): single best result; used for hover tooltip and
    // by fromTargetEvaluator for single-file navigation with tight hover underline
    // -----------------------------------------------------------------------

    @Override
    public @Nullable PsiElement resolve() {
        ResolveResult[] results = multiResolve(false);
        return results.length > 0 ? results[0].getElement() : null;
    }

    @Override
    public boolean isSoft() {
        return true;
    }

    // -----------------------------------------------------------------------
    // Minimal ResolveResult implementation
    // -----------------------------------------------------------------------

    private record SimpleResolveResult(@NotNull PsiElement element) implements ResolveResult {
        @Override public PsiElement getElement() { return element; }
        @Override public boolean isValidResult()  { return true; }
    }
}
