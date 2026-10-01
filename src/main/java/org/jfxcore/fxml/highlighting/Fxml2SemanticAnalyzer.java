package org.jfxcore.fxml.highlighting;

import com.intellij.lang.properties.references.PropertyReference;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.XmlRecursiveElementVisitor;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlProcessingInstruction;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlText;
import com.intellij.psi.xml.XmlToken;
import com.intellij.psi.xml.XmlTokenType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.descriptors.Fxml2PropertyTagDescriptor;
import org.jfxcore.fxml.lang.Fxml2BindingNotationReference;
import org.jfxcore.fxml.lang.Fxml2ExpressionReference;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.lang.Fxml2MarkupExtensionUtil;
import org.jfxcore.fxml.lang.Fxml2StyleClassReference;
import org.jfxcore.fxml.lang.Fxml2ResourceNameReference;
import org.jfxcore.fxml.resolve.Fxml2AttributeValueResolver;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2BindingPathResolver;
import org.jfxcore.fxml.resolve.Fxml2DefaultProperty;
import org.jfxcore.fxml.resolve.Fxml2ExpressionOperands;
import org.jfxcore.fxml.resolve.Fxml2ExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2TypeArgumentParser;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;
import org.jfxcore.fxml.resolve.Fxml2MarkupExtensionContentParser;
import org.jfxcore.fxml.resolve.Fxml2MarkupExtensionParser;
import org.jfxcore.fxml.resolve.Fxml2TextScanner;
import org.jfxcore.fxml.resolve.Fxml2TextSpan;
import org.jfxcore.fxml.resolve.Fxml2MarkupExtensionResolver;
import org.jfxcore.fxml.resolve.Fxml2ValueSequenceParser;
import org.jfxcore.fxml.resolve.Fxml2ValueTargetResolver;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.jfxcore.fxml.highlighting.Fxml2SemanticRole.*;

/** Analyzes each markup value once, sharing the language's parsers, references and target types. */
public final class Fxml2SemanticAnalyzer {
    private static final Set<String> EXPRESSION_INTRINSICS =
            Set.of("Evaluate", "Observe", "Push", "Synchronize");
    private static final Set<String> INTRINSIC_NAMES = Set.of(
            "Evaluate", "Observe", "Push", "Synchronize", "Class", "null", "true", "false",
            "define", "id", "context", "constant", "factory", "value", "typeArguments",
            "subclass", "class", "className", "classModifier", "classParameters");
    private enum ContentKind { VALUE, EXPRESSION, TYPE }

    private final XmlFile file;
    private final boolean resolve;
    private final Map<Character, String> prefixes;
    private final Fxml2SemanticSpans spans = new Fxml2SemanticSpans();
    private int valueDepth;

    private Fxml2SemanticAnalyzer(XmlFile file) {
        this.file = file;
        resolve = !DumbService.isDumb(file.getProject());
        prefixes = Fxml2ImportResolver.parsePrefixMappings(file);
    }

    public static @NotNull List<Fxml2SemanticSpan> analyze(@NotNull XmlFile file) {
        if (!Fxml2FileType.isFxml2(file)) return List.of();
        Fxml2SemanticAnalyzer analyzer = new Fxml2SemanticAnalyzer(file);
        file.accept(new XmlRecursiveElementVisitor() {
            @Override
            public void visitXmlTag(@NotNull XmlTag tag) {
                ProgressManager.checkCanceled();
                analyzer.tag(tag);
                super.visitXmlTag(tag);
            }

            @Override
            public void visitXmlAttribute(@NotNull XmlAttribute attribute) {
                ProgressManager.checkCanceled();
                analyzer.attribute(attribute);
                super.visitXmlAttribute(attribute);
            }

            @Override
            public void visitXmlText(@NotNull XmlText text) {
                ProgressManager.checkCanceled();
                analyzer.body(text);
            }

            @Override
            public void visitXmlProcessingInstruction(@NotNull XmlProcessingInstruction instruction) {
                if (analyzer.resolve) analyzer.references(instruction, false);
            }
        });
        return analyzer.spans.result();
    }

