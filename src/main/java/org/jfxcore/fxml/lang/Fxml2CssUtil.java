package org.jfxcore.fxml.lang;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlAttribute;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilities for CSS class-selector resolution used by the FXML2 plugin.
 *
 * <p>Because the IntelliJ CSS PSI (Ultimate-only) is not available in Community Edition,
 * this class resolves CSS class selectors by scanning {@code .css} files as plain text
 * using a regex that matches CSS class selector tokens of the form {@code .name}.
 *
 * <h3>Selector scoring</h3>
 * When multiple occurrences of {@code .className} exist in a file (e.g. {@code .text.accent},
 * {@code .button.accent}, {@code .notification.accent}), we score each match by how well the
 * <em>compound selector context</em> around it matches the owner tag's CSS type name (e.g.
 * {@code "notification"} for {@code <Notification>}).  The highest-scoring match per file
 * is kept; files are then de-duplicated by returning at most one match per base file name so
 * that both {@code primer-light.css} and {@code primer-dark.css} are exposed to the user.
 */
public final class Fxml2CssUtil {

    /**
     * Pattern that matches a CSS class selector token.
     * Group 1 = the class name (without the leading dot).
     * We match when {@code .name} is followed by optional whitespace and then one of
     * {@code ,  :  {  .  [  >  +  ~  #}: i.e. anything that legally follows a selector
     * component, or end-of-line/file.
     */
    static final Pattern CLASS_SELECTOR_PATTERN =
            Pattern.compile("\\.(-?[_a-zA-Z][\\w-]*)(?=[\\s,:{.\\[>+~#]|$)", Pattern.MULTILINE);

    /**
     * Pattern that matches a simple CSS type selector immediately preceding a class selector
     * in a compound selector, e.g. the {@code notification} in {@code .notification.accent}.
     * Used for scoring.
     */
    private static final Pattern PRECEDING_TYPE_PATTERN =
            Pattern.compile("(?:^|[\\s,>+~])([a-zA-Z][\\w-]*)\\.[\\w-]*$");

    private Fxml2CssUtil() {}

    // -----------------------------------------------------------------------
    // Attribute recognition
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when {@code attr} is a {@code styleClass} attribute
     * (not namespace-prefixed).
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean isStyleClassAttribute(@NotNull XmlAttribute attr) {
        return "styleClass".equals(attr.getLocalName()) && !attr.getName().contains(":");
    }

    // -----------------------------------------------------------------------
    // Token splitting
    // -----------------------------------------------------------------------

    /**
     * Splits a raw {@code styleClass} value (e.g. {@code "accent, elevated-1"}) into
     * individual trimmed tokens with their start offset within the raw value string.
     * Binding expressions are ignored; returns empty list.
     */
    public static @NotNull List<Token> splitStyleClassTokens(@NotNull String rawValue) {
        if (Fxml2BindingExpressionParser.looksLikeBindingExpression(rawValue)) {
            return List.of();
        }
        List<Token> result = new ArrayList<>();
        String[] parts = rawValue.split(",", -1);
        int cursor = 0;
        for (String part : parts) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                int leadingSpaces = part.indexOf(trimmed.charAt(0));
                result.add(new Token(trimmed, cursor + leadingSpaces));
            }
            cursor += part.length() + 1;
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // CSS file scanning: multi-result with scoring
    // -----------------------------------------------------------------------

    /**
     * Finds all {@link CssSelectorElement}s for {@code className} across all {@code .css}
     * files in {@code scope}, returning at most one result per distinct CSS file base-name
     * (so {@code primer-light.css} and {@code primer-dark.css} each contribute one entry).
     *
     * <p>Within each file the <em>best-scoring</em> occurrence is chosen: a match whose
     * immediately-preceding compound-selector part equals {@code cssTypeName} (e.g.
     * {@code "notification"}) scores higher than one that does not.  This ensures that
     * {@code .notification.accent} is preferred over {@code .text.accent} when the owner
     * tag is a {@code Notification}.
     *
     * @param className   the CSS class name to search for (without the leading dot)
     * @param cssTypeName optional JavaFX CSS type name of the owner node (lowercase simple
     *                    class name, e.g. {@code "notification"}); may be {@code null}
     * @param project     the current project
     * @param scope       the search scope
     */
    public static @NotNull List<CssSelectorElement> findAllCssSelectorElements(
            @NotNull String className,
            @Nullable String cssTypeName,
            @NotNull Project project,
            @NotNull GlobalSearchScope scope) {

        PsiManager psiManager = PsiManager.getInstance(project);
        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);

        // Collect one CssSelectorElement per virtual file that contains the selector,
        // then deduplicate: when the same logical CSS file exists in both a binary jar
        // and its companion sources jar, keep only the sources-jar entry so the
        // user navigates to readable source rather than a compiled/obfuscated copy.
        //
        // Deduplication key = the entry path within the jar (everything after "!/"), or
        // the full path for plain files.  Sources-jar entries are preferred over
        // binary-jar entries when their entry paths are equal.

