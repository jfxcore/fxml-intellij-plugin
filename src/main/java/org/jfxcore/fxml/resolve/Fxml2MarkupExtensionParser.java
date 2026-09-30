package org.jfxcore.fxml.resolve;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Source ranges of a brace-form extension's name, type arguments, and content. */
public final class Fxml2MarkupExtensionParser {
    private Fxml2MarkupExtensionParser() {}

    public record Extension(@NotNull Fxml2TextSpan name,
                            @Nullable Fxml2TextSpan typeArguments,
                            @NotNull Fxml2TextSpan content, boolean closed) {}

    /** Parses complete or partially edited markup, in raw or XML-escaped form. */
    public static @Nullable Extension parse(@NotNull String source) {
        if (!source.startsWith("{")) return null;
        boolean closed = source.endsWith("}");
        int limit = source.length() - (closed ? 1 : 0);
        var inner = Fxml2TextSpan.trimmed(source, 1, limit);
        int start = inner.start();
        int end = Fxml2TextScanner.identifierEnd(source, start, inner.end());
        if (end == start) return null;
        while (end < inner.end() && (source.charAt(end) == '.' || source.charAt(end) == ':')) {
            int next = Fxml2TextScanner.identifierEnd(source, end + 1, inner.end());
            if (next == end + 1) break;
            end = next;
        }
        Fxml2TextSpan name = new Fxml2TextSpan(start, end);
        Fxml2TextSpan arguments = null;
        int openingLength = Fxml2TypeArgumentParser.openingBracketLength(source, end);
        if (openingLength > 0) {
            int closing = Fxml2TypeArgumentParser.findClosingBracket(source, end + openingLength);
            if (closing < 0) {
                arguments = new Fxml2TextSpan(end, inner.end());
                end = inner.end();
            } else {
                int closingLength = source.startsWith("&gt;", closing) ? 4 : 1;
                arguments = new Fxml2TextSpan(end, closing + closingLength);
                end = arguments.end();
            }
        }
        return new Extension(name, arguments, Fxml2TextSpan.trimmed(source, end, limit), closed);
    }
}
