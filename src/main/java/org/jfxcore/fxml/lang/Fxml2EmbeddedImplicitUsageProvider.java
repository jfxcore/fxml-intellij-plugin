package org.jfxcore.fxml.lang;

import com.intellij.codeInsight.daemon.ImplicitUsageProvider;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiInvalidElementAccessException;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.XmlRecursiveElementVisitor;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.xml.XmlElementDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.descriptors.Fxml2ClassTagDescriptor;
import org.jfxcore.fxml.resolve.Fxml2PropertyNameUtil;

/**
 * Suppresses false-positive "Field/Method 'X' is never used" and "Field 'X' is assigned but
 * never accessed" warnings for fields and property-accessor methods that are referenced only
 * inside embedded FXML2 markup (binding expressions in {@code @ComponentView} annotations).
 *
 * <h3>Why is this needed?</h3>
 * <p>IntelliJ's unused-field/method analysis works by calling
 * {@link com.intellij.psi.search.searches.ReferencesSearch} over the
 * {@link com.intellij.psi.search.SearchScope} produced by all registered
 * {@link com.intellij.psi.search.UseScopeEnlarger}s.  For
 * <em>standalone</em> FXML2 files, {@link Fxml2UseScopeEnlarger} adds
 * {@code .fxml}/{@code .fxml2} files to that scope; the word-index search then
 * finds the attribute value and the {@link Fxml2BindingSegmentReference} on it
 * resolves to the Java field/method, making IntelliJ treat it as "used".
 *
 * <p>For <em>embedded</em> FXML2 markup (inside a {@code @ComponentView} text-block
 * annotation), the XML exists only as an injected language fragment: it does not
 * appear in any file index.  Word-index searches therefore never reach it, so the
 * binding-segment references inside that fragment are never found, and IntelliJ
 * reports the referenced field/method as unused.
 *
 * <p>This particularly affects two scenarios:
 * <ol>
 *   <li><b>Direct view members</b>: a field/method on the {@code @ComponentView} class
 *       itself, e.g. a JavaFX property accessor {@code vmProperty()} that is bound in the
 *       markup as {@code "vm"}. Without this provider IntelliJ would report
 *       {@code vmProperty()} as "Method is never used".</li>
 *   <li><b>View-model members</b>: a field/method on a <em>non-{@code @ComponentView}</em>
 *       class (e.g. {@code MainViewModel}) that is referenced via a multi-segment binding
 *       path in some other class's embedded markup:
     *       {@code <Label text="{fx:Observe vm.labelText}"/>}. Without this provider IntelliJ
 *       would report {@code MainViewModel.labelText} as "Field is never used".</li>
 * </ol>
 *
 * <h3>Fix</h3>
 * <p>When IntelliJ consults {@code ImplicitUsageProvider}s before emitting an
 * "unused" warning, this implementation:
 * <ol>
 *   <li>checks whether the element is a {@link PsiField}, {@link PsiMethod}, or
 *       {@link PsiClass} (including constructors);</li>
 *   <li>for a {@link PsiClass} or constructor: checks whether the class is used as
 *       an XML element tag (e.g. {@code <CustomLabel/>}) or as a markup extension in
 *       an attribute value (e.g. {@code text="{MyExt value=foo}"}) in any
 *       {@code @ComponentView}-annotated class's embedded FXML2;</li>
 *   <li>tries the <em>fast path</em>: if the containing class has {@code @ComponentView},
 *       checks its own injected {@link XmlFile} for binding-segment references;</li>
 *   <li>falls back to the <em>cross-class path</em>: searches all
 *       {@code @ComponentView}-annotated classes in the project and checks their injected
 *       XML files for binding-segment references to the element.</li>
 * </ol>
 * If at least one such reference is found, both {@link #isImplicitUsage} and
 * {@link #isImplicitRead} return {@code true}, suppressing both the "never used" and
 * "assigned but never accessed" forms of the warning.
 */
public final class Fxml2EmbeddedImplicitUsageProvider implements ImplicitUsageProvider {

    @Override
    public boolean isImplicitUsage(@NotNull PsiElement element) {
        return isReferencedInEmbeddedFxml(element);
    }

