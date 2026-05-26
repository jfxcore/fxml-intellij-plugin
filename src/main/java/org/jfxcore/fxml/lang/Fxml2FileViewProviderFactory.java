package org.jfxcore.fxml.lang;

import com.intellij.lang.Language;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.FileViewProviderFactory;
import com.intellij.psi.PsiManager;
import com.intellij.psi.SingleRootFileViewProvider;
import org.jetbrains.annotations.NotNull;

/**
 * Produces {@link Fxml2FileViewProvider} instances for {@link Fxml2FileType} files.
 *
 * <p>Registered via {@code lang.fileViewProviderFactory language="XML"} in {@code plugin.xml}.
 * Using the language-based EP is required because
 * {@code FileManagerImpl.createFileViewProvider()} only consults
 * {@code fileType.fileViewProviderFactory} when the resolved language is {@code null};
 * for files backed by a {@link com.intellij.openapi.fileTypes.LanguageFileType}
 * (like our {@link Fxml2FileType} which extends {@code XmlLikeFileType}) it always goes
 * through {@code lang.fileViewProviderFactory} instead.
 *
 * <p>For non-FXML/2 XML files this factory falls back to the default
 * {@link SingleRootFileViewProvider}, preserving pre-existing behavior.
 */
@SuppressWarnings("UnusedDeclaration") // registered via lang.fileViewProviderFactory EP in plugin.xml
public final class Fxml2FileViewProviderFactory implements FileViewProviderFactory {

    @Override
    public @NotNull FileViewProvider createFileViewProvider(@NotNull VirtualFile file,
                                                             Language language,
                                                             @NotNull PsiManager manager,
                                                             boolean eventSystemEnabled) {
        if (file.getFileType() instanceof Fxml2FileType) {
            return new Fxml2FileViewProvider(manager, file, eventSystemEnabled);
        }
        // Fall back to default behavior for every other XML file so we don't
        // accidentally alter the view provider for plain .xml, .html, etc.
        return new SingleRootFileViewProvider(manager, file, eventSystemEnabled);
    }
}
