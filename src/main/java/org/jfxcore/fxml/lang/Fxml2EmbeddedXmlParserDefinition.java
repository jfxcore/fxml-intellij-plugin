package org.jfxcore.fxml.lang;

import com.intellij.lang.xml.XMLParserDefinition;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/**
 * Parser definition for {@link Fxml2EmbeddedXmlLanguage}.
 *
 * <p>Identical to {@link XMLParserDefinition} except that {@link #createFile(FileViewProvider)}
 * returns a {@link Fxml2EmbeddedXmlFile}, which overrides
 * {@link Fxml2EmbeddedXmlFile#findElementAt(int)} to apply the same identifier-segment
 * narrowing used by {@link Fxml2FileViewProvider} in standalone FXML files.
 */
final class Fxml2EmbeddedXmlParserDefinition extends XMLParserDefinition {

    @Override
    public @NotNull PsiFile createFile(@NotNull FileViewProvider viewProvider) {
        return new Fxml2EmbeddedXmlFile(viewProvider);
    }
}
