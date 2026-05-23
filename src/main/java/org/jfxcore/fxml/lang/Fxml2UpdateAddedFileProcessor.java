package org.jfxcore.fxml.lang;

import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.file.UpdateAddedFileProcessor;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.IncorrectOperationException;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Updates the {@code fx:subclass} attribute of a newly copied FXML2 file so that
 * it reflects the new file name instead of the original one.
 *
 * <p>Example: copying {@code MainView.fxml} (which declares
 * {@code fx:subclass="com.example.MainView"}) to {@code OtherView.fxml} will
 * produce a copy with {@code fx:subclass="com.example.OtherView"}.
 *
 * <p>This processor is invoked automatically by
 * {@link com.intellij.refactoring.copy.CopyFilesOrDirectoriesHandler} after
 * the physical file copy has been performed.
 */
public final class Fxml2UpdateAddedFileProcessor extends UpdateAddedFileProcessor {

    private static final String FX_SUBCLASS = "fx:subclass";

    @Override
    public boolean canProcessElement(@NotNull PsiFile element) {
        if (!element.getName().endsWith(".fxml")) return false;
        // The PSI file's getFileType() might still be XmlFileType at this point (before
        // the VFS file-type cache is refreshed), so we also accept plain XmlFile and
        // verify the FXML2 namespace by inspecting the root tag.
        if (!(element instanceof XmlFile xmlFile)) return false;
        XmlTag root = xmlFile.getRootTag();
        if (root == null) return false;
        String fxNs = root.getAttributeValue("xmlns:fx");
        return Fxml2ImportResolver.FXML2_NAMESPACE.equals(fxNs);
    }

    @Override
    public void update(@NotNull PsiFile element, @Nullable PsiFile originalElement)
            throws IncorrectOperationException {

        if (!(element instanceof XmlFile xmlFile)) return;

        XmlTag root = xmlFile.getRootTag();
        if (root == null) return;

        String oldSubclass = root.getAttributeValue(FX_SUBCLASS);
        if (oldSubclass == null || oldSubclass.isBlank()) return;

        // Determine the simple class name from the new file name (strip extension).
        String newFileName = element.getName();
        String newSimpleName = FileUtilRt.getNameWithoutExtension(newFileName);
        if (newSimpleName.isBlank()) return;

        // Replace only the last segment (simple class name) of the fully-qualified name.
        int lastDot = oldSubclass.lastIndexOf('.');
        String newSubclass = lastDot >= 0
                ? oldSubclass.substring(0, lastDot + 1) + newSimpleName
                : newSimpleName;

        if (newSubclass.equals(oldSubclass)) return;

        CommandProcessor.getInstance().runUndoTransparentAction(() -> root.setAttribute(FX_SUBCLASS, newSubclass));
    }
}
