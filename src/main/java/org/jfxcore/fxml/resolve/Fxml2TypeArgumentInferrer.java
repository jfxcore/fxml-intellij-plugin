package org.jfxcore.fxml.resolve;

import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.PsiWildcardType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Infers a value for {@code fx:typeArguments} on a generic-class tag from surrounding
 * context, when an unambiguous choice exists.
 *
 * <p>Four inference sources are tried in order:
 * <ol>
 *   <li><b>Parent property type</b>: the tag is the value of a typed property of its
 *       parent (property-element form) or a child of a default property whose
 *       element type fixes the tag's type variables.</li>
 *   <li><b>Typed attribute value</b>: an attribute of the tag pins one or more of the
 *       tag's type variables.  Two attribute shapes are accumulated into the same
 *       substitution:
 *       <ul>
 *         <li>a binding expression whose source path's static type unifies with the
 *             property's declared type;</li>
 *         <li>a plain-text class-literal value targeting a {@code Class<T>}-typed
 *             property (mirroring the compiler's {@code Class<T>} coercion via the
 *             file's {@code <?import?>} PIs).</li>
 *       </ul>
 *   </li>
 *   <li><b>{@code fx:subclass} supertype</b>: for the root tag, the code-behind class
 *       declared by {@code fx:subclass} extends a parameterized form of the root
 *       tag's class.</li>
 *   <li><b>Final upper bound</b>: every type parameter on the tag class has a single
 *       {@code final}-class upper bound, leaving exactly one legal value.</li>
 * </ol>
 *
 * <p>Inference is rejected (returns {@code null}) whenever the candidate type
 * arguments contain wildcards, unresolved type variables, or conflict across
 * multiple inference inputs. The compiler diagnostic
 * {@code WILDCARD_CANNOT_BE_INSTANTIATED} would reject a wildcard result.
 */
public final class Fxml2TypeArgumentInferrer {

    private static final String OBSERVABLE_VALUE_FQN = "javafx.beans.value.ObservableValue";
    private static final String COLLECTION_FQN = "java.util.Collection";
    private static final String DEFAULT_PROPERTY_FQN = "javafx.beans.DefaultProperty";

    private Fxml2TypeArgumentInferrer() {}

    /**
     * Result of a successful inference: the rendered {@code fx:typeArguments} value
     * (using simple names where possible) and the list of fully-qualified class names
     * that must be imported into the file for the rendered value to resolve.
     *
     * <p>{@code importsToAdd} is empty when every referenced type is already in scope
     * (existing {@code <?import?>} PI, exact match against {@code java.lang}, or
     * default-package class).  It preserves the order in which types first appear in
     * the rendered value; duplicates are removed.
     */
    public record InferenceResult(@NotNull String renderedArgs, @NotNull List<String> importsToAdd) {}

    /**
     * Returns an {@link InferenceResult} for {@code tag} from surrounding context, or
     * {@code null} when no source produces a fully-concrete substitution.
     *
     * @param tag      the tag instantiating {@code tagClass}
     * @param tagClass the resolved class of {@code tag}, which must have one or more
     *                 type parameters
     */
    public static @Nullable InferenceResult infer(@NotNull XmlTag tag, @NotNull PsiClass tagClass) {
        PsiTypeParameter[] typeParams = tagClass.getTypeParameters();
        if (typeParams.length == 0) return null;
        if (!(tag.getContainingFile() instanceof XmlFile xmlFile)) return null;

        PsiType[] args = tryParentProperty(tag, tagClass, xmlFile);
        if (args == null) args = tryAttributeValues(tag, tagClass, xmlFile);
        if (args == null) args = trySubclassSupertype(tag, tagClass, xmlFile);
        if (args == null) args = tryFinalUpperBound(tagClass);
        if (args == null) return null;

        return renderArgs(args, xmlFile);
    }

    // -----------------------------------------------------------------------
    // Source 1 - parent property type
    // -----------------------------------------------------------------------

    private static @Nullable PsiType[] tryParentProperty(
            @NotNull XmlTag tag, @NotNull PsiClass tagClass, @NotNull XmlFile xmlFile) {
        XmlTag parentTag = tag.getParentTag();
        if (parentTag == null) return null;

        PsiClass ownerClass;
        XmlTag ownerTag;
        String propName;

        PsiClass parentTagClass = Fxml2BindingPathResolver.resolveTagClass(parentTag);
        if (parentTagClass == null) {
            // Property-element form: parent tag's name is the property name on the grandparent.
            XmlTag grandparent = parentTag.getParentTag();
            if (grandparent == null) return null;
            ownerClass = Fxml2BindingPathResolver.resolveTagClass(grandparent);
            ownerTag = grandparent;
            propName = parentTag.getLocalName();
            if (propName.contains(".")) {
                // Attached-property element (e.g. <GridPane.margin>) - not supported.
                return null;
            }
        } else {
            // Class-tag parent: tag is treated as the value of the default property.
            ownerClass = parentTagClass;
            ownerTag = parentTag;
            propName = readDefaultPropertyName(parentTagClass);
            if (propName == null) return null;
        }
        if (ownerClass == null || propName.isBlank()) return null;

        PsiElement propDecl = Fxml2PropertyResolver.resolveInstanceProperty(ownerClass, propName);
        if (propDecl == null) return null;

        PsiType propType = resolvePropertyValueType(propDecl, ownerClass, ownerTag, xmlFile);
        if (propType == null) return null;

        PsiType[] result = unifyTagAgainstTargetType(tagClass, propType);
        if (result != null) return result;

        // Collection-element fallback: the child becomes an element of a collection-typed property.
        PsiType elementType = collectionElementType(propType, xmlFile.getProject(), resolveScope(xmlFile));
        if (elementType != null) {
            result = unifyTagAgainstTargetType(tagClass, elementType);
            return result;
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Source 2 - typed attribute value (binding source OR Class<T> literal)
    // -----------------------------------------------------------------------

    private static @Nullable PsiType[] tryAttributeValues(
            @NotNull XmlTag tag, @NotNull PsiClass tagClass, @NotNull XmlFile xmlFile) {
        PsiTypeParameter[] typeParams = tagClass.getTypeParameters();
        Set<PsiTypeParameter> typeParamSet = new LinkedHashSet<>(Arrays.asList(typeParams));

        Map<PsiTypeParameter, PsiType> bindings = new IdentityHashMap<>();
        boolean foundAny = false;
        Project project = xmlFile.getProject();
        GlobalSearchScope scope = resolveScope(xmlFile);
        Map<Character, String> prefixes = Fxml2ImportResolver.parsePrefixMappings(xmlFile);

        for (XmlAttribute attr : tag.getAttributes()) {
            String ns = attr.getNamespace();
            if (Fxml2ImportResolver.FXML2_NAMESPACE.equals(ns)) continue;
            String localName = attr.getLocalName();
            if (localName.isEmpty() || localName.contains(".")) continue;
            String value = attr.getValue();
            if (value == null) continue;

            // Property declared type on tagClass (uses tagClass's type parameters as free vars).
            PsiType propType = readDeclaredPropertyType(tagClass, localName, project, scope);
            if (propType == null) continue;
            if (!containsAnyOf(propType, typeParamSet)) continue;

            PsiType contribution =
                    Fxml2BindingExpressionParser.looksLikeBindingExpression(value, prefixes)
                            ? sourceTypeFromBinding(value, tag, xmlFile)
                            : classLiteralAsClassType(value, propType, xmlFile, project, scope);
            if (contribution == null) continue;

            // Unify into a fresh per-attribute map first, then merge into the shared
            // substitution.  This makes cross-attribute conflicts on the same type
            // variable visible: a successful unification that disagrees with an earlier
            // attribute's binding cancels the whole inference, rather than silently
            // letting the first attribute win.
            Map<PsiTypeParameter, PsiType> attrBindings = new IdentityHashMap<>();
            if (!tryUnifyInto(propType, contribution, attrBindings, typeParamSet, project, scope)) {
                continue;
            }
            for (Map.Entry<PsiTypeParameter, PsiType> entry : attrBindings.entrySet()) {
                PsiType existing = bindings.get(entry.getKey());
                if (existing != null && !typesEqual(existing, entry.getValue())) {
                    return null;
                }
                bindings.put(entry.getKey(), entry.getValue());
            }
            foundAny = true;
        }

        if (!foundAny) return null;
        return buildResultArray(typeParams, bindings);
    }

    /**
     * Resolves the binding source path's static type for unification.  Returns
     * {@code null} when the value is not a parsable binding expression, when the source
     * path uses constructs the inferrer deliberately does not support (see
     * {@link #resolveBindingSourceType}), or when the resolved type is not fully concrete.
     */
    private static @Nullable PsiType sourceTypeFromBinding(
            @NotNull String value, @NotNull XmlTag tag, @NotNull XmlFile xmlFile) {
        Fxml2BindingExpressionParser.ParsedExpression expr =
                Fxml2BindingExpressionParser.parseExpression(value);
        if (expr == null) return null;
        PsiType srcType = resolveBindingSourceType(expr, tag, xmlFile);
        if (srcType == null) return null;
        if (isNotFullyConcrete(srcType)) return null;
        return srcType;
    }

    /**
     * For a plain-text attribute value targeting a {@code Class<T>}-typed property,
     * resolves the value as a class literal (via the file's {@code <?import?>} PIs) and
     * wraps the result in a {@code Class<resolved>} type ready for unification against
     * the property's pattern.  Returns {@code null} when the property type is not a
     * {@code Class<...>}, or when the literal cannot be resolved, or when the resolved
     * class violates the {@code Class<? extends Foo>} bound encoded in the property type.
     */
    private static @Nullable PsiClassType classLiteralAsClassType(
            @NotNull String value,
            @NotNull PsiType propType,
            @NotNull XmlFile xmlFile,
            @NotNull Project project,
            @NotNull GlobalSearchScope scope) {
        PsiClass propClass = PsiUtil.resolveClassInType(propType);
        if (propClass == null || !"java.lang.Class".equals(propClass.getQualifiedName())) return null;
        PsiClass resolved = Fxml2AttributeValueResolver.resolveClassLiteralRef(
                value.trim(), propType, xmlFile, scope);
        if (resolved == null) return null;
        PsiClass classCls = JavaPsiFacade.getInstance(project).findClass("java.lang.Class", scope);
        if (classCls == null) return null;
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        return factory.createType(classCls, factory.createType(resolved));
    }

    /**
     * Tries to unify {@code pattern} against {@code concrete}, accepting unwrappings of
     * {@code concrete} through {@code ObservableValue<T>} when a direct match fails.
     */
    private static boolean tryUnifyInto(
            @NotNull PsiType pattern,
            @NotNull PsiType concrete,
            @NotNull Map<PsiTypeParameter, PsiType> bindings,
            @NotNull Set<PsiTypeParameter> targetParams,
            @NotNull Project project,
            @NotNull GlobalSearchScope scope) {
        Map<PsiTypeParameter, PsiType> attempt = new IdentityHashMap<>(bindings);
        if (unify(pattern, concrete, attempt, targetParams)) {
            bindings.clear();
            bindings.putAll(attempt);
            return true;
        }
        PsiType unwrapped = unwrapObservable(concrete, project, scope);
        if (unwrapped != null) {
            attempt = new IdentityHashMap<>(bindings);
            if (unify(pattern, unwrapped, attempt, targetParams)) {
                bindings.clear();
                bindings.putAll(attempt);
                return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Source 3 - fx:subclass supertype
    // -----------------------------------------------------------------------

    private static @Nullable PsiType[] trySubclassSupertype(
            @NotNull XmlTag tag, @NotNull PsiClass tagClass, @NotNull XmlFile xmlFile) {
        XmlDocument doc = xmlFile.getDocument();
        if (doc == null || doc.getRootTag() != tag) return null;

        PsiClass codeBehind = Fxml2BindingPathResolver.resolveCodeBehindClass(xmlFile);
        if (codeBehind == null) return null;
        if (!codeBehind.isInheritor(tagClass, true)) return null;

        PsiSubstitutor superSubst =
                TypeConversionUtil.getSuperClassSubstitutor(tagClass, codeBehind, PsiSubstitutor.EMPTY);

        PsiTypeParameter[] tagTps = tagClass.getTypeParameters();
        PsiType[] result = new PsiType[tagTps.length];
        for (int i = 0; i < tagTps.length; i++) {
            PsiType binding = superSubst.substitute(tagTps[i]);
            if (binding == null) return null;
            result[i] = binding;
        }
        return validateConcrete(result);
    }

    // -----------------------------------------------------------------------
    // Source 4 - final upper bound
    // -----------------------------------------------------------------------

    private static @Nullable PsiType[] tryFinalUpperBound(@NotNull PsiClass tagClass) {
        PsiTypeParameter[] tps = tagClass.getTypeParameters();
        PsiType[] result = new PsiType[tps.length];
        for (int i = 0; i < tps.length; i++) {
            PsiClassType[] bounds = tps[i].getExtendsList().getReferencedTypes();
            if (bounds.length != 1) return null;
            PsiClassType bound = bounds[0];
            PsiClass boundClass = bound.resolve();
            if (boundClass == null) return null;
            if (!boundClass.hasModifierProperty(PsiModifier.FINAL)) return null;
            result[i] = bound;
        }
        return validateConcrete(result);
    }

    // -----------------------------------------------------------------------
    // Type unification
    // -----------------------------------------------------------------------

    /**
     * Walks {@code tagClass}'s supertype chain to {@code targetType}'s class and
     * extracts a substitution for {@code tagClass}'s own type parameters.  Returns the
     * substitution as a positional array matching {@code tagClass.getTypeParameters()},
     * or {@code null} when {@code tagClass} is not a subtype of {@code targetType}'s
     * class, or when any parameter remains unbound or non-concrete.
     */
    private static @Nullable PsiType[] unifyTagAgainstTargetType(
            @NotNull PsiClass tagClass, @NotNull PsiType targetType) {
        if (!(targetType instanceof PsiClassType targetCt)) return null;
        PsiClass targetClass = targetCt.resolve();
        if (targetClass == null) return null;
        if (!tagClass.equals(targetClass) && !tagClass.isInheritor(targetClass, true)) {
            return null;
        }
        PsiSubstitutor superSubst =
                TypeConversionUtil.getSuperClassSubstitutor(targetClass, tagClass, PsiSubstitutor.EMPTY);
        PsiSubstitutor targetSubst = targetCt.resolveGenerics().getSubstitutor();

        PsiTypeParameter[] tagTps = tagClass.getTypeParameters();
        Set<PsiTypeParameter> tagSet = new LinkedHashSet<>(Arrays.asList(tagTps));

        Map<PsiTypeParameter, PsiType> bindings = new IdentityHashMap<>();
        for (PsiTypeParameter targetTp : targetClass.getTypeParameters()) {
            PsiType expressed = superSubst.substitute(targetTp);
            PsiType concrete = targetSubst.substitute(targetTp);
            if (expressed == null || concrete == null) return null;
            if (!unify(expressed, concrete, bindings, tagSet)) return null;
        }

        return buildResultArray(tagTps, bindings);
    }

    /**
     * Unifies {@code pattern} (which may contain type variables drawn from
     * {@code targetParams}) against {@code concrete}.  Records bindings in
     * {@code bindings}; rejects on conflict.
     */
    private static boolean unify(
            @Nullable PsiType pattern,
            @Nullable PsiType concrete,
            @NotNull Map<PsiTypeParameter, PsiType> bindings,
            @NotNull Set<PsiTypeParameter> targetParams) {
        if (pattern == null || concrete == null) return false;
        if (pattern instanceof PsiClassType patternCt) {
            PsiClass patternClass = patternCt.resolve();
            if (patternClass instanceof PsiTypeParameter tp) {
                if (targetParams.contains(tp)) {
                    PsiType existing = bindings.get(tp);
                    if (existing != null && !typesEqual(existing, concrete)) return false;
                    bindings.put(tp, concrete);
                    return true;
                }
                // Free type variable from an outer scope: require exact equality.
                return typesEqual(pattern, concrete);
            }
            if (patternClass == null) return false;
            if (!(concrete instanceof PsiClassType concreteCt)) return false;
            PsiClass concreteClass = concreteCt.resolve();
            if (concreteClass == null) return false;
            if (!patternClass.equals(concreteClass)
                    && !concreteClass.isInheritor(patternClass, true)) {
                return false;
            }
            PsiSubstitutor superSubst = TypeConversionUtil.getSuperClassSubstitutor(
                    patternClass, concreteClass, concreteCt.resolveGenerics().getSubstitutor());
            PsiSubstitutor patternSubst = patternCt.resolveGenerics().getSubstitutor();
            for (PsiTypeParameter ptp : patternClass.getTypeParameters()) {
                PsiType patArg = patternSubst.substitute(ptp);
                PsiType concArg = superSubst.substitute(ptp);
                if (!unify(patArg, concArg, bindings, targetParams)) return false;
            }
            return true;
        }
        return typesEqual(pattern, concrete);
    }

    private static boolean typesEqual(@Nullable PsiType a, @Nullable PsiType b) {
        if (a == null || b == null) return a == b;
        return a.equals(b);
    }

    // -----------------------------------------------------------------------
    // Property and binding-source type extraction
    // -----------------------------------------------------------------------

    /**
     * Returns the property's value type as visible to FXML assignment (e.g.
     * {@code ObservableList<T>} for {@code ListView<T>.items}), expressed in
     * {@code ownerClass}'s own type-parameter universe, with the owner's
     * {@code fx:typeArguments} substitutor applied so that any of {@code ownerClass}'s
     * type variables are resolved to concrete types when available.
     */
    private static @Nullable PsiType resolvePropertyValueType(
            @NotNull PsiElement propDecl,
            @NotNull PsiClass ownerClass,
            @NotNull XmlTag ownerTag,
            @NotNull XmlFile xmlFile) {
        PsiType raw = readDeclaredAccessorType(
                propDecl, ownerClass, xmlFile.getProject(), resolveScope(xmlFile));
        if (raw == null) return null;

        PsiClass declaringClass = resolveDeclaringClass(propDecl, ownerClass);
        if (declaringClass == null) return null;

        PsiSubstitutor ownerSubst = Fxml2AttributeValueResolver.buildTagTypeSubstitutor(
                ownerClass, ownerTag, xmlFile);
        PsiSubstitutor effective;
        if (declaringClass.equals(ownerClass)) {
            effective = ownerSubst;
        } else {
            effective = TypeConversionUtil.getSuperClassSubstitutor(declaringClass, ownerClass, ownerSubst);
        }
        return effective.substitute(raw);
    }

    /**
     * Returns the declared value type for the {@code propName} property on
     * {@code tagClass}, expressed in {@code tagClass}'s own type-parameter universe.
     * When the property is declared on a supertype (e.g. {@code valueProperty()} on
     * {@code ComboBoxBase<T>}, accessed via {@code ComboBox<T>}), the supertype
     * substitutor is applied so that the returned type references {@code tagClass}'s
     * type parameters rather than the supertype's.  Used to extract the unification
     * pattern for Source 2.
     */
    private static @Nullable PsiType readDeclaredPropertyType(
            @NotNull PsiClass tagClass,
            @NotNull String propName,
            @NotNull Project project,
            @NotNull GlobalSearchScope scope) {
        return resolvePropertyWithSubstitution(
                tagClass, PsiSubstitutor.EMPTY, propName, project, scope);
    }

    private static @Nullable PsiType resolvePropertyWithSubstitution(
            @NotNull PsiClass targetClass,
            @NotNull PsiSubstitutor baseSubst,
            @NotNull String propName,
            @NotNull Project project,
            @NotNull GlobalSearchScope scope) {
        PsiElement decl = Fxml2PropertyResolver.resolveInstanceProperty(targetClass, propName);
        if (decl == null) return null;
        PsiType raw = readDeclaredAccessorType(decl, targetClass, project, scope);
        if (raw == null) return null;
        PsiClass declaring = resolveDeclaringClass(decl, targetClass);
        if (declaring == null) return null;
        PsiSubstitutor subst;
        if (declaring.equals(targetClass)) {
            subst = baseSubst;
        } else {
            subst = TypeConversionUtil.getSuperClassSubstitutor(
                    declaring, targetClass, baseSubst);
        }
        return subst.substitute(raw);
    }

    /**
     * Returns the class on which {@code decl} is declared.  For {@link PsiMember}s this
     * is the standard {@code getContainingClass()}; for the Kotlin-source fallback
     * (a {@code KtProperty}) the corresponding light getter's containing class is used
     * so that supertype substitution works when a Kotlin property is inherited.  Falls
     * back to {@code targetClass} when no light getter can be matched.
     */
    private static @Nullable PsiClass resolveDeclaringClass(
            @NotNull PsiElement decl, @NotNull PsiClass targetClass) {
        if (decl instanceof PsiMember pm) return pm.getContainingClass();
        PsiMethod lightGetter = Fxml2BindingPathResolver.findLightGetterForKtProperty(decl, targetClass);
        if (lightGetter != null) {
            PsiClass declaring = lightGetter.getContainingClass();
            if (declaring != null) return declaring;
        }
        return targetClass;
    }

    /**
     * Extracts the value-side {@link PsiType} from any kind of property accessor:
     * a plain field's type, a getter's return type, a setter's parameter type, or a
     * property-getter's return type unwrapped through {@code ObservableValue<T>}.
     *
     * <p>When {@code decl} is a {@code KtProperty} returned by the Kotlin-source fallback
     * in {@link Fxml2PropertyResolver}, its corresponding light getter on
     * {@code contextClass} carries the {@link PsiType} information and is resolved via
     * {@link Fxml2BindingPathResolver#findLightGetterForKtProperty}.
     */
    private static @Nullable PsiType readDeclaredAccessorType(
            @NotNull PsiElement decl,
            @NotNull PsiClass contextClass,
            @NotNull Project project,
            @NotNull GlobalSearchScope scope) {
        if (decl instanceof PsiField f) {
            PsiType t = f.getType();
            PsiType unwrapped = unwrapObservable(t, project, scope);
            return unwrapped != null ? unwrapped : t;
        }
        PsiMethod method;
        if (decl instanceof PsiMethod m) {
            method = m;
        } else {
            method = Fxml2BindingPathResolver.findLightGetterForKtProperty(decl, contextClass);
            if (method == null) return null;
        }
        String name = method.getName();
        if (Fxml2PropertyNameUtil.isPropertyAccessorName(name) && method.getParameterList().isEmpty()) {
            PsiType ret = method.getReturnType();
            if (ret == null) return null;
            PsiType unwrapped = unwrapObservable(ret, project, scope);
            return unwrapped != null ? unwrapped : ret;
        }
        if (Fxml2PropertyNameUtil.isSetterName(name) && method.getParameterList().getParametersCount() == 1) {
            return method.getParameterList().getParameters()[0].getType();
        }
        PsiType ret = method.getReturnType();
        if (ret == null || PsiTypes.voidType().equals(ret)) return null;
        PsiType unwrapped = unwrapObservable(ret, project, scope);
        return unwrapped != null ? unwrapped : ret;
    }

    /**
     * Resolves a binding expression's source path to a fully-substituted
     * {@link PsiType}, walking dot-separated segments and applying each accessor's
     * substitutor.  Unwraps {@code ObservableValue<T>} between segments but keeps
     * the final segment's type intact for unification.
     *
     * <p>Supports the {@code self/}, {@code parent/}, and {@code this} context
     * selectors that the binding parser already handles.  Returns {@code null} when
     * any segment fails to resolve or the result would be raw.
     */
    private static @Nullable PsiType resolveBindingSourceType(
            @NotNull Fxml2BindingExpressionParser.ParsedExpression expr,
            @NotNull XmlTag contextTag,
            @NotNull XmlFile xmlFile) {
        String fullPath = expr.strippedPath();
        if (fullPath.isBlank()) return null;

        Fxml2BindingExpressionParser.ContextSelector selector =
                Fxml2BindingExpressionParser.parseContextSelector(fullPath);
        String remainingPath = selector != null ? selector.remainingPath() : fullPath;
        PsiClass startClass = Fxml2BindingPathResolver.resolveStartClass(selector, contextTag, xmlFile);
        if (startClass == null) return null;

        Project project = xmlFile.getProject();
        GlobalSearchScope scope = resolveScope(xmlFile);
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        PsiType currentType = factory.createType(startClass);

        if (remainingPath.isBlank()) return currentType;

        // Reject any constructs we don't statically support (function calls, attached
        // property groups, type witnesses, observable selectors).  Source 2 inference
        // is opt-in for simple dotted paths.
        if (remainingPath.contains("(") || remainingPath.contains("<")
                || remainingPath.contains("&lt;") || remainingPath.contains("::")) {
            return null;
        }

        String[] segments = remainingPath.split("\\.");
        for (String segment : segments) {
            String segName = segment.trim();
            if (segName.isEmpty()) return null;
            if (!(currentType instanceof PsiClassType currentCt)) return null;
            PsiClass currentClass = currentCt.resolve();
            if (currentClass == null) return null;

            PsiType nextType = resolvePropertyWithSubstitution(
                    currentClass, currentCt.resolveGenerics().getSubstitutor(), segName, project, scope);
            if (nextType == null) return null;
            currentType = nextType;
        }
        return currentType;
    }

    // -----------------------------------------------------------------------
    // Type helpers
    // -----------------------------------------------------------------------

    /**
     * If {@code type} is a parameterized subtype of {@code ObservableValue<T>}, returns
    * {@code T}; otherwise returns {@code null}.
      */
    private static @Nullable PsiType unwrapObservable(
            @NotNull PsiType type, @NotNull Project project, @NotNull GlobalSearchScope scope) {
        return firstTypeParameterIfSubtypeOf(type, OBSERVABLE_VALUE_FQN, project, scope);
    }

    /**
     * If {@code type} resolves to a {@link java.util.Collection} subtype, returns its
     * element type ({@code E}); otherwise returns {@code null}.
     */
    private static @Nullable PsiType collectionElementType(
            @NotNull PsiType type, @NotNull Project project, @NotNull GlobalSearchScope scope) {
        return firstTypeParameterIfSubtypeOf(type, COLLECTION_FQN, project, scope);
    }

    private static @Nullable PsiType firstTypeParameterIfSubtypeOf(
            @NotNull PsiType type, @NotNull String superFQN,
            @NotNull Project project, @NotNull GlobalSearchScope scope) {
        if (!(type instanceof PsiClassType classType)) return null;
        PsiClass cls = classType.resolve();
        if (cls == null) return null;
        PsiClass superClass = JavaPsiFacade.getInstance(project).findClass(superFQN, scope);
        if (superClass == null) return null;
        if (!cls.equals(superClass) && !cls.isInheritor(superClass, true)) return null;
        PsiSubstitutor superSubst = TypeConversionUtil.getSuperClassSubstitutor(
                superClass, cls, classType.resolveGenerics().getSubstitutor());
        PsiTypeParameter[] tps = superClass.getTypeParameters();
        if (tps.length == 0) return null;
        return superSubst.substitute(tps[0]);
    }

    @SuppressWarnings("unused")
    private static boolean isNotFullyConcrete(@Nullable PsiType type) {
        switch (type) {
            case null: return true;
            case PsiWildcardType w:
                return true;
            case PsiClassType ct:
                if (ct.resolve() instanceof PsiTypeParameter) return true;
                for (PsiType param : ct.getParameters()) {
                    if (isNotFullyConcrete(param)) return true;
                }
                return false;
            default: return false;
        }
    }

    private static @Nullable PsiType[] validateConcrete(@NotNull PsiType[] args) {
        for (PsiType arg : args) {
            if (isNotFullyConcrete(arg)) return null;
        }
        return args;
    }

    private static @Nullable PsiType[] buildResultArray(
            @NotNull PsiTypeParameter[] typeParams,
            @NotNull Map<PsiTypeParameter, PsiType> bindings) {
        PsiType[] result = new PsiType[typeParams.length];
        for (int i = 0; i < typeParams.length; i++) {
            PsiType binding = bindings.get(typeParams[i]);
            if (binding == null) return null;
            result[i] = binding;
        }
        return validateConcrete(result);
    }

    /**
     * Returns {@code true} when {@code type} (recursively) references one of the type
     * parameters in {@code targetParams}.  Used to skip binding attributes whose
     * property type does not involve any of the tag's own type parameters.
     */
    private static boolean containsAnyOf(
            @Nullable PsiType type, @NotNull Set<PsiTypeParameter> targetParams) {
        if (type instanceof PsiClassType ct) {
            if (ct.resolve() instanceof PsiTypeParameter tp && targetParams.contains(tp)) return true;
            for (PsiType param : ct.getParameters()) {
                if (containsAnyOf(param, targetParams)) return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Default-property lookup
    // -----------------------------------------------------------------------

    private static @Nullable String readDefaultPropertyName(@NotNull PsiClass cls) {
        var ann = cls.getAnnotation(DEFAULT_PROPERTY_FQN);
        if (ann == null) {
            // Walk supertypes; @DefaultProperty is inherited in JavaFX usage even when not
            // technically @Inherited, so search the supertype chain.
            for (PsiClass sup : cls.getSupers()) {
                String name = readDefaultPropertyName(sup);
                if (name != null) return name;
            }
            return null;
        }
        var value = ann.findAttributeValue("value");
        if (value instanceof com.intellij.psi.PsiLiteralExpression lit && lit.getValue() instanceof String s) {
            return s;
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------

    /**
     * Renders {@code args} as a comma-separated {@code fx:typeArguments} value and
     * collects any FQNs that need to be imported for the rendered value to resolve.
     *
     * <p>Each type uses its simple name when one of the following holds:
     * <ul>
     *   <li>the file already imports the class (existing {@code <?import?>}), or</li>
     *   <li>the simple name does not bind anywhere else (no conflicting import, no
     *       {@code java.lang} match) - in which case the class's FQN is recorded
     *       as a planned import.</li>
     * </ul>
     * Otherwise the fully-qualified name is emitted (the compiler accepts both
     * forms per {@link Fxml2AttributeValueResolver#buildTagTypeSubstitutor}).
     */
    private static @NotNull InferenceResult renderArgs(
            @NotNull PsiType[] args, @NotNull XmlFile xmlFile) {
        Map<String, String> plannedImports = new HashMap<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(renderType(args[i], xmlFile, plannedImports));
        }
        List<String> ordered = new ArrayList<>(new LinkedHashSet<>(plannedImports.values()));
        return new InferenceResult(sb.toString(), ordered);
    }

    private static @NotNull String renderType(
            @NotNull PsiType type, @NotNull XmlFile xmlFile,
            @NotNull Map<String, String> plannedImports) {
        if (type instanceof PsiClassType ct) {
            PsiClass cls = ct.resolve();
            if (cls != null) {
                String name = nameFor(cls, xmlFile, plannedImports);
                PsiType[] params = ct.getParameters();
                if (params.length == 0) return name;
                StringBuilder sb = new StringBuilder(name).append('<');
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(renderType(params[i], xmlFile, plannedImports));
                }
                sb.append('>');
                return sb.toString();
            }
        }
        return type.getCanonicalText();
    }

    private static @NotNull String nameFor(
            @NotNull PsiClass cls, @NotNull XmlFile xmlFile,
            @NotNull Map<String, String> plannedImports) {
        String simple = cls.getName();
        String fqn = cls.getQualifiedName();
        if (simple == null) return fqn != null ? fqn : "?";
        if (fqn == null || !fqn.contains(".")) return simple;

        PsiClass viaImport = Fxml2ImportResolver.resolve(simple, xmlFile);
        if (viaImport != null) {
            return viaImport.equals(cls) ? simple : fqn;
        }
        String planned = plannedImports.get(simple);
        if (planned != null) {
            return planned.equals(fqn) ? simple : fqn;
        }
        plannedImports.put(simple, fqn);
        return simple;
    }

    private static @NotNull GlobalSearchScope resolveScope(@NotNull XmlFile xmlFile) {
        return Fxml2ImportResolver.compileScope(xmlFile);
    }
}