    @Override
    public boolean isImplicitRead(@NotNull PsiElement element) {
        return isReferencedInEmbeddedFxml(element);
    }

    @Override
    public boolean isImplicitWrite(@NotNull PsiElement element) {
        return false;
    }

    /**
     * Returns {@code true} when {@code element} is a {@link PsiField}, {@link PsiMethod},
     * {@link PsiClass}, or constructor and the injected FXML2 XML of some
     * {@code @ComponentView}-annotated class contains at least one reference to it.
     *
     * <p>For {@link PsiClass} and constructors, the class is considered referenced when:
     * <ul>
     *   <li>it is used as an XML element tag (e.g. {@code <CustomLabel/>}), or</li>
     *   <li>it is used as a markup extension in an attribute value
     *       (e.g. {@code text="{MyExt value=foo}"}).</li>
     * </ul>
     *
     * <p>For {@link PsiField} and {@link PsiMethod} (non-constructor), the check covers:
     * <ul>
     *   <li><b>Direct members</b>: when the containing class itself carries
     *       {@code @ComponentView}: e.g. {@code public MainViewModel vm} in the view class,
     *       the binding segment {@code "vm"} resolves directly to that field.</li>
     *   <li><b>Property-accessor methods</b>: {@code public ObjectProperty<MainViewModel> vmProperty()}
     *      : the binding segment {@code "vm"} resolves to the {@code vmProperty()} method
     *       (not the private backing field), matching the fxml2 compiler's actual behavior.</li>
     *   <li><b>View-model members</b>: when the containing class does <em>not</em> carry
     *       {@code @ComponentView} but is referenced from a view's embedded markup via a
     *       multi-segment binding path: e.g. {@code {fx:Observe vm.labelText}} in
     *       {@code MainView}'s markup references {@code MainViewModel.labelText}.</li>
     * </ul>
     */
    static boolean isReferencedInEmbeddedFxml(@NotNull PsiElement element) {
        // A PsiClass used as an FXML2 element tag (e.g. <sample.app.CustomLabel/>) or as a
        // markup extension in an attribute value (e.g. text="{MyExt value=foo}") is implicitly
        // used, the FXML2 runtime instantiates it.
        if (element instanceof PsiClass psiClass) {
            return isClassTaggedInEmbeddedFxml(psiClass)
                    || isClassUsedAsMarkupExtensionInEmbeddedFxml(psiClass);
        }
        // A constructor is implicitly called when the class is instantiated as an element tag
        // or as a markup extension in an attribute value.
        if (element instanceof PsiMethod method && method.isConstructor()) {
            PsiClass cls = method.getContainingClass();
            return cls != null && (isClassTaggedInEmbeddedFxml(cls)
                    || isClassUsedAsMarkupExtensionInEmbeddedFxml(cls));
        }

        // Handle both PsiField and PsiMethod (property accessors like vmProperty()).
        // For Kotlin KtProperty/KtNamedFunction, delegate to the Kotlin-specific search.
        PsiClass containingClass;
        if (element instanceof PsiField field) {
            containingClass = field.getContainingClass();
        } else if (element instanceof PsiMethod method) {
            containingClass = method.getContainingClass();
        } else {
            return isKotlinElementReferencedInEmbeddedFxml(element);
        }

        if (containingClass == null) return false;

        // Fast path: the containing class has @ComponentView: check its own embedded XML.
        // This covers the common case of a direct view class member.
        if (containingClass.getAnnotation(Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN) != null) {
            XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(containingClass);
            if (xmlFile != null && isReferencedInXmlFile(element, xmlFile)) {
                return true;
            }
        }

        // Cross-class path: the element may be a member of a non-@ComponentView class
        // (e.g. a view model) referenced via a binding path like {fx:Observe vm.labelText}
        // in some other class's embedded markup.  Use the word-index-filtered variant to
        // narrow the search to only @ComponentView classes whose embedded markup text
        // contains the property name, avoiding a full scan of all annotated classes.
        String propertyWord = Fxml2PropertyNameUtil.propertyNameFromElement(element);
        if (propertyWord == null) return false;
        Project project = element.getProject();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        boolean[] found = {false};
        Fxml2EmbeddedUtil.processAnnotatedClassesContainingWord(propertyWord, project, scope, annotatedClass -> {
            if (found[0]) return false;
            // Skip the containing class: already checked in the fast path above.
            if (element.getManager().areElementsEquivalent(containingClass, annotatedClass)) {
                return true;
            }
            XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(annotatedClass);
            if (xmlFile == null) return true;
            if (isReferencedInXmlFile(element, xmlFile)) {
                found[0] = true;
                return false;
            }
            return true;
        });
        return found[0];
    }

