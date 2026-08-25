// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.lang.Language;
import com.intellij.lang.LanguageFormatting;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resource.Fxml2ResourcePayloadLanguage;

/**
 * Formats the content of a resource as a document of the language its media type names.
 *
 * <p>Which languages this can do depends on the IDE: CSS, the media type this feature exists for,
 * is not bundled with IntelliJ IDEA Community, and a language the IDE does not have - or has no
 * formatter for - simply reports back that there is nothing to format, which leaves the payload as
 * its author wrote it.  The content is formatted in the code style of its own language rather than
 * in the markup code style, because it is a document of that language; where it is then placed in
 * the declaration is a markup decision, made by the caller.
 *
 * <p>Content that does not parse is left alone as well: a payload is edited in place, and
 * reformatting a fragment that is still being written would rearrange text whose structure is not
 * yet known.
 */
final class Fxml2ResourcePayloadFormatter {

    /** The base name of the file the content is formatted in; the extension names the language. */
    private static final String SCRATCH_FILE_NAME = "_fxml2_resource_payload";

    private Fxml2ResourcePayloadFormatter() {}

    /**
     * Returns {@code content} formatted as a document of {@code payloadLanguage}, or {@code null}
     * when this IDE cannot format that language or the content does not parse.
     *
     * @param project         the project whose code style settings apply
     * @param payloadLanguage the language the media type of the declaration names
     * @param content         the resource content
     * @param directory       the directory the declaration is written in, which is what the code
     *                        style configured for the payload language is resolved against; may be
     *                        {@code null} when the markup does not live in the file system
     */
    static @Nullable String format(@NotNull Project project,
                                   @NotNull Fxml2ResourcePayloadLanguage payloadLanguage,
                                   @NotNull String content,
                                   @Nullable VirtualFile directory) {

        if (payloadLanguage == Fxml2ResourcePayloadLanguage.PLAIN_TEXT) return null;
        if (content.isBlank()) return null;

        Language language = payloadLanguage.language();
        if (language == null) return null;
        if (LanguageFormatting.INSTANCE.forLanguage(language) == null) return null;

        String name = SCRATCH_FILE_NAME + "." + payloadLanguage.defaultExtension();
        VirtualFile file = new Fxml2ScratchFile(name, language, content, directory);
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (psiFile == null || PsiTreeUtil.hasErrorElements(psiFile)) return null;

        CodeStyleManager.getInstance(project).reformatText(psiFile, 0, psiFile.getTextLength());

        // The content is anchored at column zero: the declaration decides where it is placed, and
        // any indentation the formatter leaves at the document edges would be added on top of that.
        String formatted = psiFile.getText().strip();
        return formatted.isEmpty() ? null : formatted;
    }
}
