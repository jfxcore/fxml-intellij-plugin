package org.jfxcore.fxml.resolve;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.PsiWildcardType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Resolves a literal attribute value string to the PSI element it refers to,
 * following the FXML compiler's type-coercion rules in order:
 * enum constant, Color literal, static field on the declaring class,
 * {@code @NamedArg} constructor, primitives/String.
 */
public final class Fxml2AttributeValueResolver {

    private Fxml2AttributeValueResolver() {}

    /**
     * Result of resolving an attribute value.
     *
     * @param declaration the PSI element the value navigates to, or {@code null} if not navigatable
     *                    but still valid (e.g. primitives, strings)
     * @param valid       {@code true} if the value is valid for the property type
     * @param itemFailure the item that made a value sequence invalid, or {@code null} when the
     *                    value is valid or fails as a whole
     */
    public record Result(@Nullable PsiElement declaration, boolean valid,
                         @Nullable ItemFailure itemFailure) {

        public static final Result INVALID = new Result(null, false);
        public static final Result STRING  = new Result(null, true);

        public Result(@Nullable PsiElement declaration, boolean valid) {
            this(declaration, valid, null);
        }
    }

    /**
     * The item of a value sequence that could not be converted, so that it can be reported on
     * its own rather than on the whole value.
     *
     * @param range        the item's range within the attribute value text
     * @param text         the item text
     * @param requiredType the type the item has to be converted to
     */
    public record ItemFailure(@NotNull com.intellij.openapi.util.TextRange range,
                              @NotNull String text,
                              @NotNull PsiType requiredType) {}

    /**
     * Resolves a literal attribute value against the given property.
     *
     * @param ownerClass   the class that owns the property
     * @param propertyName the property name
     * @param value        the raw attribute value string (unquoted)
     * @param scope        the resolve scope
     */
    public static @NotNull Result resolve(
            @NotNull PsiClass ownerClass,
            @NotNull String propertyName,
            @NotNull String value,
            @NotNull GlobalSearchScope scope) {
        return resolve(ownerClass, propertyName, value, scope, List.of());
    }

    /**
     * Builds a {@link PsiSubstitutor} that maps the type parameters of {@code ownerClass}
     * to the concrete types specified in the {@code fx:typeArguments} attribute of
     * {@code tag}.  Returns {@link PsiSubstitutor#EMPTY} when the attribute is absent or
     * the types cannot be resolved.
     *
     * <p>Example: for {@code <FormattedLabel fx:typeArguments="Double" ...>} where
     * {@code FormattedLabel<T>} has one type parameter, returns a substitutor
     * that maps {@code T} -> {@code java.lang.Double}.
     *
     * @param ownerClass the generic class whose type parameters are to be substituted
     * @param tag        the XML tag that may carry an {@code fx:typeArguments} attribute
     * @param xmlFile    the containing FXML file (for import resolution)
     * @return a substitutor with the concrete mappings, or {@link PsiSubstitutor#EMPTY}
     */
    public static @NotNull PsiSubstitutor buildTagTypeSubstitutor(
            @NotNull PsiClass ownerClass,
            @NotNull XmlTag tag,
            @NotNull com.intellij.psi.xml.XmlFile xmlFile) {

        com.intellij.psi.xml.XmlAttribute typeArgsAttr = tag.getAttribute("fx:typeArguments");
        if (typeArgsAttr == null) return PsiSubstitutor.EMPTY;
        String raw = typeArgsAttr.getValue();
        if (raw == null || raw.isBlank()) return PsiSubstitutor.EMPTY;

        PsiTypeParameter[] typeParams = ownerClass.getTypeParameters();
        if (typeParams.length == 0) return PsiSubstitutor.EMPTY;

        // Top-level split only: an argument may itself be parameterized, e.g.
        // fx:typeArguments="javafx.util.Pair&lt;String, String&gt;" is a single argument.
        List<Fxml2TypeArgumentParser.TypeArg> typeArgs = Fxml2TypeArgumentParser.splitTopLevel(raw);
        PsiSubstitutor sub = PsiSubstitutor.EMPTY;
        JavaPsiFacade facade = JavaPsiFacade.getInstance(ownerClass.getProject());
        GlobalSearchScope resolveScope = Fxml2ImportResolver.compileScope(xmlFile);

        for (int i = 0; i < Math.min(typeParams.length, typeArgs.size()); i++) {
            PsiType argType = resolveTypeArgument(typeArgs.get(i).text(), xmlFile, facade, resolveScope);
            if (argType == null) return PsiSubstitutor.EMPTY; // cannot resolve -> give up
            sub = sub.put(typeParams[i], argType);
        }
        return sub;
    }

