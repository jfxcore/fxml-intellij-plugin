// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.injected.editor.InjectedFileChangesHandler;
import com.intellij.injected.editor.InjectedFileChangesHandlerProvider;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiFile;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.impl.source.tree.injected.JavaInjectedFileChangesHandlerProvider;
import com.intellij.psi.impl.source.tree.injected.changesHandler.CommonInjectedFileChangesHandler;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Chooses how a fragment editor writes its content back, for every language that hosts FXML/2
 * markup.
 *
 * <p>An FXML/2 fragment is committed by {@link Fxml2InjectedFileChangesHandler}, which splices it
 * into its host unchanged.  Every other fragment of the host language is left to the handler the
 * platform would have chosen, so registering this provider for a language the plugin does not own
 * - Java, whose fragments the Java plugin commits itself - changes nothing for anyone else.
 */
public final class Fxml2InjectedFileChangesHandlerProvider implements InjectedFileChangesHandlerProvider {

    @Override
    public @NotNull InjectedFileChangesHandler createFileChangesHandler(
            @NotNull List<? extends PsiLanguageInjectionHost.Shred> shreds,
            @NotNull Editor hostEditor,
            @NotNull Document newDocument,
            @NotNull PsiFile injectedFile) {

        return isFxml2Fragment(injectedFile)
                ? new Fxml2InjectedFileChangesHandler(shreds, hostEditor, newDocument, injectedFile)
                : delegate(shreds, hostEditor, newDocument, injectedFile);
    }

    /**
     * Returns the handler the platform would have chosen for a fragment that is not FXML/2.
     *
     * <p>The provider is registered for the languages that host FXML/2 markup, and a host file
     * written in one of them holds other injected fragments as well.  Those keep the handler of
     * their host language: for Java that is the one the Java plugin registers, which is what makes
     * a text block edited in a fragment editor behave as it does everywhere else in the IDE.
     */
    private static @NotNull InjectedFileChangesHandler delegate(
            @NotNull List<? extends PsiLanguageInjectionHost.Shred> shreds,
            @NotNull Editor hostEditor,
            @NotNull Document newDocument,
            @NotNull PsiFile injectedFile) {

        PsiLanguageInjectionHost host = ContainerUtil.getFirstItem(shreds).getHost();
        return host != null && host.getLanguage() == JavaLanguage.INSTANCE
                ? new JavaInjectedFileChangesHandlerProvider()
                        .createFileChangesHandler(shreds, hostEditor, newDocument, injectedFile)
                : new CommonInjectedFileChangesHandler(shreds, hostEditor, newDocument, injectedFile);
    }

    /** Returns whether {@code injectedFile} is a fragment this plugin injects. */
    private static boolean isFxml2Fragment(@NotNull PsiFile injectedFile) {
        return Fxml2EmbeddedUtil.isEmbeddedFxml2(injectedFile)
                || Fxml2ResourcePayloadFragment.isPayloadFragment(injectedFile);
    }
}
