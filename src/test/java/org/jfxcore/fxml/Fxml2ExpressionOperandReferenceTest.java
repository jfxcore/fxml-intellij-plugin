package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
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

/**
 * Tests that every operand of an expression is navigable in its own right, and that the type
 * arguments an expression names navigate to the classes they select.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2ExpressionOperandReferenceTest extends Fxml2TestBase {

    @BeforeAll
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
                public class ViewModel {
                    public <T> T describe(double width) { return null; }
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.property.DoubleProperty;
                import javafx.beans.property.SimpleDoubleProperty;
                import javafx.beans.property.StringProperty;
                import javafx.beans.property.SimpleStringProperty;
                public class TestView extends TestViewBase {
                    private final DoubleProperty shapeWidth = new SimpleDoubleProperty(this, "shapeWidth");
                    public DoubleProperty shapeWidthProperty() { return shapeWidth; }
                    public double getShapeWidth() { return shapeWidth.get(); }
                    private final StringProperty caption = new SimpleStringProperty(this, "caption");
                    public StringProperty captionProperty() { return caption; }
                    public String getCaption() { return caption.get(); }
                    public ViewModel getViewModel() { return null; }
                }
                """);
    }

    /** Resolves the first reference covering the caret, or {@code null} when there is none. */
    private @Nullable PsiElement resolveAtCaret() {
        return ReadAction.compute(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue attrVal = PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            assertNotNull(attrVal, "caret must be inside an attribute value");
            int relOffset = offset - attrVal.getTextRange().getStartOffset();
            for (PsiReference ref : attrVal.getReferences()) {
                if (!ref.getRangeInElement().containsOffset(relOffset)) continue;
                PsiElement target = ref.resolve();
                if (target != null) return target;
            }
            return null;
        });
    }

    /**
     * Returns the source text the reference at the caret covers, i.e. the text that Ctrl+click
     * makes sensitive, or {@code null} when no reference at the caret resolves.
     */
    private @Nullable String referenceTextAtCaret() {
        return ReadAction.compute(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue attrVal = PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            assertNotNull(attrVal, "caret must be inside an attribute value");
            int relOffset = offset - attrVal.getTextRange().getStartOffset();
            for (PsiReference ref : attrVal.getReferences()) {
                if (!ref.getRangeInElement().containsOffset(relOffset)) continue;
                if (ref.resolve() == null) continue;
                return ref.getRangeInElement().substring(attrVal.getText());
            }
            return null;
        });
    }

    /** Wraps the body in a VBox so that a {@code :parent} selector has an element to name. */
    private static String inVBox(String body) {
        return "  <VBox spacing=\"10\">\n" + body + "  </VBox>\n";
    }

    // -----------------------------------------------------------------------
    // Operands of an expression that combines values with operators
    // -----------------------------------------------------------------------

    /** A path inside a grouped arithmetic expression resolves. */
    @Test
    void pathInAGroupedArithmeticExpressionResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.shape.Rectangle",
                """
                  <Rectangle width="${(shape<caret>Width + 20) * 0.5}"/>
                """
        ));
        PsiElement target = resolveSegmentAtCaret();
        assertEquals("shapeWidthProperty",
                assertInstanceOf(PsiMethod.class, target).getName());
    }

    /** A path that is the right operand of an arithmetic expression resolves. */
    @Test
    void pathAsARightOperandResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.shape.Rectangle",
                """
                  <Rectangle height="${4 / shape<caret>Width}"/>
                """
        ));
        PsiElement target = resolveSegmentAtCaret();
        assertEquals("shapeWidthProperty",
                assertInstanceOf(PsiMethod.class, target).getName());
    }

    /** A path nested in an operator expression that is a function argument resolves. */
    @Test
    void pathInAnOperatorArgumentOfAFunctionCallResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${String.format('Width: %.0f', shape<caret>Width * 0.7)}"/>
                """
        ));
        PsiElement target = resolveSegmentAtCaret();
        assertEquals("shapeWidthProperty",
                assertInstanceOf(PsiMethod.class, target).getName());
    }

    /** A path preceded by a context selector resolves when an operator follows it. */
    @Test
    void pathAfterAContextSelectorInAnArithmeticExpressionResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.VBox\njavafx.scene.shape.Rectangle",
                inVBox("""
                    <Rectangle width="${:parent<VBox>.spa<caret>cing * 8}"/>
                """)
        ));
        PsiElement target = resolveSegmentAtCaret();
        assertEquals("spacingProperty",
                assertInstanceOf(PsiMethod.class, target).getName());
    }

    // -----------------------------------------------------------------------
    // Type arguments named by an expression
    // -----------------------------------------------------------------------

    /** The type argument of a generic method invocation navigates to the class it selects. */
    @Test
    void typeArgumentOfAnInvocationResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="${viewModel.describe&lt;Dou<caret>ble&gt;(shapeWidth)}"/>
                """
        ));
        PsiElement target = resolveAtCaret();
        assertEquals("Double", assertInstanceOf(PsiClass.class, target).getName());
        assertEquals("Double", referenceTextAtCaret(),
                "the type name alone is sensitive to Ctrl+click");
    }

    /** The type named by a context selector navigates separately from the selector itself. */
    @Test
    void typeOfAContextSelectorResolves() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.VBox\njavafx.scene.shape.Rectangle",
                inVBox("""
                    <Rectangle width="${:parent&lt;VB<caret>ox&gt;.spacing * 8}"/>
                """)
        ));
        PsiElement target = resolveAtCaret();
        assertEquals("VBox", assertInstanceOf(PsiClass.class, target).getName());
        assertEquals("VBox", referenceTextAtCaret(),
                "the type name alone is sensitive to Ctrl+click");
    }
}
