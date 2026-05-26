package org.jfxcore.fxml.lang;

import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.openapi.util.io.ByteSequence;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;

/**
 * Content-based file-type detector that promotes {@code .fxml} files to {@link Fxml2FileType}
 * when the file content shows the FXML/2 namespace.
 *
 * <h3>Detection strategy</h3>
 * <p>We scan the first {@value #DESIRED_PREFIX_LENGTH} bytes of the file for both:
 * <ol>
 *   <li>{@code xmlns="http://javafx.com/javafx"}: the standard JavaFX namespace, and</li>
 *   <li>{@code xmlns:fx="http://jfxcore.org/fxml/2.0"}: the FXML/2 namespace.</li>
 * </ol>
 * Only when <em>both</em> strings are present do we return {@link Fxml2FileType#INSTANCE};
 * otherwise {@code null} is returned and the platform continues with the next detector
 * (or falls back to the XML type that the bundled JavaFX plugin registered for {@code .fxml}).
 *
 * <h3>Interaction with the bundled JavaFX plugin</h3>
 * <p>The JetBrains JavaFX plugin registers {@code .fxml} as a plain XML file type via a static
 * {@code <fileType>} entry; it does <em>not</em> install its own {@code FileTypeDetector}.
 * Content-based detection runs <em>after</em> the extension-based lookup, so our detector gets
 * a chance to override the XML association for JFXcore files.  Plain (non-JFXcore) FXML files
 * continue to be treated as XML by the bundled plugin, unaffected.
 */
public final class Fxml2FileTypeDetector implements FileTypeRegistry.FileTypeDetector {

    private static final int DESIRED_PREFIX_LENGTH = 4096;

    @Override
    public int getDesiredContentPrefixLength() {
        return DESIRED_PREFIX_LENGTH;
    }

    @Override
    public @Nullable FileType detect(
            @NotNull VirtualFile file,
            @NotNull ByteSequence firstBytes,
            @Nullable CharSequence firstCharsIfText) {

        // Fast path: only inspect .fxml files.
        if (!file.getName().endsWith(".fxml")) {
            return null;
        }

        // Use the text representation when available (already decoded); otherwise fall back
        // to scanning the raw bytes as ISO-8859-1, sufficient for ASCII namespace URIs.
        CharSequence text = firstCharsIfText != null
                ? firstCharsIfText
                : new String(firstBytes.toBytes(), StandardCharsets.ISO_8859_1);

        return Fxml2FileType.isFxml2(text) ? Fxml2FileType.INSTANCE : null;
    }
}
