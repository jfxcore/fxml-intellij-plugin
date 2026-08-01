package org.jfxcore.fxml.actions;

import com.intellij.icons.AllIcons;
import com.intellij.ide.actions.CreateFileFromTemplateAction;
import com.intellij.ide.actions.CreateFileFromTemplateDialog;
import com.intellij.ide.fileTemplates.FileTemplate;
import com.intellij.ide.fileTemplates.FileTemplateManager;
import com.intellij.java.library.JavaLibraryModificationTracker;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.PackageIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.InputValidatorEx;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.model.java.JavaModuleSourceRootTypes;
import org.jfxcore.fxml.lang.Fxml2FileType;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

/**
 * "New -> FXML/2 File" action.
 *
 * <p>Creates a new {@code .fxml} file pre-populated with the FXML/2 namespaces
 * ({@code xmlns:fx="http://jfxcore.org/fxml/2.0"}) and an {@code fx:subclass} attribute
 * derived from the target directory's package and the chosen filename.
 *
 * <p>The action is only visible when the selected directory is under a Java source root
 * and the project has a JavaFX dependency on its classpath.
 */
public final class CreateFxml2FileAction extends CreateFileFromTemplateAction implements DumbAware {

    private static final String TEMPLATE_NAME = "Fxml2File";
    private static final String FXML_EXTENSION = "fxml";
    private static final String FXML2_EXTENSION = "fxmlx";
    private static final String FX_CLASS_ATTRIBUTE = "FX_CLASS";
    private static final String JAVAFX_APPLICATION = "javafx.application.Application";

    public CreateFxml2FileAction() {
        super("FXML/2 File", "Creates a new FXML/2 file", AllIcons.FileTypes.Xml);
    }

    // -------------------------------------------------------------------------
    // Dialog setup
    // -------------------------------------------------------------------------

    @Override
    protected void buildDialog(@NotNull Project project,
                               @NotNull PsiDirectory directory,
                               @NotNull CreateFileFromTemplateDialog.Builder builder) {
        FileTemplate template = FileTemplateManager.getInstance(project).getInternalTemplate(TEMPLATE_NAME);
        builder.setTitle("New FXML/2 File")
               .setValidator(NAME_VALIDATOR)
               .addKind("FXML/2 file", AllIcons.FileTypes.Xml, template.getName());
    }

    @Override
    protected String getActionName(PsiDirectory directory, @NotNull String newName, String templateName) {
        return "Create FXML/2 File " + newName;
    }

    /**
     * Accepts names whose base name (the part before an optional {@code .fxml} or {@code .fxmlx}
     * extension) is a valid Java identifier. The compiler derives the class of a document from its
     * file name and requires the simple name of {@code fx:subclass} to match it exactly, so a base
     * name that is not an identifier cannot be compiled.
     */
    static final InputValidatorEx NAME_VALIDATOR = new InputValidatorEx() {
        @Override
        public @Nullable String getErrorText(@NotNull String inputString) {
            String name = inputString.trim();
            if (name.isEmpty()) {
                return null;
            }
            return isValidBaseName(toBaseName(name))
                    ? null
                    : "The name must be a Java identifier, optionally followed by '." + FXML_EXTENSION
                      + "' or '." + FXML2_EXTENSION + "'";
        }

        @Override
        public boolean canClose(@NotNull String inputString) {
            String name = inputString.trim();
            return !name.isEmpty() && isValidBaseName(toBaseName(name));
        }
    };

    private static boolean isValidBaseName(@NotNull String baseName) {
        if (baseName.isEmpty() || !Character.isJavaIdentifierStart(baseName.charAt(0))) {
            return false;
        }
        return baseName.chars().allMatch(Character::isJavaIdentifierPart);
    }

    // -------------------------------------------------------------------------
    // File creation
    // -------------------------------------------------------------------------

