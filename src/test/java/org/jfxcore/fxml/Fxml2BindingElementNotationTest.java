package org.jfxcore.fxml;

import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tests for binding intrinsic markup extensions in element notation.
 *
 * <p>Each binding markup extension ({@code fx:Evaluate}, {@code fx:Observe},
 * {@code fx:Push}, {@code fx:Synchronize}) supports three notations:
 * <ol>
 *   <li>Compact attribute notation ({@code $x}, {@code ${x}}, {@code >{x}}, etc.)</li>
 *   <li>Inline curly-brace attribute notation ({@code {fx:Observe x}})</li>
 *   <li>Element notation: {@code <fx:Observe source="x"/>} inside a property element</li>
 * </ol>
 *
 * <p>The compact and inline forms are covered by other tests. This class specifically
 * tests the element-notation form, which places the markup extension as an XML child
 * of a property element tag. The {@code source=} attribute specifies the binding source path.
 *
 * <p>Corresponds to the "Element notation" usage examples in:
 * <ul>
 *   <li>{@code reference/evaluate.md}</li>
 *   <li>{@code reference/observe.md}</li>
 *   <li>{@code reference/synchronize.md}</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2BindingElementNotationTest extends Fxml2TestBase {

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
                import javafx.beans.property.StringProperty;
                import javafx.beans.property.SimpleStringProperty;
                public class TestView extends TestViewBase {
                    private final StringProperty caption = new SimpleStringProperty(this, "caption", "");
                    public StringProperty captionProperty() { return caption; }
                    public String getCaption() { return caption.get(); }
                    public void setCaption(String v) { caption.set(v); }
                }
                """);
    }

    // -----------------------------------------------------------------------
    // fx:Evaluate: element notation
    // -----------------------------------------------------------------------

    /**
     * Element notation for {@code fx:Evaluate}: the binding source path is specified via the
     * {@code source} attribute:
     * <pre>{@code
     * <Label>
     *   <text><fx:Evaluate source="caption"/></text>
     * </Label>
     * }</pre>
     */
    @Test
    void fxEvaluateElementNotationProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label>
                    <text>
                      <fx:Evaluate source="caption"/>
                    </text>
                  </Label>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code fx:Evaluate} element notation with an unresolvable path should produce an error.
     */
    @Test
    void fxEvaluateElementNotationWithUnresolvablePathProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label>
                    <text>
                      <fx:Evaluate source="<error descr="'nonExistent' in test.TestView cannot be resolved">nonExistent</error>"/>
                    </text>
                  </Label>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // fx:Observe: element notation
    // -----------------------------------------------------------------------

    /**
     * Element notation for {@code fx:Observe}:
     * <pre>{@code
     * <Label>
     *   <text><fx:Observe source="caption"/></text>
     * </Label>
     * }</pre>
     */
    @Test
    void fxObserveElementNotationProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label>
                    <text>
                      <fx:Observe source="caption"/>
                    </text>
                  </Label>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code fx:Observe} element notation with an unresolvable path should produce an error.
     */
    @Test
    void fxObserveElementNotationWithUnresolvablePathProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label>
                    <text>
                      <fx:Observe source="<error descr="'nonExistent' in test.TestView cannot be resolved">nonExistent</error>"/>
                    </text>
                  </Label>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // fx:Synchronize: element notation
    // -----------------------------------------------------------------------

    /**
     * Element notation for {@code fx:Synchronize}:
     * <pre>{@code
     * <Label>
     *   <text><fx:Synchronize source="caption"/></text>
     * </Label>
     * }</pre>
     */
    @Test
    void fxSynchronizeElementNotationProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label>
                    <text>
                      <fx:Synchronize source="caption"/>
                    </text>
                  </Label>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code fx:Synchronize} element notation with a resolvable path should produce no error.
     */
    @Test
    void fxSynchronizeElementNotationWithExplicitPathProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label>
                    <text>
                      <fx:Synchronize source="caption"/>
                    </text>
                  </Label>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // fx:Push: element notation
    // -----------------------------------------------------------------------

    /**
     * element notation for {@code fx:Push} (reverse binding):
     * <pre>{@code
     * <Label>
     *   <text><fx:Push source="caption"/></text>
     * </Label>
     * }</pre>
     */
    @Test
    void fxPushElementNotationProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label>
                    <text>
                      <fx:Push source="caption"/>
                    </text>
                  </Label>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code fx:Push} element notation with an unresolvable path should produce an error.
     */
    @Test
    void fxPushElementNotationWithUnresolvablePathProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label>
                    <text>
                      <fx:Push source="<error descr="'nonExistent' in test.TestView cannot be resolved">nonExistent</error>"/>
                    </text>
                  </Label>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }
}