    /**
     * Resolves a single (possibly parameterized) type argument such as {@code "String"} or
     * {@code "javafx.util.Pair<String, Integer>"} to a {@link PsiType}.  Nested arguments are
     * applied when they all resolve and their count matches the class's type parameters;
     * otherwise the raw type is returned.  Returns {@code null} when the type name itself
     * cannot be resolved.
     */
    private static @Nullable PsiType resolveTypeArgument(
            @NotNull String text,
            @NotNull com.intellij.psi.xml.XmlFile xmlFile,
            @NotNull JavaPsiFacade facade,
            @NotNull GlobalSearchScope resolveScope) {

        String rawName = Fxml2TypeArgumentParser.rawName(text);
        if (rawName.isBlank()) return null;

        // Try import resolution first (handles simple names and import aliases).
        PsiClass argClass = Fxml2ImportResolver.resolve(rawName, xmlFile);
        if (argClass == null) {
            argClass = facade.findClass(rawName, resolveScope);
        }
        if (argClass == null) return null;

        // The argument's own type-argument list, in literal or escaped form; empty when the
        // argument is not parameterized or its list is unterminated.
        List<Fxml2TypeArgumentParser.TypeArg> nested = Fxml2TypeArgumentParser.nestedArgs(text, 0);
        if (nested.isEmpty() || nested.size() != argClass.getTypeParameters().length) {
            return facade.getElementFactory().createType(argClass);
        }

        PsiType[] nestedTypes = new PsiType[nested.size()];
        for (int i = 0; i < nested.size(); i++) {
            PsiType nestedType = resolveTypeArgument(nested.get(i).text(), xmlFile, facade, resolveScope);
            if (nestedType == null) return facade.getElementFactory().createType(argClass);
            nestedTypes[i] = nestedType;
        }
        return facade.getElementFactory().createType(argClass, nestedTypes);
    }

    /**
     * Attempts to resolve {@code value} as a class-literal name for a {@code Class<T>} property.
     *
     * <p>Mirrors the compiler's coercion: the value is looked up first via the file's
     * {@code <?import?>} directives, then as a fully-qualified name. The resolved class is
     * then checked for assignability to the type argument {@code T} of the target
     * {@code Class<T>} type.
     *
     * <p>Returns the resolved {@link PsiClass} on success, or {@code null} when:
     * <ul>
     *   <li>{@code value} contains {@code ','} or {@code '<'} (syntax error)</li>
     *   <li>the class name cannot be resolved</li>
     *   <li>the resolved class is not assignable to {@code T}</li>
     * </ul>
     *
     * @param value     the raw attribute value (plain class name, e.g. {@code "String"})
     * @param classType the property type, which must be {@code java.lang.Class<T>}
     * @param xmlFile   the containing FXML file (for import resolution)
     * @param scope     the resolve scope
     * @return the resolved {@link PsiClass}, or {@code null}
     */
    public static @Nullable PsiClass resolveClassLiteralRef(
            @NotNull String value,
            @NotNull PsiType classType,
            @NotNull XmlFile xmlFile,
            @NotNull GlobalSearchScope scope) {
        String trimmed = value.trim();
        if (trimmed.contains(",") || trimmed.contains("<")) return null;

        // Resolve via imports first so that simple names (e.g. "String", "Button") work.
        PsiClass resolved = Fxml2ImportResolver.resolve(trimmed, xmlFile);
        if (resolved == null) {
            resolved = JavaPsiFacade.getInstance(xmlFile.getProject()).findClass(trimmed, scope);
        }
        if (resolved == null) return null;

        // Validate against the type argument of Class<T>.
        return isClassLiteralBoundCompatible(resolved, classType) ? resolved : null;
    }

    /**
     * Returns {@code true} when {@code resolved} satisfies the type-argument bound of
     * the given {@code Class<T>} type:
     * <ul>
     *   <li>Raw {@code Class} / unbounded wildcard {@code Class<?>} / {@code Class<? super Foo>}:
     *       any class is accepted.</li>
     *   <li>{@code Class<? extends Foo>}: {@code resolved} must equal or extend {@code Foo}.</li>
     *   <li>{@code Class<Foo>}: {@code resolved} must equal or extend {@code Foo}
     *       (raw forms are also accepted).</li>
     *   <li>{@code Class<T>} with an unresolved type parameter: any class is accepted.</li>
     * </ul>
     *
     * @param resolved  the candidate class
     * @param classType the property type, expected to be {@code java.lang.Class<T>}
     */
    public static boolean isClassLiteralBoundCompatible(
            @NotNull PsiClass resolved, @NotNull PsiType classType) {
        if (!(classType instanceof PsiClassType ct)) return true;
        PsiType[] params = ct.getParameters();
        if (params.length == 0) return true; // raw Class
        PsiType typeArg = params[0];
        if (typeArg instanceof PsiWildcardType wt) {
            if (wt.isExtends() && wt.getBound() != null) {
                PsiClass boundClass = PsiUtil.resolveClassInType(wt.getBound());
                return boundClass == null || resolved.equals(boundClass)
                        || resolved.isInheritor(boundClass, true);
            }
            // Class<?> or Class<? super Foo>: accept any class
        } else if (typeArg instanceof PsiClassType typeArgCt) {
            PsiClass typeArgClass = typeArgCt.resolve();
            if (typeArgClass != null && !(typeArgClass instanceof PsiTypeParameter)) {
                return resolved.equals(typeArgClass) || resolved.isInheritor(typeArgClass, true);
            }
            // Unresolved type parameter: accept any class
        }
        return true;
    }

    /**
     * Like {@link #resolve(PsiClass, String, String, GlobalSearchScope)} but also accepts the
     * sibling attribute names on the same FXML tag, enabling NamedArg-constructor-aware type
     * resolution.
     */
    public static @NotNull Result resolve(
            @NotNull PsiClass ownerClass,
            @NotNull String propertyName,
            @NotNull String value,
            @NotNull GlobalSearchScope scope,
            @NotNull Collection<String> siblingAttributes) {
        return resolve(ownerClass, propertyName, value, scope, siblingAttributes, PsiSubstitutor.EMPTY);
    }

