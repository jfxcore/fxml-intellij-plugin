// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiImportStaticStatement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.annotator.Fxml2UnusedImportsInspection;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;

import java.util.Set;

/**
 * Shared utility for import-related checks used across standalone and embedded FXML2.
 *
 * <p>This logic is shared between:
 * <ul>
 *   <li>{@link Fxml2JavaUnusedImportHighlightFilter}: suppresses Java "Unused import"
 *       highlights for imports that are referenced inside FXML2 embedded markup.</li>
 *   <li>{@link Fxml2KotlinUnusedImportSuppressor}: suppresses Kotlin "UnusedImport"
 *       inspection results for the same reason.</li>
 *   <li>{@link org.jfxcore.fxml.codeinsight.Fxml2EmbeddedJavaImportOptimizer} and
 *       {@link org.jfxcore.fxml.codeinsight.Fxml2EmbeddedKotlinImportOptimizer}:
 *       prevent the built-in optimizers from removing such imports.</li>
 *   <li>{@link org.jfxcore.fxml.codeinsight.Fxml2ImportOptimizer}: removes unused
 *       {@code <?import?>} processing instructions from standalone FXML2 files.</li>
 * </ul>
 */
public final class Fxml2ImportUtil {

    private Fxml2ImportUtil() {} // static utility only

    /**
     * Returns {@code true} if the given Java import statement is referenced by the FXML2
     * markup of any {@code @ComponentView}-annotated class in {@code javaFile}.
     *
     * <p>Skips static imports. Returns {@code false} if the import has no qualified name.
     */
    public static boolean isImportNeededInJavaFile(
            @NotNull PsiImportStatement importStmt, @NotNull PsiJavaFile javaFile) {
        if (importStmt instanceof PsiImportStaticStatement) return false;
        String qn = importStmt.getQualifiedName();
        if (qn == null) return false;
        boolean isWildcard = importStmt.isOnDemand();
        for (var cls : javaFile.getClasses()) {
            if (!cls.hasAnnotation(Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN)) continue;
            XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(cls);
            if (xmlFile == null) continue;
            if (isImportNeededByXmlFile(qn, isWildcard, xmlFile)) return true;
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Core check
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if the import described by {@code fqStr} and {@code isAllUnder}
     * covers at least one class name that is referenced inside the given embedded FXML2 markup
     * XML file.
     *
     * <p>Specifically:
     * <ul>
     *   <li>For wildcard imports ({@code isAllUnder == true}): returns {@code true} when
     *       at least one simple name used in the markup resolves to a class whose package
     *       equals {@code fqStr}.</li>
     *   <li>For specific imports ({@code isAllUnder == false}): returns {@code true} when
     *       the simple name of {@code fqStr} is used in the markup <em>and</em> resolves to
     *       exactly the class identified by {@code fqStr}.</li>
     * </ul>
     *
     * <p>The {@code @ComponentView} annotation import itself is never considered "needed by
     * markup" here: the annotation is used by the code-behind class itself.
     *
     * @param fqStr      the fully-qualified class name (specific import) or package name
     *                   (wildcard import)
     * @param isAllUnder {@code true} for wildcard imports ({@code pkg.*}),
     *                   {@code false} for specific imports ({@code pkg.ClassName})
     * @param xmlFile    the injected FXML2 XML file obtained from a {@code @ComponentView}
     *                   annotation value
     * @return {@code true} when the import is needed to resolve a name used in the markup
     */
    public static boolean isImportNeededByXmlFile(
            @NotNull String fqStr, boolean isAllUnder, @NotNull XmlFile xmlFile) {

        // The @ComponentView annotation import itself is used by the code-behind, skip.
        if (Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN.equals(fqStr)) return false;

        Set<String> usedNames = Fxml2UnusedImportsInspection.collectUsedSimpleNames(xmlFile);
        if (usedNames.isEmpty()) return false;

        if (isAllUnder) {
            // Wildcard: suppress if the package covers any used simple name.
            return usedNames.stream()
                    .anyMatch(name -> isNameCoveredByWildcard(name, fqStr, xmlFile));
        } else {
            // Specific: suppress if the simple name is used AND this import resolves it.
            String simpleName = simpleNameOf(fqStr);
            if (!usedNames.contains(simpleName)) return false;
            var resolved = Fxml2ImportResolver.resolve(simpleName, xmlFile);
            return resolved != null && fqStr.equals(resolved.getQualifiedName());
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when the class for simple name {@code name} lives in package
     * {@code pkg} (i.e. a wildcard import {@code pkg.*} covers it).
     */
    public static boolean isNameCoveredByWildcard(
            @NotNull String name, @NotNull String pkg, @NotNull XmlFile xmlFile) {
        var cls = Fxml2ImportResolver.resolve(name, xmlFile);
        if (cls == null) return false;
        String fqn = cls.getQualifiedName();
        return fqn != null
                && fqn.startsWith(pkg + ".")
                && !fqn.substring(pkg.length() + 1).contains(".");
    }

    /**
     * Returns the simple (unqualified) name: the last dot-separated segment of {@code fqn}.
     */
    public static @NotNull String simpleNameOf(@NotNull String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }
}
