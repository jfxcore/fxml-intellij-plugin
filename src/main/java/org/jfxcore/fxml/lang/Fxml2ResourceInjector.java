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
 * <p>The injected fragment includes every complete resource line and only the resource characters
 * of a line shared with declaration syntax. Indentation inside that span stays exactly as written
 * so editor changes continue to map directly onto the document.
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
        if (declaration == null || declaration.payload().isEmpty()) return;

        Language language = Fxml2ResourcePayloadLanguage.of(declaration).languageOrPlainText();
        TextRange rawPayload = declaration.payloadSpan().toTextRange();
        TextRange payload = declaration.injectionSpan(text).toTextRange();
        String prefix = text.substring(rawPayload.getStartOffset(), payload.getStartOffset());
        String suffix = text.substring(payload.getEndOffset(), rawPayload.getEndOffset());

        registrar.startInjecting(language)
                .addPlace(prefix, suffix, instruction, payload)
                .doneInjecting();
    }
}