    /**
     * Like {@link #resolve(PsiClass, String, String, GlobalSearchScope, Collection)} but also
     * accepts a {@link PsiSubstitutor} for substituting type parameters (e.g. {@code T -> Double}
     * when {@code fx:typeArguments="Double"} is present on the element tag).
     */
    public static @NotNull Result resolve(
            @NotNull PsiClass ownerClass,
            @NotNull String propertyName,
            @NotNull String value,
            @NotNull GlobalSearchScope scope,
            @NotNull Collection<String> siblingAttributes,
            @NotNull PsiSubstitutor typeSubstitutor) {
        return resolve(ownerClass, propertyName, value, scope, siblingAttributes, typeSubstitutor, null);
    }

    /**
     * Like {@link #resolve(PsiClass, String, String, GlobalSearchScope, Collection, PsiSubstitutor)}
     * but also accepts the containing {@link XmlFile} for import-aware {@code Class<T>} literal
     * resolution. When {@code xmlFile} is {@code null}, the {@code Class<T>} coercion branch
     * performs only syntax validation to avoid false-positive errors.
     */
    public static @NotNull Result resolve(
            @NotNull PsiClass ownerClass,
            @NotNull String propertyName,
            @NotNull String value,
            @NotNull GlobalSearchScope scope,
            @NotNull Collection<String> siblingAttributes,
            @NotNull PsiSubstitutor typeSubstitutor,
            @Nullable XmlFile xmlFile) {
        if (value.isBlank()) {
            return Result.INVALID;
        }
        if (Fxml2BindingExpressionParser.looksLikeBindingExpression(value)) {
            return Result.STRING;
        }

        // The type substitutor (e.g. from fx:typeArguments) is applied here, so that a property
        // of type T is resolved to the concrete type (e.g. Double).
        PsiType propType = substitutedPropertyType(
                ownerClass, propertyName, siblingAttributes, typeSubstitutor);
        if (propType == null) {
            return Result.STRING;
        }

        // Check static fields on the owner class/supertypes AND on the property type class
        // (including its boxed form for primitives).  Mirrors the compiler's newLiteralValue
        // which iterates declaringTypes=[targetType, declaringType] using the *boxed* type -
        // this is how Double.POSITIVE_INFINITY resolves on a double-typed property.
        {
            PsiField field = findStaticFieldCompatibleWith(ownerClass, value.trim(), propType);
            if (field != null) return new Result(field, true);

            // Also search on the property type's own class (e.g. Double for double properties).
            String typeName2 = propType.getCanonicalText();
            String boxedName  = boxedFqn(typeName2);
            String searchName = boxedName != null ? boxedName : typeName2;
            if (!searchName.equals(ownerClass.getQualifiedName())) {
                JavaPsiFacade facade2 = JavaPsiFacade.getInstance(ownerClass.getProject());
                PsiClass propTypeClass = facade2.findClass(searchName, scope);
                if (propTypeClass != null) {
                    PsiField f2 = findStaticFieldCompatibleWith(propTypeClass, value.trim(), propType);
                    if (f2 != null) return new Result(f2, true);
                }
            }
        }

        // Class<T> literal coercion: when the property type is java.lang.Class<T>, a plain
        // string is parsed as a class name and coerced to the corresponding Class literal.
        {
            PsiClass maybeClass = PsiUtil.resolveClassInType(propType);
            if (maybeClass != null && "java.lang.Class".equals(maybeClass.getQualifiedName())) {
                String trimmed = value.trim();
                // Parameterized type expressions (e.g. "Comparable<String>") and
                // comma-separated lists are syntax errors mirroring INVALID_EXPRESSION.
                if (trimmed.contains(",") || trimmed.contains("<")) return Result.INVALID;
                // Without an XmlFile import-aware resolution is impossible; treat as
                // syntactically valid to avoid false "Cannot coerce" errors.
                if (xmlFile == null) return Result.STRING;
                PsiClass resolved = resolveClassLiteralRef(trimmed, propType, xmlFile, scope);
                return resolved != null ? new Result(resolved, true) : Result.INVALID;
            }
        }

        // If the property type is an unresolved type parameter (e.g. T from a generic class
        // instantiated with fx:typeArguments), we cannot determine the concrete type here.
        // Accept any literal value to avoid false "Cannot coerce" errors.
        if (containsUnresolvedTypeParameter(propType)) return Result.STRING;

        String typeName = propType.getCanonicalText();
        if (isPrimitive(typeName)) {
            PsiField specialField = resolveFloatingPointSpecialLiteral(
                    typeName, value.trim(), ownerClass.getProject(), scope);
            if (specialField != null) return new Result(specialField, true);
            return parsePrimitive(typeName, value.trim());
        }
        if ("java.lang.String".equals(typeName) || "String".equals(typeName)) return Result.STRING;

        PsiClass propClass = PsiUtil.resolveClassInType(propType);

        // The value may be a sequence of values, one per collected item or constructor argument.
        // Collected items are resolved here; arguments only after the conversions that take the
        // whole value (a named color, an enum constant) have had their chance.
        List<Fxml2ValueSequenceParser.ValueItem> items = Fxml2ValueSequenceParser.split(
                value, xmlFile != null ? Fxml2ImportResolver.parsePrefixMappings(xmlFile) : Map.of());
        Fxml2ValueTargetResolver.ValueTarget target =
                Fxml2ValueTargetResolver.resolveTarget(propType, items.size(), scope);
        if (target instanceof Fxml2ValueTargetResolver.Items(PsiType collectedType)) {
            return resolveSequenceItems(collectedType, items, ownerClass, scope);
        }

        JavaPsiFacade facade = JavaPsiFacade.getInstance(ownerClass.getProject());

        // Paint / Color coercion, the compiler accepts named colors and web (#rrggbb) strings
        // for any property whose type is Color or a Paint supertype.
        PsiClass paintClass = facade.findClass("javafx.scene.paint.Paint", scope);
        PsiClass colorClass = facade.findClass("javafx.scene.paint.Color", scope);
        boolean isPaintType = (paintClass != null && propClass != null
                && (propClass.equals(paintClass) || propClass.isInheritor(paintClass, true)
                    || paintClass.isInheritor(propClass, true)))
                || (colorClass != null && propClass != null
                    && (propClass.equals(colorClass) || propClass.isInheritor(colorClass, true)));
        if (isPaintType) {
            // Check static Color fields (named colors like "RED", "BLUE"...)
            if (colorClass != null) {
                PsiField colorField = colorClass.findFieldByName(value.trim().toUpperCase(), false);
                if (colorField != null && colorField.hasModifierProperty(PsiModifier.STATIC)) {
                    return new Result(colorField, true);
                }
            }
            if (looksLikeWebColor(value.trim())) {
                return new Result(colorClass != null ? colorClass : propClass, true);
            }
            return Result.INVALID;
        }

        Result r = resolveByType(propClass, value);
        if (r != null) return r;

        if (propClass != null && Fxml2NamedArgResolver.hasNamedArgConstructor(propClass)) {
            return target instanceof Fxml2ValueTargetResolver.Arguments arguments
                    ? resolveArguments(arguments, items, propClass, scope)
                    : Result.INVALID;
        }
        if (propClass != null) {
            String fqn = propClass.getQualifiedName();
            if ("java.lang.String".equals(fqn) || "java.lang.Object".equals(fqn)) {
                return Result.STRING;
            }
        }
        return Result.INVALID;
    }

