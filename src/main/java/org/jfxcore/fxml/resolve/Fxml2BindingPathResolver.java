package org.jfxcore.fxml.resolve;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jfxcore.fxml.descriptors.Fxml2ClassTagDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser.ContextSelector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves FXML binding path segments against the code-behind class declared via
 * {@code fx:subclass} on the root tag.
 *
 * <p>A binding path like {@code viewModel.someCommand} is a dot-separated chain where:
 * <ul>
 *   <li>The first segment is resolved against the <em>code-behind class</em> (the class
 *       named in the root tag's {@code fx:subclass} attribute).</li>
 *   <li>Each subsequent segment is resolved against the <em>property type</em> returned
 *       by the previous segment, with {@code ObservableValue<T>} automatically unwrapped
 *       to {@code T}.</li>
 * </ul>
 *
 * <p>Resolution mirrors the fxml-compiler's {@code Resolver.tryResolveProperty}:
 * accepts plain fields, Java Beans getters ({@code getFoo()}/{@code isFoo()}), and
 * JavaFX Beans property getters ({@code fooProperty()}).
 */
public final class Fxml2BindingPathResolver {

    private Fxml2BindingPathResolver() {}

    /**
     * Per-file cache for resolved binding paths.
     * Map key: {@code "startClass.fqn|path|kindName"}.
     * Stored on the XmlFile and invalidated when the file itself changes or when Java-language
     * PSI changes (because binding paths reference Java class members). Using these two targeted
     * dependencies instead of the global modification tracker avoids discarding cached paths when
     * unrelated non-Java files (other FXML, CSS) are edited.
     */
    private static final Key<CachedValue<ConcurrentHashMap<String, List<Segment>>>>
            BINDING_PATH_CACHE = Key.create("fxml2.bindingPath");

    // -----------------------------------------------------------------------
    // Path segment record
    // -----------------------------------------------------------------------

    /**
     * One resolved segment of a binding path.
     *
     * @param name               the segment text (e.g. {@code "viewModel"}, {@code "someCommand"})
     * @param declaration        the PSI element the segment resolves to, or {@code null} if unresolved
     * @param resultType         the property type after unwrapping ObservableValue, or {@code null}
     * @param classQualifier     {@code true} when this segment is a class or package name qualifier,
     *                           not a value-producing member reference
     * @param observableSelector {@code true} when this segment was accessed via the {@code ::}
     *                           (observable-selection) operator; the FXML compiler requires the
     *                           member to be an {@code ObservableValue} subtype in that case
     * @param pathOffset         start offset of the segment name within the path string that was
     *                           passed to {@link #resolve(String, PsiClass, GlobalSearchScope)}
     */
    public record Segment(
            @NotNull String name,
            @Nullable PsiElement declaration,
            @Nullable PsiClass resultType,
            boolean classQualifier,
            boolean observableSelector,
            int pathOffset) {


        public boolean isResolved() { return declaration != null || classQualifier; }
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Resolves the code-behind class from the {@code fx:subclass} attribute on the root tag
     * of the given file.
     */
    public static @Nullable PsiClass resolveCodeBehindClass(@NotNull XmlFile file) {
        var doc = file.getDocument();
        if (doc == null) return null;
        XmlTag root = doc.getRootTag();
        if (root == null) return null;
        String fxClass = root.getAttributeValue("fx:subclass");
        if (fxClass == null || fxClass.isBlank()) return null;
        return Fxml2ImportResolver.resolve(fxClass, file);
    }

    /**
     * Returns {@code true} when the file declares a code-behind class via a non-blank
     * {@code fx:subclass} attribute on its root tag, regardless of whether that class can
     * currently be resolved.  Used to distinguish "no code-behind declared" (default context
     * is the root element type) from "code-behind declared but unresolvable" (do not validate
     * against the root element type, to avoid reporting unseen members as unresolved).
     */
    public static boolean hasDeclaredCodeBehind(@NotNull XmlFile file) {
        var doc = file.getDocument();
        if (doc == null) return false;
        XmlTag root = doc.getRootTag();
        if (root == null) return false;
        String fxClass = root.getAttributeValue("fx:subclass");
        return fxClass != null && !fxClass.isBlank();
    }

    /**
     * Returns the {@link PsiClass} represented by an {@link XmlTag} (from its descriptor),
     * or {@code null} if the tag cannot be resolved to a class.
     */
    public static @Nullable PsiClass resolveTagClass(@NotNull XmlTag tag) {
        return tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd ? cd.getPsiClass() : null;
    }

    /**
     * Resolves the explicitly-declared {@code fx:context} type for the given file, or
     * {@code null} if no {@code fx:context} is present on the root tag.
     *
     * <p>Two forms are supported:
     * <ol>
     *   <li><b>Binding-path form</b>: {@code fx:context="$myContext"}: the value is a
     *       binding path resolved against the code-behind class; the type of the last
     *       segment is returned.</li>
     *   <li><b>Element-notation form</b>: {@code <fx:context><MyBindingContext/></fx:context>}:
     *       the class of the single child object tag is returned.</li>
     * </ol>
     *
     * <p>In embedded FXML the language injector wraps the user markup in a synthetic
     * {@code <fxml2:embedded>} root.  {@code fx:context} is placed on the user's root
     * element (first child of the wrapper), so this method reads from the effective
     * user root rather than the raw XML document root.
     *
     * @return the context class, or {@code null} if no {@code fx:context} is set or it
     *         cannot be resolved
     */
    public static @Nullable PsiClass resolveContextClass(@NotNull XmlFile file) {
        XmlTag root = effectiveRootTag(file);
        if (root == null) return null;

        // --- Attribute (binding-path) form: fx:context="$myContext" or fx:context="${myContext}" ---
        String fxContextAttr = root.getAttributeValue("fx:context");
        if (fxContextAttr != null && !fxContextAttr.isBlank()) {
            PsiClass codeBehind = resolveCodeBehindClass(file);
            if (codeBehind != null) {
                Fxml2BindingExpressionParser.ParsedExpression expr =
                        Fxml2BindingExpressionParser.parseExpression(fxContextAttr);
                if (expr == null) return null;
                GlobalSearchScope scope = file.getResolveScope();
                // Pass null for xmlFile to avoid recursive computeIfAbsent: resolveContextClass()
                // may be called from inside resolveImpl(), which itself runs inside computeIfAbsent
                // on the same ConcurrentHashMap.  Passing null bypasses the cache for this call.
                List<Segment> segs = resolve(expr.strippedPath(), codeBehind, scope, null, null);
                if (!segs.isEmpty()) {
                    Segment last = segs.getLast();
                    if (last.resultType() != null) return last.resultType();
                }
            }
            // Attribute is set but can't be resolved: still consider context as "set"
            // by returning null without falling through to the element-notation check.
            return null;
        }

        // --- Element-notation form: <fx:context><MyBindingContext/></fx:context> ---
        for (XmlTag child : root.getSubTags()) {
            if ("context".equals(child.getLocalName())
                    && Fxml2ImportResolver.FXML2_NAMESPACE.equals(child.getNamespace())) {
                // The context element should contain exactly one object tag child.
                for (XmlTag contextChild : child.getSubTags()) {
                    PsiClass cls = resolveTagClass(contextChild);
                    if (cls != null) return cls;
                }
                // Element present but child unresolvable
                return null;
            }
        }

        return null; // no fx:context
    }

    /**
     * Returns the effective user-written root element of {@code file}.
     *
     * <p>In standalone FXML this is simply the XML document root tag.  In embedded FXML
     * the language injector wraps the user's markup in a synthetic {@code <fxml2:embedded>}
     * element (carrying {@code fx:subclass} and namespace declarations), so the actual
     * user root is the first child tag of the wrapper.
     */
    public static @Nullable XmlTag effectiveRootTag(@NotNull XmlFile file) {
        var doc = file.getDocument();
        if (doc == null) return null;
        XmlTag docRoot = doc.getRootTag();
        if (docRoot == null) return null;
        if (Fxml2EmbeddedUtil.isWrapperRoot(docRoot)) {
            XmlTag[] children = docRoot.getSubTags();
            return children.length > 0 ? children[0] : null;
        }
        return docRoot;
    }

    /**
     * Determines the start class for binding path resolution, taking the context selector
     * into account.
     *
     * <ul>
     *   <li>No selector / {@code ROOT}: {@code fx:context} type if set, otherwise the
     *       code-behind class</li>
     *   <li>{@code self} / {@code this}: the class of {@code contextTag} itself</li>
     *   <li>{@code parent} / {@code parent[N]} / {@code parent<Type>} /
     *       {@code parent<Type>[N]}:
     *       the class of the appropriate ancestor of {@code contextTag}</li>
     * </ul>
     *
     * @param selector    the parsed context selector, or {@code null} for the default (root) context
     * @param contextTag  the tag on which the binding attribute appears
     * @param xmlFile     the containing FXML file
     * @return the resolved start class, or {@code null} if unresolvable
     */
    public static @Nullable PsiClass resolveStartClass(
            @Nullable ContextSelector selector,
            @NotNull XmlTag contextTag,
            @NotNull XmlFile xmlFile) {

        // "this" token means ROOT context: same as no selector
        if (selector != null && selector.isThis()) selector = null;

        if (selector == null) {
            // If fx:context is explicitly set, it overrides the code-behind as start class.
            PsiClass contextClass = resolveContextClass(xmlFile);
            if (contextClass != null) return contextClass;
            // The default evaluation context is the document's root element.  When a
            // code-behind class is declared via fx:subclass, that class (a subtype of the
            // root element type carrying the compiler-injected fx:id fields and any
            // user-declared members) is the start class. When fx:subclass is absent, the
            // start class is the root element type itself; fx:id fields are still resolved
            // separately against the document, so first-segment fx:id references and root
            // element properties keep resolving while unknown names are reported as errors.
            PsiClass codeBehind = resolveCodeBehindClass(xmlFile);
            if (codeBehind != null) return codeBehind;
            // fx:subclass is declared but unresolvable (e.g., a stale build where the generated
            // code-behind is missing): a code-behind exists whose members cannot be seen, so
            // do not validate against the root element type; that would report user-declared
            // members as unresolved. Only fall back to the root element type when no
            // code-behind is declared at all.
            if (hasDeclaredCodeBehind(xmlFile)) return null;
            return effectiveRootTagClass(xmlFile);
        }

        if (selector.isSelf()) {
            return resolveTagClass(contextTag);
        }

        // parent selector
        if (selector.isParent()) {
            XmlTag target = findParentAncestorTag(selector, contextTag);
            return target != null ? resolveTagClass(target) : null;
        }

        return null;
    }

    /**
     * Returns the {@link XmlTag} that a context selector refers to, for navigation.
     * Used to provide Ctrl+click on {@code self}, {@code parent}, {@code this}.
     */
    public static @Nullable XmlTag resolveContextSelectorTag(
            @Nullable ContextSelector selector,
            @NotNull XmlTag contextTag) {
        if (selector == null) return null;

        // "this" -> navigate to the root tag (ROOT context = code-behind class = root element)
        if (selector.isThis()) {
            XmlTag cur = contextTag;
            while (cur.getParentTag() != null) cur = cur.getParentTag();
            return cur;
        }

        if (selector.isSelf()) return contextTag;

        if (selector.isParent()) {
            return findParentAncestorTag(selector, contextTag);
        }

        return null;
    }

    /**
     * Finds the ancestor {@link XmlTag} selected by a {@code parent[...]} context selector.
     * Applies both the type-name filter ({@code parent[Type]}) and index filter
     * ({@code parent[N]}, {@code parent[Type:N]}).
     * Returns {@code null} when no matching ancestor exists.
     */
    private static @Nullable XmlTag findParentAncestorTag(
            @NotNull ContextSelector selector,
            @NotNull XmlTag contextTag) {
        String searchType = selector.searchType();
        Integer level = selector.level();
        List<XmlTag> ancestors = objectAncestors(contextTag);

        if (searchType != null) {
            int matchCount = 0;
            int targetMatch = level != null ? level : 0;
            for (XmlTag ancestor : ancestors) {
                PsiClass cls = resolveTagClass(ancestor);
                if (cls != null && typeNameMatches(cls, searchType)) {
                    if (matchCount == targetMatch) return ancestor;
                    matchCount++;
                }
            }
            return null;
        }

        int idx = level != null ? level : 0;
        return idx < ancestors.size() ? ancestors.get(idx) : null;
    }


    /**
     * Collects ancestor {@link XmlTag}s that represent JavaFX objects (have a
     * {@link Fxml2ClassTagDescriptor}), skipping property tags like {@code <shape>}.
     * This mirrors the FXML compiler's
     * {@code context.getParents().filter(node -> node instanceof ObjectNode)}.
     * The list is ordered from nearest ancestor to farthest.
     */
    private static @NotNull List<XmlTag> objectAncestors(@NotNull XmlTag contextTag) {
        List<XmlTag> result = new ArrayList<>();
        XmlTag cur = contextTag.getParentTag();
        while (cur != null) {
            if (resolveTagClass(cur) != null) {
                result.add(cur);
            }
            cur = cur.getParentTag();
        }
        return result;
    }

    /** Returns true if the class's simple name or qualified name matches {@code typeName}. */
    private static boolean typeNameMatches(@NotNull PsiClass cls, @NotNull String typeName) {
        String simple = cls.getName();
        String fqn = cls.getQualifiedName();
        return typeName.equals(simple) || typeName.equals(fqn);
    }

    /**
     * Resolves all segments of {@code path} (dot-separated) starting from
     * {@code startClass}.
     *
     * <p>Resolution stops at the first unresolvable segment; remaining segments
     * will have {@code declaration == null}.
     *
     * @param path       dot-separated path, e.g. {@code "viewModel.someCommand"}
     * @param startClass the class to resolve the first segment against
     * @param scope      the resolve scope for type look-ups
     * @return list of {@link Segment}s, one per dot-separated token; never empty
     *         unless {@code path} is blank
     */
    public static @NotNull List<Segment> resolve(
            @NotNull String path,
            @NotNull PsiClass startClass,
            @NotNull GlobalSearchScope scope) {
        return resolve(path, startClass, scope, null, null);
    }

    public static @NotNull List<Segment> resolve(
            @NotNull String path,
            @NotNull PsiClass startClass,
            @NotNull GlobalSearchScope scope,
            @Nullable Kind kind) {
        return resolve(path, startClass, scope, kind, null);
    }

    /**
     * Like {@link #resolve(String, PsiClass, GlobalSearchScope, Kind)} but also accepts the
     * containing {@link XmlFile} so that {@code fx:id}-injected fields can be resolved as
     * path segments (the FXML compiler injects a public field for each {@code fx:id}).
     */
    public static @NotNull List<Segment> resolve(
            @NotNull String path,
            @NotNull PsiClass startClass,
            @NotNull GlobalSearchScope scope,
            @Nullable Kind kind,
            @Nullable XmlFile xmlFile) {
        // Cache resolved paths on the XmlFile so that the same path is not re-walked
        // during a single inspection pass. Only cache when xmlFile is provided (the
        // common caller path); uncached calls (xmlFile == null) are rare.
        if (xmlFile != null) {
            String cacheKey = (startClass.getQualifiedName() != null
                    ? startClass.getQualifiedName() : startClass.getName())
                    + "|" + path
                    + "|" + (kind == null ? "" : kind.name());
            ConcurrentHashMap<String, List<Segment>> cache =
                    CachedValuesManager.getCachedValue(xmlFile, BINDING_PATH_CACHE,
                            () -> CachedValueProvider.Result.create(
                                    new ConcurrentHashMap<>(),
                                    bindingCacheDependencies(xmlFile)));
            // computeIfAbsent: only one thread computes per (startClass, path, kind) triple,
            // preventing duplicate resolution work across concurrent annotator threads.
            return cache.computeIfAbsent(cacheKey,
                    k -> resolveImpl(path, startClass, scope, kind, xmlFile));
        }
        return resolveImpl(path, startClass, scope, kind, null);
    }

    private static @NotNull List<Segment> resolveImpl(
            @NotNull String path,
            @NotNull PsiClass startClass,
            @NotNull GlobalSearchScope scope,
            @Nullable Kind kind,
            @Nullable XmlFile xmlFile) {

        // Split path on both '.' and '::'.
        //
        // Attached property groups like (VBox.margin) are treated as a single atomic segment,
        // even though they contain a '.'.  The parenthesised content is not split.
        //
        // observableSelectee[i] = true when the separator BEFORE segment i is '::'.
        // This means: the FXML compiler requires that member to be an ObservableValue subtype
        // and keeps its raw type (not the contained value type) as the context for the next
        // segment.  Derived from observableSel_raw[i-1] for i > 0, and from leadingObs for i == 0.
        List<String> nameList = new ArrayList<>();
        List<Integer> nameOffsetList = new ArrayList<>();
        List<Boolean> observableSelList = new ArrayList<>();
        int start = 0;
        boolean leadingObs = path.startsWith("::");
        if (leadingObs) start = 2;
        int pos = start;
        while (pos <= path.length()) {
            if (pos == path.length()) {
                nameList.add(path.substring(start));
                nameOffsetList.add(start);
                observableSelList.add(false);
                break;
            }
            char ch = path.charAt(pos);
            if (ch == '(') {
                // Attached-property group: skip to matching ')'
                int depth = 1;
                pos++;
                while (pos < path.length() && depth > 0) {
                    char c = path.charAt(pos++);
                    if (c == '(') depth++;
                    else if (c == ')') depth--;
                }
                // pos is now one past ')'; continue scanning for separator
            } else if (pos + 1 < path.length() && ch == ':' && path.charAt(pos + 1) == ':') {
                nameList.add(path.substring(start, pos));
                nameOffsetList.add(start);
                observableSelList.add(true);
                start = pos + 2;
                pos = start;
            } else if (ch == '.') {
                nameList.add(path.substring(start, pos));
                nameOffsetList.add(start);
                observableSelList.add(false);
                start = pos + 1;
                pos = start;
            } else {
                pos++;
            }
        }

        String[] names = nameList.toArray(String[]::new);
        int[] nameOffsets = nameOffsetList.stream().mapToInt(x -> x).toArray();

        // observableSel_raw[i] = true when segment i is followed by '::'.
        // Used only to compute observableSelectee.
        boolean[] observableSel_raw = new boolean[names.length];
        for (int k = 0; k < observableSelList.size(); k++) observableSel_raw[k] = observableSelList.get(k);

        // observableSelectee[i] = true when the separator directly before segment i is '::'.
        // For i == 0: true only when there is a leading '::'.
        // For i > 0: true when the separator after segment i-1 was '::', i.e. observableSel_raw[i-1].
        boolean[] observableSelectee = new boolean[names.length];
        observableSelectee[0] = leadingObs;
        System.arraycopy(observableSel_raw, 0, observableSelectee, 1, names.length - 1);

        List<Segment> result = new ArrayList<>(names.length);

        PsiClass current = startClass;
        int i = 0;
        while (i < names.length) {
            String name = names[i];
            if (name.isBlank()) {
                result.add(new Segment(name, null, null, false, observableSelectee[i], nameOffsets[i]));
                i++;
                continue;
            }
            if (current == null) {
                result.add(new Segment(name, null, null, false, observableSelectee[i], nameOffsets[i]));
                i++;
                continue;
            }

            // --- Attached property resolution ---
            //
            // Syntax: (ClassName.propertyName), e.g. (VBox.margin).
            // The compiler calls the static getter ClassName.getPropertyName(ReceiverType).
            // In the plugin we resolve it to that static getter method.
            if (name.startsWith("(") && name.endsWith(")")) {
                String inner = name.substring(1, name.length() - 1).trim(); // e.g. "VBox.margin"
                int lastDot = inner.lastIndexOf('.');
                if (lastDot > 0) {
                    String className = inner.substring(0, lastDot);
                    String propName = inner.substring(lastDot + 1);
                    PsiClass attachedClass = xmlFile != null
                            ? Fxml2ImportResolver.resolve(className, xmlFile) : null;
                    if (attachedClass == null) {
                        attachedClass = JavaPsiFacade.getInstance(startClass.getProject())
                                .findClass(className, scope);
                    }
                    if (attachedClass != null) {
                        PsiElement staticDecl = Fxml2PropertyResolver.resolveStaticProperty(attachedClass, propName);
                        if (staticDecl != null) {
                            // Determine the return type of the static getter for the result class
                            PsiClass nextClass = null;
                            if (staticDecl instanceof PsiMethod m) {
                                PsiType ret = m.getReturnType();
                                if (ret != null) nextClass = PsiUtil.resolveClassInType(ret);
                            }
                            result.add(new Segment(name, staticDecl, nextClass,
                                    false, observableSelectee[i], nameOffsets[i]));
                            current = nextClass;
                            i++;
                            continue;
                        }
                    }
                }
                // Could not resolve: report as unresolved
                result.add(new Segment(name, null, null, false, observableSelectee[i], nameOffsets[i]));
                current = null;
                i++;
                continue;
            }

            // --- Type witness stripping ---
            //
            // Syntax: methodName<TypeArg> or methodName&lt;TypeArg&gt; (XML-escaped form)
            // Strip the <...> / &lt;...&gt; portion before property lookup; the witness is only
            // for the compiler's type inference and does not affect which method is resolved.
            String lookupName = name;
            int witnessStart = name.indexOf('<');
            if (witnessStart < 0) {
                int ltIdx = name.indexOf("&lt;");
                if (ltIdx > 0) witnessStart = ltIdx;
            }
            if (witnessStart > 0) {
                lookupName = name.substring(0, witnessStart);
            }

            // --- FQN static-field resolution (tried first) ---
            //
            // Try to interpret names[i..split-1] as a class name and names[split] as a
            // static field, scanning right-to-left so the longest (most specific) class
            // name wins and only ONE resolveClass() call is needed for well-formed paths.
            //
            // This runs before resolveInstanceProperty so that FQN paths like
            // "javafx.scene.layout.Region.USE_PREF_SIZE" never trigger a property lookup
            // on "javafx", "scene", etc.: which would traverse the full JavaFX hierarchy.
            boolean fqnResolved = false;
            for (int split = names.length - 1; split > i; split--) {
                // Skip when the field-name candidate is blank (trailing dot in path).
                if (names[split].isBlank()) continue;
                // names[split-1] is the last segment of the class name candidate, i.e. the
                // actual class name.  A valid class name is non-empty and starts with an
                // uppercase letter; empty segments arise from consecutive dots in a
                // partially-edited path, and lowercase segments are package components
                // (e.g. "layout", "scene") that can never be a class name.
                if (names[split - 1].isEmpty() || Character.isLowerCase(names[split - 1].charAt(0))) continue;
                StringBuilder classNameBuilder = new StringBuilder();
                for (int k = i; k < split; k++) {
                    if (!classNameBuilder.isEmpty()) classNameBuilder.append('.');
                    classNameBuilder.append(names[k]);
                }
       PsiClass cls = resolveClass(classNameBuilder.toString(), scope, startClass.getProject(), xmlFile);
                if (cls != null) {
                    String fieldName = names[split];
                    PsiField field = findFieldInSuperclassChain(cls, fieldName);
                    JavaPsiFacade facade = JavaPsiFacade.getInstance(startClass.getProject());
                    StringBuilder pkgBuilder = new StringBuilder();
                    for (int j = i; j < split; j++) {
                        if (!pkgBuilder.isEmpty()) pkgBuilder.append('.');
                        pkgBuilder.append(names[j]);
                        boolean isClassSeg = (j == split - 1);
                        PsiElement segDecl = isClassSeg ? cls
                                : facade.findPackage(pkgBuilder.toString());
                        PsiClass segType = isClassSeg ? cls : null;
                        // Class qualifier and package segments are never observable-selectees
                        result.add(new Segment(names[j], segDecl, segType, true, false, nameOffsets[j]));
                    }
                    // The field segment is the one accessed via ::; its observableSelectee flag
                    // is derived from observableSelectee[split] (the separator before names[split]).
                    if (field != null && field.hasModifierProperty(PsiModifier.STATIC)) {
                        result.add(new Segment(fieldName, field, null,
                                false, observableSelectee[split], nameOffsets[split]));
                        i = split + 1;
                        current = null;
                        fqnResolved = true;
                        break;
                    }
                    // Class found but field not found as a static field: check for a static
                    // getter method (e.g. {@code getClassCssMetaData()}) on the resolved class.
                    // This handles paths like {@code $Button.classCssMetaData} where the property
                    // is accessed via a static getter rather than a static field.
                    PsiMethod staticGetter = findStaticGetterOnClass(cls, fieldName);
                    if (staticGetter != null) {
                        PsiClass nextClass = PsiUtil.resolveClassInType(staticGetter.getReturnType());
                        result.add(new Segment(fieldName, staticGetter, nextClass,
                                false, observableSelectee[split], nameOffsets[split]));
                        i = split + 1;
                        current = nextClass;
                        fqnResolved = true;
                        break;
                    }
                    // Neither field nor static getter found: unresolved field.
                    result.add(new Segment(fieldName, null, cls,
                            false, observableSelectee[split], nameOffsets[split]));
                    i = split + 1;
                    current = null;
                    fqnResolved = true;
                    break;
                }
            }
            if (fqnResolved) continue;

            // --- fx:id resolution (first segment against the code-behind class) ---
            //
            // fx:id-injected fields in the code-behind (e.g. @FXML public Button myBtn) must
            // resolve via the LightFieldBuilder that navigates to the fx:id XmlAttributeValue
            // in the XML. This takes priority over resolveInstanceProperty so that Ctrl+click
            // on "myBtn" in a binding path lands on the fx:id declaration, not the Java field.
            if (xmlFile != null && current == startClass) {
                PsiField fxIdField = resolveFxId(lookupName, xmlFile);
                if (fxIdField != null) {
                    PsiClass nextClass = PsiUtil.resolveClassInType(fxIdField.getType());
                    result.add(new Segment(name, fxIdField, nextClass,
                            false, observableSelectee[i], nameOffsets[i]));
                    current = nextClass;
                    i++;
                    continue;
                }
            }

            // --- Instance property resolution ---
            //
            // Reached only when no FQN class prefix matched above and no fx:id matched.
            PsiElement typeDecl = Fxml2PropertyResolver.resolveInstanceProperty(current, lookupName);
            if (typeDecl != null) {
                resolveAndAddInstancePropertySegment(result, name, lookupName, typeDecl, current, kind,
                        observableSelectee[i], nameOffsets[i]);
                // When the current segment is the observable selectee (preceded by '::'),
                // keep the raw observable type as the context for the next segment.
                // A segment resolved with selectObservable=true
                // stores its raw type in getValueTypeInstance(), so the next segment resolves
                // against the raw observable (e.g. ListProperty) rather than its contained value
                // (e.g. ObservableList).  When the current segment is not an observable selectee,
                // always use the unwrapped value type regardless of what separator follows it.
                if (observableSelectee[i]) {
                    current = propertyTypeRaw(typeDecl, current);
                } else {
                    current = result.getLast().resultType();
                }
                i++;
                continue;
            }

            // Instance-property fallback: when the FXML compiler-generated base class is
            // absent (stale build), the code-behind's supertype chain is broken and
            // resolveInstanceProperty() won't reach the root element type.
            // The compiler always generates "class FooBase extends <rootTagType>", so try
            // the root-tag class as a synthetic supertype for the first segment only.
            // This fallback only applies to the default context (no fx:context set).
            if (xmlFile != null && current == startClass && resolveContextClass(xmlFile) == null) {
                PsiClass rootTagClass = resolveRootTagClass(xmlFile);
                if (rootTagClass != null) {
                    PsiElement rootTypeDecl = Fxml2PropertyResolver.resolveInstanceProperty(rootTagClass, lookupName);
                    if (rootTypeDecl != null) {
                        resolveAndAddInstancePropertySegment(result, name, lookupName, rootTypeDecl, rootTagClass, kind,
                                observableSelectee[i], nameOffsets[i]);
                        if (observableSelectee[i]) {
                            current = propertyTypeRaw(rootTypeDecl, rootTagClass);
                        } else {
                            current = result.getLast().resultType();
                        }
                        i++;
                        continue;
                    }
                }
            }

            // --- Inherited static field resolution ---
            //
            // The FXML compiler's Resolver.tryResolveField walks the superclass chain, so
            // a bare name like "USE_PREF_SIZE" resolves when the start class (or any of its
            // ancestors) declares a public static field with that name.
            // PsiClass.findFieldByName(name, true) already walks supertypes.
            PsiField staticField = current.findFieldByName(lookupName, true);
            if (staticField == null && xmlFile != null && current == startClass) {
                // Fallback: when the FXML compiler-generated base class is absent (e.g. the
                // project hasn't been built yet), the code-behind's supertype chain is broken
                // and findFieldByName() won't reach the root element type.
                // The compiler always generates "class FooBase extends <rootTagType>", so the
                // root tag class is effectively a supertype of the code-behind.
                //
                // This fallback only applies to the default context (no fx:context set):
                // when fx:context is explicitly set, startClass is the context type -
                // not the code-behind: and the root-tag relationship does not hold.
                if (resolveContextClass(xmlFile) == null) {
                    PsiClass rootTagClass = resolveRootTagClass(xmlFile);
                    if (rootTagClass != null) {
                        staticField = rootTagClass.findFieldByName(name, true);
                    }
                }
            }
            if (staticField != null && staticField.hasModifierProperty(PsiModifier.STATIC)
                    && !staticField.hasModifierProperty(PsiModifier.PRIVATE)) {
                result.add(new Segment(name, staticField, null,
                        false, observableSelectee[i], nameOffsets[i]));
                current = null;
                i++;
                continue;
            }

            // Nothing resolved: mark as unresolved and stop the chain.
            result.add(new Segment(name, null, null, false, observableSelectee[i], nameOffsets[i]));
            current = null;
            i++;
        }
        return result;
    }

    /**
     * Returns the {@link PsiClass} of the root XML element tag (e.g. {@code BorderPane}
     * for a file whose root tag is {@code <BorderPane ...>}).
     *
     * <p>This is useful as a fallback when the fxml2-compiler-generated base class is absent:
     * the compiler always generates {@code class FooBase extends <rootTagType>}, so the root
     * tag class is effectively in the code-behind's supertype chain.
     */
    public static @Nullable PsiClass resolveRootTagClass(@NotNull XmlFile xmlFile) {
        var doc = xmlFile.getDocument();
        if (doc == null) return null;
        XmlTag root = doc.getRootTag();
        if (root == null) return null;
        return resolveTagClass(root);
    }

    /**
     * Returns the {@link PsiClass} of the effective user-written root element tag, i.e. the
     * type that serves as the default evaluation context when no {@code fx:subclass} and no
     * {@code fx:context} are present.
     *
     * <p>Unlike {@link #resolveRootTagClass(XmlFile)}, this unwraps the synthetic
     * {@code <fxml2:embedded>} wrapper used for embedded markup (see {@link #effectiveRootTag}),
     * so it returns the user's root element type rather than the wrapper.
     */
    public static @Nullable PsiClass effectiveRootTagClass(@NotNull XmlFile xmlFile) {
        XmlTag root = effectiveRootTag(xmlFile);
        return root != null ? resolveTagClass(root) : null;
    }

    /**
     * Scans the XML file for a tag with {@code fx:id="name"} and returns a synthetic
     * {@link com.intellij.psi.impl.light.LightFieldBuilder} that presents the injected
     * field as {@code public FieldType name} in hover tooltips, while navigating to the
     * {@code fx:id} attribute in the XML on Ctrl+click.
     */
    public static @Nullable PsiField resolveFxId(
            @NotNull String name, @NotNull XmlFile xmlFile) {
        var doc = xmlFile.getDocument();
        if (doc == null) return null;
        XmlTag root = doc.getRootTag();
        if (root == null) return null;
        return findFxIdInTag(name, root, xmlFile.getProject());
    }

    private static @Nullable PsiField findFxIdInTag(
            @NotNull String name, @NotNull XmlTag tag,
            @NotNull com.intellij.openapi.project.Project project) {
        XmlAttribute idAttr = tag.getAttribute("fx:id");
        if (idAttr != null && name.equals(idAttr.getValue())) {
            PsiClass tagClass = tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd
                    ? cd.getPsiClass() : null;
            // Build a synthetic public field: <TagType> <name>
            // Navigation points to the fx:id *value* element in the XML (the XmlAttributeValue),
            // not the XmlAttribute itself.  This ensures that Ctrl+click from a binding use site
            // lands on the same PSI element that Fxml2FxIdReference resolves to (enabling
            // "highlight usages" from the definition site to light up binding-expression uses).
            PsiType fieldType = tagClass != null
                    ? JavaPsiFacade.getElementFactory(project).createType(tagClass)
                    : PsiType.getJavaLangObject(PsiManager.getInstance(project),
                            GlobalSearchScope.allScope(project));
            XmlAttributeValue valueElement = idAttr.getValueElement();
            com.intellij.psi.PsiElement navTarget = valueElement != null ? valueElement : idAttr;
            return new com.intellij.psi.impl.light.LightFieldBuilder(name, fieldType, navTarget)
                    .setModifiers(PsiModifier.PUBLIC);
        }
        for (XmlTag child : tag.getSubTags()) {
            PsiField found = findFxIdInTag(name, child, project);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Finds a field by name by walking the superclass chain only, not interfaces.
     * This mirrors the FXML compiler's {@code Resolver.tryResolveField}, which resolves
     * fields through {@code superClass()} but never traverses implemented interfaces.
     * Java does not expose static interface members through implementing class names, so
     * a reference like {@code $Impl.FIELD} must fail when {@code FIELD} is declared only
     * on an interface that {@code Impl} implements.
     */
    private static @Nullable PsiField findFieldInSuperclassChain(@NotNull PsiClass cls, @NotNull String name) {
         PsiClass current = cls;
         while (current != null) {
             PsiField field = current.findFieldByName(name, false);
             if (field != null) return field;
             current = current.getSuperClass();
         }
         return null;
     }

    /**
     * Finds a static getter method on {@code cls}: a public static no-arg method
     * whose name starts with {@code get} or {@code is} (followed by uppercase)
     * and returns a non-void type.
     *
     * <p>This mirrors the FXML compiler's recognition of static getters as property
     * accessors, e.g. {@code Button.getClassCssMetaData()} for the property
     * {@code classCssMetaData}.
     *
     * <p>Walks the superclass chain to find inherited static getters, since
     * {@code PsiClass.findMethodsByName} may not return inherited methods for
     * compiled classes without sources.
     */
    private static @Nullable PsiMethod findStaticGetterOnClass(@NotNull PsiClass cls, @NotNull String name) {
        if (name.isEmpty()) return null;
        String capName = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        String getterName = "get" + capName;
        String isGetterName = "is" + capName;
        PsiClass cursor = cls;
        while (cursor != null) {
            for (PsiMethod m : cursor.getAllMethods()) {
                String mName = m.getName();
                if ((getterName.equals(mName) || isGetterName.equals(mName))
                        && m.hasModifierProperty(PsiModifier.PUBLIC)
                        && m.hasModifierProperty(PsiModifier.STATIC)
                        && m.getParameterList().isEmpty()
                        && m.getReturnType() != null
                        && !m.getReturnType().equals(com.intellij.psi.PsiTypes.voidType())) {
                    return m;
                }
            }
            cursor = cursor.getSuperClass();
        }
        return null;
    }


    /** Resolves a class name using the project search scope, trying nested-class variants too. */
    private static @Nullable PsiClass resolveClass(
            @NotNull String className, @NotNull GlobalSearchScope scope,
            @NotNull com.intellij.openapi.project.Project project,
            @Nullable XmlFile xmlFile) {
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        if (!className.contains(".")) {
            // Simple name (e.g. "KeyEvent", "Region"): try import-based resolution first.
            if (xmlFile != null) {
                PsiClass imported = Fxml2ImportResolver.resolve(className, xmlFile);
                if (imported != null) return imported;
            }
            return facade.findClass(className, scope);
        }
        // Dotted name: go straight to facade.findClass.
        PsiClass cls = facade.findClass(className, scope);
        if (cls != null) return cls;
        // Try one nested-class variant: replace only the last '.' with '$'
        // (e.g. "com.example.Outer.Inner" -> "com.example.Outer$Inner").
        // Only one substitution is needed: a nested-class reference has exactly one
        // outer.Inner boundary.
        int dot = className.lastIndexOf('.');
        String candidate = className.substring(0, dot) + "$" + className.substring(dot + 1);
        return facade.findClass(candidate, scope);
    }

    // -----------------------------------------------------------------------
    // Cache helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the dependency array for {@link #BINDING_PATH_CACHE}: the FXML file itself,
     * the Java-language modification tracker, and (when the Kotlin plugin is present) the
     * Kotlin-language modification tracker.  Including the Kotlin tracker ensures that live
     * edits to a Kotlin code-behind file, for example adding or removing a property accessor.
     * Invalidate the cached binding-path segments so that the annotator picks up the new
     * resolution immediately.
     */
    private static Object[] bindingCacheDependencies(@NotNull XmlFile xmlFile) {
        PsiModificationTracker tracker =
                PsiModificationTracker.getInstance(xmlFile.getProject());
        com.intellij.lang.Language kotlinLang =
                com.intellij.lang.Language.findLanguageByID("kotlin");
        if (kotlinLang != null) {
            return new Object[]{
                    xmlFile,
                    tracker.forLanguage(JavaLanguage.INSTANCE),
                    tracker.forLanguage(kotlinLang)
            };
        }
        return new Object[]{xmlFile, tracker.forLanguage(JavaLanguage.INSTANCE)};
    }

    // -----------------------------------------------------------------------
    // Type unwrapping helpers
    // -----------------------------------------------------------------------

    /**
     * Resolves the navigation declaration and result type for an instance property, adds a
     * {@link Segment} to {@code result}, and returns it.  Factors out the two identical
     * resolve->propertyType->navDecl->add blocks in the main resolution loop.
     *
     * @param rawName            the raw segment text (including any type-witness suffix) used for
     *                           the {@link Segment} name so that offset arithmetic stays correct
     * @param lookupName         the name with any {@code <TypeWitness>} stripped, used for resolution
     * @param observableSelector whether this segment was accessed via {@code ::}
     * @param pathOffset         start offset of the segment name within the path string
     */
    private static void resolveAndAddInstancePropertySegment(
            @NotNull List<Segment> result,
            @NotNull String rawName,
            @NotNull String lookupName,
            @NotNull PsiElement typeDecl,
            @NotNull PsiClass contextClass,
            @Nullable Kind kind,
            boolean observableSelector,
            int pathOffset) {
        PsiClass nextClass = propertyType(typeDecl, lookupName, contextClass);
        PsiElement navDecl = Fxml2PropertyResolver.resolveInstanceProperty(contextClass, lookupName, List.of(), kind);
        result.add(new Segment(rawName, navDecl, nextClass, false, observableSelector, pathOffset));
    }

    /**
     * Given a property declaration (setter, getter, {@code xProperty()} method, or backing field),
     * returns the property's value type, unwrapping {@code ObservableValue<T>} to {@code T}.
     */
    private static @Nullable PsiClass propertyType(
            @NotNull PsiElement decl,
            @NotNull String propName,
            @NotNull PsiClass contextClass) {

        // When the documented target is a backing field, derive the type from the getter instead.
        // For plain public fields that have no getter (e.g. {@code public ViewModel vm;}),
        // fall back to the field's own type (unwrapping ObservableValue<T> if needed).
        PsiMethod method;
        if (decl instanceof PsiField field) {
            PsiElement getter = Fxml2PropertyResolver.findGetterFor(contextClass, propName);
            if (!(getter instanceof PsiMethod gm)) {
                // No getter: plain public field.  Use the field type directly.
                PsiType fieldType = field.getType();
                GlobalSearchScope scope = contextClass.getResolveScope();
                PsiType unwrapped = unwrapObservable(fieldType, scope, decl.getProject());
                return resolveClassBoxed(unwrapped != null ? unwrapped : fieldType, decl.getProject());
            }
            method = gm;
        } else if (decl instanceof PsiMethod m) {
            method = m;
        } else {
            // KtProperty fallback: the declaration is a Kotlin source property (KtProperty)
            // resolved when the synthetic getter could not be found via normal PSI lookups.
            // Find the corresponding light getter by matching the navigation element.
            PsiMethod lightGetter = findLightGetterForKtProperty(decl, contextClass);
            if (lightGetter == null) return null;
            method = lightGetter;
        }

        // If the method is an xProperty() accessor (fooProperty()), prefer the plain getter's
        // return type to avoid a fragile ObservableValue hierarchy traversal.  The plain getter
        // getName() always returns the unwrapped value type directly.
        String mName = method.getName();
        if (Fxml2PropertyNameUtil.isPropertyAccessorName(mName) && method.getParameterList().isEmpty()) {
            PsiMethod getter = Fxml2PropertyResolver.findGetterFor(contextClass, propName);
            if (getter != null) {
                PsiType getterReturn = getter.getReturnType();
                if (getterReturn != null && !getterReturn.equals(com.intellij.psi.PsiTypes.voidType())) {
                    // Use getter return type; unwrap ObservableValue only if the getter itself
                    // returns one (rare, but possible for read-only properties).
                    GlobalSearchScope scope = contextClass.getResolveScope();
                    PsiType unwrapped = unwrapObservable(getterReturn, scope, decl.getProject());
                    return resolveClassBoxed(unwrapped != null ? unwrapped : getterReturn, decl.getProject());
                }
            }
        }

        PsiType returnType = method.getReturnType();
        if (returnType == null || returnType.equals(com.intellij.psi.PsiTypes.voidType())) {
            // setter: look for matching getter to get the return type
            if (Fxml2PropertyNameUtil.isSetterName(mName)) {
                String getterPropName = Fxml2PropertyNameUtil.setterToPropertyName(mName);
                PsiMethod getter = Fxml2PropertyResolver.findGetterFor(contextClass, getterPropName);
                if (getter != null) returnType = getter.getReturnType();
            }
        }
        if (returnType == null) return null;

        // Unwrap ObservableValue<T> -> T.
        // Use contextClass.getResolveScope() so that javafx.beans.value.ObservableValue is
        // always resolvable regardless of the scope the caller passes in.
        PsiType unwrapped = unwrapObservable(returnType, contextClass.getResolveScope(), decl.getProject());
        return resolveClassBoxed(unwrapped != null ? unwrapped : returnType, decl.getProject());
    }

    /**
     * Resolves a {@link PsiType} to a {@link PsiClass}, boxing primitives first.
     * Unlike {@link PsiUtil#resolveClassInType}, this method converts {@code boolean}
     * -> {@code Boolean}, {@code int} -> {@code Integer}, etc., so that primitive-typed
     * property getters (e.g. {@code isActive() -> boolean}) produce a usable result type.
     */
    private static @Nullable PsiClass resolveClassBoxed(@NotNull PsiType type, @NotNull Project project) {
        if (type instanceof PsiPrimitiveType pt) {
            String boxedFqn = pt.getBoxedTypeName();
            if (boxedFqn != null) {
                PsiClass boxed = JavaPsiFacade.getInstance(project)
                        .findClass(boxedFqn, GlobalSearchScope.allScope(project));
                if (boxed != null) return boxed;
            }
        }
        return PsiUtil.resolveClassInType(type);
    }

    /**
     * Returns the <em>raw</em> (non-unwrapped) return type of a property declaration as a
     * {@link PsiClass}.  Used for the {@code ::} observable-selection operator, which keeps
     * the {@code ObservableValue}/{@code Property} instance rather than its contained value.
     *
     * <p>For a property method like {@code itemsProperty() -> ListProperty<String>}, returns
     * {@code ListProperty}; for a plain getter like {@code getItems() -> ObservableList<String>},
     * returns {@code ObservableList}.
     */
    public static @Nullable PsiClass propertyTypeRaw(
            @NotNull PsiElement decl,
            @NotNull PsiClass contextClass) {
        PsiMethod method;
        if (decl instanceof PsiField f) {
            return PsiUtil.resolveClassInType(f.getType());
        } else if (decl instanceof PsiMethod m) {
            method = m;
        } else {
            // KtProperty fallback: find the corresponding light getter by navigation element.
            method = findLightGetterForKtProperty(decl, contextClass);
            if (method == null) return null;
        }
        // For xProperty() accessor, return the raw property type directly
        String mName = method.getName();
        if (Fxml2PropertyNameUtil.isPropertyAccessorName(mName) && method.getParameterList().isEmpty()) {
            PsiType ret = method.getReturnType();
            return ret != null ? PsiUtil.resolveClassInType(ret) : null;
        }
        // For a plain getter, also return the raw return type
        PsiType returnType = method.getReturnType();
        if (returnType == null || returnType.equals(com.intellij.psi.PsiTypes.voidType())) {
            // setter: find getter
            if (Fxml2PropertyNameUtil.isSetterName(mName)) {
                String propName = Fxml2PropertyNameUtil.setterToPropertyName(mName);
                PsiMethod getter = Fxml2PropertyResolver.findGetterFor(contextClass, propName);
                if (getter != null) returnType = getter.getReturnType();
            }
        }
        return returnType != null ? PsiUtil.resolveClassInType(returnType) : null;
    }

    /**
     * When a property resolved to a {@code KtProperty} (Kotlin source fallback), this method
     * finds the corresponding {@link PsiMethod} light getter by matching the navigation element
     * of each method declared on {@code contextClass} against {@code ktProp}.
     *
     * <p>This is the counterpart of the {@code KtProperty} fallback in
     * {@link Fxml2PropertyResolver}: once a {@code KtProperty} is the resolution target,
     * type determination still requires a {@link PsiMethod} with a resolvable return type.
     *
     * @return the first public no-arg getter whose navigation element equals {@code ktProp},
     *         or {@code null} if none is found (Kotlin plugin absent or class not analyzable)
     */
    static @Nullable PsiMethod findLightGetterForKtProperty(
            @NotNull PsiElement ktProp, @NotNull PsiClass contextClass) {
        try {
            if (!(ktProp instanceof org.jetbrains.kotlin.psi.KtProperty)) return null;
        } catch (NoClassDefFoundError ignored) {
            return null;
        }
        for (PsiMethod m : contextClass.getMethods()) {
            if (!m.getParameterList().isEmpty()) continue;
            if (!m.hasModifierProperty(com.intellij.psi.PsiModifier.PUBLIC)) continue;
            if (m.hasModifierProperty(com.intellij.psi.PsiModifier.STATIC)) continue;
            if (m.getReturnType() == null
                    || m.getReturnType().equals(com.intellij.psi.PsiTypes.voidType())) continue;
            if (ktProp.equals(m.getNavigationElement())) return m;
        }
        return null;
    }

    /**
     * If {@code type} is a parameterised subtype of {@code javafx.beans.value.ObservableValue<T>},
     * returns {@code T}; otherwise returns {@code null}.
     */
    private static @Nullable PsiType unwrapObservable(
            @NotNull PsiType type,
            @NotNull GlobalSearchScope scope,
            @NotNull com.intellij.openapi.project.Project project) {

        if (!(type instanceof PsiClassType classType)) return null;

        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        PsiClass observable = facade.findClass("javafx.beans.value.ObservableValue", scope);
        if (observable == null) return null;

        // Walk the supertype hierarchy to find ObservableValue<T>
        PsiClassType.ClassResolveResult resolved = classType.resolveGenerics();
        PsiClass cls = resolved.getElement();
        if (cls == null) return null;

        // Check if this type IS ObservableValue or a subtype
        PsiSubstitutor sub = Fxml2AttributeValueResolver.buildObservableSubstitutor(
                cls, observable, resolved.getSubstitutor());
        if (sub == null) return null;

        // The type parameter of ObservableValue is its first (only) type parameter
        var typeParams = observable.getTypeParameters();
        if (typeParams.length == 0) return null;
        return sub.substitute(typeParams[0]);
    }
}
