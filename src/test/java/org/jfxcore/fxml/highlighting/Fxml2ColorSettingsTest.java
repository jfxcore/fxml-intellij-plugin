package org.jfxcore.fxml.highlighting;

import com.intellij.ide.highlighter.JavaHighlightingColors;
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
    void rolesFollowCustomizedJavaAttributesAndAllowIndependentOverrides() {
        var scheme = (EditorColorsScheme)EditorColorsManager.getInstance().getGlobalScheme().clone();
        var javaAttributes = new TextAttributes(Color.MAGENTA, null, null, null, Font.BOLD);
        scheme.setAttributes(JavaHighlightingColors.CLASS_NAME_ATTRIBUTES, javaAttributes);
        assertEquals(javaAttributes, scheme.getAttributes(Fxml2SemanticRole.TYPE.key()));
        var fxmlAttributes = new TextAttributes(Color.ORANGE, null, null, null, Font.ITALIC);
        scheme.setAttributes(Fxml2SemanticRole.TYPE.key(), fxmlAttributes);
        assertEquals(fxmlAttributes, scheme.getAttributes(Fxml2SemanticRole.TYPE.key()));
        assertEquals(javaAttributes, scheme.getAttributes(JavaHighlightingColors.CLASS_NAME_ATTRIBUTES));
        assertEquals(JavaHighlightingColors.ANNOTATION_ATTRIBUTE_NAME_ATTRIBUTES,
                Fxml2SemanticRole.PROPERTY_ASSIGNMENT.key().getFallbackAttributeKey());
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
