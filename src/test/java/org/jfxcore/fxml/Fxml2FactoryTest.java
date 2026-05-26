package org.jfxcore.fxml;

import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.jfxcore.fxml.annotator.Fxml2FxAttributeInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tests for the {@code fx:factory} intrinsic attribute.
 *
 * <p>The {@code fx:factory} attribute initializes an element using a static factory
 * method instead of a constructor. The method must be accessible and parameterless.
 * Type witnesses may be provided after the method name
 * (e.g. {@code fx:factory="observableArrayList<String>"}).
 *
 * <p>Corresponds to {@code reference/factory.md} in the FXML compiler documentation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2FactoryTest extends Fxml2TestBase {

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2AttributeValueInspection(), new Fxml2FxAttributeInspection());
    }

    // -----------------------------------------------------------------------
    // Positive cases
    // -----------------------------------------------------------------------

    /**
     * Doc example: {@code <FXCollections fx:factory="observableArrayList" fx:id="list1">}.
     * A valid factory method name on an imported class should produce no error.
     */
    @Test
    void validFactoryMethodProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.collections.FXCollections\njavafx.scene.control.ListView",
                """
                  <FXCollections fx:factory="observableArrayList" fx:id="list1"/>
                  <ListView items="$list1"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Doc example: {@code fx:factory="observableArrayList<String>"}: a factory method
     * with a type witness should be accepted without error.
     */
    @Test
    void factoryMethodWithTypeWitnessProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.collections.FXCollections\njavafx.scene.control.ListView\njava.lang.String",
                """
                  <FXCollections fx:factory="observableArrayList&lt;String&gt;" fx:id="list1">
                    <String>foo</String>
                    <String>bar</String>
                  </FXCollections>
                  <ListView items="$list1"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code fx:factory} may only appear on the root element or inside {@code <fx:define>};
     * check that using it in a plain child context does not crash the plugin.
     */
    @Test
    void factoryMethodInsideFxDefineProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.collections.FXCollections\njavafx.scene.control.ListView\njava.lang.String",
                """
                  <fx:define>
                    <FXCollections fx:factory="observableArrayList" fx:id="list1">
                      <String>foo</String>
                      <String>bar</String>
                    </FXCollections>
                  </fx:define>
                  <ListView items="$list1"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Negative cases
    // -----------------------------------------------------------------------

    /**
     * An unknown factory method name should produce an error.
     */
    @Test
    void unknownFactoryMethodProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.collections.FXCollections",
                """
                  <FXCollections fx:factory=<error descr="Cannot resolve factory method 'nonExistentFactory' in javafx.collections.FXCollections">"nonExistentFactory"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code fx:factory} on a non-root child element (where context is a known tag type)
     * with an unknown method should produce an error, not crash.
     */
    @Test
    void unknownFactoryMethodOnChildElementDoesNotCrash() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.collections.FXCollections",
                """
                  <FXCollections fx:factory=<error descr="Cannot resolve factory method 'doesNotExist' in javafx.collections.FXCollections">"doesNotExist"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }
}
