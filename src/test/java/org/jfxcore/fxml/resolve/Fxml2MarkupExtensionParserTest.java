package org.jfxcore.fxml.resolve;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Fxml2MarkupExtensionParserTest {
    @ParameterizedTest
    @ValueSource(strings = {"<Map<String, Integer>>", "&lt;Map&lt;String, Integer&gt;&gt;"})
    void genericWhitespaceIsPartOfTheHeader(String arguments) {
        String source = "{  sample.ValueSupplier" + arguments + "  value=ValueSupplier }";
        var extension = Fxml2MarkupExtensionParser.parse(source);
        assertNotNull(extension);
        assertEquals("sample.ValueSupplier", extension.name().textOf(source));
        assertEquals(new Fxml2TextSpan(3, 23), extension.name());
        assertNotNull(extension.typeArguments());
        assertEquals(arguments, extension.typeArguments().textOf(source));
        assertEquals("value=ValueSupplier", extension.content().textOf(source));
        assertTrue(extension.closed());
    }

    @Test
    void partialExtensionsRetainTheirAvailableRanges() {
        String source = "{fx:Observe source=model.title";
        var extension = Fxml2MarkupExtensionParser.parse(source);
        assertNotNull(extension);
        assertEquals("fx:Observe", extension.name().textOf(source));
        assertEquals("source=model.title", extension.content().textOf(source));
        assertFalse(extension.closed());
        var generic = Fxml2MarkupExtensionParser.parse("{ValueSupplier<String");
        assertNotNull(generic);
        assertNotNull(generic.typeArguments());
        assertTrue(generic.content().isEmpty());
    }

    @Test
    void valuesWithoutExtensionNamesHaveNoHeader() {
        assertNull(Fxml2MarkupExtensionParser.parse("literal"));
        assertNull(Fxml2MarkupExtensionParser.parse("{"));
        assertNull(Fxml2MarkupExtensionParser.parse("{ }"));
    }
}
