package org.jfxcore.fxml;

import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tests for object instantiation validation in FXML files.
 *
 * <p>Covers:
 * <ul>
 *   <li>Object instantiation via default constructor and {@code @NamedArg} constructors</li>
 *   <li>{@code fx:id} interaction with the node's {@code id} property</li>
 *   <li>Missing named arguments producing CONSTRUCTOR_NOT_FOUND</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2InstantiationTest extends Fxml2TestBase {

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2AttributeValueInspection());
    }

    // -----------------------------------------------------------------------
    // Default constructor / positive cases
    // -----------------------------------------------------------------------

    /** basic instantiation, no error */
    @Test
    void basicInstantiationWithFxIdProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane fx:id="pane0"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** id attr alongside fx:id */
    @Test
    void fxIdAlongsideIdAttributeProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane fx:id="pane0" id="foo"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** id set twice */
    @Test
    void duplicateIdAttributeOnInstantiatedObjectProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane <error descr="Duplicate attribute id">id</error>="foo" <error descr="Duplicate attribute id">id</error>="bar"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // @NamedArg constructors
    // -----------------------------------------------------------------------

    /** positive case */
    @Test
    void insetsWithAllNamedArgsProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane\njavafx.geometry.Insets",
                """
                  <GridPane>
                    <fx:define>
                      <Insets fx:id="insets1" left="1" top="2" right="3" bottom="4"/>
                    </fx:define>
                  </GridPane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** single-value constructor */
    @Test
    void insetsWithSingleNamedArgProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane\njavafx.geometry.Insets",
                """
                  <GridPane>
                    <padding><Insets topRightBottomLeft="1.5"/></padding>
                  </GridPane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** {@code <Insets left="1" top="2"/>}: only two of the four required args. */
    @Test
    void insetsWithMissingNamedArgsProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane\njavafx.geometry.Insets",
                """
                  <GridPane>
                    <padding><error descr="No suitable constructor found for javafx.geometry.Insets"><Insets fx:id="insets" left="1" top="2"/></error></padding>
                  </GridPane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // @NamedArg with element notation
    // -----------------------------------------------------------------------

    @Test
    void namedArgConstructorWithElementNotationProducesNoError() {
        // Add MultiArgCtorObject: extends GridPane, requires @NamedArg arg1 (GridPane) and arg2 (Button)
        getFixture().addClass("""
                import javafx.beans.NamedArg;
                import javafx.scene.control.Button;
                import javafx.scene.layout.GridPane;
                public class MultiArgCtorObject extends GridPane {
                    public MultiArgCtorObject(@NamedArg("arg1") GridPane arg1, @NamedArg("arg2") Button arg2) {}
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button\njavafx.scene.layout.GridPane\nMultiArgCtorObject",
                """
                  <GridPane>
                    <MultiArgCtorObject>
                      <arg1><GridPane/></arg1>
                      <arg2><Button/></arg2>
                    </MultiArgCtorObject>
                  </GridPane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // @NamedArg with vararg element notation (multiple children)
    // -----------------------------------------------------------------------

    /**
     * A vararg {@code @NamedArg} parameter (e.g. {@code Node... nodes}) should accept
     * multiple class-type children in element notation.
     */
    @Test
    void varargNamedArgWithMultipleElementChildrenProducesNoError() {
        getFixture().addClass("""
                import javafx.beans.NamedArg;
                import javafx.scene.Node;
                import javafx.scene.layout.GridPane;
                public class VarArgsCtorClass extends GridPane {
                    public VarArgsCtorClass() {}
                    public VarArgsCtorClass(@NamedArg("nodes") Node... nodes) {}
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane\nVarArgsCtorClass",
                """
                  <GridPane>
                    <VarArgsCtorClass>
                      <nodes>
                        <GridPane/>
                        <GridPane/>
                      </nodes>
                    </VarArgsCtorClass>
                  </GridPane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A non-vararg array {@code @NamedArg} parameter (e.g. {@code Node[] nodes}) must NOT
     * accept multiple class-type children in element notation: the compiler produces
     * {@code CANNOT_ASSIGN_FUNCTION_ARGUMENT_variadic} for this case.
     */
    @Test
    void arrayNamedArgWithMultipleElementChildrenProducesError() {

        getFixture().addClass("""
                import javafx.beans.NamedArg;
                import javafx.scene.Node;
                import javafx.scene.layout.GridPane;
                public class ArrayCtorClass extends GridPane {
                    public ArrayCtorClass(@NamedArg("nodes") Node[] nodes) {}
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane\nArrayCtorClass",
                """
                  <GridPane>
                    <ArrayCtorClass>
                      <error descr="Named argument 'nodes' cannot be assigned from multiple values"><nodes>
                        <GridPane/>
                        <GridPane/>
                      </nodes></error>
                    </ArrayCtorClass>
                  </GridPane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A non-vararg array {@code @NamedArg} parameter (e.g. {@code Node[] nodes}) with a
     * single class-type child that is NOT a {@code MarkupExtension.Supplier} must produce
     * an error: the compiler rejects this with {@code CANNOT_ASSIGN_FUNCTION_ARGUMENT}.
     *
     * <p>Mirrors compiler test
     * {@code Object_Instantiation_With_Array_Constructor_Fails_For_Scalar_Value}.
     */
    @Test
    void arrayNamedArgWithSingleScalarClassChildProducesError() {
        getFixture().addClass("""
                import javafx.beans.NamedArg;
                import javafx.scene.Node;
                import javafx.scene.layout.GridPane;
                public class ArrayCtorClass extends GridPane {
                    public ArrayCtorClass(@NamedArg("nodes") Node[] nodes) {}
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane\nArrayCtorClass",
                """
                  <GridPane>
                    <ArrayCtorClass>
                      <nodes>
                        <error descr="Named argument 'nodes' cannot be assigned from GridPane"><GridPane/></error>
                      </nodes>
                    </ArrayCtorClass>
                  </GridPane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A non-vararg array {@code @NamedArg} parameter (e.g. {@code Node[] nodes}) with a
     * single {@code MarkupExtension.Supplier<Node[]>} child must NOT produce an error:
     * a supplier-extension is a valid way to fill an array constructor argument.
     *
     * <p>Mirrors compiler test {@code Object_Is_Instantiated_With_Array_Constructor}.
     */
    @Test
    void arrayNamedArgWithSingleMarkupExtensionSupplierArrayChildProducesNoError() {
        // Mock the MarkupExtension interfaces (not on the test classpath by default).
        getFixture().addClass("""
                package org.jfxcore.markup;
                public interface MarkupExtension {
                    interface Supplier<T> extends MarkupExtension {}
                }
                """);
        getFixture().addClass("""
                package org.jfxcore.markup;
                public interface MarkupContext {}
                """);
        // The array constructor class under test.
        getFixture().addClass("""
                import javafx.beans.NamedArg;
                import javafx.scene.Node;
                import javafx.scene.layout.GridPane;
                public class ArrayCtorClass extends GridPane {
                    public ArrayCtorClass(@NamedArg("nodes") Node[] nodes) {}
                }
                """);
        // A generic Supplier<T[]> markup extension.
        getFixture().addClass("""
                import javafx.beans.NamedArg;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                public class ArrayConvExt<T> implements MarkupExtension.Supplier<T[]> {
                    @SafeVarargs
                    public ArrayConvExt(@NamedArg("values") T... values) {}
                    @Override public T[] get(MarkupContext context) { return null; }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane\nArrayCtorClass\nArrayConvExt",
                """
                  <GridPane>
                    <ArrayCtorClass>
                      <nodes>
                        <ArrayConvExt fx:typeArguments="javafx.scene.Node">
                          <values><GridPane/></values>
                        </ArrayConvExt>
                      </nodes>
                    </ArrayCtorClass>
                  </GridPane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code RadialGradient} uses a vararg {@code @NamedArg("stops") Stop... stops} constructor
     * parameter.  Multiple {@code <Stop>} children inside {@code <stops>} must produce no error.
     */
    @Test
    void radialGradientWithMultipleVarargStopsProducesNoError() {
        getFixture().configureByText("TestView.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.paint.*?>
                <?import javafx.scene.shape.*?>
                <Rectangle xmlns="http://javafx.com/javafx"
                           xmlns:fx="http://jfxcore.org/fxml/2.0">
                  <fill>
                    <RadialGradient focusAngle="0" focusDistance="0"
                                    centerX="0.5" centerY="0.5"
                                    radius="0.5" proportional="true"
                                    cycleMethod="NO_CYCLE">
                      <stops>
                        <Stop offset="0">
                          <color><Color fx:value="DODGERBLUE"/></color>
                        </Stop>
                        <Stop offset="1">
                          <color><Color fx:value="NAVY"/></color>
                        </Stop>
                      </stops>
                    </RadialGradient>
                  </fill>
                </Rectangle>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // @NamedArg with default value
    // -----------------------------------------------------------------------

    @Test
    void namedArgWithDefaultValueCanBeOmitted() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane\njavafx.geometry.Insets",
                """
                  <GridPane padding="10"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Extra non-NamedArg property attributes alongside NamedArg constructor
    // -----------------------------------------------------------------------

    /**
     * A tag may have attributes that are regular property setters (not {@code @NamedArg}
     * constructor params) alongside the constructor args. Those extra attributes must not
     * cause a false "No suitable constructor found" error: the constructor-matching logic
     * must only consider attributes whose names appear as {@code @NamedArg} params in at
     * least one constructor.
     */
    @Test
    void extraPropertyAttributesAlongsideNamedArgConstructorProducesNoError() {
        getFixture().addClass("""
                package test;
                import javafx.beans.NamedArg;
                import javafx.beans.property.StringProperty;
                import javafx.beans.property.SimpleStringProperty;
                public class NamedArgWithExtras {
                    /** Required constructor arg. */
                    public NamedArgWithExtras(@NamedArg("required") String required) {}
                    /** Extra property settable after construction. */
                    private final StringProperty extra = new SimpleStringProperty();
                    public StringProperty extraProperty() { return extra; }
                    public String getExtra() { return extra.get(); }
                    public void setExtra(String v) { extra.set(v); }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.NamedArgWithExtras",
                // 'extra' is a regular property setter: must not disqualify the constructor
                """
                  <NamedArgWithExtras required="hello" extra="world"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Deeply nested @NamedArg instantiation: Background + Border
    // -----------------------------------------------------------------------

    /**
     * A {@code Button} with a solid-color rounded background and a dashed rounded {@code Border},
     * expressed entirely in fxml2 element notation via nested {@code @NamedArg} constructors.
     *
     * <p>Object graph:
     * <ul>
     *   <li>{@code Background(fills: BackgroundFill)}: {@code BackgroundFill(fill, radii, insets)}
     *       where {@code fill} is a {@code Color} via {@code fx:value}, {@code radii} is coerced
     *       from a numeric string via {@code CornerRadii(radius)}, and {@code insets} is coerced
     *       from a string.</li>
     *   <li>{@code Border(strokes: BorderStroke)}: {@code BorderStroke(stroke, style, radii, widths)}
     *       where {@code stroke} is a {@code Color} via {@code fx:value}, {@code style} is the
     *       {@code BorderStrokeStyle.DASHED} static constant (inline {@code BorderStrokeStyle}
     *       construction is not possible because its {@code dashArray: List<Double>} parameter
     *       is an unsupported collection-type {@code @NamedArg} in the FXML compiler),
     *       {@code radii} coerced from a string, and {@code widths} via
     *       {@code BorderWidths(width)}.</li>
     * </ul>
     *
     * <p>The test verifies that the plugin produces no false-positive errors for deeply
     * nested {@code @NamedArg} construction, {@code fx:value} instantiation, and static-field
     * coercion.
     */
    @Test
    void buttonWithCustomBackgroundAndDashedRoundedBorderProducesNoError() {
        getFixture().addFileToProject("test/TestViewBase.java",
                """
                package test;
                import javafx.scene.control.Button;
                public abstract class TestViewBase extends Button {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().addFileToProject("test/TestView.java",
                """
                package test;
                public class TestView extends TestViewBase {}
                """);
        getFixture().configureByText("TestView.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.Button?>
                <?import javafx.scene.layout.Background?>
                <?import javafx.scene.layout.BackgroundFill?>
                <?import javafx.scene.layout.Border?>
                <?import javafx.scene.layout.BorderStroke?>
                <?import javafx.scene.layout.BorderStrokeStyle?>
                <?import javafx.scene.layout.BorderWidths?>
                <?import javafx.scene.paint.Color?>
                <Button xmlns="http://javafx.com/javafx"
                        xmlns:fx="http://jfxcore.org/fxml/2.0"
                        fx:subclass="test.TestView"
                        text="Click me">
                  <background>
                    <Background>
                      <fills>
                        <BackgroundFill radii="10" insets="0">
                          <fill>
                            <Color fx:value="DODGERBLUE"/>
                          </fill>
                        </BackgroundFill>
                      </fills>
                    </Background>
                  </background>
                  <border>
                    <Border>
                      <strokes>
                        <BorderStroke radii="10">
                          <stroke>
                            <Color fx:value="NAVY"/>
                          </stroke>
                          <style>
                            <BorderStrokeStyle fx:constant="DASHED"/>
                          </style>
                          <widths>
                            <BorderWidths width="2"/>
                          </widths>
                        </BorderStroke>
                      </strokes>
                    </Border>
                  </border>
                </Button>
                """);
        getFixture().checkHighlighting(false, false, false);
    }
}
