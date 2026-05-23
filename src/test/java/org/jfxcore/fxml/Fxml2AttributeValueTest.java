package org.jfxcore.fxml;

import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tests that attribute values are correctly validated.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2AttributeValueTest extends Fxml2TestBase {

    @BeforeAll
    void addArrayPropertyMock() {
        // A simple class with an Object[] property, used to test the list-value feature.
        getFixture().addClass("""
                package test;
                public class ArrayControl {
                    public ArrayControl() {}
                    public void setItems(Object[] items) {}
                    public Object[] getItems() { return null; }
                }
                """);
        // A generic class whose property type is the type parameter T.
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                import javafx.scene.control.Label;
                public class GenericLabel<T> extends Label {
                    private final ObjectProperty<T> item = new SimpleObjectProperty<>(this, "item");
                    public ObjectProperty<T> itemProperty() { return item; }
                    public T getItem() { return item.get(); }
                    public void setItem(T item) { this.item.set(item); }
                }
                """);
    }

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2AttributeValueInspection());
    }


    @Test
    void validEnumConstantProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button alignment="CENTER"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void invalidEnumConstantProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button alignment=<error descr="Cannot coerce 'INVALID' to Pos">"INVALID"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void usePrefSizeOnPrefHeightProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.Region",
                """
                  <Region prefHeight="USE_PREF_SIZE"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void usePrefSizeOnMaxHeightAndMaxWidthProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.Region",
                """
                  <Region maxHeight="USE_PREF_SIZE" maxWidth="USE_PREF_SIZE"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void useComputedSizeOnMinWidthProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.Region",
                """
                  <Region minWidth="USE_COMPUTED_SIZE"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void stringPropertyProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button text="Hello World"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void numericLiteralOnDoublePropertyProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.Region",
                """
                  <Region prefWidth="200" prefHeight="100"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void singleStyleClassProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button styleClass="my-style"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void multipleStyleClassesProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button styleClass="style1, style2"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void validStaticPropertyValueProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                """
                javafx.scene.control.Button
                javafx.scene.layout.VBox
                """,
                """
                  <VBox>
                    <Button VBox.vgrow="ALWAYS"/>
                  </VBox>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void invalidStaticPropertyValueProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                """
                javafx.scene.control.Button
                javafx.scene.layout.VBox
                """,
                """
                  <VBox>
                    <Button VBox.vgrow=<error descr="Cannot coerce 'ALWAYS2' to Priority">"ALWAYS2"</error>/>
                  </VBox>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void enumInPropertyElementTagResolvesWithoutError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView>
                    <selectionModel.selectionMode>MULTIPLE</selectionModel.selectionMode>
                  </ListView>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void invalidEnumInPropertyElementTagProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView>
                    <selectionModel.selectionMode><error descr="Cannot coerce 'INVALID' to SelectionMode">INVALID</error></selectionModel.selectionMode>
                  </ListView>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void validEnumInStaticPropertyElementTagProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                """
                javafx.scene.control.Button
                javafx.scene.layout.VBox
                """,
                """
                  <VBox>
                    <Button>
                      <VBox.vgrow>ALWAYS</VBox.vgrow>
                    </Button>
                  </VBox>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void invalidEnumInStaticPropertyElementTagProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                """
                javafx.scene.control.Button
                javafx.scene.layout.VBox
                """,
                """
                  <VBox>
                    <Button>
                      <VBox.vgrow><error descr="Cannot coerce 'FOO' to Priority">FOO</error></VBox.vgrow>
                    </Button>
                  </VBox>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void invalidStringOnDoublePropertyProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.TextArea",
                """
                  <TextArea minHeight=<error descr="Cannot coerce 'FOO' to double">"FOO"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void numericLiteralOnDoublePropertyProducesNoError2() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.TextArea",
                """
                  <TextArea minHeight="42.5"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void staticFieldOnDoublePropertyProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.TextArea",
                """
                  <TextArea minHeight="USE_PREF_SIZE"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void invalidStringOnIntPropertyProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                """
                javafx.scene.control.Button
                javafx.scene.layout.GridPane
                """,
                """
                  <GridPane>
                    <Button GridPane.columnIndex=<error descr="Cannot coerce 'FOO' to Integer">"FOO"</error>/>
                  </GridPane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void validIntOnIntPropertyProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                """
                javafx.scene.control.Button
                javafx.scene.layout.GridPane
                """,
                """
                  <GridPane>
                    <Button GridPane.columnIndex="2"/>
                  </GridPane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // fx:typeArguments type-substitution tests
    // -----------------------------------------------------------------------

    /**
     * When a generic class is used with {@code fx:typeArguments="Double"} and a property
     * whose type is the type parameter T receives a non-Double literal, the inspection must
     * report an error (a type-parameter bound to a concrete type argument must not be
     * treated as unknown when validating property values).
     */
    @Test
    void invalidLiteralForTypeParameterPropertyWithTypeArguments() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.GenericLabel",
                """
                  <GenericLabel fx:typeArguments="Double" item=<error descr="Cannot coerce 'foo' to Double">"foo"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A valid numeric literal for a Double-parameterised property must produce no error.
     */
    @Test
    void validDoubleForTypeParameterPropertyWithTypeArguments() {
        getFixture().configureByText("TestView2.fxml", fxml2(
                "test.GenericLabel",
                """
                  <GenericLabel fx:typeArguments="Double" item="3.14"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Without {@code fx:typeArguments} the type T is unknown; any literal is accepted
     * (no false positive).
     */
    @Test
    void literalForTypeParameterPropertyWithoutTypeArguments() {
        getFixture().configureByText("TestView3.fxml", fxml2(
                "test.GenericLabel",
                """
                  <GenericLabel item="anything"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // List value (comma-separated) tests
    // -----------------------------------------------------------------------

    /**
     * Comma-separated list value on an {@code Object[]} property -> no error.
     */
    @Test
    void listValue_objectArrayProperty_noCompilerLibrary_noError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.ArrayControl",
                """
                  <ArrayControl items="a, b, c"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Comma-separated list value on a collection property ({@code styleClass}) is valid.
     */
    @Test
    void listValue_collectionProperty_noVersionError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button styleClass="style1, style2"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Single (non-list) value on an {@code Object[]} property -> always valid.
     */
    @Test
    void listValue_objectArrayProperty_singleValue_noError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.ArrayControl",
                """
                  <ArrayControl items="single"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }
}
