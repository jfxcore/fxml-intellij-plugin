package org.jfxcore.fxml.resolve;

import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiInvalidElementAccessException;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Tests the declared type used by observable selection in binding paths. */
public final class Fxml2ObservableValueResolver {
    private Fxml2ObservableValueResolver() {}

    public static boolean isNotObservableDeclaration(@Nullable PsiElement declaration, @NotNull XmlFile file) {
        if (declaration == null || !declaration.isValid()) return true;
        var observable = Fxml2WellKnownClasses.observableValue(file.getProject());
        if (observable == null) return true;
        var type = switch (declaration) {
            case PsiField field -> field.getType();
            case PsiMethod method -> method.getReturnType();
            default -> null;
        };
        if (!(type instanceof PsiClassType classType)) return true;
        try {
            var resolved = classType.resolve();
            return resolved == null || (!resolved.equals(observable) && !resolved.isInheritor(observable, true));
        } catch (PsiInvalidElementAccessException ignored) {
            return true;
        }
    }
}
