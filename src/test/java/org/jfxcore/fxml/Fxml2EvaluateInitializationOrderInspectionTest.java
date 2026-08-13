package org.jfxcore.fxml;

import org.jfxcore.fxml.annotator.Fxml2EvaluateInitializationOrderInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class Fxml2EvaluateInitializationOrderInspectionTest extends Fxml2TestBase {

    private static final String WARNING =
            "Evaluate with ':parent' may depend on element initialization order";

    @BeforeEach
    void enableInspection() {
        getFixture().enableInspections(new Fxml2EvaluateInitializationOrderInspection());
    }

    @Test
    void compactEvaluateWarnsOnParentSelector() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.Pane\njavafx.scene.control.Label",
                """
                  <Pane><Label prefWidth="$<weak_warning descr="%s">:parent</weak_warning>.prefWidth"/></Pane>
                """.formatted(WARNING)));
        getFixture().checkHighlighting(false, false, true);
    }

    @Test
    void longEvaluateWarnsForNestedElementAndParentSelectors() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.Pane\njavafx.scene.control.Label",
                """
                  <Pane><Label text="{fx:Evaluate source=m(<weak_warning descr="Evaluate with ':element' may depend on element initialization order">:element</weak_warning>.width, (<weak_warning descr="%s">:parent</weak_warning>.height + 1))}"/></Pane>
                """.formatted(WARNING)));
        getFixture().checkHighlighting(false, false, true);
    }

    @Test
    void evaluateElementNotationWarns() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.Pane\njavafx.scene.control.Label",
                """
                  <Pane><Label><prefWidth><fx:Evaluate source='<weak_warning descr="%s">:parent</weak_warning>&lt;Pane&gt;.prefWidth'/></prefWidth></Label></Pane>
                """.formatted(WARNING)));
        getFixture().checkHighlighting(false, false, true);
    }

    @Test
    void otherSelectorsAndBindingKindsDoNotWarn() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.Pane\njavafx.scene.control.Label",
                """
                  <Pane>
                    <Label text="$:root.id"/>
                    <Label text="$:context.id"/>
                    <Label text="${:parent.id}"/>
                    <Label text="#{:element.id}"/>
                  </Pane>
                """));
        getFixture().checkHighlighting(false, false, true);
    }
}
