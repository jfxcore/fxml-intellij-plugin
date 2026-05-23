package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.XmlSuppressableInspectionTool;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.descriptors.Fxml2ClassTagDescriptor;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;
import org.jfxcore.fxml.resolve.Fxml2PropertyResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates {@code fx:} intrinsic attributes in FXML 2.0 files, mirroring the compiler's
 * {@code IntrinsicsTransform} checks:
 * <ul>
 *   <li>Unknown intrinsic names -> {@code "Unknown intrinsic: X"}</li>
 *   <li>ROOT-placement intrinsics on non-root elements -> {@code "Unexpected intrinsic: X"}</li>
 *   <li>{@code fx:id} value checks: blank, invalid Java identifier, duplicate</li>
 *   <li>{@code fx:typeArguments} + {@code fx:constant} conflict</li>
 *   <li>{@code {fx:Null}} on a primitive-typed property</li>
 * </ul>
 */
public final class Fxml2FxAttributeInspection extends XmlSuppressableInspectionTool {

    @Override
    public @NotNull String getShortName() {
        return "Fxml2FxAttribute";
    }

    /** All known fx: intrinsic names (matching compiler's Intrinsics list). */
    private static final Set<String> KNOWN_INTRINSICS = Set.of(
            "Null", "True", "False", "subclass", "classModifier", "classParameters", "className",
            "context", "id", "value", "constant", "factory", "typeArguments", "itemType",
            "define", "Class", "Evaluate", "Observe", "Push", "Synchronize");

    /**
     * Intrinsics with Placement.ROOT are only allowed on the root element.
     * (Compiler: subclass, classModifier, classParameters, className, context)
     */
    private static final Set<String> ROOT_ONLY_INTRINSICS = Set.of(
            "subclass", "classModifier", "classParameters", "className", "context");

    /** Java identifier pattern: matches the compiler's NameHelper.JAVA_IDENTIFIER. */
    private static final Pattern JAVA_IDENTIFIER =
            Pattern.compile("^(\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)$");

    @Override
    public ProblemDescriptor @Nullable [] checkFile(
            @NotNull PsiFile file, @NotNull InspectionManager manager, boolean isOnTheFly) {
        if (!Fxml2FileType.isFxml2(file)) return null;
        XmlDocument document = ((XmlFile) file).getDocument();
        if (document == null) return null;

        XmlFile xmlFile = (XmlFile) file;
        List<ProblemDescriptor> problems = new ArrayList<>();

        // Collect all fx:id values to detect duplicates
        Set<String> seenIds = new HashSet<>();

        for (XmlTag tag : PsiTreeUtil.findChildrenOfType(document, XmlTag.class)) {
            checkTag(tag, xmlFile, seenIds, problems, manager, isOnTheFly);
        }

        // Check {fx:Class ClassName} inline notation in regular attribute values
        for (XmlAttributeValue attrVal : PsiTreeUtil.findChildrenOfType(document, XmlAttributeValue.class)) {
            checkFxTypeInlineAttribute(attrVal, xmlFile, problems, manager, isOnTheFly);
        }

        return problems.isEmpty() ? null : problems.toArray(ProblemDescriptor.EMPTY_ARRAY);
    }