    /**
     * Returns the type of the named property with {@code typeSubstitutor} applied, so that a
     * property declared as a type parameter resolves to the concrete type supplied by
     * {@code fx:typeArguments}.
     */
    private static @Nullable PsiType substitutedPropertyType(
            @NotNull PsiClass ownerClass,
            @NotNull String propertyName,
            @NotNull Collection<String> siblingAttributes,
            @NotNull PsiSubstitutor typeSubstitutor) {

        PsiType propType = propertyType(ownerClass, propertyName, siblingAttributes);
        if (propType == null || typeSubstitutor.getSubstitutionMap().isEmpty()) return propType;
        PsiType substituted = typeSubstitutor.substitute(propType);
        return substituted != null ? substituted : propType;
    }

    /**
     * Resolves an attribute value that supplies the items of an array or collection target.
     *
     * <p>Every item is converted to {@code itemType} on its own.  A markup extension item is
     * resolved against {@code itemType} and is therefore accepted here.
     *
     * @param itemType the component type of the array, or the element type of the collection,
     *                 or {@code null} when it cannot be resolved and any item is accepted
     */
    private static @NotNull Result resolveSequenceItems(
            @Nullable PsiType itemType,
            @NotNull List<Fxml2ValueSequenceParser.ValueItem> items,
            @NotNull PsiClass ownerClass,
            @NotNull GlobalSearchScope scope) {

        if (itemType == null) return Result.STRING;
        for (Fxml2ValueSequenceParser.ValueItem item : items) {
            Result failure = convertItem(item, itemType, ownerClass, scope);
            if (failure != null) return failure;
        }
        return Result.STRING;
    }

    /**
     * Returns {@code true} when a value of {@code sourceType} can be assigned to a target of
     * {@code targetType}, either directly or through one of the conversions that apply to a
     * value-producing markup extension:
     * <ul>
     *   <li>the target is an array or collection and the value becomes its single item;</li>
     *   <li>the target is implicitly constructed from the value through a single-parameter
     *       {@code @NamedArg} constructor.</li>
     * </ul>
     */
    public static boolean acceptsSourceType(
            @NotNull PsiType targetType,
            @NotNull PsiType sourceType) {

        if (targetType.isAssignableFrom(sourceType)) return true;

        // Single item of an array or collection target.
        PsiType itemType = targetType instanceof com.intellij.psi.PsiArrayType at
                ? at.getComponentType()
                : PsiUtil.extractIterableTypeParameter(targetType, false);
        if (itemType != null
                && (containsUnresolvedTypeParameter(itemType) || itemType.isAssignableFrom(sourceType))) {
            return true;
        }

        // Implicit construction from a single value.
        PsiClass targetClass = PsiUtil.resolveClassInType(targetType);
        if (targetClass == null) return false;
        for (PsiMethod constructor : Fxml2NamedArgResolver.namedArgConstructors(targetClass)) {
            PsiParameter[] params = constructor.getParameterList().getParameters();
            if (params.length == 1 && isTypeCompatible(params[0].getType(), sourceType)) return true;
        }
        return false;
    }

