package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.JavaRecursiveElementWalkingVisitor;
import com.intellij.psi.PsiAnonymousClass;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.asJava.LightClassUtilsKt;
import org.jetbrains.kotlin.psi.KtBlockExpression;
import org.jetbrains.kotlin.psi.KtCallExpression;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtClassInitializer;
import org.jetbrains.kotlin.psi.KtClassOrObjectKt;
import org.jetbrains.kotlin.psi.KtDeclaration;
import org.jetbrains.kotlin.psi.KtPsiFactory;
import org.jetbrains.kotlin.psi.KtSecondaryConstructor;
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry;
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid;
import org.jetbrains.kotlin.psi.KtVisitorVoid;
import org.jfxcore.fxml.lang.Fxml2FileType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Inspection that warns when a code-behind class (Java or Kotlin) does not call
 * {@code initializeComponent()} on every construction path.
 *
 * <p>A class is considered a <em>code-behind class</em> when an FXML file exists in the
 * project with {@code fx:subclass} set to the fully-qualified name of that class, or when
 * the class carries embedded markup via {@code @ComponentView}.
 *
 * <p>A construction path is satisfied when the call appears in the constructor itself, in a
 * constructor it delegates to (Java {@code this(...)}/{@code super(...)}, Kotlin
 * {@code this(...)}), or, for Kotlin, in an {@code init} block. A class without any declared
 * constructor is reported on the class name, because its implicit constructor cannot call
 * {@code initializeComponent()}.
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

    /** Name of the compiler-generated initialization method. */
    private static final String INITIALIZE_COMPONENT = "initializeComponent";

    private static final String JAVA_CONSTRUCTOR_MESSAGE =
            "Constructor does not call " + INITIALIZE_COMPONENT + "(). "
            + "Add a call to " + INITIALIZE_COMPONENT + "() in this constructor, or suppress with "
            + "@SuppressWarnings(\"" + SHORT_NAME + "\").";

    private static final String JAVA_CLASS_MESSAGE =
            "Class does not call " + INITIALIZE_COMPONENT + "(). "
            + "Add a constructor that calls " + INITIALIZE_COMPONENT + "(), or suppress with "
            + "@SuppressWarnings(\"" + SHORT_NAME + "\").";

    private static final String KOTLIN_CLASS_MESSAGE =
            "Class does not call " + INITIALIZE_COMPONENT + "(). "
            + "Add a call to " + INITIALIZE_COMPONENT + "() in an init block or constructor, "
            + "or suppress with @Suppress(\"" + SHORT_NAME + "\").";

    private static final String KOTLIN_CONSTRUCTOR_MESSAGE =
            "Constructor does not call " + INITIALIZE_COMPONENT + "(). "
            + "Add a call to " + INITIALIZE_COMPONENT + "() in this constructor, or suppress with "
            + "@Suppress(\"" + SHORT_NAME + "\").";

    @Override
    public @NotNull String getShortName() {
        return SHORT_NAME;
    }

    @Override
    public @NotNull String getGroupDisplayName() {
        return "FXML/2";
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
                public void visitClass(@NotNull PsiClass psiClass) {
                    checkJavaClass(psiClass, holder);
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

    private static void checkJavaClass(@NotNull PsiClass psiClass, @NotNull ProblemsHolder holder) {
        if (psiClass instanceof PsiAnonymousClass) return;
        if (psiClass.isInterface() || psiClass.isEnum() || psiClass.isAnnotationType()) return;
        if (psiClass.getQualifiedName() == null) return;
        if (!isCodeBehindClass(psiClass, psiClass.getProject())) return;

        PsiMethod[] constructors = psiClass.getConstructors();
        if (constructors.length == 0) {
            // The implicit constructor cannot call initializeComponent() itself; the call can
            // only come from a superclass constructor on the implicit super() path.
            if (javaSuperChainInitializes(psiClass, new HashSet<>())) return;

            PsiElement target = psiClass.getNameIdentifier();
            if (target == null) return;
            holder.registerProblem(target, JAVA_CLASS_MESSAGE, new AddJavaConstructorFix());
            return;
        }

        for (PsiMethod constructor : constructors) {
            if (javaConstructorInitializes(constructor, new HashSet<>())) continue;

            PsiElement target = constructor.getNameIdentifier();
            if (target == null) target = constructor;
            holder.registerProblem(target, JAVA_CONSTRUCTOR_MESSAGE, new AddJavaCallFix());
        }
    }

    /**
     * Returns {@code true} when the given constructor calls {@code initializeComponent()}
     * directly, or delegates to a constructor that does.
     */
    private static boolean javaConstructorInitializes(
            @NotNull PsiMethod constructor, @NotNull Set<PsiMethod> visited) {
        if (!visited.add(constructor)) return false;

        PsiCodeBlock body = constructor.getBody();
        if (body == null) return false;
        if (javaBodyContainsCall(body)) return true;

        PsiMethodCallExpression delegation = javaDelegationCall(constructor);
        if (delegation != null) {
            PsiMethod target = delegation.resolveMethod();
            return target != null && javaConstructorInitializes(target, visited);
        }

        // No explicit this()/super(): the implicit super() path applies.
        PsiClass containingClass = constructor.getContainingClass();
        return containingClass != null && javaSuperChainInitializes(containingClass, visited);
    }

    /**
     * Returns {@code true} when the no-argument construction path of the superclass of
     * {@code psiClass} calls {@code initializeComponent()}.
     */
    private static boolean javaSuperChainInitializes(
            @NotNull PsiClass psiClass, @NotNull Set<PsiMethod> visited) {
        PsiClass superClass = psiClass.getSuperClass();
        if (superClass == null || superClass.equals(psiClass)) return false;

        PsiMethod[] superConstructors = superClass.getConstructors();
        if (superConstructors.length == 0) {
            return javaSuperChainInitializes(superClass, visited);
        }

        for (PsiMethod superConstructor : superConstructors) {
            if (!superConstructor.getParameterList().isEmpty()) continue;
            return javaConstructorInitializes(superConstructor, visited);
        }
        return false;
    }

    /** Returns the leading {@code this(...)} or {@code super(...)} call of a constructor, if any. */
    private static @Nullable PsiMethodCallExpression javaDelegationCall(@NotNull PsiMethod constructor) {
        PsiCodeBlock body = constructor.getBody();
        if (body == null) return null;
        PsiStatement[] statements = body.getStatements();
        if (statements.length == 0) return null;
        if (!(statements[0] instanceof PsiExpressionStatement statement)) return null;
        if (!(statement.getExpression() instanceof PsiMethodCallExpression call)) return null;

        String callee = call.getMethodExpression().getReferenceName();
        return "this".equals(callee) || "super".equals(callee) ? call : null;
    }

    private static boolean javaBodyContainsCall(@NotNull PsiCodeBlock body) {
        var found = new boolean[]{false};
        body.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
                super.visitMethodCallExpression(call);
                PsiReferenceExpression ref = call.getMethodExpression();
                if (INITIALIZE_COMPONENT.equals(ref.getReferenceName())
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
        if (ktClass.isInterface() || ktClass.isAnnotation()) return;

        var lightClass = LightClassUtilsKt.toLightClass(ktClass);
        if (lightClass == null) return;
        if (!isCodeBehindClass(lightClass, ktClass.getProject())) return;

        // An init block runs on every construction path, as does the primary constructor body.
        if (kotlinAlwaysRunningCodeContainsCall(ktClass)) return;

        List<KtSecondaryConstructor> secondaryConstructors = ktClass.getSecondaryConstructors();
        if (secondaryConstructors.isEmpty() || kotlinHasPrimaryConstructor(ktClass)) {
            // Every construction path runs the primary constructor and the init blocks, so a
            // missing call is a property of the class rather than of a single constructor.
            var nameId = ktClass.getNameIdentifier();
            if (nameId == null) return;
            holder.registerProblem(nameId, KOTLIN_CLASS_MESSAGE, new AddKotlinInitBlockFix());
            return;
        }

        for (KtSecondaryConstructor constructor : secondaryConstructors) {
            if (kotlinConstructorInitializes(constructor, new HashSet<>())) continue;

            PsiElement target = constructor.getConstructorKeyword();
            holder.registerProblem(target, KOTLIN_CONSTRUCTOR_MESSAGE, new AddKotlinCallFix());
        }
    }

    /**
     * Returns {@code true} when the class has a primary constructor, either declared explicitly
     * or implied by a super type call entry in the class header.
     */
    private static boolean kotlinHasPrimaryConstructor(@NotNull KtClass ktClass) {
        if (ktClass.getPrimaryConstructor() != null) return true;
        for (var entry : ktClass.getSuperTypeListEntries()) {
            if (entry instanceof KtSuperTypeCallEntry) return true;
        }
        return false;
    }

    /** Returns {@code true} when an init block or the primary constructor body performs the call. */
    private static boolean kotlinAlwaysRunningCodeContainsCall(@NotNull KtClass ktClass) {
        var primaryConstructor = ktClass.getPrimaryConstructor();
        if (primaryConstructor != null) {
            var body = primaryConstructor.getBodyExpression();
            if (body != null && kotlinBodyContainsCall(body)) return true;
        }

        for (var initializer : ktClass.getAnonymousInitializers()) {
            if (initializer instanceof KtClassInitializer classInitializer) {
                var body = classInitializer.getBody();
                if (body != null && kotlinBodyContainsCall(body)) return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} when the given secondary constructor calls
     * {@code initializeComponent()} directly, or delegates to one that does.
     */
    private static boolean kotlinConstructorInitializes(
            @NotNull KtSecondaryConstructor constructor, @NotNull Set<KtSecondaryConstructor> visited) {
        if (!visited.add(constructor)) return false;

        var body = constructor.getBodyExpression();
        if (body != null && kotlinBodyContainsCall(body)) return true;

        var delegationCall = constructor.getDelegationCall();
        if (!delegationCall.isCallToThis()) return false;

        var argumentList = delegationCall.getValueArgumentList();
        int argumentCount = argumentList == null ? 0 : argumentList.getArguments().size();

        var containingClass = constructor.getContainingClassOrObject();

        // Match the delegation target by argument count. An ambiguous match is treated as
        // satisfied rather than risking a false positive.
        KtSecondaryConstructor candidate = null;
        for (KtSecondaryConstructor sibling : containingClass.getSecondaryConstructors()) {
            if (sibling == constructor) continue;
            if (sibling.getValueParameters().size() != argumentCount) continue;
            if (candidate != null) return true;
            candidate = sibling;
        }
        return candidate != null && kotlinConstructorInitializes(candidate, visited);
    }

    private static boolean kotlinBodyContainsCall(@NotNull PsiElement body) {
        var found = new boolean[]{false};
        body.accept(new KtTreeVisitorVoid() {
            @Override
            public void visitCallExpression(@NotNull KtCallExpression expression) {
                super.visitCallExpression(expression);
                var callee = expression.getCalleeExpression();
                if (callee == null) return;
                if (!INITIALIZE_COMPONENT.equals(callee.getText())) return;
                var args = expression.getValueArgumentList();
                if (args == null || args.getArguments().isEmpty()) {
                    found[0] = true;
                }
            }
        });
        return found[0];
    }

    // -----------------------------------------------------------------------
    // Quick-fixes
    // -----------------------------------------------------------------------

    /** Adds {@code initializeComponent();} as the first statement of a Java constructor. */
    static final class AddJavaCallFix implements LocalQuickFix {

        @Override
        public @NotNull String getFamilyName() {
            return "Add initializeComponent() call";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiMethod constructor = PsiTreeUtil.getParentOfType(
                    descriptor.getPsiElement(), PsiMethod.class, false);
            if (constructor == null || !constructor.isConstructor()) return;

            PsiCodeBlock body = constructor.getBody();
            if (body == null) return;

            PsiStatement call = PsiElementFactory.getInstance(project)
                    .createStatementFromText(INITIALIZE_COMPONENT + "();", body);

            // The call must come first, but a this()/super() delegation has to stay leading.
            PsiMethodCallExpression delegation = javaDelegationCall(constructor);
            PsiElement added = delegation == null
                    ? body.addAfter(call, body.getLBrace())
                    : body.addAfter(call, body.getStatements()[0]);
            CodeStyleManager.getInstance(project).reformat(added);
        }
    }

    /** Adds a no-argument constructor that calls {@code initializeComponent()}. */
    static final class AddJavaConstructorFix implements LocalQuickFix {

        @Override
        public @NotNull String getFamilyName() {
            return "Add constructor calling initializeComponent()";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiClass psiClass = PsiTreeUtil.getParentOfType(
                    descriptor.getPsiElement(), PsiClass.class, false);
            if (psiClass == null) return;
            String name = psiClass.getName();
            if (name == null) return;

            String modifier = psiClass.hasModifierProperty(PsiModifier.PUBLIC) ? "public " : "";
            PsiMethod constructor = PsiElementFactory.getInstance(project).createMethodFromText(
                    modifier + name + "() {\n" + INITIALIZE_COMPONENT + "();\n}", psiClass);

            PsiMethod[] methods = psiClass.getMethods();
            PsiElement added = methods.length == 0
                    ? psiClass.add(constructor)
                    : psiClass.addBefore(constructor, methods[0]);
            CodeStyleManager.getInstance(project).reformat(added);
        }
    }

    /** Adds an {@code init} block calling {@code initializeComponent()} to a Kotlin class. */
    static final class AddKotlinInitBlockFix implements LocalQuickFix {

        @Override
        public @NotNull String getFamilyName() {
            return "Add init block calling initializeComponent()";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            KtClass ktClass = PsiTreeUtil.getParentOfType(
                    descriptor.getPsiElement(), KtClass.class, false);
            if (ktClass == null) return;

            var factory = new KtPsiFactory(project);
            var initializer = factory.createClass(
                    "class A {\ninit {\n" + INITIALIZE_COMPONENT + "()\n}\n}")
                    .getAnonymousInitializers().getFirst();

            var body = KtClassOrObjectKt.getOrCreateBody(ktClass);
            List<KtDeclaration> declarations = body.getDeclarations();
            PsiElement added = declarations.isEmpty()
                    ? body.addAfter(initializer, body.getLBrace())
                    : body.addBefore(initializer, declarations.getFirst());
            CodeStyleManager.getInstance(project).reformat(added);
        }
    }

    /** Adds {@code initializeComponent()} as the first statement of a Kotlin constructor. */
    static final class AddKotlinCallFix implements LocalQuickFix {

        @Override
        public @NotNull String getFamilyName() {
            return "Add initializeComponent() call";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            KtSecondaryConstructor constructor = PsiTreeUtil.getParentOfType(
                    descriptor.getPsiElement(), KtSecondaryConstructor.class, false);
            if (constructor == null) return;

            var factory = new KtPsiFactory(project);
            PsiElement added;
            if (constructor.getBodyExpression() instanceof KtBlockExpression body) {
                var call = factory.createExpression(INITIALIZE_COMPONENT + "()");
                var statements = body.getStatements();
                if (statements.isEmpty()) {
                    added = body.addAfter(call, body.getLBrace());
                } else {
                    added = body.addBefore(call, statements.getFirst());
                    body.addAfter(factory.createNewLine(), added);
                }
            } else {
                var block = factory.createBlock(INITIALIZE_COMPONENT + "()");
                added = constructor.add(block);
            }
            CodeStyleManager.getInstance(project).reformat(added);
        }
    }

    // -----------------------------------------------------------------------
    // Shared: code-behind detection
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if the given class is referenced as {@code fx:subclass} in any
     * FXML file in the project.
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

        for (String ext : List.of(simpleName + ".fxml", simpleName + ".fxmlx")) {
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
