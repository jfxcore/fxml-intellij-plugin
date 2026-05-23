package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.jfxcore.fxml.annotator.Fxml2FxAttributeInspection;
import org.jfxcore.fxml.lang.Fxml2AttributeValueReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for boolean prefix operators in binding expressions.
 *
 * <p>FXML2 supports two boolean prefix operators on binding paths:
 * <ul>
 *   <li>{@code !}: inverts the boolean value; converts {@code 0} or {@code null} to {@code true}</li>
 *   <li>{@code !!}: inverts twice; converts {@code 0} or {@code null} to {@code false}</li>
 * </ul>
 *
 * <p>These operators are applicable to {@code fx:Evaluate} and {@code fx:Observe} (all expression
 * types), but not to collection-content binding variants ({@code $..x}, {@code ${..x}},
 * {@code #{..x}}).
 *
 * <p>Corresponds to {@code markup-extension/expression/operators.md} in the fxml2 compiler documentation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2BooleanOperatorTest extends Fxml2TestBase {

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2AttributeValueInspection(), new Fxml2FxAttributeInspection());
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
                import javafx.beans.property.BooleanProperty;
                import javafx.beans.property.SimpleBooleanProperty;
                import javafx.beans.property.IntegerProperty;
                import javafx.beans.property.SimpleIntegerProperty;
                public class TestView extends TestViewBase {
                    private final BooleanProperty active = new SimpleBooleanProperty(false);
                    public BooleanProperty activeProperty() { return active; }
                    public boolean isActive() { return active.get(); }

                    private final IntegerProperty count = new SimpleIntegerProperty(0);
                    public IntegerProperty countProperty() { return count; }
                    public int getCount() { return count.get(); }
                }
                """);
        // Mock BooleanBindings so that navigation tests can find the expected methods
        // without requiring the real markup-runtime library on the test classpath.
        getFixture().addClass("""
                package org.jfxcore.markup.runtime;
                import javafx.beans.binding.BooleanBinding;
                import javafx.beans.value.ObservableValue;
                import javafx.beans.value.ObservableIntegerValue;
                import javafx.beans.value.ObservableLongValue;
                import javafx.beans.value.ObservableDoubleValue;
                import javafx.beans.value.ObservableFloatValue;
                public final class BooleanBindings {
                    private BooleanBindings() {}
                    public static BooleanBinding isZero(ObservableIntegerValue v)              { return null; }
                    public static BooleanBinding isZero(ObservableLongValue v)                 { return null; }
                    public static BooleanBinding isZero(ObservableFloatValue v)                { return null; }
                    public static BooleanBinding isZero(ObservableDoubleValue v)               { return null; }
                    public static BooleanBinding isZero(ObservableValue<? extends Number> v)   { return null; }
                    public static BooleanBinding isNotZero(ObservableIntegerValue v)           { return null; }
                    public static BooleanBinding isNotZero(ObservableLongValue v)              { return null; }
                    public static BooleanBinding isNotZero(ObservableFloatValue v)             { return null; }
                    public static BooleanBinding isNotZero(ObservableDoubleValue v)            { return null; }
                    public static BooleanBinding isNotZero(ObservableValue<? extends Number> v){ return null; }
                    public static BooleanBinding isNull(ObservableValue<?> v)                  { return null; }
                    public static BooleanBinding isNotNull(ObservableValue<?> v)               { return null; }
                    public static BooleanBinding isNot(ObservableValue<Boolean> v)             { return null; }
                }
                """);
    }

    // -----------------------------------------------------------------------
    // ! operator: positive cases
    // -----------------------------------------------------------------------

    /**
     * {@code disabled="${!active}"}: the {@code !} operator on a boolean binding
     * should be accepted without error.
     */
    @Test
    void singleBangOnBooleanBindingProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button disable="${!active}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code disabled="${!count}"}: the {@code !} operator on a numeric binding
     * (converts 0 to true) should be accepted without error.
     */
    @Test
    void singleBangOnNumericBindingProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button disable="${!count}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * The {@code !} operator in an Evaluate binding ({@code $!active}) should be accepted.
     */
    @Test
    void singleBangInEvaluateSyntaxProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button disable="$!active"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // !! operator: positive cases
    // -----------------------------------------------------------------------

    /**
     * {@code visible="${!!count}"}: the {@code !!} double-bang on a numeric binding
     * (converts 0 to false) should be accepted without error.
     */
    @Test
    void doubleBangOnNumericBindingProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button visible="${!!count}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * The {@code !!} operator on a boolean binding should be accepted.
     */
    @Test
    void doubleBangOnBooleanBindingProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button visible="${!!active}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Unresolvable path with operator: error still reported on path segment
    // -----------------------------------------------------------------------

    /**
     * Even with a {@code !} prefix, an unresolvable path segment should still produce
     * a resolution error on the segment.
     */
    @Test
    void singleBangWithUnresolvablePathProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button disable="${!<error descr="'nonExistent' in test.TestView cannot be resolved">nonExistent</error>}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Applicability rules: ! / !! must not be used with content bindings
    // -----------------------------------------------------------------------

    /**
     * boolean operators are NOT applicable to {@code fx:Evaluate} content ({@code $..x}).
     * Using {@code $..!items} should produce an error on the {@code !} operator.
     */
    @Test
    void singleBangOnEvaluateContentBindingProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView styleClass="$..<error descr="Boolean operator '!' is not applicable to fx:Evaluate bindings">!</error>items"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * boolean operators are NOT applicable to {@code fx:Observe} content ({@code ${..x}}).
     */
    @Test
    void singleBangOnObserveContentProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView styleClass="${..<error descr="Boolean operator '!' is not applicable to fx:Observe bindings">!</error>items}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * boolean operators are NOT applicable to {@code fx:Synchronize} content
     * ({@code #{..x}}).
     */
    @Test
    void singleBangOnSynchronizeContentProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView",
                """
                  <ListView styleClass="#{..<error descr="Boolean operator '!' is not applicable to fx:Synchronize bindings">!</error>items}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Navigation: Ctrl+click on ! / !! navigates to the correct BooleanBindings method
    // -----------------------------------------------------------------------

    /**
     * Helper: find the first XmlAttributeValue in the test file whose string value
     * matches {@code value} (e.g. {@code "${!active}"}).
     */
    private XmlAttributeValue findAttrValue(String value) {
        for (XmlTag tag : PsiTreeUtil.findChildrenOfType(
                getFixture().getFile(), XmlTag.class)) {
            for (var attr : tag.getAttributes()) {
                XmlAttributeValue v = attr.getValueElement();
                if (v != null && value.equals(v.getValue())) return v;
            }
        }
        return null;
    }

    /**
     * Helper: find the most specific reference at element-relative offset {@code offset}
     * inside the given attribute value element: mirroring the element-level
     * {@code findReferenceAt} which picks the smallest-range reference.
     * In the expressions tested here the operator always starts at element offset 3
     * (after {@code "${}}: offset 0='"', 1='$', 2='{', 3=first '!').
     */
    @SuppressWarnings("SameParameterValue") // relOffset is always 3 by design
    private static PsiReference findSmallestRefAt(XmlAttributeValue attrVal, int relOffset) {
        return attrVal.findReferenceAt(relOffset);
    }

    /**
     * {@code !} on a {@code BooleanProperty} -> navigates to
     * {@code BooleanBindings.isNot(ObservableValue<Boolean>)}.
     * This mirrors the compiler's {@code emitSharedImpl} path: boolean type -> {@code "isNot"}.
     */
    @Test
    void singleBangOnBooleanNavigatesToIsNot() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button disable="${!active}"/>
                """
        ));
        ReadAction.run(() -> {
            // '!' is at element-relative offset 3 inside the attribute value '"${!active}"'.
            XmlAttributeValue attrVal = findAttrValue("${!active}");
            assertNotNull(attrVal, "Could not find disable=\"${!active}\" attribute value");
            PsiReference ref = findSmallestRefAt(attrVal, 3);
            assertNotNull(ref, "Expected a reference on '!'");
            PsiElement resolved = ref.resolve();
            assertNotNull(resolved, "'!' should resolve to a PsiMethod");
            assertInstanceOf(PsiMethod.class, resolved);
            PsiMethod m = (PsiMethod) resolved;
            assertEquals("isNot", m.getName(),
                    "'!' on BooleanProperty should navigate to BooleanBindings.isNot");
            PsiClass cls = m.getContainingClass();
            assertNotNull(cls, "isNot should belong to a class");
            assertEquals("org.jfxcore.markup.runtime.BooleanBindings", cls.getQualifiedName());
        });
    }

    /**
     * {@code !} on an {@code IntegerProperty} -> navigates to
     * {@code BooleanBindings.isZero(...)}.
     * This mirrors the compiler's {@code emitSharedImpl} path: Number type -> {@code "isZero"}.
     */
    @Test
    void singleBangOnNumericNavigatesToIsZero() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button disable="${!count}"/>
                """
        ));
        ReadAction.run(() -> {
            XmlAttributeValue attrVal = findAttrValue("${!count}");
            assertNotNull(attrVal, "Could not find disable=\"${!count}\" attribute value");
            PsiReference ref = findSmallestRefAt(attrVal, 3);
            assertNotNull(ref, "Expected a reference on '!'");
            // Diagnostic: print all refs and what they resolve to
            StringBuilder diag = new StringBuilder("References at offset 3:\n");
            for (PsiReference r : attrVal.getReferences()) {
                var range = r.getRangeInElement();
                if (range.containsOffset(3)) {
                    diag.append("  ").append(r.getClass().getSimpleName())
                        .append(" range=").append(range)
                        .append(" resolves=").append(r.resolve()).append("\n");
                }
            }
            PsiElement resolved = ref.resolve();
            assertNotNull(resolved, "'!' should resolve to a PsiMethod\n" + diag);
            assertInstanceOf(PsiMethod.class, resolved);
            PsiMethod m = (PsiMethod) resolved;
            assertEquals("isZero", m.getName(),
                    "'!' on IntegerProperty should navigate to BooleanBindings.isZero");
            PsiClass cls = m.getContainingClass();
            assertNotNull(cls, "isZero should belong to a class");
            assertEquals("org.jfxcore.markup.runtime.BooleanBindings", cls.getQualifiedName());
        });
    }

    /**
     * {@code !!} on an {@code IntegerProperty} -> navigates to
     * {@code BooleanBindings.isNotZero(...)}.
     * This mirrors the compiler's BOOLIFY path for numeric types.
     */
    @Test
    void doubleBangOnNumericNavigatesToIsNotZero() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button visible="${!!count}"/>
                """
        ));
        ReadAction.run(() -> {
            // First '!' of '!!' is at element-relative offset 3 inside '"${!!count}"'.
            XmlAttributeValue attrVal = findAttrValue("${!!count}");
            assertNotNull(attrVal, "Could not find visible=\"${!!count}\" attribute value");
            PsiReference ref = findSmallestRefAt(attrVal, 3);
            assertNotNull(ref, "Expected a reference on '!!'");
            PsiElement resolved = ref.resolve();
            assertNotNull(resolved, "'!!' should resolve to a PsiMethod");
            assertInstanceOf(PsiMethod.class, resolved);
            PsiMethod m = (PsiMethod) resolved;
            assertEquals("isNotZero", m.getName(),
                    "'!!' on IntegerProperty should navigate to BooleanBindings.isNotZero");
            PsiClass cls = m.getContainingClass();
            assertNotNull(cls, "isNotZero should belong to a class");
            assertEquals("org.jfxcore.markup.runtime.BooleanBindings", cls.getQualifiedName());
        });
    }

    /**
     * {@code !!} on a {@code BooleanProperty} -> no operator navigation target.
     * The compiler handles this as a compile-time no-op: the child observable is yielded
     * directly without any {@code BooleanBindings} wrapping. No {@link Fxml2AttributeValueReference}
     * should be attached to the {@code !!} range; the only reference at that position should
     * be the always-null {@code Fxml2ExpressionReference} soft blocker.
     */
    @Test
    void doubleBangOnBooleanHasNoOperatorReference() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button visible="${!!active}"/>
                """
        ));
        ReadAction.run(() -> {
            XmlAttributeValue attrVal = findAttrValue("${!!active}");
            assertNotNull(attrVal, "Could not find visible=\"${!!active}\" attribute value");
            // Verify that no Fxml2AttributeValueReference covers the '!!' range [3, 5).
            boolean hasOperatorRef = false;
            for (PsiReference ref : attrVal.getReferences()) {
                if (ref instanceof Fxml2AttributeValueReference
                        && ref.getRangeInElement().getStartOffset() == 3) {
                    hasOperatorRef = true;
                    break;
                }
            }
            assertFalse(hasOperatorRef,
                    "'!!' on BooleanProperty is a compiler no-op - no operator reference expected");
        });
    }
}
