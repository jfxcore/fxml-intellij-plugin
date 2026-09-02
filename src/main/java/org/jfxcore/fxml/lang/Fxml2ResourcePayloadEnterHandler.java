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
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resource.Fxml2ResourcePayloadLanguage;

import java.util.List;

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
        PayloadAtCaret payload = payloadAt(injectedLanguageManager, file, hostFile, hostOffset);
        if (payload == null) return Result.Continue;

        int line = hostDocument.getLineNumber(hostOffset);
        int lineStart = hostDocument.getLineStartOffset(line);
        CharSequence linePrefix = hostDocument.getImmutableCharSequence().subSequence(lineStart, hostOffset);
        boolean opensPayload = payload.start() >= lineStart;
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

    /**
     * The payload the caret sits in: where it starts in the host document, and the language it is
     * written in, which is the language its indentation steps are those of.
     *
     * @param start    the offset the payload starts at in the host document
     * @param language the language the media type of the declaration names
     */
    private record PayloadAtCaret(int start, @NotNull Fxml2ResourcePayloadLanguage language) {}

    /**
     * Returns the payload the caret sits in, or {@code null} when {@code hostOffset} does not sit
     * inside the payload of a resource declaration of the markup {@code hostFile} carries.
     */
    private static @Nullable PayloadAtCaret payloadAt(@NotNull InjectedLanguageManager injectedLanguageManager,
                                                      @NotNull PsiFile file,
                                                      @NotNull PsiFile hostFile,
                                                      int hostOffset) {

        PsiLanguageInjectionHost host = markupHost(injectedLanguageManager, file, hostFile, hostOffset);
        if (host == null) return null;

        String hostText = host.getText();
        int hostStart = host.getTextRange().getStartOffset();
        int offsetInHost = hostOffset - hostStart;
        Fxml2ResourceInjectionPlan plan = Fxml2ResourceInjectionPlan.of(hostText, TextRange.allOf(hostText));

        for (Fxml2ResourceInjectionPlan.Fxml2PayloadInjection payload : plan.payloads()) {
            // A caret at the very end of a payload is still inside it: the declaration continues
            // with its terminator, not with more payload.
            if (payload.rawRange().getStartOffset() <= offsetInHost
                    && offsetInHost <= payload.rawRange().getEndOffset()) {
                return new PayloadAtCaret(hostStart + payload.rawRange().getStartOffset(),
                                          payload.payloadLanguage());
            }
        }
        return null;
    }

    /**
     * Returns the element hosting the markup the caret sits in: the resource declaration itself in
     * a standalone document, or the annotation value holding embedded markup.
     */
    private static @Nullable PsiLanguageInjectionHost markupHost(
            @NotNull InjectedLanguageManager injectedLanguageManager,
            @NotNull PsiFile file,
            @NotNull PsiFile hostFile,
            int hostOffset) {

        PsiLanguageInjectionHost injectionHost = injectedLanguageManager.getInjectionHost(file);
        if (injectionHost == null) {
            PsiElement element = hostFile.findElementAt(hostOffset);
            injectionHost = PsiTreeUtil.getParentOfType(element, PsiLanguageInjectionHost.class, false);
        }
        return injectionHost != null && hostsFxml2Markup(injectedLanguageManager, injectionHost)
                ? injectionHost
                : null;
    }

    /** Returns whether {@code host} holds an FXML/2 document, standalone or embedded. */
    private static boolean hostsFxml2Markup(@NotNull InjectedLanguageManager injectedLanguageManager,
                                            @NotNull PsiLanguageInjectionHost host) {
        if (host instanceof Fxml2ResourceProcessingInstruction) return true;

        List<Pair<PsiElement, TextRange>> injected = injectedLanguageManager.getInjectedPsiFiles(host);
        if (injected == null) return false;

        for (Pair<PsiElement, TextRange> place : injected) {
            if (place.first.getContainingFile() instanceof XmlFile xmlFile
                    && Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile)) {
                return true;
            }
        }
        return false;
    }
}
