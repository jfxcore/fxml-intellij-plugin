package org.jfxcore.fxml;

import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tests that correspond to the fxml2 compiler's InlineParserTest
 * ({@code src/test/java/org/jfxcore/compiler/parse/InlineParserTest.java}).
 *
 * <p>The InlineParser is responsible for parsing curly-brace expressions that
 * appear inside XML attribute values, e.g.:
 * <pre>
 *   prefWidth="{fx:Observe message}"
 *   prefWidth="$message"
 *   prefWidth="${message}"
 *   prefWidth="#{message}"
 * </pre>
 *
 * <p>Most failures of the InlineParser result in a parse-time exception before
 * any semantic resolution occurs, so the plugin highlights them as syntax
 * errors in the attribute value.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2InlineParserTest extends Fxml2TestBase {

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2AttributeValueInspection());
    }

    // -----------------------------------------------------------------------
    // Valid expressions, no error expected
    // -----------------------------------------------------------------------

    /** Compiler: Parse_Simple_Identifier: {fx:Evaluate foo} */
    @Test
    void simpleFxEvaluateIdentifierProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Evaluate message}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** Compiler: Compact_Syntax_Is_Expanded: $foo.bar.baz -> fx:Evaluate */
    @Test
    void dollarCompactEvaluateIsValid() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="$message"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** Compiler: Compact_Syntax_Is_Expanded: ${foo.bar.baz} -> fx:Observe */
    @Test
    void dollarBraceCompactObserveIsValid() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="${message}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** Compiler: Compact_Syntax_Is_Expanded: #{foo.bar.baz} -> fx:Synchronize */
    @Test
    void hashBraceCompactSynchronizeIsValid() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="#{message}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Compiler: InlineParser_Is_Only_Used_For_Attribute_Values.
     * Inside an element body, {@code {fx:foo bar}} is treated as literal text -
     * no inline-parser error should be reported.
     */
    @Test
    void inlineParserNotAppliedToElementTextContent() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label><prefHeight>{fx:foo bar}</prefHeight></Label>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A backslash followed by {@code {foo}} is a binding-expression escape: the
     * value {@code \{foo}} is a literal string, not an inline expression.
     * No error must be reported.
     */
    @Test
    void backslashEscapedOpenCurlyProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="\\{foo}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Observable-selector (::) compact syntax: positive cases
    // -----------------------------------------------------------------------

    /**
     * Compiler: Compact_Syntax_With_ObservableSelector_Is_Expanded: $::foo::bar.
     * Attribute value with observable-selector path notation should not cause a
     * plugin error (resolution may fail at a later stage).
     */
    @Test
    void dollarObservableSelectorCompactSyntaxProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="$::message"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Content syntax (..): positive cases
    // -----------------------------------------------------------------------

    /**
     * Compiler: Compact_Content_Syntax_Is_Expanded: $..foo -> fx:Evaluate with content expansion.
     */
    @Test
    void dollarContentCompactSyntaxProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button styleClass="$..message"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Invalid / malformed inline expressions
    // -----------------------------------------------------------------------

    /**
     * {@code {fx:Evaluate.foo.bar}}: a namespace-prefixed identifier must not be
     * fully qualified -> UNEXPECTED_TOKEN.
     */
    @Test
    void fullyQualifiedNamespacedIdentifierProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text=<error descr="Unexpected token">"{fx:Evaluate.foo.bar}"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Compiler: Invalid_Intrinsic_Namespace_Fails.
     * {@code {bar:evaluate foo}} where {@code bar} is not the declared fx-namespace prefix
     * -> UNKNOWN_NAMESPACE.
     */
    @Test
    void unknownNamespaceInInlineExpressionProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text=<error descr="Unknown XML namespace: bar">"{bar:evaluate message}"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Compiler: SyntaxMapping_Cannot_Be_Parameterized.
     * {@code ${<Foo>bar}}: the $-shorthand cannot have a type witness.
     */
    @Test
    void typeWitnessOnDollarSyntaxProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text=<error descr="Identifier expected">"${<Foo>bar}"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Angle-bracket type context selector (new syntax: parent<Type> and parent<Type>[N])
    // -----------------------------------------------------------------------

    /**
     * Compiler: {@code ${parent<VBox>/prefWidth}}: type context selector using the new
     * angle-bracket syntax should be parsed as a valid binding expression.
     * The {@code &lt;VBox&gt;} XML-entity form is used here so the attribute value is
     * well-formed XML; {@code XmlAttributeValue.getValue()} returns the unescaped form.
     */
    @Test
    void parentTypeContextSelectorBracedObserveIsValid() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.VBox",
                """
                  <VBox>
                    <VBox prefWidth="${parent&lt;VBox&gt;/prefWidth}"/>
                  </VBox>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Compiler: {@code $parent<VBox>/prefWidth}: compact {@code $}-notation with the new
     * angle-bracket type context selector must NOT be rejected as a malformed type witness.
     */
    @Test
    void parentTypeContextSelectorDollarCompactIsValid() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.VBox",
                """
                  <VBox>
                    <VBox prefWidth="$parent&lt;VBox&gt;/prefWidth"/>
                  </VBox>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Compiler: {@code ${parent<VBox>[1]/prefWidth}}: type context selector with
     * numeric index uses the new {@code parent<Type>[N]} syntax.
     */
    @Test
    void parentTypeAndIndexContextSelectorIsValid() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.VBox",
                """
                  <VBox>
                    <VBox>
                      <VBox prefWidth="${parent&lt;VBox&gt;[1]/prefWidth}"/>
                    </VBox>
                  </VBox>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Compiler: Missing_Close_Angle_Bracket_Fails for type witnesses.
     * {@code $foo<String}: missing {@code >}.
     */
    @Test
    void missingCloseAngleBracketInTypeWitnessProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text=<error descr="'>' expected">"$message<String"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Missing binding path: compiler: ContentExpressionTransform
    // -----------------------------------------------------------------------

    /**
     * Compiler: Path_Must_Be_Specified_Prefix_Notation.
     * {@code ${}}: empty braced observe compact form has no path.
     */
    @Test
    void emptyDollarBraceProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label prefHeight=<error descr="fx:Observe.source must be specified">"${}"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code #{}}: empty braced synchronize compact form has no path.
     */
    @Test
    void emptyHashBraceProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label prefHeight=<error descr="fx:Synchronize.source must be specified">"#{}"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Compiler: Path_Must_Be_Specified_Long_Notation.
     * {@code {fx:Synchronize}}: long form with no path argument.
     */
    @Test
    void fxSynchronizeNoPathProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label prefHeight=<error descr="fx:Synchronize.source must be specified">"{fx:Synchronize}"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code {fx:Observe}}: long form with no path argument.
     */
    @Test
    void fxObserveNoPathProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label prefHeight=<error descr="fx:Observe.source must be specified">"{fx:Observe}"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // fx:Push / >{x}: reverse binding
    // -----------------------------------------------------------------------

    /** Compact {@code >{path}} notation (reverse binding) is syntactically valid. */
    @Test
    void angleBraceCompactPushIsValid() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text=">{message}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** Compact {@code >{..path}} notation (reverse content binding) is syntactically valid. */
    @Test
    void angleBraceCompactPushContentIsValid() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.VBox",
                """
                  <VBox styleClass=">{..items}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** Long-form {@code {fx:Push source}} notation is syntactically valid. */
    @Test
    void fxPushLongFormIsValid() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Push message}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** Long-form {@code {fx:Push source=path}} with explicit {@code source=} keyword is valid. */
    @Test
    void fxPushWithExplicitSourceKeywordIsValid() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Push source=message}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** {@code >{}}: empty braced push compact form has no path. */
    @Test
    void emptyAngleBraceProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label prefHeight=<error descr="fx:Push.source must be specified">">{}"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** {@code {fx:Push}}: long form with no path argument. */
    @Test
    void fxPushNoPathProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label prefHeight=<error descr="fx:Push.source must be specified">"{fx:Push}"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }
}