    private void tag(XmlTag tag) {
        boolean intrinsic = Fxml2ImportResolver.FXML2_NAMESPACE.equals(tag.getNamespace());
        Fxml2SemanticRole role = intrinsic ? INTRINSIC : null;
        if (resolve && !intrinsic) {
            role = tag.getDescriptor() instanceof Fxml2PropertyTagDescriptor
                    ? PROPERTY_ASSIGNMENT : symbolRole(Fxml2BindingPathResolver.resolveTagClass(tag), false);
        }
        for (PsiElement child : tag.getChildren()) {
            if (child instanceof XmlToken token && token.getTokenType() == XmlTokenType.XML_NAME) {
                if (role != null) name(Fxml2SourceText.decode(token.getText(), token.getTextRange().getStartOffset()), role);
                if (resolve) references(token, true);
            }
        }
        if (resolve) references(tag, true);
    }

    private void attribute(XmlAttribute attribute) {
        if (attribute.isNamespaceDeclaration()) return;
        XmlTag tag = attribute.getParent() instanceof XmlTag parent ? parent : null;
        if (tag == null) return;
        boolean intrinsic = Fxml2ImportResolver.FXML2_NAMESPACE.equals(attribute.getNamespace());
        PsiElement name = attribute.getNameElement();
        if (name != null) {
            Fxml2SourceText text = Fxml2SourceText.decode(name.getText(), name.getTextRange().getStartOffset());
            name(text, intrinsic ? INTRINSIC_ATTRIBUTE : PROPERTY_ASSIGNMENT);
            if (resolve) references(attribute, true);
        }
        XmlAttributeValue value = attribute.getValueElement();
        if (value == null) return;
        Fxml2SourceText source = attributeSource(value);
        String local = attribute.getLocalName();
        if (intrinsic && "typeArguments".equals(local)) {
            colors(tag).types(source);
        } else if (intrinsic && Set.of("subclass", "class", "className").contains(local)) {
            colors(tag).types(source);
        } else if (intrinsic && "id".equals(local)) {
            spans.add(source.range(0, source.text().length()), INSTANCE_FIELD);
        } else if (intrinsic && "constant".equals(local)) {
            spans.add(source.range(0, source.text().length()), CONSTANT);
        } else if (intrinsic && "factory".equals(local)) {
            spans.add(source.range(0, source.text().length()), STATIC_METHOD_CALL);
        } else if (isExpressionArgument(attribute, tag)) {
            expression(source, tag);
        } else {
            PsiType target = resolve ? Fxml2AttributeValueResolver.attributeTargetType(attribute, file) : null;
            value(source, target, resolve ? Fxml2BindingPathResolver.resolveTagClass(tag) : null, tag);
        }
        if (resolve) references(value, false);
    }

    private boolean isExpressionArgument(XmlAttribute attribute, XmlTag tag) {
        if (isIntrinsicTag(tag) && EXPRESSION_INTRINSICS.contains(tag.getLocalName())) {
            return Set.of("source", "converter", "format", "inverseMethod").contains(attribute.getLocalName());
        }
        String value = attribute.getValue();
        return Fxml2ImportResolver.FXML2_NAMESPACE.equals(attribute.getNamespace())
                && "context".equals(attribute.getLocalName())
                && value != null && !value.startsWith("$");
    }

    private static boolean isIntrinsicTag(XmlTag tag) {
        return Fxml2ImportResolver.FXML2_NAMESPACE.equals(tag.getNamespace());
    }

