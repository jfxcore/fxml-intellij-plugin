// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.ex.QuickFixWrapper;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.jfxcore.fxml.annotator.Fxml2EmbedMarkupInspection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Fxml2EmbedMarkupInspection}.
 *
 * <p>Covers:
 * <ul>
 *   <li>The inspection reports a problem when a standalone FXML2 file has a
 *       code-behind sibling.</li>
 *   <li>The inspection is silent when no code-behind sibling exists.</li>
 *   <li>The inspection is silent for embedded FXML2 fragments.</li>
 *   <li>The quick-fix (via the inspection) embeds the markup and deletes the FXML file.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2EmbedMarkupInspectionTest extends Fxml2TestBase {

    private static final String FXML_SELF_CLOSING =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <?import javafx.scene.layout.BorderPane?>
            <BorderPane xmlns="http://javafx.com/javafx"
                        xmlns:fx="http://jfxcore.org/fxml/2.0"
                        fx:subclass="test.TestView"/>
            """;

    private static final String JAVA_CODE_BEHIND =
            """
            package test;
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
    // Inspection fires when code-behind sibling exists

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void inspectionReportsWhenCodeBehindExists() {
        getFixture().enableInspections(new Fxml2EmbedMarkupInspection());
        getFixture().addFileToProject("TestView.java", JAVA_CODE_BEHIND);
        getFixture().configureByText("TestView.fxml", FXML_SELF_CLOSING);

        List<IntentionAction> fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && "Embed markup in code-behind file".equals(fix.getFamilyName());
                })
                .toList();
        assertFalse(fixes.isEmpty(),
                "Inspection should report a quick-fix when a code-behind sibling exists");
    }

    // -----------------------------------------------------------------------
    // Inspection is silent when no code-behind sibling exists

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void inspectionSilentWhenNoCodeBehind() {
        getFixture().enableInspections(new Fxml2EmbedMarkupInspection());
        // No Java/Kotlin file added
        getFixture().configureByText("TestViewAlone.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.TestViewAlone"/>
                """);

        List<IntentionAction> fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && "Embed markup in code-behind file".equals(fix.getFamilyName());
                })
                .toList();
        assertTrue(fixes.isEmpty(),
                "Inspection must be silent when no code-behind sibling is present");
    }

    // -----------------------------------------------------------------------
    // Inspection is silent when fx:subclass is absent

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void inspectionSilentWhenFxClassAbsent() {
        getFixture().enableInspections(new Fxml2EmbedMarkupInspection());
        getFixture().addFileToProject("TestView.java", JAVA_CODE_BEHIND);
        // FXML file without fx:subclass attribute
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"/>
                """);

        List<IntentionAction> fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && "Embed markup in code-behind file".equals(fix.getFamilyName());
                })
                .toList();
        assertTrue(fixes.isEmpty(),
                "Inspection must be silent when fx:subclass attribute is absent");
    }

    // -----------------------------------------------------------------------
    // Quick-fix embeds markup and deletes FXML file

    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void quickFixEmbedsSelfClosingMarkup() {
        getFixture().enableInspections(new Fxml2EmbedMarkupInspection());
        getFixture().configureByText("TestView.fxml", FXML_SELF_CLOSING);
        VirtualFile fxmlVF = getFixture().getFile().getVirtualFile();
        getFixture().addFileToProject("TestView.java", JAVA_CODE_BEHIND);

        // Collect and apply the inspection quick-fix
        List<IntentionAction> fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && "Embed markup in code-behind file".equals(fix.getFamilyName());
                })
                .toList();
        assertFalse(fixes.isEmpty(), "Embed quick-fix must be available from inspection");

        getFixture().launchAction(fixes.getFirst());

        // FXML file must be deleted
        assertFalse(fxmlVF.isValid(),
                "FXML VirtualFile must be invalid (deleted) after quick-fix");

        // Code-behind must contain @ComponentView annotation
        VirtualFile javaVF = getFixture().findFileInTempDir("TestView.java");
        assertNotNull(javaVF, "Code-behind Java file must still exist");
        String javaText = ReadAction.compute(() -> {
            PsiFile psi = getFixture().getPsiManager().findFile(javaVF);
            return psi != null ? psi.getText() : "";
        });
        assertTrue(javaText.contains("@ComponentView"),
                "Code-behind must contain @ComponentView annotation after fix");
        assertTrue(javaText.contains("<BorderPane"),
                "Embedded annotation value must contain the root element");
        assertFalse(javaText.contains("xmlns="),
                "xmlns attribute must be stripped from embedded markup");
        assertFalse(javaText.contains("fx:subclass="),
                "fx:subclass attribute must be stripped from embedded markup");
    }
}
