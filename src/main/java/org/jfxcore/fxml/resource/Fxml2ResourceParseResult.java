package org.jfxcore.fxml.resource;

import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resolve.Fxml2TextSpan;

import java.util.List;

/**
 * The outcome of parsing one {@code <?resource ?>} declaration: what could be understood of it,
 * and everything that is wrong with it.
 *
 * <p>Unlike the compiler, which fails on the first problem, the parser keeps going wherever it
 * can, so that the editor reports all the problems of a declaration at once instead of revealing
 * them one recompile at a time.  A declaration is always produced, even when the name could not
 * be read at all: navigation and completion stay useful in that state, and the diagnostic about
 * the missing name still has a declaration to be reported on.
 *
 * @param instructionSpan the span of the whole instruction, from {@code <?} to {@code ?>} inclusive
 * @param declaration     what could be parsed
 * @param problems        every diagnostic found, in the order they were found
 */
public record Fxml2ResourceParseResult(@NotNull Fxml2TextSpan instructionSpan,
                                       @NotNull Fxml2ResourceDeclaration declaration,
                                       @NotNull List<Fxml2ResourceProblem> problems) {

    public Fxml2ResourceParseResult {
        problems = List.copyOf(problems);
    }

    /** Returns {@code true} when the declaration is free of diagnostics. */
    public boolean isValid() {
        return problems.isEmpty();
    }
}
