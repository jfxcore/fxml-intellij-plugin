package org.jfxcore.fxml.lang;

import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.SingleRootFileViewProvider;
import com.intellij.psi.xml.XmlProcessingInstruction;
import com.intellij.psi.xml.XmlToken;
import com.intellij.psi.xml.XmlTokenType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom {@link SingleRootFileViewProvider} for FXML 2.0 files.
 *
 * <p>IntelliJ's identifier-under-caret highlighting caches its result using a set of
 * <em>target ranges</em>.  One of those ranges comes from
 * {@code IdentifierHighlightingComputer.findTarget()}, which calls
 * {@code PsiFile.findElementAt(caretOffset)} and adds the resulting element's
 * {@code getTextRange()} to the cache key.
 *
 * <p>For XML processing-instruction import data, dotted property tag names, and binding
 * expression attribute values the leaf PSI element is a single wide token (e.g.
 * {@code XML_TAG_CHARACTERS} covering {@code "javafx.scene.control.Label"} as one unit,
 * {@code XML_NAME} covering {@code "selectionModel.selectionMode"} as one unit, or
 * {@code XML_ATTRIBUTE_VALUE_TOKEN} covering {@code "${vm.labelText}"} as one unit).
 * This override returns a lightweight {@link Fxml2SegmentElement} whose
 * {@code getTextRange()} covers only the single Java-identifier segment at the caret,
 * giving each identifier its own distinct cache key and therefore its own highlight result.
 * Because the segment element's {@code getParent()} skips the leaf token and returns its
 * parent directly (e.g. {@code XmlAttributeValue}, {@code XmlProcessingInstruction}, or
 * {@code XmlTag}), reference-walking in {@code SharedPsiElementImplUtil.findReferenceAt()}
 * still ascends to the element that owns the per-segment references (e.g. the
 * {@code JavaClassReference} objects that {@code ImportReferenceProvider} attaches to
 * the processing instruction).
 */
public final class Fxml2FileViewProvider extends SingleRootFileViewProvider {

    /**
     * Per-token cache: maps absolute file offset -> {@link Fxml2SegmentElement}.
     *
     * <p>Stored in the {@link XmlToken}'s user data so the cache is tied to the PSI node's
     * lifetime. When the document is modified the old token nodes are discarded and replaced
     * by new ones, so the cache is invalidated automatically.
     *
     * <p>IntelliJ's {@code assertCompletionPositionPsiConsistent} calls
     * {@code file.findElementAt(offset)} twice and checks <em>reference equality</em>
     * ({@code ==}) of the two results. Without caching, each call allocates a fresh
     * {@link Fxml2SegmentElement}, so the assertion fails even though both elements are
     * semantically identical.  Caching guarantees that the same instance is returned for
     * the same {@code (leaf, offset)} pair within a single PSI generation.
     */
    private static final Key<Map<Integer, Fxml2SegmentElement>> SEGMENT_CACHE_KEY =
            Key.create("Fxml2FileViewProvider.segmentCache");

    public Fxml2FileViewProvider(@NotNull PsiManager manager,
                                 @NotNull VirtualFile file,
                                 boolean eventSystemEnabled) {
        super(manager, file, eventSystemEnabled, XMLLanguage.INSTANCE);
    }

    // -----------------------------------------------------------------------
    // findElementAt override
    // -----------------------------------------------------------------------

    @Override
    public @Nullable PsiElement findElementAt(int offset) {
        PsiElement leaf = super.findElementAt(offset);
        return narrowBinding(leaf, offset);
    }

    // -----------------------------------------------------------------------
    // Shared narrowing helper (also used by Fxml2EmbeddedXmlFile)
    // -----------------------------------------------------------------------

