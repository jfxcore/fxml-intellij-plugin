package org.jfxcore.fxml;

import com.intellij.codeInspection.BatchQuickFix;
import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ex.QuickFixWrapper;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jfxcore.fxml.annotator.Fxml2RawTypeInspection;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the "Infer fx:typeArguments" quick-fix attached to
 * {@link Fxml2RawTypeInspection}.
 *
 * <p>The quick-fix infers the missing {@code fx:typeArguments} value from surrounding
 * context. Four inference sources are supported:
 * <ol>
 *   <li>Parent property type (when the tag is the value of a typed property).</li>
 *   <li>Typed attribute value: a binding expression whose source path's static type
 *       pins one of the tag's type variables, or a plain-text class-literal targeting
 *       a {@code Class<T>}-typed property.  Contributions accumulate across attributes
 *       of the same tag.</li>
 *   <li>The {@code fx:subclass} supertype (for the root tag).</li>
 *   <li>Single final upper bound on every type parameter.</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2InferTypeArgumentsFixTest extends Fxml2TestBase {

    private static final String FIX_FAMILY = "Infer fx:typeArguments";

    @BeforeAll
    void addBaseCodeBehind() {
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

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2RawTypeInspection());
    }

    // -----------------------------------------------------------------------
    // Source 1 - child of a strongly-typed property
    // -----------------------------------------------------------------------

    /**
     * Source 1a: the offending tag is the value of a property whose declared type is
     * a parameterized form of the tag's class.  Property type {@code Map<String, Integer>},
     * tag {@code HashMap} - expect {@code fx:typeArguments="String, Integer"}.
     */
    @Test
    void source1_inferredFromExactPropertyMatch() {
        getFixture().addClass("""
                package test;
                import java.util.HashMap;
                import java.util.Map;
                public class Source1Holder {
                    private final Map<String, Integer> entries = new HashMap<>();
                    public Map<String, Integer> getEntries() { return entries; }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                """
                test.Source1Holder
                java.util.HashMap
                """,
                """
                  <Source1Holder>
                    <entries>
                      <HashMap<caret>/>
                    </entries>
                  </Source1Holder>
                """
        ));
        applyInferFix();
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(
                result.contains("<HashMap fx:typeArguments=\"String, Integer\"/>"),
                "Expected fx:typeArguments=\"String, Integer\", got: " + result);
    }

    /**
     * Source 1b: tag is a child of a default-property whose element type fixes the
     * tag class's type variable.  Default property type
     * {@code ObservableList<Source1Token<String>>}, child {@code Source1Token} -
     * expect {@code fx:typeArguments="String"}.
     */
    @Test
    void source1_inferredFromDefaultPropertyElementType() {
        getFixture().addClass("""
                package test;
                public class Source1Token<T> {}
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.DefaultProperty;
                import javafx.collections.FXCollections;
                import javafx.collections.ObservableList;
                @DefaultProperty("items")
                public class Source1Box {
                    private final ObservableList<Source1Token<String>> items =
                            FXCollections.observableArrayList();
                    public ObservableList<Source1Token<String>> getItems() { return items; }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                """
                test.Source1Box
                test.Source1Token
                """,
                """
                  <Source1Box>
                    <Source1Token<caret>/>
                  </Source1Box>
                """
        ));
        applyInferFix();
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(
                result.contains("<Source1Token fx:typeArguments=\"String\"/>"),
                "Expected fx:typeArguments=\"String\", got: " + result);
    }

    /**
     * Source 1d (rejected): the parent tag is itself raw, so the property type
     * contains an unbound type variable.  No fix offered.
     */
    @Test
    void source1_rejectedWhenParentIsRaw() {
        getFixture().configureByText("TestView.fxml", fxml2(
                """
                javafx.scene.control.TableView
                javafx.scene.control.TableColumn
                """,
                """
                  <TableView>
                    <columns>
                      <TableColumn<caret>/>
                    </columns>
                  </TableView>
                """
        ));
        assertNoInferFixOffered();
    }

    // -----------------------------------------------------------------------
    // Source 2 - typed binding source
    // -----------------------------------------------------------------------

    /**
     * Source 2a (motivating case): a {@code ListView}'s {@code items} attribute is
     * bound to a view-model property of type {@code ReadOnlyListProperty<MyData>}.
     * The binding source unifies with {@code ObservableList<S>}, yielding {@code S = MyData}.
     */
    @Test
    void source2_inferredFromListPropertyBinding() {
        getFixture().addClass("""
                package test;
                public class Source2MyData {}
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ReadOnlyListProperty;
                import javafx.beans.property.SimpleListProperty;
                import javafx.collections.FXCollections;
                public class Source2ViewModel {
                    private final ReadOnlyListProperty<Source2MyData> dataItems =
                            new SimpleListProperty<>(FXCollections.observableArrayList());
                    public ReadOnlyListProperty<Source2MyData> dataItemsProperty() {
                        return dataItems;
                    }
                }
                """);
        getFixture().addClass("""
                package test;
                public class Source2View extends TestViewBase {
                    public Source2ViewModel getVm() { return new Source2ViewModel(); }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                """
                javafx.scene.control.ListView
                test.Source2MyData
                """,
                """
                  <ListView<caret> items="${vm.dataItems}"/>
                """,
                "test.Source2View"
        ));
        applyInferFix();
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("fx:typeArguments=\"Source2MyData\""),
                "Expected fx:typeArguments=\"Source2MyData\", got: " + result);
    }

    /**
     * Source 2b: a {@code ComboBox}'s {@code value} attribute is bound to an
     * {@code ObjectProperty<Person>}.  Unwrapping the {@code ObservableValue}
     * yields {@code Person} for the unifier; expect {@code fx:typeArguments="Source2Person"}.
     */
    @Test
    void source2_inferredFromScalarPropertyBinding() {
        getFixture().addClass("""
                package test;
                public class Source2Person {}
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                public class Source2FormViewModel {
                    private final ObjectProperty<Source2Person> selected = new SimpleObjectProperty<>();
                    public ObjectProperty<Source2Person> selectedProperty() { return selected; }
                }
                """);
        getFixture().addClass("""
                package test;
                public class Source2FormView extends TestViewBase {
                    public Source2FormViewModel getVm() { return new Source2FormViewModel(); }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                """
                javafx.scene.control.ComboBox
                test.Source2Person
                """,
                """
                  <ComboBox<caret> value="${vm.selected}"/>
                """,
                "test.Source2FormView"
        ));
        applyInferFix();
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("fx:typeArguments=\"Source2Person\""),
                "Expected fx:typeArguments=\"Source2Person\", got: " + result);
    }

    /**
     * Source 2e (rejected): the binding source itself is raw, carrying no type
     * information.  No fix offered.
     */
    @Test
    void source2_rejectedWhenBindingSourceRaw() {
        getFixture().addClass("""
                package test;
                public class Source2RawViewModel {
                    private final javafx.beans.property.ReadOnlyListProperty rawItems =
                            new javafx.beans.property.SimpleListProperty(
                                    javafx.collections.FXCollections.observableArrayList());
                    public javafx.beans.property.ReadOnlyListProperty rawItemsProperty() {
                        return rawItems;
                    }
                }
                """);
        getFixture().addClass("""
                package test;
                public class Source2RawView extends TestViewBase {
                    public Source2RawViewModel getVm() { return new Source2RawViewModel(); }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView<caret> items="${vm.rawItems}"/>
                """,
                "test.Source2RawView"
        ));
        assertNoInferFixOffered();
    }

    /**
     * Source 2f: the binding-path's first segment resolves to a Kotlin {@code val}
     * property declared on the code-behind class.  No companion {@code vmProperty()}
     * function is defined, so the resolver falls back to the {@code KtProperty}
     * navigation target rather than a Java {@link com.intellij.psi.PsiMember}.  The
     * inferrer must still derive the type of the {@code val} from the corresponding
     * Kotlin-synthesized getter so that the binding-source unifier can pin the
     * tag's type variable.  Expect {@code fx:typeArguments="Source2KtRow"}.
     */
    @Test
    void source2_inferredFromKotlinValOnCodeBehind() {
        getFixture().addClass("""
                package test;
                public class Source2KtRow {}
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ReadOnlyListProperty;
                import javafx.beans.property.SimpleListProperty;
                import javafx.collections.FXCollections;
                public class Source2KtViewModel {
                    private final ReadOnlyListProperty<Source2KtRow> dataRows =
                            new SimpleListProperty<>(FXCollections.observableArrayList());
                    public ReadOnlyListProperty<Source2KtRow> dataRowsProperty() {
                        return dataRows;
                    }
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public abstract class Source2KtViewBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().addFileToProject("test/Source2KtView.kt", """
                package test
                import javafx.beans.property.ObjectProperty
                import javafx.beans.property.SimpleObjectProperty
                class Source2KtView : Source2KtViewBase() {
                    val vm: ObjectProperty<Source2KtViewModel> =
                            SimpleObjectProperty(Source2KtViewModel())
                    init { initializeComponent() }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                """
                javafx.scene.control.ListView
                test.Source2KtRow
                """,
                """
                  <ListView<caret> items="${vm.dataRows}"/>
                """,
                "test.Source2KtView"
        ));
        applyInferFix();
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("fx:typeArguments=\"Source2KtRow\""),
                "Expected fx:typeArguments=\"Source2KtRow\", got: " + result);
    }

    /**
     * Source 2g (mixed contributions): a generic container with two type parameters is
     * pinned by two attributes on the same tag - a typed binding source binds one
     * parameter, a {@code Class<T>}-typed plain attribute value binds the other.  The
     * shared accumulation produces a complete substitution and the fix is offered with
     * both arguments in declaration order.
     */
    @Test
    void source2_inferredFromMixedBindingAndClassLiteral() {
        getFixture().addClass("""
                package test;
                public class Source2gRow {}
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ListProperty;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleListProperty;
                import javafx.beans.property.SimpleObjectProperty;
                import javafx.collections.FXCollections;
                import javafx.collections.ObservableList;
                public class Source2gContainer<TItem, TView> {
                    private final ObjectProperty<Class<TView>> viewClass = new SimpleObjectProperty<>();
                    public ObjectProperty<Class<TView>> viewClassProperty() { return viewClass; }
                    public Class<TView> getViewClass() { return viewClass.get(); }
                    public void setViewClass(Class<TView> v) { viewClass.set(v); }
                    private final ListProperty<TItem> items =
                            new SimpleListProperty<>(FXCollections.observableArrayList());
                    public ListProperty<TItem> itemsProperty() { return items; }
                    public ObservableList<TItem> getItems() { return items.get(); }
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ReadOnlyListProperty;
                import javafx.beans.property.SimpleListProperty;
                import javafx.collections.FXCollections;
                public class Source2gViewModel {
                    private final ReadOnlyListProperty<Source2gRow> dataRows =
                            new SimpleListProperty<>(FXCollections.observableArrayList());
                    public ReadOnlyListProperty<Source2gRow> dataRowsProperty() { return dataRows; }
                }
                """);
        getFixture().addClass("""
                package test;
                public class Source2gView extends TestViewBase {
                    public Source2gViewModel getVm() { return new Source2gViewModel(); }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                """
                test.Source2gContainer
                test.Source2gRow
                javafx.scene.control.Label
                """,
                """
                  <Source2gContainer<caret> viewClass="Label" items="${vm.dataRows}"/>
                """,
                "test.Source2gView"
        ));
        applyInferFix();
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("fx:typeArguments=\"Source2gRow, Label\""),
                "Expected fx:typeArguments=\"Source2gRow, Label\", got: " + result);
    }

    /**
     * Source 2h (class-literal only): a generic class with a {@code Class<T>} property
     * assigned a plain class name pins the parameter to the resolved class.
     */
    @Test
    void source2_inferredFromClassLiteralOnly() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                public class Source2hHolder<T> {
                    private final ObjectProperty<Class<T>> kind = new SimpleObjectProperty<>();
                    public ObjectProperty<Class<T>> kindProperty() { return kind; }
                    public Class<T> getKind() { return kind.get(); }
                    public void setKind(Class<T> v) { kind.set(v); }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                """
                test.Source2hHolder
                javafx.scene.control.Button
                """,
                """
                  <Source2hHolder<caret> kind="Button"/>
                """
        ));
        applyInferFix();
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("fx:typeArguments=\"Button\""),
                "Expected fx:typeArguments=\"Button\", got: " + result);
    }

    /**
     * Source 2i (rejected): the literal does not resolve to any imported class, so the
     * compiler's class-literal coercion would fail.  No fix offered.
     */
    @Test
    void source2_rejectedWhenClassLiteralUnresolved() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                public class Source2iHolder<T> {
                    private final ObjectProperty<Class<T>> kind = new SimpleObjectProperty<>();
                    public ObjectProperty<Class<T>> kindProperty() { return kind; }
                    public Class<T> getKind() { return kind.get(); }
                    public void setKind(Class<T> v) { kind.set(v); }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.Source2iHolder",
                """
                  <Source2iHolder<caret> kind="NoSuchClass"/>
                """
        ));
        assertNoInferFixOffered();
    }

    /**
     * Source 2j (rejected): the property type wraps the tag's variable in a wildcard
     * ({@code Class<? extends T>}); the wildcard cannot pin {@code T} unambiguously,
     * so no fix is offered.
     */
    @Test
    void source2_rejectedWhenClassLiteralWildcardBound() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                public class Source2jHolder<T> {
                    private final ObjectProperty<Class<? extends T>> kind = new SimpleObjectProperty<>();
                    public ObjectProperty<Class<? extends T>> kindProperty() { return kind; }
                    public Class<? extends T> getKind() { return kind.get(); }
                    public void setKind(Class<? extends T> v) { kind.set(v); }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                """
                test.Source2jHolder
                javafx.scene.control.Button
                """,
                """
                  <Source2jHolder<caret> kind="Button"/>
                """
        ));
        assertNoInferFixOffered();
    }

    /**
     * Source 2k (rejected): two attributes on the same tag are both {@code Class<T>}
     * properties referencing the same type variable, but the resolved literals are
     * unrelated.  The shared substitution rejects the conflict and no fix is offered.
     */
    @Test
    void source2_rejectedWhenClassLiteralConflictsAcrossAttrs() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                public class Source2kHolder<T> {
                    private final ObjectProperty<Class<T>> first = new SimpleObjectProperty<>();
                    public ObjectProperty<Class<T>> firstProperty() { return first; }
                    public Class<T> getFirst() { return first.get(); }
                    public void setFirst(Class<T> v) { first.set(v); }
                    private final ObjectProperty<Class<T>> second = new SimpleObjectProperty<>();
                    public ObjectProperty<Class<T>> secondProperty() { return second; }
                    public Class<T> getSecond() { return second.get(); }
                    public void setSecond(Class<T> v) { second.set(v); }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                """
                test.Source2kHolder
                javafx.scene.control.Button
                javafx.scene.control.Label
                """,
                """
                  <Source2kHolder<caret> first="Button" second="Label"/>
                """
        ));
        assertNoInferFixOffered();
    }

    /**
     * Source 2l (rejected): a value starting with the {@code %} prefix shorthand is a
     * resource reference, not a class literal.  The inferrer must recognise prefix
     * shorthands (using the file's prefix mapping) and decline class-literal coercion.
     */
    @Test
    void source2_ignoresPrefixShorthandAsClassLiteral() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                public class Source2lHolder<T> {
                    private final ObjectProperty<Class<T>> kind = new SimpleObjectProperty<>();
                    public ObjectProperty<Class<T>> kindProperty() { return kind; }
                    public Class<T> getKind() { return kind.get(); }
                    public void setKind(Class<T> v) { kind.set(v); }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.Source2lHolder",
                """
                  <Source2lHolder<caret> kind="%Label"/>
                """
        ));
        assertNoInferFixOffered();
    }

    // -----------------------------------------------------------------------
    // Source 3 - fx:subclass fixes the root tag's arguments
    // -----------------------------------------------------------------------

    /**
     * Source 3a: a standalone file declares a code-behind subclass that
     * {@code extends ListView<Person>}; the root {@code <ListView>} tag must be
     * fixed to {@code fx:typeArguments="Source3Person"}.
     */
    @Test
    void source3_inferredFromCodeBehindSupertype() {
        getFixture().addClass("""
                package test;
                public class Source3Person {}
                """);
        getFixture().addClass("""
                package test;
                import javafx.scene.control.ListView;
                public class Source3PersonListView extends ListView<Source3Person> {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.ListView?>
                <?import test.Source3Person?>
                <ListView<caret> xmlns="http://javafx.com/javafx"
                          xmlns:fx="http://jfxcore.org/fxml/2.0"
                          fx:subclass="test.Source3PersonListView"/>
                """);
        applyInferFix();
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("fx:typeArguments=\"Source3Person\""),
                "Expected fx:typeArguments=\"Source3Person\", got: " + result);
    }

    /**
     * Source 3c (rejected): code-behind subclass extends the root tag's class as a
     * raw type.  No fix offered.
     */
    @Test
    void source3_rejectedWhenSubclassExtendsRaw() {
        getFixture().addClass("""
                package test;
                public class Source3LooseListView extends javafx.scene.control.ListView {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.ListView?>
                <ListView<caret> xmlns="http://javafx.com/javafx"
                          xmlns:fx="http://jfxcore.org/fxml/2.0"
                          fx:subclass="test.Source3LooseListView"/>
                """);
        assertNoInferFixOffered();
    }

    // -----------------------------------------------------------------------
    // Source 4 - type parameter with a final upper bound
    // -----------------------------------------------------------------------

    /**
     * Source 4a: every type parameter on the tag class has an upper bound that is a
     * {@code final} class; the parameter can only be that class.  For
     * {@code class Source4Restricted<T extends String>} the only legal argument is
     * {@code String}.
     */
    @Test
    void source4_inferredFromFinalUpperBound() {
        getFixture().addClass("""
                package test;
                public class Source4Restricted<T extends String> {}
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.Source4Restricted",
                """
                  <Source4Restricted<caret>/>
                """
        ));
        applyInferFix();
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("<Source4Restricted fx:typeArguments=\"String\"/>"),
                "Expected fx:typeArguments=\"String\", got: " + result);
    }

    /**
     * Source 4b (rejected): the upper bound is non-final ({@code Number}); many
     * subclasses are legal so the fix should not pick one.
     */
    @Test
    void source4_rejectedWhenBoundIsNonFinal() {
        getFixture().addClass("""
                package test;
                public class Source4Loose<T extends Number> {}
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.Source4Loose",
                """
                  <Source4Loose<caret>/>
                """
        ));
        assertNoInferFixOffered();
    }

    /**
     * Source 4c (rejected): multi-interface upper bound is ambiguous; no fix.
     */
    @Test
    void source4_rejectedWhenMultiBound() {
        getFixture().addClass("""
                package test;
                public class Source4MultiBound<T extends Number & Comparable<T>> {}
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.Source4MultiBound",
                """
                  <Source4MultiBound<caret>/>
                """
        ));
        assertNoInferFixOffered();
    }

    // -----------------------------------------------------------------------
    // Import insertion
    // -----------------------------------------------------------------------

    /**
     * The fix renders inferred types by their simple name and adds the necessary
     * {@code <?import?>} processing instructions so the rendered value resolves.
     * The instructions must be placed in alphabetical position among existing
     * imports, matching the behavior of "Optimize Imports".
     */
    @Test
    void importAddedForInferredTypeNotYetImported() {
        getFixture().addClass("""
                package test;
                public class ImportToken {}
                """);
        getFixture().addClass("""
                package test;
                import java.util.HashMap;
                import java.util.Map;
                public class ImportHolder {
                    private final Map<String, ImportToken> entries = new HashMap<>();
                    public Map<String, ImportToken> getEntries() { return entries; }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                """
                test.ImportHolder
                java.util.HashMap
                """,
                """
                  <ImportHolder>
                    <entries>
                      <HashMap<caret>/>
                    </entries>
                  </ImportHolder>
                """
        ));
        applyInferFix();
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(
                result.contains("<HashMap fx:typeArguments=\"String, ImportToken\"/>"),
                "Expected fx:typeArguments=\"String, ImportToken\" with simple name, got: " + result);
        assertTrue(
                result.contains("<?import test.ImportToken?>"),
                "Expected <?import test.ImportToken?> to be added, got: " + result);
        int holderIdx = result.indexOf("<?import test.ImportHolder?>");
        int tokenIdx = result.indexOf("<?import test.ImportToken?>");
        assertTrue(holderIdx >= 0 && tokenIdx > holderIdx,
                "Expected ImportToken import to follow ImportHolder alphabetically, got: " + result);
    }

    /**
     * When the inferred type is already imported, no duplicate {@code <?import?>}
     * processing instruction must be added and the rendered value must use the
     * simple name.
     */
    @Test
    void noDuplicateImportWhenAlreadyImported() {
        getFixture().addClass("""
                package test;
                public class ExistingToken {}
                """);
        getFixture().addClass("""
                package test;
                import java.util.HashMap;
                import java.util.Map;
                public class ExistingHolder {
                    private final Map<String, ExistingToken> entries = new HashMap<>();
                    public Map<String, ExistingToken> getEntries() { return entries; }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                """
                test.ExistingHolder
                test.ExistingToken
                java.util.HashMap
                """,
                """
                  <ExistingHolder>
                    <entries>
                      <HashMap<caret>/>
                    </entries>
                  </ExistingHolder>
                """
        ));
        applyInferFix();
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(
                result.contains("fx:typeArguments=\"String, ExistingToken\""),
                "Expected fx:typeArguments=\"String, ExistingToken\", got: " + result);
        int firstImport = result.indexOf("<?import test.ExistingToken?>");
        int lastImport = result.lastIndexOf("<?import test.ExistingToken?>");
        assertTrue(firstImport >= 0 && firstImport == lastImport,
                "Expected a single <?import test.ExistingToken?>, got: " + result);
    }

    /**
     * When the inferred type's simple name is already bound to a different class
     * through an existing import, the fix must fall back to the fully-qualified
     * name rather than adding a conflicting import.
     */
    @Test
    void fqnUsedWhenSimpleNameConflicts() {
        getFixture().addClass("""
                package test;
                public class ConflictToken {}
                """);
        getFixture().addClass("""
                package test.other;
                public class ConflictToken {}
                """);
        getFixture().addClass("""
                package test;
                import java.util.HashMap;
                import java.util.Map;
                public class ConflictHolder {
                    private final Map<String, test.other.ConflictToken> entries = new HashMap<>();
                    public Map<String, test.other.ConflictToken> getEntries() { return entries; }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                """
                test.ConflictHolder
                test.ConflictToken
                java.util.HashMap
                """,
                """
                  <ConflictHolder>
                    <entries>
                      <HashMap<caret>/>
                    </entries>
                  </ConflictHolder>
                """
        ));
        applyInferFix();
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(
                result.contains("fx:typeArguments=\"String, test.other.ConflictToken\""),
                "Expected fx:typeArguments to use FQN for conflicting type, got: " + result);
        assertFalse(
                result.contains("<?import test.other.ConflictToken?>"),
                "No import should be added for the conflicting FQN, got: " + result);
    }

    // -----------------------------------------------------------------------
    // Batch quick-fix
    // -----------------------------------------------------------------------

    /**
     * The quick-fix must implement {@link BatchQuickFix} so "Fix all in file" works.
     */
    @Test
    void quickFixIsBatchApplicable() {
        getFixture().addClass("""
                package test;
                public class Source4Restricted<T extends String> {}
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.Source4Restricted",
                """
                  <Source4Restricted<caret>/>
                """
        ));
        getFixture().doHighlighting();
        var fix = QuickFixWrapper.unwrap(findInferFixAction());
        assertInstanceOf(BatchQuickFix.class, fix,
                "InferTypeArgumentsFix should implement BatchQuickFix");
    }

    /**
     * "Fix all in file" must apply each tag's <em>own</em> inferred arguments, not the
     * representative tag's arguments to every tag.
     *
     * <p>IntelliJ invokes {@link BatchQuickFix#applyFix(com.intellij.openapi.project.Project,
     * CommonProblemDescriptor[], List, Runnable)} on the fix instance attached to the
     * first descriptor, passing the descriptors of every problem in scope.  Each tag
     * has its own renderedArgs baked into its own fix instance, so the batch path must
     * dispatch back to the per-descriptor fix rather than reusing {@code this}.  Two
     * sibling {@code ListView} tags bound to view-model properties of different
     * element types must end up with distinct {@code fx:typeArguments} values.
     */
    @Test
    void batchFixAllAppliesPerTagInferredArguments() {
        getFixture().addClass("""
                package test;
                public class BatchRowA {}
                """);
        getFixture().addClass("""
                package test;
                public class BatchRowB {}
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ReadOnlyListProperty;
                import javafx.beans.property.SimpleListProperty;
                import javafx.collections.FXCollections;
                public class BatchViewModel {
                    private final ReadOnlyListProperty<BatchRowA> aItems =
                            new SimpleListProperty<>(FXCollections.observableArrayList());
                    public ReadOnlyListProperty<BatchRowA> aItemsProperty() { return aItems; }
                    private final ReadOnlyListProperty<BatchRowB> bItems =
                            new SimpleListProperty<>(FXCollections.observableArrayList());
                    public ReadOnlyListProperty<BatchRowB> bItemsProperty() { return bItems; }
                }
                """);
        getFixture().addClass("""
                package test;
                public class BatchView extends TestViewBase {
                    public BatchViewModel getVm() { return new BatchViewModel(); }
                }
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                """
                javafx.scene.control.ListView
                test.BatchRowA
                test.BatchRowB
                """,
                """
                  <ListView items="${vm.aItems}"/>
                  <ListView items="${vm.bItems}"/>
                """,
                "test.BatchView"
        ));

        var inspection = new Fxml2RawTypeInspection();
        var xmlFile = (XmlFile) getFixture().getFile();
        var project = getFixture().getProject();

        List<ProblemDescriptor> problems = ReadAction.compute(() -> {
            var manager = InspectionManager.getInstance(project);
            ProblemDescriptor[] arr = inspection.checkFile(xmlFile, manager, false);
            return arr == null ? new ArrayList<>() : new ArrayList<>(List.of(arr));
        });
        assertEquals(2, problems.size(),
                "Expected exactly 2 raw-type problems for the two sibling ListView tags");

        ProblemDescriptor first = problems.getFirst();
        assertNotNull(first.getFixes());
        var batchFix = (BatchQuickFix) first.getFixes()[0];
        List<PsiElement> toIgnore = new ArrayList<>();
        WriteCommandAction.runWriteCommandAction(project, () ->
                batchFix.applyFix(project,
                        problems.toArray(CommonProblemDescriptor[]::new),
                        toIgnore, null));

        record TagArgs(String text, String first, String second, int count) {}
        TagArgs result = ReadAction.compute(() -> {
            var refreshed = (XmlFile) getFixture().getFile();
            List<XmlTag> listViewTags = new ArrayList<>();
            for (XmlTag t : PsiTreeUtil.findChildrenOfType(refreshed, XmlTag.class)) {
                if ("ListView".equals(t.getLocalName())) listViewTags.add(t);
            }
            String firstArg = !listViewTags.isEmpty() ? listViewTags.get(0).getAttributeValue(
                    "typeArguments", Fxml2ImportResolver.FXML2_NAMESPACE) : null;
            String secondArg = listViewTags.size() > 1 ? listViewTags.get(1).getAttributeValue(
                    "typeArguments", Fxml2ImportResolver.FXML2_NAMESPACE) : null;
            return new TagArgs(refreshed.getText(), firstArg, secondArg, listViewTags.size());
        });
        assertEquals(2, result.count(), "Both ListView tags should still exist; got:\n" + result.text());
        assertEquals("BatchRowA", result.first(),
                "First ListView must keep its own inferred argument; got:\n" + result.text());
        assertEquals("BatchRowB", result.second(),
                "Second ListView must NOT inherit the first's argument; got:\n" + result.text());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void applyInferFix() {
        getFixture().doHighlighting();
        getFixture().launchAction(findInferFixAction());
    }

    private com.intellij.codeInsight.intention.IntentionAction findInferFixAction() {
        return getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && FIX_FAMILY.equals(fix.getFamilyName());
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Expected '" + FIX_FAMILY + "' quick-fix to be offered"));
    }

    private void assertNoInferFixOffered() {
        getFixture().doHighlighting();
        boolean offered = getFixture().getAllQuickFixes().stream()
                .anyMatch(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && FIX_FAMILY.equals(fix.getFamilyName());
                });
        assertFalse(offered, "Inference fix should not be offered in this context");
    }
}
