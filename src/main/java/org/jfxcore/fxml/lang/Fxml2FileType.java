package org.jfxcore.fxml.lang;

import com.intellij.icons.AllIcons;
import com.intellij.ide.highlighter.XmlLikeFileType;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;

import javax.swing.Icon;

/**
 * File type for FXML/2 files, specifically those {@code .fxml} files that carry the JFXcore
 * {@code xmlns:fx="http://jfxcore.org/fxml/2.0"} namespace declaration on their root element.
 *
 * <p>By extending {@link XmlLikeFileType} and backing it with {@link XMLLanguage#INSTANCE} the IDE
 * automatically provides all standard XML editor features (syntax highlighting, brace matching,
 * code folding, formatting, structure view, ...) without any additional code.
 */
public final class Fxml2FileType extends XmlLikeFileType {

    /** Singleton instance registered via {@code fieldName="INSTANCE"} in {@code plugin.xml}. */
    public static final Fxml2FileType INSTANCE = new Fxml2FileType();

    private Fxml2FileType() {
        super(XMLLanguage.INSTANCE);
    }

    @Override
    public @NotNull String getName() {
        return "FXML/2";
    }

    @Override
    public @NotNull String getDescription() {
        return "FXML/2 file";
    }

    @Override
    public @NotNull String getDefaultExtension() {
        // The canonical extension is still "fxml"; the extra "fxmlx" entry in plugin.xml
        // ensures the type can also be selected manually in Settings | File Types.
        return "fxml";
    }

    @Override
    public @NotNull Icon getIcon() {
        return AllIcons.FileTypes.Xml;
    }

    /**
     * Returns {@code true} when {@code text} contains the FXML/2 namespace URI,
     * which is the definitive marker of an FXML/2 document.
     * Used by both the file-type detector/overrider (byte content) and the PSI-level check.
     */
    public static boolean isFxml2(@NotNull CharSequence text) {
        return StringUtil.contains(text, Fxml2ImportResolver.FXML2_NAMESPACE);
    }

    /**
     * Returns {@code true} when {@code file} is an FXML/2 document.
     *
     * <p>{@code PsiFile.getFileType()} always returns the underlying language's file type
     * ({@code XmlFileType}) regardless of any {@link com.intellij.openapi.fileTypes.impl.FileTypeOverrider}.
     * We therefore first ask the {@link VirtualFile} (which does honor overriders), then fall
     * back to scanning the already-loaded PSI text.
     */
    public static boolean isFxml2(@NotNull PsiFile file) {
        VirtualFile vf = file.getViewProvider().getVirtualFile();
        if (vf.getFileType() instanceof Fxml2FileType) return true;
        // Injected fragment from a @ComponentView annotation: detected by the wrapper root namespace.
        if (file instanceof com.intellij.psi.xml.XmlFile xmlFile
                && Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile)) return true;
        // Standalone file: require .fxml / .fxmlx extension + namespace content check.
        String name = vf.getName();
        if (!name.endsWith(".fxml") && !name.endsWith(".fxmlx")) return false;
        return isFxml2(file.getViewProvider().getContents());
    }
}
