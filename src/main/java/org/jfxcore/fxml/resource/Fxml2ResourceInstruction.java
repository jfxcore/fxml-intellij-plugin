package org.jfxcore.fxml.resource;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2TextSpan;

/**
 * The lexical structure of one {@code <?resource ?>} processing instruction, as found by
 * {@link Fxml2ResourceScanner}.
 *
 * <p>This is the purely positional view of a declaration: it says where the parts are, not
 * whether they are valid.  {@link Fxml2ResourceInstructionParser} turns it into a
 * {@link Fxml2ResourceDeclaration} and the diagnostics that go with it.
 *
 * @param instruction the span of the whole instruction, from {@code <?} to {@code ?>} inclusive
 * @param target      the span of the {@code resource} target
 * @param body        the span between the target and the {@code ?>} terminator
 * @param colonOffset the offset of the colon separating the declaration from the content,
 *                    or {@code -1} when the body contains none
 * @param payload     the span of the raw payload, or {@code null} when the body has no colon
 */
public record Fxml2ResourceInstruction(@NotNull Fxml2TextSpan instruction,
                                       @NotNull Fxml2TextSpan target,
                                       @NotNull Fxml2TextSpan body,
                                       int colonOffset,
                                       @Nullable Fxml2TextSpan payload) {

    /**
     * Returns {@code true} when the instruction is complete enough to carry a payload, which is
     * the condition for injecting a language into it.
     */
    public boolean hasPayload() {
        return payload != null;
    }
}
