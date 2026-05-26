package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.impl.FakeVirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.refactoring.copy.CopyFilesOrDirectoriesHandler;
import com.intellij.refactoring.copy.CopyHandler;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.lang.Fxml2FileTypeOverrider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that copying FXML files via the IntelliJ Project-Explorer F5 copy action works.
 *
 * <p>Root cause: {@link Fxml2FileTypeOverrider#getOverriddenFileType} called
 * {@code file.isValid()} without guarding against the
 * {@link UnsupportedOperationException} thrown by
 * {@link com.intellij.openapi.vfs.newvfs.impl.StubVirtualFile#isValid()} (the
 * base class of {@link FakeVirtualFile}).  The copy dialog calls
 * {@code FileTypeChooser.getKnownFileTypeOrAssociate(parentDir, "NewName.fxml", project)},
 * which internally creates a {@link FakeVirtualFile} and asks for its file type.
 * That file-type query ran our overrider, which threw on {@code isValid()}, which
 * propagated through {@code doAction()} and silently prevented the dialog from
 * closing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2CopyFileTest extends Fxml2TestBase {

    // -----------------------------------------------------------------------
    // 1. Unit-level: Fxml2FileTypeOverrider must not throw on a FakeVirtualFile
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link Fxml2FileTypeOverrider#getOverriddenFileType} returns
     * {@code null} (and does NOT throw) when called with a {@link FakeVirtualFile}.
     *
     * <p>IntelliJ passes a {@code FakeVirtualFile} to every {@code FileTypeOverrider}
     * when checking whether the to-be-copied file has a known file type.  The overrider
     * must guard against virtual files that do not support content reads and return
     * {@code null} in that case.
     */
    @Test
    void fileTypeOverrider_doesNotThrow_onFakeVirtualFile() {
        // We need a real parent VirtualFile to construct FakeVirtualFile.
        VirtualFile sourceRoot = getFixture().getTempDirFixture().getFile("");
        assertNotNull(sourceRoot, "source root should exist");

        FakeVirtualFile fake = new FakeVirtualFile(sourceRoot, "SomeView.fxml");

        var overrider = new Fxml2FileTypeOverrider();
        // Must not throw; must return null (not Fxml2FileType) for a non-existent file.
        assertDoesNotThrow(
                () -> {
                    var result = overrider.getOverriddenFileType(fake);
                    // The file has no content, so the overrider cannot detect the namespace.
                    assertNull(result, "overrider should return null for a FakeVirtualFile");
                },
                "Fxml2FileTypeOverrider must not throw UnsupportedOperationException " +
                        "when called with a FakeVirtualFile");
    }

    // -----------------------------------------------------------------------
    // 2. Integration: CopyFilesOrDirectoriesHandler can copy an FXML file
    // -----------------------------------------------------------------------

    /**
     * Verifies that the platform's {@code CopyFilesOrDirectoriesHandler}:
     * <ul>
     *   <li>reports {@code canCopy = true} for a standalone FXML file, and</li>
     *   <li>successfully copies the file to the same directory under a new name.</li>
     * </ul>
     *
     * <p>In unit-test mode IntelliJ bypasses the dialog and copies directly, so this
     * test exercises the copy mechanics without needing a real UI.
     */
    @Test
    void copyFxml2File_succeeds() {
        // Create a minimal FXML file in the source root.
        PsiFile original = getFixture().addFileToProject(
                "test/OriginalView.fxml",
                fxml("javafx.scene.control.Button", "  <Button text=\"Hello\"/>\n"));

        assertNotNull(original, "original FXML file should exist");
        assertEquals(Fxml2FileType.INSTANCE, original.getVirtualFile().getFileType(),
                "file must be detected as Fxml2FileType");

        // canCopy must return true (needs read access).
        var elements = new com.intellij.psi.PsiElement[]{original};
        boolean canCopy = ReadAction.compute(() -> CopyHandler.canCopy(elements));
        assertTrue(canCopy, "CopyHandler.canCopy must return true for an FXML PsiFile");

        // Perform the copy programmatically.
        PsiDirectory dir = ReadAction.compute(original::getContainingDirectory);
        assertNotNull(dir);

        PsiFile[] copied = new PsiFile[1];
        WriteAction.runAndWait(() -> {
            try {
                copied[0] = CopyFilesOrDirectoriesHandler.copyToDirectory(
                        original, "CopiedView.fxml", dir);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertNotNull(copied[0], "copied file should not be null");
        assertEquals("CopiedView.fxml", copied[0].getName(),
                "copied file should have the new name");
        assertEquals(Fxml2FileType.INSTANCE, copied[0].getVirtualFile().getFileType(),
                "copied file must be detected as Fxml2FileType");
    }

    // -----------------------------------------------------------------------
    // 3. fx:subclass is updated to match the new file name after copy
    // -----------------------------------------------------------------------

    /**
     * Verifies that after copying an FXML file the {@code fx:subclass} attribute in
     * the copy reflects the new file name while the package prefix is preserved.
     *
     * <p>E.g. copying {@code OriginalView.fxml} (with
     * {@code fx:subclass="test.OriginalView"}) to {@code RenamedView.fxml} must
     * produce a copy with {@code fx:subclass="test.RenamedView"}.
     */
    @Test
    void copyFxml2File_updatesSubclass() {
        // Create a minimal FXML file whose fx:subclass we will inspect after copy.
        PsiFile original = getFixture().addFileToProject(
                "subtest/OriginalView.fxml",
                fxml("javafx.scene.control.Button", "  <Button/>\n", "com.example.OriginalView"));

        assertNotNull(original);
        assertEquals(Fxml2FileType.INSTANCE, original.getVirtualFile().getFileType());

        PsiDirectory dir = ReadAction.compute(original::getContainingDirectory);
        assertNotNull(dir);

        PsiFile[] copied = new PsiFile[1];
        WriteAction.runAndWait(() -> {
            try {
                copied[0] = CopyFilesOrDirectoriesHandler.copyToDirectory(
                        original, "RenamedView.fxml", dir);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertNotNull(copied[0], "copied file should not be null");
        assertEquals("RenamedView.fxml", copied[0].getName());

        // Verify the fx:subclass was updated.
        String subclass = ReadAction.compute(() -> {
            XmlTag root = ((XmlFile) copied[0]).getRootTag();
            return root == null ? null : root.getAttributeValue("fx:subclass");
        });

        assertEquals("com.example.RenamedView", subclass,
                "fx:subclass must be updated to reflect the new file name");
    }
}
