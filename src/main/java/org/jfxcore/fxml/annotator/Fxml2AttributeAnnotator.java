package org.jfxcore.fxml.annotator;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiInvalidElementAccessException;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlText;
import com.intellij.psi.xml.XmlToken;
import com.intellij.psi.xml.XmlTokenType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.actions.ReplaceObservableSelectorFix;
import org.jfxcore.fxml.codeinsight.Fxml2AddImportFix;
import org.jfxcore.fxml.descriptors.Fxml2ClassTagDescriptor;
import org.jfxcore.fxml.descriptors.Fxml2PropertyAttributeDescriptor;
import org.jfxcore.fxml.descriptors.Fxml2StaticPropertyAttributeDescriptor;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.jfxcore.fxml.lang.Fxml2EventHandlerUtil;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind;

import org.jfxcore.fxml.resolve.Fxml2AttributeValueItems;
import org.jfxcore.fxml.resolve.Fxml2AttributeValueResolver;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2BindingPathResolver;
import org.jfxcore.fxml.resolve.Fxml2ExpressionOperands;
import org.jfxcore.fxml.resolve.Fxml2ExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;
import org.jfxcore.fxml.resolve.Fxml2MarkupExtensionContentParser;
import org.jfxcore.fxml.resolve.Fxml2NamedArgResolver;
import org.jfxcore.fxml.resolve.Fxml2PropertyNameUtil;
import org.jfxcore.fxml.resolve.Fxml2PropertyResolver;
import org.jfxcore.fxml.resolve.Fxml2ValueSequenceParser;
import org.jfxcore.fxml.resolve.Fxml2WellKnownClasses;
import org.jfxcore.fxml.resolve.Fxml2XmlUtil;

import java.util.Set;

/**
 * Annotates invalid attributes and values in FXML files with diagnostics.
 */
public final class Fxml2AttributeAnnotator implements Annotator {

    /** fx: tag local-names (without ns prefix) whose text content is a path expression (not literal). */
    private static final Set<String> FX_PATH_TAGS =
            Set.of("fx:Evaluate", "fx:Observe", "fx:Push", "fx:Synchronize");