    /**
     * Resolves an attribute value that implicitly constructs an instance of {@code targetClass}
     * from the parameters of one of its {@code @NamedArg} constructors.
     *
     * <p>The value is a sequence of items, one per constructor parameter:
     * {@code padding="10"} selects the single-parameter constructor, {@code padding="10, 20, 10, 20"}
     * the four-parameter one.  Each item is either a literal, which must be convertible to the
     * parameter type, or a markup extension, which is resolved against the parameter type and is
     * therefore accepted here.
     *
     * @return a valid result naming {@code targetClass} when the constructor accepts the sequence,
     *         {@link Result#INVALID} when it does not
     */
    private static @NotNull Result resolveArguments(
            @NotNull Fxml2ValueTargetResolver.Arguments arguments,
            @NotNull List<Fxml2ValueSequenceParser.ValueItem> items,
            @NotNull PsiClass targetClass,
            @NotNull GlobalSearchScope scope) {

        List<PsiParameter> params = arguments.parameters();
        for (int i = 0; i < params.size() && i < items.size(); i++) {
            Result failure = convertItem(items.get(i), params.get(i).getType(), targetClass, scope);
            if (failure != null) return failure;
        }
        return new Result(targetClass, true);
    }

    /**
     * Converts one item of a value sequence to {@code itemType}.
     *
     * @return {@code null} when the item is accepted, otherwise an invalid result that names the
     *         item as the reason
     */
    private static @Nullable Result convertItem(
            @NotNull Fxml2ValueSequenceParser.ValueItem item,
            @NotNull PsiType itemType,
            @NotNull PsiClass contextClass,
            @NotNull GlobalSearchScope scope) {

        // A markup extension item is resolved against the item type and accepted here.
        if (item.isMarkupExtension()) return null;
        if (convertLiteralItem(itemType, item.text(), contextClass, scope).valid()) return null;

        var range = com.intellij.openapi.util.TextRange.from(item.offset(), item.text().length());
        return new Result(null, false, new ItemFailure(range, item.text(), itemType));
    }

    /**
     * Converts a single literal item of a value sequence to {@code targetType}, which is a
     * constructor parameter or a collection element rather than a property.
     *
     * <p>Types whose conversion rules the plugin does not model are accepted, so that an
     * unmodelled conversion is not reported as an error.
     *
     * @param contextClass the class the value is assigned on, used as the resolution context
     * @return a valid result, with the declaration the item navigates to when there is one
     */
    private static @NotNull Result convertLiteralItem(
            @NotNull PsiType targetType,
            @NotNull String literal,
            @NotNull PsiClass contextClass,
            @NotNull GlobalSearchScope scope) {

        if (containsUnresolvedTypeParameter(targetType)) return Result.STRING;

        String typeName = targetType.getCanonicalText();
        if (isPrimitive(typeName)) {
            PsiField specialField = resolveFloatingPointSpecialLiteral(
                    typeName, literal, contextClass.getProject(), scope);
            if (specialField != null) return new Result(specialField, true);
            return parsePrimitive(typeName, literal);
        }
        if ("java.lang.String".equals(typeName)
                || "java.lang.CharSequence".equals(typeName)
                || "java.lang.Object".equals(typeName)) {
            return Result.STRING;
        }

        PsiClass targetClass = PsiUtil.resolveClassInType(targetType);
        if (targetClass == null) return Result.STRING;

        Result byType = resolveByType(targetClass, literal);
        // A null result means the conversion is not modelled by the plugin, which is accepted.
        return byType != null ? byType : Result.STRING;
    }

    /**
     * Returns the value type of a static property: the second parameter type of the static setter
     * {@code static void setName(Node, T)}, or the first parameter if only one exists.
     * Used by {@link org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection} for error messages.
     */
    public static @Nullable PsiType staticPropertyType(
            @NotNull PsiClass declaringClass, @NotNull String propertyName) {
        PsiElement setter = Fxml2PropertyResolver.resolveStaticProperty(declaringClass, propertyName);
        if (!(setter instanceof PsiMethod m)) return null;
        PsiParameter[] params = m.getParameterList().getParameters();
        return params.length == 2 ? params[1].getType()
             : params.length == 1 ? params[0].getType()
             : null;
    }

    /**
     * Resolves a literal value for a <em>static</em> property attribute (e.g. {@code VBox.vgrow="ALWAYS"}).
     * The value type is derived from the second parameter of the static setter
     * ({@code static void setName(Node, T)}).
     */
    public static @NotNull Result resolveStatic(
            @NotNull PsiClass declaringClass,
            @NotNull String propertyName,
            @NotNull String value) {
        if (value.isBlank()) return Result.INVALID;
        if (Fxml2BindingExpressionParser.looksLikeBindingExpression(value)) return Result.STRING;

        PsiType propType = staticPropertyType(declaringClass, propertyName);
        if (propType == null) return Result.STRING;

        // Check static fields on the declaring class first (e.g. USE_PREF_SIZE on Region).
        PsiField field = declaringClass.findFieldByName(value.trim(), true);
        if (field != null && field.hasModifierProperty(PsiModifier.STATIC)
                && field.hasModifierProperty(PsiModifier.PUBLIC)
                && isTypeCompatible(propType, field.getType())) {
            return new Result(field, true);
        }

        String typeName = propType.getCanonicalText();
        if (isPrimitive(typeName)) {
            PsiField specialField = resolveFloatingPointSpecialLiteral(
                    typeName, value.trim(), declaringClass.getProject(), declaringClass.getResolveScope());
            if (specialField != null) return new Result(specialField, true);
            return parsePrimitive(typeName, value.trim());
        }
        if ("java.lang.String".equals(typeName)) return Result.STRING;

        PsiClass propClass = PsiUtil.resolveClassInType(propType);
        Result r = resolveByType(propClass, value);
        return r != null ? r : Result.STRING;
    }

