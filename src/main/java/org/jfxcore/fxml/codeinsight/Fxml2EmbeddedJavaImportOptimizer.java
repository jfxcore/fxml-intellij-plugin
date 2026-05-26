package org.jfxcore.fxml.codeinsight;

import com.intellij.lang.ImportOptimizer;
import com.intellij.lang.LanguageImportStatements;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.EmptyRunnable;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiImportStaticStatement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.xml.XmlFile;
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
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@link ImportOptimizer} for Java files that contain classes annotated with
 * {@code @org.jfxcore.markup.ComponentView}.
 *
 * <h2>Problem</h2>
 * IntelliJ's built-in Java import optimizer analyzes Java source code to find which
 * classes are referenced.  It does <em>not</em> understand that class names appearing
 * inside a string literal that is the value of a {@code @ComponentView} annotation are also
 * "in use", so it removes the corresponding Java import statements.
 *
 * <h2>Solution: normal "Optimize Imports" flow</h2>
 * This optimizer is registered with {@code order="first"} so that
 * {@link LanguageImportStatements#forFile} picks it (rather than the built-in Java
 * optimizer) for files that have {@code @ComponentView}.  Because {@code forFile} only
 * picks <em>one</em> optimizer per file (it breaks after the first match), our optimizer
 * must itself invoke the built-in Java import optimizer: see
 * {@link #getBuiltInOptimizerRunnable}.
 *
 * <p>The read phase captures FXML-needed imports before the built-in optimizer strips them.
 * The write phase first runs the built-in optimizer, then restores the FXML-needed imports.
 *
 * <h2>Solution: copy (F5) flow</h2>
 * {@code CopyClassesHandler} calls {@code JavaCodeStyleManager.removeRedundantImports()}
 * <em>before</em> {@code OptimizeImportsProcessor} runs.  By the time our read phase
 * executes, the FXML-needed imports are already gone and the primary strategy finds nothing
 * to restore.  The fallback strategy derives needed imports either from the injected XML
 * tree (when available) or by parsing the raw annotation string with a regex; both paths
 * use {@link PsiShortNamesCache} for class resolution.
 */
public final class Fxml2EmbeddedJavaImportOptimizer implements ImportOptimizer {

    // -----------------------------------------------------------------------
    // ImportOptimizer
    // -----------------------------------------------------------------------

    @Override
    public boolean supports(@NotNull PsiFile file) {
        if (!(file instanceof PsiJavaFile javaFile)) return false;
        return hasMarkupAnnotation(javaFile);
    }

    @Override
    public @NotNull Runnable processFile(@NotNull PsiFile file) {
        if (!(file instanceof PsiJavaFile javaFile)) return EmptyRunnable.INSTANCE;

        // --- Read phase ---
        // Collect the Java imports that are needed by the embedded FXML markup(s).
        // We capture them now (before any optimizer modifies the file) so that the write
        // phase can restore any that get removed.

        List<PsiClass> markupClasses = getMarkupClassesInFile(javaFile);
        if (markupClasses.isEmpty()) return EmptyRunnable.INSTANCE;

        PsiImportList importList = javaFile.getImportList();
        if (importList == null) return EmptyRunnable.INSTANCE;

        // Primary strategy: import targets (FQN for specific, "pkg.*" for wildcard) that
        // are referenced by at least one embedded FXML markup in this file.
        // Works when imports are still present (normal "Optimize Imports" flow).
        List<String> neededImports = collectNeededFromImportList(markupClasses, importList);

        // Determine which needed imports are markup-only (for "prefer markup" mode).
        // Must be done before the built-in optimizer removes them.
        boolean preferMarkup =
                Fxml2ImportPlacementInspectionHelper.isPreferMarkupImportEnabled(javaFile.getProject());
        Set<String> markupOnlyFqns = Collections.emptySet();
        if (preferMarkup && !neededImports.isEmpty()) {
            markupOnlyFqns = new HashSet<>();
            for (PsiImportStatement stmt : importList.getImportStatements()) {
                if (stmt instanceof PsiImportStaticStatement || stmt.isOnDemand()) continue;
                String qn = stmt.getQualifiedName();
                if (qn != null && neededImports.contains(qn)
                        && Fxml2ImportPlacementInspectionHelper.isJavaImportOnlyUsedInMarkup(
                                stmt, javaFile)) {
                    markupOnlyFqns.add(qn);
                }
            }
        }
        boolean preferCode =
                Fxml2ImportPlacementInspectionHelper.isPreferCodeImportEnabled(javaFile.getProject());

        // Fallback strategy: derive needed imports directly from the XML markup content.
        // This is required when the file's imports were already stripped before this
        // optimizer ran, e.g. CopyClassesHandler calls removeRedundantImports() prior to
        // OptimizeImportsProcessor, so in that case the primary strategy finds nothing.
        if (neededImports.isEmpty()) {
            neededImports = collectNeededFromXmlContent(markupClasses, javaFile);
        }

        // Get the built-in Java import optimizer runnable.
        // Because we are registered with order="first" and LanguageImportStatements.forFile()
        // breaks after the first supporting optimizer, the built-in optimizer is NOT
        // added to the set by forFile(). We must call it ourselves so that genuinely
        // unused imports are still removed.
        Runnable builtInRunnable = getBuiltInOptimizerRunnable(javaFile);

        if (neededImports.isEmpty() && !preferCode) {
            // Still need to run the built-in optimizer even if we have nothing to add back.
            return builtInRunnable;
        }

        // --- Write phase ---
        final List<String> finalNeeded = neededImports;
        final Set<String> finalMarkupOnly = markupOnlyFqns;
        final List<PsiClass> finalMarkupClasses = markupClasses;
        return () -> {
            builtInRunnable.run();

            if (!finalMarkupOnly.isEmpty()) {
                // "Prefer markup": restore only code+markup imports to code; move
                // markup-only imports to <?import?> PIs in all markup blocks.
                List<String> codeAndMarkup = finalNeeded.stream()
                        .filter(fqn -> !finalMarkupOnly.contains(fqn))
                        .collect(Collectors.toList());
                restoreMissingImports(javaFile, codeAndMarkup);
                // builtInRunnable.run() and restoreMissingImports both do PSI operations
                // that defer whitespace reformatting and leave the host document locked.
                // Flush the deferred operations now so Document.insertString() won't fail.
                PsiDocumentManager psiDocManager = PsiDocumentManager.getInstance(javaFile.getProject());
                Document hostDoc = psiDocManager.getDocument(javaFile);
                if (hostDoc != null) {
                    psiDocManager.doPostponedOperationsAndUnblockDocument(hostDoc);
                    psiDocManager.commitDocument(hostDoc);
                }
                for (String fqn : finalMarkupOnly) {
                    Fxml2ImportPlacementInspectionHelper.insertFqnIntoAllMarkupBlocks(
                            finalMarkupClasses, fqn, javaFile.getProject());
                }
            } else {
                restoreMissingImports(javaFile, finalNeeded);
            }

            if (preferCode) {
                // "Prefer code": move <?import?> PIs from markup to code imports.
                Fxml2ImportPlacementInspectionHelper.moveAllMarkupImportsToJavaCode(
                        javaFile, finalMarkupClasses, javaFile.getProject());
            }
        };
    }

    // -----------------------------------------------------------------------
    // Built-in optimizer delegation
    // -----------------------------------------------------------------------

    /**
     * Finds and calls the built-in Java import optimizer's read phase, returning its write
     * runnable.
     *
     * <p>Because this optimizer is registered with {@code order="first"},
     * {@link LanguageImportStatements#forFile} picks it instead of the built-in one for files
     * that carry {@code @ComponentView}.  We must therefore call the built-in optimizer
     * ourselves so that genuinely unused imports are still removed.
     */
    private static @NotNull Runnable getBuiltInOptimizerRunnable(@NotNull PsiJavaFile javaFile) {
        for (ImportOptimizer opt : LanguageImportStatements.INSTANCE.allForLanguage(javaFile.getLanguage())) {
            if (opt instanceof Fxml2EmbeddedJavaImportOptimizer) continue; // skip ourselves
            if (opt.supports(javaFile)) {
                return opt.processFile(javaFile);
            }
        }
        return EmptyRunnable.INSTANCE;
    }

    // -----------------------------------------------------------------------
    // Read-phase helpers
    // -----------------------------------------------------------------------

    /**
     * Primary strategy: returns the import targets (e.g. {@code "javafx.scene.layout.*"} or
     * {@code "javafx.scene.control.Button"}) that are referenced by at least one of the
     * given {@code markupClasses}.  Only imports that are already present in the Java file's
     * import list are considered; we never invent new imports here.
     *
     * <p>Returns an empty list when the import list has already been stripped (e.g. by
     * {@code removeRedundantImports} before this optimizer ran).  In that case the caller
     * must fall back to {@link #collectNeededFromXmlContent}.
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

                if (!Fxml2ImportUtil.isImportNeededByXmlFile(qn, imp.isOnDemand(), xmlFile)) continue;

                String importTarget = imp.isOnDemand() ? qn + ".*" : qn;
                if (!needed.contains(importTarget)) {
                    needed.add(importTarget);
                }
            }
        }
        return needed;
    }

    /**
     * Fallback strategy: derives needed import FQNs by walking the injected FXML markup
     * content and resolving class names via the project's {@link PsiShortNamesCache}.
     *
     * <p>This is used when the host file's import list has already been stripped (e.g. by
     * {@code JavaCodeStyleManager.removeRedundantImports()} which {@code CopyClassesHandler}
     * calls before {@code OptimizeImportsProcessor}).  In that case the injected XML has no
     * synthesised {@code <?import?>} PIs and {@link Fxml2ImportResolver#resolve} fails;
     * the short-names cache provides the class resolution fallback.
     *
     * <p>If the injected {@link XmlFile} has not been computed yet (e.g. in the copy-class
     * flow where the destination file has never been opened), the annotation string value is
     * parsed directly with a regex to extract candidate class names, avoiding the need for
     * the injection to be available at all.
     *
     * <p>When multiple classes share a simple name, JavaFX classes ({@code javafx.*}) are
     * preferred, then JFXcore classes ({@code org.jfxcore.*}).  If the ambiguity cannot be
     * resolved the name is skipped; the user can add the import manually.
     *
     * <p>Returns fully-qualified class names (specific imports).  The write phase uses
     * {@link #restoreMissingImports} to add them as single-class import statements (unless
     * already covered by an existing on-demand import).
     */
    private static @NotNull List<String> collectNeededFromXmlContent(
            @NotNull List<PsiClass> markupClasses,
            @NotNull PsiJavaFile javaFile) {

        List<String> needed = new ArrayList<>();
        PsiShortNamesCache shortNamesCache = PsiShortNamesCache.getInstance(javaFile.getProject());
        GlobalSearchScope scope = javaFile.getResolveScope();

        for (PsiClass markupClass : markupClasses) {
            // Try the injected XmlFile first (available when injection has been computed).
            XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(markupClass);
            Set<String> usedNames;
            if (xmlFile != null) {
                // Normal path: use the fully-computed injected XML tree.
                // isResolvableClass() already uses PsiShortNamesCache as a fallback, so
                // this works even when the file's imports are gone.
                usedNames = Fxml2UnusedImportsInspection.collectUsedSimpleNames(xmlFile);
            } else {
                // Copy-flow fallback: injection not yet computed for the new file.
                // Parse the raw annotation string value directly to find class names.
                usedNames = extractClassNamesFromAnnotation(markupClass);
            }

            for (String simpleName : usedNames) {
                // Try the FXML-import-aware resolver first (only useful when imports present).
                PsiClass resolved = (xmlFile != null)
                        ? Fxml2ImportResolver.resolve(simpleName, xmlFile)
                        : null;

                if (resolved == null) {
                    // Fall back to short-name cache, preferring javafx.* then org.jfxcore.*
                    resolved = resolveByShortName(simpleName, shortNamesCache, scope);
                }

                if (resolved == null) continue;
                String fqn = resolved.getQualifiedName();
                if (fqn == null) continue;

                // Skip java.lang.*: those classes never need an explicit import.
                if (fqn.startsWith("java.lang.") && !fqn.substring("java.lang.".length()).contains(".")) {
                    continue;
                }
                // Skip the @ComponentView annotation itself: it is not an FXML class.
                if (Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN.equals(fqn)) continue;

                if (!needed.contains(fqn)) {
                    needed.add(fqn);
                }
            }
        }
        return needed;
    }

    /**
     * Extracts simple class names from the raw markup text of a {@code @ComponentView}
     * annotation.  Used as a last-resort fallback when the injected {@link XmlFile} has
     * not yet been computed (e.g. during a copy-class operation).
     *
     * <p>Looks for:
     * <ul>
     *   <li>XML element names starting with an uppercase letter: {@code <ClassName>},
     *       {@code </ClassName>}, {@code <ClassName.prop>} (static-property elements).</li>
     *   <li>Dotted attribute prefixes starting with an uppercase letter:
     *       {@code ClassName.propName="..."} (static-property attributes).</li>
     * </ul>
     */
    private static @NotNull Set<String> extractClassNamesFromAnnotation(@NotNull PsiClass psiClass) {
        PsiAnnotation annotation = psiClass.getAnnotation(Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN);
        if (annotation == null) return Collections.emptySet();
        String markupText = Fxml2EmbedMarkupUtil.extractMarkupFromJavaAnnotation(annotation);
        if (markupText == null) return Collections.emptySet();
        return extractClassNamesFromMarkup(markupText);
    }

    /**
     * Pattern for XML element names starting with an uppercase letter (potential class names).
     * Matches {@code <ClassName}, {@code </ClassName}, {@code <ClassName.prop>}.
     */
    private static final Pattern TAG_NAME_PATTERN =
            Pattern.compile("</?([A-Z][A-Za-z0-9_$]*)");

    /**
     * Pattern for static-property attribute prefixes starting with an uppercase letter,
     * e.g. {@code Command.onAction="..."} or {@code GridPane.rowIndex="..."}.
     */
    private static final Pattern STATIC_ATTR_PATTERN =
            Pattern.compile("\\b([A-Z][A-Za-z0-9_$]*)\\.[a-z][A-Za-z0-9_$]*\\s*=");

    /**
     * Scans raw FXML markup text for simple class names referenced as element tags and
     * static-property attribute prefixes.  Returns only names starting with an uppercase
     * letter (Java class-name convention).
     */
    private static @NotNull Set<String> extractClassNamesFromMarkup(@NotNull String markup) {
        Set<String> names = new HashSet<>();
        Matcher m = TAG_NAME_PATTERN.matcher(markup);
        while (m.find()) {
            names.add(m.group(1));
        }
        m = STATIC_ATTR_PATTERN.matcher(markup);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    /**
     * Resolves a simple class name using {@link PsiShortNamesCache} within {@code scope}.
     * When multiple classes share the name, prefers {@code javafx.*} then {@code org.jfxcore.*}
     * classes (the namespaces that FXML markup typically references).
     * Returns {@code null} when no unique, deterministic match can be made.
     */
    private static @Nullable PsiClass resolveByShortName(
            @NotNull String simpleName,
            @NotNull PsiShortNamesCache cache,
            @NotNull GlobalSearchScope scope) {
        PsiClass[] classes = cache.getClassesByName(simpleName, scope);
        if (classes.length == 0) return null;
        if (classes.length == 1) return classes[0];

        // Multiple matches: prefer javafx.* first
        for (PsiClass cls : classes) {
            String fqn = cls.getQualifiedName();
            if (fqn != null && fqn.startsWith("javafx.")) return cls;
        }
        // Then prefer org.jfxcore.*
        for (PsiClass cls : classes) {
            String fqn = cls.getQualifiedName();
            if (fqn != null && fqn.startsWith("org.jfxcore.")) return cls;
        }
        // Ambiguous, skip; user can add import manually
        return null;
    }

    // -----------------------------------------------------------------------
    // Write-phase helper
    // -----------------------------------------------------------------------

    /**
     * Adds back any entry from {@code neededImports} that is currently missing from
     * {@code javaFile}'s import list.  Called inside a write action.
     */
    private static void restoreMissingImports(
            @NotNull PsiJavaFile javaFile,
            @NotNull List<String> neededImports) {

        PsiDocumentManager.getInstance(javaFile.getProject()).commitAllDocuments();

        PsiImportList list = javaFile.getImportList();
        if (list == null) return;

        JavaPsiFacade facade = JavaPsiFacade.getInstance(javaFile.getProject());
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(javaFile.getProject());
        GlobalSearchScope scope = GlobalSearchScope.allScope(javaFile.getProject());

        for (String importTarget : neededImports) {
            if (importTarget.endsWith(".*")) {
                String pkg = importTarget.substring(0, importTarget.length() - 2);
                if (list.findOnDemandImportStatement(pkg) != null) continue; // already present
                list.add(factory.createImportStatementOnDemand(pkg));
            } else {
                if (list.findSingleClassImportStatement(importTarget) != null) continue;
                // Don't add specific import if a wildcard for the same package is present.
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

    // -----------------------------------------------------------------------
    // Predicates / utilities
    // -----------------------------------------------------------------------

    private static boolean hasMarkupAnnotation(@NotNull PsiJavaFile javaFile) {
        for (PsiClass cls : javaFile.getClasses()) {
            if (cls.hasAnnotation(Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN)) return true;
        }
        return false;
    }

    private static @NotNull List<PsiClass> getMarkupClassesInFile(@NotNull PsiJavaFile javaFile) {
        List<PsiClass> result = new ArrayList<>();
        for (PsiClass cls : javaFile.getClasses()) {
            if (cls.hasAnnotation(Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN)) result.add(cls);
        }
        return result;
    }
}
