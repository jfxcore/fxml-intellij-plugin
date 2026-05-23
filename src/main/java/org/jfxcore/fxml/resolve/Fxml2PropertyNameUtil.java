package org.jfxcore.fxml.resolve;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PropertyUtilBase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Utility methods for recognising and converting JavaFX / JavaBeans accessor naming
 * conventions to plain property names.
 *
 * <p>The three naming patterns handled are:
 * <ul>
 *   <li>{@code fooProperty()}: JavaFX observable-property accessor; property name is {@code foo}.</li>
 *   <li>{@code getFoo()} / {@code isFoo()}: standard JavaBeans getter; property name is {@code foo}.</li>
 *   <li>{@code setFoo(T)}: standard JavaBeans setter; property name is {@code foo}.</li>
 * </ul>
 *
 * <p>These predicates and converters are used in many places across the plugin (property resolution,
 * binding-path resolution, completion, annotators, implicit-usage providers, reference searchers) so
 * centralising them here avoids repetitive inline string checks.
 */
public final class Fxml2PropertyNameUtil {

    private Fxml2PropertyNameUtil() {}

    // -----------------------------------------------------------------------
    // Accessor-name -> property-name conversion
    // -----------------------------------------------------------------------

    /**
     * Converts a JavaFX accessor method name to its property name, returning
     * {@code name} unchanged if it does not match any known accessor convention.
     *
     * <ul>
     *   <li>{@code setFoo(T)} / {@code getFoo()} / {@code isFoo()} -> {@code "foo"}</li>
     *   <li>{@code fooProperty()} -> {@code "foo"}</li>
     * </ul>
     */
    public static @NotNull String toPropertyName(@NotNull String name) {
        if (isSetterName(name))            return setterToPropertyName(name);
        if (isGetterName(name))            return getterToPropertyName(name);
        if (isPropertyAccessorName(name))  return propertyAccessorToPropertyName(name);
        return name;
    }

