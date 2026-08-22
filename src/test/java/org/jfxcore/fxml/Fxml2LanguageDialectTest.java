// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.source.PsiFileImpl;
import com.intellij.psi.xml.XmlFile;
import org.jfxcore.fxml.lang.Fxml2FileElementType;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.lang.Fxml2Language;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a standalone FXML/2 document is parsed as {@link Fxml2Language}, the plugin's own
 * dialect of XML, rather than as plain XML.
 *
 * <p>Owning the language is what makes {@link org.jfxcore.fxml.lang.Fxml2ParserDefinition} the
 * parser definition for these files, which in turn allows plugin-specific PSI to be substituted
 * for standard XML node types without affecting any other XML document.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class Fxml2LanguageDialectTest extends Fxml2TestBase {

    private static final String FXML2_DOCUMENT = """
            <?import javafx.scene.layout.AnchorPane?>
            <AnchorPane xmlns="http://javafx.com/javafx"
                        xmlns:fx="http://jfxcore.org/fxml/2.0"/>
            """;

    /** An FXML/2 document is an {@link XmlFile} whose language is the FXML/2 dialect. */
    @Test
    void fxml2DocumentUsesTheFxml2Dialect() {
        PsiFile file = getFixture().configureByText("View.fxml", FXML2_DOCUMENT);

        assertInstanceOf(XmlFile.class, file, "an FXML/2 document is still an XML file");
        assertSame(Fxml2Language.INSTANCE, file.getLanguage());
        assertInstanceOf(Fxml2FileType.class, file.getFileType());
        assertSame(Fxml2FileElementType.INSTANCE, ((PsiFileImpl)file).getFileElementType());
    }

    /** The dialect keeps XML as its base language, so every {@code language="XML"} EP still applies. */
    @Test
    void fxml2DialectIsAnXmlDialect() {
        assertSame(XMLLanguage.INSTANCE, Fxml2Language.INSTANCE.getBaseLanguage());
        assertNotEquals(XMLLanguage.INSTANCE, Fxml2Language.INSTANCE);
    }

    /** The document still parses as well-formed XML: the root tag and the import instruction survive. */
    @Test
    void fxml2DocumentParsesAsXml() {
        PsiFile file = getFixture().configureByText("View.fxml", FXML2_DOCUMENT);
        XmlFile xmlFile = assertInstanceOf(XmlFile.class, file);

        var rootTag = ReadAction.compute(xmlFile::getRootTag);
        assertTrue(rootTag != null, "the root tag is parsed");
        assertEquals("AnchorPane", rootTag.getName());
        assertTrue(xmlFile.getText().contains("<?import"), "the import instruction is preserved");
    }

    /** A plain XML file is untouched by the dialect: it keeps the platform's XML language. */
    @Test
    void plainXmlIsUnaffected() {
        PsiFile file = getFixture().configureByText("plain.xml", "<root/>");

        assertSame(XMLLanguage.INSTANCE, file.getLanguage());
    }
}
