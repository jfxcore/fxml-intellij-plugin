package org.jfxcore.fxml.lang;

import com.intellij.psi.filters.position.NamespaceFilter;
import com.intellij.psi.filters.position.RootTagFilter;
import com.intellij.psi.meta.MetaDataContributor;
import com.intellij.psi.meta.MetaDataRegistrar;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlFileNSInfoProvider;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.descriptors.Fxml2NamespaceDescriptor;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;

/**
 * Wires the {@link Fxml2NamespaceDescriptor} into the IntelliJ XML infrastructure.
 *
 * <p>Implements two extension points:
 * <ul>
 *   <li>{@code xml.fileNSInfoProvider}: tells the XML plugin that FXML files use
 *       {@value Fxml2ImportResolver#JAVAFX_NAMESPACE} as their default namespace.</li>
 *   <li>{@code psi.metaDataContributor}: registers {@link Fxml2NamespaceDescriptor} as the
 *       metadata object for any document whose root tag carries that namespace, which makes
 *       the IntelliJ XML layer call {@code getElementDescriptor(tag)} for every tag it needs
 *       to resolve.</li>
 * </ul>
 */
public final class Fxml2NamespaceDataProvider implements XmlFileNSInfoProvider, MetaDataContributor {

    private static final String[][] NAMESPACES =
            {{"", Fxml2ImportResolver.JAVAFX_NAMESPACE}};

    @Override
    public String[][] getDefaultNamespaces(@NotNull XmlFile file) {
        if (!isFxml2(file)) return null;
        return NAMESPACES;
    }

    @Override
    public boolean overrideNamespaceFromDocType(@NotNull XmlFile file) {
        return false;
    }

    @Override
    public void contributeMetaData(@NotNull MetaDataRegistrar registrar) {
        registrar.registerMetaData(
                new RootTagFilter(new NamespaceFilter(Fxml2ImportResolver.JAVAFX_NAMESPACE)),
                Fxml2NamespaceDescriptor.class);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Returns {@code true} for FXML/2 files. */
    private static boolean isFxml2(@NotNull XmlFile file) {
        return Fxml2FileType.isFxml2(file);
    }
}
