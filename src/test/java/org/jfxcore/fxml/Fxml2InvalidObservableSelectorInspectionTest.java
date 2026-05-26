package org.jfxcore.fxml;

import com.intellij.codeInspection.BatchQuickFix;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ex.QuickFixWrapper;
import com.intellij.openapi.application.ReadAction;
import org.jfxcore.fxml.annotator.Fxml2InvalidObservableSelectorInspection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Fxml2InvalidObservableSelectorInspection}.
 *
 * <p>The inspection detects binding path segments accessed via {@code ::} on types that
 * are not {@code ObservableValue} subtypes, and offers a quick-fix to replace {@code ::}
 * with {@code .}.  The quick-fix must implement {@link BatchQuickFix} so that
 * "Fix all in file" is available from the Alt+Enter menu.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2InvalidObservableSelectorInspectionTest extends Fxml2TestBase {

    @BeforeAll
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
                import java.util.function.Supplier;
                public class TestView extends TestViewBase {
                    // Observable property - titleProperty() returns StringProperty (ObservableValue)
                    // '::titleProperty' is valid because it returns an ObservableValue
                    private final StringProperty title = new SimpleStringProperty();
                    public StringProperty titleProperty() { return title; }

                    // Non-observable field - '::' is INVALID here; '.' must be used instead
                    private final Supplier<String> labelSupplier = () -> "hello";
                    public Supplier<String> getLabelSupplier() { return labelSupplier; }

                    // Helper for navigation in binding paths: returns this, so '::titleProperty'
                    // can be tested on the result of a navigation step
                    public TestView getThis() { return this; }
                }
                """);
    }

    @BeforeEach
    void enableInspection() {
        getFixture().enableInspections(new Fxml2InvalidObservableSelectorInspection());
    }

    // -----------------------------------------------------------------------
    // Detection
    // -----------------------------------------------------------------------

    /**
     * Using {@code ::} on a non-{@code ObservableValue} field is reported as an
     * information-level problem that carries the quick-fix.
     */
    @Test
    void invalidObservableSelectorIsReported() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="$labelSupplier::<caret>get"/>
                """
        ));
        getFixture().doHighlighting();
        var fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null
                            && "Replace observable-selection operator".equals(fix.getFamilyName());
                })
                .toList();
        assertFalse(fixes.isEmpty(),
                "Inspection should offer 'Replace observable-selection operator' fix "
                + "when '::' is used on a non-Observable field");
    }

    /**
     * Using {@code ::} on a member that returns an {@code ObservableValue} must not be reported.
     * Here {@code getThis()} returns {@code TestView}, and {@code titleProperty()} on
     * {@code TestView} returns {@code StringProperty}, an {@code ObservableValue} subtype,
     * so {@code ::titleProperty} is a valid observable-selector access.
     */
    @Test
    void validObservableSelectorIsNotReported() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="$getThis::<caret>titleProperty"/>
                """
        ));
        getFixture().doHighlighting();
        var fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null
                            && "Replace observable-selection operator".equals(fix.getFamilyName());
                })
                .toList();
        assertTrue(fixes.isEmpty(),
                "Inspection must not flag '::' when the accessed member returns an ObservableValue");
    }

    // -----------------------------------------------------------------------
    // Batch-apply support
    // -----------------------------------------------------------------------

    /**
     * Running the inspection in batch mode (isOnTheFly=false) must not register
     * {@link ProblemHighlightType#INFORMATION}-level problems: IntelliJ's
     * {@code InspectionEngine} rejects them with a {@code PluginException} when
     * collecting fixes for "Fix all in file".
     */
    @Test
    void batchModeDoesNotRegisterInformationLevelProblem() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="$labelSupplier::get"/>
                """
        ));
        var manager = InspectionManager.getInstance(getFixture().getProject());
        ProblemDescriptor[] problems = ReadAction.compute(() ->
                new Fxml2InvalidObservableSelectorInspection()
                        .checkFile(getFixture().getFile(), manager, false));
        assertNotNull(problems, "Batch mode must find problems so Fix-all-in-file can apply fixes");
        for (var p : problems) {
            assertNotEquals(ProblemHighlightType.INFORMATION, p.getHighlightType(),
                    "INFORMATION-level problems are forbidden in batch mode by InspectionEngine");
        }
    }

    /**
     * The quick-fix must implement {@link BatchQuickFix} so that "Fix all in file"
     * is available from the Alt+Enter menu.
     */
    @Test
    void replaceSelectorFixIsBatchApplicable() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="$labelSupplier::<caret>get"/>
                """
        ));
        getFixture().doHighlighting();
        var fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null
                            && "Replace observable-selection operator".equals(fix.getFamilyName());
                })
                .toList();
        assertFalse(fixes.isEmpty(), "Expected 'Replace observable-selection operator' fix");
        var fix = QuickFixWrapper.unwrap(fixes.getFirst());
        assertInstanceOf(BatchQuickFix.class, fix,
                "ReplaceObservableSelectorInspectionFix should implement BatchQuickFix");
    }

    // -----------------------------------------------------------------------
    // Quick-fix behavior
    // -----------------------------------------------------------------------

    /**
     * Applying the fix replaces {@code ::} with {@code .} in the binding path.
     */
    @Test
    void quickFixReplacesSelectorWithDot() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="$labelSupplier::<caret>get"/>
                """
        ));
        getFixture().doHighlighting();
        var action = getFixture().getAllQuickFixes().stream()
                .filter(f -> f.getText().contains("Replace '::' with '.'"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Quick-fix not found"));
        getFixture().launchAction(action);
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("$labelSupplier.get"),
                "Expected '::' replaced with '.', got: " + result);
        assertFalse(result.contains("::"), "Expected no '::' remaining");
    }
}
