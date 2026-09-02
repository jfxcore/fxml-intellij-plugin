// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import org.jfxcore.fxml.annotator.Fxml2ResourceMediaTypeInspection;
import org.jfxcore.fxml.annotator.Fxml2UnusedResourceInspection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the two advisory inspections on {@code <?resource ?>} declarations: the one that
 * reports a declaration nothing refers to, and the one that reports a media type that disagrees
 * with the resource name's extension.
 *
 * <p>Neither reports a correctness problem, which is why both are weak warnings: an unreferenced
 * resource is still compiled, and the media type is informational to the compiler.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class Fxml2ResourceAdvisoryInspectionTest extends Fxml2TestBase {

    /** The markup extension the {@code @} prefix notation is shorthand for. */
    @BeforeAll
    void addClassPathResource() {
        getFixture().addClass("""
                package org.jfxcore.markup.resource;
                import javafx.beans.DefaultProperty;
                import javafx.beans.NamedArg;
                @DefaultProperty("value")
                public final class ClassPathResource {
                    public ClassPathResource(@NamedArg("value") String value) {}
                }
                """);
    }

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(
                new Fxml2UnusedResourceInspection(),
                new Fxml2ResourceMediaTypeInspection());
    }

    /** A declaration that nothing refers to is reported on its name. */
    @Test
    void unreferencedDeclarationIsReported() {
        configure("""
                <?resource <weak_warning descr="Embedded resource 'notes.txt' is never used in this document">notes.txt</weak_warning>:body?>
                """, "");
        getFixture().checkHighlighting(false, false, true);
    }

    /** A declaration the markup loads with the prefix notation is used. */
    @Test
    void declarationUsedByPrefixNotationIsNotReported() {
        configure("""
                <?resource styles.css text/css:.root { -fx-base: black; }?>
                """, """
                  <BorderPane stylesheets="@styles.css"/>
                """);
        getFixture().checkHighlighting(false, false, true);
    }

    /** A declaration the markup loads with the markup extension notation is used. */
    @Test
    void declarationUsedByMarkupExtensionIsNotReported() {
        configure("""
                <?import org.jfxcore.markup.resource.ClassPathResource?>
                <?resource styles.css text/css:.root { -fx-base: black; }?>
                """, """
                  <BorderPane stylesheets="{ClassPathResource styles.css}"/>
                """);
        getFixture().checkHighlighting(false, false, true);
    }

    /** A stylesheet pulled in by another resource's payload counts as used. */
    @Test
    void declarationImportedByASiblingPayloadIsNotReported() {
        configure("""
                <?resource base.css text/css:.root { -fx-base: black; }?>
                <?resource theme.css text/css:@import "base.css";?>
                """, """
                  <BorderPane stylesheets="@theme.css"/>
                """);
        getFixture().checkHighlighting(false, false, true);
    }

    /** A name that is a prefix of another name is not mistaken for a usage of it. */
    @Test
    void aLongerNameIsNotCountedAsAUsage() {
        configure("""
                <?resource <weak_warning descr="Embedded resource 'base.css' is never used in this document">base.css</weak_warning> text/css:.root {}?>
                <?resource my-base.css text/css:.root {}?>
                """, """
                  <BorderPane stylesheets="@my-base.css"/>
                """);
        getFixture().checkHighlighting(false, false, true);
    }

    /** A declaration whose media type the extension implies is not reported. */
    @Test
    void mediaTypeAgreeingWithTheExtensionIsNotReported() {
        configure("""
                <?resource styles.css text/css:.root {}?>
                """, """
                  <BorderPane stylesheets="@styles.css"/>
                """);
        getFixture().checkHighlighting(false, false, true);
    }

    /** A declaration with no media type at all is reported on its name. */
    @Test
    void missingMediaTypeIsReported() {
        configure("""
                <?resource <weak_warning descr="Resource 'styles.css' has no media type; its name implies text/css">styles.css</weak_warning>:.root {}?>
                """, """
                  <BorderPane stylesheets="@styles.css"/>
                """);
        getFixture().checkHighlighting(false, false, true);
    }

    /** A declared media type that contradicts the extension is reported on the media type. */
    @Test
    void contradictingMediaTypeIsReported() {
        configure("""
                <?resource styles.css <weak_warning descr="Media type of resource 'styles.css' does not match its file extension, which implies text/css">application/json</weak_warning>:.root {}?>
                """, """
                  <BorderPane stylesheets="@styles.css"/>
                """);
        getFixture().checkHighlighting(false, false, true);
    }

    /** Writing the implied media type keeps the parameters the declaration already carried. */
    @Test
    void settingTheMediaTypeKeepsExistingParameters() {
        configure("""
                <?resource styles.css applica<caret>tion/json;charset=UTF-16LE:.root {}?>
                """, """
                  <BorderPane stylesheets="@styles.css"/>
                """);

        getFixture().launchAction(getFixture().findSingleIntention("Set media type to 'text/css'"));

        String text = ReadAction.compute(() -> getFixture().getFile().getText());
        assertTrue(text.contains("<?resource styles.css text/css;charset=UTF-16LE:"),
                "the charset parameter survives: " + text);
    }

    /** Removing an unused declaration takes the line it sits on with it. */
    @Test
    void removingAnUnusedDeclarationTakesItsLine() {
        configure("""
                <?resource not<caret>es.txt:body?>
                """, "");

        getFixture().launchAction(getFixture().findSingleIntention("Remove embedded resource 'notes.txt'"));

        String text = ReadAction.compute(() -> getFixture().getFile().getText());
        assertFalse(text.contains("<?resource"), "the declaration is gone: " + text);
        assertEquals("""
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0">
                </BorderPane>
                """, text);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void configure(String prolog, String body) {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                %s<BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0">
                %s</BorderPane>
                """.formatted(prolog, body));
    }
}
