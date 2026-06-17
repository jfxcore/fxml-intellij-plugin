package org.jfxcore.fxml.lang;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.XmlRecursiveElementVisitor;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.Processor;
import com.intellij.util.QueryExecutor;
import com.intellij.xml.XmlElementDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.descriptors.Fxml2ClassTagDescriptor;

/**
 * {@link ReferencesSearch} extension that finds references to a {@link PsiClass} inside
 * <em>embedded</em> FXML markup ({@code @ComponentView} annotations).
 *
 * <h3>Why is this needed?</h3>
 * <p>A class may be referenced in embedded FXML in two ways:
 * <ol>
 *   <li>As an <b>XML element tag</b>, e.g.:
 *       {@code <CustomControl fx:typeArguments="Double" item="1e2"/>}</li>
 *   <li>As a <b>markup extension</b> in an attribute value, e.g.:
 *       {@code <Label text="{MyMarkupExtension value=foo}"/>}</li>
 * </ol>
 * In both cases the FXML/2 compiler instantiates the class at runtime. However, IntelliJ's
 * standard "Find Usages" for the {@code PsiClass} never discovers these usage sites because:
 * <ul>
 *   <li>Embedded FXML lives inside a Java text-block literal: an injected language fragment
 *       that is <em>never</em> included in IntelliJ's word index.</li>
 *   <li>{@link Fxml2UseScopeEnlarger} only enlarges the search scope for {@code PsiField} and
 *       {@code PsiMethod} elements, so FXML files are never added to the scope when searching
 *       for usages of a {@code PsiClass}.</li>
 * </ul>
 *
 * <p>This searcher bridges the gap: when "Find Usages" is invoked on a {@link PsiClass} or
 * on one of its constructors, it
 * walks all {@code @ComponentView}-annotated classes in the project, retrieves their injected
 * {@link XmlFile}, and:
 * <ul>
 *   <li>for every XML tag whose {@link Fxml2ClassTagDescriptor#getPsiClass()} resolves to the
 *       target class, feeds the matching reference(s) on that tag to the consumer;</li>
 *   <li>for every XML attribute value whose references include one that resolves to the target
 *       class, feeds that reference to the consumer (covers markup extension usages).</li>
 * </ul>
 *
 * <p>The returned references are anchored on the injected XML PSI element; IntelliJ's
 * language-injection infrastructure maps these back to the host Java file's text-block
 * literal for display in the "Find Usages" popup.
 */
public final class Fxml2EmbeddedClassTagSearcher
        implements QueryExecutor<PsiReference, ReferencesSearch.SearchParameters> {

    @Override
    public boolean execute(
            @NotNull ReferencesSearch.SearchParameters params,
            @NotNull Processor<? super PsiReference> consumer) {

        PsiElement target = params.getElementToSearch();

        // When "Find Usages" is invoked on a constructor, resolve the containing class and
        // search for usages of that class in embedded FXML (the constructor is implicitly
        // called whenever the class is instantiated as a tag or markup extension).
        // NOTE: isConstructor() and getContainingClass() require a read action.
        PsiClass psiClass = ReadAction.nonBlocking(() -> {
            if (target instanceof PsiMethod method && method.isConstructor()) {
                return method.getContainingClass();
            } else if (target instanceof PsiClass cls) {
                return cls;
            }
            return null;
        }).executeSynchronously();
        if (psiClass == null) return true;

        SearchScope scope = params.getEffectiveSearchScope();
        if (!(scope instanceof GlobalSearchScope globalScope)) return true;

        Project project = ReadAction.nonBlocking(target::getProject).executeSynchronously();
        collectInScope(psiClass, project, globalScope, consumer);
        return true;
    }

    /**
     * Searches embedded FXML markup in {@code globalScope} for element-tag and
     * markup-extension usages of {@code psiClass}, feeding matching references to
     * {@code consumer}.
     *
     * <p>Called both from the {@link ReferencesSearch} path (this class) and from the
     * {@link com.intellij.psi.search.searches.MethodReferencesSearch} path
     * ({@link Fxml2EmbeddedClassTagMethodSearcher}).
     */
    static void collectInScope(
            @NotNull PsiClass psiClass,
            @NotNull Project project,
            @NotNull GlobalSearchScope globalScope,
            @NotNull Processor<? super PsiReference> consumer) {

        // Use the simple class name as the word-index key.  In FXML markup, the class is
        // referenced either as its simple name (<CustomLabel>) or as a dotted FQN
        // (<sample.app.CustomLabel>).  Either way, the simple name always appears as a
        // separate word token (XML tag names are split at '<' and '.'), so an IN_PLAIN_TEXT
        // lookup on the Java file containing the text-block will reliably pre-filter to
        // only the host files that actually reference the class.
        String simpleClassName = ReadAction.nonBlocking(psiClass::getName).executeSynchronously();
        if (simpleClassName == null) return;

        ReadAction.nonBlocking(() -> {
            Fxml2EmbeddedUtil.processAnnotatedClassesContainingWord(simpleClassName, project, globalScope, annotatedClass -> {
                XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(annotatedClass);
                if (xmlFile == null) return true;

                xmlFile.accept(new XmlRecursiveElementVisitor() {
                    @Override
                    public void visitXmlTag(@NotNull XmlTag tag) {
                        XmlElementDescriptor desc = tag.getDescriptor();
                        if (desc instanceof Fxml2ClassTagDescriptor cd) {
                            PsiClass tagClass = cd.getPsiClass();
                            if (tagClass != null
                                    && psiClass.getManager().areElementsEquivalent(psiClass, tagClass)) {
                                for (PsiReference ref : tag.getReferences()) {
                                    PsiElement resolved = ref.resolve();
                                    if (resolved != null
                                            && psiClass.getManager().areElementsEquivalent(psiClass, resolved)) {
                                        consumer.process(ref);
                                    }
                                }
                            }
                        }
                        super.visitXmlTag(tag);
                    }

                    @Override
                    public void visitXmlAttributeValue(@NotNull XmlAttributeValue attrValue) {
                        // Covers markup extension usages, e.g. {MyMarkupExtension value=foo}.
                        for (PsiReference ref : attrValue.getReferences()) {
                            PsiElement resolved = ref.resolve();
                            if (resolved != null
                                    && psiClass.getManager().areElementsEquivalent(psiClass, resolved)) {
                                consumer.process(ref);
                            }
                        }
                    }
                });
                return true;
            });
            return null;
        }).executeSynchronously();
    }
}
