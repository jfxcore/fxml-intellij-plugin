package org.jfxcore.fxml.descriptors;

import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.xml.XmlElementDescriptor;
import com.intellij.xml.XmlNSDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.lang.Fxml2NamespaceDataProvider;

/**
 * Namespace descriptor for FXML 2.0 files.
 *
 * <p>Registered via {@link Fxml2NamespaceDataProvider} as the metadata object for the
 * {@code http://javafx.com/javafx} namespace in FXML 2.0 files.  The IntelliJ platform calls
 * {@link #getElementDescriptor(XmlTag)} when it needs to resolve any XML tag in the file,
 * which in turn drives Ctrl+click (Go to Declaration) navigation.
 */
public final class Fxml2NamespaceDescriptor implements XmlNSDescriptor {

    private XmlFile myFile;

    @Override
    public @Nullable XmlElementDescriptor getElementDescriptor(@NotNull XmlTag tag) {
        // Delegate child-tag resolution up to the parent descriptor when present,
        // otherwise resolve the root tag directly.
        XmlTag parentTag = tag.getParentTag();
        if (parentTag != null) {
            XmlElementDescriptor parentDescriptor = parentTag.getDescriptor();
            if (parentDescriptor != null) {
                return parentDescriptor.getElementDescriptor(tag, parentTag);
            }
        }
        // Root tag (or orphaned tag): resolve the local name against imports.
        return new Fxml2ClassTagDescriptor(tag.getLocalName(), tag);
    }

    @Override
    public XmlElementDescriptor @NotNull [] getRootElementsDescriptors(@Nullable XmlDocument document) {
        return XmlElementDescriptor.EMPTY_ARRAY;
    }

    @Override
    public @Nullable XmlFile getDescriptorFile() {
        return myFile;
    }

    @Override
    public @Nullable PsiElement getDeclaration() {
        return myFile;
    }

    @Override
    public String getName(PsiElement context) {
        return null;
    }

    @Override
    public String getName() {
        return null;
    }

    @Override
    public void init(PsiElement element) {
        myFile = (XmlFile) element.getContainingFile();
    }
}
