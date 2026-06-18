package org.jfxcore.fxml.lang;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.XmlRecursiveElementVisitor;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.Processor;
import com.intellij.util.QueryExecutor;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;

/**
 * {@link ReferencesSearch} extension that, when searching for usages of a Java/Kotlin
 * field or no-arg method in a code-behind class, also finds the corresponding
 * {@code fx:id} attribute value in all FXML files that declare that class via
 * {@code fx:subclass}.
 *
 * <p>This makes "Find Usages" on a code-behind field show the FXML definition site
 * alongside normal code usages, and makes Ctrl+click from the field navigate back to
 * the FXML declaration.
 */
public final class Fxml2FxIdFieldSearcher
        implements QueryExecutor<PsiReference, ReferencesSearch.SearchParameters> {

    @Override
    public boolean execute(
            @NotNull ReferencesSearch.SearchParameters params,
            @NotNull Processor<? super PsiReference> consumer) {

        PsiElement element = params.getElementToSearch();

        // Resolve member name and containing class inside a read action first.
        // Explicitly skip XmlAttributeValue: when Find Usages is invoked on the fx:id
        // declaration, getPrimaryElements() returns both the XmlAttributeValue and the
        // generated field. We must not process the XmlAttributeValue here or we would
        // re-report the declaration itself as a usage.
        String memberName = ReadAction.nonBlocking(() -> switch (element) {
            case XmlAttributeValue ignored -> null;
            case PsiField f -> f.getName();
            case PsiMethod m when m.getParameterList().isEmpty() -> m.getName();
            default -> null;
        }).executeSynchronously();
        if (memberName == null) return true;

        PsiClass containingClass = ReadAction.nonBlocking(() -> {
            if (element instanceof PsiField f) return f.getContainingClass();
            if (element instanceof PsiMethod m) return m.getContainingClass();
            return null;
        }).executeSynchronously();
        if (containingClass == null) return true;

        String qualifiedName = ReadAction.nonBlocking(containingClass::getQualifiedName).executeSynchronously();
        if (qualifiedName == null) return true;

        Project project = ReadAction.nonBlocking(element::getProject).executeSynchronously();
        SearchScope searchScope = params.getEffectiveSearchScope();
        if (!(searchScope instanceof GlobalSearchScope globalScope)) return true;

        // processAllFilesWithWord: and everything it calls transitively: requires a
        // read action held for the entire duration. Wrap the whole call in one ReadAction
        // rather than piecemeal inner wrappers, which leave gaps between index accesses.
        ReadAction.nonBlocking(() -> {
            PsiSearchHelper.getInstance(project).processAllFilesWithWord(
                    memberName,
                    globalScope,
                    file -> {
                        if (!(file instanceof XmlFile xmlFile)) return true;
                        if (!Fxml2FileType.isFxml2(xmlFile)) return true;
                        if (!globalScope.contains(file.getViewProvider().getVirtualFile())) return true;

                        // Verify this file's fx:subclass is the containing class or a subclass.
                        XmlTag root = xmlFile.getRootTag();
                        String declaredClass = root != null ? root.getAttributeValue("fx:subclass") : null;
                        if (declaredClass == null) return true;

                        boolean matches = declaredClass.equals(qualifiedName);
                        if (!matches) {
                            PsiClass sub = JavaPsiFacade.getInstance(project)
                                    .findClass(declaredClass, globalScope);
                            matches = InheritanceUtil.isInheritor(sub, qualifiedName);
                        }
                        if (!matches) return true;

                        // Walk the file looking for fx:id="<memberName>".
                        xmlFile.accept(new XmlRecursiveElementVisitor() {
                            @Override
                            public void visitXmlAttribute(@NotNull XmlAttribute attr) {
                                super.visitXmlAttribute(attr);
                                if (!"id".equals(attr.getLocalName())) return;
                                if (!Fxml2ImportResolver.FXML2_NAMESPACE.equals(attr.getNamespace())) return;
                                if (!memberName.equals(attr.getValue())) return;

                                XmlAttributeValue valueEl = attr.getValueElement();
                                if (valueEl == null) return;

                                for (PsiReference ref : valueEl.getReferences()) {
                                    if (ref instanceof Fxml2FxIdReference) {
                                        consumer.process(ref);
                                        return;
                                    }
                                }
                            }
                        });
                        return true;
                    },
                    false);
            return null;
        }).executeSynchronously();

        // Also search embedded FXML markup in @ComponentView-annotated classes.
        // Note: FxIdReferenceProvider is registered with inVirtualFile(ofType(Fxml2FileType)),
        // which does not match injected XML files.  So attrVal.getReferences() on an injected
        // XmlAttributeValue will never contain a Fxml2FxIdReference.  Create one directly.
        ReadAction.nonBlocking(() -> {
            Fxml2EmbeddedUtil.findFxIdInEmbedded(memberName, containingClass, globalScope,
                    attrVal -> {
                        consumer.process(new Fxml2FxIdReference(attrVal));
                        return false; // found it
                    });
            return null;
        }).executeSynchronously();

        return true;
    }
}