    /**
     * Narrows {@code leaf} to the Java-identifier segment at {@code absoluteOffset}, returning
     * a {@link Fxml2SegmentElement} whose range covers only that segment.
     * Returns {@code leaf} unchanged when no narrowing applies.
     *
     * <p>The returned {@link Fxml2SegmentElement} is cached on the token's user data so that
     * two successive calls with the same {@code (leaf, absoluteOffset)} return the <em>same</em>
     * object.
     */
    static @Nullable PsiElement narrowBinding(@Nullable PsiElement leaf, int absoluteOffset) {
        if (leaf == null) return null;

        if (leaf instanceof XmlToken xmlToken) {
            var tokenType = xmlToken.getTokenType();
            TextRange seg = null;

            // Import PI data: <?import javafx.scene.control.Label?>
            if (tokenType == XmlTokenType.XML_TAG_CHARACTERS
                    && leaf.getParent() instanceof XmlProcessingInstruction pi
                    && isImportInstruction(pi)) {
                seg = segmentAt(leaf.getText(), leaf.getTextRange(), absoluteOffset);
            }

            // Dotted property tag name: <selectionModel.selectionMode>
            if (seg == null && tokenType == XmlTokenType.XML_NAME
                    && leaf.getText().indexOf('.') >= 0) {
                seg = segmentAt(leaf.getText(), leaf.getTextRange(), absoluteOffset);
            }

            // Attribute value token: ${vm.labelText}, {fx:Synchronize vm.message}, etc.
            if (seg == null && tokenType == XmlTokenType.XML_ATTRIBUTE_VALUE_TOKEN) {
                seg = segmentAt(leaf.getText(), leaf.getTextRange(), absoluteOffset);
            }

            if (seg != null) {
                return cachedSegment(xmlToken, absoluteOffset, seg);
            }
        }

        return leaf;
    }

    /**
     * Returns a {@link Fxml2SegmentElement} for the given token and offset, reusing a cached
     * instance when one already exists for this {@code (token, absoluteOffset)} pair.
     */
    private static @NotNull Fxml2SegmentElement cachedSegment(@NotNull XmlToken token,
                                                               int absoluteOffset,
                                                               @NotNull TextRange seg) {
        Map<Integer, Fxml2SegmentElement> cache = token.getUserData(SEGMENT_CACHE_KEY);
        if (cache == null) {
            cache = new ConcurrentHashMap<>();
            token.putUserData(SEGMENT_CACHE_KEY, cache);
        }
        final TextRange finalSeg = seg;
        return cache.computeIfAbsent(absoluteOffset, k -> new Fxml2SegmentElement(token, finalSeg));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Returns {@code true} when {@code pi} is a {@code <?import ...?>} instruction. */
    static boolean isImportInstruction(@NotNull XmlProcessingInstruction pi) {
        var node     = pi.getNode();
        var nameNode = node.findChildByType(XmlTokenType.XML_NAME);
        return nameNode != null && "import".equals(nameNode.getText());
    }

    /**
     * Finds the Java-identifier segment at {@code absoluteOffset} within a token whose
     * text is {@code tokenText} starting at {@code tokenRange}.
     *
     * @return the absolute {@link TextRange} of the segment, or {@code null} if the
     *         offset lands on a non-identifier character and there is no adjacent identifier.
     */
    @Nullable
    static TextRange segmentAt(@NotNull String tokenText,
                               @NotNull TextRange tokenRange,
                               int absoluteOffset) {
        int rel = absoluteOffset - tokenRange.getStartOffset();
        if (rel < 0 || rel > tokenText.length()) return null;

        // If the caret is on a separator ('.') move left to the previous identifier char.
        if (rel == tokenText.length() || !Character.isJavaIdentifierPart(tokenText.charAt(rel))) {
            if (rel > 0 && Character.isJavaIdentifierPart(tokenText.charAt(rel - 1))) {
                rel--;
            } else {
                return null;
            }
        }

        // Expand left
        int start = rel;
        while (start > 0 && Character.isJavaIdentifierPart(tokenText.charAt(start - 1))) {
            start--;
        }
        // Expand right
        int end = rel + 1;
        while (end < tokenText.length() && Character.isJavaIdentifierPart(tokenText.charAt(end))) {
            end++;
        }

        if (start >= end) return null;
        return TextRange.create(tokenRange.getStartOffset() + start,
                                tokenRange.getStartOffset() + end);
    }
}
