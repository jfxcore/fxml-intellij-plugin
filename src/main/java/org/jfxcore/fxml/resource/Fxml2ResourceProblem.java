package org.jfxcore.fxml.resource;

import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resolve.Fxml2TextSpan;

import java.util.List;

/**
 * One diagnostic about a {@code <?resource ?>} declaration, with the span it is reported on.
 *
 * <p>The span is in the coordinates of the text the declaration was parsed from, which is the
 * document text for a standalone file and the host literal text for markup embedded in an
 * annotation value.  Consumers translate it once, at the point where they know which of the two
 * they are looking at.
 *
 * @param kind      what is wrong
 * @param span      where to report it
 * @param arguments the values filled into the diagnostic message
 */
public record Fxml2ResourceProblem(@NotNull Fxml2ResourceProblemKind kind,
                                   @NotNull Fxml2TextSpan span,
                                   @NotNull List<Object> arguments) {

    public Fxml2ResourceProblem {
        arguments = List.copyOf(arguments);
    }

    /** Returns a problem of {@code kind} reported on {@code span}. */
    public static @NotNull Fxml2ResourceProblem of(@NotNull Fxml2ResourceProblemKind kind,
                                                   @NotNull Fxml2TextSpan span,
                                                   @NotNull Object @NotNull ... arguments) {
        return new Fxml2ResourceProblem(kind, span, List.of(arguments));
    }

    /** Returns the diagnostic message. */
    public @NotNull String message() {
        return kind.message(arguments.toArray());
    }
}
