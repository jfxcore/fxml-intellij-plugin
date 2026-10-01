package org.jfxcore.fxml.resolve;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Resolves configuration parameters of inline markup extensions. */
public final class Fxml2MarkupExtensionResolver {
    private Fxml2MarkupExtensionResolver() {}

    /** Finds the named constructor parameter, property accessor, or setter. */
    public static @Nullable PsiElement resolveParameter(
            @NotNull String paramName, @NotNull PsiClass extClass) {

        // 1. @NamedArg constructor parameters
        for (PsiMethod ctor : extClass.getConstructors()) {
            for (PsiParameter p : ctor.getParameterList().getParameters()) {
                if (paramName.equals(Fxml2NamedArgResolver.namedArgValue(p))) return p;
            }
        }
        // 2. JavaFX property method: paramNameProperty()
        String propertyMethodName = paramName + "Property";
        for (PsiMethod m : extClass.findMethodsByName(propertyMethodName, true)) {
            if (m.getParameterList().getParametersCount() == 0) return m;
        }
        // 3. Setter method: setParamName(T)
        if (!paramName.isEmpty()) {
            String setterName = "set" + Character.toUpperCase(paramName.charAt(0))
                    + paramName.substring(1);
            for (PsiMethod m : extClass.findMethodsByName(setterName, true)) {
                if (m.getParameterList().getParametersCount() == 1) return m;
            }
        }
        return null;
    }

}
