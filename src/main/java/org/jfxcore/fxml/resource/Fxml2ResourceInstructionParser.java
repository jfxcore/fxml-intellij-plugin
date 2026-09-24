package org.jfxcore.fxml.resource;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2TextSpan;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses one {@code <?resource ?>} processing instruction into a {@link Fxml2ResourceDeclaration}
 * and the diagnostics that go with it.
 *
 * <p>The grammar is the markup language's:
 *
 * <pre>{@code <?resource <name> [<media-type>]:<content>?>}</pre>
 *
 * <p>The colon is mandatory and separates the declaration from the content.  Everything before it
 * is a required name and an optional media type; everything after it is the payload, which
 * {@link Fxml2ResourcePayloadNormalizer} turns into the resource content.
 *
 * <p>Where the compiler stops at the first problem, this parser records the problem and keeps
 * going, so that a declaration with several mistakes shows all of them at once.  Recovery is
 * conservative: parsing stops producing a declaration only when the name itself could not be
 * read, because everything downstream is keyed on the name.
 *
 * <p>Line endings are assumed to be normalized to {@code \n} already, which is what the platform
 * guarantees for document text; the compiler performs that normalization itself because it reads
 * source files directly.
 */
public final class Fxml2ResourceInstructionParser {

    /** The processing-instruction target that declares an embedded resource. */
    public static final String TARGET = "resource";

    private static final String INSTRUCTION_START = "<?";
    private static final String INSTRUCTION_END = "?>";

    private final String source;
    private final Instruction instruction;
    private final List<Fxml2ResourceProblem> problems = new ArrayList<>();

    private Fxml2ResourceInstructionParser(@NotNull String source, @NotNull Instruction instruction) {
        this.source = source;
        this.instruction = instruction;
    }

    /**
     * Parses the resource processing instruction occupying {@code [start, end)} of {@code source}.
     *
     * @return the parse result, or {@code null} when the range is not a resource processing
     *         instruction at all
     */
    public static @Nullable Fxml2ResourceParseResult parseAt(@NotNull String source, int start, int end) {
        Instruction instruction = scanAt(source, start, end);
        return instruction == null ? null : new Fxml2ResourceInstructionParser(source, instruction).parse();
    }

    /**
     * Parses every resource processing instruction in {@code source}, in document order.
     *
     * <p>Instructions with a different target are skipped, as are unterminated ones.
     */
    public static @NotNull List<Fxml2ResourceParseResult> parseAll(@NotNull String source) {
        List<Fxml2ResourceParseResult> results = new ArrayList<>();
        int cursor = 0;

        while (true) {
            int start = source.indexOf(INSTRUCTION_START, cursor);
            if (start < 0) break;

            int end = source.indexOf(INSTRUCTION_END, start + INSTRUCTION_START.length());
            if (end < 0) break;

            cursor = end + INSTRUCTION_END.length();

            Fxml2ResourceParseResult result = parseAt(source, start, cursor);
            if (result != null) {
                results.add(result);
            }
        }

        return results;
    }

