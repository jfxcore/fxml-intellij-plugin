// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import org.jfxcore.fxml.resource.Fxml2MediaTypeParameter;
import org.jfxcore.fxml.resource.Fxml2ResourceDeclaration;
import org.jfxcore.fxml.resource.Fxml2ResourceInstructionParser;
import org.jfxcore.fxml.resource.Fxml2ResourceMediaType;
import org.jfxcore.fxml.resource.Fxml2ResourceName;
import org.jfxcore.fxml.resource.Fxml2ResourceParseResult;
import org.jfxcore.fxml.resource.Fxml2ResourcePayloadNormalizer;
import org.jfxcore.fxml.resource.Fxml2ResourceProblem;
import org.jfxcore.fxml.resource.Fxml2ResourceProblemKind;
import org.jfxcore.fxml.resource.Fxml2ResourceQuoting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the editor reads a {@code <?resource ?>} declaration exactly the way the markup
 * language defines it: the same names are rejected, the same media types are malformed, and the
 * same payload normalization is applied.
 *
 * <p>Implementation under test: {@link Fxml2ResourceInstructionParser} and
 * {@link Fxml2ResourcePayloadNormalizer}.
 */
class Fxml2ResourceInstructionParserTest {

    /** A payload character that US-ASCII cannot encode: "Gruesse" spelled with umlaut and eszett. */
    private static final String NON_ASCII_TEXT = "Gr\u00fc\u00dfe";

    /** The first character of {@link #NON_ASCII_TEXT} that US-ASCII cannot encode. */
    private static final char NON_ASCII_CHARACTER = '\u00fc';

    // -----------------------------------------------------------------------
    // Name and media type
    // -----------------------------------------------------------------------

    /** A declaration without a media type is valid and describes a UTF-8 {@code text/plain} resource. */
    @Test
    void omittedMediaTypeDefaultsToPlainTextInUtf8() {
        Fxml2ResourceDeclaration declaration = parseValid("<?resource styles.css:\n    greeting\n?>");

        assertEquals("styles.css", declaration.name().value());
        assertEquals(Fxml2ResourceQuoting.UNQUOTED, declaration.name().quoting());
        assertFalse(declaration.hasExplicitMediaType());
        assertEquals(Fxml2ResourceMediaType.TEXT_PLAIN, declaration.effectiveMediaType());
        assertEquals(StandardCharsets.UTF_8, declaration.charset());
        assertEquals("greeting", declaration.content());
    }

    /** Parameters are read in declaration order, and the charset parameter is found ignoring case. */
    @Test
    void explicitMediaTypeIsParsedWithItsParameters() {
        Fxml2ResourceDeclaration declaration = parseValid(
                "<?resource data.xyz application/x-example;Version=2; note=\"a:b\";CHARSET='UTF-16LE':payload?>");

        Fxml2ResourceMediaType mediaType = declaration.effectiveMediaType();
        assertEquals("application", mediaType.type());
        assertEquals("x-example", mediaType.subtype());
        assertEquals("application/x-example", mediaType.essence());
        assertEquals(List.of("Version", "note", "CHARSET"),
                mediaType.parameters().stream().map(Fxml2MediaTypeParameter::name).toList());
        assertEquals(List.of("2", "a:b", "UTF-16LE"),
                mediaType.parameters().stream().map(Fxml2MediaTypeParameter::value).toList());
        assertEquals(StandardCharsets.UTF_16LE, declaration.charset());
        assertEquals("payload", declaration.content());
    }

    /** A colon inside a quoted parameter value does not terminate the declaration. */
    @Test
    void quotedParameterValueMayContainAColon() {
        assertEquals("body", parseValid("<?resource f.txt text/plain;note=\"a:b\":body?>").content());
    }

    /** Quoting is a property of the declaration text only; it never becomes part of the name. */
    @Test
    void quotedAndBareNamesHaveTheSameIdentity() {
        assertEquals("theme.css", parseValid("<?resource theme.css:text?>").name().value());
        assertEquals("theme.css", parseValid("<?resource \"theme.css\":text?>").name().value());

        Fxml2ResourceDeclaration spaced = parseValid("<?resource \"dark theme.css\":text?>");
        assertEquals("dark theme.css", spaced.name().value());
        assertEquals(Fxml2ResourceQuoting.DOUBLE, spaced.name().quoting());
    }

    /** A name that is not a portable file name is reported, quoting notwithstanding. */
    @ParameterizedTest
    @ValueSource(strings = {
            "", ".", "..", "subdir/file", "subdir\\file", "bad:name", "bad*name", "bad?name",
            "bad\"name", "bad<name", "bad>name", "bad|name", "trailing ", "trailing.", "CON",
            "con.txt", "PRN.log", "AUX", "NUL", "COM1.dat", "COM9", "LPT1", "lpt9.css"
    })
    void nonPortableNamesAreReported(String name) {
        char quote = name.indexOf('"') >= 0 ? '\'' : '"';

        assertProblem(parse("<?resource " + quote + name + quote + ":payload?>"),
                Fxml2ResourceProblemKind.INVALID_NAME);
    }

