// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.JavaRecursiveElementWalkingVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Project-level {@link CodeStyleManager.Listener} that captures a snapshot of every
 * {@code @ComponentView} text-block literal's content and the active editor's selection
 * <em>before</em> the Java formatter runs.
 *
 * <h2>Why?</h2>
 * IntelliJ's Java formatter applies a "continuation indent" (typically 8 spaces) to all
 * text-block content lines that fall within the requested format range.  This modifies the
 * document before {@link Fxml2EmbeddedMarkupFormattingProcessor} is invoked, and it also
 * shifts the {@code SelectionModel}'s {@code RangeMarker}s.  As a result the post-formatter
 * selection covers more lines than the user originally selected.
 *
 * <p>By capturing the original content and selection here (before any document changes),
 * {@link Fxml2EmbeddedMarkupFormattingProcessor} can:
 * <ol>
 *   <li>Correctly identify which lines the user originally selected (by index, not offset).</li>
 *   <li>Restore lines outside the selection to their pre-formatter content instead of leaving
 *       them with the Java formatter's continuation indent.</li>
 * </ol>
 *
 * <h2>Registration</h2>
 * Registered via {@code <projectListeners>} in {@code plugin.xml}.
 */
public final class Fxml2ReformatSnapshotTracker implements CodeStyleManager.Listener {

    /**
     * Snapshot of a single {@code @ComponentView} text-block literal as it existed
     * <em>before</em> the Java formatter ran.
     *
     * @param originalContent the raw content string (between opening {@code """\n}
     *                        and the closing {@code """})
     */
    public record LiteralSnapshot(@NotNull String originalContent) {}

    /**
     * Full snapshot for one "reformat" invocation: all captured literals plus the
     * original editor selection (in pre-formatter document coordinates).
     *
     * @param literalsByContentStart map from {@code contentStart} -> {@link LiteralSnapshot}
     * @param originalSelection      editor selection before the Java formatter ran;
     *                               {@code null} when there is no selection (full-file reformat)
     */
    public record FormatSnapshot(
            @NotNull Map<Integer, LiteralSnapshot> literalsByContentStart,
            @Nullable TextRange originalSelection) {}

    // -----------------------------------------------------------------------
    // Thread-local snapshot (captured by beforeReformatText, read by processText)

    private static final ThreadLocal<FormatSnapshot> SNAPSHOT = new ThreadLocal<>();

    // -----------------------------------------------------------------------
    // CodeStyleManager.Listener implementation

    @Override
    public void beforeReformatText(@NotNull PsiFile file) {
        if (isApplicable(file)) {
            // Java/Kotlin host file: capture snapshot directly.
            captureSnapshot(file);
        } else {
            // Check if it's an injected FXML2 fragment.  When the user presses
            // Ctrl+Alt+L while the caret is *inside* an injected FXML fragment,
            // IntelliJ uses setInjectedContext(true) and the action receives the
            // injected XmlFile rather than the Java/Kotlin host.  In that case we
            // still need to capture the host file's content and the editor's
            // selection before the formatter runs.
            captureFromInjectedFxml2(file);
        }
    }

    @Override
    public void afterReformatText(@NotNull PsiFile file) {
        // Clear the snapshot when the reformat of the *host* Java/Kotlin file or the
        // injected FXML2 fragment completes.
        // We deliberately do NOT clear it for the temporary XML files created inside
        // formatXmlContent, so the snapshot is still available for the outer processText.
        if (isApplicable(file)) {
            SNAPSHOT.remove();
        } else {
            tryRemoveForInjected(file);
        }
    }

    // -----------------------------------------------------------------------
    // Static accessor for the PostFormatProcessor

    /** Returns the snapshot captured for the current thread, or {@code null}. */
    public static @Nullable FormatSnapshot getSnapshot() {
        return SNAPSHOT.get();
    }

    // -----------------------------------------------------------------------
    // Helpers

    private static boolean isApplicable(@NotNull PsiFile file) {
        if (file instanceof PsiJavaFile) return true;
        try {
            return file instanceof org.jetbrains.kotlin.psi.KtFile;
        } catch (NoClassDefFoundError ignored) {
            return false;
        }
    }

    /**
     * Captures the snapshot for a Java/Kotlin host file directly.
     */
    private static void captureSnapshot(@NotNull PsiFile hostFile) {
        Project project = hostFile.getProject();
        com.intellij.openapi.editor.Document doc =
                PsiDocumentManager.getInstance(project).getDocument(hostFile);
        if (doc == null) return;

        // Capture the editor selection BEFORE the document changes.
        TextRange selection = captureEditorSelection(doc, project);

        // Capture the content of each @ComponentView literal.
        Map<Integer, LiteralSnapshot> map = new HashMap<>();
        captureJavaLiterals(hostFile, doc, map);
        captureKotlinLiterals(hostFile, doc, map);

        if (!map.isEmpty()) {
            SNAPSHOT.set(new FormatSnapshot(map, selection));
        } else {
            SNAPSHOT.remove();
        }
    }

