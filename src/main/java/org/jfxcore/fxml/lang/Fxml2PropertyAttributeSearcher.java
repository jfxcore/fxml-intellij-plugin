// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.XmlRecursiveElementVisitor;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.Processor;
import com.intellij.util.QueryExecutor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.descriptors.Fxml2PropertyAttributeDescriptor;
import org.jfxcore.fxml.resolve.Fxml2PropertyNameUtil;

/**
 * {@link ReferencesSearch} extension that, when "Find Usages" is invoked on a Java
 * property-accessor method (getter, setter, or {@code xProperty()} method) or field, also
 * searches FXML2 files for XML attribute names that reference the element as a property
 * assignment.
 *
 * <h3>Why is this needed?</h3>
 * <p>For an attribute like {@code <FormattedLabel formatter="$doubleFormatter"/>}, the
 * fxml2 compiler maps the attribute name {@code formatter} to the setter
 * {@code setFormatter(Function<T,String>)}.  Navigation from the FXML2 attribute to the
 * setter already works via {@link Fxml2PropertyAttributeDescriptor#getDeclaration()}.
 *
 * <p>However, "Find Usages" on {@code setFormatter} shows no results because:
 * <ol>
 *   <li>There is no {@link PsiReference} on the {@code formatter} attribute name that
 *       resolves to {@code setFormatter}.</li>
 *   <li>{@link Fxml2UseScopeEnlarger} only enlarges the search scope for multi-parameter
 *       methods; single-parameter methods (setters) require this dedicated searcher to
 *       make "Find Usages" include FXML2 files.</li>
 * </ol>
 *
 * <p>This searcher bridges that gap: given a target {@link com.intellij.psi.PsiMethod} or
 * {@link com.intellij.psi.PsiField}, it
 * <ol>
 *   <li>derives the property name (e.g. {@code "formatter"} from {@code setFormatter});</li>
 *   <li>uses the word index (IN_PLAIN_TEXT context, since XML attribute names are indexed
 *       with {@code IN_PLAIN_TEXT | IN_FOREIGN_LANGUAGES}) to locate candidate standalone
 *       FXML2 files efficiently;</li>
 *   <li>walks each candidate file (and all embedded FXML2 markup) for XML attributes whose
 *       name matches and whose {@link Fxml2PropertyAttributeDescriptor#getDeclaration()}
 *       is equivalent to the target;</li>
 *   <li>emits a synthetic {@link PsiReference} on the attribute's name element so that
 *       IntelliJ shows the FXML2 use site in the "Find Usages" results.</li>
 * </ol>
 *
 * <h3>Word-index context note</h3>
 * <p>IntelliJ's {@link PsiSearchHelper#processAllFilesWithWord} uses
 * {@link com.intellij.psi.search.UsageSearchContext#IN_CODE}, but XML attribute names are
 * indexed by {@code XmlFilterLexer} with
 * {@link com.intellij.psi.search.UsageSearchContext#IN_PLAIN_TEXT} |
 * {@link com.intellij.psi.search.UsageSearchContext#IN_FOREIGN_LANGUAGES}.
 * Therefore this searcher uses {@link PsiSearchHelper#processAllFilesWithWordInText}
 * (which uses {@code IN_PLAIN_TEXT}) to find files containing the attribute name.
 *
 * <p>Complements {@link Fxml2BindingSegmentSearcher} (which handles binding-expression
 * segment references like {@code {fx:Observe vm.labelText}}) and
 * {@link Fxml2FxIdFieldSearcher} (which handles {@code fx:id} attribute values).
 */