    /** Control characters are not portable in a file name, the delete character included. */
    @ParameterizedTest
    @ValueSource(chars = {0x01, 0x1f, 0x7f})
    void namesContainingControlCharactersAreReported(char control) {
        String name = "control" + control + "name";

        assertFalse(Fxml2ResourceName.isPortable(name));
        assertProblem(parse("<?resource \"" + name + "\":payload?>"), Fxml2ResourceProblemKind.INVALID_NAME);
    }

    /** A portable name is accepted; the reserved-device rule only applies to the whole stem. */
    @ParameterizedTest
    @ValueSource(strings = {"styles.css", "a", "file.name.with.dots.txt", "UPPER.CSS", "com0.txt", "console.txt"})
    void portableNamesAreAccepted(String name) {
        assertTrue(Fxml2ResourceName.isPortable(name), name);
        assertEquals(name, parseValid("<?resource " + name + ":payload?>").name().value());
    }

    /** A declaration that names no resource is reported as such. */
    @ParameterizedTest
    @ValueSource(strings = {"<?resource?>", "<?resource ?>", "<?resource   \n  ?>", "<?resource :payload?>"})
    void missingNameIsReported(String declaration) {
        assertProblem(parse(declaration), Fxml2ResourceProblemKind.MISSING_NAME);
    }

    /** A media type that does not follow the {@code type/subtype} grammar is reported. */
    @ParameterizedTest
    @ValueSource(strings = {
            " text/plain;:payload",
            " text/:payload",
            " /plain:payload",
            " */plain:payload",
            " text/*:payload",
            " text/plain;charset=:payload"
    })
    void malformedMediaTypesAreReported(String remainder) {
        Fxml2ResourceParseResult result = parse("<?resource file.txt" + remainder + "?>");

        assertProblem(result, Fxml2ResourceProblemKind.INVALID_MEDIA_TYPE);
        assertNotNull(result.declaration(), "the name is still readable");
        assertNull(result.declaration().mediaType(), "a malformed media type is not reported as parsed");
    }

    /** An unterminated quote swallows the colon, leaving a declaration with no content separator. */
    @Test
    void unterminatedDescriptorQuoteIsReported() {
        assertProblem(parse("<?resource file.txt text/plain;charset=\"unterminated:payload?>"),
                Fxml2ResourceProblemKind.INVALID_DECLARATION);
    }

    /** A declaration missing the mandatory colon is reported. */
    @Test
    void missingContentSeparatorIsReported() {
        assertProblem(parse("<?resource file.txt text/plain?>"), Fxml2ResourceProblemKind.INVALID_DECLARATION);
    }

    /** An unterminated name quote is reported, and no declaration is produced. */
    @Test
    void unterminatedNameQuoteIsReported() {
        Fxml2ResourceParseResult result = parse("<?resource \"unterminated:payload?>");

        assertProblem(result, Fxml2ResourceProblemKind.INVALID_DECLARATION);
        assertNull(result.declaration());
    }

    /** Parameter names are unique ignoring case. */
    @ParameterizedTest
    @CsvSource({
            "text/plain;charset=UTF-8;Charset=US-ASCII, Charset",
            "application/x-example;version=1;VERSION=2, VERSION"
    })
    void duplicateMediaTypeParametersAreReportedCaseInsensitively(String mediaType, String duplicateName) {
        Fxml2ResourceProblem problem = assertProblem(
                parse("<?resource file.txt " + mediaType + ":value?>"),
                Fxml2ResourceProblemKind.DUPLICATE_MEDIA_TYPE_PARAMETER);

        assertEquals(List.of(duplicateName, "file.txt"), problem.arguments());
    }

    /** A charset that this JVM does not know, or that is not a legal charset name, is reported. */
    @ParameterizedTest
    @ValueSource(strings = {"x-no-such-charset", "not a charset"})
    void unsupportedCharsetIsReported(String charsetName) {
        Fxml2ResourceParseResult result =
                parse("<?resource file.txt text/plain;charset=\"" + charsetName + "\":value?>");

        assertProblem(result, Fxml2ResourceProblemKind.UNSUPPORTED_CHARSET);
        assertNotNull(result.declaration());
        assertNull(result.declaration().charset());
    }

