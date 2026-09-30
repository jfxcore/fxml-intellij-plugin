package org.jfxcore.fxml.highlighting;

import com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resolve.Fxml2ExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2TypeArgumentParser;

import java.util.List;
import java.util.function.Consumer;

import static org.jfxcore.fxml.highlighting.Fxml2SemanticRole.*;

/** Syntax-only expression traversal; source positions come from the shared expression parser. */
final class Fxml2ExpressionColors {
    private static final List<String> OPERATORS = List.of(
            "!==", "===", "!=", "==", "<=", ">=", "&&", "||", "!!", "::", "..");
    private final Fxml2SemanticSpans spans;
    private final Consumer<Fxml2SourceText> extension;

    Fxml2ExpressionColors(Fxml2SemanticSpans spans, Consumer<Fxml2SourceText> extension) {
        this.spans = spans;
        this.extension = extension;
    }

    void expression(@NotNull Fxml2SourceText source) {
        punctuation(source);
        try {
            visit(Fxml2ExpressionParser.parse(source.text()), source, 0);
        } catch (Fxml2ExpressionParser.ParseException ignored) {
            // Incomplete expressions retain their recognized punctuation.
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
                int end = range.start() + 1;
                while (end < range.end() && Character.isJavaIdentifierPart(source.text().charAt(end))) end++;
                spans.add(source.range(range.start(), end), SELECTOR);
                if (selector.typeSpan() != null) {
                    types(source.slice(selector.typeSpan().start(), selector.typeSpan().end()));
                }
                if (selector.depth() != null) {
                    int open = source.text().indexOf('(', end);
                    if (open >= 0 && open < range.end()) {
                        int begin = open + 1;
                        while (begin < range.end() && Character.isWhitespace(source.text().charAt(begin))) begin++;
                        int finish = begin;
                        if (finish < range.end() && "+-".indexOf(source.text().charAt(finish)) >= 0) finish++;
                        while (finish < range.end() && Character.isDigit(source.text().charAt(finish))) finish++;
                        spans.add(source.range(begin, finish), NUMBER);
                    }
                }
            }
            case Fxml2ExpressionParser.AttachedPropertyExpression attached -> {
                if (attached.receiver() != null) visit(attached.receiver(), source, depth + 1);
                int open = source.text().lastIndexOf('(', range.end() - 1);
                int begin = open + 1;
                while (begin < range.end() && Character.isWhitespace(source.text().charAt(begin))) begin++;
                int nameEnd = begin + attached.declaringType().length();
                types(source.slice(begin, nameEnd));
                spans.add(source.range(nameEnd + 1, nameEnd + 1 + attached.property().length()), PROPERTY_READ);
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
            String[] parts = type.name().split("\\.");
            int start = type.offset();
            for (String part : parts) {
                spans.add(source.range(start, start + part.length()), TYPE);
                start += part.length() + 1;
            }
        }
    }

    void escapes(Fxml2SourceText source) {
        for (int i = 0; i + 1 < source.text().length(); i++) {
            if (source.text().charAt(i) == '\\') {
                spans.add(source.range(i, i + 2), ESCAPE);
                i++;
            }
        }
    }

    void punctuation(Fxml2SourceText source) {
        char quote = 0;
        for (int i = 0; i < source.text().length();) {
            char ch = source.text().charAt(i);
            if (quote != 0) {
                if (ch == '\\') i++;
                else if (ch == quote) quote = 0;
                i++;
                continue;
            }
            if (ch == '\'' || ch == '"') {
                quote = ch;
                i++;
                continue;
            }
            Fxml2SemanticRole role = switch (ch) {
                case '{', '}' -> BRACES;
                case '(', ')' -> PARENTHESES;
                case '[', ']' -> BRACKETS;
                case ',' -> COMMA;
                case ';' -> SEMICOLON;
                case '.' -> DOT;
                case '+', '-', '*', '/', '<', '>', '=', '!', '&', '|', ':' -> OPERATOR;
                default -> null;
            };
            int end = i + 1;
            if (role != null) {
                if (role == OPERATOR || role == DOT) {
                    for (String operator : OPERATORS) {
                        if (source.text().startsWith(operator, i)) {
                            end = i + operator.length();
                            role = OPERATOR;
                            break;
                        }
                    }
                }
                spans.add(source.range(i, end), role);
            }
            i = end;
        }
    }
}
