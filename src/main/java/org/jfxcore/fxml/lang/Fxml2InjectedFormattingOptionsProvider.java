// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.formatting.InjectedFormattingOptionsProvider;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Tells the platform to delegate formatting of an embedded FXML injected fragment
 * back to its host (Java or Kotlin) file.
 *
 * <p>When the user presses Ctrl+Alt+L while the caret is inside an injected XML
 * fragment in a {@code @ComponentView} annotation, IntelliJ would normally reformat
 * only the XML in isolation.  By returning {@code true} here, the request is instead
 * forwarded to the top-level Java / Kotlin file, which then runs the full file
 * formatter followed by {@code Fxml2EmbeddedMarkupFormattingProcessor}.  This ensures
 * consistent formatting regardless of whether the user invokes the action from the
 * Java editor or from inside the injected XML fragment.
 */
public final class Fxml2InjectedFormattingOptionsProvider implements InjectedFormattingOptionsProvider {

    @Override
    public @Nullable Boolean shouldDelegateToTopLevel(@NotNull PsiFile file) {
        if (!Fxml2EmbeddedUtil.isEmbeddedFxml2(file)) return null;
        // Delegate to the Java/Kotlin host file so that Fxml2EmbeddedMarkupFormattingProcessor
        // can apply the correct Java-context base indent.
        return true;
    }
}
