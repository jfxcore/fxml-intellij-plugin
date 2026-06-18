package org.jfxcore.fxml.lang;

import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.PsiWildcardType;
import com.intellij.psi.util.TypeConversionUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2WellKnownClasses;

/**
 * Shared utilities for event-handler method references in FXML.
 *
 * <p>When a property type is {@code EventHandler<T>}, the FXML compiler accepts a
 * plain method name as the attribute value and wires it as a method reference on the
 * code-behind class.  This utility centralizes:
 * <ul>
 *   <li>detection of {@code EventHandler}-typed properties
 *       ({@link #isEventHandlerType}),</li>
 *   <li>extraction of the concrete event type {@code T} from
 *       {@code EventHandler<T>} ({@link #extractEventTypeClass}),</li>
 *   <li>signature compatibility check for a candidate method
 *       ({@link #isCompatibleHandlerMethod}), and</li>
 *   <li>selection of the best matching overload ({@link #findBestHandlerMethod}).</li>
 * </ul>
 *
 * <p>Used by:
 * <ul>
 *   <li>{@link org.jfxcore.fxml.annotator.Fxml2AttributeAnnotator}: validates that
 *       the referenced method exists and has a compatible signature.</li>
 *   <li>{@link Fxml2ReferenceContributor}: provides a Ctrl+click reference from the
 *       attribute value to the method declaration.</li>
 *   <li>{@link Fxml2EventHandlerMethodSearcher}: surfaces event-handler attribute
 *       values as usages when "Find Usages" is invoked on the Java method.</li>
 * </ul>
 */
public final class Fxml2EventHandlerUtil {

    private Fxml2EventHandlerUtil() {}

    /**
     * Returns {@code true} when {@code type} is {@code javafx.event.EventHandler} or a
     * parameterized form of it (e.g. {@code EventHandler<ActionEvent>}).
     *
     * <p>Uses erasure-based assignability so that subtypes of {@code EventHandler} are
     * also accepted, matching the FXML compiler's {@code subtypeOf(EventHandlerDecl())}
     * check in {@code PropertyAssignmentTransform}.
     */
    public static boolean isEventHandlerType(@Nullable PsiType type, @NotNull Project project) {
        if (type == null) return false;
        PsiClass eventHandlerClass = Fxml2WellKnownClasses.eventHandler(project);
        if (eventHandlerClass == null) return false;
        PsiType erased = TypeConversionUtil.erasure(type);
        PsiType eventHandlerRaw = JavaPsiFacade.getElementFactory(project)
                .createType(eventHandlerClass);
        return erased != null && TypeConversionUtil.isAssignable(eventHandlerRaw, erased);
    }

    /**
     * Extracts the event-type argument {@code T} from an {@code EventHandler<T>} type.
     *
     * <p>Returns the resolved {@link PsiClass} for {@code T}, or {@code null} when the
     * type is raw (no generic parameter), uses a wildcard whose bound cannot be resolved,
     * or the argument is an unresolved type variable.
     *
     * <p>Example: for {@code EventHandler<ActionEvent>} returns the {@code ActionEvent} class.
     */
    public static @Nullable PsiClass extractEventTypeClass(@Nullable PsiType propType) {
        if (!(propType instanceof PsiClassType classType)) return null;
        PsiType[] typeArgs = classType.getParameters();
        if (typeArgs.length == 0) return null;
        PsiType arg = typeArgs[0];
        if (arg instanceof PsiWildcardType wildcard) {
            arg = wildcard.getBound();
        }
        if (!(arg instanceof PsiClassType eventClassType)) return null;
        return eventClassType.resolve();
    }

    /**
     * Returns {@code true} when {@code method} is a valid event-handler target for an
     * {@code EventHandler<T>} property where {@code eventType} is {@code T}.
     *
     * <p>A compatible signature satisfies all of the following:
     * <ul>
     *   <li>Return type is {@code void}.</li>
     *   <li>The parameter list is empty, <em>or</em> contains exactly one parameter
     *       whose type is {@code T} or a subtype of {@code T}.</li>
     * </ul>
     *
     * <p>When {@code eventType} is {@code null} (raw {@code EventHandler} without a type
     * argument), any {@code void} method with 0 or 1 parameters is considered compatible.
     *
     * <p>Mirrors the FXML compiler's {@code EventHandlerGenerator.findMethod()} logic.
     */
    public static boolean isCompatibleHandlerMethod(@NotNull PsiMethod method,
                                                    @Nullable PsiClass eventType) {
        if (!PsiTypes.voidType().equals(method.getReturnType())) return false;
        PsiParameter[] params = method.getParameterList().getParameters();
        if (params.length == 0) return true;
        if (params.length != 1) return false;
        if (eventType == null) return true;
        PsiType paramType = params[0].getType();
        if (!(paramType instanceof PsiClassType ct)) return false;
        PsiClass paramClass = ct.resolve();
        if (paramClass == null) return false;
        return paramClass.equals(eventType) || paramClass.isInheritor(eventType, true);
    }

    /**
     * Finds the best-matching event-handler method on {@code codeBehind} for the given
     * {@code methodName} and {@code eventType}.
     *
     * <p>Selection mirrors the FXML compiler's preference: when both a zero-parameter
     * and a one-parameter overload are compatible, the one-parameter overload is preferred
     * (matching {@code EventHandlerGenerator.findMethod()}).
     *
     * @param codeBehind the code-behind class to search (including inherited methods)
     * @param methodName the event-handler method name (already trimmed)
     * @param eventType  the event type {@code T} from {@code EventHandler<T>}, or
     *                   {@code null} for raw {@code EventHandler}
     * @return the best matching {@link PsiMethod}, or {@code null} if none is found
     */
    public static @Nullable PsiMethod findBestHandlerMethod(@NotNull PsiClass codeBehind,
                                                            @NotNull String methodName,
                                                            @Nullable PsiClass eventType) {
        PsiMethod[] methods = codeBehind.findMethodsByName(methodName, true);
        PsiMethod noParamMatch = null;
        PsiMethod paramMatch = null;
        for (PsiMethod m : methods) {
            if (!isCompatibleHandlerMethod(m, eventType)) continue;
            if (m.getParameterList().getParametersCount() == 0) {
                if (noParamMatch == null) noParamMatch = m;
            } else {
                if (paramMatch == null) paramMatch = m;
            }
        }
        return paramMatch != null ? paramMatch : noParamMatch;
    }
}
