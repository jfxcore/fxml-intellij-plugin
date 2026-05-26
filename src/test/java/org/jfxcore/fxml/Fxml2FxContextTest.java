package org.jfxcore.fxml;

import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.jfxcore.fxml.annotator.Fxml2UnusedImportsInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tests for {@code fx:context} binding-context resolution.
 *
 * <p>The FXML compiler allows overriding the default binding context (the code-behind class)
 * via {@code fx:context} on the root tag. Two forms are supported:
 * <ol>
 *   <li><b>Attribute / binding-path form</b>: {@code fx:context="$myContext"}: the context
 *       is a property/field on the code-behind class.</li>
 *   <li><b>Element-notation form</b>: {@code <fx:context><MyCtx/></fx:context>}: the context
 *       is an inline object defined in the FXML document.</li>
 * </ol>
 *
 * <p>Binding paths inside the document are then resolved against the context class instead
 * of the code-behind class. Static fields inherited from the root element's type (e.g.
 * {@code USE_PREF_SIZE} from {@code Region}) continue to resolve, since the code-behind
 * effectively extends the root element type via the compiler-generated base class.
 * However, when {@code fx:context} IS set, the root-tag fallback does NOT apply to paths
 * resolved against the context object itself.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2FxContextTest extends Fxml2TestBase {

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2AttributeValueInspection());
    }

    @BeforeEach
    void addClasses() {
        // Compiler-generated base class: present in these tests to keep the chain intact
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public abstract class TestViewBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        // Code-behind class: exposes myContext as a field
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                public class TestView extends TestViewBase {
                    public final MyContext myContext = new MyContext();
                    public MyContext getMyContext() { return myContext; }
                }
                """);
        // Context class: used as the binding context via fx:context
        getFixture().addClass("""
                package test;
                import javafx.beans.property.StringProperty;
                import javafx.beans.property.SimpleStringProperty;
                public class MyContext {
                    private final StringProperty userName =
                            new SimpleStringProperty(this, "userName", "");
                    public StringProperty userNameProperty() { return userName; }
                    public String getUserName()  { return userName.get(); }
                    public void setUserName(String v) { userName.set(v); }
                }
                """);
    }

    // -----------------------------------------------------------------------
    // Attribute (binding-path) form: fx:context="$myContext"
    // -----------------------------------------------------------------------

    /**
     * When {@code fx:context="$myContext"} is set, a binding path like
     * {@code ${userName}} is resolved against {@code MyContext}, not the code-behind.
     */
    @Test
    void bindingPathResolvesAgainstContextClassAttributeForm() {
        getFixture().configureByText("CtxAttr.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.Label?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.TestView"
                            fx:context="$myContext">
                  <Label text="${userName}"/>
                </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A property that exists only on the code-behind class (not on the context class)
     * must produce an error when fx:context is set.
     */
    @Test
    void bindingPathOnCodeBehindOnlyPropertyProducesErrorWhenContextSet() {
        getFixture().configureByText("CtxAttrErr.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.Label?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.TestView"
                            fx:context="$myContext">
                  <Label text="${<error descr="'myContext' in test.MyContext cannot be resolved">myContext</error>}"/>
                </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Element-notation form: <fx:context><MyContext/></fx:context>
    // -----------------------------------------------------------------------

    /**
     * When {@code fx:context} is an inline element, a binding path is resolved against
     * the class of the inline object.
     */
    @Test
    void bindingPathResolvesAgainstContextClassElementForm() {
        getFixture().configureByText("CtxElem.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.Label?>
                <?import test.MyContext?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.TestView">
                  <fx:context>
                    <MyContext/>
                  </fx:context>
                  <Label text="${userName}"/>
                </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * An unresolvable path against the element-notation context class must produce an error.
     */
    @Test
    void bindingPathOnMissingPropertyProducesErrorWithElementContextForm() {
        getFixture().configureByText("CtxElemErr.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.Label?>
                <?import test.MyContext?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.TestView">
                  <fx:context>
                    <MyContext/>
                  </fx:context>
                  <Label text="${<error descr="'noSuchProp' in test.MyContext cannot be resolved">noSuchProp</error>}"/>
                </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Static field (USE_PREF_SIZE): no fx:context
    // -----------------------------------------------------------------------

    /**
     * Without {@code fx:context}, the default context is the code-behind class.
     * {@code $USE_PREF_SIZE} resolves via the code-behind's supertype chain
     * (code-behind -> TestViewBase -> BorderPane -> Region).
     */
    @Test
    void staticFieldResolvesWithDefaultContext() {
        getFixture().configureByText("NoCtxStatic.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.TextArea?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.TestView">
                  <TextArea minHeight="$USE_PREF_SIZE"/>
                </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Static field (USE_PREF_SIZE) when fx:context IS set
    // -----------------------------------------------------------------------

    /**
     * When {@code fx:context="$myContext"} is set, static fields accessible from
     * the root tag type (e.g. {@code USE_PREF_SIZE} from {@code Region}) must still
     * be reachable with a FQN or via the root-element class prefix.
     * A bare {@code $USE_PREF_SIZE} is an instance-path segment resolved against
     * {@code MyContext}: it is NOT on {@code MyContext}, so it must be an error.
     */
    @Test
    void bareStaticFieldFromRootTagIsNotFoundOnContextClass() {
        getFixture().configureByText("CtxAttrStatic.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.TextArea?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.TestView"
                            fx:context="$myContext">
                  <TextArea minHeight="$<error descr="'USE_PREF_SIZE' in test.MyContext cannot be resolved">USE_PREF_SIZE</error>"/>
                </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Unresolvable class inside <fx:context> element notation
    // -----------------------------------------------------------------------

    /**
     * A class name inside {@code <fx:context>} that cannot be imported must produce a
     * "Cannot resolve symbol" error so that the auto-import quick-fix is offered.
     */
    @Test
    void unresolvableClassInsideFxContextElementProducesError() {
        getFixture().configureByText("CtxElemBadClass.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.TestView">
                  <fx:context>
                    <<error descr="Cannot resolve symbol 'CannotResolveMe123'">CannotResolveMe123</error>/>
                  </fx:context>
                </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A valid (imported) class inside {@code <fx:context>} must produce no error.
     */
    @Test
    void importedClassInsideFxContextElementProducesNoError() {
        getFixture().configureByText("CtxElemGoodClass.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import test.MyContext?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.TestView">
                  <fx:context>
                    <MyContext/>
                  </fx:context>
                </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A fully-qualified static field reference ({@code $Region.USE_PREF_SIZE}) must always
     * resolve regardless of whether {@code fx:context} is set.
     */
    @Test
    void fullyQualifiedStaticFieldResolvesWithContextSet() {
        getFixture().configureByText("CtxAttrStaticFqn.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.TextArea?>
                <?import javafx.scene.layout.Region?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.TestView"
                            fx:context="$myContext">
                  <TextArea minHeight="$Region.USE_PREF_SIZE"/>
                </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Observable binding form: fx:context="${observableProperty}"
    // -----------------------------------------------------------------------

    /**
     * {@code fx:context} can be bound with a unidirectional observable binding
     * ({@code ${...}}) when the context object may change at runtime.
     * The attribute value must be accepted without error.
     */
    @Test
    void fxContextWithObservableBindingProducesNoError() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                public class TestViewObsCtx extends TestViewBase {
                    private final ObjectProperty<MyContext> ctx =
                            new SimpleObjectProperty<>(this, "ctx", new MyContext());
                    public ObjectProperty<MyContext> ctxProperty() { return ctx; }
                    public MyContext getCtx() { return ctx.get(); }
                }
                """);
        getFixture().configureByText("ObsCtx.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.Label?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.TestViewObsCtx"
                            fx:context="${ctx}">
                  <Label text="${userName}"/>
                </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Unused import: class referenced only in element-notation fx:context
    // -----------------------------------------------------------------------

    /**
     * An {@code <?import?>} PI for a class referenced only inside
     * {@code <fx:context><MyContext/></fx:context>} must NOT be flagged as unused.
     */
    @Test
    void importOfContextClassInElementNotationIsNotUnused() {
        getFixture().enableInspections(new Fxml2UnusedImportsInspection());
        getFixture().configureByText("CtxElemImport.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import test.MyContext?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.TestView">
                  <fx:context>
                    <MyContext/>
                  </fx:context>
                </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, true);
    }
}