    private void body(XmlText text) {
        if (!(text.getParent() instanceof XmlTag tag)) return;
        Fxml2SourceText source = Fxml2SourceText.decode(text.getText(), text.getTextRange().getStartOffset());
        PsiClass owner = null;
        PsiType target = null;
        if (resolve && tag.getDescriptor() instanceof Fxml2PropertyTagDescriptor property) {
            owner = property.getOwnerClass();
            if (owner != null) {
                target = property.isStatic()
                        ? Fxml2AttributeValueResolver.staticPropertyType(owner, property.getPropertyName())
                        : Fxml2AttributeValueResolver.propertyType(owner, property.getPropertyName(), List.of());
            }
        } else if (resolve) {
            owner = Fxml2BindingPathResolver.resolveTagClass(tag);
            if (owner != null) {
                var property = Fxml2DefaultProperty.resolve(owner);
                if (property != null) {
                    target = Fxml2AttributeValueResolver.propertyType(owner, property.name(), List.of());
                }
            }
        }
        if (isIntrinsicTag(tag) && EXPRESSION_INTRINSICS.contains(tag.getLocalName())) {
            expression(source, tag);
        } else {
            value(source, target, owner, tag);
        }
    }

    private static Fxml2SourceText attributeSource(XmlAttributeValue value) {
        String raw = value.getText();
        int begin = !raw.isEmpty() && (raw.charAt(0) == '"' || raw.charAt(0) == '\'') ? 1 : 0;
        int end = raw.length();
        if (begin == 1 && end > 1 && raw.charAt(end - 1) == raw.charAt(0)) end--;
        return Fxml2SourceText.decode(raw.substring(begin, end), value.getTextRange().getStartOffset() + begin);
    }

    private Fxml2ExpressionColors colors(XmlTag tag) {
        return new Fxml2ExpressionColors(spans, source -> value(source, null, null, tag));
    }

    private void value(Fxml2SourceText source, @Nullable PsiType type, @Nullable PsiClass owner, XmlTag tag) {
        ProgressManager.checkCanceled();
        if (++valueDepth > 128) {
            valueDepth--;
            return;
        }
        try {
            var sequence = Fxml2ValueSequenceParser.parseSequence(source.text(), prefixes);
            var items = sequence.items();
            var target = resolve && type != null
                    ? Fxml2ValueTargetResolver.resolveTarget(type, items.size(), file.getResolveScope()) : null;
            if (target instanceof Fxml2ValueTargetResolver.Items
                    || target instanceof Fxml2ValueTargetResolver.Arguments) {
                for (int i = 0; i < items.size(); i++) {
                    var item = items.get(i);
                    PsiType itemType = switch (target) {
                        case Fxml2ValueTargetResolver.Items collected -> collected.itemType();
                        case Fxml2ValueTargetResolver.Arguments arguments -> i < arguments.parameters().size()
                                ? arguments.parameters().get(i).getType() : null;
                        default -> type;
                    };
                    oneValue(source.slice(item.offset(), item.offset() + item.text().length()), itemType, owner, tag);
                }
                for (var separator : sequence.separators()) {
                    spans.add(source.range(separator.start(), separator.end()), COMMA);
                }
            } else {
                var trimmed = Fxml2TextSpan.trimmed(source.text(), 0, source.text().length());
                if (!trimmed.isEmpty()) oneValue(source.slice(trimmed.start(), trimmed.end()), type, owner, tag);
            }
        } finally {
            valueDepth--;
        }
    }

    private void oneValue(Fxml2SourceText source, @Nullable PsiType type, @Nullable PsiClass owner, XmlTag tag) {
        String text = source.text();
        if (text.isEmpty()) return;
        if (text.startsWith("\\")) {
            if (Fxml2BindingExpressionParser.looksLikeBindingExpression(text.substring(1), prefixes)) {
                spans.add(source.range(0, text.length()), STRING);
                spans.add(source.range(0, Math.min(2, text.length())), ESCAPE);
            }
            return;
        }
        var binding = Fxml2BindingExpressionParser.parseExpressionLenient(text);
        if (binding != null) {
            binding(source, binding, tag);
            return;
        }
        if (text.charAt(0) == '{') {
            extension(source, tag);
            return;
        }
        Object notation = Fxml2BindingExpressionParser.parse(text, prefixes);
        if (notation instanceof Fxml2BindingExpressionParser.PrefixShorthandExpression prefix) {
            spans.add(source.range(0, 1), PREFIX);
            PsiClass extensionClass = resolve ? Fxml2ImportResolver.resolve(prefix.mappedClass(), file) : null;
            sections(source.slice(1, text.length()), extensionClass, ContentKind.VALUE, tag);
            return;
        }
        literal(source, type, owner, tag);
    }

