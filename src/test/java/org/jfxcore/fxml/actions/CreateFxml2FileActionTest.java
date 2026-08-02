package org.jfxcore.fxml.actions;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import org.jfxcore.fxml.Fxml2TestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how the "New -> FXML/2 File" action turns the name entered in the dialog into the
 * created file name and the generated {@code fx:subclass} value, and which names it accepts.
 *
 * <p>The compiler derives the class of a document from its file name and requires the simple name
 * of {@code fx:subclass} to match it, so only names whose base name is a Java identifier can be
 * compiled. The supported extensions are {@code .fxml} and {@code .fxmlx}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CreateFxml2FileActionTest extends Fxml2TestBase {

    // -----------------------------------------------------------------------
    // File name and fx:subclass

    @Test
    void appendsFxmlExtensionToPlainName() {
        assertCreated("MyFile", "MyFile.fxml");
    }

    @Test
    void keepsFxmlExtension() {
        assertCreated("MyFile.fxml", "MyFile.fxml");
    }

    @Test
    void keepsFxmlxExtension() {
        assertCreated("MyFile.fxmlx", "MyFile.fxmlx");
    }

    @Test
    void qualifiesSubclassWithPackageOfTargetDirectory() {
        PsiFile anchor = getFixture().addFileToProject("com/example/anchor.txt", "");
        PsiDirectory dir = ReadAction.compute(anchor::getContainingDirectory);
        PsiFile created = create("MyFile", dir);

        assertEquals("MyFile.fxml", ReadAction.compute(created::getName));
        assertSubclass(created, "com.example.MyFile");
    }

    // -----------------------------------------------------------------------
    // Name validation

    @Test
    void acceptsIdentifierWithSupportedExtension() {
        assertValid("MyFile");
        assertValid("MyFile.fxml");
        assertValid("MyFile.fxmlx");
        assertValid("my_file2");
    }

    @Test
    void rejectsNameThatIsNoJavaIdentifier() {
        assertInvalid("MyFile.txt");
        assertInvalid("MyFile.txt.fxml");
        assertInvalid("my-file.fxml");
        assertInvalid("2ndFile");
        assertInvalid("sub/dir/MyFile.fxml");
    }

    // -----------------------------------------------------------------------
    // Helpers

    private void assertCreated(String enteredName, String expectedFileName) {
        PsiFile anchor = getFixture().addFileToProject("anchor.txt", "");
        PsiDirectory dir = ReadAction.compute(anchor::getContainingDirectory);
        PsiFile created = create(enteredName, dir);

        assertEquals(expectedFileName, ReadAction.compute(created::getName));
        assertSubclass(created, "MyFile");
    }

    private static PsiFile create(String enteredName, PsiDirectory dir) {
        PsiFile created = WriteCommandAction.writeCommandAction(dir.getProject())
                .compute(() -> new CreateFxml2FileAction().createFile(enteredName, "Fxml2File", dir));
        assertNotNull(created, "action must create a file");
        return created;
    }

    private static void assertSubclass(PsiFile file, String expectedClassName) {
        String text = ReadAction.compute(file::getText);
        assertTrue(text.contains("fx:subclass=\"" + expectedClassName + "\""),
                   "expected fx:subclass=\"" + expectedClassName + "\" in:\n" + text);
    }

    private static void assertValid(String enteredName) {
        assertNull(CreateFxml2FileAction.NAME_VALIDATOR.getErrorText(enteredName),
                   "expected '" + enteredName + "' to be accepted");
        assertTrue(CreateFxml2FileAction.NAME_VALIDATOR.canClose(enteredName));
    }

    private static void assertInvalid(String enteredName) {
        assertNotNull(CreateFxml2FileAction.NAME_VALIDATOR.getErrorText(enteredName),
                      "expected '" + enteredName + "' to be rejected");
    }
}
