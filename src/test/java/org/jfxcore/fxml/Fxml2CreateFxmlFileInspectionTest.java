// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.ex.QuickFixWrapper;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.jfxcore.fxml.annotator.Fxml2CreateFxmlFileInspection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Fxml2CreateFxmlFileInspection}.
 *
 * <p>Covers:
 * <ul>
 *   <li>The inspection reports a problem when a Java class has {@code @ComponentView}
 *       embedded markup and no sibling {@code .fxml} file exists.</li>
 *   <li>The inspection is silent when a sibling {@code .fxml} file already exists.</li>
 *   <li>The inspection is silent for non-{@code @ComponentView} annotations.</li>
 *   <li>The quick-fix (via the inspection) creates the FXML file and removes the
 *       annotation.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2CreateFxmlFileInspectionTest extends Fxml2TestBase {

    private static final String JAVA_WITH_ANNOTATION = """
            package test;
            import org.jfxcore.markup.ComponentView;
            import javafx.scene.layout.BorderPane;
            @ComponentView(""\"
                <BorderPane/>
                ""\")
            public class TestView extends TestViewBase {
                public TestView() { initializeComponent(); }
            }
            """;

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
    // Inspection fires when no FXML sibling exists

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void inspectionReportsWhenNoFxmlSibling() {
        getFixture().enableInspections(new Fxml2CreateFxmlFileInspection());
        getFixture().configureByText("TestView.java", JAVA_WITH_ANNOTATION);

        List<IntentionAction> fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && "Create FXML file".equals(fix.getFamilyName());
                })
                .toList();
        assertFalse(fixes.isEmpty(),
                "Inspection should offer 'Create FXML file' fix when no .fxml sibling exists");
    }

    // -----------------------------------------------------------------------
    // Inspection is silent when FXML sibling already exists

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void inspectionSilentWhenFxmlSiblingExists() {
        getFixture().enableInspections(new Fxml2CreateFxmlFileInspection());
        getFixture().addFileToProject("TestView.fxml", "<?xml version=\"1.0\"?>");
        getFixture().configureByText("TestView.java", JAVA_WITH_ANNOTATION);

        List<IntentionAction> fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && "Create FXML file".equals(fix.getFamilyName());
                })
                .toList();
        assertTrue(fixes.isEmpty(),
                "Inspection must be silent when a sibling .fxml file already exists");
    }

    // -----------------------------------------------------------------------
    // Inspection is silent for non-ComponentView annotations

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void inspectionSilentForUnrelatedAnnotation() {
        getFixture().enableInspections(new Fxml2CreateFxmlFileInspection());
        getFixture().configureByText("TestView.java", """
                package test;
                @SuppressWarnings("unused")
                public class TestView {}
                """);

        List<IntentionAction> fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && "Create FXML file".equals(fix.getFamilyName());
                })
                .toList();
        assertTrue(fixes.isEmpty(),
                "Inspection must not fire on non-@ComponentView annotations");
    }

    // -----------------------------------------------------------------------
    // Quick-fix creates the FXML file and removes the annotation

    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void quickFixCreatesFxmlFile() {
        getFixture().enableInspections(new Fxml2CreateFxmlFileInspection());
        getFixture().configureByText("TestView.java", JAVA_WITH_ANNOTATION);

        List<IntentionAction> fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && "Create FXML file".equals(fix.getFamilyName());
                })
                .toList();
        assertFalse(fixes.isEmpty(), "'Create FXML file' quick-fix must be available");

        getFixture().launchAction(fixes.getFirst());

        // FXML file must be created
        VirtualFile fxmlVF = getFixture().findFileInTempDir("TestView.fxml");
        assertNotNull(fxmlVF, "TestView.fxml must be created by the quick-fix");

        String fxmlText = ReadAction.compute(() -> {
            PsiFile psi = getFixture().getPsiManager().findFile(fxmlVF);
            return psi != null ? psi.getText() : "";
        });
        assertFalse(fxmlText.isEmpty(), "FXML file must have content");

        assertTrue(fxmlText.contains("xmlns=\"http://javafx.com/javafx\""),
                "FXML must have xmlns attribute");
        assertTrue(fxmlText.contains("xmlns:fx=\"http://jfxcore.org/fxml/2.0\""),
                "FXML must have xmlns:fx attribute");
        assertTrue(fxmlText.contains("fx:subclass=\"test.TestView\""),
                "FXML must have correct fx:subclass attribute");
        assertTrue(fxmlText.contains("<BorderPane"),
                "FXML must contain the root element");
        assertTrue(fxmlText.contains("<?import javafx.scene.layout.BorderPane?>"),
                "FXML must have <?import?> PI for BorderPane");

        // Annotation must be removed from the Java file
        VirtualFile javaVF = getFixture().findFileInTempDir("TestView.java");
        assertNotNull(javaVF, "Java file must still exist");
        String javaText = ReadAction.compute(() -> {
            PsiFile psi = getFixture().getPsiManager().findFile(javaVF);
            return psi != null ? psi.getText() : "";
        });
        assertFalse(javaText.contains("@ComponentView"),
                "@ComponentView annotation must be removed from the Java file after fix");
    }

    // -----------------------------------------------------------------------
    // Quick-fix "Fix all in file" converts all @ComponentView in one file

    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void quickFixAllInFileAppliesAllFixes() {
        getFixture().enableInspections(new Fxml2CreateFxmlFileInspection());
        // A Java file with @ComponentView annotation (only one class for simplicity)
        getFixture().configureByText("TestView.java", JAVA_WITH_ANNOTATION);

        List<IntentionAction> fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && "Create FXML file".equals(fix.getFamilyName());
                })
                .toList();
        assertFalse(fixes.isEmpty(), "Expected 'Create FXML file' quick-fix from inspection");

        // Apply all fixes collected
        fixes.forEach(getFixture()::launchAction);

        // Verify the FXML file was created
        VirtualFile fxmlVF = getFixture().findFileInTempDir("TestView.fxml");
        assertNotNull(fxmlVF, "TestView.fxml must be created after applying all fixes");
    }
}
