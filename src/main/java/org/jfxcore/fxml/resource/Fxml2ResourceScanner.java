package org.jfxcore.fxml.resource;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2TextSpan;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the parts of {@code <?resource ?>} processing instructions in raw text, without any PSI.
 *
 * <p>The scanner is the single authority on where a declaration's body and payload begin and end.
 * Sharing it between the injectors on both document forms and the parser is what guarantees that
 * the XML view of a document and the payload injected into it cannot disagree about where the
 * payload sits.
 *
 * <p>Scanning is deliberately tolerant: an instruction that is malformed or still being typed
 * yields no payload rather than an exception, which lets the injectors fall back to leaving the
 * document alone instead of breaking the XML view of the surrounding markup.
 */
public final class Fxml2ResourceScanner {

    /** The processing-instruction target that declares an embedded resource. */
    public static final String TARGET = "resource";

    private static final String INSTRUCTION_START = "<?";
    private static final String INSTRUCTION_END = "?>";

    private Fxml2ResourceScanner() {}

    /**
     * Returns every resource processing instruction in {@code text}, in document order.
     *
     * <p>Instructions with a different target are skipped, as are unterminated ones.
     */
    public static @NotNull List<Fxml2ResourceInstruction> scanAll(@NotNull String text) {
        List<Fxml2ResourceInstruction> instructions = new ArrayList<>();
        int cursor = 0;

        while (true) {
            int start = text.indexOf(INSTRUCTION_START, cursor);
            if (start < 0) break;

            int end = text.indexOf(INSTRUCTION_END, start + INSTRUCTION_START.length());
            if (end < 0) break;

            Fxml2ResourceInstruction instruction =
                    scanAt(text, start, end + INSTRUCTION_END.length());
            if (instruction != null) {
                instructions.add(instruction);
            }

            cursor = end + INSTRUCTION_END.length();
        }

        return instructions;
    }

    /**
     * Scans the single processing instruction occupying {@code [start, end)} of {@code text}.
     *
     * @return the scanned instruction, or {@code null} when the range is not a resource
     *         processing instruction
     */
    public static @Nullable Fxml2ResourceInstruction scanAt(@NotNull String text, int start, int end) {
        if (start < 0 || end > text.length() || end - start < INSTRUCTION_START.length() + INSTRUCTION_END.length()) {
            return null;
        }
        if (!text.startsWith(INSTRUCTION_START, start) || !text.startsWith(INSTRUCTION_END, end - INSTRUCTION_END.length())) {
            return null;
        }

        int targetStart = start + INSTRUCTION_START.length();
        int targetEnd = targetStart + TARGET.length();
        int bodyEnd = end - INSTRUCTION_END.length();
        if (targetEnd > bodyEnd || !text.startsWith(TARGET, targetStart)) return null;

        // The target must be a whole word: "<?resources ...?>" is a different instruction.
        if (targetEnd < bodyEnd && !isXmlWhitespace(text.charAt(targetEnd))) return null;

        Fxml2TextSpan body = new Fxml2TextSpan(targetEnd, bodyEnd);
        int colon = findDescriptorColon(text, targetEnd, bodyEnd);
        Fxml2TextSpan payload = colon < 0 ? null : new Fxml2TextSpan(colon + 1, bodyEnd);

        return new Fxml2ResourceInstruction(
                new Fxml2TextSpan(start, end),
                new Fxml2TextSpan(targetStart, targetEnd),
                body,
                colon,
                payload);
    }

    /**
     * Returns the offset of the colon that separates the declaration from the content, or
     * {@code -1} when {@code [start, end)} contains none.
     *
     * <p>The scan honors quotes and backslash escapes, so a colon inside a quoted media-type
     * parameter value does not terminate the declaration.  An unterminated quote consumes the
     * rest of the range, which is what makes a half-typed declaration report no payload.
     */
    public static int findDescriptorColon(@NotNull String text, int start, int end) {
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

    /** Returns {@code true} when {@code character} is whitespace as XML defines it. */
    static boolean isXmlWhitespace(char character) {
        return character == ' ' || character == '\t' || character == '\n' || character == '\r';
    }
}
