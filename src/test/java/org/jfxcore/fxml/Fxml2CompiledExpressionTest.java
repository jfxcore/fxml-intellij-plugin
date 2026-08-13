package org.jfxcore.fxml;

import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tests for compiled expressions that combine values, as documented in
 * {@code markup-extension/expression/operators.md} and {@code function.md}: arithmetic, relational
 * and logical operators, truthiness conversions, invocations whose arguments are expressions of
 * their own, type arguments that select the type an invocation is made with, and the context
 * selectors that name the element an expression is evaluated against.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2CompiledExpressionTest extends Fxml2TestBase {

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2AttributeValueInspection());
    }

    @BeforeEach
    void addCodeBehind() {
        // Minimal generated base class (normally produced by the FXML compiler)
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public abstract class TestViewBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.property.DoubleProperty;
                import javafx.beans.property.SimpleDoubleProperty;
                import javafx.beans.property.SimpleStringProperty;
                import javafx.beans.property.StringProperty;
                public class TestView extends TestViewBase {
                    private final DoubleProperty shapeWidth = new SimpleDoubleProperty(this, "shapeWidth");
                    private final StringProperty caption = new SimpleStringProperty(this, "caption");
                    public DoubleProperty shapeWidthProperty() { return shapeWidth; }
                    public double getShapeWidth() { return shapeWidth.get(); }
                    public StringProperty captionProperty() { return caption; }
                    public String getCaption() { return caption.get(); }
                    public <T> String describe(T value) { return String.valueOf(value); }
                    private final ViewModel viewModel = new ViewModel();
                    public ViewModel getViewModel() { return viewModel; }
                }
                """);
        getFixture().addClass("""
                package test;
                public class ViewModel {
                    public <T> String describe(T value) { return String.valueOf(value); }
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                import javafx.scene.control.Label;
                public class TypedLabel<T> extends Label {
                    private final ObjectProperty<T> item = new SimpleObjectProperty<>(this, "item");
                    public ObjectProperty<T> itemProperty() { return item; }
                    public T getItem() { return item.get(); }
                    public void setItem(T value) { item.set(value); }
                }
                """);
    }

    /** Arithmetic operands are combined, and grouping overrides the operator precedence. */
    @Test
    void arithmeticExpressionProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.shape.Rectangle",
                """
                  <Rectangle width="${(shapeWidth + 20) * 0.5}" height="${shapeWidth / 4}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** A relational operator, a truthiness conversion and a logical operator yield a boolean. */
    @Test
    void relationalAndLogicalExpressionProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="Wide" visible="${shapeWidth > 40 &amp;&amp; !!caption}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** The arguments of an invocation are expressions of their own. */
    @Test
    void invocationWithExpressionArgumentsProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${String.format('Width: %.0f', shapeWidth * 0.7)}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** A constructor is invoked like a method, with its arguments in parentheses. */
    @Test
    void constructorInvocationProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.shape.Rectangle\njavafx.scene.paint.Color",
                """
                  <Rectangle width="20" fill="$Color(0.2, 0.4, 0.6, 1)"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** A type argument after the method name selects the type the method is invoked with. */
    @Test
    void invocationWithTypeArgumentProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${describe&lt;Double&gt;(shapeWidth)}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** A type argument applies to an invocation that is made on a receiver as well. */
    @Test
    void invocationOnAReceiverWithTypeArgumentProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${viewModel.describe&lt;Double&gt;(shapeWidth)}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** The type argument of the tag reaches the members of the generic class. */
    @Test
    void typeArgumentOfTheTagReachesItsMembers() {
        getFixture().configureByText("TestView.fxml", fxml(
                "test.TypedLabel",
                """
                  <TypedLabel fx:typeArguments="String" item="${caption}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** A path that does not resolve is reported where it stands among the operands. */
    @Test
    void unresolvedOperandProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.shape.Rectangle",
                """
                  <Rectangle width="${(shapeWidth + <error descr="'missing' in test.TestView cannot be resolved">missing</error>) * 0.5}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** A path that does not resolve inside an argument is reported on that argument. */
    @Test
    void unresolvedInvocationArgumentProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${String.format('%s', <error descr="'missing' in test.TestView cannot be resolved">missing</error> + caption)}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** A typed parent selector evaluates the path against the nearest ancestor of that type. */
    @Test
    void typedParentSelectorProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.VBox\njavafx.scene.shape.Rectangle",
                """
                  <VBox spacing="10">
                    <Rectangle width="${:parent&lt;VBox&gt;.spacing * 8}" height="4"/>
                  </VBox>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }
}
