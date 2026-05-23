package org.jfxcore.fxml.lang;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;

import java.util.HashSet;
import java.util.Set;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.asJava.KotlinAsJavaSupport;
import org.jetbrains.kotlin.asJava.classes.KtLightClass;
import org.jetbrains.kotlin.asJava.elements.KtLightElementBase;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtStringTemplateExpression;

import java.util.List;

/**
 * Utilities for working with FXML2 markup embedded via the
 * {@code @org.jfxcore.markup.ComponentView} annotation.
 *
 * <p>The language injector wraps the user's markup in a synthetic
 * {@code <fxml2:embedded>} root element that carries the namespace declarations and
 * {@code fx:subclass}. Every method here uses the presence / namespace of that wrapper root
 * to detect embedded FXML2 files without relying on user-data keys.
 */
public final class Fxml2EmbeddedUtil {

    /** FQN of the {@code @ComponentView} annotation processed by the fxml2 compiler. */
    public static final String MARKUP_ANNOTATION_FQN = "org.jfxcore.markup.ComponentView";

    /**
     * Namespace URI placed on the synthetic wrapper root element that the injector adds
     * around the user's markup content.  This is the sole detection signal used by
     * {@link #isEmbeddedFxml2} and {@link #isWrapperRoot}.
     */
    public static final String EMBEDDED_WRAPPER_NS = "http://jfxcore.org/fxml/2.0/embedded";

    /**
     * Qualified element name ({@code fxml2:embedded}) used for the synthetic wrapper root.
     * The {@code fxml2} prefix is bound to {@link #EMBEDDED_WRAPPER_NS}.
     */
    public static final String EMBEDDED_WRAPPER_LOCAL = "fxml2:embedded";

    private Fxml2EmbeddedUtil() {}

