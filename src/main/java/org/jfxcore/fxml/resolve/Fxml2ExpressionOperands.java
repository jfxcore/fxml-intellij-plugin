package org.jfxcore.fxml.resolve;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Decomposes an expression into the parts that resolve against the evaluation context: the
 * operands that name a value, and the type names that select the type a part is used with.
 *
 * <p>An expression that combines values with operators is not one path: resolving its text as a
 * whole would read the operators as path segments.  Both the validation of an expression and the
 * navigation of its parts therefore work from the parts this class yields, so that each part is
 * treated in its own right and carries the offset at which it stands in the source.
 */
public final class Fxml2ExpressionOperands {

    private Fxml2ExpressionOperands() {
    }

    /** Distinguishes the operands that resolve as a path from those that resolve as a call. */
    public enum OperandKind {
        /** A path, which may begin with a context selector, e.g. {@code :parent<VBox>.spacing}. */
        PATH,
        /**
         * The name an invocation invokes, i.e. the text preceding its argument list, which names
         * either a method (e.g. {@code String.format}) or a constructor (e.g. {@code Color}).
         * The values the invocation is passed are operands of their own.
         */
        FUNCTION_NAME
    }

    /**
     * One operand of an expression.
     *
     * @param kind   how the operand resolves
     * @param text   the operand's source text
     * @param offset offset of {@code text} within the text the operand's offsets are counted from
     */
    public record Operand(@NotNull OperandKind kind, @NotNull String text, int offset) {
    }

    /**
     * A type name that an expression names, either as the type argument of a path or invocation
     * or as the type of a {@code :parent} context selector.
     *
     * @param text   the type name, which may itself be parameterized (e.g. {@code Map<String, V>})
     * @param offset offset of {@code text} within the text the offsets are counted from
     */
    public record TypeName(@NotNull String text, int offset) {
    }

    /**
     * Returns the operands of an expression in source order.  Operators, groupings and literals
     * carry no operand of their own; the operands of an invocation's arguments are yielded after
     * the invocation itself, so that the invocation and the values it is passed each stand on
     * their own.
     *
     * @param expression the expression to decompose
     * @param base       offset that the returned offsets are counted from, i.e. the offset of
     *                   {@link Fxml2ExpressionParser.Expression#source()} within the text the
     *                   caller positions its results in
     */
    public static @NotNull List<Operand> operands(
            Fxml2ExpressionParser.@NotNull Expression expression, int base) {
        List<Operand> operands = new ArrayList<>(4);
        collectOperands(expression, base, operands);
        return List.copyOf(operands);
    }

    private static void collectOperands(
            Fxml2ExpressionParser.@NotNull Expression expression,
            int base,
            @NotNull List<Operand> operands) {

        switch (expression) {
            case Fxml2ExpressionParser.BinaryExpression binary -> {
                collectOperands(binary.left(), base, operands);
                collectOperands(binary.right(), base, operands);
            }
            case Fxml2ExpressionParser.UnaryExpression unary ->
                    collectOperands(unary.operand(), base, operands);
            case Fxml2ExpressionParser.GroupedExpression grouped ->
                    collectOperands(grouped.expression(), base, operands);
            case Fxml2ExpressionParser.LiteralExpression ignored -> { }
            case Fxml2ExpressionParser.InvocationExpression invocation -> {
                Fxml2ExpressionParser.Expression target = invocation.target();
                operands.add(new Operand(OperandKind.FUNCTION_NAME, target.text(),
                        base + target.span().start()));
                for (Fxml2ExpressionParser.Expression argument : invocation.arguments()) {
                    collectOperands(argument, base, operands);
                }
            }
            default -> operands.add(new Operand(OperandKind.PATH, expression.text(),
                    base + expression.span().start()));
        }
    }

    /**
     * Returns {@code true} when the expression is a single value that resolves as one path,
     * rather than one that combines values with operators.  A leading boolean operator belongs
     * to the notation and does not combine values.
     */
    public static boolean isPathExpression(Fxml2ExpressionParser.@NotNull Expression expression) {
        return switch (expression) {
            case Fxml2ExpressionParser.UnaryExpression unary ->
                    (unary.operator() == Fxml2ExpressionParser.UnaryOperator.NOT
                            || unary.operator() == Fxml2ExpressionParser.UnaryOperator.BOOLIFY)
                            && isPathExpression(unary.operand());
            case Fxml2ExpressionParser.PathExpression ignored -> true;
            case Fxml2ExpressionParser.MemberExpression ignored -> true;
            case Fxml2ExpressionParser.AttachedPropertyExpression ignored -> true;
            case Fxml2ExpressionParser.ContextSelectorExpression ignored -> true;
            case Fxml2ExpressionParser.InvocationExpression ignored -> true;
            case Fxml2ExpressionParser.GroupedExpression grouped ->
                    isPathExpression(grouped.expression());
            default -> false;
        };
    }

    /**
     * Returns every type name the expression names, in source order, at any depth of the
     * expression tree.  A name may itself be parameterized; splitting it into the names it
     * nests is left to {@link Fxml2TypeArgumentParser#allTypeNames}.
     *
     * @param base offset that the returned offsets are counted from
     */
    public static @NotNull List<TypeName> typeNames(
            Fxml2ExpressionParser.@NotNull Expression expression, int base) {
        List<TypeName> names = new ArrayList<>(2);
        collectTypeNames(expression, base, names);
        return List.copyOf(names);
    }

    private static void collectTypeNames(
            Fxml2ExpressionParser.@NotNull Expression expression,
            int base,
            @NotNull List<TypeName> names) {

        switch (expression) {
            case Fxml2ExpressionParser.BinaryExpression binary -> {
                collectTypeNames(binary.left(), base, names);
                collectTypeNames(binary.right(), base, names);
            }
            case Fxml2ExpressionParser.UnaryExpression unary ->
                    collectTypeNames(unary.operand(), base, names);
            case Fxml2ExpressionParser.GroupedExpression grouped ->
                    collectTypeNames(grouped.expression(), base, names);
            case Fxml2ExpressionParser.InvocationExpression invocation -> {
                collectTypeNames(invocation.target(), base, names);
                for (Fxml2ExpressionParser.Expression argument : invocation.arguments()) {
                    collectTypeNames(argument, base, names);
                }
            }
            case Fxml2ExpressionParser.MemberExpression member -> {
                collectTypeNames(member.receiver(), base, names);
                addTypeArguments(member.member(), base, names);
            }
            case Fxml2ExpressionParser.AttachedPropertyExpression attached -> {
                if (attached.receiver() != null) {
                    collectTypeNames(attached.receiver(), base, names);
                }
            }
            case Fxml2ExpressionParser.ContextSelectorExpression selector -> {
                String typeName = selector.typeName();
                Fxml2ExpressionParser.Span typeSpan = selector.typeSpan();
                if (typeName != null && typeSpan != null) {
                    names.add(new TypeName(typeName, base + typeSpan.start()));
                }
            }
            default -> addTypeArguments(expression, base, names);
        }
    }

    private static void addTypeArguments(
            Fxml2ExpressionParser.@NotNull Expression expression,
            int base,
            @NotNull List<TypeName> names) {

        for (Fxml2ExpressionParser.TypeArgument argument : expression.typeArguments()) {
            names.add(new TypeName(argument.text(), base + argument.span().start()));
        }
    }
}