    private void binding(Fxml2SourceText source, Fxml2BindingExpressionParser.ParsedExpression binding, XmlTag tag) {
        String text = source.text();
        var extension = Fxml2MarkupExtensionParser.parse(text);
        int contentStart = binding.prefixLength();
        if (extension != null) {
            spans.add(source.range(0, 1), BRACES);
            name(source.slice(extension.name().start(), extension.name().end()), INTRINSIC);
            contentStart = extension.content().start();
        } else {
            spans.add(source.range(0, 1), PREFIX);
            if (binding.prefixLength() > 1 && text.charAt(1) == '{') spans.add(source.range(1, 2), BRACES);
        }
        int contentEnd = text.endsWith("}") ? text.length() - 1 : text.length();
        if (contentEnd < text.length()) spans.add(source.range(contentEnd, text.length()), BRACES);
        if (contentStart <= contentEnd) {
            Fxml2SourceText content = source.slice(contentStart, contentEnd);
            sectionSyntax(content, Fxml2MarkupExtensionContentParser.parseContent(content.text()));
        }
        int end = Math.min(text.length(), binding.pathOffset() + binding.path().length());
        expression(source.slice(binding.pathOffset(), end), tag);
        for (var parameter : binding.params()) {
            if (parameter.pathOffset() >= 0) {
                int finish = Math.min(text.length(), parameter.pathOffset() + parameter.path().length());
                expression(source.slice(parameter.pathOffset(), finish), tag);
            }
        }
    }

    private void expression(Fxml2SourceText source, XmlTag tag) {
        if (source.text().startsWith("..")) {
            spans.add(source.range(0, 2), OPERATOR);
            expression(source.slice(2, source.text().length()), tag);
            return;
        }
        var tree = colors(tag).expression(source);
        if (!resolve || tree == null) return;
        for (var operand : Fxml2ExpressionOperands.operands(tree, 0)) {
            var selector = Fxml2BindingExpressionParser.parseContextSelector(operand.text());
            String path = selector != null ? selector.remainingPath() : operand.text();
            int base = operand.offset() + (selector != null ? selector.selectorLength() : 0);
            PsiClass start = Fxml2BindingPathResolver.resolveStartClass(selector, tag, file);
            if (start == null || path.isBlank()) continue;
            boolean call = operand.kind() == Fxml2ExpressionOperands.OperandKind.FUNCTION_NAME;
            var segments = call
                    ? Fxml2BindingPathResolver.resolveFunctionName(path, start, file.getResolveScope(), null, file)
                    : Fxml2BindingPathResolver.functionCallParenIndex(path) >= 0
                        ? Fxml2BindingPathResolver.resolveFunctionCall(path, start, file.getResolveScope(), null, file, tag)
                        : Fxml2BindingPathResolver.resolve(path, start, file.getResolveScope(), null, file);
            for (int i = 0; i < segments.size(); i++) {
                var segment = segments.get(i);
                Fxml2SemanticRole role = symbolRole(segment.declaration(), call && i + 1 == segments.size());
                int begin = base + segment.pathOffset();
                int end = begin + segment.name().length();
                if (role != null && Fxml2TextScanner.isIdentifier(segment.name())
                        && begin >= 0 && end <= source.text().length()) {
                    spans.add(source.range(begin, end), role);
                }
            }
        }
        for (var type : Fxml2ExpressionOperands.typeNames(tree, 0)) {
            resolvedTypes(source.slice(type.offset(), type.offset() + type.text().length()));
        }
    }