    /**
     * Creates the file from the FXML/2 template. The extension entered by the user is kept if it is
     * one of the FXML/2 extensions, otherwise {@code .fxml} is appended, and {@code FX_CLASS} is
     * substituted in the template text.
     */
    @Override
    protected PsiFile createFile(String name, String templateName, PsiDirectory dir) {
        FileTemplate template = FileTemplateManager.getInstance(dir.getProject()).getInternalTemplate(templateName);
        String baseName = toBaseName(name);
        String extension = extensionOf(name);
        String fileName = baseName + "." + (isFxml2Extension(extension) ? extension : FXML_EXTENSION);

        String text;
        try {
            text = template.getText(Map.of(FX_CLASS_ATTRIBUTE, computeFxClass(baseName, dir)));
        } catch (IOException e) {
            throw new IncorrectOperationException("Cannot read the FXML/2 file template", (Throwable)e);
        }

        Project project = dir.getProject();
        dir.checkCreateFile(fileName);
        PsiFile file = (PsiFile)dir.add(
                PsiFileFactory.getInstance(project).createFileFromText(fileName, Fxml2FileType.INSTANCE, text));
        if (template.isReformatCode()) {
            CodeStyleManager.getInstance(project).reformat(file);
        }

        VirtualFile virtualFile = file.getVirtualFile();
        if (virtualFile != null) {
            FileEditorManager.getInstance(project).openFile(virtualFile, true);
        }
        return file;
    }

    /**
     * Returns the entered name without an {@code .fxml} or {@code .fxmlx} extension. Any other
     * extension is part of the base name, because the file keeps it and gets {@code .fxml} appended.
     */
    private static @NotNull String toBaseName(@NotNull String name) {
        String extension = extensionOf(name);
        return isFxml2Extension(extension) ? name.substring(0, name.length() - extension.length() - 1) : name;
    }

    /**
     * Returns the extension of the last segment of the given path, or an empty string if it has none.
     */
    private static @NotNull String extensionOf(@NotNull String path) {
        int lastSeparator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        return dot > lastSeparator + 1 ? path.substring(dot + 1) : "";
    }

    private static boolean isFxml2Extension(@NotNull String extension) {
        return FXML_EXTENSION.equalsIgnoreCase(extension) || FXML2_EXTENSION.equalsIgnoreCase(extension);
    }

    /**
     * Builds the fully-qualified {@code fx:subclass} value from the directory's package and the
     * file's base name. The compiler requires the simple class name to match the file name, so the
     * base name is used verbatim.
     */
    private static String computeFxClass(@NotNull String baseName, @NotNull PsiDirectory dir) {
        VirtualFile vDir = dir.getVirtualFile();
        String pkg = PackageIndex.getInstance(dir.getProject()).getPackageNameByDirectory(vDir);
        return !StringUtil.isEmpty(pkg) ? pkg + "." + baseName : baseName;
    }

    // -------------------------------------------------------------------------
    // Visibility / availability
    // -------------------------------------------------------------------------

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    protected boolean isAvailable(@NotNull DataContext dataContext) {
        if (!super.isAvailable(dataContext)) return false;
        return checkFxml2Available(dataContext);
    }

    private static boolean checkFxml2Available(@NotNull DataContext ctx) {
        Project project = CommonDataKeys.PROJECT.getData(ctx);
        var view = LangDataKeys.IDE_VIEW.getData(ctx);
        if (project == null || view == null) return false;

        PsiDirectory[] dirs = view.getDirectories();
        if (dirs.length == 0) return false;

        var index = ProjectRootManager.getInstance(project).getFileIndex();
        boolean underSourceRoot = Arrays.stream(dirs)
                .map(PsiDirectory::getVirtualFile)
                .anyMatch(vf -> index.isUnderSourceRootOfType(vf, JavaModuleSourceRootTypes.SOURCES));
        if (!underSourceRoot) return false;

        Module module = ModuleUtilCore.findModuleForFile(dirs[0].getVirtualFile(), project);
        return hasJavaFxOnClasspath(module);
    }

    @SuppressWarnings("UnstableApiUsage")
    private static boolean hasJavaFxOnClasspath(@Nullable Module module) {
        if (module == null || module.isDisposed()) return false;
        return CachedValuesManager.getManager(module.getProject()).getCachedValue(module, () -> {
            boolean found = JavaPsiFacade.getInstance(module.getProject())
                    .findClass(JAVAFX_APPLICATION,
                               GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module)) != null;
            return CachedValueProvider.Result.create(
                    found, JavaLibraryModificationTracker.getInstance(module.getProject()));
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

}
