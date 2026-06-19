package org.jfxcore.fxml.lang;

import com.intellij.javaee.ImplicitNamespaceDescriptorProvider;
import com.intellij.openapi.module.Module;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.xml.XmlNSDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.descriptors.Fxml2NamespaceDescriptor;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;

/**
 * Returns a non-null {@link XmlNSDescriptor} for the two FXML namespace URIs
 * ({@code http://javafx.com/javafx} and {@code http://jfxcore.org/fxml/2.0}).
 *
 * <p>{@link com.intellij.javaee.ExternalResourceManagerExBase#isIgnoredResource} checks
 * every registered {@code ImplicitNamespaceDescriptorProvider}, if any provider returns
 * a non-null descriptor for a given URI, that URI is treated as "known" and the hard
 * {@code URLReference} on the {@code xmlns} / {@code xmlns:fx} declarations resolves
 * successfully: suppressing the "URI is not registered" error.
 */
public final class Fxml2ImplicitNamespaceDescriptorProvider implements ImplicitNamespaceDescriptorProvider {

    @Override
    public @Nullable XmlNSDescriptor getNamespaceDescriptor(@Nullable Module module,
                                                             @NotNull String ns,
                                                             @Nullable PsiFile file) {
        // Recognize any FXML namespace variant (JavaFX default or FXML/2 intrinsics),
        // accepting versioned/trailing-slash forms.
        if (!Fxml2ImportResolver.isFxmlNamespace(ns)) return null;
        // Only activate for FXML/2 files; classic FXML files are out of scope.
        if (file instanceof XmlFile xmlFile && !Fxml2FileType.isFxml2(xmlFile)) {
            return null;
        }
        return new Fxml2NamespaceDescriptor();
    }
}
