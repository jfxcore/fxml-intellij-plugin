package org.jfxcore.fxml.resolve;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Fxml2TextScannerTest {
    @ParameterizedTest
    @ValueSource(strings = {"'", "\""})
    void quotedTextProtectsDelimitersAndTracksEscapes(String quote) {
        String source = quote + "a;,{}\\" + quote + "b" + quote + ";";
        var quoted = Fxml2TextScanner.quoted(source, 0);
        assertTrue(quoted.closed());
        assertEquals(new Fxml2TextSpan(0, 10), quoted.span());
        assertEquals(List.of(new Fxml2TextSpan(6, 8)), quoted.escapes());
        assertEquals(List.of(Fxml2TextScanner.Kind.STRING, Fxml2TextScanner.Kind.SEMICOLON),
                Fxml2TextScanner.scan(source).stream().map(Fxml2TextScanner.Token::kind).toList());
    }

    @Test
    void partialStringsConsumeTheRemainingText() {
        var quoted = Fxml2TextScanner.quoted("'value\\", 0);
        assertFalse(quoted.closed());
        assertEquals(new Fxml2TextSpan(0, 7), quoted.span());
        assertTrue(quoted.escapes().isEmpty());
    }

    @Test
    void operatorsUseTheirCompleteSourceRanges() {
        String source = "a !== b && c::value .. items";
        assertEquals(List.of("!==", "&&", "::", ".."), Fxml2TextScanner.scan(source).stream()
                .map(token -> token.span().textOf(source)).toList());
    }
}