    /**
     * Resolves {@code value} as an enum constant or public static field on {@code propClass}.
     * Returns {@code null} when {@code propClass} is null or no match is found and the caller
     * should continue with its own fallback logic.
     */
    private static @Nullable Result resolveByType(@Nullable PsiClass propClass, @NotNull String value) {
        if (propClass == null) return null;
        if (propClass.isEnum()) {
            PsiField enumConst = propClass.findFieldByName(value.trim(), false);
            if (enumConst != null && enumConst.hasModifierProperty(PsiModifier.STATIC)) {
                return new Result(enumConst, true);
            }
            return Result.INVALID;
        }
        PsiField field = propClass.findFieldByName(value.trim(), true);
        if (field != null && field.hasModifierProperty(PsiModifier.STATIC)
                && field.hasModifierProperty(PsiModifier.PUBLIC)) {
            return new Result(field, true);
        }
        return null;
    }

    /**
     * Extracts the plain value {@link PsiType} from a property declaration element returned by
     * {@link Fxml2PropertyResolver#resolveInstanceProperty}.  Handles:
     * <ul>
     *   <li>{@link PsiMethod} getter: unwraps ObservableValue and returns the value type.</li>
     *   <li>{@link PsiMethod} setter: returns the single parameter type.</li>
     *   <li>{@link PsiParameter} ({@code @NamedArg}): returns the parameter type.</li>
     *   <li>{@link PsiField}: returns the field type.</li>
     * </ul>
     * Returns {@code null} if the type cannot be determined.
     */
    public static @Nullable PsiType resolveDeclarationPsiType(@NotNull PsiElement decl) {
        return switch (decl) {
            case PsiParameter param  -> param.getType();
            case PsiField field      -> field.getType();
            case PsiMethod method    -> {
                // Setter: take the parameter type.
                PsiParameter[] params = method.getParameterList().getParameters();
                if (params.length == 1) yield params[0].getType();
                // Getter/property-getter: take return type and unwrap ObservableValue<T> -> T.
                PsiType ret = method.getReturnType();
                if (ret != null && !ret.equals(PsiTypes.voidType())) {
                    PsiClass owner = method.getContainingClass();
                    if (owner != null) {
                        PsiType unwrapped = unwrapObservableType(ret, owner);
                        yield unwrapped != null ? unwrapped : ret;
                    }
                    yield ret;
                }
                yield null;
            }
            default -> null;
        };
    }

    /**
     * Returns the effective Java type of the named property on {@code ownerClass}, unwrapping
     * observable wrappers where necessary.  Returns {@code null} when the property cannot
     * be resolved or the type is indeterminate.
     *
     * <p>The type is always derived from the <em>setter parameter</em> or <em>getter return
     * type</em>, never from the {@code xProperty()} return type, because JavaFX primitive
     * property specialisations (e.g. {@code DoubleProperty}) do not carry a useful generic
     * type argument that would survive erasure-free unwrapping.  The setter/getter always
     * expose the plain value type (e.g. {@code double}, {@code String}).
     *
     * <p>Handles both JavaFX-property methods and {@code @NamedArg} constructor parameters.
     */
    public static @Nullable PsiType propertyType(@NotNull PsiClass ownerClass, @NotNull String propName,
                                                  @NotNull Collection<String> siblingAttributes) {
        PsiElement decl = Fxml2PropertyResolver.resolveInstanceProperty(ownerClass, propName, siblingAttributes);

        // NamedArg constructor parameter: use the parameter type directly.
        if (decl instanceof PsiParameter param) {
            return param.getType();
        }

        // decl may be a PsiField (backing field with javadoc) or a PsiMethod accessor.
        // Either way, derive the value type from the setter parameter or getter return type,
        // which always carries the plain value type (not an ObservableValue subtype).
        if (decl == null) return null;

        // Resolve all three accessor forms independently so we can always reach the plain value type.
        PsiMethod setter = Fxml2PropertyResolver.findSetterFor(ownerClass, propName);
        PsiMethod getter = Fxml2PropertyResolver.findGetterFor(ownerClass, propName);

        // Prefer setter parameter type (most direct: avoids ObservableValue wrapping entirely).
        if (setter != null) {
            PsiParameter[] params = setter.getParameterList().getParameters();
            if (params.length == 1) return params[0].getType();
        }
        // Fall back to getter return type, unwrapping ObservableValue<T> -> T.
        if (getter != null) {
            PsiType ret = getter.getReturnType();
            if (ret != null && !ret.equals(PsiTypes.voidType())) {
                PsiType unwrapped = unwrapObservableType(ret, ownerClass);
                return unwrapped != null ? unwrapped : ret;
            }
        }
        return null;
    }

