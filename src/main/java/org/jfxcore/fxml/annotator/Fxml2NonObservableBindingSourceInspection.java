package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.BatchQuickFix;
import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiInvalidElementAccessException;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.actions.ConvertBindingNotationIntention;
import org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser.ParsedExpression;
import org.jfxcore.fxml.resolve.Fxml2BindingPathResolver;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;
import org.jfxcore.fxml.resolve.Fxml2WellKnownClasses;
import org.jfxcore.fxml.resolve.Fxml2XmlUtil;

import java.util.List;
import java.util.Map;

/**
 * Reports FXML {@code ${source}} (Observe) and {@code #{source}} (Synchronize) binding expressions
 * where <em>no segment</em> in the binding path chain has an observable declaration type
 * ({@code javafx.beans.value.ObservableValue}).
 *
 * <p>When every segment in the path is a plain field or Java-Beans getter (neither returns an
 * {@code ObservableValue}), the FXML compiler rejects the binding with
 * {@code INVALID_UNIDIRECTIONAL_BINDING_SOURCE} or {@code INVALID_BIDIRECTIONAL_BINDING_SOURCE}.
 * A quick-fix offers to simplify the expression to a one-way {@code $source} (Evaluate) binding.
 *
 * <p>Note: if <em>any</em> segment in the chain does return an {@code ObservableValue} (e.g. the
 * first segment is an {@code ObjectProperty<ViewModel>} field), the binding is trackable and this
 * inspection does not fire, the compiler accepts such paths correctly.
 */
public final class Fxml2NonObservableBindingSourceInspection extends LocalInspectionTool {

    /** fx:* element-notation binding tags and their corresponding binding kinds. */
    private static final Map<String, Kind> FX_OBSERVE_SYNC_KINDS = Map.of(
            "Observe",     Kind.OBSERVE,
            "Push",        Kind.PUSH,
            "Synchronize", Kind.SYNCHRONIZE);

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new PsiElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (!(element.getContainingFile() instanceof XmlFile xmlFile)) return;
                if (!Fxml2FileType.isFxml2(xmlFile)) return;
                if (!(element instanceof XmlAttributeValue attrVal)) return;

                // Skip non-property attribute values (fx:id, fx:subclass, etc.)
                if (attrVal.getParent() instanceof XmlAttribute parentAttr) {
                    if (Fxml2XmlUtil.isNonPropertyAttribute(parentAttr.getName())) return;
                }

