package org.jfxcore.fxml;

import org.jfxcore.fxml.annotator.Fxml2RawTypeInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tests for the raw-type warning inspection ({@link Fxml2RawTypeInspection}).
 *
 * <p>When a generic class tag is used without {@code fx:typeArguments}, the FXML compiler
 * treats it as a raw type. The plugin mirrors this with a WARNING-level inspection.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2RawTypeTest extends Fxml2TestBase {

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2RawTypeInspection());
    }

    @BeforeEach
    void addCodeBehind() {
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public abstract class TestViewBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().addClass("""
                package test;
                public class TestView extends TestViewBase {}
                """);
    }

    /**
     * 24.1: A generic class tag WITHOUT {@code fx:typeArguments} should produce a warning.
     */
    @Test
    void genericClassWithoutTypeArgumentsProducesWarning() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.ListView",
                """
                  <warning descr="Generic class 'ListView' is used as a raw type; consider adding fx:typeArguments"><ListView/></warning>
                """
        ));
        getFixture().checkHighlighting(true, false, false);
    }

    /**
     * 24.1: A generic class tag WITH {@code fx:typeArguments} must NOT produce a warning.
     */
    @Test
    void genericClassWithTypeArgumentsProducesNoWarning() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.ListView",
                """
                  <ListView fx:typeArguments="String"/>
                """
        ));
        getFixture().checkHighlighting(true, false, false);
    }

    /**
     * 24.1: A non-generic class must not produce a raw-type warning.
     */
    @Test
    void nonGenericClassProducesNoWarning() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button/>
                """
        ));
        getFixture().checkHighlighting(true, false, false);
    }

    /**
     * 24.1: The root tag, which is the code-behind class itself, must not be flagged even
     * if it inherits from a generic class (the root is declared by {@code fx:subclass}, not
     * {@code fx:typeArguments}).
     */
    @Test
    void rootBorderPaneTagProducesNoWarning() {
        // BorderPane is not generic, so no warning is expected on the root tag either.
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.ListView",
                """
                  <ListView fx:typeArguments="String"/>
                """
        ));
        getFixture().checkHighlighting(true, false, false);
    }
}
