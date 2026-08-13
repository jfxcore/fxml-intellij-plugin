package org.jfxcore.fxml.resolve;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves type arguments across a supertype hierarchy.
 *
 * <p>A type argument that a class supplies to one of its supertypes is only meaningful together
 * with the substitutions made along the way: in {@code ObservableList<Double>} the element type of
 * {@code Collection<E>} is reached by composing the substitutor of every step from the class to
 * {@code Collection}.  The type utilities of the platform stop short of this on class stubs whose
 * supertypes are not fully resolved, so the walk is performed explicitly.
 */
public final class Fxml2TypeHierarchy {

    private Fxml2TypeHierarchy() {}

    /**
     * Walks from {@code cls} to {@code ancestor} and returns the substitutor that applies at
     * {@code ancestor}, so that its type parameters can be substituted with the arguments
     * {@code cls} supplies.
     *
     * @param substitutor the substitutor that applies at {@code cls}
     * @return the substitutor at {@code ancestor}, or {@code null} when {@code cls} does not
     *         inherit from it
     */
    public static @Nullable PsiSubstitutor substitutorFor(
            @NotNull PsiClass cls,
            @NotNull PsiClass ancestor,
            @NotNull PsiSubstitutor substitutor) {

        if (cls.equals(ancestor)) return substitutor;
        for (PsiClassType superType : cls.getSuperTypes()) {
            PsiClassType.ClassResolveResult resolved = superType.resolveGenerics();
            PsiClass superClass = resolved.getElement();
            if (superClass == null) continue;
            PsiSubstitutor found = substitutorFor(superClass, ancestor,
                    compose(resolved.getSubstitutor(), substitutor));
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Returns the type argument {@code cls} supplies for the type parameter of {@code ancestor} at
     * {@code index}, or {@code null} when it cannot be determined.
     *
     * @param substitutor the substitutor that applies at {@code cls}
     */
    public static @Nullable PsiType typeArgumentFor(
            @NotNull PsiClass cls,
            @NotNull PsiClass ancestor,
            int index,
            @NotNull PsiSubstitutor substitutor) {

        PsiSubstitutor atAncestor = substitutorFor(cls, ancestor, substitutor);
        if (atAncestor == null) return null;
        var typeParams = ancestor.getTypeParameters();
        return index < typeParams.length ? atAncestor.substitute(typeParams[index]) : null;
    }

    /**
     * Composes two substitutors: every value of {@code inner} is mapped through {@code outer}, so
     * that the result carries the substitutions accumulated along a supertype chain.
     */
    private static @NotNull PsiSubstitutor compose(
            @NotNull PsiSubstitutor inner, @NotNull PsiSubstitutor outer) {
        PsiSubstitutor composed = PsiSubstitutor.EMPTY;
        for (var entry : inner.getSubstitutionMap().entrySet()) {
            PsiType mapped = entry.getValue();
            composed = composed.put(entry.getKey(), mapped != null ? outer.substitute(mapped) : null);
        }
        return composed;
    }
}
