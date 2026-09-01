// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.codeInsight.highlighting.BraceMatchingUtil;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.injected.editor.EditorWindow;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.highlighter.HighlighterIterator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Aligns a payload closing brace with the line carrying its matching opening brace. */
public final class Fxml2ResourcePayloadBraceTypedHandler extends TypedHandlerDelegate {

    @Override
    public @NotNull Result charTyped(char c,
                                     @NotNull Project project,
                                     @NotNull Editor editor,
                                     @NotNull PsiFile file) {
        if (c != '}') return Result.CONTINUE;

        PayloadEditorContext context = payloadContext(editor, file);
        if (context == null) return Result.CONTINUE;

        Document matchingDocument = context.matchingEditor().getDocument();
        int matchingClosingOffset = context.matchingClosingOffset();
        int closingLine = matchingDocument.getLineNumber(matchingClosingOffset);
        int closingLineStart = matchingDocument.getLineStartOffset(closingLine);
        CharSequence matchingText = matchingDocument.getImmutableCharSequence();
        if (containsNonWhitespace(matchingText, closingLineStart, matchingClosingOffset)) {
            return Result.CONTINUE;
        }

        int openingOffset = findOpeningBrace(matchingText, matchingClosingOffset);
        if (context.useLanguageMatcher()) {
            HighlighterIterator iterator = context.matchingEditor().getHighlighter()
                    .createIterator(matchingClosingOffset);
            if (!iterator.atEnd()
                    && BraceMatchingUtil.matchBrace(
                            matchingText, context.file().getFileType(), iterator, false)) {
                openingOffset = iterator.getStart();
            }
        }
        if (openingOffset < 0) return Result.CONTINUE;

        Document document = context.targetDocument();
        int targetOpeningOffset = context.toTargetOffset(openingOffset);
        int targetClosingOffset = context.toTargetOffset(matchingClosingOffset);
        int targetClosingLine = document.getLineNumber(targetClosingOffset);
        int targetClosingLineStart = document.getLineStartOffset(targetClosingLine);
        CharSequence text = document.getImmutableCharSequence();
        if (containsNonWhitespace(text, targetClosingLineStart, targetClosingOffset)) {
            return Result.CONTINUE;
        }

        int openingLine = document.getLineNumber(targetOpeningOffset);
        int openingLineStart = document.getLineStartOffset(openingLine);
        int openingIndentEnd = openingLineStart;
        while (openingIndentEnd < document.getLineEndOffset(openingLine)
                && Character.isWhitespace(text.charAt(openingIndentEnd))) {
            openingIndentEnd++;
        }

        document.replaceString(targetClosingLineStart, targetClosingOffset,
                text.subSequence(openingLineStart, openingIndentEnd));
        return Result.CONTINUE;
    }

    private record PayloadEditorContext(@NotNull PsiFile file,
                                        @NotNull Editor matchingEditor,
                                        @NotNull Document targetDocument,
                                        int matchingClosingOffset,
                                        boolean useLanguageMatcher,
                                        @Nullable DocumentWindow documentWindow) {

        int toTargetOffset(int matchingOffset) {
            return documentWindow != null ? documentWindow.injectedToHost(matchingOffset) : matchingOffset;
        }
    }

    private static PayloadEditorContext payloadContext(@NotNull Editor editor, @NotNull PsiFile file) {
        int closingOffset = editor.getCaretModel().getOffset() - 1;
        if (Fxml2ResourcePayloadFragment.isPayloadFragment(file)) {
            if (editor instanceof EditorWindow window) {
                return new PayloadEditorContext(file, editor, window.getDocument().getDelegate(), closingOffset,
                        true, window.getDocument());
            }

            return new PayloadEditorContext(file, editor, editor.getDocument(), closingOffset, true, null);
        }

        if (!(editor instanceof EditorWindow window) || !Fxml2EmbeddedUtil.isEmbeddedFxml2(file)) {
            return null;
        }

        Editor hostEditor = window.getDelegate();
        int hostClosingOffset = hostEditor.getCaretModel().getOffset() - 1;
        String prefix = hostEditor.getDocument().getText(new TextRange(0, hostClosingOffset));
        int declarationStart = prefix.lastIndexOf("<?resource");
        if (declarationStart < 0 || prefix.lastIndexOf("?>") >= declarationStart) return null;

        return new PayloadEditorContext(
                file, hostEditor, hostEditor.getDocument(), hostClosingOffset, false, null);
    }

    private static boolean containsNonWhitespace(@NotNull CharSequence text, int start, int end) {
        for (int i = start; i < end; i++) {
            if (!Character.isWhitespace(text.charAt(i))) return true;
        }
        return false;
    }

    /** Finds the opener when the active editor has no brace matcher for the payload language. */
    private static int findOpeningBrace(@NotNull CharSequence text, int closingOffset) {
        int depth = 1;
        for (int i = closingOffset - 1; i >= 0; i--) {
            if (text.charAt(i) == '}') {
                depth++;
            } else if (text.charAt(i) == '{' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
