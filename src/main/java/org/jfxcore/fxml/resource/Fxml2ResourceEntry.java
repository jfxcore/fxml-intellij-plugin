package org.jfxcore.fxml.resource;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resolve.Fxml2TextSpan;

import java.util.List;

/**
 * One embedded resource of a document, bound to the PSI element its declaration was read from.
 *
 * <p>The anchor is the element whose text the declaration's spans index into: the processing
 * instruction itself in a standalone FXML/2 file, and the injection host literal in markup
 * embedded in a {@code @ComponentView} annotation value.  Binding the spans to an anchor rather
 * than to a file is what lets both document forms share one model: a consumer that wants a range
 * in the file the user is editing asks {@link #fileRangeOf} and does not need to know which form
 * it is looking at.
 *
 * @param declaration the parsed declaration
 * @param problems    the diagnostics of this declaration, document-wide ones included
 * @param anchor      the element the declaration's spans are relative to
 */
public record Fxml2ResourceEntry(@NotNull Fxml2ResourceDeclaration declaration,
                                 @NotNull List<Fxml2ResourceProblem> problems,
                                 @NotNull PsiElement anchor) {

    public Fxml2ResourceEntry {
        problems = List.copyOf(problems);
    }

    /** Returns the name this entry declares. */
    public @NotNull Fxml2ResourceName name() {
        return declaration.name();
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
        return fileRangeOf(declaration.nameSpan());
    }

    /** Returns {@code true} when this entry has no diagnostics. */
    public boolean isValid() {
        return problems.isEmpty();
    }
}
