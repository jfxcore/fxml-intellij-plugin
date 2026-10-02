package org.jfxcore.fxml.lang;

import com.intellij.lang.injection.MultiHostInjector;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtImportDirective;
import org.jetbrains.kotlin.psi.KtStringTemplateExpression;

import java.util.List;

/**
 * Injects {@link Fxml2EmbeddedXmlLanguage} into the value of a
 * {@code @org.jfxcore.markup.ComponentView} annotation on a Kotlin type declaration.
 *
 * <p>This is the Kotlin counterpart of {@link Fxml2MarkupAnnotationInjector} (which handles
 * Java {@code PsiLiteralExpression} text-block hosts). Here the injection host is a
 * {@link KtStringTemplateExpression}: the Kotlin string literal that forms the annotation
 * value.
 *
 * <h2>Injected document structure</h2>
 * Identical to the Java injector: the XML declaration followed by the wrapper root element
 * with namespace declarations and {@code fx:subclass}.  Import resolution is handled lazily
 * by {@link org.jfxcore.fxml.resolve.Fxml2ImportResolver} reading the host Kotlin file's
 * import list directly, so no {@code &lt;?import?&gt;} PIs are included in the prefix.
 *
 * <p>This class is registered only in {@code fxml2-with-kotlin.xml} so it is loaded only
 * when the Kotlin plugin ({@code org.jetbrains.kotlin}) is present.
 */
@SuppressWarnings("DuplicatedCode")
public final class Fxml2KotlinMarkupAnnotationInjector implements MultiHostInjector {

    @Override
    public void getLanguagesToInject(
            @NotNull MultiHostRegistrar registrar, @NotNull PsiElement context) {

        if (!(context instanceof KtStringTemplateExpression stringExpr)) return;
        if (!stringExpr.isValidHost()) return;

        // Walk up: KtStringTemplateExpression -> KtValueArgument -> KtValueArgumentList -> KtAnnotationEntry
        KtAnnotationEntry annotationEntry = PsiTreeUtil.getParentOfType(
                stringExpr, KtAnnotationEntry.class);
        if (annotationEntry == null) return;

        // Quick check by short name before any import scanning
        var shortName = annotationEntry.getShortName();
        if (shortName == null || !"ComponentView".equals(shortName.getIdentifier())) return;

        // Confirm it is org.jfxcore.markup.ComponentView by checking the file's imports
        PsiFile containingFile = stringExpr.getContainingFile();
        if (!(containingFile instanceof KtFile ktFile)) return;
        if (!isMarkupAnnotationImported(ktFile)) return;

        // The annotation must be on a type declaration (class, object, interface)
        KtClassOrObject ktClass = PsiTreeUtil.getParentOfType(
                annotationEntry, KtClassOrObject.class);
        if (ktClass == null) return;

        // Resolve to a Java PsiClass to obtain the fully-qualified name
        var lightClass = Fxml2KotlinPsiClassUtil.toPsiClass(ktClass);
        if (lightClass == null) return;
        Fxml2EmbeddedMarkupInjection.inject(registrar, stringExpr, lightClass);
    }

    @Override
    public @NotNull @Unmodifiable List<? extends Class<? extends PsiElement>> elementsToInjectIn() {
        return List.of(KtStringTemplateExpression.class);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when the Kotlin file explicitly imports
     * {@code org.jfxcore.markup.ComponentView} (either directly or via a wildcard).
     */
    private static boolean isMarkupAnnotationImported(@NotNull KtFile ktFile) {
        for (KtImportDirective imp : ktFile.getImportDirectives()) {
            var fqName = imp.getImportedFqName();
            if (fqName == null) continue;
            String fqStr = fqName.asString();
            // Direct import: org.jfxcore.markup.ComponentView
            if (Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN.equals(fqStr)) return true;
            // Wildcard import: org.jfxcore.markup.*
            if (imp.isAllUnder() && "org.jfxcore.markup".equals(fqStr)) return true;
        }
        return false;
    }

}
