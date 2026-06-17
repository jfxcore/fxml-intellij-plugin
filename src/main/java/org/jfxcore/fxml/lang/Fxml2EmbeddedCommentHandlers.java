// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.codeInsight.generation.CommentByBlockCommentHandler;
import com.intellij.codeInsight.generation.actions.CommentByLineCommentAction;
import com.intellij.injected.editor.EditorWindow;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Hosts two action overrides that ensure correct comment syntax when the caret or
 * selection is inside embedded FXML markup in a {@code @ComponentView} annotation.
 *
 * <h2>Problem</h2>
 * <p>The platform's {@code CommentByLineCommentHandler} redirects to the host file's
 * commenter whenever the injection-host element's text starts with {@code "}: which is
 * always the case for Java text blocks ({@code """..."""}).  As a result, pressing
 * Ctrl+/ inside embedded FXML inserts {@code //} (Java) instead of
 * {@code <!--} ... {@code -->} (XML).  Similarly, Ctrl+Shift+/ inserts Java block
 * comments instead of {@code <!--} ... {@code -->}.
 *
 * <h2>Fix</h2>
 * <ul>
 *   <li>{@link LineCommentAction} overrides {@code CommentByLineComment}.  When the
 *       selection contains at least one FXML line, it toggles per-line
 *       {@code <!--} ... {@code -->} markers for FXML lines and {@code //} markers for
 *       Java/Kotlin lines.  For all other contexts it delegates to the original platform
 *       action unchanged.</li>
 *   <li>{@link BlockCommentAction} overrides {@code CommentByBlockComment}.  When the
 *       caret or selection start is inside FXML, it wraps/unwraps the selection in
 *       {@code <!--} ... {@code -->}.  Otherwise it delegates to the platform action.</li>
 * </ul>
 */
public final class Fxml2EmbeddedCommentHandlers {

    private Fxml2EmbeddedCommentHandlers() {}

    // -----------------------------------------------------------------------
    // CommentByLineComment override
    // -----------------------------------------------------------------------

    /**
     * Replaces the platform's {@code CommentByLineComment} action (registered via
     * {@code overrides="true"} in {@code plugin.xml}).
     *
     * <p>When the selection (or caret line) contains at least one FXML line, each line
     * is toggled independently:
     * <ul>
     *   <li>FXML lines are wrapped in {@code <!-- content -->} (or unwrapped when all
     *       lines are already commented).</li>
     *   <li>Java/Kotlin lines are prefixed with {@code // } (or de-prefixed).</li>
     * </ul>
     * <p>For selections containing no FXML lines the original platform handler is
     * called unchanged via {@code super.actionPerformed(e)}.
     */
    public static final class LineCommentAction extends CommentByLineCommentAction {

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            if (project == null || editor == null) {
                super.actionPerformed(e);
                return;
            }

            // When the caret is inside an injected FXML fragment, IntelliJ may provide
            // the injected EditorWindow instead of the host editor.  Always work with the
            // top-level (host) editor so that getDocument() returns the host document and
            // InjectedLanguageManager.findInjectedElementAt() receives the host PSI file.
            Editor hostEditor = editor instanceof EditorWindow ew ? ew.getDelegate() : editor;

            PsiFile hostFile = PsiDocumentManager.getInstance(project)
                    .getPsiFile(hostEditor.getDocument());
            if (hostFile == null) {
                super.actionPerformed(e);
                return;
            }

            InjectedLanguageManager ilm = InjectedLanguageManager.getInstance(project);
            Document document = hostEditor.getDocument();
            List<Caret> allCarets = hostEditor.getCaretModel().getAllCarets();

            // ----------------------------------------------------------------
            // Phase 1 (read):  Collect per-caret comment data.
            //   If a caret's selection contains no FXML lines, fall through
            //   to the original Java handler for that entire invocation.
            // ----------------------------------------------------------------
            List<CaretCommentData> dataList = new ArrayList<>(allCarets.size());

            for (Caret caret : allCarets) {
                CaretCommentData data = collectCaretData(caret, document, hostFile, ilm);
                if (data == null) {
                    // No FXML in this caret's range: delegate entirely to original.
                    super.actionPerformed(e);
                    return;
                }
                dataList.add(data);
            }

            if (dataList.isEmpty()) {
                super.actionPerformed(e);
                return;
            }

            // ----------------------------------------------------------------
            // Phase 2 (write):  Apply toggle in reverse-offset order so that
            //   earlier offsets are not displaced by later insertions /
            //   deletions.
            // ----------------------------------------------------------------
            WriteCommandAction.runWriteCommandAction(project,
                    "Comment with Line Comment", null,
                    () -> {
                        dataList.sort((a, b) ->
                                Integer.compare(b.firstLine(), a.firstLine()));
                        for (CaretCommentData data : dataList) {
                            applyToggle(document, data);
                        }
                    }, hostFile);
        }

        // -------------------------------------------------------------------
        // Data collection
        // -------------------------------------------------------------------

        /**
         * Returns comment data for {@code caret}, or {@code null} when the selection
         * should be delegated to the original platform handler.
         *
         * <p>Delegation happens when:
         * <ul>
         *   <li>the selection contains no FXML lines at all, or</li>
         *   <li>the selection contains a <em>mix</em> of FXML and Java/Kotlin lines.</li>
         * </ul>
         *
         * <p>The mixed case is delegated intentionally: commenting out the Java structural
         * lines (e.g. {@code @ComponentView("""} or the closing {@code """)} ) destroys
         * the FXML injection context, so a subsequent un-comment action can no longer
         * recognize those lines as FXML.  By letting the platform use {@code //} for the
         * entire selection in the mixed case, both comment and un-comment operations are
         * symmetric.
         */
        private static @Nullable CaretCommentData collectCaretData(
                @NotNull Caret caret,
                @NotNull Document document,
                @NotNull PsiFile hostFile,
                @NotNull InjectedLanguageManager ilm) {

            // Determine line range to comment.
            int caretOff   = caret.getOffset();
            int startOffset = caret.hasSelection() ? caret.getSelectionStart() : caretOff;
            int endOffset   = caret.hasSelection() ? caret.getSelectionEnd()   : caretOff;

            int startLine = document.getLineNumber(startOffset);
            int endLine   = document.getLineNumber(endOffset);

            // Don't include a trailing empty line when the selection ends exactly
            // at the start of that line.
            if (endLine > startLine
                    && document.getLineStartOffset(endLine) == endOffset) {
                endLine--;
            }

            CharSequence chars = document.getCharsSequence();
            List<LineInfo> lines    = new ArrayList<>();
            boolean        allCommented = true;
            boolean        anyFxml2    = false;
            boolean        anyJava     = false;

            for (int line = startLine; line <= endLine; line++) {
                int lineStart = document.getLineStartOffset(line);
                int lineEnd   = document.getLineEndOffset(line);

                // Find first non-whitespace character on this line.
                int contentStart = lineStart;
                while (contentStart < lineEnd
                        && (chars.charAt(contentStart) == ' '
                            || chars.charAt(contentStart) == '\t')) {
                    contentStart++;
                }
                if (contentStart == lineEnd) {
                    continue; // blank / whitespace-only line, skip
                }

                // Determine if this line is inside the FXML injection.
                boolean isFxml2 = isOffsetInFxml2(contentStart, hostFile, ilm);
                if (isFxml2) {
                    anyFxml2 = true;
                } else {
                    anyJava = true;
                }

                // Find last non-whitespace character on this line.
                int contentEnd = lineEnd;
                while (contentEnd > contentStart
                        && (chars.charAt(contentEnd - 1) == ' '
                            || chars.charAt(contentEnd - 1) == '\t')) {
                    contentEnd--;
                }

                CharSequence lineContent = chars.subSequence(contentStart, contentEnd);
                boolean      commented;
                if (isFxml2) {
                    commented = isXmlLineComment(lineContent);
                } else {
                    commented = isJavaLineComment(lineContent);
                }
                if (!commented) {
                    allCommented = false;
                }

                lines.add(new LineInfo(line, contentStart, contentEnd, commented, isFxml2));
            }

            if (!anyFxml2 || anyJava) {
                // No FXML lines, or mixed FXML+Java selection: let the platform
                // handle it with Java "//" so that comment/un-comment is symmetric.
                return null;
            }

            if (lines.isEmpty()) {
                return null; // nothing actionable in this caret's range
            }

            return new CaretCommentData(lines, allCommented);
        }

        // -------------------------------------------------------------------
        // Apply toggle
        // -------------------------------------------------------------------

        private static void applyToggle(@NotNull Document document,
                                        @NotNull CaretCommentData data) {
            // Iterate in reverse so that each replacement preserves the offsets
            // of lines processed later (lower line numbers).
            List<LineInfo> lines = data.lines();
            for (int i = lines.size() - 1; i >= 0; i--) {
                LineInfo info = lines.get(i);

                int lineStart = document.getLineStartOffset(info.line());
                int lineEnd   = document.getLineEndOffset(info.line());
                CharSequence chars = document.getCharsSequence();

                // Re-derive content boundaries from the current document state;
                // earlier replacements in this same batch (higher line numbers)
                // cannot shift the start of a lower line.
                int cs = lineStart;
                while (cs < lineEnd
                        && (chars.charAt(cs) == ' ' || chars.charAt(cs) == '\t')) {
                    cs++;
                }
                int ce = lineEnd;
                while (ce > cs
                        && (chars.charAt(ce - 1) == ' '
                            || chars.charAt(ce - 1) == '\t')) {
                    ce--;
                }
                if (cs >= ce) {
                    continue;
                }

                String indent   = chars.subSequence(lineStart, cs).toString();
                String content  = chars.subSequence(cs, ce).toString();

                if (info.isFxml2()) {
                    if (data.allCommented()) {
                        document.replaceString(lineStart, lineEnd,
                                indent + uncommentXml(content));
                    } else {
                        document.replaceString(lineStart, lineEnd,
                                indent + "<!--" + content + "-->");
                    }
                } else {
                    // Java / Kotlin line
                    if (data.allCommented()) {
                        document.replaceString(lineStart, lineEnd,
                                indent + uncommentJava(content));
                    } else {
                        document.replaceString(lineStart, lineEnd,
                                indent + "// " + content);
                    }
                }
            }
        }

        // -------------------------------------------------------------------
        // Helpers
        // -------------------------------------------------------------------

        private static boolean isOffsetInFxml2(int offset,
                                               @NotNull PsiFile hostFile,
                                               @NotNull InjectedLanguageManager ilm) {
            PsiElement injected = ilm.findInjectedElementAt(hostFile, offset);
            if (injected == null) {
                return false;
            }
            PsiFile injectedFile = injected.getContainingFile();
            return injectedFile instanceof XmlFile xmlFile
                    && Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile);
        }

        /**
         * Returns {@code true} when {@code lineContent} (already trimmed of surrounding
         * whitespace) looks like a per-line XML comment: starts with {@code <!--} and
         * ends with {@code -->}.
         */
        private static boolean isXmlLineComment(@NotNull CharSequence lineContent) {
            if (lineContent.length() < 7) {
                // Minimum: "<!---->" = 7 chars
                return false;
            }
            String s = lineContent.toString();
            return s.startsWith("<!--") && s.endsWith("-->");
        }

        /**
         * Returns {@code true} when {@code lineContent} (already trimmed) looks like a
         * Java/Kotlin line comment: starts with {@code //}.
         */
        private static boolean isJavaLineComment(@NotNull CharSequence lineContent) {
            return lineContent.length() >= 2
                    && lineContent.charAt(0) == '/'
                    && lineContent.charAt(1) == '/';
        }

        /**
         * Strips the {@code <!--} / {@code -->} wrappers from a single XML-commented
         * line that has already been confirmed to match {@link #isXmlLineComment}.
         */
        @NotNull
        private static String uncommentXml(@NotNull String content) {
            // "<!--content-->" -> "content"
            if (content.startsWith("<!--") && content.endsWith("-->")) {
                return content.substring(4, content.length() - 3).trim();
            }
            return content; // fallback, should not reach here
        }

        /**
         * Strips the {@code // } (or {@code //}) prefix from a Java/Kotlin line comment.
         */
        @NotNull
        private static String uncommentJava(@NotNull String content) {
            if (content.startsWith("// ")) {
                return content.substring(3);
            }
            if (content.startsWith("//")) {
                return content.substring(2).stripLeading();
            }
            return content; // fallback, should not reach here
        }
    }

    // -----------------------------------------------------------------------
    // CommentByBlockComment override
    // -----------------------------------------------------------------------

    /**
     * Replaces the platform's {@code CommentByBlockComment} action (registered via
     * {@code overrides="true"} in {@code plugin.xml}).
     *
     * <p>When the caret or selection start is inside embedded FXML markup, wraps the
     * selection in {@code <!-- } / {@code  -->} (or removes those wrappers when the
     * selection is already an XML block comment).  For all other contexts it delegates
     * to the original platform action unchanged.
     *
     * <h2>Why a separate override is needed</h2>
     * <p>{@code CommentByBlockComment} uses {@code MultiCaretCodeInsightAction}, which
     * promotes to the injected editor via
     * {@code InjectedLanguageUtil.getCaretForInjectedLanguageNoCommit}.  In the test
     * environment (and in some real editing scenarios) this promotion fails because
     * {@code findInjectedPsiNoCommit} cannot locate the injection when the PSI has not
     * been committed at the moment the action runs, causing the platform to fall back to
     * the Java commenter and insert Java block comments instead of {@code <!--} ... {@code -->}.
     */
    public static final class BlockCommentAction extends AnAction implements DumbAware {

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            if (project == null || editor == null) {
                fallback(e);
                return;
            }

            // Always work with the host (top-level) editor.
            Editor hostEditor = editor instanceof EditorWindow ew ? ew.getDelegate() : editor;

            PsiFile hostFile = PsiDocumentManager.getInstance(project)
                    .getPsiFile(hostEditor.getDocument());
            if (hostFile == null) {
                fallback(e);
                return;
            }

            InjectedLanguageManager ilm = InjectedLanguageManager.getInstance(project);
            Document document = hostEditor.getDocument();
            Caret caret = hostEditor.getCaretModel().getCurrentCaret();

            int selStart = caret.hasSelection() ? caret.getSelectionStart() : caret.getOffset();
            int selEnd   = caret.hasSelection() ? caret.getSelectionEnd()   : caret.getOffset();

            // Commit any pending document changes so that the PSI and the
            // InjectedLanguageManager's injection boundaries are current.
            // Without this, a document mutation from the previous invocation
            // (e.g. the first block-comment press) leaves the PSI stale and
            // causes findInjectedElementAt() to return null for offsets that
            // are genuinely inside the FXML injection.
            PsiDocumentManager.getInstance(project).commitDocument(document);

            // Only intercept when the selection start (or caret) is inside FXML.
            if (!isSelectionInFxml2(selStart, selEnd, hostFile, ilm)) {
                fallback(e);
                return;
            }

            WriteCommandAction.runWriteCommandAction(project,
                    "Comment with Block Comment", null,
                    () -> {
                        boolean added = applyBlockCommentToggle(document, selStart, selEnd);
                        if (added) {
                            // After inserting "<!--" (4 chars at selStart) and "-->" (3 chars
                            // at selEnd), the full block comment spans [selStart, selEnd+7) in
                            // the modified document.  Explicitly set the selection to this full
                            // range so that the very next press sees a complete "<!--...-->"
                            // block (Case 1 in applyBlockCommentToggle) and removes it,
                            // regardless of what IntelliJ's caret-tracking does with the insertions.
                            caret.setSelection(selStart, selEnd + 7);
                        }
                    },
                    hostFile);
        }

        // -------------------------------------------------------------------
        // Block comment toggle
        // -------------------------------------------------------------------

        /**
         * Applies the block-comment toggle to the given selection range.
         *
         * @return {@code true} when a new comment was <em>added</em> (Case 3),
         *         {@code false} when an existing comment was <em>removed</em>
         *         (Cases 1 or 2).
         */
        private static boolean applyBlockCommentToggle(@NotNull Document document,
                                                       int selStart, int selEnd) {
            CharSequence chars = document.getCharsSequence();

            // Trim whitespace at both ends of the selection to locate the
            // actual content start / end.
            int trimStart = selStart;
            while (trimStart < selEnd && Character.isWhitespace(chars.charAt(trimStart))) {
                trimStart++;
            }
            int trimEnd = selEnd;
            while (trimEnd > trimStart && Character.isWhitespace(chars.charAt(trimEnd - 1))) {
                trimEnd--;
            }

            // Case 1: The trimmed selection is already an XML block comment.
            if (isXmlBlockComment(chars, trimStart, trimEnd)) {
                uncommentBlock(document, trimStart, trimEnd);
                return false;
            }

            // Case 2: The selection covers the inner content of an existing block comment
            // whose markers lie just outside the selection boundaries.  This is the typical
            // state after the first press: we explicitly set the caret selection to the inner
            // content [selStart+4, selEnd+4] so that the markers are immediately adjacent.
            // Skipping at most one space handles both "<!--content-->" and "<!-- content -->".
            int outerStart = outerCommentStart(chars, trimStart);
            int outerEnd   = outerCommentEnd(chars, trimEnd);
            if (outerStart >= 0 && outerEnd > outerStart) {
                uncommentBlock(document, outerStart, outerEnd);
                return false;
            }

            // Case 3: No existing comment: wrap the selection.
            commentBlock(document, selStart, selEnd);
            return true;
        }

        /**
         * Returns {@code true} when the text in {@code [start, end)} starts with
         * {@code <!--} and ends with {@code -->}.
         */
        private static boolean isXmlBlockComment(CharSequence chars, int start, int end) {
            int len = end - start;
            if (len < 7) return false; // minimum: "<!---->"
            return chars.charAt(start)     == '<'
                && chars.charAt(start + 1) == '!'
                && chars.charAt(start + 2) == '-'
                && chars.charAt(start + 3) == '-'
                && chars.charAt(end - 1)   == '>'
                && chars.charAt(end - 2)   == '-'
                && chars.charAt(end - 3)   == '-';
        }

        /** Inserts {@code <!--} at {@code selStart} and {@code -->} at {@code selEnd}. */
        private static void commentBlock(@NotNull Document document, int selStart, int selEnd) {
            // Insert suffix first so that selStart offset remains valid.
            document.insertString(selEnd,   "-->");
            document.insertString(selStart, "<!--");
        }

        /**
         * Removes the {@code <!--} prefix and {@code -->} suffix from the block comment
         * that runs {@code [trimStart, trimEnd)}.
         *
         * <p>Exactly 4 characters ({@code <!--}) are deleted at {@code trimStart} and
         * exactly 3 characters ({@code -->}) are deleted at {@code trimEnd: 3}.
         * No surrounding spaces are stripped, since {@link #commentBlock} never inserts
         * extra spaces, any whitespace adjacent to the markers is part of the original
         * content and must be preserved.
         */
        private static void uncommentBlock(@NotNull Document document,
                                           int trimStart, int trimEnd) {
            // Remove suffix first (higher offset) so that trimStart remains valid.
            document.deleteString(trimEnd - 3, trimEnd);     // removes "-->"
            document.deleteString(trimStart, trimStart + 4); // removes "<!--"
        }

        /**
         * Looks backward from {@code pos}, skipping at most one space, for a {@code <!--}
         * sequence.  Returns the index of {@code <} if found immediately before
         * {@code pos} (with optional space), or {@code -1}.
         */
        private static int outerCommentStart(CharSequence chars, int pos) {
            int i = pos;
            if (i > 0 && chars.charAt(i - 1) == ' ') i--; // skip optional space
            if (i >= 4
                    && chars.charAt(i - 4) == '<'
                    && chars.charAt(i - 3) == '!'
                    && chars.charAt(i - 2) == '-'
                    && chars.charAt(i - 1) == '-') {
                return i - 4;
            }
            return -1;
        }

        /**
         * Looks forward from {@code pos}, skipping at most one space, for a {@code -->}
         * sequence.  Returns the index just after {@code >} if found immediately after
         * {@code pos} (with optional space), or {@code -1}.
         */
        private static int outerCommentEnd(CharSequence chars, int pos) {
            int i = pos;
            if (i < chars.length() && chars.charAt(i) == ' ') i++; // skip optional space
            if (i + 3 <= chars.length()
                    && chars.charAt(i)     == '-'
                    && chars.charAt(i + 1) == '-'
                    && chars.charAt(i + 2) == '>') {
                return i + 3;
            }
            return -1;
        }

        // -------------------------------------------------------------------
        // Helpers
        // -------------------------------------------------------------------

        /**
         * Returns {@code true} when the entire selection is inside an embedded FXML
         * injection.  For a bare caret ({@code selStart == selEnd}) only the caret
         * position is checked.
         *
         * <p>Both the selection start <em>and</em> the position just before the selection
         * end must be inside FXML; if either endpoint falls outside (e.g. a selection
         * that spans FXML markup and surrounding Java code), this returns {@code false}
         * so the action falls back to Java {@code /* }&#42;{@code /} block comments.
         */
        private static boolean isSelectionInFxml2(int selStart, int selEnd,
                                                  @NotNull PsiFile hostFile,
                                                  @NotNull InjectedLanguageManager ilm) {
            if (!isOffsetInFxml2(selStart, hostFile, ilm)) return false;
            if (selEnd > selStart) return isOffsetInFxml2(selEnd - 1, hostFile, ilm);
            return true;
        }

        private static boolean isOffsetInFxml2(int offset,
                                               @NotNull PsiFile hostFile,
                                               @NotNull InjectedLanguageManager ilm) {
            PsiElement injected = ilm.findInjectedElementAt(hostFile, offset);
            if (injected == null) return false;
            PsiFile injectedFile = injected.getContainingFile();
            return injectedFile instanceof XmlFile xmlFile
                    && Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile);
        }

        /**
         * Delegates to the platform's block-comment handler when the caret is outside
         * an FXML injection, so that Java/Kotlin block comments work normally.
         */
        private static void fallback(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            if (project == null || editor == null) return;

            PsiFile file = PsiDocumentManager.getInstance(project)
                    .getPsiFile(editor.getDocument());
            if (file == null) return;

            CommentByBlockCommentHandler handler = new CommentByBlockCommentHandler();
            WriteCommandAction.runWriteCommandAction(project, "Comment with Block Comment", null, () -> {
                editor.getCaretModel().runForEachCaret(caret -> handler.invoke(project, editor, caret, file));
                handler.postInvoke();
            }, file);
            editor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);
        }
    }

    // -----------------------------------------------------------------------
    // Value types
    // -----------------------------------------------------------------------

    /**
     * Describes a single non-blank line that is a candidate for toggling.
     *
     * @param isFxml2 {@code true} when the line is inside an FXML injection and should
     *                be commented with {@code <!-- ... -->}; {@code false} for
     *                Java/Kotlin lines that should use {@code // }.
     */
    private record LineInfo(
            int line,
            int contentStart,
            int contentEnd,
            boolean commented,
            boolean isFxml2) {
    }

    /**
     * Collected comment information for one caret's selection range.
     *
     * @param lines        non-blank lines inside the caret's selection
     * @param allCommented {@code true} when every line in {@code lines} is already
     *                     commented in its respective language: signals "uncomment"
     */
    private record CaretCommentData(
            List<LineInfo> lines,
            boolean allCommented) {

        int firstLine() {
            return lines.isEmpty() ? Integer.MAX_VALUE : lines.getFirst().line();
        }
    }

}
