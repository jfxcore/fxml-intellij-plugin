// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.application.options.CodeStyle;
import com.intellij.lang.Language;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.modifier.CodeStyleSettingsModifier;
import com.intellij.psi.codeStyle.modifier.TransientCodeStyleSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resource.Fxml2ResourceDeclaration;
import org.jfxcore.fxml.resource.Fxml2ResourceInstruction;
import org.jfxcore.fxml.resource.Fxml2ResourceInstructionParser;
import org.jfxcore.fxml.resource.Fxml2ResourcePayloadLanguage;
import org.jfxcore.fxml.resource.Fxml2ResourceScanner;

import java.util.EnumMap;
import java.util.Map;

/**
 * The indentation steps that apply where a piece of markup is written.
 *
 * <p>Which step a language is indented in is a property of the file, not of the project: an
 * {@code .editorconfig} rule that applies where the file lives overrides the code style configured
 * for the language.  Such a rule is resolved from a path, so the step is asked for with a probe
 * file of the right extension standing in the directory the markup lives in, which is what makes
 * the answer the same one the IDE would give for a real file written there.
 *
 * <p>The rules that apply to a file are contributed by {@link CodeStyleSettingsModifier}
 * extensions, and they are asked here directly rather than through
 * {@link CodeStyle#getIndentOptions(Project, VirtualFile)}.  That entry point reaches a modifier
 * only for a file of the local file system, and only while no local copy of the code style
 * settings is installed, so a probe would never see an {@code .editorconfig} section and every
 * step would silently fall back to the code style configured project-wide for the language.
 *
 * <p>Asking through a probe also makes the answer independent of the code style settings that
 * happen to be installed on the current thread.  Formatting embedded markup runs with a local copy
 * of the settings, and a lookup that starts from settings rather than from a file would see that
 * copy instead of the rules configured for the directory, so embedded markup and a standalone
 * document would disagree about the same payload.
 */
@SuppressWarnings("UnstableApiUsage") // CodeStyleSettingsModifier is the only way to ask what applies to a file.
public final class Fxml2EffectiveIndent {

    /** The base name of the probe files; only their extension and directory are load-bearing. */
    private static final String PROBE_NAME = "_fxml2_indent_probe";

    private Fxml2EffectiveIndent() {}

    /**
     * Returns the step markup is indented in where {@code contextFile} lives.
     *
     * @param contextFile the file the markup is written in, or {@code null} when it does not live
     *                    in the file system, in which case the project-wide XML step applies
     */
    public static @NotNull Fxml2IndentStep ofMarkup(@NotNull Project project,
                                                    @Nullable VirtualFile contextFile) {

        return of(project, contextFile, XMLLanguage.INSTANCE, Fxml2FileType.INSTANCE.getDefaultExtension());
    }

    /**
     * Returns the step the payload of a resource declaration is indented in where
     * {@code contextFile} lives.
     *
     * <p>A payload the IDE has no language for is indented in markup steps: it has no code style
     * of its own to follow, and the lines it is written on are markup lines.
     *
     * @param contextFile     the file the markup is written in, or {@code null} when it does not
     *                        live in the file system
     * @param payloadLanguage the language the media type of the declaration names
     */
    public static @NotNull Fxml2IndentStep ofPayload(@NotNull Project project,
                                                     @Nullable VirtualFile contextFile,
                                                     @NotNull Fxml2ResourcePayloadLanguage payloadLanguage) {

        Language language = payloadLanguage.language();
        if (language == null || payloadLanguage == Fxml2ResourcePayloadLanguage.PLAIN_TEXT) {
            return ofMarkup(project, contextFile);
        }

        return of(project, contextFile, language, payloadLanguage.defaultExtension());
    }

    /**
     * Returns the steps that apply to {@code markup} written in {@code contextFile}: the markup
     * step, and the step of every payload language the markup declares a resource in.
     *
     * <p>Resolving all of them in one place is what allows the answer to be taken before a local
     * copy of the code style settings is installed for a reformat, which is when a lookup can no
     * longer see the rules configured for the file.
     *
     * @param contextFile the file the markup is written in, or {@code null} when it does not live
     *                    in the file system
     * @param markup      the markup text, which is scanned for resource declarations
     */
    public static @NotNull Fxml2IndentSteps stepsFor(@NotNull Project project,
                                                     @Nullable VirtualFile contextFile,
                                                     @NotNull String markup) {

        Map<Fxml2ResourcePayloadLanguage, Fxml2IndentStep> payloads =
                new EnumMap<>(Fxml2ResourcePayloadLanguage.class);

        for (Fxml2ResourceInstruction instruction : Fxml2ResourceScanner.scanAll(markup)) {
            Fxml2ResourceDeclaration declaration =
                    Fxml2ResourceInstructionParser.parse(markup, instruction).declaration();
            if (declaration == null) continue;

            payloads.computeIfAbsent(Fxml2ResourcePayloadLanguage.of(declaration),
                                     language -> ofPayload(project, contextFile, language));
        }

        return new Fxml2IndentSteps(ofMarkup(project, contextFile), payloads);
    }

    private static @NotNull Fxml2IndentStep of(@NotNull Project project,
                                               @Nullable VirtualFile contextFile,
                                               @NotNull Language language,
                                               @NotNull String extension) {

        Fxml2IndentStep configured = ofLanguage(project, language);
        VirtualFile directory = contextFile != null ? contextFile.getParent() : null;
        if (directory == null) return configured;

        VirtualFile probe = new Fxml2ScratchFile(PROBE_NAME + "." + extension, language, "", directory);
        PsiFile probeFile = PsiManager.getInstance(project).findFile(probe);
        if (probeFile == null) return configured;

        CommonCodeStyleSettings.IndentOptions options =
                asWrittenIn(project, probe, probeFile).getCommonSettings(language).getIndentOptions();
        return options != null && options.INDENT_SIZE > 0 ? new Fxml2IndentStep(options.INDENT_SIZE) : configured;
    }

    /**
     * Returns the code style that applies to {@code probeFile} at its current location, which is the
     * project code style with directory-specific rules applied.
     */
    private static @NotNull CodeStyleSettings asWrittenIn(@NotNull Project project,
                                                          @NotNull VirtualFile probe,
                                                          @NotNull PsiFile probeFile) {

        CodeStyleSettings projectSettings = CodeStyle.getSettings(project);
        TransientCodeStyleSettings settings =
                new TransientCodeStyleSettings(probe, project, projectSettings);

        boolean modified = false;
        for (CodeStyleSettingsModifier modifier : CodeStyleSettingsModifier.EP_NAME.getExtensionList()) {
            modified |= modifier.modifySettings(settings, probeFile);
        }

        return modified ? settings : projectSettings;
    }

    /** Returns the step configured project-wide for {@code language}. */
    private static @NotNull Fxml2IndentStep ofLanguage(@NotNull Project project, @NotNull Language language) {
        CodeStyleSettings settings = CodeStyle.getSettings(project);
        CommonCodeStyleSettings.IndentOptions options = settings.getCommonSettings(language).getIndentOptions();
        return new Fxml2IndentStep(options != null && options.INDENT_SIZE > 0
                                   ? options.INDENT_SIZE
                                   : settings.getIndentSize(null));
    }
}
