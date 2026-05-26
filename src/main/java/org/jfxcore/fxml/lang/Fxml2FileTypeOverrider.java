package org.jfxcore.fxml.lang;

import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Overrides the file type for {@code .fxml} files that carry the FXML/2 namespace,
 * returning {@link Fxml2FileType#INSTANCE} for them.
 *
 * <h3>Why a FileTypeOverrider instead of a FileTypeDetector?</h3>
 * <p>The bundled JavaFX plugin registers {@code .fxml} as an extension for the XML file type via a
 * static {@code <fileType name="XML" extensions="fxml"/>} entry. The IntelliJ platform only runs
 * content-based {@code FileTypeDetector}s when the extension lookup returns <em>null</em> or
 * {@code DetectedByContentFileType}. Since the static XML mapping always wins the extension lookup,
 * detectors are never invoked for {@code .fxml} files.
 * <p>{@code FileTypeOverrider} is consulted <em>before</em> any extension or content lookup, so it
 * can claim JFXcore files regardless of what the bundled plugin registered.
 *
 * <h3>Detection strategy</h3>
 * <p>We read the first {@value #MAX_HEADER_BYTES} bytes of the file and look for:
 * <ol>
 *   <li>{@code xmlns="http://javafx.com/javafx"}</li>
 *   <li>{@code xmlns:fx="http://jfxcore.org/fxml/2.0"}</li>
 * </ol>
 * Both must be present. Plain FXML files (which use {@code xmlns:fx="http://javafx.com/fxml"})
 * are not matched and continue to be handled by the bundled JavaFX plugin.
 */
public final class Fxml2FileTypeOverrider implements FileTypeOverrider {

    private static final int MAX_HEADER_BYTES = 4096;

    @Override
    public @Nullable FileType getOverriddenFileType(@NotNull VirtualFile file) {
        if (!file.getName().endsWith(".fxml")) return null;
        try {
            // isDirectory() / isValid() / getInputStream() may throw
            // UnsupportedOperationException on lightweight stub files such as
            // FakeVirtualFile (used by the copy-dialog's file-type check).
            // Catch RuntimeException as a blanket guard so we never propagate out
            // of a FileTypeOverrider callback.
            if (file.isDirectory() || !file.isValid()) return null;
            String header = readHeader(file);
            if (header != null && Fxml2FileType.isFxml2(header)) {
                return Fxml2FileType.INSTANCE;
            }
        } catch (IOException | RuntimeException ignored) {
            // If we can't read the file, leave it to the normal detection pipeline.
        }
        return null;
    }

    /** Reads up to {@value #MAX_HEADER_BYTES} bytes and decodes them as Latin-1. */
    private static @Nullable String readHeader(@NotNull VirtualFile file) throws IOException {
        byte[] bytes;
        try (InputStream in = file.getInputStream()) {
            bytes = in.readNBytes(MAX_HEADER_BYTES);
        }
        if (bytes.length == 0) return null;
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }
}