    /**
     * When formatting is invoked while the caret is inside an injected FXML2 fragment,
     * {@link com.intellij.codeInsight.actions.ReformatCodeAction} (which has
     * {@code setInjectedContext(true)}) receives the <em>injected</em> XML file rather
     * than the Java/Kotlin host.  {@code beforeReformatText} is therefore fired with the
     * XML file.  We detect this case and capture the snapshot from the
     * host file so that {@link Fxml2EmbeddedMarkupFormattingProcessor} can apply partial
     * changes correctly.
     */
    private static void captureFromInjectedFxml2(@NotNull PsiFile file) {
        try {
            if (!(file instanceof XmlFile xmlFile)) return;
            if (!Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile)) return;

            PsiLanguageInjectionHost host = Fxml2EmbeddedUtil.getInjectionHost(xmlFile);
            if (host == null) return;

            PsiFile hostFile = host.getContainingFile();
            if (!isApplicable(hostFile)) return;

            captureSnapshot(hostFile);
        } catch (Exception ignored) {}
    }

    /**
     * Clears the snapshot when an injected FXML2 fragment's formatting cycle completes.
     * Must be called from {@link #afterReformatText} only for non-Java/Kotlin files.
     */
    private static void tryRemoveForInjected(@NotNull PsiFile file) {
        try {
            if (file instanceof XmlFile xmlFile && Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile)) {
                SNAPSHOT.remove();
            }
        } catch (Exception ignored) {}
    }

    private static @Nullable TextRange captureEditorSelection(
            @NotNull com.intellij.openapi.editor.Document doc,
            @NotNull Project project) {
        Editor[] editors = EditorFactory.getInstance().getEditors(doc, project);
        for (Editor editor : editors) {
            if (editor.getSelectionModel().hasSelection()) {
                return TextRange.create(
                        editor.getSelectionModel().getSelectionStart(),
                        editor.getSelectionModel().getSelectionEnd());
            }
        }
        return null;
    }

    private static void captureJavaLiterals(
            @NotNull PsiFile file,
            @NotNull com.intellij.openapi.editor.Document doc,
            @NotNull Map<Integer, LiteralSnapshot> out) {

        if (!(file instanceof PsiJavaFile)) return;

        file.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override
            public void visitLiteralExpression(@NotNull PsiLiteralExpression expr) {
                super.visitLiteralExpression(expr);
                if (!isComponentViewLiteral(expr)) return;
                captureLiteralContent(getContentRange(expr), doc, out);
            }
        });
    }

    private static void captureKotlinLiterals(
            @NotNull PsiFile file,
            @NotNull com.intellij.openapi.editor.Document doc,
            @NotNull Map<Integer, LiteralSnapshot> out) {
        try {
            if (!(file instanceof org.jetbrains.kotlin.psi.KtFile)) return;
            file.accept(new org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
                @Override
                public void visitStringTemplateExpression(
                        @NotNull org.jetbrains.kotlin.psi.KtStringTemplateExpression expr) {
                    super.visitStringTemplateExpression(expr);
                    if (!isKotlinComponentViewExpression(expr)) return;
                    TextRange cr = getKotlinContentRange(expr);
                    if (cr == null) return;
                    captureLiteralContent(cr, doc, out);
                }
            });
        } catch (NoClassDefFoundError ignored) {}
    }

    private static void captureLiteralContent(
            @Nullable TextRange contentRange,
            @NotNull com.intellij.openapi.editor.Document doc,
            @NotNull Map<Integer, LiteralSnapshot> out) {
        if (contentRange == null) return;
        String content = doc.getText().substring(
                contentRange.getStartOffset(), contentRange.getEndOffset());
        out.put(contentRange.getStartOffset(),
                new LiteralSnapshot(content));
    }

    private static boolean isComponentViewLiteral(@NotNull PsiLiteralExpression literal) {
        if (!(literal.getValue() instanceof String)) return false;
        if (!literal.isTextBlock()) return false;
        PsiAnnotation ann = PsiTreeUtil.getParentOfType(literal, PsiAnnotation.class);
        if (ann == null) return false;
        return Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN.equals(ann.getQualifiedName());
    }

    private static @Nullable TextRange getContentRange(@NotNull PsiLiteralExpression literal) {
        if (!literal.isTextBlock()) return null;
        String text = literal.getText();
        if (text == null || !text.startsWith("\"\"\"") || !text.endsWith("\"\"\"")) return null;
        int docStart = literal.getTextRange().getStartOffset();
        int contentStart = docStart + 4; // skip """ + mandatory newline
        int contentEnd   = literal.getTextRange().getEndOffset() - 3;
        if (contentStart > contentEnd) return null;
        return TextRange.create(contentStart, contentEnd);
    }

    private static boolean isKotlinComponentViewExpression(
            @NotNull org.jetbrains.kotlin.psi.KtStringTemplateExpression expr) {
        try {
            var ann = PsiTreeUtil.getParentOfType(
                    expr, org.jetbrains.kotlin.psi.KtAnnotationEntry.class);
            if (ann == null) return false;
            var shortName = ann.getShortName();
            return shortName != null && "ComponentView".equals(shortName.getIdentifier());
        } catch (NoClassDefFoundError ignored) {
            return false;
        }
    }

    private static @Nullable TextRange getKotlinContentRange(
            @NotNull org.jetbrains.kotlin.psi.KtStringTemplateExpression expr) {
        String text = expr.getText();
        if (text == null) return null;
        int openQuote = text.indexOf("\"\"\"");
        if (openQuote < 0) return null;
        int afterOpenQuotes = openQuote + 3;
        if (afterOpenQuotes >= text.length() || text.charAt(afterOpenQuotes) != '\n') return null;
        int contentStart = expr.getTextRange().getStartOffset() + afterOpenQuotes + 1;
        int closeQuote = text.lastIndexOf("\"\"\"");
        if (closeQuote <= openQuote) return null;
        int contentEnd = expr.getTextRange().getStartOffset() + closeQuote;
        if (contentStart > contentEnd) return null;
        return TextRange.create(contentStart, contentEnd);
    }
}
