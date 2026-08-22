package org.jfxcore.fxml.lang;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.ElementManipulators;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resource.Fxml2ResourceEntry;
import org.jfxcore.fxml.resource.Fxml2ResourceModel;
import org.jfxcore.fxml.resource.Fxml2ResourceName;
import org.jfxcore.fxml.resource.Fxml2ResourceQuoting;

import java.util.Objects;

/**
 * A reference from an {@code @name} or {@code {ClassPathResource name}} usage to the
 * {@code <?resource ?>} declaration of that name in the same document.
 *
 * <p>The runtime resolves a simple relative name by looking for an embedded resource first and an
 * external file second, and this reference is the first half of that: it resolves when the
 * document declares the name, and stays unresolved otherwise so that the file references the
 * contributor also attaches can take over.
 *
 * <p>Matching is exact, in case and in interior whitespace, because the runtime derives the
 * resource file name from the logical name verbatim.  A near miss would not resolve at runtime
 * either, and reporting it as resolved here would hide a real problem.
 */
public final class Fxml2ResourceNameReference extends PsiReferenceBase<XmlAttributeValue> {

    private final String resourceName;
    private final XmlFile contextFile;

    public Fxml2ResourceNameReference(@NotNull XmlAttributeValue element,
                                      @NotNull TextRange rangeInElement,
                                      @NotNull String resourceName,
                                      @NotNull XmlFile contextFile) {
        super(element, rangeInElement, /* soft= */ true);
        this.resourceName = resourceName;
        this.contextFile = contextFile;
    }

    /** Returns {@code true} when the document declares an embedded resource with this name. */
    public boolean isDeclared() {
        return entry() != null;
    }

    @Override
    public @Nullable PsiElement resolve() {
        Fxml2ResourceEntry entry = entry();
        return entry == null ? null : new Fxml2ResourceDeclarationElement(entry);
    }

    /**
     * Rewrites the usage to name {@code newElementName}.
     *
     * <p>The usage is quoted when the new name needs it, mirroring the declaration: the {@code @}
     * prefix notation accepts a single-quoted name, which is how a name containing spaces is
     * written in a usage.
     */
    @Override
    public @NotNull PsiElement handleElementRename(@NotNull String newElementName)
            throws IncorrectOperationException {
        String written = Fxml2ResourceQuoting.needsQuoting(newElementName)
                ? Fxml2ResourceQuoting.SINGLE.write(newElementName)
                : newElementName;

        return ElementManipulators.handleContentChange(getElement(), getRangeInElement(), written);
    }

    @Override
    public boolean isReferenceTo(@NotNull PsiElement element) {
        return element instanceof Fxml2ResourceDeclarationElement declaration
                && Objects.equals(declaration.getName(), resourceName)
                && isDeclared();
    }

    /** Returns the declaration this reference resolves to, or {@code null} when there is none. */
    private @Nullable Fxml2ResourceEntry entry() {
        return Fxml2ResourceName.isPortable(resourceName)
                ? Fxml2ResourceModel.of(contextFile).resolve(resourceName)
                : null;
    }
}