    private static void checkTag(
            @NotNull XmlTag tag,
            @NotNull XmlFile xmlFile,
            @NotNull Set<String> seenIds,
            @NotNull List<ProblemDescriptor> problems,
            @NotNull InspectionManager manager,
            boolean isOnTheFly) {

        // Skip the synthetic <fxml2:embedded> wrapper root injected for @ComponentView-annotated classes.
        // It carries fx:subclass / xmlns / ... attributes that are valid by construction; processing it
        // would produce false positives (e.g. a spurious "Unexpected intrinsic: class" because the
        // document root is the wrapper itself, making every other element appear non-root).
        if (Fxml2EmbeddedUtil.isWrapperRoot(tag)) return;

        boolean isRootTag = isRootTag(tag, xmlFile);

        // Check for conflicting fx:constant + fx:typeArguments on the same tag
        XmlAttribute constantAttr = null;
        XmlAttribute typeArgumentsAttr = null;
        XmlAttribute factoryAttr = null;

        for (XmlAttribute attr : tag.getAttributes()) {
            // xml:* attributes: only xml:space is recognized; all other xml:* attributes are unknown intrinsics.
            // (The xml prefix is a reserved XML namespace and never a JavaFX property.)
            String attrName = attr.getName();
            if (attrName.startsWith("xml:")) {
                if ("xml:space".equals(attrName)) {
                    String val = attr.getValue();
                    if (val != null && !"preserve".equals(val) && !"default".equals(val)) {
                        XmlAttributeValue valueEl = attr.getValueElement();
                        PsiElement target = valueEl != null ? valueEl : attr;
                        problems.add(manager.createProblemDescriptor(
                                target,
                                "Cannot coerce '" + val + "' to xml:space",
                                (LocalQuickFix) null,
                                ProblemHighlightType.GENERIC_ERROR,
                                isOnTheFly));
                    }
                } else {
                    problems.add(manager.createProblemDescriptor(
                            attr,
                            "Unknown intrinsic: " + attrName,
                            (LocalQuickFix) null,
                            ProblemHighlightType.GENERIC_ERROR,
                            isOnTheFly));
                }
                continue;
            }

            String ns = attr.getNamespace();
            if (!Fxml2ImportResolver.FXML2_NAMESPACE.equals(ns)) continue;

            String localName = attr.getLocalName();

            // 1. Unknown intrinsic
            if (!KNOWN_INTRINSICS.contains(localName)) {
                problems.add(manager.createProblemDescriptor(
                        attr,
                        "Unknown intrinsic: " + localName,
                        (LocalQuickFix) null,
                        ProblemHighlightType.GENERIC_ERROR,
                        isOnTheFly));
                continue;
            }

            // 1a. fx:subclass is explicitly forbidden in embedded FXML 2.0, since the code-behind class is
            //     always derived from the @ComponentView-annotated Java class, never from markup.
            if ("subclass".equals(localName) && Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile)) {
                //noinspection DialogTitleCapitalization
                problems.add(manager.createProblemDescriptor(
                        attr,
                        "fx:subclass is not allowed in embedded FXML 2.0",
                        (LocalQuickFix) null,
                        ProblemHighlightType.GENERIC_ERROR,
                        isOnTheFly));
                continue;
            }

            // 2. ROOT-placement intrinsics on non-root elements
            if (ROOT_ONLY_INTRINSICS.contains(localName) && !isRootTag) {
                problems.add(manager.createProblemDescriptor(
                        attr,
                        "Unexpected intrinsic: " + localName,
                        (LocalQuickFix) null,
                        ProblemHighlightType.GENERIC_ERROR,
                        isOnTheFly));
                continue;
            }

            // 3. fx:id validation
            if ("id".equals(localName)) {
                XmlAttributeValue valueEl = attr.getValueElement();
                String rawValue = attr.getValue();
                if (rawValue == null) rawValue = "";
                String trimmed = rawValue.trim();

                if (trimmed.isEmpty()) {
                    // Blank id -> error on whole attribute
                    //noinspection DialogTitleCapitalization
                    problems.add(manager.createProblemDescriptor(
                            attr,
                            "fx:id cannot be empty",
                            (LocalQuickFix) null,
                            ProblemHighlightType.GENERIC_ERROR,
                            isOnTheFly));
                } else if (!JAVA_IDENTIFIER.matcher(trimmed).matches()) {
                    // Invalid identifier -> error on the value text
                    PsiElement target = valueEl != null ? valueEl : attr;
                    problems.add(manager.createProblemDescriptor(
                            target,
                            "'" + trimmed + "' is not a valid ID",
                            (LocalQuickFix) null,
                            ProblemHighlightType.GENERIC_ERROR,
                            isOnTheFly));
                } else if (!seenIds.add(trimmed)) {
                    // Duplicate -> error on the value text
                    PsiElement target = valueEl != null ? valueEl : attr;
                    problems.add(manager.createProblemDescriptor(
                            target,
                            "Duplicate ID: " + trimmed,
                            (LocalQuickFix) null,
                            ProblemHighlightType.GENERIC_ERROR,
                            isOnTheFly));
                }
                continue;
            }

            // 4. Track fx:constant, fx:typeArguments, and fx:factory for later validation
            if ("constant".equals(localName)) constantAttr = attr;
            if ("typeArguments".equals(localName)) typeArgumentsAttr = attr;
            if ("factory".equals(localName)) factoryAttr = attr;

            // 5. fx:classModifier value validation (only on root, already checked above)
            if ("classModifier".equals(localName) && isRootTag) {
                XmlAttributeValue valueEl = attr.getValueElement();
                String val = attr.getValue();
                if (val != null && !val.isBlank()) {
                    String trimmedVal = val.trim();
                    if (!"protected".equals(trimmedVal) && !"package".equals(trimmedVal)) {
                        PsiElement target = valueEl != null ? valueEl : attr;
                        problems.add(manager.createProblemDescriptor(
                                target,
                                "Invalid class modifier: " + trimmedVal,
                                (LocalQuickFix) null,
                                ProblemHighlightType.GENERIC_ERROR,
                                isOnTheFly));
                    }
                }
            }

            // 6. fx:className requires fx:subclass on same root tag
            if ("className".equals(localName) && isRootTag) {
                if (tag.getAttribute("fx:subclass") == null) {
                    XmlAttributeValue valueEl = attr.getValueElement();
                    PsiElement target = valueEl != null ? valueEl : attr;
                    //noinspection DialogTitleCapitalization
                    problems.add(manager.createProblemDescriptor(
                            target,
                            "fx:className can only be used with fx:subclass",
                            (LocalQuickFix) null,
                            ProblemHighlightType.GENERIC_ERROR,
                            isOnTheFly));
                }
            }
        }

