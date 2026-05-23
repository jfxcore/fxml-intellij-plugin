package org.jfxcore.fxml;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.BatchQuickFix;
import com.intellij.codeInspection.ex.QuickFixWrapper;
import org.jfxcore.fxml.annotator.Fxml2BindingNotationInspectionToLongForm;
import org.jfxcore.fxml.annotator.Fxml2BindingNotationInspectionToShortForm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link org.jfxcore.fxml.actions.ConvertBindingNotationIntention}.
 *
 * <p>Each test verifies both that the correct intention is offered at the caret
 * position and that invoking it produces the expected document text.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2BindingNotationIntentionTest extends Fxml2TestBase {

    // -----------------------------------------------------------------------
    // ToLongForm  (compact -> {fx:...})
    // -----------------------------------------------------------------------

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void toLongFormEvaluateIsOfferedForDollarNotation() {
        getFixture().configureByText("Test.fxml", fxml2(
                "javafx.scene.control.Label",
                "  <Label text=\"$model.statusTex<caret>t\"/>\n"
        ));
        IntentionAction action = getFixture().findSingleIntention("Convert to long-form binding notation");
        assertNotNull(action, "ToLongForm intention should be available on $path");
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void toLongFormEvaluateConvertsCorrectly() {
        getFixture().configureByText("Test.fxml", fxml2(
                "javafx.scene.control.Label",
                "  <Label text=\"$model.statusText<caret>\"/>\n"
        ));
        IntentionAction action = getFixture().findSingleIntention("Convert to long-form binding notation");
        getFixture().launchAction(action);
        assertTrue(getFixture().getEditor().getDocument().getText()
                        .contains("text=\"{fx:Evaluate model.statusText}\""),
                "Expected text=\"{fx:Evaluate model.statusText}\"");
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void toLongFormObserveConvertsCorrectly() {
        getFixture().configureByText("Test.fxml", fxml2(
                "javafx.scene.control.Label",
                "  <Label text=\"${model.statusText<caret>}\"/>\n"
        ));
        IntentionAction action = getFixture().findSingleIntention("Convert to long-form binding notation");
        getFixture().launchAction(action);
        assertTrue(getFixture().getEditor().getDocument().getText()
                        .contains("text=\"{fx:Observe model.statusText}\""),
                "Expected text=\"{fx:Observe model.statusText}\"");
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void toLongFormSynchronizeConvertsCorrectly() {
        getFixture().configureByText("Test.fxml", fxml2(
                "javafx.scene.control.Label",
                "  <Label text=\"#{model.statusText<caret>}\"/>\n"
        ));
        IntentionAction action = getFixture().findSingleIntention("Convert to long-form binding notation");
        getFixture().launchAction(action);
        assertTrue(getFixture().getEditor().getDocument().getText()
                        .contains("text=\"{fx:Synchronize model.statusText}\""),
                "Expected text=\"{fx:Synchronize model.statusText}\"");
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void toLongFormEvaluateContentConvertsCorrectly() {
        getFixture().configureByText("Test.fxml", fxml2(
                "javafx.scene.layout.VBox",
                "  <VBox styleClass=\"$..styles<caret>\"/>\n"
        ));
        IntentionAction action = getFixture().findSingleIntention("Convert to long-form binding notation");
        getFixture().launchAction(action);
        assertTrue(getFixture().getEditor().getDocument().getText()
                        .contains("styleClass=\"{fx:Evaluate ..styles}\""),
                "Expected styleClass=\"{fx:Evaluate ..styles}\"");
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void toLongFormObserveContentConvertsCorrectly() {
        getFixture().configureByText("Test.fxml", fxml2(
                "javafx.scene.layout.VBox",
                "  <VBox styleClass=\"${..styles<caret>}\"/>\n"
        ));
        IntentionAction action = getFixture().findSingleIntention("Convert to long-form binding notation");
        getFixture().launchAction(action);
        assertTrue(getFixture().getEditor().getDocument().getText()
                        .contains("styleClass=\"{fx:Observe ..styles}\""),
                "Expected styleClass=\"{fx:Observe ..styles}\"");
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void toLongFormSynchronizeContentConvertsCorrectly() {
        getFixture().configureByText("Test.fxml", fxml2(
                "javafx.scene.layout.VBox",
                "  <VBox styleClass=\"#{..styles<caret>}\"/>\n"
        ));
        IntentionAction action = getFixture().findSingleIntention("Convert to long-form binding notation");
        getFixture().launchAction(action);
        assertTrue(getFixture().getEditor().getDocument().getText()
                        .contains("styleClass=\"{fx:Synchronize ..styles}\""),
                "Expected styleClass=\"{fx:Synchronize ..styles}\"");
    }

    // -----------------------------------------------------------------------
    // ToShortForm  ({fx:...} -> compact)
    // -----------------------------------------------------------------------

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void toShortFormIsOfferedForLongFormNotation() {
        getFixture().configureByText("Test.fxml", fxml2(
                "javafx.scene.control.Label",
                "  <Label text=\"{fx:Evaluate model.statusText<caret>}\"/>\n"
        ));
        IntentionAction action = getFixture().findSingleIntention("Convert to short-form binding notation");
        assertNotNull(action, "ToShortForm intention should be available on {fx:Evaluate ...}");
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void toShortFormEvaluateConvertsCorrectly() {
        getFixture().configureByText("Test.fxml", fxml2(
                "javafx.scene.control.Label",
                "  <Label text=\"{fx:Evaluate model.statusText<caret>}\"/>\n"
        ));
        IntentionAction action = getFixture().findSingleIntention("Convert to short-form binding notation");
        getFixture().launchAction(action);
        assertTrue(getFixture().getEditor().getDocument().getText()
                        .contains("text=\"$model.statusText\""),
                "Expected text=\"$model.statusText\"");
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void toShortFormObserveConvertsCorrectly() {
        getFixture().configureByText("Test.fxml", fxml2(
                "javafx.scene.control.Label",
                "  <Label text=\"{fx:Observe model.statusText<caret>}\"/>\n"
        ));
        IntentionAction action = getFixture().findSingleIntention("Convert to short-form binding notation");
        getFixture().launchAction(action);
        assertTrue(getFixture().getEditor().getDocument().getText()
                        .contains("text=\"${model.statusText}\""),
                "Expected text=\"${model.statusText}\"");
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void toShortFormSynchronizeConvertsCorrectly() {
        getFixture().configureByText("Test.fxml", fxml2(
                "javafx.scene.control.Label",
                "  <Label text=\"{fx:Synchronize model.statusText<caret>}\"/>\n"
        ));
        IntentionAction action = getFixture().findSingleIntention("Convert to short-form binding notation");
        getFixture().launchAction(action);
        assertTrue(getFixture().getEditor().getDocument().getText()
                        .contains("text=\"#{model.statusText}\""),
                "Expected text=\"#{model.statusText}\"");
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void toShortFormEvaluateContentConvertsCorrectly() {
        getFixture().configureByText("Test.fxml", fxml2(
                "javafx.scene.layout.VBox",
                "  <VBox styleClass=\"{fx:Evaluate ..styles<caret>}\"/>\n"
        ));
        IntentionAction action = getFixture().findSingleIntention("Convert to short-form binding notation");
        getFixture().launchAction(action);
        assertTrue(getFixture().getEditor().getDocument().getText()
                        .contains("styleClass=\"$..styles\""),
                "Expected styleClass=\"$..styles\"");
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void toShortFormObserveContentConvertsCorrectly() {
        getFixture().configureByText("Test.fxml", fxml2(
                "javafx.scene.layout.VBox",
                "  <VBox styleClass=\"{fx:Observe ..styles<caret>}\"/>\n"
        ));
        IntentionAction action = getFixture().findSingleIntention("Convert to short-form binding notation");
        getFixture().launchAction(action);
        assertTrue(getFixture().getEditor().getDocument().getText()
                        .contains("styleClass=\"${..styles}\""),
                "Expected styleClass=\"${..styles}\"");
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void toShortFormSynchronizeContentConvertsCorrectly() {
        getFixture().configureByText("Test.fxml", fxml2(
                "javafx.scene.layout.VBox",
                "  <VBox styleClass=\"{fx:Synchronize ..styles<caret>}\"/>\n"
        ));
        IntentionAction action = getFixture().findSingleIntention("Convert to short-form binding notation");
        getFixture().launchAction(action);
        assertTrue(getFixture().getEditor().getDocument().getText()
                        .contains("styleClass=\"#{..styles}\""),
                "Expected styleClass=\"#{..styles}\"");
    }

    // -----------------------------------------------------------------------
    // Availability guards: intentions must NOT appear in the wrong context
    // -----------------------------------------------------------------------

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void toLongFormNotAvailableOnPlainAttributeValue() {
        getFixture().configureByText("Test.fxml", fxml2(
                "javafx.scene.control.Label",
                "  <Label text=\"Hel<caret>lo\"/>\n"
        ));
        boolean found = getFixture().getAvailableIntentions().stream()
                .anyMatch(i -> i.getText().equals("Convert to long-form binding notation"));
        assertFalse(found, "ToLongForm must NOT be offered on a plain attribute value");
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void toShortFormNotAvailableOnPlainAttributeValue() {
        getFixture().configureByText("Test.fxml", fxml2(
                "javafx.scene.control.Label",
                "  <Label text=\"Hel<caret>lo\"/>\n"
        ));
        boolean found = getFixture().getAvailableIntentions().stream()
                .anyMatch(i -> i.getText().equals("Convert to short-form binding notation"));
        assertFalse(found, "ToShortForm must NOT be offered on a plain attribute value");
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void toLongFormNotAvailableOnLongFormValue() {
        getFixture().configureByText("Test.fxml", fxml2(
                "javafx.scene.control.Label",
                "  <Label text=\"{fx:Evaluate model.statusText<caret>}\"/>\n"
        ));
        boolean found = getFixture().getAvailableIntentions().stream()
                .anyMatch(i -> i.getText().equals("Convert to long-form binding notation"));
        assertFalse(found, "ToLongForm must NOT be offered on an already-long-form value");
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void toShortFormNotAvailableOnCompactValue() {
        getFixture().configureByText("Test.fxml", fxml2(
                "javafx.scene.control.Label",
                "  <Label text=\"$model.statusText<caret>\"/>\n"
        ));
        boolean found = getFixture().getAvailableIntentions().stream()
                .anyMatch(i -> i.getText().equals("Convert to short-form binding notation"));
        assertFalse(found, "ToShortForm must NOT be offered on an already-compact value");
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void toLongFormNotAvailableOnTagName() {
        // Caret is on the tag name "Label", not in an attribute value
        getFixture().configureByText("Test.fxml", fxml2(
                "javafx.scene.control.Label",
                "  <La<caret>bel text=\"$model.statusText\"/>\n"
        ));
        boolean found = getFixture().getAvailableIntentions().stream()
                .anyMatch(i -> i.getText().equals("Convert to long-form binding notation"));
        assertFalse(found, "ToLongForm must NOT be offered when caret is on a tag name");
    }

    // -----------------------------------------------------------------------
    // Inspection: "Fix all in file" via Fxml2BindingNotationInspection
    // -----------------------------------------------------------------------

    /**
     * The ToLongForm inspection quick-fix must implement {@link BatchQuickFix} so that
     * "Fix all in file" is available in the Alt+Enter menu.
     */
    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void toLongFormFixIsBatchApplicable() {
        getFixture().enableInspections(new Fxml2BindingNotationInspectionToLongForm());
        getFixture().configureByText("TestBatch.fxml", fxml2(
                "javafx.scene.control.Label",
                "  <Label text=\"$model.title<caret>\"/>\n"
        ));
        var fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && "Convert to long-form binding notation".equals(fix.getFamilyName());
                })
                .toList();
        assertFalse(fixes.isEmpty(), "Expected ToLongForm quick-fix from inspection");
        var fix = QuickFixWrapper.unwrap(fixes.getFirst());
        assertInstanceOf(BatchQuickFix.class, fix,
                "ToLongFormFix should implement BatchQuickFix for 'Fix all in file' support");
    }

    /**
     * The ToShortForm inspection quick-fix must implement {@link BatchQuickFix} so that
     * "Fix all in file" is available in the Alt+Enter menu.
     */
    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void toShortFormFixIsBatchApplicable() {
        getFixture().enableInspections(new Fxml2BindingNotationInspectionToShortForm());
        getFixture().configureByText("TestBatch2.fxml", fxml2(
                "javafx.scene.control.Label",
                "  <Label text=\"{fx:Evaluate model.title<caret>}\"/>\n"
        ));
        var fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && "Convert to short-form binding notation".equals(fix.getFamilyName());
                })
                .toList();
        assertFalse(fixes.isEmpty(), "Expected ToShortForm quick-fix from inspection");
        var fix = QuickFixWrapper.unwrap(fixes.getFirst());
        assertInstanceOf(BatchQuickFix.class, fix,
                "ToShortFormFix should implement BatchQuickFix for 'Fix all in file' support");
    }

    /**
     * With the ToLongForm inspection enabled, running all quick-fixes with the matching
     * family name converts every compact binding in the document to long-form in one shot.
     */
    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void inspectionToLongFormFixAllInFile() {
        getFixture().enableInspections(new Fxml2BindingNotationInspectionToLongForm());
        getFixture().configureByText("InspLong.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="$model.title<caret>" tooltip="${model.tip}"/>
                """
        ));

        // Collect all quick-fixes from highlight infos whose family name matches
        List<IntentionAction> fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && "Convert to long-form binding notation".equals(fix.getFamilyName());
                })
                .toList();
        assertFalse(fixes.isEmpty(), "Expected ToLongForm quick-fixes from inspection");

        fixes.forEach(getFixture()::launchAction);

        String doc = getFixture().getEditor().getDocument().getText();
        assertTrue(doc.contains("{fx:Evaluate model.title}"),  "Expected $model.title -> {fx:Evaluate model.title}");
        assertTrue(doc.contains("{fx:Observe model.tip}"),     "Expected ${model.tip} -> {fx:Observe model.tip}");
        assertFalse(doc.contains("=\"$"),                   "No compact $... binding should remain");
    }

    /**
     * With the ToShortForm inspection enabled, running all quick-fixes with the matching
     * family name converts every long-form binding in the document to compact notation.
     */
    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void inspectionToShortFormFixAllInFile() {
        getFixture().enableInspections(new Fxml2BindingNotationInspectionToShortForm());
        getFixture().configureByText("InspShort.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Evaluate model.title<caret>}" tooltip="{fx:Observe model.tip}"/>
                """
        ));

        List<IntentionAction> fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && "Convert to short-form binding notation".equals(fix.getFamilyName());
                })
                .toList();
        assertFalse(fixes.isEmpty(), "Expected ToShortForm quick-fixes from inspection");

        fixes.forEach(getFixture()::launchAction);

        String doc = getFixture().getEditor().getDocument().getText();
        assertTrue(doc.contains("\"$model.title\""),  "Expected {fx:Evaluate model.title} -> $model.title");
        assertTrue(doc.contains("\"${model.tip}\""),  "Expected {fx:Observe model.tip} -> ${model.tip}");
        assertFalse(doc.contains("{fx:"),           "No long-form {fx:...} binding should remain");
    }
}
