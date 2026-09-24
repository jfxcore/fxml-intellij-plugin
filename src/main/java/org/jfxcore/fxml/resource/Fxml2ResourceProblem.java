package org.jfxcore.fxml.resource;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2TextSpan;

/**
 * One diagnostic about a {@code <?resource ?>} declaration, with the span it is reported on.
 *
 * <p>The span is in the coordinates of the text the declaration was parsed from, which is the
 * document text for a standalone file and the host literal text for markup embedded in an
 * annotation value.  Consumers translate it once, at the point where they know which of the two
 * they are looking at.
 *
 * <p>The message is rendered when the problem is created, because that is where the values it
 * names are known.  What a fix needs beyond the message is carried as a typed value instead.
 *
 * @param kind               what is wrong
 * @param span               where to report it
 * @param message            the diagnostic message
 * @param duplicateParameter the repeated media-type parameter, for a
 *                           {@link Fxml2ResourceProblemKind#DUPLICATE_MEDIA_TYPE_PARAMETER}
 *                           problem, and {@code null} for every other kind
 */
public record Fxml2ResourceProblem(@NotNull Fxml2ResourceProblemKind kind,
                                   @NotNull Fxml2TextSpan span,
                                   @NotNull String message,
                                   @Nullable Fxml2MediaTypeParameter duplicateParameter) {

    /** Returns a problem of {@code kind} reported on {@code span}. */
    public static @NotNull Fxml2ResourceProblem of(@NotNull Fxml2ResourceProblemKind kind,
                                                   @NotNull Fxml2TextSpan span,
                                                   @NotNull Object @NotNull ... arguments) {
        return new Fxml2ResourceProblem(kind, span, kind.message(arguments), null);
    }

    /** Returns a problem reporting that {@code parameter} repeats one already declared. */
    public static @NotNull Fxml2ResourceProblem duplicateParameter(@NotNull Fxml2MediaTypeParameter parameter,
                                                                   @NotNull String resourceName) {
        return new Fxml2ResourceProblem(
                Fxml2ResourceProblemKind.DUPLICATE_MEDIA_TYPE_PARAMETER,
                parameter.span(),
                Fxml2ResourceProblemKind.DUPLICATE_MEDIA_TYPE_PARAMETER.message(parameter.name(), resourceName),
                parameter);
    }
}
