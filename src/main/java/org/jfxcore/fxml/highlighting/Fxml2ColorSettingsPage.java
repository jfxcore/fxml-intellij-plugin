package org.jfxcore.fxml.highlighting;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.lang.Fxml2Language;

import javax.swing.Icon;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Scheme settings and an index-independent preview for FXML/2 semantic roles. */
public final class Fxml2ColorSettingsPage implements ColorSettingsPage {
    @Override
    public Icon getIcon() {
        return AllIcons.FileTypes.Xml;
    }

    @Override
    public @NotNull SyntaxHighlighter getHighlighter() {
        return SyntaxHighlighterFactory.getSyntaxHighlighter(Fxml2Language.INSTANCE, null, null);
    }

    @Override
    public @NotNull String getDemoText() {
        return """
                <?import sample.<TYPE>View</TYPE>?>
                <<TYPE>View</TYPE> xmlns="http://javafx.com/javafx" xmlns:fx="http://jfxcore.org/fxml/2.0">
                    <<TYPE>WidthBehavior</TYPE> <PROPERTY_ASSIGNMENT>columnWidth</PROPERTY_ASSIGNMENT>="<BRACES>{</BRACES><TYPE>PersistedWidth</TYPE> <STRING>view.columns.title</STRING><SEMICOLON>;</SEMICOLON> <PROPERTY_ASSIGNMENT>defaultValue</PROPERTY_ASSIGNMENT><OPERATOR>=</OPERATOR><NUMBER>200</NUMBER><BRACES>}</BRACES>"/>
                    <<TYPE>Label</TYPE> <PROPERTY_ASSIGNMENT>text</PROPERTY_ASSIGNMENT>="<PREFIX>$</PREFIX><BRACES>{</BRACES><TYPE>String</TYPE><DOT>.</DOT><STATIC_METHOD_CALL>format</STATIC_METHOD_CALL><PARENTHESES>(</PARENTHESES><STRING>'Count: %d'</STRING><COMMA>,</COMMA> <INSTANCE_FIELD>model</INSTANCE_FIELD><DOT>.</DOT><PROPERTY_READ>count</PROPERTY_READ> <OPERATOR>+</OPERATOR> <NUMBER>1</NUMBER><PARENTHESES>)</PARENTHESES><BRACES>}</BRACES>"/>
                    <<TYPE>Label</TYPE> <PROPERTY_ASSIGNMENT>text</PROPERTY_ASSIGNMENT>="<PREFIX>%</PREFIX><RESOURCE_KEY>message.title</RESOURCE_KEY>"/>
                    <<TYPE>ImageView</TYPE> <PROPERTY_ASSIGNMENT>image</PROPERTY_ASSIGNMENT>="<PREFIX>@</PREFIX><RESOURCE_PATH>icons/view.png</RESOURCE_PATH>"/>
                    <<TYPE>Pane</TYPE> <PROPERTY_ASSIGNMENT>visible</PROPERTY_ASSIGNMENT>="<PREFIX>$</PREFIX><BRACES>{</BRACES><SELECTOR>:parent</SELECTOR><OPERATOR>&lt;</OPERATOR><TYPE>Pane</TYPE><OPERATOR>&gt;</OPERATOR><PARENTHESES>(</PARENTHESES><NUMBER>2</NUMBER><PARENTHESES>)</PARENTHESES><DOT>.</DOT><PROPERTY_READ>visible</PROPERTY_READ> <OPERATOR>&amp;&amp;</OPERATOR> <KEYWORD>true</KEYWORD><BRACES>}</BRACES>"/>
                    <<TYPE>Label</TYPE> <PROPERTY_ASSIGNMENT>alignment</PROPERTY_ASSIGNMENT>="<CONSTANT>CENTER</CONSTANT>" <PROPERTY_ASSIGNMENT>styleClass</PROPERTY_ASSIGNMENT>="<CSS_CLASS>title</CSS_CLASS>"/>
                    <<INTRINSIC>fx:Observe</INTRINSIC> <PROPERTY_ASSIGNMENT>source</PROPERTY_ASSIGNMENT>="<INSTANCE_FIELD>model</INSTANCE_FIELD><DOT>.</DOT><PROPERTY_READ>title</PROPERTY_READ>"/>
                </<TYPE>View</TYPE>>
                """ + additionalExamples();
    }

    private static String additionalExamples() {
        return Arrays.stream(Fxml2SemanticRole.values())
                .map(role -> "<!-- " + role.displayName() + ": <" + role.name() + ">sample</" + role.name() + "> -->\n")
                .reduce("", String::concat);
    }

    @Override
    public @NotNull Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
        Map<String, TextAttributesKey> keys = new LinkedHashMap<>();
        for (Fxml2SemanticRole role : Fxml2SemanticRole.values()) keys.put(role.name(), role.key());
        return keys;
    }

    @Override
    public AttributesDescriptor @NotNull [] getAttributeDescriptors() {
        return Arrays.stream(Fxml2SemanticRole.values())
                .map(role -> new AttributesDescriptor(role.displayName(), role.key()))
                .toArray(AttributesDescriptor[]::new);
    }

    @Override
    public ColorDescriptor @NotNull [] getColorDescriptors() {
        return ColorDescriptor.EMPTY_ARRAY;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "FXML/2";
    }
}
