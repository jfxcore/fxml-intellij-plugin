package org.jfxcore.fxml.lang;

import com.intellij.codeInspection.InspectionSuppressor;
import com.intellij.codeInspection.SuppressQuickFix;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Suppresses false-positive "Property 'X' is never used" warnings from Kotlin's K2
 * unused-symbol inspection ({@code UnusedSymbol}) for {@link org.jetbrains.kotlin.psi.KtProperty}
 * elements that are referenced in FXML markup, either standalone or embedded.
 *
 * <h3>Why is this needed?</h3>
 * <p>Kotlin's K2 unused-declaration analysis distinguishes between two element kinds:
 * <ul>
 *   <li><b>{@code KtNamedFunction}</b>: K2's {@code isEntryPoint} calls
 *       {@code isJavaEntryPoint.isEntryPoint(lightMethod)}, which in turn consults all
 *       registered {@link com.intellij.codeInsight.daemon.ImplicitUsageProvider}s.
 *       Our {@link Fxml2StandaloneImplicitUsageProvider} returns {@code true} for light
 *       methods whose property name appears in a standalone FXML binding path, so K2
 *       correctly suppresses the warning.</li>
 *   <li><b>{@code KtProperty}</b>: K2's {@code isEntryPoint} returns early via an
 *       annotation-pattern check only; it <em>never</em> calls
 *       {@code isJavaEntryPoint.isEntryPoint(lightGetter)}, so {@code ImplicitUsageProvider}
 *       is never consulted.  K2 then falls back to {@code hasNonTrivialUsages}, which
 *       calls {@link com.intellij.psi.search.searches.MethodReferencesSearch} on the
 *       generated getter.  This path works correctly when search results are stable but
 *       can be pre-empted by a {@code ProcessCanceledException} or by timing races during
 *       the concurrent highlighting pass, leaving the old "unused" annotation in place.</li>
 * </ul>
 *
 * <p>This suppressor bridges the gap by hooking into
 * {@link com.intellij.codeInspection.SuppressionUtil#inspectionResultSuppressed}, which
 * K2's {@code KotlinUnusedHighlightingProcessor.handleDeclaration} calls at line 197
 * (before the expensive {@code getPsiToReportProblem} path).  When the element is a
 * {@code KtProperty} and an FXML reference resolves back to that property, this
 * suppressor returns {@code true} and the warning is suppressed immediately, without
 * depending on the reliability of the {@code MethodReferencesSearch} path.  Both
 * standalone and embedded markup are checked:
 * <ul>
 *   <li>{@link Fxml2StandaloneImplicitUsageProvider#isReferencedInStandaloneFxml}
 *       scans the file-system FXML files via the word index.</li>
 *   <li>{@link Fxml2EmbeddedImplicitUsageProvider#isReferencedInEmbeddedFxml} scans the
 *       injected XML of every {@code @ComponentView}-annotated class whose markup
 *       contains the property name.  This covers the {@code <fx:context>} case where the
 *       Kotlin property lives on a non-{@code @ComponentView} context class and is only
 *       referenced from another class's embedded FXML binding.</li>
 * </ul>
 *
 * <p>Registered via {@code lang.inspectionSuppressor language="kotlin"} in
 * {@code fxml2-with-kotlin.xml}, so it is loaded only when the Kotlin plugin is present.
 *
 * @see Fxml2StandaloneImplicitUsageProvider
 * @see Fxml2EmbeddedImplicitUsageProvider
 */
public final class Fxml2KotlinUnusedSymbolSuppressor implements InspectionSuppressor {

    /**
     * The {@code suppressId} of Kotlin's unused-symbol inspection, as declared in
     * the K1 ({@code inspections-fe10.xml}) and K2 ({@code kotlin.code-insight.inspections.k2.xml})
     * plugin descriptors via {@code suppressId="unused"} on the {@code <localInspection>} entry.
     *
     * <p>{@link com.intellij.codeInspection.SuppressionUtil#inspectionResultSuppressed}
     * calls {@code tool.isSuppressedFor(place)}, which in turn calls
     * {@link com.intellij.codeInspection.InspectionProfileEntry#getSuppressId()} on the K2
     * {@code UnusedSymbolInspection}.  For a {@link com.intellij.codeInspection.LocalInspectionTool}
     * this returns the {@code suppressId} attribute supplied in the {@code <localInspection>}
     * registration, not the inspection's short name.  The toolId passed to suppressors is
     * therefore {@code "unused"} (and additionally {@code "UNUSED_PARAMETER"}, the alternative
     * id, but parameters are out of scope here).
     */
    private static final String UNUSED_SYMBOL_TOOL_ID = "unused";

    @Override
    public boolean isSuppressedFor(@NotNull PsiElement element, @NotNull String toolId) {
        if (!UNUSED_SYMBOL_TOOL_ID.equals(toolId)) return false;

        // Only suppress for KtProperty; KtNamedFunction is already handled via
        // ImplicitUsageProvider (K2's isEntryPoint calls isJavaEntryPoint.isEntryPoint
        // for functions, which consults ImplicitUsageProvider).
        try {
            if (!(element instanceof org.jetbrains.kotlin.psi.KtProperty)) return false;
        } catch (NoClassDefFoundError ignored) {
            // Kotlin plugin absent; should not happen here since we're in fxml2-with-kotlin.xml
            return false;
        }

        return Fxml2StandaloneImplicitUsageProvider.isReferencedInStandaloneFxml(element)
                || Fxml2EmbeddedImplicitUsageProvider.isReferencedInEmbeddedFxml(element);
    }

    @Override
    public SuppressQuickFix @NotNull [] getSuppressActions(@Nullable PsiElement element, @NotNull String toolId) {
        // We do not offer a "Suppress" quick-fix: the property IS used (in FXML).
        return SuppressQuickFix.EMPTY_ARRAY;
    }
}
