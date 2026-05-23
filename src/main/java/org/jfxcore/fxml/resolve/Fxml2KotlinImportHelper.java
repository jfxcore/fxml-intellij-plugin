package org.jfxcore.fxml.resolve;

import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtImportDirective;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads import directives from a Kotlin source file on behalf of
 * {@link Fxml2ImportResolver}.
 *
 * <p>This class directly references the Kotlin PSI API ({@link KtFile}).  It is kept in a
 * separate class so that {@link Fxml2ImportResolver} (which is always loaded) can invoke
 * it inside a {@code try/catch(NoClassDefFoundError)} block.  If the Kotlin plugin is not
 * installed the JVM will throw {@code NoClassDefFoundError} when this class is loaded, and
 * the caller silently falls back to an empty import list.
 */
final class Fxml2KotlinImportHelper {

    private Fxml2KotlinImportHelper() {}

    /**
     * Returns the import targets declared in {@code file} if it is a {@link KtFile},
     * or an empty list if {@code file} is not a Kotlin file.
     *
     * @param file the PSI file that is the host of an embedded FXML2 injection
     * @return non-static import targets, in declaration order; wildcard imports are
     *         returned with the {@code .*} suffix restored
     */
    static @NotNull List<String> parseImports(@NotNull PsiFile file) {
        if (!(file instanceof KtFile ktFile)) return List.of();
        List<String> result = new ArrayList<>();
        for (KtImportDirective imp : ktFile.getImportDirectives()) {
            if (!imp.isValidImport()) continue;
            var fqName = imp.getImportedFqName();
            if (fqName == null) continue;
            String fqStr = fqName.asString();
            if (Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN.equals(fqStr)) continue;
            // Restore the ".*" suffix that FqName strips for wildcard imports.
            result.add(imp.isAllUnder() ? fqStr + ".*" : fqStr);
        }
        return result;
    }
}
