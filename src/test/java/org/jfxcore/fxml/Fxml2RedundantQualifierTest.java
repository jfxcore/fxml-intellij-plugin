package org.jfxcore.fxml;

import com.intellij.codeInspection.BatchQuickFix;
import com.intellij.codeInspection.ex.QuickFixWrapper;
import org.jfxcore.fxml.annotator.Fxml2RedundantQualifierInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Fxml2RedundantQualifierInspection}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2RedundantQualifierTest extends Fxml2TestBase {

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
                public class TestView extends TestViewBase {}
                """);
    }

    // -----------------------------------------------------------------------
    // fx:typeArguments: redundant qualifier detection
    // -----------------------------------------------------------------------

    /**
     * When the class is directly imported, the full package prefix is redundant.
     * {@code fx:typeArguments="javafx.scene.control.Button"} with
     * {@code <?import javafx.scene.control.Button?>} should flag {@code "javafx.scene.control."}.
     */
    @Test
    void typeArgumentsWithDirectImportFlagsRedundantPrefix() {
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView\njavafx.scene.control.Button",
                // The prefix 'javafx.scene.control.' inside the quoted value is greyed out.
                """
                  <ListView fx:typeArguments="<weak_warning descr="Redundant qualifier 'javafx.scene.control.'">javafx.scene.control.</weak_warning>Button"/>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    /**
     * When the class is not imported, no warning should appear.
     */
    @Test
    void typeArgumentsWithoutImportProducesNoWarning() {
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView",
                // Button is NOT imported: qualifier is necessary
                """
                  <ListView fx:typeArguments="javafx.scene.control.Button"/>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    /**
     * A simple (non-qualified) name in fx:typeArguments produces no warning.
     */
    @Test
    void typeArgumentsWithSimpleNameProducesNoWarning() {
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView\njavafx.scene.control.Button",
                """
                  <ListView fx:typeArguments="Button"/>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    /**
     * Quick-fix removes the redundant prefix, leaving just the simple name.
     */
    @Test
    void typeArgumentsQuickFixRemovesPrefix() {
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView\njavafx.scene.control.Button",
                // caret inside the redundant "javafx.scene.control." prefix
                """
                  <ListView fx:typeArguments="javafx.scene.<caret>control.Button"/>
                """
        ));
        // Trigger highlighting so inspection results are available
        getFixture().doHighlighting();
        var fixes = getFixture().getAllQuickFixes();
        assertFalse(fixes.isEmpty(), "Expected a quick-fix for redundant qualifier: " + fixes);
        getFixture().launchAction(fixes.stream()
                .filter(f -> f.getText().contains("Remove redundant qualifier"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Quick-fix not found: " + fixes)));
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("fx:typeArguments=\"Button\""),
                "Expected shortened value 'Button', but got: " + result);
        assertFalse(result.contains("fx:typeArguments=\"javafx.scene.control.Button\""),
                "Expected redundant qualifier to be removed from typeArguments attribute");
    }

    /**
     * For a nested class like {@code "com.example.OuterViewModel.Row"},
     * if {@code com.example.OuterViewModel} is imported, the prefix
     * {@code "com.example."} is redundant and the value should shorten to
     * {@code "OuterViewModel.Row"}.
     */
    @Test
    void typeArgumentsNestedClassWithOuterImportFlagsPackagePrefix() {
        getFixture().addFileToProject("com/example/OuterViewModel.java",
                """
                package com.example;
                public class OuterViewModel {
                    public static class Row {}
                }
                """);
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView\ncom.example.OuterViewModel",
                // "OuterViewModel.Row" is resolvable via the existing import
                """
                  <ListView fx:typeArguments="<weak_warning descr="Redundant qualifier 'com.example.'">com.example.</weak_warning>OuterViewModel.Row"/>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    /**
     * Quick-fix for a nested class shortens to {@code "OuterViewModel2.Row"}.
     */
    @Test
    void typeArgumentsNestedClassQuickFixShortensToBestSuffix() {
        getFixture().addFileToProject("com/example/OuterViewModel2.java",
                """
                package com.example;
                public class OuterViewModel2 {
                    public static class Row {}
                }
                """);
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView\ncom.example.OuterViewModel2",
                // caret inside the redundant "com.example." prefix
                """
                  <ListView fx:typeArguments="com.<caret>example.OuterViewModel2.Row"/>
                """
        ));
        getFixture().doHighlighting();
        var fixes = getFixture().getAllQuickFixes();
        assertFalse(fixes.isEmpty(), "Expected a quick-fix for redundant qualifier: " + fixes);
        getFixture().launchAction(fixes.stream()
                .filter(f -> f.getText().contains("Remove redundant qualifier"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Quick-fix not found: " + fixes)));
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("fx:typeArguments=\"OuterViewModel2.Row\""),
                "Expected shortened value 'OuterViewModel2.Row', but got: " + result);
        assertFalse(result.contains("com.example.OuterViewModel2.Row"),
                "Expected redundant package qualifier to be removed");
    }

    // -----------------------------------------------------------------------
    // String-typed class-name attributes: must never warn
    // -----------------------------------------------------------------------

    /**
     * String-typed attributes (e.g. viewClassName) must never produce a redundant-qualifier
     * warning, even when the value is a FQN for an imported class.
     */
    @Test
    void stringClassNameAttributeNeverWarns() {
        getFixture().addFileToProject("test2/ClassNameHolder.java",
                """
                package test2;
                import javafx.beans.NamedArg;
                public class ClassNameHolder {
                    public ClassNameHolder(@NamedArg("targetClass") String targetClass) {}
                }
                """);
        getFixture().addFileToProject("test2/MyView.java",
                """
                package test2;
                public class MyView {}
                """);
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        // test2.MyView is imported: but String-typed values must NOT be flagged.
        getFixture().configureByText("TestView.fxml", fxml2(
                "test2.ClassNameHolder\ntest2.MyView",
                """
                  <ClassNameHolder targetClass="test2.MyView"/>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }


    // -----------------------------------------------------------------------
    // FQN element tags
    // -----------------------------------------------------------------------

    /**
     * A FQN element tag like {@code <javafx.scene.control.TextField/>} where {@code TextField}
     * is imported should flag the redundant package prefix.
     */
    @Test
    void fqnElementTagWithDirectImportFlagsRedundantPrefix() {
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.BorderPane\njavafx.scene.control.TextField",
                """
                  <BorderPane>
                    <<weak_warning descr="Redundant qualifier 'javafx.scene.control.'">javafx.scene.control.</weak_warning>TextField/>
                  </BorderPane>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    /**
     * A FQN element tag whose class is not imported must not be flagged.
     */
    @Test
    void fqnElementTagWithoutImportProducesNoWarning() {
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.BorderPane",
                // TextField is NOT imported: qualifier is necessary
                """
                  <BorderPane>
                    <javafx.scene.control.TextField/>
                  </BorderPane>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    /**
     * Quick-fix for a FQN element tag renames it to the simple name.
     */
    @Test
    void fqnElementTagQuickFixRenamesToSimpleName() {
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.BorderPane\njavafx.scene.control.TextField",
                // caret inside the redundant prefix in the opening tag
                """
                  <BorderPane>
                    <javafx.scene.control.<caret>TextField/>
                  </BorderPane>
                """
        ));
        getFixture().doHighlighting();
        var fixes = getFixture().getAllQuickFixes();
        assertFalse(fixes.isEmpty(), "Expected a quick-fix for redundant qualifier: " + fixes);
        getFixture().launchAction(fixes.stream()
                .filter(f -> f.getText().contains("Remove redundant qualifier"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Quick-fix not found: " + fixes)));
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("<TextField/>"), "Expected tag renamed to <TextField/>, but got: " + result);
        assertFalse(result.contains("<javafx.scene.control.TextField/>"),
                "Expected FQN element tag to be shortened");
    }

    // -----------------------------------------------------------------------
    // Binding expression with FQN class prefix
    // -----------------------------------------------------------------------

    /**
     * When the code-behind class inherits a static field (e.g. {@code BorderPane}
     * transitively extends {@code Region}), the entire {@code FQNClass.} prefix is
     * redundant.  This also subsumes any import-based reduction.
     */
    @Test
    void bindingExpressionFqnWithDirectImportFlagsRedundantPrefix() {
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        // TestView extends BorderPane extends Region -> USE_PREF_SIZE is in scope
        // Case 1 fires: entire "javafx.scene.layout.Region." is redundant.
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.Region",
                """
                  <Region minHeight="$<weak_warning descr="Redundant qualifier 'javafx.scene.layout.Region.'">javafx.scene.layout.Region.</weak_warning>USE_PREF_SIZE"/>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    /**
     * When a FQN static field reference is used but neither the class is imported nor
     * the field is inherited by the start class, no warning is produced.
     * We use a file without fx:subclass (no code-behind) and without an import.
     */
    @Test
    void bindingExpressionFqnWithoutImportOrInheritanceProducesNoWarning() {
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        // No fx:subclass -> no code-behind -> no inheritance; Region is NOT imported -> no warning.
        getFixture().configureByText("TestView.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.Region?>
                <javafx.scene.layout.Region xmlns="http://javafx.com/javafx"
                                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                                            minHeight="$javafx.scene.layout.Region.USE_PREF_SIZE"/>
                """);
        // Region IS imported here, so the package prefix IS redundant (Case 2).
        // This test is now only meaningful without any import: use a raw FQN tag + no import.
        // Rewrite: no import, no code-behind -> no reduction possible.
        getFixture().configureByText("TestView2.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <javafx.scene.layout.Region xmlns="http://javafx.com/javafx"
                                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                                            minHeight="$javafx.scene.layout.Region.USE_PREF_SIZE"/>
                """);
        getFixture().checkHighlighting(false, false, true);
    }

    /**
     * Quick-fix for the import-only case (class imported, field not inherited by start class)
     * removes only the package prefix, leaving the class name.
     */
    @Test
    void bindingExpressionFqnClassImportedNoInheritanceQuickFixRemovesPackageOnly() {
        // Use a file without fx:subclass so there is no code-behind: only Case 2 applies.
        getFixture().addFileToProject("test/NoParentView.java",
                """
                package test;
                public class NoParentView {}
                """);
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        getFixture().configureByText("TestView.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.layout.Region?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.NoParentView"
                            minHeight="$javafx.scene.<caret>layout.Region.USE_PREF_SIZE"/>
                """);
        getFixture().doHighlighting();
        var fixes = getFixture().getAllQuickFixes();
        assertFalse(fixes.isEmpty(), "Expected a quick-fix for redundant qualifier: " + fixes);
        getFixture().launchAction(fixes.stream()
                .filter(f -> f.getText().contains("Remove redundant qualifier"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Quick-fix not found: " + fixes)));
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("minHeight=\"$Region.USE_PREF_SIZE\""),
                "Expected shortened binding path '$Region.USE_PREF_SIZE', but got: " + result);
    }

    /**
     * When {@code Region.USE_PREF_SIZE} is accessible as an inherited field of the binding
     * start class, the entire {@code javafx.scene.layout.Region.} prefix (including the
     * class name) is redundant and {@code $USE_PREF_SIZE} works directly.
     * This models the real sample: {@code <TextArea>} with a code-behind that extends
     * {@code BorderPane}, which transitively extends {@code Region}.
     */
    @Test
    void bindingExpressionFqnRedundantBecauseFieldInheritedByStartClass() {
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        // Root tag has fx:subclass="test.MyView" which extends BorderPane which extends Region.
        // USE_PREF_SIZE is defined on Region: it's inherited by the code-behind start class.
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.Region\njavafx.scene.control.TextArea",
                """
                  <TextArea minHeight="$<weak_warning descr="Redundant qualifier 'javafx.scene.layout.Region.'">javafx.scene.layout.Region.</weak_warning>USE_PREF_SIZE"/>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    /**
     * Quick-fix for the "field in scope" case removes the entire FQN class prefix,
     * leaving just the bare field name.
     */
    @Test
    void bindingExpressionFqnFieldInScopeQuickFixRemovesEntireClassPrefix() {
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.Region\njavafx.scene.control.TextArea",
                """
                  <TextArea minHeight="$javafx.scene.layout.Region.<caret>USE_PREF_SIZE"/>
                """
        ));
        getFixture().doHighlighting();
        var fixes = getFixture().getAllQuickFixes();
        assertFalse(fixes.isEmpty(), "Expected a quick-fix for redundant qualifier: " + fixes);
        getFixture().launchAction(fixes.stream()
                .filter(f -> f.getText().contains("Remove redundant qualifier"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Quick-fix not found: " + fixes)));
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("minHeight=\"$USE_PREF_SIZE\""),
                "Expected bare field reference '$USE_PREF_SIZE', but got: " + result);
    }

    /**
     * When the FQN class is imported but the field is NOT inherited by the start class,
     * only the package prefix (not the class name) is redundant.
     * Here the code-behind is a plain Java class (not a Region subtype), so Case 1
     * cannot fire and only the import-based Case 2 reduction applies.
     */
    @Test
    void bindingExpressionFqnClassImportedButNotInheritedFlagsOnlyPackagePrefix() {
        getFixture().addFileToProject("test3/PlainView.java",
                """
                package test3;
                public class PlainView {}
                """);
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        // PlainView does not extend Region: USE_PREF_SIZE is NOT in scope via inheritance.
        // Region IS imported: only the package prefix "javafx.scene.layout." is redundant.
        getFixture().configureByText("TestView.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.layout.Region?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test3.PlainView"
                            minHeight="$<weak_warning descr="Redundant qualifier 'javafx.scene.layout.'">javafx.scene.layout.</weak_warning>Region.USE_PREF_SIZE"/>
                """);
        getFixture().checkHighlighting(false, false, true);
    }

    // -----------------------------------------------------------------------
    // FQN class prefix in static-property attribute names
    // -----------------------------------------------------------------------

    /**
     * A static-property attribute with a FQN class prefix (e.g.
     * {@code javafx.scene.layout.VBox.vgrow="ALWAYS"}) should flag the redundant prefix
     * when {@code VBox} is imported.
     */
    @Test
    void staticPropertyAttributeFqnWithDirectImportFlagsRedundantPrefix() {
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.VBox\njavafx.scene.control.TextField",
                """
                  <VBox>
                    <TextField <weak_warning descr="Redundant qualifier 'javafx.scene.layout.'">javafx.scene.layout.</weak_warning>VBox.vgrow="ALWAYS"/>
                  </VBox>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    /**
     * A static-property attribute with a FQN class prefix where the class is not imported
     * must not be flagged.
     */
    @Test
    void staticPropertyAttributeFqnWithoutImportProducesNoWarning() {
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                // VBox is NOT imported: qualifier is necessary
                "javafx.scene.control.TextField",
                """
                  <javafx.scene.layout.VBox>
                    <TextField javafx.scene.layout.VBox.vgrow="ALWAYS"/>
                  </javafx.scene.layout.VBox>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    /**
     * Quick-fix for a FQN static-property attribute renames it to the short form.
     */
    @Test
    void staticPropertyAttributeFqnQuickFixShortens() {
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.VBox\njavafx.scene.control.TextField",
                // caret inside the redundant "javafx.scene.layout." in the attribute name
                """
                  <VBox>
                    <TextField javafx.scene.layout.<caret>VBox.vgrow="ALWAYS"/>
                  </VBox>
                """
        ));
        getFixture().doHighlighting();
        var fixes = getFixture().getAllQuickFixes();
        assertFalse(fixes.isEmpty(), "Expected a quick-fix for redundant qualifier: " + fixes);
        getFixture().launchAction(fixes.stream()
                .filter(f -> f.getText().contains("Remove redundant qualifier"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Quick-fix not found: " + fixes)));
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("VBox.vgrow=\"ALWAYS\""),
                "Expected shortened attribute 'VBox.vgrow', but got: " + result);
        assertFalse(result.contains("javafx.scene.layout.VBox.vgrow"),
                "Expected redundant qualifier to be removed from attribute name");
    }

    // -----------------------------------------------------------------------
    // FQN class prefix in static-property element tags
    // -----------------------------------------------------------------------

    /**
     * A static-property element tag with a FQN class prefix (e.g.
     * {@code <javafx.scene.layout.VBox.margin>}) should flag the redundant prefix
     * when {@code VBox} is imported.
     */
    @Test
    void staticPropertyElementTagFqnWithDirectImportFlagsRedundantPrefix() {
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.BorderPane\njavafx.scene.layout.VBox\njavafx.scene.control.TextField",
                """
                  <BorderPane>
                    <<weak_warning descr="Redundant qualifier 'javafx.scene.layout.'">javafx.scene.layout.</weak_warning>VBox.margin>
                      <TextField/>
                    </<weak_warning descr="Redundant qualifier 'javafx.scene.layout.'">javafx.scene.layout.</weak_warning>VBox.margin>
                  </BorderPane>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    /**
     * A static-property element tag with a FQN class prefix where the class is not imported
     * must not be flagged.
     */
    @Test
    void staticPropertyElementTagFqnWithoutImportProducesNoWarning() {
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                // VBox is NOT imported: qualifier is necessary
                "javafx.scene.layout.BorderPane\njavafx.scene.control.TextField",
                """
                  <BorderPane>
                    <javafx.scene.layout.VBox.margin>
                      <TextField/>
                    </javafx.scene.layout.VBox.margin>
                  </BorderPane>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    // -----------------------------------------------------------------------
    // Markup extension class name FQN
    // -----------------------------------------------------------------------

    /**
     * When a markup extension class is used with a FQN and the class is already imported,
     * the package prefix is redundant and should be flagged.
     */
    @Test
    void markupExtensionClassFqnWithDirectImportFlagsRedundantPrefix() {
        getFixture().addFileToProject("test/MyExt.java", """
                package test;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                import javafx.beans.property.Property;
                public class MyExt implements MarkupExtension.PropertyConsumer<String> {
                    @Override public void accept(Property<String> p, MarkupContext c) {}
                }
                """);
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.MyExt",
                """
                  <Label text="{<weak_warning descr="Redundant qualifier 'test.'">test.</weak_warning>MyExt}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    /**
     * When a markup extension class FQN is used but the class is not yet imported,
     * no redundant-qualifier warning should appear (the "add import" intention handles that).
     */
    @Test
    void markupExtensionClassFqnWithoutImportProducesNoWarning() {
        getFixture().addFileToProject("test/MyExt2.java", """
                package test;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                import javafx.beans.property.Property;
                public class MyExt2 implements MarkupExtension.PropertyConsumer<String> {
                    @Override public void accept(Property<String> p, MarkupContext c) {}
                }
                """);
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label", // MyExt2 NOT imported
                """
                  <Label text="{test.MyExt2}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    /**
     * Quick-fix for a FQN markup extension class name removes the redundant package prefix.
     */
    @Test
    void markupExtensionClassFqnQuickFixRemovesPrefix() {
        getFixture().addFileToProject("test/MyExt3.java", """
                package test;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                import javafx.beans.property.Property;
                public class MyExt3 implements MarkupExtension.PropertyConsumer<String> {
                    @Override public void accept(Property<String> p, MarkupContext c) {}
                }
                """);
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.MyExt3",
                """
                  <Label text="{test.<caret>MyExt3}"/>
                """
        ));
        getFixture().doHighlighting();
        var fixes = getFixture().getAllQuickFixes();
        assertFalse(fixes.isEmpty(), "Expected a quick-fix for redundant qualifier");
        getFixture().launchAction(fixes.stream()
                .filter(f -> f.getText().contains("Remove redundant qualifier"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Quick-fix not found: " + fixes)));
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("\"{MyExt3}\""), "Expected prefix stripped, got: " + result);
        assertFalse(result.contains("{test.MyExt3}"), "Expected FQN removed from value, got: " + result);
    }

    // -----------------------------------------------------------------------
    // Markup extension type argument FQN
    // -----------------------------------------------------------------------

    /**
     * A type argument FQN in a markup extension where the class is accessible via
     * implicit java.lang.* should flag the redundant prefix (no import needed).
     */
    @Test
    void markupExtTypeArgImplicitJavaLangFlagsRedundantPrefix() {
        getFixture().addFileToProject("test/GenExt.java", """
                package test;
                import javafx.beans.NamedArg;
                import javafx.beans.property.Property;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                public class GenExt<T> implements MarkupExtension.PropertyConsumer<T> {
                    public GenExt(@NamedArg("key") String key) {}
                    @Override public void accept(Property<T> p, MarkupContext c) {}
                }
                """);
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.GenExt",
                // java.lang.String is implicitly accessible: "java.lang." prefix is redundant
                """
                  <Label text="{GenExt<<weak_warning descr="Redundant qualifier 'java.lang.'">java.lang.</weak_warning>String> key=x}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    /**
     * A type argument FQN in a markup extension where the class is explicitly imported
     * should flag the redundant prefix.
     */
    @Test
    void markupExtTypeArgWithDirectImportFlagsRedundantPrefix() {
        getFixture().addFileToProject("test/GenExt2.java", """
                package test;
                import javafx.beans.NamedArg;
                import javafx.beans.property.Property;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                public class GenExt2<T> implements MarkupExtension.PropertyConsumer<T> {
                    public GenExt2(@NamedArg("key") String key) {}
                    @Override public void accept(Property<T> p, MarkupContext c) {}
                }
                """);
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.GenExt2\njavafx.scene.control.Button",
                // Button is imported, "javafx.scene.control." prefix is redundant
                """
                  <Label text="{GenExt2<<weak_warning descr="Redundant qualifier 'javafx.scene.control.'">javafx.scene.control.</weak_warning>Button> key=x}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    /**
     * A type argument FQN where the class is NOT accessible should produce no warning
     * (the "add import" intention handles it instead).
     */
    @Test
    void markupExtTypeArgWithoutImportProducesNoWarning() {
        getFixture().addFileToProject("test/GenExt3.java", """
                package test;
                import javafx.beans.NamedArg;
                import javafx.beans.property.Property;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                public class GenExt3<T> implements MarkupExtension.PropertyConsumer<T> {
                    public GenExt3(@NamedArg("key") String key) {}
                    @Override public void accept(Property<T> p, MarkupContext c) {}
                }
                """);
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        // Button is NOT imported: qualifier is necessary
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.GenExt3",
                """
                  <Label text="{GenExt3<javafx.scene.control.Button> key=x}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, true);
    }

    /**
     * Quick-fix for a redundant type argument prefix removes it from inside the angle brackets.
     */
    @Test
    void markupExtTypeArgQuickFixRemovesPrefix() {
        getFixture().addFileToProject("test/GenExt4.java", """
                package test;
                import javafx.beans.NamedArg;
                import javafx.beans.property.Property;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                public class GenExt4<T> implements MarkupExtension.PropertyConsumer<T> {
                    public GenExt4(@NamedArg("key") String key) {}
                    @Override public void accept(Property<T> p, MarkupContext c) {}
                }
                """);
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.GenExt4",
                // java.lang.String: "java.lang." prefix is redundant
                """
                  <Label text="{GenExt4<java.lang.<caret>String> key=x}"/>
                """
        ));
        getFixture().doHighlighting();
        var fixes = getFixture().getAllQuickFixes();
        assertFalse(fixes.isEmpty(), "Expected a quick-fix for redundant qualifier");
        getFixture().launchAction(fixes.stream()
                .filter(f -> f.getText().contains("Remove redundant qualifier"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Quick-fix not found: " + fixes)));
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("{GenExt4<String> key=x}"), "Expected prefix stripped, got: " + result);
        assertFalse(result.contains("java.lang.String"), "Expected FQN removed, got: " + result);
    }

    /**
     * Quick-fix for a FQN static-property element tag renames it to the short form.
     */
    @Test
    void staticPropertyElementTagFqnQuickFixShortens() {
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.BorderPane\njavafx.scene.layout.VBox\njavafx.scene.control.TextField",
                // caret inside the redundant prefix in the opening tag
                """
                  <BorderPane>
                    <javafx.scene.layout.<caret>VBox.margin>
                      <TextField/>
                    </javafx.scene.layout.VBox.margin>
                  </BorderPane>
                """
        ));
        getFixture().doHighlighting();
        var fixes = getFixture().getAllQuickFixes();
        assertFalse(fixes.isEmpty(), "Expected a quick-fix for redundant qualifier: " + fixes);
        getFixture().launchAction(fixes.stream()
                .filter(f -> f.getText().contains("Remove redundant qualifier"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Quick-fix not found: " + fixes)));
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("<VBox.margin>"),
                "Expected tag renamed to <VBox.margin>, but got: " + result);
        assertFalse(result.contains("<javafx.scene.layout.VBox.margin>"),
                "Expected FQN static-property tag to be shortened");
    }

    // -----------------------------------------------------------------------
    // Batch-apply support
    // -----------------------------------------------------------------------

    /**
     * All three quick-fix types exposed by this inspection must implement
     * {@link BatchQuickFix} so that "Fix all in file" is available.
     */
    @Test
    void redundantQualifierFixesAreBatchApplicable() {
        getFixture().enableInspections(new Fxml2RedundantQualifierInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView\njavafx.scene.control.Button",
                """
                  <ListView fx:typeArguments="javafx.scene.control.<caret>Button"/>
                """
        ));
        getFixture().doHighlighting();
        var fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && "Remove redundant qualifier".equals(fix.getFamilyName());
                })
                .toList();
        assertFalse(fixes.isEmpty(), "Expected 'Remove redundant qualifier' fix");
        var fix = QuickFixWrapper.unwrap(fixes.getFirst());
        assertInstanceOf(BatchQuickFix.class, fix,
                "Redundant qualifier fix should implement BatchQuickFix for 'Fix all in file' support");
    }
}
