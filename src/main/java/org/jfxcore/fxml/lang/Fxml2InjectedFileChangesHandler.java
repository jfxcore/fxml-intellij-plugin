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

/** Writes an edited FXML/2 fragment back to its host without changing its source layout. */
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
