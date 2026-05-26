package org.jfxcore.fxml.lang;

import com.intellij.lang.injection.MultiHostInjector;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.ElementManipulators;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;

/**
 * Injects {@link Fxml2EmbeddedXmlLanguage} into the value of a
 * {@code @org.jfxcore.markup.ComponentView} annotation on a Java type declaration.
 *
 * <h2>Injected document structure</h2>
 * The injected text seen by the XML parser is:
 * <pre>{@code
 * <?xml version="1.0" encoding="UTF-8"?>
 * <fxml2:embedded
 *     xmlns="[http://javafx.com/javafx]"
 *     xmlns:fx="[http://jfxcore.org/fxml/2.0]"
 *     xmlns:fxml2="[http://jfxcore.org/fxml/2.0/embedded]"
 *     fx:subclass="com.example.MainView">
 *     <!-- user markup (the annotation value) -->
 * </fxml2:embedded>
 * }</pre>
 *
 * <p>The synthetic wrapper root ({@code fxml2:embedded}) serves two purposes:
 * <ol>
 *   <li>It declares both FXML/2 namespace URIs so that {@code fx:}-prefixed attributes
 *       inside the user markup resolve to the correct namespace.</li>
 *   <li>It carries {@code fx:subclass} so that
 *       {@link org.jfxcore.fxml.resolve.Fxml2BindingPathResolver#resolveCodeBehindClass}
 *       works without modification.</li>
 * </ol>
 *
 * <p>Import resolution is handled lazily by
 * {@link org.jfxcore.fxml.resolve.Fxml2ImportResolver#parseImports}, which reads the
 * host Java file's import list directly via the injection host rather than from
 * synthesized {@code &lt;?import?&gt;} PIs in the prefix.  Keeping the prefix free of
 * import data makes it stable across edits, preventing the completion framework from
 * encountering a stale injected-file reference in its cached PSI copy.
 */
public final class Fxml2MarkupAnnotationInjector implements MultiHostInjector {

    @Override
    public void getLanguagesToInject(
            @NotNull MultiHostRegistrar registrar, @NotNull PsiElement context) {

        if (!(context instanceof PsiLiteralExpression literal)) return;
        if (!(literal.getValue() instanceof String)) return;

        // Walk up: literal -> PsiNameValuePair -> PsiAnnotation
        PsiAnnotation annotation = PsiTreeUtil.getParentOfType(literal, PsiAnnotation.class);
        if (annotation == null) return;
        if (!Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN.equals(annotation.getQualifiedName())) return;

        // The annotation must be on a type declaration (class, interface, enum)
        PsiClass hostClass = PsiTreeUtil.getParentOfType(annotation, PsiClass.class);
        if (hostClass == null) return;
        String hostFqn = hostClass.getQualifiedName();
        if (hostFqn == null) return;

        // Build the injection prefix (XML declaration + wrapper root).
        String prefix = buildPrefix(hostFqn);
        String suffix = "\n</" + Fxml2EmbeddedUtil.EMBEDDED_WRAPPER_LOCAL + ">";

        TextRange valueRange = ElementManipulators.getValueTextRange(literal);
        registrar
                .startInjecting(Fxml2EmbeddedXmlLanguage.INSTANCE)
                .addPlace(prefix, suffix, (PsiLanguageInjectionHost) literal, valueRange)
                .doneInjecting();
    }

    @Override
    public @NotNull @Unmodifiable List<? extends Class<? extends PsiElement>> elementsToInjectIn() {
        return List.of(PsiLiteralExpression.class);
    }

    /**
     * Builds the injection prefix: the XML declaration followed by the wrapper root
     * opening tag with namespace declarations and {@code fx:subclass}.
     *
     * <p>Import PIs are intentionally omitted from the prefix so that the prefix text
     * remains stable across edits to the host Java file's import list.  A stable prefix
     * prevents IntelliJ from recreating the injected PSI file when imports change, which
     * would otherwise leave a stale {@code originalFile} pointer in the completion
     * framework's cached PSI copy and trigger an assertion error in
     * {@code CompletionInitializationUtil.setOriginalFile}.
     *
     * <p>Import resolution still works: {@link org.jfxcore.fxml.resolve.Fxml2ImportResolver}
     * falls back to reading the host Java file's import list directly when the injected
     * XML prolog contains no {@code &lt;?import?&gt;} PIs.
     */
    private static @NotNull String buildPrefix(@NotNull String hostFqn) {

        // The XML declaration creates an XmlProlog node in the injected tree.
        // Without it, XmlDocument.getProlog() returns null.
        // Wrapper root with namespace declarations and fx:subclass.
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
               '<' + Fxml2EmbeddedUtil.EMBEDDED_WRAPPER_LOCAL +
               " xmlns=\"http://javafx.com/javafx\"" +
               " xmlns:fx=\"http://jfxcore.org/fxml/2.0\"" +
               " xmlns:fxml2=\"" + Fxml2EmbeddedUtil.EMBEDDED_WRAPPER_NS + '"' +
               " fx:subclass=\"" + hostFqn + "\">\n";
    }
}
