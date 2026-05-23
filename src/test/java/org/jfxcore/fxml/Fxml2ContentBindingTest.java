package org.jfxcore.fxml;

import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tests for content-binding expression intrinsics.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code fx:Evaluate} with {@code ..} path / {@code $..x}: one-time content assignment to a collection</li>
 *   <li>{@code fx:Observe} with {@code ..} path / {@code ${..x}}: unidirectional content binding</li>
 *   <li>{@code fx:Push} with {@code ..} path / {@code >{..x}}: reverse content binding</li>
 *   <li>{@code fx:Synchronize} with {@code ..} path / {@code #{..x}}: bidirectional content binding</li>
 * </ul>
 *
 * <p>Corresponds to:
 * <ul>
 *   <li>{@code reference/evaluate.md}</li>
 *   <li>{@code reference/observe.md}</li>
 *   <li>{@code reference/push.md}</li>
 *   <li>{@code reference/synchronize.md}</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2ContentBindingTest extends Fxml2TestBase {

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
                import javafx.beans.property.ListProperty;
                import javafx.beans.property.MapProperty;
                import javafx.beans.property.SimpleListProperty;
                import javafx.beans.property.SimpleMapProperty;
                import javafx.collections.FXCollections;
                import javafx.collections.ObservableList;
                import javafx.collections.ObservableMap;
                public class TestView extends TestViewBase {
                    private final ListProperty<String> items =
                        new SimpleListProperty<>(FXCollections.observableArrayList());
                    public ListProperty<String> itemsProperty() { return items; }
                    public ObservableList<String> getItems() { return items.get(); }

                    private final MapProperty<String, String> properties =
                        new SimpleMapProperty<>(FXCollections.observableHashMap());
                    public MapProperty<String, String> propertiesProperty() { return properties; }
                    public ObservableMap<String, String> getProperties() { return properties.get(); }
                }
                """);
    }

    // -----------------------------------------------------------------------
    // fx:Evaluate ..x / $..x: compact notation
    // -----------------------------------------------------------------------

    /**
     * {@code $..items}: compact content notation on a list property should
     * produce no error.
     */
    @Test
    void dollarDotDotCompactContentSyntaxProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView styleClass="$..items"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code {fx:Evaluate ..myPath}}: explicit attribute notation for content assignment.
     */
    @Test
    void fxEvaluateContentAttributeNotationProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView styleClass="{fx:Evaluate ..items}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code {fx:Evaluate source=..myPath}}: explicit attribute notation with {@code source=}
     * property spelled out.
     */
    @Test
    void fxEvaluateContentAttributeNotationWithExplicitPathProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView styleClass="{fx:Evaluate source=..items}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Element notation: {@code <fx:Evaluate source="..items"/>} inside a property element.
     */
    @Test
    void fxEvaluateContentElementNotationProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView>
                    <styleClass>
                      <fx:Evaluate source="..items"/>
                    </styleClass>
                  </ListView>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // fx:Observe ..x / ${..x}
    // -----------------------------------------------------------------------

    /**
     * {@code ${..items}}: compact observe-content notation on a list property.
     */
    @Test
    void dollarBraceDotDotCompactObserveContentSyntaxProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView styleClass="${..items}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code {fx:Observe ..items}}: explicit attribute notation for observe-content.
     */
    @Test
    void fxObserveContentAttributeNotationProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView styleClass="{fx:Observe ..items}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Element notation: {@code <fx:Observe source="..items"/>} inside a property element.
     */
    @Test
    void fxObserveContentElementNotationProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView>
                    <styleClass>
                      <fx:Observe source="..items"/>
                    </styleClass>
                  </ListView>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // fx:Push ..x / >{..x}: reverse content binding
    // -----------------------------------------------------------------------

    /**
     * {@code >{..items}}: compact reverse content binding notation on a list property.
     */
    @Test
    void angleBraceDotDotCompactPushContentSyntaxProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView styleClass=">{..items}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code {fx:Push ..items}}: explicit attribute notation for reverse content binding.
     */
    @Test
    void fxPushContentAttributeNotationProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView styleClass="{fx:Push ..items}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Element notation for {@code fx:Push} reverse content binding:
     * {@code <fx:Push source="..items"/>} inside a property element.
     */
    @Test
    void fxPushContentElementNotationProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView>
                    <styleClass>
                      <fx:Push source="..items"/>
                    </styleClass>
                  </ListView>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // fx:Synchronize ..x / #{..x}
    // -----------------------------------------------------------------------

    /**
     * {@code #{..items}}: compact bidirectional synchronize-content notation.
     */
    @Test
    void hashBraceDotDotCompactSynchronizeContentSyntaxProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView styleClass="#{..items}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code {fx:Synchronize ..items}}: explicit attribute notation.
     */
    @Test
    void fxSynchronizeContentAttributeNotationProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView styleClass="{fx:Synchronize ..items}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Element notation: {@code <fx:Synchronize source="..items"/>} inside a property element.
     */
    @Test
    void fxSynchronizeContentElementNotationProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView>
                    <styleClass>
                      <fx:Synchronize source="..items"/>
                    </styleClass>
                  </ListView>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Unresolvable path: error case
    // -----------------------------------------------------------------------

    /**
     * An unresolvable path in {@code $..nonExistent} should produce an error.
     */
    @Test
    void dotDotCompactWithUnresolvablePathProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView styleClass="$..<error descr="'nonExistent' in test.TestView cannot be resolved">nonExistent</error>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // 18.2 Map property: $..x, ${..x}, #{..x}
    // -----------------------------------------------------------------------

    /**
     * 18.2: {@code $..properties} compact content notation on a {@code MapProperty}
     * should be accepted without error (the compiler supports List, Set, and Map targets).
     */
    @Test
    void dotDotCompactContentOnMapPropertyProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="$..properties"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * 18.2: {@code {fx:Evaluate ..properties}} explicit notation on a {@code MapProperty}
     * should be accepted without error.
     */
    @Test
    void fxEvaluateContentOnMapPropertyProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Evaluate ..properties}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * 18.2: {@code ${..properties}} observe-content compact notation on a {@code MapProperty}
     * should be accepted without error.
     */
    @Test
    void dollarBraceDotDotCompactObserveContentOnMapPropertyProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="${..properties}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }
}
