package org.jfxcore.fxml.resolve;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Determines what an attribute value denotes: a single value, or a sequence of values.
 *
 * <p>An attribute value is a comma-separated sequence when its target collects several values -
 * a collection, an array, or a type that is implicitly constructed from named arguments.  For
 * every other target a separator carries no special meaning and the whole value is one literal,
 * which is why {@code text="hello, world"} assigns the string {@code "hello, world"}.
 *
 * <p>The number of items takes part in the decision: implicit construction selects the
 * constructor whose parameter count matches the sequence, so a value that matches no
 * constructor has no argument target at all.
 */
public final class Fxml2ValueTargetResolver {

    private Fxml2ValueTargetResolver() {}

    /** What the items of an attribute value are assigned to. */
    public sealed interface ValueTarget {}

    /**
     * A target that takes the whole value as a single value.  Separators in the value are part
     * of the literal.
     *
     * @param type the target type
     */
    public record Scalar(@NotNull PsiType type) implements ValueTarget {}

    /**
     * A target that collects the items of the value: an array or a collection.
     *
     * @param itemType the array component type or the collection element type, or {@code null}
     *                 when the element type cannot be resolved
     */
    public record Items(@Nullable PsiType itemType) implements ValueTarget {}

    /**
     * A target that is implicitly constructed from the items of the value, one item per
     * constructor parameter.
     *
     * <p>When several constructors share the parameter count, the first one declared is used, so
     * that all consumers see the same parameter list for a given value.
     *
     * @param parameters the parameters of the selected {@code @NamedArg} constructor
     */
    public record Arguments(@NotNull List<PsiParameter> parameters) implements ValueTarget {}

    /**
     * Resolves what {@code targetType} takes from an attribute value of {@code itemCount} items.
     *
     * @param targetType the property, parameter or element type the value is assigned to
     * @param itemCount  the number of items in the value, which selects the constructor of an
     *                   implicitly constructed target
     * @param scope      the resolve scope
     * @return the target, which is a {@link Scalar} when the value is not a sequence
     */
    public static @NotNull ValueTarget resolveTarget(
            @NotNull PsiType targetType,
            int itemCount,
            @NotNull GlobalSearchScope scope) {

        if (targetType instanceof PsiArrayType arrayType) {
            return new Items(arrayType.getComponentType());
        }

        PsiClass targetClass = PsiUtil.resolveClassInType(targetType);
        if (targetClass == null) return new Scalar(targetType);

        if (isCollection(targetClass, targetType, scope)) {
            return new Items(collectionItemType(targetType, targetClass, scope));
        }

        // Implicit construction: the constructor whose parameter count matches the sequence.
        for (PsiMethod constructor : Fxml2NamedArgResolver.namedArgConstructors(targetClass)) {
            List<PsiParameter> params = List.of(constructor.getParameterList().getParameters());
            if (params.size() == itemCount) return new Arguments(params);
        }

        return new Scalar(targetType);
    }

    /**
     * Returns {@code true} when values are collected by {@code targetType}, i.e. it is a
     * {@code java.util.Collection} or exposes an element type.
     */
    private static boolean isCollection(
            @NotNull PsiClass targetClass,
            @NotNull PsiType targetType,
            @NotNull GlobalSearchScope scope) {

        if (PsiUtil.extractIterableTypeParameter(targetType, false) != null) return true;
        PsiClass collectionClass = JavaPsiFacade.getInstance(targetClass.getProject())
                .findClass("java.util.Collection", scope);
        return collectionClass != null
                && (targetClass.equals(collectionClass) || targetClass.isInheritor(collectionClass, true));
    }

    /**
     * Returns the element type of a collection target, or {@code null} when it cannot be
     * resolved, which includes an element type that is still a type parameter.
     *
     * <p>{@link PsiUtil#extractIterableTypeParameter} is tried first and the
     * {@code java.util.Collection} supertype chain is walked explicitly as a fallback, because
     * the {@code Iterable} supertype of a class stub is not always fully resolved by the type
     * utilities.
     */
    private static @Nullable PsiType collectionItemType(
            @NotNull PsiType targetType,
            @NotNull PsiClass targetClass,
            @NotNull GlobalSearchScope scope) {

        PsiType itemType = PsiUtil.extractIterableTypeParameter(targetType, false);
        if (itemType == null) {
            PsiClass collectionClass = JavaPsiFacade.getInstance(targetClass.getProject())
                    .findClass("java.util.Collection", scope);
            if (collectionClass != null && targetType instanceof PsiClassType classType) {
                PsiClassType.ClassResolveResult resolved = classType.resolveGenerics();
                PsiClass element = resolved.getElement();
                if (element != null) {
                    itemType = Fxml2TypeHierarchy.typeArgumentFor(
                            element, collectionClass, 0, resolved.getSubstitutor());
                }
            }
        }
        if (itemType instanceof PsiClassType classType && classType.resolve() instanceof PsiTypeParameter) {
            return null;
        }
        return itemType;
    }

}
