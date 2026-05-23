package org.jfxcore.fxml.lang;

import com.intellij.codeInsight.daemon.ImplicitUsageProvider;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiInvalidElementAccessException;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiReference;
import com.intellij.psi.XmlRecursiveElementVisitor;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resolve.Fxml2PropertyNameUtil;

/**
 * Suppresses false-positive "Field/Method/Function 'X' is never used" warnings for
 * fields, property-accessor methods, and Kotlin functions/properties that are referenced
 * in <em>standalone</em> FXML2 files via binding expressions such as
 * {@code {fx:Observe vm.labelText}}.
 *
 * <h3>Why is this needed?</h3>
 * <p>For standalone FXML2 files, {@link Fxml2UseScopeEnlarger} adds {@code .fxml}/
 * {@code .fxml2} files to the search scope for {@link com.intellij.psi.PsiField} and
 * {@link com.intellij.psi.PsiMethod} elements.  This allows IntelliJ's unused-declaration
 * analysis (which uses {@link com.intellij.psi.search.searches.ReferencesSearch}) to find
 * binding-segment references in FXML2 files for <em>Java</em> elements.
 *
 * <p>However, for <em>Kotlin</em> elements (e.g. a {@code KtNamedFunction} for
 * {@code fun labelTextProperty()}, or a {@code KtProperty} for {@code val message}),
 * Kotlin's unused-declaration inspection queries the element's {@code useScope} using the
 * Kotlin PSI element directly, not its Java {@code KtLightMethod} wrapper.  Since
 * {@link Fxml2UseScopeEnlarger} only handles {@link com.intellij.psi.PsiField} and
 * {@link com.intellij.psi.PsiMethod}, FXML2 files are never added to the scope for Kotlin
 * elements, and the search finds nothing.
 *
 * <p>This provider bridges that gap by searching standalone FXML2 files directly via the
 * word index and checking whether any {@link Fxml2BindingSegmentReference} resolves to the
 * element (or its Kotlin light-class equivalent).
 *
 * <p>Both Java ({@link com.intellij.psi.PsiField}, {@link com.intellij.psi.PsiMethod}) and
 * Kotlin ({@code KtNamedFunction}, {@code KtProperty}) elements are handled.  Java elements
 * serve as an additional safety net in case a future IntelliJ version changes how
 * unused-declaration analysis interacts with use-scope enlargers.
 *
 * <p>Complements {@link Fxml2EmbeddedImplicitUsageProvider}, which covers references in
 * <em>embedded</em> FXML2 markup (inside {@code @ComponentView} annotations).  Standalone
 * and embedded markup are distinct: standalone files are indexed by the word index and the
 * injected fragments are not, so the two providers are both necessary.
 */
public final class Fxml2StandaloneImplicitUsageProvider implements ImplicitUsageProvider {

    @Override
    public boolean isImplicitUsage(@NotNull PsiElement element) {
        return isReferencedInStandaloneFxml(element);
    }

    @Override
    public boolean isImplicitRead(@NotNull PsiElement element) {
        return isReferencedInStandaloneFxml(element);
    }

    @Override
    public boolean isImplicitWrite(@NotNull PsiElement element) {
        return false;
    }

