package org.jfxcore.fxml.resolve;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Parses the expression carried by an FXML/2 binding notation. */
public final class Fxml2ExpressionParser {

    private Fxml2ExpressionParser() {
    }

    public record Span(int start, int end) {
        public Span {
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("Invalid source span");
            }
        }
    }

    public record TypeArgument(@NotNull String text, @NotNull Span span) {
    }

    public sealed interface Expression permits PathExpression, MemberExpression,
            AttachedPropertyExpression, InvocationExpression, ContextSelectorExpression, LiteralExpression,
            UnaryExpression, BinaryExpression, GroupedExpression {
        @NotNull Span span();

        @NotNull String source();

        default @NotNull String text() {
            return source().substring(span().start(), span().end());
        }

        default @NotNull List<TypeArgument> typeArguments() {
            return List.of();
        }
    }

    public record PathExpression(@NotNull String name, @NotNull List<TypeArgument> typeArguments,
                                 @NotNull Span span, @NotNull String source) implements Expression {
        public PathExpression {
            typeArguments = List.copyOf(typeArguments);
        }
    }

    public enum SelectionOperator {VALUE, OBSERVABLE}

    public record MemberExpression(@NotNull Expression receiver, @NotNull PathExpression member,
                                   @NotNull SelectionOperator operator, @NotNull Span span,
                                   @NotNull String source) implements Expression {
        @Override
        public @NotNull List<TypeArgument> typeArguments() {
            return member.typeArguments();
        }
    }

    public record AttachedPropertyExpression(@Nullable Expression receiver,
                                             @NotNull String declaringType,
                                             @NotNull String property,
                                             @NotNull SelectionOperator operator,
                                             @NotNull Span span,
                                             @NotNull String source) implements Expression {
    }

    public record InvocationExpression(@NotNull Expression target,
                                       @NotNull List<Expression> arguments,
                                       @NotNull Span span,
                                       @NotNull String source) implements Expression {
        public InvocationExpression {
            arguments = List.copyOf(arguments);
        }
    }

    public enum ContextSelectorKind {CONTEXT, ROOT, ELEMENT, PARENT}

    public record ContextSelectorExpression(@NotNull ContextSelectorKind kind,
                                            @Nullable String typeName,
                                            @Nullable Integer depth,
                                            @Nullable Span typeSpan,
                                            @NotNull Span span,
                                            @NotNull String source) implements Expression {
    }

    public enum LiteralKind {STRING, NUMBER, TRUE, FALSE, NULL, MARKUP_EXTENSION}

    public record LiteralExpression(@NotNull LiteralKind kind, @NotNull Span span,
                                    @NotNull String source) implements Expression {
    }

    public enum UnaryOperator {PLUS, NEGATE, NOT, BOOLIFY}

    public record UnaryExpression(@NotNull UnaryOperator operator, @NotNull Expression operand,
                                  @NotNull Span span, @NotNull String source) implements Expression {
    }

    public enum BinaryOperator {
        MULTIPLY, DIVIDE, ADD, SUBTRACT,
        LESS, LESS_OR_EQUAL, GREATER, GREATER_OR_EQUAL,
        EQUAL, NOT_EQUAL, IDENTITY_EQUAL, IDENTITY_NOT_EQUAL,
        AND, OR
    }

    public record BinaryExpression(@NotNull Expression left, @NotNull BinaryOperator operator,
                                   @NotNull Expression right, @NotNull Span span,
                                   @NotNull String source) implements Expression {
    }

    public record GroupedExpression(@NotNull Expression expression, @NotNull Span span,
                                    @NotNull String source) implements Expression {
    }

    public static final class ParseException extends IllegalArgumentException {
        private final Span span;

        public ParseException(@NotNull String message, @NotNull Span span) {
            super(message);
            this.span = span;
        }

        @SuppressWarnings("unused")
        public @NotNull Span span() {
            return span;
        }
    }

    public static @NotNull Expression parse(@NotNull String source) {
        Parser parser = new Parser(source);
        Expression expression = parser.parseExpression(0);
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("Unexpected token", parser.position, parser.position + 1);
        }
        return expression;
    }

    /** Parses only a context selector at the start of a possibly incomplete expression. */
    public static @Nullable ContextSelectorExpression parseLeadingContextSelector(
            @NotNull String source) {
        if (!source.startsWith(":")) {
            return null;
        }
        Parser parser = new Parser(source);
        try {
            return parser.parseContextSelector();
        } catch (ParseException ignored) {
            return null;
        }
    }

    private static final class Parser {
        private final String source;
        private int position;

        private Parser(String source) {
            this.source = source;
        }

        private Expression parseExpression(int minimumPrecedence) {
            Expression left = parseUnary();
            while (true) {
                skipWhitespace();
                OperatorToken token = peekBinaryOperator();
                if (token == null || token.precedence < minimumPrecedence) {
                    return left;
                }
                position += token.text.length();
                Expression right = parseExpression(token.precedence + 1);
                left = new BinaryExpression(left, token.operator, right,
                        new Span(left.span().start(), right.span().end()), source);
            }
        }

        private Expression parseUnary() {
            skipWhitespace();
            int start = position;
            UnaryOperator operator = null;
            if (consume("!!")) {
                operator = UnaryOperator.BOOLIFY;
            } else if (consume("!")) {
                operator = UnaryOperator.NOT;
            } else if (consume("+")) {
                operator = UnaryOperator.PLUS;
            } else if (consume("-")) {
                operator = UnaryOperator.NEGATE;
            }
            if (operator == null) {
                return parsePostfix();
            }
            Expression operand = parseUnary();
            return new UnaryExpression(operator, operand, new Span(start, operand.span().end()), source);
        }

        private Expression parsePostfix() {
            Expression result = parsePrimary();
            boolean pathChain = result instanceof PathExpression
                    || result instanceof ContextSelectorExpression
                    || result instanceof AttachedPropertyExpression;
            while (true) {
                skipWhitespace();
                if (peek("(")) {
                    if (result instanceof AttachedPropertyExpression) {
                        throw error("Unexpected token", position, position + 1);
                    }
                    int start = result.span().start();
                    List<Expression> arguments = parseArguments();
                    result = new InvocationExpression(result, arguments,
                            new Span(start, position), source);
                    pathChain = false;
                    continue;
                }

                SelectionOperator selection;
                if (consume("::")) {
                    selection = SelectionOperator.OBSERVABLE;
                } else if (consume(".")) {
                    selection = SelectionOperator.VALUE;
                } else {
                    return result;
                }

                skipWhitespace();
                if (peek("(")) {
                    if (!pathChain) {
                        throw error("Identifier expected", position, position);
                    }
                    result = parseAttachedProperty(result, selection);
                    continue;
                }
                PathExpression member = parseNamedPath();
                result = new MemberExpression(result, member, selection,
                        new Span(result.span().start(), member.span().end()), source);
            }
        }

        private Expression parsePrimary() {
            skipWhitespace();
            if (atEnd()) {
                throw error("Expression expected", position, position);
            }
            int start = position;
            if (consume("::")) {
                if (peek("(")) {
                    return parseAttachedProperty(null, SelectionOperator.OBSERVABLE, start);
                }
                PathExpression selected = parseNamedPath();
                return new PathExpression("::" + selected.name(), selected.typeArguments(),
                        new Span(start, selected.span().end()), source);
            }
            char ch = source.charAt(position);
            if (ch == '(') {
                position++;
                Expression nested = parseExpression(0);
                skipWhitespace();
                require(")", "')' expected");
                return new GroupedExpression(nested, new Span(start, position), source);
            }
            if (ch == ':') {
                return parseContextSelector();
            }
            if (ch == '\'' || ch == '"') {
                return parseStringLiteral();
            }
            if (Character.isDigit(ch)) {
                return parseNumberLiteral();
            }
            PathExpression path = parseNamedPath();
            return switch (path.name()) {
                case "true" -> new LiteralExpression(LiteralKind.TRUE, path.span(), source);
                case "false" -> new LiteralExpression(LiteralKind.FALSE, path.span(), source);
                case "null" -> new LiteralExpression(LiteralKind.NULL, path.span(), source);
                default -> path;
            };
        }

        private ContextSelectorExpression parseContextSelector() {
            int start = position++;
            int nameStart = position;
            String name = readIdentifier();
            ContextSelectorKind kind = switch (name) {
                case "context" -> ContextSelectorKind.CONTEXT;
                case "root" -> ContextSelectorKind.ROOT;
                case "element" -> ContextSelectorKind.ELEMENT;
                case "parent" -> ContextSelectorKind.PARENT;
                default -> throw error("Unknown context selector", nameStart, position);
            };
            String typeName = null;
            Span typeSpan = null;
            Integer depth = null;
            if (kind == ContextSelectorKind.PARENT && skipWhitespaceAndConsume("<")) {
                skipWhitespace();
                int typeStart = position;
                typeName = readQualifiedIdentifier();
                int typeEnd = position;
                skipWhitespace();
                require(">", "'>' expected");
                typeSpan = new Span(typeStart, typeEnd);
            }
            if (kind == ContextSelectorKind.PARENT && skipWhitespaceAndConsume("(")) {
                skipWhitespace();
                int numberStart = position;
                if (peek("+") || peek("-")) {
                    position++;
                }
                int digitStart = position;
                while (!atEnd() && Character.isDigit(source.charAt(position))) {
                    position++;
                }
                if (digitStart == position) {
                    throw error("Parent depth expected", position, position);
                }
                try {
                    depth = Integer.parseInt(source.substring(numberStart, position));
                } catch (NumberFormatException ex) {
                    throw error("Parent depth is too large", numberStart, position);
                }
                skipWhitespace();
                require(")", "')' expected");
            }
            return new ContextSelectorExpression(kind, typeName, depth, typeSpan,
                    new Span(start, position), source);
        }

        private AttachedPropertyExpression parseAttachedProperty(
                @Nullable Expression receiver, @NotNull SelectionOperator operator) {
            return parseAttachedProperty(receiver, operator,
                    receiver != null ? receiver.span().start() : position);
        }

        private AttachedPropertyExpression parseAttachedProperty(
                @Nullable Expression receiver, @NotNull SelectionOperator operator, int start) {
            require("(", "'(' expected");
            skipWhitespace();
            int typeStart = position;
            String qualified = readQualifiedIdentifier();
            int separator = qualified.lastIndexOf('.');
            if (separator < 0) {
                throw error("'.' expected", typeStart, position);
            }
            skipWhitespace();
            require(")", "')' expected");
            return new AttachedPropertyExpression(receiver,
                    qualified.substring(0, separator), qualified.substring(separator + 1),
                    operator, new Span(start, position), source);
        }

        private PathExpression parseNamedPath() {
            int start = position;
            String name = readIdentifier();
            List<TypeArgument> arguments = speculateTypeArguments();
            return new PathExpression(name, arguments, new Span(start, position), source);
        }

        private List<TypeArgument> speculateTypeArguments() {
            skipWhitespace();
            if (!peek("<")) {
                return List.of();
            }
            int checkpoint = position;
            consume("<");
            skipWhitespace();
            if (peek(">")) {
                consume(">");
                if (isGenericFollower()) {
                    throw error("Type argument expected", checkpoint + 1, checkpoint + 1);
                }
                position = checkpoint;
                return List.of();
            }

            List<TypeArgument> result = new ArrayList<>();
            boolean missingAfterComma = false;
            while (true) {
                skipWhitespace();
                int argumentStart = position;
                if (!isIdentifierStart()) {
                    position = checkpoint;
                    return List.of();
                }
                if (!parseTypeName()) {
                    position = checkpoint;
                    return List.of();
                }
                int argumentEnd = position;
                result.add(new TypeArgument(source.substring(argumentStart, argumentEnd),
                        new Span(argumentStart, argumentEnd)));
                skipWhitespace();
                if (!consume(",")) {
                    break;
                }
                missingAfterComma = true;
                skipWhitespace();
                if (peek(">")) {
                    break;
                }
                missingAfterComma = false;
            }
            if (!consume(">")) {
                position = checkpoint;
                return List.of();
            }
            if (!isGenericFollower()) {
                position = checkpoint;
                return List.of();
            }
            if (missingAfterComma) {
                throw error("Type argument expected", position - 1, position - 1);
            }
            return List.copyOf(result);
        }

        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        private boolean parseTypeName() {
            if (!isIdentifierStart()) {
                return false;
            }
            readIdentifier();
            //noinspection WhileCanBeDoWhile
            while (consume(".")) {
                if (!isIdentifierStart()) {
                    return false;
                }
                readIdentifier();
            }
            if (consume("<")) {
                do {
                    skipWhitespace();
                    if (!parseTypeName()) {
                        return false;
                    }
                    skipWhitespace();
                } while (consume(","));

                return consume(">");
            }
            return true;
        }

        private boolean isGenericFollower() {
            int checkpoint = position;
            skipWhitespace();
            boolean result = atEnd() || peek("(") || peek(".") || peek("::")
                    || peek(")") || peek(",") || peek(";")
                    || peek("+") || peek("-") || peek("*") || peek("/")
                    || peek("<") || peek(">") || peek("=") || peek("!")
                    || peek("&") || peek("|") || peek("}");
            position = checkpoint;
            return result;
        }

        private List<Expression> parseArguments() {
            require("(", "'(' expected");
            skipWhitespace();
            if (consume(")")) {
                return List.of();
            }
            List<Expression> arguments = new ArrayList<>();
            while (true) {
                skipWhitespace();
                arguments.add(peek("{") ? parseMarkupExtension() : parseExpression(0));
                skipWhitespace();
                if (consume(")")) {
                    return List.copyOf(arguments);
                }
                require(",", "',' or ')' expected");
            }
        }

        private LiteralExpression parseMarkupExtension() {
            int start = position;
            int depth = 0;
            char quote = 0;
            boolean escaped = false;
            while (!atEnd()) {
                char ch = source.charAt(position++);
                if (quote != 0) {
                    if (escaped) {
                        escaped = false;
                    } else if (ch == '\\') {
                        escaped = true;
                    } else if (ch == quote) {
                        quote = 0;
                    }
                } else if (ch == '\'' || ch == '"') {
                    quote = ch;
                } else if (ch == '{') {
                    depth++;
                } else if (ch == '}' && --depth == 0) {
                    return new LiteralExpression(
                            LiteralKind.MARKUP_EXTENSION, new Span(start, position), source);
                }
            }
            throw error("Markup extension is not closed", start, position);
        }

        private LiteralExpression parseStringLiteral() {
            int start = position;
            char quote = source.charAt(position++);
            boolean escaped = false;
            while (!atEnd()) {
                char ch = source.charAt(position++);
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == quote) {
                    return new LiteralExpression(
                            LiteralKind.STRING, new Span(start, position), source);
                }
            }
            throw error("String literal is not closed", start, position);
        }

        private LiteralExpression parseNumberLiteral() {
            int start = position;
            while (!atEnd() && Character.isDigit(source.charAt(position))) {
                position++;
            }
            if (!atEnd() && source.charAt(position) == '.') {
                position++;
                while (!atEnd() && Character.isDigit(source.charAt(position))) {
                    position++;
                }
            }
            if (!atEnd() && (source.charAt(position) == 'e' || source.charAt(position) == 'E')) {
                int exponentStart = position++;
                if (!atEnd() && (source.charAt(position) == '+' || source.charAt(position) == '-')) {
                    position++;
                }
                int exponentDigits = position;
                while (!atEnd() && Character.isDigit(source.charAt(position))) {
                    position++;
                }
                if (exponentDigits == position) {
                    position = exponentStart;
                }
            }
            if (!atEnd() && "fFdDlL".indexOf(source.charAt(position)) >= 0) {
                position++;
            }
            return new LiteralExpression(LiteralKind.NUMBER, new Span(start, position), source);
        }

        private @NotNull String readQualifiedIdentifier() {
            int start = position;
            readIdentifier();
            //noinspection WhileCanBeDoWhile
            while (consume(".")) {
                readIdentifier();
            }
            return source.substring(start, position);
        }

        private @NotNull String readIdentifier() {
            if (!isIdentifierStart()) {
                throw error("Identifier expected", position, position);
            }
            int start = position++;
            while (!atEnd() && Character.isJavaIdentifierPart(source.charAt(position))) {
                position++;
            }
            return source.substring(start, position);
        }

        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        private boolean isIdentifierStart() {
            return !atEnd() && Character.isJavaIdentifierStart(source.charAt(position));
        }

        private @Nullable OperatorToken peekBinaryOperator() {
            for (OperatorToken token : OPERATORS) {
                if (peek(token.text)) {
                    return token;
                }
            }
            return null;
        }

        private void require(String token, String message) {
            if (!consume(token)) {
                throw error(message, position, position);
            }
        }

        private boolean consume(String token) {
            String sourceToken = sourceToken(token);
            if (!source.startsWith(sourceToken, position)) {
                return false;
            }
            position += sourceToken.length();
            return true;
        }

        private boolean skipWhitespaceAndConsume(@NotNull String token) {
            int checkpoint = position;
            skipWhitespace();
            if (consume(token)) {
                return true;
            }
            position = checkpoint;
            return false;
        }

        private boolean peek(String token) {
            return source.startsWith(sourceToken(token), position);
        }

        private @NotNull String sourceToken(@NotNull String token) {
            if ("<".equals(token) && source.startsWith("&lt;", position)) {
                return "&lt;";
            }
            if (">".equals(token) && source.startsWith("&gt;", position)) {
                return "&gt;";
            }
            return token;
        }

        private void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(source.charAt(position))) {
                position++;
            }
        }

        private boolean atEnd() {
            return position >= source.length();
        }

        private ParseException error(String message, int start, int end) {
            return new ParseException(message, new Span(start, Math.max(start, end)));
        }
    }

    private record OperatorToken(String text, BinaryOperator operator, int precedence) {
    }

    private static final List<OperatorToken> OPERATORS = List.of(
            new OperatorToken("!==", BinaryOperator.IDENTITY_NOT_EQUAL, 3),
            new OperatorToken("===", BinaryOperator.IDENTITY_EQUAL, 3),
            new OperatorToken("!=", BinaryOperator.NOT_EQUAL, 3),
            new OperatorToken("==", BinaryOperator.EQUAL, 3),
            new OperatorToken("&lt;=", BinaryOperator.LESS_OR_EQUAL, 4),
            new OperatorToken("&gt;=", BinaryOperator.GREATER_OR_EQUAL, 4),
            new OperatorToken("<=", BinaryOperator.LESS_OR_EQUAL, 4),
            new OperatorToken(">=", BinaryOperator.GREATER_OR_EQUAL, 4),
            new OperatorToken("&&", BinaryOperator.AND, 2),
            new OperatorToken("||", BinaryOperator.OR, 1),
            new OperatorToken("*", BinaryOperator.MULTIPLY, 6),
            new OperatorToken("/", BinaryOperator.DIVIDE, 6),
            new OperatorToken("+", BinaryOperator.ADD, 5),
            new OperatorToken("-", BinaryOperator.SUBTRACT, 5),
            new OperatorToken("&lt;", BinaryOperator.LESS, 4),
            new OperatorToken("&gt;", BinaryOperator.GREATER, 4),
            new OperatorToken("<", BinaryOperator.LESS, 4),
            new OperatorToken(">", BinaryOperator.GREATER, 4));
}
