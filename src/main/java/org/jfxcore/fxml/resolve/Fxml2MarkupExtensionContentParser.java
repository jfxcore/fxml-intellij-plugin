package org.jfxcore.fxml.resolve;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Splits the content of a markup extension into the sections it assigns.
 *
 * <p>The content of a markup extension is the text after its class name, in brace notation
 * ({@code {StaticResource greeting; formatArguments=Jane, Doe}}) as well as in prefix notation
 * ({@code %greeting; formatArguments=Jane, Doe}).  It consists of a positional value for the
 * default property and of named parameters:
 *
 * <pre>{@code
 * <Label text="{StaticResource greeting; formatArguments=Jane, Doe}"/>
 * }</pre>
 *
 * <p>Sections are separated by {@code ';'} or by a line break.  A comma does <em>not</em> separate
 * them: it separates the values of the section it appears in, which is a value list resolved by
 * {@link Fxml2ValueSequenceParser}.  In the example above, {@code Jane} and {@code Doe} are the two
 * values of {@code formatArguments} rather than a parameter and a stray value.
 *
 * <p>It follows that a parameter can only begin where a section begins.  An assignment that follows
 * a comma belongs to the value list it stands in and is not a parameter of the extension.
 */
public final class Fxml2MarkupExtensionContentParser {

    private Fxml2MarkupExtensionContentParser() {}

    /** A section of the content of a markup extension. */
    public sealed interface Section {
        /** Offset of the section within the parsed content. */
        int offset();
    }

    /**
     * A named parameter, {@code name=value}.
     *
     * @param name        the parameter name
     * @param offset      offset of {@code name} within the parsed content
     * @param value       the value, which may be a comma-separated value list
     * @param valueOffset offset of {@code value} within the parsed content
     * @param assignmentOffset offset of the assignment operator within the parsed content
     */
    public record NamedParameter(@NotNull String name, int offset,
                                 @NotNull String value, int valueOffset, int assignmentOffset) implements Section {}

    /**
     * A value that is not assigned to a named parameter, which supplies the default property of
     * the extension.
     *
     * @param text   the value text, which may be a comma-separated value list
     * @param offset offset of {@code text} within the parsed content
     */
    public record PositionalValue(@NotNull String text, int offset) implements Section {}

    /**
     * Splits {@code content} into its sections.
     *
     * @param content the content of a markup extension, i.e. the text after its class name
     * @return the sections in source order; empty when {@code content} is blank
     */
    public static @NotNull List<Section> parse(@NotNull String content) {
        return parseContent(content).sections();
    }

    public record Content(@NotNull List<Section> sections, @NotNull List<Fxml2TextSpan> separators) {
        public Content {
            sections = List.copyOf(sections);
            separators = List.copyOf(separators);
        }
    }

    /** Returns sections and their top-level semicolon or line-break separators. */
    public static @NotNull Content parseContent(@NotNull String content) {
        List<Section> sections = new ArrayList<>();
        List<Fxml2TextSpan> separators = new ArrayList<>();
        int depth = 0;
        int start = 0;
        var tokens = Fxml2TextScanner.scan(content);
        Set<Integer> assignments = tokens.stream()
                .filter(token -> token.kind() == Fxml2TextScanner.Kind.ASSIGNMENT)
                .map(token -> token.span().start()).collect(Collectors.toSet());
        for (var token : tokens) {
            switch (token.kind()) {
                case OPEN_BRACE, OPEN_PARENTHESIS, OPEN_BRACKET -> depth++;
                case CLOSE_BRACE, CLOSE_PARENTHESIS, CLOSE_BRACKET -> { if (depth > 0) depth--; }
                case SEMICOLON, LINE_BREAK -> {
                    if (depth == 0) {
                        addSection(sections, content, start, token.span().start(), assignments);
                        separators.add(token.span());
                        start = token.span().end();
                    }
                }
                default -> { }
            }
        }
        addSection(sections, content, start, content.length(), assignments);
        return new Content(sections, separators);
    }

    private static void addSection(
            @NotNull List<Section> sections, @NotNull String content, int start, int end,
            @NotNull Set<Integer> assignments) {

        Fxml2TextSpan span = Fxml2TextSpan.trimmed(content, start, end);
        if (span.isEmpty()) return;

        int begin = span.start();
        int finish = span.end();
        int nameEnd = Fxml2TextScanner.identifierEnd(content, begin, finish);
        int afterName = nameEnd;
        while (afterName < finish && Character.isWhitespace(content.charAt(afterName))) afterName++;

        if (nameEnd > begin && assignments.contains(afterName)) {
            int valueStart = afterName + 1;
            while (valueStart < finish && Character.isWhitespace(content.charAt(valueStart))) valueStart++;
            sections.add(new NamedParameter(
                    content.substring(begin, nameEnd), begin,
                    content.substring(valueStart, finish), valueStart, afterName));
            return;
        }

        sections.add(new PositionalValue(content.substring(begin, finish), begin));
    }

}
