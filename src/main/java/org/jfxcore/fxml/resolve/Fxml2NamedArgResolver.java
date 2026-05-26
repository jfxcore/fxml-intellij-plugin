package org.jfxcore.fxml.resolve;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiLiteralValue;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Resolves {@code @NamedArg} constructor parameters in JavaFX classes, mirroring the
 * FXML compiler's {@code ValueEmitterFactory.findNamedArgsConstructors} / {@code getNamedArgParams} logic.
 *
 * <p>A constructor is a "NamedArgs constructor" if <em>every</em> formal parameter is annotated
 * with {@code @javafx.beans.NamedArg}.  Only public constructors are considered.
 *
 * <p>The compiler decides to use named-arg construction when it cannot instantiate the object via
 * the default (no-arg) constructor while satisfying all the supplied FXML attributes as JavaFX
 * properties.  The plugin mirrors this by:
 * <ol>
 *   <li>First checking whether the attribute name resolves as a regular JavaFX property.  If it
 *       does, the default-constructor path is used and NamedArg resolution is not required.</li>
 *   <li>If it does <em>not</em> resolve as a JavaFX property, checking all NamedArg constructors
 *       of the owning class for a parameter whose {@code @NamedArg("value")} equals the attribute
 *       name.</li>
 * </ol>
 */
public final class Fxml2NamedArgResolver {

    /** Fully-qualified name of the {@code @NamedArg} annotation. */
    public static final String NAMED_ARG_FQN = "javafx.beans.NamedArg";