    private void resolvedTypes(Fxml2SourceText source) {
        for (var type : Fxml2TypeArgumentParser.allTypeNames(source.text(), 0)) {
            PsiClass declaration = Fxml2ImportResolver.resolve(type.name(), file);
            var parts = Fxml2TextScanner.nameParts(type.name());
            for (int i = 0; i < parts.size(); i++) {
                var part = parts.get(i);
                Fxml2SemanticRole role = i + 1 == parts.size() ? classRole(declaration) : IDENTIFIER;
                spans.add(source.range(type.offset() + part.start(), type.offset() + part.end()), role);
            }
        }
    }

    private void extension(Fxml2SourceText source, XmlTag tag) {
        String text = source.text();
        spans.add(source.range(0, 1), BRACES);
        var extension = Fxml2MarkupExtensionParser.parse(text);
        if (extension == null) return;
        String extensionName = extension.name().textOf(text);
        String localName = extensionName.substring(extensionName.indexOf(':') + 1);
        boolean intrinsic = extensionName.contains(":") && INTRINSIC_NAMES.contains(localName);
        PsiClass extensionClass = resolve && !intrinsic ? Fxml2ImportResolver.resolve(extensionName, file) : null;
        if (extensionClass == null && resolve && !intrinsic) {
            extensionClass = JavaPsiFacade.getInstance(file.getProject()).findClass(
                    "org.jfxcore.markup.resource." + extensionName, file.getResolveScope());
        }
        name(source.slice(extension.name().start(), extension.name().end()), intrinsic ? INTRINSIC : classRole(extensionClass));
        if (extension.typeArguments() != null) {
            var arguments = extension.typeArguments();
            colors(tag).types(source.slice(arguments.start(), arguments.end()));
        }
        if (extension.closed()) spans.add(source.range(text.length() - 1, text.length()), BRACES);
        if (!extension.content().isEmpty()) sections(source.slice(extension.content().start(), extension.content().end()), extensionClass,
                intrinsic && EXPRESSION_INTRINSICS.contains(localName) ? ContentKind.EXPRESSION
                        : intrinsic && "Class".equals(localName) ? ContentKind.TYPE : ContentKind.VALUE, tag);
    }

    private void sections(Fxml2SourceText source, @Nullable PsiClass extensionClass,
                          ContentKind contentKind, XmlTag tag) {
        var content = Fxml2MarkupExtensionContentParser.parseContent(source.text());
        sectionSyntax(source, content);
        for (var section : content.sections()) {
            String propertyName;
            Fxml2SourceText value;
            if (section instanceof Fxml2MarkupExtensionContentParser.NamedParameter parameter) {
                propertyName = parameter.name();
                value = source.slice(parameter.valueOffset(), parameter.valueOffset() + parameter.value().length());
            } else {
                var positional = (Fxml2MarkupExtensionContentParser.PositionalValue)section;
                var defaultProperty = extensionClass != null ? Fxml2DefaultProperty.resolve(extensionClass) : null;
                propertyName = defaultProperty != null ? defaultProperty.name() : null;
                value = source.slice(positional.offset(), positional.offset() + positional.text().length());
            }
            if (contentKind == ContentKind.EXPRESSION) {
                expression(value, tag);
            } else if (contentKind == ContentKind.TYPE) {
                colors(tag).types(value);
            } else {
                PsiElement declaration = extensionClass != null && propertyName != null
                        ? Fxml2MarkupExtensionResolver.resolveParameter(propertyName, extensionClass) : null;
                PsiType type = declaration != null ? Fxml2AttributeValueResolver.resolveDeclarationPsiType(declaration) : null;
                value(value, type, extensionClass, tag);
                if (extensionClass != null && "key".equals(propertyName)
                        && !Fxml2BindingExpressionParser.looksLikeBindingExpression(value.text(), prefixes)
                        && Fxml2MarkupExtensionUtil.isResourceKeyExtension(extensionClass)) {
                    spans.add(value.range(0, value.text().length()), RESOURCE_KEY);
                } else if (extensionClass != null && "value".equals(propertyName)
                        && !Fxml2BindingExpressionParser.looksLikeBindingExpression(value.text(), prefixes)
                        && "org.jfxcore.markup.resource.ClassPathResource".equals(extensionClass.getQualifiedName())) {
                    spans.add(value.range(0, value.text().length()), RESOURCE_PATH);
                }
            }
        }
    }