    /**
     * If {@code type} is a JavaFX observable property type, returns the plain value type.
     * Handles both the generic case ({@code ObservableValue<T>} -> {@code T}) and
     * the primitive specializations ({@code IntegerProperty} -> {@code int}, etc.)
     * which lose their generic argument at the bytecode level.
     */
    private static @Nullable PsiType unwrapObservableType(@NotNull PsiType type,
                                                           @NotNull PsiClass contextClass) {
        if (!(type instanceof PsiClassType classType)) return null;
        PsiClass cls = classType.resolve();
        if (cls == null) return null;

        // Fast-path: primitive JavaFX property specializations that carry no useful T
        PsiType primitive = primitivePropertyType(cls, contextClass);
        if (primitive != null) return primitive;

        // Generic ObservableValue<T>: walk supertype chain to find T
        JavaPsiFacade facade = JavaPsiFacade.getInstance(contextClass.getProject());
        GlobalSearchScope scope = contextClass.getResolveScope();
        PsiClass observable = facade.findClass("javafx.beans.value.ObservableValue", scope);
        if (observable == null) return null;
        if (!cls.isInheritor(observable, true) && !cls.equals(observable)) return null;

        // Walk the substitutor chain to resolve T
        PsiSubstitutor sub = classType.resolveGenerics().getSubstitutor();
        PsiSubstitutor composed = Fxml2TypeHierarchy.substitutorFor(cls, observable, sub);
        if (composed == null) return null;
        var typeParams = observable.getTypeParameters();
        if (typeParams.length == 0) return null;
        PsiType resolved = composed.substitute(typeParams[0]);
        // Reject unresolved type parameters
        if (resolved instanceof com.intellij.psi.PsiClassType rt && rt.resolve() instanceof com.intellij.psi.PsiTypeParameter) return null;
        return resolved;
    }

    /**
     * If {@code cls} is a JavaFX primitive-typed observable/property type, returns its plain
     * value {@link PsiType}; otherwise returns {@code null}.
     *
     * <p>The numeric types ({@code int}, {@code long}, {@code double}, {@code float}) all inherit
     * from {@code ObservableNumberValue extends ObservableValue<Number>}, so the generic
     * {@code ObservableValue<T>} unwrapper would resolve them to {@code Number} instead of the
     * primitive.  We detect them by checking inheritance from the topmost primitive-specific
     * interfaces ({@code ObservableIntegerValue}, {@code WritableIntegerValue}, etc.), which
     * automatically covers every subclass: {@code *Property}, {@code *PropertyBase},
     * {@code *Wrapper}, {@code *Expression}, and any user-defined subtype.
     *
     * <p>Boolean and String are handled similarly: {@code ObservableBooleanValue} /
     * {@code WritableBooleanValue} for boolean; the generic path already handles String
     * correctly (via {@code ObservableObjectValue<String>}), but we include
     * {@code ObservableStringValue} / {@code WritableStringValue} here for completeness.
     */
    private static @Nullable PsiType primitivePropertyType(@NotNull PsiClass cls,
                                                            @NotNull PsiClass contextClass) {
        GlobalSearchScope scope = contextClass.getResolveScope();
        JavaPsiFacade f = JavaPsiFacade.getInstance(contextClass.getProject());

        // Each entry: check if cls IS or INHERITS FROM the topmost primitive interface.
        // Order matters for the numeric types: check ObservableIntegerValue before
        // ObservableDoubleValue etc. to avoid false positives across the number family.
        record Entry(String iface, PsiType type) {}
        var entries = new Entry[] {
            new Entry("javafx.beans.value.ObservableIntegerValue", PsiTypes.intType()),
            new Entry("javafx.beans.value.WritableIntegerValue",   PsiTypes.intType()),
            new Entry("javafx.beans.value.ObservableLongValue",    PsiTypes.longType()),
            new Entry("javafx.beans.value.WritableLongValue",      PsiTypes.longType()),
            new Entry("javafx.beans.value.ObservableDoubleValue",  PsiTypes.doubleType()),
            new Entry("javafx.beans.value.WritableDoubleValue",    PsiTypes.doubleType()),
            new Entry("javafx.beans.value.ObservableFloatValue",   PsiTypes.floatType()),
            new Entry("javafx.beans.value.WritableFloatValue",     PsiTypes.floatType()),
            new Entry("javafx.beans.value.ObservableBooleanValue", PsiTypes.booleanType()),
            new Entry("javafx.beans.value.WritableBooleanValue",   PsiTypes.booleanType()),
            new Entry("javafx.beans.value.ObservableStringValue",
                      f.getElementFactory().createTypeByFQClassName("java.lang.String", scope)),
            new Entry("javafx.beans.value.WritableStringValue",
                      f.getElementFactory().createTypeByFQClassName("java.lang.String", scope)),
        };

        for (Entry e : entries) {
            PsiClass iface = f.findClass(e.iface(), scope);
            if (iface != null && (cls.equals(iface) || cls.isInheritor(iface, true))) {
                return e.type();
            }
        }
        return null;
    }

    private static boolean isPrimitive(@NotNull String typeName) {
        return switch (typeName) {
            case "boolean", "byte", "short", "int", "long", "float", "double", "char",
                 "java.lang.Boolean", "java.lang.Byte", "java.lang.Short", "java.lang.Integer",
                 "java.lang.Long", "java.lang.Float", "java.lang.Double", "java.lang.Character"
                    -> true;
            default -> false;
        };
    }

