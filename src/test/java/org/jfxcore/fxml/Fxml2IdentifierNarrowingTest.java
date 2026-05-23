package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link org.jfxcore.fxml.lang.Fxml2FileViewProvider#findElementAt} returns a
 * <em>narrow</em> segment element for each Java-identifier segment inside an XML
 * attribute-value token (e.g. a binding expression such as {@code ${vm.labelText}}).
 * Each identifier segment must have a distinct {@link com.intellij.openapi.util.TextRange}
 * covering exactly that identifier.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2IdentifierNarrowingTest extends Fxml2TestBase {

    // -----------------------------------------------------------------------
    // Short-form binding: ${vm.labelText}
    // -----------------------------------------------------------------------

    /**
     * For a short-form binding {@code text="${vm.labelText}"}, the element returned
     * by {@code findElementAt()} for "vm" and the element returned for "labelText"
     * must have <em>different</em> text ranges, and each range must cover exactly
     * the identifier under the caret.
     */
    @Test
    void shortFormBindingSegmentsHaveDifferentNarrowRanges() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="${vm.labelText}"/>
                """
        ));
        ReadAction.run(() -> {
            String text = getFixture().getFile().getText();

            // Locate the two identifiers within ${vm.labelText}
            int vmOffset        = text.indexOf("${vm.") + 2;         // 'v' of "vm"
            int labelTextOffset = text.indexOf(".labelText}") + 1;   // 'l' of "labelText"

            PsiElement vmElem        = getFixture().getFile().findElementAt(vmOffset);
            PsiElement labelTextElem = getFixture().getFile().findElementAt(labelTextOffset);

            assertNotNull(vmElem,        "Expected a non-null element at the 'vm' position");
            assertNotNull(labelTextElem, "Expected a non-null element at the 'labelText' position");

            TextRange vmRange        = vmElem.getTextRange();
            TextRange labelTextRange = labelTextElem.getTextRange();

            assertNotEquals(vmRange, labelTextRange,
                    "Identifiers 'vm' and 'labelText' must have different narrow text ranges "
                    + "so the identifier-highlight cache produces separate results for each. "
                    + "Got same range: " + vmRange);

            assertEquals("vm",        vmElem.getText(),
                    "Element at 'vm' offset must cover exactly the text 'vm'");
            assertEquals("labelText", labelTextElem.getText(),
                    "Element at 'labelText' offset must cover exactly the text 'labelText'");
        });
    }

    // -----------------------------------------------------------------------
    // Long-form binding: {fx:Synchronize vm.message}
    // -----------------------------------------------------------------------

    /**
     * For a long-form binding {@code text="{fx:Synchronize vm.message}"}, the element
     * returned for "vm" and the element returned for "message" must have different narrow ranges.
     */
    @Test
    void longFormBindingSegmentsHaveDifferentNarrowRanges() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.TextField",
                """
                  <TextField text="{fx:Synchronize vm.message}"/>
                """
        ));
        ReadAction.run(() -> {
            String text = getFixture().getFile().getText();

            int vmOffset      = text.indexOf("vm.message");       // 'v' of "vm"
            int messageOffset = text.indexOf(".message}") + 1;    // 'm' of "message"

            PsiElement vmElem      = getFixture().getFile().findElementAt(vmOffset);
            PsiElement messageElem = getFixture().getFile().findElementAt(messageOffset);

            assertNotNull(vmElem,      "Expected a non-null element at the 'vm' position");
            assertNotNull(messageElem, "Expected a non-null element at the 'message' position");

            assertNotEquals(vmElem.getTextRange(), messageElem.getTextRange(),
                    "Identifiers 'vm' and 'message' in {fx:Synchronize vm.message} must "
                    + "have different narrow text ranges. Got same range: " + vmElem.getTextRange());

            assertEquals("vm",      vmElem.getText(),
                    "Element at 'vm' offset must cover exactly the text 'vm'");
            assertEquals("message", messageElem.getText(),
                    "Element at 'message' offset must cover exactly the text 'message'");
        });
    }

    /**
     * For a long-form binding {@code text="{fx:Synchronize vm.message}"}, the element
     * returned for the binding keyword "Synchronize" must also be narrow: covering only
     * "Synchronize", not the full attribute value token.
     *
     * <p>Its range must be different from the "vm" segment's range, ensuring that moving the
     * caret from the keyword to a path segment refreshes the highlight.
     */
    @Test
    void longFormBindingKeywordAndPathSegmentHaveDifferentNarrowRanges() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.TextField",
                """
                  <TextField text="{fx:Synchronize vm.message}"/>
                """
        ));
        ReadAction.run(() -> {
            String text = getFixture().getFile().getText();

            int syncOffset = text.indexOf("Synchronize");       // 'S' of "Synchronize"
            int vmOffset   = text.indexOf("vm.message");        // 'v' of "vm"

            PsiElement syncElem = getFixture().getFile().findElementAt(syncOffset);
            PsiElement vmElem   = getFixture().getFile().findElementAt(vmOffset);

            assertNotNull(syncElem, "Expected a non-null element at the 'Synchronize' position");
            assertNotNull(vmElem,   "Expected a non-null element at the 'vm' position");

            assertNotEquals(syncElem.getTextRange(), vmElem.getTextRange(),
                    "Keyword 'Synchronize' and path segment 'vm' must have different narrow "
                    + "text ranges. Got same range: " + syncElem.getTextRange());

            assertEquals("Synchronize", syncElem.getText(),
                    "Element at 'Synchronize' offset must cover exactly 'Synchronize'");
            assertEquals("vm", vmElem.getText(),
                    "Element at 'vm' offset must cover exactly 'vm'");
        });
    }

    // -----------------------------------------------------------------------
    // Boolean-negation binding: ${!myButton3.disabled}
    // -----------------------------------------------------------------------

    /**
     * For a boolean-negation binding {@code disable="${!myButton3.disabled}"}, the element
     * returned for "myButton3" and the element returned for "disabled" must have different
     * narrow ranges.
     */
    @Test
    void booleanNegationBindingSegmentsHaveDifferentNarrowRanges() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button fx:id="myButton3"/>
                  <Button disable="${!myButton3.disabled}"/>
                """
        ));
        ReadAction.run(() -> {
            String text = getFixture().getFile().getText();

            // The second occurrence of "myButton3" is inside the binding expression
            int myButton3Offset = text.lastIndexOf("myButton3");      // 'm' of "myButton3" in binding
            int disabledOffset  = text.indexOf(".disabled}") + 1;     // 'd' of "disabled"

            PsiElement myButton3Elem = getFixture().getFile().findElementAt(myButton3Offset);
            PsiElement disabledElem  = getFixture().getFile().findElementAt(disabledOffset);

            assertNotNull(myButton3Elem, "Expected a non-null element at the 'myButton3' position");
            assertNotNull(disabledElem,  "Expected a non-null element at the 'disabled' position");

            assertNotEquals(myButton3Elem.getTextRange(), disabledElem.getTextRange(),
                    "Identifiers 'myButton3' and 'disabled' in ${!myButton3.disabled} must have "
                    + "different narrow text ranges. Got same range: " + myButton3Elem.getTextRange());

            assertEquals("myButton3", myButton3Elem.getText(),
                    "Element at 'myButton3' offset must cover exactly 'myButton3'");
            assertEquals("disabled",  disabledElem.getText(),
                    "Element at 'disabled' offset must cover exactly 'disabled'");
        });
    }

    // -----------------------------------------------------------------------
    // Import PI
    // -----------------------------------------------------------------------

    /**
     * Segments of a dotted import path such as {@code javafx.scene.control.Label} in
     * {@code <?import javafx.scene.control.Label?>} must have different narrow text ranges.
     */
    @Test
    void importPiSegmentsHaveDifferentNarrowRanges() {
        getFixture().configureByText("TestView.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.Label?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.TestView">
                  <Label text="hello"/>
                </BorderPane>
                """);
        ReadAction.run(() -> {
            String text = getFixture().getFile().getText();

            int javafxOffset  = text.indexOf("javafx.scene.control.Label");   // 'j' of "javafx"
            int controlOffset = text.indexOf(".control.Label") + 1;           // 'c' of "control"
            int labelOffset   = text.indexOf(".Label?>") + 1;                 // 'L' of "Label"

            PsiElement javafxElem  = getFixture().getFile().findElementAt(javafxOffset);
            PsiElement controlElem = getFixture().getFile().findElementAt(controlOffset);
            PsiElement labelElem   = getFixture().getFile().findElementAt(labelOffset);

            assertNotNull(javafxElem,  "Expected element at 'javafx'");
            assertNotNull(controlElem, "Expected element at 'control'");
            assertNotNull(labelElem,   "Expected element at 'Label'");

            assertNotEquals(javafxElem.getTextRange(),  controlElem.getTextRange(),
                    "Import segments 'javafx' and 'control' must have different narrow ranges");
            assertNotEquals(controlElem.getTextRange(), labelElem.getTextRange(),
                    "Import segments 'control' and 'Label' must have different narrow ranges");

            assertEquals("javafx",  javafxElem.getText());
            assertEquals("control", controlElem.getText());
            assertEquals("Label",   labelElem.getText());
        });
    }
}
