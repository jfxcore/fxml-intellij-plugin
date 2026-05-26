package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for event-handler syntax in FXML.
 *
 * <p>FXML/2 supports two forms of event handler assignment on code-behind classes:
 * <ol>
 *   <li><b>Field handlers</b>: {@code onAction="$myHandler"}: a {@code $}-prefixed reference
 *       to an {@code EventHandler}-typed field on the code-behind class.</li>
 *   <li><b>Method handlers</b>: {@code onAction="myMethod"}: a plain method name that refers
 *       to a method on the code-behind class whose signature is compatible with
 *       {@code EventHandler<ActionEvent>}. The {@code event} parameter is optional.
 *       The compiler infers from the property's target type ({@code EventHandler<E>}) that the
 *       value is a method reference, not a literal string.</li>
 * </ol>
 *
 * <p>Corresponds to {@code event-handlers.md} in the FXML compiler documentation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2EventHandlerTest extends Fxml2TestBase {

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2AttributeValueInspection());
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
                import javafx.event.ActionEvent;
                import javafx.event.EventHandler;
                import javafx.scene.input.MouseEvent;
                public class TestView extends TestViewBase {
                    public final EventHandler<ActionEvent> myActionHandler = event -> {};
                    public final String notAHandler = "hello";
                    public void handleAction(ActionEvent event) {}
                    public void handleActionNoParam() {}
                    public void mouseHandler(MouseEvent event) {}
                    public String returnsString() { return ""; }
                    public void tooManyParams(ActionEvent e1, ActionEvent e2) {}
                }
                """);
    }

    // -----------------------------------------------------------------------
    // Field event handlers via $ reference
    // -----------------------------------------------------------------------

    /**
     * {@code <Button onAction="$myActionHandler"/>}: references an EventHandler field
     * on the code-behind class. Should resolve without error.
     */
    @Test
    void fieldEventHandlerViaEvaluateReferenceProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button onAction="$myActionHandler"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Referencing a non-existent field as an event handler should produce an error.
     */
    @Test
    void fieldEventHandlerWithUnknownFieldProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button onAction="$<error descr="'nonExistentHandler' in test.TestView cannot be resolved">nonExistentHandler</error>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * the field referenced via {@code $} must have a compatible {@code EventHandler} type.
     * Using a field of an incompatible type (e.g. {@code String}) should produce an error.
     */
    @Test
    void fieldEventHandlerWithWrongTypeProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button onAction="<error descr="Incompatible types: 'String' cannot be assigned to 'EventHandler<ActionEvent>'">$notAHandler</error>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Method event handlers via plain method name
    // -----------------------------------------------------------------------

    /**
     * A method name without any prefix wires a method with a compatible signature when
     * the target property type is {@code EventHandler<E>}.
     */
    @Test
    void methodEventHandlerWithEventParamProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button onAction="handleAction"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * The {@code event} parameter is optional in the method signature.
     * A handler method with no parameters should be accepted.
     */
    @Test
    void methodEventHandlerWithoutParamProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button onAction="handleActionNoParam"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Referencing a non-existent method produces an error covering the full value.
     */
    @Test
    void methodEventHandlerWithUnknownMethodProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button onAction="<error descr="'nonExistentMethod' in test.TestView cannot be resolved">nonExistentMethod</error>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A method whose single parameter type is incompatible with the event type
     * (e.g. MouseEvent for an ActionEvent handler) must produce an error.
     * The compiler's UNSUITABLE_EVENT_HANDLER check is mirrored here.
     */
    @Test
    void methodEventHandlerWithIncompatibleParameterTypeProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button onAction="<error descr="'mouseHandler' does not match the signature of an event handler for ActionEvent">mouseHandler</error>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A method that does not return void must produce an error, because the compiler
     * requires the handler method to return void.
     */
    @Test
    void methodEventHandlerWithNonVoidReturnProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button onAction="<error descr="'returnsString' does not match the signature of an event handler for ActionEvent">returnsString</error>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A method with two parameters is not a valid event-handler signature.
     * Only 0 or 1 parameters are accepted.
     */
    @Test
    void methodEventHandlerWithTooManyParamsProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button onAction="<error descr="'tooManyParams' does not match the signature of an event handler for ActionEvent">tooManyParams</error>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A plain method name on an {@code EventHandler}-typed property without a code-behind
     * class (no {@code fx:subclass}) should not crash the plugin.
     */
    @Test
    void methodEventHandlerWithoutCodeBehindDoesNotCrash() {
        getFixture().configureByText("StandaloneView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.Button?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0">
                  <Button onAction="handleAction"/>
                </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Generic property with fx:typeArguments: no false positives
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // Ctrl+click navigation (reference resolution)
    // -----------------------------------------------------------------------

    /**
     * Ctrl+click on a method-handler attribute value must navigate to the Java method
     * declared in the code-behind class.
     *
     * <p>{@code <Button onAction="<caret>handleAction"/>} should resolve to
     * {@code TestView.handleAction(ActionEvent)}.
     */
    @Test
    void methodEventHandlerNavigatesToMethod() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button onAction="<caret>handleAction"/>
                """
        ));

        PsiElement resolved = ReadAction.compute(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue attrVal = PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            if (attrVal == null) return null;
            for (PsiReference ref : attrVal.getReferences()) {
                PsiElement target = ref.resolve();
                if (target instanceof PsiMethod) return target;
            }
            return null;
        });

        assertNotNull(resolved, "onAction=\"handleAction\" should resolve to a PsiMethod");
        assertInstanceOf(PsiMethod.class, resolved,
                "Reference should navigate to a PsiMethod");
        assertEquals("handleAction", ((PsiMethod) resolved).getName(),
                "Should navigate to 'handleAction'");
    }

    /**
     * When both a zero-parameter and a one-parameter overload are present, the
     * reference must resolve to the one-parameter overload, matching the compiler's
     * preference (mirrors {@code EventHandlerGenerator.findMethod()}).
     */
    @Test
    void methodEventHandlerPrefersParameterizedOverload() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button onAction="<caret>handleAction"/>
                """
        ));

        PsiMethod resolved = ReadAction.compute(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue attrVal = PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            if (attrVal == null) return null;
            for (PsiReference ref : attrVal.getReferences()) {
                PsiElement target = ref.resolve();
                if (target instanceof PsiMethod m) return m;
            }
            return null;
        });

        assertNotNull(resolved);
        assertEquals(1, resolved.getParameterList().getParametersCount(),
                "Compiler prefers the overload with a parameter; plugin must match");
    }

    /**
     * Ctrl+click on an unresolved method name (method not in code-behind) must return
     * a null-resolving reference (not crash), and the annotator emits the error separately.
     */
    @Test
    void methodEventHandlerUnresolvedReturnsNullReference() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button onAction="<caret>nonExistentMethod"/>
                """
        ));

        PsiElement resolved = ReadAction.compute(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue attrVal = PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            if (attrVal == null) return null;
            for (PsiReference ref : attrVal.getReferences()) {
                PsiElement target = ref.resolve();
                if (target instanceof PsiMethod) return target;
            }
            return null;
        });

        assertNull(resolved, "Unresolved method name must not navigate anywhere");
    }

    // -----------------------------------------------------------------------
    // Find Usages (MethodReferencesSearch)
    // -----------------------------------------------------------------------

    /**
     * "Find Usages" on a code-behind event-handler method must discover the FXML
     * attribute value that references it by name.
     */
    @Test
    void findUsagesOnHandlerMethodFindsAttributeInFxml2() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button onAction="handleAction"/>
                """
        ));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var cls = JavaPsiFacade.getInstance(project)
                    .findClass("test.TestView", GlobalSearchScope.projectScope(project));
            assertNotNull(cls, "Must find TestView class");

            PsiMethod[] methods = cls.findMethodsByName("handleAction", false);
            assertTrue(methods.length > 0, "Must find handleAction method");
            // The with-parameter overload is the one selected by the compiler
            PsiMethod handlerMethod = null;
            for (PsiMethod m : methods) {
                if (m.getParameterList().getParametersCount() == 1) {
                    handlerMethod = m;
                    break;
                }
            }
            assertNotNull(handlerMethod, "Must find handleAction(ActionEvent) overload");

            Collection<PsiReference> refs = MethodReferencesSearch.search(
                    handlerMethod, GlobalSearchScope.allScope(project), true).findAll();

            assertFalse(refs.isEmpty(),
                    "MethodReferencesSearch must find handleAction usage in FXML attribute");
        });
    }

    /**
     * "Find Usages" on a zero-parameter handler method must discover the FXML
     * attribute value referencing it.
     */
    @Test
    void findUsagesOnParameterlessHandlerMethodFindsAttributeInFxml2() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button onAction="handleActionNoParam"/>
                """
        ));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var cls = JavaPsiFacade.getInstance(project)
                    .findClass("test.TestView", GlobalSearchScope.projectScope(project));
            assertNotNull(cls, "Must find TestView class");

            PsiMethod[] methods = cls.findMethodsByName("handleActionNoParam", false);
            assertTrue(methods.length > 0, "Must find handleActionNoParam method");

            Collection<PsiReference> refs = MethodReferencesSearch.search(
                    methods[0], GlobalSearchScope.allScope(project), true).findAll();

            assertFalse(refs.isEmpty(),
                    "MethodReferencesSearch must find handleActionNoParam usage in FXML attribute");
        });
    }

    /**
     * "Find Usages" on a method that is NOT referenced as an event handler
     * (incompatible type) must not produce false positives for that attribute.
     * The {@code mouseHandler} method is referenced as an event handler for
     * an ActionEvent property, which is a signature mismatch, so the plugin
     * should not resolve it and therefore not find a usage.
     */
    @Test
    void findUsagesDoesNotIncludeMismatchedHandlerMethod() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button onAction="mouseHandler"/>
                """
        ));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var cls = JavaPsiFacade.getInstance(project)
                    .findClass("test.TestView", GlobalSearchScope.projectScope(project));
            assertNotNull(cls, "Must find TestView class");

            PsiMethod[] methods = cls.findMethodsByName("mouseHandler", false);
            assertTrue(methods.length > 0, "Must find mouseHandler method");

            Collection<PsiReference> refs = MethodReferencesSearch.search(
                    methods[0], GlobalSearchScope.allScope(project), true).findAll();

            // No usage should be found: mouseHandler has an incompatible signature for onAction
            assertTrue(refs.isEmpty() || refs.stream().noneMatch(r ->
                    r.getElement().getContainingFile().getName().endsWith(".fxml")),
                    "mouseHandler must not be found as an event-handler usage in FXML");
        });
    }

    // -----------------------------------------------------------------------
    // Generic property with fx:typeArguments: no false positives
    // -----------------------------------------------------------------------

    /**
     * When a generic class is used with {@code fx:typeArguments}, assigning a field whose type
     * matches the resolved type argument should not produce an "Incompatible types" error.
     */
    @Test
    void evaluateBindingToGenericPropertyWithTypeArgumentsProducesNoError() {
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public abstract class TestViewBase2 extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.scene.control.Label;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                import java.util.function.Function;
                public class FormattedLabel<T> extends Label {
                    private final ObjectProperty<Function<T, String>> formatter =
                        new SimpleObjectProperty<>(this, "formatter", Object::toString);
                    public ObjectProperty<Function<T, String>> formatterProperty() { return formatter; }
                    public Function<T, String> getFormatter() { return formatter.get(); }
                    public void setFormatter(Function<T, String> v) { formatter.set(v); }
                }
                """);
        getFixture().addClass("""
                package test;
                import java.util.function.Function;
                public class TestView2 extends TestViewBase2 {
                    public final Function<Double, String> doubleFormatter = d -> String.format("%.2f", d);
                }
                """);
        getFixture().configureByText("TestView2.fxml", fxml(
                "test.FormattedLabel", """
                  <FormattedLabel fx:typeArguments="java.lang.Double" formatter="$doubleFormatter"/>
                """, "test.TestView2"
        ));
        getFixture().checkHighlighting(false, false, false);
    }
}