        // 4. Conflict: fx:typeArguments + fx:constant
        if (constantAttr != null && typeArgumentsAttr != null) {
            //noinspection DialogTitleCapitalization
            problems.add(manager.createProblemDescriptor(
                    typeArgumentsAttr,
                    "fx:typeArguments and fx:constant cannot be used at the same time",
                    (LocalQuickFix) null,
                    ProblemHighlightType.GENERIC_ERROR,
                    isOnTheFly));
        }

        // 4a. fx:typeArguments arity and per-argument bound validation.
        //     Arity is checked first; bounds are only checked when the count matches,
        //     matching the compiler's early-exit on NUM_TYPE_ARGUMENTS_MISMATCH.
        if (typeArgumentsAttr != null && constantAttr == null) {
            PsiClass tagClass = tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd
                    ? cd.getPsiClass() : null;
            if (tagClass != null) {
                PsiTypeParameter[] typeParams = tagClass.getTypeParameters();
                int expectedCount = typeParams.length;
                String rawTypeArgs = typeArgumentsAttr.getValue();
                String[] argTokens = rawTypeArgs != null ? rawTypeArgs.split(",", -1) : new String[0];
                int actualCount = 0;
                for (String token : argTokens) {
                    if (!token.isBlank()) actualCount++;
                }
                if (actualCount > 0) {
                    String className = tagClass.getQualifiedName();
                    if (className == null) className = tag.getLocalName();
                    XmlAttributeValue typeArgsValueEl = typeArgumentsAttr.getValueElement();
                    PsiElement typeArgsTarget = typeArgsValueEl != null ? typeArgsValueEl : typeArgumentsAttr;
                    if (expectedCount == 0) {
                        problems.add(manager.createProblemDescriptor(
                                typeArgsTarget,
                                className + " cannot be parameterized",
                                (LocalQuickFix) null,
                                ProblemHighlightType.GENERIC_ERROR,
                                isOnTheFly));
                    } else if (actualCount != expectedCount) {
                        problems.add(manager.createProblemDescriptor(
                                typeArgsTarget,
                                className + ": required " + expectedCount + " type argument(s), but " + actualCount + " were provided",
                                (LocalQuickFix) null,
                                ProblemHighlightType.GENERIC_ERROR,
                                isOnTheFly));
                    } else {
                        // Count matches: validate each argument against its type parameter's bound.
                        checkTypeArgumentBounds(typeArgumentsAttr, rawTypeArgs, argTokens, typeParams,
                                xmlFile, tag, problems, manager, isOnTheFly);
                    }
                }
            }
        }