    private void sectionSyntax(Fxml2SourceText source, Fxml2MarkupExtensionContentParser.Content content) {
        for (var section : content.sections()) {
            if (section instanceof Fxml2MarkupExtensionContentParser.NamedParameter parameter) {
                spans.add(source.range(parameter.offset(), parameter.offset() + parameter.name().length()), PROPERTY_ASSIGNMENT);
                spans.add(source.range(parameter.assignmentOffset(), parameter.assignmentOffset() + 1), OPERATOR);
            }
        }
        for (var separator : content.separators()) {
            if (";".equals(separator.textOf(source.text()))) {
                spans.add(source.range(separator.start(), separator.end()), SEMICOLON);
            }
        }
    }

    private void literal(Fxml2SourceText source, @Nullable PsiType type, @Nullable PsiClass owner, XmlTag tag) {
        if (type == null) return;
        String typeName = type.getCanonicalText();
        String text = source.text();
        if (text.startsWith("'") || text.startsWith("\"")) {
            try {
                var expression = Fxml2ExpressionParser.parse(text);
                if (expression instanceof Fxml2ExpressionParser.LiteralExpression literal
                        && literal.kind() == Fxml2ExpressionParser.LiteralKind.STRING) {
                    spans.add(source.range(0, text.length()), STRING);
                    colors(tag).escapes(source);
                    return;
                }
            } catch (Fxml2ExpressionParser.ParseException ignored) {
                // An unfinished quoted value retains its surrounding XML formatting.
            }
        }
        if ("java.lang.String".equals(typeName) || "java.lang.CharSequence".equals(typeName)) {
            spans.add(source.range(0, text.length()), STRING);
            return;
        }
        if (Set.of("boolean", "java.lang.Boolean").contains(typeName)) {
            if ("true".equals(text) || "false".equals(text)) spans.add(source.range(0, text.length()), KEYWORD);
            return;
        }
        try {
            var expression = Fxml2ExpressionParser.parse(text);
            if (expression instanceof Fxml2ExpressionParser.LiteralExpression literal
                    && literal.kind() == Fxml2ExpressionParser.LiteralKind.NUMBER
                    || expression instanceof Fxml2ExpressionParser.UnaryExpression unary
                    && unary.operand() instanceof Fxml2ExpressionParser.LiteralExpression number
                    && number.kind() == Fxml2ExpressionParser.LiteralKind.NUMBER) {
                if (Set.of("byte", "short", "int", "long", "float", "double", "java.lang.Byte",
                        "java.lang.Short", "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double").contains(typeName)) {
                    spans.add(source.range(0, text.length()), NUMBER);
                    return;
                }
            }
        } catch (Fxml2ExpressionParser.ParseException ignored) {
            // Literal conversion also accepts symbolic values.
        }
        if (resolve && owner != null) {
            var converted = Fxml2AttributeValueResolver.convertLiteralItem(type, text, owner, file.getResolveScope());
            Fxml2SemanticRole role = converted.declaration() instanceof PsiClass
                    ? STRING : symbolRole(converted.declaration(), false);
            if (role != null) spans.add(source.range(0, text.length()), role);
        }
        if ("java.lang.Class".equals(typeName) || typeName.startsWith("java.lang.Class<")) colors(tag).types(source);
    }

