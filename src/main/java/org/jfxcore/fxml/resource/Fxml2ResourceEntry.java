package org.jfxcore.fxml.resource;

import com.intellij.openapi.util.TextRange;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2TextSpan;

import java.util.List;

/**
 * One {@code <?resource ?>} directive of a document, bound to the PSI element its text was read
 * from.
 *
 * <p>The anchor is the element whose text the declaration's spans index into: the processing
 * instruction itself in a standalone FXML/2 file, and the injection host literal in markup
 * embedded in a {@code @ComponentView} annotation value.  Binding the spans to an anchor rather
 * than to a file is what lets both document forms share one model: a consumer that wants a range
 * in the file the user is editing asks {@link #fileRangeOf} and does not need to know which form
 * it is looking at.  This is the only place parser spans become physical file ranges.
 *
 * @param result the parse result of the directive
 * @param anchor the element the parse result's spans are relative to
 */
public record Fxml2ResourceEntry(@NotNull Fxml2ResourceParseResult result, @NotNull PsiElement anchor) {

    /** Returns the parsed declaration, which names no resource when it could not be read. */
    public @NotNull Fxml2ResourceDeclaration declaration() {
        return result.declaration();
    }

    /** Returns the diagnostics of this directive, document-wide ones included. */
    public @NotNull List<Fxml2ResourceProblem> problems() {
        return result.problems();
    }

    /** Returns the span of the whole {@code <?resource ?>} instruction, relative to the anchor. */
    public @NotNull Fxml2TextSpan instructionSpan() {
        return result.instructionSpan();
    }

    /** Returns the name this entry declares. */
    public @NotNull Fxml2ResourceName name() {
        return declaration().name();
    }

    /** Returns the file the declaration is written in, which is never an injected fragment. */
    public @NotNull PsiFile declaringFile() {
        return anchor.getContainingFile();
    }

    /** Returns {@code span} as a range in {@link #declaringFile()}. */
    public @NotNull TextRange fileRangeOf(@NotNull Fxml2TextSpan span) {
        return span.shifted(anchor.getTextRange().getStartOffset()).toTextRange();
    }

    /** Returns the range of the resource name in the declaring file, excluding any quotes. */
    public @NotNull TextRange nameRange() {
        return fileRangeOf(declaration().nameSpan());
    }

    /** Returns the name range in {@code file}'s coordinate space, when it contains this document. */
    public @Nullable TextRange nameRangeIn(@NotNull PsiFile file) {
        if (file.equals(declaringFile())) return nameRange();
        if (!(file.getViewProvider().getDocument() instanceof DocumentWindow window)) return null;

        TextRange hostRange = nameRange();
        int start = window.hostToInjected(hostRange.getStartOffset());
        int end = window.hostToInjected(hostRange.getEndOffset());
        return start < 0 || end < start ? null : new TextRange(start, end);
    }

    /** Returns {@code true} when this entry has no diagnostics. */
    public boolean isValid() {
        return result.isValid();
    }
}
