package org.jfxcore.fxml.lang;

import com.intellij.lang.ASTNode;
import com.intellij.lang.Language;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.FakePsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A thin {@link FakePsiElement} wrapper that reports a narrow {@link #getTextRange()} covering
 * only one identifier segment within a larger leaf token (e.g. "scene" within the
 * {@code XML_TAG_CHARACTERS} token "javafx.scene.control.Label").
 *
 * <p>Its sole purpose is to give {@code IdentifierHighlightingComputer.findTarget()} a
 * <em>narrow</em> text range for caching, so that the identifier-under-caret result
 * computed for one segment (e.g. "javafx") is NOT reused when the caret moves to a
 * different segment of the same token (e.g. "scene").
 *
 * <p>{@link #getParent()} returns the <em>parent of</em> the actual underlying leaf token
 * (e.g. {@code XmlAttributeValue} for an {@code XML_ATTRIBUTE_VALUE_TOKEN} leaf, or
 * {@code XmlProcessingInstruction} for an {@code XML_TAG_CHARACTERS} leaf inside an import PI).
 * Skipping the leaf-token level lets {@code SharedPsiElementImplUtil.findReferenceAt()} walk
 * directly to the element that owns the per-segment references, and lets
 * {@code elementsAroundOffsetUp} compute correct parent offsets without going through the
 * intermediate {@code XmlToken} node.
 *
 * <p><b>Why {@code getTextOffset()} is intentionally left at 0</b>:<br>
 * {@code FakePsiElement} implements {@link com.intellij.psi.PsiNamedElement}, so
 * {@code TargetElementUtilBase.getNamedElement()} would otherwise treat this wrapper as
 * a named declaration (because its default {@code getTextOffset() == getTextRange().getStartOffset()}
 * check would pass).  Leaving {@code getTextOffset()} at the {@code FakePsiElement} default of
 * {@code 0} makes that check fail (since segment ranges are never at offset 0 in a real file),
 * preventing a spurious {@code PsiSymbolDeclaration} from being generated and competing with
 * the real {@code PsiSymbolReference} during Ctrl+hover navigation.
 */
@SuppressWarnings("UnusedDeclaration") // registered indirectly via Fxml2FileViewProvider
final class Fxml2SegmentElement extends FakePsiElement {

    private final PsiElement myLeaf;
    private final TextRange myAbsoluteRange;

    /** Lazily-created {@link SegmentNode} wrapper; see {@link #getNode()}. */
    @Nullable private volatile ASTNode mySegmentNode;

    Fxml2SegmentElement(@NotNull PsiElement leaf, @NotNull TextRange absoluteSegmentRange) {
        myLeaf = leaf;
        myAbsoluteRange = absoluteSegmentRange;
    }

    // -----------------------------------------------------------------------
    // Identity / tree
    // -----------------------------------------------------------------------

    /**
     * Returns the <em>parent of</em> the real leaf token so that:
     * <ul>
     *   <li>reference-walking in {@code SharedPsiElementImplUtil.findReferenceAt()} reaches
     *       the element that owns the per-segment references (e.g. {@code XmlAttributeValue},
     *       {@code XmlProcessingInstruction}, {@code XmlTag}) without an extra hop through
     *       the intermediate {@code XmlToken}; and</li>
     *   <li>{@code elementsAroundOffsetUp} computes correct {@code offsetInElement} values
     *       for each ancestor, because {@link #getStartOffsetInParent()} is computed relative
     *       to this same parent.</li>
     * </ul>
     */
    @Override
    public @NotNull PsiElement getParent() {
        PsiElement p = myLeaf.getParent();
        return p != null ? p : myLeaf;
    }

    /**
     * Returns the start of this segment's text range relative to the parent element
     * (i.e. relative to {@link #getParent()}'s text range start).
     *
     * <p>{@code FakePsiElement} defaults this to {@code 0}, which would cause
     * {@code elementsAtOffsetUp} to pass a wrong {@code offsetInElement} to ancestor
     * elements when walking up the PSI tree.
     */
    @Override
    public int getStartOffsetInParent() {
        PsiElement parent = getParent();
        return myAbsoluteRange.getStartOffset() - parent.getTextRange().getStartOffset();
    }

    /**
     * Returns a thin {@link ASTNode} wrapper whose {@link ASTNode#getChars()} returns only
     * the segment's characters (not the full leaf-token text).
     *
     * <p>IntelliJ's {@code CompletionAssertions.assertCompletionPositionPsiConsistent} checks:
     * <pre>
     *   fileCopyText.subSequence(element.getTextRange().getStartOffset(),
     *                            element.getTextRange().getEndOffset())
     *       == element.getNode().getChars()
     * </pre>
     * Without this override, {@code getNode().getChars()} returns the <em>full</em>
     * {@code XmlToken}'s text (e.g. {@code "IntellijIdeaRulezzz "}, 20 chars), while
     * {@code getTextRange()} covers only the segment (e.g. {@code "IntellijIdeaRulezzz"},
     * 19 chars), causing the assertion to fail every time completion is invoked.
     *
     * <p>The {@link SegmentNode#getElementType()} delegate still reports the original token
     * type, so callers such as {@code XmlCompletionContributor} continue to work correctly.
     */
    @Override
    public @Nullable ASTNode getNode() {
        ASTNode real = myLeaf.getNode();
        if (real == null) return null;
        ASTNode n = mySegmentNode;
        if (n == null) {
            mySegmentNode = n = new SegmentNode(real, myLeaf.getTextRange().getStartOffset(), myAbsoluteRange);
        }
        return n;
    }

    @Override
    public @NotNull Language getLanguage() {
        return myLeaf.getLanguage();
    }

    @Override
    public @NotNull PsiFile getContainingFile() {
        return myLeaf.getContainingFile();
    }

    @Override
    public boolean isValid() {
        return myLeaf.isValid();
    }

    /** Must be {@code true} so that {@code collectCodeBlockMarkerRanges} uses the range. */
    @Override
    public boolean isPhysical() {
        return true;
    }

    // -----------------------------------------------------------------------
    // Text range: the whole point of this class
    // -----------------------------------------------------------------------

    @Override
    public @NotNull TextRange getTextRange() {
        return myAbsoluteRange;
    }

    // NOTE: getTextOffset() is intentionally NOT overridden here.
    // FakePsiElement.getTextOffset() returns 0, which prevents TargetElementUtilBase
    // from treating this wrapper as a named declaration (it checks
    // parent.getTextOffset() == element.getTextRange().getStartOffset(),
    // which would be 0 == E and therefore false for any real file position E).

    @Override
    public int getTextLength() {
        return myAbsoluteRange.getLength();
    }

    @Override
    public @NotNull String getText() {
        PsiFile file = myLeaf.getContainingFile();
        if (file == null) return "";
        CharSequence chars = file.getViewProvider().getContents();
        int start = myAbsoluteRange.getStartOffset();
        int end   = myAbsoluteRange.getEndOffset();
        if (start < 0 || end > chars.length()) return "";
        return chars.subSequence(start, end).toString();
    }

    @Override
    public char @NotNull [] textToCharArray() {
        return getText().toCharArray();
    }

    // -----------------------------------------------------------------------
    // PsiNamedElement (required by FakePsiElement)
    // -----------------------------------------------------------------------

    @Override
    public @NotNull String getName() {
        return getText();
    }

    // -----------------------------------------------------------------------
    // SegmentNode: minimal ASTNode wrapper reporting only the segment chars
    // -----------------------------------------------------------------------

    /**
     * A lightweight {@link ASTNode} wrapper that delegates everything to the real leaf node
     * except {@link #getChars()}/{@link #getText()}/{@link #textContains}/{@link #getStartOffset}/
     * {@link #getTextLength}/{@link #getTextRange}, which are overridden to reflect only the
     * narrow segment range rather than the full token.
     */
    private static final class SegmentNode implements ASTNode {

        private final ASTNode delegate;
        private final CharSequence segChars;
        private final TextRange segRange;

        SegmentNode(@NotNull ASTNode delegate, int tokenStartOffset, @NotNull TextRange segRange) {
            this.delegate = delegate;
            this.segRange = segRange;
            CharSequence full = delegate.getChars();
            int relStart = segRange.getStartOffset() - tokenStartOffset;
            int relEnd   = segRange.getEndOffset()   - tokenStartOffset;
            this.segChars = (relStart >= 0 && relEnd <= full.length() && relStart < relEnd)
                            ? full.subSequence(relStart, relEnd) : full;
        }

        // Text content: return segment-only chars
        @Override public @NotNull IElementType getElementType() { return delegate.getElementType(); }
        @Override public @NotNull String getText() { return segChars.toString(); }
        @Override public @NotNull CharSequence getChars() { return segChars; }
        @Override public boolean textContains(char c) {
            for (int i = 0; i < segChars.length(); i++) if (segChars.charAt(i) == c) return true;
            return false;
        }

        // Positional info: use the segment range
        @Override public int getStartOffset() { return segRange.getStartOffset(); }
        @Override public int getTextLength() { return segRange.getLength(); }
        @Override public TextRange getTextRange() { return segRange; }

        // Tree navigation: delegate to real node
        @Override public @Nullable ASTNode getTreeParent() { return delegate.getTreeParent(); }
        @Override public @Nullable ASTNode getFirstChildNode() { return null; } // leaf
        @Override public @Nullable ASTNode getLastChildNode() { return null; }  // leaf
        @Override public @Nullable ASTNode getTreeNext() { return delegate.getTreeNext(); }
        @Override public @Nullable ASTNode getTreePrev() { return delegate.getTreePrev(); }
        @Override public ASTNode @NotNull [] getChildren(@Nullable TokenSet filter) { return EMPTY_ARRAY; }

        // Mutation: not supported on a virtual/read-only wrapper
        @Override public void addChild(@NotNull ASTNode child) { throw new UnsupportedOperationException(); }
        @Override public void addChild(@NotNull ASTNode child, @Nullable ASTNode anchorBefore) { throw new UnsupportedOperationException(); }
        @Override public void addLeaf(@NotNull IElementType type, @NotNull CharSequence text, @Nullable ASTNode anchorBefore) { throw new UnsupportedOperationException(); }
        @Override public void removeChild(@NotNull ASTNode child) { throw new UnsupportedOperationException(); }
        @Override public void removeRange(@NotNull ASTNode first, @Nullable ASTNode keep) { throw new UnsupportedOperationException(); }
        @Override public void replaceChild(@NotNull ASTNode oldChild, @NotNull ASTNode newChild) { throw new UnsupportedOperationException(); }
        @Override public void replaceAllChildrenToChildrenOf(@NotNull ASTNode anotherParent) { throw new UnsupportedOperationException(); }
        @Override public void addChildren(@NotNull ASTNode first, @Nullable ASTNode stop, @Nullable ASTNode anchor) { throw new UnsupportedOperationException(); }

        // Copy / search: SegmentNode is a virtual wrapper; these delegate to the real node
        @Override public @NotNull Object clone() { throw new UnsupportedOperationException(); }
        @Override public @Nullable ASTNode copyElement() { return delegate.copyElement(); }
        @Override public @Nullable ASTNode findLeafElementAt(int offset) {
            return (offset >= 0 && offset < segRange.getLength()) ? this : null;
        }
        @Override public @Nullable ASTNode findChildByType(@NotNull IElementType type) { return null; }
        @Override public @Nullable ASTNode findChildByType(@NotNull IElementType type, @Nullable ASTNode anchor) { return null; }
        @Override public @Nullable ASTNode findChildByType(@NotNull TokenSet typesSet) { return null; }
        @Override public @Nullable ASTNode findChildByType(@NotNull TokenSet typesSet, @Nullable ASTNode anchor) { return null; }

        // PSI link: return the original leaf so PSI-aware code still works
        @Override public @Nullable PsiElement getPsi() { return delegate.getPsi(); }
        @Override public @Nullable <T extends PsiElement> T getPsi(@NotNull Class<T> clazz) { return delegate.getPsi(clazz); }

        // User data: delegate to the real node
        @Override public @Nullable <T> T getUserData(@NotNull Key<T> key) { return delegate.getUserData(key); }
        @Override public <T> void putUserData(@NotNull Key<T> key, @Nullable T value) { delegate.putUserData(key, value); }
        @Override public @Nullable <T> T getCopyableUserData(@NotNull Key<T> key) { return delegate.getCopyableUserData(key); }
        @Override public <T> void putCopyableUserData(@NotNull Key<T> key, @Nullable T value) { delegate.putCopyableUserData(key, value); }
    }
}
