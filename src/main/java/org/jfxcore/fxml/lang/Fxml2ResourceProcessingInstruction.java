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
import org.jfxcore.fxml.resource.Fxml2ResourceInstruction;
import org.jfxcore.fxml.resource.Fxml2ResourceScanner;

/**
 * A {@code <?resource ?>} processing instruction in a standalone FXML/2 document, which is also an
 * injection host so that the payload can be edited in the language its media type names.
 *
 * <p>The platform's {@code XmlProcessingInstructionImpl} is not a
 * {@link PsiLanguageInjectionHost}, and no extension point can make it one: the XML processing
 * instruction node type belongs to the XML language, so registering an AST factory for a dialect
 * does not reach it.  The supported way through is that {@code PsiBuilderImpl} consults the parser
 * definition itself as an AST factory before the language-keyed lookup, which lets
 * {@link Fxml2ParserDefinition} substitute this class for the standard one.  Because the
 * substitution goes through the parser definition, it applies only to documents parsed as FXML/2.
 *
 * <p>Only a resource instruction that actually has a payload is a valid host.  An import
 * instruction, or a resource instruction that is still being typed and has no content separator
 * yet, reports {@code false} so that nothing is injected into it.
 */
public final class Fxml2ResourceProcessingInstruction
        extends XmlProcessingInstructionImpl
        implements PsiLanguageInjectionHost {

    /** The name of the throwaway file a replacement instruction is parsed from. */
    private static final String DUMMY_FILE_NAME = "_fxml2_resource_update.fxml";

    /**
     * Returns the scanned structure of this instruction, or {@code null} when it is not a resource
     * declaration or carries no payload.
     */
    public @Nullable Fxml2ResourceInstruction resourceInstruction() {
        String text = getText();
        Fxml2ResourceInstruction instruction = Fxml2ResourceScanner.scanAt(text, 0, text.length());
        return instruction != null && instruction.hasPayload() ? instruction : null;
    }

    @Override
    public boolean isValidHost() {
        return resourceInstruction() != null;
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
    public @NotNull LiteralTextEscaper<Fxml2ResourceProcessingInstruction> createLiteralTextEscaper() {
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
