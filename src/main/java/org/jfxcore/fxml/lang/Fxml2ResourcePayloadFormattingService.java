// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.formatting.FormattingRangesInfo;
import com.intellij.formatting.service.FormattingService;
import com.intellij.lang.ImportOptimizer;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Answers a reformat requested from inside the payload of a {@code <?resource ?>} declaration by
 * reformatting the document the declaration is written in.
 *
 * <p>A payload is an editable fragment of the language its media type names, so the caret can sit
 * in it when the user reformats.  The fragment alone cannot answer that request: the content of a
 * payload is formatted in the code style of its own language, but where the content is placed is
 * decided by the declaration that carries it and the markup around that, neither of which the
 * fragment sees.  Formatting the fragment in isolation therefore indents the payload against
 * nothing, and leaves the terminator of the declaration behind at a column of its own.
 *
 * <p>Handing the request to the enclosing document is what makes the caret position irrelevant:
 * reformatting from inside a payload produces exactly what reformatting from anywhere else in the
 * file produces, in a standalone document as well as in markup embedded in an annotation value.
 * The document is formatted as a whole, because that is the request the user made - a reformat
 * with the payload as its subject is a reformat of the declaration that carries it.
 *
 * <p>The service is consulted only for an explicit reformat.  Formatting that happens while typing
 * is left to the fragment, where {@link Fxml2ResourcePayloadEnterHandler} places new lines by the
 * same rules without rewriting the document around them.
 */
public final class Fxml2ResourcePayloadFormattingService implements FormattingService {

    @Override
    public @NotNull Set<Feature> getFeatures() {
        // Not AD_HOC_FORMATTING: a refactoring or a quick fix that touches a payload must not
        // reformat the document around it.
        return Set.of(Feature.FORMAT_FRAGMENTS);
    }

    @Override
    public boolean canFormat(@NotNull PsiFile file) {
        return Fxml2ResourcePayloadFragment.isPayloadFragment(file);
    }

    @Override
    public @NotNull PsiElement formatElement(@NotNull PsiElement element, boolean canChangeWhiteSpaceOnly) {
        formatDocumentOf(element.getContainingFile());
        return element;
    }

    @Override
    public @NotNull PsiElement formatElement(@NotNull PsiElement element,
                                             @NotNull TextRange range,
                                             boolean canChangeWhiteSpaceOnly) {
        formatDocumentOf(element.getContainingFile());
        return element;
    }

    @Override
    public void formatRanges(@NotNull PsiFile file,
                             FormattingRangesInfo rangesInfo,
                             boolean canChangeWhiteSpaceOnly,
                             boolean quickFormat) {
        formatDocumentOf(file);
    }

    @Override
    public @NotNull Set<ImportOptimizer> getImportOptimizers(@NotNull PsiFile file) {
        return Set.of();
    }

    /** Reformats the file {@code fragment} is injected into, which is where the declaration is. */
    private static void formatDocumentOf(@NotNull PsiFile fragment) {
        Project project = fragment.getProject();
        PsiFile hostFile = InjectedLanguageManager.getInstance(project).getTopLevelFile(fragment);
        if (hostFile == fragment) return;

        CodeStyleManager.getInstance(project).reformatText(hostFile, 0, hostFile.getTextLength());
    }
}
