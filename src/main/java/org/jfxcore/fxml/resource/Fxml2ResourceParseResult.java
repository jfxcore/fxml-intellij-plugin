package org.jfxcore.fxml.resource;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The outcome of parsing one {@code <?resource ?>} declaration: what could be understood of it,
 * and everything that is wrong with it.
 *
 * <p>Unlike the compiler, which fails on the first problem, the parser keeps going wherever it
 * can, so that the editor reports all the problems of a declaration at once instead of revealing
 * them one recompile at a time.  A declaration is present whenever the name could be read, even
 * when later parts are malformed, because navigation and completion stay useful in that state.
 *
 * @param declaration what could be parsed, or {@code null} when not even the name could be read
 * @param problems    every diagnostic found, in the order they were found
 */
public record Fxml2ResourceParseResult(@Nullable Fxml2ResourceDeclaration declaration,
                                       @NotNull List<Fxml2ResourceProblem> problems) {

    public Fxml2ResourceParseResult {
        problems = List.copyOf(problems);
    }

    /** Returns {@code true} when the declaration is free of diagnostics. */
    public boolean isValid() {
        return declaration != null && problems.isEmpty();
    }
}