    /** A payload character the selected charset cannot encode is reported at its position. */
    @Test
    void unrepresentableCharacterIsReportedAtItsPosition() {
        String declaration = "<?resource file.txt text/plain;charset=US-ASCII:" + NON_ASCII_TEXT + "?>";
        int firstNonAscii = declaration.indexOf(NON_ASCII_CHARACTER);

        Fxml2ResourceProblem problem =
                assertProblem(parse(declaration), Fxml2ResourceProblemKind.UNREPRESENTABLE_CHARACTER);

        assertEquals(firstNonAscii, problem.span().start());
        assertEquals(firstNonAscii + 1, problem.span().end());
        assertEquals(List.of("file.txt", "US-ASCII"), problem.arguments());
    }

    /** The same payload is accepted when the declaration selects a charset that can encode it. */
    @Test
    void nonAsciiPayloadIsAcceptedInAnEncodingThatSupportsIt() {
        assertEquals(NON_ASCII_TEXT,
                parseValid("<?resource file.txt text/plain;charset=UTF-8:" + NON_ASCII_TEXT + "?>").content());
    }

    // -----------------------------------------------------------------------
    // Payload normalization
    // -----------------------------------------------------------------------

    /**
     * The layout indentation is removed from every content line; a blank line loses at most the
     * same indentation and keeps any whitespace beyond it.
     */
    @Test
    void multilineLayoutIsRemovedAndBlankLineContentIsPreserved() {
        assertEquals("first\n    second\n\nthird",
                parseValid("<?resource file.txt:\n\t  first\n\t      second\n\t  \n\t  third\n?>").content());
    }

    /** A same-line payload is preserved exactly, spaces around it included. */
    @Test
    void sameLinePayloadIsPreservedExactly() {
        assertEquals("  value ", parseValid("<?resource file.txt:  value ?>").content());
        assertEquals("", parseValid("<?resource file.txt:?>").content());
    }

    /** An intentional trailing blank line stays part of the content. */
    @Test
    void intentionalTrailingNewlineIsPreserved() {
        assertEquals("value\n", parseValid("<?resource file.txt:\n    value\n    \n?>").content());
    }

    /** Content offsets map back to the positions the characters occupy in the document. */
    @Test
    void normalizedContentMapsBackOntoTheSource() {
        String declaration = "<?resource file.txt:\n    alpha\n    beta\n?>";
        Fxml2ResourceDeclaration parsed = parseValid(declaration);

        assertEquals("alpha\nbeta", parsed.content());
        assertEquals(declaration.indexOf("alpha"), parsed.payload().sourceOffset(0));
        assertEquals(declaration.indexOf("beta"), parsed.payload().sourceOffset("alpha\n".length()));
    }

    // -----------------------------------------------------------------------
    // Reindentation
    // -----------------------------------------------------------------------

    /** Laying content out at an indentation and reading it back yields the same content. */
    @ParameterizedTest
    @ValueSource(strings = {
            "value",
            "  leading spaces are content",
            "first\nsecond",
            "first\n    indented\n\nlast",
            "trailing newline\n",
            "",
            ".root {\n    -fx-font-size: 1.1em;\n}"
    })
    void reindentingIsTheInverseOfNormalizing(String content) {
        for (String indent : List.of("", "    ", "\t", "        ")) {
            String declaration = "<?resource file.txt:"
                    + Fxml2ResourcePayloadNormalizer.reindent(content, indent) + "?>";

            assertEquals(content, parseValid(declaration).content(), "round trip at indent '" + indent + "'");
        }
    }

    /** The indentation a payload was laid out at is reported back for a payload that has one. */
    @Test
    void commonIndentIsReported() {
        assertEquals("    ", Fxml2ResourcePayloadNormalizer.commonIndentOf("    a\n    b"));
        assertEquals("", Fxml2ResourcePayloadNormalizer.commonIndentOf("a\n    b"));
        assertEquals("", Fxml2ResourcePayloadNormalizer.commonIndentOf(""));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Fxml2ResourceParseResult parse(String declaration) {
        Fxml2ResourceParseResult result =
                Fxml2ResourceInstructionParser.parseAt(declaration, 0, declaration.length());

        assertNotNull(result, "the text is a resource processing instruction");
        return result;
    }

    private static Fxml2ResourceDeclaration parseValid(String declaration) {
        Fxml2ResourceParseResult result = parse(declaration);

        assertEquals(List.of(), result.problems().stream().map(Fxml2ResourceProblem::message).toList());
        assertTrue(result.isValid());
        assertNotNull(result.declaration());
        return result.declaration();
    }

    private static Fxml2ResourceProblem assertProblem(Fxml2ResourceParseResult result,
                                                      Fxml2ResourceProblemKind expected) {
        Fxml2ResourceProblem problem = result.problems().stream()
                .filter(candidate -> candidate.kind() == expected)
                .findFirst()
                .orElse(null);

        assertNotNull(problem, "expected " + expected + " but got "
                + result.problems().stream().map(Fxml2ResourceProblem::kind).toList());
        assertSame(expected, problem.kind());
        return problem;
    }
}