    private void name(Fxml2SourceText source, Fxml2SemanticRole role) {
        for (var part : Fxml2TextScanner.nameParts(source.text())) {
            spans.add(source.range(part.start(), part.end()), role);
            if (part.end() < source.text().length()) spans.add(source.range(part.end(), part.end() + 1), DOT);
        }
    }

    private void references(PsiElement element, boolean assignment) {
        for (PsiReference reference : element.getReferences()) {
            if (reference instanceof Fxml2ExpressionReference || reference instanceof Fxml2BindingNotationReference) continue;
            TextRange relative = reference.getRangeInElement();
            if (relative.isEmpty() || relative.getEndOffset() > element.getTextLength()) continue;
            TextRange range = relative.shiftRight(element.getTextRange().getStartOffset());
            Fxml2SemanticRole previous = spans.roleAt(range.getStartOffset());
            if (previous == PREFIX || previous == SELECTOR || previous == INTRINSIC || previous == INTRINSIC_ATTRIBUTE
                    || previous == NUMBER || previous == KEYWORD
                    || previous == ESCAPE || previous == OPERATOR) continue;
            Fxml2SemanticRole role;
            switch (reference) {
                case PropertyReference ignored -> role = RESOURCE_KEY;
                case Fxml2StyleClassReference ignored -> role = CSS_CLASS;
                case Fxml2ResourceNameReference ignored -> role = RESOURCE_PATH;
                default -> {
                    PsiElement target = reference.resolve();
                    if (target instanceof PsiFile) {
                        spans.add(range, RESOURCE_PATH);
                        continue;
                    }
                    if (!Fxml2TextScanner.isIdentifier(relative.substring(element.getText()))) continue;
                    if (previous == PROPERTY_ASSIGNMENT && !(target instanceof PsiClass || target instanceof PsiPackage)) continue;
                    boolean handler = element instanceof XmlAttributeValue value
                            && value.getParent() instanceof XmlAttribute attribute
                            && attribute.getLocalName().startsWith("on") && target instanceof PsiMethod;
                    boolean invocation = previous == METHOD_CALL || previous == STATIC_METHOD_CALL || previous == CONSTRUCTOR_CALL;
                    role = symbolRole(target, handler || invocation);
                    if (assignment && (target instanceof PsiMethod || target instanceof PsiField || target instanceof PsiParameter)) {
                        role = PROPERTY_ASSIGNMENT;
                    }
                    if (previous == STRING && target instanceof PsiClass) continue;
                }
            }
            if (role != null) spans.add(range, role);
        }
    }

    private static Fxml2SemanticRole classRole(@Nullable PsiClass type) {
        if (type instanceof PsiTypeParameter) return TYPE_PARAMETER;
        if (type != null && type.isEnum()) return ENUM;
        if (type != null && type.isInterface()) return INTERFACE;
        return TYPE;
    }

    private static Fxml2SemanticRole symbolRole(@Nullable PsiElement target, boolean invocation) {
        if (target instanceof PsiClass type) return invocation ? CONSTRUCTOR_CALL : classRole(type);
        if (target instanceof PsiEnumConstant) return CONSTANT;
        if (target instanceof PsiField field) {
            boolean isStatic = field.hasModifierProperty(PsiModifier.STATIC);
            boolean isFinal = field.hasModifierProperty(PsiModifier.FINAL);
            return isStatic ? isFinal ? CONSTANT : STATIC_FIELD : isFinal ? INSTANCE_FINAL_FIELD : INSTANCE_FIELD;
        }
        if (target instanceof PsiMethod method) {
            if (method.isConstructor()) return CONSTRUCTOR_CALL;
            return invocation ? method.hasModifierProperty(PsiModifier.STATIC) ? STATIC_METHOD_CALL : METHOD_CALL : PROPERTY_READ;
        }
        if (target instanceof PsiParameter || target instanceof PsiPackage) return IDENTIFIER;
        return null;
    }
}
