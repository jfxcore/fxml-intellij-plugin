// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.codeinsight;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiImportStaticStatement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.xml.XmlFile;
import com.intellij.refactoring.RefactoringHelper;
import com.intellij.usageView.UsageInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.annotator.Fxml2ImportPlacementInspectionHelper;
import org.jfxcore.fxml.annotator.Fxml2UnusedImportsInspection;
import org.jfxcore.fxml.lang.Fxml2EmbedMarkupUtil;
import org.jfxcore.fxml.lang.Fxml2ImportUtil;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link RefactoringHelper} that prevents IntelliJ's
 * {@code OptimizeImportsRefactoringHelper} from removing Java imports that are needed
 * by embedded FXML2 markup in {@code @ComponentView}-annotated classes.
 *
 * <h2>Problem</h2>
 * After every refactoring (rename, move, extract, etc.) IntelliJ runs
 * {@code OptimizeImportsRefactoringHelper}, which calls
 * {@code JavaCodeStyleManager.findRedundantImports()} on every Java file that was
 * touched.  That method only walks the Java PSI tree and cannot see class names
 * referenced inside the {@code @ComponentView} string-literal annotation value, so it
 * classifies those imports as redundant and deletes them.
 *
 * <h2>Solution</h2>
 * This helper is registered with {@code order="last"} so that it runs <em>after</em>
 * {@code OptimizeImportsRefactoringHelper}.
 *
 * <ul>
 *   <li>In the <b>read phase</b> ({@link #prepareOperation}): for every Java file
 *       involved in the refactoring that contains a {@code @ComponentView}-annotated
 *       class, we capture the list of import targets (e.g.
 *       {@code "javafx.scene.layout.*"}) that are referenced by the embedded FXML2
 *       markup.</li>
 *   <li>In the <b>write phase</b> ({@link #performOperation}): we add back any of
 *       those import targets that are no longer present in the file.</li>
 * </ul>
 *
 * <p>The same fallback strategies used by {@link Fxml2EmbeddedJavaImportOptimizer} are
 * applied here: if the injected XML file is not yet available we fall back to the
 * short-names cache (when the injection was computed but the file has no imports) or
 * to a regex scan of the raw annotation text (when the injection has never been
 * computed).
 */
public final class Fxml2EmbeddedJavaRefactoringHelper
        implements RefactoringHelper<Map<SmartPsiElementPointer<PsiJavaFile>, List<String>>> {

    // -----------------------------------------------------------------------
    // RefactoringHelper
    // -----------------------------------------------------------------------

    /**
     * Read phase: collect the FXML-needed imports for every affected Java file that
     * contains a {@code @ComponentView}-annotated class.
     *
     * <p>Called <em>before</em> {@code performRefactoring} (so the imports are still
     * present) and inside a {@code ReadAction}.
     */
    @Override
    public @NotNull Map<SmartPsiElementPointer<PsiJavaFile>, List<String>> prepareOperation(
            @NotNull UsageInfo @NotNull [] usages,
            @NotNull List<? extends @NotNull PsiElement> elements) {

        Map<SmartPsiElementPointer<PsiJavaFile>, List<String>> result = new LinkedHashMap<>();

        // Process the elements being refactored (e.g. the renamed class itself).
        for (PsiElement element : elements) {
            if (!element.isValid()) continue;
            PsiFile file = element.getContainingFile();
            if (file instanceof PsiJavaFile javaFile) {
                addIfNeeded(javaFile, result);
            }
        }

        // Process the files that contain usages of the refactored element.
        for (UsageInfo usage : usages) {
            if (usage.isNonCodeUsage) continue;
            PsiFile file = usage.getFile();
            if (file instanceof PsiJavaFile javaFile) {
                addIfNeeded(javaFile, result);
            }
        }

        return result;
    }

    /**
     * Write phase: add back any FXML-needed imports that were deleted by a previous
     * {@code RefactoringHelper} (typically {@code OptimizeImportsRefactoringHelper}).
     *
     * <p>Called <em>after</em> {@code performRefactoring} and after all other helpers
     * with lower priority have run.
     *
     * <p>Two passes are performed:
     * <ol>
     *   <li>Restore imports from the list captured in the read phase (handles the common
     *       case of imports that still refer to existing classes).</li>
     *   <li>Re-scan the markup and add any imports that are now needed but not present
     *       (handles the rename case, where a class was renamed and the old import FQN
     *       no longer exists).</li>
     * </ol>
     */
    @Override
    public void performOperation(
            @NotNull Project project,
            @NotNull Map<SmartPsiElementPointer<PsiJavaFile>, List<String>> operationData) {

        if (operationData.isEmpty()) return;

        boolean preferMarkup = Fxml2ImportPlacementInspectionHelper.isPreferMarkupImportEnabled(project);
        boolean preferCode = Fxml2ImportPlacementInspectionHelper.isPreferCodeImportEnabled(project);

        ApplicationManager.getApplication().runWriteAction(() -> {
            PsiDocumentManager.getInstance(project).commitAllDocuments();
            for (Map.Entry<SmartPsiElementPointer<PsiJavaFile>, List<String>> entry
                    : operationData.entrySet()) {
                PsiJavaFile javaFile = entry.getKey().getElement();
                if (javaFile == null || !javaFile.isValid()) continue;
                // Pass 1: restore imports captured before the refactoring.
                restoreMissingImports(javaFile, entry.getValue());
                // Pass 2: add any imports that are now needed but still absent
                // (e.g. a renamed class whose new FQN was not in the original list).
                List<String> freshlyNeeded = collectNeededFromXmlContent(
                        getMarkupClassesInFile(javaFile), javaFile);
                restoreMissingImports(javaFile, freshlyNeeded);

                // Pass 3: apply the active import-placement preference (if any).
                List<PsiClass> markupClasses = getMarkupClassesInFile(javaFile);
                if (preferMarkup) {
                    applyPreferMarkupToJavaFile(javaFile, markupClasses, project);
                } else if (preferCode) {
                    Fxml2ImportPlacementInspectionHelper.moveAllMarkupImportsToJavaCode(
                            javaFile, markupClasses, project);
                }
            }
        });
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Adds the given {@code javaFile} to {@code result} with its FXML-needed imports,
     * unless it is already present or has no {@code @ComponentView}-annotated class.
     */
    private static void addIfNeeded(
            @NotNull PsiJavaFile javaFile,
            @NotNull Map<SmartPsiElementPointer<PsiJavaFile>, List<String>> result) {

        if (!hasMarkupAnnotation(javaFile)) return;

        // Skip if already processed (pointer equality check).
        for (SmartPsiElementPointer<PsiJavaFile> ptr : result.keySet()) {
            PsiJavaFile existing = ptr.getElement();
            if (existing != null && existing.equals(javaFile)) return;
        }

        List<String> needed = collectNeededImports(javaFile);
        if (!needed.isEmpty()) {
            SmartPsiElementPointer<PsiJavaFile> ptr =
                    SmartPointerManager.getInstance(javaFile.getProject())
                            .createSmartPsiElementPointer(javaFile);
            result.put(ptr, needed);
        }
    }

    /**
     * Collects the import targets (FQNs or on-demand patterns) that are needed by the
     * embedded FXML2 markup in {@code javaFile}.
     *
     * <p>Uses a three-level strategy:
     * <ol>
     *   <li>Primary: read from the existing import list, keeping those that the
     *       injected XML file confirms are used in the markup.</li>
     *   <li>Fallback A: if the XML injection is unavailable, derive FQNs from
     *       {@link Fxml2UnusedImportsInspection#collectUsedSimpleNames} and resolve
     *       them via {@link PsiShortNamesCache}.</li>
     *   <li>Fallback B: if the injection has never been computed, parse the raw
     *       annotation string with a regex to extract candidate class names, then
     *       resolve them via {@link PsiShortNamesCache}.</li>
     * </ol>
     */
    private static @NotNull List<String> collectNeededImports(@NotNull PsiJavaFile javaFile) {
        List<PsiClass> markupClasses = getMarkupClassesInFile(javaFile);
        if (markupClasses.isEmpty()) return Collections.emptyList();

        PsiImportList importList = javaFile.getImportList();
        if (importList == null) return Collections.emptyList();

        // Primary: use the existing import list combined with the injected XML.
        List<String> needed = collectNeededFromImportList(markupClasses, importList);

        // Fallbacks: used when the import list has been stripped before this runs,
        // or when the injection is unavailable.
        if (needed.isEmpty()) {
            needed = collectNeededFromXmlContent(markupClasses, javaFile);
        }

        return needed;
    }

    /**
     * Primary strategy: collect import targets from the file's existing import list,
     * keeping only those that the injected XML confirms are needed by the markup.
     */
    private static @NotNull List<String> collectNeededFromImportList(
            @NotNull List<PsiClass> markupClasses,
            @NotNull PsiImportList importList) {

        List<String> needed = new ArrayList<>();

        for (PsiClass markupClass : markupClasses) {
            XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(markupClass);
            if (xmlFile == null) continue;

            for (PsiImportStatement imp : importList.getImportStatements()) {
                if (imp instanceof PsiImportStaticStatement) continue;
                String qn = imp.getQualifiedName();
                if (qn == null) continue;
                if (!Fxml2ImportUtil.isImportNeededByXmlFile(
                        qn, imp.isOnDemand(), xmlFile)) {
                    continue;
                }
                String target = imp.isOnDemand() ? qn + ".*" : qn;
                if (!needed.contains(target)) {
                    needed.add(target);
                }
            }
        }
        return needed;
    }

    /**
     * Fallback strategy: derive needed import FQNs directly from the FXML2 markup
     * content, using {@link PsiShortNamesCache} for class resolution.
     *
     * <p>Works even when the import list has already been stripped, or when the
     * injected {@link XmlFile} has not been computed yet.
     */
    private static @NotNull List<String> collectNeededFromXmlContent(
            @NotNull List<PsiClass> markupClasses,
            @NotNull PsiJavaFile javaFile) {

        List<String> needed = new ArrayList<>();
        PsiShortNamesCache cache = PsiShortNamesCache.getInstance(javaFile.getProject());
        GlobalSearchScope scope = javaFile.getResolveScope();

        for (PsiClass markupClass : markupClasses) {
            XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(markupClass);
            Set<String> usedNames;
            if (xmlFile != null) {
                usedNames = Fxml2UnusedImportsInspection.collectUsedSimpleNames(xmlFile);
            } else {
                usedNames = extractClassNamesFromAnnotation(markupClass);
            }

            for (String simpleName : usedNames) {
                PsiClass resolved = (xmlFile != null)
                        ? Fxml2ImportResolver.resolve(simpleName, xmlFile)
                        : null;
                if (resolved == null) {
                    resolved = resolveByShortName(simpleName, cache, scope);
                }
                if (resolved == null) continue;
                String fqn = resolved.getQualifiedName();
                if (fqn == null) continue;
                // Skip java.lang.*: those never need an explicit import.
                if (fqn.startsWith("java.lang.")
                        && !fqn.substring("java.lang.".length()).contains(".")) {
                    continue;
                }
                // Skip the @ComponentView annotation itself.
                if (Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN.equals(fqn)) continue;
                if (!needed.contains(fqn)) {
                    needed.add(fqn);
                }
            }
        }
        return needed;
    }

    /** Pattern matching XML element names starting with an uppercase letter. */
    private static final Pattern TAG_NAME_PATTERN =
            Pattern.compile("</?([A-Z][A-Za-z0-9_$]*)");

    /** Pattern matching static-property attribute prefixes like {@code GridPane.rowIndex=}. */
    private static final Pattern STATIC_ATTR_PATTERN =
            Pattern.compile("\\b([A-Z][A-Za-z0-9_$]*)\\.[a-z][A-Za-z0-9_$]*\\s*=");

    /**
     * Last-resort fallback: extract simple class names from the raw
     * {@code @ComponentView} annotation text using regex.
     */
    private static @NotNull Set<String> extractClassNamesFromAnnotation(
            @NotNull PsiClass psiClass) {
        var annotation = psiClass.getAnnotation(Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN);
        if (annotation == null) return Collections.emptySet();
        String markup = Fxml2EmbedMarkupUtil.extractMarkupFromJavaAnnotation(annotation);
        if (markup == null) return Collections.emptySet();
        return extractClassNamesFromMarkup(markup);
    }

    private static @NotNull Set<String> extractClassNamesFromMarkup(@NotNull String markup) {
        Set<String> names = new HashSet<>();
        Matcher m = TAG_NAME_PATTERN.matcher(markup);
        while (m.find()) names.add(m.group(1));
        m = STATIC_ATTR_PATTERN.matcher(markup);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    /**
     * Resolves {@code simpleName} using {@link PsiShortNamesCache}, preferring
     * {@code javafx.*} then {@code org.jfxcore.*}.
     */
    private static @Nullable PsiClass resolveByShortName(
            @NotNull String simpleName,
            @NotNull PsiShortNamesCache cache,
            @NotNull GlobalSearchScope scope) {
        PsiClass[] classes = cache.getClassesByName(simpleName, scope);
        if (classes.length == 0) return null;
        if (classes.length == 1) return classes[0];
        for (PsiClass cls : classes) {
            String fqn = cls.getQualifiedName();
            if (fqn != null && fqn.startsWith("javafx.")) return cls;
        }
        for (PsiClass cls : classes) {
            String fqn = cls.getQualifiedName();
            if (fqn != null && fqn.startsWith("org.jfxcore.")) return cls;
        }
        return null; // ambiguous, skip
    }

    /**
     * Adds back any import target from {@code neededImports} that is currently absent
     * from {@code javaFile}'s import list.
     */
    private static void restoreMissingImports(
            @NotNull PsiJavaFile javaFile,
            @NotNull List<String> neededImports) {

        PsiImportList list = javaFile.getImportList();
        if (list == null) return;

        JavaPsiFacade facade = JavaPsiFacade.getInstance(javaFile.getProject());
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(javaFile.getProject());
        GlobalSearchScope scope = GlobalSearchScope.allScope(javaFile.getProject());

        for (String importTarget : neededImports) {
            if (importTarget.endsWith(".*")) {
                String pkg = importTarget.substring(0, importTarget.length() - 2);
                if (list.findOnDemandImportStatement(pkg) != null) continue;
                list.add(factory.createImportStatementOnDemand(pkg));
            } else {
                if (list.findSingleClassImportStatement(importTarget) != null) continue;
                int lastDot = importTarget.lastIndexOf('.');
                if (lastDot > 0) {
                    String pkg = importTarget.substring(0, lastDot);
                    if (list.findOnDemandImportStatement(pkg) != null) continue;
                }
                PsiClass cls = facade.findClass(importTarget, scope);
                if (cls == null) continue;
                list.add(factory.createImportStatement(cls));
            }
        }
    }

    /**
     * If "prefer markup imports" is active, moves any code imports that are exclusively
     * used inside embedded FXML2 markup to {@code <?import?>} PIs inside those blocks,
     * and removes the corresponding Java import statements.
     */
    private static void applyPreferMarkupToJavaFile(
            @NotNull PsiJavaFile javaFile,
            @NotNull List<PsiClass> markupClasses,
            @NotNull Project project) {

        PsiImportList importList = javaFile.getImportList();
        if (importList == null) return;

        List<PsiImportStatement> toConvert = new ArrayList<>();
        for (PsiImportStatement stmt : importList.getImportStatements()) {
            if (stmt instanceof PsiImportStaticStatement || stmt.isOnDemand()) continue;
            if (Fxml2ImportPlacementInspectionHelper.isJavaImportOnlyUsedInMarkup(stmt, javaFile)) {
                toConvert.add(stmt);
            }
        }

        for (PsiImportStatement stmt : toConvert) {
            if (!stmt.isValid()) continue;
            String fqn = stmt.getQualifiedName();
            if (fqn == null) continue;
            Fxml2ImportPlacementInspectionHelper.insertFqnIntoAllMarkupBlocks(
                    markupClasses, fqn, project);
            stmt.delete();
        }
    }

    private static boolean hasMarkupAnnotation(@NotNull PsiJavaFile javaFile) {
        for (PsiClass cls : javaFile.getClasses()) {
            if (cls.hasAnnotation(Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN)) return true;
        }
        return false;
    }

    private static @NotNull List<PsiClass> getMarkupClassesInFile(
            @NotNull PsiJavaFile javaFile) {
        List<PsiClass> result = new ArrayList<>();
        for (PsiClass cls : javaFile.getClasses()) {
            if (cls.hasAnnotation(Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN)) result.add(cls);
        }
        return result;
    }
}
