package org.jfxcore.fxml;

import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tests for advanced binding-path features:
 * <ul>
 *   <li>8.3 Attached-property selection: {@code (ClassName.propertyName)}</li>
 *   <li>8.4 Generic type witness: {@code methodName&lt;TypeArg&gt;}</li>
 * </ul>
 *
 * <p>Corresponds to {@code binding/binding-path.md} in the FXML compiler documentation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2BindingPathAdvancedTest extends Fxml2TestBase {

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2AttributeValueInspection());
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
        // Use a unique class name to avoid conflicts with other test classes that also
        // define test.TestView. The fx:subclass attribute in the FXML references this class.
        getFixture().addClass("""
                package test;
                import javafx.scene.control.Label;
                import javafx.beans.property.StringProperty;
                import javafx.beans.property.SimpleStringProperty;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                public class AdvancedTestView extends TestViewBase {
                    /** A label exposed as a field for attached-property tests. */
                    public Label myLabel = new Label();

                    private final StringProperty message =
                            new SimpleStringProperty(this, "message", "");
                    public StringProperty messageProperty() { return message; }
                    public String getMessage() { return message.get(); }
                    public void setMessage(String v) { message.set(v); }

                    /** Generic getter for type-witness tests. */
                    public <T> ObjectProperty<T> genericValue() { return new SimpleObjectProperty<>(); }
                }
                """);
    }

    // -----------------------------------------------------------------------
    // 8.3 Attached properties
    // -----------------------------------------------------------------------

    /**
     * {@code $myLabel.(VBox.margin)}: selects an attached property value.
     * {@code VBox.getMargin(Node)} is a static getter on {@code VBox};
     * the {@code (VBox.margin)} segment should resolve without error.
     * <p>We use {@code name} (which has a getter) as the receiver to keep the test simple.
     */
    @Test
    void attachedPropertyInBindingPathResolvesWithoutError() {
        getFixture().configureByText("AdvancedTestView.fxml", fxml2(
                """
                javafx.scene.control.Label
                javafx.scene.layout.VBox
                """,
                // Use fx:Evaluate (EVALUATE) notation to avoid ObservableValue requirement on last segment
                """
                  <Label text="{fx:Evaluate message.(VBox.margin)}"/>
                """,
                "test.AdvancedTestView"
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * An attached-property segment with an unknown property name should produce an error
     * on the {@code (ClassName.unknownProp)} segment.
     */
    @Test
    void attachedPropertyWithUnknownPropertyProducesError() {
        getFixture().configureByText("AdvancedTestView.fxml", fxml2(
                """
                javafx.scene.control.Label
                javafx.scene.layout.VBox
                """,
                """
                  <Label text="{fx:Evaluate message.<error descr="'(VBox.nonExistentProp)' in VBox cannot be resolved">(VBox.nonExistentProp)</error>}"/>
                """,
                "test.AdvancedTestView"
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // 8.4 Generic type witness
    // -----------------------------------------------------------------------

    /**
     * {@code ${genericValue&lt;String&gt;}}: a type witness in XML-escaped form.
     * The plugin should strip the witness and resolve {@code genericValue} on the
     * code-behind class without error.
     */
    @Test
    void typeWitnessInBindingPathResolvesWithoutError() {
        getFixture().configureByText("AdvancedTestView.fxml", fxml2(
                "javafx.scene.control.Label",
                // &lt;String&gt; is the XML-safe form of <String>; getValue() returns the
                // raw text, so the resolver sees the literal &lt;String&gt; string.
                """
                  <Label text="${genericValue&lt;String&gt;}"/>
                """,
                "test.AdvancedTestView"
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A type-witness segment whose base name does not exist should still produce an error.
     */
    @Test
    void typeWitnessWithUnknownMethodProducesError() {
        getFixture().configureByText("AdvancedTestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="${<error descr="'nonExistentGeneric&lt;String&gt;' in test.AdvancedTestView cannot be resolved">nonExistentGeneric&lt;String&gt;</error>}"/>
                """,
                "test.AdvancedTestView"
        ));
        getFixture().checkHighlighting(false, false, false);
    }
}
