package org.jfxcore.fxml;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ex.QuickFixWrapper;
import org.jfxcore.fxml.annotator.Fxml2EvaluateInitializationOrderInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

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
    void compactEvaluateInValueListItemWarns() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.Pane\njavafx.scene.shape.Polygon",
                """
                  <Pane><Polygon points="0, $<weak_warning descr="%s">:parent</weak_warning>.prefWidth, 40"/></Pane>
                """.formatted(WARNING)));
        getFixture().checkHighlighting(false, false, true);
    }

    @Test
    void evaluateNestedInMarkupExtensionWarns() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.Pane\njavafx.scene.control.Label",
                """
                  <Pane><Label text="<error descr="Cannot resolve markup extension class 'org.jfxcore.markup.resource.StaticResource'">%%</error>greeting; formatArguments=$<weak_warning descr="%s">:parent</weak_warning>.prefWidth"/></Pane>
                """.formatted(WARNING)));
        getFixture().checkHighlighting(false, false, true);
    }

    @Test
    void literalCommaInSingleValuedPropertyDoesNotWarn() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.Pane\njavafx.scene.control.Label",
                """
                  <Pane><Label text="some text, $:parent.prefWidth"/></Pane>
                """));
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

    // -----------------------------------------------------------------------
    // Quick fix: convert the one-time evaluation into an observable binding

    @Test
    void quickFixWrapsCompactEvaluateAndDropsEnclosingParentheses() {
        assertFixedTo(
                """
                  <Pane><Label maxHeight="$(:element.prefWidth * 5)"/></Pane>
                """,
                """
                  <Pane><Label maxHeight="${:element.prefWidth * 5}"/></Pane>
                """);
    }

    @Test
    void quickFixKeepsParenthesesThatGroupPartOfTheExpression() {
        assertFixedTo(
                """
                  <Pane><Label maxHeight="$(:element.prefWidth + 5) * 2"/></Pane>
                """,
                """
                  <Pane><Label maxHeight="${(:element.prefWidth + 5) * 2}"/></Pane>
                """);
    }

    @Test
    void quickFixConvertsMarkupExtensionKeyword() {
        assertFixedTo(
                """
                  <Pane><Label maxHeight="{fx:Evaluate source=:parent.prefWidth}"/></Pane>
                """,
                """
                  <Pane><Label maxHeight="{fx:Observe source=:parent.prefWidth}"/></Pane>
                """);
    }

    @Test
    void quickFixConvertsEvaluateElement() {
        assertFixedTo(
                """
                  <Pane><Label><prefWidth><fx:Evaluate source=":parent.prefWidth"/></prefWidth></Label></Pane>
                """,
                """
                  <Pane><Label><prefWidth><fx:Observe source=":parent.prefWidth"/></prefWidth></Label></Pane>
                """);
    }

    @Test
    void quickFixConvertsOnlyTheAffectedValueListItem() {
        assertFixedTo(
                "javafx.scene.layout.Pane\njavafx.scene.shape.Polygon",
                """
                  <Pane><Polygon points="0, $:parent.prefWidth, 40"/></Pane>
                """,
                """
                  <Pane><Polygon points="0, ${:parent.prefWidth}, 40"/></Pane>
                """);
    }

    private void assertFixedTo(String body, String expectedBody) {
        assertFixedTo("javafx.scene.layout.Pane\njavafx.scene.control.Label", body, expectedBody);
    }

    /** Applies the quick fix of the first reported problem and compares the resulting file. */
    private void assertFixedTo(String imports, String body, String expectedBody) {
        getFixture().configureByText("TestView.fxml", fxml(imports, body));
        List<IntentionAction> fixes = getFixture().getAllQuickFixes().stream()
                .filter(action -> {
                    LocalQuickFix fix = QuickFixWrapper.unwrap(action);
                    return fix != null && "Convert to observable binding".equals(fix.getFamilyName());
                })
                .toList();
        assertFalse(fixes.isEmpty(), "Inspection must offer the conversion quick fix");
        getFixture().launchAction(fixes.getFirst());
        getFixture().checkResult(fxml(imports, expectedBody));
    }
}
