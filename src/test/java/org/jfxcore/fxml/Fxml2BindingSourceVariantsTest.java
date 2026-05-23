package org.jfxcore.fxml;

import com.intellij.codeInspection.BatchQuickFix;
import com.intellij.codeInspection.ex.QuickFixWrapper;
import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.xml.XmlAttributeValue;
import java.util.Objects;
import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.jfxcore.fxml.annotator.Fxml2NonObservableBindingSourceInspection;
import org.jfxcore.fxml.lang.Fxml2BindingSegmentReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for binding paths that resolve via Java-Beans-style getters and plain fields,
 * in addition to the JavaFX {@code xxxProperty()} pattern.
 *
 * <p>Doc feature ({@code binding/binding-path.md}): The binding path can be:
 * <ul>
 *   <li>A plain field ({@code public String userName})</li>
 *   <li>A Java-Beans getter ({@code getUserName()} -> {@code userName})</li>
 *   <li>A boolean getter ({@code isActive()} -> {@code active})</li>
 *   <li>A JavaFX property method ({@code userNameProperty()} -> {@code userName})</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2BindingSourceVariantsTest extends Fxml2TestBase {

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(
                new Fxml2AttributeValueInspection(),
                new Fxml2NonObservableBindingSourceInspection());
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
        getFixture().addClass("""
                package test;
                public class TestView extends TestViewBase {
                    // Plain public field
                    public String plainField = "hello";

                    // Java-Beans getter pair
                    private String beanName = "world";
                    public String getBeanName() { return beanName; }
                    public void setBeanName(String v) { beanName = v; }

                    // Boolean getter pair
                    private boolean activated = false;
                    public boolean isActivated() { return activated; }
                    public void setActivated(boolean v) { activated = v; }
                }
                """);
    }

    // -----------------------------------------------------------------------
    // Plain field as binding source
    // -----------------------------------------------------------------------

    /**
     * A binding path can match a plain {@code public} field.
     * {@code $plainField} should resolve without error.
     */
    @Test
    void plainFieldAsBindingSourceProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="$plainField"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Using {@code ${plainField}} (Observe binding) on a plain field that is not an
     * {@code ObservableValue} should produce an error, since it cannot be observed.
     */
    @Test
    void plainFieldInObservableBindingProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text=<error descr="plainField is not a valid binding source, required javafx.beans.value.ObservableValue">"${plainField}"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Java Beans-style getter as binding source
    // -----------------------------------------------------------------------

    /**
     * A binding path segment matching a {@code getXxx()} getter should resolve.
     * {@code $beanName} -> resolves via {@code getBeanName()}.
     */
    @Test
    void javaBeanGetterAsEvaluateBindingSourceProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="$beanName"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A binding path segment matching an {@code isXxx()} boolean getter should resolve.
     * {@code $activated} -> resolves via {@code isActivated()}.
     */
    @Test
    void javaBeanBooleanGetterAsEvaluateBindingSourceProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button disable="$activated"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Using {@code ${beanName}} (Observe binding) on a plain Beans getter (not an
     * {@code ObservableValue}) should produce an error.
     */
    @Test
    void javaBeanGetterInObservableBindingProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text=<error descr="beanName is not a valid binding source, required javafx.beans.value.ObservableValue">"${beanName}"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Multi-segment path through Beans getter
    // -----------------------------------------------------------------------

    /**
     * When the first segment is an {@code ObjectProperty<T>} (observable), the path
     * {@code ${model.actionCommand}} is valid even though the last segment ({@code actionCommand})
     * is a plain non-observable field on {@code T}.  The compiler accepts this because the
     * observable {@code model} property makes the whole chain trackable.
     *
     * <p>This is the canonical "false-positive" case: the IDE must NOT report an error here.
     */
    @Test
    void observableIntermediateSegmentMakesPathValid() {
        getFixture().addClass("""
                package test;
                public class Command {
                    public void execute() {}
                }
                """);
        getFixture().addClass("""
                package test;
                public class ViewModel {
                    public final Command actionCommand = new Command();
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                import javafx.scene.layout.BorderPane;
                public abstract class SampleViewBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                public class SampleView extends SampleViewBase {
                    private final ObjectProperty<ViewModel> model =
                        new SimpleObjectProperty<>(new ViewModel());
                    public ObjectProperty<ViewModel> modelProperty() { return model; }
                }
                """);
        getFixture().configureByText("SampleView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.Button?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.SampleView">
                  <Button onAction="${model.actionCommand}"/>
                </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A multi-segment path where an intermediate segment is resolved via a getter
     * should not produce a false-positive error.
     */
    @Test
    void multiSegmentPathThroughBeanGetterProducesNoError() {
        getFixture().addClass("""
                package test;
                public class Address {
                    private String city = "Berlin";
                    public String getCity() { return city; }
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public abstract class NestedViewBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().addClass("""
                package test;
                public class NestedView extends NestedViewBase {
                    private Address address = new Address();
                    public Address getAddress() { return address; }
                }
                """);
        getFixture().configureByText("NestedView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.Label?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.NestedView">
                  <Label text="$address.city"/>
                </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Observable selection operator ::: leading form
    // -----------------------------------------------------------------------

    /**
     * {@code ${::addresses.size}}: the leading {@code ::} selects the
     * {@code ObservableValue} instance itself rather than the contained value,
     * allowing binding to properties like {@code ListProperty.sizeProperty()}.
     * The leading {@code ::} must not produce a resolution error.
     */
    @Test
    void leadingObservableSelectionOperatorProducesNoError() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ListProperty;
                import javafx.beans.property.SimpleListProperty;
                import javafx.collections.FXCollections;
                import javafx.collections.ObservableList;
                import javafx.scene.layout.BorderPane;
                public abstract class ListView2Base extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ListProperty;
                import javafx.beans.property.SimpleListProperty;
                import javafx.collections.FXCollections;
                public class ListView2 extends ListView2Base {
                    private final ListProperty<String> items =
                        new SimpleListProperty<>(FXCollections.observableArrayList());
                    public ListProperty<String> itemsProperty() { return items; }
                }
                """);
        getFixture().configureByText("ListView2.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.Label?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.ListView2">
                  <Label text="${::items.size}"/>
                </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * When {@code ::} (observable-selection operator) is applied to a member that does not
     * return an {@code ObservableValue}, the fxml2 compiler rejects the reference with
     * {@code INVALID_INVARIANT_REFERENCE}. The plugin must report the same error, pointing
     * only at the member-name segment (not the preceding {@code ::}).
     *
     * <p>The correct alternative is to use {@code .} instead of {@code ::}.
     */
    @Test
    void observableSelectorOnNonObservableMemberProducesError() {
        getFixture().addClass("""
                package test;
                import java.util.function.Function;
                public class DataRow {
                    public static final Function<DataRow, String> labelExtractor = r -> r.getLabel();
                    private final String label;
                    public DataRow(String label) { this.label = label; }
                    public String getLabel() { return label; }
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public abstract class RowViewBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().addClass("""
                package test;
                public class RowView extends RowViewBase {
                }
                """);
        // DataRow::labelExtractor uses :: on a non-ObservableValue field (Function<DataRow,String>),
        // which the fxml2 compiler rejects with INVALID_INVARIANT_REFERENCE.
        getFixture().configureByText("RowView.fxml", fxml2(
                """
                test.DataRow
                javafx.scene.control.Label
                """,
                "  <Label text=\"$DataRow::" +
                        error("'labelExtractor' in test.DataRow cannot be referenced" +
                                " (note: '.' can be used instead of '::' within a path expression)",
                                "labelExtractor") +
                        "\"/>",
                "test.RowView"
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * When a binding path using {@code ::} on a non-observable member appears as a positional
     * argument inside a markup extension (e.g. {@code {MyExtension $DataClass::nonObservable}}),
     * the IDE must report the same error as the fxml2 compiler: the member cannot be referenced
     * via the observable-selection operator.
     */
    @Test
    void observableSelectorOnNonObservableMemberInMarkupExtensionArgProducesError() {
        getFixture().addClass("""
                package test;
                import java.util.function.Function;
                public class DataRow {
                    public static final Function<DataRow, String> labelExtractor = r -> r.getLabel();
                    private final String label;
                    public DataRow(String label) { this.label = label; }
                    public String getLabel() { return label; }
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public abstract class RowViewBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().addClass("""
                package test;
                public class RowView extends RowViewBase {
                }
                """);
        getFixture().addClass("""
                package org.jfxcore.markup;
                public interface MarkupExtension {
                    interface Supplier<T> extends MarkupExtension {}
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.util.Callback;
                import org.jfxcore.markup.MarkupExtension;
                public class CellFactory implements MarkupExtension.Supplier<Callback<?, ?>> {
                    public CellFactory(java.util.function.Function<?, ?> getter) {}
                }
                """);
        // CellFactory positional arg uses :: on a non-ObservableValue field (Function<DataRow,String>),
        // which the fxml2 compiler rejects with INVALID_INVARIANT_REFERENCE.
        getFixture().configureByText("RowView.fxml", fxml2(
                """
                test.DataRow
                test.CellFactory
                javafx.scene.control.TableView
                javafx.scene.control.TableColumn
                """,
                "  <TableView fx:typeArguments=\"DataRow\">" +
                "    <columns><TableColumn text=\"Title\" cellFactory=\"{CellFactory $DataRow::" +
                        error("'labelExtractor' in test.DataRow cannot be referenced" +
                                " (note: '.' can be used instead of '::' within a path expression)",
                                "labelExtractor") +
                        "}\"/></columns></TableView>",
                "test.RowView"
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Observable collection as binding dependency
    // -----------------------------------------------------------------------

    /**
     * A path whose intermediate segment is an {@code ObservableList} (not an
     * {@code ObservableValue}) is now accepted by the fxml2 compiler as a valid
     * observe-binding source: the collection provides content-based invalidation.
     *
     * <p>Example: {@code ${items.size}} where {@code items} is a plain
     * {@code ObservableList<String>} field should not produce an error, because the
     * compiler re-evaluates the binding whenever the list content changes.
     */
    @Test
    void observableListIntermediateSegmentMakesPathValid() {
        getFixture().addClass("""
                package test;
                import javafx.collections.FXCollections;
                import javafx.collections.ObservableList;
                import javafx.scene.layout.BorderPane;
                public abstract class ListViewBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.collections.FXCollections;
                import javafx.collections.ObservableList;
                public class ListView extends ListViewBase {
                    public final ObservableList<String> items =
                        FXCollections.observableArrayList("a", "b", "c");
                }
                """);
        getFixture().configureByText("ListView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.Label?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.ListView">
                  <Label text="${items.size}"/>
                </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A path whose intermediate segment is an {@code ObservableSet} provides
     * content-based invalidation and should not produce a non-observable error.
     */
    @Test
    void observableSetIntermediateSegmentMakesPathValid() {
        getFixture().addClass("""
                package test;
                import javafx.collections.FXCollections;
                import javafx.collections.ObservableSet;
                import javafx.scene.layout.BorderPane;
                public abstract class SetViewBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.collections.FXCollections;
                import javafx.collections.ObservableSet;
                public class SetView extends SetViewBase {
                    public final ObservableSet<String> tags =
                        FXCollections.observableSet("x", "y");
                }
                """);
        getFixture().configureByText("SetView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.Label?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.SetView">
                  <Label text="${tags.size}"/>
                </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A path whose intermediate segment is an {@code ObservableMap} provides
     * content-based invalidation and should not produce a non-observable error.
     */
    @Test
    void observableMapIntermediateSegmentMakesPathValid() {
        getFixture().addClass("""
                package test;
                import javafx.collections.FXCollections;
                import javafx.collections.ObservableMap;
                import javafx.scene.layout.BorderPane;
                public abstract class MapViewBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.collections.FXCollections;
                import javafx.collections.ObservableMap;
                public class MapView extends MapViewBase {
                    public final ObservableMap<String, Integer> scores =
                        FXCollections.observableHashMap();
                }
                """);
        getFixture().configureByText("MapView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.Label?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.MapView">
                  <Label text="${scores.size}"/>
                </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A mid-path {@code ::} operator (e.g. {@code vm::items.size}) keeps the observable
     * instance as the context for the following segment.  When the view model holds a
     * {@code ListProperty<String>} accessible via {@code itemsProperty()}, the path
     * {@code vm::items.size} resolves {@code items} as a raw {@code ListProperty}, allowing
     * the subsequent {@code .size} to find {@code ListProperty.sizeProperty()}.
     */
    @Test
    void midPathObservableSelectionOperatorProducesNoError() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ListProperty;
                import javafx.beans.property.SimpleListProperty;
                import javafx.collections.FXCollections;
                public class ItemViewModel {
                    private final ListProperty<String> items =
                        new SimpleListProperty<>(FXCollections.observableArrayList());
                    public ListProperty<String> itemsProperty() { return items; }
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                import javafx.scene.layout.BorderPane;
                public abstract class ItemViewBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                public class ItemView extends ItemViewBase {
                    private final ObjectProperty<ItemViewModel> vm =
                        new SimpleObjectProperty<>(new ItemViewModel());
                    public ObjectProperty<ItemViewModel> vmProperty() { return vm; }
                }
                """);
        getFixture().configureByText("ItemView.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.Label?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.ItemView">
                  <Label text="${vm::items.size}"/>
                </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * When a path segment is accessed via a property accessor ({@code xProperty()}) that
     * returns an {@code ObjectProperty<ViewModel>}, and the {@code ::} operator is applied to
     * the following segment (e.g. {@code $vm::name}), the segment {@code name} must be resolved
     * against the value type of the property ({@code ViewModel}), not against the raw
     * observable wrapper class ({@code ObjectProperty}).
     *
     * <p>Resolving against the raw {@code ObjectProperty} type would incorrectly find
     * {@code ReadOnlyProperty.getName()} (which returns {@code String}, not an
     * {@code ObservableValue}) and produce a false-positive error.
     */
    @Test
    void observableSelectionViaPropertyAccessorProducesNoError() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.StringProperty;
                import javafx.beans.property.SimpleStringProperty;
                public class PropViewModel {
                    private final StringProperty name = new SimpleStringProperty(this, "name", "");
                    public StringProperty nameProperty() { return name; }
                    public String getName() { return name.get(); }
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                import javafx.scene.layout.BorderPane;
                public abstract class PropViewBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                public class PropView extends PropViewBase {
                    private final ObjectProperty<PropViewModel> vm =
                        new SimpleObjectProperty<>(new PropViewModel());
                    public ObjectProperty<PropViewModel> vmProperty() { return vm; }
                }
                """);
        getFixture().configureByText("PropView.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.Label?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.PropView">
                  <Label text="$vm::name"/>
                </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Observable selection operator :: on plain getter returning observable
    // -----------------------------------------------------------------------

    /**
     * When a path segment is accessed via a plain getter method (e.g. {@code getVm()}) that
     * returns an {@code ObjectProperty<ViewModel>}, and the {@code ::} operator is applied to
     * the following segment (e.g. {@code $vm::name}), the segment "name" must be resolved
     * against the value type of the property ({@code ViewModel}), not against the raw
     * observable wrapper class ({@code ObjectProperty}).
     *
     * <p>Resolving against the raw {@code ObjectProperty} type would incorrectly find
     * {@code ReadOnlyProperty.getName()} (which returns {@code String}, not an
     * {@code ObservableValue}) and produce a false positive
     * {@code INVALID_INVARIANT_REFERENCE} error.
     */
    @Test
    void observableSelectionOnSegmentAfterPlainGetterProducesNoError() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.StringProperty;
                import javafx.beans.property.SimpleStringProperty;
                public class LabelViewModel {
                    private final StringProperty label = new SimpleStringProperty(this, "label", "");
                    public StringProperty labelProperty() { return label; }
                    public String getLabel() { return label.get(); }
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                import javafx.scene.layout.BorderPane;
                public abstract class WrapperViewBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        // WrapperView has getVm() but no vmProperty(): a plain getter returning an ObjectProperty.
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                public class WrapperView extends WrapperViewBase {
                    private final ObjectProperty<LabelViewModel> vm =
                        new SimpleObjectProperty<>(new LabelViewModel());
                    public ObjectProperty<LabelViewModel> getVm() { return vm; }
                }
                """);
        getFixture().configureByText("WrapperView.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.Label?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.WrapperView">
                  <Label text="$vm::<caret>label"/>
                </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Ctrl+click on a segment accessed via {@code ::} after a plain getter must navigate
     * to the property accessor ({@code labelProperty()}) on the value type, not to an
     * unrelated getter on the observable wrapper class.
     */
    @Test
    void observableSelectionOnSegmentAfterPlainGetterNavigatesToPropertyAccessor() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.StringProperty;
                import javafx.beans.property.SimpleStringProperty;
                public class LabelViewModel2 {
                    private final StringProperty label = new SimpleStringProperty(this, "label", "");
                    public StringProperty labelProperty() { return label; }
                    public String getLabel() { return label.get(); }
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                import javafx.scene.layout.BorderPane;
                public abstract class WrapperView2Base extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                public class WrapperView2 extends WrapperView2Base {
                    private final ObjectProperty<LabelViewModel2> vm =
                        new SimpleObjectProperty<>(new LabelViewModel2());
                    public ObjectProperty<LabelViewModel2> getVm() { return vm; }
                }
                """);
        getFixture().configureByText("WrapperView2.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.Label?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.WrapperView2">
                  <Label text="$vm::<caret>label"/>
                </BorderPane>
                """);
        PsiElement resolved = resolveSegmentAtCaret();
        assertNotNull(resolved, "Expected 'label' segment to resolve to a PSI element");
        // Must navigate to the labelProperty() method on LabelViewModel2, not to getName()
        // inherited from ReadOnlyProperty via ObjectProperty.
        assertInstanceOf(PsiMethod.class, resolved,
                "Expected 'label' to resolve to a method, got: " + resolved);
        PsiMethod method = (PsiMethod) resolved;
        assertEquals("labelProperty", method.getName(),
                "Expected navigation to labelProperty() on LabelViewModel2, but got: "
                + method.getName() + " in " + Objects.requireNonNull(method.getContainingClass()).getQualifiedName());
        assertEquals("test.LabelViewModel2", method.getContainingClass().getQualifiedName(),
                "Expected labelProperty() to be declared in LabelViewModel2");
    }

    /**
     * Resolves the {@link Fxml2BindingSegmentReference} at the caret offset.
     */
    @SuppressWarnings("SameParameterValue")
    private PsiElement resolveSegmentAtCaret() {
        return ReadAction.compute(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue attrVal = com.intellij.psi.util.PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            if (attrVal == null) return null;
            int relOffset = offset - attrVal.getTextRange().getStartOffset();
            for (PsiReference ref : attrVal.getReferences()) {
                if (!(ref instanceof Fxml2BindingSegmentReference)) continue;
                if (ref.getRangeInElement().containsOffset(relOffset)) {
                    return ref.resolve();
                }
            }
            return null;
        });
    }

    // -----------------------------------------------------------------------
    // Batch-apply support
    // -----------------------------------------------------------------------

    /**
     * The "Use evaluate binding" quick-fix must implement {@link BatchQuickFix} so that
     * "Fix all in file" is available when multiple non-observable bindings exist.
     */
    @Test
    void convertToEvaluateBindingFixIsBatchApplicable() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="${<caret>plainField}"/>
                """
        ));
        getFixture().doHighlighting();
        var fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && "Use evaluate binding ($source)".equals(fix.getFamilyName());
                })
                .toList();
        assertFalse(fixes.isEmpty(), "Expected 'Use evaluate binding ($source)' quick-fix");
        var fix = QuickFixWrapper.unwrap(fixes.getFirst());
        assertInstanceOf(BatchQuickFix.class, fix,
                "ConvertToEvaluateBindingFix should implement BatchQuickFix for 'Fix all in file' support");
    }
}
