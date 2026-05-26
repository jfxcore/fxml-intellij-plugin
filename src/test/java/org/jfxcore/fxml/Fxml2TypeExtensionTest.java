package org.jfxcore.fxml;

import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.jfxcore.fxml.annotator.Fxml2FxAttributeInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tests for the {@code fx:Class} markup extension (renamed from {@code fx:Type}).
 *
 * <p>The {@code fx:Class} extension resolves a name to a {@code Class<?>} literal.
 * It can be used in attribute notation ({@code {fx:Class MyClass}}), element notation
 * inside a property element, and as an argument to function bindings.
 *
 * <p>Corresponds to {@code reference/class.md} in the FXML compiler documentation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2TypeExtensionTest extends Fxml2TestBase {

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2AttributeValueInspection(), new Fxml2FxAttributeInspection());
    }

    // -----------------------------------------------------------------------
    // Attribute notation: {fx:Class ClassName}
    // -----------------------------------------------------------------------

    /**
     * {@code {fx:Class MyClass}}: valid class name in inline attribute form
     * should produce no error.
     */
    @Test
    void fxTypeWithValidClassProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Class java.lang.String}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code {fx:Class MyClass}} with an imported simple name: should resolve
     * without error.
     */
    @Test
    void fxTypeWithImportedSimpleNameProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\njava.lang.String",
                """
                  <Label text="{fx:Class String}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code {fx:Class NonExistentClass}}: should produce an error.
     */
    @Test
    void fxTypeWithUnresolvableClassProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Class <error descr="Cannot resolve symbol 'NonExistentClass'">NonExistentClass</error>}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code {fx:Class name=MyClass}}: explicit {@code name=} property form
     * should be accepted.
     */
    @Test
    void fxTypeWithExplicitNamePropertyProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Class name=java.lang.String}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Element notation
    // -----------------------------------------------------------------------

    /**
     * Doc element notation:
     * <pre>{@code
     * <Label>
     *   <text><fx:Class name="String"/></text>
     * </Label>
     * }</pre>
     */
    @Test
    void fxTypeElementNotationProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label>
                    <text>
                      <fx:Class name="java.lang.String"/>
                    </text>
                  </Label>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Element notation with an unresolvable class name should produce an error.
     */
    @Test
    void fxTypeElementNotationWithUnresolvableClassProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label>
                    <text>
                      <fx:Class name="<error descr="Cannot resolve symbol 'DoesNotExist'">DoesNotExist</error>"/>
                    </text>
                  </Label>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }
}
