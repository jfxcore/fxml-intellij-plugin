// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that double-click word selection (EditorSelectWord action) inside embedded FXML markup
 * does not include FXML sigil characters ({@code $}, {@code #}, {@code %}) in the selection.
 *
 * <p>In Java, {@code $} is a valid identifier character, so double-clicking on "vm" in
 * {@code $vm.field} would normally select {@code $vm}. The plugin must trim the
 * leading sigil so that only the identifier "vm" is selected.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class Fxml2WordSelectionTest extends Fxml2TestBase {

    @BeforeAll
    void addMarkupAnnotation() {
        getFixture().addClass("""
                package org.jfxcore.markup;
                import java.lang.annotation.*;
                @Target(ElementType.TYPE)
                @Retention(RetentionPolicy.SOURCE)
                public @interface ComponentView {
                    String value();
                }
                """);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Positions the caret at the {@code <caret>} marker, triggers EditorSelectWord,
     * and returns the selected text.
     */
    private String selectWord(String javaSource) {
        getFixture().configureByText("TestView.java", javaSource);
        getFixture().doHighlighting();
        getFixture().performEditorAction("EditorSelectWord");
        Editor editor = getFixture().getEditor();
        return ApplicationManager.getApplication().runReadAction(
                (com.intellij.openapi.util.Computable<String>) () ->
                        editor.getSelectionModel().getSelectedText());
    }

    // -----------------------------------------------------------------------
    // Embedded FXML: $ prefix (evaluate binding)
    // -----------------------------------------------------------------------

    /**
     * Double-clicking on the identifier in {@code $vm<caret>} should select "vm",
     * not "$vm".
     */
    @Test
    void dollarsignPrefixNotIncludedInSelection_evaluate() {
        String selected = selectWord("""
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <Button Command.onAction="$v<caret>m"/>
                    </StackPane>
                \""")
                public class TestView {
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);
        assertEquals("vm", selected,
                "Expected selection 'vm' but got: '" + selected + "'");
    }

    /**
     * Double-clicking on the identifier after ${  in {@code ${vm<caret>.message}} should
     * select "vm", not "${vm" or "$vm".
     */
    @Test
    void dollarsignBracePrefixNotIncludedInSelection() {
        String selected = selectWord("""
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <Button text="${v<caret>m.message}"/>
                    </StackPane>
                \""")
                public class TestView {
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);
        assertEquals("vm", selected,
                "Expected selection 'vm' but got: '" + selected + "'");
    }

    /**
     * Double-clicking on the identifier in {@code #{vm<caret>} } (bidirectional binding)
     * should select "vm", not "#vm" or "#{vm".
     */
    @Test
    void hashPrefixNotIncludedInSelection_bidirectional() {
        String selected = selectWord("""
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <TextField text="#{v<caret>m.message}"/>
                    </StackPane>
                \""")
                public class TestView {
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);
        assertEquals("vm", selected,
                "Expected selection 'vm' but got: '" + selected + "'");
    }

    /**
     * Double-clicking on the property key in {@code %butt<caret>on.text} (static resource)
     * should select "button", not "%button".
     */
    @Test
    void percentPrefixNotIncludedInSelection_staticResource() {
        String selected = selectWord("""
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <Button text="%butt<caret>on.text"/>
                    </StackPane>
                \""")
                public class TestView {
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);
        assertEquals("button", selected,
                "Expected selection 'button' but got: '" + selected + "'");
    }

    /**
     * Double-clicking on a plain attribute value (no sigil) should still select the whole word.
     */
    @Test
    void plainAttributeValueNotAffected() {
        String selected = selectWord("""
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane>
                      <Button text="Hel<caret>lo"/>
                    </StackPane>
                \""")
                public class TestView {
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);
        assertEquals("Hello", selected,
                "Expected selection 'Hello' but got: '" + selected + "'");
    }

    // -----------------------------------------------------------------------
    // Standalone FXML files (word-selection must work correctly)
    // -----------------------------------------------------------------------

    /**
     * In a standalone FXML file, double-clicking on "vm" in {@code $vm<caret>.message}
     * should still select just "vm".
     */
    @Test
    void standaloneFile_dollarsignNotIncluded() {
        getFixture().configureByText("TestView.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.*?>
                <?import javafx.scene.control.*?>
                <StackPane xmlns="http://javafx.com/javafx"
                           xmlns:fx="http://jfxcore.org/fxml/2.0"
                           fx:subclass="test.TestView">
                  <Button text="$v<caret>m.message"/>
                </StackPane>
                """);
        getFixture().doHighlighting();
        getFixture().performEditorAction("EditorSelectWord");
        Editor editor = getFixture().getEditor();
        String selected = ApplicationManager.getApplication().runReadAction(
                (com.intellij.openapi.util.Computable<String>) () ->
                        editor.getSelectionModel().getSelectedText());
        assertEquals("vm", selected,
                "Expected selection 'vm' but got: '" + selected + "'");
    }
}
