package org.jfxcore.fxml.resource;

import org.jetbrains.annotations.NotNull;

/**
 * Finds usages of an embedded resource name in the text of the document that declares it.
 *
 * <p>A resource is used through one of three spellings: the {@code @name} prefix notation, the
 * {@code {ClassPathResource name}} markup extension, and, inside another resource's payload, a
 * stylesheet {@code @import} or {@code url(...)}.  All three are text in the same document, which
 * is why a scan of the document text finds them all without needing the reference machinery, and
 * without needing to know which of the two document forms it is looking at.
 *
 * <p>The scan is deliberately conservative in one direction only: it may report a usage that a
 * stricter reading would not count, but it never misses one.  That is the right bias for the
 * inspection it serves, which reports an unused declaration: a false "used" is invisible, while a
 * false "unused" would invite the user to delete something that is needed.
 */
public final class Fxml2ResourceUsageScanner {

    /** The markup extension the {@code @} prefix notation is shorthand for. */
    private static final String CLASSPATH_RESOURCE = "ClassPathResource";

    private Fxml2ResourceUsageScanner() {}

    /**
     * Returns {@code true} when {@code text} contains at least one usage of {@code name} that is
     * not the declaration itself.
     *
     * @param text            the document text to scan
     * @param declarationSpan the span of the declaration, which is not counted as a usage
     * @param name            the declared resource name
     */
    public static boolean isUsed(@NotNull String text,
                                 @NotNull org.jfxcore.fxml.resolve.Fxml2TextSpan declarationSpan,
                                 @NotNull String name) {
        if (name.isEmpty()) return false;

        int cursor = 0;
        while (true) {
            int at = text.indexOf(name, cursor);
            if (at < 0) return false;

            cursor = at + name.length();
            if (at >= declarationSpan.start() && at < declarationSpan.end()) continue;
            if (isWholeName(text, at, name) && isUsagePosition(text, at)) return true;
        }
    }

    /**
     * Returns {@code true} when the occurrence at {@code offset} is the whole name rather than
     * part of a longer one, so that {@code styles.css} is not found inside {@code my-styles.css}.
     */
    private static boolean isWholeName(@NotNull String text, int offset, @NotNull String name) {
        int end = offset + name.length();
        return (offset == 0 || !isNameCharacter(text.charAt(offset - 1)))
                && (end == text.length() || !isNameCharacter(text.charAt(end)));
    }

    private static boolean isNameCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '-' || character == '_' || character == '.';
    }

    /**
     * Returns {@code true} when the occurrence at {@code offset} stands where a resource name is
     * read: after an {@code @}, after {@code ClassPathResource}, or inside a {@code url(...)}.
     *
     * <p>An occurrence may be preceded by a quote, which is how a name containing spaces is
     * written in a usage, so quotes are skipped before the preceding text is examined.
     */
    private static boolean isUsagePosition(@NotNull String text, int offset) {
        int before = offset;
        while (before > 0 && isQuote(text.charAt(before - 1))) --before;

        if (before > 0 && text.charAt(before - 1) == '@') return true;
        if (before > 0 && text.charAt(before - 1) == '(') return true;

        // "{ClassPathResource name}" and "@import name": the preceding word decides.
        int wordEnd = before;
        while (wordEnd > 0 && isHorizontalWhitespace(text.charAt(wordEnd - 1))) --wordEnd;
        int wordStart = wordEnd;
        while (wordStart > 0 && Character.isLetterOrDigit(text.charAt(wordStart - 1))) --wordStart;
        if (wordStart == wordEnd) return false;

        String word = text.substring(wordStart, wordEnd);
        return CLASSPATH_RESOURCE.equals(word)
                || ("import".equals(word) && wordStart > 0 && text.charAt(wordStart - 1) == '@');
    }

    private static boolean isQuote(char character) {
        return character == '\'' || character == '"';
    }

    private static boolean isHorizontalWhitespace(char character) {
        return character == ' ' || character == '\t';
    }
}