public final class Fxml2PropertyAttributeSearcher
        implements QueryExecutor<PsiReference, ReferencesSearch.SearchParameters> {

    @Override
    public boolean execute(
            @NotNull ReferencesSearch.SearchParameters params,
            @NotNull Processor<? super PsiReference> consumer) {

        PsiElement target = params.getElementToSearch();

        // Only handle Java methods and fields.
        String propertyName = ReadAction.compute(
                () -> Fxml2PropertyNameUtil.propertyNameFromElement(target));
        if (propertyName == null) return true;

        SearchScope effectiveScope = params.getEffectiveSearchScope();
        if (!(effectiveScope instanceof GlobalSearchScope globalScope)) return true;

        collectInScope(target, propertyName, globalScope, consumer);
        return true;
    }

    /**
     * Searches standalone and embedded FXML2 files in {@code globalScope} for
     * property-attribute usages of {@code target} with the given {@code propertyName}.
     * Called both from the {@link ReferencesSearch} path (this class) and from the
     * {@link com.intellij.psi.search.searches.MethodReferencesSearch} path
     * ({@link Fxml2PropertyAttributeMethodSearcher}).
     */
    static void collectInScope(
            @NotNull PsiElement target,
            @NotNull String propertyName,
            @NotNull GlobalSearchScope globalScope,
            @NotNull Processor<? super PsiReference> consumer) {

        Project project = ReadAction.compute(target::getProject);

        // 1. Standalone FXML2 files: use the word index (IN_PLAIN_TEXT context) to locate
        //    candidate files containing the property name, then walk each file for matches.
        ReadAction.run(() ->
            PsiSearchHelper.getInstance(project).processAllFilesWithWordInText(
                    propertyName,
                    globalScope,
                    file -> {
                        if (!(file instanceof XmlFile xmlFile)) return true;
                        if (!Fxml2FileType.isFxml2(xmlFile)) return true;
                        collectMatchingAttributes(xmlFile, target, consumer);
                        return true;
                    },
                    /* caseSensitively= */ false)
        );

        // 2. Embedded FXML2 markup: injected XML is not indexed, but Java text-block content
        //    is indexed as IN_PLAIN_TEXT (and Kotlin raw strings as IN_STRINGS), so we can
        //    use the word index to pre-filter to only the host files containing the property name.
        ReadAction.run(() ->
            Fxml2EmbeddedUtil.processAnnotatedClassesContainingWord(propertyName, project, globalScope, annotatedClass -> {
                XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(annotatedClass);
                if (xmlFile == null) return true;
                collectMatchingAttributes(xmlFile, target, consumer);
                return true;
            })
        );
    }

    // -----------------------------------------------------------------------
    // Attribute scanning
    // -----------------------------------------------------------------------

    /**
     * Walks {@code xmlFile} looking for {@link XmlAttribute}s whose
     * {@link Fxml2PropertyAttributeDescriptor#getDeclaration()} is equivalent to
     * {@code target}, and forwards a synthetic reference for each match to
     * {@code consumer}.
     */
    static void collectMatchingAttributes(
            @NotNull XmlFile xmlFile,
            @NotNull PsiElement target,
            @NotNull Processor<? super PsiReference> consumer) {

        xmlFile.accept(new XmlRecursiveElementVisitor() {
            @Override
            public void visitXmlAttribute(@NotNull XmlAttribute attr) {
                super.visitXmlAttribute(attr);
                // Dotted attribute names (static properties) are handled elsewhere.
                if (attr.getLocalName().contains(".")) return;

                PsiElement decl = getDeclarationFromDescriptor(attr);
                if (decl == null) return;

                if (!isEquivalentTo(decl, target, attr.getManager())) return;


                // Emit a synthetic soft reference on the attribute's name element.
                PsiElement nameEl = attr.getNameElement();
                TextRange range = new TextRange(0, nameEl.getTextLength());
                consumer.process(new PsiReferenceBase<>(nameEl, range, /* soft= */ true) {
                    @Override
                    public PsiElement resolve() {
                        return decl;
                    }

                    @Override
                    public boolean isReferenceTo(@NotNull PsiElement element) {
                        return isEquivalentTo(decl, element, getElement().getManager());
                    }

                    /**
                     * Translates the raw new accessor name to the property name so that
                     * renaming e.g. {@code setFormatter} to {@code setFormatter2} results in
                     * the attribute being renamed to {@code formatter2} rather than
                     * {@code setFormatter2}.
                     */
                    @Override
                    public @NotNull PsiElement handleElementRename(@NotNull String newElementName)
                            throws com.intellij.util.IncorrectOperationException {
                        String propertyName = toPropertyName(newElementName);
                        return super.handleElementRename(propertyName);
                    }
                });
            }
        });
    }

    /**
     * Returns the {@link Fxml2PropertyAttributeDescriptor#getDeclaration()} for
     * {@code attr}, or {@code null} if the attribute does not have an
     * {@link Fxml2PropertyAttributeDescriptor}.
     */
    private static @Nullable PsiElement getDeclarationFromDescriptor(@NotNull XmlAttribute attr) {
        var descriptor = attr.getDescriptor();
        if (!(descriptor instanceof Fxml2PropertyAttributeDescriptor propDesc)) return null;
        return propDesc.getDeclaration();
    }

    /**
     * Returns {@code true} when {@code decl} is PSI-equivalent to {@code target},
     * including the navigation-element fallback for Kotlin light-class wrappers,
     * and also when {@code decl} and {@code target} are different accessors of the
     * <em>same</em> JavaFX/JavaBeans property (same property name, same containing class).
     *
     * <p>The latter case handles "Find Usages" on a setter: IntelliJ may invoke the
     * searcher with the backing field as the target instead of the setter method.
     * E.g. when "Find Usages" is run on {@code setFormatter}, the searcher may receive
     * {@code target=PsiField:formatter} while {@code decl=PsiMethod:setFormatter}.
     * Both belong to the same property, so we treat them as equivalent.
     */
    private static boolean isEquivalentTo(
            @NotNull PsiElement decl,
            @NotNull PsiElement target,
            @NotNull PsiManager mgr) {
        if (mgr.areElementsEquivalent(decl, target)) return true;
        // Kotlin light-class navigation element fallback
        PsiElement navEl = decl.getNavigationElement();
        if (navEl != null && navEl != decl && mgr.areElementsEquivalent(navEl, target)) return true;
        // Same property group: both members have the same property name and live in the same class.
        return samePropertyMember(decl, target);
    }

    /**
     * Returns {@code true} if both {@code a} and {@code b} are Java methods or fields
     * that belong to the same containing class and derive the same property name.
     */
    private static boolean samePropertyMember(@NotNull PsiElement a, @NotNull PsiElement b) {
        if (!(a instanceof PsiMember ma) || !(b instanceof PsiMember mb)) return false;
        PsiClass classA = ma.getContainingClass();
        PsiClass classB = mb.getContainingClass();
        if (classA == null || classB == null) return false;
        String qnA = classA.getQualifiedName();
        String qnB = classB.getQualifiedName();
        if (qnA == null || !qnA.equals(qnB)) return false;
        String nameA = propertyNameOf(ma);
        String nameB = propertyNameOf(mb);
        return nameA != null && nameA.equals(nameB);
    }

    private static @Nullable String propertyNameOf(@NotNull PsiMember member) {
        if (member instanceof PsiMethod m) return Fxml2PropertyNameUtil.propertyNameFromMethod(m);
        if (member instanceof PsiField f) return f.getName();
        return null;
    }

    /**
     * Derives the property name from an accessor method name.
     *
     * <p>Accessor naming patterns handled:
     * <ul>
     *   <li>{@code setFoo(...)} -> {@code "foo"}</li>
     *   <li>{@code getFoo()} / {@code isFoo()} -> {@code "foo"}</li>
     *   <li>{@code fooProperty()} -> {@code "foo"}</li>
     * </ul>
     * If the name does not match any pattern, it is returned unchanged.
     */
    private static @NotNull String toPropertyName(@NotNull String name) {
        return Fxml2PropertyNameUtil.toPropertyName(name);
    }
}
