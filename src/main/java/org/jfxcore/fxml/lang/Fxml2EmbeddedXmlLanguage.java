package org.jfxcore.fxml.lang;

import com.intellij.lang.xml.XMLLanguage;

/**
 * A lightweight dialect of {@link XMLLanguage} used exclusively for FXML markup that is
 * injected into Java / Kotlin source files via the {@code @ComponentView} annotation.
 *
 * <p>Using a distinct language (rather than plain {@link XMLLanguage#INSTANCE}) lets us
 * register a custom {@link Fxml2EmbeddedXmlParserDefinition} for it.  That parser
 * definition creates {@link Fxml2EmbeddedXmlFile} instances whose
 * {@link Fxml2EmbeddedXmlFile#findElementAt(int)} override applies the same identifier-segment
 * narrowing that {@link Fxml2FileViewProvider} provides for standalone FXML/2 files,
 * fixing identifier-under-caret highlighting in embedded FXML.
 *
 * <p>Because this language extends {@link XMLLanguage}, all EP contributions registered for
 * {@code language="XML"} (reference contributors, annotators, completions, ...) are inherited
 * automatically via IntelliJ's language-extension dialect walk.
 */
final class Fxml2EmbeddedXmlLanguage extends XMLLanguage {

    static final Fxml2EmbeddedXmlLanguage INSTANCE = new Fxml2EmbeddedXmlLanguage();

    private Fxml2EmbeddedXmlLanguage() {
        super(XMLLanguage.INSTANCE, "Fxml2EmbeddedXml");
    }
}
