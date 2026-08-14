package org.jfxcore.fxml;

import com.intellij.codeInsight.lookup.LookupElement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that completion in a value sequence offers the values of the item at the caret rather
 * than the values of the whole property.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2ValueSequenceCompletionTest extends Fxml2TestBase {

    @BeforeAll
    void addPropertyMocks() {
        getFixture().addClass("""
                package test;
                import java.util.List;
                import javafx.geometry.Pos;
                import javafx.scene.layout.Pane;
                public class SequencePane extends Pane {
                    public SequencePane() {}
                    public void setPositions(List<Pos> positions) {}
                    public List<Pos> getPositions() { return null; }
                }
                """);
    }

    private static List<String> lookupStrings(LookupElement[] items) {
        return Arrays.stream(items).map(LookupElement::getLookupString).toList();
    }

    /** The first item of a collection value is completed against the element type. */
    @Test
    void firstItemIsCompletedAgainstTheElementType() {
        getFixture().configureByText("SeqFirst.fxml", fxml(
                "test.SequencePane",
                """
                  <SequencePane positions="<caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items");
        assertTrue(lookupStrings(items).contains("CENTER"),
                "Expected the element type's constants, got: " + lookupStrings(items));
    }

    /** An item after a separator is completed against the element type as well. */
    @Test
    void itemAfterSeparatorIsCompletedAgainstTheElementType() {
        getFixture().configureByText("SeqLater.fxml", fxml(
                "test.SequencePane",
                """
                  <SequencePane positions="TOP_LEFT, <caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items");
        assertTrue(lookupStrings(items).contains("CENTER"),
                "Expected the element type's constants, got: " + lookupStrings(items));
    }

    /**
     * An item of an implicit-constructor value is completed against the type of the constructor
     * parameter it supplies.
     */
    @Test
    void itemOfImplicitConstructorIsCompletedAgainstItsParameterType() {
        getFixture().configureByText("SeqArgs.fxml", fxml(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane padding="10, 20, 10, <caret>"/>
                """
        ));
        LookupElement[] items = getFixture().completeBasic();
        assertNotNull(items, "Expected completion items");
        assertTrue(lookupStrings(items).contains("Infinity"),
                "Expected the parameter type's constants, got: " + lookupStrings(items));
    }

}
