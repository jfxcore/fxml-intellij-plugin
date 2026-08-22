package org.jfxcore.fxml.lang;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.impl.FakePsiElement;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.codeinsight.Fxml2ResourceDeclarationEditor;
import org.jfxcore.fxml.resource.Fxml2ResourceEntry;
import org.jfxcore.fxml.resource.Fxml2ResourceName;
import org.jfxcore.fxml.resource.Fxml2ResourceQuoting;

/**
 * The declaration site of an embedded resource, as a navigable and renameable element.
 *
 * <p>A resource name is not a PSI element in either document form: in a standalone document it is
 * a stretch of a processing instruction's text, and in embedded markup it is a stretch of a Java or
 * Kotlin string literal.  Wrapping it in a fake element gives it the identity that navigation,
 * find usages and rename all need, without inventing a parser for it.
 *
 * <p>Navigation works through {@code getContainingFile()} and {@code getTextOffset()}, so it lands
 * on the name itself rather than on the declaration as a whole.  Renaming rewrites the declaration
 * text, adding or removing quoting as the new name requires.
 */
public final class Fxml2ResourceDeclarationElement extends FakePsiElement implements PsiNamedElement {

    private final PsiFile file;
    private final TextRange nameRange;
    private final String name;

    public Fxml2ResourceDeclarationElement(@NotNull Fxml2ResourceEntry entry) {
        this.file = entry.declaringFile();
        this.nameRange = entry.nameRange();
        this.name = entry.name().value();
    }

    /** Returns {@code true} when this element is the declaration site of {@code entry}. */
    public boolean declares(@NotNull Fxml2ResourceEntry entry) {
        return name.equals(entry.name().value()) && file.equals(entry.declaringFile());
    }

    @Override
    public @NotNull PsiElement getParent() {
        return file;
    }

    @Override
    public @NotNull PsiFile getContainingFile() {
        return file;
    }

    @Override
    public int getTextOffset() {
        return nameRange.getStartOffset();
    }

    @Override
    public @NotNull TextRange getTextRange() {
        return nameRange;
    }

    @Override
    public @NotNull String getName() {
        return name;
    }

    @Override
    public @NotNull String getPresentableText() {
        return "Embedded resource '" + name + "' [" + file.getName() + "]";
    }

    /**
     * Renames the declared resource.
     *
     * <p>The new name is written in the least intrusive quoting style that fits it, so that a name
     * gaining a space gains quotes and a name losing one loses them again.  A name that is not a
     * portable file name is refused rather than written, because the compiler would reject it.
     */
    @Override
    public @NotNull PsiElement setName(@NotNull String newName) throws IncorrectOperationException {
        if (!Fxml2ResourceName.isPortable(newName)) {
            throw new IncorrectOperationException("'" + newName + "' is not a portable resource name");
        }

        Fxml2ResourceEntry entry = Fxml2ResourceDeclarationEditor.findDeclaration(file, name);
        if (entry == null) return this;

        Fxml2ResourceDeclarationEditor.replace(
                file.getProject(), entry,
                entry.declaration().quotedNameSpan(),
                Fxml2ResourceQuoting.required(newName).write(newName));

        Fxml2ResourceEntry renamed = Fxml2ResourceDeclarationEditor.findDeclaration(file, newName);
        return renamed != null ? new Fxml2ResourceDeclarationElement(renamed) : this;
    }

    @Override
    public boolean isEquivalentTo(@Nullable PsiElement another) {
        return another instanceof Fxml2ResourceDeclarationElement other
                && name.equals(other.name)
                && file.equals(other.file);
    }
}
