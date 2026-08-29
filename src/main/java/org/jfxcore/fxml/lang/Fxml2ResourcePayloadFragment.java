// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Recognizes the injected fragment that holds the payload of a {@code <?resource ?>} declaration.
 *
 * <p>A payload is edited as a document of the language its media type names, in both forms markup
 * takes: {@link Fxml2ResourceInjector} injects it into a declaration of a standalone document, and
 * {@link Fxml2EmbeddedMarkupInjection} injects it beside the markup fragment of an annotation
 * value.  Whatever the editor asks of the fragment as a whole - reformatting it, above all - is a
 * question about the declaration that carries it, because where a payload sits is decided by the
 * markup around it, which the fragment does not see.
 */
final class Fxml2ResourcePayloadFragment {

    private Fxml2ResourcePayloadFragment() {}

    /**
     * Returns {@code true} when {@code file} is the payload of a resource declaration, in a
     * standalone document or in markup embedded in an annotation value.
     */
    static boolean isPayloadFragment(@NotNull PsiFile file) {
        InjectedLanguageManager manager = InjectedLanguageManager.getInstance(file.getProject());
        if (!manager.isInjectedFragment(file)) return false;

        PsiLanguageInjectionHost host = manager.getInjectionHost(file);
        if (host == null) return false;

        // A standalone document injects the payload into the declaration itself, which hosts
        // nothing else.
        if (host instanceof Fxml2ResourceProcessingInstruction) return true;

        // An annotation value hosts the markup fragment and every payload of it side by side, so
        // a fragment other than the markup is a payload of that markup.
        return !Fxml2EmbeddedUtil.isEmbeddedFxml2(file) && hostsEmbeddedMarkup(manager, host);
    }

    /** Returns whether {@code host} carries markup embedded in a {@code @ComponentView} value. */
    private static boolean hostsEmbeddedMarkup(@NotNull InjectedLanguageManager manager,
                                               @NotNull PsiLanguageInjectionHost host) {

        @Nullable List<Pair<PsiElement, TextRange>> injected = manager.getInjectedPsiFiles(host);
        if (injected == null) return false;

        for (Pair<PsiElement, TextRange> place : injected) {
            if (place.first instanceof PsiFile injectedFile && Fxml2EmbeddedUtil.isEmbeddedFxml2(injectedFile)) {
                return true;
            }
        }
        return false;
    }
}