        // 7. fx:constant: validate that the named field exists as a static field on the tag's class
        if (constantAttr != null && typeArgumentsAttr == null) {
            PsiClass tagClass = tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd
                    ? cd.getPsiClass() : null;
            if (tagClass != null) {
                String fieldName = constantAttr.getValue();
                if (fieldName != null && !fieldName.isBlank()) {
                    com.intellij.psi.PsiField field = tagClass.findFieldByName(fieldName.trim(), true);
                    boolean isStaticField = field != null
                            && field.hasModifierProperty(PsiModifier.STATIC);
                    if (!isStaticField) {
                        XmlAttributeValue valueEl = constantAttr.getValueElement();
                        PsiElement target = valueEl != null ? valueEl : constantAttr;
                        problems.add(manager.createProblemDescriptor(
                                target,
                                "Cannot resolve static field '" + fieldName.trim() + "' in "
                                        + tagClass.getQualifiedName(),
                                (LocalQuickFix) null,
                                ProblemHighlightType.GENERIC_ERROR,
                                isOnTheFly));
                    }
                }
            }
        }

        // 8. fx:factory: validate that the named static method exists on the tag's class
        if (factoryAttr != null) {
            PsiClass tagClass = tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd
                    ? cd.getPsiClass() : null;
            if (tagClass != null) {
                // getValue() returns entity-decoded value; also strip type witness like <String>
                String factoryValue = factoryAttr.getValue();
                if (factoryValue != null && !factoryValue.isBlank()) {
                    String methodName = factoryValue.trim();
                    // Strip type witness: "observableArrayList<String>" -> "observableArrayList"
                    // Also handle XML-entity-encoded form "&lt;" in case getValue() is not fully decoded
                    int angleIdx = methodName.indexOf('<');
                    if (angleIdx < 0) angleIdx = methodName.indexOf('&'); // &lt; form
                    if (angleIdx > 0) methodName = methodName.substring(0, angleIdx).trim();
                    boolean found = false;
                    for (PsiMethod m : tagClass.findMethodsByName(methodName, true)) {
                        if (m.hasModifierProperty(PsiModifier.STATIC)
                                && m.getParameterList().isEmpty()) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        XmlAttributeValue valueEl = factoryAttr.getValueElement();
                        PsiElement target = valueEl != null ? valueEl : factoryAttr;
                        problems.add(manager.createProblemDescriptor(
                                target,
                                "Cannot resolve factory method '" + methodName + "' in "
                                        + tagClass.getQualifiedName(),
                                (LocalQuickFix) null,
                                ProblemHighlightType.GENERIC_ERROR,
                                isOnTheFly));
                    }
                }
            }
        }

