package org.jfxcore.fxml.lang;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.PsiReference;
import com.intellij.psi.XmlRecursiveElementVisitor;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resolve.Fxml2BindingPathResolver;

/**
 * Shared utility that collects all usages of an {@code fx:id} declaration.
 *
 * <p>Both the PSI find-usages handler ({@link Fxml2FxIdFindUsagesHandlerFactory})
 * and the symbol-based usage searcher ({@link Fxml2FxIdUsageSearcher}) delegate here
 * so that the two paths always return identical results.
 *
 * <p>The collected usages are:
 * <ol>
 *   <li>Binding-segment references in the containing FXML file that
 *       {@link Fxml2BindingSegmentReference#isReferenceTo resolve to} the declaration.</li>
 *   <li>Code-behind field (or no-arg method) usages found via
 *       {@link ReferencesSearch} in the given scope.</li>
 *   <li>The name identifier of the code-behind field or method (declaration entry).</li>
 * </ol>
 *
 * <p>The fx:id declaration line itself is always excluded.
 *
 * <p>May be called from a background thread without a read lock; all PSI access
 * is internally wrapped in {@code ReadAction.nonBlocking} as required.
 */
final class Fxml2FxIdUsageCollector {

    private Fxml2FxIdUsageCollector() {}

    /**
     * Collects all usages of {@code declaration} and delivers them to the provided processors.
     *
     * @param declaration   the {@code fx:id} {@link XmlAttributeValue} whose usages are sought
     * @param scope         the search scope for code-behind member usages
     * @param refProcessor  receives each found {@link PsiReference}; returning {@code false} stops collection
     * @param declProcessor receives the code-behind member's name identifier; returning {@code false} stops collection
     * @return {@code false} if any processor signaled early stop, {@code true} otherwise
     */
    static boolean collect(
            @NotNull XmlAttributeValue declaration,
            @NotNull SearchScope scope,
            @NotNull Processor<? super PsiReference> refProcessor,
            @NotNull Processor<? super PsiElement> declProcessor) {

        // -----------------------------------------------------------------------
        // Step 1: binding-segment references in the FXML file
        // -----------------------------------------------------------------------
        boolean[] proceed = {true};
        ReadAction.nonBlocking(() -> {
            if (!(declaration.getContainingFile() instanceof XmlFile xmlFile)) return null;
            xmlFile.accept(new XmlRecursiveElementVisitor() {
                @Override
                public void visitXmlAttributeValue(@NotNull XmlAttributeValue candidate) {
                    super.visitXmlAttributeValue(candidate);
                    if (!proceed[0]) return;
                    if (candidate.getManager().areElementsEquivalent(candidate, declaration)) return;
                    for (PsiReference ref : candidate.getReferences()) {
                        if (ref instanceof Fxml2BindingSegmentReference segRef
                                && segRef.isReferenceTo(declaration)) {
                            if (!proceed[0]) return;
                            proceed[0] = refProcessor.process(ref);
                        }
                    }
                }
            });
            return null;
        }).executeSynchronously();
        if (!proceed[0]) return false;

        // -----------------------------------------------------------------------
        // Step 2: code-behind field/method usages and declaration
        // -----------------------------------------------------------------------
        String idName = ReadAction.nonBlocking(declaration::getValue).executeSynchronously();
        if (idName.isBlank()) return true;

        XmlFile xmlFile = ReadAction.nonBlocking(() ->
                declaration.getContainingFile() instanceof XmlFile f ? f : null
        ).executeSynchronously();
        if (xmlFile == null) return true;

        PsiClass codeBehind = ReadAction.nonBlocking(
                () -> Fxml2BindingPathResolver.resolveCodeBehindClass(xmlFile)
        ).executeSynchronously();
        if (codeBehind == null) return true;

        PsiField field = ReadAction.nonBlocking(
                () -> codeBehind.findFieldByName(idName, true)
        ).executeSynchronously();
        if (field != null) {
            return collectMemberUsages(field, declaration, scope, refProcessor, declProcessor);
        }

        PsiMember method = ReadAction.nonBlocking(() -> {
            for (PsiMethod m : codeBehind.findMethodsByName(idName, true)) {
                if (m.getParameterList().isEmpty()) {
                    return (PsiMember) m;
                }
            }
            return null;
        }).executeSynchronously();
        if (method != null) {
            return collectMemberUsages(method, declaration, scope, refProcessor, declProcessor);
        }

        return true;
    }

    // -----------------------------------------------------------------------
    // Implementation
    // -----------------------------------------------------------------------

    /**
     * Runs {@link ReferencesSearch} on the code-behind {@code member} and delivers results
     * to the processors.  Binding-segment references are skipped here because they are
     * already collected in step 1 of {@link #collect}.
     */
    private static boolean collectMemberUsages(
            @NotNull PsiMember member,
            @NotNull XmlAttributeValue declaration,
            @NotNull SearchScope scope,
            @NotNull Processor<? super PsiReference> refProcessor,
            @NotNull Processor<? super PsiElement> declProcessor) {

        // Code usages via standard reference search.
        // Binding-segment refs are filtered out — already collected in step 1.
        boolean[] proceed = {true};
        ReferencesSearch.search(member, scope).forEach(ref -> {
            if (!proceed[0]) return false;
            // Skip binding-segment refs: found by the FXML walk in step 1.
            if (ref instanceof Fxml2BindingSegmentReference) return true;
            // Skip the fx:id declaration element itself.
            PsiElement refEl = ReadAction.nonBlocking(ref::getElement).executeSynchronously();
            boolean isDecl = ReadAction.nonBlocking(
                    () -> declaration.getManager().areElementsEquivalent(refEl, declaration)
            ).executeSynchronously();
            if (isDecl) return true;
            proceed[0] = refProcessor.process(ref);
            return proceed[0];
        });
        if (!proceed[0]) return false;

        // Member declaration (field/method name identifier).
        PsiElement nameId = ReadAction.nonBlocking(() ->
                member instanceof PsiNameIdentifierOwner owner ? owner.getNameIdentifier() : null
        ).executeSynchronously();
        if (nameId != null) {
            return declProcessor.process(nameId);
        }

        return true;
    }
}