    // -----------------------------------------------------------------------
    // Core logic
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when {@code element} is a Java/Kotlin field or method/function
     * and at least one standalone FXML2 file in the project contains either:
     * <ul>
     *   <li>a {@link Fxml2BindingSegmentReference} that resolves to this element, or</li>
     *   <li>an XML attribute whose {@link org.jfxcore.fxml.descriptors.Fxml2PropertyAttributeDescriptor#getDeclaration()}
     *       resolves to this element (e.g. {@code formatter="$doubleFormatter"}).</li>
     * </ul>
     *
     * <p>Uses the word index to locate candidate FXML2 files efficiently, visiting only files that
     * contain the property name as a word are visited.
     */
    static boolean isReferencedInStandaloneFxml(@NotNull PsiElement element) {
        String propertyName = Fxml2PropertyNameUtil.propertyNameFromElement(element);
        if (propertyName == null) return false;

        Project project = element.getProject();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

        boolean[] found = {false};
        PsiSearchHelper.getInstance(project).processAllFilesWithWord(
                propertyName,
                scope,
                file -> {
                    if (found[0]) return false;
                    if (!(file instanceof XmlFile xmlFile)) return true;
                    if (!Fxml2FileType.isFxml2(xmlFile)) return true;
                    if (isReferencedInXmlFile(element, xmlFile)) {
                        found[0] = true;
                        return false;
                    }
                    return true;
                },
                /* caseSensitively= */ false
        );

        return found[0];
    }


    // -----------------------------------------------------------------------
    // XML-file scan
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if {@code xmlFile} contains at least one reference that
     * resolves to {@code element}: either a {@link Fxml2BindingSegmentReference} (for
     * binding-path segments such as {@code {fx:Observe vm.labelText}}) or an XML
     * attribute whose {@link org.jfxcore.fxml.descriptors.Fxml2PropertyAttributeDescriptor}
     * declaration matches {@code element} (for plain property attributes such as
     * {@code formatter="$doubleFormatter"}).
     *
     * <p>For Kotlin elements the reference resolves to the {@code KtLightMethod} wrapper
     * (a {@link com.intellij.psi.PsiMethod} whose navigation element is the Kotlin source
     * declaration). Therefore, in addition to a direct {@link PsiManager#areElementsEquivalent}
     * check, this method also compares via the resolved element's navigation element so that
     * both {@code KtNamedFunction} and its corresponding {@code KtLightMethod} are matched
     * correctly.
     */
    private static boolean isReferencedInXmlFile(
            @NotNull PsiElement element, @NotNull XmlFile xmlFile) {
        boolean[] found = {false};

        // Check 1: property attribute names (e.g. formatter="$x" -> setFormatter).
        Fxml2PropertyAttributeSearcher.collectMatchingAttributes(
                xmlFile, element, ref -> { found[0] = true; return false; });
        if (found[0]) return true;

        // Check 2: binding-segment references in attribute values
        // (e.g. {fx:Observe vm.labelText} -> labelTextProperty()).
        xmlFile.accept(new XmlRecursiveElementVisitor() {
            @Override
            public void visitXmlAttributeValue(@NotNull XmlAttributeValue attrValue) {
                if (found[0]) return;
                for (PsiReference ref : attrValue.getReferences()) {
                    if (!(ref instanceof Fxml2BindingSegmentReference)) continue;
                    PsiElement resolved = ref.resolve();
                    if (resolved == null) continue;
                    PsiManager mgr = element.getManager();
                    // Direct equivalence (covers Java elements and compiled KtLightMethod
                    // when IntelliJ compares equivalent KtNamedFunction <-> KtLightMethod).
                    if (mgr.areElementsEquivalent(element, resolved)) {
                        found[0] = true;
                        return;
                    }
                    // Navigation-element fallback: for Kotlin, resolved is KtLightMethod
                    // whose getNavigationElement() returns the KtNamedFunction.  This
                    // handles cases where areElementsEquivalent does not bridge the gap.
                    //
                    // Guard: getNavigationElement() can throw PsiInvalidElementAccessException
                    // when the underlying Kotlin PSI stub was detached from the file tree
                    // (e.g. the Kotlin source was edited while this scan was running and the
                    // FXML reference cache still holds the old KtLightMethod).  We catch
                    // the exception and treat it as "not a match" so the provider returns
                    // false rather than crashing the highlighting pass.
                    try {
                        PsiElement navEl = resolved.getNavigationElement();
                        if (navEl != null && navEl != resolved
                                && mgr.areElementsEquivalent(element, navEl)) {
                            found[0] = true;
                            return;
                        }
                    } catch (PsiInvalidElementAccessException ignored) {
                        // The resolved element was invalidated between the resolve() call
                        // and the getNavigationElement() call.  Skip it.
                    }
                }
            }
        });
        return found[0];
    }
}
