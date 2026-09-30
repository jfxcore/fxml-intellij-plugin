package org.jfxcore.fxml.highlighting;

import com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2ExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2TypeArgumentParser;

import org.jfxcore.fxml.resolve.Fxml2TextScanner;
import java.util.function.Consumer;

import static org.jfxcore.fxml.highlighting.Fxml2SemanticRole.*;

/** Syntax-only expression traversal; source positions come from the shared expression parser. */
final class Fxml2ExpressionColors {
    private final Fxml2SemanticSpans spans;
    private final Consumer<Fxml2SourceText> extension;

    Fxml2ExpressionColors(Fxml2SemanticSpans spans, Consumer<Fxml2SourceText> extension) {
        this.spans = spans;
        this.extension = extension;
    }

    @Nullable Fxml2ExpressionParser.Expression expression(@NotNull Fxml2SourceText source) {
        punctuation(source);
        try {
            var expression = Fxml2ExpressionParser.parse(source.text());
            visit(expression, source, 0);
            return expression;
        } catch (Fxml2ExpressionParser.ParseException ignored) {
            // Incomplete expressions retain their recognized punctuation.
            return null;
        }
    }

    private void visit(Fxml2ExpressionParser.Expression expression, Fxml2SourceText source, int depth) {
        ProgressManager.checkCanceled();
        if (depth > 128) return;
        var range = expression.span();
        switch (expression) {
            case Fxml2ExpressionParser.LiteralExpression literal -> {
                if (literal.kind() == Fxml2ExpressionParser.LiteralKind.MARKUP_EXTENSION) {
                    extension.accept(source.slice(range.start(), range.end()));
                } else {
                    Fxml2SemanticRole role = switch (literal.kind()) {
                        case STRING -> STRING;
                        case NUMBER -> NUMBER;
                        default -> KEYWORD;
                    };
                    spans.add(source.range(range.start(), range.end()), role);
                    if (role == STRING) escapes(source.slice(range.start(), range.end()));
                }
            }
            case Fxml2ExpressionParser.PathExpression path -> {
                int start = range.start();
                if (path.name().startsWith("::")) start += 2;
                int length = path.name().startsWith("::") ? path.name().length() - 2 : path.name().length();
                spans.add(source.range(start, start + length), IDENTIFIER);
                for (var argument : path.typeArguments()) {
                    types(source.slice(argument.span().start(), argument.span().end()));
                }
            }
            case Fxml2ExpressionParser.MemberExpression member -> {
                visit(member.receiver(), source, depth + 1);
                visit(member.member(), source, depth + 1);
            }
            case Fxml2ExpressionParser.InvocationExpression invocation -> {
                visit(invocation.target(), source, depth + 1);
                for (var argument : invocation.arguments()) visit(argument, source, depth + 1);
            }
            case Fxml2ExpressionParser.ContextSelectorExpression selector -> {
                spans.add(source.range(selector.nameSpan().start(), selector.nameSpan().end()), SELECTOR);
                if (selector.typeSpan() != null) {
                    types(source.slice(selector.typeSpan().start(), selector.typeSpan().end()));
                }
                if (selector.depthSpan() != null) {
                    spans.add(source.range(selector.depthSpan().start(), selector.depthSpan().end()), NUMBER);
                }
            }
            case Fxml2ExpressionParser.AttachedPropertyExpression attached -> {
                if (attached.receiver() != null) visit(attached.receiver(), source, depth + 1);
                types(source.slice(attached.declaringTypeSpan().start(), attached.declaringTypeSpan().end()));
                spans.add(source.range(attached.propertySpan().start(), attached.propertySpan().end()), PROPERTY_READ);
            }
            case Fxml2ExpressionParser.UnaryExpression unary -> visit(unary.operand(), source, depth + 1);
            case Fxml2ExpressionParser.BinaryExpression binary -> {
                visit(binary.left(), source, depth + 1);
                visit(binary.right(), source, depth + 1);
            }
            case Fxml2ExpressionParser.GroupedExpression grouped -> visit(grouped.expression(), source, depth + 1);
        }
    }

    void types(@NotNull Fxml2SourceText source) {
        punctuation(source);
        for (var type : Fxml2TypeArgumentParser.allTypeNames(source.text(), 0)) {
            for (var part : Fxml2TextScanner.nameParts(type.name())) {
                spans.add(source.range(type.offset() + part.start(), type.offset() + part.end()), TYPE);
            }
        }
    }

    void escapes(Fxml2SourceText source) {
        if (source.text().isEmpty() || !Fxml2TextScanner.isQuote(source.text().charAt(0))) return;
        for (var escape : Fxml2TextScanner.quoted(source.text(), 0).escapes()) {
            spans.add(source.range(escape.start(), escape.end()), ESCAPE);
        }
    }

    void punctuation(Fxml2SourceText source) {
        for (var token : Fxml2TextScanner.scan(source.text())) {
            Fxml2SemanticRole role = switch (token.kind()) {
                case OPEN_BRACE, CLOSE_BRACE -> BRACES;
                case OPEN_PARENTHESIS, CLOSE_PARENTHESIS -> PARENTHESES;
                case OPEN_BRACKET, CLOSE_BRACKET -> BRACKETS;
                case COMMA -> COMMA;
                case SEMICOLON -> SEMICOLON;
                case DOT -> DOT;
                case OPEN_ANGLE, CLOSE_ANGLE, ASSIGNMENT, OPERATOR -> OPERATOR;
                default -> null;
            };
            if (role != null) spans.add(source.range(token.span().start(), token.span().end()), role);
        }
    }
}
