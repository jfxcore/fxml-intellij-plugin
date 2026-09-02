// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.lang.Language;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.modifier.CodeStyleSettingsModifier;
import com.intellij.psi.codeStyle.modifier.CodeStyleStatusBarUIContributor;
import com.intellij.psi.codeStyle.modifier.TransientCodeStyleSettings;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * A rule that decides the indentation step of a language for the files it applies to, standing in
 * for an {@code .editorconfig} section in the tests.
 *
 * <p>{@code EditorConfigCodeStyleSettingsModifier} stays inactive in unit test mode, and what the
 * tests are about is that a step is resolved through the modifiers that apply where the markup
 * lives, whichever rule they come from.  That an {@code .editorconfig} section is one such rule is
 * a property of the platform, not of this plugin.
 */
@SuppressWarnings("UnstableApiUsage") // CodeStyleSettingsModifier is how a rule reaches a file.
final class Fxml2DirectoryCodeStyleRule implements CodeStyleSettingsModifier {

    private final int step;
    private final List<Language> languages;

    private Fxml2DirectoryCodeStyleRule(int step, @NotNull List<Language> languages) {
        this.step = step;
        this.languages = List.copyOf(languages);
    }

    /**
     * Registers a rule that gives {@code languages} an indentation step of {@code step} for every
     * file, and returns the handle that unregisters it again.
     */
    static @NotNull Disposable install(int step, @NotNull List<Language> languages) {
        Disposable disposable = Disposer.newDisposable("fxml2.test.directoryRule");
        CodeStyleSettingsModifier.EP_NAME.getPoint()
                .registerExtension(new Fxml2DirectoryCodeStyleRule(step, languages), disposable);
        return disposable;
    }

    @Override
    public boolean modifySettings(@NotNull TransientCodeStyleSettings settings, @NotNull PsiFile file) {
        boolean modified = false;
        for (Language language : languages) {
            CommonCodeStyleSettings.IndentOptions options =
                    settings.getCommonSettings(language).getIndentOptions();
            if (options == null) continue;
            options.INDENT_SIZE = step;
            modified = true;
        }
        return modified;
    }

    @Override
    public CodeStyleStatusBarUIContributor getStatusBarUiContributor(@NotNull TransientCodeStyleSettings settings) {
        return null;
    }

    @Override
    public @NotNull String getName() {
        return "Test Directory Rule";
    }
}
