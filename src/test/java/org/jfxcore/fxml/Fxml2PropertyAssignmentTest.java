package org.jfxcore.fxml;

import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for property assignment validation in FXML files.
 *
 * <p>Covers attribute/element property assignment, coercion rules (enum, number,
 * boolean, Insets, Color, array, collection), duplicate properties, read-only
 * properties, and property naming rules.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2PropertyAssignmentTest extends Fxml2TestBase {

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2AttributeValueInspection());
    }

    // -----------------------------------------------------------------------
    // Duplicate property
    // -----------------------------------------------------------------------

    /** Duplicate attribute value fires "Duplicate attribute" on both name tokens. */
    @Test
    void duplicateAttributePropertyProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane <error descr="Duplicate attribute prefWidth">prefWidth</error>="10" <error descr="Duplicate attribute prefWidth">prefWidth</error>="20"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void duplicateElementPropertyProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane>
                    <prefWidth>10</prefWidth>
                    <error descr="prefWidth is set more than once"><prefWidth>20</prefWidth></error>
                  </GridPane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void duplicateAttributeAndElementPropertyProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane prefWidth="10">
                    <error descr="prefWidth is set more than once"><prefWidth>20</prefWidth></error>
                  </GridPane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Multiple children of the same unresolvable class type inside an unresolvable parent
     * must NOT produce "X is set more than once" errors.
     *
     * <p>Uppercase-starting tag names denote class instantiations in FXML by convention;
     * they must not be treated as property-element tags even when their import is missing.
     * The annotation reports "Cannot resolve symbol 'VBox'" for the VBox (whose parent
     * BorderPane IS resolved), but stays silent for the Button children (whose parent VBox
     * is unresolved: cascade suppression). The inspection must also stay silent.
     */
    @Test
    void multipleUnresolvableClassTagSiblingsProduceNoSetMoreThanOnceError() {
        // VBox not imported; parent BorderPane is imported -> annotator reports error on VBox.
        // Button not imported; parent VBox is unresolved -> annotator stays silent for Button.
        // The inspection must NOT additionally fire "Button is set more than once".
        getFixture().configureByText("TestView.fxml", fxml2(
                "",
                """
                  <<error descr="Cannot resolve symbol 'VBox'">VBox</error>>
                    <Button text="One"/>
                    <Button text="Two"/>
                    <Button text="Three"/>
                  </VBox>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Coercion: enum
    // -----------------------------------------------------------------------

    @Test
    void enumAttributeValueProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane alignment="CENTER"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void enumElementValueProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane>
                    <alignment>CENTER</alignment>
                  </GridPane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void chainedPropertyEnumAttributeValueProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView selectionModel.selectionMode="MULTIPLE"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Coercion: numbers / booleans
    // -----------------------------------------------------------------------

    @Test
    void numericAttributeValueProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button prefWidth="123.5"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void booleanAttributeValueProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button managed="true" visible="false"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void negativeInfinityAttributeValueProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button prefWidth="-Infinity"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void staticFieldValueOnDoublePropertyProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane prefWidth="POSITIVE_INFINITY"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Coercion: Insets
    // -----------------------------------------------------------------------

    @Test
    void singleValueInsetsAttributeProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane padding="1"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void commaSeparatedInsetsAttributeProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane padding="1,2,3,4"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** Two-value Insets is invalid. */
    @Test
    void invalidInsetsValueProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane padding=<error descr="'1,2' is not a valid value for padding">"1,2"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Coercion: Color / Paint
    // -----------------------------------------------------------------------

    @Test
    void namedColorAttributeValueProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button textFill="red"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void webColorAttributeValueProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button textFill="#12345678"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Coercion: collection (styleClass)
    // -----------------------------------------------------------------------

    @Test
    void commaSeparatedStyleClassesProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane styleClass="style1, style2, style3"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Static properties
    // -----------------------------------------------------------------------

    @Test
    void staticPropertyColumnIndexProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane\njavafx.scene.layout.Pane",
                """
                  <GridPane>
                    <Pane GridPane.columnIndex="1"/>
                  </GridPane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Property naming
    // -----------------------------------------------------------------------

    /** {@code foo.bar.baz} does not exist on Button and must produce an error. */
    @Test
    void unresolvablePropertyChainProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button <error descr="'foo.bar.baz' in javafx.scene.control.Button cannot be resolved">foo.bar.baz="Hello!"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** {@code <Button.text>Hello!</Button.text>} element notation is valid. */
    @Test
    void qualifiedPropertyInElementNotationProducesNoError() {
        getFixture().configureByText("TestView.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.*?>
                <Button xmlns="http://javafx.com/javafx" xmlns:fx="http://jfxcore.org/fxml/2.0">
                    <Button.text>Hello!</Button.text>
                </Button>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /** {@code Button.text="Hello!"} as an attribute (not a static property) is invalid. */
    @Test
    void qualifiedPropertyInAttributeNotationProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button <error descr="'Button.text' in javafx.scene.control.Button cannot be resolved">Button.text="Hello!"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Fully-qualified property element notation
    // -----------------------------------------------------------------------

    /**
     * Compiler: {@code Fully_Qualified_Property_Of_Base_Type_Is_Valid} -
     * {@code <javafx.scene.control.Labeled.text>} inside {@code <Button>} is valid.
     */
    @Test
    void fullyQualifiedPropertyInElementNotationProducesNoError() {
        getFixture().configureByText("TestView.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.*?>
                <Button xmlns="http://javafx.com/javafx" xmlns:fx="http://jfxcore.org/fxml/2.0">
                    <javafx.scene.control.Button.text>Hello!</javafx.scene.control.Button.text>
                </Button>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * FQN-qualified property from a base type: {@code <javafx.scene.control.Labeled.text>}
     * inside {@code <Button>} is valid (Labeled is a supertype of Button).
     */
    @Test
    void fullyQualifiedPropertyFromBaseTypeProducesNoError() {
        getFixture().configureByText("TestView.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.*?>
                <Button xmlns="http://javafx.com/javafx" xmlns:fx="http://jfxcore.org/fxml/2.0">
                    <javafx.scene.control.Labeled.text>Hello!</javafx.scene.control.Labeled.text>
                </Button>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Compiler: {@code Nonexistent_Qualified_Property_Cannot_Be_Resolved} -
     * A FQN-qualified property where the property name does not exist produces an error
     * on the property-name segment only.
     */
    @Test
    void fullyQualifiedNonExistentPropertyProducesError() {
        getFixture().configureByText("TestView.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.*?>
                <Button xmlns="http://javafx.com/javafx" xmlns:fx="http://jfxcore.org/fxml/2.0">
                    <javafx.scene.control.Button.<error descr="'doesNotExist' in javafx.scene.control.Button cannot be resolved">doesNotExist</error>>foo</javafx.scene.control.Button.doesNotExist>
                </Button>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Compiler: {@code Qualified_Property_Cannot_Be_Resolved_Without_Import} -
     * {@code <Button.text>} inside {@code <javafx.scene.control.Button>} (no import)
     * fails because {@code Button} is not in scope without the import: the plugin
     * reports "'Button' in javafx.scene.control.Button cannot be resolved".
     */
    @Test
    void qualifiedPropertyWithoutImportProducesError() {
        getFixture().configureByText("TestView.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <javafx.scene.control.Button xmlns="http://javafx.com/javafx" xmlns:fx="http://jfxcore.org/fxml/2.0">
                    <<error descr="'Button' in javafx.scene.control.Button cannot be resolved">Button.text</error>>Hello!</Button.text>
                </javafx.scene.control.Button>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * FQN-qualified property navigation: per-segment references exist on
     * {@code javafx.scene.control.Button.text} so that Ctrl+click on each
     * segment navigates to the package, class, or property method.
     */
    @Test
    void fullyQualifiedPropertyTagSegmentsAreIndividuallyNavigable() {
        getFixture().configureByText("TestView.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.*?>
                <Button xmlns="http://javafx.com/javafx" xmlns:fx="http://jfxcore.org/fxml/2.0">
                    <javafx.scene.control.Button.<caret>text>Hello!</javafx.scene.control.Button.text>
                </Button>
                """);
        com.intellij.openapi.application.ReadAction.run(() -> {
            com.intellij.psi.PsiReference ref = getFixture().getFile().findReferenceAt(getFixture().getCaretOffset());
            assertNotNull(ref, "Expected a reference on 'text' segment of FQN property tag");
            com.intellij.psi.PsiElement resolved = ref.resolve();
            assertNotNull(resolved, "Expected 'text' to resolve to a PsiElement");
        });
    }

    // -----------------------------------------------------------------------
    // Disambiguation: property element vs imported class tag
    // -----------------------------------------------------------------------

    /**
     * An unqualified {@code <text>} element is unambiguous when no class named
     * {@code text} is imported: it resolves as the {@code Button.text} property.
     */
    @Test
    void unqualifiedPropertyElementTagProducesNoError() {
        getFixture().configureByText("TestView.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.Button?>
                <Button xmlns="http://javafx.com/javafx" xmlns:fx="http://jfxcore.org/fxml/2.0">
                    <text>Hello!</text>
                </Button>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * When a class named {@code text} IS imported, an unqualified {@code <text>} tag
     * becomes a class instantiation, not a property assignment.
     * The qualified form {@code <Button.text>} must always resolve as a property even then.
     */
    @Test
    void qualifiedPropertyTagAlwaysResolvesAsPropertyEvenWhenClassImported() {
        // Add a fake "text" class to simulate the ambiguous case
        getFixture().addClass("""
                package test;
                public class text {}
                """);
        getFixture().configureByText("TestView.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.Button?>
                <?import test.text?>
                <Button xmlns="http://javafx.com/javafx" xmlns:fx="http://jfxcore.org/fxml/2.0">
                    <Button.text>Hello!</Button.text>
                </Button>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code Qualified_Property_Is_Interpreted_As_Static_Property} -
     * When the element tag's class prefix is NOT a supertype of the parent class,
     * the tag is treated as a static property (and produces an error on the property
     * name suffix when it doesn't exist as a static property on that class).
     */
    @Test
    void qualifiedPropertyFromUnrelatedClassIsInterpretedAsStaticAndProducesError() {
        getFixture().configureByText("TestView.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.*?>
                <Labeled xmlns="http://javafx.com/javafx" xmlns:fx="http://jfxcore.org/fxml/2.0">
                    <Button.<error descr="'text' in javafx.scene.control.Button cannot be resolved">text</error>>foo</Button.text>
                </Labeled>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Primitive JavaFX observable/property types: value validation
    // -----------------------------------------------------------------------

    /**
     * All JavaFX primitive property/observable types (DoubleProperty, FloatProperty,
     * LongProperty, BooleanProperty, IntegerProperty, and their Base/Wrapper/Observable variants)
     * must validate their values properly: valid numeric/boolean literals accepted,
     * non-parseable strings rejected.
     * The suite uses a custom class whose backing fields cover all the observable family members.
     */
    @Test
    void doublePropertyBackedFieldValidValueProducesNoError() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.DoubleProperty;
                import javafx.beans.property.SimpleDoubleProperty;
                public class DoubleHolder {
                    private final DoubleProperty amount = new SimpleDoubleProperty(0);
                    public DoubleProperty amountProperty() { return amount; }
                    public double getAmount() { return amount.get(); }
                    public void setAmount(double v) { amount.set(v); }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.DoubleHolder", """
                  <DoubleHolder amount="3.14"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void doublePropertyBackedFieldInvalidValueProducesError() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.DoubleProperty;
                import javafx.beans.property.SimpleDoubleProperty;
                public class DoubleHolder2 {
                    private final DoubleProperty amount = new SimpleDoubleProperty(0);
                    public DoubleProperty amountProperty() { return amount; }
                    public double getAmount() { return amount.get(); }
                    public void setAmount(double v) { amount.set(v); }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.DoubleHolder2", """
                  <DoubleHolder2 amount=<error descr="Cannot coerce 'FOO' to double">"FOO"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void floatPropertyBackedFieldInvalidValueProducesError() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.FloatProperty;
                import javafx.beans.property.SimpleFloatProperty;
                public class FloatHolder {
                    private final FloatProperty ratio = new SimpleFloatProperty(0);
                    public FloatProperty ratioProperty() { return ratio; }
                    public float getRatio() { return ratio.get(); }
                    public void setRatio(float v) { ratio.set(v); }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.FloatHolder", """
                  <FloatHolder ratio=<error descr="Cannot coerce 'BAD' to float">"BAD"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void longPropertyBackedFieldInvalidValueProducesError() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.LongProperty;
                import javafx.beans.property.SimpleLongProperty;
                public class LongHolder {
                    private final LongProperty count = new SimpleLongProperty(0);
                    public LongProperty countProperty() { return count; }
                    public long getCount() { return count.get(); }
                    public void setCount(long v) { count.set(v); }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.LongHolder", """
                  <LongHolder count=<error descr="Cannot coerce 'BAD' to long">"BAD"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void booleanPropertyBackedFieldInvalidValueProducesError() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.BooleanProperty;
                import javafx.beans.property.SimpleBooleanProperty;
                public class BoolHolder {
                    private final BooleanProperty flag = new SimpleBooleanProperty(false);
                    public BooleanProperty flagProperty() { return flag; }
                    public boolean isFlag() { return flag.get(); }
                    public void setFlag(boolean v) { flag.set(v); }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.BoolHolder", """
                  <BoolHolder flag=<error descr="Cannot coerce 'BAD' to boolean">"BAD"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void booleanPropertyBackedFieldValidValueProducesNoError() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.BooleanProperty;
                import javafx.beans.property.SimpleBooleanProperty;
                public class BoolHolder2 {
                    private final BooleanProperty flag = new SimpleBooleanProperty(false);
                    public BooleanProperty flagProperty() { return flag; }
                    public boolean isFlag() { return flag.get(); }
                    public void setFlag(boolean v) { flag.set(v); }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.BoolHolder2", """
                  <BoolHolder2 flag="true"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A {@code DoubleProperty}-backed backing field exposed via a chained property path in
     * element notation must be validated correctly: invalid value must produce an error.
     */
    @Test
    void doublePropertyChainedElementNotationInvalidValueProducesError() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.DoubleProperty;
                import javafx.beans.property.SimpleDoubleProperty;
                public class Inner {
                    private final DoubleProperty scale = new SimpleDoubleProperty(1.0);
                    public DoubleProperty scaleProperty() { return scale; }
                    public double getScale() { return scale.get(); }
                    public void setScale(double v) { scale.set(v); }
                }
                """);
        getFixture().addClass("""
                package test;
                public class Outer extends javafx.scene.layout.VBox {
                    public Inner getInner() { return new Inner(); }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.Outer", """
                  <Outer>
                    <inner.scale><error descr="Cannot coerce 'FOO' to double">FOO</error></inner.scale>
                  </Outer>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Generic class with fx:typeArguments
    // -----------------------------------------------------------------------

    /**
     * When a generic class is used with {@code fx:typeArguments}, a literal value for a
     * type-parameter-typed property should be accepted without a "Cannot coerce" error.
     */
    @Test
    void literalValueForTypeParameterPropertyWithTypeArgumentsProducesNoError() {
        getFixture().addClass("""
                package test;
                import javafx.scene.control.Label;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                public class GenericControl<T> extends Label {
                    private final ObjectProperty<T> item = new SimpleObjectProperty<>(this, "item", null);
                    public ObjectProperty<T> itemProperty() { return item; }
                    public T getItem() { return item.get(); }
                    public void setItem(T v) { item.set(v); }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.GenericControl", """
                  <GenericControl fx:typeArguments="java.lang.Double" item="12.34567"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void doublePropertyChainedElementNotationValidValueProducesNoError() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.DoubleProperty;
                import javafx.beans.property.SimpleDoubleProperty;
                public class Inner2 {
                    private final DoubleProperty scale = new SimpleDoubleProperty(1.0);
                    public DoubleProperty scaleProperty() { return scale; }
                    public double getScale() { return scale.get(); }
                    public void setScale(double v) { scale.set(v); }
                }
                """);
        getFixture().addClass("""
                package test;
                public class Outer2 extends javafx.scene.layout.VBox {
                    public Inner2 getInner2() { return new Inner2(); }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.Outer2", """
                  <Outer2>
                    <inner2.scale>1.5</inner2.scale>
                  </Outer2>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }
}
