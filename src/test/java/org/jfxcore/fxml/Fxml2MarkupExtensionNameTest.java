package org.jfxcore.fxml;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.lookup.LookupElement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for naming a markup extension class in an attribute value: completing the name the user
 * has begun to type, and importing the class the name denotes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2MarkupExtensionNameTest extends Fxml2TestBase {

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
                public class TestView extends TestViewBase {
                }
                """);
        getFixture().addClass("""
                package org.jfxcore.markup;
                public interface MarkupExtension {
                    interface Supplier<T> extends MarkupExtension {
                        T get(MarkupContext context);
                    }
                }
                """);
        getFixture().addClass("package org.jfxcore.markup; public interface MarkupContext {}");
        getFixture().addClass("""
                package ext;
                import javafx.beans.NamedArg;
                import org.jfxcore.markup.MarkupContext;
                import org.jfxcore.markup.MarkupExtension;
                public class ScaledInset implements MarkupExtension.Supplier<Double> {
                    public ScaledInset(@NamedArg("value") double value,
                                       @NamedArg("factor") double factor) {}
                    @Override
                    public Double get(MarkupContext context) { return null; }
                }
                """);
    }

    /** The text of the file being edited, with the caret marker removed. */
    private String documentText() {
        return getFixture().getEditor().getDocument().getText();
    }

    // -----------------------------------------------------------------------
    // Completing the name
    // -----------------------------------------------------------------------

    /**
     * Completing a partly typed extension name inserts the name alone: the parameters that
     * follow it are already separated from it, so the completion adds no separator of its own.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void completingANameBeforeParametersAddsNoSpace() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.GridPane\next.ScaledInset",
                """
                  <GridPane padding="10, 10, 10, {ScaledInse<caret> value=6; factor=2}"/>
                """
        ));
        getFixture().completeBasic();
        assertTrue(documentText().contains("{ScaledInset value=6; factor=2}"),
                "the completed name must not add a second separator, document: " + documentText());
    }

    /** Completing a name that stands alone likewise inserts the name alone. */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void completingANameWithoutParametersAddsNoSpace() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.GridPane\next.ScaledInset",
                """
                  <GridPane padding="10, 10, 10, {ScaledInse<caret>}"/>
                """
        ));
        getFixture().completeBasic();
        assertTrue(documentText().contains("{ScaledInset}"),
                "the completed name must not be followed by a space, document: " + documentText());
    }

    /** No markup extension completion offers a name that ends in a separator. */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void noExtensionCompletionEndsWithASpace() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label\next.ScaledInset",
                """
                  <Label text="{Scaled<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            assertTrue(documentText().contains("{ScaledInset"),
                    "expected the single match to be inserted, document: " + documentText());
            assertFalse(documentText().contains("{ScaledInset "),
                    "the inserted name must not be followed by a space, document: " + documentText());
            return;
        }
        for (LookupElement item : items) {
            assertEquals(item.getLookupString().stripTrailing(), item.getLookupString(),
                    "completion '" + item.getLookupString() + "' must not end with a separator");
        }
    }

    // -----------------------------------------------------------------------
    // Importing the class the name denotes
    // -----------------------------------------------------------------------

    /** Applying the fix adds the import, so that the name resolves. */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void addingTheImportResolvesTheExtensionName() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="{Scaled<caret>Inset value=6; factor=2}"/>
                """
        ));
        IntentionAction fix = getFixture().findSingleIntention("Add import for 'ScaledInset'");
        getFixture().launchAction(fix);
        assertTrue(documentText().contains("<?import ext.ScaledInset?>"),
                "expected the import to be added, document: " + documentText());
    }

    /** A markup extension that stands as the whole value offers the import as well. */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void anUnimportedExtensionAsTheWholeValueOffersToAddTheImport() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="{Scaled<caret>Inset value=6; factor=2}"/>
                """
        ));
        List<IntentionAction> intentions = getFixture().getAvailableIntentions();
        assertTrue(intentions.stream().anyMatch(a -> a.getText().contains("Add import for 'ScaledInset'")),
                "expected an add-import fix, got: " + intentions.stream().map(IntentionAction::getText).toList());
    }

    // -----------------------------------------------------------------------
    // The name of an extension that is one item of a value sequence
    // -----------------------------------------------------------------------

    /** An extension in an item of a value sequence offers to add the import as well. */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void anUnimportedExtensionInAnItemOffersToAddTheImport() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane padding="10, 10, 10, {Scaled<caret>Inset value=6; factor=2}"/>
                """
        ));
        List<IntentionAction> intentions = getFixture().getAvailableIntentions();
        assertTrue(intentions.stream().anyMatch(a -> a.getText().contains("Add import for 'ScaledInset'")),
                "expected an add-import fix, got: " + intentions.stream().map(IntentionAction::getText).toList());
    }

    /** Applying the fix on an item adds the import. */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void addingTheImportFromAnItemResolvesTheExtensionName() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane padding="10, 10, 10, {Scaled<caret>Inset value=6; factor=2}"/>
                """
        ));
        IntentionAction fix = getFixture().findSingleIntention("Add import for 'ScaledInset'");
        getFixture().launchAction(fix);
        assertTrue(documentText().contains("<?import ext.ScaledInset?>"),
                "expected the import to be added, document: " + documentText());
    }
}
