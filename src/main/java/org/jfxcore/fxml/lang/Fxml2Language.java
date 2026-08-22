package org.jfxcore.fxml.lang;

import com.intellij.lang.xml.XMLLanguage;

/**
 * The language of standalone FXML/2 documents: a dialect of {@link XMLLanguage}.
 *
 * <p>A dedicated dialect gives the plugin ownership of the parser definition that parses
 * {@code .fxml} files carrying the JFXcore namespace, which is what makes it possible to
 * substitute plugin-specific PSI for standard XML node types (see
 * {@link Fxml2ParserDefinition}).  Every extension point registered for {@code language="XML"}
 * keeps applying, because the platform walks a language's dialect chain when looking up
 * language extensions.
 *
 * @see Fxml2EmbeddedXmlLanguage for the sibling dialect used by markup injected into
 *      {@code @ComponentView} annotation values
 */
public final class Fxml2Language extends XMLLanguage {

    /** Singleton instance; referenced by {@code language="FXML2"} registrations in {@code plugin.xml}. */
    public static final Fxml2Language INSTANCE = new Fxml2Language();

    private Fxml2Language() {
        super(XMLLanguage.INSTANCE, "FXML2");
    }
}
