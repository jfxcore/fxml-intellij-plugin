package org.jfxcore.fxml.lang;

import com.intellij.codeInsight.daemon.ImplicitUsageProvider;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiInvalidElementAccessException;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.XmlRecursiveElementVisitor;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2PropertyNameUtil;

/**
 * Suppresses false-positive "Field/Method/Function 'X' is never used" warnings for
 * fields, property-accessor methods, and Kotlin functions/properties that are referenced
 * in <em>standalone</em> FXML files via binding expressions (e.g.
 * {@code {fx:Observe vm.labelText}}) or property-attribute values (e.g.
 * {@code formatter="$doubleFormatter"}).
 *
 * <h3>Why is this needed?</h3>
 * <p>For standalone FXML files, {@link Fxml2UseScopeEnlarger} adds {@code .fxml}/
 * {@code .fxmlx} files to the search scope for {@link com.intellij.psi.PsiField} and
 * {@link com.intellij.psi.PsiMethod} elements.  This allows IntelliJ's unused-declaration
 * analysis (which uses {@link com.intellij.psi.search.searches.ReferencesSearch}) to find
 * binding-segment references in FXML files for <em>Java</em> elements.
 *
 * <p>However, for <em>Kotlin</em> elements (e.g. a {@code KtNamedFunction} for
 * {@code fun labelTextProperty()}, or a {@code KtProperty} for {@code val message}),
 * Kotlin's unused-declaration inspection queries the element's {@code useScope} using the
 * Kotlin PSI element directly, not its Java {@code KtLightMethod} wrapper.  Since
 * {@link Fxml2UseScopeEnlarger} does not enlarge the scope for plain Kotlin functions
 * (only for property-accessor-named ones), FXML files are never added to the scope for
 * such Kotlin elements, and the search finds nothing.
 *
 * <p>This provider bridges that gap by searching standalone FXML files directly via the
 * word index and checking whether any {@link Fxml2BindingSegmentReference} resolves to the
 * element (or its Kotlin light-class equivalent).
 *
 * <h3>Java vs Kotlin event-handler methods</h3>
 * <p>Java event-handler methods (e.g. {@code public void handleClick()}) are intentionally
 * <em>not</em> covered by this provider.  They have actual
 * {@link com.intellij.psi.PsiReference}s via {@code EventHandlerMethodReferenceProvider}
 * and are discovered by {@code Fxml2EventHandlerMethodSearcher} through
 * {@link com.intellij.psi.search.searches.MethodReferencesSearch}.  Marking them as
 * implicitly used would cause
 * {@link com.intellij.codeInspection.deadCode.UnusedDeclarationInspectionBase#isEntryPoint}
 * to return {@code true}, which suppresses the Code Vision usage count.
 * Kotlin {@code KtNamedFunction} event handlers remain here because their scope is not
 * enlarged by {@link Fxml2UseScopeEnlarger}.
 *
 * <p>Java ({@link com.intellij.psi.PsiField}, {@link com.intellij.psi.PsiMethod}) and
 * Kotlin ({@code KtNamedFunction}, {@code KtProperty}) property-accessor elements are
 * both handled.  Java elements serve as a safety net in case a future IntelliJ version
 * changes how unused-declaration analysis interacts with use-scope enlargers.
 *
 * <p>Complements {@link Fxml2EmbeddedImplicitUsageProvider}, which covers references in
 * <em>embedded</em> FXML markup (inside {@code @ComponentView} annotations).  Standalone
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
     * and at least one standalone FXML file in the project contains either:
     * <ul>
     *   <li>a {@link Fxml2BindingSegmentReference} that resolves to this element (e.g.
     *       {@code {fx:Observe vm.labelText}} -> {@code labelTextProperty()}),</li>
     *   <li>an XML attribute whose {@link org.jfxcore.fxml.descriptors.Fxml2PropertyAttributeDescriptor#getDeclaration()}
     *       resolves to this element (e.g. {@code formatter="$doubleFormatter"}), or</li>
     *   <li>a {@link Fxml2AttributeValueReference} that resolves to this element for Kotlin
     *       functions (e.g. {@code onAction="handleClick"} on an {@code EventHandler}-typed
     *       property; Java methods are excluded — see class javadoc).</li>
     * </ul>
     *
     * <p>Uses the word index to locate candidate FXML files efficiently; only files that
     * contain the property/method name as a word are visited.
     */
    static boolean isReferencedInStandaloneFxml(@NotNull PsiElement element) {
        String propertyName = Fxml2PropertyNameUtil.propertyNameFromElement(element);
        if (propertyName == null) {
            // Plain event-handler method: not a property accessor, getter, or setter.
            // Fall back to the method/function name itself as the search word.
            propertyName = plainHandlerMethodName(element);
            if (propertyName == null) return false;
        }

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
        // Check 3: event-handler method references (onAction="handleClick").
        xmlFile.accept(new XmlRecursiveElementVisitor() {
            @Override
            public void visitXmlAttributeValue(@NotNull XmlAttributeValue attrValue) {
                if (found[0]) return;
                for (PsiReference ref : attrValue.getReferences()) {
                    if (!(ref instanceof Fxml2BindingSegmentReference)
                            && !(ref instanceof Fxml2AttributeValueReference)) continue;
                    PsiElement resolved = ref.resolve();
                    if (resolved == null) continue;
                    PsiManager mgr = element.getManager();
                    if (mgr.areElementsEquivalent(element, resolved)) {
                        found[0] = true;
                        return;
                    }
                    // Navigation-element fallback: for Kotlin, resolved is KtLightMethod
                    // whose getNavigationElement() returns the KtNamedFunction source
                    // declaration.
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

    /**
     * Returns the function name when {@code element} is a Kotlin {@code KtNamedFunction}
     * that could serve as an event-handler target, or {@code null} otherwise.
     *
     * <p>This is the fallback search word used when
     * {@link Fxml2PropertyNameUtil#propertyNameFromElement} returns {@code null}.
     *
     * <p>Java {@link PsiMethod} event-handler methods are intentionally excluded here.
     * They have actual {@link com.intellij.psi.PsiReference}s provided by
     * {@code EventHandlerMethodReferenceProvider} and are found by
     * {@code Fxml2EventHandlerMethodSearcher} via
     * {@link com.intellij.psi.search.searches.MethodReferencesSearch}.  Returning
     * {@code true} from {@link #isImplicitUsage} for those methods causes
     * {@link com.intellij.codeInspection.deadCode.UnusedDeclarationInspectionBase#isEntryPoint}
     * to return {@code true}, which suppresses the Code Vision usage count.
     * Kotlin {@code KtNamedFunction} event handlers remain here because
     * {@link Fxml2UseScopeEnlarger} does not enlarge the scope for plain Kotlin functions,
     * so the platform's word-index pre-check would short-circuit before
     * {@code MethodReferencesSearch} runs.
     */
    static @Nullable String plainHandlerMethodName(@NotNull PsiElement element) {
        try {
            if (element instanceof org.jetbrains.kotlin.psi.KtNamedFunction f) {
                return f.getName();
            }
        } catch (NoClassDefFoundError ignored) {}
        return null;
    }
}
