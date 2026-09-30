package org.jfxcore.fxml.highlighting;

import com.intellij.ide.highlighter.JavaHighlightingColors;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import org.jetbrains.annotations.NotNull;

/** Configurable semantic roles shared by standalone and embedded markup. */
public enum Fxml2SemanticRole {
    TYPE("Symbols//Class", JavaHighlightingColors.CLASS_NAME_ATTRIBUTES),
    INTERFACE("Symbols//Interface", JavaHighlightingColors.INTERFACE_NAME_ATTRIBUTES),
    ENUM("Symbols//Enum", JavaHighlightingColors.ENUM_NAME_ATTRIBUTES),
    TYPE_PARAMETER("Symbols//Type parameter", JavaHighlightingColors.TYPE_PARAMETER_NAME_ATTRIBUTES),
    PROPERTY_ASSIGNMENT("Markup//Property assignment", JavaHighlightingColors.ANNOTATION_ATTRIBUTE_NAME_ATTRIBUTES),
    PROPERTY_READ("Symbols//Property read", JavaHighlightingColors.INSTANCE_FIELD_ATTRIBUTES),
    INSTANCE_FIELD("Symbols//Instance field", JavaHighlightingColors.INSTANCE_FIELD_ATTRIBUTES),
    INSTANCE_FINAL_FIELD("Symbols//Instance final field", JavaHighlightingColors.INSTANCE_FINAL_FIELD_ATTRIBUTES),
    STATIC_FIELD("Symbols//Static field", JavaHighlightingColors.STATIC_FIELD_ATTRIBUTES),
    CONSTANT("Symbols//Constant", JavaHighlightingColors.STATIC_FINAL_FIELD_ATTRIBUTES),
    METHOD_CALL("Symbols//Method call", JavaHighlightingColors.METHOD_CALL_ATTRIBUTES),
    STATIC_METHOD_CALL("Symbols//Static method call", JavaHighlightingColors.STATIC_METHOD_ATTRIBUTES),
    CONSTRUCTOR_CALL("Symbols//Constructor call", JavaHighlightingColors.CONSTRUCTOR_CALL_ATTRIBUTES),
    SELECTOR("Expressions//Context selector", JavaHighlightingColors.KEYWORD),
    INTRINSIC("Markup//Intrinsic", JavaHighlightingColors.KEYWORD),
    PREFIX("Expressions//Prefix", JavaHighlightingColors.OPERATION_SIGN),
    NUMBER("Literals//Number", JavaHighlightingColors.NUMBER),
    KEYWORD("Literals//Boolean and null", JavaHighlightingColors.KEYWORD),
    STRING("Literals//String", JavaHighlightingColors.STRING),
    ESCAPE("Literals//Escape", JavaHighlightingColors.VALID_STRING_ESCAPE),
    OPERATOR("Expressions//Operator", JavaHighlightingColors.OPERATION_SIGN),
    DOT("Expressions//Member selection", JavaHighlightingColors.DOT),
    BRACES("Expressions//Braces", JavaHighlightingColors.BRACES),
    PARENTHESES("Expressions//Parentheses", JavaHighlightingColors.PARENTHESES),
    BRACKETS("Expressions//Brackets", JavaHighlightingColors.BRACKETS),
    COMMA("Expressions//Comma", JavaHighlightingColors.COMMA),
    SEMICOLON("Expressions//Semicolon", JavaHighlightingColors.JAVA_SEMICOLON),
    RESOURCE_KEY("Resources//Resource key", JavaHighlightingColors.STRING),
    RESOURCE_PATH("Resources//Resource path", JavaHighlightingColors.STRING),
    CSS_CLASS("Resources//CSS class", DefaultLanguageHighlighterColors.IDENTIFIER),
    IDENTIFIER("Symbols//Identifier and package", DefaultLanguageHighlighterColors.IDENTIFIER);

    private final String displayName;
    private final TextAttributesKey key;

    Fxml2SemanticRole(String displayName, TextAttributesKey fallback) {
        this.displayName = displayName;
        key = TextAttributesKey.createTextAttributesKey("FXML2_" + name(), fallback);
    }

    public @NotNull String displayName() {
        return displayName;
    }

    public @NotNull TextAttributesKey key() {
        return key;
    }
}
