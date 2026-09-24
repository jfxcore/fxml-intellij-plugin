// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate;
import com.intellij.injected.editor.EditorWindow;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Continues the indentation of a {@code <?resource ?>} payload when Enter is pressed inside it,
 * in a standalone FXML/2 document as well as in markup embedded in a {@code @ComponentView}
 * annotation.
 *
 * <p>The indent adjustment that follows the inserted newline is computed by the formatter of the
 * document the payload is written in, which knows the declaration as a single token and therefore
 * indents the new line as markup, or as an annotation value, rather than as a continuation of the
 * payload.
 *
 * <p>The handler is reached with the injected payload editor when the caret is known to sit in an
 * injected fragment and with the editor of the enclosing document otherwise, so it works from the
 * host offset the caret maps to in either case.  It inserts the newline and the payload
 * indentation into the host document itself and stops the chain, so the indentation the user sees
 * is the one {@link Fxml2PayloadIndent} describes.
 */
public final class Fxml2ResourcePayloadEnterHandler implements EnterHandlerDelegate {

    @Override
    public Result preprocessEnter(@NotNull PsiFile file,
                                  @NotNull Editor editor,
                                  @NotNull Ref<Integer> caretOffset,
                                  @NotNull Ref<Integer> caretAdvance,
                                  @NotNull DataContext dataContext,
                                  @Nullable EditorActionHandler originalHandler) {

        Project project = dataContext.getData(CommonDataKeys.PROJECT);
        if (project == null) return Result.Continue;

        Editor hostEditor = editor instanceof EditorWindow injectedEditor
                ? injectedEditor.getDelegate()
                : editor;
        Document hostDocument = hostEditor.getDocument();
        InjectedLanguageManager injectedLanguageManager = InjectedLanguageManager.getInstance(project);

        int hostOffset = editor instanceof EditorWindow
                ? injectedLanguageManager.injectedToHost(file, caretOffset.get())
                : caretOffset.get();

        PsiFile hostFile = injectedLanguageManager.getTopLevelFile(file);
        Fxml2ResourcePayloadContext payload = Fxml2ResourcePayloadContext.find(file, caretOffset.get());
        if (payload == null) return Result.Continue;

        int line = hostDocument.getLineNumber(hostOffset);
        int lineStart = hostDocument.getLineStartOffset(line);
        CharSequence linePrefix = hostDocument.getImmutableCharSequence().subSequence(lineStart, hostOffset);
        boolean opensPayload = payload.payloadStart() >= lineStart;
        VirtualFile contextFile = hostFile.getVirtualFile();
        String inserted = "\n"
                + Fxml2PayloadIndent.of(linePrefix,
                                        Fxml2EffectiveIndent.ofMarkup(project, contextFile),
                                        Fxml2EffectiveIndent.ofPayload(project, contextFile, payload.language()),
                                        opensPayload).text();

        PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(hostDocument);
        hostDocument.insertString(hostOffset, inserted);
        PsiDocumentManager.getInstance(project).commitDocument(hostDocument);

        hostEditor.getCaretModel().moveToOffset(hostOffset + inserted.length());
        hostEditor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);
        hostEditor.getSelectionModel().removeSelection();
        return Result.Stop;
    }

}
