package org.jfxcore.fxml.lang;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.PsiType;
import com.intellij.psi.XmlRecursiveElementVisitor;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.Processor;
import com.intellij.util.QueryExecutor;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.descriptors.Fxml2ClassTagDescriptor;
import org.jfxcore.fxml.resolve.Fxml2AttributeValueResolver;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2BindingPathResolver;
import org.jfxcore.fxml.resolve.Fxml2NamedArgResolver;
import org.jfxcore.fxml.resolve.Fxml2XmlUtil;

import java.util.List;

/**
 * {@link MethodReferencesSearch} extension that surfaces FXML event-handler attribute
 * values as usages of the referenced code-behind method.
 *
 * <p>When a property type is {@code EventHandler<T>}, the FXML compiler treats a plain
 * method name in the attribute value (e.g. {@code onAction="handleClick"}) as a reference
 * to the named method on the code-behind class.  This searcher ensures that "Find Usages"
 * on the Java method also shows the FXML attribute as a use site.
 *
 * <p>Complements {@link Fxml2ReferenceContributor}'s {@code EventHandlerMethodReferenceProvider},
 * which provides the reference for Ctrl+click navigation.  The searcher is needed for the
 * {@link MethodReferencesSearch} path, since IntelliJ routes "Find Usages" on a
 * {@link PsiMethod} through that search rather than {@code ReferencesSearch}.
 */
public final class Fxml2EventHandlerMethodSearcher
        implements QueryExecutor<PsiReference, MethodReferencesSearch.SearchParameters> {

    @Override
    public boolean execute(
            @NotNull MethodReferencesSearch.SearchParameters params,
            @NotNull Processor<? super PsiReference> consumer) {

        PsiMethod target = params.getMethod();
        SearchScope effectiveScope = params.getEffectiveSearchScope();

        String methodName = ReadAction.nonBlocking(target::getName).executeSynchronously();

        if (!(effectiveScope instanceof GlobalSearchScope globalScope)) return true;

        Project project = ReadAction.nonBlocking(target::getProject).executeSynchronously();

        // Standalone FXML files: pre-filter via the word index, then walk each candidate.
        // XML attribute values are indexed as IN_PLAIN_TEXT, so processAllFilesWithWordInText
        // finds files where the method name appears verbatim as content text.
        boolean[] shouldContinue = {true};

        ReadAction.nonBlocking(() -> {
            PsiSearchHelper.getInstance(project).processAllFilesWithWordInText(
                    methodName, globalScope,
                    file -> {
                        if (!(file instanceof XmlFile xmlFile)) return true;
                        if (!Fxml2FileType.isFxml2(xmlFile)) return true;
                        if (collectHandlerRefs(xmlFile, target, consumer)) {
                            shouldContinue[0] = false;
                            return false;
                        }
                        return true;
                    },
                    /* caseSensitively= */ true);
            return null;
        }).executeSynchronously();

        if (!shouldContinue[0]) return false;

        // Embedded FXML markup: injected XML is not indexed, but the host file's text
        // (Java text-block or Kotlin raw string) is indexed as plain text.
        ReadAction.nonBlocking(() -> {
            Fxml2EmbeddedUtil.processAnnotatedClassesContainingWord(
                    methodName, project, globalScope, annotatedClass -> {
                        XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(annotatedClass);
                        if (xmlFile == null) return true;
                        if (collectHandlerRefs(xmlFile, target, consumer)) {
                            shouldContinue[0] = false;
                            return false;
                        }
                        return true;
                    });
            return null;
        }).executeSynchronously();

        return shouldContinue[0];
    }

    /**
     * Walks {@code xmlFile} for {@link XmlAttributeValue}s that are plain method names on
     * {@code EventHandler}-typed properties and resolve to {@code target}, emitting a soft
     * reference for each match. Returns {@code true} if the consumer signalled stop.
     */
    static boolean collectHandlerRefs(
            @NotNull XmlFile xmlFile,
            @NotNull PsiMethod target,
            @NotNull Processor<? super PsiReference> consumer) {

        String targetName = target.getName();
        boolean[] shouldStop = {false};

        xmlFile.accept(new XmlRecursiveElementVisitor() {
            @Override
            public void visitXmlAttributeValue(@NotNull XmlAttributeValue attrVal) {
                super.visitXmlAttributeValue(attrVal);
                if (shouldStop[0]) return;

                String rawValue = attrVal.getValue();
                if (!rawValue.strip().equals(targetName)) return;
                if (Fxml2BindingExpressionParser.looksLikeBindingExpression(rawValue)) return;
                if (!(attrVal.getParent() instanceof XmlAttribute attr)) return;
                String attrLocalName = attr.getLocalName();
                if (Fxml2XmlUtil.isNonPropertyAttribute(attr.getName())) return;
                if (attrLocalName.contains(".")) return;
                if (!(attr.getParent() instanceof XmlTag tag)) return;
                if (!(tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd)) return;
                PsiClass ownerClass = cd.getPsiClass();
                if (ownerClass == null) return;
                List<String> siblings = Fxml2NamedArgResolver.collectAttributeNames(tag);
                PsiType propType = Fxml2AttributeValueResolver.propertyType(ownerClass, attrLocalName, siblings);
                if (!Fxml2EventHandlerUtil.isEventHandlerType(propType, xmlFile.getProject())) return;
                PsiClass codeBehind = Fxml2BindingPathResolver.resolveCodeBehindClass(xmlFile);
                if (codeBehind == null) return;
                PsiClass eventType = Fxml2EventHandlerUtil.extractEventTypeClass(propType);
                PsiMethod bestMethod = Fxml2EventHandlerUtil.findBestHandlerMethod(codeBehind, targetName, eventType);
                if (bestMethod == null) return;
                if (!target.getManager().areElementsEquivalent(bestMethod, target)) return;

                int leadingSpaces = rawValue.length() - rawValue.stripLeading().length();
                TextRange range = new TextRange(1 + leadingSpaces, 1 + leadingSpaces + targetName.length());

                if (!consumer.process(new PsiReferenceBase<>(attrVal, range, /* soft= */ true) {
                    @Override
                    public PsiElement resolve() {
                        return bestMethod;
                    }

                    @Override
                    public boolean isReferenceTo(@NotNull PsiElement element) {
                        return target.getManager().areElementsEquivalent(bestMethod, element);
                    }
                })) {
                    shouldStop[0] = true;
                }
            }
        });
        return shouldStop[0];
    }
}
