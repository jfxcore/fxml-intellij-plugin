// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.resource;

import org.jetbrains.annotations.NotNull;

/**
 * The shape a declaration writes its payload in.
 *
 * <p>A payload either continues the declaration line or starts on a line of its own, and the
 * {@code ?>} terminator either follows the payload directly or sits on a line of its own.  That
 * shape is a property of the declaration rather than of the resource: it is what the author chose,
 * and reformatting preserves it while rewriting the content between.
 *
 * <p>{@link #write} is the counterpart of {@link Fxml2ResourcePayloadNormalizer#normalize}: given
 * the content of a resource, it produces the raw payload text a declaration carries between its
 * colon and its terminator, laid out in this shape and at a given indentation.
 *
 * @param separator       the horizontal whitespace between the colon and a payload that continues
 *                        the declaration line; empty for a payload that starts on its own line,
 *                        where whatever follows the colon is trailing whitespace
 * @param startsOnOwnLine whether the payload starts on the line below the declaration
 * @param endsOnOwnLine   whether the terminator sits on a line of its own
 */
public record Fxml2ResourcePayloadLayout(@NotNull String separator,
                                         boolean startsOnOwnLine,
                                         boolean endsOnOwnLine) {

    /** The shape of a payload written on lines of its own, which is what multi-line content gets. */
    public static final Fxml2ResourcePayloadLayout ON_OWN_LINES =
            new Fxml2ResourcePayloadLayout("", true, true);

    public Fxml2ResourcePayloadLayout {
        if (startsOnOwnLine && !separator.isEmpty()) {
            throw new IllegalArgumentException("a payload on its own line has no separator: " + separator);
        }
    }

    /** Returns the shape {@code rawPayload} is written in. */
    public static @NotNull Fxml2ResourcePayloadLayout of(@NotNull String rawPayload) {
        int separatorEnd = 0;
        while (separatorEnd < rawPayload.length() && isHorizontalWhitespace(rawPayload.charAt(separatorEnd))) {
            ++separatorEnd;
        }

        boolean startsOnOwnLine = separatorEnd < rawPayload.length() && rawPayload.charAt(separatorEnd) == '\n';
        int lastLineBreak = rawPayload.lastIndexOf('\n');
        boolean endsOnOwnLine = lastLineBreak >= 0
                && isHorizontalWhitespace(rawPayload, lastLineBreak + 1, rawPayload.length());

        return new Fxml2ResourcePayloadLayout(startsOnOwnLine ? "" : rawPayload.substring(0, separatorEnd),
                                              startsOnOwnLine,
                                              endsOnOwnLine);
    }

    /**
     * Returns {@code content} without the separator, which is the text of the resource as a
     * document of its own: the separator is layout the declaration line carries, not content the
     * payload language sees.
     */
    public @NotNull String withoutSeparator(@NotNull String content) {
        return content.startsWith(separator) ? content.substring(separator.length()) : content.stripLeading();
    }

    /**
     * Returns the raw payload text that writes {@code content} in this shape.
     *
     * @param content            the resource content
     * @param declarationIndent  the indentation the declaration itself starts at
     * @param payloadIndent      the indentation a payload on its own lines is written at
     */
    public @NotNull String write(@NotNull String content,
                                 @NotNull String declarationIndent,
                                 @NotNull String payloadIndent) {

        String bodyIndent = startsOnOwnLine ? payloadIndent : declarationIndent;
        StringBuilder result = new StringBuilder(separator);
        if (startsOnOwnLine) result.append('\n');

        int start = 0;
        for (int line = 0; start <= content.length(); ++line) {
            int lineBreak = content.indexOf('\n', start);
            int lineEnd = lineBreak < 0 ? content.length() : lineBreak;

            if (line > 0) result.append('\n');
            // A blank line stays blank so that laying content out never writes trailing whitespace.
            if (!isBlank(content, start, lineEnd)) {
                // The first line of a payload that continues the declaration line is already in
                // place; every other line starts at the indentation the payload is written at.
                if (line > 0 || startsOnOwnLine) result.append(bodyIndent);
                result.append(content, start, lineEnd);
            }

            if (lineBreak < 0) break;
            start = lineBreak + 1;
        }

        if (endsOnOwnLine) result.append('\n').append(declarationIndent);
        return result.toString();
    }

    private static boolean isBlank(@NotNull String text, int start, int end) {
        for (int index = start; index < end; ++index) {
            if (!Character.isWhitespace(text.charAt(index))) return false;
        }
        return true;
    }

    private static boolean isHorizontalWhitespace(@NotNull String text, int start, int end) {
        for (int index = start; index < end; ++index) {
            if (!isHorizontalWhitespace(text.charAt(index))) return false;
        }
        return true;
    }

    private static boolean isHorizontalWhitespace(char character) {
        return character == ' ' || character == '\t';
    }
}
