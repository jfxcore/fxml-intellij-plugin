package org.jfxcore.fxml;

import org.jfxcore.fxml.annotator.Fxml2UnusedFxIdInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tests for {@link Fxml2UnusedFxIdInspection}: reports {@code fx:id} attributes
 * whose auto-generated field is never accessed in the code-behind class
 * and whose value never appears in a binding expression in the FXML2 file.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2UnusedFxIdInspectionTest extends Fxml2TestBase {

    @BeforeEach
    void enableInspection() {
        getFixture().enableInspections(new Fxml2UnusedFxIdInspection());
    }

    // -----------------------------------------------------------------------
    // Happy paths: no warning expected
    // -----------------------------------------------------------------------

    /**
     * An fx:id whose auto-generated field is accessed from the code-behind class produces no warning.
     */
    @Test
    void fxIdWithFieldAccessProducesNoWarning() {
        getFixture().addFileToProject("test/UsedFieldView.fxml",
                fxml2("""
                """, """
                        <Button fx:id="myButton"/>
                        """, "test.UsedFieldView"));
        getFixture().configureByText("UsedFieldView.java", """
                package test;
                import javafx.scene.control.Button;
                public class UsedFieldView {
                    Button myButton;
                    void init() {
                        myButton.setText("hello");
                    }
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * An fx:id used in a binding expression produces no warning.
     */
    @Test
    void fxIdInBindingExpressionProducesNoWarning() {
        getFixture().addFileToProject("test/UsedBindingView.fxml",
                fxml2("""
                """, """
                        <Button fx:id="btn1"/>
                        <Label text="${btn1.text}"/>
                        """, "test.UsedBindingView"));
        getFixture().configureByText("UsedBindingView.java", """
                package test;
                public class UsedBindingView {
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * An fx:id used in a command binding expression produces no warning.
     */
    @Test
    void fxIdInCommandBindingProducesNoWarning() {
        getFixture().addFileToProject("test/UsedCommandView.fxml",
                fxml2("""
                """, """
                        <Button fx:id="btn1"/>
                        <Button Command.onAction="$btn1.fire"/>
                        """, "test.UsedCommandView"));
        getFixture().configureByText("UsedCommandView.java", """
                package test;
                public class UsedCommandView {
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Multiple fx:id values where some are used and some are unused.
     * Only the unused ones should produce warnings.
     */
    @Test
    void onlyUnusedFxIdsProduceWarnings() {
        getFixture().addFileToProject("test/PartialView.fxml",
                fxml2("""
                """, """
                        <Button fx:id="usedBtn"/>
                        <Label text="${usedBtn.text}"/>
                        <Button <warning descr="Unused fx:id">fx:id="unusedBtn"</warning>/>\
                        <Button <warning descr="Unused fx:id">fx:id="anotherUnused"</warning>/>\
                        """, "test.PartialView"));
        getFixture().configureByText("PartialView.java", """
                package test;
                public class PartialView {
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Warning cases
    // -----------------------------------------------------------------------

    /**
     * An fx:id whose field is never accessed and has no binding usage produces a warning.
     */
    @Test
    void unusedFxIdProducesWarning() {
        getFixture().addFileToProject("test/UnusedView.fxml",
                fxml2("""
                """, """
                        <Button <warning descr="Unused fx:id">fx:id="unusedBtn"</warning>/>\
                        """, "test.UnusedView"));
        getFixture().configureByText("UnusedView.java", """
                package test;
                public class UnusedView {
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Multiple unused fx:id values all produce warnings.
     */
    @Test
    void multipleUnusedFxIdsAllProduceWarnings() {
        getFixture().addFileToProject("test/MultiUnusedView.fxml",
                fxml2("""
                """, """
                        <Button <warning descr="Unused fx:id">fx:id="btn1"</warning>/>\
                        <Button <warning descr="Unused fx:id">fx:id="btn2"</warning>/>\
                        """, "test.MultiUnusedView"));
        getFixture().configureByText("MultiUnusedView.java", """
                package test;
                public class MultiUnusedView {
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * An fx:id whose auto-generated field is accessed via a parent class reference produces no warning.
     */
    @Test
    void fxIdWithFieldAccessInParentClassProducesNoWarning() {
        getFixture().addFileToProject("test/InheritedView.fxml",
                fxml2("""
                """, """
                        <Button fx:id="myButton"/>
                        """, "test.InheritedView"));
        getFixture().configureByText("InheritedView.java", """
                package test;
                import javafx.scene.control.Button;
                public class InheritedView extends InheritedViewBase {
                    void init() {
                        this.myButton.setText("hello");
                    }
                }
                class InheritedViewBase {
                    Button myButton;
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * An fx:id used in a nested element's binding expression produces no warning.
     */
    @Test
    void fxIdInNestedBindingProducesNoWarning() {
        getFixture().addFileToProject("test/NestedView.fxml",
                fxml2("""
                """, """
                        <Button fx:id="btn1"/>
                        <VBox>
                            <HBox>
                                <Label text="${btn1.text}"/>
                            </HBox>
                        </VBox>
                        """, "test.NestedView"));
        getFixture().configureByText("NestedView.java", """
                package test;
                public class NestedView {
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    /**
     * An empty fx:id value is ignored (no warning).
     */
    @Test
    void emptyFxIdProducesNoWarning() {
        getFixture().addFileToProject("test/EmptyFxIdView.fxml",
                fxml2("""
                """, """
                        <Button fx:id=""/>
                        """, "test.EmptyFxIdView"));
        getFixture().configureByText("EmptyFxIdView.java", """
                package test;
                public class EmptyFxIdView {
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * FXML2 file without a code-behind class: only binding-segment references count.
     */
    @Test
    void fxIdWithoutCodeBehindChecksOnlyBindings() {
        getFixture().addFileToProject("test/NoCodeBehindView.fxml",
                fxml2("""
                """, """
                        <Button fx:id="btn1"/>
                        <Label text="${btn1.text}"/>
                        """, "test.NoCodeBehindView"));
        getFixture().configureByText("NoCodeBehindView.java", """
                package test;
                public class NoCodeBehindView {
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * An fx:id whose auto-generated field is accessed from a Kotlin code-behind class
     * produces no warning. The generated field lives on the compiler-generated Java base class
     * and is referenced from the {@code .kt} source file by simple-name access.
     */
    @Test
    void fxIdWithFieldAccessFromKotlinCodeBehindProducesNoWarning() {
        getFixture().addFileToProject("test/UsedKtViewBase.java", """
                package test;
                import javafx.scene.control.Button;
                import javafx.scene.layout.StackPane;
                public abstract class UsedKtViewBase extends StackPane {
                    protected Button btn1;
                    protected Button btn2;
                    protected final void initializeComponent() {}
                }
                """);
        getFixture().addFileToProject("test/UsedKtView.kt", """
                package test
                class UsedKtView() : UsedKtViewBase() {
                    init {
                        initializeComponent()
                        useButton(btn1)
                    }
                    private fun useButton(b: javafx.scene.control.Button?) {}
                }
                """);
        getFixture().configureByText("UsedKtView.fxml",
                fxml2("""
                        javafx.scene.control.Button
                        """, """
                        <Button fx:id="btn1"/>
                        <Button <warning descr="Unused fx:id">fx:id="btn2"</warning>/>\
                        """, "test.UsedKtView"));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * An fx:id whose auto-generated field is accessed from the code-behind produces no warning.
     */
    @Test
    void fxIdFieldAccessProducesNoWarning() {
        getFixture().addFileToProject("test/FieldMatchView.fxml",
                fxml2("""
                """, """
                        <TextField fx:id="textField"/>
                        """, "test.FieldMatchView"));
        getFixture().configureByText("FieldMatchView.java", """
                package test;
                import javafx.scene.control.TextField;
                public class FieldMatchView {
                    TextField textField;
                    void init() {
                        textField.setText("hello");
                    }
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * When the code-behind extends a generated base class that has not yet been compiled,
     * fx:id attributes must not produce false-positive warnings.
     * The generated base class would normally hold the fx:id fields; if it is absent
     * from the project, field-usage resolution fails and the inspection must stay silent.
     */
    @Test
    void fxIdNotWarnedWhenGeneratedBaseClassIsUnresolvable() {
        // UncompiledViewBase is intentionally absent — simulates the fxml2 compiler
        // annotation processor not having run yet.
        getFixture().addFileToProject("test/UncompiledView.java", """
                package test;
                import javafx.scene.control.Button;
                public class UncompiledView extends UncompiledViewBase {
                    void init() {
                        btn1.setText("hello");
                    }
                }
                """);
        getFixture().configureByText("UncompiledView.fxml",
                fxml2("""
                        javafx.scene.control.Button
                        """, """
                        <Button fx:id="btn1"/>
                        """, "test.UncompiledView"));
        getFixture().checkHighlighting(true, false, false);
    }

    /**
     * Same as {@link #fxIdNotWarnedWhenGeneratedBaseClassIsUnresolvable()} but for a Kotlin
     * code-behind whose supertype list references the (missing) generated base class.
     */
    @Test
    void fxIdNotWarnedWhenGeneratedBaseClassIsUnresolvableKotlin() {
        // UncompiledKtViewBase is intentionally absent — simulates the fxml2 compiler
        // annotation processor not having run yet.
        getFixture().addFileToProject("test/UncompiledKtView.kt", """
                package test
                class UncompiledKtView() : UncompiledKtViewBase() {
                    init {
                        btn1.text = "hello"
                    }
                }
                """);
        getFixture().configureByText("UncompiledKtView.fxml",
                fxml2("""
                        javafx.scene.control.Button
                        """, """
                        <Button fx:id="btn1"/>
                        """, "test.UncompiledKtView"));
        getFixture().checkHighlighting(true, false, false);
    }
}
