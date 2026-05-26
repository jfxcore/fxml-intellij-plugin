package org.jfxcore.fxml;

import com.intellij.codeInsight.navigation.impl.GTDActionData;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiReference;
import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static com.intellij.codeInsight.navigation.impl.GtdKt.gotoDeclaration;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that XML element tag names resolve correctly to JavaFX classes and that
 * unknown or unimported class names produce the expected error annotation.
 */
@SuppressWarnings("UnstableApiUsage")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2TagResolutionTest extends Fxml2TestBase {
    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2AttributeValueInspection());
    }

    // -----------------------------------------------------------------------
    // Tags that should resolve without error
    // -----------------------------------------------------------------------

    @Test
    void knownClassTagWithCorrectImportResolvesWithoutError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button text="OK"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void rootTagResolvesWithoutError() {
        getFixture().configureByText("TestView.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.TestView"/>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void nestedKnownClassTagsResolveWithoutError() {
        getFixture().configureByText("TestView.fxml", fxml(
                """
                javafx.scene.control.Button
                javafx.scene.layout.VBox
                """,
                """
                  <VBox>
                    <Button text="OK"/>
                  </VBox>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void propertyElementTagDoesNotProduceError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button>
                    <text>Hello</text>
                  </Button>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Dotted property tag: per-segment reference ranges (opening and closing)
    // -----------------------------------------------------------------------

    /**
     * For a dotted property tag like {@code <selectionModel.selectionMode>}, ctrl-hovering
     * over each segment should underline only that segment, not the whole dotted name.
     * This test checks the opening tag.
     */
    @Test
    void dottedPropertyOpeningTagSegmentsHaveIndividualReferenceRanges() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.ListView",
                """
                  <ListView>
                    <selectionModel.<caret>selectionMode>MULTIPLE</selectionModel.selectionMode>
                  </ListView>
                """
        ));
        ReadAction.run(() -> {
            int caretOffset = getFixture().getCaretOffset();
            PsiReference ref = getFixture().getFile().findReferenceAt(caretOffset);
            assertNotNull(ref, "Expected a reference on the 'selectionMode' segment");
            String rangeText = ref.getRangeInElement().substring(ref.getElement().getText());
            assertEquals("selectionMode", rangeText,
                    "Opening tag: reference range should be exactly 'selectionMode'");
            PsiElement resolved = ref.resolve();
            assertNotNull(resolved, "Expected 'selectionMode' to resolve");
        });
    }

    @Test
    void dottedPropertyClosingTagSegmentsHaveIndividualReferenceRanges() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.ListView",
                """
                  <ListView>
                    <selectionModel.selectionMode>MULTIPLE</selectionModel.<caret>selectionMode>
                  </ListView>
                """
        ));
        ReadAction.run(() -> {
            int caretOffset = getFixture().getCaretOffset();
            PsiReference ref = getFixture().getFile().findReferenceAt(caretOffset);
            assertNotNull(ref, "Expected a reference on the 'selectionMode' segment of the closing tag");
            String rangeText = ref.getRangeInElement().substring(ref.getElement().getText());
            assertEquals("selectionMode", rangeText,
                    "Closing tag: reference range should be exactly 'selectionMode', not the whole dotted name");
            PsiElement resolved = ref.resolve();
            assertNotNull(resolved, "Expected 'selectionMode' to resolve on closing tag");
        });
    }

    // -----------------------------------------------------------------------
    // Unresolved tag error diagnostics
    // -----------------------------------------------------------------------

    @Test
    void unknownClassTagWithoutImportProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "",
                """
                  <<error descr="Cannot resolve symbol 'ButtonFoo'">ButtonFoo</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void classTagWithNonExistentImportProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.ButtonFoo",
                """
                  <<error descr="Cannot resolve symbol 'ButtonFoo'">ButtonFoo</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** An unresolvable tag produces exactly one error on the tag itself,
     *  not spurious errors on its attributes. */
    @Test
    void unresolvedClassTagProducesErrorOnTagNotOnAttributes() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane>
                    <<error descr="Cannot resolve symbol 'VBox2'">VBox2</error> spacing="10" alignment="CENTER"/>
                  </GridPane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** An unresolvable tag inside a property tag produces exactly one error on the tag. */
    @Test
    void unresolvedClassTagInsidePropertyTagProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.BorderPane",
                """
                  <BorderPane>
                    <center>
                      <<error descr="Cannot resolve symbol 'VBox2'">VBox2</error> spacing="10" alignment="CENTER"/>
                    </center>
                  </BorderPane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * An unresolved class tag inside a static property element tag must produce an error.
     *
     * <p>Static property element tags (e.g. {@code <PropHolder.behaviors>}) are resolved by the
     * prefix class ({@code PropHolder}) and a static accessor ({@code getBehaviors(Button)}).
     * A child tag whose name is neither a class nor a known property of the property's element
     * type must still be flagged as an unresolved symbol.
     */
    @Test
    void unresolvedClassTagInsideStaticPropertyElementTagProducesError() {
        getFixture().addFileToProject("test/StaticPropClass.java",
                """
                package test;
                import javafx.scene.control.Button;
                public class StaticPropClass {
                    public static java.util.List<Object> getBehaviors(Button node) { return null; }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml(
                """
                javafx.scene.control.Button
                test.StaticPropClass
                """,
                """
                  <Button>
                    <StaticPropClass.behaviors>
                      <<error descr="Cannot resolve symbol 'UnknownBehavior'">UnknownBehavior</error>/>
                    </StaticPropClass.behaviors>
                  </Button>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * When the caret is on an unresolved class name inside a static property element tag,
     * the "Add import" quick fix must be offered.
     */
    @Test
    void addImportFixOfferedForUnresolvedClassInsideStaticPropertyElementTag() {
        getFixture().addFileToProject("test/StaticPropClass2.java",
                """
                package test;
                import javafx.scene.control.Button;
                public class StaticPropClass2 {
                    public static java.util.List<Object> getBehaviors(Button node) { return null; }
                }
                """);
        getFixture().addFileToProject("test/MyBehavior.java",
                """
                package test;
                public class MyBehavior {}
                """);
        getFixture().configureByText("TestView.fxml", fxml(
                """
                javafx.scene.control.Button
                test.StaticPropClass2
                """,
                """
                  <Button>
                    <StaticPropClass2.behaviors>
                      <MyBehav<caret>ior/>
                    </StaticPropClass2.behaviors>
                  </Button>
                """
        ));
        getFixture().doHighlighting();
        com.intellij.codeInsight.intention.IntentionAction fix =
                getFixture().findSingleIntention("Add import for 'MyBehavior'");
        assertNotNull(fix,
                "Add import fix must be offered for unresolved class inside a static property element tag");
    }

    // -----------------------------------------------------------------------
    // Dotted attribute name: per-segment reference ranges
    // -----------------------------------------------------------------------

    /**
     * Ctrl+hovering over the class part of a static property attribute like {@code VBox.vgrow}
     * should underline only {@code VBox}, not the whole {@code VBox.vgrow}.
     */
    @Test
    void staticPropertyAttributeClassSegmentHasCorrectReferenceRange() {
        getFixture().configureByText("TestView.fxml", fxml(
                """
                javafx.scene.control.Button
                javafx.scene.layout.VBox
                """,
                """
                  <VBox>
                    <Button VB<caret>ox.vgrow="ALWAYS"/>
                  </VBox>
                """
        ));
        ReadAction.run(() -> {
            PsiReference ref = getFixture().getFile().findReferenceAt(getFixture().getCaretOffset());
            assertNotNull(ref, "Expected a reference on 'VBox'");
            assertEquals("VBox", ref.getRangeInElement().substring(ref.getElement().getText()),
                    "Class segment range should be exactly 'VBox'");
            assertNotNull(ref.resolve(), "VBox should resolve to a class");
        });
    }

    /**
     * Ctrl+hovering over the property part of a static property attribute like {@code VBox.vgrow}
     * should underline only {@code vgrow}, not the whole {@code VBox.vgrow}.
     */
    @Test
    void staticPropertyAttributePropSegmentHasCorrectReferenceRange() {
        getFixture().configureByText("TestView.fxml", fxml(
                """
                javafx.scene.control.Button
                javafx.scene.layout.VBox
                """,
                """
                  <VBox>
                    <Button VBox.vgr<caret>ow="ALWAYS"/>
                  </VBox>
                """
        ));
        ReadAction.run(() -> {
            PsiReference ref = getFixture().getFile().findReferenceAt(getFixture().getCaretOffset());
            assertNotNull(ref, "Expected a reference on 'vgrow'");
            assertEquals("vgrow", ref.getRangeInElement().substring(ref.getElement().getText()),
                    "Property segment range should be exactly 'vgrow'");
            assertNotNull(ref.resolve(), "vgrow should resolve to a static property method");
        });
    }

    /**
     * The value {@code ALWAYS} in the attribute notation {@code VBox.vgrow="ALWAYS"}
     * must navigate to {@code javafx.scene.layout.Priority.ALWAYS}.
     */
    @Test
    void staticPropertyAttributeValueNavigatesToEnumConstant() {
        getFixture().configureByText("TestView.fxml", fxml(
                """
                javafx.scene.control.Button
                javafx.scene.layout.VBox
                """,
                """
                  <VBox>
                    <Button VBox.vgrow="AL<caret>WAYS"/>
                  </VBox>
                """
        ));
        ReadAction.run(() -> {
            PsiReference ref = getFixture().getFile().findReferenceAt(getFixture().getCaretOffset());
            assertNotNull(ref, "Expected a reference on 'ALWAYS'");
            PsiElement target = ref.resolve();
            assertNotNull(target, "ALWAYS must resolve to Priority.ALWAYS");
            PsiField field1 = assertInstanceOf(PsiField.class, target, "Target must be a PsiField");
            assertEquals("ALWAYS", field1.getName());
        });
    }

    /**
     * The value {@code ALWAYS} in the element notation {@code <VBox.vgrow>ALWAYS</VBox.vgrow>}
     * must navigate to {@code javafx.scene.layout.Priority.ALWAYS}.
     */
    @Test
    void staticPropertyElementValueNavigatesToEnumConstant() {
        getFixture().configureByText("TestView.fxml", fxml(
                """
                javafx.scene.control.Button
                javafx.scene.layout.VBox
                """,
                """
                  <VBox>
                    <Button>
                      <VBox.vgrow>AL<caret>WAYS</VBox.vgrow>
                    </Button>
                  </VBox>
                """
        ));
        ReadAction.run(() -> {
            PsiReference ref = getFixture().getFile().findReferenceAt(getFixture().getCaretOffset());
            assertNotNull(ref, "Expected a reference on 'ALWAYS' inside <VBox.vgrow>");
            PsiElement target = ref.resolve();
            assertNotNull(target, "ALWAYS must resolve to Priority.ALWAYS");
            PsiField field2 = assertInstanceOf(PsiField.class, target, "Target must be a PsiField");
            assertEquals("ALWAYS", field2.getName());
        });
    }

    // -----------------------------------------------------------------------
    // fx:typeArguments: per-segment package + class navigation
    // -----------------------------------------------------------------------

    /**
     * Each segment of {@code fx:typeArguments="javafx.scene.control.ListView"} should be
     * navigable: package parts resolve to {@link com.intellij.psi.PsiPackage},
     * class part resolves to {@link com.intellij.psi.PsiClass}.
     */
    @Test
    void typeArgumentsPackageSegmentNavigates() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.ListView",
                """
                  <ListView fx:typeArguments="javafx.scene.con<caret>trol.ListView"/>
                """
        ));
        ReadAction.run(() -> {
            PsiReference ref = getFixture().getFile().findReferenceAt(getFixture().getCaretOffset());
            assertNotNull(ref, "Expected a reference on 'control' package segment");
            PsiElement resolved = ref.resolve();
            assertNotNull(resolved, "'control' package segment should resolve");
            assertInstanceOf(com.intellij.psi.PsiPackage.class, resolved,
                    "'control' should resolve to a PsiPackage");
        });
    }

    @Test
    void typeArgumentsClassSegmentNavigates() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.ListView",
                """
                  <ListView fx:typeArguments="javafx.scene.control.List<caret>View"/>
                """
        ));
        ReadAction.run(() -> {
            PsiReference ref = getFixture().getFile().findReferenceAt(getFixture().getCaretOffset());
            assertNotNull(ref, "Expected a reference on 'ListView' class segment");
            PsiElement resolved = ref.resolve();
            assertNotNull(resolved, "'ListView' class segment should resolve");
            assertInstanceOf(com.intellij.psi.PsiClass.class, resolved,
                    "'ListView' should resolve to a PsiClass");
            assertEquals("ListView", ((com.intellij.psi.PsiClass) resolved).getName());
        });
    }

    /**
     * Static nested class segment (e.g. {@code Nested} in {@code org.example.Outer.Nested}) must
     * navigate to the nested class.
     */
    @Test
    void typeArgumentsNestedClassSegmentNavigates() {
        getFixture().addFileToProject("org/example/Outer.java",
                """
                package org.example;
                public class Outer {
                    public static class Nested {}
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.ListView",
                """
                  <ListView fx:typeArguments="org.example.Outer.Ne<caret>sted"/>
                """
        ));
        ReadAction.run(() -> {
            PsiReference ref = getFixture().getFile().findReferenceAt(getFixture().getCaretOffset());
            assertNotNull(ref, "Expected a reference on 'Nested' nested class segment");
            PsiElement resolved = ref.resolve();
            assertNotNull(resolved, "'Nested' nested class segment should resolve");
            assertInstanceOf(com.intellij.psi.PsiClass.class, resolved,
                    "'Nested' should resolve to a PsiClass");
            assertEquals("Nested", ((com.intellij.psi.PsiClass) resolved).getName());
        });
    }

    /**
     * Inner (non-static) class segment must also navigate to the inner class.
     */
    @Test
    void typeArgumentsInnerClassSegmentNavigates() {
        getFixture().addFileToProject("org/example/Container.java",
                """
                package org.example;
                public class Container {
                    public class Item {}
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.ListView",
                """
                  <ListView fx:typeArguments="org.example.Container.It<caret>em"/>
                """
        ));
        ReadAction.run(() -> {
            PsiReference ref = getFixture().getFile().findReferenceAt(getFixture().getCaretOffset());
            assertNotNull(ref, "Expected a reference on 'Item' inner class segment");
            PsiElement resolved = ref.resolve();
            assertNotNull(resolved, "'Item' inner class segment should resolve");
            assertInstanceOf(com.intellij.psi.PsiClass.class, resolved,
                    "'Item' should resolve to a PsiClass");
            assertEquals("Item", ((com.intellij.psi.PsiClass) resolved).getName());
        });
    }

    /**
     * Two levels of nesting: {@code org.example.Outer.Inner.Leaf}, each segment navigable.
     */
    @Test
    void typeArgumentsDoubleNestedClassSegmentNavigates() {
        getFixture().addFileToProject("org/example/Outer.java",
                """
                package org.example;
                public class Outer {
                    public static class Inner {
                        public static class Leaf {}
                    }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.ListView",
                """
                  <ListView fx:typeArguments="org.example.Outer.Inner.Le<caret>af"/>
                """
        ));
        ReadAction.run(() -> {
            PsiReference ref = getFixture().getFile().findReferenceAt(getFixture().getCaretOffset());
            assertNotNull(ref, "Expected a reference on 'Leaf'");
            PsiElement resolved = ref.resolve();
            assertNotNull(resolved, "'Leaf' should resolve");
            assertInstanceOf(com.intellij.psi.PsiClass.class, resolved);
            assertEquals("Leaf", ((com.intellij.psi.PsiClass) resolved).getName());
        });
    }

    @Test
    void typeArgumentsDoubleNestedClassIntermediateSegmentNavigates() {
        getFixture().addFileToProject("org/example/Outer.java",
                """
                package org.example;
                public class Outer {
                    public static class Inner {
                        public static class Leaf {}
                    }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.ListView",
                """
                  <ListView fx:typeArguments="org.example.Outer.Inn<caret>er.Leaf"/>
                """
        ));
        ReadAction.run(() -> {
            PsiReference ref = getFixture().getFile().findReferenceAt(getFixture().getCaretOffset());
            assertNotNull(ref, "Expected a reference on 'Inner'");
            PsiElement resolved = ref.resolve();
            assertNotNull(resolved, "'Inner' should resolve");
            assertInstanceOf(com.intellij.psi.PsiClass.class, resolved);
            assertEquals("Inner", ((com.intellij.psi.PsiClass) resolved).getName());
        });
    }

    // -----------------------------------------------------------------------
    // String-typed attribute whose value is a fully-qualified class name
    // -----------------------------------------------------------------------

    /**
     * For an attribute like {@code targetClass="javafx.scene.control.Button"},
     * each segment should be navigable: package parts -> PsiPackage, class -> PsiClass.
     */
    @Test
    void stringClassNameAttributePackageSegmentNavigates() {
        getFixture().addFileToProject("test/ClassNameHolder.java",
                """
                package test;
                import javafx.beans.NamedArg;
                public class ClassNameHolder {
                    public ClassNameHolder(@NamedArg("targetClass") String targetClass) {}
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml(
                "test.ClassNameHolder",
                """
                  <ClassNameHolder targetClass="javafx.scene.con<caret>trol.Button"/>
                """
        ));
        ReadAction.run(() -> {
            PsiReference ref = getFixture().getFile().findReferenceAt(getFixture().getCaretOffset());
            assertNotNull(ref, "Expected a reference on 'control' in targetClass value");
            PsiElement resolved = ref.resolve();
            assertNotNull(resolved, "'control' should resolve to a package");
            assertInstanceOf(com.intellij.psi.PsiPackage.class, resolved,
                    "'control' should resolve to a PsiPackage");
        });
    }

    @Test
    void stringClassNameAttributeClassSegmentNavigates() {
        getFixture().addFileToProject("test/ClassNameHolder.java",
                """
                package test;
                import javafx.beans.NamedArg;
                public class ClassNameHolder {
                    public ClassNameHolder(@NamedArg("targetClass") String targetClass) {}
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml(
                "test.ClassNameHolder",
                """
                  <ClassNameHolder targetClass="javafx.scene.control.But<caret>ton"/>
                """
        ));
        ReadAction.run(() -> {
            PsiReference ref = getFixture().getFile().findReferenceAt(getFixture().getCaretOffset());
            assertNotNull(ref, "Expected a reference on 'Button' in targetClass value");
            PsiElement resolved = ref.resolve();
            assertNotNull(resolved, "'Button' should resolve to a class");
            assertInstanceOf(com.intellij.psi.PsiClass.class, resolved,
                    "'Button' should resolve to a PsiClass");
            assertEquals("Button", ((com.intellij.psi.PsiClass) resolved).getName());
        });
    }

    /**
     * {@code targetClass} pointing to a class defined in the same project (not a library).
     */
    @Test
    void stringClassNameAttributeProjectLocalClassSegmentNavigates() {
        getFixture().addFileToProject("test/ClassNameHolder.java",
                """
                package test;
                import javafx.beans.NamedArg;
                public class ClassNameHolder {
                    public ClassNameHolder(@NamedArg("targetClass") String targetClass) {}
                }
                """);
        getFixture().addFileToProject("org/example/MyView.java",
                """
                package org.example;
                public class MyView {}
                """);
        getFixture().configureByText("TestView.fxml", fxml(
                "test.ClassNameHolder",
                """
                  <ClassNameHolder targetClass="org.example.MyV<caret>iew"/>
                """
        ));
        ReadAction.run(() -> {
            PsiReference ref = getFixture().getFile().findReferenceAt(getFixture().getCaretOffset());
            assertNotNull(ref, "Expected a reference on 'MyView' in targetClass value");
            PsiElement resolved = ref.resolve();
            assertNotNull(resolved, "'MyView' should resolve to a project-local class");
            assertInstanceOf(com.intellij.psi.PsiClass.class, resolved,
                    "'MyView' should resolve to a PsiClass");
            assertEquals("MyView", ((com.intellij.psi.PsiClass) resolved).getName());
        });
    }

    /**
     * A {@code String}-typed attribute whose value is an unresolvable FQN must produce no error.
     * Navigation still works when the class exists; this test verifies the no-error case.
     */
    @Test
    void stringClassNameAttributeWithUnresolvableClassProducesNoError() {
        getFixture().addFileToProject("test/ClassNameHolder2.java",
                """
                package test;
                import javafx.beans.NamedArg;
                public class ClassNameHolder2 {
                    public ClassNameHolder2(@NamedArg("targetClass") String targetClass) {}
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml(
                "test.ClassNameHolder2",
                """
                  <ClassNameHolder2 targetClass="some.nonexistent.ClassName"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // fx:typeArguments: comma-separated list: navigation and hover ranges
    // -----------------------------------------------------------------------

    /**
     * First type argument in a comma-separated list must navigate independently.
     * {@code fx:typeArguments="javafx.scene.control.Button, javafx.scene.control.Label"}:
     * caret on {@code Button} must resolve to PsiClass Button.
     */
    @Test
    void typeArgumentsCommaListFirstTokenClassNavigates() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.ListView",
                """
                  <ListView fx:typeArguments="javafx.scene.control.But<caret>ton, javafx.scene.control.Label"/>
                """
        ));
        ReadAction.run(() -> {
            PsiReference ref = getFixture().getFile().findReferenceAt(getFixture().getCaretOffset());
            assertNotNull(ref, "Expected a reference on 'Button' (first token)");
            PsiElement resolved = ref.resolve();
            assertNotNull(resolved, "'Button' should resolve");
            assertInstanceOf(com.intellij.psi.PsiClass.class, resolved);
            assertEquals("Button", ((com.intellij.psi.PsiClass) resolved).getName());
        });
    }

    /**
     * Second type argument in a comma-separated list must navigate independently.
     * Caret on {@code Label} must resolve to PsiClass Label.
     */
    @Test
    void typeArgumentsCommaListSecondTokenClassNavigates() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.ListView",
                """
                  <ListView fx:typeArguments="javafx.scene.control.Button, javafx.scene.control.La<caret>bel"/>
                """
        ));
        ReadAction.run(() -> {
            PsiReference ref = getFixture().getFile().findReferenceAt(getFixture().getCaretOffset());
            assertNotNull(ref, "Expected a reference on 'Label' (second token)");
            PsiElement resolved = ref.resolve();
            assertNotNull(resolved, "'Label' should resolve");
            assertInstanceOf(com.intellij.psi.PsiClass.class, resolved);
            assertEquals("Label", ((com.intellij.psi.PsiClass) resolved).getName());
        });
    }

    /**
     * Package segment of the second type argument must also navigate.
     * Caret on {@code control} in the second token.
     */
    @Test
    void typeArgumentsCommaListSecondTokenPackageSegmentNavigates() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.ListView",
                """
                  <ListView fx:typeArguments="javafx.scene.control.Button, javafx.scene.con<caret>trol.Label"/>
                """
        ));
        ReadAction.run(() -> {
            PsiReference ref = getFixture().getFile().findReferenceAt(getFixture().getCaretOffset());
            assertNotNull(ref, "Expected a reference on 'control' in second token");
            PsiElement resolved = ref.resolve();
            assertNotNull(resolved, "'control' package should resolve");
            assertInstanceOf(com.intellij.psi.PsiPackage.class, resolved);
        });
    }

    /**
     * Extra whitespace around the comma must be tolerated:
     * {@code "javafx.scene.control.Button  ,  javafx.scene.control.Label"}.
     */
    @Test
    void typeArgumentsCommaListWithExtraWhitespaceNavigates() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.ListView",
                """
                  <ListView fx:typeArguments="javafx.scene.control.Button  ,  javafx.scene.control.La<caret>bel"/>
                """
        ));
        ReadAction.run(() -> {
            PsiReference ref = getFixture().getFile().findReferenceAt(getFixture().getCaretOffset());
            assertNotNull(ref, "Expected a reference on 'Label' despite surrounding whitespace");
            PsiElement resolved = ref.resolve();
            assertNotNull(resolved, "'Label' should resolve despite extra whitespace");
            assertInstanceOf(com.intellij.psi.PsiClass.class, resolved);
            assertEquals("Label", ((com.intellij.psi.PsiClass) resolved).getName());
        });
    }

    /**
     * Hover on {@code Button} in a two-type-argument list must underline only
     * the {@code Button} segment (6 chars), not the whole attribute value.
     */
    @Test
    void typeArgumentsCommaListFirstTokenHoverRangeIsTight() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.ListView",
                // caret on "Button": 6 chars
                """
                  <ListView fx:typeArguments="javafx.scene.control.But<caret>ton, javafx.scene.control.Label"/>
                """
        ));
        TextRange range = ReadAction.compute(() -> {
            int offset = getFixture().getCaretOffset();
            GTDActionData data = gotoDeclaration(getFixture().getFile(), offset);
            if (data == null) return null;
            var ctrlMouseData = data.ctrlMouseData();
            if (ctrlMouseData == null) return null;
            List<TextRange> ranges = ctrlMouseData.getRanges();
            return ranges.isEmpty() ? null : ranges.getFirst();
        });
        assertNotNull(range, "Expected a hover highlight range");
        assertEquals(6, range.getLength(),
                "Hover should cover only 'Button' (6 chars), got " + range.getLength() + " at " + range);
    }

    /**
     * Hover on {@code Label} in a two-type-argument list must underline only
     * the {@code Label} segment (5 chars).
     */
    @Test
    void typeArgumentsCommaListSecondTokenHoverRangeIsTight() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.ListView",
                """
                  <ListView fx:typeArguments="javafx.scene.control.Button, javafx.scene.control.La<caret>bel"/>
                """
        ));
        TextRange range = ReadAction.compute(() -> {
            int offset = getFixture().getCaretOffset();
            GTDActionData data = gotoDeclaration(getFixture().getFile(), offset);
            if (data == null) return null;
            var ctrlMouseData = data.ctrlMouseData();
            if (ctrlMouseData == null) return null;
            List<TextRange> ranges = ctrlMouseData.getRanges();
            return ranges.isEmpty() ? null : ranges.getFirst();
        });
        assertNotNull(range, "Expected a hover highlight range for Label");
        assertEquals(5, range.getLength(),
                "Hover should cover only 'Label' (5 chars), got " + range.getLength() + " at " + range);
    }

    /**
     * A whitespace-only trailing token produced by typing a comma and a space after the
     * last type argument (e.g. {@code "javafx.scene.control.Button, "}) must not cause
     * {@code getReferences()} to throw a {@link StringIndexOutOfBoundsException}.
     * The empty trailing token must be silently skipped; no reference covers the trailing position.
     */
    @Test
    void typeArgumentsTrailingWhitespaceTokenDoesNotThrow() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.ListView",
                """
                  <ListView fx:typeArguments="javafx.scene.control.Button, <caret>"/>
                """
        ));
        ReadAction.run(() -> {
            // Must not throw StringIndexOutOfBoundsException.
            PsiReference ref = getFixture().getFile().findReferenceAt(getFixture().getCaretOffset());
            assertNull(ref, "Trailing whitespace-only token should produce no reference");
        });
    }

    // -----------------------------------------------------------------------
    // @NamedArg attribute-name navigation
    // -----------------------------------------------------------------------

    /**
     * Ctrl+clicking on the attribute name of a {@code @NamedArg} constructor parameter
     * should navigate to the constructor parameter declaration.
     */
    @Test
    void namedArgAttributeNameNavigatesToConstructorParameter() {
        getFixture().addFileToProject("test/MultiArgHolder.java",
                """
                package test;
                import javafx.beans.NamedArg;
                public class MultiArgHolder {
                    public MultiArgHolder(
                            @NamedArg("required") String required,
                            @NamedArg("optional") boolean optional) {}
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml(
                "test.MultiArgHolder",
                """
                  <MultiArgHolder required="x" optio<caret>nal="true"/>
                """
        ));
        ReadAction.run(() -> {
            // The attribute name reference is via XmlAttributeReference -> descriptor.getDeclaration()
            // which is driven by Fxml2PropertyAttributeDescriptor with sibling attrs.
            var attrVal = getFixture().getFile().findElementAt(getFixture().getCaretOffset());
            assertNotNull(attrVal);
            // Walk up to the XmlAttribute
            var xmlAttr = attrVal.getParent();
            while (xmlAttr != null && !(xmlAttr instanceof com.intellij.psi.xml.XmlAttribute)) {
                xmlAttr = xmlAttr.getParent();
            }
            assertNotNull(xmlAttr, "Expected an XmlAttribute at caret");
            var descriptor = ((com.intellij.psi.xml.XmlAttribute) xmlAttr).getDescriptor();
            assertNotNull(descriptor, "Expected a descriptor for 'optional'");
            PsiElement decl = descriptor.getDeclaration();
            assertNotNull(decl, "Expected 'optional' to resolve to a constructor parameter");
                assertInstanceOf(com.intellij.psi.PsiParameter.class, decl,
                    "'optional' should resolve to a @NamedArg PsiParameter");
            assertEquals("optional", ((com.intellij.psi.PsiParameter) decl).getName());
        });
    }

    // -----------------------------------------------------------------------
    // FQN dotted element tag: per-segment navigation
    // -----------------------------------------------------------------------

    /**
     * For a FQN element tag like {@code <javafx.scene.control.TextField/>},
     * ctrl+hovering over a package segment (e.g. {@code control}) should navigate to
     * the corresponding {@link com.intellij.psi.PsiPackage}.
     */
    @Test
    void fqnElementTagPackageSegmentNavigates() {
        getFixture().configureByText("TestView.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <javafx.scene.con<caret>trol.TextField
                        xmlns="http://javafx.com/javafx"
                        xmlns:fx="http://jfxcore.org/fxml/2.0"/>
                """);
        ReadAction.run(() -> {
            PsiReference ref = getFixture().getFile().findReferenceAt(getFixture().getCaretOffset());
            assertNotNull(ref, "Expected a reference on 'control' segment of FQN tag");
            PsiElement resolved = ref.resolve();
            assertNotNull(resolved, "'control' segment should resolve");
            assertInstanceOf(com.intellij.psi.PsiPackage.class, resolved,
                    "'control' should resolve to a PsiPackage");
        });
    }

    /**
     * For a FQN element tag like {@code <javafx.scene.control.TextField/>},
     * ctrl+hovering over the class segment ({@code TextField}) should navigate to
     * the corresponding {@link com.intellij.psi.PsiClass}.
     */
    @Test
    void fqnElementTagClassSegmentNavigates() {
        getFixture().configureByText("TestView.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <javafx.scene.control.Text<caret>Field
                        xmlns="http://javafx.com/javafx"
                        xmlns:fx="http://jfxcore.org/fxml/2.0"/>
                """);
        ReadAction.run(() -> {
            PsiReference ref = getFixture().getFile().findReferenceAt(getFixture().getCaretOffset());
            assertNotNull(ref, "Expected a reference on 'TextField' class segment of FQN tag");
            PsiElement resolved = ref.resolve();
            assertNotNull(resolved, "'TextField' segment should resolve");
            assertInstanceOf(com.intellij.psi.PsiClass.class, resolved,
                    "'TextField' should resolve to a PsiClass");
            assertEquals("TextField", ((com.intellij.psi.PsiClass) resolved).getName());
        });
    }

    // -----------------------------------------------------------------------
    // AddImportForClassReferenceIntention: FQN element tag
    // -----------------------------------------------------------------------

    /**
     * When the caret is on a class segment of a FQN element tag that is not yet imported,
     * the "Add import for class reference" intention must be available, must insert the
     * correct {@code <?import?>} PI, and must rename the tag to the simple name.
     */
    @Test
    void addImportIntentionOfferedForFqnElementTag() {
        getFixture().configureByText("AddImportTag.fxml", fxml(
                "", // Label is NOT imported
                """
                  <javafx.scene.control.La<caret>bel text="hello"/>
                """
        ));
        com.intellij.codeInsight.intention.IntentionAction action =
                getFixture().findSingleIntention("Add import for class reference");
        assertNotNull(action, "Expected 'Add import for class reference' intention to be available");
        getFixture().launchAction(action);
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("<?import javafx.scene.control.Label?>"),
                "Expected import to be added, got: " + result);
        assertTrue(result.contains("<Label text=\"hello\"/>"),
                "Expected tag to be renamed to simple name, got: " + result);
        assertFalse(result.contains("<javafx.scene.control.Label"),
                "Expected FQN tag to be replaced with simple name, got: " + result);
    }

    /**
     * When the class of a FQN element tag is already imported, the
     * "Add import for class reference" intention must NOT be offered.
     */
    @Test
    void addImportIntentionNotOfferedForFqnElementTagWhenAlreadyImported() {
        getFixture().configureByText("AddImportTagAlready.fxml", fxml(
                "javafx.scene.control.Label", // Label IS already imported
                """
                  <javafx.scene.control.La<caret>bel text="hello"/>
                """
        ));
        List<com.intellij.codeInsight.intention.IntentionAction> actions =
                getFixture().getAvailableIntentions();
        assertFalse(
                actions.stream().anyMatch(f -> f.getText().equals("Add import for class reference")),
                "Should not offer 'Add import for class reference' when already imported, got: "
                        + actions.stream().map(com.intellij.codeInsight.intention.IntentionAction::getText).toList());
    }
}
