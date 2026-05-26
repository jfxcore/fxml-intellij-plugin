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
        if (!isFxml2Namespace(ns)) return null;
        // Only activate for FXML/2 files; leave classic FXML files to the JavaFX plugin.
        if (file instanceof XmlFile xmlFile && !Fxml2FileType.isFxml2(xmlFile)) {
            return null;
        }
        return new Fxml2NamespaceDescriptor();
    }

    /**
     * Returns {@code true} for any namespace URI that is a recognized FXML variant:
     * <ul>
     *   <li>{@code http://javafx.com/javafx}         (exact)</li>
     *   <li>{@code http://javafx.com/javafx/}        (trailing slash)</li>
     *   <li>{@code http://javafx.com/javafx/21}      (version sub-path)</li>
     *   <li>{@code http://jfxcore.org/fxml/2.0}      (exact)</li>
     *   <li>{@code http://jfxcore.org/fxml/2.0/}     (trailing slash)</li>
     * </ul>
     */
    static boolean isFxml2Namespace(@NotNull String ns) {
        return ns.equals(Fxml2ImportResolver.JAVAFX_NAMESPACE)
                || ns.startsWith(Fxml2ImportResolver.JAVAFX_NAMESPACE + "/")
                || ns.equals(Fxml2ImportResolver.FXML2_NAMESPACE)
                || ns.startsWith(Fxml2ImportResolver.FXML2_NAMESPACE + "/");
    }
}
