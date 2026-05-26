// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiLiteralExpression;
import org.jetbrains.annotations.NotNull;

/**
 * Utilities for detecting markup extension semantics by convention.
 *
 * <h3>Resource-key extension convention</h3>
 * <p>A markup extension class is considered a <em>resource-key extension</em> when both of
 * the following conditions are satisfied:
 * <ol>
 *   <li>The unqualified class name contains the substring {@code "Resource"} (case-sensitive).</li>
 *   <li>The class is annotated with {@code @DefaultProperty("key")}.</li>
 * </ol>
 * <p>Extensions that satisfy this convention take a resource bundle key as their positional
 * default argument, enabling the IDE to provide navigation to resource bundle entries,
 * hover documentation, and unused-property suppression for that argument.
 *
 * <p>The built-in extensions {@code StaticResource} and {@code DynamicResource} from the
 * {@code org.jfxcore.markup.resource} package satisfy the convention by construction.
 * Custom markup extensions whose class name contains {@code "Resource"} and that carry
 * {@code @DefaultProperty("key")} will be treated the same way automatically.
 *
 * <h3>Future extension point</h3>
 * <p>The convention implemented here is intentionally isolated in this single class so that
 * it can later be replaced by an explicit annotation (e.g., {@code @ResourceKeyExtension})
 * without touching any other code.
 */
public final class Fxml2MarkupExtensionUtil {

    /** FQN of the {@code @DefaultProperty} annotation used by JavaFX / JFXcore. */
    private static final String DEFAULT_PROPERTY_FQN = "javafx.beans.DefaultProperty";

    /** The {@code @DefaultProperty} value that identifies the resource-key parameter. */
    private static final String RESOURCE_KEY_PARAM_NAME = "key";

    /** Substring that must appear in the unqualified class name. */
    private static final String RESOURCE_NAME_FRAGMENT = "Resource";

    private Fxml2MarkupExtensionUtil() {}

    // -----------------------------------------------------------------------
    // Resource-key extension detection
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if {@code extClass} is a resource-key markup extension by convention.
     *
     * <p>Both criteria must be satisfied:
     * <ul>
     *   <li>The unqualified class name contains {@code "Resource"}.</li>
     *   <li>The class carries {@code @DefaultProperty("key")}.</li>
     * </ul>
     *
     * <p>This is the canonical check. All IDE features that must decide whether a markup
     * extension's positional default argument is a resource bundle key should call this method.
     * When a dedicated annotation is introduced in the future, only this method needs to change.
     *
     * @param extClass the resolved markup extension class
     * @return {@code true} if the class satisfies the resource-key extension convention
     */
    public static boolean isResourceKeyExtension(@NotNull PsiClass extClass) {
        String name = extClass.getName();
        if (name == null || !name.contains(RESOURCE_NAME_FRAGMENT)) {
            return false;
        }
        return hasDefaultPropertyKey(extClass);
    }

    /**
     * Returns {@code true} if the unqualified class name alone suggests a resource-key extension.
     *
     * <p>This is a lightweight approximation for contexts where only the class name is available
     * as a string (e.g., when scanning raw markup attribute values without PSI class resolution).
     * It applies only the name criterion and <em>cannot</em> verify {@code @DefaultProperty};
     * callers that have access to a {@link PsiClass} should use
     * {@link #isResourceKeyExtension(PsiClass)} instead.
     *
     * @param simpleClassName the unqualified class name extracted from markup
     * @return {@code true} if the name contains {@code "Resource"}
     */
    public static boolean simpleNameSuggestsResourceKeyExtension(@NotNull String simpleClassName) {
        return simpleClassName.contains(RESOURCE_NAME_FRAGMENT);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if {@code psiClass} is annotated with
     * {@code @DefaultProperty("key")}.
     */
    private static boolean hasDefaultPropertyKey(@NotNull PsiClass psiClass) {
        PsiAnnotation annotation = psiClass.getAnnotation(DEFAULT_PROPERTY_FQN);
        if (annotation == null) {
            return false;
        }
        PsiAnnotationMemberValue value = annotation.findAttributeValue("value");
        if (value instanceof PsiLiteralExpression literal) {
            Object v = literal.getValue();
            return RESOURCE_KEY_PARAM_NAME.equals(v);
        }
        return false;
    }
}
