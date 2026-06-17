package org.jfxcore.fxml.lang;

import com.intellij.codeInsight.daemon.ImplicitUsageProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlAttribute;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;

/**
 * Suppresses the platform's false-positive "Namespace declaration is never used" warning
 * (reported by {@code XmlUnusedNamespaceInspection}) for the FXML/2 namespace declaration
 * {@code xmlns:fx="http://jfxcore.org/fxml/2.0"}.
 *
 * <h3>Why is this needed?</h3>
 * <p>A document is classified as FXML/2 purely by the presence of the
 * {@code http://jfxcore.org/fxml/2.0} namespace URI (see {@link Fxml2FileTypeOverrider} and
 * {@link Fxml2FileType#isFxml2(CharSequence)}). When a document binds only through
 * expressions such as {@code ${...}} and never references an {@code fx:} intrinsic, the
 * {@code fx} prefix has no textual usage. The platform's {@code XmlUnusedNamespaceInspection}
 * then reports the declaration as unused and offers a quick fix to remove it.
 *
 * <p>Removing the declaration would delete the only occurrence of the namespace URI, so the
 * IDE would stop recognizing the file as FXML/2 (its file type, references, completion, and
 * annotators would all revert to plain XML / standard FXML). The declaration is therefore
 * load-bearing regardless of whether any {@code fx:} intrinsic appears, and must never be
 * flagged as unused.
 *
 * <h3>Mechanism</h3>
 * <p>{@code XmlUnusedNamespaceInspection} consults every registered
 * {@link ImplicitUsageProvider} (via its {@code isImplicitUsage(XmlAttribute)} check) before
 * reporting a namespace declaration. Returning {@code true} here for the FXML/2 namespace
 * declaration suppresses both the warning and its removal quick fix.
 */
public final class Fxml2NamespaceImplicitUsageProvider implements ImplicitUsageProvider {

    @Override
    public boolean isImplicitUsage(@NotNull PsiElement element) {
        if (!(element instanceof XmlAttribute attribute) || !attribute.isNamespaceDeclaration()) {
            return false;
        }
        return Fxml2ImportResolver.isFxml2Namespace(attribute.getValue());
    }

    @Override
    public boolean isImplicitRead(@NotNull PsiElement element) {
        return false;
    }

    @Override
    public boolean isImplicitWrite(@NotNull PsiElement element) {
        return false;
    }
}
