package org.jfxcore.fxml;

import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tests for event-handler syntax in FXML2.
 *
 * <p>FXML2 supports two forms of event handler assignment on code-behind classes:
 * <ol>
 *   <li><b>Field handlers</b>: {@code onAction="$myHandler"}: a {@code $}-prefixed reference
 *       to an {@code EventHandler}-typed field on the code-behind class.</li>
 *   <li><b>Method handlers</b>: {@code onAction="myMethod"}: a plain method name that refers
 *       to a method on the code-behind class whose signature is compatible with
 *       {@code EventHandler<ActionEvent>}. The {@code event} parameter is optional.
 *       The compiler infers from the property's target type ({@code EventHandler<E>}) that the
 *       value is a method reference, not a literal string.</li>
 * </ol>
 *
 * <p>Corresponds to {@code event-handlers.md} in the fxml2 compiler documentation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2EventHandlerTest extends Fxml2TestBase {

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
        getFixture().addClass("""
                package test;
                import javafx.event.ActionEvent;
                import javafx.event.EventHandler;
                public class TestView extends TestViewBase {
                    public final EventHandler<ActionEvent> myActionHandler = event -> {};
                    public final String notAHandler = "hello";
                    public void handleAction(ActionEvent event) {}
                    public void handleActionNoParam() {}
                }
                """);
    }

    // -----------------------------------------------------------------------
    // Field event handlers via $ reference
    // -----------------------------------------------------------------------

    /**
     * {@code <Button onAction="$myActionHandler"/>}: references an EventHandler field
     * on the code-behind class. Should resolve without error.
     */
    @Test
    void fieldEventHandlerViaEvaluateReferenceProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button onAction="$myActionHandler"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Referencing a non-existent field as an event handler should produce an error.
     */
    @Test
    void fieldEventHandlerWithUnknownFieldProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button onAction="$<error descr="'nonExistentHandler' in test.TestView cannot be resolved">nonExistentHandler</error>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * the field referenced via {@code $} must have a compatible {@code EventHandler} type.
     * Using a field of an incompatible type (e.g. {@code String}) should produce an error.
     */
    @Test
    void fieldEventHandlerWithWrongTypeProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button onAction="<error descr="Incompatible types: 'String' cannot be assigned to 'EventHandler<ActionEvent>'">$notAHandler</error>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Method event handlers via plain method name
    // -----------------------------------------------------------------------

    /**
     * A method name without any prefix wires a method with a compatible signature when
     * the target property type is {@code EventHandler<E>}.
     */
    @Test
    void methodEventHandlerWithEventParamProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button onAction="handleAction"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * The {@code event} parameter is optional in the method signature.
     * A handler method with no parameters should be accepted.
     */
    @Test
    void methodEventHandlerWithoutParamProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button onAction="handleActionNoParam"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Referencing a non-existent method produces an error covering the full value.
     */
    @Test
    void methodEventHandlerWithUnknownMethodProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button onAction="<error descr="'nonExistentMethod' in test.TestView cannot be resolved">nonExistentMethod</error>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A plain method name on an {@code EventHandler}-typed property without a code-behind
     * class (no {@code fx:subclass}) should not crash the plugin.
     */
    @Test
    void methodEventHandlerWithoutCodeBehindDoesNotCrash() {
        getFixture().configureByText("StandaloneView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.Button?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0">
                  <Button onAction="handleAction"/>
                </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Generic property with fx:typeArguments: no false positives
    // -----------------------------------------------------------------------

    /**
     * When a generic class is used with {@code fx:typeArguments}, assigning a field whose type
     * matches the resolved type argument should not produce an "Incompatible types" error.
     */
    @Test
    void evaluateBindingToGenericPropertyWithTypeArgumentsProducesNoError() {
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public abstract class TestViewBase2 extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.scene.control.Label;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                import java.util.function.Function;
                public class FormattedLabel<T> extends Label {
                    private final ObjectProperty<Function<T, String>> formatter =
                        new SimpleObjectProperty<>(this, "formatter", Object::toString);
                    public ObjectProperty<Function<T, String>> formatterProperty() { return formatter; }
                    public Function<T, String> getFormatter() { return formatter.get(); }
                    public void setFormatter(Function<T, String> v) { formatter.set(v); }
                }
                """);
        getFixture().addClass("""
                package test;
                import java.util.function.Function;
                public class TestView2 extends TestViewBase2 {
                    public final Function<Double, String> doubleFormatter = d -> String.format("%.2f", d);
                }
                """);
        getFixture().configureByText("TestView2.fxml", fxml2(
                "test.FormattedLabel", """
                  <FormattedLabel fx:typeArguments="java.lang.Double" formatter="$doubleFormatter"/>
                """, "test.TestView2"
        ));
        getFixture().checkHighlighting(false, false, false);
    }
}
