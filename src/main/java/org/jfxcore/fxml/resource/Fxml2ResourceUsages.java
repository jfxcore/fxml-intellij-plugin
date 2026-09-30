// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.resource;

import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.lang.Fxml2ResourceNameReference;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;
import org.jfxcore.fxml.resolve.Fxml2MarkupExtensionContentParser;
import org.jfxcore.fxml.resolve.Fxml2ExpressionParser;
import java.util.Map;
import org.jfxcore.fxml.resolve.Fxml2TextSpan;
import org.jfxcore.fxml.resolve.Fxml2ValueSequenceParser;

/** Combines markup resource references with resource payload references. */
public final class Fxml2ResourceUsages {
    private static final String RESOURCE_CLASS = "org.jfxcore.markup.resource.ClassPathResource";

    private Fxml2ResourceUsages() {}

    public static boolean isUsed(@NotNull Fxml2ResourceEntry declaration, @NotNull XmlFile file) {
        var mappings = Fxml2ImportResolver.parsePrefixMappings(file);
        for (var value : PsiTreeUtil.findChildrenOfType(file, XmlAttributeValue.class)) {
            for (var reference : value.getReferences()) {
                if (reference instanceof Fxml2ResourceNameReference embedded
                        && embedded.isReferenceTo(new org.jfxcore.fxml.lang.Fxml2ResourceDeclarationElement(declaration))) {
                    return true;
                }
            }
            if (potentialUse(declaration.name(), value.getValue(), file, mappings)) return true;
        }
        for (var entry : Fxml2ResourceModel.of(file).entries()) {
            if (entry.equals(declaration)) continue;
            if (Fxml2ResourceUsageScanner.isUsed(entry.declaration().payload().text(),
                    new Fxml2TextSpan(0, 0), declaration.name().value())) return true;
        }
        return false;
    }

    private static boolean potentialUse(Fxml2ResourceName name, String value, XmlFile file,
                                        Map<Character, String> mappings) {
        for (var item : Fxml2ValueSequenceParser.split(value, mappings)) {
            Object parsed = Fxml2BindingExpressionParser.parse(item.text(), mappings);
            if (parsed instanceof Fxml2BindingExpressionParser.ParsedExpression binding) {
                try {
                    if (potentialUse(name, binding.expression(), file, mappings)) return true;
                } catch (Fxml2ExpressionParser.ParseException ignored) {
                    // Incomplete expressions have no definite resource target.
                }
                continue;
            }
            String content;
            String extension;
            if (parsed instanceof Fxml2BindingExpressionParser.PrefixShorthandExpression prefix) {
                extension = prefix.mappedClass();
                content = item.text().substring(1);
            } else if (parsed instanceof Fxml2BindingExpressionParser.MarkupExtensionExpression markup) {
                extension = markup.extensionName();
                int begin = markup.nameOffset() + extension.length();
                content = item.text().substring(begin, item.text().length() - 1).trim();
            } else continue;
            var extensionClass = Fxml2ImportResolver.resolve(extension, file);
            if (RESOURCE_CLASS.equals(extension)
                    || extensionClass != null && RESOURCE_CLASS.equals(extensionClass.getQualifiedName())) {
                var invocation = Fxml2ResourceInvocation.parse(content);
                if (invocation != null && invocation.name().equals(name)
                        && Fxml2ResourceLoader.resolve(invocation, file) != Fxml2ResourceLoader.CUSTOM) return true;
            }
            for (var section : Fxml2MarkupExtensionContentParser.parse(content)) {
                String nested = section instanceof Fxml2MarkupExtensionContentParser.NamedParameter parameter
                        ? parameter.value() : ((Fxml2MarkupExtensionContentParser.PositionalValue)section).text();
                if (potentialUse(name, nested, file, mappings)) return true;
            }
        }
        return false;
    }

    private static boolean potentialUse(Fxml2ResourceName name, Fxml2ExpressionParser.Expression expression,
                                        XmlFile file, Map<Character, String> mappings) {
        return switch (expression) {
            case Fxml2ExpressionParser.LiteralExpression literal ->
                    literal.kind() == Fxml2ExpressionParser.LiteralKind.MARKUP_EXTENSION
                            && potentialUse(name, literal.text(), file, mappings);
            case Fxml2ExpressionParser.UnaryExpression unary -> potentialUse(name, unary.operand(), file, mappings);
            case Fxml2ExpressionParser.BinaryExpression binary -> potentialUse(name, binary.left(), file, mappings)
                    || potentialUse(name, binary.right(), file, mappings);
            case Fxml2ExpressionParser.GroupedExpression grouped -> potentialUse(name, grouped.expression(), file, mappings);
            case Fxml2ExpressionParser.MemberExpression member -> potentialUse(name, member.receiver(), file, mappings);
            case Fxml2ExpressionParser.InvocationExpression invocation ->
                    potentialUse(name, invocation.target(), file, mappings)
                            || invocation.arguments().stream().anyMatch(argument -> potentialUse(name, argument, file, mappings));
            default -> false;
        };
    }
}