    /**
     * Parses the resource instruction containing {@code offset}, including an instruction whose
     * terminator has not been typed yet.
     */
    public static @Nullable Fxml2ResourceParseResult parseContaining(@NotNull String source, int offset) {
        int cursor = 0;
        while (cursor <= offset) {
            int start = source.indexOf(INSTRUCTION_START, cursor);
            if (start < 0 || start > offset) return null;

            int terminator = source.indexOf(INSTRUCTION_END, start + INSTRUCTION_START.length());
            if (terminator >= 0) {
                int end = terminator + INSTRUCTION_END.length();
                if (offset <= end) return parseAt(source, start, end);
                cursor = end;
                continue;
            }

            String completed = source + INSTRUCTION_END;
            return parseAt(completed, start, completed.length());
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Scanning
    // -----------------------------------------------------------------------

    /**
     * The lexical structure of one {@code <?resource ?>} processing instruction: where the parts
     * are, not whether they are valid.
     *
     * @param instruction the span of the whole instruction, from {@code <?} to {@code ?>} inclusive
     * @param body        the span between the target and the {@code ?>} terminator
     * @param colonOffset the offset of the colon separating the declaration from the content,
     *                    or {@code -1} when the body contains none
     */
    private record Instruction(@NotNull Fxml2TextSpan instruction,
                               @NotNull Fxml2TextSpan body,
                               int colonOffset) {}

    /**
     * Scans the single processing instruction occupying {@code [start, end)} of {@code text}.
     *
     * <p>Scanning is deliberately tolerant: an instruction that is malformed or still being typed
     * yields no payload rather than an exception, which lets the injectors fall back to leaving
     * the document alone instead of breaking the XML view of the surrounding markup.
     *
     * @return the scanned instruction, or {@code null} when the range is not a resource
     *         processing instruction
     */
    private static @Nullable Instruction scanAt(@NotNull String text, int start, int end) {
        if (start < 0 || end > text.length()
                || end - start < INSTRUCTION_START.length() + INSTRUCTION_END.length()) {
            return null;
        }
        if (!text.startsWith(INSTRUCTION_START, start)
                || !text.startsWith(INSTRUCTION_END, end - INSTRUCTION_END.length())) {
            return null;
        }

        int targetStart = start + INSTRUCTION_START.length();
        int targetEnd = targetStart + TARGET.length();
        int bodyEnd = end - INSTRUCTION_END.length();
        if (targetEnd > bodyEnd || !text.startsWith(TARGET, targetStart)) return null;

        // The target must be a whole word: "<?resources ...?>" is a different instruction.
        if (targetEnd < bodyEnd && !Fxml2ResourceSyntax.isXmlWhitespace(text.charAt(targetEnd))) return null;

        return new Instruction(
                new Fxml2TextSpan(start, end),
                new Fxml2TextSpan(targetEnd, bodyEnd),
                findContentSeparator(text, targetEnd, bodyEnd));
    }

    /**
     * Returns the offset of the colon that separates the declaration from the content, or
     * {@code -1} when {@code [start, end)} contains none.
     *
     * <p>The scan honors quotes and backslash escapes, so a colon inside a quoted media-type
     * parameter value does not terminate the declaration.  An unterminated quote consumes the
     * rest of the range, which is what makes a half-typed declaration report no payload.
     */
    private static int findContentSeparator(@NotNull String text, int start, int end) {
        char quote = 0;
        boolean escaped = false;

        for (int i = start; i < end; ++i) {
            char character = text.charAt(i);

            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == quote) {
                    quote = 0;
                }
            } else if (character == '\'' || character == '"') {
                quote = character;
            } else if (character == ':') {
                return i;
            }
        }

        return -1;
    }

    // -----------------------------------------------------------------------
    // Parsing
    // -----------------------------------------------------------------------

    private @NotNull Fxml2ResourceParseResult parse() {
        int bodyStart = instruction.body().start();
        int bodyEnd = instruction.body().end();

        if (bodyStart < bodyEnd && !Fxml2ResourceSyntax.isXmlWhitespace(source.charAt(bodyStart))) {
            report(Fxml2ResourceProblemKind.INVALID_DECLARATION, new Fxml2TextSpan(bodyStart, bodyStart + 1));
            return nameless(bodyEnd);
        }

        int cursor = skipXmlWhitespace(bodyStart, bodyEnd);
        if (cursor == bodyEnd) {
            report(Fxml2ResourceProblemKind.MISSING_NAME, emptySpanAt(bodyEnd));
            return nameless(bodyEnd);
        }

        char quote = source.charAt(cursor);
        boolean quoted = quote == '\'' || quote == '"';
        int nameStart;
        int nameEnd;

        if (quoted) {
            nameStart = ++cursor;
            while (cursor < bodyEnd && source.charAt(cursor) != quote) {
                ++cursor;
            }

            if (cursor == bodyEnd) {
                report(Fxml2ResourceProblemKind.INVALID_DECLARATION,
                        new Fxml2TextSpan(nameStart - 1, bodyEnd));
                return nameless(bodyEnd);
            }

            nameEnd = cursor;
            ++cursor;

            if (cursor < bodyEnd
                    && source.charAt(cursor) != ':'
                    && !Fxml2ResourceSyntax.isXmlWhitespace(source.charAt(cursor))) {
                report(Fxml2ResourceProblemKind.INVALID_DECLARATION, new Fxml2TextSpan(cursor, cursor + 1));
            }
        } else {
            nameStart = cursor;
            while (cursor < bodyEnd
                    && source.charAt(cursor) != ':'
                    && !Fxml2ResourceSyntax.isXmlWhitespace(source.charAt(cursor))) {
                ++cursor;
            }

            nameEnd = cursor;
            if (nameStart == nameEnd) {
                report(Fxml2ResourceProblemKind.MISSING_NAME, emptySpanAt(cursor));
                return nameless(bodyEnd);
            }
        }

        Fxml2TextSpan nameSpan = new Fxml2TextSpan(nameStart, nameEnd);
        Fxml2TextSpan quotedNameSpan = quoted
                ? new Fxml2TextSpan(nameStart - 1, nameEnd + 1)
                : nameSpan;

        Fxml2ResourceName name = new Fxml2ResourceName(nameSpan.textOf(source));
        if (!name.isPortable()) {
            report(Fxml2ResourceProblemKind.INVALID_NAME, nameSpan, name.value());
        }

        cursor = skipXmlWhitespace(cursor, bodyEnd);
        int colon = instruction.colonOffset();
        if (colon < 0) {
            report(Fxml2ResourceProblemKind.INVALID_DECLARATION, emptySpanAt(bodyEnd));
            return result(declaration(name, nameSpan, quotedNameSpan, null,
                                      emptySpanAt(bodyEnd), emptySpanAt(bodyEnd), false));
        }

        int mediaEnd = colon;
        while (mediaEnd > cursor && Fxml2ResourceSyntax.isXmlWhitespace(source.charAt(mediaEnd - 1))) {
            --mediaEnd;
        }

        Fxml2TextSpan mediaTypeSpan = new Fxml2TextSpan(cursor, mediaEnd);
        Fxml2ResourceMediaType mediaType = mediaTypeSpan.isEmpty()
                ? null
                : new MediaTypeScanner(name.value(), mediaTypeSpan).parse();

        Fxml2TextSpan payloadSpan = new Fxml2TextSpan(colon + 1, bodyEnd);
        Fxml2ResourceDeclaration declaration =
                declaration(name, nameSpan, quotedNameSpan, mediaType, mediaTypeSpan, payloadSpan, true);
        verifyEncodable(declaration);

        return result(declaration);
    }