    // -----------------------------------------------------------------------
    // Detection helpers
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when {@code file} is an injected XML fragment that originated
     * from a {@code @ComponentView} annotation value.
     *
     * <p>Detection is based on the root element's namespace: the injector always places a
     * {@code <fxml2:embedded xmlns:fxml2=".../embedded" ...>} root that declares
     * {@link #EMBEDDED_WRAPPER_NS}.
     */
    public static boolean isEmbeddedFxml2(@NotNull PsiFile file) {
        if (!(file instanceof XmlFile xmlFile)) return false;
        if (!InjectedLanguageManager.getInstance(file.getProject()).isInjectedFragment(file)) return false;
        var doc = xmlFile.getDocument();
        if (doc == null) return false;
        XmlTag root = doc.getRootTag();
        // Use getName() (raw "prefix:localName") rather than getNamespace() to avoid
        // triggering namespace-map computation, which calls back into XmlExtension.isAvailable
        // -> isFxml2 -> isEmbeddedFxml2 -> getNamespace(): an infinite recursion.
        return root != null && EMBEDDED_WRAPPER_LOCAL.equals(root.getName());
    }

    /**
     * Returns {@code true} when {@code tag} is the synthetic wrapper root element added by
     * the language injector ({@code <fxml2:embedded>}).
     */
    public static boolean isWrapperRoot(@NotNull XmlTag tag) {
        // Use getName() (raw "prefix:localName") rather than getNamespace() to avoid
        // the same namespace-map recursion that affects isEmbeddedFxml2().
        return EMBEDDED_WRAPPER_LOCAL.equals(tag.getName());
    }

    // -----------------------------------------------------------------------
    // Host / class resolution
    // -----------------------------------------------------------------------

    /**
     * Returns the {@link PsiLanguageInjectionHost} (typically a
     * {@link PsiLiteralExpression} text block) that contains the embedded markup, or
     * {@code null} if {@code file} is not an embedded FXML2 file.
     */
    public static @Nullable PsiLanguageInjectionHost getInjectionHost(@NotNull XmlFile file) {
        if (!isEmbeddedFxml2(file)) return null;
        return InjectedLanguageManager.getInstance(file.getProject())
                .getInjectionHost(file.getViewProvider());
    }

    /**
     * Returns the {@link PsiClass} whose {@code @ComponentView} annotation produced this injected
     * FXML2 file, or {@code null} if it cannot be determined.
     *
     * <p>Handles both Java ({@code PsiLiteralExpression} host) and Kotlin
     * ({@code KtStringTemplateExpression} host) injection sources.
     */
    public static @Nullable PsiClass getHostClass(@NotNull XmlFile file) {
        PsiLanguageInjectionHost host = getInjectionHost(file);
        if (host == null) return null;

        // Java path: PsiLiteralExpression -> PsiNameValuePair -> PsiAnnotation -> PsiClass
        PsiAnnotation annotation = PsiTreeUtil.getParentOfType(host, PsiAnnotation.class);
        if (annotation != null) {
            return PsiTreeUtil.getParentOfType(annotation, PsiClass.class);
        }

        // Kotlin path: KtStringTemplateExpression -> KtValueArgument -> KtValueArgumentList
        //              -> KtAnnotationEntry -> KtClassOrObject -> KtLightClass (PsiClass)
        try {
            if (host instanceof KtStringTemplateExpression ktStr) {
                KtAnnotationEntry annotEntry =
                        PsiTreeUtil.getParentOfType(ktStr, KtAnnotationEntry.class);
                if (annotEntry == null) return null;
                KtClassOrObject ktClass =
                        PsiTreeUtil.getParentOfType(annotEntry, KtClassOrObject.class);
                if (ktClass == null) return null;
                return KotlinAsJavaSupport.getInstance(ktClass.getProject())
                        .getLightClass(ktClass);
            }
        } catch (NoClassDefFoundError ignored) {
            // Kotlin plugin not present at runtime, degrade gracefully
        }

        return null;
    }

    // -----------------------------------------------------------------------
    // Lookup: @ComponentView class -> injected XmlFile
    // -----------------------------------------------------------------------

    /**
     * Returns the {@link XmlFile} injected from the {@code @ComponentView} annotation of
     * {@code psiClass}, or {@code null} if {@code psiClass} has no {@code @ComponentView}
     * annotation or if no injection has been computed yet.
     *
     * <p>Handles both Java ({@link PsiLiteralExpression} host) and Kotlin
     * ({@link KtStringTemplateExpression} host) annotated classes.
     */
    public static @Nullable XmlFile getInjectedXmlFile(@NotNull PsiClass psiClass) {
        PsiAnnotation annotation = psiClass.getAnnotation(MARKUP_ANNOTATION_FQN);
        if (annotation == null) return null;

        // Resolve the injection host from the annotation value.
        PsiLanguageInjectionHost host = resolveAnnotationValueHost(psiClass, annotation);
        if (host == null) return null;

        List<com.intellij.openapi.util.Pair<PsiElement, com.intellij.openapi.util.TextRange>> injected =
                InjectedLanguageManager.getInstance(psiClass.getProject()).getInjectedPsiFiles(host);
        if (injected == null) return null;
        for (var pair : injected) {
            if (pair.first instanceof XmlFile xmlFile && isEmbeddedFxml2(xmlFile)) {
                return xmlFile;
            }
        }
        return null;
    }

    /**
     * Resolves the {@link PsiLanguageInjectionHost} that holds the embedded markup for the
     * given {@code @ComponentView} annotation.
     *
     * <ul>
     *   <li><b>Java path</b>: {@code annotation.findAttributeValue("value")} returns a
     *       {@link PsiLiteralExpression} which is directly a {@link PsiLanguageInjectionHost}.
     *   <li><b>Kotlin K2 path</b>: the annotation value is a {@code SymbolPsiAnnotationMemberValue}
     *       (a {@link KtLightElementBase} subclass); its {@code getKotlinOrigin()} returns the
     *       underlying {@link KtStringTemplateExpression}.
     *   <li><b>Kotlin fallback path</b>: when {@code psiClass} is a {@link KtLightClass},
     *       navigate directly from the {@link KtClassOrObject} origin to its
     *       {@link KtAnnotationEntry} to obtain the {@link KtStringTemplateExpression}.
     * </ul>
     */
    private static @Nullable PsiLanguageInjectionHost resolveAnnotationValueHost(
            @NotNull PsiClass psiClass, @NotNull PsiAnnotation annotation) {

        // Java path: returns PsiLiteralExpression directly
        PsiAnnotationMemberValue value = annotation.findAttributeValue("value");
        if (value instanceof PsiLanguageInjectionHost host) return host;

        // Kotlin K2 path: SymbolPsiAnnotationMemberValue extends KtLightElementBase;
        // getKotlinOrigin() returns the actual KtStringTemplateExpression.
        try {
            if (value instanceof KtLightElementBase ktLight) {
                var origin = ktLight.getKotlinOrigin();
                if (origin instanceof PsiLanguageInjectionHost host) return host;
            }
        } catch (NoClassDefFoundError ignored) {
            // Kotlin plugin not present
        }

        // Kotlin fallback path: navigate directly from the KtClassOrObject.
        try {
            if (psiClass instanceof KtLightClass ktLightClass) {
                KtClassOrObject ktClass = ktLightClass.getKotlinOrigin();
                if (ktClass == null) return null;
                return getKotlinMarkupStringExpression(ktClass);
            }
        } catch (NoClassDefFoundError ignored) {
            // Kotlin plugin not present
        }

        return null;
    }

    /**
     * Finds the {@link KtStringTemplateExpression} that is the value of the first
     * {@code @ComponentView} annotation on {@code ktClass}.
     *
     * <p>This is the direct-navigation fallback used when the light-class annotation API
     * does not return a {@link PsiLanguageInjectionHost} (e.g. Kotlin K2 symbol-based
     * light classes with computed annotation values).
     */
    private static @Nullable PsiLanguageInjectionHost getKotlinMarkupStringExpression(
            @NotNull KtClassOrObject ktClass) {
        for (KtAnnotationEntry annotEntry : ktClass.getAnnotationEntries()) {
            var shortName = annotEntry.getShortName();
            if (shortName == null || !"ComponentView".equals(shortName.getIdentifier())) continue;
            var args = annotEntry.getValueArguments();
            if (args.isEmpty()) continue;
            var argExpr = args.getFirst().getArgumentExpression();
            if (argExpr instanceof PsiLanguageInjectionHost host) return host;
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Annotated-class search
    // -----------------------------------------------------------------------

    /**
     * Invokes {@code processor} for every {@link PsiClass} in {@code scope} that is
     * annotated with {@code @ComponentView}.
     *
     * <p>Returns silently (without calling {@code processor}) when the annotation class
     * {@link #MARKUP_ANNOTATION_FQN} is not present on the project's classpath.
     */
    public static void processAnnotatedClasses(
            @NotNull Project project,
            @NotNull GlobalSearchScope scope,
            @NotNull Processor<? super PsiClass> processor) {
        PsiClass annotationClass = JavaPsiFacade.getInstance(project)
                .findClass(MARKUP_ANNOTATION_FQN, GlobalSearchScope.allScope(project));
        if (annotationClass == null) return;
        AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope)
                .forEach(processor);
    }

    /**
     * Invokes {@code processor} for every {@link PsiClass} annotated with
     * {@code @ComponentView} whose embedded markup text-block contains {@code word}.
     *
     * <p>This is a more efficient alternative to {@link #processAnnotatedClasses} for
     * reference searches, instead of walking every {@code @ComponentView}-annotated class
     * in the project, it uses IntelliJ's word index to pre-filter to only those host
     * Java/Kotlin files that actually contain the search term in their text-block content.
     *
     * <h3>Index context mapping</h3>
     * <ul>
     *   <li><b>Java</b>: {@code TEXT_BLOCK_LITERAL} tokens are indexed as
     *       {@link com.intellij.psi.search.UsageSearchContext#IN_PLAIN_TEXT} by
     *       {@code JavaFilterLexer} (they do not match the {@code STRING_LITERAL} branch
     *       or the skip-word set, so they fall through to the plain-text catch-all).
     *       Discovered via {@link PsiSearchHelper#processAllFilesWithWordInText}.</li>
     *   <li><b>Kotlin</b>: raw (triple-quoted) string templates are indexed as
     *       {@link com.intellij.psi.search.UsageSearchContext#IN_STRINGS} by
     *       {@code KotlinFilterLexer}.
     *       Discovered via {@link PsiSearchHelper#processAllFilesWithWordInLiterals}.</li>
     * </ul>
     *
     * <p>The two candidate sets are unioned into a narrowed scope that is then passed to
     * {@link #processAnnotatedClasses}, which uses the stub index to find only the
     * {@code @ComponentView}-annotated classes within those files.  Files that happen to
     * contain the word in other contexts (e.g. comments, regular string literals) may
     * appear in the candidate set but will produce no annotated classes, incurring at most
     * a cheap stub-index miss.
     *
     * <p><b>Must be called inside a read action.</b>
     *
     * @param word       the word to look up in the index (e.g. simple class name, property
     *                   name, or CSS class name)
     * @param project    the current project
     * @param scope      the search scope (annotated classes outside this scope are ignored)
     * @param processor  receives each matching {@link PsiClass}; return {@code false} to
     *                   stop early
     */
    public static void processAnnotatedClassesContainingWord(
            @NotNull String word,
            @NotNull Project project,
            @NotNull GlobalSearchScope scope,
            @NotNull Processor<? super PsiClass> processor) {

        PsiSearchHelper helper = PsiSearchHelper.getInstance(project);
        Set<VirtualFile> candidateFiles = new HashSet<>();

        // Java: TEXT_BLOCK_LITERAL content is indexed as IN_PLAIN_TEXT
        helper.processAllFilesWithWordInText(word, scope, file -> {
            VirtualFile vf = file.getVirtualFile();
            if (vf != null) candidateFiles.add(vf);
            return true;
        }, /* caseSensitively= */ true);

        // Kotlin: raw string template content is indexed as IN_STRINGS
        helper.processAllFilesWithWordInLiterals(word, scope, file -> {
            VirtualFile vf = file.getVirtualFile();
            if (vf != null) candidateFiles.add(vf);
            return true;
        });

        if (candidateFiles.isEmpty()) return;

        GlobalSearchScope narrowedScope = GlobalSearchScope.filesScope(project, candidateFiles);
        processAnnotatedClasses(project, narrowedScope, processor);
    }

    // -----------------------------------------------------------------------
    // fx:id search within embedded markup
    // -----------------------------------------------------------------------

    /**
     * Walks the injected FXML2 XML of every {@code @ComponentView}-annotated class in
     * {@code scope} that is the same as, or a subclass of, {@code containingClass},
     * and calls {@code consumer} for every {@code fx:id} attribute value equal to
     * {@code idName}.
     *
     * <p>This is the embedded-FXML2 counterpart of the filename-index scan in
     * {@link Fxml2FxIdFieldSearcher}.
     */
    public static void findFxIdInEmbedded(
            @NotNull String idName,
            @NotNull PsiClass containingClass,
            @NotNull GlobalSearchScope scope,
            @NotNull Processor<? super com.intellij.psi.xml.XmlAttributeValue> consumer) {

        String containingFqn = containingClass.getQualifiedName();
        if (containingFqn == null) return;
        Project project = containingClass.getProject();

        processAnnotatedClassesContainingWord(idName, project, scope, annotatedClass -> {
            String annotatedFqn = ReadAction.compute(annotatedClass::getQualifiedName);
            if (annotatedFqn == null) return true;

            boolean matches = ReadAction.compute(() ->
                    annotatedFqn.equals(containingFqn)
                    || InheritanceUtil.isInheritor(annotatedClass, containingFqn));
            if (!matches) return true;

            XmlFile xmlFile = ReadAction.compute(() -> getInjectedXmlFile(annotatedClass));
            if (xmlFile == null) return true;

            ReadAction.run(() -> xmlFile.accept(new com.intellij.psi.XmlRecursiveElementVisitor() {
                @Override
                public void visitXmlAttribute(@NotNull com.intellij.psi.xml.XmlAttribute attr) {
                    super.visitXmlAttribute(attr);
                    if (!"id".equals(attr.getLocalName())) return;
                    if (!org.jfxcore.fxml.resolve.Fxml2ImportResolver.FXML2_NAMESPACE
                            .equals(attr.getNamespace())) return;
                    if (!idName.equals(attr.getValue())) return;
                    var valEl = attr.getValueElement();
                    if (valEl != null) consumer.process(valEl);
                }
            }));
            return true;
        });
    }

    /**
     * Finds the {@link com.intellij.psi.xml.XmlAttributeValue} of the first {@code fx:id}
     * declaration matching {@code field.getName()} in any embedded FXML2 of classes that
     * are or extend {@code field.getContainingClass()}.
     *
     * <p>Used by {@link Fxml2FxIdFindUsagesHandlerFactory} to resolve a Java field to its
     * embedded declaration site.
     */
    public static @Nullable com.intellij.psi.xml.XmlAttributeValue findFxIdInEmbedded(
            @NotNull com.intellij.psi.PsiField field) {
        PsiClass containingClass = field.getContainingClass();
        if (containingClass == null) return null;
        String fieldName = field.getName();
        Project project = field.getProject();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

        var result = new com.intellij.psi.xml.XmlAttributeValue[1];
        findFxIdInEmbedded(fieldName, containingClass, scope, attrVal -> {
            result[0] = attrVal;
            return false; // stop after first match
        });
        return result[0];
    }
}
