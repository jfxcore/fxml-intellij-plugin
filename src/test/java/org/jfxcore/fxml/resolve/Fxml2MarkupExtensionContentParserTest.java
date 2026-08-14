package org.jfxcore.fxml.resolve;

import org.jfxcore.fxml.resolve.Fxml2MarkupExtensionContentParser.NamedParameter;
import org.jfxcore.fxml.resolve.Fxml2MarkupExtensionContentParser.PositionalValue;
import org.jfxcore.fxml.resolve.Fxml2MarkupExtensionContentParser.Section;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the section structure of markup extension content: {@code ';'} and a line break
 * separate the sections, whereas a comma separates the values within a section.
 */
class Fxml2MarkupExtensionContentParserTest {

    @Test
    void blankContentHasNoSections() {
        assertTrue(Fxml2MarkupExtensionContentParser.parse("   ").isEmpty());
    }

    @Test
    void positionalValueIsASectionOfItsOwn() {
        List<Section> sections = Fxml2MarkupExtensionContentParser.parse("greeting");
        assertEquals(1, sections.size());
        PositionalValue value = assertInstanceOf(PositionalValue.class, sections.getFirst());
        assertEquals("greeting", value.text());
        assertEquals(0, value.offset());
    }

    @Test
    void semicolonSeparatesSections() {
        List<Section> sections = Fxml2MarkupExtensionContentParser.parse("greeting; count=2");
        assertEquals(2, sections.size());
        assertInstanceOf(PositionalValue.class, sections.getFirst());
        NamedParameter param = assertInstanceOf(NamedParameter.class, sections.get(1));
        assertEquals("count", param.name());
        assertEquals(10, param.offset());
        assertEquals("2", param.value());
        assertEquals(16, param.valueOffset());
    }

    @Test
    void lineBreakSeparatesSections() {
        List<Section> sections = Fxml2MarkupExtensionContentParser.parse("value=1\nfactor=2");
        assertEquals(2, sections.size());
        assertEquals("value", assertInstanceOf(NamedParameter.class, sections.getFirst()).name());
        assertEquals("factor", assertInstanceOf(NamedParameter.class, sections.get(1)).name());
    }

    /** A comma builds the value list of the parameter it follows, so it starts no new section. */
    @Test
    void commaKeepsTheValuesInOneSection() {
        List<Section> sections = Fxml2MarkupExtensionContentParser.parse("formatArguments=Jane, Doe");
        assertEquals(1, sections.size());
        NamedParameter param = assertInstanceOf(NamedParameter.class, sections.getFirst());
        assertEquals("formatArguments", param.name());
        assertEquals("Jane, Doe", param.value());
    }

    /** An assignment after a comma is part of the value list, not a parameter of its own. */
    @Test
    void assignmentAfterACommaStaysInTheValue() {
        List<Section> sections = Fxml2MarkupExtensionContentParser.parse("value=6, factor=2");
        assertEquals(1, sections.size());
        NamedParameter param = assertInstanceOf(NamedParameter.class, sections.getFirst());
        assertEquals("value", param.name());
        assertEquals("6, factor=2", param.value());
    }

    /** A separator inside a nested construct or a string literal does not end a section. */
    @Test
    void nestedSeparatorsAreNotSectionSeparators() {
        List<Section> sections = Fxml2MarkupExtensionContentParser.parse(
                "text={StaticResource a; count=1}; format='a;b'");
        assertEquals(2, sections.size());
        assertEquals("{StaticResource a; count=1}",
                assertInstanceOf(NamedParameter.class, sections.getFirst()).value());
        assertEquals("'a;b'",
                assertInstanceOf(NamedParameter.class, sections.get(1)).value());
    }

    /** A name that is not followed by '=' supplies the default property. */
    @Test
    void identifierWithoutAssignmentIsAPositionalValue() {
        List<Section> sections = Fxml2MarkupExtensionContentParser.parse("greeting; formatArguments");
        assertEquals(2, sections.size());
        assertEquals("formatArguments",
                assertInstanceOf(PositionalValue.class, sections.get(1)).text());
    }
}
