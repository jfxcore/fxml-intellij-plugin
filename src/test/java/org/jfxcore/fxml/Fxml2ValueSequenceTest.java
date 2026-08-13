package org.jfxcore.fxml;

import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tests for attribute values that denote a sequence of values rather than a single value.
 *
 * <p>When the target of an attribute is a collection, an array, or a type that can be
 * implicitly constructed from multiple named arguments, the attribute value is a
 * comma-separated list. Each item of the list is resolved independently against the
 * required item type, and each item can either be a literal value or a value-producing
 * markup extension.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2ValueSequenceTest extends Fxml2TestBase {

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
        // Code-behind supplying numeric values for list items
        getFixture().addClass("""
                package test;
                import javafx.beans.property.DoubleProperty;
                import javafx.beans.property.SimpleDoubleProperty;
                public class TestView extends TestViewBase {
                    private final DoubleProperty inset = new SimpleDoubleProperty(this, "inset");
                    public DoubleProperty insetProperty() { return inset; }
                    public double getInset() { return inset.get(); }
                    public void setInset(double v) { inset.set(v); }
                }
                """);
    }

    // -----------------------------------------------------------------------
    // Implicit construction from a comma-separated list
    // -----------------------------------------------------------------------

    /** A list of literal values maps to the four named constructor arguments of Insets. */
    @Test
    void literalItemsInImplicitConstructorListProduceNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane padding="10, 20, 10, 20"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** A single markup extension supplies the single-argument Insets constructor. */
    @Test
    void singleExpressionForImplicitConstructorProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane padding="$inset"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** Items of an implicit-constructor list can be markup extensions. */
    @Test
    void expressionItemsInImplicitConstructorListProduceNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane padding="10, $inset, 10, $inset"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** A list whose length matches no constructor is still an error. */
    @Test
    void expressionItemsInImplicitConstructorListOfInvalidLengthProduceError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane padding=<error descr="'10, $inset' is not a valid value for padding">"10, $inset"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Collection items
    // -----------------------------------------------------------------------

    /** Literal items of a collection property are converted to the element type. */
    @Test
    void literalItemsInCollectionListProduceNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.shape.Polygon",
                """
                  <Polygon points="0, 0, 50, 100, 100, 50"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** Items of a collection property can be markup extensions. */
    @Test
    void expressionItemsInCollectionListProduceNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.shape.Polygon",
                """
                  <Polygon points="0, 0, $inset, 100"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** A literal item that cannot be converted to the element type is an error on that item. */
    @Test
    void invalidLiteralItemInCollectionListProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.shape.Polygon",
                """
                  <Polygon points="0, <error descr="Cannot coerce 'foo' to Double">foo</error>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** The error marks the failing item, not the items that convert. */
    @Test
    void invalidLastItemInCollectionListIsReportedOnThatItem() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.shape.Polygon",
                """
                  <Polygon points="0, 50, 100, <error descr="Cannot coerce 'bar' to Double">bar</error>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** An item of an implicit-constructor list is reported against its parameter type. */
    @Test
    void invalidItemInImplicitConstructorListIsReportedOnThatItem() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane padding="10, <error descr="Cannot coerce 'wide' to double">wide</error>, 10, 20"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Non-list targets
    // -----------------------------------------------------------------------

    /** A comma has no special meaning when the target is neither a collection nor an array. */
    @Test
    void commaInScalarPropertyValueIsLiteral() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="hello, world"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }
}
