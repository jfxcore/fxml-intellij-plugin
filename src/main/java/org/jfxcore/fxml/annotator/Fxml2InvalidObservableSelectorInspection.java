package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.BatchQuickFix;
import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.XmlSuppressableInspectionTool;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiInvalidElementAccessException;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser.ContextSelector;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser.MarkupExtensionExpression;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser.ParsedExpression;
import org.jfxcore.fxml.resolve.Fxml2BindingPathResolver;
import org.jfxcore.fxml.resolve.Fxml2BindingPathResolver.Segment;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;
import org.jfxcore.fxml.resolve.Fxml2WellKnownClasses;
import org.jfxcore.fxml.resolve.Fxml2XmlUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports binding path segments accessed via {@code ::} whose declared type is not an
 * {@code ObservableValue} subtype, and provides a batch-applicable quick-fix to replace
 * {@code ::} with {@code .}.
 *
 * <p>The fxml2 compiler rejects such references with {@code INVALID_INVARIANT_REFERENCE}.
 *
 * <p>This inspection complements {@link Fxml2AttributeAnnotator}: the annotator shows an
 * {@code ERROR} underline in real-time, while this inspection exposes the same
 * {@code Replace '::' with '.'} fix as a {@link LocalQuickFix} + {@link BatchQuickFix} so that
 * "Fix all in file" and "Fix all in project" are available from the Alt+Enter menu.
 *
 * <p>Reported at {@link ProblemHighlightType#INFORMATION} (no visible underline) to avoid
 * double-highlighting since the annotator already shows the {@code ERROR}.
 */
public final class Fxml2InvalidObservableSelectorInspection extends XmlSuppressableInspectionTool {

    @Override
    public @NotNull String getShortName() { return "Fxml2InvalidObservableSelector"; }

    private static final Map<String, Kind> FX_ELEMENT_KINDS = Map.of(
            "Evaluate",    Kind.EVALUATE,
            "Observe",     Kind.OBSERVE,
            "Push",        Kind.PUSH,
            "Synchronize", Kind.SYNCHRONIZE);

    @Override
    public @NotNull String getDisplayName() { return "Invalid observable-selection operator"; }

    @Override
    public ProblemDescriptor @Nullable [] checkFile(
            @NotNull PsiFile file, @NotNull InspectionManager manager, boolean isOnTheFly) {
        if (!Fxml2FileType.isFxml2(file)) return null;
        XmlFile xmlFile = (XmlFile) file;

        List<ProblemDescriptor> problems = new ArrayList<>();
        for (XmlAttributeValue attrVal : PsiTreeUtil.findChildrenOfType(xmlFile, XmlAttributeValue.class)) {
            checkAttributeValue(attrVal, xmlFile, manager, isOnTheFly, problems);
        }
        return problems.isEmpty() ? null : problems.toArray(ProblemDescriptor.EMPTY_ARRAY);
    }

    private static void checkAttributeValue(
            @NotNull XmlAttributeValue attrVal,
            @NotNull XmlFile xmlFile,
            @NotNull InspectionManager manager,
            boolean isOnTheFly,
            @NotNull List<ProblemDescriptor> problems) {

        if (!(attrVal.getParent() instanceof XmlAttribute attr)) return;
        String rawValue = attrVal.getValue();
        if (rawValue.isBlank()) return;

        // Element notation source path: <fx:Evaluate source="path::member"/>
        if ("source".equals(attr.getName())
                && attr.getParent() instanceof XmlTag fxTag
                && Fxml2ImportResolver.FXML2_NAMESPACE.equals(fxTag.getNamespace())
                && FX_ELEMENT_KINDS.containsKey(fxTag.getLocalName())) {
            checkElementNotationPath(attrVal, rawValue, fxTag, xmlFile, manager, isOnTheFly, problems);
            return;
        }

        if (Fxml2XmlUtil.isNonPropertyAttribute(attr.getName())) return;

        Object parsed = Fxml2BindingExpressionParser.parse(rawValue,
                Fxml2ImportResolver.parsePrefixMappings(xmlFile));
        if (parsed instanceof ParsedExpression expr) {
            checkMainBindingPath(attrVal, expr, xmlFile, manager, isOnTheFly, problems);
        } else if (parsed instanceof MarkupExtensionExpression) {
            checkMarkupExtensionBindingArgs(attrVal, rawValue, xmlFile, manager, isOnTheFly, problems);
        }
    }

    private static void checkElementNotationPath(
            @NotNull XmlAttributeValue attrVal,
            @NotNull String path,
            @NotNull XmlTag fxTag,
            @NotNull XmlFile xmlFile,
            @NotNull InspectionManager manager,
            boolean isOnTheFly,
            @NotNull List<ProblemDescriptor> problems) {

        PsiClass startClass = Fxml2BindingPathResolver.resolveStartClass(null, fxTag, xmlFile);
        if (startClass == null) return;

        ContextSelector selector = Fxml2BindingExpressionParser.parseContextSelector(path);
        String remainingPath = selector != null ? selector.remainingPath() : path;
        if (remainingPath.isBlank()) return;

        if (selector != null) {
            startClass = Fxml2BindingPathResolver.resolveStartClass(selector, fxTag, xmlFile);
            if (startClass == null) return;
        }

        int dotDotOffset = 0;
        if (remainingPath.startsWith("..")) {
            dotDotOffset = 2;
            remainingPath = remainingPath.substring(2).trim();
            if (remainingPath.isBlank()) return;
        }

        // Skip function-call paths (not applicable for :: detection)
        for (int pi = 0; pi < remainingPath.length(); pi++) {
            if (remainingPath.charAt(pi) == '(' && (pi == 0 || remainingPath.charAt(pi - 1) != '.')) return;
        }

        int selectorOffset = selector != null ? selector.selectorLength() : 0;
        Kind kind = FX_ELEMENT_KINDS.getOrDefault(fxTag.getLocalName(), Kind.EVALUATE);
        GlobalSearchScope scope = xmlFile.getResolveScope();
        List<Segment> segments = Fxml2BindingPathResolver.resolve(remainingPath, startClass, scope, kind, xmlFile);

        PsiClass prevType = startClass;
        for (Segment seg : segments) {
            if (seg.isResolved() && seg.observableSelector() && !seg.classQualifier()
                    && isNotObservableDeclaration(seg.declaration(), xmlFile)) {
                // selectorOffset + dotDotOffset + seg.pathOffset() = position of segment start
                // in the decoded value string; '::' immediately precedes it.
                int valueOffset = selectorOffset + dotDotOffset + seg.pathOffset() - 2;
                addProblem(attrVal, valueOffset, seg.name(), prevType, manager, isOnTheFly, problems);
            }
            prevType = seg.isResolved() ? seg.resultType() : null;
        }
    }

    private static void checkMainBindingPath(
            @NotNull XmlAttributeValue attrVal,
            @NotNull ParsedExpression expr,
            @NotNull XmlFile xmlFile,
            @NotNull InspectionManager manager,
            boolean isOnTheFly,
            @NotNull List<ProblemDescriptor> problems) {

        String strippedPath = expr.strippedPath();
        ContextSelector selector = Fxml2BindingExpressionParser.parseContextSelector(strippedPath);
        String path = selector != null ? selector.remainingPath() : strippedPath;
        if (path.isBlank()) return;

        XmlTag contextTag = null;
        if (attrVal.getParent() instanceof XmlAttribute attr
                && attr.getParent() instanceof XmlTag t) {
            contextTag = t;
        }

        PsiClass startClass = contextTag != null
                ? Fxml2BindingPathResolver.resolveStartClass(selector, contextTag, xmlFile)
                : Fxml2BindingPathResolver.resolveCodeBehindClass(xmlFile);
        if (startClass == null) return;

        // Skip function-call paths
        for (int pi = 0; pi < path.length(); pi++) {
            if (path.charAt(pi) == '(' && (pi == 0 || path.charAt(pi - 1) != '.')) return;
        }

        GlobalSearchScope scope = xmlFile.getResolveScope();
        List<Segment> segments = Fxml2BindingPathResolver.resolve(path, startClass, scope, expr.kind(), xmlFile);

        int selectorLength = selector != null ? selector.selectorLength() : 0;

        PsiClass prevType = startClass;
        for (Segment seg : segments) {
            if (seg.isResolved() && seg.observableSelector() && !seg.classQualifier()
                    && isNotObservableDeclaration(seg.declaration(), xmlFile)) {
                // valueOffset = position of '::' in attrVal.getValue()
                // = expr.strippedPathOffset() + selectorLength + seg.pathOffset() - 2
                // Derivation: docStart = attrDocBase + (1 + expr.strippedPathOffset() + selectorLength) + seg.pathOffset()
                //             selectorDocOffset = docStart - 2
                //             valueOffset = selectorDocOffset - attrDocBase - 1 = base + seg.pathOffset() - 3
                //                         = 1 + strippedPathOffset + selectorLength + seg.pathOffset() - 3
                //                         = strippedPathOffset + selectorLength + seg.pathOffset() - 2
                int valueOffset = expr.strippedPathOffset() + selectorLength + seg.pathOffset() - 2;
                addProblem(attrVal, valueOffset, seg.name(), prevType, manager, isOnTheFly, problems);
            }
            prevType = seg.isResolved() ? seg.resultType() : null;
        }
    }

    private static void checkMarkupExtensionBindingArgs(
            @NotNull XmlAttributeValue attrVal,
            @NotNull String rawValue,
            @NotNull XmlFile xmlFile,
            @NotNull InspectionManager manager,
            boolean isOnTheFly,
            @NotNull List<ProblemDescriptor> problems) {

        if (rawValue.length() <= 2) return;
        String inner = rawValue.substring(1, rawValue.length() - 1).trim();
        int firstSpace = indexOfWhitespaceME(inner);
        if (firstSpace < 0) return;
        String paramsPart = inner.substring(firstSpace).trim();
        int paramsPartInRaw = rawValue.indexOf(paramsPart, 1 + firstSpace);
        if (paramsPartInRaw < 0) return;

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
        int pos = 0;
        while (pos < paramsPart.length()) {
            char ch = paramsPart.charAt(pos);
            if (Character.isWhitespace(ch) || ch == ',' || ch == ';') { pos++; continue; }
            if ((ch == '$' || ch == '#') && pos + 1 < paramsPart.length()) {
                int sigEnd = pos + 1;
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
                    pathEnd = scan - 1;
                } else {
                    pathStart = sigEnd;
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
                    List<Segment> segments = Fxml2BindingPathResolver.resolve(
                            path, startClass, scope, Kind.EVALUATE, xmlFile);
                    PsiClass prevType = startClass;
                    for (Segment seg : segments) {
                        if (seg.isResolved() && seg.observableSelector() && !seg.classQualifier()
                                && isNotObservableDeclaration(seg.declaration(), xmlFile)) {
                            // valueOffset = paramsPartInRaw + pathStart + seg.pathOffset() - 2
                            // Derivation: docParamsBase = attrStart + 1 + paramsPartInRaw
                            //             docPathBase = docParamsBase + pathStart
                            //             segDocStart = docPathBase + seg.pathOffset()
                            //             selectorDocOffset = segDocStart - 2
                            //             valueOffset = selectorDocOffset - attrStart - 1
                            //                         = paramsPartInRaw + pathStart + seg.pathOffset() - 2
                            int valueOffset = paramsPartInRaw + pathStart + seg.pathOffset() - 2;
                            addProblem(attrVal, valueOffset, seg.name(), prevType, manager, isOnTheFly, problems);
                        }
                        prevType = seg.isResolved() ? seg.resultType() : null;
                    }
                }
                pos = hasBrace ? pathEnd + 1 : pathEnd;
                continue;
            }
            pos++;
        }
    }

    private static void addProblem(
            @NotNull XmlAttributeValue attrVal,
            int valueOffset,
            @NotNull String segName,
            @Nullable PsiClass prevType,
            @NotNull InspectionManager manager,
            boolean isOnTheFly,
            @NotNull List<ProblemDescriptor> problems) {

        if (valueOffset < 0 || valueOffset + 2 + segName.length() > attrVal.getValue().length()) return;
        // Range within attrVal.getText() (which starts with the opening quote): +1 to skip it.
        // The segment name starts 2 chars after valueOffset (which points to '::').
        // Anchoring on the name matches the annotator's error range so Alt+Enter shows the fix.
        TextRange elemRange = TextRange.create(1 + valueOffset + 2, 1 + valueOffset + 2 + segName.length());
        // INFORMATION is forbidden in batch mode (isOnTheFly=false): InspectionEngine rejects it.
        // Use INFORMATION only in on-the-fly mode to avoid double-highlighting with the annotator.
        ProblemHighlightType highlightType = isOnTheFly
                ? ProblemHighlightType.INFORMATION
                : ProblemHighlightType.WEAK_WARNING;
        String ownerName = prevType != null ? prevType.getQualifiedName() : "?";
        problems.add(manager.createProblemDescriptor(
                attrVal, elemRange,
                "'::' is invalid: '" + segName + "' in " + ownerName + " is not an ObservableValue",
                highlightType,
                isOnTheFly,
                new ReplaceObservableSelectorInspectionFix(valueOffset)));
    }

    /** Finds the index of the first whitespace character outside {@code <...>} angle brackets. */
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

    private static boolean isNotObservableDeclaration(@Nullable PsiElement decl, @NotNull XmlFile xmlFile) {
        if (decl == null || !decl.isValid()) return true;
        PsiClass observableClass = Fxml2WellKnownClasses.observableValue(xmlFile.getProject());
        if (observableClass == null) return true;
        com.intellij.psi.PsiType type = switch (decl) {
            case com.intellij.psi.PsiField f -> f.getType();
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

    // For injected FXML2: redirect the preview-copy to the host Java file so that
    // QuickFixWrapper routes to our generatePreview override instead of crashing in
    // ProblemDescriptor.getDescriptorForPreview when it can't find the injected XmlAttributeValue
    // in the copy (injected PSI trees have no parent beyond the injected file boundary).
    private static @NotNull PsiElement writableElementFor(@NotNull PsiFile file) {
        InjectedLanguageManager ilm = InjectedLanguageManager.getInstance(file.getProject());
        if (ilm.isInjectedFragment(file)) {
            PsiElement host = file.getContext();
            if (host != null) return host;
        }
        return file;
    }

    /**
     * Replaces the {@code ::} observable-selection operator with {@code .} at a specific
     * position in the binding path.  Implements {@link BatchQuickFix} so that "Fix all in
     * file" / "Fix all in project" are available from the Alt+Enter menu.
     */
    static final class ReplaceObservableSelectorInspectionFix implements LocalQuickFix, BatchQuickFix {

        private final int valueOffset;

        ReplaceObservableSelectorInspectionFix(int valueOffset) {
            this.valueOffset = valueOffset;
        }

        @Override public @NotNull String getName() { return "Replace '::' with '.'"; }
        @Override public @NotNull String getFamilyName() { return "Replace observable-selection operator"; }

        @Override
        public @NotNull PsiElement getElementToMakeWritable(@NotNull PsiFile file) {
            return writableElementFor(file);
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project, @NotNull ProblemDescriptor previewDescriptor) {
            return IntentionPreviewInfo.EMPTY;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement elem = descriptor.getPsiElement();
            if (!(elem instanceof XmlAttributeValue attrVal)) return;
            if (!(attrVal.getParent() instanceof XmlAttribute attr)) return;
            String value = attrVal.getValue();
            if (valueOffset < 0 || valueOffset + 2 > value.length()) return;
            if (value.charAt(valueOffset) != ':' || value.charAt(valueOffset + 1) != ':') return;
            attr.setValue(value.substring(0, valueOffset) + "." + value.substring(valueOffset + 2));
        }

        @Override
        public void applyFix(
                @NotNull Project project,
                CommonProblemDescriptor @NotNull [] descriptors,
                @NotNull List<PsiElement> psiElementsToIgnore,
                @Nullable Runnable refreshViews) {
            for (CommonProblemDescriptor descriptor : descriptors) {
                if (descriptor instanceof ProblemDescriptor pd) {
                    applyFix(project, pd);
                    PsiElement elem = pd.getPsiElement();
                    if (elem != null) psiElementsToIgnore.add(elem);
                }
            }
            if (refreshViews != null) refreshViews.run();
        }
    }
}
