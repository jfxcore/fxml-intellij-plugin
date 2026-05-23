package org.jfxcore.fxml.indexing;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.ide.highlighter.XmlLikeFileType;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.indexing.DataIndexer;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.FileContent;
import com.intellij.util.indexing.ID;
import com.intellij.util.indexing.ScalarIndexExtension;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persistent index that maps fully-qualified class names (from FXML2 import declarations)
 * to the source files that declare them.
 *
 * <p>Two sources of FXML2 import data are indexed:
 * <ol>
 *   <li><b>Standalone FXML2 files</b> ({@code .fxml} / {@code .fxml2}): any
 *       {@code <?import fully.qualified.ClassName?>} processing instruction whose
 *       containing file carries the FXML2 namespace URI
 *       ({@code http://jfxcore.org/fxml/2.0}).</li>
 *   <li><b>Embedded FXML2 in Java files</b>: both the host file's
 *       {@code import} declarations and any {@code <?import?>} PIs written inside
 *       a {@code @ComponentView} annotation value.</li>
 * </ol>
 *
 * <p>The index key is the raw import target string as written in the source
 * (e.g. {@code "javafx.scene.control.Label"} or {@code "javafx.scene.control.*"}).
 * Wildcard package imports are stored verbatim with the trailing {@code .*} so
 * query callers can disambiguate exact from wildcard results.
 *
 * <p>This index enables O(1) "Find Usages of Java class in FXML2" queries and
 * supports change-impact analysis: because the index is rebuilt only when file
 * content changes (not on every Java PSI modification), it does not contribute
 * to the "Analyzing code" delay experienced after commits.
 */
public final class Fxml2ClassReferenceIndex extends ScalarIndexExtension<String> {

    /**
     * Stable index identifier.  Bump {@link #getVersion()} whenever the
     * indexing logic changes to force a full rebuild of existing index data.
     */
    public static final ID<String, Void> KEY = ID.create("fxml2.classReference");

    /** FXML2 namespace URI used by both the file detector and the FXML2 compiler. */
    private static final String FXML2_NS = "http://jfxcore.org/fxml/2.0";

    /** FQN of the @ComponentView annotation processed by the fxml2 compiler. */
    private static final String COMPONENT_VIEW_ANNOTATION = "@ComponentView";

    /** Matches {@code <?import target?>} in raw XML / FXML2 text. */
    private static final Pattern IMPORT_PI_PATTERN =
            Pattern.compile("<\\?import\\s+([^?]+?)\\s*\\?>");

    /** Matches a single-line Java {@code import} statement (non-static, non-star-ignored). */
    private static final Pattern JAVA_IMPORT_PATTERN =
            Pattern.compile("^import\\s+((?:[\\w$]+\\.)*[\\w$*]+)\\s*;", Pattern.MULTILINE);

    // -----------------------------------------------------------------------
    // FileBasedIndexExtension interface
    // -----------------------------------------------------------------------

    @Override
    public @NotNull ID<String, Void> getName() {
        return KEY;
    }

    @Override
    public @NotNull DataIndexer<String, Void, FileContent> getIndexer() {
        return inputData -> {
            CharSequence content = inputData.getContentAsText();
            FileType fileType = inputData.getFileType();

            if (fileType instanceof XmlLikeFileType) {
                return indexXmlFile(content);
            }
            if (fileType instanceof JavaFileType) {
                return indexJavaFile(content);
            }
            return Collections.emptyMap();
        };
    }

    @Override
    public @NotNull KeyDescriptor<String> getKeyDescriptor() {
        return EnumeratorStringDescriptor.INSTANCE;
    }

    @Override
    public @NotNull FileBasedIndex.InputFilter getInputFilter() {
        // Accept XML-like files (.fxml / .fxml2) and Java files (@ComponentView embedded markup).
        // Fxml2FileType extends XmlLikeFileType (not XmlFileType), so the check uses
        // XmlLikeFileType to cover both the built-in XmlFileType and our custom Fxml2FileType.
        return file -> {
            FileType ft = file.getFileType();
            if (ft instanceof XmlLikeFileType) {
                String name = file.getName();
                return name.endsWith(".fxml") || name.endsWith(".fxml2");
            }
            return ft instanceof JavaFileType;
        };
    }

    @Override
    public boolean dependsOnFileContent() {
        return true;
    }

    @Override
    public int getVersion() {
        return 1;
    }

    // -----------------------------------------------------------------------
    // Indexing implementations
    // -----------------------------------------------------------------------

    /**
     * Extracts FXML2 import targets from a standalone {@code .fxml} or {@code .fxml2} file.
     *
     * <p>Only files that carry the FXML2 namespace URI are processed; plain FXML
     * (JavaFX 1/2) and generic XML files are skipped.
     */
    private static @NotNull Map<String, Void> indexXmlFile(@NotNull CharSequence content) {
        // Quick rejection: only index files with the FXML2 namespace.
        if (notContains(content, FXML2_NS)) {
            return Collections.emptyMap();
        }

        Map<String, Void> result = new HashMap<>();
        Matcher m = IMPORT_PI_PATTERN.matcher(content);
        while (m.find()) {
            String target = m.group(1).trim();
            if (!target.isEmpty()) {
                result.put(target, null);
            }
        }
        return result;
    }

    /**
     * Extracts import targets from a Java file that embeds FXML2 via {@code @ComponentView}.
     *
     * <p>Two categories of imports are indexed:
     * <ol>
     *   <li>Top-level Java {@code import} statements: these are automatically available
     *       inside the embedded FXML2 fragment (the injector folds them into the XML prolog).</li>
     *   <li>{@code <?import?>} processing instructions written inside the
     *       {@code @ComponentView} annotation value.</li>
     * </ol>
     *
     * <p>The Java file must contain a {@code @ComponentView} reference; files that do not
     * embed FXML2 are indexed only for their Java imports if they also carry the annotation
     * identifier in any form (false positives are bounded and acceptable for index purposes).
     */
    private static @NotNull Map<String, Void> indexJavaFile(@NotNull CharSequence content) {
        // Quick rejection: only Java files that might carry @ComponentView.
        if (notContains(content, COMPONENT_VIEW_ANNOTATION)) {
            return Collections.emptyMap();
        }

        Map<String, Void> result = new HashMap<>();

        // Index regular Java import statements (they become available to the embedded FXML2).
        Matcher javaMatcher = JAVA_IMPORT_PATTERN.matcher(content);
        while (javaMatcher.find()) {
            String importTarget = javaMatcher.group(1).trim();
            if (!importTarget.isEmpty() && !importTarget.startsWith("static ")) {
                result.put(importTarget, null);
            }
        }

        // Index <?import?> PIs found inside the annotation value.
        // This covers user-written PI declarations inside the embedded markup.
        Matcher piMatcher = IMPORT_PI_PATTERN.matcher(content);
        while (piMatcher.find()) {
            String target = piMatcher.group(1).trim();
            if (!target.isEmpty()) {
                result.put(target, null);
            }
        }

        return result;
    }

    // -----------------------------------------------------------------------
    // Query API
    // -----------------------------------------------------------------------

    /**
     * Returns all source files (both standalone FXML2 and Java files with embedded FXML2)
     * that declare an import for the given fully-qualified class name or wildcard package.
     *
     * <p>Both exact imports ({@code javafx.scene.control.Label}) and wildcard packages
     * ({@code javafx.scene.control.*}) are stored verbatim; callers that want to
     * find every file that might reference a class {@code com.example.Foo} should
     * query both {@code "com.example.Foo"} and {@code "com.example.*"}.
     *
     * @param importTarget the exact import target string to look up
     * @param scope        the search scope to restrict results to
     * @return an unordered collection of virtual files; never {@code null}
     */
    public static @NotNull Collection<VirtualFile> getFilesImporting(
            @NotNull String importTarget,
            @NotNull GlobalSearchScope scope) {
        return ReadAction.compute(() -> {
            try {
                return FileBasedIndex.getInstance().getContainingFiles(KEY, importTarget, scope);
            } catch (IndexNotReadyException e) {
                return Collections.emptyList();
            }
        });
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    /** Returns {@code true} if {@code haystack} does not contain {@code needle}, without creating a String copy. */
    private static boolean notContains(@NotNull CharSequence haystack, @NotNull String needle) {
        int hLen = haystack.length();
        int nLen = needle.length();
        if (nLen == 0) return false;
        if (hLen < nLen) return true;
        outer:
        for (int i = 0; i <= hLen - nLen; i++) {
            for (int j = 0; j < nLen; j++) {
                if (haystack.charAt(i + j) != needle.charAt(j)) continue outer;
            }
            return false;
        }
        return true;
    }
}