    /**
     * Validates that {@code value} can be coerced to the given primitive/wrapper type,
     * mirroring the compiler's {@code ValueEmitterFactory.newLiteralValue} parse logic.
     * Returns {@link Result#STRING} (valid, no navigation target) on success,
     * {@link Result#INVALID} on failure.
     */
    private static @NotNull Result parsePrimitive(@NotNull String typeName, @NotNull String value) {
        try {
            switch (typeName) {
                case "boolean", "java.lang.Boolean" -> {
                    if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
                        return Result.INVALID;
                }
                case "char", "java.lang.Character" -> {
                    if (value.length() != 1) return Result.INVALID;
                }
                case "byte",  "java.lang.Byte"      -> Byte.parseByte(value);
                case "short", "java.lang.Short"     -> Short.parseShort(value);
                case "int",   "java.lang.Integer"   -> Integer.parseInt(value);
                case "long",  "java.lang.Long"      -> Long.parseLong(value);
                case "float", "java.lang.Float"     -> Float.parseFloat(value);
                case "double","java.lang.Double"    -> Double.parseDouble(value);
                default -> { return Result.STRING; }
            }
            return Result.STRING;
        } catch (NumberFormatException e) {
            return Result.INVALID;
        }
    }

    /** Looks up a public static field named {@code name} on {@code cls} (including supertypes)
     *  whose type is compatible with {@code propType}. Returns {@code null} if not found. */
    private static @Nullable PsiField findStaticFieldCompatibleWith(
            @NotNull PsiClass cls, @NotNull String name,
            @NotNull PsiType propType) {
        PsiField field = cls.findFieldByName(name, true);
        if (field != null
                && field.hasModifierProperty(PsiModifier.STATIC)
                && field.hasModifierProperty(PsiModifier.PUBLIC)
                && isTypeCompatible(propType, field.getType())) {
            return field;
        }
        return null;
    }

    /** Returns the FQN of the boxed form of a primitive type name, or {@code null} if not primitive. */
    public static @Nullable String boxedFqn(@NotNull String typeName) {
        return switch (typeName) {
            case "boolean" -> "java.lang.Boolean";
            case "byte"    -> "java.lang.Byte";
            case "short"   -> "java.lang.Short";
            case "int"     -> "java.lang.Integer";
            case "long"    -> "java.lang.Long";
            case "float"   -> "java.lang.Float";
            case "double"  -> "java.lang.Double";
            case "char"    -> "java.lang.Character";
            default        -> null;
        };
    }

    /** Returns true if {@code type} is, or contains, an unresolved type parameter (e.g. {@code T}). */
    public static boolean containsUnresolvedTypeParameter(@NotNull PsiType type) {
        return switch (type) {
            case com.intellij.psi.PsiClassType ct -> {
                if (ct.resolve() instanceof com.intellij.psi.PsiTypeParameter) yield true;
                for (PsiType arg : ct.getParameters()) {
                    if (containsUnresolvedTypeParameter(arg)) yield true;
                }
                yield false;
            }
            case com.intellij.psi.PsiWildcardType wt -> {
                PsiType bound = wt.getBound();
                yield bound != null && containsUnresolvedTypeParameter(bound);
            }
            case com.intellij.psi.PsiArrayType at -> containsUnresolvedTypeParameter(at.getComponentType());
            default -> false;
        };
    }

    private static boolean looksLikeWebColor(@NotNull String value) {
        String v = value.trim();
        if (v.startsWith("#")) return true;
        // Named web colors are looked up case-insensitively by JavaFX
        return com.intellij.xml.util.ColorMap.getHexCodeForColorName(v.toLowerCase()) != null;
    }

    /**
     * Returns {@code true} if {@code fieldType} is assignable to {@code propType}, also
     * treating primitive/boxed equivalents as compatible (e.g. {@code double} <-> {@code Double}).
     * Used to match static-field constants like {@code Region.USE_PREF_SIZE : double}
     * against a primitive-typed property such as {@code prefHeight : double}.
     */
    public static boolean isTypeCompatible(@NotNull PsiType propType, @NotNull PsiType fieldType) {
        if (propType.isAssignableFrom(fieldType)) return true;
        // Also accept boxed<->unboxed equivalence
        String p = propType.getCanonicalText();
        String f = fieldType.getCanonicalText();
        String pb = boxedFqn(p);
        String fb = boxedFqn(f);
        return (pb != null && pb.equals(f)) || (fb != null && fb.equals(p)) || p.equals(f);
    }

    /**
     * Maps the Java floating-point special literal strings {@code "Infinity"} and
     * {@code "-Infinity"} to the corresponding constant fields on {@code Double} or
     * {@code Float}, so that attribute values using these strings can offer navigation
     * to {@code POSITIVE_INFINITY} or {@code NEGATIVE_INFINITY}.
     *
     * <p>{@code "NaN"} is not handled here because it matches the actual field name
     * {@code Double.NaN}/{@code Float.NaN} and is already resolved by the static-field
     * lookup in {@link #resolve}.
     *
     * @return the matching field, or {@code null} if {@code value} is not a special literal
     *         or the class cannot be found
     */
    private static @Nullable PsiField resolveFloatingPointSpecialLiteral(
            @NotNull String typeName, @NotNull String value,
            @NotNull com.intellij.openapi.project.Project project,
            @NotNull GlobalSearchScope scope) {
        String fieldName = switch (value) {
            case "Infinity"  -> "POSITIVE_INFINITY";
            case "-Infinity" -> "NEGATIVE_INFINITY";
            default          -> null;
        };
        if (fieldName == null) return null;
        String className = ("float".equals(typeName) || "java.lang.Float".equals(typeName))
                ? "java.lang.Float" : "java.lang.Double";
        PsiClass cls = JavaPsiFacade.getInstance(project).findClass(className, scope);
        return cls != null ? cls.findFieldByName(fieldName, false) : null;
    }

}
