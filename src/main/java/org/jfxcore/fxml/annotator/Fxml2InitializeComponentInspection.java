package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaRecursiveElementWalkingVisitor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.asJava.KotlinAsJavaSupport;
import org.jetbrains.kotlin.psi.KtCallExpression;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtClassInitializer;
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid;
import org.jetbrains.kotlin.psi.KtVisitorVoid;
import org.jfxcore.fxml.lang.Fxml2FileType;

import java.util.List;

/**
 * Inspection that warns when a code-behind constructor (Java or Kotlin) does not call
 * {@code initializeComponent()}.
 *
 * <p>A class is considered a <em>code-behind class</em> when an FXML 2.0 file exists in the
 * project with {@code fx:subclass} set to the fully-qualified name of that class.
 *
 * <p>Suppression:
 * <ul>
 *   <li>Java: {@code @SuppressWarnings("Fxml2InitializeComponent")}</li>
 *   <li>Kotlin: {@code @Suppress("Fxml2InitializeComponent")}</li>
 * </ul>
 */
public final class Fxml2InitializeComponentInspection extends LocalInspectionTool {

    /** Short name used for suppression annotations and the inspection profile key. */
    public static final String SHORT_NAME = "Fxml2InitializeComponent";

    @Override
    public @NotNull String getShortName() {
        return SHORT_NAME;
    }

    @Override
    public @NotNull String getGroupDisplayName() {
        return "FXML 2.0";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Missing initializeComponent() call in code-behind constructor";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    // -----------------------------------------------------------------------
    // Visitor dispatch
    // -----------------------------------------------------------------------

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        PsiFile file = holder.getFile();
        String name = file.getName();

        if (name.endsWith(".java")) {
            return new JavaElementVisitor() {
                @Override
                public void visitMethod(@NotNull PsiMethod method) {
                    checkJavaConstructor(method, holder);
                }
            };
        }

        if (name.endsWith(".kt")) {
            try {
                return new KtVisitorVoid() {
                    @Override
                    public void visitClass(@NotNull KtClass ktClass) {
                        checkKotlinClass(ktClass, holder);
                    }
                };
            } catch (NoClassDefFoundError e) {
                // Kotlin plugin not present, degrade gracefully
                return PsiElementVisitor.EMPTY_VISITOR;
            }
        }

        return PsiElementVisitor.EMPTY_VISITOR;
    }

    // -----------------------------------------------------------------------
    // Java handling
    // -----------------------------------------------------------------------


    private static void checkJavaConstructor(@NotNull PsiMethod method, @NotNull ProblemsHolder holder) {
        if (!method.isConstructor()) return;

        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) return;
        if (containingClass.getQualifiedName() == null) return;

        if (!isCodeBehindClass(containingClass, method.getProject())) return;
        if (javaConstructorCallsInitializeComponent(method)) return;

        String message = "Constructor does not call initializeComponent(). "
                + "Add a call to initializeComponent() in this constructor, or suppress with "
                + "@SuppressWarnings(\"" + SHORT_NAME + "\").";

        PsiElement target = method.getNameIdentifier();
        if (target == null) target = method;
        holder.registerProblem(target, message);
    }

    private static boolean javaConstructorCallsInitializeComponent(@NotNull PsiMethod constructor) {
        var body = constructor.getBody();
        if (body == null) return false;

        var found = new boolean[]{false};
        body.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
                super.visitMethodCallExpression(call);
                PsiReferenceExpression ref = call.getMethodExpression();
                if ("initializeComponent".equals(ref.getReferenceName())
                        && call.getArgumentList().isEmpty()) {
                    found[0] = true;
                    stopWalking();
                }
            }
        });
        return found[0];
    }

    // -----------------------------------------------------------------------
    // Kotlin handling
    // -----------------------------------------------------------------------


    private static void checkKotlinClass(@NotNull KtClass ktClass, @NotNull ProblemsHolder holder) {
        var lightClass = KotlinAsJavaSupport.getInstance(ktClass.getProject()).getLightClass(ktClass);
        if (lightClass == null) return;
        if (!isCodeBehindClass(lightClass, ktClass.getProject())) return;
        if (kotlinClassCallsInitializeComponent(ktClass)) return;

        var nameId = ktClass.getNameIdentifier();
        if (nameId == null) return;

        holder.registerProblem(nameId,
                "Class does not call initializeComponent(). "
                + "Add a call to initializeComponent() in an init block or constructor, "
                + "or suppress with @Suppress(\"" + SHORT_NAME + "\").");
    }

    private static boolean kotlinClassCallsInitializeComponent(@NotNull KtClass ktClass) {
        // Primary constructor body
        var primaryCtor = ktClass.getPrimaryConstructor();
        if (primaryCtor != null) {
            var body = primaryCtor.getBodyExpression();
            if (body != null && kotlinBodyContainsCall(body)) return true;
        }

        // init { ... } blocks
        for (var initializer : ktClass.getAnonymousInitializers()) {
            if (initializer instanceof KtClassInitializer ki) {
                var body = ki.getBody();
                if (body != null && kotlinBodyContainsCall(body)) return true;
            }
        }

        // Secondary constructors
        for (var ctor : ktClass.getSecondaryConstructors()) {
            var body = ctor.getBodyExpression();
            if (body != null && kotlinBodyContainsCall(body)) return true;
        }

        return false;
    }

    private static boolean kotlinBodyContainsCall(@NotNull PsiElement body) {
        var found = new boolean[]{false};
        body.accept(new KtTreeVisitorVoid() {
            @Override
            public void visitCallExpression(@NotNull KtCallExpression expression) {
                super.visitCallExpression(expression);
                var callee = expression.getCalleeExpression();
                if (callee == null) return;
                if (!"initializeComponent".equals(callee.getText())) return;
                var args = expression.getValueArgumentList();
                if (args == null || args.getArguments().isEmpty()) {
                    found[0] = true;
                }
            }
        });
        return found[0];
    }

    // -----------------------------------------------------------------------
    // Shared: code-behind detection
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if the given class is referenced as {@code fx:subclass} in any
     * FXML 2.0 file in the project.
     */
    public static boolean isCodeBehindClass(@NotNull PsiClass psiClass, @NotNull Project project) {
        String qualifiedName = psiClass.getQualifiedName();
        if (qualifiedName == null) return false;

        // Fast path: a class annotated with @ComponentView is always a code-behind class.
        if (psiClass.hasAnnotation("org.jfxcore.markup.ComponentView")) return true;

        String simpleName = psiClass.getName();
        if (simpleName == null) return false;

        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        PsiManager psiManager = PsiManager.getInstance(project);

        for (String ext : List.of(simpleName + ".fxml", simpleName + ".fxml2")) {
            for (var vFile : FilenameIndex.getVirtualFilesByName(ext, scope)) {
                PsiFile psiFile = psiManager.findFile(vFile);
                if (!(psiFile instanceof XmlFile xmlFile)) continue;
                if (!Fxml2FileType.isFxml2(xmlFile)) continue;
                XmlTag root = xmlFile.getRootTag();
                if (root == null) continue;
                String fxClass = root.getAttributeValue("fx:subclass");
                if (qualifiedName.equals(fxClass)) return true;
            }
        }
        return false;
    }
}