    /** Maps fx: binding element local-names (without prefix) to their binding Kind. */
    private static final java.util.Map<String, Kind> FX_ELEMENT_KINDS = java.util.Map.of(
            "Evaluate",    Kind.EVALUATE,
            "Observe",     Kind.OBSERVE,
            "Push",        Kind.PUSH,
            "Synchronize", Kind.SYNCHRONIZE);

    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        // Fast reject: only process the three element types we actually handle.
        // IntelliJ calls annotate() for every PSI node in the file (XmlTag, XmlText,
        // XmlToken for '<', '>', '/', etc.); this guard eliminates ~90% of those calls
        // before any logging or switch overhead.
        if (!(element instanceof XmlAttribute)
                && !(element instanceof XmlAttributeValue)
                && !(element instanceof XmlToken tok
                        && tok.getTokenType() == XmlTokenType.XML_DATA_CHARACTERS)) {
            return;
        }
        annotateElement(element, holder);
    }

    private static void annotateElement(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        switch (element) {
            case XmlAttribute attribute -> {
                if (!(attribute.getContainingFile() instanceof XmlFile xmlFile)) return;
                if (!Fxml2FileType.isFxml2(xmlFile)) return;
                XmlTag tag = attribute.getParent();
                if (tag == null) return;
                // Skip attributes on the synthetic wrapper root added for @ComponentView injection.
                if (Fxml2EmbeddedUtil.isWrapperRoot(tag)) return;
                String attrName = attribute.getName();
                if (Fxml2XmlUtil.isNonPropertyAttribute(attrName)) return;
                int dot = attrName.lastIndexOf('.');
                if (dot > 0) {
                    annotateDottedAttribute(attribute, attrName, tag, holder);
                } else {
                    annotateInstanceAttribute(attribute, attrName, tag, holder);
                }
            }
            case XmlAttributeValue attrVal -> {
                if (!(attrVal.getContainingFile() instanceof XmlFile xmlFile)) return;
                if (!Fxml2FileType.isFxml2(xmlFile)) return;
                annotateBindingPath(attrVal, xmlFile, holder);
            }
            // Text content inside <fx:Evaluate>...</fx:Evaluate> etc. must come from the source= attribute,
            // not from element text: the compiler rejects this with "Invalid expression".
            case XmlToken token when token.getTokenType() == XmlTokenType.XML_DATA_CHARACTERS -> {
                if (!(token.getContainingFile() instanceof XmlFile xmlFile)) return;
                if (!Fxml2FileType.isFxml2(xmlFile)) return;
                if (token.getParent() instanceof XmlText xmlText
                        && xmlText.getParent() instanceof XmlTag parentTag) {
                    String tagName = parentTag.getName();
                    if (FX_PATH_TAGS.contains(tagName)) {
                        String text = token.getText().trim();
                        if (!text.isBlank()) {
                            holder.newAnnotation(HighlightSeverity.ERROR, "Invalid expression")
                                    .range(token)
                                    .create();
                        }
                    }
                }
            }
            default -> {}
        }
    }


    private static void annotateDottedAttribute(
            XmlAttribute attribute, String attrName, XmlTag tag, AnnotationHolder holder) {
        int dot = attrName.indexOf('.');
        String prefix = attrName.substring(0, dot);

        if (!(tag.getContainingFile() instanceof XmlFile xmlFile)) return;

        PsiClass ownerClass = tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd ? cd.getPsiClass() : null;
        var desc = Fxml2ClassTagDescriptor.attributeDescriptorFor(attrName, ownerClass, tag);

        if (desc instanceof Fxml2StaticPropertyAttributeDescriptor sd) {
            // Prefix resolved to an imported class: validate the static property on it.
            if (sd.resolveProperty() == null) {
                int lastDot = attrName.lastIndexOf('.');
                String propName = attrName.substring(lastDot + 1);
                PsiClass declaringClass = sd.getDeclaringClass();
                String classLabel = declaringClass != null ? declaringClass.getQualifiedName() : prefix;
                // Check if the prefix class IS the owning tag's class: qualified own-class notation
                // (e.g. Button.text on a <Button> tag). Compiler error: 'Button.text' in ... cannot be resolved
                if (ownerClass != null && ownerClass.equals(declaringClass)) {
                    holder.newAnnotation(HighlightSeverity.ERROR,
                            "'" + attrName + "' in " + ownerClass.getQualifiedName() + " cannot be resolved")
                            .range(attribute).create();
                } else {
                    // Highlight only the property-name segment (after the last dot), not the
                    // entire "ClassName.propertyName" token, so the error pinpoints what is wrong.
                    int nameStart = attribute.getNameElement().getTextRange().getStartOffset();
                    TextRange propRange = new TextRange(nameStart + lastDot + 1,
                            nameStart + attrName.length());
                    holder.newAnnotation(HighlightSeverity.ERROR,
                            "'" + propName + "' in " + classLabel + " cannot be resolved")
                            .range(propRange).create();
                }
            }
            return;
        }

        if (ownerClass == null) return;
        GlobalSearchScope scope = xmlFile.getResolveScope();
        com.intellij.psi.JavaPsiFacade facade = com.intellij.psi.JavaPsiFacade.getInstance(tag.getProject());

        // Try resolving the prefix as a class (via imports, then full scope, then short-name cache).
        // This handles qualified own-class notation (Button.text) and unimported class references,
        // regardless of whether the prefix starts with upper or lower case.
        PsiClass prefixClass = Fxml2ImportResolver.resolve(prefix, xmlFile);
        if (prefixClass == null) prefixClass = facade.findClass(prefix, scope);
        if (prefixClass == null) {
            com.intellij.psi.search.PsiShortNamesCache cache =
                    com.intellij.psi.search.PsiShortNamesCache.getInstance(tag.getProject());
            PsiClass[] found = cache.getClassesByName(prefix, scope);
            if (found.length > 0) prefixClass = found[0];
        }

        if (prefixClass != null) {
            if (prefixClass.equals(ownerClass)) {
                // Qualified own-class attribute notation: compiler rejects this:
                // "'Button.text' in javafx.scene.control.Button cannot be resolved"
                holder.newAnnotation(HighlightSeverity.ERROR,
                        "'" + attrName + "' in " + ownerClass.getQualifiedName() + " cannot be resolved")
                        .range(attribute).create();
            } else {
                // Prefix class is known but not imported: report missing import.
                // Highlight only the class-name prefix (before the first dot) so the error:
                //   (a) visually appears exactly once (not spanning the whole ClassName.propName token)
                //   (b) does not extend over the property segment, which is not independently erroneous
                int nameStart = attribute.getNameElement().getTextRange().getStartOffset();
                TextRange prefixRange = new TextRange(nameStart, nameStart + dot);
                holder.newAnnotation(HighlightSeverity.ERROR,
                        "Cannot resolve symbol '" + prefix + "'")
                        .range(prefixRange)
                        .withFix(new Fxml2AddImportFix(prefix, /* checkUnusable= */ false))
                        .create();
            }
            return;
        }

        // Prefix does not resolve as a class: treat the whole dotted name as a chained
        // instance property path and walk each segment, reporting on the first unresolvable one.
        // Report when the whole chain cannot be resolved on the owning class.
        String[] parts = attrName.split("\\.", -1);
        PsiClass current = ownerClass;
        for (int i = 0; i < parts.length; i++) {
            PsiElement chainDecl = Fxml2PropertyResolver.resolveInstanceProperty(current, parts[i]);
            if (chainDecl == null) {
                // Report the full dotted name as unresolvable, mirroring the compiler diagnostic
                holder.newAnnotation(HighlightSeverity.ERROR,
                        "'" + attrName + "' in " + ownerClass.getQualifiedName() + " cannot be resolved")
                        .range(attribute).create();
                return;
            }
            if (i < parts.length - 1) {
                current = Fxml2PropertyResolver.resolvePropertyType(chainDecl);
                if (current == null) return; // can't continue chain
            }
        }
    }

    private static void annotateInstanceAttribute(
            XmlAttribute attribute, String attrName, XmlTag tag, AnnotationHolder holder) {
        PsiClass ownerClass = tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd ? cd.getPsiClass() : null;
        if (ownerClass == null) return;

        var desc = Fxml2ClassTagDescriptor.attributeDescriptorFor(attrName, ownerClass, tag);

        // Only instance-property descriptors with a known owner class need validation.
        if (!(desc instanceof Fxml2PropertyAttributeDescriptor)) return;

        java.util.List<String> siblingAttrs = Fxml2NamedArgResolver.collectAttributeNames(tag);
        if (Fxml2PropertyResolver.resolveInstanceProperty(ownerClass, attrName, siblingAttrs) == null) {
            holder.newAnnotation(HighlightSeverity.ERROR,
                    "'" + attrName + "' in " + ownerClass.getQualifiedName() + " cannot be resolved")
                    .range(attribute.getNameElement()).create();
        }
    }

    /**
     * Validates the {@code source} attribute value on an {@code fx:*} binding element in
     * element notation (e.g. {@code <fx:Evaluate source="caption"/>}).
     *
     * <p>The value is a plain binding path (not wrapped in a {@code $}/{@code ${}/etc.
     * expression), so it is resolved directly against the code-behind class.
     */
    private static void annotateElementNotationPath(
            @NotNull XmlAttributeValue attrVal,
            @NotNull String path,
            @NotNull XmlTag fxTag,
            @NotNull XmlFile xmlFile,
            @NotNull AnnotationHolder holder) {

        // Resolve the context tag: the fx:* element is inside a property element, which is
        // inside a class tag. The class tag provides the "self" context for parent/self selectors,
        // but for default context we use the code-behind class (same as attribute notation).
        PsiClass startClass = Fxml2BindingPathResolver.resolveStartClass(null, fxTag, xmlFile);
        if (startClass == null) return;

        // Parse a context selector if present.
        Fxml2BindingExpressionParser.ContextSelector selector =
                Fxml2BindingExpressionParser.parseContextSelector(path);
        String remainingPath = selector != null ? selector.remainingPath() : path;
        if (remainingPath.isBlank()) return;

        if (selector != null) {
            startClass = Fxml2BindingPathResolver.resolveStartClass(selector, fxTag, xmlFile);
            if (startClass == null) return;
        }

        GlobalSearchScope scope = xmlFile.getResolveScope();
        Kind kind = FX_ELEMENT_KINDS.getOrDefault(fxTag.getLocalName(), Kind.EVALUATE);

        // Content semantics are triggered by a ".." prefix
        // in the path value: <fx:Evaluate source="..items"/> means EVALUATE_CONTENT.
        int dotDotOffset = 0;
        if (remainingPath.startsWith("..")) {
            kind = switch (kind) {
                case EVALUATE    -> Kind.EVALUATE_CONTENT;
                case OBSERVE     -> Kind.OBSERVE_CONTENT;
                case PUSH        -> Kind.PUSH_CONTENT;
                case SYNCHRONIZE -> Kind.SYNCHRONIZE_CONTENT;
                default -> kind;
            };
            dotDotOffset = 2;
            remainingPath = remainingPath.substring(2).trim();
            if (remainingPath.isBlank()) return;
        }

        int selectorOffset = selector != null ? selector.selectorLength() : 0;
        // Base offset within the attribute value text (past the opening quote, selector, and "..").
        int base = 1 + selectorOffset + dotDotOffset;

        // Function-call syntax: a '(' that is not immediately preceded by '.'
        // (a '.' before '(' marks an attached-property group, not a function call).
        int parenIdx = Fxml2BindingPathResolver.functionCallParenIndex(remainingPath);
        if (parenIdx >= 0) {
            // fx:Push (reverse binding) is not applicable to function expressions; the compiler
            // fails on this alone, so report only that and skip detailed function validation.
            if (kind == Kind.PUSH || kind == Kind.PUSH_CONTENT) {
                annotateFunctionNotReverseBindable(attrVal, remainingPath, holder, base);
                return;
            }
            annotateFunctionCall(attrVal, remainingPath, startClass, fxTag, xmlFile, holder, base, kind);
            // fx:Synchronize: single-argument requirement and an available inverse method
            // (unless an explicit inverseMethod= attribute is present on the element).
            if (kind == Kind.SYNCHRONIZE || kind == Kind.SYNCHRONIZE_CONTENT) {
                annotateBidirectionalArgumentCount(attrVal, remainingPath, holder, base);
                if (fxTag.getAttribute("inverseMethod") == null) {
                    String funcPath = remainingPath.substring(0, parenIdx);
                    annotateInverseMethodRequired(attrVal, funcPath, startClass, xmlFile, holder, base);
                }
            }
            return;
        }

        var segments = Fxml2BindingPathResolver.resolve(remainingPath, startClass, scope, kind, xmlFile);
        reportPathSegments(attrVal, segments, startClass, base, xmlFile, holder);
    }

    /**
     * Validates an event-handler method reference: a plain method name assigned to an
     * {@code EventHandler}-typed property (e.g. {@code onAction="handleAction"}).
     *
     * <p>Reports two distinct error cases, matching the FXML compiler:
     * <ol>
     *   <li>No method with the given name exists on the code-behind class.</li>
     *   <li>A method with the given name exists but no overload has a compatible
     *       signature (must return {@code void} and accept 0 or 1 parameter of the
     *       event type).</li>
     * </ol>
     *
     * <p>If there is no code-behind class the reference is silently accepted.
     */
    private static void annotateMethodHandlerRef(
            @NotNull XmlAttributeValue attrVal,
            @NotNull String methodName,
            @NotNull PsiType eventHandlerType,
            @NotNull XmlFile xmlFile,
            @NotNull AnnotationHolder holder) {
        PsiClass codeBehind = Fxml2BindingPathResolver.resolveCodeBehindClass(xmlFile);
        if (codeBehind == null) return;

        // Offset within the document: +1 to skip the opening quote character
        int nameStart = attrVal.getTextRange().getStartOffset() + 1;
        int nameEnd = nameStart + methodName.length();

        PsiMethod[] methods = codeBehind.findMethodsByName(methodName, true);
        if (methods.length == 0) {
            String ownerName = codeBehind.getQualifiedName();
            holder.newAnnotation(HighlightSeverity.ERROR,
                    "'" + methodName + "' in " + ownerName + " cannot be resolved")
                    .range(new TextRange(nameStart, nameEnd))
                    .create();
            return;
        }

        // Check that at least one overload has a compatible signature
        PsiClass eventType = Fxml2EventHandlerUtil.extractEventTypeClass(eventHandlerType);
        boolean hasCompatible = java.util.Arrays.stream(methods)
                .anyMatch(m -> Fxml2EventHandlerUtil.isCompatibleHandlerMethod(m, eventType));
        if (hasCompatible) return;

        String eventTypeName = eventType != null ? eventType.getName() : "Event";
        holder.newAnnotation(HighlightSeverity.ERROR,
                "'" + methodName + "' does not match the signature of an event handler for "
                + eventTypeName)
                .range(new TextRange(nameStart, nameEnd))
                .create();
    }

    /**
     * Returns {@code true} when the type of the given PSI element (field or method return type)
     * is a subtype of {@code javafx.beans.value.ObservableValue}.
     *
     * <p>Used to validate that members accessed via the {@code ::} (observable-selection)
     * operator in a binding path are actually observable, mirroring the FXML compiler's
     * {@code INVALID_INVARIANT_REFERENCE} check.
     */
    private static boolean isNotObservableDeclaration(@Nullable PsiElement decl, @NotNull XmlFile xmlFile) {
        if (decl == null || !decl.isValid()) return true;
        PsiClass observableClass = Fxml2WellKnownClasses.observableValue(xmlFile.getProject());
        if (observableClass == null) return true;

        PsiType type = switch (decl) {
            case com.intellij.psi.PsiField  f -> f.getType();
            case PsiMethod m -> m.getReturnType();
            default -> null;
        };
        if (!(type instanceof com.intellij.psi.PsiClassType ct)) return true;
        PsiClass resolved;
        try {
            resolved = ct.resolve();
        } catch (PsiInvalidElementAccessException ignored) {
            return true;
        }
        return resolved == null
                || (!resolved.equals(observableClass) && !resolved.isInheritor(observableClass, true));
    }

    /**
     * Returns {@code true} when {@code type} is {@code javafx.event.EventHandler} or a
     * parameterized form of it (e.g. {@code EventHandler<ActionEvent>}).
     *
     * <p>Delegates to {@link Fxml2EventHandlerUtil#isEventHandlerType} which is the
     * shared implementation used across reference providers and searchers.
     */
    static boolean isEventHandlerType(@Nullable PsiType type, @NotNull XmlFile xmlFile) {
        return Fxml2EventHandlerUtil.isEventHandlerType(type, xmlFile.getProject());
    }

    /**
     * Reports "cannot be resolved" (and {@code ::} observable-selector) errors for a contiguous
     * sequence of resolved/unresolved binding path {@link Fxml2BindingPathResolver.Segment}s,
     * walking a {@code prevType} chain (starting at {@code startClass}) so each unresolved segment
     * is reported against its owner type.  Only the first unresolved segment in the chain is
     * reported.  Shared by attribute-notation, element-notation, function-name, function-argument,
     * and secondary-parameter path validation.
     *
     * @param attrTextBase offset within the attribute value text (1 = first char after the opening
     *                     quote) of the first character of the segments' path
     * @return {@code true} if every segment resolved without error
     */
    private static boolean reportPathSegments(
            @NotNull XmlAttributeValue attrVal,
            @NotNull java.util.List<Fxml2BindingPathResolver.Segment> segments,
            @NotNull PsiClass startClass,
            int attrTextBase,
            @NotNull XmlFile xmlFile,
            @NotNull AnnotationHolder holder) {
        int attrDocBase = attrVal.getTextRange().getStartOffset();
        PsiClass prevType = startClass;
        boolean prevResolved = true;
        boolean allResolved = true;

        for (Fxml2BindingPathResolver.Segment seg : segments) {
            int docStart = attrDocBase + attrTextBase + seg.pathOffset();
            int docEnd = docStart + seg.name().length();
            if (!seg.isResolved()) {
                if (prevResolved) {
                    // For attached-property segments like (VBox.prop), extract the declaring
                    // class name from inside the parens for a clearer error message.
                    String ownerName;
                    String segName = seg.name();
                    if (segName.startsWith("(") && segName.endsWith(")")) {
                        String inner = segName.substring(1, segName.length() - 1);
                        int lastDot = inner.lastIndexOf('.');
                        ownerName = lastDot > 0 ? inner.substring(0, lastDot) : inner;
                    } else {
                        ownerName = prevType != null ? prevType.getQualifiedName() : "?";
                    }
                    var builder = holder.newAnnotation(HighlightSeverity.ERROR,
                            "'" + segName + "' in " + ownerName + " cannot be resolved")
                            .range(new TextRange(docStart, docEnd));
                    Fxml2AddImportFix importFix = addImportFixForClassSegment(segName, xmlFile);
                    if (importFix != null) builder = builder.withFix(importFix);
                    builder.create();
                }
                prevResolved = false;
                prevType = null;
                allResolved = false;
            } else {
                // When the segment was accessed via '::' (observable-selection operator), the FXML
                // compiler requires the member to be an ObservableValue subtype. If it is not,
                // the compiler rejects the reference with INVALID_INVARIANT_REFERENCE.
                if (seg.observableSelector() && !seg.classQualifier()
                        && isNotObservableDeclaration(seg.declaration(), xmlFile)) {
                    String segName = seg.name();
                    String ownerName = prevType != null ? prevType.getQualifiedName() : "?";
                    int selectorDocOffset = ReplaceObservableSelectorFix.selectorOffsetBefore(docStart);
                    ReplaceObservableSelectorFix fix =
                            ReplaceObservableSelectorFix.of(attrVal, selectorDocOffset);
                    var builder = holder.newAnnotation(HighlightSeverity.ERROR,
                            "'" + segName + "' in " + ownerName
                            + " cannot be referenced"
                            + " (note: '.' can be used instead of '::' within a path expression)")
                            .range(new TextRange(docStart, docEnd));
                    if (fix != null) builder = builder.withFix(fix);
                    builder.create();
                    allResolved = false;
                }
                prevResolved = true;
                prevType = seg.resultType();
            }
        }
        return allResolved;
    }

    /**
     * Returns an "Add import for '{@code segName}'" quick fix when an unresolved binding-path
     * segment denotes a class reference whose import is missing: the function-name (constructor
     * or static-method qualifier) of a function expression such as {@code ${Color(...)}}, or a
     * static-field class qualifier such as {@code Foo} in {@code ${Foo.BAR}}.
     *
     * <p>Returns {@code null} unless {@code segName} is a simple (un-dotted) identifier starting
     * with an uppercase letter and at least one importable candidate class exists; this keeps the
     * fix from appearing on ordinary unresolved instance-property segments, which are lowercase.
     */
    private static @org.jetbrains.annotations.Nullable Fxml2AddImportFix addImportFixForClassSegment(
            @NotNull String segName, @NotNull XmlFile xmlFile) {
        if (segName.isEmpty() || segName.indexOf('.') >= 0) return null;
        if (!Character.isJavaIdentifierStart(segName.charAt(0)) || !Character.isUpperCase(segName.charAt(0))) {
            return null;
        }
        for (int i = 1; i < segName.length(); i++) {
            if (!Character.isJavaIdentifierPart(segName.charAt(i))) return null;
        }
        // checkUnusable=false: the class is referenced as a constructor/static qualifier, not a
        // directly-instantiable FXML tag, so abstract classes and interfaces remain valid candidates.
        if (Fxml2AddImportFix.findCandidates(segName, xmlFile, false).isEmpty()) return null;
        return new Fxml2AddImportFix(segName, /* checkUnusable= */ false);
    }

    /**
     * Validates a function-call binding expression: the function-name path and every path
     * argument.  Mirrors the fxml-compiler's {@code AbstractFunctionEmitterFactory.findFunction}
     * (the method path is resolved as an instance path, a static class path, or a constructor)
     * plus the per-argument resolution of expression arguments against the evaluation context.
     *
     * <p>String, number, boolean, {@code null}, and {@code {...}} markup-extension arguments are
     * literals and carry no resolution error.
     *
     * @param path         the full binding path (selector and {@code ..} already stripped),
     *                     e.g. {@code "String.format('Width: %.0f', width)"}
     * @param attrTextBase offset within the attribute value text of {@code path[0]}
     */
    private static void annotateFunctionCall(
            @NotNull XmlAttributeValue attrVal,
            @NotNull String path,
            @NotNull PsiClass startClass,
            @org.jetbrains.annotations.Nullable XmlTag contextTag,
            @NotNull XmlFile xmlFile,
            @NotNull AnnotationHolder holder,
            int attrTextBase,
            @NotNull Kind kind) {
        int parenIdx = Fxml2BindingPathResolver.functionCallParenIndex(path);
        if (parenIdx < 0) return;

        Fxml2ExpressionParser.Expression callExpr;
        try {
            callExpr = Fxml2ExpressionParser.parse(path);
        } catch (Fxml2ExpressionParser.ParseException ignored) {
            // The name still resolves when an argument does not parse.
            String funcPath = path.substring(0, parenIdx);
            reportPathSegments(attrVal,
                    Fxml2BindingPathResolver.resolveFunctionName(
                            funcPath, startClass, xmlFile.getResolveScope(), kind, xmlFile),
                    startClass, attrTextBase, xmlFile, holder);
            return;
        }
        if (isSelectedFromInvocation(callExpr)) {
            reportPathSegments(attrVal,
                    Fxml2BindingPathResolver.resolveFunctionCall(path, startClass,
                            xmlFile.getResolveScope(), kind, xmlFile, contextTag),
                    startClass, attrTextBase, xmlFile, holder);
        } else {
            annotateExpressionOperands(attrVal, callExpr, attrTextBase,
                    startClass, contextTag, xmlFile, kind, holder);
        }
    }

    private static boolean isSelectedFromInvocation(
            Fxml2ExpressionParser.@NotNull Expression expression) {
        Fxml2ExpressionParser.MemberExpression member;
        if (expression instanceof Fxml2ExpressionParser.InvocationExpression invocation
                && invocation.target() instanceof Fxml2ExpressionParser.MemberExpression targetMember) {
            member = targetMember;
        } else if (expression instanceof Fxml2ExpressionParser.MemberExpression selectedMember) {
            member = selectedMember;
        } else {
            return false;
        }
        Fxml2ExpressionParser.Expression receiver = member.receiver();
        while (receiver instanceof Fxml2ExpressionParser.GroupedExpression grouped) {
            receiver = grouped.expression();
        }
        return receiver instanceof Fxml2ExpressionParser.InvocationExpression;
    }

    /**
     * Resolves one path of an expression against the evaluation context and reports the segments
     * that do not resolve.  The path may begin with a context selector, which names the element it
     * is resolved against.
     *
     * @param path        the path text, which contains no operator
     * @param attrTextBase offset of {@code path} within the attribute value text, i.e. counting the
     *                     opening quote
     * @return the resolved segments, or {@code null} when the path is empty or the class it is
     *         resolved against is unknown
     */
    private static java.util.@Nullable List<Fxml2BindingPathResolver.Segment> annotatePathExpression(
            @NotNull XmlAttributeValue attrVal,
            @NotNull String path,
            int attrTextBase,
            @NotNull Kind kind,
            @Nullable XmlTag contextTag,
            @NotNull XmlFile xmlFile,
            @NotNull AnnotationHolder holder) {

        Fxml2BindingExpressionParser.ContextSelector selector =
                Fxml2BindingExpressionParser.parseContextSelector(path);
        String remaining = selector != null ? selector.remainingPath() : path;
        if (remaining.isBlank()) return null;

        PsiClass startClass = contextTag != null
                ? Fxml2BindingPathResolver.resolveStartClass(selector, contextTag, xmlFile)
                : Fxml2BindingPathResolver.resolveCodeBehindClass(xmlFile);
        if (startClass == null) return null;

        var segments = Fxml2BindingPathResolver.resolve(
                remaining, startClass, xmlFile.getResolveScope(), kind, xmlFile);
        reportPathSegments(attrVal, segments, startClass,
                attrTextBase + (selector != null ? selector.selectorLength() : 0), xmlFile, holder);
        return segments;
    }

    /**
     * Reports the operands of an expression that combines values with operators.  Each operand is
     * validated in its own right, so that a path stands where the expression puts it rather than
     * being read as one path spanning the operators.
     *
     * @param attrTextBase offset of the expression source within the attribute value text
     */
    private static void annotateExpressionOperands(
            @NotNull XmlAttributeValue attrVal,
            Fxml2ExpressionParser.@NotNull Expression expression,
            int attrTextBase,
            @NotNull PsiClass startClass,
            @Nullable XmlTag contextTag,
            @NotNull XmlFile xmlFile,
            @NotNull Kind kind,
            @NotNull AnnotationHolder holder) {

        for (Fxml2ExpressionOperands.Operand operand
                : Fxml2ExpressionOperands.operands(expression, attrTextBase)) {
            switch (operand.kind()) {
                case FUNCTION_NAME -> reportPathSegments(attrVal,
                        Fxml2BindingPathResolver.resolveFunctionName(
                                operand.text(), startClass, xmlFile.getResolveScope(), kind, xmlFile),
                        startClass, operand.offset(), xmlFile, holder);
                case PATH -> annotatePathExpression(attrVal, operand.text(), operand.offset(),
                        kind, contextTag, xmlFile, holder);
            }
        }
    }

    /**
     * Reports that a function expression is not a valid reverse ({@code fx:Push} / {@code >{...}})
     * binding source, mirroring the compiler's {@code INVALID_REVERSE_BINDING_SOURCE} diagnostic.
     * Highlights the whole function-call expression.
     */
    private static void annotateFunctionNotReverseBindable(
            @NotNull XmlAttributeValue attrVal,
            @NotNull String path,
            @NotNull AnnotationHolder holder,
            int attrTextBase) {
        int parenIdx = Fxml2BindingPathResolver.functionCallParenIndex(path);
        if (parenIdx <= 0) return;
        String funcPath = path.substring(0, parenIdx);
        int lastDot = funcPath.lastIndexOf('.');
        String name = lastDot >= 0 ? funcPath.substring(lastDot + 1) : funcPath;
        int docStart = attrVal.getTextRange().getStartOffset() + attrTextBase;
        holder.newAnnotation(HighlightSeverity.ERROR,
                        name + " is not a valid reverse binding source")
                .range(new TextRange(docStart, docStart + path.length()))
                .create();
    }

    /**
     * For a bidirectional ({@code fx:Synchronize} / {@code #{...}}) function binding, the method or
     * constructor must be invoked with exactly one argument
     * (compiler: {@code INVALID_BIDIRECTIONAL_METHOD_PARAM_COUNT}).
     */
    private static void annotateBidirectionalArgumentCount(
            @NotNull XmlAttributeValue attrVal,
            @NotNull String path,
            @NotNull AnnotationHolder holder,
            int attrTextBase) {
        if (Fxml2BindingPathResolver.functionArguments(path).size() == 1) return;
        int docStart = attrVal.getTextRange().getStartOffset() + attrTextBase;
        holder.newAnnotation(HighlightSeverity.ERROR,
                        "A bidirectional conversion method or constructor must be invoked with a single argument")
                .range(new TextRange(docStart, docStart + path.length()))
                .create();
    }

    /**
     * For bidirectional function bindings ({@code #{method(arg)}}) without an explicit
     * {@code inverseMethod=}, the referenced method must be annotated with
     * {@code @org.jfxcore.markup.InverseMethod} (compiler: {@code METHOD_NOT_INVERTIBLE}).
     *
     * <p>Reports a {@link HighlightSeverity#ERROR} on the method-name segment when the method
     * resolves but no overload carries the annotation.  When the method does not resolve (the
     * error is already reported by {@link #annotateFunctionCall}) or the path is a constructor,
     * nothing is reported here.
     *
     * @param funcPath the function path before the {@code (}, e.g. {@code "formatDouble"},
     *                 {@code "String.format"}, or {@code "c1.c2.instanceNot"}
     * @param base     offset within the attribute value text where {@code funcPath} starts
     */
    private static void annotateInverseMethodRequired(
            @NotNull XmlAttributeValue attrVal,
            @NotNull String funcPath,
            @NotNull PsiClass startClass,
            @NotNull XmlFile xmlFile,
            @NotNull AnnotationHolder holder,
            int base) {
        if (funcPath.isBlank()) return;

        GlobalSearchScope scope = xmlFile.getResolveScope();
        var nameSegs = Fxml2BindingPathResolver.resolveFunctionName(
                funcPath, startClass, scope, Kind.SYNCHRONIZE, xmlFile);
        if (nameSegs.isEmpty()) return;

        Fxml2BindingPathResolver.Segment methodSeg = nameSegs.getLast();
        // Constructor (class) reference: @InverseMethod is not applicable here.
        if (methodSeg.classQualifier()) return;
        if (!(methodSeg.declaration() instanceof PsiMethod resolvedMethod)) return; // unresolved: already reported
        PsiClass owner = resolvedMethod.getContainingClass();
        if (owner == null) return;

        // Accept if any overload carries @org.jfxcore.markup.InverseMethod.
        for (PsiMethod method : owner.findMethodsByName(resolvedMethod.getName(), true)) {
            if (method.hasAnnotation("org.jfxcore.markup.InverseMethod")) return;
        }

        int docStart = attrVal.getTextRange().getStartOffset() + base + methodSeg.pathOffset();
        int docEnd = docStart + methodSeg.name().length();
        holder.newAnnotation(HighlightSeverity.ERROR,
                "'" + resolvedMethod.getName() + "' is not annotated with @org.jfxcore.markup.InverseMethod, " +
                "and no user-defined inverse method was provided")
                .range(new TextRange(docStart, docEnd))
                .create();
    }

    /**
     * Returns {@code true} when the expression carries an explicit {@code inverseMethod=} secondary
     * parameter.  Used to suppress the "method must be invertible" diagnostic, since an explicit
     * inverse method supplies what the {@code @InverseMethod} annotation otherwise would.
     */
    private static boolean hasInverseMethodParam(
            @NotNull Fxml2BindingExpressionParser.ParsedExpression expr) {
        for (Fxml2BindingExpressionParser.SecondaryParam param : expr.params()) {
            if ("inverseMethod".equals(param.name())) return true;
        }
        return false;
    }

    /**
     * Validates every secondary parameter of a binding expression: the parameter name, the parameter
     * value path, and any conflicts between parameters.
     *
     * <p>A name that is not valid for the binding kind is reported as unresolved (compiler:
     * {@code PROPERTY_NOT_FOUND}).  Among the names that conflict for the kind (for
     * {@code fx:Synchronize}: {@code format}, {@code converter}, {@code inverseMethod}), the second
     * and later occurrences are reported (compiler: {@code CONFLICTING_PROPERTIES}).
     *
     * <p>Each value path is resolved regardless of whether its name is valid: {@code format} and
     * {@code converter} are property paths; {@code inverseMethod} and every unrecognized name are
     * resolved like a function name (the value's last segment is a method or constructor).
     */
    private static void annotateSecondaryParams(
            @NotNull XmlAttributeValue attrVal,
            @NotNull Fxml2BindingExpressionParser.ParsedExpression expr,
            @NotNull PsiClass startClass,
            @NotNull XmlFile xmlFile,
            @NotNull AnnotationHolder holder) {
        GlobalSearchScope scope = xmlFile.getResolveScope();
        Set<String> valid = Fxml2BindingExpressionParser.validSecondaryParams(expr.kind());
        Set<String> conflicting = Fxml2BindingExpressionParser.conflictingSecondaryParams(expr.kind());
        int valueStart = attrVal.getTextRange().getStartOffset();
        String existingConflicting = null;

        for (Fxml2BindingExpressionParser.SecondaryParam param : expr.params()) {
            String name = param.name();
            boolean nameValid = valid.contains(name);

            // Name validation: an unrecognized name cannot be resolved against the intrinsic.
            if (!nameValid && param.nameOffset() >= 0) {
                int nameStart = valueStart + 1 + param.nameOffset();
                holder.newAnnotation(HighlightSeverity.ERROR,
                                "'" + name + "' in "
                                        + Fxml2BindingExpressionParser.kindToIntrinsicName(expr.kind())
                                        + " cannot be resolved")
                        .range(new TextRange(nameStart, nameStart + name.length()))
                        .create();
            } else if (nameValid && conflicting.contains(name) && param.nameOffset() >= 0) {
                // Conflict validation: only the second (and later) conflicting parameter is flagged.
                if (existingConflicting == null) {
                    existingConflicting = name;
                } else {
                    int nameStart = valueStart + 1 + param.nameOffset();
                    holder.newAnnotation(HighlightSeverity.ERROR,
                                    name + " and " + existingConflicting
                                            + " cannot be used at the same time")
                            .range(new TextRange(nameStart, nameStart + name.length()))
                            .create();
                }
            }

            // Value-path validation (independent of name validity).
            String paramPath = param.path();
            int paramPathOffset = param.pathOffset();
            if (paramPath.isBlank() || paramPathOffset < 0) continue;

            annotateSecondaryParamValue(
                    attrVal, paramPath, paramPathOffset, startClass, scope, expr.kind(), xmlFile, holder);
        }
    }

    /**
     * Validates the value path of a secondary parameter without hard-coding which parameters take a
     * property versus a method.  A parameter value may be either a property path (e.g. for
     * {@code format} / {@code converter}, pointing to a {@code Format} / {@code StringConverter}
     * instance) or a method/constructor path (e.g. for {@code inverseMethod}).  Both interpretations
     * are attempted: whichever fully resolves is reported.  When neither resolves, the
     * method/constructor interpretation is reported, since it yields the more useful diagnostics
     * (including the add-import fix for an unresolved class qualifier).
     */
    private static void annotateSecondaryParamValue(
            @NotNull XmlAttributeValue attrVal,
            @NotNull String paramPath,
            int paramPathOffset,
            @NotNull PsiClass startClass,
            @NotNull GlobalSearchScope scope,
            @NotNull Kind kind,
            @NotNull XmlFile xmlFile,
            @NotNull AnnotationHolder holder) {
        var propertySegs = Fxml2BindingPathResolver.resolve(paramPath, startClass, scope, null, xmlFile);
        if (isFullyResolved(propertySegs)) {
            reportPathSegments(attrVal, propertySegs, startClass, 1 + paramPathOffset, xmlFile, holder);
            return;
        }
        var methodSegs = Fxml2BindingPathResolver.resolveFunctionName(paramPath, startClass, scope, kind, xmlFile);
        // Report the method interpretation when it fully resolves, and otherwise as the fallback for
        // its richer diagnostics.
        reportPathSegments(attrVal, methodSegs, startClass, 1 + paramPathOffset, xmlFile, holder);
    }

    /** Returns {@code true} when {@code segments} is non-empty and every segment resolved. */
    private static boolean isFullyResolved(
            @NotNull java.util.List<Fxml2BindingPathResolver.Segment> segments) {
        return !segments.isEmpty() && segments.stream().allMatch(Fxml2BindingPathResolver.Segment::isResolved);
    }

    /**
     * Validates a custom markup extension invocation like {@code {MyExtension}}.
     *
     * <p>The extension class must:
     * <ol>
     *   <li>Be resolvable via the FXML imports in scope, and
     *   <li>Implement {@code org.jfxcore.markup.MarkupExtension} (otherwise the compiler reports
     *       {@code UNEXPECTED_MARKUP_EXTENSION}).
     * </ol>
     *
     * <p>Also validates parameter names (5.4) and {@code @ReturnType} applicability (5.6).
     *
     * @param hasTypeArg {@code true} if the invocation contains an explicit generic type argument
     *                   (e.g. {@code {StaticResource<String> key}}).  When {@code false},
     *                   the {@code @ReturnType} check is skipped for raw generic suppliers.
     */
    private static void annotateMarkupExtension(
            @NotNull XmlAttributeValue attrVal,
            @NotNull String rawValue,
            int itemOffset,
            @NotNull String extensionName,
            int nameOffset,
            boolean hasTypeArg,
            @Nullable PsiType targetType,
            @NotNull XmlFile xmlFile,
            @NotNull AnnotationHolder holder) {

        // Compute the document range of the extension name (inside the braces).
        // +1 skips the opening quote, the item offset the items in front of this one.
        int docStart = attrVal.getTextRange().getStartOffset() + 1 + itemOffset + nameOffset;
        int docEnd   = docStart + extensionName.length();
        TextRange nameRange = new TextRange(docStart, docEnd);

        // 1. Resolve the class via FXML imports.
        PsiClass extClass = Fxml2ImportResolver.resolve(extensionName, xmlFile);
        if (extClass == null) {
            extClass = JavaPsiFacade.getInstance(xmlFile.getProject())
                    .findClass(extensionName, xmlFile.getResolveScope());
        }
        if (extClass == null) {
            // A name that no import resolves may denote a class that is only missing its import,
            // so the fix that adds it is offered alongside the diagnostic.  The name is that of
            // the extension class itself, which is instantiated like a tag.
            var builder = holder.newAnnotation(HighlightSeverity.ERROR,
                    "Cannot resolve symbol '" + extensionName + "'")
                    .range(nameRange);
            if (!extensionName.contains(".")) {
                builder = builder.withFix(new Fxml2AddImportFix(extensionName));
            }
            builder.create();
            return;
        }

        // 2. Check that it implements org.jfxcore.markup.MarkupExtension.
        PsiClass markupExtClass = Fxml2WellKnownClasses.markupExtension(xmlFile.getProject());
        if (markupExtClass != null && !extClass.isInheritor(markupExtClass, true)) {
            holder.newAnnotation(HighlightSeverity.ERROR,
                    "'" + extensionName + "' is used like a markup extension")
                    .range(nameRange)
                    .create();
            return;
        }
        // If markupExtClass is null (library not on classpath), accept silently.

        // 3. (5.4) Validate parameter names in the extension expression.
        // rawValue is one item of the attribute value, e.g. "{MyExtension param1=value1}".
        if (rawValue.length() > 2) {
            String inner = rawValue.substring(1, rawValue.length() - 1).trim();
            int firstSpace = indexOfWhitespaceME(inner);
            if (firstSpace >= 0) {
                String paramsPart = inner.substring(firstSpace).trim();
                int paramsPartInRaw = rawValue.indexOf(paramsPart, 1 + firstSpace);
                if (paramsPartInRaw >= 0) {
                    annotateMarkupExtensionParams(
                            attrVal, paramsPart, itemOffset + paramsPartInRaw, extClass, holder);
                    annotateMarkupExtensionBindingArgs(
                            attrVal, paramsPart, itemOffset + paramsPartInRaw, xmlFile, holder);
                }
            }
        }

        // 4. (5.6) Validate @ReturnType applicability when used on a property.
        if (attrVal.getParent() instanceof XmlAttribute attr
                && attr.getParent() instanceof XmlTag tag) {
            String attrName = attr.getName();
            if (!Fxml2XmlUtil.isNonPropertyAttribute(attrName)) {
                annotateMarkupExtensionReturnType(
                        nameRange, extClass, attrName, tag, targetType, xmlFile, holder, hasTypeArg);
            }
        }
    }

    /**
     * Validates a prefix-shorthand markup extension invocation such as {@code @icons/app.png}
     * or {@code %greeting; formatArguments=Jane, Doe}.
     *
     * <p>Steps performed:
     * <ol>
     *   <li>Resolve the mapped extension class.  If the markup runtime library is absent from
     *       the classpath, reports "Cannot resolve markup extension class".</li>
     *   <li>Verify that the class implements {@code MarkupExtension}.</li>
     *   <li>Apply {@code @ReturnType} validation (prefix shorthand is always raw form, so
     *       {@code hasTypeArg=false} is passed, raw invocations skip the check for
     *       generic suppliers without {@code @ReturnType}).</li>
     *   <li>Validate any {@code ; param=value} arguments after the prefix.</li>
     * </ol>
     */
    private static void annotatePrefixShorthand(
            @NotNull XmlAttributeValue attrVal,
            @NotNull Fxml2BindingExpressionParser.PrefixShorthandExpression pse,
            @NotNull String rawValue,
            int itemOffset,
            @Nullable PsiType targetType,
            @NotNull XmlFile xmlFile,
            @NotNull AnnotationHolder holder) {

        // The prefix char is at item offset 0; +1 skips the opening quote of the attribute value.
        int docStart = attrVal.getTextRange().getStartOffset() + 1 + itemOffset;
        TextRange prefixRange = new TextRange(docStart, docStart + 1);
        // Full expression range (prefix char + default arg, e.g. "@path") used for return-type errors
        TextRange fullRange = new TextRange(docStart, docStart + rawValue.length());

        // 1. Resolve the mapped class.
        // The prefix declaration may use a simple name (e.g. <?prefix % = MyExt?>) or a FQN.
        // Try Fxml2ImportResolver first (handles simple names via <?import?> declarations),
        // then fall back to a direct findClass for FQNs (covers the built-in defaults such
        // as ClassPathResource and StaticResource which are always stored as FQNs).
        String mappedClass = pse.mappedClass();
        PsiClass extClass = Fxml2ImportResolver.resolve(mappedClass, xmlFile);
        if (extClass == null) {
            extClass = JavaPsiFacade.getInstance(xmlFile.getProject())
                    .findClass(mappedClass, GlobalSearchScope.allScope(xmlFile.getProject()));
        }
        if (extClass == null) {
            // Class cannot be resolved: either the markup runtime library is absent or
            // the import for a simple-name prefix declaration is missing.
            holder.newAnnotation(HighlightSeverity.ERROR,
                    "Cannot resolve markup extension class '" + mappedClass + "'")
                    .range(prefixRange)
                    .create();
            return;
        }

        // 2. Check that it implements MarkupExtension.
        PsiClass markupExtClass = Fxml2WellKnownClasses.markupExtension(xmlFile.getProject());
        if (markupExtClass != null && !extClass.isInheritor(markupExtClass, true)) {
            holder.newAnnotation(HighlightSeverity.ERROR,
                    "'" + extClass.getName() + "' is used like a markup extension")
                    .range(prefixRange)
                    .create();
            return;
        }

        // 3. @ReturnType validation.  Prefix shorthand is always raw form (no type arg).
        if (attrVal.getParent() instanceof XmlAttribute attr
                && attr.getParent() instanceof XmlTag tag) {
            String attrName = attr.getName();
            if (!Fxml2XmlUtil.isNonPropertyAttribute(attrName)) {
                annotateMarkupExtensionReturnType(fullRange, extClass, attrName, tag, targetType,
                        xmlFile, holder, /* hasTypeArg= */ false);
            }
        }

        // 4. Validate ; param=value arguments.
        if (pse.paramsPart() != null && !pse.paramsPart().isEmpty()) {
            annotateMarkupExtensionParams(
                    attrVal, pse.paramsPart(), itemOffset + pse.paramsOffset(), extClass, holder);
        }
    }

    /**
     * Validates the content of a markup extension invocation: each named parameter has to
     * correspond to a known {@code @NamedArg} constructor parameter, a JavaFX property
     * ({@code xyzProperty()} method), or a getter/setter pair ({@code getXyz}/{@code setXyz})
     * on the extension class.
     *
     * <p>Parameters are separated by {@code ';'} or by a line break.  An assignment that follows a
     * comma stands in the value list of the parameter before it, where the markup language expects
     * a value, and is reported as a parameter that misses its separator.
     *
     * @param content      the content of the extension, i.e. the text after its class name
     * @param contentInRaw offset of {@code content} within the raw attribute value (no quotes)
     */
    private static void annotateMarkupExtensionParams(
            @NotNull XmlAttributeValue attrVal,
            @NotNull String content,
            int contentInRaw,
            @NotNull PsiClass extClass,
            @NotNull AnnotationHolder holder) {

        java.util.Set<String> knownParams = collectKnownExtensionParams(extClass);
        if (knownParams.isEmpty()) return;

        int docBase = attrVal.getTextRange().getStartOffset() + 1 + contentInRaw;
        for (var section : Fxml2MarkupExtensionContentParser.parse(content)) {
            switch (section) {
                case Fxml2MarkupExtensionContentParser.NamedParameter param -> {
                    if (!knownParams.contains(param.name())) {
                        int start = docBase + param.offset();
                        holder.newAnnotation(HighlightSeverity.ERROR,
                                        "Unknown markup extension parameter '" + param.name() + "'")
                                .range(new TextRange(start, start + param.name().length()))
                                .create();
                    }
                    annotateMisplacedParams(
                            attrVal, param.value(), contentInRaw + param.valueOffset(), holder);
                }
                case Fxml2MarkupExtensionContentParser.PositionalValue value ->
                        annotateMisplacedParams(
                                attrVal, value.text(), contentInRaw + value.offset(), holder);
            }
        }
    }

    /**
     * Reports an assignment that stands in a value list, where the markup language expects a value.
     * Its name is a parameter of the extension that misses the {@code ';'} in front of it.
     *
     * @param valueInRaw offset of {@code value} within the raw attribute value (no quotes)
     */
    private static void annotateMisplacedParams(
            @NotNull XmlAttributeValue attrVal,
            @NotNull String value,
            int valueInRaw,
            @NotNull AnnotationHolder holder) {

        var items = Fxml2ValueSequenceParser.split(value, java.util.Map.of());
        for (int i = 1; i < items.size(); i++) {
            var item = items.get(i);
            int nameLength = assignedNameLength(item.text());
            if (nameLength == 0) continue;

            int start = attrVal.getTextRange().getStartOffset() + 1 + valueInRaw + item.offset();
            holder.newAnnotation(HighlightSeverity.ERROR,
                            "Expected ';' before parameter '" + item.text().substring(0, nameLength)
                            + "'")
                    .range(new TextRange(start, start + nameLength))
                    .create();
        }
    }

    /**
     * Returns the length of the name assigned by {@code text}, or {@code 0} when {@code text} does
     * not start with an identifier followed by {@code '='}.
     */
    private static int assignedNameLength(@NotNull String text) {
        if (text.isEmpty() || !Character.isJavaIdentifierStart(text.charAt(0))) return 0;
        int end = 1;
        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
        int afterName = end;
        while (afterName < text.length() && Character.isWhitespace(text.charAt(afterName))) afterName++;
        return afterName < text.length() && text.charAt(afterName) == '=' ? end : 0;
    }

    /**
     * Scans the parameter part of a markup extension expression for positional binding
     * sub-expressions (e.g. {@code $DataClass::field}) and annotates any invalid
     * observable-selection operator usages within them.
     *
     * <p>Binding sub-expressions are identified by a leading {@code $} or {@code #} sigil
     * that is not followed immediately by an {@code =} sign (which would make it a named
     * parameter value). For each such sub-expression, the binding path is resolved using
     * the code-behind class as the start class and each segment is checked: when the
     * observable-selection operator {@code ::} was used to access a member that is not an
     * {@code ObservableValue} subtype, an error is reported.
     *
     * @param attrVal         the attribute value node that contains the markup extension text
     * @param paramsPart      the parameter portion of the markup extension, after the class name
     * @param paramsPartInRaw the offset of {@code paramsPart} within {@code attrVal.getValue()}
     * @param xmlFile         the containing FXML file
     * @param holder          the annotation holder for reporting errors
     */
    private static void annotateMarkupExtensionBindingArgs(
            @NotNull XmlAttributeValue attrVal,
            @NotNull String paramsPart,
            int paramsPartInRaw,
            @NotNull XmlFile xmlFile,
            @NotNull AnnotationHolder holder) {

        // Resolve the start class (code-behind or context).
        XmlTag contextTag = null;
        if (attrVal.getParent() instanceof XmlAttribute attr
                && attr.getParent() instanceof XmlTag t) {
            contextTag = t;
        }
        PsiClass startClass = contextTag != null
                ? Fxml2BindingPathResolver.resolveStartClass(null, contextTag, xmlFile)
                : Fxml2BindingPathResolver.resolveCodeBehindClass(xmlFile);
        if (startClass == null) return;

        GlobalSearchScope scope = xmlFile.getResolveScope();
        // Base document offset for the start of paramsPart (skipping opening quote of attrVal).
        int docParamsBase = attrVal.getTextRange().getStartOffset() + 1 + paramsPartInRaw;

        int pos = 0;
        while (pos < paramsPart.length()) {
            char ch = paramsPart.charAt(pos);
            // Skip separators and whitespace.
            if (Character.isWhitespace(ch) || ch == ',' || ch == ';') {
                pos++;
                continue;
            }
            // Detect a binding sigil: $ or # that is not part of a named-arg value (key=...).
            if ((ch == '$' || ch == '#') && pos + 1 < paramsPart.length()) {
                int sigEnd = pos + 1;
                // Skip the optional { ... } wrapper (e.g. ${path} or #{path}).
                boolean hasBrace = paramsPart.charAt(sigEnd) == '{';
                int pathStart;
                int pathEnd;
                if (hasBrace) {
                    pathStart = sigEnd + 1;
                    int depth = 1;
                    int scan = pathStart;
                    while (scan < paramsPart.length() && depth > 0) {
                        char c = paramsPart.charAt(scan++);
                        if (c == '{') depth++;
                        else if (c == '}') depth--;
                    }
                    pathEnd = scan - 1; // points to the closing '}'
                } else {
                    pathStart = sigEnd;
                    // Token ends at whitespace, comma, semicolon, or end of params.
                    int scan = pathStart;
                    while (scan < paramsPart.length()) {
                        char c = paramsPart.charAt(scan);
                        if (Character.isWhitespace(c) || c == ',' || c == ';') break;
                        scan++;
                    }
                    pathEnd = scan;
                }
                String path = paramsPart.substring(pathStart, pathEnd);
                if (!path.isBlank()) {
                    // Resolve and validate the path segments.
                    java.util.List<Fxml2BindingPathResolver.Segment> segments =
                            Fxml2BindingPathResolver.resolve(path, startClass, scope,
                                    Kind.EVALUATE, xmlFile);
                    // Doc offset of path[0] within the document.
                    int docPathBase = docParamsBase + pathStart;
                    PsiClass prevType = startClass;
                    boolean prevResolved = true;
                    for (Fxml2BindingPathResolver.Segment seg : segments) {
                        int segDocStart = docPathBase + seg.pathOffset();
                        int segDocEnd = segDocStart + seg.name().length();
                        if (!seg.isResolved()) {
                            if (prevResolved) {
                                String ownerName = prevType != null ? prevType.getQualifiedName() : "?";
                                holder.newAnnotation(HighlightSeverity.ERROR,
                                        "'" + seg.name() + "' in " + ownerName + " cannot be resolved")
                                        .range(new TextRange(segDocStart, segDocEnd))
                                        .create();
                            }
                            prevResolved = false;
                            prevType = null;
                        } else {
                            if (seg.observableSelector() && !seg.classQualifier()
                                    && isNotObservableDeclaration(seg.declaration(), xmlFile)) {
                                String ownerName = prevType != null ? prevType.getQualifiedName() : "?";
                                holder.newAnnotation(HighlightSeverity.ERROR,
                                        "'" + seg.name() + "' in " + ownerName
                                        + " cannot be referenced"
                                        + " (note: '.' can be used instead of '::' within a path expression)")
                                        .range(new TextRange(segDocStart, segDocEnd))
                                        .create();
                            }
                            prevResolved = true;
                            prevType = seg.resultType();
                        }
                    }
                }
                pos = hasBrace ? pathEnd + 1 : pathEnd;
                continue;
            }
            pos++;
        }
    }

    /**
     * Collects all valid parameter names for a markup extension class:
     * {@code @NamedArg} constructor params, JavaFX property methods, and getter/setter pairs.
     */
    private static @NotNull java.util.Set<String> collectKnownExtensionParams(@NotNull PsiClass extClass) {
        java.util.Set<String> params = new java.util.LinkedHashSet<>();
        for (com.intellij.psi.PsiMethod ctor : extClass.getConstructors()) {
            for (com.intellij.psi.PsiParameter p : ctor.getParameterList().getParameters()) {
                String n = Fxml2NamedArgResolver.namedArgValue(p);
                if (n != null) params.add(n);
            }
        }
        for (com.intellij.psi.PsiMethod m : extClass.getAllMethods()) {
            String mName = m.getName();
            if (Fxml2PropertyNameUtil.isPropertyAccessorName(mName)
                    && m.getParameterList().getParametersCount() == 0) {
                params.add(Fxml2PropertyNameUtil.propertyAccessorToPropertyName(mName));
            }
            if (Fxml2PropertyNameUtil.isSetterName(mName)
                    && m.getParameterList().getParametersCount() == 1) {
                params.add(Fxml2PropertyNameUtil.setterToPropertyName(mName));
            }
        }
        return params;
    }

    /**
     * Returns the index of the first whitespace character in {@code s} that is not inside
     * a generic type-argument block, or {@code -1} if none.
     *
     * <p>This is used to split the inner markup-extension text into the class-name token
     * (which may include generic type arguments) and the trailing parameter list.
     *
     * <p>Two forms of type arguments are handled:
     * <ul>
     *   <li><b>Literal</b>: {@code MyMarkupExtension<String> key=val}, with balanced {@code <}/{@code >}
     *       depth tracking prevents splitting inside the angle-bracket block.
     *   <li><b>XML-entity</b>: {@code MyMarkupExtension&lt;String&gt; key=val}, where the {@code &lt;}
     *       and {@code &gt;} sequences contain no literal {@code <}/{@code >} characters, so the
     *       depth tracker stays at 0 and the first whitespace found is always after the closing
     *       {@code &gt;}, which is correct.
     * </ul>
     * Nested generics like {@code Map<String, Integer>} are also handled correctly by the
     * depth counter.
     */
    private static int indexOfWhitespaceME(@NotNull String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') { if (depth > 0) depth--; }
            else if (depth == 0 && Character.isWhitespace(c)) return i;
        }
        return -1;
    }

    /**
     * (5.6) When a markup extension implements {@code MarkupExtension.Supplier} and its
     * {@code get()} method carries {@code @ReturnType}, validates that the target property
     * type is compatible with one of the declared types.
     *
     * <p>When {@code hasTypeArg} is {@code false} (raw invocation without an explicit
     * {@code <TypeArg>}), the check is <em>skipped</em> for extensions whose {@code get()}
     * method has no {@code @ReturnType} annotation and that implement the supplier interface
     * with a generic type parameter.  This matches the fxml-compiler's
     * "raw Supplier -> bottom type -> compatible with any type" rule.
     * Extensions that carry an explicit {@code @ReturnType} (e.g. {@code ClassPathResource})
     * are always checked regardless of {@code hasTypeArg}.
     */
    private static void annotateMarkupExtensionReturnType(
            @NotNull TextRange nameRange,
            @NotNull PsiClass extClass,
            @NotNull String attrName,
            @NotNull XmlTag tag,
            @Nullable PsiType targetType,
            @NotNull XmlFile xmlFile,
            @NotNull AnnotationHolder holder,
            boolean hasTypeArg) {

        com.intellij.openapi.project.Project project = xmlFile.getProject();
        PsiClass supplierClass = Fxml2WellKnownClasses.markupExtensionSupplier(project);
        if (supplierClass == null || !extClass.isInheritor(supplierClass, true)) return;

        PsiClass markupContextClass = Fxml2WellKnownClasses.markupContext(project);
        com.intellij.psi.PsiMethod getMethod = null;
        for (com.intellij.psi.PsiMethod m : extClass.findMethodsByName("get", true)) {
            com.intellij.psi.PsiParameter[] params = m.getParameterList().getParameters();
            if (params.length == 1 && markupContextClass != null
                    && params[0].getType() instanceof com.intellij.psi.PsiClassType pct
                    && markupContextClass.equals(pct.resolve())) {
                getMethod = m;
                break;
            }
        }
        if (getMethod == null) return;

        com.intellij.psi.PsiAnnotation returnTypeAnn =
                getMethod.getAnnotation("org.jfxcore.markup.MarkupExtension.Supplier.ReturnType");

        // Raw invocation with no type argument and no @ReturnType -> the compiler treats the
        // return type as bottom (compatible with any type).  Skip the check in that case.
        if (returnTypeAnn == null && !hasTypeArg) return;
        if (returnTypeAnn == null) return;

        com.intellij.psi.PsiAnnotationMemberValue valueAttr = returnTypeAnn.findAttributeValue("value");
        if (valueAttr == null) return;

        java.util.List<PsiClass> allowedTypes = new java.util.ArrayList<>();
        if (valueAttr instanceof com.intellij.psi.PsiArrayInitializerMemberValue arr) {
            for (com.intellij.psi.PsiAnnotationMemberValue v : arr.getInitializers()) {
                if (v instanceof com.intellij.psi.PsiClassObjectAccessExpression coe) {
                    com.intellij.psi.PsiType t = coe.getOperand().getType();
                    if (t instanceof com.intellij.psi.PsiClassType pct2) {
                        PsiClass cls = pct2.resolve();
                        if (cls != null) allowedTypes.add(cls);
                    }
                }
            }
        } else if (valueAttr instanceof com.intellij.psi.PsiClassObjectAccessExpression coe) {
            com.intellij.psi.PsiType t = coe.getOperand().getType();
            if (t instanceof com.intellij.psi.PsiClassType pct2) {
                PsiClass cls = pct2.resolve();
                if (cls != null) allowedTypes.add(cls);
            }
        }
        if (allowedTypes.isEmpty()) return;

        if (!(tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd)) return;
        PsiClass ownerClass = cd.getPsiClass();
        if (ownerClass == null) return;
        if (targetType == null) return;

        // When the extension also implements PropertyConsumer and the target attribute
        // has an underlying JavaFX property method (xProperty()), PropertyConsumer takes
        // precedence at runtime: skip the Supplier @ReturnType compatibility check.
        PsiClass propConsumerClass = Fxml2WellKnownClasses.markupExtensionPropertyConsumer(project);
        if (propConsumerClass != null && extClass.isInheritor(propConsumerClass, true)) {
            String propMethodName = attrName + "Property";
            for (com.intellij.psi.PsiMethod m : ownerClass.findMethodsByName(propMethodName, true)) {
                if (m.getParameterList().isEmpty()
                        && m.hasModifierProperty(com.intellij.psi.PsiModifier.PUBLIC)
                        && !m.hasModifierProperty(com.intellij.psi.PsiModifier.STATIC)) {
                    return; // PropertyConsumer takes precedence, no @ReturnType validation
                }
            }
        }

        boolean applicable = allowedTypes.stream().anyMatch(allowed -> {
            com.intellij.psi.PsiType allowedType =
                    com.intellij.psi.JavaPsiFacade.getElementFactory(project)
                            .createType(allowed);
            return targetType.isAssignableFrom(allowedType);
        });

        if (!applicable) {
            String allowedNames = allowedTypes.stream()
                    .map(c -> c.getName() != null ? c.getName() : c.getQualifiedName())
                    .collect(java.util.stream.Collectors.joining(", "));
            holder.newAnnotation(HighlightSeverity.ERROR,
                    "Markup extension '" + extClass.getName()
                    + "' is not applicable to '" + attrName
                    + "': supported types are " + allowedNames)
                    .range(nameRange)
                    .create();
        }
    }

    /**
     * Validates one item of a value sequence that supplies its value through a markup extension
     * or a binding expression.  The item is validated against the type of the item it supplies,
     * so that an expression which does not produce that type is reported on the item that uses it.
     */
    private static void annotateValueItem(
            @NotNull XmlAttributeValue attrVal,
            @NotNull Fxml2AttributeValueItems.TypedItem typedItem,
            @NotNull XmlFile xmlFile,
            @NotNull AnnotationHolder holder) {

        Fxml2ValueSequenceParser.ValueItem item = typedItem.item();
        if (!item.isMarkupExtension()) return;

        Object parsed = Fxml2BindingExpressionParser.parse(
                item.text(), Fxml2ImportResolver.parsePrefixMappings(xmlFile));
        switch (parsed) {
            case Fxml2BindingExpressionParser.PrefixShorthandExpression pse ->
                    annotatePrefixShorthand(attrVal, pse, item.text(), item.offset(),
                            typedItem.requiredType(), xmlFile, holder);
            case Fxml2BindingExpressionParser.MarkupExtensionExpression(
                    String extensionName, int nameOffset, boolean hasTypeArg) ->
                    annotateMarkupExtension(attrVal, item.text(), item.offset(), extensionName,
                            nameOffset, hasTypeArg, typedItem.requiredType(), xmlFile, holder);
            case Fxml2BindingExpressionParser.ParsedExpression expr ->
                    annotateBindingItem(attrVal, typedItem, expr, xmlFile, holder);
            case null, default -> { }
        }
    }

    /**
     * Validates one item of a value sequence that is a binding expression.
     *
     * <p>An item supplies a value rather than assigning to a property, so only the notations that
     * produce a value are applicable to it: the one-time and the unidirectional notation.  The
     * content notations and the reverse and bidirectional notations assign to a property, which an
     * item is not.
     *
     * <p>A path that calls a function is left to the whole-value validation, whose function
     * handling resolves the call and its arguments.
     */
    private static void annotateBindingItem(
            @NotNull XmlAttributeValue attrVal,
            @NotNull Fxml2AttributeValueItems.TypedItem typedItem,
            @NotNull Fxml2BindingExpressionParser.ParsedExpression expr,
            @NotNull XmlFile xmlFile,
            @NotNull AnnotationHolder holder) {

        Fxml2ValueSequenceParser.ValueItem item = typedItem.item();
        int itemDocStart = attrVal.getTextRange().getStartOffset() + 1 + item.offset();
        TextRange itemRange = new TextRange(itemDocStart, itemDocStart + item.text().length());

        Kind kind = expr.kind();
        if (kind != Kind.EVALUATE && kind != Kind.OBSERVE) {
            holder.newAnnotation(HighlightSeverity.ERROR,
                            kind.intrinsicName() + " bindings are not applicable to an item of a "
                            + "value list")
                    .range(itemRange)
                    .create();
            return;
        }

        if (Fxml2BindingPathResolver.functionCallParenIndex(expr.strippedPath()) >= 0) return;

        XmlTag contextTag = attrVal.getParent() instanceof XmlAttribute attr
                && attr.getParent() instanceof XmlTag tag ? tag : null;
        // The path is positioned relative to the item, which starts after the opening quote.
        var segments = annotatePathExpression(attrVal, expr.strippedPath(),
                1 + item.offset() + expr.strippedPathOffset(), kind, contextTag, xmlFile, holder);
        if (segments != null && isFullyResolved(segments)) {
            annotateBindingSourceType(segments, kind, typedItem.requiredType(), itemRange, holder);
        }
    }

    private static void annotateBindingPath(XmlAttributeValue attrVal, XmlFile xmlFile, AnnotationHolder holder) {
        String rawValue = attrVal.getValue();
        if (rawValue.isBlank()) {
            return;
        }
        // Skip fx: attribute values: they use separate reference/validation providers
        // (e.g. fx:context is validated by FxContextReferenceProvider against the code-behind).
        if (attrVal.getParent() instanceof XmlAttribute parentAttr) {
            if (Fxml2XmlUtil.isNonPropertyAttribute(parentAttr.getName())) return;
        }

        // Plain method name on an EventHandler-typed property: validate against code-behind.
        // The compiler infers from the property type that a plain name is a method reference,
        // not a literal string to be coerced.
        if (attrVal.getParent() instanceof XmlAttribute attr
                && attr.getParent() instanceof XmlTag tag
                && !Fxml2BindingExpressionParser.looksLikeBindingExpression(rawValue)) {
            String attrName = attr.getName();
            if (!attrName.contains(".") && tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd) {
                PsiClass ownerClass = cd.getPsiClass();
                if (ownerClass != null) {
                    java.util.List<String> siblings = Fxml2NamedArgResolver.collectAttributeNames(tag);
                    PsiType propType = Fxml2AttributeValueResolver.propertyType(ownerClass, attrName, siblings);
                    if (isEventHandlerType(propType, xmlFile)) {
                        annotateMethodHandlerRef(attrVal, rawValue.trim(), propType, xmlFile, holder);
                        return;
                    }
                }
            }
        }

        // --- Element-notation path validation ---
        // When the attribute is "source" on an fx:* binding element
        // (e.g. <fx:Evaluate source="caption"/>), the value is a plain binding path
        // (not a $ or {} expression). Validate it directly.
        if (attrVal.getParent() instanceof XmlAttribute pathAttr
                && "source".equals(pathAttr.getName())
                && pathAttr.getParent() instanceof XmlTag fxTag
                && Fxml2ImportResolver.FXML2_NAMESPACE.equals(fxTag.getNamespace())
                && FX_ELEMENT_KINDS.containsKey(fxTag.getLocalName())) {
            annotateElementNotationPath(attrVal, rawValue, fxTag, xmlFile, holder);
            return;
        }

        // A value that is a sequence of several items has one markup extension per item, each
        // producing the type of the item it supplies.  A value that is a single item is validated
        // below against the property type, because a single value is offered to the property
        // before it is offered to a constructor parameter.
        java.util.List<Fxml2AttributeValueItems.TypedItem> items =
                Fxml2AttributeValueItems.resolveTypedItems(attrVal, xmlFile);
        if (items.size() > 1) {
            for (Fxml2AttributeValueItems.TypedItem typedItem : items) {
                annotateValueItem(attrVal, typedItem, xmlFile, holder);
            }
            return;
        }

        PsiType valueTargetType = attrVal.getParent() instanceof XmlAttribute valueAttr
                ? Fxml2AttributeValueResolver.attributeTargetType(valueAttr, xmlFile)
                : null;

        Object parsed = Fxml2BindingExpressionParser.parse(rawValue,
                Fxml2ImportResolver.parsePrefixMappings(xmlFile));
        switch (parsed) {
            case null -> { return; }
            case Fxml2BindingExpressionParser.ParseError error -> {
                String message = error.message();
                int errorOffset = error.errorOffset();
                int errorLength = error.errorLength();
                // When the error covers the entire value (offset=0, length=value.length()),
                // expand to include the surrounding quotes so the whole attribute value is highlighted.
                if (errorOffset == 0 && errorLength == rawValue.length()) {
                    holder.newAnnotation(HighlightSeverity.ERROR, message)
                            .range(attrVal)
                            .create();
                    return;
                }
                annotateValueError(attrVal, holder, message,
                        new TextRange(errorOffset, errorOffset + errorLength));
                return;
            }
            case Fxml2BindingExpressionParser.MissingBindingPath(String intrinsicName) -> {
                // Highlight the whole attribute value (including surrounding quotes)
                holder.newAnnotation(HighlightSeverity.ERROR,
                                intrinsicName + ".source must be specified")
                        .range(attrVal.getTextRange())
                        .create();
                return;
            }
            case Fxml2BindingExpressionParser.PrefixShorthandExpression pse -> {
                annotatePrefixShorthand(attrVal, pse, rawValue, /* itemOffset= */ 0,
                        valueTargetType, xmlFile, holder);
                return;
            }
            case Fxml2BindingExpressionParser.MarkupExtensionExpression(
                    String extensionName, int nameOffset, boolean hasTypeArg) -> {
                annotateMarkupExtension(attrVal, rawValue, /* itemOffset= */ 0, extensionName,
                        nameOffset, hasTypeArg, valueTargetType, xmlFile, holder);
                return;
            }
            default -> { /* ParsedExpression: fall through to path validation */ }
        }
        Fxml2BindingExpressionParser.ParsedExpression expr = (Fxml2BindingExpressionParser.ParsedExpression) parsed;

        Fxml2ExpressionParser.Expression expressionTree;
        try {
            expressionTree = expr.expression();
        } catch (org.jfxcore.fxml.resolve.Fxml2ExpressionParser.ParseException error) {
            int errorStart = expr.pathOffset() + error.span().start();
            int errorEnd = expr.pathOffset() + error.span().end();
            annotateValueError(attrVal, holder, error.getMessage(),
                    new TextRange(errorStart, errorEnd));
            return;
        }

        // Boolean operator applicability: ! / !! must not be used with content binding kinds.
        // fx:Evaluate/Observe/Push/Synchronize ..source are not applicable here
        if (expr.operatorLength() > 0) {
            Kind kind = expr.kind();
            if (kind == Kind.EVALUATE_CONTENT || kind == Kind.OBSERVE_CONTENT
                    || kind == Kind.PUSH_CONTENT || kind == Kind.SYNCHRONIZE_CONTENT) {
                String op = expr.operatorLength() == 2 ? "!!" : "!";
                String kindLabel = kind.intrinsicName();
                // Highlight the operator character(s) within the attribute value
                int docBase = attrVal.getTextRange().getStartOffset() + 1; // skip opening quote
                int opStart = docBase + expr.pathOffset();
                int opEnd = opStart + expr.operatorLength();
                holder.newAnnotation(HighlightSeverity.ERROR,
                        "Boolean operator '" + op + "' is not applicable to " + kindLabel + " bindings")
                        .range(new TextRange(opStart, opEnd))
                        .create();
                return;
            }
        }

        String strippedPath = expr.strippedPath();
        // The :: (observable-selection) operator is now handled directly by the resolver,
        // which splits on both '.' and '::' and tracks which segments must not be unwrapped.
        // No pre-processing of :: is needed here.

        // Parse a context selector.
        Fxml2BindingExpressionParser.ContextSelector selector =
                Fxml2BindingExpressionParser.parseContextSelector(strippedPath);
        String path = selector != null ? selector.remainingPath() : strippedPath;

        // Get the context tag for selector resolution.
        XmlTag contextTag = null;
        if (attrVal.getParent() instanceof com.intellij.psi.xml.XmlAttribute attr
                && attr.getParent() instanceof XmlTag t) {
            contextTag = t;
        }

        // Resolve start class using the context selector
        PsiClass startClass;
        if (contextTag != null) {
            startClass = Fxml2BindingPathResolver.resolveStartClass(selector, contextTag, xmlFile);
        } else {
            startClass = Fxml2BindingPathResolver.resolveCodeBehindClass(xmlFile);
        }
        if (startClass == null) {
            return;
        }

        // If the path is empty after stripping the selector, there is nothing to validate.
        if (path.isBlank()) return;

        // An expression that combines values with operators is validated operand by operand: its
        // text is not one path, so resolving it as one would report the operators as segments.
        if (!Fxml2ExpressionOperands.isPathExpression(expressionTree)) {
            annotateExpressionOperands(attrVal, expressionTree, 1 + expr.pathOffset(),
                    startClass, contextTag, xmlFile, expr.kind(), holder);
            if (expr.hasParam()) {
                annotateSecondaryParams(attrVal, expr, startClass, xmlFile, holder);
            }
            return;
        }

        // Function-call syntax: a '(' that is NOT immediately preceded by '.'
        // (which would indicate an attached-property group like "(VBox.margin)").
        // e.g. "formatDouble(value)", "String.format('...', x)", "Color(0.5, 0.5, 0.5, 1.0)".
        int parenIdx = Fxml2BindingPathResolver.functionCallParenIndex(path);
        if (parenIdx >= 0) {
            int pathBase = 1 + expr.strippedPathOffset() + (selector != null ? selector.selectorLength() : 0);
            // fx:Push (reverse binding) is not applicable to function expressions; the compiler
            // fails on this alone, so report only that and skip detailed function validation.
            if (expr.kind() == Kind.PUSH || expr.kind() == Kind.PUSH_CONTENT) {
                annotateFunctionNotReverseBindable(attrVal, path, holder, pathBase);
                return;
            }
            // Validate the function-name path and every path argument (compiler:
            // AbstractFunctionEmitterFactory.findFunction plus per-argument resolution).
            annotateFunctionCall(attrVal, path, startClass, contextTag, xmlFile, holder,
                    pathBase, expr.kind());
            // fx:Synchronize: the method/constructor must be invoked with a single argument and
            // an inverse method must be available.
            if (expr.kind() == Kind.SYNCHRONIZE || expr.kind() == Kind.SYNCHRONIZE_CONTENT) {
                annotateBidirectionalArgumentCount(attrVal, path, holder, pathBase);
                String funcPath = path.substring(0, parenIdx);
                if (!hasInverseMethodParam(expr)) {
                    annotateInverseMethodRequired(attrVal, funcPath, startClass, xmlFile, holder, pathBase);
                }
            }
            // Also validate the secondary params (inverseMethod=, format=, converter=, ...) if present
            if (expr.hasParam()) {
                annotateSecondaryParams(attrVal, expr, startClass, xmlFile, holder);
            }
            return;
        }

        GlobalSearchScope scope = xmlFile.getResolveScope();
        var segments = Fxml2BindingPathResolver.resolve(path, startClass, scope, expr.kind(), xmlFile);

        // Base offset within the attribute value text: skip opening quote, boolean operator, and
        // context selector.  Each segment is positioned at base + seg.pathOffset().
        int base = 1 + expr.strippedPathOffset() + (selector != null ? selector.selectorLength() : 0);
        boolean allResolved = reportPathSegments(attrVal, segments, startClass, base, xmlFile, holder);

        // Validate secondary params (inverseMethod=, format=, converter=, ...) if present
        if (expr.hasParam()) {
            annotateSecondaryParams(attrVal, expr, startClass, xmlFile, holder);
        }

        // The source type of the expression must be assignable to the property it is assigned to.
        if (allResolved && contextTag != null
                && attrVal.getParent() instanceof XmlAttribute targetAttr
                && !Fxml2XmlUtil.isNonPropertyAttribute(targetAttr.getName())) {
            // The value content only, excluding the surrounding quotes.
            TextRange inner = new TextRange(
                    attrVal.getTextRange().getStartOffset() + 1,
                    attrVal.getTextRange().getEndOffset() - 1);
            annotateBindingSourceType(
                    segments, expr.kind(),
                    Fxml2AttributeValueResolver.attributeTargetType(targetAttr, xmlFile),
                    inner, holder);
        }
    }

    /**
     * Reports a binding source type that cannot be assigned to the type the expression supplies
     * its value to, which is the property for an expression that spans the whole attribute value
     * and the item type for an expression that is one item of a value sequence.
     *
     * <p>Only a single-segment evaluate path participates: a longer path loses its type arguments
     * segment by segment, and the other binding kinds have assignment rules of their own, so
     * checking them here would report types the markup language accepts.
     *
     * @param segments   the resolved path segments, which all have to be resolved
     * @param targetType the type the expression supplies, or {@code null} when it is unknown
     * @param range      the document range to report on
     */
    private static void annotateBindingSourceType(
            @NotNull java.util.List<Fxml2BindingPathResolver.Segment> segments,
            @NotNull Kind kind,
            @Nullable PsiType targetType,
            @NotNull TextRange range,
            @NotNull AnnotationHolder holder) {

        if (kind != Kind.EVALUATE || segments.size() != 1 || targetType == null) return;
        PsiElement decl = segments.getFirst().declaration();
        if (decl == null || !decl.isValid()) return;

        // resolveDeclarationPsiType unwraps ObservableValue<T> to T and returns the parameter
        // type of a setter-resolved property rather than void.
        PsiType sourceType;
        try {
            sourceType = Fxml2AttributeValueResolver.resolveDeclarationPsiType(decl);
        } catch (PsiInvalidElementAccessException ignored) {
            return;
        }
        if (sourceType == null) return;
        if (Fxml2AttributeValueResolver.acceptsSourceType(targetType, sourceType)) return;
        if (Fxml2AttributeValueResolver.containsUnresolvedTypeParameter(targetType)) return;

        holder.newAnnotation(HighlightSeverity.ERROR,
                        "Incompatible types: '" + sourceType.getPresentableText()
                        + "' cannot be assigned to '" + targetType.getPresentableText() + "'")
                .range(range)
                .create();
    }

    private static void annotateValueError(
            @NotNull XmlAttributeValue attributeValue,
            @NotNull AnnotationHolder holder,
            @NotNull String message,
            @NotNull TextRange relativeRange) {
        TextRange valueRange = attributeValue.getTextRange();
        int contentStart = valueRange.getStartOffset() + 1;
        int contentEnd = valueRange.getEndOffset() - 1;
        int start = Math.min(contentStart + relativeRange.getStartOffset(), contentEnd);
        if (start >= contentEnd) {
            holder.newAnnotation(HighlightSeverity.ERROR, message)
                    .range(attributeValue)
                    .create();
            return;
        }

        int end = contentStart + relativeRange.getEndOffset();
        holder.newAnnotation(HighlightSeverity.ERROR, message)
                .range(new TextRange(start, Math.clamp(end, start + 1, contentEnd)))
                .create();
    }
}
