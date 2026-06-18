package org.jfxcore.fxml.lang;

import com.intellij.lang.properties.IProperty;
import com.intellij.lang.properties.references.PropertyReferenceBase;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.XmlRecursiveElementVisitor;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.Processor;
import com.intellij.util.QueryExecutor;
import org.jetbrains.annotations.NotNull;

/**
 * {@link ReferencesSearch} extension that finds {@link PropertyReferenceBase} instances in
 * embedded FXML markup ({@code @ComponentView} annotations) that resolve to a given
 * {@link IProperty}.
 *
 * <h3>Why is this needed?</h3>
 * <p>IntelliJ's standard {@code CachesBasedRefSearcher} uses the word index to locate
 * potential reference sites.  Because embedded FXML markup exists inside Java text-block
 * literals (injected language fragments), it is never indexed in the word index.
 * Consequently, {@link ReferencesSearch#search} invoked on an {@link IProperty} never
 * reaches the embedded XML: "Find Usages" and "Navigate to Use Sites" show no results
 * even though the property is referenced via {@code %key} in embedded FXML.
 *
 * <p>This searcher bridges the gap: when the target element is an {@link IProperty}, it
 * walks all {@code @ComponentView}-annotated classes in the search scope, retrieves their
 * injected {@link XmlFile} via {@link Fxml2EmbeddedUtil#getInjectedXmlFile}, and feeds
 * every {@link PropertyReferenceBase} on any {@link XmlAttributeValue} that resolves to the
 * target property to the consumer.  Both the {@code %key} prefix shorthand and the long-form
 * {@code {DynamicResource key}} / {@code {StaticResource key}} notations are covered.
 */
public final class Fxml2EmbeddedPropertyReferenceSearcher
        implements QueryExecutor<PsiReference, ReferencesSearch.SearchParameters> {

    @Override
    public boolean execute(
            @NotNull ReferencesSearch.SearchParameters params,
            @NotNull Processor<? super PsiReference> consumer) {

        PsiElement target = params.getElementToSearch();
        if (!(target instanceof IProperty property)) return true;

        String key = ReadAction.nonBlocking(property::getKey).executeSynchronously();
        if (key == null || key.isEmpty()) return true;

        SearchScope scope = params.getEffectiveSearchScope();
        if (!(scope instanceof GlobalSearchScope globalScope)) return true;

        Project project = ReadAction.nonBlocking(target::getProject).executeSynchronously();

        ReadAction.nonBlocking(() -> {
            Fxml2EmbeddedUtil.processAnnotatedClasses(project, globalScope, annotatedClass -> {
                XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(annotatedClass);
                if (xmlFile == null) return true;

                xmlFile.accept(new XmlRecursiveElementVisitor() {
                    @Override
                    public void visitXmlAttributeValue(@NotNull XmlAttributeValue attrValue) {
                        String value = attrValue.getValue();
                        // Quick pre-filter: value must look like a resource key reference for
                        // this key: avoids calling getReferences() on every attribute.
                        if (!Fxml2EmbeddedPropertyUsageProvider.isResourceKeyReference(value, key)) return;

                        for (PsiReference ref : attrValue.getReferences()) {
                            if (ref instanceof PropertyReferenceBase propRef
                                    && propRef.isReferenceTo(target)) {
                                consumer.process(propRef);
                                return; // only one match per attribute value
                            }
                        }
                    }
                });
                return true;
            });
            return null;
        }).executeSynchronously();

        return true;
    }
}
