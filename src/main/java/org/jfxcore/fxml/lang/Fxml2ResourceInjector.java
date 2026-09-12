package org.jfxcore.fxml.lang;

import com.intellij.lang.injection.MultiHostInjector;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resource.Fxml2ResourceDeclaration;
import org.jfxcore.fxml.resource.Fxml2ResourceParseResult;

import java.util.List;

/**
 * Injects the payload language into a {@code <?resource ?>} declaration of a standalone FXML/2
 * document, so that a {@code text/css} payload is edited with the same highlighting, completion,
 * folding, commenting and reformatting a standalone stylesheet would get.
 *
 * <p>The injector is registered for {@link Fxml2ProcessingInstruction} alone, which is a
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
        return List.of(Fxml2ProcessingInstruction.class);
    }

    @Override
    public void getLanguagesToInject(@NotNull MultiHostRegistrar registrar, @NotNull PsiElement context) {
        if (!(context instanceof Fxml2ProcessingInstruction instruction)) return;
        if (!instruction.isValidHost()) return;

        Fxml2ResourceParseResult directive = instruction.resourceDirective();
        if (directive == null) return;

        String text = instruction.getText();
        Fxml2ResourceDeclaration declaration = directive.declaration();
        if (declaration.payload().isEmpty()) return;

        Fxml2ResourcePayloadInjection.of(text, declaration).inject(registrar, instruction);
    }
}
