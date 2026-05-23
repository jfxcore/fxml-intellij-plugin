package org.jfxcore.fxml.lang;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.source.xml.TagNameReference;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.xml.DefaultXmlExtension;
import com.intellij.xml.util.XmlUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;
import org.jfxcore.fxml.resolve.Fxml2TagResolver;

/**
 * Plugs into the IntelliJ XML infrastructure for FXML 2.0 files.
 * The key override is {@link #createTagNameReference}, which enables Ctrl+click
 * navigation from element tag names to their Java class declarations.
 */
public final class Fxml2XmlExtension extends DefaultXmlExtension {

    @Override
    public boolean isAvailable(@NotNull PsiFile file) {
        return Fxml2FileType.isFxml2(file);
    }

    @Override
    public TagNameReference createTagNameReference(@NotNull ASTNode nameElement, boolean startTagFlag) {
        return new Fxml2TagNameReference(nameElement, startTagFlag);
    }

    @Override
    public String @Nullable[][] getNamespacesFromDocument(XmlDocument parent, boolean declarationsExist) {
        return XmlUtil.getDefaultNamespaces(parent);
    }

    /**
     * Resolves every FXML 2.0 element tag name to the corresponding {@link PsiClass}
     * via {@link Fxml2ImportResolver}.
     */
    public static final class Fxml2TagNameReference extends TagNameReference {

        public Fxml2TagNameReference(@NotNull ASTNode element, boolean startTagFlag) {
            super(element, startTagFlag);
        }

        /**
         * Always soft: we own all error reporting via {@link org.jfxcore.fxml.annotator.Fxml2TagAnnotator}.
         * Returning {@code true} suppresses IntelliJ's built-in "Cannot resolve symbol" from
         * the XML reference infrastructure, which would otherwise fire once per name token
         * (start tag + end tag = two errors) with the wrong highlight range.
         */
        @Override
        public boolean isSoft() {
            return true;
        }

        /**
         * For dotted property names (e.g. {@code selectionModel.selectionMode}), returns
         * an empty range so this reference does not compete with the per-segment references
         * registered on the {@link XmlTag} element by {@code StaticPropertyTagNameReferenceProvider}.
         * Those XmlTag-level references are authoritative for dotted names.
         *
         * <p>For plain (non-dotted) names, delegates to the parent implementation.
         */
        @Override
        public @NotNull TextRange getRangeInElement() {
            TextRange fullRange = super.getRangeInElement();
            XmlTag tag = getTagElement();
            if (tag == null) return fullRange;
            // For dotted names, yield to the XmlTag-level per-segment references
            if (tag.getLocalName().contains(".")) return TextRange.EMPTY_RANGE;
            return fullRange;
        }

        @Override
        public @Nullable PsiElement resolve() {
            XmlTag tag = getTagElement();
            if (tag == null) return null;
            PsiFile file = tag.getContainingFile();
            if (!(file instanceof XmlFile xmlFile)) return null;
            if (Fxml2ImportResolver.FXML2_NAMESPACE.equals(tag.getNamespace())) return null;
            String localName = tag.getLocalName();
            if (localName.isEmpty()) return null;
            // Dotted names are resolved by the per-segment XmlTag-level references
            if (localName.contains(".")) return null;

            // Plain name: try as a Java class, then as a property tag.
            PsiClass resolved = Fxml2ImportResolver.resolve(localName, xmlFile);
            if (resolved != null) return resolved;
            return Fxml2TagResolver.resolvePropertyTagDeclaration(tag, localName, xmlFile);
        }


        @Override
        public @Nullable PsiElement bindToElement(@NotNull PsiElement element) {
            if (element instanceof PsiClass psiClass) {
                XmlTag tag = getTagElement();
                if (tag != null) {
                    // Always use the simple (unqualified) class name as the tag name.
                    // FXML 2.0 resolves element tags via <?import?> declarations, so a simple
                    // name is always correct as long as there is a matching import.
                    // Reading the stale injected-XML prolog to check whether the name is
                    // resolvable can produce the wrong answer during rename refactoring
                    // (imports in the host file are updated in the same write action but the
                    // prolog may not yet reflect the change), causing a spurious fallback to
                    // the fully-qualified name.
                    String simpleName = psiClass.getName();
                    if (simpleName != null) {
                        try {
                            return tag.setName(simpleName);
                        } catch (Exception ignored) {}
                    }
                }
            }
            return super.bindToElement(element);
        }
    }
}
