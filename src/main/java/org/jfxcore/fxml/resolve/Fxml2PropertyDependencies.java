// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.resolve;

import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.descriptors.Fxml2PropertyTagDescriptor;
import org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Definite one-time dependencies between property assignments on an object. */
public final class Fxml2PropertyDependencies {
    public record Assignment(@NotNull PsiElement declaration, @NotNull PsiElement anchor,
                             @NotNull String name, @NotNull List<PsiElement> contents) {
        public Assignment { contents = List.copyOf(contents); }
        public Assignment(PsiElement declaration, PsiElement anchor, String name) {
            this(declaration, anchor, name, List.of(anchor));
        }
    }
    public record Cycle(@NotNull List<Assignment> assignments) {
        public Cycle { assignments = List.copyOf(assignments); }
    }

    private Fxml2PropertyDependencies() {}

    public static @NotNull List<Cycle> cycles(@NotNull XmlTag object, @NotNull XmlFile file) {
        var owner = Fxml2BindingPathResolver.resolveTagClass(object);
        if (owner == null) return List.of();
        Map<PsiElement, List<Assignment>> assignments = new LinkedHashMap<>();
        for (XmlAttribute attribute : object.getAttributes()) {
            if (Fxml2XmlUtil.isNonPropertyAttribute(attribute.getName()) || attribute.getValueElement() == null) continue;
            var declaration = Fxml2PropertyResolver.resolveInstanceProperty(owner, attribute.getLocalName());
            if (declaration != null) addAssignment(assignments,
                    new Assignment(declaration, attribute.getValueElement(), attribute.getName()));
        }
        for (XmlTag child : object.getSubTags()) {
            if (child.getDescriptor() instanceof Fxml2PropertyTagDescriptor descriptor
                    && !descriptor.isStatic() && descriptor.getDeclaration() != null) {
                addAssignment(assignments, new Assignment(descriptor.getDeclaration(), child, descriptor.getPropertyName()));
            }
        }
        var defaultProperty = Fxml2DefaultProperty.resolve(owner);
        if (defaultProperty != null) {
            var declaration = Fxml2PropertyResolver.resolveInstanceProperty(owner, defaultProperty.name());
            List<PsiElement> content = new ArrayList<>();
            for (XmlTag child : object.getSubTags()) {
                if (Fxml2BindingPathResolver.resolveTagClass(child) != null) content.add(child);
            }
            if (declaration != null && !content.isEmpty()) {
                addAssignment(assignments, new Assignment(declaration, content.getFirst(), defaultProperty.name(), content));
            }
        }
        Fxml2DependencyGraph<Assignment> graph = new Fxml2DependencyGraph<>();
        for (var targets : assignments.values()) {
            for (var target : targets) {
                for (var content : target.contents()) {
                    if (content instanceof XmlAttributeValue value) {
                        collectValue(target, value, object, object, file, assignments, graph);
                    } else if (content instanceof XmlTag property) {
                        for (XmlAttributeValue value : PsiTreeUtil.findChildrenOfType(property, XmlAttributeValue.class)) {
                            XmlTag valueTag = value.getParent() instanceof XmlAttribute attribute ? attribute.getParent() : property;
                            XmlTag context = Fxml2BindingPathResolver.resolveTagClass(valueTag) != null ? valueTag
                                    : Fxml2XmlUtil.findEnclosingClassTag(valueTag, file);
                            if (context != null) collectValue(target, value, context, object, file, assignments, graph);
                        }
                    }
                }
            }
        }
        return graph.cycles().stream().map(Cycle::new).toList();
    }

    private static void addAssignment(Map<PsiElement, List<Assignment>> assignments, Assignment assignment) {
        assignments.computeIfAbsent(assignment.declaration(), ignored -> new ArrayList<>()).add(assignment);
    }

    private static void collectValue(Assignment target, XmlAttributeValue value, XmlTag context, XmlTag object,
                                     XmlFile file, Map<PsiElement, List<Assignment>> assignments,
                                     Fxml2DependencyGraph<Assignment> graph) {
        Object parsed = Fxml2BindingExpressionParser.parse(value.getValue());
        if (value.getParent() instanceof XmlAttribute attribute && "source".equals(attribute.getName())) {
            String bindingTag = attribute.getParent().getName();
            if ("fx:Evaluate".equals(bindingTag)) {
                collectExpression(target, value.getValue(), context, object, file, assignments, graph);
            }
            return;
        }
        if (!(parsed instanceof Fxml2BindingExpressionParser.ParsedExpression binding)) return;
        if (binding.kind() == Kind.EVALUATE || binding.kind() == Kind.EVALUATE_CONTENT) {
            collectExpression(target, binding.path(), context, object, file, assignments, graph);
        }
        for (var parameter : binding.params()) {
            if ("converter".equals(parameter.name()) || "format".equals(parameter.name())) {
                collectExpression(target, parameter.path(), context, object, file, assignments, graph);
            }
        }
    }

    private static void collectExpression(Assignment target, String source, XmlTag context, XmlTag object,
                                          XmlFile file, Map<PsiElement, List<Assignment>> assignments,
                                          Fxml2DependencyGraph<Assignment> graph) {
        Fxml2ExpressionParser.Expression expression;
        try { expression = Fxml2ExpressionParser.parse(source); }
        catch (Fxml2ExpressionParser.ParseException ignored) { return; }
        for (var operand : Fxml2ExpressionOperands.operands(expression, 0)) {
            var selector = Fxml2BindingExpressionParser.parseContextSelector(operand.text());
            XmlTag selected;
            String path = operand.text();
            if (selector != null) {
                selected = selector.isRoot() ? Fxml2BindingPathResolver.effectiveRootTag(file)
                        : Fxml2BindingPathResolver.resolveContextSelectorTag(selector, context);
                path = selector.remainingPath();
            } else {
                selected = hasContext(context) ? null : Fxml2BindingPathResolver.effectiveRootTag(file);
            }
            if (selected != object) continue;
            String first = firstProperty(path, operand.kind());
            if (first == null) continue;
            var declaration = Fxml2PropertyResolver.resolveInstanceProperty(
                    Fxml2BindingPathResolver.resolveTagClass(object), first);
            var sources = assignments.get(declaration);
            if (sources != null) for (Assignment assignment : sources) graph.addDependency(target, assignment);
        }
    }

    private static boolean hasContext(XmlTag tag) {
        for (XmlTag current = tag; current != null; current = current.getParentTag()) {
            if (current.getAttribute("fx:context") != null) return true;
        }
        return false;
    }

    private static @Nullable String firstProperty(String path, Fxml2ExpressionOperands.OperandKind kind) {
        if (path.isEmpty() || path.startsWith("::")) return null;
        int end = 0;
        while (end < path.length() && Character.isJavaIdentifierPart(path.charAt(end))) end++;
        if (end == 0) return null;
        if (kind == Fxml2ExpressionOperands.OperandKind.FUNCTION_NAME && end == path.length()) return null;
        return path.substring(0, end);
    }
}
