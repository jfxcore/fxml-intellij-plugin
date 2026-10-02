package org.jfxcore.fxml.lang;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.name.FqName;
import org.jetbrains.kotlin.psi.KtClassOrObject;

/**
 * Maps Kotlin class declarations to their Java {@link PsiClass} view.
 *
 * <p>The lookup goes through {@link JavaPsiFacade}, which returns the light class that the
 * Kotlin plugin registers for the declaration. Among all classes with the declaration's
 * fully-qualified name in its resolve scope, the one navigating back to the declaration is
 * selected, so that same-named classes in other modules are never returned.
 */
public final class Fxml2KotlinPsiClassUtil {

    private Fxml2KotlinPsiClassUtil() {}

    /**
     * Returns the Java view of {@code ktClass}, or {@code null} if the declaration has no
     * fully-qualified name (local or anonymous classes) or no Java view is available.
     */
    public static @Nullable PsiClass toPsiClass(@NotNull KtClassOrObject ktClass) {
        FqName fqName = ktClass.getFqName();
        if (fqName == null) return null;

        PsiManager manager = ktClass.getManager();
        PsiClass[] candidates = JavaPsiFacade.getInstance(ktClass.getProject())
                .findClasses(fqName.asString(), ktClass.getResolveScope());
        for (PsiClass candidate : candidates) {
            if (manager.areElementsEquivalent(candidate.getNavigationElement(), ktClass)) {
                return candidate;
            }
        }
        return null;
    }
}