    // -----------------------------------------------------------------------
    // Predicates on raw method names
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when {@code name} follows the JavaFX {@code xProperty()} convention -
     * i.e. it ends with {@code "Property"} and is long enough to have at least one character
     * before the suffix.
     *
     * <p>Callers that additionally require the method to have no parameters should check
     * {@link PsiMethod#getParameterList()} themselves.
     */
    public static boolean isPropertyAccessorName(@NotNull String name) {
        return name.length() > 8 && name.endsWith("Property");
    }

    /**
     * Returns {@code true} when {@code name} follows the JavaBeans setter convention -
     * i.e. it starts with {@code "set"}, is longer than three characters, and has an
     * upper-case letter immediately after the prefix.
     *
     * <p>Callers that additionally require a specific parameter count should check
     * {@link PsiMethod#getParameterList()} themselves.
     */
    public static boolean isSetterName(@NotNull String name) {
        return name.length() > 3
                && name.startsWith("set")
                && Character.isUpperCase(name.charAt(3));
    }

    /**
     * Returns {@code true} when {@code name} follows the JavaBeans getter convention -
     * i.e. it starts with {@code "get"} (+ upper-case 4th char) or {@code "is"}
     * (+ upper-case 3rd char).
     *
     * <p>Callers that additionally require no parameters / a non-void return type should
     * check {@link PsiMethod#getParameterList()} and {@link PsiMethod#getReturnType()} themselves.
     */
    public static boolean isGetterName(@NotNull String name) {
        return (name.length() > 3 && name.startsWith("get") && Character.isUpperCase(name.charAt(3)))
                || (name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2)));
    }

    // -----------------------------------------------------------------------
    // Name converters (call only after the corresponding predicate returned true)
    // -----------------------------------------------------------------------

    /**
     * Strips the {@code "Property"} suffix: {@code "fooProperty"} -> {@code "foo"}.
     * Only call after {@link #isPropertyAccessorName} returned {@code true}.
     */
    public static @NotNull String propertyAccessorToPropertyName(@NotNull String name) {
        return name.substring(0, name.length() - 8);
    }

    /**
     * Strips the {@code "set"} prefix and lower-cases the next character:
     * {@code "setFoo"} -> {@code "foo"}.
     * Only call after {@link #isSetterName} returned {@code true}.
     */
    public static @NotNull String setterToPropertyName(@NotNull String name) {
        return Character.toLowerCase(name.charAt(3)) + name.substring(4);
    }

    /**
     * Strips the {@code "get"} / {@code "is"} prefix and lower-cases the next character:
     * {@code "getFoo"} -> {@code "foo"}, {@code "isFoo"} -> {@code "foo"}.
     * Only call after {@link #isGetterName} returned {@code true}.
     */
    public static @NotNull String getterToPropertyName(@NotNull String name) {
        if (name.startsWith("get")) {
            return Character.toLowerCase(name.charAt(3)) + name.substring(4);
        }
        // "is..."
        return Character.toLowerCase(name.charAt(2)) + name.substring(3);
    }

    // -----------------------------------------------------------------------
    // Higher-level helpers
    // -----------------------------------------------------------------------


    /**
     * Derives the property name from a {@link PsiMethod}.
     *
     * <p>Uses {@link PropertyUtilBase#getPropertyName} for getter recognition so that
     * all IntelliJ-defined getter heuristics are respected.
     *
     * <ul>
     *   <li>{@code xProperty()}: returns {@code "x"}</li>
     *   <li>{@code getX()} / {@code isX()}: returns {@code "x"} (no-arg only)</li>
     *   <li>{@code setX(T)}: returns {@code "x"} (single-arg only)</li>
     * </ul>
     *
     * @return the derived property name, or {@code null} if the method name matches no pattern
     */
    public static @Nullable String propertyNameFromMethod(@NotNull PsiMethod m) {
        String name = m.getName();
        // xProperty() -> "x"
        if (isPropertyAccessorName(name) && m.getParameterList().isEmpty()) {
            return propertyAccessorToPropertyName(name);
        }
        // getX() / isX() -> "x"  (standard JavaBeans no-arg getter)
        if (m.getParameterList().isEmpty()) {
            String prop = PropertyUtilBase.getPropertyName(m);
            if (prop != null) return prop;
        }
        // setX(T) -> "x"  (JavaBeans setter)
        if (isSetterName(name) && m.getParameterList().getParametersCount() == 1) {
            return setterToPropertyName(name);
        }
        return null;
    }

    /**
     * Derives the property name from a Java or Kotlin PSI element.
     *
     * <ul>
     *   <li>{@link PsiField}          -> field name</li>
     *   <li>{@link PsiMethod}         -> {@link #propertyNameFromMethod(PsiMethod)}</li>
     *   <li>Kotlin {@code KtProperty} -> property name</li>
     *   <li>Kotlin {@code KtNamedFunction} -> function-name stripping (same patterns as
     *       {@link #propertyNameFromMethod} but using Kotlin value-parameters)</li>
     * </ul>
     *
     * <p>The Kotlin branch is guarded by {@link NoClassDefFoundError} so that the method
     * works correctly when the Kotlin plugin is absent at runtime.
     *
     * @return the derived property name, or {@code null} if the element is not a recognized
     *         field / method / property
     */
    public static @Nullable String propertyNameFromElement(@NotNull PsiElement element) {
        // Java field: property name = field name
        if (element instanceof PsiField f) return f.getName();

        // Java method: strip common naming patterns
        if (element instanceof PsiMethod m) return propertyNameFromMethod(m);

        // Kotlin elements: guarded by NoClassDefFoundError in case the Kotlin plugin is absent.
        try {
            if (element instanceof org.jetbrains.kotlin.psi.KtNamedFunction ktFun) {
                String name = ktFun.getName();
                if (name == null) return null;
                // fooProperty() -> "foo"
                if (isPropertyAccessorName(name) && ktFun.getValueParameters().isEmpty()) {
                    return propertyAccessorToPropertyName(name);
                }
                // getX() / isX() -> "x"  (JavaBeans-style Kotlin function)
                if (ktFun.getValueParameters().isEmpty() && isGetterName(name)) {
                    return getterToPropertyName(name);
                }
                // setX(T) -> "x"  (JavaBeans-style Kotlin setter)
                if (isSetterName(name) && ktFun.getValueParameters().size() == 1) {
                    return setterToPropertyName(name);
                }
                return null;
            }
            if (element instanceof org.jetbrains.kotlin.psi.KtProperty ktProp) {
                return ktProp.getName();
            }
        } catch (NoClassDefFoundError ignored) {
            // Kotlin plugin absent at runtime: Kotlin elements won't reach here.
        }
        return null;
    }
}
