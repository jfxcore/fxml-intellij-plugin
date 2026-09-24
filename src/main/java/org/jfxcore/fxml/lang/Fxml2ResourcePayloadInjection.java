// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiLanguageInjectionHost;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resource.Fxml2ResourceDeclaration;
import org.jfxcore.fxml.resource.Fxml2ResourcePayloadLanguage;

/** One resource payload injection and the identity attached to its injected file. */
record Fxml2ResourcePayloadInjection(@NotNull TextRange range,
                                     @NotNull TextRange rawRange,
                                     @NotNull String prefix,
                                     @NotNull String suffix,
                                     @NotNull Fxml2ResourcePayloadMarker marker) {

    static @NotNull Fxml2ResourcePayloadInjection of(@NotNull String source,
                                                     @NotNull Fxml2ResourceDeclaration declaration) {
        TextRange rawRange = declaration.payloadSpan().toTextRange();
        TextRange range = declaration.injectionSpan(source).toTextRange();
        return new Fxml2ResourcePayloadInjection(
                range,
                rawRange,
                source.substring(rawRange.getStartOffset(), range.getStartOffset()),
                source.substring(range.getEndOffset(), rawRange.getEndOffset()),
                new Fxml2ResourcePayloadMarker(
                        declaration.name(), Fxml2ResourcePayloadLanguage.of(declaration)));
    }

    void inject(@NotNull MultiHostRegistrar registrar, @NotNull PsiLanguageInjectionHost host) {
        registrar.startInjecting(marker.language().languageOrPlainText())
                .putInjectedFileUserData(Fxml2ResourcePayloadMarker.KEY, marker)
                .addPlace(prefix, suffix, host, range)
                .doneInjecting();
    }
}
