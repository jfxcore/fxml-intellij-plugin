package org.jfxcore.fxml;

import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tests that correspond to the fxml2 compiler's DefineBlockTest and
 * CollectionsTest
 * ({@code src/compilerTest/java/org/jfxcore/compiler/DefineBlockTest.java},
 * {@code src/compilerTest/java/org/jfxcore/compiler/CollectionsTest.java}).
 *
 * <p>Covers the {@code <fx:define>} block, collections (ArrayList, HashSet,
 * HashMap), and incompatible-type errors when adding items.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2DefineAndCollectionsTest extends Fxml2TestBase {

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2AttributeValueInspection());
    }

    // -----------------------------------------------------------------------
    // fx:define: positive cases
    // -----------------------------------------------------------------------

    /**
     * Compiler: Define_Single_Primitive_Value: String inside fx:define.
     * The plugin currently cannot tell that {@code <String>} inside {@code <fx:define>}
     * is being used to instantiate a primitive/wrapper type (not as a JavaFX Node), so
     * it reports "Cannot resolve symbol 'String'" even when the import is present.
     */
    @Test
    void fxDefineSingleStringProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "java.lang.String\njavafx.scene.layout.GridPane",
                """
                  <GridPane>
                    <fx:define><String fx:id="str0">Hello!</String></fx:define>
                  </GridPane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** Compiler: Reference_Value_In_DefineBlock: $str reference to fx:define entry */
    @Test
    void fxDefineReferenceViaBindingProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="$str">
                    <fx:define><String fx:id="str">Hello!</String></fx:define>
                  </Label>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Collections: positive cases
    // -----------------------------------------------------------------------

    /**
     * Compiler: Objects_Are_Added_To_List: ArrayList with fx:typeArguments.
     * The plugin currently reports "Cannot resolve symbol 'ArrayList'" and
     * "Cannot resolve symbol 'String'" for non-JavaFX node types used as
     * object tags inside {@code <fx:define>}.
     */
    @Test
    void arrayListWithTypeArgumentsProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane\njava.util.ArrayList\njava.lang.String",
                """
                  <GridPane>
                    <fx:define>
                      <ArrayList fx:typeArguments="Object" fx:id="list">
                        <String fx:id="str0">foo</String>
                      </ArrayList>
                    </fx:define>
                  </GridPane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** Compiler: styleClass comma-separated collection assignment */
    @Test
    void styleClassCollectionAssignmentProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane styleClass="style1, style2, style3"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Collections: incompatible type errors
    // -----------------------------------------------------------------------

    /**
     * Compiler: Objects_Are_Added_To_Incompatible_List_ItemType_Fails.
     * Adding a String to an ArrayList&lt;Integer&gt;.
     */
    @Test
    void addingIncompatibleItemToTypedListProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane\njava.util.ArrayList",
                """
                  <GridPane>
                    <fx:define>
                      <ArrayList fx:typeArguments="Integer" fx:id="list">
                        <error descr="String cannot be added to ArrayList, required Integer"><String>foo</String></error>
                      </ArrayList>
                    </fx:define>
                  </GridPane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Compiler: Comma_Separated_String_Initializer_For_DoubleList_Fails.
     * Comma-separated string values cannot be coerced to Double list items.
     */
    @Test
    void commaSeparatedStringsInTypedDoubleListProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane\njava.util.ArrayList",
                """
                  <GridPane>
                    <fx:define>
                      <ArrayList fx:id="list" fx:typeArguments="java.lang.Double">
                        <error descr="String cannot be added to ArrayList, required Double">style1, style2, style3</error>
                      </ArrayList>
                    </fx:define>
                  </GridPane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // fx:define: static property attribute reference
    // -----------------------------------------------------------------------

    /**
     * An object defined in {@code <fx:define>} with {@code fx:id} can be
     * referenced in a static (attached) property attribute, e.g.
     * {@code BorderPane.margin="$margins1"} where {@code margins1} is an
     * {@code Insets} defined in the define block.
     */
    @Test
    void fxDefineObjectReferencedInStaticPropertyAttributeProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.StackPane\njavafx.scene.layout.BorderPane\njavafx.geometry.Insets\njavafx.scene.control.Button",
                """
                  <StackPane>
                    <fx:define>
                      <Insets fx:id="margins1" topRightBottomLeft="4"/>
                    </fx:define>
                    <Button BorderPane.margin="$margins1"/>
                  </StackPane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Multiple fx:id definitions: scoping
    // -----------------------------------------------------------------------

    /**
     * A generic non-{@code Collection} class that carries {@code fx:typeArguments}
     * must NOT have its children checked against the type argument.  Children go to the
     * {@code @DefaultProperty}: their type is unrelated to the class's type parameter.
     *
     * <p>Scenario: {@code GenericWrapper<TItem>} is a {@code Callback}-like class with
     * {@code @DefaultProperty("template")} whose {@code template} property accepts a
     * {@code TemplateItem<TItem>}.  Adding a {@code TemplateItem<Row>} child to
     * {@code GenericWrapper<Row>} is valid; the plugin must not report
     * "TemplateItem cannot be added to GenericWrapper, required Row".
     */
    @Test
    void genericNonCollectionWithTypeArgumentsAllowsDefaultPropertyChild() {
        getFixture().addClass("""
                package test;
                import javafx.beans.DefaultProperty;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                import javafx.util.Callback;
                @DefaultProperty("template")
                public class GenericWrapper<TItem> implements Callback<TItem, Object> {
                    private final ObjectProperty<TemplateItem<TItem>> template =
                        new SimpleObjectProperty<>(this, "template");
                    public ObjectProperty<TemplateItem<TItem>> templateProperty() { return template; }
                    @Override public Object call(TItem param) { return null; }
                }
                """);
        getFixture().addClass("""
                package test;
                public class TemplateItem<TItem> {}
                """);
        getFixture().addClass("""
                package test;
                public class Row {}
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane\ntest.GenericWrapper\ntest.TemplateItem",
                """
                  <GridPane>
                    <fx:define>
                      <GenericWrapper fx:typeArguments="test.Row" fx:id="w">
                        <TemplateItem fx:typeArguments="test.Row"/>
                      </GenericWrapper>
                    </fx:define>
                  </GridPane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }
}