                // Dispatch to the appropriate checker based on usage form.
                if (isElementNotationPath(attrVal)) {
                    checkElementNotationPath(attrVal, xmlFile, holder);
                } else {
                    checkShortFormBinding(attrVal, xmlFile, holder);
                }
            }
        };
    }

    // -----------------------------------------------------------------------
    // Element-notation source check  (<fx:Observe source="..."/> / <fx:Push source="..."/> / <fx:Synchronize source="..."/>)
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when {@code attrVal} is the value of a {@code source=} attribute
     * on an {@code <fx:Observe>}, {@code <fx:Push>}, or {@code <fx:Synchronize>}
     * element-notation tag.
     */
    private static boolean isElementNotationPath(@NotNull XmlAttributeValue attrVal) {
        if (!(attrVal.getParent() instanceof XmlAttribute pathAttr)) return false;
        if (!"source".equals(pathAttr.getName())) return false;
        if (!(pathAttr.getParent() instanceof XmlTag fxTag)) return false;
        return Fxml2ImportResolver.FXML2_NAMESPACE.equals(fxTag.getNamespace())
                && FX_OBSERVE_SYNC_KINDS.containsKey(fxTag.getLocalName());
    }

    private static void checkElementNotationPath(
            @NotNull XmlAttributeValue attrVal,
            @NotNull XmlFile xmlFile,
            @NotNull ProblemsHolder holder) {

        XmlTag fxTag = ((XmlAttribute) attrVal.getParent()).getParent();
        Kind kind = FX_OBSERVE_SYNC_KINDS.get(fxTag.getLocalName());
        if (kind == null) return;

        String rawPath = attrVal.getValue();
        if (rawPath.isBlank()) return;

        // Resolve start class (handles self/, parent/, etc.)
        PsiClass startClass = Fxml2BindingPathResolver.resolveStartClass(null, fxTag, xmlFile);
        if (startClass == null) return;

        // Parse context selector
        Fxml2BindingExpressionParser.ContextSelector selector =
                Fxml2BindingExpressionParser.parseContextSelector(rawPath);
        String remainingPath = selector != null ? selector.remainingPath() : rawPath;
        if (remainingPath.isBlank()) return;

        if (selector != null) {
            startClass = Fxml2BindingPathResolver.resolveStartClass(selector, fxTag, xmlFile);
            if (startClass == null) return;
        }

        // Handle ".." content prefix
        if (remainingPath.startsWith("..")) {
            kind = switch (kind) {
                case OBSERVE     -> Kind.OBSERVE_CONTENT;
                case PUSH        -> Kind.PUSH_CONTENT;
                case SYNCHRONIZE -> Kind.SYNCHRONIZE_CONTENT;
                default          -> kind;
            };
            remainingPath = remainingPath.substring(2).trim();
            if (remainingPath.isBlank()) return;
        }

        GlobalSearchScope scope = xmlFile.getResolveScope();
        List<Fxml2BindingPathResolver.Segment> segments =
                Fxml2BindingPathResolver.resolve(remainingPath, startClass, scope, kind, xmlFile);

        if (segments.isEmpty()) return;
        // Only fire when ALL segments resolved (otherwise annotator already showed an error)
        boolean allResolved = segments.stream().allMatch(Fxml2BindingPathResolver.Segment::isResolved);
        if (!allResolved) return;

        if (lacksObservableSegment(segments, xmlFile.getProject())) {
            String msg = remainingPath + " is not a valid binding source, required javafx.beans.value.ObservableValue";
            holder.registerProblem(attrVal, msg, ProblemHighlightType.GENERIC_ERROR,
                    new RenameElementTagToEvaluateFix(kind));
        } else if (kind == Kind.SYNCHRONIZE) {
            Fxml2BindingPathResolver.Segment last = segments.getLast();
            if (last.isResolved() && lacksWritablePropertyDeclaration(last, xmlFile.getProject())) {
                String msg = remainingPath + " is not a valid bidirectional binding source,"
                        + " required javafx.beans.property.Property";
                holder.registerProblem(attrVal, msg, ProblemHighlightType.GENERIC_ERROR,
                        new RenameElementTagToObserveFix());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Short-form binding check  (${source} / >{source} / #{source})
    // -----------------------------------------------------------------------

    private static void checkShortFormBinding(
            @NotNull XmlAttributeValue attrVal,
            @NotNull XmlFile xmlFile,
            @NotNull ProblemsHolder holder) {

        String rawValue = attrVal.getValue();
        if (rawValue.isBlank()) return;

        ParsedExpression expr = Fxml2BindingExpressionParser.parseExpression(rawValue);
        if (expr == null) return;

        // Only check OBSERVE / PUSH / SYNCHRONIZE kinds (and their content variants)
        Kind kind = expr.kind();
        if (kind != Kind.OBSERVE && kind != Kind.PUSH && kind != Kind.SYNCHRONIZE
                && kind != Kind.OBSERVE_CONTENT && kind != Kind.PUSH_CONTENT && kind != Kind.SYNCHRONIZE_CONTENT) {
            return;
        }

        String strippedPath = expr.strippedPath();

        // Parse context selector
        Fxml2BindingExpressionParser.ContextSelector selector =
                Fxml2BindingExpressionParser.parseContextSelector(strippedPath);
        String path = selector != null ? selector.remainingPath() : strippedPath;
        if (path.isBlank()) return;

        // Resolve start class
        XmlTag contextTag = attrVal.getParent() instanceof XmlAttribute attr
                ? attr.getParent() : null;
        PsiClass startClass;
        if (selector != null && contextTag != null) {
            startClass = Fxml2BindingPathResolver.resolveStartClass(selector, contextTag, xmlFile);
        } else {
            startClass = Fxml2BindingPathResolver.resolveCodeBehindClass(xmlFile);
        }
        if (startClass == null) return;

        // Skip function-call paths (they are handled separately)
        if (path.contains("(")) return;

        GlobalSearchScope scope = xmlFile.getResolveScope();
        List<Fxml2BindingPathResolver.Segment> segments =
                Fxml2BindingPathResolver.resolve(path, startClass, scope, kind, xmlFile);

        if (segments.isEmpty()) return;
        // Only fire when ALL segments resolved (otherwise annotator already showed an error)
        boolean allResolved = segments.stream().allMatch(Fxml2BindingPathResolver.Segment::isResolved);
        if (!allResolved) return;

        if (lacksObservableSegment(segments, xmlFile.getProject())) {
            String msg = path + " is not a valid binding source, required javafx.beans.value.ObservableValue";
            holder.registerProblem(attrVal, msg, ProblemHighlightType.GENERIC_ERROR,
                    new ConvertToEvaluateBindingFix());
        } else if (kind == Kind.SYNCHRONIZE) {
            Fxml2BindingPathResolver.Segment last = segments.getLast();
            if (last.isResolved() && lacksWritablePropertyDeclaration(last, xmlFile.getProject())) {
                String msg = path + " is not a valid bidirectional binding source,"
                        + " required javafx.beans.property.Property";
                holder.registerProblem(attrVal, msg, ProblemHighlightType.GENERIC_ERROR,
                        new ConvertToObserveBindingFix());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Observability helper
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when no segment in {@code segments} contributes an observable
     * dependency, meaning the binding path cannot be tracked for changes at runtime.
     *
     * <p>A segment contributes a dependency when its declaration type is either:
     * <ul>
     *   <li>a subtype of {@code javafx.beans.value.ObservableValue} (value-based dependency), or</li>
     *   <li>a subtype of {@code javafx.collections.ObservableList},
     *       {@code javafx.collections.ObservableSet}, or {@code javafx.collections.ObservableMap}
     *       (content-based dependency).</li>
     * </ul>
     *
     * <p>This mirrors the FXML compiler's {@code hasObservableDependency()} check:
     * observable collections count as content-based dependency providers. If an
     * intermediate segment is an {@code ObservableList} (for example), the binding is
     * re-evaluated whenever the collection content changes, even though {@code ObservableList}
     * is not an {@code ObservableValue}.
     */
    private static boolean lacksObservableSegment(
            @NotNull List<Fxml2BindingPathResolver.Segment> segments,
            @NotNull Project project) {

        PsiClass observableValueClass = Fxml2WellKnownClasses.observableValue(project);
        if (observableValueClass == null) return false;

        PsiClass observableListClass = Fxml2WellKnownClasses.observableList(project);
        PsiClass observableSetClass  = Fxml2WellKnownClasses.observableSet(project);
        PsiClass observableMapClass  = Fxml2WellKnownClasses.observableMap(project);

        for (Fxml2BindingPathResolver.Segment seg : segments) {
            PsiElement decl = seg.declaration();
            if (decl == null || !decl.isValid()) continue;
            PsiType declType = switch (decl) {
                case PsiField  f -> f.getType();
                case PsiMethod m -> m.getReturnType();
                default          -> null;
            };
            if (declType instanceof PsiClassType ct) {
                PsiClass resolved;
                try {
                    resolved = ct.resolve();
                } catch (PsiInvalidElementAccessException ignored) {
                    continue;
                }
                if (resolved != null) {
                    if (InheritanceUtil.isInheritorOrSelf(resolved, observableValueClass, true)) return false;
                    if (InheritanceUtil.isInheritorOrSelf(resolved, observableListClass, true)) return false;
                    if (InheritanceUtil.isInheritorOrSelf(resolved, observableSetClass, true)) return false;
                    if (InheritanceUtil.isInheritorOrSelf(resolved, observableMapClass, true)) return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns {@code true} when the declaration type of {@code segment} is not a subtype of
     * {@code javafx.beans.property.Property}, meaning the value cannot be written back by a
     * bidirectional binding.
     *
     * <p>Observable types that do not implement {@code Property}, such as
     * {@code ReadOnlyStringProperty} or any plain {@code ObservableValue}, satisfy the
     * observability requirement but not the writability requirement. The FXML compiler
     * rejects such types as bidirectional binding sources with
     * {@code INVALID_BIDIRECTIONAL_BINDING_SOURCE}.
     *
     * <p>Returns {@code false} (no error) when the declaration type cannot be determined
     * or when JavaFX is absent from the classpath, so the check is silently suppressed
     * in those edge cases.
     */
    private static boolean lacksWritablePropertyDeclaration(
            @NotNull Fxml2BindingPathResolver.Segment segment,
            @NotNull Project project) {
        PsiElement decl = segment.declaration();
        if (decl == null || !decl.isValid()) return false;
        PsiClass propertyClass = Fxml2WellKnownClasses.property(project);
        if (propertyClass == null) return false;

        PsiType declType = switch (decl) {
            case PsiField  f -> f.getType();
            case PsiMethod m -> m.getReturnType();
            default          -> null;
        };
        if (!(declType instanceof PsiClassType ct)) return false;
        PsiClass resolved;
        try {
            resolved = ct.resolve();
        } catch (PsiInvalidElementAccessException ignored) {
            return false;
        }
        return resolved == null || !InheritanceUtil.isInheritorOrSelf(resolved, propertyClass, true);
    }

    // -----------------------------------------------------------------------
    // Quick-fixes
    // -----------------------------------------------------------------------

    /**
     * Quick-fix for short-form bindings: converts a non-evaluate binding expression
     * (e.g. {@code ${source}}, {@code >{source}}, or {@code #{source}}) to the corresponding
     * evaluate form {@code $source}.
     */
    static final class ConvertToEvaluateBindingFix implements LocalQuickFix, BatchQuickFix {

        @Override
        public @NotNull String getName() { return "Use evaluate binding ($source)"; }

        @Override
        public @NotNull String getFamilyName() { return getName(); }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement elem = descriptor.getPsiElement();
            if (!(elem instanceof XmlAttributeValue attrVal)) return;
            if (!(attrVal.getParent() instanceof XmlAttribute attr)) return;

            ParsedExpression expr = Fxml2BindingExpressionParser.parseExpression(attrVal.getValue());
            if (expr == null) return;

            Kind newKind = toEvaluateKind(expr.kind());
            if (newKind == null) return;

            boolean isCompact = ConvertBindingNotationIntention.isCompactNotation(expr);
            String newValue = isCompact
                    ? ConvertBindingNotationIntention.toShortForm(newKind, expr.path())
                    : ConvertBindingNotationIntention.toLongForm(newKind, expr.path());
            attr.setValue(newValue);
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

    /**
     * Quick-fix for element-notation bindings: renames the enclosing
     * {@code <fx:Observe>} / {@code <fx:Synchronize>} tag to {@code <fx:Evaluate>}.
     */
    static final class RenameElementTagToEvaluateFix implements LocalQuickFix, BatchQuickFix {

        private final Kind kind;

        RenameElementTagToEvaluateFix(@NotNull Kind kind) {
            this.kind = kind;
        }

        @Override
        public @NotNull String getName() {
            String from = kindToElementName(kind);
            return "Change <fx:" + from + "> to <fx:Evaluate>";
        }

        @Override
        public @NotNull String getFamilyName() { return "Use evaluate binding"; }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement elem = descriptor.getPsiElement();
            // descriptor points to the source= attribute value; navigate to its parent tag
            if (!(elem instanceof XmlAttributeValue attrVal)) return;
            if (!(attrVal.getParent() instanceof XmlAttribute pathAttr)) return;
            if (!(pathAttr.getParent() instanceof XmlTag fxTag)) return;
            if (!FX_OBSERVE_SYNC_KINDS.containsKey(fxTag.getLocalName())) return;

            // Use the PSI rename to change the tag name.
            // XmlTag.setName() handles both the open and close tag.
            // Preserve the existing namespace prefix (usually "fx").
            String nsPrefix = fxTag.getNamespacePrefix();
            String newTagName = nsPrefix.isEmpty() ? "Evaluate" : nsPrefix + ":Evaluate";
            try {
                fxTag.setName(newTagName);
            } catch (com.intellij.util.IncorrectOperationException e) {
                // Ignore: tag rename failed with no change applied
            }
        }

        private static @NotNull String kindToElementName(@NotNull Kind kind) {
            return switch (kind) {
                case OBSERVE, OBSERVE_CONTENT         -> "Observe";
                case PUSH, PUSH_CONTENT               -> "Push";
                case SYNCHRONIZE, SYNCHRONIZE_CONTENT -> "Synchronize";
                default                               -> "Observe";
            };
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

    /**
     * Quick-fix for short-form SYNCHRONIZE bindings where the terminal property is read-only:
     * converts {@code #{source}} to {@code ${source}} (Observe / one-way binding).
     */
    static final class ConvertToObserveBindingFix implements LocalQuickFix, BatchQuickFix {

        @Override
        public @NotNull String getName() { return "Use observe binding (${source})"; }

        @Override
        public @NotNull String getFamilyName() { return getName(); }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement elem = descriptor.getPsiElement();
            if (!(elem instanceof XmlAttributeValue attrVal)) return;
            if (!(attrVal.getParent() instanceof XmlAttribute attr)) return;

            ParsedExpression expr = Fxml2BindingExpressionParser.parseExpression(attrVal.getValue());
            if (expr == null) return;

            Kind newKind = toObserveKind(expr.kind());
            if (newKind == null) return;

            boolean isCompact = ConvertBindingNotationIntention.isCompactNotation(expr);
            String newValue = isCompact
                    ? ConvertBindingNotationIntention.toShortForm(newKind, expr.path())
                    : ConvertBindingNotationIntention.toLongForm(newKind, expr.path());
            attr.setValue(newValue);
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

    /**
     * Quick-fix for element-notation SYNCHRONIZE bindings where the terminal property is
     * read-only: renames the enclosing {@code <fx:Synchronize>} tag to {@code <fx:Observe>}.
     */
    static final class RenameElementTagToObserveFix implements LocalQuickFix, BatchQuickFix {

        @Override
        public @NotNull String getName() { return "Change <fx:Synchronize> to <fx:Observe>"; }

        @Override
        public @NotNull String getFamilyName() { return getName(); }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement elem = descriptor.getPsiElement();
            if (!(elem instanceof XmlAttributeValue attrVal)) return;
            if (!(attrVal.getParent() instanceof XmlAttribute pathAttr)) return;
            if (!(pathAttr.getParent() instanceof XmlTag fxTag)) return;
            if (!"Synchronize".equals(fxTag.getLocalName())) return;

            String nsPrefix = fxTag.getNamespacePrefix();
            String newTagName = nsPrefix.isEmpty() ? "Observe" : nsPrefix + ":Observe";
            try {
                fxTag.setName(newTagName);
            } catch (com.intellij.util.IncorrectOperationException e) {
                // Ignore: tag rename failed with no change applied
            }
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

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Maps an OBSERVE/PUSH/SYNCHRONIZE kind to the corresponding EVALUATE kind. */
    private static @Nullable Kind toEvaluateKind(@NotNull Kind kind) {
        return switch (kind) {
            case OBSERVE, PUSH, SYNCHRONIZE                        -> Kind.EVALUATE;
            case OBSERVE_CONTENT, PUSH_CONTENT, SYNCHRONIZE_CONTENT -> Kind.EVALUATE_CONTENT;
            default                                                -> null;
        };
    }

    /** Maps a SYNCHRONIZE kind to the corresponding OBSERVE kind. */
    private static @Nullable Kind toObserveKind(@NotNull Kind kind) {
        return switch (kind) {
            case SYNCHRONIZE         -> Kind.OBSERVE;
            case SYNCHRONIZE_CONTENT -> Kind.OBSERVE_CONTENT;
            default                  -> null;
        };
    }
}
