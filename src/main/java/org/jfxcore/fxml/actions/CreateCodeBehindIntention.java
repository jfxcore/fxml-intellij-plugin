package org.jfxcore.fxml.actions;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.ide.fileTemplates.FileTemplate;
import com.intellij.ide.fileTemplates.FileTemplateManager;
import com.intellij.ide.fileTemplates.FileTemplateUtil;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Iconable;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.jfxcore.fxml.lang.Fxml2FileType;

import javax.swing.Icon;
import java.util.Properties;

/**
 * Intention action that creates a code-behind class for an FXML file.
 *
 * <p>Given {@code fx:subclass="com.example.MyView"}, this creates either
 * {@code MyView.java} or {@code MyView.kt} (detected from path) in the same
 * directory as the FXML file, extending {@code MyViewBase} and calling
 * {@code initializeComponent()} in the constructor / {@code init} block.
 *
 * <p>Registered as an {@code <intentionAction>} extension so it appears in the
 * Alt+Enter menu whenever the caret is on or inside an {@code fx:subclass} attribute
 * whose named class does not yet exist, without any error highlight.
 */
public final class CreateCodeBehindIntention implements IntentionAction, PriorityAction, Iconable {

    private static final String JAVA_TEMPLATE = "Fxml2CodeBehind.java";
    private static final String KOTLIN_TEMPLATE = "Fxml2CodeBehind.kt";

    /** Updated during {@link #isAvailable} so {@link #getText()} can show the class name. */
    private volatile String cachedSimpleName = null;

    @Override
    public @NotNull String getText() {
        String name = cachedSimpleName;
        return name != null ? "Create code-behind class '" + name + "'" : getFamilyName();
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Create code-behind class";
    }

    // -------------------------------------------------------------------------
    // Availability: cheap checks only, no PSI class resolution on EDT
    // -------------------------------------------------------------------------

    /**
     * Uses only cheap checks: FXML file type, caret position, and a VFS sibling-file
     * existence check. No {@code JavaPsiFacade.findClass()} here: that would block the
     * EDT and cause the "Searching for Context Actions" spinner to hang indefinitely.
     */
    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        if (!(file instanceof XmlFile xmlFile)) return false;
        if (!Fxml2FileType.isFxml2(xmlFile)) return false;
        // Embedded FXML: the annotated class is already the code-behind, suppress.
        if (Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile)) return false;
        String fxClass = getFxClass(editor, file);
        if (StringUtil.isEmptyOrSpaces(fxClass)) return false;
        String simpleName = StringUtil.getShortName(fxClass);
        // Hide the action if the sibling .kt or .java file already exists (pure VFS, no PSI).
        if (siblingExists(file.getVirtualFile(), simpleName)) return false;
        cachedSimpleName = simpleName;
        return true;
    }

    // -------------------------------------------------------------------------
    // Invocation
    // -------------------------------------------------------------------------

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile psiFile)
            throws IncorrectOperationException {
        if (!(psiFile instanceof XmlFile)) return;
        String fxClass = getFxClass(editor, psiFile);
        if (StringUtil.isEmptyOrSpaces(fxClass)) return;
        // isAvailable() already checked that the sibling file doesn't exist on disk,
        // which is sufficient, no PSI findClass() call needed here.
        applyFix(project, psiFile, fxClass);
    }

    @Override
    public boolean startInWriteAction() {
        return false; // applyFix runs its own WriteAction
    }

    public static void applyFix(@NotNull Project project, @NotNull PsiFile psiFile,
                                @NotNull String fxClass) {
        PsiDirectory dir = psiFile.getContainingDirectory();
        if (dir == null) return;

        String simpleName  = StringUtil.getShortName(fxClass);
        String packageName = StringUtil.getPackageName(fxClass);
        String baseName    = simpleName + "Base";
        boolean useKotlin  = isKotlinContext(psiFile.getVirtualFile());
        String templateName = useKotlin ? KOTLIN_TEMPLATE : JAVA_TEMPLATE;

        FileTemplateManager mgr = FileTemplateManager.getInstance(project);
        FileTemplate template = mgr.getInternalTemplate(templateName);

        Properties props = mgr.getDefaultProperties();
        props.setProperty("PACKAGE_NAME", packageName);
        props.setProperty("NAME", simpleName);
        props.setProperty("BASE_CLASS", baseName);

        try {
            PsiElement created = FileTemplateUtil.createFromTemplate(template, simpleName, props, dir);
            PsiFile createdFile = created.getContainingFile();
            if (createdFile != null) {
                VirtualFile vf = createdFile.getVirtualFile();
                if (vf != null) {
                    FileEditorManager.getInstance(project).openFile(vf, true);
                }
            }
        } catch (Exception e) {
            // Silently ignore: IntelliJ will show a balloon for filesystem errors
        }
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.TOP;
    }

    @Override
    public @Nullable Icon getIcon(int flags) {
        return null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if a sibling file named {@code simpleName.kt} or
     * {@code simpleName.java} already exists in the same VFS directory as {@code fxmlFile}.
     * This is a pure VFS operation, no PSI, no class index lookup.
     */
    private static boolean siblingExists(@Nullable VirtualFile fxmlFile, @NotNull String simpleName) {
        if (fxmlFile == null) return false;
        VirtualFile parent = fxmlFile.getParent();
        if (parent == null) return false;
        return parent.findChild(simpleName + ".kt") != null
                || parent.findChild(simpleName + ".java") != null;
    }

    /**
     * Returns the {@code fx:subclass} value only when the caret is directly on the
     * {@code fx:subclass} attribute name token or inside its value, nowhere else.
     */
    private static @Nullable String getFxClass(@Nullable Editor editor, @NotNull PsiFile file) {
        if (editor == null) return null;
        int offset = editor.getCaretModel().getOffset();
        PsiElement element = file.findElementAt(offset);
        if (element == null) return null;

        // Must be inside an fx:subclass attribute on the root tag
        XmlAttribute attr = PsiTreeUtil.getParentOfType(element, XmlAttribute.class);
        if (attr == null) return null;
        if (!"fx:subclass".equals(attr.getName())) return null;
        XmlTag tag = attr.getParent();
        if (tag == null || tag.getParentTag() != null) return null;
        return attr.getValue();
    }

    /**
     * Returns {@code true} if the FXML file lives under a {@code .../kotlin/...}
     * source tree. Falls back to {@code false} (Java) if neither segment appears.
     */
    static boolean isKotlinContext(@Nullable VirtualFile fxmlFile) {
        if (fxmlFile == null) return false;
        String path = fxmlFile.getPath();
        int kotlinIdx = path.indexOf("/kotlin/");
        int javaIdx   = path.indexOf("/java/");
        if (kotlinIdx < 0 && javaIdx < 0) return false;
        if (kotlinIdx < 0) return false;
        if (javaIdx   < 0) return true;
        return kotlinIdx < javaIdx;
    }
}
