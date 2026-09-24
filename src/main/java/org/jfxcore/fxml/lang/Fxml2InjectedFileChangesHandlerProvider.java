// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.injected.editor.InjectedFileChangesHandler;
import com.intellij.injected.editor.InjectedFileChangesHandlerProvider;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.impl.source.tree.injected.JavaInjectedFileChangesHandlerProvider;
import com.intellij.psi.impl.source.tree.injected.changesHandler.CommonInjectedFileChangesHandler;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Selects exact source write-back for FXML/2 fragments and host defaults for other injections. */
public final class Fxml2InjectedFileChangesHandlerProvider implements InjectedFileChangesHandlerProvider {

    @Override
    public @NotNull InjectedFileChangesHandler createFileChangesHandler(
            @NotNull List<? extends PsiLanguageInjectionHost.Shred> shreds,
            @NotNull Editor hostEditor,
            @NotNull Document newDocument,
            @NotNull PsiFile injectedFile) {

        if (Fxml2EmbeddedUtil.isEmbeddedFxml2(injectedFile)
                || Fxml2ResourcePayloadMarker.of(injectedFile) != null) {
            return new Fxml2InjectedFileChangesHandler(shreds, hostEditor, newDocument, injectedFile);
        }

        PsiLanguageInjectionHost host = ContainerUtil.getFirstItem(shreds).getHost();
        return host != null && host.getLanguage() == JavaLanguage.INSTANCE
                ? new JavaInjectedFileChangesHandlerProvider()
                        .createFileChangesHandler(shreds, hostEditor, newDocument, injectedFile)
                : new CommonInjectedFileChangesHandler(shreds, hostEditor, newDocument, injectedFile);
    }
}
