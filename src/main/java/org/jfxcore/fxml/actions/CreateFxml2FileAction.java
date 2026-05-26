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
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.PackageIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.model.java.JavaModuleSourceRootTypes;

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
               .addKind("FXML/2 file", AllIcons.FileTypes.Xml, template.getName());
    }

    @Override
    protected String getActionName(PsiDirectory directory, @NotNull String newName, String templateName) {
        return "Create FXML/2 File " + newName;
    }

    // -------------------------------------------------------------------------
    // File creation: substitute FX_CLASS directly via Velocity properties
    // -------------------------------------------------------------------------

    @Override
    protected PsiFile createFile(String name, String templateName, PsiDirectory dir) {
        FileTemplate template = FileTemplateManager.getInstance(dir.getProject()).getInternalTemplate(templateName);
        String fxClass = computeFxClass(name, dir);
        return createFileFromTemplate(name, template, dir, null, true, Map.of(), Map.of("FX_CLASS", fxClass));
    }

    /**
     * Builds the fully-qualified {@code fx:subclass} value from the directory's package
     * and the file's base name converted to a Java class name.
     */
    private static String computeFxClass(@NotNull String baseName, @NotNull PsiDirectory dir) {
        VirtualFile vDir = dir.getVirtualFile();
        String pkg = PackageIndex.getInstance(dir.getProject()).getPackageNameByDirectory(vDir);
        String className = toClassName(baseName);
        return !StringUtil.isEmpty(pkg) ? pkg + "." + className : className;
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

    /**
     * Converts a filename (potentially with hyphens, underscores, digits at start, etc.)
     * into a valid Java class name with an upper-case first letter.
     * Mirrors the logic in the bundled JavaFX plugin's {@code CreateFxmlFileAction}.
     */
    private static String toClassName(String name) {
        int start;
        for (start = 0; start < name.length(); start++) {
            char c = name.charAt(start);
            if (Character.isJavaIdentifierStart(c) && c != '_' && c != '$') break;
        }
        StringBuilder sb = new StringBuilder();
        boolean capitalizeNext = true;
        for (int i = start; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isJavaIdentifierPart(c) || c == '_' || c == '$') {
                capitalizeNext = true;
                continue;
            }
            if (capitalizeNext) {
                capitalizeNext = false;
                sb.append(Character.toUpperCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
