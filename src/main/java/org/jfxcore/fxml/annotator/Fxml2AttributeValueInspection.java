package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlText;
import com.intellij.psi.xml.XmlToken;
import com.intellij.psi.xml.XmlTokenType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.codeinsight.Fxml2AddImportFix;
import org.jfxcore.fxml.descriptors.Fxml2ClassTagDescriptor;
import org.jfxcore.fxml.descriptors.Fxml2PropertyTagDescriptor;
import org.jfxcore.fxml.descriptors.Fxml2StaticPropertyAttributeDescriptor;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.resolve.Fxml2AttributeValueResolver;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;
import org.jfxcore.fxml.resolve.Fxml2NamedArgResolver;
import org.jfxcore.fxml.resolve.Fxml2XmlUtil;

import java.util.List;

/**
 * Validates literal attribute values in FXML files (enum constants, color names, etc.),
 * missing {@code @NamedArg} constructor arguments, and collection item-type mismatches.
 */
public final class Fxml2AttributeValueInspection extends LocalInspectionTool {

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new PsiElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (!(element.getContainingFile() instanceof XmlFile xmlFile)) return;
                if (!Fxml2FileType.isFxml2(xmlFile)) return;

                switch (element) {
                    case XmlTag tag -> {
                        checkDuplicateElementProperties(tag, holder);
                        checkNamedArgConstructor(tag, xmlFile, holder);
                        checkCollectionChildTag(tag, xmlFile, holder);
                        checkCollectionTagTextContent(tag, xmlFile, holder);
                        checkNamedArgArrayMultipleValues(tag, xmlFile, holder);
                    }
                    case XmlAttributeValue attrVal -> checkAttributeValue(attrVal, xmlFile, holder);
                    case XmlToken token when token.getTokenType() == XmlTokenType.XML_DATA_CHARACTERS
                            || token.getTokenType() == XmlTokenType.XML_CHAR_ENTITY_REF ->
                            checkElementTextContent(token, xmlFile, holder);
                    default -> {}
                }
            }
        };
    }

    // -----------------------------------------------------------------------
    // Vararg vs. array @NamedArg multiple-value validation
    // -----------------------------------------------------------------------

    /**
     * When a property element tag is used as a {@code @NamedArg} constructor argument in element
     * notation AND the corresponding constructor parameter is a <em>non-vararg</em> array type
     * (e.g. {@code Node[] nodes}), the FXML compiler imposes two constraints:
     * <ol>
     *   <li>More than one class-type child is rejected with
     *       {@code CANNOT_ASSIGN_FUNCTION_ARGUMENT_variadic}.</li>
      *   <li>Exactly one class-type child is accepted <em>only</em> when that child is a
     *       {@code MarkupExtension.Supplier}: a single plain class child is rejected with
     *       {@code CANNOT_ASSIGN_FUNCTION_ARGUMENT_named}.</li>
     * </ol>
     *
     * <p>Vararg parameters (e.g. {@code Node... nodes}) accept any number of children and are left
     * untouched by this check.
     *
     * <p>Mirrors compiler class {@code ValueEmitterFactory}.
     */
    private static void checkNamedArgArrayMultipleValues(@NotNull XmlTag tag,
                                                          @NotNull XmlFile xmlFile,
                                                          @NotNull ProblemsHolder holder) {
        // Only process property-element tags (not class tags, not fx: namespace, not dotted names).
        String localName = tag.getLocalName();
        if (localName.isEmpty()) return;
        if (tag.getNamespace().equals(Fxml2ImportResolver.FXML2_NAMESPACE)) return;
        if (localName.contains(".")) return;
        // Class tags are not property element tags.
        if (Fxml2ImportResolver.resolve(localName, xmlFile) != null) return;

        // The immediate parent must resolve to a Java class.
        XmlTag parentTag = tag.getParentTag();
        if (parentTag == null) return;
        PsiClass parentClass = Fxml2ImportResolver.resolve(parentTag.getLocalName(), xmlFile);
        if (parentClass == null) return;

        // Collect class-type children; text-only children are handled elsewhere.
        java.util.List<XmlTag> classChildren = new java.util.ArrayList<>();
        for (XmlTag child : tag.getSubTags()) {
            if (child.getNamespace().equals(Fxml2ImportResolver.FXML2_NAMESPACE)) continue;
            if (Fxml2ImportResolver.resolve(child.getLocalName(), xmlFile) != null) {
                classChildren.add(child);
            }
        }
        if (classChildren.isEmpty()) return; // no class-type children, nothing to check

        // Look for a @NamedArg constructor parameter with this name.
        for (PsiMethod ctor : parentClass.getConstructors()) {
            if (!ctor.hasModifierProperty(PsiModifier.PUBLIC)) continue;
            if (!Fxml2NamedArgResolver.isFullyAnnotatedNamedArgs(ctor)) continue;
            for (PsiParameter param : ctor.getParameterList().getParameters()) {
                String paramName = Fxml2NamedArgResolver.namedArgValue(param);
                if (!localName.equals(paramName)) continue;
                // Found the matching @NamedArg parameter.
                if (param.isVarArgs()) {
                    // Vararg: any number of children is explicitly supported, no error.
                    return;
                }
                if (!(param.getType() instanceof PsiArrayType)) {
                    // Not an array parameter, handled by other checks.
                    return;
                }
                // Non-vararg array parameter.
                if (classChildren.size() > 1) {
                    // Multiple class-type children are not allowed for a non-vararg array.
                    holder.registerProblem(tag,
                            "Named argument '" + localName + "' cannot be assigned from multiple values");
                } else {
                    // Single class-type child, only allowed when it is a MarkupExtension.Supplier.
                    // A plain (non-Supplier) class child cannot be assigned to an array parameter.
                    XmlTag singleChild = classChildren.getFirst();
                    PsiClass childClass = Fxml2ImportResolver.resolve(singleChild.getLocalName(), xmlFile);
                    if (childClass != null && !isMarkupExtensionSupplier(childClass, xmlFile)) {
                        holder.registerProblem(singleChild,
                                "Named argument '" + localName + "' cannot be assigned from "
                                + childClass.getName());
                    }
                }
                return;
            }
        }
    }

    /**
     * Returns {@code true} when {@code cls} implements {@code MarkupExtension.Supplier},
     * indicating that it can supply a value (possibly of array type) for a property target.
     */
    private static boolean isMarkupExtensionSupplier(@NotNull PsiClass cls, @NotNull XmlFile xmlFile) {
        PsiClass supplierClass = JavaPsiFacade.getInstance(xmlFile.getProject())
                .findClass("org.jfxcore.markup.MarkupExtension.Supplier", xmlFile.getResolveScope());
        return InheritanceUtil.isInheritorOrSelf(cls, supplierClass, true);
    }

    /**
     * Returns {@code true} when {@code type} is a {@code java.lang.Class} type (raw or parameterized),
     * indicating that a plain string attribute value should be treated as a class-literal name.
     */
    private static boolean isClassType(@Nullable PsiType type) {
        if (type == null) return false;
        PsiClass cls = PsiUtil.resolveClassInType(type);
        return cls != null && "java.lang.Class".equals(cls.getQualifiedName());
    }

    // -----------------------------------------------------------------------
    // NamedArg constructor validation
    // -----------------------------------------------------------------------

    /**
     * Checks that, when a tag's class has only {@code @NamedArg} constructors (no default
     * constructor), at least one constructor is fully satisfied by the tag's attributes.
     * Mirrors the compiler's CONSTRUCTOR_NOT_FOUND error.
     */
    private static void checkNamedArgConstructor(@NotNull XmlTag tag,
                                                  @NotNull XmlFile xmlFile,
                                                  @NotNull ProblemsHolder holder) {
        if (tag.getNamespace().equals(Fxml2ImportResolver.FXML2_NAMESPACE)) return;
        if (!(tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd)) return;
        PsiClass psiClass = cd.getPsiClass();
        if (psiClass == null) return;

        // fx:value and fx:constant use valueOf()/static-field construction, not @NamedArg.
        if (tag.getAttribute("fx:value") != null || tag.getAttribute("fx:constant") != null) return;

        // Only apply when the class has NO public no-arg constructor, meaning named-arg
        // construction is required.
        boolean hasDefaultCtor = false;
        boolean hasNamedArgCtor = false;
        for (PsiMethod ctor : psiClass.getConstructors()) {
            if (!ctor.hasModifierProperty(com.intellij.psi.PsiModifier.PUBLIC)) continue;
            if (ctor.getParameterList().getParametersCount() == 0) { hasDefaultCtor = true; break; }
            if (Fxml2NamedArgResolver.isFullyAnnotatedNamedArgs(ctor)) hasNamedArgCtor = true;
        }
        // If there are no constructors at all (e.g. abstract), skip.
        if (psiClass.getConstructors().length == 0) return;
        if (hasDefaultCtor) return;         // default ctor available, no NamedArg requirement
        if (!hasNamedArgCtor) return;       // no NamedArg ctors, different problem

        List<String> provided = new java.util.ArrayList<>(Fxml2NamedArgResolver.collectAttributeNames(tag));
        // Also collect child element names used in NamedArg element-notation
        // (e.g. <arg1><GridPane/></arg1> means "arg1" is provided).
        for (XmlTag child : tag.getSubTags()) {
            String childName = child.getLocalName();
            if (childName.isEmpty()) continue;
            if (child.getNamespace().equals(Fxml2ImportResolver.FXML2_NAMESPACE)) continue;
            // Only include as a NamedArg if the name doesn't resolve to a class
            if (Fxml2ImportResolver.resolve(childName, xmlFile) == null) {
                provided.add(childName);
            }
        }

        // Build the union of all @NamedArg param names across every constructor so we can
        // distinguish constructor args from regular property-setter attributes. Attributes
        // whose names appear in no constructor are property setters and must be ignored when
        // testing whether a constructor is satisfied, otherwise extra setter-only attributes
        // like "code" or "controlDown" would incorrectly disqualify a valid constructor.
        java.util.Set<String> allNamedArgNames = new java.util.HashSet<>();
        for (PsiMethod ctor : psiClass.getConstructors()) {
            if (!ctor.hasModifierProperty(com.intellij.psi.PsiModifier.PUBLIC)) continue;
            if (!Fxml2NamedArgResolver.isFullyAnnotatedNamedArgs(ctor)) continue;
            for (PsiParameter p : ctor.getParameterList().getParameters()) {
                String n = Fxml2NamedArgResolver.namedArgValue(p);
                if (n != null) allNamedArgNames.add(n);
            }
        }
        // Filter provided to only the names that are known constructor params.
        List<String> providedCtorArgs = provided.stream()
                .filter(allNamedArgNames::contains)
                .toList();

        // Check whether any NamedArg constructor is fully satisfied:
        // every provided constructor arg must be a param name, and every required param must be provided.
        for (PsiMethod ctor : psiClass.getConstructors()) {
            if (!ctor.hasModifierProperty(com.intellij.psi.PsiModifier.PUBLIC)) continue;
            if (!Fxml2NamedArgResolver.isFullyAnnotatedNamedArgs(ctor)) continue;

            PsiParameter[] params = ctor.getParameterList().getParameters();
            // Collect param names for quick lookup
            java.util.Set<String> paramNames = new java.util.HashSet<>();
            for (PsiParameter p : params) {
                String n = Fxml2NamedArgResolver.namedArgValue(p);
                if (n != null) paramNames.add(n);
            }
            // Every provided constructor-arg attribute must be a recognized param of this constructor.
            boolean allProvidedValid = paramNames.containsAll(providedCtorArgs);
            if (!allProvidedValid) continue;
            // Every required (non-optional) param must be supplied.
            boolean allRequiredSupplied = true;
            for (PsiParameter p : params) {
                String name = Fxml2NamedArgResolver.namedArgValue(p);
                if (name == null) { allRequiredSupplied = false; break; }
                boolean supplied = providedCtorArgs.contains(name);
                boolean optional  = Fxml2NamedArgResolver.namedArgDefaultValue(p) != null;
                if (!supplied && !optional) { allRequiredSupplied = false; break; }
            }
            if (allRequiredSupplied) return; // found a satisfiable constructor, no error
        }

        holder.registerProblem(tag,
                "No suitable constructor found for " + psiClass.getQualifiedName());
    }

    // -----------------------------------------------------------------------
    // Collection item-type validation
    // -----------------------------------------------------------------------

    /** Returns {@code true} when {@code cls} is NOT a subtype of {@code java.util.Collection}. */
    private static boolean isNotCollectionClass(@NotNull PsiClass cls, @NotNull com.intellij.openapi.project.Project project) {
        PsiClass javaCollection = JavaPsiFacade.getInstance(project)
                .findClass("java.util.Collection", GlobalSearchScope.allScope(project));
        return !InheritanceUtil.isInheritorOrSelf(cls, javaCollection, true);
    }

    /**
     * Resolves the {@code fx:typeArguments} attribute on {@code collectionTag} to a
     * {@link PsiClass}, or returns {@code null} if not present / not resolvable.
     */
    private static @Nullable PsiClass resolveTypeArgument(@NotNull XmlTag collectionTag,
                                                           @NotNull XmlFile xmlFile) {
        XmlAttribute typeArgsAttr = collectionTag.getAttribute("fx:typeArguments");
        if (typeArgsAttr == null) return null;
        String raw = typeArgsAttr.getValue();
        if (raw == null || raw.isBlank()) return null;
        // raw may be a simple name ("Integer"), a qualified name ("java.lang.Double"), or
        // a comma-separated list, so we only handle the single-argument case.
        String typeName = raw.contains(",") ? raw.substring(0, raw.indexOf(',')).trim() : raw.trim();
        // Try resolving via imports first, then as a fully-qualified name.
        PsiClass resolved = Fxml2ImportResolver.resolve(typeName, xmlFile);
        if (resolved != null) return resolved;
        return JavaPsiFacade.getInstance(xmlFile.getProject())
                .findClass(typeName, GlobalSearchScope.allScope(xmlFile.getProject()));
    }

    /**
     * When a class tag is a direct child of a typed collection tag (one that carries
     * {@code fx:typeArguments}), checks that the child's class is assignable to the
     * collection's element type.
     *
     * <p>Only fires when the parent class is a {@code java.util.Collection} subtype.
     * Generic non-collection classes (e.g. {@code Callback} implementations that carry
     * {@code fx:typeArguments} for their own type parameter) are excluded, and their children
     * go to the class's {@code @DefaultProperty} and the type argument is not the item type.
     */
    private static void checkCollectionChildTag(@NotNull XmlTag tag,
                                                 @NotNull XmlFile xmlFile,
                                                 @NotNull ProblemsHolder holder) {
        XmlTag parent = tag.getParentTag();
        if (parent == null) return;
        if (tag.getNamespace().equals(Fxml2ImportResolver.FXML2_NAMESPACE)) return;

        // Only fire for class tags (i.e., the tag resolves to a Java class).
        PsiClass childClass = Fxml2ImportResolver.resolve(tag.getLocalName(), xmlFile);
        if (childClass == null) return;

        // Parent must be a collection class with fx:typeArguments.
        PsiClass typeArg = resolveTypeArgument(parent, xmlFile);
        if (typeArg == null) return;

        // Only apply the element-type check when the parent class is an actual
        // java.util.Collection subtype. For generic non-collection parents the
        // fx:typeArguments parameterises the class itself; children are assigned to the
        // @DefaultProperty and the type argument is NOT the expected child type.
        PsiClass parentClass = Fxml2ImportResolver.resolve(parent.getLocalName(), xmlFile);
        if (parentClass == null) return;
        if (isNotCollectionClass(parentClass, xmlFile.getProject())) return;

        // The child class must be assignable to the type argument.
        if (!InheritanceUtil.isInheritorOrSelf(childClass, typeArg, true)) {
            String collectionName = parent.getLocalName();
            holder.registerProblem(tag,
                    childClass.getName() + " cannot be added to " + collectionName
                    + ", required " + typeArg.getName());
        }
    }

    /**
     * When a typed collection tag (one that carries {@code fx:typeArguments}) contains text
     * content (comma-separated items), checks that the items can be coerced to the element type.
     * Reports on the full {@link XmlText} element to produce a single error covering all tokens.
     *
     * <p>Only fires when the tag's class is a {@code java.util.Collection} subtype (same
     * constraint as {@link #checkCollectionChildTag}).
     */
    private static void checkCollectionTagTextContent(@NotNull XmlTag collectionTag,
                                                       @NotNull XmlFile xmlFile,
                                                       @NotNull ProblemsHolder holder) {
        PsiClass typeArg = resolveTypeArgument(collectionTag, xmlFile);
        if (typeArg == null) return;

        // Only apply to actual collection types, same guard as checkCollectionChildTag.
        PsiClass tagClass = Fxml2ImportResolver.resolve(collectionTag.getLocalName(), xmlFile);
        if (tagClass == null) return;
        if (isNotCollectionClass(tagClass, xmlFile.getProject())) return;

        for (PsiElement child : collectionTag.getChildren()) {
            if (!(child instanceof XmlText xmlText)) continue;
            String trimmed = xmlText.getValue().trim();
            if (trimmed.isBlank()) continue;
            if (Fxml2BindingExpressionParser.looksLikeBindingExpression(trimmed)) continue;

            boolean targetIsString = "java.lang.String".equals(typeArg.getQualifiedName())
                    || "java.lang.Object".equals(typeArg.getQualifiedName())
                    || "java.lang.CharSequence".equals(typeArg.getQualifiedName());
            if (!targetIsString) {
                // Collect all non-whitespace XML_DATA_CHARACTERS tokens.
                XmlToken first = null;
                XmlToken last = null;
                for (PsiElement t : xmlText.getChildren()) {
                    if (t instanceof XmlToken tok
                            && tok.getTokenType() == XmlTokenType.XML_DATA_CHARACTERS
                            && !tok.getText().isBlank()) {
                        if (first == null) first = tok;
                        last = tok;
                    }
                }
                String msg = "String cannot be added to " + collectionTag.getLocalName()
                        + ", required " + typeArg.getName();
                if (first != null && first == last) {
                    // Single non-whitespace token: report directly on it.
                    holder.registerProblem(first, msg);
                } else if (first != null) {
                    // Multiple tokens: report on XmlText with a sub-range from first to last.
                    int startInText = first.getStartOffsetInParent();
                    int endInText = last.getStartOffsetInParent() + last.getTextLength();
                    holder.registerProblem(xmlText,
                            com.intellij.openapi.util.TextRange.create(startInText, endInText), msg);
                }
                break;
            }
        }
    }
    /**
     * Detects element-property tags that duplicate a property already set by an attribute or
     * by an earlier element-property sibling on the same parent tag. Reports on the duplicate
     * child tag with the compiler-matching message "X is set more than once".
     *
     * <p>Attribute-to-attribute duplicates are already caught by IntelliJ's built-in XML validator.
     */
    private static void checkDuplicateElementProperties(@NotNull XmlTag tag,
                                                         @NotNull ProblemsHolder holder) {
        XmlTag parent = tag.getParentTag();
        if (parent == null) return;
        if (!(tag.getContainingFile() instanceof XmlFile xmlFile)) return;

        // Skip class tags (resolvable to a Java class), fx: tags, and dotted names.
        String localName = tag.getLocalName();
        if (localName.isEmpty()) return;
        if (tag.getNamespace().equals(Fxml2ImportResolver.FXML2_NAMESPACE)) return;
        if (localName.contains(".")) return;
        // In FXML/2 (as in FXML), class-element tags always start with an uppercase letter
        // while property-element tags always start with a lowercase letter.  An
        // uppercase-starting tag that cannot be resolved (e.g. because its import is
        // missing) is still a class tag, not a property.  Treating it as a property
        // would produce false "X is set more than once" errors when multiple instances of
        // the same unresolved class appear as siblings inside a container.
        if (Character.isUpperCase(localName.charAt(0))) return;
        if (Fxml2ImportResolver.resolve(localName, xmlFile) != null) return; // it's a class tag

        // Check if the same property is already set by an attribute on the parent tag.
        for (XmlAttribute attr : parent.getAttributes()) {
            String attrName = attr.getName();
            if (Fxml2XmlUtil.isNonPropertyAttribute(attrName)) continue;
            if (attrName.contains(".")) continue;
            if (attrName.equals(localName)) {
                holder.registerProblem(tag, localName + " is set more than once");
                return;
            }
        }

        // Check if an earlier sibling element has already set the same property.
        for (XmlTag sibling : parent.getSubTags()) {
            if (sibling == tag) break;
            String sibName = sibling.getLocalName();
            if (sibName.isEmpty()) continue;
            if (sibling.getNamespace().equals(Fxml2ImportResolver.FXML2_NAMESPACE)) continue;
            if (sibName.contains(".")) continue;
            if (Fxml2ImportResolver.resolve(sibName, xmlFile) != null) continue; // class tag
            if (sibName.equals(localName)) {
                holder.registerProblem(tag, localName + " is set more than once");
                return;
            }
        }
    }

    /** Validates a literal attribute value, e.g. {@code alignment="INVALID"}. */
    private static void checkAttributeValue(@NotNull XmlAttributeValue attrVal,
                                             @NotNull XmlFile xmlFile,
                                             @NotNull ProblemsHolder holder) {
        String rawValue = attrVal.getValue();
        if (rawValue.isBlank()) return;
        // Skip regular binding expressions ($source, ${source}, >{source}, #{source}, {...}).
        if (Fxml2BindingExpressionParser.looksLikeBindingExpression(rawValue)) return;
        // Skip prefix-shorthand invocations (@resource, %key, or custom-prefix values).
        // These are validated by the annotator, not by the value-type checker.
        java.util.Map<Character, String> prefixMappings =
                org.jfxcore.fxml.resolve.Fxml2ImportResolver.parsePrefixMappings(xmlFile);
        if (Fxml2BindingExpressionParser.looksLikeBindingExpression(rawValue, prefixMappings)) return;

        if (!(attrVal.getParent() instanceof XmlAttribute attr)) return;
        String attrName = attr.getName();
        if (Fxml2XmlUtil.isNonPropertyAttribute(attrName)) return;
        if (!(attr.getParent() instanceof XmlTag tag)) return;

        // A plain method name assigned to an EventHandler-typed property is a method reference,
        // not a literal value. Skip it here; the annotator validates method references separately.
        if (!attrName.contains(".") && tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd) {
            PsiClass ownerClass = cd.getPsiClass();
            if (ownerClass != null) {
                java.util.List<String> siblings = Fxml2NamedArgResolver.collectAttributeNames(tag);
                PsiType handlerPropType = Fxml2AttributeValueResolver.propertyType(ownerClass, attrName, siblings);
                if (Fxml2AttributeAnnotator.isEventHandlerType(handlerPropType, xmlFile)) return;
            }
        }

        GlobalSearchScope scope = xmlFile.getResolveScope();

        // Array-typed properties accept comma-separated list values.
        // Collection-typed properties (ObservableList, List, ...) are handled separately.
        if (rawValue.contains(",")) {
            PsiType propType = resolvePropType(attr, attrName, tag);
            if (propType instanceof PsiArrayType) {
                return; // Array properties always accept any value (single or list)
            }
        }

        // Build a type substitutor from fx:typeArguments so that generic properties
        // (e.g. T on FormattedLabel<T>) are validated against the concrete type.
        PsiSubstitutor typeSubstitutor = PsiSubstitutor.EMPTY;
        if (tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd) {
            PsiClass ownerClass = cd.getPsiClass();
            if (ownerClass != null) {
                typeSubstitutor = Fxml2AttributeValueResolver.buildTagTypeSubstitutor(
                        ownerClass, tag, xmlFile);
            }
        }

        Fxml2AttributeValueResolver.Result result =
                resolveAttributeValueWithSubstitutor(attrName, rawValue, tag, scope, typeSubstitutor, xmlFile);
        // result == null means the attribute owner could not be resolved; treat as valid.
        if (result == null || result.valid()) return;

        // Resolution says invalid: compute propType for a meaningful error message.
        PsiType propType = resolvePropType(attr, attrName, tag, typeSubstitutor);
        String simplePropName = attrName.contains(".")
                ? attrName.substring(attrName.lastIndexOf('.') + 1) : attrName;

        // For Class<T> properties, distinguish syntax errors from type-compatibility failures:
        // a comma or angle bracket signals an invalid expression, otherwise use the standard
        // "Cannot coerce" message matching CANNOT_CONVERT_SOURCE_TYPE.
        if (isClassType(propType) && (rawValue.contains(",") || rawValue.contains("<"))) {
            holder.registerProblem(attrVal, "Invalid expression");
            return;
        }

        // For Class<T> properties whose value is an unresolved simple class name, offer an
        // Add Import quick fix when at least one candidate class exists on the classpath.
        // The fix runs Fxml2AddImportFix.findCandidates again at apply time and inserts the
        // <?import?> PI (or shows a popup when multiple candidates exist).
        String coercionMessage =
                Fxml2XmlUtil.buildCoercionErrorMessage(rawValue, simplePropName, propType);
        if (isClassType(propType)) {
            String simpleName = rawValue.trim();
            if (isSimpleClassName(simpleName)
                    && !Fxml2AddImportFix.findCandidates(simpleName, xmlFile, false).isEmpty()) {
                holder.registerProblem(attrVal, coercionMessage,
                        new Fxml2AddImportFix(simpleName, /* checkUnusable= */ false));
                return;
            }
        }
        holder.registerProblem(attrVal, coercionMessage);
    }

    /**
     * Returns {@code true} when {@code value} looks like an unqualified Java class name
     * (a non-empty Java identifier with no dots, commas, or angle brackets) and could be
     * resolved by adding an {@code <?import?>} PI.
     */
    private static boolean isSimpleClassName(@NotNull String value) {
        if (value.isEmpty() || value.contains(".") || value.contains(",") || value.contains("<")) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(value.charAt(0))) return false;
        for (int i = 1; i < value.length(); i++) {
            if (!Character.isJavaIdentifierPart(value.charAt(i))) return false;
        }
        return true;
    }

    /**
     * Like {@link org.jfxcore.fxml.lang.Fxml2ReferenceContributor#resolveAttributeValue} but
     * also accepts a {@link PsiSubstitutor} for type-parameter substitution (e.g. T -> Double
     * when {@code fx:typeArguments="Double"} is present on the tag) and the containing
     * {@link XmlFile} for import-aware {@code Class<T>} literal resolution.
     */
    private static @Nullable Fxml2AttributeValueResolver.Result resolveAttributeValueWithSubstitutor(
            @NotNull String attrName,
            @NotNull String rawValue,
            @NotNull XmlTag tag,
            @NotNull GlobalSearchScope scope,
            @NotNull PsiSubstitutor typeSubstitutor,
            @NotNull XmlFile xmlFile) {

        if (attrName.contains(".")) {
            // Delegate to existing logic for static / chained properties (no type-param substitution there).
            return org.jfxcore.fxml.lang.Fxml2ReferenceContributor.resolveAttributeValue(
                    attrName, rawValue, tag, scope);
        }

        // Plain instance property: use the substitutor-aware and xmlFile-aware overload.
        if (!(tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd)) return null;
        PsiClass ownerClass = cd.getPsiClass();
        if (ownerClass == null) return null;
        List<String> siblingAttrs = Fxml2NamedArgResolver.collectAttributeNames(tag);
        return Fxml2AttributeValueResolver.resolve(
                ownerClass, attrName, rawValue, scope, siblingAttrs, typeSubstitutor, xmlFile);
    }

    /**
     * Resolves the {@link PsiType} of the property named {@code attrName} on the given tag,
     * taking into account static, chained-instance, and plain-instance forms.
     * Returns {@code null} when the type cannot be determined.
     */
    private static @Nullable PsiType resolvePropType(
            @NotNull XmlAttribute attr,
            @NotNull String attrName,
            @NotNull XmlTag tag) {
        return resolvePropType(attr, attrName, tag, PsiSubstitutor.EMPTY);
    }

    /**
     * Like {@link #resolvePropType(XmlAttribute, String, XmlTag)} but also applies
     * {@code typeSubstitutor} to the resolved type (e.g. T -> Double for fx:typeArguments).
     */
    private static @Nullable PsiType resolvePropType(
            @NotNull XmlAttribute attr,
            @NotNull String attrName,
            @NotNull XmlTag tag,
            @NotNull PsiSubstitutor typeSubstitutor) {
        PsiType raw = resolvePropTypeRaw(attr, attrName, tag);
        if (raw == null) return null;
        if (typeSubstitutor.getSubstitutionMap().isEmpty()) return raw;
        PsiType substituted = typeSubstitutor.substitute(raw);
        return substituted != null ? substituted : raw;
    }

    /**
     * Core type resolution without substitution.
     */
    private static @Nullable PsiType resolvePropTypeRaw(
            @NotNull XmlAttribute attr,
            @NotNull String attrName,
            @NotNull XmlTag tag) {
        if (attrName.contains(".")) {
            if (attr.getDescriptor() instanceof Fxml2StaticPropertyAttributeDescriptor sd) {
                PsiClass declaringClass = sd.getDeclaringClass();
                if (declaringClass == null) return null;
                return Fxml2AttributeValueResolver.staticPropertyType(
                        declaringClass, attrName.substring(attrName.lastIndexOf('.') + 1));
            }
            // Chained instance property
            if (!(tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd)) return null;
            PsiClass ownerClass = cd.getPsiClass();
            if (ownerClass == null) return null;
            Object[] chain = Fxml2XmlUtil.resolveChainedPropertyOwner(ownerClass, attrName);
            if (chain == null) return null;
            List<String> siblingAttrs = Fxml2NamedArgResolver.collectAttributeNames(tag);
            return Fxml2AttributeValueResolver.propertyType(
                    (PsiClass) chain[0], (String) chain[1], siblingAttrs);
        }
        if (!(tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd)) return null;
        PsiClass ownerClass = cd.getPsiClass();
        if (ownerClass == null) return null;
        List<String> siblingAttrs = Fxml2NamedArgResolver.collectAttributeNames(tag);
        return Fxml2AttributeValueResolver.propertyType(ownerClass, attrName, siblingAttrs);
    }

    /**
     * Validates text content inside a property element tag, e.g.
     * {@code <selectionModel.selectionMode>INVALID</selectionModel.selectionMode>}.
     */
    private static void checkElementTextContent(@NotNull XmlToken token,
                                                 @NotNull XmlFile xmlFile,
                                                 @NotNull ProblemsHolder holder) {
        if (!(token.getParent() instanceof XmlText xmlText)) return;
        if (!(xmlText.getParent() instanceof XmlTag propTag)) return;

        // Skip class tags, only validate text inside property tags
        if (Fxml2ImportResolver.resolve(propTag.getLocalName(), xmlFile) != null) return;

        String text = token.getText().trim();
        if (text.isBlank()) return;
        // Check both the token text and the full XmlText value: the XML lexer may split
        // "{fx:foo bar}" into multiple tokens; the individual token "bar}" alone doesn't look
        // like a binding expression, but the full content does.
        if (Fxml2BindingExpressionParser.looksLikeBindingExpression(text)) return;
        if (Fxml2BindingExpressionParser.looksLikeBindingExpression(xmlText.getValue().trim())) return;

        GlobalSearchScope scope = xmlFile.getResolveScope();

        // --- Static property element tag (e.g. <VBox.vgrow>FOO</VBox.vgrow>) ---
        if (propTag.getDescriptor() instanceof Fxml2PropertyTagDescriptor ptd && ptd.isStatic()) {
            PsiClass ownerClass = ptd.getOwnerClass();
            if (ownerClass == null) return;
            Fxml2AttributeValueResolver.Result result =
                    Fxml2AttributeValueResolver.resolveStatic(ownerClass, ptd.getPropertyName(), text);
            if (!result.valid()) {
                PsiType propType = Fxml2AttributeValueResolver.staticPropertyType(ownerClass, ptd.getPropertyName());
                holder.registerProblem(token,
                        Fxml2XmlUtil.buildCoercionErrorMessage(text, ptd.getPropertyName(), propType));
            }
            return;
        }

        // --- Instance property element tag (e.g. <selectionModel.selectionMode>INVALID</...>) ---
        XmlTag classTag = Fxml2XmlUtil.findEnclosingClassTag(propTag, xmlFile);
        if (classTag == null) return;
        if (!(classTag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd)) return;
        PsiClass ownerClass = cd.getPsiClass();
        if (ownerClass == null) return;

        String propertyPath = Fxml2XmlUtil.buildPropertyPath(propTag, classTag);
        if (propertyPath == null) return;

        Object[] chain = Fxml2XmlUtil.resolveChainedPropertyOwner(ownerClass, propertyPath);
        if (chain == null) return;
        PsiClass current = (PsiClass) chain[0];
        String lastProp = (String) chain[1];

        Fxml2AttributeValueResolver.Result result =
                Fxml2AttributeValueResolver.resolve(current, lastProp, text, scope);
        if (!result.valid()) {
            PsiType propType = Fxml2AttributeValueResolver.propertyType(current, lastProp, List.of());
            holder.registerProblem(token,
                    Fxml2XmlUtil.buildCoercionErrorMessage(text, lastProp, propType));
        }
    }

}
