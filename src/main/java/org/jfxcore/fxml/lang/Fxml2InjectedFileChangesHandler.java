// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.impl.source.tree.injected.changesHandler.CommonInjectedFileChangesHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Writes what was edited in a fragment editor back into an FXML/2 document, character for
 * character.
 *
 * <p>An FXML/2 fragment - markup embedded in an annotation value, or the payload of a
 * {@code <?resource ?>} declaration in either form of markup - is injected over the text of its
 * host exactly as written, indentation included.  Committing such a fragment is therefore a plain
 * splice: the text of the fragment replaces the range of the host it came from, and nothing about
 * the layout is recomputed.
 *
 * <p>Content-change handling of the host language cannot do that.  For a Java text block it
 * indents every line of the committed text to the column the fragment starts at, and escapes the
 * line breaks and quotes of text that is already written as the source it is spliced into.  Both
 * rewrite a payload that was laid out by the rules of the document it lives in.  Replacing the
 * whole host text instead keeps the host delimiters and leaves everything between them as the
 * fragment editor has it.
 */
final class Fxml2InjectedFileChangesHandler extends CommonInjectedFileChangesHandler {

    Fxml2InjectedFileChangesHandler(@NotNull List<? extends PsiLanguageInjectionHost.Shred> shreds,
                                    @NotNull Editor hostEditor,
                                    @NotNull Document fragmentDocument,
                                    @NotNull PsiFile injectedFile) {
        super(shreds, hostEditor, fragmentDocument, injectedFile);
    }

    @Override
    protected @Nullable PsiLanguageInjectionHost updateHostElement(@NotNull PsiLanguageInjectionHost host,
                                                                   @NotNull TextRange insideHost,
                                                                   @NotNull String content) {
        String hostText = host.getText();
        if (!TextRange.allOf(hostText).contains(insideHost)) return null;

        return host.updateText(hostText.substring(0, insideHost.getStartOffset())
                               + content
                               + hostText.substring(insideHost.getEndOffset()));
    }
}
