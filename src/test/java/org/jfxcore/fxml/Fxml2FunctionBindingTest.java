package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.impl.source.resolve.reference.impl.PsiMultiReference;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.jfxcore.fxml.lang.Fxml2BindingParamNameReference;
import org.jfxcore.fxml.lang.Fxml2NamespaceUrlReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                public class Conv {
                    public static String toText(double d) { return Double.toString(d); }
                    public static double fromText(String s) { return Double.parseDouble(s); }
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

    /**
     * A class imported solely to be used as the constructor of a function-binding
     * expression (e.g. {@code ${Color(...)}}) must not be reported as an unused import.
     * The constructor name counts as a use of the corresponding import.
     */
    @Test
    void constructorClassImportUsedOnlyInFunctionExpressionIsNotFlaggedAsUnused() {
        getFixture().enableInspections(new org.jfxcore.fxml.annotator.Fxml2UnusedImportsInspection());
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label\njavafx.scene.paint.Color",
                """
                  <Label textFill="${Color(0.5, 0.5, 0.5, 1.0)}"/>
                """
        ));
        getFixture().checkHighlighting(true, false, false);
    }

    /**
     * When the constructor class of a function-binding expression is not imported, the
     * "'X' ... cannot be resolved" error must offer an "Add import for 'X'" quick fix that
     * inserts the missing {@code <?import?>} processing instruction.
     */
    @Test
    void unresolvedConstructorClassInFunctionExpressionOffersAddImportFix() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label textFill="${Co<caret>lor(0.5, 0.5, 0.5, 1.0)}"/>
                """
        ));
        getFixture().doHighlighting();
        var fixes = getFixture().getAllQuickFixes();
        assertTrue(fixes.stream().anyMatch(
                        f -> f.getText().contains("Add import") && f.getText().contains("Color")),
                "Expected 'Add import for Color' quick fix, but got: "
                        + fixes.stream().map(com.intellij.codeInsight.intention.IntentionAction::getText).toList());

        getFixture().launchAction(fixes.stream()
                .filter(f -> f.getText().contains("Add import") && f.getText().contains("Color"))
                .findFirst()
                .orElseThrow());
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("<?import javafx.scene.paint.Color?>"),
                "Expected Color import to be added, but got: " + result);
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
     * A secondary-parameter name that is not valid for the binding kind is reported as unresolved,
     * but its value path is still resolved.  Here the non-bidirectional {@code fx:Observe} binding
     * ({@code ${...}}) does not accept any secondary parameter, so {@code xy} is flagged; the value
     * {@code Double.parseDouble} (a static method) still resolves and produces no error.
     */
    @Test
    void unknownSecondaryParamNameIsReportedButValueStillResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.TextField",
                """
                  <TextField text="${Double.toString(value); <error descr="'xy' in fx:Observe cannot be resolved">xy</error>=Double.parseDouble}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A secondary-parameter value is resolved as either a property path or a method path, without
     * hard-coding which parameter names take which.  Here the value {@code label} is a property
     * (not a method), so it resolves even though the parameter name {@code xy} is unrecognized.
     */
    @Test
    void unknownSecondaryParamWithPropertyValueResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.TextField",
                """
                  <TextField text="${Double.toString(value); <error descr="'xy' in fx:Observe cannot be resolved">xy</error>=label}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * When the value of an unrecognized secondary parameter cannot be resolved, both the name and
     * the value are flagged independently.
     */
    @Test
    void unknownSecondaryParamReportsNameAndUnresolvableValue() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.TextField",
                """
                  <TextField text="${Double.toString(value); <error descr="'xy' in fx:Observe cannot be resolved">xy</error>=<error descr="'nope' in test.TestView cannot be resolved">nope</error>}"/>
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

    /**
     * Ctrl+click on a secondary-parameter value that is a property (rather than a method) must
     * navigate to that property's accessor, even when the parameter name is unrecognized: the value
     * is resolved as a property or a method path without hard-coding which names take which.
     */
    @Test
    void ctrlClickOnUnknownParamPropertyValueResolvesToAccessor() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.TextField",
                """
                  <TextField text="${Double.toString(value); xy=la<caret>bel}"/>
                """
        ));
        PsiElement target = resolveSegmentAtCaret();
        PsiMethod method = assertInstanceOf(PsiMethod.class, target,
                "Ctrl+click on the property value 'label' should resolve to its accessor");
        assertEquals("labelProperty", method.getName());
    }

    // -----------------------------------------------------------------------
    // inverseMethod= parameter navigation and documentation
    // -----------------------------------------------------------------------

    /**
     * Ctrl+click on the method name of an {@code inverseMethod=} parameter
     * ({@code parseDouble}) must navigate to the corresponding method, the same way the
     * primary function-name path resolves its method segment.
     */
    @Test
    void ctrlClickOnInverseMethodNameResolvesToMethod() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.TextField",
                """
                  <TextField text="#{formatDouble(value); inverseMethod=parse<caret>Double}"/>
                """
        ));
        PsiElement target = resolveSegmentAtCaret();
        PsiMethod method = assertInstanceOf(PsiMethod.class, target,
                "Ctrl+click on 'parseDouble' inverse method should resolve to a method");
        assertEquals("parseDouble", method.getName());
        assertNotNull(method.getContainingClass());
        assertEquals("test.TestView", method.getContainingClass().getQualifiedName());
    }

    /**
     * Every segment of a qualified {@code inverseMethod=} path must be navigable: the class
     * qualifier ({@code Double}) navigates to the class, and the method name
     * ({@code parseDouble}) navigates to the static method, mirroring the sample
     * {@code #{Double.toString(value); inverseMethod=Double.parseDouble}}.
     */
    @Test
    void ctrlClickOnQualifiedInverseMethodClassResolvesToClass() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.TextField",
                """
                  <TextField text="#{Double.toString(value); inverseMethod=Doub<caret>le.parseDouble}"/>
                """
        ));
        PsiElement target = resolveSegmentAtCaret();
        PsiClass cls = assertInstanceOf(PsiClass.class, target,
                "Ctrl+click on 'Double' qualifier should resolve to the class");
        assertEquals("java.lang.Double", cls.getQualifiedName());
    }

    /**
     * Ctrl+click on the static method name of a qualified {@code inverseMethod=} path
     * ({@code Double.parseDouble}) must navigate to the JDK method.
     */
    @Test
    void ctrlClickOnQualifiedInverseMethodNameResolvesToMethod() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.TextField",
                """
                  <TextField text="#{Double.toString(value); inverseMethod=Double.parse<caret>Double}"/>
                """
        ));
        PsiElement target = resolveSegmentAtCaret();
        PsiMethod method = assertInstanceOf(PsiMethod.class, target,
                "Ctrl+click on 'parseDouble' should resolve to the JDK method");
        assertEquals("parseDouble", method.getName());
        assertNotNull(method.getContainingClass());
        assertEquals("java.lang.Double", method.getContainingClass().getQualifiedName());
    }

    /**
     * A fully-resolved qualified inverse method path ({@code Double.parseDouble}) must produce
     * no error, and the JDK {@code Double} class needs no {@code <?import?>}.
     */
    @Test
    void qualifiedInverseMethodProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.TextField",
                """
                  <TextField text="#{Double.toString(value); inverseMethod=Double.parseDouble}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Ctrl+click on the {@code inverseMethod} keyword opens the bidirectional-function-binding
     * section of the function-expressions documentation page.
     */
    @Test
    void ctrlClick_onInverseMethodParamName_opensFunctionDocs() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.TextField",
                """
                  <TextField text="#{formatDouble(value); inverseMethod=parseDouble}"/>
                """
        ));
        ReadAction.run(() -> {
            String url = resolveParamNameUrl();
            assertEquals(Fxml2BindingParamNameReference.INVERSE_METHOD_DOCS_URL, url,
                    "inverseMethod param name should open the function-expression docs");
        });
    }

    /**
     * A class imported solely to be referenced as a qualifier of an {@code inverseMethod=}
     * path (e.g. {@code Conv} in {@code inverseMethod=Conv.fromText}) must not be reported as
     * an unused import.
     */
    @Test
    void inverseMethodClassImportIsNotFlaggedAsUnused() {
        getFixture().enableInspections(new org.jfxcore.fxml.annotator.Fxml2UnusedImportsInspection());
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.TextField\ntest.Conv",
                """
                  <TextField text="#{formatDouble(value); inverseMethod=Conv.fromText}"/>
                """
        ));
        getFixture().checkHighlighting(true, false, false);
    }

    /**
     * When the class qualifier of an {@code inverseMethod=} path is not imported, the
     * "cannot be resolved" error must offer an "Add import" quick fix that inserts the missing
     * {@code <?import?>} processing instruction.
     */
    @Test
    void unresolvedInverseMethodClassOffersAddImportFix() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.TextField",
                """
                  <TextField text="#{formatDouble(value); inverseMethod=Co<caret>nv.fromText}"/>
                """
        ));
        getFixture().doHighlighting();
        var fixes = getFixture().getAllQuickFixes();
        assertTrue(fixes.stream().anyMatch(
                        f -> f.getText().contains("Add import") && f.getText().contains("Conv")),
                "Expected 'Add import for Conv' quick fix, but got: "
                        + fixes.stream().map(com.intellij.codeInsight.intention.IntentionAction::getText).toList());

        getFixture().launchAction(fixes.stream()
                .filter(f -> f.getText().contains("Add import") && f.getText().contains("Conv"))
                .findFirst()
                .orElseThrow());
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("<?import test.Conv?>"),
                "Expected Conv import to be added, but got: " + result);
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

    // -----------------------------------------------------------------------
    // Navigation on partially-typed (unterminated) binding expressions
    // -----------------------------------------------------------------------

    /**
     * A binding expression that is still being typed and has no closing brace
     * (e.g. {@code ${String.fo}) must still resolve its early, already-complete segments.
     * Ctrl+click on the {@code String} class qualifier must navigate to {@code java.lang.String}
     * even though the trailing member segment is incomplete and the {@code }} is missing.
     */
    @Test
    void ctrlClickOnClassQualifierOfUnterminatedPathResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${Str<caret>ing.fo"/>
                """
        ));
        PsiElement target = resolveSegmentAtCaret();
        assertInstanceOf(com.intellij.psi.PsiClass.class, target,
                "Ctrl+click on 'String' in an unterminated binding should resolve to the class");
        assertEquals("java.lang.String",
                ((com.intellij.psi.PsiClass) target).getQualifiedName());
    }

    /**
     * The function-name qualifier of an unterminated function-call binding
     * (e.g. {@code ${String.format('foo', height}) where the {@code )} and {@code }} are
     * missing) must still resolve. Ctrl+click on {@code String} must navigate to the class.
     */
    @Test
    void ctrlClickOnFunctionQualifierOfUnterminatedCallResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${Str<caret>ing.format('foo', value"/>
                """
        ));
        PsiElement target = resolveSegmentAtCaret();
        assertInstanceOf(com.intellij.psi.PsiClass.class, target,
                "Ctrl+click on 'String' in an unterminated function call should resolve to the class");
        assertEquals("java.lang.String",
                ((com.intellij.psi.PsiClass) target).getQualifiedName());
    }

    /**
     * Resolves the reference at the position of a secondary-parameter keyword (before its
     * {@code =} separator) to the documentation URL reported by its
     * {@link Fxml2NamespaceUrlReference.UrlNavigationTarget}.
     */
    private String resolveParamNameUrl() {
        String paramName = "inverseMethod";
        XmlFile xmlFile = (XmlFile) getFixture().getFile();
        XmlAttributeValue attrVal = null;
        for (XmlTag tag : PsiTreeUtil.findChildrenOfType(xmlFile, XmlTag.class)) {
            XmlAttribute attr = tag.getAttribute("text");
            if (attr != null && attr.getValue() != null && attr.getValue().contains(paramName + "=")) {
                attrVal = attr.getValueElement();
                break;
            }
        }
        assertNotNull(attrVal, "text attribute with param '" + paramName + "' not found");

        String attrText = attrVal.getText();
        int semicolonPos = attrText.indexOf(';');
        assertTrue(semicolonPos > 0, "No ';' separator in " + attrText);
        int paramNamePos = attrText.indexOf(paramName, semicolonPos);
        assertTrue(paramNamePos > 0, "Param name '" + paramName + "' not found after ';' in " + attrText);

        int midOffset = paramNamePos + paramName.length() / 2;
        PsiReference ref = attrVal.findReferenceAt(midOffset);
        assertNotNull(ref, "No reference at param name '" + paramName + "' in " + attrText);

        Fxml2NamespaceUrlReference.UrlNavigationTarget urlTarget = findParamNameUrlTarget(ref);
        assertNotNull(urlTarget, "No param-name URL reference found at '" + paramName + "' in " + attrText);
        return urlTarget.getName();
    }

    private static Fxml2NamespaceUrlReference.UrlNavigationTarget findParamNameUrlTarget(PsiReference ref) {
        if (!(ref instanceof PsiMultiReference multi)) {
            PsiElement resolved = ref.resolve();
            return resolved instanceof Fxml2NamespaceUrlReference.UrlNavigationTarget t ? t : null;
        }
        for (PsiReference inner : multi.getReferences()) {
            if (inner instanceof Fxml2BindingParamNameReference) {
                PsiElement resolved = inner.resolve();
                if (resolved instanceof Fxml2NamespaceUrlReference.UrlNavigationTarget t) return t;
            }
        }
        return null;
    }
}
