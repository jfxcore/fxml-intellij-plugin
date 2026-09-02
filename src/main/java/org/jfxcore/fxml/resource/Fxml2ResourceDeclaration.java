package org.jfxcore.fxml.resource;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2TextSpan;

import java.nio.charset.Charset;

/**
 * One embedded resource declared by a {@code <?resource ?>} processing instruction.
 *
 * <p>This is the unit every consumer works with: validation reports on its spans, injection uses
 * its editor-facing payload span, references resolve against its name, and the extract and embed
 * intentions rewrite it. All spans are in the coordinates of the text the declaration was parsed
 * from.
 *
 * @param name          the declared name
 * @param nameSpan      the span of the name, excluding any quotes
 * @param mediaType     the declared media type, or {@code null} when the declaration omits it or
 *                      the declared one is malformed
 * @param mediaTypeSpan the span of the media type; empty when the declaration omits it
 * @param payloadSpan   the span of the raw payload, from just after the colon to just before {@code ?>}
 * @param payload       the resource content, with its mapping back onto the source
 * @param instruction   the lexical structure the declaration was parsed from
 */
public record Fxml2ResourceDeclaration(@NotNull Fxml2ResourceName name,
                                       @NotNull Fxml2TextSpan nameSpan,
                                       @Nullable Fxml2ResourceMediaType mediaType,
                                       @NotNull Fxml2TextSpan mediaTypeSpan,
                                       @NotNull Fxml2TextSpan payloadSpan,
                                       @NotNull Fxml2ResourcePayload payload,
                                       @NotNull Fxml2ResourceInstruction instruction) {

    /** Returns the declared media type, or {@code text/plain} when the declaration omits one. */
    public @NotNull Fxml2ResourceMediaType effectiveMediaType() {
        return mediaType != null ? mediaType : Fxml2ResourceMediaType.TEXT_PLAIN;
    }

    /** Returns {@code true} when the declaration writes an explicit media type. */
    public boolean hasExplicitMediaType() {
        return mediaType != null && !mediaTypeSpan.isEmpty();
    }

    /** Returns the charset the payload is encoded with, or {@code null} when it is unsupported. */
    public @Nullable Charset charset() {
        return effectiveMediaType().charset();
    }

    /** Returns the resource content. */
    public @NotNull String content() {
        return payload.text();
    }

    /**
     * Returns the source span presented as the injected resource in the editor.
     *
     * <p>Whitespace beside the colon or terminator is declaration layout and is excluded. When a
     * boundary occupies its own line, the adjacent complete payload line belongs to the resource:
     * its leading indentation and terminating line break are included. This makes every line that
     * contains only resource text an entirely injected line while keeping mixed boundary lines
     * limited to their resource characters.
     */
    public @NotNull Fxml2TextSpan injectionSpan(@NotNull String source) {
        String rawPayload = payloadSpan.textOf(source);
        Fxml2ResourcePayloadLayout layout = Fxml2ResourcePayloadLayout.of(rawPayload);

        int startInPayload = layout.startsOnOwnLine()
                ? rawPayload.indexOf('\n') + 1
                : layout.separator().length();
        int endInPayload = layout.endsOnOwnLine()
                ? rawPayload.lastIndexOf('\n') + 1
                : trimHorizontalWhitespace(rawPayload, startInPayload, rawPayload.length());

        return new Fxml2TextSpan(payloadSpan.start() + startInPayload, payloadSpan.start() + endInPayload);
    }

    private static int trimHorizontalWhitespace(@NotNull String source, int start, int end) {
        while (end > start && isHorizontalWhitespace(source.charAt(end - 1))) {
            --end;
        }
        return end;
    }

    private static boolean isHorizontalWhitespace(char character) {
        return character == ' ' || character == '\t';
    }

    /** Returns the span the name occupies including its quotes, which is what rename replaces. */
    public @NotNull Fxml2TextSpan quotedNameSpan() {
        return name.quoting() == Fxml2ResourceQuoting.UNQUOTED
                ? nameSpan
                : new Fxml2TextSpan(nameSpan.start() - 1, nameSpan.end() + 1);
    }

    /** Returns {@code true} when this declaration resolves a reference written as {@code reference}. */
    public boolean declares(@NotNull String reference) {
        return name.matches(reference);
    }
}
