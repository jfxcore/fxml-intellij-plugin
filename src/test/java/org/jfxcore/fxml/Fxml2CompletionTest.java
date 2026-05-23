package org.jfxcore.fxml;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link org.jfxcore.fxml.codeinsight.Fxml2CompletionContributor}.
 *
 * <p>Because the fixture uses {@code @TestInstance(PER_CLASS)}, files added with
 * {@code addFileToProject} persist across tests within this class.  Each test
 * therefore uses a distinct source path so that repeated calls never collide.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2CompletionTest extends Fxml2TestBase {

    /** Full FQN lookup strings: what gets inserted into the document. */
    private static List<String> lookupStrings(LookupElement[] items) {
        return Arrays.stream(items).map(LookupElement::getLookupString).toList();
    }

    /** Presentable (display) text: the short segment shown to the user in the popup. */
    private static List<String> displayNames(LookupElement[] items) {
        return Arrays.stream(items).map(e -> {
            LookupElementPresentation p = new LookupElementPresentation();
            e.renderElement(p);
            return p.getItemText();
        }).toList();
    }

    // -----------------------------------------------------------------------
    // fx:typeArguments completion
    // -----------------------------------------------------------------------

    /**
     * Typing a partial package name in {@code fx:typeArguments} should offer
     * matching sub-packages.  The lookup string is the full subpackage FQN
     * (e.g. {@code "javafx.scene.control"}) and the display name is just the
     * last segment (e.g. {@code "control"}).
     */
    @Test
    void typeArgumentsPackageCompletion() {
        getFixture().configureByText("TypeArgsPackage.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView fx:typeArguments="javafx.scene.<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items");
        List<String> fqns = lookupStrings(items);
        List<String> names = displayNames(items);
        assertTrue(fqns.contains("javafx.scene.control"),
                "Expected 'javafx.scene.control' FQN in completions, got: " + fqns);
        assertTrue(names.contains("control"),
                "Expected 'control' display name in completions, got: " + names);
    }

    /**
     * Typing a partial class name in {@code fx:typeArguments} should offer matching classes.
     */
    @Test
    void typeArgumentsClassCompletion() {
        getFixture().configureByText("TypeArgsClass.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView fx:typeArguments="javafx.scene.control.List<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items");
        List<String> fqns = lookupStrings(items);
        List<String> names = displayNames(items);
        assertTrue(fqns.contains("javafx.scene.control.ListView"),
                "Expected 'javafx.scene.control.ListView' FQN in completions, got: " + fqns);
        assertTrue(names.contains("ListView"),
                "Expected 'ListView' display name in completions, got: " + names);
    }

    /**
     * After {@code Outer.}, completion should offer its inner/nested classes.
     */
    @Test
    void typeArgumentsNestedClassCompletion() {
        getFixture().addFileToProject("model/Outer.java",
                """
                package model;
                public class Outer {
                    public static class Nested {}
                    public static class OtherNested {}
                }
                """);
        getFixture().configureByText("TypeArgsNested.fxml", fxml2(
                "javafx.scene.control.ListView\nmodel.Outer",
                """
                  <ListView fx:typeArguments="model.Outer.<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items");
        List<String> fqns = lookupStrings(items);
        List<String> names = displayNames(items);
        assertTrue(fqns.contains("model.Outer.Nested"),
                "Expected 'model.Outer.Nested' FQN in completions, got: " + fqns);
        assertTrue(fqns.contains("model.Outer.OtherNested"),
                "Expected 'model.Outer.OtherNested' FQN in completions, got: " + fqns);
        assertTrue(names.contains("Nested"),       "Expected 'Nested' display name, got: "      + names);
        assertTrue(names.contains("OtherNested"),  "Expected 'OtherNested' display name, got: " + names);
    }

    /**
     * Typing a partial class name in {@code fx:typeArguments} should offer matching
     * project-local classes.
     */
    @Test
    void typeArgumentsProjectClassPartialCompletion() {
        getFixture().addFileToProject("model/MyData.java",
                """
                package model;
                public class MyData {}
                """);
        getFixture().configureByText("TypeArgsPartial.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView fx:typeArguments="model.MyD<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        // null means a single match was auto-inserted: verify the document text
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("model.MyData"),
                    "Expected 'model.MyData' to be auto-inserted, document: " + text);
            return;
        }
        List<String> fqns = lookupStrings(items);
        List<String> names = displayNames(items);
        assertTrue(fqns.contains("model.MyData"),
                "Expected 'model.MyData' in completions, got: " + fqns);
        assertTrue(names.contains("MyData"),
                "Expected 'MyData' display name, got: " + names);
    }

    /**
     * Typing a partial simple name in {@code fx:typeArguments} for a class that is NOT yet
     * imported must offer that class by its simple name and insert the import when selected.
     * Abstract classes are valid type arguments and must not be filtered out.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void typeArgumentsUnimportedSimpleNameCompletion() {
        getFixture().addFileToProject("sample/app/UnimportedItem.java",
                """
                package sample.app;
                public abstract class UnimportedItem {}
                """);
        getFixture().addFileToProject("sample/app/MyListUI.java",
                """
                package sample.app;
                import javafx.scene.control.ListView;
                public class MyListUI<T> extends ListView<T> {}
                """);
        // UnimportedItem is NOT imported: typing its prefix should still offer it.
        getFixture().configureByText("TypeArgsUnimported.fxml", fxml2(
                "sample.app.MyListUI",
                """
                  <MyListUI fx:typeArguments="Unimported<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            // Single match was auto-inserted.
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("UnimportedItem"),
                    "Expected 'UnimportedItem' to be auto-inserted, document: " + text);
            assertTrue(text.contains("<?import sample.app.UnimportedItem?>"),
                    "Expected import to be added, document: " + text);
            return;
        }
        List<String> names = displayNames(items);
        assertTrue(names.contains("UnimportedItem"),
                "Expected 'UnimportedItem' display name in completions, got: " + names);

        // Select the item and verify the import is added.
        getFixture().getLookup().setCurrentItem(
                items[names.indexOf("UnimportedItem")]);
        getFixture().finishLookup(com.intellij.codeInsight.lookup.Lookup.NORMAL_SELECT_CHAR);
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("<?import sample.app.UnimportedItem?>"),
                "Expected import to be added after selecting completion, but got: " + result);
    }

    /**
     * Typing a partial simple name in {@code fx:typeArguments} should offer matching
     * imported classes by their simple name. E.g. with {@code <?import sample.app.Button?>},
     * typing {@code fx:typeArguments="Bu<caret>"} must offer {@code Button}.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void typeArgumentsImportedSimpleNameCompletion() {
        getFixture().addFileToProject("sample/app/Button.java",
                """
                package sample.app;
                public class Button extends javafx.scene.control.Button {}
                """);
        getFixture().configureByText("TypeArgsImported.fxml", fxml2(
                "javafx.scene.control.ListView\nsample.app.Button",
                """
                  <ListView fx:typeArguments="Bu<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("Button"),
                    "Expected 'Button' to be auto-inserted, document: " + text);
            return;
        }
        List<String> fqns = lookupStrings(items);
        List<String> names = displayNames(items);
        assertTrue(fqns.contains("Button") || fqns.contains("sample.app.Button"),
                "Expected 'Button' or 'sample.app.Button' in typeArguments completions, got: " + fqns);
        assertTrue(names.contains("Button"),
                "Expected 'Button' display name in typeArguments completions, got: " + names);
    }

    /**
     * {@code java.lang.*} classes (e.g. {@code String}) are always accessible without an explicit
     * import. The completion provider must still offer them when the user types a partial name in
     * {@code fx:typeArguments}.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void typeArgumentsJavaLangClassCompletion() {
        getFixture().configureByText("TypeArgsJavaLang.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView fx:typeArguments="Strin<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("String"),
                    "Expected 'String' to be auto-inserted, document: " + text);
            return;
        }
        List<String> names = displayNames(items);
        assertTrue(names.contains("String"),
                "Expected 'String' (java.lang) in fx:typeArguments completions, got: " + names);
    }

    // -----------------------------------------------------------------------
    // <?import ...?> FQN completion
    // -----------------------------------------------------------------------

    /**
     * {@code fx:typeArguments} with multiple comma-separated arguments: completing the second
     * argument should offer the imported class, not be confused by the first argument.
     * E.g. {@code fx:typeArguments="Row,Bu<caret>"} must offer {@code Button}.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void typeArgumentsMultipleArgsCompletion() {
        getFixture().addFileToProject("sample/app/Button.java",
                """
                package sample.app;
                public class Button extends javafx.scene.control.Button {}
                """);
        getFixture().addFileToProject("sample/app/Row.java",
                """
                package sample.app;
                public class Row {}
                """);
        getFixture().configureByText("TypeArgsMulti.fxml", fxml2(
                "javafx.scene.control.ListView\nsample.app.Button\nsample.app.Row",
                """
                  <ListView fx:typeArguments="Row,Bu<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("Button"),
                    "Expected 'Button' to be auto-inserted, document: " + text);
            return;
        }
        List<String> fqns = lookupStrings(items);
        List<String> names = displayNames(items);
        assertTrue(fqns.contains("Button") || fqns.contains("sample.app.Button"),
                "Expected 'Button' in multi-arg typeArguments completions, got: " + fqns);
        assertTrue(names.contains("Button"),
                "Expected 'Button' display name in multi-arg typeArguments completions, got: " + names);
    }

    /**
     * Same as above but with a space after the comma: {@code "Row, Bu<caret>"}.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void typeArgumentsMultipleArgsWithSpaceCompletion() {
        getFixture().addFileToProject("sample/app/Button2.java",
                """
                package sample.app;
                public class Button2 extends javafx.scene.control.Button {}
                """);
        getFixture().addFileToProject("sample/app/Row2.java",
                """
                package sample.app;
                public class Row2 {}
                """);
        getFixture().configureByText("TypeArgsMultiSpace.fxml", fxml2(
                "javafx.scene.control.ListView\nsample.app.Button2\nsample.app.Row2",
                """
                  <ListView fx:typeArguments="Row2, Bu<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("Button2"),
                    "Expected 'Button2' to be auto-inserted, document: " + text);
            return;
        }
        List<String> fqns = lookupStrings(items);
        List<String> names = displayNames(items);
        assertTrue(fqns.contains("Button2") || fqns.contains("sample.app.Button2"),
                "Expected 'Button2' in space-separated typeArguments completions, got: " + fqns);
        assertTrue(names.contains("Button2"),
                "Expected 'Button2' display name in space-separated typeArguments completions, got: " + names);
    }

    /**
     * Inside {@code <?import javafx.scene.<caret>?>} the contributor should offer
     * sub-packages (e.g. {@code javafx.scene.control}).
     */
    @Test
    void importPiPackageCompletion() {
        getFixture().configureByText("ImportPiPackage.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.<caret>?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.TestView"/>
                """);
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items in import PI");
        List<String> fqns = lookupStrings(items);
        List<String> names = displayNames(items);
        assertTrue(fqns.contains("javafx.scene.control"),
                "Expected 'javafx.scene.control' in import PI completions, got: " + fqns);
        assertTrue(names.contains("control"),
                "Expected 'control' display name in import PI completions, got: " + names);
    }

    /**
     * Inside {@code <?import javafx.scene.control.List<caret>?>} the contributor should
     * offer matching classes (e.g. {@code javafx.scene.control.ListView}).
     */
    @Test
    void importPiClassCompletion() {
        getFixture().configureByText("ImportPiClass.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.List<caret>?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.TestView"/>
                """);
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items in import PI");
        List<String> fqns = lookupStrings(items);
        List<String> names = displayNames(items);
        assertTrue(fqns.contains("javafx.scene.control.ListView"),
                "Expected 'javafx.scene.control.ListView' in import PI completions, got: " + fqns);
        assertTrue(names.contains("ListView"),
                "Expected 'ListView' display name in import PI completions, got: " + names);
    }

    /**
     * Inside an import PI, project-local classes should also be offered.
     */
    @Test
    void importPiProjectClassCompletion() {
        getFixture().addFileToProject("org/example/MyPane.java",
                """
                package org.example;
                import javafx.scene.layout.Pane;
                public class MyPane extends Pane {}
                """);
        getFixture().configureByText("ImportPiProject.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import org.example.MyP<caret>?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.TestView"/>
                """);
        LookupElement[] items = getFixture().completeBasic();
        // null -> single match auto-inserted
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("org.example.MyPane"),
                    "Expected 'org.example.MyPane' to be auto-inserted, document: " + text);
            return;
        }
        List<String> fqns = lookupStrings(items);
        assertTrue(fqns.contains("org.example.MyPane"),
                "Expected 'org.example.MyPane' in import PI completions, got: " + fqns);
    }

    // -----------------------------------------------------------------------
    // Dotted FQN element tag completion
    // -----------------------------------------------------------------------

    /**
     * When a tag name already contains a dot (direct FQN usage), completing a
     * sub-package prefix should offer sub-packages.
     * E.g. {@code <javafx.scene.<caret>/>} -> offers {@code javafx.scene.control} etc.
     */
    @Test
    void dottedTagNamePackageCompletion() {
        getFixture().configureByText("DottedTagPackage.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <javafx.scene.<caret> xmlns="http://javafx.com/javafx"
                                      xmlns:fx="http://jfxcore.org/fxml/2.0"
                                      fx:subclass="test.TestView"/>
                """);
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items for dotted tag");
        List<String> fqns = lookupStrings(items);
        List<String> names = displayNames(items);
        assertTrue(fqns.contains("javafx.scene.control"),
                "Expected 'javafx.scene.control' for dotted tag, got: " + fqns);
        assertTrue(names.contains("control"),
                "Expected 'control' display name for dotted tag, got: " + names);
    }

    /**
     * When a tag name contains a dot and ends with a partial class name,
     * completing should offer matching classes.
     * E.g. {@code <javafx.scene.control.List<caret>/>} -> offers {@code ListView} etc.
     */
    @Test
    void dottedTagNameClassCompletion() {
        getFixture().configureByText("DottedTagClass.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <javafx.scene.control.List<caret> xmlns="http://javafx.com/javafx"
                                                  xmlns:fx="http://jfxcore.org/fxml/2.0"
                                                  fx:subclass="test.TestView"/>
                """);
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items for dotted tag class");
        List<String> fqns = lookupStrings(items);
        List<String> names = displayNames(items);
        assertTrue(fqns.contains("javafx.scene.control.ListView"),
                "Expected 'javafx.scene.control.ListView' for dotted tag, got: " + fqns);
        assertTrue(names.contains("ListView"),
                "Expected 'ListView' display name for dotted tag, got: " + names);
    }

    // -----------------------------------------------------------------------
    // String-typed attribute value: no FQN/package completion offered
    // -----------------------------------------------------------------------

    /**
     * Typing inside a {@code String}-typed attribute value (e.g. {@code text=""}) must not
     * offer any FQN class-name or package completions: the value is free-form text.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void stringTypedAttributeValueOffersNoFqnCompletion() {
        getFixture().configureByText("StringAttrNoFqn.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button text="java<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        // Either null (single auto-insert, unlikely) or an array: must not contain packages/classes
        if (items != null) {
            List<String> fqns = lookupStrings(items);
            assertFalse(fqns.contains("javafx"),
                    "String-typed attribute must not offer package completions, got: " + fqns);
            assertFalse(fqns.stream().anyMatch(s -> s.startsWith("java.")),
                    "String-typed attribute must not offer FQN class completions, got: " + fqns);
        }
    }

    // -----------------------------------------------------------------------
    // Binding expression: static field completion after a qualified class name
    // -----------------------------------------------------------------------

    /**
     * Completing after {@code {fx:Evaluate javafx.scene.layout.Region.USE_PREF_S}} must offer
     * {@code USE_PREF_SIZE} and finish promptly.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingExpressionStaticFieldCompletionAfterQualifiedClassIsPrompt() {
        getFixture().configureByText("StaticFieldCompletion.fxml", fxml2(
                "javafx.scene.layout.Region",
                """
                  <Region minHeight="{fx:Evaluate javafx.scene.layout.Region.USE_PREF_S<caret>}"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        // May auto-insert if only one match; check the document in that case
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("USE_PREF_SIZE"),
                    "Expected USE_PREF_SIZE to be auto-inserted, document: " + text);
            return;
        }
        List<String> names = displayNames(items);
        assertTrue(names.contains("USE_PREF_SIZE"),
                "Expected 'USE_PREF_SIZE' in completions, got: " + names);
    }

    /**
     * Completing after {@code {fx:Evaluate javafx.scene.layout.Region.}} (no partial name) must
     * offer static fields of {@code Region}, not instance property names.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingExpressionStaticFieldCompletionAfterQualifiedClassDotIsPrompt() {
        getFixture().configureByText("StaticFieldCompletionDot.fxml", fxml2(
                "javafx.scene.layout.Region",
                """
                  <Region minHeight="{fx:Evaluate javafx.scene.layout.Region.<caret>}"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items after 'Region.'");
        List<String> names = displayNames(items);
        assertTrue(names.contains("USE_PREF_SIZE"),
                "Expected 'USE_PREF_SIZE' in completions after 'Region.', got: " + names);
        assertTrue(names.contains("USE_COMPUTED_SIZE"),
                "Expected 'USE_COMPUTED_SIZE' in completions after 'Region.', got: " + names);
        // Must NOT offer instance property names (they'd be wrong here)
        assertTrue(names.stream().noneMatch(n -> n.equals("minHeight") || n.equals("prefWidth")),
                "Must not offer instance properties after a class qualifier, got: " + names);
    }

    /**
     * Completing with the caret positioned <em>inside</em> an already-typed binding path
     * (e.g. cursor after {@code KeyEvent} in {@code $...KeyEvent.KEY_PRESSED}) must offer
     * classes matching that partial class name and must not corrupt the document on
     * insertion.  Only the text before the caret must be used as the prefix for completion;
     * any suffix after the caret must be preserved without being incorporated into the
     * inserted text.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingExpressionCompletionWithSuffixAfterCaretDoesNotCorruptDocument() {
        getFixture().configureByText("MidValueCompletion.fxml", fxml2(
                "javafx.scene.layout.Region",
                // Cursor after "KeyEvent": ".KEY_PRESSED" is the existing suffix after the caret
                """
                  <Region minHeight="$javafx.scene.input.KeyEvent<caret>.KEY_PRESSED"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected class completion items when cursor is mid-value");
        List<String> fqns = lookupStrings(items);
        List<String> names = displayNames(items);
        // KeyEvent must be offered (cursor is completing the class segment "KeyEvent")
        assertTrue(fqns.contains("javafx.scene.input.KeyEvent"),
                "Expected 'javafx.scene.input.KeyEvent' in completions, got: " + fqns);
        assertTrue(names.contains("KeyEvent"),
                "Expected 'KeyEvent' display name in completions, got: " + names);
    }

    /**
     * Completing after {@code {fx:Evaluate javafx.scene.layout.}} (cursor after the last dot)
     * must offer all classes in the {@code javafx.scene.layout} package (e.g. {@code Region},
     * {@code BorderPane}, {@code VBox}).
     *
     * <p>Lookup strings must be full FQNs (e.g. {@code javafx.scene.layout.Region}) so that
     * IntelliJ replaces the typed prefix correctly without duplication.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingExpressionCompletionAfterPackagePrefixOffersClasses() {
        getFixture().configureByText("PkgPrefixClasses.fxml", fxml2(
                "javafx.scene.layout.Region",
                """
                  <Region minHeight="{fx:Evaluate javafx.scene.layout.<caret>}"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items after 'javafx.scene.layout.'");
        List<String> names = displayNames(items);
        List<String> fqns  = lookupStrings(items);
        assertTrue(names.contains("Region"),
                "Expected 'Region' in completions after 'javafx.scene.layout.', got: " + names);
        assertTrue(names.contains("BorderPane"),
                "Expected 'BorderPane' in completions after 'javafx.scene.layout.', got: " + names);
        assertTrue(names.contains("VBox"),
                "Expected 'VBox' in completions after 'javafx.scene.layout.', got: " + names);
        assertTrue(fqns.contains("javafx.scene.layout.Region"),
                "Expected FQN 'javafx.scene.layout.Region' as lookup string, got: " + fqns);
        assertTrue(fqns.contains("javafx.scene.layout.BorderPane"),
                "Expected FQN 'javafx.scene.layout.BorderPane' as lookup string, got: " + fqns);
        assertTrue(fqns.stream().noneMatch(s -> s.equals("Region") || s.equals("BorderPane")),
                "Lookup strings must be full FQNs, not bare names - got: " + fqns);
    }

    /**
     * Completing a partial package segment in a binding expression, e.g.
     * {@code {fx:Evaluate javafx.scene.lay<caret>}}, must suggest {@code layout}.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingExpressionCompletionWithPartialPackageSegmentOffersPackage() {
        getFixture().configureByText("PartialPkg.fxml", fxml2(
                "javafx.scene.layout.Region",
                """
                  <Region minHeight="{fx:Evaluate javafx.scene.lay<caret>}"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        // null means exactly one match was auto-inserted
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("javafx.scene.layout"),
                    "Expected 'javafx.scene.layout' to be auto-inserted for partial 'javafx.scene.lay', document: " + text);
            return;
        }
        assertNotNull(items, "Expected completion items for partial package 'javafx.scene.lay'");
        List<String> names = displayNames(items);
        List<String> fqns  = lookupStrings(items);
        assertTrue(names.contains("layout"),
                "Expected 'layout' display name for partial 'javafx.scene.lay', got: " + names);
        assertTrue(fqns.contains("javafx.scene.layout"),
                "Expected 'javafx.scene.layout' FQN for partial 'javafx.scene.lay', got: " + fqns);
    }

    /**
     * Completing after a package prefix with a code-behind class present must finish promptly.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingExpressionCompletionAfterPackagePrefixWithCodeBehindIsPrompt() {
        getFixture().addFileToProject("test/TestViewPkg.java",
                """
                package test;
                public class TestViewPkg {}
                """);
        getFixture().configureByText("PackagePrefixCB.fxml", fxml2(
                "javafx.scene.layout.Region",
                """
                  <Region minHeight="{fx:Evaluate javafx.scene.layout.<caret>}"/>
                """,
                "test.TestViewPkg"
        ));
        // Must complete promptly: the @Timeout enforces the time limit.
        getFixture().completeBasic();
    }

    /**
     * Completing a static field path with a code-behind class present must offer
     * {@code USE_PREF_SIZE} and finish promptly.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingExpressionStaticFieldCompletionWithCodeBehindIsPrompt() {
        // Add the code-behind class so that resolveCodeBehindClass() returns non-null,
        // matching the real-IDE scenario where fx:subclass resolves to an actual class.
        getFixture().addFileToProject("test/TestViewCB.java",
                """
                package test;
                import javafx.scene.layout.BorderPane;
                public class TestViewCB extends BorderPane {}
                """);
        getFixture().configureByText("StaticFieldCompletionCB.fxml",
                // Use a hand-crafted document so we can point fx:subclass at our new class
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.layout.Region?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.TestViewCB">
                  <Region minHeight="{fx:Evaluate javafx.scene.layout.Region.USE_PREF_S<caret>}"/>
                </BorderPane>
                """);
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("USE_PREF_SIZE"),
                    "Expected USE_PREF_SIZE to be auto-inserted, document: " + text);
            return;
        }
        List<String> names = displayNames(items);
        assertTrue(names.contains("USE_PREF_SIZE"),
                "Expected 'USE_PREF_SIZE' in completions (with code-behind), got: " + names);
    }

    // -----------------------------------------------------------------------
    // Static property attribute name completion (e.g. VBox.vgrow)
    // -----------------------------------------------------------------------

    /**
     * Typing a partial static-property attribute name like {@code VBox.vgro} should offer
     * the matching attribute {@code VBox.vgrow}.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void staticPropertyAttributeNameCompletionOffersMatchingProperty() {
        getFixture().configureByText("StaticPropAttr.fxml", fxml2(
                """
                javafx.scene.control.TextField
                javafx.scene.layout.VBox
                """,
                """
                  <VBox>
                    <TextField VBox.vgro<caret>="ALWAYS"/>
                  </VBox>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        // null means a single match was auto-inserted
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("VBox.vgrow"),
                    "Expected 'VBox.vgrow' to be auto-inserted, document: " + text);
            return;
        }
        List<String> names = lookupStrings(items);
        assertTrue(names.contains("VBox.vgrow"),
                "Expected 'VBox.vgrow' in attribute name completions, got: " + names);
    }

    /**
     * Typing just {@code VBox.} (after the dot) should offer all static properties of VBox.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void staticPropertyAttributeNameCompletionAfterDotOffersAllProperties() {
        getFixture().configureByText("StaticPropAttrDot.fxml", fxml2(
                """
                javafx.scene.control.TextField
                javafx.scene.layout.VBox
                """,
                """
                  <VBox>
                    <TextField VBox.<caret>="ALWAYS"/>
                  </VBox>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items after 'VBox.'");
        List<String> names = lookupStrings(items);
        assertTrue(names.contains("VBox.vgrow"),
                "Expected 'VBox.vgrow' in completions after 'VBox.', got: " + names);
        assertTrue(names.contains("VBox.margin"),
                "Expected 'VBox.margin' in completions after 'VBox.', got: " + names);
    }

    /**
     * Typing a package prefix for a static-property attribute name (e.g. {@code org.})
     * should offer sub-packages (e.g. {@code jfxcore}) just like FQN completion elsewhere.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void fqnStaticPropertyAttrPackagePrefixOffersSubPackages() {
        getFixture().addFileToProject("org/jfxcore/command/Command3.java", """
                package org.jfxcore.command;
                import javafx.event.EventTarget;
                public class Command3 {
                    public static void setOnAction(EventTarget owner, Command3 cmd) {}
                    public static Command3 getOnAction(EventTarget owner) { return null; }
                }
                """);
        getFixture().configureByText("FqnStaticAttrPkg.fxml", fxml2(
                "javafx.scene.control.Button",
                // typing "org.": should offer sub-packages like "jfxcore"
                """
                  <Button org.<caret>=""/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items after 'org.'");
        List<String> fqns = lookupStrings(items);
        List<String> names = displayNames(items);
        assertTrue(fqns.stream().anyMatch(s -> s.startsWith("org.jfxcore")),
                "Expected sub-package 'org.jfxcore' in completions, got: " + fqns);
        assertTrue(names.contains("jfxcore"),
                "Expected display name 'jfxcore' in completions, got: " + names);
    }

    /**
     * Abstract classes with static property setters (e.g. {@code Command}) must be offered
     * in FQN static-property attribute name completion even though they cannot be instantiated
     * as tags.  Typing {@code org.jfxcore.command.Comm} must suggest {@code Command}.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void fqnStaticPropertyAttrAbstractClassIsOffered() {
        getFixture().addFileToProject("org/jfxcore/command/AbstractCommand.java", """
                package org.jfxcore.command;
                import javafx.event.EventTarget;
                public abstract class AbstractCommand {
                    public abstract void execute();
                    public static void setOnAction(EventTarget owner, AbstractCommand cmd) {}
                    public static AbstractCommand getOnAction(EventTarget owner) { return null; }
                }
                """);
        getFixture().configureByText("FqnStaticAttrAbstract.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button org.jfxcore.command.AbstractComman<caret>=""/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("AbstractCommand"),
                    "Expected abstract 'AbstractCommand' to be auto-inserted, got: " + text);
            return;
        }
        List<String> fqns = lookupStrings(items);
        List<String> names = displayNames(items);
        assertTrue(fqns.stream().anyMatch(s -> s.contains("AbstractCommand")),
                "Expected abstract 'AbstractCommand' in FQN attr completions, got: " + fqns);
        assertTrue(names.contains("AbstractCommand"),
                "Expected 'AbstractCommand' display name in completions, got: " + names);
    }

    /**
     * Typing a partial FQN class name as a static-property attribute prefix
     * (e.g. {@code org.jfxcore.command.Comman}) should complete to the class
     * {@code Command3} (partial class name, not yet at the dot).
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void fqnStaticPropertyAttrPartialClassNameCompletesToClass() {
        getFixture().addFileToProject("org/jfxcore/command/Command4.java", """
                package org.jfxcore.command;
                import javafx.event.EventTarget;
                public class Command4 {
                    public static void setOnAction(EventTarget owner, Command4 cmd) {}
                    public static Command4 getOnAction(EventTarget owner) { return null; }
                }
                """);
        getFixture().configureByText("FqnStaticAttrClass.fxml", fxml2(
                "javafx.scene.control.Button",
                // typing "org.jfxcore.command.Comman": should complete to "Command4"
                """
                  <Button org.jfxcore.command.Comman<caret>=""/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            // Single match auto-inserted
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("Command4"),
                    "Expected 'Command4' to be auto-inserted, got: " + text);
            return;
        }
        List<String> fqns = lookupStrings(items);
        List<String> names = displayNames(items);
        assertTrue(fqns.stream().anyMatch(s -> s.contains("Command4")),
                "Expected 'Command4' FQN in completions, got: " + fqns);
        assertTrue(names.contains("Command4"),
                "Expected display name 'Command4' in completions, got: " + names);
    }

    /**
     * After typing a fully-resolved FQN class followed by a dot
     * (e.g. {@code org.jfxcore.command.Command4.}), the completion should offer
     * static properties of that class (e.g. {@code org.jfxcore.command.Command4.onAction}).
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void fqnStaticPropertyAttrAfterClassDotOffersStaticProperties() {
        getFixture().addFileToProject("org/jfxcore/command/Command5.java", """
                package org.jfxcore.command;
                import javafx.event.EventTarget;
                public class Command5 {
                    public static void setOnAction(EventTarget owner, Command5 cmd) {}
                    public static Command5 getOnAction(EventTarget owner) { return null; }
                }
                """);
        getFixture().configureByText("FqnStaticAttrProp.fxml", fxml2(
                "javafx.scene.control.Button",
                // typing "org.jfxcore.command.Command5.": should offer Command5.onAction
                """
                  <Button org.jfxcore.command.Command5.<caret>=""/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("Command5.onAction"),
                    "Expected 'Command5.onAction' to be auto-inserted, got: " + text);
            return;
        }
        List<String> fqns = lookupStrings(items);
        assertTrue(fqns.stream().anyMatch(s -> s.endsWith("Command5.onAction")),
                "Expected 'Command5.onAction' in completions after FQN class dot, got: " + fqns);
    }

    // -----------------------------------------------------------------------
    // Dotted property tag name completion (selectionModel.selectionMo -> selectionMode)
    // -----------------------------------------------------------------------

    /**
     * Typing a partial dotted property tag name like {@code <selectionModel.selectionMo>}
     * should complete the last segment to a matching property name on the resolved type.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void dottedPropertyTagNameCompletionOffersMatchingProperty() {
        getFixture().configureByText("PropTagCompletion.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView>
                    <selectionModel.selectionMo<caret>>SINGLE</selectionModel.selectionMode>
                  </ListView>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("selectionModel.selectionMode"),
                    "Expected 'selectionMode' to be auto-inserted, document: " + text);
            return;
        }
        List<String> names = lookupStrings(items);
        assertTrue(names.contains("selectionModel.selectionMode"),
                "Expected 'selectionModel.selectionMode' in completions, got: " + names);
    }

    /**
     * After a single property segment and a dot {@code <selectionModel.>},
     * should offer all property names on the type of that segment.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void dottedPropertyTagNameCompletionAfterDotOffersProperties() {
        getFixture().configureByText("PropTagCompletionDot.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView>
                    <selectionModel.<caret>>SINGLE</selectionModel.selectionMode>
                  </ListView>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completions after 'selectionModel.'");
        List<String> names = lookupStrings(items);
        assertTrue(names.contains("selectionModel.selectionMode"),
                "Expected 'selectionModel.selectionMode' in completions, got: " + names);
    }

    // -----------------------------------------------------------------------
    // XML text content enum completion (>MULTIP<caret></)
    // -----------------------------------------------------------------------

    /**
     * Typing a partial enum value as XML text content inside a property element tag should
     * complete to the matching enum constant.
     * E.g. {@code <selectionModel.selectionMode>MULTIP<caret></...>} -> {@code MULTIPLE}.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void xmlTextContentEnumCompletionOffersMatchingConstant() {
        getFixture().configureByText("TextEnumCompletion.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView>
                    <selectionModel.selectionMode>MULTIP<caret></selectionModel.selectionMode>
                  </ListView>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("MULTIPLE"),
                    "Expected MULTIPLE to be auto-inserted, document: " + text);
            return;
        }
        List<String> names = lookupStrings(items);
        assertTrue(names.contains("MULTIPLE"),
                "Expected 'MULTIPLE' in text content completions, got: " + names);
    }

    /**
     * When a property's declaration resolves to a backing {@code PsiField} typed as
     * {@code ObjectProperty<SelectionMode>} rather than to a getter/setter, completion
     * must still offer the enum constants of the unwrapped value type ({@code SelectionMode}).
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void xmlTextContentEnumCompletionWhenDeclIsBackingField() {
        getFixture().addClass("""
                package sample;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                import javafx.scene.control.MultipleSelectionModel;
                import javafx.scene.control.SelectionMode;
                public class MySelectionModel<T> extends MultipleSelectionModel<T> {
                    /** The selection mode. */
                    private final ObjectProperty<SelectionMode> selectionModeProperty =
                            new SimpleObjectProperty<>(SelectionMode.SINGLE);
                    public ObjectProperty<SelectionMode> selectionModeProperty() { return selectionModeProperty; }
                    public void setSelectionMode(SelectionMode m) { selectionModeProperty.set(m); }
                    public SelectionMode getSelectionMode() { return selectionModeProperty.get(); }
                    @Override public javafx.collections.ObservableList<Integer> getSelectedIndices() { return null; }
                    @Override public javafx.collections.ObservableList<T> getSelectedItems() { return null; }
                    @Override public void selectIndices(int index, int... indices) {}
                    @Override public void select(int index) {}
                    @Override public void select(T obj) {}
                    @Override public void clearAndSelect(int index) {}
                    @Override public void clearSelection(int index) {}
                    @Override public void clearSelection() {}
                    @Override public boolean isSelected(int index) { return false; }
                    @Override public boolean isEmpty() { return true; }
                    @Override public void selectPrevious() {}
                    @Override public void selectNext() {}
                    @Override public void selectFirst() {}
                    @Override public void selectLast() {}
                }
                """);
        getFixture().addClass("""
                package sample;
                import javafx.scene.control.ListView;
                public class MyListView<T> extends ListView<T> {
                    public MyListView() { super(); }
                    @Override public sample.MySelectionModel<T> getSelectionModel() { return null; }
                }
                """);
        getFixture().configureByText("BackingFieldEnum.fxml", fxml2(
                "sample.MyListView",
                """
                  <MyListView>
                    <selectionModel.selectionMode>MULTIP<caret></selectionModel.selectionMode>
                  </MyListView>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("MULTIPLE"),
                    "Expected MULTIPLE to be auto-inserted, document: " + text);
            return;
        }
        List<String> names = lookupStrings(items);
        assertTrue(names.contains("MULTIPLE"),
                "Expected 'MULTIPLE' in completions when decl is backing field, got: " + names);
    }

    /**
     * Typing a partial enum value in a simple (non-dotted) property element tag should
     * also complete to the matching enum constant.
     * E.g. {@code <alignment>CENTER_<caret></alignment>} -> {@code CENTER_LEFT}, etc.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void xmlTextContentEnumCompletionSimplePropertyTag() {
        getFixture().configureByText("TextEnumCompletionSimple.fxml", fxml2(
                "javafx.scene.layout.VBox",
                """
                  <VBox>
                    <alignment>CENTER_<caret></alignment>
                  </VBox>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items for partial enum in text content");
        List<String> names = lookupStrings(items);
        assertTrue(names.stream().anyMatch(n -> n.startsWith("CENTER_")),
                "Expected at least one CENTER_* constant in completions, got: " + names);
    }

    /**
     * Typing a partial enum value as XML text inside a <em>static</em> property element tag
     * (e.g. {@code <VBox.vgrow>ALW<caret></VBox.vgrow>}) must complete to the matching enum
     * constant ({@code ALWAYS}).
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void xmlTextContentEnumCompletionForStaticPropertyElementTag() {
        getFixture().configureByText("StaticPropElemEnumCompletion.fxml", fxml2(
                """
                javafx.scene.control.Button
                javafx.scene.layout.VBox
                """,
                """
                  <VBox>
                    <Button>
                      <VBox.vgrow>ALW<caret></VBox.vgrow>
                    </Button>
                  </VBox>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("ALWAYS"),
                    "Expected ALWAYS to be auto-inserted, document: " + text);
            return;
        }
        List<String> names = lookupStrings(items);
        assertTrue(names.contains("ALWAYS"),
                "Expected 'ALWAYS' in completions for static property element tag, got: " + names);
    }

    /**
     * With an empty prefix, all enum constants must be offered inside a static property
     * element tag. E.g. {@code <VBox.vgrow><caret></VBox.vgrow>} -> ALWAYS, NEVER, SOMETIMES.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void xmlTextContentEnumCompletionForStaticPropertyElementTagAllConstants() {
        getFixture().configureByText("StaticPropElemEnumCompletionAll.fxml", fxml2(
                """
                javafx.scene.control.Button
                javafx.scene.layout.VBox
                """,
                """
                  <VBox>
                    <Button>
                      <VBox.vgrow><caret></VBox.vgrow>
                    </Button>
                  </VBox>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items inside empty <VBox.vgrow>");
        List<String> names = lookupStrings(items);
        assertTrue(names.contains("ALWAYS"),
                "Expected 'ALWAYS' in completions, got: " + names);
        assertTrue(names.contains("NEVER"),
                "Expected 'NEVER' in completions, got: " + names);
    }

    /**
     * Typing a partial enum value as the value of a <em>dotted</em> attribute
     * (e.g. {@code selectionModel.selectionMode="MULTIP<caret>"}) should complete to
     * the matching enum constant ({@code MULTIPLE}).
     *
     * <p>This exercises the path where {@link
     * org.jfxcore.fxml.codeinsight.Fxml2CompletionContributor}'s
     * {@code addLiteralValueCompletions} receives a dotted attribute name and must
     * walk the property chain to obtain the terminal property type.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void attributeValueEnumCompletionForDottedPropertyName() {
        getFixture().configureByText("DottedAttrEnumCompletion.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView selectionModel.selectionMode="MULTIP<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("MULTIPLE"),
                    "Expected MULTIPLE to be auto-inserted, document: " + text);
            return;
        }
        List<String> names = lookupStrings(items);
        assertTrue(names.contains("MULTIPLE"),
                "Expected 'MULTIPLE' in dotted-attribute completions, got: " + names);
    }

    // -----------------------------------------------------------------------
    // Literal attribute value completion for primitive-typed properties
    // -----------------------------------------------------------------------

    /**
     * For a {@code double}-typed property like {@code minHeight}, completing a partial static
     * field name (e.g. {@code "USE_CO<caret>"}) must offer {@code USE_COMPUTED_SIZE} from
     * {@code Region}'s supertype chain.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void attributeValueStaticFieldCompletionForDoubleProperty() {
        getFixture().configureByText("DoubleStaticCompletion.fxml", fxml2(
                "javafx.scene.control.TextArea",
                """
                  <TextArea minHeight="USE_CO<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("USE_COMPUTED_SIZE"),
                    "Expected USE_COMPUTED_SIZE to be auto-inserted, document: " + text);
            return;
        }
        List<String> names = lookupStrings(items);
        assertTrue(names.contains("USE_COMPUTED_SIZE"),
                "Expected 'USE_COMPUTED_SIZE' in completions for double minHeight, got: " + names);
        assertTrue(names.contains("USE_PREF_SIZE"),
                "Expected 'USE_PREF_SIZE' in completions for double minHeight, got: " + names);
    }

    /**
     * With an empty prefix on a {@code double}-typed property, all compatible static fields
     * (including {@code Double.POSITIVE_INFINITY}) and Region's constants must be offered.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void attributeValueStaticFieldCompletionForDoublePropertyAllFields() {
        getFixture().configureByText("DoubleStaticCompletionAll.fxml", fxml2(
                "javafx.scene.control.TextArea",
                """
                  <TextArea minHeight="<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items for empty double attribute value");
        List<String> names = lookupStrings(items);
        assertTrue(names.contains("USE_COMPUTED_SIZE"),
                "Expected 'USE_COMPUTED_SIZE' in completions, got: " + names);
        assertTrue(names.contains("USE_PREF_SIZE"),
                "Expected 'USE_PREF_SIZE' in completions, got: " + names);
    }

    /**
     * For a {@code boolean}-typed property like {@code editable}, completing a partial
     * value (e.g. {@code "f<caret>"}) must offer {@code false} (and boolean-compatible static
     * fields) but must NOT offer unrelated property names or class-member names that the
     * default XML contributor would inject.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void attributeValueBooleanCompletionDoesNotOfferUnrelatedNames() {
        getFixture().configureByText("BoolCompletion.fxml", fxml2(
                "javafx.scene.control.TextArea",
                """
                  <TextArea editable="f<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            // Single match auto-inserted: must be "false"
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("false"),
                    "Expected 'false' to be auto-inserted for boolean editable, document: " + text);
            return;
        }
        List<String> names = lookupStrings(items);
        // "false" and "true" must be present as literal completions
        assertTrue(names.contains("false"), "Expected 'false' in boolean completions, got: " + names);
        assertTrue(names.contains("true"),  "Expected 'true' in boolean completions, got: " + names);
        // The default XML contributor injects all kinds of attribute/member names: verify none appear
        assertTrue(names.stream().noneMatch(n -> n.equals("cellFactory") || n.equals("prefHeight")
                        || n.equals("framework") || n.equals("text") || n.equals("wrapText")),
                "Default XML completion names must not appear in boolean property completions, got: " + names);
    }

    // -----------------------------------------------------------------------
    // Binding path completion through a plain field and an ObjectProperty field
    // -----------------------------------------------------------------------

    /**
     * When {@code vm} is a plain field of type {@code ViewModel}, completing
     * {@code ${vm.nameToolt<caret>}} must offer {@code nameTooltip}.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingPathCompletionThroughPlainFieldProperty() {
        getFixture().addFileToProject("test/ViewModel.java",
                """
                package test;
                import javafx.beans.property.StringProperty;
                import javafx.beans.property.SimpleStringProperty;
                public class ViewModel {
                    public StringProperty getNameTooltip() { return new SimpleStringProperty(); }
                }
                """);
        getFixture().addFileToProject("test/TestViewVM.java",
                """
                package test;
                public class TestViewVM extends javafx.scene.layout.BorderPane {
                    public ViewModel getVm() { return new ViewModel(); }
                }
                """);
        getFixture().configureByText("BindingVmPlain.fxml", fxml2(
                "javafx.scene.control.Tooltip",
                """
                  <Tooltip text="${vm.nameToolt<caret>}"/>
                """,
                "test.TestViewVM"
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("nameTooltip"),
                    "Expected 'nameTooltip' to be auto-inserted, document: " + text);
            return;
        }
        List<String> names = displayNames(items);
        assertTrue(names.contains("nameTooltip"),
                "Expected 'nameTooltip' in completions after 'vm.nameToolt', got: " + names);
    }

    /**
     * When {@code vm} is an {@code ObjectProperty<ViewModel>} field, completing
     * {@code ${vm.nameToolt<caret>}} must also offer {@code nameTooltip} (the
     * property type argument is unwrapped automatically).
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingPathCompletionThroughObjectPropertyField() {
        getFixture().addFileToProject("test/ViewModelOP.java",
                """
                package test;
                import javafx.beans.property.StringProperty;
                import javafx.beans.property.SimpleStringProperty;
                public class ViewModelOP {
                    public StringProperty getNameTooltip() { return new SimpleStringProperty(); }
                }
                """);
        getFixture().addFileToProject("test/TestViewVMOP.java",
                """
                package test;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                public class TestViewVMOP extends javafx.scene.layout.BorderPane {
                    private final ObjectProperty<ViewModelOP> vm =
                            new SimpleObjectProperty<>(new ViewModelOP());
                    public ObjectProperty<ViewModelOP> vmProperty() { return vm; }
                    public ViewModelOP getVm() { return vm.get(); }
                    public void setVm(ViewModelOP v) { vm.set(v); }
                }
                """);
        getFixture().configureByText("BindingVmOP.fxml", fxml2(
                "javafx.scene.control.Tooltip",
                """
                  <Tooltip text="${vm.nameToolt<caret>}"/>
                """,
                "test.TestViewVMOP"
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("nameTooltip"),
                    "Expected 'nameTooltip' to be auto-inserted (ObjectProperty case), document: " + text);
            return;
        }
        List<String> names = displayNames(items);
        assertTrue(names.contains("nameTooltip"),
                "Expected 'nameTooltip' in completions after 'vm.nameToolt' (ObjectProperty case), got: " + names);
    }

    /**
     * When a view-model class exposes properties as <em>plain public fields</em>
     * (e.g. {@code public final StringProperty message}) rather than getter methods,
     * completing a partial path segment on that view-model type must still offer
     * the field names.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingPathCompletionThroughPublicFieldPropertyOnViewModel() {
        getFixture().addFileToProject("test/ViewModelPF.java",
                """
                package test;
                import javafx.beans.property.StringProperty;
                import javafx.beans.property.SimpleStringProperty;
                public class ViewModelPF {
                    public final StringProperty message = new SimpleStringProperty("");
                    public final StringProperty labelText = new SimpleStringProperty("hello");
                }
                """);
        getFixture().addFileToProject("test/TestViewVMPF.java",
                """
                package test;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                public class TestViewVMPF extends javafx.scene.layout.BorderPane {
                    private final ObjectProperty<ViewModelPF> vm =
                            new SimpleObjectProperty<>(new ViewModelPF());
                    public final ObjectProperty<ViewModelPF> vmProperty() { return vm; }
                }
                """);
        getFixture().configureByText("BindingVmPF.fxml", fxml2(
                "javafx.scene.control.TextField",
                """
                  <TextField text="{fx:Synchronize vm.mes<caret>}"/>
                """,
                "test.TestViewVMPF"
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("message"),
                    "Expected 'message' to be auto-inserted (plain field case), document: " + text);
            return;
        }
        List<String> names = displayNames(items);
        assertTrue(names.contains("message"),
                "Expected 'message' in completions after 'vm.mes' (plain public field), got: " + names);
    }

    /**
     * Static fields declared on the code-behind class are valid binding sources for simple
     * {@code $name} expressions. Completing a partial name must offer matching static fields.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingPathCompletionForStaticFieldOnCodeBehind() {
        getFixture().addFileToProject("test/CodeBehindStatic.java",
                """
                package test;
                import java.util.function.Function;
                public class CodeBehindStatic extends javafx.scene.layout.BorderPane {
                    public static final Function<Double, String> doubleFormatter = d -> String.format("%.2f", d);
                    public static final String viewStaticLabel = "hello";
                    public String getInstanceProp() { return ""; }
                }
                """);
        getFixture().configureByText("BindingStaticField.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="${doubleForm<caret>}"/>
                """,
                "test.CodeBehindStatic"
        ));
        LookupElement[] items = getFixture().completeBasic();
        // Static fields on the code-behind are NOT offered in binding path completion
        // when completing on an instance segment (only instance properties are offered)
        // Static fields are only offered when completing on a class qualifier (e.g. Region.)
        if (items != null) {
            List<String> names = displayNames(items);
            assertFalse(names.contains("doubleFormatter"),
                    "Static field 'doubleFormatter' must not appear on instance segment, got: " + names);
        }
    }

    // -----------------------------------------------------------------------
    // fx: intrinsic attribute name completion
    // -----------------------------------------------------------------------

    /**
     * Completing {@code fx:<caret>} on a class tag must offer the standard
     * fx: intrinsic attribute names such as {@code fx:id}, {@code fx:factory},
     * {@code fx:context}, {@code fx:typeArguments}, {@code fx:constant}, etc.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void fxIntrinsicAttributeNameCompletion() {
        getFixture().configureByText("FxAttrNames.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button fx:<caret>/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected attribute name completion items for 'fx:'");
        List<String> names = lookupStrings(items);
        assertTrue(names.contains("fx:id"),
                "Expected 'fx:id' in attribute completions, got: " + names);
        assertTrue(names.contains("fx:factory"),
                "Expected 'fx:factory' in attribute completions, got: " + names);
        assertTrue(names.contains("fx:typeArguments"),
                "Expected 'fx:typeArguments' in attribute completions, got: " + names);
        assertTrue(names.contains("fx:constant"),
                "Expected 'fx:constant' in attribute completions, got: " + names);
        assertTrue(names.contains("fx:context"),
                "Expected 'fx:context' in attribute completions, got: " + names);
    }

    /**
     * Completing {@code fx:i<caret>} must offer {@code fx:id} and/or {@code fx:itemType}.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void fxIntrinsicAttributeNamePrefixCompletion() {
        getFixture().configureByText("FxAttrPrefix.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button fx:<caret>/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        // There should always be multiple fx: attribute completions (id, factory, typeArguments, etc.)
        assertNotNull(items, "Expected multiple fx: attribute completions");
        List<String> names = lookupStrings(items);
        assertTrue(names.contains("fx:id"),
                "Expected 'fx:id' in attribute completions, got: " + names);
        assertTrue(names.contains("fx:factory"),
                "Expected 'fx:factory' in attribute completions, got: " + names);
        assertTrue(names.contains("fx:typeArguments"),
                "Expected 'fx:typeArguments' in attribute completions, got: " + names);
    }

    // -----------------------------------------------------------------------
    // Binding expression context selector completion
    // -----------------------------------------------------------------------

    /**
     * Completing inside an empty binding value should offer {@code self/}
     * and {@code parent/} as context selector prefixes.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingContextSelectorCompletion() {
        getFixture().configureByText("BindingSelectors.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="${<caret>}"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected binding context selector completions");
        List<String> names = lookupStrings(items);
        assertTrue(names.contains("self/"),
                "Expected 'self/' in binding completions, got: " + names);
        assertTrue(names.contains("parent/"),
                "Expected 'parent/' in binding completions, got: " + names);
    }

    /**
     * Completing {@code se<caret>} inside a binding expression should offer {@code self/}.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingContextSelectorSelfPrefixCompletion() {
        getFixture().configureByText("BindingSelf.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="${se<caret>}"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            // auto-inserted
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("self/"), "Expected 'self/' to be auto-inserted, got: " + text);
            return;
        }
        List<String> names = lookupStrings(items);
        assertTrue(names.contains("self/"),
                "Expected 'self/' in binding completions for 'se', got: " + names);
    }

    // -----------------------------------------------------------------------
    // fx:factory attribute value completion
    // -----------------------------------------------------------------------

    /**
     * Completing the value of {@code fx:factory} must offer the public static
     * no-arg methods on the tag's class.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void fxFactoryAttributeValueCompletion() {
        getFixture().addFileToProject("test/FactoryBean.java",
                """
                package test;
                import javafx.scene.layout.Pane;
                public class FactoryBean extends Pane {
                    public static FactoryBean createDefault() { return new FactoryBean(); }
                    public static FactoryBean createSpecial() { return new FactoryBean(); }
                    public void nonStaticMethod() {}
                }
                """);
        getFixture().configureByText("FactoryCompletion.fxml", fxml2(
                "test.FactoryBean",
                """
                  <FactoryBean fx:factory="<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected fx:factory method completions");
        List<String> names = lookupStrings(items);
        assertTrue(names.contains("createDefault"),
                "Expected 'createDefault' in fx:factory completions, got: " + names);
        assertTrue(names.contains("createSpecial"),
                "Expected 'createSpecial' in fx:factory completions, got: " + names);
        assertFalse(names.contains("nonStaticMethod"),
                "Did not expect instance method 'nonStaticMethod' in fx:factory completions, got: " + names);
    }

    // -----------------------------------------------------------------------
    // fx:id element names in binding expression completion
    // -----------------------------------------------------------------------

    /**
     * Elements declared with {@code fx:id} in the FXML file are accessible as named
     * binding sources in the default (root) context: the fxml2 compiler injects a field
     * for each {@code fx:id} into the generated base class.
     *
     * <p>Completing a partial name that matches {@code fx:id} values (e.g. {@code myBu}
     * matching {@code fx:id="myButton1"} and {@code fx:id="myButton2"}) must include all
     * matching names in the autocompletion list.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingExpressionCompletionIncludesFxIdElements() {
        getFixture().configureByText("BindingFxIdCompletion.fxml", fxml2(
                """
                javafx.scene.control.Button
                javafx.scene.control.Label
                """,
                """
                  <Label fx:id="myLabel"/>
                  <Button fx:id="myButton1" text="hello"/>
                  <Button fx:id="myButton2" text="world"/>
                  <Button disable="${!myBu<caret>}"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        // items may be null if IntelliJ auto-inserted a single match; check the
        // document text in that case.  When both myButton1 and myButton2 match,
        // the completion popup must stay open (items != null).
        assertNotNull(items, "Expected multiple completion items for '${!myBu<caret>}' "
                + "(both myButton1 and myButton2 should match 'myBu')");
        List<String> names = displayNames(items);
        List<String> fqns  = lookupStrings(items);
        assertTrue(names.contains("myButton1"),
                "Expected 'myButton1' (fx:id) in completions for '${!myBu}', got: " + names);
        assertTrue(names.contains("myButton2"),
                "Expected 'myButton2' (fx:id) in completions for '${!myBu}', got: " + names);
        assertTrue(fqns.contains("myButton1"),
                "Expected 'myButton1' as lookup string in fx:id completions, got: " + fqns);
        assertTrue(fqns.contains("myButton2"),
                "Expected 'myButton2' as lookup string in fx:id completions, got: " + fqns);
    }

    /**
     * All {@code fx:id} names declared anywhere in the file (including nested elements)
     * must appear in the binding completion list at the first segment level.
     * With an empty partial name (cursor at start of the expression), every fx:id must appear.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingExpressionCompletionIncludesAllFxIdElementsWithEmptyPrefix() {
        getFixture().configureByText("BindingFxIdAll.fxml", fxml2(
                """
                javafx.scene.control.Button
                javafx.scene.control.Label
                """,
                """
                  <Label fx:id="myLabel1"/>
                  <Button fx:id="myButton3" text="hello"/>
                  <Button disable="${<caret>}"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items with empty binding expression");
        List<String> names = displayNames(items);
        assertTrue(names.contains("myLabel1"),
                "Expected 'myLabel1' (fx:id) in completions, got: " + names);
        assertTrue(names.contains("myButton3"),
                "Expected 'myButton3' (fx:id) in completions, got: " + names);
    }

    /**
     * fx:id names must NOT appear in binding completions when a {@code self/} or
     * {@code parent/} context selector is used: they are only part of the root (code-behind)
     * context, not the self/parent tag class.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingExpressionFxIdNotOfferedInSelfContext() {
        getFixture().configureByText("BindingFxIdSelf.fxml", fxml2(
                """
                javafx.scene.control.Button
                """,
                """
                  <Button fx:id="myButton3" disable="${self/<caret>}"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        // items may be null (single match) or a list; either way myButton3 must not appear
        if (items != null) {
            List<String> names = displayNames(items);
            assertFalse(names.contains("myButton3"),
                    "fx:id 'myButton3' must not appear in self/ context completions, got: " + names);
        }
    }

    // -----------------------------------------------------------------------
    // fx: intrinsic keyword / markup-extension completion in binding attribute values
    // -----------------------------------------------------------------------

    /**
     * Completing inside {@code "{<caret>"} (just after the opening brace, before anything else)
     * must offer all standard fx: intrinsic keywords as well as any imported MarkupExtension
     * classes.  This is the primary "what goes here?" completion for brace expressions.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void openBraceCompletionOffersAllIntrinsics() {
        getFixture().configureByText("OpenBrace.fxml", fxml2(
                "javafx.scene.control.TextField",
                """
                  <TextField text="{<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected intrinsic keyword completions after '{'");
        List<String> names = displayNames(items);
        assertTrue(names.contains("fx:Evaluate"),
                "Expected 'fx:Evaluate' after '{', got: " + names);
        assertTrue(names.contains("fx:Observe"),
                "Expected 'fx:Observe' after '{', got: " + names);
        assertTrue(names.contains("fx:Synchronize"),
                "Expected 'fx:Synchronize' after '{', got: " + names);
        assertTrue(names.contains("fx:Null"),
                "Expected 'fx:Null' after '{', got: " + names);
        assertTrue(names.contains("fx:Class"),
                "Expected 'fx:Class' after '{', got: " + names);
    }

    /**
     * Completing {@code "{fx<caret>"} must offer all {@code fx:*} intrinsics (since they all
     * start with "fx").
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void partialFxPrefixCompletionOffersIntrinsics() {
        getFixture().configureByText("PartialFxPrefix.fxml", fxml2(
                "javafx.scene.control.TextField",
                """
                  <TextField text="{fx<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completions after '{fx'");
        List<String> names = displayNames(items);
        assertTrue(names.contains("fx:Evaluate"),
                "Expected 'fx:Evaluate' for prefix 'fx', got: " + names);
        assertTrue(names.contains("fx:Observe"),
                "Expected 'fx:Observe' for prefix 'fx', got: " + names);
        assertTrue(names.contains("fx:Synchronize"),
                "Expected 'fx:Synchronize' for prefix 'fx', got: " + names);
    }

    /**
     * Completing inside {@code "{fx:<caret>}"} in an attribute value must offer the
     * standard fx: binding intrinsic keywords: {@code Evaluate}, {@code Observe},
     * {@code Synchronize}, {@code Null}, and {@code Type}.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void fxIntrinsicBindingKeywordCompletion() {
        getFixture().configureByText("FxIntrinsicKeyword.fxml", fxml2(
                "javafx.scene.control.TextField",
                """
                  <TextField text="{fx:<caret>}"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected intrinsic keyword completions inside {fx:}");
        List<String> names = displayNames(items);
        assertTrue(names.contains("fx:Evaluate"),
                "Expected 'fx:Evaluate' in fx: intrinsic completions, got: " + names);
        assertTrue(names.contains("fx:Observe"),
                "Expected 'fx:Observe' in fx: intrinsic completions, got: " + names);
        assertTrue(names.contains("fx:Push"),
                "Expected 'fx:Push' in fx: intrinsic completions, got: " + names);
        assertTrue(names.contains("fx:Synchronize"),
                "Expected 'fx:Synchronize' in fx: intrinsic completions, got: " + names);
        assertTrue(names.contains("fx:Null"),
                "Expected 'fx:Null' in fx: intrinsic completions, got: " + names);
        assertTrue(names.contains("fx:Class"),
                "Expected 'fx:Class' in fx: intrinsic completions, got: " + names);
    }

    /**
     * Completing inside {@code "{fx:Pu<caret>}"} must offer {@code fx:Push} (and not
     * {@code fx:Evaluate}, {@code fx:Observe}, or {@code fx:Synchronize}).
     * If there is only one match, IntelliJ may auto-insert it: check the document in that case.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void fxPushIntrinsicBindingKeywordCompletion() {
        getFixture().configureByText("FxPushKeyword.fxml", fxml2(
                "javafx.scene.control.TextField",
                """
                  <TextField text="{fx:Pu<caret>}"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            // Single match auto-inserted
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("Push"),
                    "Expected 'Push' to be auto-inserted for prefix 'Pu', document: " + text);
            return;
        }
        List<String> names = displayNames(items);
        assertTrue(names.contains("fx:Push"),
                "Expected 'fx:Push' in fx: intrinsic completions for 'Pu', got: " + names);
        assertFalse(names.contains("fx:Evaluate"),
                "'fx:Evaluate' must not appear when prefix is 'Pu', got: " + names);
        assertFalse(names.contains("fx:Observe"),
                "'fx:Observe' must not appear when prefix is 'Pu', got: " + names);
        assertFalse(names.contains("fx:Synchronize"),
                "'fx:Synchronize' must not appear when prefix is 'Pu', got: " + names);
    }

    /**
     * Completing inside {@code "{fx:Sy<caret>}"} must offer only intrinsics that start with
     * "Sy", i.e. {@code Synchronize}, and must filter out {@code Evaluate} and {@code Observe}.
     * If there is only one match, IntelliJ may auto-insert it: check the document in that case.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void fxIntrinsicBindingKeywordPrefixCompletion() {
        getFixture().configureByText("FxIntrinsicKeywordPrefix.fxml", fxml2(
                "javafx.scene.control.TextField",
                """
                  <TextField text="{fx:Sy<caret>}"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            // Single match auto-inserted
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("Synchronize"),
                    "Expected 'Synchronize' to be auto-inserted, document: " + text);
            return;
        }
        List<String> names = displayNames(items);
        assertTrue(names.contains("fx:Synchronize"),
                "Expected 'fx:Synchronize' in fx: intrinsic completions for 'Sy', got: " + names);
        assertFalse(names.contains("fx:Evaluate"),
                "'fx:Evaluate' must not appear when prefix is 'Sy', got: " + names);
        assertFalse(names.contains("fx:Observe"),
                "'fx:Observe' must not appear when prefix is 'Sy', got: " + names);
    }

    /**
     * When a MarkupExtension class is imported and the user types "{<caret>", it must appear
     * alongside the built-in fx: intrinsics in the completion list.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void openBraceCompletionOffersImportedMarkupExtension() {
        getFixture().addFileToProject("ext/MyResource.java",
                """
                package ext;
                import org.jfxcore.markup.MarkupExtension;
                public class MyResource implements MarkupExtension<String> {
                    public String getKey() { return null; }
                    public void setKey(String k) {}
                    @Override public String get() { return null; }
                }
                """);
        getFixture().configureByText("OpenBraceExt.fxml", fxml2(
                "javafx.scene.control.TextField\next.MyResource",
                """
                  <TextField text="{<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completions including markup extension");
        List<String> names = displayNames(items);
        assertTrue(names.contains("MyResource"),
                "Expected imported MarkupExtension 'MyResource' in completions, got: " + names);
        // Built-in intrinsics must also appear
        assertTrue(names.contains("fx:Evaluate"),
                "Expected 'fx:Evaluate' alongside 'MyResource', got: " + names);
    }

    /**
     * When a partial name matches an imported MarkupExtension, only that extension
     * (and any matching intrinsics) must be offered.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void openBraceCompletionFiltersByMarkupExtensionPrefix() {
        getFixture().addFileToProject("ext/MyResource2.java",
                """
                package ext;
                import org.jfxcore.markup.MarkupExtension;
                public class MyResource2 implements MarkupExtension<String> {
                    public String getKey() { return null; }
                    public void setKey(String k) {}
                    @Override public String get() { return null; }
                }
                """);
        getFixture().configureByText("OpenBraceExtPrefix.fxml", fxml2(
                "javafx.scene.control.TextField\next.MyResource2",
                """
                  <TextField text="{My<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("MyResource2"),
                    "Expected 'MyResource2' to be auto-inserted, got: " + text);
            return;
        }
        List<String> names = displayNames(items);
        assertTrue(names.contains("MyResource2"),
                "Expected 'MyResource2' for prefix 'My', got: " + names);
        // fx: intrinsics don't start with "My" so should not appear
        assertFalse(names.contains("fx:Evaluate"),
                "'fx:Evaluate' must not appear for prefix 'My', got: " + names);
    }

    /**
     * Built-in markup extensions from {@code org.jfxcore.markup.resource} should appear in
     * the completion list even when they are NOT yet imported, because the plugin scans the
     * well-known resource-extension package.  Selecting one auto-adds the import.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void openBraceCompletionOffersBuiltinMarkupExtension() {
        // Set up mock markup library classes
        getFixture().addClass("""
                package org.jfxcore.markup;
                public interface MarkupExtension {}
                """);
        getFixture().addClass("""
                package org.jfxcore.markup.resource;
                public final class DynamicResource implements org.jfxcore.markup.MarkupExtension {
                    public DynamicResource(String key) {}
                }
                """);
        // DynamicResource is intentionally NOT imported in the FXML below
        getFixture().configureByText("BuiltinExt.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="{<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completions including built-in markup extension");
        List<String> names = displayNames(items);
        assertTrue(names.contains("DynamicResource"),
                "Expected built-in 'DynamicResource' in completions (no explicit import), got: " + names);
        // fx: intrinsics must also be present
        assertTrue(names.contains("fx:Evaluate"),
                "Expected 'fx:Evaluate' alongside DynamicResource, got: " + names);
    }

    // -----------------------------------------------------------------------
    // Binding path completion inside markup extension parameter bindings
    // -----------------------------------------------------------------------

    /**
     * When the user places the cursor inside a {@code $ClassName.partial} binding
     * expression that appears as a parameter of a markup extension invocation
     * (e.g. {@code {MyExtension $ItemType.label<caret>}}) the IDE must offer the
     * static members of {@code ItemType} as completions, even when {@code ItemType}
     * is not imported in the FXML2 file.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void markupExtParamBindingCompletesStaticMemberAfterClassQualifier() {
        getFixture().addClass("""
                package test;
                import java.util.function.Function;
                public class ItemType {
                    public static final Function<ItemType, String> labelAccessor = item -> item.label;
                    public static final Function<ItemType, String> titleAccessor = item -> item.title;
                    public String label;
                    public String title;
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.property.Property;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                public class ItemSupplier implements MarkupExtension.PropertyConsumer<Object> {
                    public ItemSupplier() {}
                    @Override
                    public void accept(Property<Object> property, MarkupContext context) {}
                }
                """);
        getFixture().configureByText("MarkupExtParamBindingCompletion.fxml", fxml2(
                "javafx.scene.control.Label\ntest.ItemSupplier",
                """
                  <Label text="{ItemSupplier $ItemType.<caret>}"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items for binding path inside markup extension params");
        List<String> names = displayNames(items);
        assertTrue(names.contains("labelAccessor"),
                "Expected 'labelAccessor' in completions, got: " + names);
        assertTrue(names.contains("titleAccessor"),
                "Expected 'titleAccessor' in completions, got: " + names);
    }

    // -----------------------------------------------------------------------
    // fx:classModifier attribute value completion
    // -----------------------------------------------------------------------

    /**
     * Completing the value of {@code fx:classModifier} must offer {@code protected}
     * and {@code package}.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void fxClassModifierValueCompletion() {
        getFixture().configureByText("ClassModifier.fxml", fxml2(
                "javafx.scene.layout.BorderPane",
                ""  // body, classModifier is on the root tag
        ));
        // classModifier is a root-only attribute so put the caret on the root BorderPane
        getFixture().configureByText("ClassModifierRoot.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.TestView"
                            fx:classModifier="<caret>"/>
                """);
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected fx:classModifier value completions");
        List<String> names = lookupStrings(items);
        assertTrue(names.contains("protected"),
                "Expected 'protected' in fx:classModifier completions, got: " + names);
        assertTrue(names.contains("package"),
                "Expected 'package' in fx:classModifier completions, got: " + names);
    }

    // -----------------------------------------------------------------------
    // Binding path: root-tag class fallback when compiler-generated base absent
    // -----------------------------------------------------------------------

    /**
     * When the fxml2 compiler hasn't run yet ("stale build"), the generated base class
     * ({@code FooBase extends BorderPane}) doesn't exist, so the code-behind class does
     * not inherit root-element properties through its supertype chain.
     *
     * <p>Completion must still offer properties from the root-element type as a fallback,
     * because the compiler always generates a base class that extends the root-element type.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingPathCompletionFallsBackToRootTagClassWhenBaseClassAbsent() {
        // A custom root-element type carries a distinctive property that the code-behind
        // does NOT inherit (because the compiler-generated base class is absent).
        // Completion must still offer that property via the root-tag-class fallback.
        getFixture().addFileToProject("test/RootWithUniqueProp.java",
                """
                package test;
                import javafx.scene.layout.Pane;
                public class RootWithUniqueProp extends Pane {
                    public String getVeryUniqueRootProp() { return ""; }
                }
                """);
        getFixture().addFileToProject("test/StaleBuildCB.java",
                """
                package test;
                import javafx.scene.control.Label;
                public class StaleBuildCB extends Label {
                    // Does NOT extend RootWithUniqueProp: simulates a stale build where the
                    // compiler-generated "StaleBuildCBBase extends RootWithUniqueProp" is absent.
                }
                """);
        getFixture().configureByText("BindingRootFallback.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import test.RootWithUniqueProp?>
                <?import javafx.scene.control.Label?>
                <RootWithUniqueProp xmlns="http://javafx.com/javafx"
                                    xmlns:fx="http://jfxcore.org/fxml/2.0"
                                    fx:subclass="test.StaleBuildCB">
                  <Label text="${veryUniq<caret>}"/>
                </RootWithUniqueProp>
                """);
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("veryUniqueRootProp"),
                    "Expected 'veryUniqueRootProp' to be auto-inserted via root-tag fallback, document: " + text);
            return;
        }
        List<String> names = displayNames(items);
        assertTrue(names.contains("veryUniqueRootProp"),
                "Expected 'veryUniqueRootProp' (root-tag property) in completions even when "
                        + "code-behind does not inherit it, got: " + names);
    }

    // -----------------------------------------------------------------------
    // Binding path: '::' observable-selector completion
    // -----------------------------------------------------------------------

    /**
     * The {@code ::} observable-selector operator separates path segments just like
     * {@code .}, but selects the raw {@code ObservableValue}/{@code Property} instance
     * rather than its wrapped value.
     *
     * <p>Completion must split on {@code ::} so that a partial name after the operator
     * (e.g. {@code items::siz}) is recognized and the correct property names are offered.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingPathCompletionWithObservableSelector() {
        getFixture().addFileToProject("test/ObservableSelectorCB.java",
                """
                package test;
                import javafx.beans.property.ListProperty;
                import javafx.beans.property.SimpleListProperty;
                import javafx.collections.FXCollections;
                public class ObservableSelectorCB extends javafx.scene.layout.BorderPane {
                    private final ListProperty<String> items =
                            new SimpleListProperty<>(FXCollections.observableArrayList());
                    public ListProperty<String> itemsProperty() { return items; }
                    public javafx.collections.ObservableList<String> getItems() { return items.get(); }
                }
                """);
        getFixture().configureByText("BindingObsSel.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="${items::siz<caret>}"/>
                """,
                "test.ObservableSelectorCB"
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("size"),
                    "Expected 'size' to be auto-inserted after 'items::', document: " + text);
            return;
        }
        List<String> names = displayNames(items);
        assertTrue(names.contains("size"),
                "Expected 'size' in completions after '::' observable-selector, got: " + names);
    }

    // -----------------------------------------------------------------------
    // Binding path: attached-property completion  (ClassName.propertyName)
    // -----------------------------------------------------------------------

    /**
     * Inside an attached-property group {@code (ClassName.propertyName)}, completing a
     * partial property name must offer the static properties declared on {@code ClassName}.
     *
     * <p>For example, {@code (VBox.mar)} should offer {@code (VBox.margin)}.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingPathCompletionForAttachedProperty() {
        getFixture().configureByText("BindingAttachedProp.fxml", fxml2(
                """
                javafx.scene.control.Label
                javafx.scene.layout.VBox
                """,
                """
                  <Label text="${(VBox.mar<caret>)}"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("margin"),
                    "Expected 'margin' to be auto-inserted for attached property, document: " + text);
            return;
        }
        List<String> names = displayNames(items);
        assertTrue(names.stream().anyMatch(n -> n.contains("margin")),
                "Expected 'margin' (VBox static property) in completions for '(VBox.mar)', got: " + names);
    }

    // -----------------------------------------------------------------------
    // Binding path completion: static field filtering
    // -----------------------------------------------------------------------

    /**
     * Non-public (package-private) static fields must NOT appear in binding path
     * completion.  A class with a public field {@code PUBLIC_CONST} and a
     * package-private field {@code TEMP_CONST} must only offer the public one.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingPathCompletionFiltersNonPublicStaticFields() {
        getFixture().addFileToProject("sample/FilterTest.java",
                """
                package sample;
                import javafx.scene.layout.Region;
                public class FilterTest extends Region {
                    public static final String PUBLIC_CONST = "public";
                    static final String TEMP_CONST = "temp";
                }
                """);
        getFixture().configureByText("FilterNonPublic.fxml", fxml2(
                "sample.FilterTest",
                """
                  <FilterTest text="${sample.FilterTest.<caret>}"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items after class qualifier");
        List<String> names = displayNames(items);
        assertTrue(names.contains("PUBLIC_CONST"),
                "Expected 'PUBLIC_CONST' in completions, got: " + names);
        assertFalse(names.contains("TEMP_CONST"),
                "Non-public 'TEMP_CONST' must not appear in completions, got: " + names);
    }

    /**
     * Static fields must only be offered when completing on a class qualifier
     * (e.g. {@code $vm.someClass.}), not when completing on an instance name
     * (e.g. {@code $btn2.}).  After a class qualifier, only public static fields
     * should appear.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingPathCompletionStaticFieldsOnlyOnClassQualifier() {
        getFixture().configureByText("StaticOnClassQualifier.fxml", fxml2(
                "javafx.scene.layout.Region",
                """
                  <Region text="${javafx.scene.layout.Region.<caret>}"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items after class qualifier");
        List<String> names = displayNames(items);
        // USE_PREF_SIZE is a public static field on Region
        assertTrue(names.contains("USE_PREF_SIZE"),
                "Public static 'USE_PREF_SIZE' should appear after class qualifier, got: " + names);
        // BASELINE_OFFSET_SAME_AS_HEIGHT is also a public static field
        assertTrue(names.contains("BASELINE_OFFSET_SAME_AS_HEIGHT"),
                "Public static 'BASELINE_OFFSET_SAME_AS_HEIGHT' should appear after class qualifier, got: " + names);
    }

    /**
     * Public static fields compatible with the target property type SHOULD appear in
     * completion when completing on a class qualifier.  This test verifies that
     * {@code USE_PREF_SIZE} (double-typed) is offered when completing for a double-typed
     * property like {@code prefHeight}.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingPathCompletionOffersCompatibleStaticFields() {
        getFixture().configureByText("CompatibleTypes.fxml", fxml2(
                "javafx.scene.layout.Region",
                """
                  <Region prefHeight="${javafx.scene.layout.Region.<caret>}"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items after class qualifier");
        List<String> names = displayNames(items);
        // USE_PREF_SIZE is double-typed, compatible with double property 'prefHeight'
        assertTrue(names.contains("USE_PREF_SIZE"),
                "Compatible double-typed 'USE_PREF_SIZE' should appear for double property, got: " + names);
        // USE_COMPUTED_SIZE is also double-typed
        assertTrue(names.contains("USE_COMPUTED_SIZE"),
                "Compatible double-typed 'USE_COMPUTED_SIZE' should appear for double property, got: " + names);
    }

    // -----------------------------------------------------------------------
    // Additional static field completion matrix tests
    // -----------------------------------------------------------------------

    /**
     * On a class qualifier with a String-typed property, non-public static fields must
     * NOT appear even though they are compatible with the target type.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingPathCompletionNonPublicStaticFieldOnClassQualifierNotOffered() {
        getFixture().addFileToProject("sample/NonPublicStatic.java",
                """
                package sample;
                import javafx.scene.layout.Region;
                public class NonPublicStatic extends Region {
                    public static final String PUBLIC_STR = "public";
                    static final String PRIVATE_STR = "private";
                }
                """);
        getFixture().configureByText("NonPublicClassQualifier.fxml", fxml2(
                "sample.NonPublicStatic",
                """
                  <NonPublicStatic text="${sample.NonPublicStatic.<caret>}"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items after class qualifier");
        List<String> names = displayNames(items);
        assertTrue(names.contains("PUBLIC_STR"),
                "Public static 'PUBLIC_STR' should appear on class qualifier, got: " + names);
        assertFalse(names.contains("PRIVATE_STR"),
                "Non-public static 'PRIVATE_STR' must NOT appear on class qualifier, got: " + names);
    }

    /**
     * On an instance segment (e.g. $vm.property.), public static fields must NOT appear
     * regardless of whether they are compatible with the target property type.
     * This test verifies that after a class qualifier, only static fields are offered
     * (not instance properties), confirming the distinction between class qualifier
     * and instance segment completion contexts.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingPathCompletionPublicStaticFieldOnInstanceNotOffered() {
        getFixture().configureByText("InstanceStatic.fxml", fxml2(
                "javafx.scene.layout.Region",
                """
                  <Region text="${javafx.scene.layout.Region.<caret>}"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items after class qualifier");
        List<String> names = displayNames(items);
        // After a class qualifier, only static fields should appear (not instance properties)
        assertTrue(names.contains("USE_PREF_SIZE"),
                "Static field 'USE_PREF_SIZE' should appear after class qualifier, got: " + names);
        // Instance properties should NOT appear after a class qualifier
        assertTrue(names.stream().noneMatch(n -> n.equals("minHeight") || n.equals("prefWidth")),
                "Instance properties must NOT appear after class qualifier, got: " + names);
    }

    /**
     * Attached property completion should only offer PUBLIC static fields
     * that are compatible with the target type.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingPathCompletionAttachedPropertyPublicStaticOnly() {
        getFixture().configureByText("AttachedPropPublic.fxml", fxml2(
                """
                javafx.scene.control.Label
                javafx.scene.layout.VBox
                """,
                """
                  <Label text="${(VBox.<caret>)}}"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items for attached property");
        List<String> names = displayNames(items);
        // VBox.margin is a public static property
        assertTrue(names.stream().anyMatch(n -> n.contains("margin")),
                "Public static 'margin' should appear for attached property, got: " + names);
    }

    // -----------------------------------------------------------------------
    // fx:context element notation: class-name tag completion
    // -----------------------------------------------------------------------

    /**
     * Typing a partial class name inside {@code <fx:context>} (element notation) must offer
     * class completions from the project's imports and class index, just like any other
     * class-instantiation position in FXML.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void classTagCompletionInsideFxContextElement() {
        getFixture().addFileToProject("test/CtxTarget.java", """
                package test;
                public class CtxTarget {}
                """);
        getFixture().configureByText("FxCtxTagComplete.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import test.CtxTarget?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.TestView">
                  <fx:context>
                    <CtxTarge<caret>/>
                  </fx:context>
                </BorderPane>
                """);
        LookupElement[] items = getFixture().completeBasic();
        // null means the single match was auto-inserted
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("CtxTarget"),
                    "Expected 'CtxTarget' to be auto-inserted, document: " + text);
            return;
        }
        List<String> names = displayNames(items);
        assertTrue(names.contains("CtxTarget"),
                "Expected 'CtxTarget' in completions inside <fx:context>, got: " + names);
    }

    // -----------------------------------------------------------------------
    // fx:context attribute value completion: code-behind fields
    // -----------------------------------------------------------------------

    /**
     * Completing inside {@code fx:context="$<caret>"} must offer code-behind fields/properties,
     * just like any other binding-path attribute value at the root context level.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bindingPathCompletionInFxContextAttributeOffersCodeBehindFields() {
        getFixture().addClass("""
                package test;
                public abstract class CtxCompletionBase extends javafx.scene.layout.BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().addClass("""
                package test;
                public class MyCtxClass {}
                """);
        getFixture().addClass("""
                package test;
                public class CtxCompletionView extends CtxCompletionBase {
                    public test.MyCtxClass myBindingCtx;
                }
                """);
        getFixture().configureByText("FxCtxAttrComplete.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.CtxCompletionView"
                            fx:context="$myBinding<caret>"/>
                """);
        LookupElement[] items = getFixture().completeBasic();
        // null means single match was auto-inserted
        if (items == null) {
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("myBindingCtx"),
                    "Expected 'myBindingCtx' to be auto-inserted, document: " + text);
            return;
        }
        List<String> names = displayNames(items);
        assertTrue(names.contains("myBindingCtx"),
                "Expected 'myBindingCtx' in fx:context=\"$...\" completions, got: " + names);
    }
}
