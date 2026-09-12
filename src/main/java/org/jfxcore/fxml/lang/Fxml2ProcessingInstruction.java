package org.jfxcore.fxml.lang;

import com.intellij.psi.LiteralTextEscaper;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.impl.source.xml.XmlProcessingInstructionImpl;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlProcessingInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resource.Fxml2ResourceInstructionParser;
import org.jfxcore.fxml.resource.Fxml2ResourceParseResult;

/**
 * A processing instruction in a standalone FXML/2 document. Resource declarations can use it as
 * an injection host so their payload is editable in the language its media type names.
 *
 * <p>The platform's {@code XmlProcessingInstructionImpl} is not a
 * {@link PsiLanguageInjectionHost}, and no extension point can make it one: the XML processing
 * instruction node type belongs to the XML language, so registering an AST factory for a dialect
 * does not reach it.  The supported way through is that {@code PsiBuilderImpl} consults the parser
 * definition itself as an AST factory before the language-keyed lookup, which lets
 * {@link Fxml2ParserDefinition} substitute this class for the standard one.  Because the
 * substitution goes through the parser definition, it applies only to documents parsed as FXML/2.
 */
public final class Fxml2ProcessingInstruction
        extends XmlProcessingInstructionImpl
        implements PsiLanguageInjectionHost {

    /** The name of the throwaway file a replacement instruction is parsed from. */
    private static final String DUMMY_FILE_NAME = "_fxml2_resource_update.fxml";

    /**
     * Returns the parsed declaration of this instruction, or {@code null} when it is not a
     * resource declaration at all.
     *
     * <p>Parsing this instruction is a common enough question that it is answered here rather than
     * by every consumer importing the parser: injection, folding, and formatting all start from it.
     */
    public @Nullable Fxml2ResourceParseResult resourceDirective() {
        String text = getText();
        return Fxml2ResourceInstructionParser.parseAt(text, 0, text.length());
    }

    /**
     * Only a resource instruction that declares a named resource and writes the content separator
     * is a valid host.  An import instruction, or a resource instruction that is still being typed,
     * reports {@code false} so that nothing is injected into it.
     */
    @Override
    public boolean isValidHost() {
        Fxml2ResourceParseResult directive = resourceDirective();
        return directive != null
                && directive.declaration().hasName()
                && directive.declaration().hasContentSeparator();
    }

    /**
     * Rebuilds this instruction from {@code text}.
     *
     * <p>The replacement is parsed as a whole FXML/2 document rather than assembled from nodes,
     * so that the result is exactly what the parser would have produced for the new text.  A
     * replacement that does not parse back into a single processing instruction is refused rather
     * than applied, because a partial replacement would corrupt the document.
     */
    @Override
    public @NotNull PsiLanguageInjectionHost updateText(@NotNull String text) {
        XmlProcessingInstruction replacement = parseInstruction(text);
        if (replacement == null) return this;

        return (PsiLanguageInjectionHost)replace(replacement);
    }

    /**
     * Returns an escaper that maps the injected fragment onto this instruction one character at a
     * time, which is correct because processing-instruction content has no entity expansion and no
     * escape sequences: its text is its value.
     */
    @Override
    public @NotNull LiteralTextEscaper<Fxml2ProcessingInstruction> createLiteralTextEscaper() {
        return LiteralTextEscaper.createSimple(this);
    }

    /** Parses {@code text} into a processing instruction, or returns {@code null} when it is not one. */
    private @Nullable XmlProcessingInstruction parseInstruction(@NotNull String text) {
        var file = PsiFileFactory.getInstance(getProject())
                .createFileFromText(DUMMY_FILE_NAME, Fxml2Language.INSTANCE, text);
        if (!(file instanceof XmlFile)) return null;

        XmlProcessingInstruction parsed =
                PsiTreeUtil.findChildOfType(file, XmlProcessingInstruction.class);

        return parsed != null && parsed.getTextLength() == text.length() ? parsed : null;
    }
}
