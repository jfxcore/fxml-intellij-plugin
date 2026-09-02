// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import org.jfxcore.fxml.resource.Fxml2ResourcePayloadLayout;
import org.jfxcore.fxml.resource.Fxml2ResourcePayloadNormalizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the shape a declaration writes its payload in, which is what reformatting preserves
 * while it rewrites the content between.
 *
 * <p>Implementation under test: {@link Fxml2ResourcePayloadLayout}.
 */
class Fxml2ResourcePayloadLayoutTest {

    // -----------------------------------------------------------------------
    // Reading the shape off a raw payload
    // -----------------------------------------------------------------------

    /** A payload that follows the colon on the same line continues the declaration line. */
    @Test
    void payloadAfterTheColonContinuesTheDeclarationLine() {
        Fxml2ResourcePayloadLayout layout = Fxml2ResourcePayloadLayout.of(" .rule {\n  a: b;\n}");

        assertFalse(layout.startsOnOwnLine());
        assertFalse(layout.endsOnOwnLine());
        assertEquals(" ", layout.separator());
    }

    /** The whitespace between the colon and the payload is the separator, tabs included. */
    @Test
    void separatorIsTheWhitespaceAfterTheColon() {
        assertEquals("", Fxml2ResourcePayloadLayout.of(".rule {}").separator());
        assertEquals("   ", Fxml2ResourcePayloadLayout.of("   .rule {}").separator());
        assertEquals("\t", Fxml2ResourcePayloadLayout.of("\t.rule {}").separator());
    }

    /** A payload that starts below the declaration has no separator to preserve. */
    @Test
    void payloadBelowTheDeclarationStartsOnItsOwnLine() {
        Fxml2ResourcePayloadLayout layout = Fxml2ResourcePayloadLayout.of("  \n  .rule {\n  }\n");

        assertTrue(layout.startsOnOwnLine());
        assertTrue(layout.endsOnOwnLine());
        assertEquals("", layout.separator());
    }

    /** A terminator that follows the payload directly does not sit on a line of its own. */
    @Test
    void terminatorFollowingThePayloadIsNotOnItsOwnLine() {
        assertFalse(Fxml2ResourcePayloadLayout.of("\n  .rule {\n  }").endsOnOwnLine());
        assertTrue(Fxml2ResourcePayloadLayout.of("\n  .rule {\n  }\n  ").endsOnOwnLine());
    }

    // -----------------------------------------------------------------------
    // Writing content back into the shape
    // -----------------------------------------------------------------------

    /** Content written on its own lines is indented at the payload indentation. */
    @Test
    void contentOnOwnLinesIsWrittenAtThePayloadIndentation() {
        String payload = Fxml2ResourcePayloadLayout.ON_OWN_LINES
                .write(".rule {\n  a: b;\n}", "  ", "    ");

        assertEquals("\n    .rule {\n      a: b;\n    }\n  ", payload);
    }

    /** Content that continues the declaration line starts there and follows the declaration. */
    @Test
    void contentOnTheDeclarationLineFollowsTheDeclaration() {
        String payload = new Fxml2ResourcePayloadLayout(" ", false, false)
                .write(".rule {\n  a: b;\n}", "  ", "    ");

        assertEquals(" .rule {\n    a: b;\n  }", payload);
    }

    /** A blank line stays blank, so that laying content out never writes trailing whitespace. */
    @Test
    void blankLinesStayBlank() {
        String payload = Fxml2ResourcePayloadLayout.ON_OWN_LINES.write("a\n\nb", "", "  ");

        assertEquals("\n  a\n\n  b\n", payload);
    }

    /** The separator is layout, not content, and is not handed to the payload language. */
    @Test
    void separatorIsNotPartOfTheContent() {
        Fxml2ResourcePayloadLayout layout = Fxml2ResourcePayloadLayout.of("   .rule {}");

        assertEquals(".rule {}", layout.withoutSeparator("   .rule {}"));
    }

    // -----------------------------------------------------------------------
    // Relation to normalization
    // -----------------------------------------------------------------------

    /** Writing content on its own lines is what normalization reads back as that content. */
    @Test
    void writingOnOwnLinesIsTheInverseOfNormalizing() {
        String content = ".rule {\n  a: b;\n}";
        String payload = Fxml2ResourcePayloadLayout.ON_OWN_LINES.write(content, "    ", "    ");

        assertEquals(content, Fxml2ResourcePayloadNormalizer.normalize(payload, 0, payload.length()).text());
    }
}