    private @NotNull Fxml2ResourceDeclaration declaration(@NotNull Fxml2ResourceName name,
                                                          @NotNull Fxml2TextSpan nameSpan,
                                                          @NotNull Fxml2TextSpan quotedNameSpan,
                                                          @Nullable Fxml2ResourceMediaType mediaType,
                                                          @NotNull Fxml2TextSpan mediaTypeSpan,
                                                          @NotNull Fxml2TextSpan payloadSpan,
                                                          boolean hasContentSeparator) {
        String rawPayload = payloadSpan.textOf(source);
        Fxml2ResourcePayloadLayout layout = Fxml2ResourcePayloadLayout.of(rawPayload);
        Fxml2ResourcePayload payload = Fxml2ResourcePayloadNormalizer.normalize(
                source, payloadSpan.start(), payloadSpan.end(), layout);

        return new Fxml2ResourceDeclaration(
                name, nameSpan, quotedNameSpan, mediaType, mediaTypeSpan, payloadSpan, payload, layout,
                hasContentSeparator);
    }

    /**
     * Reports the first character of the content that the selected charset cannot encode.
     *
     * <p>The check is the compiler's: the content is encoded with a strict encoder, and the
     * position the encoder stops at identifies the offending character.  Nothing is reported when
     * the charset itself is unsupported, because that problem is already on the declaration.
     */
    private void verifyEncodable(@NotNull Fxml2ResourceDeclaration declaration) {
        Charset charset = declaration.charset();
        if (charset == null) return;

        String content = declaration.content();
        if (content.isEmpty()) return;

        CharsetEncoder encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);

        CharBuffer input = CharBuffer.wrap(content);
        long requested = (long)Math.ceil(input.remaining() * (double)encoder.maxBytesPerChar());
        ByteBuffer output = ByteBuffer.allocate(Math.clamp(requested, 1, Integer.MAX_VALUE));

        CoderResult result = encoder.encode(input, output, true);
        if (!result.isError()) {
            result = encoder.flush(output);
        }
        if (!result.isError()) return;

        int offset = input.position();
        Fxml2TextSpan span = offset < content.length()
                ? declaration.payload().sourceSpanOf(offset, offset + Character.charCount(content.codePointAt(offset)))
                : declaration.payloadSpan();

        report(Fxml2ResourceProblemKind.UNREPRESENTABLE_CHARACTER, span,
                declaration.name().value(), charset.name());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private int skipXmlWhitespace(int offset, int end) {
        while (offset < end && Fxml2ResourceSyntax.isXmlWhitespace(source.charAt(offset))) {
            ++offset;
        }
        return offset;
    }

    private static @NotNull Fxml2TextSpan emptySpanAt(int offset) {
        return new Fxml2TextSpan(offset, offset);
    }

    private void report(@NotNull Fxml2ResourceProblemKind kind,
                        @NotNull Fxml2TextSpan span,
                        @NotNull Object @NotNull ... arguments) {
        problems.add(Fxml2ResourceProblem.of(kind, span, arguments));
    }

    private @NotNull Fxml2ResourceParseResult result(@NotNull Fxml2ResourceDeclaration declaration) {
        return new Fxml2ResourceParseResult(instruction.instruction(), declaration, problems);
    }

