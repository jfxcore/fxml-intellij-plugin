package org.jfxcore.fxml.lang;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.FakePsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resource.Fxml2ResourceName;

/**
 * A fake {@link PsiElement} that represents a CSS class-selector token (e.g. {@code .mystyle1})
 * inside a plain CSS file.
 *
 * <p>Because the IntelliJ CSS PSI is not available in Community Edition, we wrap the
 * enclosing {@link PsiFile} and a sub-{@link TextRange} so that navigation via
 * {@code PsiElementBase.navigate()} (which delegates to {@code PsiNavigationSupport}
 * using {@link #getContainingFile()} and {@link #getTextOffset()}) works out of the box.
 *
 * <p>A selector written in the payload of a {@code <?resource ?>} declaration carries the name of
 * the resource it came from, so that a consumer that needs to say where the selector lives does
 * not have to scan the declaring document a second time to find out.
 */
public final class CssSelectorElement extends FakePsiElement {

    private final @NotNull PsiFile myFile;
    private final @NotNull TextRange myRange;   // range of ".name" inside the file text
    private final @NotNull String myClassName;  // the name without the dot
    private final @Nullable Fxml2ResourceName myEmbeddedResource;

    CssSelectorElement(@NotNull PsiFile file, @NotNull TextRange range) {
        this(file, range,
             file.getText().substring(range.getStartOffset() + 1, range.getEndOffset()), null);
    }

    CssSelectorElement(@NotNull PsiFile file,
                       @NotNull TextRange range,
                       @NotNull String className,
                       @Nullable Fxml2ResourceName embeddedResource) {
        this.myFile             = file;
        this.myRange            = range;
        this.myClassName        = className;
        this.myEmbeddedResource = embeddedResource;
    }

    /**
     * Returns the name of the embedded resource this selector is written in, or {@code null} when
     * it is written in a {@code .css} file of its own.
     */
    public @Nullable Fxml2ResourceName embeddedResource() {
        return myEmbeddedResource;
    }

    // FakePsiElement requires a non-null parent
    @Override
    public @NotNull PsiElement getParent() {
        return myFile;
    }

    // -----------------------------------------------------------------------
    // Navigation: PsiElementBase.navigate() uses getContainingFile()+getTextOffset()
    // -----------------------------------------------------------------------

    @Override
    public @NotNull PsiFile getContainingFile() {
        return myFile;
    }

    @Override
    public int getTextOffset() {
        return myRange.getStartOffset();
    }

    @Override
    public @NotNull TextRange getTextRange() {
        return myRange;
    }

    // -----------------------------------------------------------------------
    // Presentation (tooltip shown on Ctrl+hover)
    // -----------------------------------------------------------------------

    @Override
    public @NotNull String getPresentableText() {
        return "Class selector ." + myClassName + " [" + myFile.getName() + "]";
    }

    @Override
    public @NotNull String getName() {
        return myClassName;
    }
}
