package org.jfxcore.fxml.lang;

import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiLanguageInjectionHost;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Registers the injected fragments of markup embedded in a {@code @ComponentView} annotation
 * value, for both the Java and the Kotlin host.
 *
 * <p>The two language injectors differ only in how they find the host and its value range; what
 * they register is the same, and registering it in one place is what keeps the Java and the Kotlin
 * form of an embedded document behaving identically.
 *
 * <p>A document without resource declarations is registered as it always was: one markup fragment
 * over the whole value.  A document with them is registered as one markup fragment made of several
 * places that skip the payloads, plus one fragment per payload.  A single registrar accumulates
 * several {@code startInjecting} / {@code doneInjecting} rounds into one result, which is what
 * makes the second kind possible at all.
 */
final class Fxml2EmbeddedMarkupInjection {

    private Fxml2EmbeddedMarkupInjection() {}

    /**
     * Injects the markup occupying {@code valueRange} of {@code host}, carving out any resource
     * payloads and injecting each of those in its own language.
     *
     * @param registrar  the registrar to register with
     * @param host       the injection host holding the markup
     * @param valueRange the range of the host's text that holds the markup
     * @param prefix     the text prepended to the markup fragment
     * @param suffix     the text appended to the markup fragment
     */
    static void inject(@NotNull MultiHostRegistrar registrar,
                       @NotNull PsiLanguageInjectionHost host,
                       @NotNull TextRange valueRange,
                       @NotNull String prefix,
                       @NotNull String suffix) {
        Fxml2ResourceInjectionPlan plan = Fxml2ResourceInjectionPlan.of(host.getText(), valueRange);

        registrar.startInjecting(Fxml2EmbeddedXmlLanguage.INSTANCE);
        List<TextRange> ranges = plan.markupRanges();
        for (int i = 0; i < ranges.size(); ++i) {
            registrar.addPlace(
                    i == 0 ? prefix : null,
                    i == ranges.size() - 1 ? suffix : null,
                    host,
                    ranges.get(i));
        }
        registrar.doneInjecting();

        for (Fxml2ResourceInjectionPlan.Fxml2PayloadInjection payload : plan.payloads()) {
            registrar.startInjecting(payload.language())
                    .addPlace(payload.prefix(), payload.suffix(), host, payload.range())
                    .doneInjecting();
        }
    }
}
