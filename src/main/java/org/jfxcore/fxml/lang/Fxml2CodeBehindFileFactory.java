package org.jfxcore.fxml.lang;

import com.intellij.ide.fileTemplates.FileTemplate;
import com.intellij.ide.fileTemplates.FileTemplateManager;
import com.intellij.ide.fileTemplates.FileTemplateUtil;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2CodeBehindName;

import java.util.Properties;

/**
 * Creates the code-behind class declared by an FXML file's {@code fx:subclass} directive.
 *
 * <p>The class is created next to the FXML file, extending the markup base class that the
 * FXML compiler generates, and calling {@code initializeComponent()} from its constructor
 * (Java) or {@code init} block (Kotlin). The language is chosen from the source tree the
 * FXML file lives in.
 */
public final class Fxml2CodeBehindFileFactory {

    private static final String JAVA_TEMPLATE = "Fxml2CodeBehind.java";
    private static final String KOTLIN_TEMPLATE = "Fxml2CodeBehind.kt";

    private Fxml2CodeBehindFileFactory() {
    }

    /**
     * Creates the code-behind file for {@code name} in the directory of {@code fxmlFile}
     * and opens it in the editor. Does nothing when the file cannot be created.
     */
    public static void create(@NotNull Project project,
                              @NotNull PsiFile fxmlFile,
                              @NotNull Fxml2CodeBehindName name) {
        PsiDirectory dir = fxmlFile.getContainingDirectory();
        if (dir == null) return;

        boolean useKotlin = isKotlinContext(fxmlFile.getVirtualFile());
        FileTemplateManager templateManager = FileTemplateManager.getInstance(project);
        FileTemplate template = templateManager.getInternalTemplate(
                useKotlin ? KOTLIN_TEMPLATE : JAVA_TEMPLATE);

        Properties props = templateManager.getDefaultProperties();
        props.setProperty("PACKAGE_NAME", name.packageName());
        props.setProperty("NAME", name.simpleName());
        props.setProperty("BASE_CLASS", name.markupBaseName());

        try {
            PsiElement created = FileTemplateUtil.createFromTemplate(
                    template, name.simpleName(), props, dir);
            PsiFile createdFile = created.getContainingFile();
            if (createdFile == null) return;
            VirtualFile virtualFile = createdFile.getVirtualFile();
            if (virtualFile != null) {
                FileEditorManager.getInstance(project).openFile(virtualFile, true);
            }
        } catch (Exception e) {
            // Filesystem and template errors are surfaced to the user by the IDE.
        }
    }

    /**
     * Returns {@code true} when {@code fxmlFile} lives under a {@code .../kotlin/...}
     * source tree, and {@code false} when it lives under a Java source tree or neither.
     */
    static boolean isKotlinContext(@Nullable VirtualFile fxmlFile) {
        if (fxmlFile == null) return false;
        String path = fxmlFile.getPath();
        int kotlinIndex = path.indexOf("/kotlin/");
        int javaIndex = path.indexOf("/java/");
        if (kotlinIndex < 0) return false;
        if (javaIndex < 0) return true;
        return kotlinIndex < javaIndex;
    }
}