    /**
     * Returns {@code true} if {@code xmlFile} contains at least one reference that
     * resolves to {@code element}: either a {@link Fxml2BindingSegmentReference} or an
     * XML attribute whose {@link org.jfxcore.fxml.descriptors.Fxml2PropertyAttributeDescriptor}
     * declaration matches {@code element} (e.g. {@code formatter="$doubleFormatter"}).
     */
    private static boolean isReferencedInXmlFile(
            @NotNull PsiElement element, @NotNull XmlFile xmlFile) {
        boolean[] found = {false};

        // Check 1: property attribute names (e.g. formatter="$x" -> setFormatter).
        Fxml2PropertyAttributeSearcher.collectMatchingAttributes(
                xmlFile, element, ref -> { found[0] = true; return false; });
        if (found[0]) return true;

        // Check 2: binding-segment references in attribute values.
        xmlFile.accept(new XmlRecursiveElementVisitor() {
            @Override
            public void visitXmlAttributeValue(@NotNull XmlAttributeValue attrValue) {
                if (found[0]) return;
                for (PsiReference ref : attrValue.getReferences()) {
                    if (!(ref instanceof Fxml2BindingSegmentReference)) continue;
                    PsiElement resolved = ref.resolve();
                    if (resolved == null) continue;
                    PsiManager mgr = element.getManager();
                    if (mgr.areElementsEquivalent(element, resolved)) {
                        found[0] = true;
                        return;
                    }
                    // Navigation-element fallback: for Kotlin, the resolved element is a
                    // KtLightMethod/KtLightField whose getNavigationElement() returns the
                    // KtProperty or KtNamedFunction source declaration.
                    try {
                        PsiElement navEl = resolved.getNavigationElement();
                        if (navEl != null && navEl != resolved
                                && mgr.areElementsEquivalent(element, navEl)) {
                            found[0] = true;
                            return;
                        }
                    } catch (PsiInvalidElementAccessException ignored) {
                        // Resolved element was invalidated between resolve() and
                        // getNavigationElement(); treat as no match.
                    }
                }
            }
        });
        return found[0];
    }

    /**
     * Cross-class embedded FXML2 search for Kotlin source declarations ({@code KtProperty},
     * {@code KtNamedFunction}).
     *
     * <p>K2's unused-symbol analysis never calls {@link ImplicitUsageProvider} for
     * {@code KtProperty} nodes, so those elements bypass
     * {@link #isReferencedInEmbeddedFxml(PsiElement)}'s Java fast path.  This method handles
     * them by deriving the property name, scanning all {@code @ComponentView}-annotated
     * classes whose embedded markup text contains that word, and checking each injected
     * XML file for a binding-segment reference whose navigation element matches the
     * {@code KtProperty} or {@code KtNamedFunction}.
     */
    private static boolean isKotlinElementReferencedInEmbeddedFxml(@NotNull PsiElement element) {
        try {
            if (!(element instanceof org.jetbrains.kotlin.psi.KtProperty)
                    && !(element instanceof org.jetbrains.kotlin.psi.KtNamedFunction)) {
                return false;
            }
        } catch (NoClassDefFoundError ignored) {
            return false;
        }

        String propertyWord = Fxml2PropertyNameUtil.propertyNameFromElement(element);
        if (propertyWord == null) return false;
        Project project = element.getProject();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        boolean[] found = {false};
        Fxml2EmbeddedUtil.processAnnotatedClassesContainingWord(propertyWord, project, scope, annotatedClass -> {
            if (found[0]) return false;
            XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(annotatedClass);
            if (xmlFile == null) return true;
            if (isReferencedInXmlFile(element, xmlFile)) {
                found[0] = true;
                return false;
            }
            return true;
        });
        return found[0];
    }

