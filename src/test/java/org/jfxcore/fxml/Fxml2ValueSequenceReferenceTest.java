package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests that a markup extension is navigable in every item of a value sequence, not only in an
 * item that spans the whole attribute value.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2ValueSequenceReferenceTest extends Fxml2TestBase {

    @BeforeAll
    void addCodeBehind() {
        // Minimal generated base class (normally produced by the FXML compiler)
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public abstract class TestViewBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.property.DoubleProperty;
                import javafx.beans.property.SimpleDoubleProperty;
                public class TestView extends TestViewBase {
                    private final DoubleProperty inset = new SimpleDoubleProperty(this, "inset");
                    public DoubleProperty insetProperty() { return inset; }
                    public double getInset() { return inset.get(); }
                    public void setInset(double v) { inset.set(v); }
                }
                """);
    }

    @BeforeAll
    void addMarkupExtensionMocks() {
        getFixture().addClass("""
                package org.jfxcore.markup;
                public interface MarkupExtension {
                    interface Supplier<T> extends MarkupExtension {
                        T get(MarkupContext context) throws Exception;
                    }
                }
                """);
        getFixture().addClass("package org.jfxcore.markup; public interface MarkupContext {}");
        getFixture().addClass("""
                package org.jfxcore.markup.resource;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                import javafx.beans.DefaultProperty;
                import javafx.beans.NamedArg;
                @DefaultProperty("key")
                public final class StaticResource<T> implements MarkupExtension.Supplier<T> {
                    public StaticResource(@NamedArg("key") String key) {}
                    @Override
                    public T get(MarkupContext context) { return null; }
                }
                """);
        getFixture().addClass("""
                package test;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                import javafx.beans.NamedArg;
                public class DoubleValue implements MarkupExtension.Supplier<Double> {
                    public DoubleValue(@NamedArg("value") double value) {}
                    @Override
                    public Double get(MarkupContext context) { return null; }
                }
                """);
    }

    /** Resolves the reference at the caret, or {@code null} when there is none. */
    private @Nullable PsiElement resolveAtCaret() {
        return ReadAction.compute(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue attrVal = PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            assertNotNull(attrVal, "caret must be inside an attribute value");
            PsiReference ref = attrVal.findReferenceAt(offset - attrVal.getTextRange().getStartOffset());
            return ref != null ? ref.resolve() : null;
        });
    }

    @Test
    void markupExtensionInALaterItemResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.shape.Polygon\ntest.DoubleValue",
                """
                  <Polygon points="0, {Double<caret>Value value=50}, 100"/>
                """
        ));
        PsiElement target = resolveAtCaret();
        assertEquals("DoubleValue", assertInstanceOf(com.intellij.psi.PsiClass.class, target).getName());
    }

    @Test
    void markupExtensionParameterInALaterItemResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.shape.Polygon\ntest.DoubleValue",
                """
                  <Polygon points="0, {DoubleValue va<caret>lue=50}, 100"/>
                """
        ));
        PsiElement target = resolveAtCaret();
        assertEquals("value", assertInstanceOf(com.intellij.psi.PsiParameter.class, target).getName());
    }

    @Test
    void prefixShorthandInALaterItemResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.shape.Polygon",
                """
                  <Polygon points="0, <caret>%pointX, 100"/>
                """
        ));
        PsiElement target = resolveAtCaret();
        assertEquals("StaticResource", assertInstanceOf(com.intellij.psi.PsiClass.class, target).getName());
    }

    @Test
    void prefixShorthandInAnExtensionParameterResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label\norg.jfxcore.markup.resource.StaticResource",
                """
                  <Label text="{StaticResource greeting; formatArguments=<caret>%name}"/>
                """
        ));
        PsiElement target = resolveAtCaret();
        assertEquals("StaticResource", assertInstanceOf(com.intellij.psi.PsiClass.class, target).getName());
    }

    // -----------------------------------------------------------------------
    // Binding expressions
    // -----------------------------------------------------------------------

    /** A binding path in a later item resolves against the code-behind class. */
    @Test
    void bindingPathInALaterItemResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.shape.Polygon",
                """
                  <Polygon points="0, $in<caret>set, 100"/>
                """
        ));
        PsiElement target = resolveSegmentAtCaret();
        assertEquals("setInset",
                assertInstanceOf(com.intellij.psi.PsiMethod.class, target).getName());
    }

    /** A binding path in an implicit-constructor argument resolves against its own item. */
    @Test
    void bindingPathInAConstructorArgumentResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane padding="10, 20, 10, $in<caret>set"/>
                """
        ));
        PsiElement target = resolveSegmentAtCaret();
        assertEquals("setInset",
                assertInstanceOf(com.intellij.psi.PsiMethod.class, target).getName());
    }

    /** A binding path that spans the whole value keeps resolving. */
    @Test
    void bindingPathInASingleItemValueResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane padding="$in<caret>set"/>
                """
        ));
        PsiElement target = resolveSegmentAtCaret();
        assertEquals("setInset",
                assertInstanceOf(com.intellij.psi.PsiMethod.class, target).getName());
    }

    // -----------------------------------------------------------------------
    // Static property attributes
    // -----------------------------------------------------------------------

    /** The items of a static property attribute are resolved like those of a plain property. */
    @Test
    void markupExtensionInALaterItemOfAStaticPropertyResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.GridPane\njavafx.scene.control.Label\ntest.DoubleValue",
                """
                  <Label GridPane.margin="10, {Double<caret>Value value=20}, 10, 20"/>
                """
        ));
        PsiElement target = resolveAtCaret();
        assertEquals("DoubleValue", assertInstanceOf(com.intellij.psi.PsiClass.class, target).getName());
    }

    // -----------------------------------------------------------------------
    // Markup extension content
    // -----------------------------------------------------------------------

    /** A parameter that follows a separator resolves to the parameter it names. */
    @Test
    void parameterAfterASeparatorResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.shape.Polygon\ntest.DoubleValue",
                """
                  <Polygon points="0, {DoubleValue 50; va<caret>lue=60}"/>
                """
        ));
        PsiElement target = resolveAtCaret();
        assertEquals("value", assertInstanceOf(com.intellij.psi.PsiParameter.class, target).getName());
    }

    /**
     * A comma separates the values of one parameter, so an assignment that follows a comma is a
     * value rather than a parameter and names nothing.
     */
    @Test
    void assignmentAfterACommaIsNotAParameter() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.shape.Polygon\ntest.DoubleValue",
                """
                  <Polygon points="0, {DoubleValue value=50, va<caret>lue=60}"/>
                """
        ));
        assertNull(resolveAtCaret());
    }

    /** A binding expression among the values of a parameter is navigable. */
    @Test
    void bindingExpressionInAParameterValueListResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label\norg.jfxcore.markup.resource.StaticResource",
                """
                  <Label text="{StaticResource greeting; formatArguments=Jane, $in<caret>set}"/>
                """
        ));
        PsiElement target = resolveSegmentAtCaret();
        assertEquals("setInset",
                assertInstanceOf(com.intellij.psi.PsiMethod.class, target).getName());
    }

    /**
     * A separator in the value of a property that takes a single value is part of the literal, so
     * the value keeps its whole-value treatment and the text after the separator is not an item.
     */
    @Test
    void separatorInAScalarValueDoesNotCreateItems() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="hello, %gre<caret>eting"/>
                """
        ));
        assertNull(resolveAtCaret());
    }
}
