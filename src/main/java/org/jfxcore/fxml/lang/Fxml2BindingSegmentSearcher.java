package org.jfxcore.fxml.lang;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.XmlRecursiveElementVisitor;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.Processor;
import com.intellij.util.QueryExecutor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.asJava.LightClassUtilsKt;
import org.jfxcore.fxml.resolve.Fxml2PropertyNameUtil;

/**
 * {@link ReferencesSearch} extension that finds {@link Fxml2BindingSegmentReference}s
 * in FXML files that resolve to a given Java element.
 *
 * <p>This is needed for <em>identifier-under-caret highlighting</em>, when the caret is
 * on a binding-path segment (e.g. {@code disabled} in
 * {@code {fx:Synchronize scene.root.disabled}}), IntelliJ resolves the reference
 * to the corresponding Java element (e.g. {@code Node.disabledProperty()}) and then calls
 * {@code ReferencesSearch.search(javaElement, LocalSearchScope(fxmlFile))} to find all
 * occurrences in the file that should be highlighted.
 *
 * <p>Without this searcher, the word-based {@code CachesBasedRefSearcher} looks for the
 * literal method name (e.g. {@code "disabledProperty"}) in the XML text and finds nothing,
 * because the segment name in the binding expression ({@code "disabled"}) does not match
 * the JavaFX property-getter name.
 *
 * <p>This searcher handles both:
 * <ul>
 *   <li><b>{@link LocalSearchScope}</b>: identifier-under-caret highlighting.  Walks all
 *       {@link XmlAttributeValue} elements in each FXML file in the scope and checks
 *       whether any {@link Fxml2BindingSegmentReference} resolves to the target element via
 *       {@link PsiReference#isReferenceTo}.</li>
 *   <li><b>{@link GlobalSearchScope}</b>: "Find Usages" on a property accessor method or
 *       field.  The word-based {@code CachesBasedRefSearcher} cannot find these usages
 *       because the binding-path segment text (e.g. {@code "vm"}) differs from the method
 *       name (e.g. {@code "vmProperty"}).  This searcher extracts the property name,
 *       searches standalone FXML files via the word index, and also searches embedded
 *       FXML markup directly (since injected fragments are not indexed).  This ensures
 *       "Find Usages" on {@code vmProperty()} correctly shows the {@code "vm"} use sites
 *       in the markup.</li>
 * </ul>
 *
 * <p>For <em>embedded</em> FXML files (injected via {@code @ComponentView}), the local
 * scope searcher also adds a synthetic reference for the host Kotlin/Java declaration that
 * the target element was resolved from.  This ensures that identifier-under-caret
 * highlighting from a binding expression segment (e.g. {@code vm} in
 * {@code {fx:Observe vm.message}}) also lights up the corresponding field/property declaration
 * in the enclosing class (e.g. {@code val vm = MainViewModel()} in the host Kotlin file),
 * completing the symmetric bidirectional highlighting behavior.
 *
 * <p>Two complementary code paths achieve this:
 * <ol>
 *   <li><b>Host file in scope</b> ({@link #addDeclarationReferenceForHostFile}): when the
 *       host Kotlin/Java file appears as a scope element, checks whether the target's
 *       navigation element lives there, confirms that the file's embedded FXML markup
 *       references the target, and emits a synthetic reference on the declaration's
 *       name-identifier token.</li>
 * </ol>
 * <p>The synthetic reference must be emitted from the host-file branch exclusively.
 * Emitting it from inside the XML-file branch would cause spurious highlights because
 * IntelliJ's {@code IdentifierHighlighterPass} for the injected XML document translates
 * every reference element position into the injected coordinate system; a reference whose
 * element lives in the <em>host</em> file ends up at the wrong injected offset, lighting
 * up an unrelated identifier.
 */
