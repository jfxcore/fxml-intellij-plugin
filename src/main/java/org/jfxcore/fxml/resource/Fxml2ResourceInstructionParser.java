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

    private final String source;
    private final Fxml2ResourceInstruction instruction;
    private final List<Fxml2ResourceProblem> problems = new ArrayList<>();

    private Fxml2ResourceInstructionParser(@NotNull String source, @NotNull Fxml2ResourceInstruction instruction) {
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
        Fxml2ResourceInstruction instruction = Fxml2ResourceScanner.scanAt(source, start, end);
        return instruction == null ? null : parse(source, instruction);
    }

    /** Parses the already scanned resource processing instruction {@code instruction}. */
    public static @NotNull Fxml2ResourceParseResult parse(@NotNull String source,
                                                          @NotNull Fxml2ResourceInstruction instruction) {
        return new Fxml2ResourceInstructionParser(source, instruction).parse();
    }

    // -----------------------------------------------------------------------
    // Parsing
    // -----------------------------------------------------------------------

    private @NotNull Fxml2ResourceParseResult parse() {
        int bodyStart = instruction.body().start();
        int bodyEnd = instruction.body().end();

        if (bodyStart >= bodyEnd) {
            return failed(Fxml2ResourceProblemKind.MISSING_NAME, emptySpanAt(bodyEnd));
        }

        if (!Fxml2ResourceScanner.isXmlWhitespace(source.charAt(bodyStart))) {
            return failed(Fxml2ResourceProblemKind.INVALID_DECLARATION, new Fxml2TextSpan(bodyStart, bodyStart + 1));
        }

        int cursor = skipXmlWhitespace(bodyStart, bodyEnd);
        if (cursor == bodyEnd) {
            return failed(Fxml2ResourceProblemKind.MISSING_NAME, emptySpanAt(bodyEnd));
        }

        Fxml2ResourceQuoting quoting = Fxml2ResourceQuoting.of(source.charAt(cursor));
        int nameStart;
        int nameEnd;

        if (quoting != null) {
            char quote = source.charAt(cursor);
            nameStart = ++cursor;
            while (cursor < bodyEnd && source.charAt(cursor) != quote) {
                ++cursor;
            }

            if (cursor == bodyEnd) {
                return failed(Fxml2ResourceProblemKind.INVALID_DECLARATION,
                        new Fxml2TextSpan(nameStart - 1, bodyEnd));
            }

            nameEnd = cursor;
            ++cursor;

            if (cursor < bodyEnd
                    && source.charAt(cursor) != ':'
                    && !Fxml2ResourceScanner.isXmlWhitespace(source.charAt(cursor))) {
                report(Fxml2ResourceProblemKind.INVALID_DECLARATION, new Fxml2TextSpan(cursor, cursor + 1));
            }
        } else {
            quoting = Fxml2ResourceQuoting.UNQUOTED;
            nameStart = cursor;
            while (cursor < bodyEnd
                    && source.charAt(cursor) != ':'
                    && !Fxml2ResourceScanner.isXmlWhitespace(source.charAt(cursor))) {
                ++cursor;
            }

            nameEnd = cursor;
            if (nameStart == nameEnd) {
                return failed(Fxml2ResourceProblemKind.MISSING_NAME, emptySpanAt(cursor));
            }
        }

        Fxml2TextSpan nameSpan = new Fxml2TextSpan(nameStart, nameEnd);
        Fxml2ResourceName name = new Fxml2ResourceName(nameSpan.textOf(source), quoting);
        if (!name.isPortable()) {
            report(Fxml2ResourceProblemKind.INVALID_NAME, nameSpan, name.value());
        }

        cursor = skipXmlWhitespace(cursor, bodyEnd);
        int colon = instruction.colonOffset();
        if (colon < 0) {
            report(Fxml2ResourceProblemKind.INVALID_DECLARATION, emptySpanAt(bodyEnd));
            return new Fxml2ResourceParseResult(
                    declaration(name, nameSpan, null, emptySpanAt(bodyEnd), emptySpanAt(bodyEnd)),
                    problems);
        }

        int mediaEnd = colon;
        while (mediaEnd > cursor && Fxml2ResourceScanner.isXmlWhitespace(source.charAt(mediaEnd - 1))) {
            --mediaEnd;
        }

        Fxml2TextSpan mediaTypeSpan = new Fxml2TextSpan(cursor, mediaEnd);
        Fxml2ResourceMediaType mediaType = mediaTypeSpan.isEmpty()
                ? null
                : new MediaTypeScanner(name.value(), mediaTypeSpan).parse();

        Fxml2TextSpan payloadSpan = new Fxml2TextSpan(colon + 1, bodyEnd);
        Fxml2ResourceDeclaration declaration = declaration(name, nameSpan, mediaType, mediaTypeSpan, payloadSpan);
        verifyEncodable(declaration);

        return new Fxml2ResourceParseResult(declaration, problems);
    }

    private @NotNull Fxml2ResourceDeclaration declaration(@NotNull Fxml2ResourceName name,
                                                          @NotNull Fxml2TextSpan nameSpan,
                                                          @Nullable Fxml2ResourceMediaType mediaType,
                                                          @NotNull Fxml2TextSpan mediaTypeSpan,
                                                          @NotNull Fxml2TextSpan payloadSpan) {
        Fxml2ResourcePayload payload =
                Fxml2ResourcePayloadNormalizer.normalize(source, payloadSpan.start(), payloadSpan.end());

        return new Fxml2ResourceDeclaration(
                name, nameSpan, mediaType, mediaTypeSpan, payloadSpan, payload, instruction);
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
        ByteBuffer output = ByteBuffer.allocate((int)Math.min(Integer.MAX_VALUE, Math.max(1, requested)));

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
        while (offset < end && Fxml2ResourceScanner.isXmlWhitespace(source.charAt(offset))) {
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

    private @NotNull Fxml2ResourceParseResult failed(@NotNull Fxml2ResourceProblemKind kind,
                                                     @NotNull Fxml2TextSpan span) {
        report(kind, span);
        return new Fxml2ResourceParseResult(null, problems);
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
            if (type == null || type.equals("*") || !poll('/')) {
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
                if (!poll(';')) return reportInvalid();

                skipOptionalWhitespace();
                int parameterStart = offset;
                String name = parseToken();
                if (name == null) return reportInvalid();

                skipOptionalWhitespace();
                if (!poll('=')) return reportInvalid();

                skipOptionalWhitespace();
                String value = parseParameterValue();
                if (value == null) return reportInvalid();

                Fxml2TextSpan parameterSpan = new Fxml2TextSpan(parameterStart, offset);
                if (!parameterNames.add(name.toLowerCase(Locale.ROOT))) {
                    report(Fxml2ResourceProblemKind.DUPLICATE_MEDIA_TYPE_PARAMETER,
                            parameterSpan, name, resourceName);
                } else {
                    parameters.add(new Fxml2MediaTypeParameter(name, value, parameterSpan));
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

        private boolean poll(char expected) {
            if (offset < span.end() && source.charAt(offset) == expected) {
                ++offset;
                return true;
            }
            return false;
        }
    }
}
