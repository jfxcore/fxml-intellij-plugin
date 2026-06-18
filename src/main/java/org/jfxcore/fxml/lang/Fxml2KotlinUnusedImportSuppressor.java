package org.jfxcore.fxml.lang;

import com.intellij.codeInspection.InspectionSuppressor;
import com.intellij.codeInspection.SuppressQuickFix;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.asJava.LightClassUtilsKt;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtImportDirective;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link InspectionSuppressor} for the Kotlin {@code UnusedImport} inspection
 * ({@code org.jetbrains.kotlin.idea.codeInsight.inspections.shared.KotlinUnusedImportInspection}).
 *
 * <h2>Problem</h2>
 * Kotlin's built-in unused-import analysis examines Kotlin source code to find which
 * imports are needed.  It does <em>not</em> understand that class names referenced inside a
 * string literal that is the value of a {@code @ComponentView} annotation are also "in use", so it
 * marks the corresponding Kotlin import statements as "unused" and greys them out.
 *
 * <h2>Solution</h2>
 * This suppressor intercepts the {@code UnusedImport} inspection for every
 * {@link KtImportDirective} found in a Kotlin file that contains a {@code @ComponentView}-annotated
 * class.  It obtains the injected XML file (if available) and checks whether the import
 * covers any class name referenced in the embedded FXML markup.  If so it returns
 * {@code true} and the grey highlight is suppressed.
 *
 * <p>Registered via {@code lang.inspectionSuppressor language="kotlin"} in
 * {@code fxml2-with-kotlin.xml} so it is loaded only when the Kotlin plugin is present.
 */
public final class Fxml2KotlinUnusedImportSuppressor implements InspectionSuppressor {

    /**
     * The {@code suppressId} of
     * {@code org.jetbrains.kotlin.idea.codeInsight.inspections.shared.KotlinUnusedImportInspection}
     * as declared in its {@code localInspection} registration.
     */
    private static final String UNUSED_IMPORT_TOOL_ID = "UnusedImport";

    // -----------------------------------------------------------------------
    // InspectionSuppressor
    // -----------------------------------------------------------------------

    @Override
    public boolean isSuppressedFor(@NotNull PsiElement element, @NotNull String toolId) {
        if (!UNUSED_IMPORT_TOOL_ID.equals(toolId)) return false;
        if (!(element instanceof KtImportDirective importDirective)) return false;

        PsiFile containingFile = element.getContainingFile();
        if (!(containingFile instanceof KtFile ktFile)) return false;

        List<KtClassOrObject> markupClasses = findMarkupAnnotatedClasses(ktFile);
        if (markupClasses.isEmpty()) return false;

        return isImportNeededByEmbeddedMarkup(importDirective, markupClasses);
    }

    @Override
    public SuppressQuickFix @NotNull [] getSuppressActions(@Nullable PsiElement element, @NotNull String toolId) {
        // We do not offer any quick-fix here; the import is intentionally kept.
        return SuppressQuickFix.EMPTY_ARRAY;
    }

    // -----------------------------------------------------------------------
    // Core logic
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when {@code importDirective} resolves to a class (or package)
     * that is referenced by at least one embedded FXML markup in the file.
     */
    private static boolean isImportNeededByEmbeddedMarkup(
            @NotNull KtImportDirective importDirective,
            @NotNull List<KtClassOrObject> markupClasses) {

        // Aliased imports (`import foo.Bar as Baz`) are invisible to FXML: the compiler
        // ignores them (cannot forward an alias to the AP), so never suppress them here.
        if (importDirective.getAliasName() != null) return false;

        var fqName = importDirective.getImportedFqName();
        if (fqName == null) return false;
        // Strip backtick-escaping for keyword-named identifiers (e.g. `sealed`).
        String fqStr = fqName.asString().replace("`", "");

        for (KtClassOrObject ktClass : markupClasses) {
            var lightClass = LightClassUtilsKt.toLightClass(ktClass);
            if (lightClass == null) continue;

            XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(lightClass);
            if (xmlFile == null) continue;      // injection not yet computed, skip

            if (Fxml2ImportUtil.isImportNeededByXmlFile(
                    fqStr, importDirective.isAllUnder(), xmlFile)) {
                return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------


    /**
     * Collects all {@link KtClassOrObject} declarations in {@code ktFile} that carry
     * an {@code @ComponentView} annotation entry (identified by short name only: cheap check).
     */
    private static @NotNull List<KtClassOrObject> findMarkupAnnotatedClasses(@NotNull KtFile ktFile) {
        List<KtClassOrObject> result = new ArrayList<>();
        for (KtClassOrObject ktClass :
                PsiTreeUtil.findChildrenOfType(ktFile, KtClassOrObject.class)) {
            for (KtAnnotationEntry annotEntry : ktClass.getAnnotationEntries()) {
                var shortName = annotEntry.getShortName();
                if (shortName != null && "ComponentView".equals(shortName.getIdentifier())) {
                    result.add(ktClass);
                    break;
                }
            }
        }
        return result;
    }

}
