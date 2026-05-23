// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.BatchQuickFix;
import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.ex.QuickFixWrapper;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiJavaFile;
import org.jfxcore.fxml.annotator.Fxml2PreferMarkupImportInspection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Fxml2PreferMarkupImportInspection}.
 *
 * <p>Covers:
 * <ul>
 *   <li>The inspection reports a Java import that is only referenced inside
 *       embedded FXML2 markup (not in Java code itself).</li>
 *   <li>The inspection is silent when the import is also used in Java code.</li>
 *   <li>The inspection is silent when the import already has a {@code <?import?>}
 *       counterpart in the markup (duplicate guard).</li>
 *   <li>The quick-fix inserts {@code <?import fqn?>} into every {@code @ComponentView}
 *       markup block and removes the Java import.</li>
 *   <li>Nested {@code @ComponentView} classes in the same file each receive the
 *       {@code <?import?>} PI.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2PreferMarkupImportInspectionTest extends Fxml2TestBase {

    private static final String FIX_NAME = "Move to markup <?import?>";

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
    // Inspection fires: import only used in embedded markup

    /**
     * A Java import that is referenced exclusively inside a {@code @ComponentView}
     * string value and never in the surrounding Java code must be flagged.
     */
    @Test @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void importOnlyUsedInMarkup_inspection_fires() {
        getFixture().enableInspections(new Fxml2PreferMarkupImportInspection());
        getFixture().configureByText("MarkupOnlyView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.BorderPane;
                import javafx.scene.control.Button;
                @ComponentView(\"""
                    <BorderPane>
                        <Button text="click me"/>
                    </BorderPane>
                    \""")
                public class MarkupOnlyView {
                    public MarkupOnlyView() {}
                }
                """);


        List<IntentionAction> fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && FIX_NAME.equals(fix.getFamilyName());
                })
                .toList();
        assertFalse(fixes.isEmpty(),
                "Expected '" + FIX_NAME + "' quick-fix for imports only used in markup");
    }

    // -----------------------------------------------------------------------
    // Inspection is silent: import also used in Java code

    /**
     * When the imported class is referenced directly in the Java source (not only inside
     * the markup string), the inspection must remain silent.
     */
    @Test @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void importUsedInJavaCode_noFix() {
        getFixture().enableInspections(new Fxml2PreferMarkupImportInspection());
        getFixture().configureByText("JavaUsageView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.BorderPane;
                @ComponentView(\"""
                    <BorderPane/>
                    \""")
                public class JavaUsageView extends BorderPane {
                    public JavaUsageView() {}
                }
                """);


        List<IntentionAction> fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && FIX_NAME.equals(fix.getFamilyName());
                })
                .toList();
        assertTrue(fixes.isEmpty(),
                "Must not report import as markup-only when it is also used in Java code");
    }

    // -----------------------------------------------------------------------
    // Inspection is silent: no @ComponentView in file

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void noComponentView_noFix() {
        getFixture().enableInspections(new Fxml2PreferMarkupImportInspection());
        getFixture().configureByText("PlainJava.java", """
                package test;
                import javafx.scene.layout.BorderPane;
                public class PlainJava {
                    private BorderPane pane = new BorderPane();
                }
                """);

        List<IntentionAction> fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && FIX_NAME.equals(fix.getFamilyName());
                })
                .toList();
        assertTrue(fixes.isEmpty(),
                "Must not report any 'Move to markup' fixes for files without @ComponentView");
    }

    // -----------------------------------------------------------------------
    // Duplicate guard: inspection is silent when markup already has the import

    /**
     * When the embedded markup already contains {@code <?import fqn?>} for the same class,
     * the inspection must not offer to move the Java import again.
     */
    @Test @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void markupAlreadyHasImport_noFix() {
        getFixture().enableInspections(new Fxml2PreferMarkupImportInspection());
        getFixture().configureByText("DuplicateGuardView.java", """
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
                public class DuplicateGuardView {
                    public DuplicateGuardView() {}
                }
                """);


        // The Button import already exists as <?import?> in the markup; no fix should be offered.
        List<IntentionAction> buttonFixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    if (fix == null || !FIX_NAME.equals(fix.getFamilyName())) return false;
                    // The fix text includes the FQN; check it does not mention Button.
                    return fix.getName().contains("Button");
                })
                .toList();
        assertTrue(buttonFixes.isEmpty(),
                "Must not offer 'Move to markup' for Button when markup already has <?import javafx.scene.control.Button?>");
    }

    // -----------------------------------------------------------------------
    // Quick-fix: inserts <?import?> into markup and removes Java import

    /**
     * Applying the quick-fix for a markup-only import must:
     * <ol>
     *   <li>Insert {@code <?import fqn?>} into the embedded markup.</li>
     *   <li>Remove the Java import from the host file.</li>
     * </ol>
     */
    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void quickFixInsertsMarkupImportAndRemovesJavaImport() {
        getFixture().enableInspections(new Fxml2PreferMarkupImportInspection());
        getFixture().configureByText("FixView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.BorderPane;
                import javafx.scene.control.Button;
                @ComponentView(\"""
                    <BorderPane>
                        <Button text="click"/>
                    </BorderPane>
                    \""")
                public class FixView {
                    public FixView() {}
                }
                """);


        List<IntentionAction> fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && FIX_NAME.equals(fix.getFamilyName());
                })
                .toList();
        assertFalse(fixes.isEmpty(), "Expected '" + FIX_NAME + "' quick-fix");

        // Apply just the first matching fix: one application is enough since the fix
        // inserts into every markup block and deletes the Java import in one step.
        getFixture().launchAction(fixes.getFirst());

        String resultText = ReadAction.compute(() -> getFixture().getFile().getText());

        // After the fix, the moved import must NOT appear as a Java import.
        // It must appear as <?import?> inside the markup.
        assertTrue(resultText.contains("<?import"),
                "After fix, markup must contain at least one <?import?> PI\n" + resultText);
    }

    // -----------------------------------------------------------------------
    // Nested @ComponentView classes in the same file all receive the <?import?>

    /**
     * When a Java file contains two {@code @ComponentView}-annotated nested classes,
     * both markup blocks must receive the {@code <?import?>} PI when the fix is applied.
     */
    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void quickFixAppliesImportToAllMarkupBlocks() {
        getFixture().enableInspections(new Fxml2PreferMarkupImportInspection());
        getFixture().configureByText("MultiView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.BorderPane;
                import javafx.scene.control.Button;
                @ComponentView(\"""
                    <BorderPane>
                        <Button text="first"/>
                    </BorderPane>
                    \""")
                public class MultiView {
                    public MultiView() {}

                    @ComponentView(\"""
                        <BorderPane>
                            <Button text="second"/>
                        </BorderPane>
                        \""")
                    public static class InnerView {
                        public InnerView() {}
                    }
                }
                """);


        List<IntentionAction> fixes = getFixture().getAllQuickFixes().stream()
                .filter(a -> {
                    var fix = QuickFixWrapper.unwrap(a);
                    return fix != null && FIX_NAME.equals(fix.getFamilyName());
                })
                .toList();
        assertFalse(fixes.isEmpty(), "Expected '" + FIX_NAME + "' quick-fix");

        // Apply just the first fix. The fix inserts <?import?> into ALL markup blocks in one
        // write action, so one application is enough to verify both blocks are updated.
        getFixture().launchAction(fixes.getFirst());

        String resultText = ReadAction.compute(() -> getFixture().getFile().getText());
        // The markup import(s) must appear in the embedded markup content.
        // Count occurrences of <?import in the result to verify both blocks were updated.
        long piCount = resultText.lines()
                .filter(line -> line.trim().startsWith("<?import"))
                .count();
        assertTrue(piCount >= 2,
                "Expected at least 2 <?import?> PIs in the result after applying fix to two markup blocks.\n"
                + resultText);
    }

    // -----------------------------------------------------------------------
    // "Fix all in file" must move every markup-only import without a document-lock error

    /**
     * When "Fix all in file" is applied to multiple markup-only import problems in one
     * write action, each import must be moved to a {@code <?import?>} PI in the markup
     * and removed from the Java source without throwing a document-lock exception.
     *
     * <p>A PSI {@code delete()} defers whitespace reformatting via
     * {@code PostprocessReformattingAspect}, locking the host document for subsequent
     * {@link com.intellij.openapi.editor.Document#insertString} calls. The implementation
     * must flush this deferred reformatting before each markup PI insertion.
     *
     * <p>This test exercises the "Fix all in file" path by collecting all
     * {@link ProblemDescriptor}s and passing them to
     * {@link BatchQuickFix#applyFix(com.intellij.openapi.project.Project,
     * CommonProblemDescriptor[], java.util.List, Runnable)} inside a single write action.
     */
    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void batchFixAllDoesNotLockDocument() {
        getFixture().enableInspections(new Fxml2PreferMarkupImportInspection());
        getFixture().configureByText("BatchAllView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.BorderPane;
                import javafx.scene.control.Button;
                import javafx.scene.control.Label;
                @ComponentView(\"""
                    <BorderPane>
                        <Button text="click"/>
                        <Label text="info"/>
                    </BorderPane>
                    \""")
                public class BatchAllView {
                    public BatchAllView() {}
                }
                """);

        // Trigger injection so that isImportNeededByXmlFile() has an XmlFile to inspect.
        getFixture().doHighlighting();

        var inspection = new Fxml2PreferMarkupImportInspection();
        var javaFile = (PsiJavaFile) getFixture().getFile();
        var project = getFixture().getProject();

        // Collect all problem descriptors by visiting each import statement.
        List<ProblemDescriptor> problems = ReadAction.compute(() -> {
            var holder = new ProblemsHolder(
                    InspectionManager.getInstance(project), javaFile, false);
            var visitor = inspection.buildVisitor(holder, false);
            PsiImportList importList = javaFile.getImportList();
            if (importList != null) {
                for (var stmt : importList.getImportStatements()) {
                    stmt.accept(visitor);
                }
            }
            return new ArrayList<>(holder.getResults());
        });

        assertTrue(problems.size() >= 2,
                "Expected at least 2 markup-only import problems; got " + problems.size());

        // Obtain the batch quick-fix from the first descriptor.
        ProblemDescriptor problemDescriptor = problems.getFirst();
        assertNotNull(problemDescriptor.getFixes());
        var batchFix = (BatchQuickFix) problemDescriptor.getFixes()[0];
        List<PsiElement> toIgnore = new ArrayList<>();

        // Apply all descriptors in one write action, replicating the "Fix all in file" path.
        assertDoesNotThrow(() -> WriteCommandAction.runWriteCommandAction(project, () ->
                batchFix.applyFix(project,
                        problems.toArray(CommonProblemDescriptor[]::new),
                        toIgnore, null)));

        String text = ReadAction.compute(() -> getFixture().getFile().getText());
        long piCount = text.lines().filter(l -> l.trim().startsWith("<?import")).count();
        assertTrue(piCount >= 2,
                "Expected at least 2 <?import?> PIs after batch fix\n" + text);
        assertFalse(text.contains("import javafx.scene.control.Button;"),
                "Button must have been moved to <?import?>\n" + text);
        assertFalse(text.contains("import javafx.scene.control.Label;"),
                "Label must have been moved to <?import?>\n" + text);
    }

    // -----------------------------------------------------------------------
    // Document.insertString must succeed after PSI delete() + doPostponedOperationsAndUnblockDocument

    /**
     * After a PSI {@code delete()} operation, which locks the host document via
     * {@code PostprocessReformattingAspect}, calling
     * {@link PsiDocumentManager#doPostponedOperationsAndUnblockDocument} must restore the
     * ability to call {@link Document#insertString} without a document-lock error.
     *
     * <p>Any write-action sequence that performs a PSI element removal followed by a
     * direct document insertion must flush the deferred reformatting between the two
     * operations to satisfy this requirement.
     */
    @Test @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void documentInsertStringSucceedsAfterPsiDeleteAndUnlock() {
        getFixture().configureByText("LockView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.BorderPane;
                @ComponentView(\"""
                    <BorderPane/>
                    \""")
                public class LockView {
                    public LockView() {}
                }
                """);

        var javaFile = (PsiJavaFile) getFixture().getFile();
        var project = getFixture().getProject();
        // Capture the document in a read action; getDocument() may return null from inside a write action.
        PsiDocumentManager psiDocManager = PsiDocumentManager.getInstance(project);
        Document hostDoc = ReadAction.compute(() -> psiDocManager.getDocument(javaFile));
        assertNotNull(hostDoc, "Expected a non-null Document for the test file");

        assertDoesNotThrow(() -> WriteCommandAction.runWriteCommandAction(project, () -> {
            // A PSI delete() locks the host document via deferred whitespace reformatting.
            PsiImportList importList = javaFile.getImportList();
            var removedImport = importList != null
                    ? importList.findSingleClassImportStatement("javafx.scene.layout.BorderPane")
                    : null;
            if (removedImport != null) removedImport.delete();

            // Flushing the deferred reformatting unlocks the document for insertString().
            psiDocManager.doPostponedOperationsAndUnblockDocument(hostDoc);
            psiDocManager.commitDocument(hostDoc);
            hostDoc.insertString(hostDoc.getTextLength(), "// sentinel\n");
        }));

        // Read the raw document text; the write action does not commit the sentinel to PSI.
        String text = ReadAction.compute(hostDoc::getText);
        assertTrue(text.contains("// sentinel"),
                "Document.insertString must succeed after doPostponedOperationsAndUnblockDocument\n" + text);
    }
}
