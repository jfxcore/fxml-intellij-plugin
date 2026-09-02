// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.psi.ElementManipulators;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import org.jfxcore.fxml.resource.Fxml2ResourceDeclaration;
import org.jfxcore.fxml.resource.Fxml2ResourceInstructionParser;
import org.jfxcore.fxml.resource.Fxml2ResourceParseResult;
import org.jfxcore.fxml.resource.Fxml2ResourcePayloadLanguage;

import java.util.List;

/**
 * Recognizes the injected fragment that holds the payload of a {@code <?resource ?>} declaration.
 *
 * <p>A payload is edited as a document of the language its media type names, in both forms markup
 * takes: {@link Fxml2ResourceInjector} injects it into a declaration of a standalone document, and
 * {@link Fxml2EmbeddedMarkupInjection} injects it beside the markup fragment of an annotation
 * value. Operations performed on the fragment as a whole (most notably reformatting) are evaluated
 * in the context of its parent declaration. This is because a payload's positioning is determined by the
 * surrounding markup, which is not visible to the fragment itself.
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

    /**
     * Returns the language the payload {@code file} holds is written in, or {@code null} when
     * {@code file} is not the payload of a resource declaration.
     *
     * <p>The language is the one the media type of the declaration names, which is a property of
     * the declaration rather than of the fragment: a media type the IDE has no language for is
     * edited as plain text, and the step it is written in is still the one its declaration asks
     * for.
     */
    static @Nullable Fxml2ResourcePayloadLanguage languageOf(@NotNull PsiFile file) {
        if (!isPayloadFragment(file)) return null;

        InjectedLanguageManager manager = InjectedLanguageManager.getInstance(file.getProject());
        PsiLanguageInjectionHost host = manager.getInjectionHost(file);
        if (host == null) return null;

        int startInHost = manager.injectedToHost(file, 0) - host.getTextRange().getStartOffset();
        String hostText = host.getText();

        if (host instanceof Fxml2ResourceProcessingInstruction) {
            Fxml2ResourceParseResult result = Fxml2ResourceInstructionParser.parseAt(hostText, 0, hostText.length());
            Fxml2ResourceDeclaration declaration = result != null ? result.declaration() : null;
            return declaration != null ? Fxml2ResourcePayloadLanguage.of(declaration) : null;
        }

        Fxml2ResourceInjectionPlan plan =
                Fxml2ResourceInjectionPlan.of(hostText, ElementManipulators.getValueTextRange(host));
        for (Fxml2ResourceInjectionPlan.Fxml2PayloadInjection payload : plan.payloads()) {
            if (payload.range().getStartOffset() == startInHost) return payload.payloadLanguage();
        }
        return null;
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