    private Fxml2NamedArgResolver() {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------


    /**
     * Returns {@code true} if {@code ownerClass} has at least one public NamedArgs constructor
     * (i.e. every parameter of that constructor carries {@code @NamedArg}).
     */
    public static boolean hasNamedArgConstructor(@NotNull PsiClass ownerClass) {
        for (PsiMethod constructor : ownerClass.getConstructors()) {
            if (!constructor.hasModifierProperty(PsiModifier.PUBLIC)) continue;
            if (isFullyAnnotatedNamedArgs(constructor)) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} when the supplied attribute name should be resolved as a {@code @NamedArg}
     * parameter rather than a regular JavaFX property, matching the FXML compiler's decision logic.
     *
     * <p>The compiler uses named-arg construction when at least one attribute on the FXML tag does
     * <em>not</em> resolve as a JavaFX property.  Rather than scanning all sibling attributes here
     * (which would be expensive), the plugin uses the simpler heuristic:
     *
     * <ul>
     *   <li>If the owning class has no public no-arg constructor, the named-arg path is always taken
     *       and every attribute must be either a JavaFX property or a named-arg parameter.</li>
     *   <li>If the class has a no-arg constructor, attributes that are regular JavaFX properties are
     *       handled by the default-constructor path; an attribute that is <em>not</em> a regular
     *       JavaFX property is then checked against NamedArg parameters.</li>
     * </ul>
     *
     * In practice this means: if the attribute name cannot be resolved as a JavaFX property, we
     * check whether it is a NamedArg parameter name.  If it is, it is valid.  The caller is
     * responsible for performing the JavaFX-property check first.
     *
     * @param ownerClass    the class owning the tag
     * @param paramName     the attribute/property name to look up
     * @param siblingAttributes names of the other attributes on the same tag (used to check
     *                          whether the named-arg constructor can be fully satisfied)
     * @return the matching {@link PsiParameter}, or {@code null}
     */
    public static @Nullable PsiParameter resolveNamedArgIfApplicable(
            @NotNull PsiClass ownerClass,
            @NotNull String paramName,
            @NotNull Collection<String> siblingAttributes) {

        // Find the best-matching NamedArgs constructor: the one with the most parameters that are
        // either supplied by siblingAttributes or are optional (have a defaultValue in @NamedArg).
        // This mirrors ValueEmitterFactory.findNamedArgsConstructors.
        List<PsiMethod> candidates = new ArrayList<>();
        for (PsiMethod constructor : ownerClass.getConstructors()) {
            if (!constructor.hasModifierProperty(PsiModifier.PUBLIC)) continue;
            if (!isFullyAnnotatedNamedArgs(constructor)) continue;

            PsiParameter[] params = constructor.getParameterList().getParameters();
            // Check that all non-optional params are satisfied by siblingAttributes.
            boolean allSatisfied = true;
            for (PsiParameter p : params) {
                String name = namedArgValue(p);
                if (name == null) { allSatisfied = false; break; }
                boolean supplied = siblingAttributes.contains(name);
                boolean optional = namedArgDefaultValue(p) != null;
                if (!supplied && !optional) { allSatisfied = false; break; }
            }
            if (allSatisfied) {
                candidates.add(constructor);
            }
        }

        // Among candidates, find one that contains paramName.
        for (PsiMethod constructor : candidates) {
            PsiParameter param = findNamedArgParam(constructor, paramName);
            if (param != null) return param;
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if every parameter of {@code constructor} has an {@code @NamedArg}
     * annotation with a non-empty {@code value}.
     */
    public static boolean isFullyAnnotatedNamedArgs(@NotNull PsiMethod constructor) {
        PsiParameter[] params = constructor.getParameterList().getParameters();
        if (params.length == 0) return false; // a no-arg constructor is not a NamedArgs constructor
        for (PsiParameter p : params) {
            if (namedArgValue(p) == null) return false;
        }
        return true;
    }

    /**
     * Returns the {@link PsiParameter} inside {@code constructor} whose {@code @NamedArg("value")}
     * equals {@code name}, or {@code null}.
     *
     * <p>The constructor must be a NamedArgs constructor (all params annotated).
     */
    private static @Nullable PsiParameter findNamedArgParam(
            @NotNull PsiMethod constructor, @NotNull String name) {
        if (!isFullyAnnotatedNamedArgs(constructor)) return null;
        for (PsiParameter p : constructor.getParameterList().getParameters()) {
            if (name.equals(namedArgValue(p))) return p;
        }
        return null;
    }

    /**
     * Returns the string value of the {@code value} attribute of {@code @NamedArg} on {@code param},
     * or {@code null} if the annotation is absent or its value is empty/missing.
     */
    public static @Nullable String namedArgValue(@NotNull PsiParameter param) {
        PsiAnnotation ann = param.getAnnotation(NAMED_ARG_FQN);
        if (ann == null) return null;
        PsiAnnotationMemberValue val = ann.findAttributeValue("value");
        if (!(val instanceof PsiLiteralValue lit)) return null;
        Object v = lit.getValue();
        if (!(v instanceof String s) || s.isEmpty()) return null;
        return s;
    }

    /**
     * Returns the explicit {@code defaultValue} string of {@code @NamedArg} on {@code param},
     * or {@code null} if the parameter is required (no explicit non-empty defaultValue).
     *
     * <p>The FXML compiler treats a param as optional only when {@code defaultValue} is
     * explicitly set in the bytecode to a non-empty string.  IntelliJ's
     * {@code findAttributeValue("defaultValue")} returns {@code ""} (the annotation-declared
     * default) even when the annotation element was never written, so we must reject empty
     * strings to match the compiler's behavior.
     */
    public static @Nullable String namedArgDefaultValue(@NotNull PsiParameter param) {
        PsiAnnotation ann = param.getAnnotation(NAMED_ARG_FQN);
        if (ann == null) return null;
        PsiAnnotationMemberValue val = ann.findAttributeValue("defaultValue");
        if (val == null) return null;
        if (!(val instanceof PsiLiteralValue lit)) return null;
        Object v = lit.getValue();
        // Empty string "" is the annotation-declared default and means "not set" -> required.
        if (!(v instanceof String s) || s.isEmpty()) return null;
        return s;
    }

    /**
     * Convenience: collects all attribute names on {@code tag} except {@code fx:} prefixed ones
     * and namespace declarations.
     */
    public static @NotNull List<String> collectAttributeNames(@NotNull XmlTag tag) {
        List<String> names = new ArrayList<>();
        for (var attr : tag.getAttributes()) {
            String name = attr.getName();
            if (!Fxml2XmlUtil.isNonPropertyAttribute(name)) {
                names.add(name);
            }
        }
        return names;
    }
}
