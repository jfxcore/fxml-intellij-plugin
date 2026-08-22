package org.jfxcore.fxml.lang;

import com.intellij.lang.Language;
import com.intellij.lang.injection.MultiHostInjector;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resource.Fxml2ResourceDeclaration;
import org.jfxcore.fxml.resource.Fxml2ResourceInstructionParser;
import org.jfxcore.fxml.resource.Fxml2ResourceParseResult;
import org.jfxcore.fxml.resource.Fxml2ResourcePayloadLanguage;

import java.util.List;

/**
 * Injects the payload language into a {@code <?resource ?>} declaration of a standalone FXML/2
 * document, so that a {@code text/css} payload is edited with the same highlighting, completion,
 * folding, commenting and reformatting a standalone stylesheet would get.
 *
 * <p>The injector is registered for {@link Fxml2ResourceProcessingInstruction} alone, which is a
 * class only FXML/2 documents produce.  Nothing else competes for the host: the platform stops
 * asking injectors as soon as one produces a result, so keeping the host class exclusive is what
 * keeps this injection and any other injection from shadowing each other.
 *
 * <p>The injected fragment is the raw payload, exactly as written.  It is deliberately not the
 * normalized resource content: the editor edits the document, and the normalization the compiler
 * applies is a property of how the content is read, not of what is on screen.
 */
public final class Fxml2ResourceInjector implements MultiHostInjector {

    @Override
    public @NotNull List<? extends Class<? extends PsiElement>> elementsToInjectIn() {
        return List.of(Fxml2ResourceProcessingInstruction.class);
    }

    @Override
    public void getLanguagesToInject(@NotNull MultiHostRegistrar registrar, @NotNull PsiElement context) {
        if (!(context instanceof Fxml2ResourceProcessingInstruction instruction)) return;
        if (!instruction.isValidHost()) return;

        String text = instruction.getText();
        Fxml2ResourceParseResult result = Fxml2ResourceInstructionParser.parseAt(text, 0, text.length());
        if (result == null) return;

        Fxml2ResourceDeclaration declaration = result.declaration();
        if (declaration == null || declaration.payloadSpan().isEmpty()) return;

        Language language = Fxml2ResourcePayloadLanguage.of(declaration).languageOrPlainText();
        TextRange payload = declaration.payloadSpan().toTextRange();

        registrar.startInjecting(language)
                .addPlace(null, null, instruction, payload)
                .doneInjecting();
    }
}
