package org.jfxcore.fxml;

import com.intellij.codeInspection.BatchQuickFix;
import com.intellij.codeInspection.ex.QuickFixWrapper;
import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.jfxcore.fxml.annotator.Fxml2FxAttributeInspection;
import org.jfxcore.fxml.annotator.Fxml2RedundantDoubleBangInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Fxml2RedundantDoubleBangInspection}.
 *
 * <p>The {@code !!} (BOOLIFY) operator is a compile-time no-op when applied to a
 * {@code Boolean}-typed binding path.  For {@code Number} or other reference types it
 * maps to {@code BooleanBindings.isNotZero} / {@code isNotNull} and must not be warned.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2RedundantDoubleBangTest extends Fxml2TestBase {

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(
                new Fxml2RedundantDoubleBangInspection(),
                new Fxml2AttributeValueInspection(),
                new Fxml2FxAttributeInspection());
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
                import javafx.beans.property.BooleanProperty;
                import javafx.beans.property.SimpleBooleanProperty;
                import javafx.beans.property.IntegerProperty;
                import javafx.beans.property.SimpleIntegerProperty;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                public class TestView extends TestViewBase {
                    private final BooleanProperty active = new SimpleBooleanProperty(false);
                    public BooleanProperty activeProperty() { return active; }
                    public boolean isActive() { return active.get(); }

                    private final IntegerProperty count = new SimpleIntegerProperty(0);
                    public IntegerProperty countProperty() { return count; }
                    public int getCount() { return count.get(); }

                    private final ObjectProperty<Object> item = new SimpleObjectProperty<>();
                    public ObjectProperty<Object> itemProperty() { return item; }
                    public Object getItem() { return item.get(); }
                }
                """);
    }

    // -----------------------------------------------------------------------
    // Positive cases: warning expected
    // -----------------------------------------------------------------------

    /**
     * {@code ${!!active}}: {@code active} resolves to {@code BooleanProperty} (Boolean),
     * so {@code !!} is a no-op and should be flagged.
     */
    @Test
    void doubleBangOnBooleanBindFlagsWeakWarning() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button disable="${<weak_warning descr="'!!' is redundant on a Boolean binding">!!</weak_warning>active}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    /**
     * {@code $!!active} (Evaluate compact form {@code $}) also warns: the position of {@code !!}
     * is correctly computed for the {@code $} prefix (no braces).
     */
    @Test
    void doubleBangInEvaluateSyntaxOnBooleanFlagsWeakWarning() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button disable="$<weak_warning descr="'!!' is redundant on a Boolean binding">!!</weak_warning>active"/>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    // -----------------------------------------------------------------------
    // Negative cases: no warning expected
    // -----------------------------------------------------------------------

    /**
     * {@code ${!!count}}: {@code count} is an {@code IntegerProperty} (Number),
     * so {@code !!} maps to {@code BooleanBindings.isNotZero}: not a no-op.
     */
    @Test
    void doubleBangOnNumericBindingProducesNoWarning() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button visible="${!!count}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    /**
     * {@code ${!!item}}: {@code item} is an {@code Object} type,
     * so {@code !!} maps to {@code BooleanBindings.isNotNull}: not a no-op.
     */
    @Test
    void doubleBangOnObjectBindingProducesNoWarning() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button visible="${!!item}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    /**
     * Single {@code !} on Boolean never warns: {@code !} maps to {@code BooleanBindings.isNot},
     * which is a real transformation (logical negation).
     */
    @Test
    void singleBangOnBooleanProducesNoWarning() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button disable="${!active}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    /**
     * {@code ${!!active}} where the path cannot be resolved (unknown property): no crash,
     * no warning (resultType is null so we cannot conclude it's Boolean).
     * The annotator still reports an ERROR on the unresolvable segment; we just verify that
     * no additional {@code <weak_warning>} is added for the {@code !!} itself.
     */
    @Test
    void doubleBangWithUnresolvablePathProducesNoWarning() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button disable="${!!<error descr="'nonExistent' in test.TestView cannot be resolved">nonExistent</error>}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    // -----------------------------------------------------------------------
    // Quick-fix
    // -----------------------------------------------------------------------

    /**
     * The quick-fix exposes batch-apply support so "Fix all in file" is available.
     */
    @Test
    void removeDoubleBangFixIsBatchApplicable() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button disable="${!!<caret>active}"/>
                """
        ));
        getFixture().doHighlighting();
        var action = getFixture().getAllQuickFixes().stream()
                .filter(f -> f.getText().contains("Remove redundant '!!'"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Quick-fix not found"));
        var fix = QuickFixWrapper.unwrap(action);
        assertNotNull(fix, "Fix should be a LocalQuickFix");
        assertInstanceOf(BatchQuickFix.class, fix,
                "RemoveDoubleBangFix should implement BatchQuickFix for 'Fix all in file' support");
    }

    /**
     * The quick-fix "Remove redundant '!!'" strips {@code !!} from the value,
     * turning {@code disable="${!!active}"} into {@code disable="${active}"}.
     */
    @Test
    void quickFixRemovesDoubleBangFromBracesSyntax() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button disable="${!!<caret>active}"/>
                """
        ));
        getFixture().doHighlighting();
        var fixes = getFixture().getAllQuickFixes();
        assertFalse(fixes.isEmpty(), "Expected a quick-fix for redundant '!!': " + fixes);
        getFixture().launchAction(fixes.stream()
                .filter(f -> f.getText().contains("Remove redundant '!!'"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Quick-fix not found: " + fixes)));
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("disable=\"${active}\""),
                "Expected '!!' to be removed: got " + result);
        assertFalse(result.contains("!!"),
                "Expected no '!!' remaining in the file: got " + result);
    }

    /**
     * The quick-fix works for the Evaluate compact ({@code $}) syntax:
     * {@code disable="$!!active"} -> {@code disable="$active"}.
     */
    @Test
    void quickFixRemovesDoubleBangFromEvaluateSyntax() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button disable="$!!<caret>active"/>
                """
        ));
        getFixture().doHighlighting();
        var fixes = getFixture().getAllQuickFixes();
        assertFalse(fixes.isEmpty(), "Expected a quick-fix for redundant '!!': " + fixes);
        getFixture().launchAction(fixes.stream()
                .filter(f -> f.getText().contains("Remove redundant '!!'"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Quick-fix not found: " + fixes)));
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("disable=\"$active\""),
                "Expected '!!' to be removed: got " + result);
        assertFalse(result.contains("!!"),
                "Expected no '!!' remaining in the file: got " + result);
    }
}
