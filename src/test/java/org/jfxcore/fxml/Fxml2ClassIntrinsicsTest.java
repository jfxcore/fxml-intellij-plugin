package org.jfxcore.fxml;

import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.jfxcore.fxml.annotator.Fxml2FxAttributeInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tests for root-element intrinsic attributes that control the generated class:
 * {@code fx:subclass}, {@code fx:classModifier}, {@code fx:classParameters},
 * and {@code fx:className}.
 *
 * <p>All four attributes may only appear on the root element.
 *
 * <p>Corresponds to:
 * <ul>
 *   <li>{@code reference/subclass.md}</li>
 *   <li>{@code reference/classModifier.md}</li>
 *   <li>{@code reference/classParameters.md}</li>
 *   <li>{@code reference/className.md}</li>
 * </ul>
 * in the fxml2 compiler documentation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2ClassIntrinsicsTest extends Fxml2TestBase {

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2AttributeValueInspection(), new Fxml2FxAttributeInspection());
    }

    // -----------------------------------------------------------------------
    // fx:classModifier
    // -----------------------------------------------------------------------

    /**
     * {@code fx:classModifier="protected"} on the root element should be valid.
     */
    @Test
    void fxClassModifierProtectedOnRootProducesNoError() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:classModifier="protected"/>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code fx:classModifier="package"} on the root element should be valid.
     */
    @Test
    void fxClassModifierPackageOnRootProducesNoError() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:classModifier="package"/>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * An invalid {@code fx:classModifier} value should produce an error.
     */
    @Test
    void fxClassModifierInvalidValueProducesError() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:classModifier=<error descr="Invalid class modifier: private">"private"</error>/>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code fx:classModifier} on a non-root element should produce an error.
     */
    @Test
    void fxClassModifierOnNonRootElementProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane <error descr="Unexpected intrinsic: classModifier">fx:classModifier="protected"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // fx:classParameters
    // -----------------------------------------------------------------------

    /**
     * {@code fx:classParameters="String, Integer"}: valid constructor parameters
     * on the root element should be accepted.
     */
    @Test
    void fxClassParametersOnRootProducesNoError() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:classParameters="String, Integer"/>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Doc example: {@code fx:classParameters="String, MyClass<Double>"}: generic class
     * as constructor parameter should be accepted.
     */
    @Test
    void fxClassParametersWithGenericTypeProducesNoError() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:classParameters="String, java.util.List&lt;Double&gt;"/>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code fx:classParameters} on a non-root element should produce an error.
     */
    @Test
    void fxClassParametersOnNonRootElementProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane <error descr="Unexpected intrinsic: classParameters">fx:classParameters="String"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // fx:subclass
    // -----------------------------------------------------------------------

    /**
     * {@code fx:subclass="com.example.MyView"} on the root element should be valid.
     */
    @Test
    void fxSubclassOnRootProducesNoError() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.TestView"/>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code fx:subclass} on a non-root element should produce an error.
     */
    @Test
    void fxSubclassOnNonRootElementProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane <error descr="Unexpected intrinsic: subclass">fx:subclass="test.TestView"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // fx:className
    // -----------------------------------------------------------------------

    /**
     * {@code fx:className="MyCustomBaseClass"} on the root element alongside
     * {@code fx:subclass} should be accepted without error.
     */
    @Test
    void fxClassNameWithFxSubclassOnRootProducesNoError() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.TestView"
                            fx:className="MyCustomBaseClass"/>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code fx:className} without {@code fx:subclass} should produce an error,
     * since it is only meaningful when a code-behind class is present.
     */
    @Test
    void fxClassNameWithoutFxSubclassProducesError() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:className=<error descr="fx:className can only be used with fx:subclass">"MyCustomBaseClass"</error>/>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code fx:className} on a non-root element should produce an error.
     */
    @Test
    void fxClassNameOnNonRootElementProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane <error descr="Unexpected intrinsic: className">fx:className="MyBase"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }
}