    /**
     * Returns {@code true} if any {@code @ComponentView}-annotated class in the project has
     * embedded FXML2 markup that uses {@code psiClass} as an XML element tag.
     *
     * <p>This covers the case where a custom control is instantiated in embedded FXML2 via
     * a fully-qualified or imported class name, e.g.
     * {@code <sample.app.CustomLabel fx:typeArguments="Double" item="1e2"/>}.
     */
    static boolean isClassTaggedInEmbeddedFxml(@NotNull PsiClass psiClass) {
        String simpleName = psiClass.getName();
        if (simpleName == null) return false;
        Project project = psiClass.getProject();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        boolean[] found = {false};
        Fxml2EmbeddedUtil.processAnnotatedClassesContainingWord(simpleName, project, scope, annotatedClass -> {
            if (found[0]) return false;
            XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(annotatedClass);
            if (xmlFile == null) return true;
            if (isClassTaggedInXmlFile(psiClass, xmlFile)) {
                found[0] = true;
                return false;
            }
            return true;
        });
        return found[0];
    }

    /**
     * Returns {@code true} if any {@code @ComponentView}-annotated class in the project has
     * embedded FXML2 markup that uses {@code psiClass} as a markup extension in an attribute
     * value (e.g. {@code text="{MyExt value=foo}"}).
     *
     * <p>This is the companion check to {@link #isClassTaggedInEmbeddedFxml} for the
     * markup extension attribute-notation case, where the class is not an XML element tag
     * but is referenced inside a {@code {ClassName ...}} expression in an attribute value.
     */
    private static boolean isClassUsedAsMarkupExtensionInEmbeddedFxml(@NotNull PsiClass psiClass) {
        String simpleName = psiClass.getName();
        if (simpleName == null) return false;
        Project project = psiClass.getProject();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        boolean[] found = {false};
        Fxml2EmbeddedUtil.processAnnotatedClassesContainingWord(simpleName, project, scope, annotatedClass -> {
            if (found[0]) return false;
            XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(annotatedClass);
            if (xmlFile == null) return true;
            if (isClassUsedAsMarkupExtensionInXmlFile(psiClass, xmlFile)) {
                found[0] = true;
                return false;
            }
            return true;
        });
        return found[0];
    }

    /**
     * Returns {@code true} if {@code xmlFile} contains at least one XML attribute value
     * whose references include a reference that resolves to {@code psiClass}.
     *
     * <p>This detects markup extension usages of the form {@code {ClassName ...}} in
     * attribute values, where the {@link com.intellij.psi.PsiReference} on the attribute
     * value resolves to the extension class.
     */
    private static boolean isClassUsedAsMarkupExtensionInXmlFile(
            @NotNull PsiClass psiClass, @NotNull XmlFile xmlFile) {
        boolean[] found = {false};
        xmlFile.accept(new XmlRecursiveElementVisitor() {
            @Override
            public void visitXmlAttributeValue(@NotNull XmlAttributeValue attrValue) {
                if (found[0]) return;
                for (PsiReference ref : attrValue.getReferences()) {
                    PsiElement resolved = ref.resolve();
                    if (psiClass.getManager().areElementsEquivalent(psiClass, resolved)) {
                        found[0] = true;
                        return;
                    }
                }
            }
        });
        return found[0];
    }

    /**
     * Returns {@code true} if {@code xmlFile} contains at least one XML tag whose
     * {@link Fxml2ClassTagDescriptor#getPsiClass()} is equivalent to {@code psiClass}.
     */
    static boolean isClassTaggedInXmlFile(@NotNull PsiClass psiClass, @NotNull XmlFile xmlFile) {
        boolean[] found = {false};
        xmlFile.accept(new XmlRecursiveElementVisitor() {
            @Override
            public void visitXmlTag(@NotNull XmlTag tag) {
                if (found[0]) return;
                XmlElementDescriptor desc = tag.getDescriptor();
                if (desc instanceof Fxml2ClassTagDescriptor cd) {
                    PsiClass tagClass = cd.getPsiClass();
                    if (tagClass != null
                            && psiClass.getManager().areElementsEquivalent(psiClass, tagClass)) {
                        found[0] = true;
                        return;
                    }
                }
                super.visitXmlTag(tag);
            }
        });
        return found[0];
    }
}
