package org.jfxcore.fxml.codeinsight;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.refactoring.rename.RenamePsiElementProcessor;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.lang.Fxml2ResourceDeclarationElement;
import org.jfxcore.fxml.lang.Fxml2ResourceNameReference;
import org.jfxcore.fxml.resource.Fxml2ResourceName;

import java.util.Map;

/**
 * Renames an embedded resource: its {@code <?resource ?>} declaration and every {@code @name} and
 * {@code {ClassPathResource name}} usage of it.
 *
 * <p>A resource declaration is not ordinary PSI in either document form, so the platform's default
 * rename processing has nothing to work with: the declaration is represented by a
 * {@link Fxml2ResourceDeclarationElement}, a synthetic element that knows how to rewrite the
 * declaration text.  This processor is what routes a rename to it.
 *
 * <p>Only usages that actually resolve to the declaration are rewritten.  Resource declarations
 * are scoped to one document, so a usage anywhere else names a different resource, or an external
 * one, and carries no reference to this declaration in the first place.
 *
 * <p>A name that is not a portable file name is refused before anything is written, because the
 * compiler would reject the resulting declaration.
 */
public final class Fxml2ResourceRenameProcessor extends RenamePsiElementProcessor {

    @Override
    public boolean canProcessElement(@NotNull PsiElement element) {
        return element instanceof Fxml2ResourceDeclarationElement;
    }

    @Override
    public void renameElement(@NotNull PsiElement element,
                              @NotNull String newName,
                              UsageInfo @NotNull [] usages,
                              @Nullable com.intellij.refactoring.listeners.RefactoringElementListener listener)
            throws IncorrectOperationException {
        if (!Fxml2ResourceName.isPortable(newName)) {
            throw new IncorrectOperationException(
                    "'" + newName + "' is not a portable resource name and cannot be used as a resource name");
        }

        // Usages first: rewriting the declaration invalidates the offsets the usages were found at
        // only after the fact, but a usage rewrite must not depend on the declaration still saying
        // the old name, which is what resolving during the loop would require.
        for (UsageInfo usage : usages) {
            PsiReference reference = usage.getReference();
            if (reference instanceof Fxml2ResourceNameReference) {
                reference.handleElementRename(newName);
            }
        }

        if (element instanceof Fxml2ResourceDeclarationElement declaration) {
            declaration.setName(newName);
        }

        if (listener != null) {
            listener.elementRenamed(element);
        }
    }

    /**
     * Declines to prepare a rename dialog target of its own: the element the rename starts from is
     * already the declaration, whether the caret was on it or on one of its usages.
     */
    @Override
    public @Nullable PsiElement substituteElementToRename(@NotNull PsiElement element,
                                                          @Nullable com.intellij.openapi.editor.Editor editor) {
        return element;
    }

    @Override
    public void prepareRenaming(@NotNull PsiElement element,
                                @NotNull String newName,
                                @NotNull Map<PsiElement, String> allRenames) {
        // The declaration is the only element renamed; its usages are rewritten through their
        // references rather than as separate rename targets.
    }
}
