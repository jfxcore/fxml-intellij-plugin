package org.jfxcore.fxml.resolve;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PropertyUtilBase;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves an XML attribute name (or short-element property-tag name) to the corresponding
 * Java declaration ({@link PsiMethod}) inside a JavaFX class.
 *
 * <p>The FXML compiler recognizes two kinds of property references:
 *
 * <ol>
 *   <li><b>Instance property</b>: a plain name like {@code text} on a {@code <Button>} tag.
 *       Resolution priority (mirrors the fxml-compiler's {@code Resolver.tryResolveProperty}):
 *       <ol>
 *         <li>{@code nameProperty()}: public, non-static, no-arg, returns {@code ObservableValue}</li>
 *         <li>{@code getName()} or {@code isName()}: public, non-static, no-arg getter</li>
 *         <li>{@code setName(T)}: public, non-static, single-arg setter</li>
 *       </ol>
 *       The <em>navigation target</em> follows the IntelliJ JavaFX plugin convention
 *       ({@code prepareWritableProperties}): prefer the same-named non-static backing field
 *       when it carries a Javadoc comment (e.g. {@code TextInputControl.text},
 *       {@code Region.maxWidth}), then the first accessor method with a Javadoc comment
 *       ({@code nameProperty()} &gt; getter &gt; setter), then the undocumented tiebreak
 *       (field &gt; {@code nameProperty()} &gt; getter &gt; setter).
 *   </li>
 *   <li><b>Static property</b>: a dotted name like {@code GridPane.rowIndex} (used both as an XML
 *       attribute and as a child tag).  The class part is resolved against the file's imports and the
 *       property part is looked up as a static setter with two parameters:
 *       {@code static void setRowIndex(Node receiver, T value)}.
 *   </li>
 * </ol>
 */
public final class Fxml2PropertyResolver {

    private Fxml2PropertyResolver() {}

    /**
     * Per-class cache for resolved instance properties.
     * The cache map key is {@code "propertyName|kindName"} (where kind is empty for null).
     * Stored as {@code Optional<PsiElement>} because {@link java.util.concurrent.ConcurrentHashMap}
     * cannot hold null values.
     *
     * <p>Dependencies: the owner class element (its containing-file modification stamp) AND
     * the Java-language PSI modification tracker.  The Java tracker dependency ensures the cache
     * is invalidated when <em>any</em> Java source file changes, which prevents stale results when
     * a property is inherited from a superclass that was modified in the same commit.  The owner-class
     * dependency additionally invalidates the cache when the specific class file is reloaded.
     * Together, the cache survives FXML, CSS, and resource-bundle edits (where the Java tracker does
     * not change) but resets after any Java-only change, which is correct behavior.
     *
     * <p>Populated via {@link java.util.concurrent.ConcurrentHashMap#computeIfAbsent} so that
     * concurrent annotator threads never perform duplicate lookups for the same (class, property)
     * pair during a single daemon pass.
     */
    private static final Key<CachedValue<ConcurrentHashMap<String, Optional<PsiElement>>>>
            INSTANCE_PROPERTY_CACHE = Key.create("fxml2.instanceProperty");

    /**
     * Per-class cache for resolved static properties.
     * The cache map key is the property name.
     * Stored as {@code Optional<PsiElement>} because ConcurrentHashMap cannot hold null values.
     * Dependencies: owner class element and Java-language PSI modification tracker (see
     * {@link #INSTANCE_PROPERTY_CACHE} for the rationale).
     */
    private static final Key<CachedValue<ConcurrentHashMap<String, Optional<PsiElement>>>>
            STATIC_PROPERTY_CACHE = Key.create("fxml2.staticProperty");

    /**
     * Per-class cache for all instance property names produced by {@link #getAllPropertyNames}.
     * Dependencies: owner class element and Java-language PSI modification tracker.
     */
    private static final Key<CachedValue<Set<String>>> ALL_PROPERTY_NAMES_CACHE =
            Key.create("fxml2.allPropertyNames");

    /**
     * Per-class cache for all static property names produced by {@link #getAllStaticPropertyNames}.
     * Dependencies: owner class element and Java-language PSI modification tracker.
     */
    private static final Key<CachedValue<Set<String>>> ALL_STATIC_PROPERTY_NAMES_CACHE =
            Key.create("fxml2.allStaticPropertyNames");

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Resolves a plain property name (no dot) against an owner {@link PsiClass}.
     *
     * <p>Returns the member that carries a Javadoc/KDoc comment, checked in order:
     * setter -> {@code xProperty()} -> getter -> same-named backing field.
     * When none have docs the tiebreak is: setter -> {@code xProperty()} -> getter
     * (mirrors plain-assignment / {@code fx:Evaluate} behavior).
     * Falls back to a {@code @NamedArg} constructor parameter when no accessor exists.
     *
     * <p>Delegates to {@link #resolveInstanceProperty(PsiClass, String, Collection, Kind)}
     * with {@code kind = null}.
     *
     * @param ownerClass   the class to search (may be {@code null})
     * @param propertyName the property/attribute name
     * @return the best-matching declaration, or {@code null}
     */
    public static @Nullable PsiElement resolveInstanceProperty(
            @Nullable PsiClass ownerClass, @NotNull String propertyName) {
        return resolveInstanceProperty(ownerClass, propertyName, List.of());
    }

    /**
     * Like {@link #resolveInstanceProperty(PsiClass, String)} but also accepts the set of
     * all attribute names present on the same tag, used to select the best-matching NamedArgs
     * constructor (the one whose required parameters are all satisfied).
     * Delegates to {@link #resolveInstanceProperty(PsiClass, String, Collection, Kind)}
     * with {@code kind = null}.
     */
    public static @Nullable PsiElement resolveInstanceProperty(
            @Nullable PsiClass ownerClass,
            @NotNull String propertyName,
            @NotNull Collection<String> siblingAttributes) {
        return resolveInstanceProperty(ownerClass, propertyName, siblingAttributes, null);
    }

    /**
     * Like {@link #resolveInstanceProperty(PsiClass, String, Collection)} but selects the
     * navigation target using the binding kind from the FXML compiler, matching which
     * accessor the compiler actually uses at runtime:
     *
     * <ul>
     *   <li>{@link Kind#EVALUATE}, {@link Kind#EVALUATE_CONTENT}: compiler uses setter
     *       (then xProperty): prefer setter.</li>
     *   <li>{@link Kind#OBSERVE}, {@link Kind#OBSERVE_CONTENT}: compiler uses xProperty
     *       (then getter): prefer xProperty.</li>
     *   <li>{@link Kind#PUSH}, {@link Kind#PUSH_CONTENT}: compiler uses xProperty
     *       (then getter): prefer xProperty.</li>
     *   <li>{@link Kind#SYNCHRONIZE}, {@link Kind#SYNCHRONIZE_CONTENT}: compiler requires
     *       xProperty: prefer xProperty.</li>
     * </ul>
     *
     * <p>Javadoc always wins, if any accessor has docs, that accessor is returned regardless
     * of kind.  The kind-based order is only the tiebreak when none have docs.
     */
    public static @Nullable PsiElement resolveInstanceProperty(
            @Nullable PsiClass ownerClass,
            @NotNull String propertyName,
            @NotNull Collection<String> siblingAttributes,
            @Nullable Kind kind) {
        return resolveInstancePropertyImpl(ownerClass, propertyName, siblingAttributes, kind);
    }

    private static @Nullable PsiElement resolveInstancePropertyImpl(
            @Nullable PsiClass ownerClass,
            @NotNull String propertyName,
            @NotNull Collection<String> siblingAttributes,
            @Nullable Kind kind) {
        if (ownerClass == null || propertyName.isEmpty()) return null;

        // Cache key: "propertyName|kindName" where kind is empty string for null.
        String cacheKey = propertyName + "|" + (kind == null ? "" : kind.name());

        // Use both the owner class and the Java-language modification tracker as dependencies.
        // The Java tracker ensures the cache is invalidated whenever any Java source file changes,
        // which prevents stale results when a property is inherited from a superclass that was
        // modified in a different file. For library classes loaded from JARs the Java tracker
        // changes rarely; for user source it changes on each commit or edit, but within a single
        // daemon pass computeIfAbsent still prevents duplicate work across concurrent threads.
        ConcurrentHashMap<String, Optional<PsiElement>> cache =
                CachedValuesManager.getCachedValue(ownerClass, INSTANCE_PROPERTY_CACHE,
                        () -> CachedValueProvider.Result.create(
                                new ConcurrentHashMap<>(),
                                modificationDependencies(ownerClass)));

        if (siblingAttributes.isEmpty()) {
            // Standard path: compute atomically so that only one thread pays the cost of a
            // cache miss even when multiple annotator threads analyze the same class concurrently.
            return cache.computeIfAbsent(cacheKey, k ->
                    Optional.ofNullable(resolveInstancePropertyUncached(ownerClass, propertyName, List.of(), kind))
            ).orElse(null);
        }

        // Non-empty siblings path.
        // Siblings are only relevant for @NamedArg constructor disambiguation, which is only
        // reached when the class has NO getter, setter, or xProperty() method at all.
        //
        // Fast pre-check before entering computeIfAbsent to avoid lock acquisition overhead
        // on the hot (already-cached) path.
        Optional<PsiElement> cached = cache.get(cacheKey);
        if (cached != null && cached.isPresent()) return cached.get();

        // Not yet cached or cached as null (previous NamedArg-only lookup).
        // Atomically try to populate the cache with a no-siblings resolution, which is correct
        // for any property that has a getter/setter/propertyGetter (the vast majority).
        // computeIfAbsent guarantees at most one thread does the expensive PSI lookup per key.
        // If the no-siblings resolution yields null, fall back to sibling-dependent resolution
        // (only needed for @NamedArg constructor disambiguation on value-object classes).
        return cache.computeIfAbsent(cacheKey, k ->
                Optional.ofNullable(resolveInstancePropertyUncached(ownerClass, propertyName, List.of(), kind)))
                .orElseGet(() -> resolveInstancePropertyUncached(ownerClass, propertyName, siblingAttributes, kind));
    }

    private static @Nullable PsiElement resolveInstancePropertyUncached(
            @NotNull PsiClass ownerClass,
            @NotNull String propertyName,
            @NotNull Collection<String> siblingAttributes,
            @Nullable Kind kind) {

        PsiMethod setter         = findSetter(ownerClass, propertyName);
        PsiMethod getter         = findGetter(ownerClass, propertyName);
        PsiMethod propertyGetter = findPropertyGetter(ownerClass, propertyName);

        if (setter == null && getter == null && propertyGetter == null) {
            // Check for a plain public instance field with the given name (e.g. public String plainField).
            // The FXML compiler supports plain fields as binding path segments and assignment targets.
            PsiField plainField = findPublicInstanceField(ownerClass, propertyName);
            if (plainField != null) return plainField;
            // Check for a plain public instance method with the property name directly
            // (e.g. public String userName() or public <T> Property<T> genericValue()).
            // "a plain field or method with the name userName, returning a String"
            PsiMethod bareMethod = findBareMethod(ownerClass, propertyName);
            if (bareMethod != null) return bareMethod;
            // Kotlin-source fallback: in K2 mode, synthetic property getters (val/var foo)
            // may not be present in findMethodsByName results when the class hierarchy contains
            // unresolvable supertypes (e.g., a compiler-generated base class that is not yet
            // compiled). Fall back to reading the Kotlin source PSI directly.
            PsiElement ktProp = findKotlinProperty(ownerClass, propertyName);
            if (ktProp != null) return ktProp;
            return Fxml2NamedArgResolver.resolveNamedArgIfApplicable(ownerClass, propertyName, siblingAttributes);
        }

        // Build the preferred order based on kind, then check javadoc in that order.
        // This means for fx:Observe we check xProperty first, so if it has docs it wins over setter.
        PsiMethod first, second, third;
        if (kind == null || kind == Kind.EVALUATE || kind == Kind.EVALUATE_CONTENT) {
            // Compiler uses setter -> xProperty().set()
            first = setter; second = propertyGetter; third = getter;
        } else {
            // OBSERVE / SYNCHRONIZE / content variants: compiler uses xProperty() -> getter
            first = propertyGetter; second = getter; third = setter;
        }

        if (first  != null && hasJavadoc(first))  return first;
        if (second != null && hasJavadoc(second)) return second;
        if (third  != null && hasJavadoc(third))  return third;

        PsiField backingField = findNonStaticField(ownerClass, propertyName);
        if (backingField != null && hasJavadoc(backingField)) return backingField;

        // No javadoc: fall back to kind-preferred order.
        if (first  != null) return first;
        return java.util.Objects.requireNonNullElse(second, third);
    }

    /**
     * Resolves a static property on {@code declaringClass}: the static setter
     * {@code static void setName(Node, T)}, or the static getter if no setter exists.
     *
     * <p>Results are cached per {@link PsiClass} using the class itself as the dependency,
     * so the cache survives commits that do not touch the declaring class's source file.
     */
    public static @Nullable PsiElement resolveStaticProperty(
            @NotNull PsiClass declaringClass, @NotNull String propertyName) {
        ConcurrentHashMap<String, Optional<PsiElement>> cache =
                CachedValuesManager.getCachedValue(declaringClass, STATIC_PROPERTY_CACHE,
                        () -> CachedValueProvider.Result.create(
                                new ConcurrentHashMap<>(),
                                modificationDependencies(declaringClass)));
        return cache.computeIfAbsent(propertyName, k -> {
            PsiMethod setter = findStaticSetter(declaringClass, propertyName);
            return Optional.ofNullable(setter != null ? setter : findStaticGetter(declaringClass, propertyName));
        }).orElse(null);
    }

    /**
     * Returns all settable/gettable instance property names on {@code cls} and its supertypes.
     * Includes names derived from:
     * <ul>
     *   <li>setters ({@code setFoo}),</li>
     *   <li>getters ({@code getFoo}/{@code isFoo}),</li>
     *   <li>{@code fooProperty()} methods, and</li>
     *   <li>plain {@code public} non-static fields (e.g. {@code public StringProperty message}).</li>
     * </ul>
     *
     * <p>Results are cached per {@link PsiClass} using the class itself and the Java-language
     * PSI modification tracker as dependencies (see {@link #INSTANCE_PROPERTY_CACHE} for rationale).
     */
    public static @NotNull Set<String> getAllPropertyNames(@NotNull PsiClass cls) {
        return CachedValuesManager.getCachedValue(cls, ALL_PROPERTY_NAMES_CACHE,
                () -> CachedValueProvider.Result.create(
                        computeAllPropertyNames(cls),
                        modificationDependencies(cls)));
    }

    private static @NotNull Set<String> computeAllPropertyNames(@NotNull PsiClass cls) {
        Set<String> names = new LinkedHashSet<>();
        for (PsiMethod m : cls.getAllMethods()) {
            if (!isPublicInstance(m)) continue;
            String mName = m.getName();
            // setFoo(T) -> "foo"
            if (Fxml2PropertyNameUtil.isSetterName(mName)
                    && m.getParameterList().getParametersCount() == 1
                    && m.getReturnType() != null && m.getReturnType().equals(PsiTypes.voidType())) {
                names.add(Fxml2PropertyNameUtil.setterToPropertyName(mName));
            }
            // getFoo() / isFoo() -> "foo"
            if (m.getParameterList().isEmpty()
                    && m.getReturnType() != null && !m.getReturnType().equals(PsiTypes.voidType())) {
                String prop = PropertyUtilBase.getPropertyName(m);
                if (prop != null) names.add(prop);
            }
            // fooProperty() -> "foo"
            if (Fxml2PropertyNameUtil.isPropertyAccessorName(mName) && m.getParameterList().isEmpty()) {
                names.add(Fxml2PropertyNameUtil.propertyAccessorToPropertyName(mName));
            }
        }
        // Plain non-private non-static fields are also valid binding path segments per the
        // FXML compiler's property resolution rules (public, protected, package-private).
        for (PsiField f : cls.getAllFields()) {
            if (!f.hasModifierProperty(PsiModifier.PRIVATE) && !f.hasModifierProperty(PsiModifier.STATIC)) {
                names.add(f.getName());
            }
        }
        return names;
    }

    /**
     * Returns all static property names on {@code cls}:
     * names derived from static setters {@code setFoo(Node, T)} and static getters {@code getFoo(Node)}.
     *
     * <p>Results are cached per {@link PsiClass} using the class itself and the Java-language
     * PSI modification tracker as dependencies (see {@link #INSTANCE_PROPERTY_CACHE} for rationale).
     */
    public static @NotNull Set<String> getAllStaticPropertyNames(@NotNull PsiClass cls) {
        return CachedValuesManager.getCachedValue(cls, ALL_STATIC_PROPERTY_NAMES_CACHE,
                () -> CachedValueProvider.Result.create(
                        computeAllStaticPropertyNames(cls),
                        modificationDependencies(cls)));
    }

    private static @NotNull Set<String> computeAllStaticPropertyNames(@NotNull PsiClass cls) {
        Set<String> names = new LinkedHashSet<>();
        for (PsiMethod m : cls.getAllMethods()) {
            if (!isPublicStatic(m)) continue;
            String mName = m.getName();
            int paramCount = m.getParameterList().getParametersCount();
            // static setFoo(Node, T) -> "foo"
            if (Fxml2PropertyNameUtil.isSetterName(mName)
                    && paramCount == 2
                    && m.getReturnType() != null && m.getReturnType().equals(PsiTypes.voidType())) {
                names.add(Fxml2PropertyNameUtil.setterToPropertyName(mName));
            }
            // static getFoo(Node) / isFoo(Node) -> "foo"
            if (paramCount == 1
                    && m.getReturnType() != null && !m.getReturnType().equals(PsiTypes.voidType())) {
                if (Fxml2PropertyNameUtil.isGetterName(mName)) {
                    names.add(Fxml2PropertyNameUtil.getterToPropertyName(mName));
                }
            }
        }
        return names;
    }

    /**
     * Returns the {@link PsiClass} that a property declaration resolves to: the setter
     * parameter type, the getter return type, or the constructor parameter type: unwrapping
     * one level of {@code ObservableValue<T>} where applicable.
     *
     * <p>This is a shared helper used by the annotator and tag resolver to walk property chains.
     */
    public static @Nullable PsiClass resolvePropertyType(@NotNull PsiElement decl) {
        PsiType type;
        switch (decl) {
            case PsiParameter param -> type = param.getType();
            case PsiField field -> {
                // A backing field (e.g. ObjectProperty<SelectionMode> selectionModeProperty)
                // carries the *observable* type, not the plain value type.
                // Prefer the setter parameter type or getter return type, which always carry
                // the unwrapped value type. Fall back to the raw field type only as last resort.
                PsiClass owner = field.getContainingClass();
                if (owner != null) {
                    // Derive the property name from the field name:
                    // "selectionModeProperty" -> "selectionMode"  (strip trailing "Property")
                    // "selectionMode"         -> "selectionMode"  (no suffix)
                    String fieldName = field.getName();
                    String propName = Fxml2PropertyNameUtil.isPropertyAccessorName(fieldName)
                            ? Fxml2PropertyNameUtil.propertyAccessorToPropertyName(fieldName)
                            : fieldName;
                    PsiMethod setter = findSetter(owner, propName);
                    if (setter != null) {
                        PsiParameter[] params = setter.getParameterList().getParameters();
                        if (params.length == 1) {
                            return PsiUtil.resolveClassInType(params[0].getType());
                        }
                    }
                    PsiMethod getter = findGetter(owner, propName);
                    if (getter != null) {
                        PsiType ret = getter.getReturnType();
                        if (ret != null && !ret.equals(PsiTypes.voidType())) {
                            return PsiUtil.resolveClassInType(ret);
                        }
                    }
                }
                type = field.getType();
            }
            case PsiMethod method -> {
                type = method.getReturnType();
                if (type == null || type.equals(PsiTypes.voidType())) {
                    if (method.getParameterList().getParametersCount() == 1) {
                        type = method.getParameterList().getParameters()[0].getType();
                    } else {
                        return null;
                    }
                }
            }
            default -> {
                return null;
            }
        }
        return PsiUtil.resolveClassInType(type);
    }

    // -----------------------------------------------------------------------
    // Package-accessible helpers for type resolution
    // -----------------------------------------------------------------------

    /**
     * Returns the plain-value setter {@code setName(T)} for {@code propertyName} on
     * {@code ownerClass}, or {@code null} if none exists.
     * Used by {@link Fxml2AttributeValueResolver} to derive the coercion type.
     */
    static @Nullable PsiMethod findSetterFor(@NotNull PsiClass ownerClass, @NotNull String propertyName) {
        return findSetter(ownerClass, propertyName);
    }

    /**
     * Returns the plain-value getter {@code getName()} / {@code isName()} for
     * {@code propertyName} on {@code ownerClass}, or {@code null} if none exists.
     * Used by {@link Fxml2AttributeValueResolver} to derive the coercion type.
     */
    static @Nullable PsiMethod findGetterFor(@NotNull PsiClass ownerClass, @NotNull String propertyName) {
        return findGetter(ownerClass, propertyName);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the cache dependency array used by all per-class {@link CachedValue} instances:
     * the class element itself, the Java-language modification tracker (invalidated on any Java
     * source change), and, when the Kotlin plugin is present, the Kotlin-language modification
     * tracker (invalidated on any Kotlin source change).
     *
     * <p>Including the Kotlin tracker is necessary because property-resolution results are stored
     * in user data keyed by the code-behind {@link PsiClass} instance.  In the live IDE that
     * instance persists across annotator passes; without the Kotlin tracker the cache would
     * survive Kotlin source edits such as adding or removing a {@code fun vmProperty()} accessor,
     * causing stale resolution results until the IDE is restarted.
     */
    private static Object[] modificationDependencies(@NotNull PsiClass cls) {
        PsiModificationTracker tracker = PsiModificationTracker.getInstance(cls.getProject());
        com.intellij.lang.Language kotlinLang =
                com.intellij.lang.Language.findLanguageByID("kotlin");
        if (kotlinLang != null) {
            return new Object[]{
                    cls,
                    tracker.forLanguage(JavaLanguage.INSTANCE),
                    tracker.forLanguage(kotlinLang)
            };
        }
        return new Object[]{cls, tracker.forLanguage(JavaLanguage.INSTANCE)};
    }

    /** Finds a public non-static no-arg method {@code nameProperty()} returning ObservableValue. */
    private static @Nullable PsiMethod findPropertyGetter(@NotNull PsiClass cls, @NotNull String name) {
        String methodName = name + "Property";
        for (PsiMethod m : cls.findMethodsByName(methodName, true)) {
            if (isPublicInstance(m) && m.getParameterList().isEmpty()) {
                return m;
            }
        }
        return null;
    }

    /** Finds {@code getName()} or {@code isName()}: public, non-static, no-arg, non-void return. */
    private static @Nullable PsiMethod findGetter(@NotNull PsiClass cls, @NotNull String name) {
        // Use targeted name lookups instead of getAllMethods() to avoid scanning
        // the full class hierarchy when the property doesn't exist.
        String capName = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String getterName : new String[]{"get" + capName, "is" + capName}) {
            for (PsiMethod m : cls.findMethodsByName(getterName, true)) {
                if (isPublicInstance(m)
                        && m.getParameterList().isEmpty()
                        && m.getReturnType() != null
                        && !m.getReturnType().equals(PsiTypes.voidType())) {
                    return m;
                }
            }
        }
        // Fallback for Kotlin classes: in K2 mode, synthetic property getters (e.g. getVm()
        // for "val vm") may not appear in findMethodsByName results when the class hierarchy
        // has unresolvable supertypes. Use getMethods() which does not rely on the name index.
        return findGetterInDeclaredMethods(cls, capName);
    }

    /**
     * Fallback getter lookup that iterates {@link PsiClass#getMethods()} directly.
     * Only applied to classes backed by Kotlin source, where K2 light-class synthetic
     * getters may be absent from the method-name index in certain error states.
     */
    private static @Nullable PsiMethod findGetterInDeclaredMethods(
            @NotNull PsiClass cls, @NotNull String capName) {
        try {
            if (!(cls.getNavigationElement()
                    instanceof org.jetbrains.kotlin.psi.KtClassOrObject)) {
                return null;
            }
        } catch (NoClassDefFoundError ignored) {
            return null;
        }
        for (PsiMethod m : cls.getMethods()) {
            String mName = m.getName();
            if (!mName.equals("get" + capName) && !mName.equals("is" + capName)) continue;
            if (isPublicInstance(m)
                    && m.getParameterList().isEmpty()
                    && m.getReturnType() != null
                    && !m.getReturnType().equals(PsiTypes.voidType())) {
                return m;
            }
        }
        return null;
    }

    /**
     * Fallback for Kotlin classes when synthetic getters cannot be found through normal
     * PSI lookups: looks for a non-private {@code KtProperty} with the given name in the
     * Kotlin source of {@code cls}.  Returns the {@code KtProperty} itself so that
     * Ctrl+click navigates directly to the Kotlin property declaration.
     */
    private static @Nullable PsiElement findKotlinProperty(
            @NotNull PsiClass cls, @NotNull String name) {
        try {
            PsiElement nav = cls.getNavigationElement();
            if (nav instanceof org.jetbrains.kotlin.psi.KtClassOrObject ktClass) {
                for (org.jetbrains.kotlin.psi.KtDeclaration decl : ktClass.getDeclarations()) {
                    if (decl instanceof org.jetbrains.kotlin.psi.KtProperty ktProp
                            && name.equals(ktProp.getName())
                            && !ktProp.hasModifier(
                                    org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD)) {
                        return ktProp;
                    }
                }
            }
        } catch (NoClassDefFoundError ignored) {
            // Kotlin plugin absent at runtime.
        }
        return null;
    }

    /** Finds {@code setName(T)}: public, non-static, single-arg, void return. */
    private static @Nullable PsiMethod findSetter(@NotNull PsiClass cls, @NotNull String name) {
        String setterName = PropertyUtilBase.suggestSetterName(name);
        for (PsiMethod m : cls.findMethodsByName(setterName, true)) {
            if (isPublicInstance(m)
                    && m.getParameterList().getParametersCount() == 1
                    && m.getReturnType() != null
                    && m.getReturnType().equals(com.intellij.psi.PsiTypes.voidType())) {
                return m;
            }
        }
        return null;
    }

    /**
     * Finds {@code public static void setName(Node, T)} on {@code declaringClass}.
     * The first parameter can be any type (not restricted to Node, for flexibility).
     */
    private static @Nullable PsiMethod findStaticSetter(@NotNull PsiClass declaringClass, @NotNull String name) {
        String setterName = PropertyUtilBase.suggestSetterName(name);
        for (PsiMethod m : declaringClass.findMethodsByName(setterName, true)) {
            if (isPublicStatic(m)
                    && m.getParameterList().getParametersCount() == 2
                    && m.getReturnType() != null
                    && m.getReturnType().equals(com.intellij.psi.PsiTypes.voidType())) {
                return m;
            }
        }
        return null;
    }

    /**
     * Finds {@code public static T getName(Node)} on {@code declaringClass}.
     * Static property getters take exactly one parameter (the receiver/node).
     *
     * <p>Uses targeted name-based lookup ({@code findMethodsByName}) rather than scanning all
     * methods, matching only the {@code get{Name}} and {@code is{Name}} variants expected by the
     * FXML compiler for static property accessors.
     */
    private static @Nullable PsiMethod findStaticGetter(@NotNull PsiClass declaringClass, @NotNull String name) {
        if (name.isEmpty()) return null;
        String capName = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String getterName : new String[]{"get" + capName, "is" + capName}) {
            for (PsiMethod m : declaringClass.findMethodsByName(getterName, true)) {
                if (isPublicStatic(m)
                        && m.getParameterList().getParametersCount() == 1
                        && m.getReturnType() != null
                        && !m.getReturnType().equals(com.intellij.psi.PsiTypes.voidType())) {
                    return m;
                }
            }
        }
        return null;
    }

    private static boolean isPublicInstance(@NotNull PsiMethod m) {
        return m.hasModifierProperty(PsiModifier.PUBLIC)
                && !m.hasModifierProperty(PsiModifier.STATIC);
    }

    private static boolean isPublicStatic(@NotNull PsiMethod m) {
        return m.hasModifierProperty(PsiModifier.PUBLIC)
                && m.hasModifierProperty(PsiModifier.STATIC);
    }

    /**
     * Finds a public non-static no-arg method whose name equals {@code propertyName}
     * exactly (not {@code getFoo()}, but {@code foo()} directly).
     * "a plain field or method with the name userName, returning a String".
     */
    private static @Nullable PsiMethod findBareMethod(@NotNull PsiClass cls, @NotNull String propertyName) {
        for (PsiMethod m : cls.findMethodsByName(propertyName, true)) {
            if (isPublicInstance(m)
                    && m.getParameterList().isEmpty()
                    && m.getReturnType() != null
                    && !m.getReturnType().equals(com.intellij.psi.PsiTypes.voidType())) {
                return m;
            }
        }
        return null;
    }

    /**
     * Searches {@code cls} and its superclass chain for a non-private, non-static field
     * named {@code propertyName}. Plain non-private fields are valid binding path segments
     * per the FXML compiler's {@code tryResolveField} logic, which accepts public,
     * protected, and package-private (default-access) fields: the generated base class is
     * always in the same package as the code-behind, so package-private fields are accessible.
     */
    private static @Nullable PsiField findPublicInstanceField(@NotNull PsiClass cls, @NotNull String propertyName) {
        PsiClass cursor = cls;
        while (cursor != null) {
            PsiField field = cursor.findFieldByName(propertyName, false);
            if (field != null
                    && !field.hasModifierProperty(PsiModifier.PRIVATE)
                    && !field.hasModifierProperty(PsiModifier.STATIC)) {
                return field;
            }
            cursor = cursor.getSuperClass();
        }
        return null;
    }

    /**
     * Searches {@code cls} and its superclass chain for the first non-static field whose
     * name equals {@code propertyName}.  This is the backing field where JavaFX places its
     * javadocs (e.g. {@code TextInputControl.text}, {@code Region.maxWidth}).
     */
    private static @Nullable PsiField findNonStaticField(@NotNull PsiClass cls, @NotNull String propertyName) {
        PsiClass cursor = cls;
        while (cursor != null) {
            PsiField field = cursor.findFieldByName(propertyName, false);
            if (field != null && !field.hasModifierProperty(PsiModifier.STATIC)) {
                return field;
            }
            cursor = cursor.getSuperClass();
        }
        return null;
    }

    /** Returns {@code true} if {@code field} has a non-trivial Javadoc/KDoc comment. */
    private static boolean hasJavadoc(@NotNull PsiField field) {
        PsiElement nav = field.getNavigationElement();

        // Java source
        if (nav instanceof PsiField f) {
            PsiDocComment doc = f.getDocComment();
            return doc != null && doc.getText().length() > 6;
        }

        // Kotlin source (KtProperty etc.): check via reflection
        if (nav != null) {
            try {
                java.lang.reflect.Method getDoc = nav.getClass().getMethod("getDocComment");
                return getDoc.invoke(nav) != null;
            } catch (ReflectiveOperationException ignored) {}
        }
        return false;
    }

    /**
     * Returns {@code true} if {@code method} has a non-empty Javadoc comment attached to it
     * (either directly or, for inherited methods, on the nearest super-declaration that has one).
     * Calls {@link PsiElement#getNavigationElement()} first so that compiled classes with
     * attached sources are resolved to their source-backed PSI element.
     */
    private static boolean hasJavadoc(@NotNull PsiMethod method) {
        if (hasJavadocDirect(method)) return true;
        for (PsiMethod super_ : method.findDeepestSuperMethods()) {
            if (hasJavadocDirect(super_)) return true;
        }
        return false;
    }

    private static boolean hasJavadocDirect(@NotNull PsiMethod method) {
        PsiElement nav = method.getNavigationElement();

        // Java source: navigation element is a PsiMethod with a PsiDocComment.
        if (nav instanceof PsiMethod m) {
            PsiDocComment doc = m.getDocComment();
            return doc != null && doc.getText().length() > 6;
        }

        // Kotlin source: navigation element is a KtDeclaration whose getDocComment()
        // returns a KDoc (org.jetbrains.kotlin.kdoc.psi.api.KDoc), not a PsiDocComment.
        // Avoid a compile-time dependency on the Kotlin plugin by calling reflectively.
        if (nav != null) {
            try {
                java.lang.reflect.Method getDoc = nav.getClass().getMethod("getDocComment");
                Object kdoc = getDoc.invoke(nav);
                return kdoc != null;
            } catch (ReflectiveOperationException ignored) {
                // Not a KtDeclaration or method unavailable: fall through.
            }
        }

        // Compiled class without sources: no doc available.
        return false;
    }
}
