// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.ex.QuickFixWrapper;
import com.intellij.openapi.application.ReadAction;
import org.jfxcore.fxml.annotator.Fxml2PreferCodeImportInspection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Fxml2PreferCodeImportInspection}.
 *
 * <p>Covers:
 * <ul>
 *   <li>The inspection reports a {@code <?import?>} PI inside embedded markup when no
 *       equivalent code import exists in the host file.</li>
 *   <li>The inspection is silent when the host file already has the import (duplicate
 *       guard).</li>
 *   <li>The inspection is silent when a conflicting import for the same simple name
 *       exists (conflict guard).</li>
 *   <li>The quick-fix adds the import to the host Java file and removes the
 *       {@code <?import?>} PI from the markup.</li>
 *   <li>The batch quick-fix processes multiple PIs in one pass.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2PreferCodeImportInspectionTest extends Fxml2TestBase {

    private static final String FIX_NAME = "Move to Java/Kotlin import";

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
    // Inspection fires: markup <?import?> with no corresponding code import

    /**
     * A {@code <?import?>} inside embedded FXML2 markup that has no corresponding code
     * import in the host Java file must be flagged.
     */
    @Test @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void markupImportWithNoCodeImport_inspection_fires() {
        getFixture().enableInspections(new Fxml2PreferCodeImportInspection());
        getFixture().configureByText("MarkupImportView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.BorderPane;
                @ComponentView(\"""
                    <?import javafx.scene.control.Button?>
                    <BorderPane>
                        <Button text="hello"/>
                    </BorderPane>
                    \""")
                public class MarkupImportView {
                    public MarkupImportView() {}
                }
                """);


        List<IntentionAction> fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && FIX_NAME.equals(fix.getFamilyName());
                })
                .toList();
        assertFalse(fixes.isEmpty(),
                "Expected '" + FIX_NAME + "' quick-fix for <?import javafx.scene.control.Button?> "
                + "when no code import exists in the host file");
    }

    // -----------------------------------------------------------------------
    // Duplicate guard: inspection is silent when code import already exists

    /**
     * When the host Java file already has a code import for the same FQN, the inspection
     * must remain silent (the declaration is not duplicated).
     */
    @Test @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void codeImportAlreadyExists_noFix() {
        getFixture().enableInspections(new Fxml2PreferCodeImportInspection());
        getFixture().configureByText("AlreadyImportedView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.BorderPane;
                import javafx.scene.control.Button;
                @ComponentView(\"""
                    <?import javafx.scene.control.Button?>
                    <BorderPane>
                        <Button text="hello"/>
                    </BorderPane>
                    \""")
                public class AlreadyImportedView {
                    public AlreadyImportedView() {}
                }
                """);


        List<IntentionAction> fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && FIX_NAME.equals(fix.getFamilyName());
                })
                .toList();
        assertTrue(fixes.isEmpty(),
                "Must not offer 'Move to Java/Kotlin import' when the host file already imports the same FQN");
    }

    // -----------------------------------------------------------------------
    // Wildcard guard: inspection is silent when covered by wildcard import

    /**
     * When the host file has a wildcard import that covers the class referenced by the
     * {@code <?import?>} PI, the inspection must not offer to move it (the class is
     * already available in Java code scope).
     */
    @Test @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void wildcardImportCoversClass_noFix() {
        getFixture().enableInspections(new Fxml2PreferCodeImportInspection());
        getFixture().configureByText("WildcardView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.BorderPane;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <?import javafx.scene.control.Button?>
                    <BorderPane>
                        <Button text="hello"/>
                    </BorderPane>
                    \""")
                public class WildcardView {
                    public WildcardView() {}
                }
                """);


        List<IntentionAction> fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && FIX_NAME.equals(fix.getFamilyName());
                })
                .toList();
        assertTrue(fixes.isEmpty(),
                "Must not offer 'Move to Java/Kotlin import' when the class is already covered by a wildcard import");
    }

    // -----------------------------------------------------------------------
    // Conflict guard: inspection is silent when a different class with the same
    // simple name is already imported

    /**
     * When the host file imports a class with the same simple name as the markup
     * {@code <?import?>} but a different FQN, the inspection must not offer the fix
     * (moving the import would introduce an ambiguous identifier).
     */
    @Test @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void conflictingSimpleNameImport_noFix() {
        // Create a test class named "Button" in a different package.
        getFixture().addClass("""
                package test.ui;
                public class Button extends javafx.scene.Node {}
                """);

        getFixture().enableInspections(new Fxml2PreferCodeImportInspection());
        getFixture().configureByText("ConflictView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.BorderPane;
                import test.ui.Button;
                @ComponentView(\"""
                    <?import javafx.scene.control.Button?>
                    <BorderPane/>
                    \""")
                public class ConflictView {
                    public ConflictView() {}
                }
                """);


        List<IntentionAction> fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && FIX_NAME.equals(fix.getFamilyName());
                })
                .toList();
        assertTrue(fixes.isEmpty(),
                "Must not offer 'Move to Java/Kotlin import' when a different class with the "
                + "same simple name is already imported in the host file");
    }

    // -----------------------------------------------------------------------
    // Quick-fix: adds import to host file and removes PI from markup

    /**
     * After applying the quick-fix:
     * <ol>
     *   <li>{@code import javafx.scene.control.Button;} must appear in the Java file.</li>
     *   <li>{@code <?import javafx.scene.control.Button?>} must be removed from the
     *       markup.</li>
     * </ol>
     */
    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void quickFixAddsCodeImportAndRemovesMarkupPi() {
        getFixture().enableInspections(new Fxml2PreferCodeImportInspection());
        getFixture().configureByText("CodeImportFixView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.BorderPane;
                @ComponentView(\"""
                    <?import javafx.scene.control.Button?>
                    <BorderPane>
                        <Button text="hello"/>
                    </BorderPane>
                    \""")
                public class CodeImportFixView {
                    public CodeImportFixView() {}
                }
                """);


        List<IntentionAction> fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && FIX_NAME.equals(fix.getFamilyName());
                })
                .toList();
        assertFalse(fixes.isEmpty(), "Expected '" + FIX_NAME + "' quick-fix");

        getFixture().launchAction(fixes.getFirst());

        String resultText = ReadAction.compute(() -> getFixture().getFile().getText());

        assertTrue(resultText.contains("import javafx.scene.control.Button"),
                "After fix, host file must contain 'import javafx.scene.control.Button'\n" + resultText);
        assertFalse(resultText.contains("<?import javafx.scene.control.Button?>"),
                "After fix, markup must not contain '<?import javafx.scene.control.Button?>'\n" + resultText);
    }

    // -----------------------------------------------------------------------
    // Batch quick-fix: processes multiple PIs in one pass

    /**
     * When multiple {@code <?import?>} PIs in the same markup block are reported, the
     * batch quick-fix must process all of them in one pass.
     */
    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void batchFixProcessesMultiplePis() {
        getFixture().enableInspections(new Fxml2PreferCodeImportInspection());
        getFixture().configureByText("BatchFixView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.BorderPane;
                @ComponentView(\"""
                    <?import javafx.scene.control.Button?>
                    <?import javafx.scene.control.Label?>
                    <BorderPane>
                        <Button text="click"/>
                        <Label text="info"/>
                    </BorderPane>
                    \""")
                public class BatchFixView {
                    public BatchFixView() {}
                }
                """);


        List<IntentionAction> fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && FIX_NAME.equals(fix.getFamilyName());
                })
                .toList();
        assertFalse(fixes.isEmpty(), "Expected '" + FIX_NAME + "' quick-fixes");

        // Apply all fixes.
        fixes.forEach(getFixture()::launchAction);

        String resultText = ReadAction.compute(() -> getFixture().getFile().getText());

        assertTrue(resultText.contains("import javafx.scene.control.Button"),
                "After batch fix, 'import javafx.scene.control.Button' must be in host file\n" + resultText);
        assertTrue(resultText.contains("import javafx.scene.control.Label"),
                "After batch fix, 'import javafx.scene.control.Label' must be in host file\n" + resultText);
        assertFalse(resultText.contains("<?import javafx.scene.control.Button?>"),
                "After batch fix, Button <?import?> PI must be removed\n" + resultText);
        assertFalse(resultText.contains("<?import javafx.scene.control.Label?>"),
                "After batch fix, Label <?import?> PI must be removed\n" + resultText);
    }
}