    /**
     * Returns the result of a declaration no name could be read from, which is an empty
     * declaration positioned where the name would have been.
     */
    private @NotNull Fxml2ResourceParseResult nameless(int bodyEnd) {
        Fxml2TextSpan span = emptySpanAt(bodyEnd);
        return result(declaration(new Fxml2ResourceName(""), span, span, null, span, span, false));
    }

    /**
     * Scans a media type, which is a {@code type/subtype} essence followed by zero or more
     * {@code ; name = value} parameters, with optional horizontal whitespace around the
     * separators and optionally quoted values.
     *
     * <p>The scanner reports at most one grammar problem per media type: once the grammar has
     * broken, further positions say more about the recovery point than about the mistake.
     */
    private final class MediaTypeScanner {

        private final String resourceName;
        private final Fxml2TextSpan span;
        private int offset;

        MediaTypeScanner(@NotNull String resourceName, @NotNull Fxml2TextSpan span) {
            this.resourceName = resourceName;
            this.span = span;
            this.offset = span.start();
        }

        @Nullable Fxml2ResourceMediaType parse() {
            String type = parseToken();
            if (type == null || type.equals("*") || absent('/')) {
                return reportInvalid();
            }

            String subtype = parseToken();
            if (subtype == null || subtype.equals("*")) {
                return reportInvalid();
            }

            List<Fxml2MediaTypeParameter> parameters = new ArrayList<>();
            Set<String> parameterNames = new HashSet<>();

            while (offset < span.end()) {
                skipOptionalWhitespace();
                if (absent(';')) return reportInvalid();

                skipOptionalWhitespace();
                int parameterStart = offset;
                String name = parseToken();
                if (name == null) return reportInvalid();

                skipOptionalWhitespace();
                if (absent('=')) return reportInvalid();

                skipOptionalWhitespace();
                String value = parseParameterValue();
                if (value == null) return reportInvalid();

                Fxml2MediaTypeParameter parameter = new Fxml2MediaTypeParameter(
                        name, value, new Fxml2TextSpan(parameterStart, offset));

                if (parameterNames.add(name.toLowerCase(Locale.ROOT))) {
                    parameters.add(parameter);
                } else {
                    problems.add(Fxml2ResourceProblem.duplicateParameter(parameter, resourceName));
                }
            }

            Fxml2ResourceMediaType mediaType = new Fxml2ResourceMediaType(type, subtype, parameters);
            verifyCharset(mediaType);
            return mediaType;
        }

        private void verifyCharset(@NotNull Fxml2ResourceMediaType mediaType) {
            Fxml2MediaTypeParameter parameter = mediaType.charsetParameter();
            if (parameter == null || mediaType.charset() != null) return;

            report(Fxml2ResourceProblemKind.UNSUPPORTED_CHARSET,
                    parameter.span(), parameter.value(), resourceName);
        }

        /**
         * Reports the media type as malformed, on the character the grammar broke at.
         *
         * <p>When the grammar broke because the media type ended early there is no such character,
         * and the whole media type is highlighted instead: a zero-width highlight would report the
         * problem without showing the user where it is.
         */
        private @Nullable Fxml2ResourceMediaType reportInvalid() {
            int start = Math.min(offset, span.end());
            Fxml2TextSpan reported = start < span.end() ? new Fxml2TextSpan(start, start + 1) : span;

            report(Fxml2ResourceProblemKind.INVALID_MEDIA_TYPE, reported, resourceName);
            return null;
        }

        private @Nullable String parseToken() {
            int start = offset;
            while (offset < span.end() && Fxml2MediaTypeWriter.isTokenCharacter(source.charAt(offset))) {
                ++offset;
            }

            return start == offset ? null : source.substring(start, offset);
        }

        private @Nullable String parseParameterValue() {
            if (offset == span.end()) return null;

            char quote = source.charAt(offset);
            if (quote != '\'' && quote != '"') return parseToken();

            ++offset;
            StringBuilder value = new StringBuilder();
            while (offset < span.end()) {
                char character = source.charAt(offset++);
                if (character == quote) return value.toString();

                if (character == '\\') {
                    if (offset == span.end()) return null;
                    character = source.charAt(offset++);
                }

                if (character == 0x7f || character < 0x20 && character != '\t') return null;

                value.append(character);
            }

            return null;
        }

        private void skipOptionalWhitespace() {
            while (offset < span.end() && (source.charAt(offset) == ' ' || source.charAt(offset) == '\t')) {
                ++offset;
            }
        }

        /** Consumes {@code expected} when it is next, and reports whether it was not there. */
        private boolean absent(char expected) {
            if (offset < span.end() && source.charAt(offset) == expected) {
                ++offset;
                return false;
            }
            return true;
        }
    }
}
