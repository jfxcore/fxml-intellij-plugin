package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.util.PsiTreeUtil;
import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.jfxcore.fxml.annotator.Fxml2NonObservableBindingSourceInspection;
import org.jfxcore.fxml.lang.Fxml2BindingSegmentReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for binding expression resolution and error reporting in FXML attribute values.
 *
 * <p>Covers all three compact binding syntaxes ({@code $...}, {@code ${...}},
 * {@code #{...}}) as well as the explicit
 * {@code {fx:Evaluate}, {fx:Observe}, {fx:Synchronize}} element syntax.
 * Parent selectors and static-constant paths are also included.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2BindingExpressionTest extends Fxml2TestBase {

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(
                new Fxml2AttributeValueInspection(),
                new Fxml2NonObservableBindingSourceInspection());
    }

    @BeforeEach
    void addCodeBehind() {
        // Minimal generated base class (normally produced by the FXML compiler)
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public abstract class TestViewBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        // Code-behind with observable properties used by binding tests
        getFixture().addClass("""
                package test;
                import javafx.beans.property.StringProperty;
                import javafx.beans.property.SimpleStringProperty;
                import javafx.beans.property.DoubleProperty;
                import javafx.beans.property.SimpleDoubleProperty;
                import javafx.beans.property.BooleanProperty;
                import javafx.beans.property.SimpleBooleanProperty;
                public class TestView extends TestViewBase {
                    private final StringProperty message = new SimpleStringProperty(this, "message", "");
                    public StringProperty messageProperty() { return message; }
                    public String getMessage() { return message.get(); }
                    public void setMessage(String v) { message.set(v); }

                    private final DoubleProperty width2 = new SimpleDoubleProperty(this, "width2", 0);
                    public DoubleProperty width2Property() { return width2; }
                    public double getWidth2() { return width2.get(); }
                    public void setWidth2(double v) { width2.set(v); }

                    private final BooleanProperty flag = new SimpleBooleanProperty(this, "flag", false);
                    public BooleanProperty flagProperty() { return flag; }
                    public boolean isFlag() { return flag.get(); }
                    public void setFlag(boolean v) { flag.set(v); }
                }
                """);
    }

    @Test
    void fxEvaluateExplicitSyntaxResolvesWithoutError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Evaluate message}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void fxObserveExplicitSyntaxResolvesWithoutError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Observe message}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void fxSynchronizeExplicitSyntaxResolvesWithoutError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Synchronize message}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Compact binding syntaxes
    // -----------------------------------------------------------------------

    @Test
    void dollarEvaluateCompactSyntaxResolvesWithoutError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="$message"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void dollarBraceBindCompactSyntaxResolvesWithoutError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${message}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void hashBraceBindBidirectionalCompactSyntaxResolvesWithoutError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="#{message}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Nonexistent path
    // -----------------------------------------------------------------------

    @Test
    void dollarEvaluateToNonexistentPropertyProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="$<error descr="'nonExistent' in test.TestView cannot be resolved">nonExistent</error>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void fxObserveToNonexistentPropertyProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Observe <error descr="'nonExistent' in test.TestView cannot be resolved">nonExistent</error>}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Multi-segment path
    // -----------------------------------------------------------------------

    @Test
    void multiSegmentBindingPathResolvesWithoutError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Observe message}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Static constant path
    // -----------------------------------------------------------------------

    /** {@code $Region.USE_PREF_SIZE} is a valid Evaluate ({@code $}) binding source. */
    @Test
    void dollarEvaluateToStaticConstantProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.Region",
                """
                  <Region maxWidth="$Region.USE_PREF_SIZE"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** {@code ${Region.USE_PREF_SIZE}} is not observable and must produce an error. */
    @Test
    void dollarBraceToStaticConstantProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.Region",
                """
                  <Region maxWidth=<error descr="Region.USE_PREF_SIZE is not a valid binding source, required javafx.beans.value.ObservableValue">"${Region.USE_PREF_SIZE}"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Parent selector
    // -----------------------------------------------------------------------

    @Test
    void parentSelectorBindingResolvesWithoutError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.Pane",
                """
                  <Pane fx:id="outer" prefWidth="123">
                    <Pane prefWidth="${parent/prefWidth}"/>
                  </Pane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code parent<MyType>} selects the first ancestor whose type is {@code MyType}.
     * {@code ${parent<Pane>/prefWidth}} (written as {@code &lt;Pane&gt;} in XML) should
     * resolve {@code prefWidth} against the nearest {@code Pane} ancestor.
     */
    @Test
    void parentTypeSelectorResolvesWithoutError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.Pane",
                """
                  <Pane prefWidth="100">
                    <Pane prefWidth="${parent&lt;Pane&gt;/prefWidth}"/>
                  </Pane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code parent<MyType>[N]} selects the N-th ancestor of type {@code MyType}.
     * Using index 1 should select the second matching ancestor.
     */
    @Test
    void parentTypeSelectorWithIndexResolvesWithoutError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.Pane",
                """
                  <Pane prefWidth="200">
                    <Pane prefWidth="100">
                      <Pane prefWidth="${parent&lt;Pane&gt;[1]/prefWidth}"/>
                    </Pane>
                  </Pane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * When the path segment after {@code parent<MyType>/} does not exist on the type,
     * an error must be produced on the unresolvable segment.
     */
    @Test
    void parentTypeSelectorWithUnresolvablePathProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.Pane",
                """
                  <Pane>
                    <Pane prefWidth="${parent&lt;Pane&gt;/<error descr="'noSuchProperty' in javafx.scene.layout.Pane cannot be resolved">noSuchProperty</error>}"/>
                  </Pane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Path expression in element content
    // -----------------------------------------------------------------------

    /** Inside {@code <fx:Evaluate>...</fx:Evaluate>} the binding source must be given via the
     *  {@code source} attribute, not as text content. */
    @Test
    void pathExpressionInsideFxEvaluateElementContentProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label>
                    <text>
                      <fx:Evaluate><error descr="Invalid expression">message</error></fx:Evaluate>
                    </text>
                  </Label>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Explicit source= property in binding intrinsic
    // -----------------------------------------------------------------------

    /** {@code text="${source=message}"} is a valid explicit source property form. */
    @Test
    void explicitPathPropertyInBindingIntrinsicResolvesWithoutError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${source=message}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Performance: incomplete static-field path must not freeze analysis
    // -----------------------------------------------------------------------

    /**
     * An unresolvable field in a fully-qualified static-field path (e.g.
     * {@code {fx:Evaluate javafx.scene.layout.Region.USE_PREF_S}}) must produce an error
     * and complete promptly.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void incompleteStaticFieldPathInFxEvaluateIsAnalyzedPromptly() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.Region",
                """
                  <Region minHeight="{fx:Evaluate javafx.scene.layout.Region.<error descr="'USE_PREF_S' in javafx.scene.layout.Region cannot be resolved">USE_PREF_S</error>}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A bare static-field reference (e.g. {@code $USE_PREF_SIZE}) resolves when the
     * code-behind class inherits the field from a superclass.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bareStaticFieldInheritedFromSuperclassResolves() {
        // TestView extends TestViewBase extends BorderPane extends Region.
        // USE_PREF_SIZE is a static field on Region.
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.TextArea",
                """
                  <TextArea minHeight="$USE_PREF_SIZE"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * When the compiler-generated base class is missing (project not yet built),
     * the code-behind's supertype chain is broken. A bare static field reference like
     * {@code $USE_PREF_SIZE} should still resolve via the root tag's element type
     * (the compiler always generates {@code class FooBase extends <rootTagType>}).
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void bareStaticFieldResolvesViaRootTagClassWhenBaseClassAbsent() {
        // Simulate a stale build: the code-behind exists but NOT its compiler-generated
        // base class. The code-behind directly extends Object here.
        getFixture().addFileToProject("test/StaleView.java",
                """
                package test;
                // Normally: extends StaleViewBase extends BorderPane extends Region
                // Here StaleViewBase is absent, simulating a pre-build / stale state.
                public class StaleView {
                }
                """);
        // Root tag is BorderPane (which extends Region, which has USE_PREF_SIZE).
        // The file uses fx:subclass="test.StaleView" so startClass = StaleView.
        // Without the fallback, USE_PREF_SIZE would be unresolvable.
        getFixture().configureByText("StaleView.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.TextArea?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.StaleView">
                  <TextArea minHeight="$USE_PREF_SIZE"/>
                </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * When the compiler-generated base class is missing (project not yet built),
     * instance properties inherited from the root tag type must still resolve.
     * E.g. {@code ${this.height}} on a {@code BorderPane} root should resolve even
     * when {@code MainViewBase} (which would extend {@code BorderPane}) is absent.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void instancePropertyResolvesViaRootTagClassWhenBaseClassAbsent() {
        getFixture().addFileToProject("test/StaleView2.java",
                """
                package test;
                // Normally: extends StaleView2Base extends BorderPane
                // Here the base class is absent, simulating a pre-build / stale state.
                public class StaleView2 {
                }
                """);
        getFixture().configureByText("StaleView2.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.shape.Ellipse?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.StaleView2">
                  <Ellipse radiusY="${this.height}"/>
                </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * When a binding path uses a {@code parent[N]/} context selector and the first
     * path segment is unresolvable, the reference range must start at the first
     * character of the segment name: not one position to the right.
     *
     * <p>The {@code parent[N]/} selector length was off-by-one in
     * {@code Fxml2BindingExpressionParser.parseContextSelector}, causing the annotator
     * to compute {@code base} one too large and highlight {@code electionModel.}
     * instead of {@code selectionModel}.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void parentSelectorSegmentRangeIsCorrect() {
        getFixture().configureByText("ParentSelectorRange.fxml", fxml(
                "javafx.scene.layout.VBox",
                // parent[1] = the outer VBox (root), which has no "selectionModel"
                """
                  <VBox>
                    <VBox parameter="${parent[1]/selectionModel<caret>.selectedItem}"/>
                  </VBox>
                """
        ));
        ReadAction.run(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue attrVal = PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            assertNotNull(attrVal, "Expected XmlAttributeValue at caret");
            String attrText = attrVal.getText(); // includes surrounding quotes
            int selIdx = attrText.indexOf("selectionModel");
            assertTrue(selIdx > 0, "Expected 'selectionModel' inside attribute value text, got: " + attrText);
            // Find the Fxml2BindingSegmentReference whose range starts at selIdx
            boolean found = false;
            for (PsiReference ref : attrVal.getReferences()) {
                if (!(ref instanceof Fxml2BindingSegmentReference)) continue;
                var range = ref.getRangeInElement();
                if (range.getStartOffset() == selIdx) {
                    assertEquals(selIdx + "selectionModel".length(), range.getEndOffset(),
                            "Range must end right after 'selectionModel'");
                    found = true;
                    break;
                }
            }
            assertTrue(found, "Expected a Fxml2BindingSegmentReference starting exactly at 'selectionModel' "
                    + "(offset " + selIdx + ") in: " + attrText);
        });
    }

    // -----------------------------------------------------------------------
    // Angle-bracket type context selector: parent<Type> and parent<Type>[N]
    // -----------------------------------------------------------------------

    /**
     * Compiler: {@code ${parent<Pane>/prefWidth}}: the angle-bracket type context selector.
     * The first path segment after the selector must be resolved against the matching ancestor.
     * Using {@code &lt;VBox&gt;} (XML-safe form of {@code <VBox>}); after unescaping the plugin
     * sees {@code parent<VBox>/prefWidth}.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void parentTypeContextSelectorResolvesProperty() {
        getFixture().configureByText("ParentTypeSelector.fxml", fxml(
                "javafx.scene.layout.VBox",
                """
                  <VBox prefWidth="123">
                    <VBox prefWidth="${parent&lt;VBox&gt;/prefWidth}"/>
                  </VBox>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Compiler: {@code ${parent<Pane>[0]/prefWidth}}: type context selector with numeric index.
     * Corresponds to the compiler's new {@code parent<Type>[N]} syntax.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void parentTypeAndIndexContextSelectorResolvesProperty() {
        getFixture().configureByText("ParentTypeIndexSelector.fxml", fxml(
                "javafx.scene.layout.VBox",
                """
                  <VBox prefWidth="123">
                    <VBox>
                      <VBox prefWidth="${parent&lt;VBox&gt;[0]/prefWidth}"/>
                    </VBox>
                  </VBox>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Compiler: {@code $parent<VBox>/prefWidth}: compact {@code $} notation with the
     * angle-bracket type context selector.  Any {@code <} inside a compact {@code $} path
     * must be parsed as a type context selector, not as a malformed type witness.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void parentTypeContextSelectorInDollarCompactSyntaxResolvesProperty() {
        getFixture().configureByText("ParentTypeSelectorDollar.fxml", fxml(
                "javafx.scene.layout.VBox",
                """
                  <VBox prefWidth="123">
                    <VBox prefWidth="$parent&lt;VBox&gt;/prefWidth"/>
                  </VBox>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * The segment-reference range after a {@code parent<Type>/} context selector must start
     * at the first character of the property name, not shifted by the selector length.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void parentTypeContextSelectorSegmentRangeIsCorrect() {
        getFixture().configureByText("ParentTypeSelectorRange.fxml", fxml(
                "javafx.scene.layout.VBox",
                // parent<VBox> = the outer VBox (root), which has no "selectionModel"
                """
                  <VBox>
                    <VBox parameter="${parent&lt;VBox&gt;/selectionModel<caret>.selectedItem}"/>
                  </VBox>
                """
        ));
        ReadAction.run(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue attrVal = PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            assertNotNull(attrVal, "Expected XmlAttributeValue at caret");
            String attrText = attrVal.getText(); // includes surrounding quotes
            int selIdx = attrText.indexOf("selectionModel");
            assertTrue(selIdx > 0, "Expected 'selectionModel' in attribute value text, got: " + attrText);
            boolean found = false;
            for (PsiReference ref : attrVal.getReferences()) {
                if (!(ref instanceof Fxml2BindingSegmentReference)) continue;
                var range = ref.getRangeInElement();
                if (range.getStartOffset() == selIdx) {
                    assertEquals(selIdx + "selectionModel".length(), range.getEndOffset(),
                            "Range must end right after 'selectionModel'");
                    found = true;
                    break;
                }
            }
            assertTrue(found, "Expected a Fxml2BindingSegmentReference starting exactly at 'selectionModel' "
                    + "(offset " + selIdx + ") in: " + attrText);
        });
    }

    // -----------------------------------------------------------------------
    // Bidirectional binding to read-only property
    // -----------------------------------------------------------------------

    /**
     * A {@code ReadOnlyStringProperty} is observable but not writable.
     * The FXML compiler rejects {@code #{readOnlyCaption}} with
     * "is not a valid bidirectional binding source, required javafx.beans.property.Property".
     * The plugin must report the same error.
     */
    @Test
    void synchronizeToReadOnlyPropertyProducesError() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ReadOnlyStringProperty;
                import javafx.beans.property.ReadOnlyStringWrapper;
                import javafx.scene.layout.BorderPane;
                public class ReadOnlyView extends BorderPane {
                    private final ReadOnlyStringWrapper readOnlyCaption =
                            new ReadOnlyStringWrapper(this, "readOnlyCaption", "");
                    public ReadOnlyStringProperty readOnlyCaptionProperty() {
                        return readOnlyCaption.getReadOnlyProperty();
                    }
                    public String getReadOnlyCaption() { return readOnlyCaption.get(); }
                }
                """);
        getFixture().configureByText("ReadOnlyView.fxml", fxml(
                "javafx.scene.control.Label",
                "  <Label text=" + error(
                        "readOnlyCaption is not a valid bidirectional binding source,"
                        + " required javafx.beans.property.Property",
                        "\"#{readOnlyCaption}\"") + "/>\n",
                "test.ReadOnlyView"
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A {@code StringProperty} (writable) used with {@code #{}} must not produce an error,
     * since it satisfies the {@code javafx.beans.property.Property} requirement.
     */
    @Test
    void synchronizeToWritablePropertyProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="#{message}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Multi-segment path: {@code #{vm.readOnlyCaption}} where {@code vm} is an
     * {@code ObjectProperty<ViewModel>} and {@code readOnlyCaption} on the view-model
     * is a {@code ReadOnlyStringProperty}. The terminal segment is read-only, so the
     * bidirectional binding must be rejected.
     */
    @Test
    void synchronizeMultiSegmentToReadOnlyPropertyProducesError() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ReadOnlyStringProperty;
                import javafx.beans.property.ReadOnlyStringWrapper;
                public class ReadOnlyViewModel {
                    private final ReadOnlyStringWrapper readOnlyCaption =
                            new ReadOnlyStringWrapper(this, "readOnlyCaption", "");
                    public ReadOnlyStringProperty readOnlyCaptionProperty() {
                        return readOnlyCaption.getReadOnlyProperty();
                    }
                    public String getReadOnlyCaption() { return readOnlyCaption.get(); }
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                import javafx.scene.layout.BorderPane;
                public class VmView extends BorderPane {
                    private final ObjectProperty<ReadOnlyViewModel> vm =
                            new SimpleObjectProperty<>(this, "vm", new ReadOnlyViewModel());
                    public ObjectProperty<ReadOnlyViewModel> vmProperty() { return vm; }
                    public ReadOnlyViewModel getVm() { return vm.get(); }
                    public void setVm(ReadOnlyViewModel v) { vm.set(v); }
                }
                """);
        getFixture().configureByText("VmView.fxml", fxml(
                "javafx.scene.control.Label",
                "  <Label text=" + error(
                        "vm.readOnlyCaption is not a valid bidirectional binding source,"
                        + " required javafx.beans.property.Property",
                        "\"#{vm.readOnlyCaption}\"") + "/>\n",
                "test.VmView"
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Element-notation {@code <fx:Synchronize source="readOnlyCaption"/>} where
     * {@code readOnlyCaption} is a {@code ReadOnlyStringProperty} must be reported as an error,
     * since bidirectional binding requires a writable {@code javafx.beans.property.Property}.
     */
    @Test
    void fxSynchronizeElementNotationToReadOnlyPropertyProducesError() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ReadOnlyStringProperty;
                import javafx.beans.property.ReadOnlyStringWrapper;
                import javafx.scene.layout.BorderPane;
                public class ReadOnlyView2 extends BorderPane {
                    private final ReadOnlyStringWrapper readOnlyCaption =
                            new ReadOnlyStringWrapper(this, "readOnlyCaption", "");
                    public ReadOnlyStringProperty readOnlyCaptionProperty() {
                        return readOnlyCaption.getReadOnlyProperty();
                    }
                    public String getReadOnlyCaption() { return readOnlyCaption.get(); }
                }
                """);
        getFixture().configureByText("ReadOnlyView2.fxml", fxml(
                "javafx.scene.control.Label",
                "  <Label>\n"
                + "    <text>\n"
                + "      <fx:Synchronize source=" + error(
                        "readOnlyCaption is not a valid bidirectional binding source,"
                        + " required javafx.beans.property.Property",
                        "\"readOnlyCaption\"") + "/>\n"
                + "    </text>\n"
                + "  </Label>\n",
                "test.ReadOnlyView2"
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Compact push binding (>{...}) navigation
    // -----------------------------------------------------------------------

    /**
     * Ctrl+click on a path segment inside a compact push binding ({@code >{message}}) must
     * navigate to the property accessor method.
     */
    @Test
    void ctrlClickOnPathSegmentOfPushBindingResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text=">{mes<caret>sage}"/>
                """
        ));
        PsiElement target = resolveSegmentAtCaret();
        assertInstanceOf(PsiMethod.class, target,
                "Ctrl+click on 'message' in a >{...} binding should resolve to the property accessor");
    }

    // -----------------------------------------------------------------------
    // Incomplete binding expressions: missing closing '}'
    // -----------------------------------------------------------------------

    /**
     * A compact {@code ${...}} binding expression without a closing {@code }} must be reported
     * as a {@code '} expected} error on the whole attribute value.
     */
    @Test
    void missingClosingBraceInDollarBraceBindingProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text=<error descr="'}' expected">"${message"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * An explicit {@code {fx:Evaluate ...}} binding expression without a closing {@code }} must
     * be reported as a {@code '} expected} error on the whole attribute value.
     */
    @Test
    void missingClosingBraceInFxEvaluateSyntaxProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text=<error descr="'}' expected">"{fx:Evaluate message"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Navigation on partially-typed (unterminated) binding expressions
    // -----------------------------------------------------------------------

    /**
     * A compact observe binding ({@code ${...}}) with no closing brace must still resolve
     * its already-complete path segment for Ctrl+click navigation.
     */
    @Test
    void ctrlClickOnPathSegmentOfUnterminatedObserveBindingResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${mes<caret>sage"/>
                """
        ));
        PsiElement target = resolveSegmentAtCaret();
        assertInstanceOf(PsiMethod.class, target,
                "Ctrl+click on 'message' in an unterminated ${...} binding should resolve to the property accessor");
    }

    /**
     * A compact bidirectional binding ({@code #{...}}) with no closing brace must still
     * resolve its already-complete path segment for Ctrl+click navigation.
     */
    @Test
    void ctrlClickOnPathSegmentOfUnterminatedSynchronizeBindingResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="#{mes<caret>sage"/>
                """
        ));
        PsiElement target = resolveSegmentAtCaret();
        assertInstanceOf(PsiMethod.class, target,
                "Ctrl+click on 'message' in an unterminated #{...} binding should resolve to the property accessor");
    }

    /**
     * A compact push binding ({@code >{...}}) with no closing brace must still resolve
     * its already-complete path segment for Ctrl+click navigation.
     */
    @Test
    void ctrlClickOnPathSegmentOfUnterminatedPushBindingResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text=">{mes<caret>sage"/>
                """
        ));
        PsiElement target = resolveSegmentAtCaret();
        assertInstanceOf(PsiMethod.class, target,
                "Ctrl+click on 'message' in an unterminated >{...} binding should resolve to the property accessor");
    }

    /**
     * A keyword observe binding ({@code {fx:Observe ...}}) with no closing brace must
     * still resolve its already-complete path segment for Ctrl+click navigation.
     */
    @Test
    void ctrlClickOnPathSegmentOfUnterminatedFxObserveBindingResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Observe mes<caret>sage"/>
                """
        ));
        PsiElement target = resolveSegmentAtCaret();
        assertInstanceOf(PsiMethod.class, target,
                "Ctrl+click on 'message' in an unterminated {fx:Observe ...} binding should resolve to the property accessor");
    }

    /**
     * A keyword synchronize binding ({@code {fx:Synchronize ...}}) with no closing brace
     * must still resolve its already-complete path segment for Ctrl+click navigation.
     */
    @Test
    void ctrlClickOnPathSegmentOfUnterminatedFxSynchronizeBindingResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Synchronize mes<caret>sage"/>
                """
        ));
        PsiElement target = resolveSegmentAtCaret();
        assertInstanceOf(PsiMethod.class, target,
                "Ctrl+click on 'message' in an unterminated {fx:Synchronize ...} binding should resolve to the property accessor");
    }

    /**
     * A keyword evaluate binding ({@code {fx:Evaluate ...}}) with no closing brace must
     * still resolve its already-complete path segment for Ctrl+click navigation.
     */
    @Test
    void ctrlClickOnPathSegmentOfUnterminatedFxEvaluateBindingResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Evaluate mes<caret>sage"/>
                """
        ));
        PsiElement target = resolveSegmentAtCaret();
        assertInstanceOf(PsiMethod.class, target,
                "Ctrl+click on 'message' in an unterminated {fx:Evaluate ...} binding should resolve to the property accessor");
    }

    /**
     * A keyword push binding ({@code {fx:Push ...}}) with no closing brace must still
     * resolve its already-complete path segment for Ctrl+click navigation.
     */
    @Test
    void ctrlClickOnPathSegmentOfUnterminatedFxPushBindingResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Push mes<caret>sage"/>
                """
        ));
        PsiElement target = resolveSegmentAtCaret();
        assertInstanceOf(PsiMethod.class, target,
                "Ctrl+click on 'message' in an unterminated {fx:Push ...} binding should resolve to the property accessor");
    }
}
