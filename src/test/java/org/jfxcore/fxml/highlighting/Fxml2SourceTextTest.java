package org.jfxcore.fxml.highlighting;

import com.intellij.openapi.util.TextRange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Fxml2SourceTextTest {
    @Test
    void decodedOperatorsAndIdentifiersMapToTheirCompleteXmlSource() {
        var source = Fxml2SourceText.decode("model.am&#111;unt &lt;= 20 &amp;&amp; true", 10);
        assertEquals("model.amount <= 20 && true", source.text());
        assertEquals(new TextRange(16, 27), source.range(6, 12));
        int begin = source.text().indexOf("&&");
        assertEquals("&amp;&amp;", "model.am&#111;unt &lt;= 20 &amp;&amp; true".substring(
                source.range(begin, begin + 2).getStartOffset() - 10,
                source.range(begin, begin + 2).getEndOffset() - 10));
    }

    @Test
    void nestedSlicesRetainTheOriginalFileCoordinates() {
        var source = Fxml2SourceText.decode("{Value amount=&#x32;00}", 30);
        var number = source.slice(7, source.text().length() - 1).slice(7, 10);
        assertEquals("200", number.text());
        assertEquals(new TextRange(44, 52), number.range(0, 3));
    }

    @Test
    void incompleteEntitiesAndQuotesArePreserved() {
        var source = Fxml2SourceText.decode("&unknown; &lt &apos;text&apos;", 0);
        assertEquals("&unknown; &lt 'text'", source.text());
    }
}
