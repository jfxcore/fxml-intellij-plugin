// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@link EditorActionHandler} registered with {@code order="first"} for the
 * {@code com.intellij.editorActionHandler} extension point, action
 * {@code EditorSelectWord} (also called on mouse double-click).
 *
 * <p>After the platform's default handler has determined the word at the caret,
 * this handler trims any leading FXML2 sigil character ({@code $}, {@code %})
 * from the selection when the caret is inside an FXML2 attribute value.
 *
 * <p>Without this fix, double-clicking on {@code vm} in {@code $vm.sayHello}
 * produces the selection {@code $vm} because Java (and the XML editor) treat
 * {@code $} as a valid identifier character.
 */
public final class Fxml2WordSelectionHandler extends EditorActionHandler {

    private final EditorActionHandler myDelegate;

    /** Called by the IntelliJ platform: receives the previously-registered handler. */
    public Fxml2WordSelectionHandler(@Nullable EditorActionHandler delegate) {
        myDelegate = delegate;
    }

    @Override
    protected boolean isEnabledForCaret(@NotNull Editor editor,
                                        @NotNull Caret caret,
                                        DataContext dataContext) {
        return myDelegate == null || myDelegate.isEnabled(editor, caret, dataContext);
    }

    @Override
    protected void doExecute(@NotNull Editor editor,
                             @Nullable Caret caret,
                             DataContext dataContext) {
        // Run the default word-selection first.
        if (myDelegate != null) {
            myDelegate.execute(editor, caret, dataContext);
        }

        // Then trim fxml2 sigil characters from the beginning of the selection.
        Project project = editor.getProject();
        if (project == null) return;

        if (caret != null) {
            trimSigilFromSelection(editor, caret, project);
        } else {
            editor.getCaretModel().runForEachCaret(c -> trimSigilFromSelection(editor, c, project));
        }
    }

    /**
     * If the caret is inside an FXML2 attribute value and the selection starts with
     * a sigil character ({@code $} or {@code %}), removes that character
     * from the start of the selection.
     */
    private static void trimSigilFromSelection(@NotNull Editor editor,
                                               @NotNull Caret caret,
                                               @NotNull Project project) {
        if (!caret.hasSelection()) return;

        int start = caret.getSelectionStart();
        int end   = caret.getSelectionEnd();
        if (start >= end) return;

        CharSequence text = editor.getDocument().getCharsSequence();
        char first = text.charAt(start);
        if (first != '$' && first != '%') return;

        // Only apply inside FXML2 contexts.
        if (!isInFxml2Context(editor, project, start + 1)) return;

        // Trim the sigil.
        caret.setSelection(start + 1, end);
    }

    /**
     * Returns {@code true} when {@code offset} falls inside an FXML2 document -
     * either a standalone {@code .fxml}/{@code .fxml2} file or an embedded FXML2
     * fragment inside a Java/Kotlin {@code @ComponentView} annotation.
     */
    private static boolean isInFxml2Context(@NotNull Editor editor,
                                            @NotNull Project project,
                                            int offset) {
        PsiDocumentManager docManager = PsiDocumentManager.getInstance(project);
        PsiFile hostFile = docManager.getPsiFile(editor.getDocument());
        if (hostFile == null) return false;

        // Case 1: the editor IS a standalone FXML2 file.
        if (hostFile instanceof XmlFile && Fxml2FileType.isFxml2(hostFile)) {
            return true;
        }

        // Case 2: the position is inside an injected FXML2 fragment.
        InjectedLanguageManager ilm = InjectedLanguageManager.getInstance(project);
        PsiElement injected = ilm.findInjectedElementAt(hostFile, offset);
        if (injected == null) return false;

        PsiFile injectedFile = injected.getContainingFile();
        return injectedFile instanceof XmlFile && Fxml2EmbeddedUtil.isEmbeddedFxml2(injectedFile);
    }
}
