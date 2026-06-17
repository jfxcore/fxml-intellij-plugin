package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.jfxcore.fxml.lang.Fxml2BindingSegmentReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for function-binding syntax in FXML binding expressions.
 *
 * <p>FXML/2 allows calling static or instance methods and constructors inside binding
 * expressions. The method or constructor is re-evaluated when any observable argument
 * changes (for {@code fx:Observe}).
 *
 * <p>Examples from the documentation ({@code binding/function-binding.md}):
 * <pre>{@code
 * <Button text="${String.format('Width: %.0f', self/width)}"/>
 * <Button textFill="${Color(path.to.red, path.to.green, path.to.blue, 1)}"/>
 * <TextField text="#{method(value); inverseMethod=inverseMethod}"/>
 * }</pre>
 *
 * <p>Method arguments can include: path expressions, string literals, number literals
 * ({@code 1} int, {@code 1L} long, {@code 1F} float, {@code 1D}/{@code 1.0} double),
 * boolean literals ({@code true}, {@code false}), the null literal ({@code null}),
 * {@code {fx:Class MyClass}}, and value-supplier markup extensions.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2FunctionBindingTest extends Fxml2TestBase {

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
                public class Helper {
                    public String shout(String s) { return s.toUpperCase(); }
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.property.DoubleProperty;
                import javafx.beans.property.SimpleDoubleProperty;
                import javafx.beans.property.StringProperty;
                import javafx.beans.property.SimpleStringProperty;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                public class TestView extends TestViewBase {
                    private final DoubleProperty value = new SimpleDoubleProperty(0);
                    public DoubleProperty valueProperty() { return value; }
                    public double getValue() { return value.get(); }

                    private final StringProperty label = new SimpleStringProperty("");
                    public StringProperty labelProperty() { return label; }
                    public String getLabel() { return label.get(); }

                    private final ObjectProperty<Helper> helper = new SimpleObjectProperty<>(new Helper());
                    public ObjectProperty<Helper> helperProperty() { return helper; }
                    public Helper getHelper() { return helper.get(); }

                    public static String formatDouble(double d) {
                        return String.format("%.2f", d);
                    }
                    public static double parseDouble(String s) {
                        return Double.parseDouble(s);
                    }
                }
                """);
    }

    // -----------------------------------------------------------------------
    // Static method invocation
    // -----------------------------------------------------------------------

    /**
     * {@code ${String.format('Width: %.0f', self/width)}}: calling a static method
     * with a string literal argument and a path argument. Should produce no error.
     */
    @Test
    void staticMethodCallWithStringLiteralProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Button",
                """
                  <Button text="${String.format('Width: %.0f', self/prefWidth)}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Calling a static method defined on the code-behind class should produce no error.
     */
    @Test
    void staticMethodOnCodeBehindClassProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${formatDouble(value)}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Calling a method that does not exist should produce an error.
     */
    @Test
    void unknownStaticMethodProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${<error descr="'nonExistentMethod' in test.TestView cannot be resolved">nonExistentMethod</error>(value)}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Constructor invocation (without 'new' keyword)
    // -----------------------------------------------------------------------

    /**
     * {@code ${Color(path.to.red, path.to.green, path.to.blue, 1)}}: constructor
     * call syntax (class name, no 'new' keyword). Should produce no error when the
     * constructor is resolvable.
     */
    @Test
    void constructorCallSyntaxProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label\njavafx.scene.paint.Color",
                """
                  <Label textFill="${Color(0.5, 0.5, 0.5, 1.0)}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Method arguments: numeric literals
    // -----------------------------------------------------------------------

    /**
     * number literals can be passed as function binding arguments:
     * {@code 1} (int), {@code 1L} (long), {@code 1F} (float), {@code 1D}/{@code 1.0} (double).
     * Numeric literals with the D suffix are the traditional form.
     */
    @Test
    void numericLiteralArgumentsProduceNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label\njavafx.scene.paint.Color",
                """
                  <Label textFill="${Color(0.1D, 0.2D, 0.3D, 1.0D)}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code 1.0} (plain floating-point literal without suffix) is also
     * valid as a double literal in function binding arguments.
     */
    @Test
    void plainDoubleLiteralWithoutSuffixProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label\njavafx.scene.paint.Color",
                """
                  <Label textFill="${Color(0.1, 0.2, 0.3, 1.0)}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Method arguments: boolean and null literals
    // -----------------------------------------------------------------------

    /**
     * {@code true} and {@code false} can be passed as literal arguments
     * in function binding expressions: they are recognized as boolean keywords,
     * not as path references.
     */
    @Test
    void booleanLiteralArgumentsProduceNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${String.format('val=%s %s', true, false)}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code null} can be passed as a literal argument in function
     * binding expressions; both {@code null} literals and {@code {fx:Null}} references
     * are accepted.
     */
    @Test
    void nullLiteralArgumentProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${String.format('val=%s', null)}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Doc (b1e9c37): Boolean and null literals can be mixed with other argument types
     * (path expressions, number literals) in the same function call.
     */
    @Test
    void mixedLiteralArgumentTypesProduceNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${String.format('%s %.2f %s', true, value, null)}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Bidirectional function binding with inverseMethod
    // -----------------------------------------------------------------------

    /**
     * {@code #{formatDouble(value); inverseMethod=parseDouble}}: bidirectional
     * function binding with a specified inverse method. Should produce no error.
     */
    @Test
    void bidirectionalFunctionBindingWithInverseMethodProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.TextField",
                """
                  <TextField text="#{formatDouble(value); inverseMethod=parseDouble}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * An unresolvable inverse method path should produce an error.
     */
    @Test
    void bidirectionalFunctionBindingWithUnresolvableInverseMethodProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.TextField",
                """
                  <TextField text="#{formatDouble(value); inverseMethod=<error descr="'nonExistentInverse' in test.TestView cannot be resolved">nonExistentInverse</error>}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Bidirectional function binding without {@code inverseMethod=} and without
     * {@code @org.jfxcore.markup.InverseMethod} on the method produces a
     * {@code METHOD_NOT_INVERTIBLE} error.
     */
    @Test
    void bidirectionalFunctionBindingWithoutAnnotationProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.TextField",
                """
                  <TextField text="#{<error descr="'formatDouble' is not annotated with @org.jfxcore.markup.InverseMethod, and no user-defined inverse method was provided">formatDouble</error>(value)}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Bidirectional function binding without {@code inverseMethod=} but with
     * {@code @org.jfxcore.markup.InverseMethod} on the method produces no error.
     */
    @Test
    void bidirectionalFunctionBindingWithAnnotationProducesNoError() {
        // Add the @InverseMethod annotation type to the test fixture.
        getFixture().addClass("""
                package org.jfxcore.markup;
                import java.lang.annotation.*;
                @Target(ElementType.METHOD)
                @Retention(RetentionPolicy.CLASS)
                public @interface InverseMethod {
                    String value();
                }
                """);
        // Code-behind class whose formatDouble is annotated with @InverseMethod.
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public abstract class AnnotatedViewBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.property.DoubleProperty;
                import javafx.beans.property.SimpleDoubleProperty;
                public class AnnotatedView extends AnnotatedViewBase {
                    private final DoubleProperty value = new SimpleDoubleProperty(0);
                    public DoubleProperty valueProperty() { return value; }
                    public double getValue() { return value.get(); }

                    @org.jfxcore.markup.InverseMethod("parseDouble")
                    public static String formatDouble(double d) {
                        return String.format("%.2f", d);
                    }
                    public static double parseDouble(String s) {
                        return Double.parseDouble(s);
                    }
                }
                """);
        getFixture().configureByText("AnnotatedView.fxml", fxml(
                "javafx.scene.control.TextField",
                """
                  <TextField text="#{formatDouble(value)}"/>
                """,
                "test.AnnotatedView"
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Method argument special forms
    // -----------------------------------------------------------------------

    /**
     * {@code {fx:Class MyClass}} can be passed as a method argument.
     * The binding should be accepted without error.
     */
    @Test
    void fxTypeAsMethodArgumentProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${String.format('Value: %s', {fx:Class java.lang.String})}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code {fx:Null}} can be passed as a method argument.
     * The binding should be accepted without error.
     */
    @Test
    void fxNullAsMethodArgumentProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${String.valueOf({fx:Null})}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * 11.4: When a method has multiple overloads, the plugin must not flag the
     * call as an error even if the exact overload cannot be determined without type
     * analysis (e.g. {@code String.valueOf(double)} vs {@code String.valueOf(Object)}).
     *
     * <p>The plugin checks that the method name exists, not which overload matches,
     * so all of these should pass without error.
     */
    @Test
    void overloadedMethodResolvesWithoutError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${String.valueOf(value)}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * 11.4: An overloaded static method on the code-behind class must not cause a false error.
     * Here {@code formatDouble} has one overload; the plugin accepts any matching name.
     */
    @Test
    void overloadedCodeBehindMethodResolvesWithoutError() {
        // String.format is overloaded (String.format(String, Object...) and
        // String.format(Locale, String, Object...)): the plugin must accept either.
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${String.format('val=%.2f', value)}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Function calls in element notation (fx:Evaluate, fx:Observe)
    // -----------------------------------------------------------------------

    /**
     * Element-notation {@code <fx:Evaluate source="method(args)"/>} must accept function-call
     * syntax in the source path, the same as attribute-notation {@code $method(args)}.
     * A valid method name must produce no error.
     */
    @Test
    void fxEvaluateElementNotationWithFunctionCallProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label>
                    <text><fx:Evaluate source="formatDouble(value)"/></text>
                  </Label>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Element-notation {@code <fx:Observe source="method(args)"/>} must accept function-call
     * syntax the same as attribute-notation {@code ${method(args)}}.
     */
    @Test
    void fxObserveElementNotationWithFunctionCallProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label>
                    <text><fx:Observe source="formatDouble(value)"/></text>
                  </Label>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Element-notation {@code <fx:Evaluate source="unknown(args)"/>} must report an error
     * on the unresolved function name only, not on the full {@code name(args)} string.
     */
    @Test
    void fxEvaluateElementNotationWithUnknownFunctionCallProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label>
                    <text><fx:Evaluate source="<error descr="'nonExistentMethod' in test.TestView cannot be resolved">nonExistentMethod</error>(value)"/></text>
                  </Label>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Element-notation {@code <fx:Evaluate source="method(badArg)"/>} must report an unresolvable
     * path argument, the same as attribute notation.
     */
    @Test
    void fxEvaluateElementNotationWithUnresolvableArgumentProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label>
                    <text><fx:Evaluate source="String.format('%s', <error descr="'nope' in test.TestView cannot be resolved">nope</error>)"/></text>
                  </Label>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Element-notation {@code <fx:Push source="method(args)"/>} is not applicable to function
     * expressions.
     */
    @Test
    void fxPushElementNotationWithFunctionProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label>
                    <text><fx:Push source="<error descr="format is not a valid reverse binding source">String.format('%s', value)</error>"/></text>
                  </Label>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Navigation: Ctrl+click on the function name and on path arguments
    // -----------------------------------------------------------------------

    /**
     * Ctrl+click on the method name of a static method call ({@code String.format})
     * must navigate to the {@code java.lang.String#format} method, mirroring how the
     * compiler resolves the last path segment of a function call as a method name.
     */
    @Test
    void ctrlClickOnStaticMethodNameResolvesToMethod() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${String.for<caret>mat('Value: %.2f', value)}"/>
                """
        ));
        PsiElement target = resolveSegmentAtCaret();
        PsiMethod method = assertInstanceOf(PsiMethod.class, target,
                "Ctrl+click on 'format' should resolve to a method");
        assertEquals("format", method.getName());
        assertNotNull(method.getContainingClass());
        assertEquals("java.lang.String", method.getContainingClass().getQualifiedName());
    }

    /**
     * Ctrl+click on a path argument of a function call ({@code value} in
     * {@code String.format('...', value)}) must navigate to the property declaration on
     * the binding context, the same as a plain binding path would.
     */
    @Test
    void ctrlClickOnPathArgumentResolvesToProperty() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${String.format('Value: %.2f', val<caret>ue)}"/>
                """
        ));
        PsiElement target = resolveSegmentAtCaret();
        assertNotNull(target, "Ctrl+click on 'value' argument should resolve to a declaration");
    }

    /**
     * Without an {@code fx:subclass}, the default evaluation context is the root element type
     * (here {@code VBox}).  A bare path argument ({@code width}) must resolve against that root
     * element type, and the {@code String.format} method name must resolve as well.
     */
    @Test
    void rootContextFunctionCallArgumentResolves() {
        getFixture().configureByText("WidthView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.Button?>
                <?import javafx.scene.layout.VBox?>
                <VBox xmlns="http://javafx.com/javafx"
                      xmlns:fx="http://jfxcore.org/fxml/2.0">
                  <Button text="${String.format('Width: %.0f', wid<caret>th)}"/>
                </VBox>
                """);
        PsiElement target = resolveSegmentAtCaret();
        PsiMethod method = assertInstanceOf(PsiMethod.class, target,
                "Ctrl+click on 'width' should resolve to its accessor on the root element type");
        assertEquals("widthProperty", method.getName());
    }

    // -----------------------------------------------------------------------
    // Argument resolution and error reporting
    // -----------------------------------------------------------------------

    /**
     * An unresolvable path argument must be reported against the evaluation context,
     * highlighting only the offending segment (not the whole function call).
     */
    @Test
    void unresolvableArgumentPathProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${String.format('%s', <error descr="'noSuchField' in test.TestView cannot be resolved">noSuchField</error>)}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A typo in a chained argument path must be reported against the owner type of the chain,
     * not the binding context.
     */
    @Test
    void unresolvableChainedArgumentSegmentProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${String.format('%s', helper.<error descr="'missing' in test.Helper cannot be resolved">missing</error>)}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A qualified <em>instance</em> method path ({@code helper.shout(label)}) must resolve against
     * the evaluation context (the method path is resolved like any other expression), producing no
     * error.
     */
    @Test
    void instanceMethodPathProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${helper.shout(label)}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * An unresolvable method on a resolved instance receiver must report the method-name segment
     * against the receiver type.
     */
    @Test
    void unknownInstanceMethodProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${helper.<error descr="'whisper' in test.Helper cannot be resolved">whisper</error>(label)}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A constant reference ({@code Double.POSITIVE_INFINITY}) is a valid argument and must produce
     * no error.
     */
    @Test
    void constantArgumentProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label\njava.lang.Double",
                """
                  <Label text="${String.format('%s', Double.POSITIVE_INFINITY)}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A nested method invocation as an argument must have its own arguments resolved too.
     */
    @Test
    void unresolvableArgumentInNestedCallProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${String.format('%s', String.valueOf(<error descr="'bogus' in test.TestView cannot be resolved">bogus</error>))}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Applicability per markup extension
    // -----------------------------------------------------------------------

    /**
     * Function expressions are not applicable to {@code fx:Push} (reverse) bindings; the compiler
     * reports {@code INVALID_REVERSE_BINDING_SOURCE}.
     */
    @Test
    void functionInPushBindingProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text=">{<error descr="format is not a valid reverse binding source">String.format('%s', value)</error>}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A bidirectional ({@code fx:Synchronize}) function binding must invoke the method with exactly
     * one argument (compiler: {@code INVALID_BIDIRECTIONAL_METHOD_PARAM_COUNT}).
     */
    @Test
    void bidirectionalFunctionWithMultipleArgumentsProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.TextField",
                """
                  <TextField text="#{<error descr="A bidirectional conversion method or constructor must be invoked with a single argument">formatDouble(value, label)</error>; inverseMethod=parseDouble}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Finds the first {@link Fxml2BindingSegmentReference} at the caret that resolves to a
     * non-null element.
     */
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
}
