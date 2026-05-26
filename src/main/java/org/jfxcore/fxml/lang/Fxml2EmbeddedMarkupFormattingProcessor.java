// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.JavaRecursiveElementWalkingVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link PostFormatProcessor} that reformats embedded FXML markup inside
 * {@code @ComponentView} text-block annotations after the standard Java / Kotlin
 * formatter has run.
 *
 * <h2>Why a PostFormatProcessor?</h2>
 * The Java formatter's {@code TextBlockBlock} treats every line of a text-block as a
 * {@code TextLineBlock} with a uniform continuation indent: it has no knowledge of
 * the injected XML structure.  This processor extracts the raw XML from each
 * {@code @ComponentView} annotation value, delegates to IntelliJ's built-in XML
 * formatter for correct structure-aware formatting, and re-anchors the resulting
 * indentation to the Java context before writing it back to the host document.
 *
 * <h2>Indentation formula</h2>
 * <pre>
 *   indent(element) = annotationColumn + javaIndentSize
 *                   + xmlLevel * xmlIndentSize
 * </pre>
 * where:
 * <ul>
 *   <li>{@code annotationColumn}: the column of the {@code @} sign in the Java file.</li>
 *   <li>{@code javaIndentSize}:   from the project's Java code-style settings.</li>
 *   <li>{@code xmlIndentSize}:    determined from the XML formatter's own output,
 *       which honors the project's XML code-style settings (including EditorConfig
 *       values merged into {@link CodeStyleSettings} before this processor runs).</li>
 * </ul>
 *
 * <h2>XML formatting</h2>
 * The actual XML formatting is performed by
 * {@link Fxml2EmbedMarkupUtil#formatXmlContent}, which is shared with the
 * "Embed markup in code-behind file" action so that both always produce identical output.
 *
 * <h2>Replacement range</h2>
 * For a Java text block {@code """\n...\n"""}, the content range
 * {@code [literalStart+4, literalEnd-3]} covers everything after the opening
 * {@code """\n} and before the closing {@code """}.  The new content is:
 * <pre>
 *   formattedXmlLines + "\n" + " ".repeat(annotationColumn)
 * </pre>
 * so the closing {@code """} lands on its own line at column {@code annotationColumn}.
 */
public final class Fxml2EmbeddedMarkupFormattingProcessor implements PostFormatProcessor {

    @Override
    public @NotNull PsiElement processElement(
            @NotNull PsiElement source, @NotNull CodeStyleSettings settings) {
        return source;
    }

    @Override
    public @NotNull TextRange processText(
            @NotNull PsiFile source,
            @NotNull TextRange rangeToReformat,
            @NotNull CodeStyleSettings settings) {

        if (isApplicable(source)) {
            Project project = source.getProject();
            Document doc = PsiDocumentManager.getInstance(project).getDocument(source);
            if (doc == null) return rangeToReformat;

            // The Java formatter modifies the document without committing the PSI first.
            // We use the (potentially stale) PSI only for elements whose start offsets are
            // guaranteed to be unchanged (the @ComponentView annotation and the opening """
            // of the text block, both precede the content that the Java formatter altered),
            // and we scan the document text directly to locate the closing """ delimiter.

            // Collect all (range, newContent) pairs, then apply in reverse order.
            List<Pair<TextRange, String>> changes = new ArrayList<>();
            collectJavaChanges(source, rangeToReformat, settings, doc, changes);
            collectKotlinChanges(source, rangeToReformat, settings, doc, changes);

            // Sort by start offset descending so earlier offsets are not invalidated.
            changes.sort((a, b) -> Integer.compare(
                    b.first.getStartOffset(), a.first.getStartOffset()));

            int delta = 0;
            for (Pair<TextRange, String> change : changes) {
                TextRange range = change.first;
                String newText = change.second;
                doc.replaceString(range.getStartOffset(), range.getEndOffset(), newText);
                delta += newText.length() - range.getLength();
            }

            // Commit the document so that PsiFile.getText() returns our correctly-formatted
            // content rather than the stale "lastCommittedText" snapshot taken before the
            // Java formatter ran.
            if (!changes.isEmpty()) {
                PsiDocumentManager.getInstance(project).commitDocument(doc);
            }

            return rangeToReformat.grown(delta);
        }

        // IntelliJ's core formatting service passes the *injected* PsiFile to
        // PostFormatProcessor.processText even though the actual Java formatting
        // was already delegated to the host file via
        // Fxml2InjectedFormattingOptionsProvider.shouldDelegateToTopLevel.
        // Detect this and apply our XML re-formatting to the host document directly.
        return tryProcessInjected(source, rangeToReformat, settings);
    }

    // -----------------------------------------------------------------------
    // Injected-fragment fallback
    // -----------------------------------------------------------------------

    /**
     * Called when {@link #processText} receives an injected XML {@link PsiFile} instead
     * of the Java/Kotlin host file.
     *
     * <p>IntelliJ's core formatting service passes the original (injected) file to the
     * {@code PostFormatProcessor} even after the actual Java formatting has been delegated
     * to the host file via {@link Fxml2InjectedFormattingOptionsProvider}.
     * We detect this, find the injection host in the Java/Kotlin document, and apply the
     * XML re-formatting there.
     */
    private static @NotNull TextRange tryProcessInjected(
            @NotNull PsiFile source,
            @NotNull TextRange rangeToReformat,
            @NotNull CodeStyleSettings settings) {

        if (!(source instanceof XmlFile xmlFile)) return rangeToReformat;
        if (!Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile)) return rangeToReformat;

        PsiLanguageInjectionHost host = Fxml2EmbeddedUtil.getInjectionHost(xmlFile);
        if (host == null) return rangeToReformat;

        PsiFile hostFile = host.getContainingFile();
        if (!isApplicable(hostFile)) return rangeToReformat; // safety: must be Java or Kotlin

        Project project = hostFile.getProject();
        Document hostDoc = PsiDocumentManager.getInstance(project).getDocument(hostFile);
        if (hostDoc == null) return rangeToReformat;

        List<Pair<TextRange, String>> changes = new ArrayList<>();

        // Use the full host-document range so the entire FXML content is reformatted.
        // The rangeToReformat here is in injected-file coordinates, not host coordinates,
        // so it cannot be used directly for partial-line selection.
        TextRange fullHostRange = TextRange.create(0, hostDoc.getTextLength());

        if (host instanceof PsiLiteralExpression literal && isComponentViewLiteral(literal)) {
            // Java host: process the @ComponentView text-block literal directly.
            collectChange(literal, fullHostRange, settings, hostDoc, changes);
        } else {
            // Kotlin host: delegate to the Kotlin change collector.
            tryCollectKotlinChangeForHost(host, fullHostRange, settings, hostDoc, changes);
        }

        // Apply changes in reverse order so earlier offsets are not invalidated.
        changes.sort((a, b) -> Integer.compare(b.first.getStartOffset(), a.first.getStartOffset()));
        for (Pair<TextRange, String> change : changes) {
            hostDoc.replaceString(change.first.getStartOffset(), change.first.getEndOffset(),
                    change.second);
        }
        if (!changes.isEmpty()) {
            PsiDocumentManager.getInstance(project).commitDocument(hostDoc);
        }

        return rangeToReformat;
    }

    private static void tryCollectKotlinChangeForHost(
            @NotNull PsiLanguageInjectionHost host,
            @NotNull TextRange rangeToReformat,
            @NotNull CodeStyleSettings settings,
            @NotNull Document hostDoc,
            @NotNull List<Pair<TextRange, String>> changes) {
        try {
            if (!(host instanceof org.jetbrains.kotlin.psi.KtStringTemplateExpression ktExpr)) return;
            collectKotlinChange(ktExpr, rangeToReformat, settings, hostDoc, changes);
        } catch (NoClassDefFoundError ignored) {}
    }

    // -----------------------------------------------------------------------
    // Applicability guards
    // -----------------------------------------------------------------------

    private static boolean isApplicable(@NotNull PsiFile file) {
        if (file instanceof PsiJavaFile) return true;
        try {
            return file instanceof org.jetbrains.kotlin.psi.KtFile;
        } catch (NoClassDefFoundError ignored) {
            return false;
        }
    }

    private static boolean isComponentViewLiteral(@NotNull PsiLiteralExpression literal) {
        if (!(literal.getValue() instanceof String)) return false;
        if (!literal.isTextBlock()) return false;
        PsiAnnotation ann = PsiTreeUtil.getParentOfType(literal, PsiAnnotation.class);
        if (ann == null) return false;
        return Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN.equals(ann.getQualifiedName());
    }

    // -----------------------------------------------------------------------
    // Change collection: Java
    // -----------------------------------------------------------------------

    private static void collectJavaChanges(
            @NotNull PsiFile source,
            @NotNull TextRange rangeToReformat,
            @NotNull CodeStyleSettings settings,
            @NotNull Document doc,
            @NotNull List<Pair<TextRange, String>> changes) {

        if (!(source instanceof PsiJavaFile)) return;

        source.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override
            public void visitLiteralExpression(
                    @NotNull PsiLiteralExpression expression) {
                super.visitLiteralExpression(expression);
                // Quick pre-filter: skip literals that are completely outside the range.
                if (!rangeToReformat.intersects(expression.getTextRange())) return;
                if (!isComponentViewLiteral(expression)) return;
                collectChange(expression, rangeToReformat, settings, doc, changes);
            }
        });
    }

    // -----------------------------------------------------------------------
    // Change collection: Kotlin (optional)
    // -----------------------------------------------------------------------

    private static void collectKotlinChanges(
            @NotNull PsiFile source,
            @NotNull TextRange rangeToReformat,
            @NotNull CodeStyleSettings settings,
            @NotNull Document doc,
            @NotNull List<Pair<TextRange, String>> changes) {

        try {
            if (!(source instanceof org.jetbrains.kotlin.psi.KtFile)) return;
            source.accept(new org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
                @Override
                public void visitStringTemplateExpression(
                        @NotNull org.jetbrains.kotlin.psi.KtStringTemplateExpression expr) {
                    super.visitStringTemplateExpression(expr);
                    if (!rangeToReformat.intersects(expr.getTextRange())) return;
                    if (!isKotlinComponentViewExpression(expr)) return;
                    collectKotlinChange(expr, rangeToReformat, settings, doc, changes);
                }
            });
        } catch (NoClassDefFoundError ignored) {}
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

    // -----------------------------------------------------------------------
    // Core: collect change for a Java literal
    // -----------------------------------------------------------------------

    private static void collectChange(
            @NotNull PsiLiteralExpression literal,
            @NotNull TextRange rangeToReformat,
            @NotNull CodeStyleSettings settings,
            @NotNull Document doc,
            @NotNull List<Pair<TextRange, String>> changes) {

        TextRange contentRange = getTextBlockContentRangeFromDoc(literal, doc);
        if (contentRange == null) return;

        // Only reformat if the selection overlaps with the actual FXML content lines,
        // not merely with the surrounding """ delimiters.
        if (!rangeToReformat.intersects(contentRange)) return;

        String rawContent = doc.getText().substring(
                contentRange.getStartOffset(), contentRange.getEndOffset());

        int javaIndentSize = getJavaIndentSize(settings);
        int annColumn      = computeAnnotationColumn(literal, doc);
        int baseIndent     = annColumn + javaIndentSize;

        // Read the snapshot BEFORE calling formatXmlContent.
        // formatXmlContent calls CodeStyleManager.reformatText on a temporary XML file,
        // which fires afterReformatText -> Fxml2ReformatSnapshotTracker.SNAPSHOT.remove(),
        // clearing our snapshot before we can use it.
        Fxml2ReformatSnapshotTracker.FormatSnapshot snapshot =
                Fxml2ReformatSnapshotTracker.getSnapshot();
        String originalContent = null;
        TextRange originalSelection = null;
        if (snapshot != null) {
            Fxml2ReformatSnapshotTracker.LiteralSnapshot ls =
                    snapshot.literalsByContentStart().get(contentRange.getStartOffset());
            if (ls != null) originalContent = ls.originalContent();
            originalSelection = snapshot.originalSelection();
        }

        String newContent = Fxml2EmbedMarkupUtil.formatXmlContent(
                literal.getProject(), rawContent, baseIndent, annColumn,
                literal.getContainingFile().getVirtualFile());
        if (newContent == null) return;

        applyPartialChanges(contentRange, rawContent, originalContent, newContent,
                originalSelection, changes);
    }

    private static void collectKotlinChange(
            @NotNull org.jetbrains.kotlin.psi.KtStringTemplateExpression expr,
            @NotNull TextRange rangeToReformat,
            @NotNull CodeStyleSettings settings,
            @NotNull Document doc,
            @NotNull List<Pair<TextRange, String>> changes) {

        TextRange contentRange = getKotlinStringContentRange(expr);
        if (contentRange == null) return;

        // Only reformat if the selection overlaps with the actual FXML content lines.
        if (!rangeToReformat.intersects(contentRange)) return;

        String rawContent = doc.getText().substring(
                contentRange.getStartOffset(), contentRange.getEndOffset());

        int javaIndentSize = getJavaIndentSize(settings);
        int annColumn      = computeKotlinAnnotationColumn(expr, doc);
        int baseIndent     = annColumn + javaIndentSize;

        // Read the snapshot BEFORE calling formatXmlContent (same reason as collectChange).
        Fxml2ReformatSnapshotTracker.FormatSnapshot snapshot =
                Fxml2ReformatSnapshotTracker.getSnapshot();
        String originalContent = null;
        TextRange originalSelection = null;
        if (snapshot != null) {
            Fxml2ReformatSnapshotTracker.LiteralSnapshot ls =
                    snapshot.literalsByContentStart().get(contentRange.getStartOffset());
            if (ls != null) originalContent = ls.originalContent();
            originalSelection = snapshot.originalSelection();
        }

        String newContent = Fxml2EmbedMarkupUtil.formatXmlContent(
                expr.getProject(), rawContent, baseIndent, annColumn,
                expr.getContainingFile().getVirtualFile());
        if (newContent == null) return;

        applyPartialChanges(contentRange, rawContent, originalContent, newContent,
                originalSelection, changes);
    }

    // -----------------------------------------------------------------------
    // Partial-selection line-by-line change application
    // -----------------------------------------------------------------------

    /**
     * Compares {@code rawContent} (post-Java-formatter) and {@code newContent} (desired
     * XML-formatted output) line by line and emits the minimal set of document replacements
     * needed to produce the correct final content.
     *
     * <h3>Selective reformatting (partial selection)</h3>
     * <p>When both {@code originalContent} and {@code originalSelection} are non-{@code null}
     * (captured by {@link Fxml2ReformatSnapshotTracker} before the Java formatter ran), each
     * line is processed as follows:
     * <ol>
     *   <li><b>Within the original selection</b>: apply XML formatting: replace
     *       {@code rawLines[i]} with {@code newLines[i]} when they differ.</li>
     *   <li><b>Outside the original selection</b>: undo any "continuation indent" the Java
     *       formatter may have added: replace {@code rawLines[i]} with
     *       {@code originalLines[i]} when they differ.</li>
     * </ol>
     * This ensures that lines the user never selected are left exactly as they were before
     * the formatter ran, even if the Java formatter's text-block handling touched them.
     *
     * <h3>No snapshot (full-file reformat or programmatic call)</h3>
     * <p>When {@code originalContent} or {@code originalSelection} is {@code null} (no
     * snapshot available, or the user had no selection), all changed lines are reformatted:
     * replace every {@code rawLines[i]} that differs from {@code newLines[i]}.
     *
     * <h3>Line-count mismatch</h3>
     * <p>If the line counts of {@code rawContent} and {@code newContent} differ (e.g. the
     * XML formatter wrapped a long attribute), a single whole-block replacement is emitted
     * instead, because partial replacement with differing line counts would corrupt the
     * document structure.
     *
     * @param contentRange     range of the full FXML content in the host document
     *                         (post-Java-formatter coordinates)
     * @param rawContent       current content read from the host document
     *                         (after the Java formatter has run)
     * @param originalContent  content before the Java formatter ran; {@code null} when no
     *                         snapshot is available
     * @param newContent       fully-formatted content produced by the XML formatter
     * @param originalSelection editor selection captured before the Java formatter ran;
     *                          {@code null} means "reformat all changed lines"
     * @param changes           output list to which replacements are added
     */
    private static void applyPartialChanges(
            @NotNull TextRange contentRange,
            @NotNull String rawContent,
            @Nullable String originalContent,
            @NotNull String newContent,
            @Nullable TextRange originalSelection,
            @NotNull List<Pair<TextRange, String>> changes) {

        String[] rawLines = rawContent.split("\n", -1);
        String[] newLines = newContent.split("\n", -1);

        if (rawLines.length != newLines.length) {
            // Line count changed (e.g. attribute wrapping by the XML formatter).
            // Cannot do a diff; fall back to replacing the whole content block.
            changes.add(Pair.create(contentRange, newContent));
            return;
        }

        // Split the original (pre-Java-formatter) content so we can detect which lines
        // the Java formatter changed and restore them if they are outside the selection.
        String[] originalLines = (originalContent != null)
                ? originalContent.split("\n", -1)
                : null;

        // If the line counts don't match across all three arrays, fall back to whole-block.
        if (originalLines != null && originalLines.length != rawLines.length) {
            changes.add(Pair.create(contentRange, newContent));
            return;
        }

        // Compute per-line selection membership using the ORIGINAL content's line offsets.
        // The Java formatter may have changed line lengths (adding continuation indent),
        // but it never adds or removes newlines, so line indices remain stable.
        boolean[] inSelection = null;
        if (originalLines != null && originalSelection != null) {
            inSelection = computeOriginalLineSelection(
                    contentRange.getStartOffset(), originalLines, originalSelection);
        }

        int lineOffset = contentRange.getStartOffset();
        for (int i = 0; i < rawLines.length; i++) {
            int lineEnd = lineOffset + rawLines[i].length();

            if (inSelection == null || inSelection[i]) {
                // Line is within the user's original selection (or no filter) ->
                // apply XML formatting.
                if (!rawLines[i].equals(newLines[i])) {
                    changes.add(Pair.create(TextRange.create(lineOffset, lineEnd), newLines[i]));
                }
            } else {
                // Line is outside the user's selection -> undo any Java-formatter change
                // (e.g. continuation indent added to text-block content lines) so the
                // line is restored to exactly what it was before the formatter ran.
                String original = originalLines[i];
                if (!rawLines[i].equals(original)) {
                    changes.add(Pair.create(TextRange.create(lineOffset, lineEnd), original));
                }
            }

            lineOffset = lineEnd + 1; // +1 for the '\n' separator
        }
    }

    /**
     * Computes a {@code boolean[]} indicating which lines of the text-block content
     * were covered by the user's original selection.
     *
     * <p>The computation is performed in <em>original</em> document coordinates (before
     * the Java formatter ran) using the original content's line lengths, which are stable
     * across the Java formatter's continuation-indent changes (newlines are not affected).
     *
     * @param originalContentStart start offset of the content in the original document;
     *                             equals {@code contentRange.getStartOffset()} in the
     *                             current document when the Java formatter has not modified
     *                             anything before the text block
     * @param originalLines        lines split from the original (pre-formatter) content
     * @param originalSelection    the editor selection captured before the Java formatter ran
     * @return {@code true} at index {@code i} iff original line {@code i} overlapped
     *         with the original selection; never {@code null}
     */
    private static boolean @NotNull [] computeOriginalLineSelection(
            int originalContentStart,
            String @NotNull [] originalLines,
            @NotNull TextRange originalSelection) {

        boolean[] result = new boolean[originalLines.length];
        int lineOffset = originalContentStart;
        for (int i = 0; i < originalLines.length; i++) {
            int lineEnd = lineOffset + originalLines[i].length();
            // Use intersectsStrict (strict < comparison) so that a line whose range
            // merely *touches* the selection boundary at a single point is NOT considered
            // in-selection.  For example, if the selection ends exactly at offset X and
            // the next line starts at offset X, intersects() (which uses <=) would
            // incorrectly include that line; intersectsStrict() (which uses <) does not.
            result[i] = originalSelection.intersectsStrict(TextRange.create(lineOffset, lineEnd + 1));
            lineOffset = lineEnd + 1;
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Text-block content range helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the range in the host document covering the text-block content -
     * everything after the opening {@code """\n} and before the closing {@code """}.
     *
     * <p>This method does <em>not</em> rely on the PSI element's end offset (which may be
     * stale after the Java formatter modified the document without committing the PSI).
     * Instead it uses the literal's <em>start</em> offset (guaranteed correct because the
     * formatter does not move the opening {@code """}) and scans the document text forward
     * to find the closing {@code """}.
     */
    private static @Nullable TextRange getTextBlockContentRangeFromDoc(
            @NotNull PsiLiteralExpression literal, @NotNull Document doc) {

        if (!literal.isTextBlock()) return null;

        int literalStart = literal.getTextRange().getStartOffset();
        int contentStart = literalStart + 4; // skip """ + mandatory newline
        if (contentStart >= doc.getTextLength()) return null;

        String docText = doc.getText();
        int contentEnd = docText.indexOf("\"\"\"", contentStart);
        if (contentEnd < 0 || contentEnd <= contentStart) return null;

        return TextRange.create(contentStart, contentEnd);
    }

    /**
     * Returns the content range for a Kotlin multiline string template.
     */
    private static @Nullable TextRange getKotlinStringContentRange(
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

    // -----------------------------------------------------------------------
    // Indent / column helpers
    // -----------------------------------------------------------------------

    private static int getJavaIndentSize(@NotNull CodeStyleSettings settings) {
        CommonCodeStyleSettings javaSettings =
                settings.getCommonSettings(JavaLanguage.INSTANCE);
        CommonCodeStyleSettings.IndentOptions opts = javaSettings.getIndentOptions();
        return opts != null ? opts.INDENT_SIZE : 4;
    }

    /**
     * Returns the zero-based column of the {@code @ComponentView} annotation's
     * {@code @} sign in the document.
     */
    private static int computeAnnotationColumn(
            @NotNull PsiLiteralExpression literal, @NotNull Document doc) {

        PsiAnnotation ann = PsiTreeUtil.getParentOfType(literal, PsiAnnotation.class);
        if (ann == null) return 0;
        int offset = ann.getTextOffset();
        int line = doc.getLineNumber(offset);
        return offset - doc.getLineStartOffset(line);
    }

    private static int computeKotlinAnnotationColumn(
            @NotNull org.jetbrains.kotlin.psi.KtStringTemplateExpression expr,
            @NotNull Document doc) {

        try {
            var ann = PsiTreeUtil.getParentOfType(
                    expr, org.jetbrains.kotlin.psi.KtAnnotationEntry.class);
            if (ann == null) return 0;
            int offset = ann.getTextOffset();
            int line = doc.getLineNumber(offset);
            return offset - doc.getLineStartOffset(line);
        } catch (NoClassDefFoundError ignored) {
            return 0;
        }
    }
}
