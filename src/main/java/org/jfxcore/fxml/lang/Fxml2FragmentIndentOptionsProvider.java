// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.injected.editor.VirtualFileWindow;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings.IndentOptions;
import com.intellij.psi.codeStyle.FileIndentOptionsProvider;
import com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resource.Fxml2ResourcePayloadLanguage;

/**
 * Gives a fragment editor of FXML/2 markup the indentation step of the document the fragment
 * belongs to.
 *
 * <p>"Edit fragment" copies the injected fragment into a temporary file of its own to allow editing
 * as a document of its language. Because this temporary file lacks a parent directory, standard
 * directory-specific rules (such as those in {@code .editorconfig}) do not apply directly, which would
 * cause the editor to fall back to project-wide indentation settings. A line typed in the fragment
 * editor would then land at an incorrect indentation column, which would be written back into the
 * original document upon closing the editor.
 *
 * <p>The step is therefore resolved for the host the fragment came from, exactly as
 * {@link Fxml2ResourcePayloadFormattingProcessor} resolves it when the same payload is reformatted
 * in place.  A markup fragment is indented in the markup step, a payload fragment in the step of
 * the language its media type names.
 */
public final class Fxml2FragmentIndentOptionsProvider extends FileIndentOptionsProvider {

    @Override
    public @Nullable IndentOptions getIndentOptions(@NotNull Project project,
                                                    @NotNull CodeStyleSettings settings,
                                                    @NotNull VirtualFile file) {

        if (!(file instanceof LightVirtualFile copy)) return null;
        if (!(copy.getOriginalFile() instanceof VirtualFileWindow window)) return null;

        VirtualFile injected = (VirtualFile)window;
        PsiFile injectedFile = PsiManager.getInstance(project).findFile(injected);
        if (injectedFile == null) return null;

        VirtualFile hostFile = window.getDelegate();
        Fxml2IndentStep step;
        if (Fxml2EmbeddedUtil.isEmbeddedFxml2(injectedFile)) {
            step = Fxml2EffectiveIndent.ofMarkup(project, hostFile);
        }
        else {
            Fxml2ResourcePayloadLanguage payloadLanguage = Fxml2ResourcePayloadFragment.languageOf(injectedFile);
            if (payloadLanguage == null) return null;
            step = Fxml2EffectiveIndent.ofPayload(project, hostFile, payloadLanguage);
        }

        IndentOptions options = new IndentOptions();
        options.copyFrom(settings.getCommonSettings(injectedFile.getLanguage()).getIndentOptions());
        options.INDENT_SIZE = step.width();
        return options;
    }
}
