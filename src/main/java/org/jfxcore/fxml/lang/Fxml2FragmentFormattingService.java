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
 * Answers a reformat requested from an FXML/2 injected fragment by reformatting its owning
 * document.
 *
 * <p>A payload is an editable fragment of the language its media type names, so the caret can sit
 * in it when the user reformats.  The fragment alone cannot answer that request: the content of a
 * payload is formatted in the code style of its own language, but where the content is placed is
 * decided by the declaration that carries it and the markup around that, neither of which the
 * fragment sees.  Formatting the fragment in isolation therefore indents the payload against
 * nothing, and leaves the terminator of the declaration behind at a column of its own.
 *
 * <p>Handing the request to the enclosing document gives the host formatter the structure needed
 * to answer it. A payload request reformats the document as a whole because the payload belongs to
 * its declaration. An embedded-markup request preserves the host editor's selected range, when
 * present, so invoking reformat from an injected editor behaves like invoking it from the host.
 *
 * <p>The service is consulted only for an explicit reformat.  Formatting that happens while typing
 * is left to the fragment, where {@link Fxml2ResourcePayloadEnterHandler} places new lines by the
 * same rules without rewriting the document around them.
 */
public final class Fxml2FragmentFormattingService implements FormattingService {

    @Override
    public @NotNull Set<Feature> getFeatures() {
        // Not AD_HOC_FORMATTING: a refactoring or a quick fix that touches a payload must not
        // reformat the document around it.
        return Set.of(Feature.FORMAT_FRAGMENTS);
    }

    @Override
    public boolean canFormat(@NotNull PsiFile file) {
        return Fxml2ResourcePayloadMarker.of(file) != null || Fxml2EmbeddedUtil.isEmbeddedFxml2(file);
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
        Fxml2ResourcePayloadContext context = Fxml2ResourcePayloadContext.find(fragment, 0);
        PsiFile hostFile = context != null
                ? context.topLevelFile()
                : InjectedLanguageManager.getInstance(project).getTopLevelFile(fragment);
        if (hostFile == fragment) return;

        Fxml2ReformatSnapshotTracker.FormatSnapshot snapshot = Fxml2ReformatSnapshotTracker.getSnapshot();
        TextRange selection = snapshot == null ? null : snapshot.originalSelection();
        TextRange range = selection != null ? selection : TextRange.create(0, hostFile.getTextLength());
        CodeStyleManager.getInstance(project).reformatText(
                hostFile, range.getStartOffset(), range.getEndOffset());
    }
}
