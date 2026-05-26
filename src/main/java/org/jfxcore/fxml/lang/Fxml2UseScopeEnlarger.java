package org.jfxcore.fxml.lang;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.DelegatingGlobalSearchScope;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.UseScopeEnlarger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2PropertyNameUtil;

/**
 * Enlarges the search scope for fields and methods that may be referenced in FXML files,
 * either as {@code fx:id} values or as property attribute names (e.g.
 * {@code <FormattedLabel formatter="$doubleFormatter"/>}).
 *
 * <p>Without this, "Find Usages" for a code-behind field or a property-setter method
 * searches only Java/Kotlin source files and misses the FXML use site.  By adding a
 * scope that includes all {@code .fxml} and {@code .fxml2} files in the project, IntelliJ
 * will pass those files to {@link Fxml2FxIdFieldSearcher} and
 * {@link Fxml2PropertyAttributeSearcher}, which then check for matching references.
 */
public final class Fxml2UseScopeEnlarger extends UseScopeEnlarger {

    @Override
    public @Nullable SearchScope getAdditionalUseScope(@NotNull PsiElement element) {
        // Enlarge for classes, fields, and for any method that looks like a JavaFX/JavaBeans
        // property accessor: no-arg getters / xProperty(), single-arg setters.
        // This covers code-behind fields (fx:id), binding segment targets, and property
        // attribute declarations (e.g. setFormatter(Function<T,String>)).
        // Classes are enlarged so that "Find Usages" on a custom control class also finds
        // its use as an XML element tag in standalone FXML files.
        //
        // Kotlin source elements (KtProperty, KtNamedFunction) are handled separately below
        // because Kotlin's unused-declaration inspection (K2 mode) calls getUseScope() on the
        // raw KtProperty / KtNamedFunction before running MethodReferencesSearch. Without the
        // enlargement the word-index pre-check finds zero occurrences and immediately returns
        // "never used", bypassing both MethodReferencesSearch and ImplicitUsageProvider.
        VirtualFile sourceFile;
        switch (element) {
            case PsiClass cls -> sourceFile = virtualFileOf(cls);
            case PsiField f -> sourceFile = virtualFileOf(f.getContainingClass());
            case PsiMethod m -> {
                // 0-param: getters, xProperty() accessors, no-arg event handlers.
                // 1-param: setters, single-event-arg handlers (e.g. void onClick(ActionEvent e)).
                // 2+-param methods cannot be referenced from FXML.
                // Enlargement ensures the unused-declaration analysis includes FXML files in
                // its word-index pre-check so MethodReferencesSearch is not skipped early.
                if (m.getParameterList().getParametersCount() > 1) return null;
                sourceFile = virtualFileOf(m.getContainingClass());
            }
            default -> sourceFile = kotlinElementSourceFile(element);
        }
        if (sourceFile == null) return null;

        // Only enlarge for elements whose source file is in project content (not a library).
        Project project = element.getProject();
        if (!ProjectFileIndex.getInstance(project).isInSourceContent(sourceFile)) {
            return null;
        }

        return new Fxml2FilesSearchScope(GlobalSearchScope.projectScope(project));
    }

    /**
     * Returns the virtual file of the given class's source file, or {@code null}.
     */
    private static @Nullable VirtualFile virtualFileOf(@Nullable PsiClass cls) {
        if (cls == null) return null;
        PsiFile file = cls.getContainingFile();
        return file != null ? file.getVirtualFile() : null;
    }

    /**
     * Returns the virtual file for a Kotlin source element ({@code KtProperty} or
     * {@code KtNamedFunction}) when the element should have its use scope enlarged to
     * include FXML files, or {@code null} if the element should not be enlarged.
     *
     * <p>Enlargement is granted for:
     * <ul>
     *   <li>Non-private {@code KtProperty}, any public/internal/protected val or var
     *       could appear as a binding-path segment (e.g. {@code {fx:Observe vm.message}}).</li>
     *   <li>Non-private {@code KtNamedFunction} whose name follows a JavaFX/JavaBeans
     *       accessor convention (e.g. {@code labelTextProperty()}, {@code getLabel()},
     *       {@code setLabel(T)}).</li>
     * </ul>
     *
     * <p>Private members are excluded because they cannot be referenced from FXML markup.
     *
     * <p>The entire method is guarded by {@link NoClassDefFoundError} so that it fails
     * silently when the Kotlin plugin is absent at runtime.
     */
    private static @Nullable VirtualFile kotlinElementSourceFile(@NotNull PsiElement element) {
        try {
            if (element instanceof org.jetbrains.kotlin.psi.KtProperty ktProp) {
                if (!ktProp.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD)) {
                    return ktProp.getContainingFile().getVirtualFile();
                }
            } else if (element instanceof org.jetbrains.kotlin.psi.KtNamedFunction ktFun) {
                String name = ktFun.getName();
                boolean isAccessor = name != null
                        && (Fxml2PropertyNameUtil.isPropertyAccessorName(name)
                                || Fxml2PropertyNameUtil.isGetterName(name)
                                || Fxml2PropertyNameUtil.isSetterName(name));
                if (isAccessor
                        && !ktFun.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD)) {
                    return ktFun.getContainingFile().getVirtualFile();
                }
            }
        } catch (NoClassDefFoundError ignored) {
            // Kotlin plugin absent at runtime.
        }
        return null;
    }

    /**
     * A {@link GlobalSearchScope} that accepts only {@code .fxml} and {@code .fxml2}
     * virtual files within the delegate scope.
     */
    public static final class Fxml2FilesSearchScope extends DelegatingGlobalSearchScope {

        public Fxml2FilesSearchScope(@NotNull GlobalSearchScope baseScope) {
            super(baseScope);
        }

        @Override
        public boolean contains(@NotNull VirtualFile file) {
            if (!super.contains(file)) return false;
            String name = file.getName();
            return name.endsWith(".fxml") || name.endsWith(".fxml2");
        }
    }
}