public final class Fxml2BindingSegmentSearcher
        implements QueryExecutor<PsiReference, ReferencesSearch.SearchParameters> {

    @Override
    public boolean execute(
            @NotNull ReferencesSearch.SearchParameters params,
            @NotNull Processor<? super PsiReference> consumer) {

        PsiElement target = params.getElementToSearch();
        SearchScope scope = params.getScopeDeterminedByUser();

        if (scope instanceof LocalSearchScope localScope) {
            // Local scope: identifier-under-caret highlighting (existing behavior).
            for (PsiElement scopeElement : localScope.getScope()) {
                PsiFile file = scopeElement instanceof PsiFile f
                        ? f
                        : scopeElement.getContainingFile();

                if (file instanceof XmlFile xmlFile && Fxml2FileType.isFxml2(xmlFile)) {
                    // Walk the FXML file for binding-segment references to the target.
                    collectMatchingSegments(xmlFile, target, consumer);

                } else {
                    // Non-XML file in scope (typically the host Kotlin/Java file):
                    // 1. Add a synthetic declaration reference (XML->Java direction: target is
                    //    from a stub and its navigation element lives in this file).
                    addDeclarationReferenceForHostFile(file, target, consumer);
                    // 2. Walk embedded XML for direct binding segments (Java->XML direction:
                    //    cursor is on the source declaration itself, e.g. vmProperty() in the
                    //    Java file; highlight the "vm" segments in the embedded markup).
                    walkEmbeddedXmlInHostFile(file, target, consumer);
                }
            }
            return true;
        }

        // Global scope: "Find Usages" on a property accessor / field.
        //
        // CachesBasedRefSearcher looks for the literal method name in the FXML text and
        // finds nothing because the binding segment (e.g. "vm") differs from the accessor
        // name (e.g. "vmProperty").  Search here instead.
        SearchScope effectiveScope = params.getEffectiveSearchScope();
        if (effectiveScope instanceof GlobalSearchScope globalScope) {
            handleGlobalSearch(target, globalScope, consumer);
        }

        return true;
    }

    // -----------------------------------------------------------------------
    // Global search for property accessors
    // -----------------------------------------------------------------------

    /**
     * Handles "Find Usages" (GlobalSearchScope) for property accessors referenced as
     * binding-path segments in FXML files.
     *
     * <p>When the target is {@code vmProperty()}, the markup uses "vm" (not "vmProperty")
     * as the segment text, so the word-based {@code CachesBasedRefSearcher} cannot find
     * the references.  This method extracts the property name ("vm") and:
     * <ol>
     *   <li>Searches standalone FXML files via the word index.</li>
     *   <li>Searches embedded FXML markup in the target's containing class directly
     *       (injected fragments are not indexed, so word search cannot reach them).</li>
     * </ol>
     */
    static void handleGlobalSearch(
            @NotNull PsiElement target,
            @NotNull GlobalSearchScope globalScope,
            @NotNull Processor<? super PsiReference> consumer) {

        String propertyName = ReadAction.nonBlocking(() -> Fxml2PropertyNameUtil.propertyNameFromElement(target)).executeSynchronously();
        if (propertyName == null) return;

        Project project = ReadAction.nonBlocking(target::getProject).executeSynchronously();

        // 1. Standalone FXML files: use the word index to find candidates efficiently.
        //    processAllFilesWithWord, and everything it calls transitively, requires a
        //    read action held for the entire duration (same pattern as Fxml2FxIdFieldSearcher).
        ReadAction.nonBlocking(() -> {
            PsiSearchHelper.getInstance(project).processAllFilesWithWord(
                    propertyName,
                    globalScope,
                    file -> {
                        if (!(file instanceof XmlFile xmlFile)) return true;
                        if (!Fxml2FileType.isFxml2(xmlFile)) return true;
                        collectMatchingSegments(xmlFile, target, consumer);
                        return true;
                    },
                    false);
            return null;
        }).executeSynchronously();

        // 2. Embedded FXML markup: injected XML is not indexed, but Java text-block content
        //    is indexed as IN_PLAIN_TEXT (and Kotlin raw strings as IN_STRINGS), so use the
        //    word index to pre-filter to only the host files containing the property name.
        //    The target may be a member of a non-@ComponentView class (e.g. a view-model)
        //    referenced via a multi-segment path, so all annotated classes in scope are candidates.
        ReadAction.nonBlocking(() -> {
            Fxml2EmbeddedUtil.processAnnotatedClassesContainingWord(propertyName, project, globalScope, annotatedClass -> {
                XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(annotatedClass);
                if (xmlFile == null) return true;
                collectMatchingSegments(xmlFile, target, consumer);
                return true;
            });
            return null;
        }).executeSynchronously();
    }


    // -----------------------------------------------------------------------
    // Synthetic declaration-reference helpers (local scope / embedded)
    // -----------------------------------------------------------------------

    /**
     * When cursor is on a Java/Kotlin field or method in the host file (e.g.
     * {@code vmProperty()} in {@code MainView.java}), walks the embedded FXML markup
     * associated with the target's class and emits any {@link Fxml2BindingSegmentReference}s
     * that resolve to {@code target}.
     *
     * <p>This covers the <em>Java->XML</em> identifier-highlighting direction:
     * cursor on {@code vmProperty()} -> the {@code "vm"} binding segments in the
     * embedded text-block markup are highlighted as well.
     *
     * <p>The word-based {@code CachesBasedRefSearcher} cannot handle this because the
     * segment text ({@code "vm"}) differs from the method name ({@code "vmProperty"}),
     * and the LocalSearchScope used for identifier highlighting usually contains only the
     * host Java file, not the injected XML document.
     *
     * <p>Only runs when {@code target} is declared directly in {@code file} (i.e. the
     * cursor is on the source declaration itself, not on a compiled/stub element).
     */
    private static void walkEmbeddedXmlInHostFile(
            @NotNull PsiFile file,
            @NotNull PsiElement target,
            @NotNull Processor<? super PsiReference> consumer) {
        PsiFile targetFile = target.getContainingFile();
        if (targetFile == null || !targetFile.equals(file)) return;

        XmlFile xmlFile = getEmbeddedXmlFile(target);
        if (xmlFile == null) return;

        collectMatchingSegments(xmlFile, target, consumer);
    }

    /**
     * Handles the case where the host Kotlin/Java file (not the injected XML file) is
     * the scope element.  If {@code target}'s navigation element lives in {@code file},
     * the declaration differs from {@code target} itself (direction-2 guard), and the
     * file's embedded FXML markup has at least one binding segment that resolves to
     * {@code target}, emits a synthetic reference for the declaration's name identifier.
     */
    private static void addDeclarationReferenceForHostFile(
            @NotNull PsiFile file,
            @NotNull PsiElement target,
            @NotNull Processor<? super PsiReference> consumer) {

        PsiElement navEl = target.getNavigationElement();
        if (navEl == null) navEl = target;

        // Direction-1 guard: same identity check as in addHostDeclarationReference.
        if (target == navEl) return;

        // The navigation element must live in the file we are currently processing.
        PsiFile navFile = navEl.getContainingFile();
        if (navFile == null || !navFile.equals(file)) return;

        // Obtain the embedded FXML file for the class that owns navEl.
        XmlFile xmlFile = getEmbeddedXmlFile(navEl);
        if (xmlFile == null) return;

        // Only emit if the XML actually references the target, avoiding false positives
        // when multiple classes in the same file have @ComponentView annotations.
        boolean[] found = {false};
        xmlFile.accept(new XmlRecursiveElementVisitor() {
            @Override
            public void visitXmlAttributeValue(@NotNull XmlAttributeValue attrValue) {
                if (found[0]) return;
                for (PsiReference ref : attrValue.getReferences()) {
                    if (ref instanceof Fxml2BindingSegmentReference
                            && ref.isReferenceTo(target)) {
                        found[0] = true;
                        return;
                    }
                }
            }
        });
        if (!found[0]) return;

        PsiElement nameId = getNameIdentifier(navEl);
        if (nameId == null) return;

        emitDeclarationReference(nameId, navEl, target, consumer);
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    /**
     * Returns the injected FXML {@link XmlFile} associated with the {@code @ComponentView}
     * annotation of the class that contains {@code navEl}, or {@code null} if there is
     * none.
     *
     * <p>Tries the Kotlin path first ({@code KtClassOrObject} -> {@code KtLightClass} ->
     * {@link Fxml2EmbeddedUtil#getInjectedXmlFile}), falling back to the Java path
     * ({@code PsiClass} -> {@link Fxml2EmbeddedUtil#getInjectedXmlFile}).
     */
    private static @Nullable XmlFile getEmbeddedXmlFile(@NotNull PsiElement navEl) {
        try {
            var ktClass = PsiTreeUtil.getParentOfType(
                    navEl, org.jetbrains.kotlin.psi.KtClassOrObject.class, false);
            if (ktClass != null) {
                var lc = LightClassUtilsKt.toLightClass(ktClass);
                if (lc != null) {
                    XmlFile xml = Fxml2EmbeddedUtil.getInjectedXmlFile(lc);
                    if (xml != null) return xml;
                }
            }
        } catch (NoClassDefFoundError ignored) {
            // Kotlin plugin absent at runtime
        }

        PsiClass cls = PsiTreeUtil.getParentOfType(navEl, PsiClass.class, false);
        return cls != null ? Fxml2EmbeddedUtil.getInjectedXmlFile(cls) : null;
    }

    /**
     * Creates and feeds a synthetic {@link PsiReferenceBase} to {@code consumer}.
     * The reference's {@link PsiReference#getElement()} is {@code nameId} (the
     * declaration's name-identifier token), and {@link PsiReference#resolve()} returns
     * {@code navEl}.
     */
    private static void emitDeclarationReference(
            @NotNull PsiElement nameId,
            @NotNull PsiElement navEl,
            @NotNull PsiElement target,
            @NotNull Processor<? super PsiReference> consumer) {

        TextRange range = new TextRange(0, nameId.getTextLength());
        consumer.process(new PsiReferenceBase<>(nameId, range, /* soft= */ true) {
            @Override
            public @NotNull PsiElement resolve() {
                return navEl;
            }

            @Override
            public boolean isReferenceTo(@NotNull PsiElement element) {
                var mgr = getElement().getManager();
                return mgr.areElementsEquivalent(navEl, element)
                        || mgr.areElementsEquivalent(target, element);
            }
        });
    }

    /**
     * Walks all {@link XmlAttributeValue} nodes in {@code xmlFile} and forwards every
     * {@link Fxml2BindingSegmentReference} that {@link PsiReference#isReferenceTo resolves
     * to} {@code target} to {@code consumer}.
     */
    private static void collectMatchingSegments(
            @NotNull XmlFile xmlFile,
            @NotNull PsiElement target,
            @NotNull Processor<? super PsiReference> consumer) {
        xmlFile.accept(new XmlRecursiveElementVisitor() {
            @Override
            public void visitXmlAttributeValue(@NotNull XmlAttributeValue attrValue) {
                for (PsiReference ref : attrValue.getReferences()) {
                    if (ref instanceof Fxml2BindingSegmentReference segRef
                            && segRef.isReferenceTo(target)) {
                        if (!consumer.process(ref)) return;
                    }
                }
            }
        });
    }

    /** Returns the name identifier of {@code element} when it is a named declaration. */
    private static @Nullable PsiElement getNameIdentifier(@NotNull PsiElement element) {
        if (element instanceof PsiNameIdentifierOwner owner) {
            return owner.getNameIdentifier();
        }
        return null;
    }
}
