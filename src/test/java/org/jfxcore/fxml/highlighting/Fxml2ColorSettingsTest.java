package org.jfxcore.fxml.highlighting;

import com.intellij.ide.highlighter.JavaHighlightingColors;
import com.intellij.openapi.editor.XmlHighlighterColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.markup.TextAttributes;
import org.jfxcore.fxml.Fxml2TestBase;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Fxml2ColorSettingsTest extends Fxml2TestBase {
    @Test
    void markupRolesFollowCustomizedXmlAttributesAndAllowIndependentOverrides() {
        var scheme = (EditorColorsScheme)EditorColorsManager.getInstance().getGlobalScheme().clone();
        var tagAttributes = new TextAttributes(Color.MAGENTA, null, null, null, Font.BOLD);
        scheme.setAttributes(XmlHighlighterColors.XML_TAG_NAME, tagAttributes);
        for (var role : new Fxml2SemanticRole[] {Fxml2SemanticRole.TYPE, Fxml2SemanticRole.INTERFACE,
                Fxml2SemanticRole.ENUM, Fxml2SemanticRole.TYPE_PARAMETER, Fxml2SemanticRole.INTRINSIC}) {
            assertEquals(XmlHighlighterColors.XML_TAG_NAME, role.key().getFallbackAttributeKey());
            assertEquals(tagAttributes, scheme.getAttributes(role.key()));
        }
        var attributeAttributes = new TextAttributes(Color.BLUE, null, null, null, Font.PLAIN);
        scheme.setAttributes(XmlHighlighterColors.XML_ATTRIBUTE_NAME, attributeAttributes);
        assertEquals(attributeAttributes, scheme.getAttributes(Fxml2SemanticRole.PROPERTY_ASSIGNMENT.key()));
        assertEquals(XmlHighlighterColors.XML_ATTRIBUTE_NAME,
                Fxml2SemanticRole.INTRINSIC_ATTRIBUTE.key().getFallbackAttributeKey());
        assertEquals(attributeAttributes, scheme.getAttributes(Fxml2SemanticRole.INTRINSIC_ATTRIBUTE.key()));
        var fxmlAttributes = new TextAttributes(Color.ORANGE, null, null, null, Font.ITALIC);
        scheme.setAttributes(Fxml2SemanticRole.TYPE.key(), fxmlAttributes);
        assertEquals(fxmlAttributes, scheme.getAttributes(Fxml2SemanticRole.TYPE.key()));
        assertEquals(tagAttributes, scheme.getAttributes(XmlHighlighterColors.XML_TAG_NAME));
        assertEquals(XmlHighlighterColors.XML_ATTRIBUTE_NAME,
                Fxml2SemanticRole.PROPERTY_ASSIGNMENT.key().getFallbackAttributeKey());
    }

    @Test
    void expressionRolesContinueToFollowJavaAttributes() {
        var scheme = (EditorColorsScheme)EditorColorsManager.getInstance().getGlobalScheme().clone();
        var fieldAttributes = new TextAttributes(Color.MAGENTA, null, null, null, Font.BOLD);
        scheme.setAttributes(JavaHighlightingColors.INSTANCE_FIELD_ATTRIBUTES, fieldAttributes);
        assertEquals(fieldAttributes, scheme.getAttributes(Fxml2SemanticRole.PROPERTY_READ.key()));
        assertEquals(fieldAttributes, scheme.getAttributes(Fxml2SemanticRole.INSTANCE_FIELD.key()));
    }

    @Test
    void settingsPreviewCoversEveryConfigurableRoleWithoutIndexes() {
        var page = new Fxml2ColorSettingsPage();
        var previewKeys = page.getAdditionalHighlightingTagToDescriptorMap();
        assertEquals(Fxml2SemanticRole.values().length, page.getAttributeDescriptors().length);
        for (var role : Fxml2SemanticRole.values()) {
            assertEquals(role.key(), previewKeys.get(role.name()));
            assertTrue(page.getDemoText().contains("<" + role.name() + ">"), role.name());
            assertTrue(Arrays.stream(page.getAttributeDescriptors()).anyMatch(descriptor ->
                    role.key().equals(descriptor.getKey())));
        }
    }
}
