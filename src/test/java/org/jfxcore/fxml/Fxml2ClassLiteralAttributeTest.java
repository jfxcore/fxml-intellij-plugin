package org.jfxcore.fxml;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code Class<T>} attribute-value coercion feature.
 *
 * <p>When a property type is {@code java.lang.Class<T>}, a plain string attribute value is
 * treated as a class name and coerced to the corresponding {@code Class} literal.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2ClassLiteralAttributeTest extends Fxml2TestBase {

    @BeforeAll
    void addClassLiteralPaneMock() {
        // A generic class with a Class<T> property and a Class<?> property.
        getFixture().addClass("""
                package test;
                import javafx.scene.Node;
                import javafx.scene.layout.Pane;
                public class ClassLiteralPane<T> extends Pane {
                    public Class<T> getClassLiteral() { return null; }
                    public void setClassLiteral(Class<T> value) {}
                    public Class<?> getWildcardClassLiteral() { return null; }
                    public void setWildcardClassLiteral(Class<?> value) {}
                    public Class<? extends Node> getNodeClassLiteral() { return null; }
                    public void setNodeClassLiteral(Class<? extends Node> value) {}
                }
                """);
    }

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2AttributeValueInspection());
    }

    // -----------------------------------------------------------------------
    // Inspection: valid cases (no errors expected)
    // -----------------------------------------------------------------------

    /**
     * A class literal that matches the type argument must produce no error.
     * {@code classLiteral="String"} on {@code Class<String>} (T bound to String via
     * {@code fx:typeArguments}).
     */
    @Test
    void classLiteralMatchingTypeArgumentProducesNoError() {
        getFixture().configureByText("ClassLiteralValid.fxml", fxml(
                "test.ClassLiteralPane",
                """
                  <ClassLiteralPane fx:typeArguments="String" classLiteral="String"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A raw class name is valid for a {@code Class<Comparable<String>>} property when the
     * resolved class is the raw form of the type argument's erasure.
     * {@code classLiteral="Comparable"} on {@code Class<Comparable<String>>}.
     */
    @Test
    void classLiteralRawComparableOnParameterizedTypeProducesNoError() {
        getFixture().configureByText("ClassLiteralComparable.fxml", fxml(
                "test.ClassLiteralPane",
                """
                  <ClassLiteralPane classLiteral="Comparable"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Any class is valid for a wildcard {@code Class<?>} property.
     * {@code wildcardClassLiteral="Double"} on {@code Class<?>}.
     */
    @Test
    void classLiteralOnWildcardClassPropertyProducesNoError() {
        getFixture().configureByText("ClassLiteralWildcard.fxml", fxml(
                "test.ClassLiteralPane",
                """
                  <ClassLiteralPane wildcardClassLiteral="Double"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Inspection: invalid cases (errors expected)
    // -----------------------------------------------------------------------

    /**
     * A comma-separated list of class names is a syntax error.
     * {@code classLiteral="String, Double"} must be reported as "Invalid expression".
     */
    @Test
    void commaSeparatedClassNamesProducesInvalidExpressionError() {
        getFixture().configureByText("ClassLiteralComma.fxml", fxml(
                "test.ClassLiteralPane",
                """
                  <ClassLiteralPane fx:typeArguments="String" classLiteral=<error descr="Invalid expression">"String, Double"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A parameterized type expression as the value is a syntax error.
     * {@code classLiteral="Comparable<String>"} must be reported as "Invalid expression".
     */
    @Test
    void parameterizedTypeExpressionProducesInvalidExpressionError() {
        getFixture().configureByText("ClassLiteralParameterized.fxml", fxml(
                "test.ClassLiteralPane",
                """
                  <ClassLiteralPane fx:typeArguments="String" classLiteral=<error descr="Invalid expression">"Comparable<String>"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A class that is not assignable to the type argument must be reported as a coercion error.
     * {@code classLiteral="Double"} on {@code Class<String>} must produce a
     * "Cannot coerce" message.
     */
    @Test
    void incompatibleClassLiteralProducesCoercionError() {
        getFixture().configureByText("ClassLiteralIncompatible.fxml", fxml(
                "test.ClassLiteralPane",
                """
                  <ClassLiteralPane fx:typeArguments="String" classLiteral=<error descr="Cannot coerce 'Double' to Class<String>">"Double"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Navigation (Ctrl+click)
    // -----------------------------------------------------------------------

    /**
     * Ctrl+click on a valid class literal navigates to the referenced class.
     * {@code classLiteral="<caret>String"} must resolve to {@code java.lang.String}.
     */
    @Test
    void classLiteralNavigatesToClass() {
        getFixture().configureByText("ClassLiteralNav.fxml", fxml(
                "test.ClassLiteralPane",
                """
                  <ClassLiteralPane fx:typeArguments="String" classLiteral="<caret>String"/>
                """
        ));

        PsiElement resolved = ReadAction.compute(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue attrVal = PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            if (attrVal == null) return null;
            PsiReference[] refs = attrVal.getReferences();
            return refs.length > 0 ? refs[0].resolve() : null;
        });

        assertNotNull(resolved, "classLiteral='String' should resolve to a PsiClass");
        assertInstanceOf(PsiClass.class, resolved, "Should navigate to a PsiClass");
        assertEquals("java.lang.String", ((PsiClass) resolved).getQualifiedName(),
                "Should navigate to java.lang.String");
    }

    // -----------------------------------------------------------------------
    // Completion
    // -----------------------------------------------------------------------

    /**
     * Completing a {@code Class<String>} property should offer imported classes
     * that are assignable to {@code String}.
     */
    @Test
    void classLiteralCompletionOffersCompatibleImportedClasses() {
        getFixture().configureByText("ClassLiteralCompletion.fxml", fxml(
                "test.ClassLiteralPane",
                """
                  <ClassLiteralPane fx:typeArguments="String" classLiteral="<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items for Class<String> property");
        List<String> names = lookupItemTexts(items);
        // String itself is importable (java.lang.* implicit) and compatible with Class<String>.
        assertTrue(names.contains("String"),
                "Expected 'String' in completions for Class<String>, got: " + names);
    }

    /**
     * Completing a {@code Class<?>} wildcard property should offer all imported classes.
     */
    @Test
    void classLiteralCompletionForWildcardOffersAllImportedClasses() {
        getFixture().configureByText("ClassLiteralWildcardCompletion.fxml", fxml(
                "test.ClassLiteralPane",
                """
                  <ClassLiteralPane wildcardClassLiteral="<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items for Class<?> property");
        List<String> names = lookupItemTexts(items);
        // The file imports BorderPane and ClassLiteralPane; BorderPane is compatible with Class<?>.
        assertTrue(names.contains("BorderPane") || names.contains("ClassLiteralPane"),
                "Expected at least one imported class in completions for Class<?>, got: " + names);
    }

    /**
     * Completing a {@code Class<? extends Node>} property with a typed prefix should also
     * offer non-imported classes whose simple name starts with the prefix, filtered by
     * assignability to {@code Node}.
     *
     * <p>When the prefix matches a single candidate (e.g. {@code "Grid"} -> {@code GridPane}),
     * IntelliJ auto-applies it; the test then verifies the document text and the inserted
     * {@code <?import?>} PI.
     */
    @Test
    void classLiteralCompletionOffersNonImportedAssignableClasses() {
        getFixture().configureByText("ClassLiteralNonImportedCompletion.fxml", fxml(
                "test.ClassLiteralPane",
                """
                  <ClassLiteralPane nodeClassLiteral="Grid<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        if (items == null) {
            // Single match auto-applied: GridPane should now be in the document AND imported.
            String text = getFixture().getEditor().getDocument().getText();
            assertTrue(text.contains("nodeClassLiteral=\"GridPane\""),
                    "Expected single-match 'GridPane' to be inserted into the attribute value, got: " + text);
            assertTrue(text.contains("<?import javafx.scene.layout.GridPane?>"),
                    "Expected '<?import javafx.scene.layout.GridPane?>' to be inserted, got: " + text);
            return;
        }
        List<String> names = lookupItemTexts(items);
        // GridPane is not imported in the FXML file but is on the classpath and extends Node.
        assertTrue(names.contains("GridPane"),
                "Expected non-imported 'GridPane' in completions for Class<? extends Node>, got: " + names);
    }

    /**
     * Type-incompatible classes must not be offered for a bounded {@code Class<? extends Node>}
     * property even when the prefix matches. {@code String} matches the typed prefix "Str" but
     * is not assignable to {@code Node}.
     */
    @Test
    void classLiteralCompletionFiltersOutIncompatibleNonImportedClasses() {
        getFixture().configureByText("ClassLiteralIncompatibleCompletion.fxml", fxml(
                "test.ClassLiteralPane",
                """
                  <ClassLiteralPane nodeClassLiteral="Str<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        // completeBasic may return null when there are zero completions; treat that as empty.
        List<String> names = items == null ? List.of() : lookupItemTexts(items);
        assertFalse(names.contains("String"),
                "Did not expect 'String' in completions for Class<? extends Node>, got: " + names);
    }

    /**
     * The "Add import" quick fix must be registered on a {@code Class<? extends Node>} property
     * whose value is an unresolved simple class name with a single candidate on the classpath.
     * Invoking the fix inserts the {@code <?import?>} PI and clears the coercion error.
     */
    @Test
    void unresolvedClassNameOffersAddImportQuickfix() {
        getFixture().configureByText("ClassLiteralAddImport.fxml", fxml(
                "test.ClassLiteralPane",
                """
                  <ClassLiteralPane nodeClassLiteral="Grid<caret>Pane"/>
                """
        ));
        // The inspection registers a coercion error with an Add Import quick fix.
        IntentionAction action = getFixture().findSingleIntention("Add import for 'GridPane'");
        assertNotNull(action, "Add import quickfix should be offered for unresolved class literal");
        getFixture().launchAction(action);

        String text = getFixture().getEditor().getDocument().getText();
        assertTrue(text.contains("<?import javafx.scene.layout.GridPane?>"),
                "Expected <?import javafx.scene.layout.GridPane?> to be inserted, got: " + text);
    }

    /** Renders the visible item text for each lookup element. */
    private static List<String> lookupItemTexts(LookupElement[] items) {
        return Arrays.stream(items)
                .map(e -> {
                    LookupElementPresentation p = new LookupElementPresentation();
                    e.renderElement(p);
                    return p.getItemText();
                })
                .toList();
    }
}
