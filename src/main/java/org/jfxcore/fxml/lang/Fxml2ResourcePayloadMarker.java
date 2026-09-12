// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resource.Fxml2ResourceName;
import org.jfxcore.fxml.resource.Fxml2ResourcePayloadLanguage;

/** Offset-free identity attached to an injected resource payload. */
record Fxml2ResourcePayloadMarker(@NotNull Fxml2ResourceName name,
                                  @NotNull Fxml2ResourcePayloadLanguage language) {

    static final Key<Fxml2ResourcePayloadMarker> KEY = Key.create("Fxml2ResourcePayloadMarker");

    static @Nullable Fxml2ResourcePayloadMarker of(@NotNull PsiFile file) {
        return file.getUserData(KEY);
    }
}