        // 9. fx:Class element notation: validate class name on <fx:Class name="X"/>
        if ("Class".equals(tag.getLocalName())
                && Fxml2ImportResolver.FXML2_NAMESPACE.equals(tag.getNamespace())) {
            XmlAttribute nameAttr = tag.getAttribute("name");
            if (nameAttr != null) {
                String typeName = nameAttr.getValue();
                if (typeName != null && !typeName.isBlank()) {
                    PsiClass resolved = Fxml2ImportResolver.resolve(typeName.trim(), xmlFile);
                    if (resolved == null) {
                        resolved = JavaPsiFacade.getInstance(tag.getProject())
                                .findClass(typeName.trim(), tag.getResolveScope());
                    }
                    if (resolved == null) {
                        XmlAttributeValue valueEl = nameAttr.getValueElement();
                        if (valueEl != null) {
                            // Report on the content inside the quotes (range within XmlAttributeValue)
                            // XmlAttributeValue.getText() = "DoesNotExist", range [1, len+1] = inner text
                            int innerLen = typeName.trim().length();
                            TextRange innerRange = TextRange.create(1, 1 + innerLen);
                            problems.add(manager.createProblemDescriptor(
                                    valueEl,
                                    innerRange,
                                    "Cannot resolve symbol '" + typeName.trim() + "'",
                                    ProblemHighlightType.GENERIC_ERROR,
                                    isOnTheFly));
                        } else {
                            problems.add(manager.createProblemDescriptor(
                                    nameAttr,
                                    "Cannot resolve symbol '" + typeName.trim() + "'",
                                    (LocalQuickFix) null,
                                    ProblemHighlightType.GENERIC_ERROR,
                                    isOnTheFly));
                        }
                    }
                }
            }
        }

