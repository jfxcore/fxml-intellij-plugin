package org.jfxcore.fxml.lang;

import com.intellij.psi.tree.IFileElementType;

/**
 * The file node type of standalone FXML/2 documents.
 *
 * <p>Binding the file node type to {@link Fxml2Language} is what routes lazy parsing of an
 * FXML/2 file through {@link Fxml2ParserDefinition}: {@code IFileElementType.doParseContents}
 * resolves the parser definition from the node type's language.  This mirrors how the platform
 * defines its own {@code XML_FILE}, {@code XHTML_FILE} and {@code DTD_FILE} node types.
 */
public final class Fxml2FileElementType {

    /** The FXML/2 file node type. */
    public static final IFileElementType INSTANCE = new IFileElementType("FXML2_FILE", Fxml2Language.INSTANCE);

    private Fxml2FileElementType() {}
}
