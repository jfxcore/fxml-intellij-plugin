package org.jfxcore.fxml.lang;

import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.ElementManipulators;
import com.intellij.psi.PsiClass;
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

    /** Injects the markup held by a Java or Kotlin {@code @ComponentView} value. */
    static void inject(@NotNull MultiHostRegistrar registrar,
                       @NotNull PsiLanguageInjectionHost host,
                       @NotNull PsiClass hostClass) {
        String hostFqn = hostClass.getQualifiedName();
        if (hostFqn == null) return;

        TextRange valueRange = ElementManipulators.getValueTextRange(host);
        Fxml2ResourceInjectionPlan plan = Fxml2ResourceInjectionPlan.of(host.getText(), valueRange);
        String prefix = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        '<' + Fxml2EmbeddedUtil.EMBEDDED_WRAPPER_LOCAL +
                        " xmlns=\"http://javafx.com/javafx\"" +
                        " xmlns:fx=\"http://jfxcore.org/fxml/2.0\"" +
                        " xmlns:fxml2=\"" + Fxml2EmbeddedUtil.EMBEDDED_WRAPPER_NS + '"' +
                        " fx:subclass=\"" + hostFqn + "\">\n";
        String suffix = "\n</" + Fxml2EmbeddedUtil.EMBEDDED_WRAPPER_LOCAL + ">";

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

        for (Fxml2ResourcePayloadInjection payload : plan.payloads()) {
            payload.inject(registrar, host);
        }
    }
}
