package org.jfxcore.fxml;

import com.intellij.codeInsight.intention.IntentionAction;
import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.jfxcore.fxml.annotator.Fxml2FxAttributeInspection;
import org.jfxcore.fxml.annotator.Fxml2UnusedImportsInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that correspond to the fxml2 compiler's IntrinsicsTest
 * ({@code src/compilerTest/java/org/jfxcore/compiler/IntrinsicsTest.java}).
 *
 * <p>Covers the built-in {@code fx:} attributes (id, class, constant, null,
 * type, typeArguments, classParameters, Evaluate/Observe/Synchronize compact
 * syntax, etc.).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2IntrinsicsTest extends Fxml2TestBase {

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2AttributeValueInspection(), new Fxml2FxAttributeInspection());
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
        // Minimal code-behind: intentionally has NO 'foo' property so binding errors fire
        getFixture().addClass("""
                package test;
                public class TestView extends TestViewBase {}
                """);
    }

    // -----------------------------------------------------------------------
    // Unknown / unexpected intrinsics: compiler: IntrinsicsTest.Unknown_Intrinsic,
    //   Root_Intrinsic_Cannot_Be_Used_On_Child_Element
    // -----------------------------------------------------------------------

    /** Compiler: Unknown_Intrinsic: fx:foo is not a known intrinsic */
    @Test
    void unknownFxAttributeProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane <error descr="Unknown intrinsic: foo">fx:foo="foo"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code fx:Stylesheet} was removed from the compiler.
     * Using it as an {@code fx:} attribute must produce "Unknown intrinsic: Stylesheet".
     */
    @Test
    void fxStylesheetAttributeProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane <error descr="Unknown intrinsic: Stylesheet">fx:Stylesheet="something"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code fx:Stylesheet} was removed from the compiler.
     * Using it as an element tag must produce "Unknown intrinsic: Stylesheet".
     */
    @Test
    void fxStylesheetElementProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane>
                    <<error descr="Unknown intrinsic: Stylesheet">fx:Stylesheet</error>/>
                  </GridPane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** Compiler: Root_Intrinsic_Cannot_Be_Used_On_Child_Element: fx:subclass on non-root */
    @Test
    void fxClassOnNonRootElementProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane <error descr="Unexpected intrinsic: subclass">fx:subclass="java.lang.String"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // fx:id: compiler: IdIntrinsicTest
    // -----------------------------------------------------------------------

    /** Compiler: Empty_FxId_Is_Invalid */
    @Test
    void emptyFxIdProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane <error descr="fx:id cannot be empty">fx:id="  "</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** Compiler: Duplicate_FxId_Is_Invalid */
    @Test
    void duplicateFxIdProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane fx:id="pane0">
                    <GridPane fx:id=<error descr="Duplicate ID: pane0">"pane0"</error>/>
                  </GridPane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** Compiler: FxId_Non_JavaIdentifier_Is_Invalid */
    @Test
    void fxIdWithSpaceProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane fx:id=<error descr="'foo bar' is not a valid ID">"foo bar"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** A valid fx:id identifier must not produce any error (positive case). */
    @Test
    void validFxIdProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane fx:id="pane0"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // fx:True and fx:False: compiler: BooleanIntrinsicsTest
    // -----------------------------------------------------------------------

    /** Compiler: TrueAndFalse_Can_Be_Assigned_To_Property_With_PrimitiveType: visible is boolean */
    @Test
    void fxTrueOnBooleanPrimitivePropertyProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button visible="{fx:True}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** Compiler: TrueAndFalse_Can_Be_Assigned_To_Property_With_PrimitiveType: disable is boolean */
    @Test
    void fxFalseOnBooleanPrimitivePropertyProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button disable="{fx:False}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** Compiler: TrueAndFalse_Can_Be_Assigned_To_Property_With_ReferenceType: Boolean boxed */
    @Test
    void fxTrueOnBooleanBoxedPropertyProducesNoError() {
        getFixture().addFileToProject("test/BoolPane.java", """
                package test;
                import javafx.scene.layout.Pane;
                public class BoolPane extends Pane {
                    private Boolean enabled;
                    public Boolean getEnabled() { return enabled; }
                    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.BoolPane",
                """
                  <BoolPane enabled="{fx:True}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** Compiler: TrueAndFalse_Can_Be_Assigned_To_Property_With_ReferenceType: Boolean boxed */
    @Test
    void fxFalseOnBooleanBoxedPropertyProducesNoError() {
        getFixture().addFileToProject("test/BoolPane2.java", """
                package test;
                import javafx.scene.layout.Pane;
                public class BoolPane2 extends Pane {
                    private Boolean enabled;
                    public Boolean getEnabled() { return enabled; }
                    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.BoolPane2",
                """
                  <BoolPane2 enabled="{fx:False}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** fx:True on a String (reference, non-boolean) property must produce an error. */
    @Test
    void fxTrueOnStringPropertyProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text=<error descr="fx:True cannot be assigned to a non-boolean property">"{fx:True}"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** fx:False on a String (reference, non-boolean) property must produce an error. */
    @Test
    void fxFalseOnStringPropertyProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text=<error descr="fx:False cannot be assigned to a non-boolean property">"{fx:False}"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** fx:True on a non-boolean primitive (double) property must produce an error. */
    @Test
    void fxTrueOnDoublePrimitivePropertyProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label prefWidth=<error descr="fx:True cannot be assigned to a non-boolean property">"{fx:True}"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // fx:Null: compiler: NullIntrinsicTest
    // -----------------------------------------------------------------------

    /** Compiler: Null_Can_Be_Assigned_To_Property_With_ReferenceType */
    @Test
    void fxNullOnStringPropertyProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Null}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** Compiler: Null_Cannot_Be_Assigned_To_Property_With_PrimitiveType */
    @Test
    void fxNullOnPrimitivePropertyProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label prefWidth=<error descr="fx:Null cannot be assigned to a primitive property">"{fx:Null}"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Binding compact-syntax intrinsics: compiler: BindingIntrinsicsTest
    // -----------------------------------------------------------------------

    /**
     * Compiler: FxEvaluate_Compact_Syntax_Has_Correct_CodeHighlight.
     * {@code $foo.bar} where {@code foo} does not exist -> MEMBER_NOT_FOUND on first segment.
     */
    @Test
    void fxEvaluateCompactSyntaxFirstSegmentHighlighted() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button prefHeight="$<error descr="'foo' in test.TestView cannot be resolved">foo</error>.bar"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Compiler: FxBind_Compact_Syntax_Has_Correct_CodeHighlight.
     * {@code ${foo.bar}} where {@code foo} does not exist.
     */
    @Test
    void fxBindCompactSyntaxFirstSegmentHighlighted() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button prefHeight="${<error descr="'foo' in test.TestView cannot be resolved">foo</error>.bar}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Compiler: FxBindBidirectional_Compact_Syntax_Has_Correct_CodeHighlight.
     * {@code #{foo.bar}} where {@code foo} does not exist.
     */
    @Test
    void fxBindBidirectionalCompactSyntaxFirstSegmentHighlighted() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button prefHeight="#{<error descr="'foo' in test.TestView cannot be resolved">foo</error>.bar}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // fx:typeArguments unresolved class: the class name must be highlighted as an error
    // -----------------------------------------------------------------------

    /**
     * When {@code fx:typeArguments} contains a FQN whose final class segment does not exist
     * (e.g. {@code sample.app.Foo}), the class segment must be highlighted as an unresolved
     * reference error.
     *
     * <p>Only the unresolvable final class segment must be highlighted; all other
     * segments of the fully-qualified name are soft references and must not produce errors.
     */
    @Test
    void fxTypeArgumentsUnresolvableClassProducesError() {
        getFixture().addFileToProject("sample/app/MyList.java",
                """
                package sample.app;
                import javafx.scene.control.ListView;
                public class MyList<T> extends ListView<T> {}
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "sample.app.MyList",
                """
                  <MyList fx:typeArguments="sample.app.<error descr="Cannot resolve symbol 'Foo'">Foo</error>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * When {@code fx:typeArguments} contains a valid FQN, no error should appear.
     */
    @Test
    void fxTypeArgumentsResolvableClassProducesNoError() {
        getFixture().addFileToProject("sample/app/MyList2.java",
                """
                package sample.app;
                import javafx.scene.control.ListView;
                public class MyList2<T> extends ListView<T> {}
                """);
        getFixture().addFileToProject("sample/app/Item.java",
                """
                package sample.app;
                public class Item {}
                """);
        getFixture().configureByText("TestView2.fxml", fxml2(
                "sample.app.MyList2",
                """
                  <MyList2 fx:typeArguments="sample.app.Item"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Three comma-separated type arguments: all resolvable: must produce no error.
     */
    @Test
    void fxTypeArgumentsThreeValidCommaSeparatedProducesNoError() {
        getFixture().addFileToProject("sample/app/MyMap.java",
                """
                package sample.app;
                public class MyMap<K, V, M> {}
                """);
        getFixture().addFileToProject("sample/app/KeyType.java",
                """
                package sample.app;
                public class KeyType {}
                """);
        getFixture().addFileToProject("sample/app/ValueType.java",
                """
                package sample.app;
                public class ValueType {}
                """);
        getFixture().addFileToProject("sample/app/MetaType.java",
                """
                package sample.app;
                public class MetaType {}
                """);
        getFixture().configureByText("TestView3.fxml", fxml2(
                "sample.app.MyMap",
                """
                  <MyMap fx:typeArguments="sample.app.KeyType, sample.app.ValueType, sample.app.MetaType"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Three comma-separated type arguments where the second one ({@code sample.app.NoSuch})
     * does not exist: only that segment must be highlighted as an unresolved reference;
     * the first and third valid types must not be highlighted.
     */
    @Test
    void fxTypeArgumentsThreeCommaSeparatedMiddleUnresolvableProducesError() {
        getFixture().addFileToProject("sample/app/MyMap2.java",
                """
                package sample.app;
                public class MyMap2<K, V, M> {}
                """);
        getFixture().addFileToProject("sample/app/KeyType2.java",
                """
                package sample.app;
                public class KeyType2 {}
                """);
        getFixture().addFileToProject("sample/app/MetaType2.java",
                """
                package sample.app;
                public class MetaType2 {}
                """);
        getFixture().configureByText("TestView4.fxml", fxml2(
                "sample.app.MyMap2",
                """
                  <MyMap2 fx:typeArguments="sample.app.KeyType2, sample.app.<error descr="Cannot resolve symbol 'NoSuch'">NoSuch</error>, sample.app.MetaType2"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * When all type arguments in a comma-separated {@code fx:typeArguments} value are
     * unresolvable, every unresolvable segment must be highlighted as an error.
     */
    @Test
    void fxTypeArgumentsAllCommaSeparatedUnresolvableProducesErrorForEach() {
        getFixture().addFileToProject("sample/app/MyMap3.java",
                """
                package sample.app;
                public class MyMap3<K, V> {}
                """);
        getFixture().configureByText("TestView5.fxml", fxml2(
                "sample.app.MyMap3",
                """
                  <MyMap3 fx:typeArguments="<error descr="Cannot resolve symbol 'foo'">foo</error>, <error descr="Cannot resolve symbol 'bar'">bar</error>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // fx:typeArguments count validation: compiler: NUM_TYPE_ARGUMENTS_MISMATCH / CANNOT_PARAMETERIZE_TYPE
    // -----------------------------------------------------------------------

    /**
     * Compiler: NUM_TYPE_ARGUMENTS_MISMATCH: fewer type arguments than type parameters requires an error.
     * A class with two type parameters must not accept only one argument.
     */
    @Test
    void fxTypeArgumentsTooFewProducesError() {
        getFixture().addFileToProject("sample/app/TwoParamType.java",
                """
                package sample.app;
                public class TwoParamType<K, V> {}
                """);
        getFixture().configureByText("TestViewTwoParamFew.fxml", fxml2(
                "sample.app.TwoParamType",
                """
                  <TwoParamType fx:typeArguments=<error descr="sample.app.TwoParamType: required 2 type argument(s), but 1 were provided">"java.lang.String"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Compiler: NUM_TYPE_ARGUMENTS_MISMATCH: more type arguments than type parameters requires an error.
     * A class with two type parameters must not accept three arguments.
     */
    @Test
    void fxTypeArgumentsTooManyProducesError() {
        getFixture().addFileToProject("sample/app/TwoParamType2.java",
                """
                package sample.app;
                public class TwoParamType2<K, V> {}
                """);
        getFixture().configureByText("TestViewTwoParamMany.fxml", fxml2(
                "sample.app.TwoParamType2",
                """
                  <TwoParamType2 fx:typeArguments=<error descr="sample.app.TwoParamType2: required 2 type argument(s), but 3 were provided">"java.lang.String, java.lang.Integer, java.lang.Double"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * When the number of type arguments exactly matches the class's type parameter count,
     * no count-mismatch error must be produced.
     */
    @Test
    void fxTypeArgumentsCorrectCountProducesNoError() {
        getFixture().addFileToProject("sample/app/TwoParamType3.java",
                """
                package sample.app;
                public class TwoParamType3<K, V> {}
                """);
        getFixture().configureByText("TestViewTwoParamOk.fxml", fxml2(
                "sample.app.TwoParamType3",
                """
                  <TwoParamType3 fx:typeArguments="java.lang.String, java.lang.Integer"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Compiler: CANNOT_PARAMETERIZE_TYPE: a non-generic class cannot accept type arguments.
     */
    @Test
    void fxTypeArgumentsOnNonGenericClassProducesError() {
        getFixture().configureByText("TestViewNonGeneric.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane fx:typeArguments=<error descr="javafx.scene.layout.GridPane cannot be parameterized">"java.lang.String"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // fx:typeArguments bound validation: compiler: TYPE_ARGUMENT_OUT_OF_BOUND
    // -----------------------------------------------------------------------

    /**
     * Compiler: TYPE_ARGUMENT_OUT_OF_BOUND: a type argument that does not satisfy the
     * type parameter's class bound must produce an error, highlighting that specific token.
     * {@code String} does not extend {@code Number}.
     */
    @Test
    void fxTypeArgumentsViolatesClassBoundProducesError() {
        getFixture().addFileToProject("sample/app/BndNum.java",
                """
                package sample.app;
                public class BndNum<T extends java.lang.Number> {}
                """);
        getFixture().configureByText("TestViewBndNum.fxml", fxml2(
                "sample.app.BndNum",
                """
                  <BndNum fx:typeArguments="<error descr="Type argument java.lang.String is not within its bound, should extend java.lang.Number">java.lang.String</error>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A type argument that is a direct subtype of the class bound must produce no error.
     * {@code Integer} extends {@code Number}.
     */
    @Test
    void fxTypeArgumentsWithinClassBoundProducesNoError() {
        getFixture().addFileToProject("sample/app/BndNum2.java",
                """
                package sample.app;
                public class BndNum2<T extends java.lang.Number> {}
                """);
        getFixture().configureByText("TestViewBndNum2.fxml", fxml2(
                "sample.app.BndNum2",
                """
                  <BndNum2 fx:typeArguments="java.lang.Integer"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A type argument equal to the bound itself must produce no error.
     * {@code Number} satisfies {@code T extends Number}.
     */
    @Test
    void fxTypeArgumentsExactBoundTypeProducesNoError() {
        getFixture().addFileToProject("sample/app/BndNum3.java",
                """
                package sample.app;
                public class BndNum3<T extends java.lang.Number> {}
                """);
        getFixture().configureByText("TestViewBndNum3.fxml", fxml2(
                "sample.app.BndNum3",
                """
                  <BndNum3 fx:typeArguments="java.lang.Number"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Compiler: TYPE_ARGUMENT_OUT_OF_BOUND: a type argument violating an interface bound
     * must produce an error. {@code Object} does not implement {@code Comparable}.
     */
    @Test
    void fxTypeArgumentsViolatesInterfaceBoundProducesError() {
        getFixture().addFileToProject("sample/app/BndComp.java",
                """
                package sample.app;
                public class BndComp<T extends java.lang.Comparable> {}
                """);
        getFixture().configureByText("TestViewBndComp.fxml", fxml2(
                "sample.app.BndComp",
                """
                  <BndComp fx:typeArguments="<error descr="Type argument java.lang.Object is not within its bound, should extend java.lang.Comparable">java.lang.Object</error>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A type argument that implements an interface bound must produce no error.
     * {@code String} implements {@code Comparable}.
     */
    @Test
    void fxTypeArgumentsSatisfiesInterfaceBoundProducesNoError() {
        getFixture().addFileToProject("sample/app/BndComp2.java",
                """
                package sample.app;
                public class BndComp2<T extends java.lang.Comparable> {}
                """);
        getFixture().configureByText("TestViewBndComp2.fxml", fxml2(
                "sample.app.BndComp2",
                """
                  <BndComp2 fx:typeArguments="java.lang.String"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * When a type parameter has both a class bound and an interface bound, only the
     * class bound is checked (mirrors the compiler's class-bound-priority rule).
     * {@code String} fails the {@code Number} class bound even though it satisfies
     * {@code Comparable}.
     */
    @Test
    void fxTypeArgumentsClassBoundTakesPriorityOverInterfaceBound() {
        getFixture().addFileToProject("sample/app/BndDual.java",
                """
                package sample.app;
                @SuppressWarnings("rawtypes")
                public class BndDual<T extends java.lang.Number & java.lang.Comparable> {}
                """);
        getFixture().configureByText("TestViewBndDual.fxml", fxml2(
                "sample.app.BndDual",
                """
                  <BndDual fx:typeArguments="<error descr="Type argument java.lang.String is not within its bound, should extend java.lang.Number">java.lang.String</error>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * With two type parameters where the second has a class bound, only the second
     * type argument should produce an error when it violates its bound. The first
     * argument (for the unbounded parameter) must not be flagged.
     * The error token must be highlighted precisely within the comma-separated value.
     */
    @Test
    void fxTypeArgumentsSecondArgViolatesBoundProducesErrorOnSecond() {
        getFixture().addFileToProject("sample/app/BndPair.java",
                """
                package sample.app;
                public class BndPair<K, V extends java.lang.Number> {}
                """);
        getFixture().configureByText("TestViewBndPair.fxml", fxml2(
                "sample.app.BndPair",
                """
                  <BndPair fx:typeArguments="java.lang.String, <error descr="Type argument java.lang.Object is not within its bound, should extend java.lang.Number">java.lang.Object</error>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * When a type parameter's bound references another type parameter (e.g., {@code V extends K}),
     * the substitutor must expand the bound using the provided argument for {@code K}.
     * Here K=Number, so V's effective bound is Number; {@code String} violates it.
     */
    @Test
    void fxTypeArgumentsTypeParamReferencingBoundViolatedProducesError() {
        getFixture().addFileToProject("sample/app/BndRef.java",
                """
                package sample.app;
                public class BndRef<K, V extends K> {}
                """);
        getFixture().configureByText("TestViewBndRef.fxml", fxml2(
                "sample.app.BndRef",
                """
                  <BndRef fx:typeArguments="java.lang.Number, <error descr="Type argument java.lang.String is not within its bound, should extend java.lang.Number">java.lang.String</error>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * When a bound references another type parameter and the provided argument for that
     * sibling parameter satisfies the transitive bound, no error must be produced.
     * K=Number, V=Integer: Integer extends Number, so V's bound (K=Number) is satisfied.
     */
    @Test
    void fxTypeArgumentsTypeParamReferencingBoundSatisfiedProducesNoError() {
        getFixture().addFileToProject("sample/app/BndRef2.java",
                """
                package sample.app;
                public class BndRef2<K, V extends K> {}
                """);
        getFixture().configureByText("TestViewBndRef2.fxml", fxml2(
                "sample.app.BndRef2",
                """
                  <BndRef2 fx:typeArguments="java.lang.Number, java.lang.Integer"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * An unresolvable type argument must not additionally produce a spurious bound error,
     * the reference contributor already reports the unresolved symbol. The bound check
     * must be silently skipped for that argument.
     */
    @Test
    void fxTypeArgumentsUnresolvableArgDoesNotProduceSpuriousBoundError() {
        getFixture().addFileToProject("sample/app/BndNum4.java",
                """
                package sample.app;
                public class BndNum4<T extends java.lang.Number> {}
                """);
        getFixture().configureByText("TestViewBndNum4.fxml", fxml2(
                "sample.app.BndNum4",
                // The reference contributor marks the last segment "NoSuch" as unresolved;
                // no additional bound error must appear on the same token.
                """
                  <BndNum4 fx:typeArguments="sample.app.<error descr="Cannot resolve symbol 'NoSuch'">NoSuch</error>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // TypeArguments + constant conflict: compiler: TypeArguments_And_Constant_Cannot_Be_Used_At_Same_Time
    // -----------------------------------------------------------------------

    /** Compiler: TypeArguments_And_Constant_Cannot_Be_Used_At_Same_Time */
    @Test
    void fxTypeArgumentsAndFxConstantConflictProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane fx:constant="FOO" <error descr="fx:typeArguments and fx:constant cannot be used at the same time">fx:typeArguments="<error descr="Cannot resolve symbol 'bar'">bar</error>"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * An import used only in {@code fx:typeArguments} must not be reported as unused.
     * The import optimizer must include {@code fx:} attributes when collecting used simple
     * names, so that classes referenced only in this position are not flagged as unused.
     */
    @Test
    void importUsedOnlyInFxTypeArgumentsIsNotFlaggedAsUnused() {
        getFixture().addFileToProject("sample/app/Row.java",
                """
                package sample.app;
                public class Row {}
                """);
        getFixture().addFileToProject("sample/app/MyList.java",
                """
                package sample.app;
                import javafx.scene.control.ListView;
                public class MyList<T> extends ListView<T> {}
                """);
        getFixture().enableInspections(new Fxml2UnusedImportsInspection());
        // Row is imported and referenced only via fx:typeArguments: must NOT be "Unused import".
        // checkHighlighting(checkWarnings, checkInfos, checkWeakWarnings)
        getFixture().configureByText("TestView.fxml", fxml2(
                "sample.app.MyList\nsample.app.Row",
                """
                  <MyList fx:typeArguments="Row"/>
                """
        ));
        getFixture().checkHighlighting(true, false, false);
    }

    /**
     * When a simple name in {@code fx:typeArguments} is unresolved because its import is
     * missing, the "Add import for '...'" intention action must be offered.
     */
    @Test
    void fxTypeArgumentsUnresolvedSimpleNameOffersAddImportFix() {
        getFixture().addFileToProject("sample/app/RowItem.java",
                """
                package sample.app;
                public class RowItem {}
                """);
        getFixture().addFileToProject("sample/app/MyList3.java",
                """
                package sample.app;
                import javafx.scene.control.ListView;
                public class MyList3<T> extends ListView<T> {}
                """);
        // RowItem is NOT imported: "Cannot resolve symbol 'RowItem'" is expected.
        getFixture().configureByText("TestView.fxml", fxml2(
                "sample.app.MyList3",
                // Caret on the unresolved name so getAllQuickFixes() picks up its fix.
                """
                  <MyList3 fx:typeArguments="Row<caret>Item"/>
                """
        ));
        getFixture().doHighlighting();
        var fixes = getFixture().getAllQuickFixes();
        assertTrue(fixes.stream().anyMatch(f -> f.getText().contains("Add import") && f.getText().contains("RowItem")),
                "Expected 'Add import for RowItem' quick fix, but got: " + fixes.stream().map(IntentionAction::getText).toList());

        // Apply the fix and verify the import was added.
        getFixture().launchAction(fixes.stream()
                .filter(f -> f.getText().contains("Add import") && f.getText().contains("RowItem"))
                .findFirst()
                .orElseThrow());
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("<?import sample.app.RowItem?>"),
                "Expected import to be added, but got: " + result);
    }

    /**
     * When a simple name in {@code fx:typeArguments} refers to an abstract class whose import
     * is missing, the "Add import for '...'" intention must be offered. Abstract classes are
     * valid type arguments (they do not need to be directly instantiable).
     */
    @Test
    void fxTypeArgumentsAbstractClassUnresolvedOffersAddImportFix() {
        getFixture().addFileToProject("sample/app/AbstractItem.java",
                """
                package sample.app;
                public abstract class AbstractItem {}
                """);
        getFixture().addFileToProject("sample/app/MyListA.java",
                """
                package sample.app;
                import javafx.scene.control.ListView;
                public class MyListA<T> extends ListView<T> {}
                """);
        // AbstractItem is NOT imported: "Cannot resolve symbol" is expected.
        getFixture().configureByText("TestViewAbstract.fxml", fxml2(
                "sample.app.MyListA",
                """
                  <MyListA fx:typeArguments="Abstract<caret>Item"/>
                """
        ));
        getFixture().doHighlighting();
        var fixes = getFixture().getAllQuickFixes();
        assertTrue(
                fixes.stream().anyMatch(f -> f.getText().contains("Add import") && f.getText().contains("AbstractItem")),
                "Expected 'Add import for AbstractItem' quick fix, but got: "
                        + fixes.stream().map(IntentionAction::getText).toList());

        // Apply the fix and verify the import was inserted.
        getFixture().launchAction(fixes.stream()
                .filter(f -> f.getText().contains("Add import") && f.getText().contains("AbstractItem"))
                .findFirst()
                .orElseThrow());
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("<?import sample.app.AbstractItem?>"),
                "Expected import to be added, but got: " + result);
    }

    // -----------------------------------------------------------------------
    // fx:id and Node.id
    // -----------------------------------------------------------------------

    /**
     * {@code fx:id} also sets {@code Node.id} on the element.
     * Setting {@code fx:id="foo"} implicitly sets {@code id="foo"} at runtime.
     * The plugin must not flag an explicit {@code id} attribute alongside
     * {@code fx:id} as a conflict or error.
     */
    @Test
    void fxIdAlongsideExplicitNodeIdProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button fx:id="myButton" id="myButton"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // Unused import detection
    // -----------------------------------------------------------------------

    /**
     * A truly unused {@code <?import?>} must be reported as a warning.
     *
     * <p>The inspection should produce a "Unused import" warning for any import whose
     * simple name does not appear as a tag name or dotted attribute prefix in the document.
     */
    @Test
    void unusedImportProducesWarning() {
        getFixture().addFileToProject("sample/app/UnusedClass.java",
                """
                package sample.app;
                public class UnusedClass {}
                """);
        getFixture().enableInspections(new Fxml2UnusedImportsInspection());
        // UnusedClass is imported but never referenced: should be "Unused import".
        // checkHighlighting(checkWarnings, checkInfos, checkWeakWarnings)
        getFixture().configureByText("TestView.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <warning descr="Unused import"><?import sample.app.UnusedClass?></warning>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.TestView">
                </BorderPane>
                """);
        getFixture().checkHighlighting(true, false, false);
    }

    /**
     * An import that IS used as a tag name must NOT be reported as unused.
     */
    @Test
    void importUsedAsTagNameIsNotFlaggedAsUnused() {
        getFixture().addFileToProject("sample/app/UsedClass.java",
                """
                package sample.app;
                import javafx.scene.layout.Pane;
                public class UsedClass extends Pane {}
                """);
        getFixture().enableInspections(new Fxml2UnusedImportsInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                "sample.app.UsedClass",
                """
                  <UsedClass/>
                """
        ));
        getFixture().checkHighlighting(true, false, false);
    }

    /**
     * An import used as a plain class-name value for a {@code Class<T>}-typed property
     * must NOT be reported as unused.
     *
     * <p>When a control exposes a {@code Class<T>} property (e.g. {@code viewClass}),
     * the compiler resolves the attribute value as a class name via the file's import
     * directives.  The unused-import inspection must recognize such plain-string class
     * name values as usages of the corresponding import.
     */
    @Test
    void importUsedAsClassTypedPropertyValueIsNotFlaggedAsUnused() {
        getFixture().addFileToProject("sample/app/ItemClass.java",
                """
                package sample.app;
                public class ItemClass {}
                """);
        getFixture().addFileToProject("sample/app/ClassHolder.java",
                """
                package sample.app;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                import javafx.scene.control.Label;
                public class ClassHolder<T> extends Label {
                    private final ObjectProperty<Class<T>> itemClass = new SimpleObjectProperty<>();
                    public Class<T> getItemClass() { return itemClass.get(); }
                    public ObjectProperty<Class<T>> itemClassProperty() { return itemClass; }
                    public void setItemClass(Class<T> value) { itemClass.set(value); }
                }
                """);
        getFixture().enableInspections(new Fxml2UnusedImportsInspection());
        // ItemClass is used only as the value of a Class<T> property: must NOT be flagged.
        getFixture().configureByText("TestView.fxml", fxml2(
                "sample.app.ItemClass\nsample.app.ClassHolder",
                """
                  <ClassHolder itemClass="ItemClass"/>
                """
        ));
        getFixture().checkHighlighting(true, false, false);
    }
}
