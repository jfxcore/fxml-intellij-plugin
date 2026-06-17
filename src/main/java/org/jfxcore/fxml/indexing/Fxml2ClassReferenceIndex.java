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
 * Persistent index that maps fully-qualified class names (from FXML import declarations)
 * to the source files that declare them.
 *
 * <p>Two sources of FXML import data are indexed:
 * <ol>
 *   <li><b>Standalone FXML files</b> ({@code .fxml} / {@code .fxmlx}): any
 *       {@code <?import fully.qualified.ClassName?>} processing instruction whose
 *       containing file carries the FXML/2 namespace URI
 *       ({@code http://jfxcore.org/fxml/2.0}).</li>
 *   <li><b>Embedded FXML in Java files</b>: both the host file's
 *       {@code import} declarations and any {@code <?import?>} PIs written inside
 *       a {@code @ComponentView} annotation value.</li>
 * </ol>
 *
 * <p>The index key is the raw import target string as written in the source
 * (e.g. {@code "javafx.scene.control.Label"} or {@code "javafx.scene.control.*"}).
 * Wildcard package imports are stored verbatim with the trailing {@code .*} so
 * query callers can disambiguate exact from wildcard results.
 *
 * <p>This index enables O(1) "Find Usages of Java class in FXML" queries and
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

    /** FXML/2 namespace URI used by the file detector. */
    private static final String FXML2_NS = "http://jfxcore.org/fxml/2.0";

    /** Textual marker used to quickly reject Java files that do not reference {@code @ComponentView}. */
    private static final String COMPONENT_VIEW_ANNOTATION = "@ComponentView";

    /** Matches {@code <?import target?>} in raw XML / FXML text. */
    private static final Pattern IMPORT_PI_PATTERN =
            Pattern.compile("<\\?import\\s+([^?]+?)\\s*\\?>");

    /**
     * Matches a single-line Java {@code import} statement.  Static imports are excluded;
     * wildcard imports (e.g. {@code com.foo.*}) are captured verbatim with the trailing star.
     */
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
        // Accept XML-like files (.fxml / .fxmlx) and Java files (@ComponentView embedded markup).
        // Fxml2FileType extends XmlLikeFileType (not XmlFileType), so the check uses
        // XmlLikeFileType to cover both the built-in XmlFileType and our custom Fxml2FileType.
        return file -> {
            FileType ft = file.getFileType();
            if (ft instanceof XmlLikeFileType) {
                String name = file.getName();
                return name.endsWith(".fxml") || name.endsWith(".fxmlx");
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
     * Extracts FXML import targets from a standalone {@code .fxml} or {@code .fxmlx} file.
     *
     * <p>Only files that carry the FXML/2 namespace URI are processed; classic FXML and
     * generic XML files are skipped.
     */
    private static @NotNull Map<String, Void> indexXmlFile(@NotNull CharSequence content) {
        // Quick rejection: only index files with the FXML/2 namespace.
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
     * Extracts import targets from a Java file that embeds FXML via {@code @ComponentView}.
     *
     * <p>Two categories of imports are indexed:
     * <ol>
     *   <li>Top-level Java {@code import} statements: these are automatically available
     *       inside the embedded FXML fragment (the injector folds them into the XML prolog).</li>
     *   <li>{@code <?import?>} processing instructions written inside the
     *       {@code @ComponentView} annotation value.</li>
     * </ol>
     *
     * <p>The textual prefilter accepts any file containing the substring
     * {@code @ComponentView}.  False positives (for example, the identifier appearing in a
     * comment or unrelated string literal) cause the file's regular Java imports to be added
     * to the index even when no markup is actually embedded; this is bounded and acceptable
     * because the recorded entries are still valid Java imports of the host file.
     */
    private static @NotNull Map<String, Void> indexJavaFile(@NotNull CharSequence content) {
        // Quick rejection: only Java files that might carry @ComponentView.
        if (notContains(content, COMPONENT_VIEW_ANNOTATION)) {
            return Collections.emptyMap();
        }

        Map<String, Void> result = new HashMap<>();

        // Index regular Java import statements (they become available to the embedded FXML).
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
     * Returns all source files (both standalone FXML and Java files with embedded FXML)
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
        return ReadAction.<Collection<VirtualFile>>nonBlocking(() -> {
            try {
                return FileBasedIndex.getInstance().getContainingFiles(KEY, importTarget, scope);
            } catch (IndexNotReadyException e) {
                return Collections.emptyList();
            }
        }).executeSynchronously();
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
