package org.jfxcore.fxml.resolve;

import org.jfxcore.fxml.resolve.Fxml2ExpressionParser.BinaryExpression;
import org.jfxcore.fxml.resolve.Fxml2ExpressionParser.BinaryOperator;
import org.jfxcore.fxml.resolve.Fxml2ExpressionParser.AttachedPropertyExpression;
import org.jfxcore.fxml.resolve.Fxml2ExpressionParser.ContextSelectorExpression;
import org.jfxcore.fxml.resolve.Fxml2ExpressionParser.ContextSelectorKind;
import org.jfxcore.fxml.resolve.Fxml2ExpressionParser.Expression;
import org.jfxcore.fxml.resolve.Fxml2ExpressionParser.InvocationExpression;
import org.jfxcore.fxml.resolve.Fxml2ExpressionParser.MemberExpression;
import org.jfxcore.fxml.resolve.Fxml2ExpressionParser.UnaryExpression;
import org.jfxcore.fxml.resolve.Fxml2ExpressionParser.UnaryOperator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Fxml2ExpressionParserTest {

    @Test
    void operatorPrecedenceMatchesTheLanguage() {
        BinaryExpression logicalOr = binary("a + b * c < d == ready && valid || visible", BinaryOperator.OR);
        BinaryExpression logicalAnd = assertInstanceOf(BinaryExpression.class, logicalOr.left());
        assertEquals(BinaryOperator.AND, logicalAnd.operator());
        BinaryExpression equality = assertInstanceOf(BinaryExpression.class, logicalAnd.left());
        assertEquals(BinaryOperator.EQUAL, equality.operator());
        BinaryExpression relation = assertInstanceOf(BinaryExpression.class, equality.left());
        assertEquals(BinaryOperator.LESS, relation.operator());
        BinaryExpression addition = assertInstanceOf(BinaryExpression.class, relation.left());
        assertEquals(BinaryOperator.ADD, addition.operator());
        assertEquals(BinaryOperator.MULTIPLY,
                assertInstanceOf(BinaryExpression.class, addition.right()).operator());
    }

    @Test
    void binaryOperatorsAssociateLeftToRight() {
        BinaryExpression outer = binary("a - b - c", BinaryOperator.SUBTRACT);
        assertEquals(BinaryOperator.SUBTRACT,
                assertInstanceOf(BinaryExpression.class, outer.left()).operator());
    }

    @Test
    void groupingOverridesPrecedence() {
        BinaryExpression multiplication = binary("(a + b) * c", BinaryOperator.MULTIPLY);
        assertEquals("(a + b)", multiplication.left().text());
    }

    @Test
    void unaryOperatorsAreDistinguishedFromBinaryOperators() {
        UnaryExpression unary = assertInstanceOf(UnaryExpression.class,
                Fxml2ExpressionParser.parse("!-+value"));
        assertEquals(UnaryOperator.NOT, unary.operator());
        assertEquals(UnaryOperator.NEGATE,
                assertInstanceOf(UnaryExpression.class, unary.operand()).operator());
        assertEquals(UnaryOperator.PLUS,
                assertInstanceOf(UnaryExpression.class,
                        assertInstanceOf(UnaryExpression.class, unary.operand()).operand()).operator());
    }

    @Test
    void currentContextSelectorsHaveTypedStructureAndExactSpans() {
        assertSelector(":context", ContextSelectorKind.CONTEXT, null, null);
        assertSelector(":root", ContextSelectorKind.ROOT, null, null);
        assertSelector(":element", ContextSelectorKind.ELEMENT, null, null);
        assertSelector(":parent", ContextSelectorKind.PARENT, null, null);
        assertSelector(":parent(0)", ContextSelectorKind.PARENT, null, 0);
        assertSelector(":parent<Pane>(2)", ContextSelectorKind.PARENT, "Pane", 2);
        assertSelector(":parent<Pane>(+3)", ContextSelectorKind.PARENT, "Pane", 3);
        assertSelector(":parent(-12)", ContextSelectorKind.PARENT, null, -12);
        assertSelector(":parent <Pane> (2)", ContextSelectorKind.PARENT, "Pane", 2);

        MemberExpression member = assertInstanceOf(MemberExpression.class,
                Fxml2ExpressionParser.parse(":parent<Pane>.width"));
        assertEquals(":parent<Pane>", member.receiver().text());
        assertEquals("width", member.member().text());

        ContextSelectorExpression expr = Fxml2ExpressionParser.parseLeadingContextSelector(":element.");
        assertNotNull(expr);
        assertEquals(":element", expr.text());
    }

    @Test
    void numericLiteralsStopBeforeBinarySigns() {
        assertEquals(BinaryOperator.ADD, binary("1+2", BinaryOperator.ADD).operator());
        assertEquals(BinaryOperator.SUBTRACT, binary("1e2-3", BinaryOperator.SUBTRACT).operator());
        assertInstanceOf(Fxml2ExpressionParser.LiteralExpression.class,
                Fxml2ExpressionParser.parse("1e-2"));
    }

    @Test
    void attachedPropertySegmentsMatchCompilerExpressions() {
        AttachedPropertyExpression leading = assertInstanceOf(AttachedPropertyExpression.class,
                Fxml2ExpressionParser.parse("::(GridPane.rowIndex)"));
        assertEquals(Fxml2ExpressionParser.SelectionOperator.OBSERVABLE, leading.operator());
        assertEquals("GridPane", leading.declaringType());
        assertEquals("rowIndex", leading.property());

        AttachedPropertyExpression selected = assertInstanceOf(AttachedPropertyExpression.class,
                Fxml2ExpressionParser.parse(":context.(GridPane.rowIndex)"));
        assertInstanceOf(ContextSelectorExpression.class, selected.receiver());
        assertThrows(Fxml2ExpressionParser.ParseException.class,
                () -> Fxml2ExpressionParser.parse("factory().(GridPane.rowIndex)"));
    }

    @Test
    void markupExtensionsAreAcceptedOnlyAsCompleteInvocationArguments() {
        InvocationExpression invocation = assertInstanceOf(InvocationExpression.class,
                Fxml2ExpressionParser.parse("f(value, {StaticResource key})"));
        assertEquals(Fxml2ExpressionParser.LiteralKind.MARKUP_EXTENSION,
                assertInstanceOf(Fxml2ExpressionParser.LiteralExpression.class,
                        invocation.arguments().get(1)).kind());
        assertThrows(Fxml2ExpressionParser.ParseException.class,
                () -> Fxml2ExpressionParser.parse("f({StaticResource key} + value)"));
    }

    @Test
    void selectorIsACompletePrimary() {
        BinaryExpression identity = binary(":parent === owner", BinaryOperator.IDENTITY_EQUAL);
        assertInstanceOf(ContextSelectorExpression.class, identity.left());
    }

    @Test
    void literalKeywordsAreOnlyLiteralsInPrimaryPosition() {
        assertInstanceOf(Fxml2ExpressionParser.LiteralExpression.class,
                Fxml2ExpressionParser.parse("true"));
        MemberExpression qualified = assertInstanceOf(MemberExpression.class,
                Fxml2ExpressionParser.parse(":element.true"));
        assertEquals("true", qualified.member().text());
    }

    @Test
    void repeatedPostfixMemberAndInvocationExpressionsAreSupported() {
        MemberExpression qux = assertInstanceOf(MemberExpression.class,
                Fxml2ExpressionParser.parse("foo(a).bar<T>.baz(c).qux"));
        InvocationExpression baz = assertInstanceOf(InvocationExpression.class, qux.receiver());
        assertEquals(1, baz.arguments().size());
        assertEquals(List.of("c"), baz.arguments().stream().map(Expression::text).toList());
    }

    @Test
    void onlyNamedPathTargetsCanBeInvoked() {
        assertInvocationRejectedAt("foo()()", 5);
        assertInvocationRejectedAt("(factory())()", 11);
        assertInvocationRejectedAt(":context()", 8);
        assertInvocationRejectedAt(":element()", 8);
        assertInvocationRejectedAt(":root()", 5);
        assertInvocationRejectedAt("1()", 1);

        assertInstanceOf(InvocationExpression.class, Fxml2ExpressionParser.parse("foo().bar()"));
        assertInstanceOf(MemberExpression.class, Fxml2ExpressionParser.parse("(factory()).value"));
        assertInstanceOf(InvocationExpression.class, Fxml2ExpressionParser.parse("::method()"));
        assertInstanceOf(InvocationExpression.class,
                Fxml2ExpressionParser.parse(":context.Type()"));
        assertInstanceOf(InvocationExpression.class,
                Fxml2ExpressionParser.parse("pane.(Owner.value).method()"));
    }

    @Test
    void genericPostfixSpeculationUsesTheCompleteTypeListAndFollower() {
        InvocationExpression twoArguments = assertInstanceOf(InvocationExpression.class,
                Fxml2ExpressionParser.parse("m(a < b, c > d)"));
        assertEquals(List.of("a < b", "c > d"),
                twoArguments.arguments().stream().map(Expression::text).toList());

        InvocationExpression oneArgument = assertInstanceOf(InvocationExpression.class,
                Fxml2ExpressionParser.parse("n(a < b, c > +d)"));
        assertEquals(1, oneArgument.arguments().size());
        assertEquals("a < b, c > +d", oneArgument.arguments().getFirst().text());
        BinaryExpression addition = assertInstanceOf(BinaryExpression.class,
                oneArgument.arguments().getFirst());
        assertEquals(BinaryOperator.ADD, addition.operator());
        assertEquals(List.of("b", "c"), addition.left().typeArguments().stream()
                .map(Fxml2ExpressionParser.TypeArgument::text).toList());

        assertEquals(List.of("a < b", "c > d"),
                Fxml2BindingPathResolver.functionArguments("m(a < b, c > d)").stream()
                        .map(Fxml2BindingPathResolver.FunctionArgument::text).toList());
        assertEquals(List.of("a < b, c > +d"),
                Fxml2BindingPathResolver.functionArguments("n(a < b, c > +d)").stream()
                        .map(Fxml2BindingPathResolver.FunctionArgument::text).toList());
    }

    @Test
    void genericPostfixAmbiguitiesMatchCompilerExamples() {
        assertInstanceOf(BinaryExpression.class, Fxml2ExpressionParser.parse("a < b > c"));
        assertEquals(BinaryOperator.ADD, binary("a < b > +c", BinaryOperator.ADD).operator());
        assertInstanceOf(InvocationExpression.class, Fxml2ExpressionParser.parse("a < b > (c)"));
        assertInstanceOf(BinaryExpression.class, Fxml2ExpressionParser.parse("a < b + c > (d)"));
        assertThrows(Fxml2ExpressionParser.ParseException.class,
                () -> Fxml2ExpressionParser.parse("a<>(c)"));
        assertThrows(Fxml2ExpressionParser.ParseException.class,
                () -> Fxml2ExpressionParser.parse("a<T,>(c)"));
        assertThrows(Fxml2ExpressionParser.ParseException.class,
                () -> Fxml2ExpressionParser.parse("a<String[]>(c)"));
    }

    @Test
    void locatesCaretInsideIncompleteTypeWitnesses() {
        var first = Fxml2ExpressionParser.locateTypeArgumentCaret("convert<Str");
        assertTrue(first.isPresent());
        assertEquals("convert", first.orElseThrow().ownerName());
        assertEquals("Str", first.orElseThrow().prefix());
        assertEquals(0, first.orElseThrow().argumentIndex());
        assertEquals(1, first.orElseThrow().depth());

        var second = Fxml2ExpressionParser.locateTypeArgumentCaret("Box<String, Int");
        assertTrue(second.isPresent());
        assertEquals("Int", second.orElseThrow().prefix());
        assertEquals(1, second.orElseThrow().argumentIndex());

        var nested = Fxml2ExpressionParser.locateTypeArgumentCaret("convert<List<Str");
        assertTrue(nested.isPresent());
        assertEquals("List", nested.orElseThrow().ownerName());
        assertEquals("Str", nested.orElseThrow().prefix());
        assertEquals(2, nested.orElseThrow().depth());

        var encoded = Fxml2ExpressionParser.locateTypeArgumentCaret("convert&lt;java.lang.Str");
        assertTrue(encoded.isPresent());
        assertEquals("java.lang.Str", encoded.orElseThrow().prefix());
    }

    @Test
    void comparisonPrefixesAreNotTypeWitnessCompletionContexts() {
        assertTrue(Fxml2ExpressionParser.locateTypeArgumentCaret("a < Str").isEmpty());
        assertTrue(Fxml2ExpressionParser.locateTypeArgumentCaret("a < b + C").isEmpty());
        assertTrue(Fxml2ExpressionParser.locateTypeArgumentCaret("convert<String>.").isEmpty());
    }

    /**
     * An attribute value spells {@code &&} in its escaped form, which is the only form XML admits
     * for it.
     */
    @Test
    void escapedLogicalAndIsAnOperator() {
        BinaryExpression and = binary("ready &amp;&amp; valid", BinaryOperator.AND);
        assertEquals("ready", and.left().text());
        assertEquals("valid", and.right().text());
    }

    /** A name that carries no type argument list ends where its identifier ends. */
    @Test
    void aPathSpanEndsWithItsName() {
        BinaryExpression product = binary("width * 0.7", BinaryOperator.MULTIPLY);
        assertEquals("width", product.left().text());
        assertEquals(5, product.left().span().end());
    }

    private static BinaryExpression binary(String text, BinaryOperator operator) {
        BinaryExpression result = assertInstanceOf(BinaryExpression.class,
                Fxml2ExpressionParser.parse(text));
        assertEquals(operator, result.operator());
        return result;
    }

    private static void assertInvocationRejectedAt(String text, int openingParenthesis) {
        Fxml2ExpressionParser.ParseException exception = assertThrows(
                Fxml2ExpressionParser.ParseException.class,
                () -> Fxml2ExpressionParser.parse(text));
        assertEquals(new Fxml2ExpressionParser.Span(openingParenthesis, openingParenthesis + 1),
                exception.span());
    }

    private static void assertSelector(String text, ContextSelectorKind kind,
                                       String type, Integer depth) {
        ContextSelectorExpression selector = assertInstanceOf(ContextSelectorExpression.class,
                Fxml2ExpressionParser.parse(text));
        assertEquals(kind, selector.kind());
        assertEquals(type, selector.typeName());
        assertEquals(depth, selector.depth());
        assertEquals(0, selector.span().start());
        assertEquals(text.length(), selector.span().end());
    }
}
