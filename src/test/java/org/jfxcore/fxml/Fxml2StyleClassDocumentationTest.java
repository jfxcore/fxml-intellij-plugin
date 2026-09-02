package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import com.intellij.platform.backend.documentation.DocumentationTarget;
import org.jfxcore.fxml.lang.CssSelectorElement;
import org.jfxcore.fxml.lang.Fxml2StyleClassDocumentationTargetProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that a style class shows the CSS rule that declares it as quick documentation, both
 * when the rule is written in a {@code .css} file and when it is written into a
 * {@code <?resource ?>} declaration of the document.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class Fxml2StyleClassDocumentationTest extends Fxml2TestBase {

    @BeforeAll
    void addMarkupAnnotation() {
        getFixture().addClass("""
                package org.jfxcore.markup;
                import java.lang.annotation.*;
                @Target(ElementType.TYPE)
                @Retention(RetentionPolicy.SOURCE)
                public @interface ComponentView {
                    String value();
                }
                """);
    }

    /** Returns the documentation HTML shown at the caret, or {@code null} when there is none. */
    private String documentationAtCaret() {
        return ReadAction.compute(() -> {
            List<CssSelectorElement> selectors =
                    Fxml2StyleClassDocumentationTargetProvider.resolveSelectorsAt(
                            getFixture().getFile(), getFixture().getCaretOffset());
            return selectors.isEmpty()
                    ? null
                    : Fxml2StyleClassDocumentationTargetProvider.documentationHtmlOf(selectors.getFirst());
        });
    }

    @Test
    void styleClassShowsRuleOfEmbeddedResourceStylesheet() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <?resource styles.css text/css:
                      .my-style1 {
                        -fx-text-fill: darkorange;
                      }
                  ?>
                  <Label styleClass="my-st<caret>yle1" stylesheets="@styles.css"/>
                """
        ));

        String html = documentationAtCaret();
        assertNotNull(html, "A style class declared by an embedded stylesheet must show its rule");
        assertTrue(html.contains(".my-style1"), "Documentation must show the selector; got: " + html);
        assertTrue(html.contains("-fx-text-fill: darkorange"),
                "Documentation must show the declaration block; got: " + html);
        assertTrue(html.contains("styles.css"),
                "Documentation must name the stylesheet the rule is written in; got: " + html);

        ReadAction.run(() -> {
            List<? extends DocumentationTarget> targets =
                    new Fxml2StyleClassDocumentationTargetProvider().documentationTargets(
                            getFixture().getFile(), getFixture().getCaretOffset());
            assertEquals(1, targets.size(),
                    "The hover pipeline must be offered one target per declaring stylesheet");
        });
    }

    @Test
    void styleClassShowsRuleOfEmbeddedResourceStylesheetInComponentView() {
        getFixture().configureByText("TestView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.control.Label;
                @ComponentView(\"""
                    <?resource styles.css text/css:
                        .my-style1 { -fx-text-fill: darkorange; }
                    ?>
                    <Label styleClass="my-st<caret>yle1" stylesheets="@styles.css"/>
                    \""")
                public class TestView extends Label {}
                """);

        String html = documentationAtCaret();
        assertNotNull(html, "A style class of embedded markup must show its rule");
        assertTrue(html.contains(".my-style1 { -fx-text-fill: darkorange; }"),
                "Documentation must show the whole rule; got: " + html);
    }

    @Test
    void styleClassShowsRuleOfStylesheetFile() {
        getFixture().addFileToProject("style.css", """
                .mystyle1 {
                  -fx-stroke: black;
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label styleClass="myst<caret>yle1"/>
                """
        ));

        String html = documentationAtCaret();
        assertNotNull(html, "A style class declared by a stylesheet file must show its rule");
        assertTrue(html.contains("-fx-stroke: black"),
                "Documentation must show the declaration block; got: " + html);
        assertTrue(html.contains("style.css"),
                "Documentation must name the stylesheet file; got: " + html);
    }

    @Test
    void styleClassOfAnotherTokenIsNotDocumented() {
        getFixture().addFileToProject("style.css", """
                .mystyle1 {
                  -fx-stroke: black;
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="he<caret>llo" styleClass="mystyle1"/>
                """
        ));

        assertNull(documentationAtCaret(),
                "Only a style-class name is documented, not another attribute value");
    }

    @Test
    void unresolvedStyleClassIsNotDocumented() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label styleClass="does-not-<caret>exist"/>
                """
        ));

        assertNull(documentationAtCaret(),
                "A style class no stylesheet declares must show no documentation");
    }
}