        // 5. fx:Null on primitive property: check attribute values that contain {fx:Null}
        // 5b. fx:True / fx:False on non-boolean property
        for (XmlAttribute attr : tag.getAttributes()) {
            String ns = attr.getNamespace();
            if (Fxml2ImportResolver.FXML2_NAMESPACE.equals(ns)) continue;
            String attrName = attr.getLocalName();
            if (attrName.startsWith("xmlns")) continue;
            String rawValue = attr.getValue();
            if (rawValue == null) continue;

            if ("{fx:Null}".equals(rawValue)) {
                PsiType propType = resolveInstancePropType(tag, attrName);
                if (propType != null && propType.getDeepComponentType() instanceof com.intellij.psi.PsiPrimitiveType) {
                    XmlAttributeValue valueEl = attr.getValueElement();
                    PsiElement target = valueEl != null ? valueEl : attr;
                    //noinspection DialogTitleCapitalization
                    problems.add(manager.createProblemDescriptor(
                            target,
                            "fx:Null cannot be assigned to a primitive property",
                            (LocalQuickFix) null,
                            ProblemHighlightType.GENERIC_ERROR,
                            isOnTheFly));
                }

            } else if ("{fx:True}".equals(rawValue) || "{fx:False}".equals(rawValue)) {
                String intrinsicName = "{fx:True}".equals(rawValue) ? "fx:True" : "fx:False";

                PsiType propType = resolveInstancePropType(tag, attrName);
                if (propType != null && !isBooleanCompatible(propType)) {
                    XmlAttributeValue valueEl = attr.getValueElement();
                    PsiElement target = valueEl != null ? valueEl : attr;
                    problems.add(manager.createProblemDescriptor(
                            target,
                            intrinsicName + " cannot be assigned to a non-boolean property",
                            (LocalQuickFix) null,
                            ProblemHighlightType.GENERIC_ERROR,
                            isOnTheFly));
                }
            }
        }
    }

    /**
     * Validates each type argument in {@code fx:typeArguments} against its corresponding
     * type parameter's declared bound.  Mirrors the compiler's
     * {@code TypeInvoker.checkProvidedArgument()} logic:
     * <ul>
     *   <li>The relevant bound is the class bound (first non-interface entry in the
     *       extends list), or the first interface bound when no class bound is present.</li>
     *   <li>Assignability is checked at the raw-type (erasure) level to match the
     *       compiler's bytecode-level {@code isAssignableFrom} and to avoid false positives
     *       from parameterized bounds such as {@code Comparable<T>}.</li>
     *   <li>Bounds that reference another type parameter (e.g., {@code V extends K}) are
     *       resolved via a substitutor built from all provided arguments, so that the
     *       effective bound reflects the concrete type supplied for the sibling parameter.</li>
     *   <li>Unresolvable type arguments are silently skipped; the reference contributor
     *       has already reported an "unresolved symbol" error for them.</li>
     * </ul>
     * Errors are reported directly into {@code problems}, with a {@link TextRange} that
     * pinpoints the offending token within the {@code fx:typeArguments} attribute value.
     */
    private static void checkTypeArgumentBounds(
            @NotNull XmlAttribute typeArgumentsAttr,
            @NotNull String rawTypeArgs,
            @NotNull String[] argTokens,
            @NotNull PsiTypeParameter[] typeParams,
            @NotNull XmlFile xmlFile,
            @NotNull XmlTag tag,
            @NotNull List<ProblemDescriptor> problems,
            @NotNull InspectionManager manager,
            boolean isOnTheFly) {

        JavaPsiFacade facade = JavaPsiFacade.getInstance(tag.getProject());
        PsiElementFactory factory = facade.getElementFactory();
        GlobalSearchScope scope = tag.getResolveScope();
        XmlAttributeValue valueEl = typeArgumentsAttr.getValueElement();

        // First pass: resolve each non-blank token to its PsiClass.
        // argClasses[i] maps to typeParams[i]; null when the class cannot be resolved
        // (already reported as an unresolved reference by the reference contributor).
        PsiClass[] argClasses = new PsiClass[typeParams.length];
        {
            int idx = 0;
            for (String token : argTokens) {
                if (idx >= typeParams.length) break;
                String name = token.trim();
                if (!name.isBlank()) {
                    argClasses[idx] = resolveTypeArgClass(name, xmlFile, facade, scope);
                    idx++;
                }
            }
        }

        // Build a substitutor that maps each type parameter to the raw type of its
        // provided argument.  This lets us expand bounds that reference sibling type
        // parameters: for class Foo<K, V extends K> and provided args [Number, Integer],
        // V's bound "K" is substituted to Number before the assignability check.
        PsiSubstitutor substitutor = PsiSubstitutor.EMPTY;
        for (int i = 0; i < typeParams.length; i++) {
            if (argClasses[i] != null) {
                substitutor = substitutor.put(typeParams[i], factory.createType(argClasses[i]));
            }
        }

        // Second pass: check each resolved argument against its parameter's bound.
        // Track the cursor position in rawTypeArgs so we can compute a precise text
        // range for highlighting only the offending token within the attribute value.
        int searchFrom = 0;
        int paramIdx = 0;
        for (String token : argTokens) {
            String name = token.trim();
            int tokenStart = name.isEmpty() ? -1 : rawTypeArgs.indexOf(name, searchFrom);
            searchFrom += token.length() + 1; // advance past "token,"

            if (name.isBlank() || paramIdx >= typeParams.length) {
                if (!name.isBlank()) paramIdx++;
                continue;
            }

            PsiTypeParameter typeParam = typeParams[paramIdx];
            PsiClass argClass = argClasses[paramIdx];
            paramIdx++;

            if (argClass == null) continue; // unresolved; already reported by reference contributor

            PsiClassType[] bounds = typeParam.getExtendsListTypes();
            if (bounds.length == 0) continue; // unbounded: any reference type is valid

            // Select the relevant bound following the compiler's priority rule:
            // class bound (the first non-interface, non-type-parameter entry in the
            // extends list) takes precedence over interface bounds.  In valid Java syntax
            // the class bound, if present, must always appear first.
            PsiClassType relevantBound = bounds[0];
            for (PsiClassType b : bounds) {
                PsiClass bc = b.resolve();
                if (bc != null && !bc.isInterface() && !(bc instanceof PsiTypeParameter)) {
                    relevantBound = b;
                    break;
                }
            }

            // Expand type-parameter references in the bound through the substitutor
            // (e.g., "K" in "V extends K" becomes the concrete type provided for K).
            PsiType substituted = substitutor.substitute(relevantBound);
            if (!(substituted instanceof PsiClassType subCT)) continue;

            PsiClass boundClass = subCT.resolve();
            if (boundClass == null || boundClass instanceof PsiTypeParameter) {
                // Bound still references an unresolved type parameter, skip to avoid false positives.
                continue;
            }

            // Erase both types to their raw class forms before the assignability check.
            // This matches the compiler's bytecode-level TypeInstance.isAssignableFrom
            // semantics and prevents false positives from parameterized bounds
            // (e.g., Comparable<T> is treated as raw Comparable for this check).
            PsiClassType rawBound = factory.createType(boundClass);
            PsiClassType rawArg   = factory.createType(argClass);

            if (!rawBound.isAssignableFrom(rawArg)) {
                String argFqn   = argClass.getQualifiedName()   != null ? argClass.getQualifiedName()   : name;
                String boundFqn = boundClass.getQualifiedName() != null ? boundClass.getQualifiedName() : boundClass.getName();
                String message  = "Type argument " + argFqn + " is not within its bound, should extend " + boundFqn;

                if (valueEl != null && tokenStart >= 0) {
                    // +1 to skip the opening quote of the XmlAttributeValue text
                    TextRange range = TextRange.create(tokenStart + 1, tokenStart + 1 + name.length());
                    problems.add(manager.createProblemDescriptor(
                            valueEl, range, message, ProblemHighlightType.GENERIC_ERROR, isOnTheFly));
                } else {
                    problems.add(manager.createProblemDescriptor(
                            valueEl != null ? valueEl : typeArgumentsAttr,
                            message, (LocalQuickFix) null,
                            ProblemHighlightType.GENERIC_ERROR, isOnTheFly));
                }
            }
        }
    }

    /** Resolves a type argument name to its {@link PsiClass}, trying import resolution first. */
    private static @Nullable PsiClass resolveTypeArgClass(
            @NotNull String name,
            @NotNull XmlFile xmlFile,
            @NotNull JavaPsiFacade facade,
            @NotNull GlobalSearchScope scope) {
        PsiClass c = Fxml2ImportResolver.resolve(name, xmlFile);
        if (c == null) c = facade.findClass(name, scope);
        return c;
    }

    /**
     * Resolves the {@link PsiType} of the instance property {@code attrName} on {@code tag},
     * or returns {@code null} if the owner class or the property cannot be resolved.
     */
    private static @Nullable PsiType resolveInstancePropType(
            @NotNull XmlTag tag, @NotNull String attrName) {
        PsiClass ownerClass = tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd
                ? cd.getPsiClass() : null;
        if (ownerClass == null) return null;
        PsiElement decl = Fxml2PropertyResolver.resolveInstanceProperty(ownerClass, attrName);
        if (decl == null) return null;
        return getPsiType(decl);
    }

    /**
     * Validates {@code {fx:Class ClassName}} or {@code {fx:Class name=ClassName}} inline notation
     * in regular (non-fx:) attribute values.
     */
    private static void checkFxTypeInlineAttribute(
            @NotNull XmlAttributeValue attrVal,
            @NotNull XmlFile xmlFile,
            @NotNull List<ProblemDescriptor> problems,
            @NotNull InspectionManager manager,
            boolean isOnTheFly) {
        if (!(attrVal.getParent() instanceof XmlAttribute attr)) return;
        // Only check non-fx: attributes
        if (attr.getName().startsWith("fx:") || attr.getName().startsWith("xmlns")) return;
        String rawValue = attrVal.getValue();
        if (rawValue.isBlank()) return;
        // Must look like {fx:Class ...}
        String trimmed = rawValue.trim();
        if (!trimmed.startsWith("{fx:Class ") && !trimmed.equals("{fx:Class}")) return;
        if (!trimmed.endsWith("}")) return;

        // Extract the class name argument
        String inner = trimmed.substring("{fx:Class".length(), trimmed.length() - 1).trim();
        if (inner.isBlank()) return;

        // Strip "name=" prefix if present
        String className = inner.startsWith("name=") ? inner.substring("name=".length()).trim() : inner;
        if (className.isBlank()) return;

        // Resolve the class
        PsiClass resolved = Fxml2ImportResolver.resolve(className, xmlFile);
        if (resolved == null) {
            resolved = JavaPsiFacade.getInstance(attrVal.getProject())
                    .findClass(className, attrVal.getResolveScope());
        }
        if (resolved == null) {
            // Find exact offset of className within the attribute value text (inside quotes)
            int classNameStart = rawValue.indexOf(className);
            if (classNameStart < 0) classNameStart = 0;
            int classNameEnd = classNameStart + className.length();
            com.intellij.openapi.util.TextRange range = com.intellij.openapi.util.TextRange.create(
                    classNameStart + 1, classNameEnd + 1); // +1 for opening quote
            problems.add(manager.createProblemDescriptor(
                    attrVal,
                    range,
                    "Cannot resolve symbol '" + className + "'",
                    ProblemHighlightType.GENERIC_ERROR,
                    isOnTheFly));
        }
    }

    /** Returns the PSI type of a resolved property declaration (field, method, or parameter). */
    private static @Nullable PsiType getPsiType(@NotNull PsiElement decl) {
        return switch (decl) {
            case com.intellij.psi.PsiField field -> field.getType();
            case com.intellij.psi.PsiMethod method -> {
                // setter: use first parameter type
                var params = method.getParameterList().getParameters();
                yield params.length == 1 ? params[0].getType() : method.getReturnType();
            }
            case com.intellij.psi.PsiParameter param -> param.getType();
            default -> null;
        };
    }

    /**
     * Returns {@code true} if {@code type} is compatible with a boolean value
     * (i.e. can accept {@code {fx:True}} or {@code {fx:False}}).
     * Allowed types: {@code boolean} primitive, {@code java.lang.Boolean}, {@code java.lang.Object}.
     */
    private static boolean isBooleanCompatible(@NotNull PsiType type) {
        if (com.intellij.psi.PsiTypes.booleanType().equals(type)) return true;
        if (type instanceof com.intellij.psi.PsiClassType ct) {
            com.intellij.psi.PsiClass cls = ct.resolve();
            if (cls == null) return true; // unknown type, be permissive
            String fqn = cls.getQualifiedName();
            return "java.lang.Boolean".equals(fqn) || "java.lang.Object".equals(fqn);
        }
        return false; // other primitive types (int, double, etc.)
    }

    /**
     * Returns {@code true} when {@code tag} is the effective document root element.
     *
     * <p>In standalone FXML2, the effective root is the document's root tag.  In
     * embedded FXML2 the injector wraps the user's markup in a synthetic
     * {@code <fxml2:embedded>} element, making it the XML document root.  The
     * user-written root is therefore the first child of that wrapper, and it must
     * be treated as the effective root for placement checks such as
     * {@code ROOT_ONLY_INTRINSICS}.
     */
    private static boolean isRootTag(@NotNull XmlTag tag, @NotNull XmlFile xmlFile) {
        XmlDocument doc = xmlFile.getDocument();
        if (doc == null) return false;
        XmlTag docRoot = doc.getRootTag();
        if (docRoot == null) return false;
        if (Fxml2EmbeddedUtil.isWrapperRoot(docRoot)) {
            XmlTag[] children = docRoot.getSubTags();
            return children.length > 0 && tag == children[0];
        }
        return tag == docRoot;
    }
}
