// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.application.ReadAction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the FXML2-aware import optimizer behavior in Java and Kotlin files carrying
 * {@code @ComponentView} annotations.
 *
 * <p>Covers:
 * <ul>
 *   <li>The "Optimize imports (FXML2)" intention action is available when the caret
 *       is in the import section of a {@code @ComponentView} Java or Kotlin file.</li>
 *   <li>Running that action preserves imports that are referenced only inside the
 *       embedded FXML2 markup and not in the surrounding host-language code.</li>
 *   <li>The action is not available in host-language files that do not carry
 *       {@code @ComponentView}.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2OptimizeImportsTest extends Fxml2TestBase {

    private static final String ACTION_NAME = "Optimize Imports (FXML2)";

    @BeforeAll
    void addComponentViewAnnotation() {
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
    // Action availability

    /**
     * The "Optimize imports (FXML2)" action must be available when the caret is positioned
     * inside the import section of a {@code @ComponentView}-annotated Java file.
     */
    @Test @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void optimizeImportsActionIsAvailableOnImportInComponentViewFile() {
        getFixture().configureByText("OptView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.<caret>BorderPane;
                @ComponentView(\"""
                    <BorderPane/>
                    \""")
                public class OptView {}
                """);
        getFixture().doHighlighting();

        boolean hasAction = getFixture().getAvailableIntentions().stream()
                .anyMatch(a -> ACTION_NAME.equals(a.getFamilyName()));
        assertTrue(hasAction,
                "Expected '" + ACTION_NAME + "' action in @ComponentView Java file");
    }

    /**
     * The "Optimize imports (FXML2)" action must NOT be available in a Java file that
     * has no {@code @ComponentView} annotation, so it does not interfere with ordinary
     * Java files.
     */
    @Test @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void optimizeImportsActionIsNotAvailableInOrdinaryJavaFile() {
        getFixture().configureByText("PlainView.java", """
                package test;
                import javafx.scene.layout.<caret>BorderPane;
                public class PlainView {
                    public BorderPane root = new BorderPane();
                }
                """);
        getFixture().doHighlighting();

        boolean hasAction = getFixture().getAvailableIntentions().stream()
                .anyMatch(a -> ACTION_NAME.equals(a.getFamilyName()));
        assertFalse(hasAction,
                "'" + ACTION_NAME + "' must not appear in files without @ComponentView");
    }

    // -----------------------------------------------------------------------
    // Action behavior: markup-needed imports are preserved

    /**
     * When "Optimize imports (FXML2)" is invoked on a {@code @ComponentView} Java file
     * whose only use of an import is inside the embedded markup, the import must be kept
     * because removing it would break the markup compilation.
     */
    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void optimizeImportsPreservesMarkupNeededImport() {
        getFixture().configureByText("PreserveView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.<caret>BorderPane;
                @ComponentView(\"""
                    <BorderPane/>
                    \""")
                public class PreserveView {}
                """);
        // Trigger injection so the optimizer can analyze embedded markup.
        getFixture().doHighlighting();

        IntentionAction action = getFixture().getAvailableIntentions().stream()
                .filter(a -> ACTION_NAME.equals(a.getFamilyName()))
                .findFirst()
                .orElse(null);
        assertNotNull(action, "Expected '" + ACTION_NAME + "' action");

        getFixture().launchAction(action);

        String text = ReadAction.compute(() -> getFixture().getFile().getText());
        assertTrue(text.contains("import javafx.scene.layout.BorderPane"),
                "Optimize imports (FXML2) must preserve imports needed by embedded markup\n" + text);
    }

    // -----------------------------------------------------------------------
    // Kotlin

    /**
     * The "Optimize imports (FXML2)" action must be available when the caret is inside
     * the import section of a {@code @ComponentView}-annotated Kotlin file.
     */
    @Test @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void optimizeImportsActionIsAvailableOnImportInKotlinComponentViewFile() {
        getFixture().configureByText("KtOptView.kt", """
                package test
                import org.jfxcore.markup.ComponentView
                import javafx.scene.layout.<caret>BorderPane
                @ComponentView(\"""
                    <BorderPane/>
                    \""")
                open class KtOptView
                """);
        getFixture().doHighlighting();

        boolean hasAction = getFixture().getAvailableIntentions().stream()
                .anyMatch(a -> ACTION_NAME.equals(a.getFamilyName()));
        assertTrue(hasAction,
                "Expected '" + ACTION_NAME + "' action in @ComponentView Kotlin file");
    }

    /**
     * When "Optimize imports (FXML2)" is invoked on a {@code @ComponentView} Kotlin file
     * whose only use of an import is inside the embedded markup, the import must be kept.
     */
    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void optimizeImportsPreservesMarkupNeededImportInKotlin() {
        getFixture().configureByText("KtPreserveView.kt", """
                package test
                import org.jfxcore.markup.ComponentView
                import javafx.scene.layout.<caret>BorderPane
                @ComponentView(\"""
                    <BorderPane/>
                    \""")
                open class KtPreserveView
                """);
        getFixture().doHighlighting();

        IntentionAction action = getFixture().getAvailableIntentions().stream()
                .filter(a -> ACTION_NAME.equals(a.getFamilyName()))
                .findFirst()
                .orElse(null);
        assertNotNull(action, "Expected '" + ACTION_NAME + "' action in Kotlin file");

        getFixture().launchAction(action);

        String text = ReadAction.compute(() -> getFixture().getFile().getText());
        assertTrue(text.contains("import javafx.scene.layout.BorderPane"),
                "Optimize imports (FXML2) must preserve FXML2-needed imports in Kotlin\n" + text);
    }
}
