// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import org.jfxcore.fxml.annotator.Fxml2CyclicPropertyAssignmentInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2CyclicPropertyAssignmentTest extends Fxml2TestBase {
    @BeforeEach
    void enableInspection() {
        getFixture().enableInspections(new Fxml2CyclicPropertyAssignmentInspection());
    }

    @Test
    void evaluationCycleIsReported() {
        configure("prefWidth=\"$prefHeight\" prefHeight=\"$prefWidth\"", "");
        assertTrue(hasCycle());
    }

    @Test
    void arithmeticCycleIsReported() {
        configure("prefWidth=\"{fx:Evaluate prefHeight + 10}\" prefHeight=\"$prefWidth\"", "");
        assertTrue(hasCycle());
    }

    @Test
    void explicitElementSelectorCycleIsReported() {
        configure("prefWidth=\"$:element.prefHeight\" prefHeight=\"$:element.prefWidth\"", "");
        assertTrue(hasCycle());
    }

    @Test
    void propertyElementCycleIsReported() {
        configure("", "<prefWidth><fx:Evaluate source=\"prefHeight\"/></prefWidth>"
                + "<prefHeight><fx:Evaluate source=\"prefWidth\"/></prefHeight>");
        assertTrue(hasCycle());
    }

    @Test
    void observableCycleIsAllowed() {
        configure("prefWidth=\"${prefHeight}\" prefHeight=\"${prefWidth}\"", "");
        assertFalse(hasCycle());
    }

    @Test
    void mixedCycleIsAllowed() {
        configure("prefWidth=\"$prefHeight\" prefHeight=\"${prefWidth}\"", "");
        assertFalse(hasCycle());
    }

    @Test
    void acyclicAssignmentsAreAllowed() {
        configure("prefWidth=\"$prefHeight\" prefHeight=\"123\"", "");
        assertFalse(hasCycle());
    }

    @Test
    void dynamicContextDoesNotProduceSpeculativeCycles() {
        configure("fx:context=\"$userData\" prefWidth=\"$prefHeight\" prefHeight=\"$prefWidth\"", "");
        assertFalse(hasCycle());
    }

    @Test
    void nestedParentDependencyParticipatesInContainerCycle() {
        configure("prefWidth=\"$center.prefWidth\"",
                "<center><BorderPane prefWidth=\"$:parent.prefWidth\"/></center>");
        assertTrue(hasCycle());
    }

    @Test
    void defaultPropertyChildrenParticipateInContainerCycle() {
        getFixture().configureByText("AssignmentView.fxml", """
                <?import javafx.scene.layout.VBox?>
                <VBox xmlns="http://javafx.com/javafx" xmlns:fx="http://jfxcore.org/fxml/2.0"
                      prefWidth="$children.size">
                    <VBox prefWidth="$:parent.prefWidth"/>
                </VBox>
                """);
        assertTrue(hasCycle());
    }

    private void configure(String attributes, String children) {
        getFixture().configureByText("AssignmentView.fxml", """
                <?import javafx.scene.layout.BorderPane?>
                <BorderPane xmlns="http://javafx.com/javafx" xmlns:fx="http://jfxcore.org/fxml/2.0"
                """ + attributes + ">" + children + "</BorderPane>");
    }

    private boolean hasCycle() {
        return getFixture().doHighlighting().stream().anyMatch(
                info -> info.getDescription() != null && info.getDescription().startsWith("Cyclic property assignment:"));
    }
}