        // Map from dedup-key -> (CssSelectorElement, isSource)
        Map<String, CssSelectorElement> byEntryPath = new LinkedHashMap<>();
        Map<String, Boolean> isSourceByKey = new LinkedHashMap<>();

        for (VirtualFile vf : FilenameIndex.getAllFilesByExt(project, "css", scope)) {
            PsiFile psiFile = psiManager.findFile(vf);
            if (psiFile == null) continue;

            String text = psiFile.getText();
            CssSelectorElement element = bestMatch(text, className, cssTypeName, psiFile);
            if (element == null) continue;

            String path   = vf.getPath();
            // For jar entries the path is "/abs/path/to/foo.jar!/inner/path/file.css";
            // use the inner path as the dedup key so binary and sources jars collapse.
            int bangSlash = path.indexOf("!/");
            String entryKey = bangSlash >= 0 ? path.substring(bangSlash + 2) : path;

            boolean isSource = fileIndex.isInLibrarySource(vf);
            Boolean existing = isSourceByKey.get(entryKey);

            if (existing == null) {
                // First time we see this entry path: record it.
                byEntryPath.put(entryKey, element);
                isSourceByKey.put(entryKey, isSource);
            } else if (isSource && !existing) {
                // We already have a binary-jar entry; replace it with the sources-jar entry.
                byEntryPath.put(entryKey, element);
                isSourceByKey.put(entryKey, true);
            }
            // else: already have a sources entry, or this is another binary duplicate; skip.
        }

        return new ArrayList<>(byEntryPath.values());
    }


    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------


    /**
     * Finds the best-scoring occurrence of {@code .className} in {@code cssFile} and wraps it
     * in a {@link CssSelectorElement}, or returns {@code null} if the class is absent.
     */
    private static @Nullable CssSelectorElement bestMatch(
            @NotNull String cssText,
            @NotNull String className,
            @Nullable String cssTypeName,
            @NotNull PsiFile cssFile) {

        TextRange range = findSelectorRange(cssText, className, cssTypeName);
        return range != null ? new CssSelectorElement(cssFile, range) : null;
    }

    /**
     * Scores a match at {@code matchStart} in {@code cssText}.
     *
     * <ul>
     *   <li>+2 if {@code cssTypeName} is non-null and the text immediately before the
     *       matched {@code .className} token is exactly {@code .cssTypeName} (compound
     *       selector like {@code .notification.accent}).</li>
     *   <li>+1 if the matched {@code .className} token is the only class in its compound
     *       selector (standalone selector like {@code .elevated-1 {}).</li>
     *   <li>0 otherwise.</li>
     * </ul>
     */
    private static int scoreMatch(@NotNull String cssText, int matchStart,
                                   @Nullable String cssTypeName) {
        // Look at the characters immediately before the dot to determine context.
        // Extract the line prefix up to the current match position.
        int lineStart = cssText.lastIndexOf('\n', matchStart - 1) + 1;
        String prefix = cssText.substring(lineStart, matchStart);

        if (cssTypeName != null) {
            // Check for direct type+class compound: e.g. ".notification" just before our ".accent"
            // This handles ".notification.accent {": prefix ends in ".notification"
            if (prefix.endsWith("." + cssTypeName)) {
                return 2;
            }
            // Also accept type selector directly (without dot) just before our class:
            // e.g. "notification.accent": prefix ends in "notification" (no preceding dot)
            Matcher pm = PRECEDING_TYPE_PATTERN.matcher(prefix);
            if (pm.find() && cssTypeName.equals(pm.group(1))) {
                return 2;
            }
        }

        // Standalone: the prefix (from the last , or start of selector) contains no other class
        String selectorContext = prefix.replaceAll(".*[,{]", "").trim();
        if (selectorContext.isEmpty()) {
            return 1; // first class in selector, preceded only by whitespace / start-of-line
        }
        return 0;
    }

    /**
     * Finds the best-scoring occurrence of {@code .className} in {@code cssText} and returns
     * its {@link TextRange}, or {@code null} if not found.  Used by tests.
     */
    static @Nullable TextRange findSelectorRange(@NotNull String cssText,
                                                  @NotNull String className,
                                                  @Nullable String cssTypeName) {
        TextRange bestRange = null;
        int bestScore = -1;
        Matcher m = CLASS_SELECTOR_PATTERN.matcher(cssText);
        while (m.find()) {
            if (!className.equals(m.group(1))) continue;
            int score = scoreMatch(cssText, m.start(), cssTypeName);
            if (score > bestScore) {
                bestScore = score;
                bestRange = new TextRange(m.start(), m.end(1));
            }
        }
        return bestRange;
    }

    // -----------------------------------------------------------------------
    // Token record
    // -----------------------------------------------------------------------

    /**
     * A single trimmed CSS class name token and its start offset within the raw attribute
     * value string (0-based, not accounting for the surrounding XML quotes).
     */
    public record Token(@NotNull String name, int offsetInValue) {}
}
