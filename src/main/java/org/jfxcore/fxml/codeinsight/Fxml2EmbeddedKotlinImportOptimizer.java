package org.jfxcore.fxml.codeinsight;

import com.intellij.lang.ImportOptimizer;
import com.intellij.lang.LanguageImportStatements;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.EmptyRunnable;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.asJava.KotlinAsJavaSupport;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtImportDirective;
import org.jetbrains.kotlin.psi.KtPsiFactory;
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
 * {@link ImportOptimizer} for Kotlin files that contain classes annotated with
 * {@code @org.jfxcore.markup.ComponentView}.
 *
 * <h2>Problem</h2>
 * IntelliJ's built-in Kotlin import optimizer analyzes Kotlin source code to find which
 * classes are referenced.  It does <em>not</em> understand that class names appearing
 * inside a string literal that is the value of a {@code @ComponentView} annotation are also
 * "in use", so it removes the corresponding Kotlin import statements.
 *
 * <h2>Solution: normal "Optimize Imports" flow</h2>
 * This optimizer is registered with {@code order="first"} so that
 * {@link LanguageImportStatements#forFile} picks it (rather than the built-in Kotlin
 * optimizer) for files that have {@code @ComponentView}.  Because {@code forFile} only
 * picks <em>one</em> optimizer per file (it breaks after the first match), our optimizer
 * must itself invoke the built-in Kotlin import optimizer: see
 * {@link #getBuiltInOptimizerRunnable}.
 *
 * <p>The read phase captures FXML-needed imports before the built-in optimizer strips them.
 * The write phase first runs the built-in optimizer, then restores the FXML-needed imports.
 *
 * <h2>Solution: copy (F5) flow</h2>
 * When the file's imports are already stripped before this optimizer runs, the primary
 * strategy finds nothing.  The fallback strategy derives needed imports either from the
 * injected XML tree (when available) or by parsing the raw annotation string with a
 * regex; both paths use {@link PsiShortNamesCache} for class resolution.
 */
public final class Fxml2EmbeddedKotlinImportOptimizer implements ImportOptimizer {

    // -----------------------------------------------------------------------
    // ImportOptimizer
    // -----------------------------------------------------------------------

    @Override
    public boolean supports(@NotNull PsiFile file) {
        if (!(file instanceof KtFile ktFile)) return false;
        return hasMarkupAnnotation(ktFile);
    }

    @Override
    public @NotNull Runnable processFile(@NotNull PsiFile file) {
        if (!(file instanceof KtFile ktFile)) return EmptyRunnable.INSTANCE;

        // --- Read phase ---
        // Collect the Kotlin imports that are needed by the embedded FXML2 markup(s).
        // We capture them now (before any optimizer modifies the file) so that the write
        // phase can restore any that get removed.

        List<KtClassOrObject> markupClasses = getMarkupClassesInFile(ktFile);
        if (markupClasses.isEmpty()) return EmptyRunnable.INSTANCE;

        // Primary strategy: import targets (FQN for specific, "pkg.*" for wildcard) that
        // are referenced by at least one embedded FXML2 markup in this file.
        // Works when imports are still present (normal "Optimize Imports" flow).
        List<String> neededImports = collectNeededFromImportList(markupClasses, ktFile);

        // Fallback strategy: derive needed imports directly from the XML markup content.
        // This is required when the file's imports were already stripped before this
        // optimizer ran, e.g. in a copy-class flow where removeRedundantImports() was
        // called prior to OptimizeImportsProcessor.
        if (neededImports.isEmpty()) {
            neededImports = collectNeededFromXmlContent(markupClasses, ktFile);
        }

        // Check preference inspections: if "prefer markup imports" is enabled, detect
        // imports that are only used in markup and convert them to <?import?> PIs.
        Project project = ktFile.getProject();
        boolean preferMarkup = Fxml2ImportPlacementInspectionHelper.isPreferMarkupImportEnabled(project);
        Set<String> markupOnlyFqns = Collections.emptySet();
        if (preferMarkup && !neededImports.isEmpty()) {
            markupOnlyFqns = new HashSet<>();
            var importList = ktFile.getImportList();
            if (importList != null) {
                for (KtImportDirective directive : importList.getImports()) {
                    var fqName = directive.getImportedFqName();
                    if (fqName == null || directive.isAllUnder()) continue;
                    String qn = fqName.asString();
                    if (neededImports.contains(qn)
                            && Fxml2ImportPlacementInspectionHelper.isKotlinImportOnlyUsedInMarkup(directive, ktFile)) {
                        markupOnlyFqns.add(qn);
                    }
                }
            }
        }
        boolean preferCode = Fxml2ImportPlacementInspectionHelper.isPreferCodeImportEnabled(project);

        // Get the built-in Kotlin import optimizer runnable.
        // Because we are registered with order="first" and LanguageImportStatements.forFile()
        // breaks after the first supporting optimizer, the built-in optimizer is NOT
        // added to the set by forFile(). We must call it ourselves so that genuinely
        // unused imports are still removed.
        Runnable builtInRunnable = getBuiltInOptimizerRunnable(ktFile);

        if (neededImports.isEmpty() && !preferCode) {
            // Still need to run the built-in optimizer even if we have nothing to add back.
            return builtInRunnable;
        }

        // --- Write phase ---
        final List<String> finalNeeded = neededImports;
        final Set<String> finalMarkupOnly = markupOnlyFqns;
        return () -> {
            builtInRunnable.run();
            if (!finalMarkupOnly.isEmpty()) {
                // Restore only code-and-markup (non-markup-only) imports; the markup-only
                // ones are converted to <?import?> PIs inside the embedded markup blocks.
                List<String> codeAndMarkup = finalNeeded.stream()
                        .filter(fqn -> !finalMarkupOnly.contains(fqn))
                        .collect(Collectors.toList());
                restoreMissingImports(ktFile, codeAndMarkup);
                List<PsiClass> psiClasses = markupClasses.stream()
                        .map(cls -> KotlinAsJavaSupport.getInstance(project).getLightClass(cls))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                for (String fqn : finalMarkupOnly) {
                    Fxml2ImportPlacementInspectionHelper.insertFqnIntoAllMarkupBlocks(
                            psiClasses, fqn, project);
                }
            } else {
                restoreMissingImports(ktFile, finalNeeded);
            }
            if (preferCode) {
                List<PsiClass> psiClasses = markupClasses.stream()
                        .map(cls -> KotlinAsJavaSupport.getInstance(project).getLightClass(cls))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                Fxml2ImportPlacementInspectionHelper.moveAllMarkupImportsToKotlinCode(ktFile, psiClasses, project);
            }
        };
    }

    // -----------------------------------------------------------------------
    // Built-in optimizer delegation
    // -----------------------------------------------------------------------

    /**
     * Finds and calls the built-in Kotlin import optimizer's read phase, returning its write
     * runnable.
     *
     * <p>Because this optimizer is registered with {@code order="first"},
     * {@link LanguageImportStatements#forFile} picks it instead of the built-in one for files
     * that carry {@code @ComponentView}.  We must therefore call the built-in optimizer
     * ourselves so that genuinely unused imports are still removed.
     */
    private static @NotNull Runnable getBuiltInOptimizerRunnable(@NotNull KtFile ktFile) {
        for (ImportOptimizer opt : LanguageImportStatements.INSTANCE.allForLanguage(ktFile.getLanguage())) {
            if (opt instanceof Fxml2EmbeddedKotlinImportOptimizer) continue; // skip ourselves
            if (opt.supports(ktFile)) {
                return opt.processFile(ktFile);
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
     * given {@code markupClasses}.  Only imports that are already present in the Kotlin file's
     * import list are considered; we never invent new imports here.
     *
     * <p>Returns an empty list when the import list has already been stripped.  In that case
     * the caller must fall back to {@link #collectNeededFromXmlContent}.
     */
    private static @NotNull List<String> collectNeededFromImportList(
            @NotNull List<KtClassOrObject> markupClasses,
            @NotNull KtFile ktFile) {

        List<String> needed = new ArrayList<>();

        for (KtClassOrObject ktClass : markupClasses) {
            var lightClass = KotlinAsJavaSupport.getInstance(ktClass.getProject())
                    .getLightClass(ktClass);
            if (lightClass == null) continue;

            XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(lightClass);
            if (xmlFile == null) continue;

            for (KtImportDirective imp : ktFile.getImportDirectives()) {
                if (!imp.isValidImport()) continue;
                var fqName = imp.getImportedFqName();
                if (fqName == null) continue;
                // Aliased imports (`import foo.Bar as Baz`) are invisible to FXML2 -
                // the compiler ignores them too (cannot forward an alias to the AP).
                if (imp.getAliasName() != null) continue;
                // Strip backtick-escaping for keyword-named identifiers (e.g. `sealed`).
                String fqStr = fqName.asString().replace("`", "");

                if (!Fxml2ImportUtil.isImportNeededByXmlFile(fqStr, imp.isAllUnder(), xmlFile)) continue;

                String importTarget = imp.isAllUnder() ? fqStr + ".*" : fqStr;
                if (!needed.contains(importTarget)) {
                    needed.add(importTarget);
                }
            }
        }
        return needed;
    }

    /**
     * Fallback strategy: derives needed import FQNs by walking the injected FXML2 markup
     * content and resolving class names via the project's {@link PsiShortNamesCache}.
     *
     * <p>This is used when the host file's import list has already been stripped.  In that
     * case the injected XML has no synthesised {@code <?import?>} PIs and
     * {@link Fxml2ImportResolver#resolve} fails; the short-names cache provides the class
     * resolution fallback.
     *
     * <p>If the injected {@link XmlFile} has not been computed yet, the annotation string
     * value is parsed directly with a regex to extract candidate class names.
     *
     * <p>When multiple classes share a simple name, JavaFX classes ({@code javafx.*}) are
     * preferred, then JFXcore classes ({@code org.jfxcore.*}).
     */
    private static @NotNull List<String> collectNeededFromXmlContent(
            @NotNull List<KtClassOrObject> markupClasses,
            @NotNull KtFile ktFile) {

        List<String> needed = new ArrayList<>();
        PsiShortNamesCache shortNamesCache = PsiShortNamesCache.getInstance(ktFile.getProject());
        GlobalSearchScope scope = ktFile.getResolveScope();

        for (KtClassOrObject ktClass : markupClasses) {
            var lightClass = KotlinAsJavaSupport.getInstance(ktClass.getProject())
                    .getLightClass(ktClass);
            if (lightClass == null) continue;

            // Try the injected XmlFile first (available when injection has been computed).
            XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(lightClass);
            Set<String> usedNames;
            if (xmlFile != null) {
                // Normal path: use the fully-computed injected XML tree.
                usedNames = Fxml2UnusedImportsInspection.collectUsedSimpleNames(xmlFile);
            } else {
                // Copy-flow fallback: injection not yet computed for the new file.
                usedNames = extractClassNamesFromAnnotation(ktClass);
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
     * annotation on a Kotlin class.  Used as a last-resort fallback when the injected
     * {@link XmlFile} has not yet been computed.
     *
     * <p>Looks for XML element names and static-property attribute prefixes that start
     * with an uppercase letter (Java/Kotlin class-name convention).
     */
    private static @NotNull Set<String> extractClassNamesFromAnnotation(@NotNull KtClassOrObject ktClass) {
        for (KtAnnotationEntry annotEntry : ktClass.getAnnotationEntries()) {
            var shortName = annotEntry.getShortName();
            if (shortName == null || !"ComponentView".equals(shortName.getIdentifier())) continue;
            var args = annotEntry.getValueArguments();
            if (args.isEmpty()) continue;
            var argExpr = args.getFirst().getArgumentExpression();
            String markupText = Fxml2EmbedMarkupUtil.extractMarkupFromKotlinExpression(argExpr);
            if (markupText != null) return extractClassNamesFromMarkup(markupText);
        }
        return Collections.emptySet();
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
     * Scans raw FXML2 markup text for simple class names referenced as element tags and
     * static-property attribute prefixes.  Returns only names starting with an uppercase
     * letter (Java/Kotlin class-name convention).
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
     * classes (the namespaces that FXML2 markup typically references).
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
     * {@code ktFile}'s import list.  Called inside a write action.
     */
    private static void restoreMissingImports(
            @NotNull KtFile ktFile,
            @NotNull List<String> neededImports) {

        PsiDocumentManager.getInstance(ktFile.getProject()).commitAllDocuments();

        var importList = ktFile.getImportList();
        if (importList == null) return;

        KtPsiFactory factory = new KtPsiFactory(ktFile.getProject());

        for (String importTarget : neededImports) {
            if (isAlreadyPresent(ktFile, importTarget)) continue;

            // Build the import directive from text to avoid using the deprecated ImportPath API.
            // createFile("import pkg.Cls\n") is a lightweight in-memory parse; we copy the
            // resulting KtImportDirective node into the real file's import list.
            KtFile tempFile = factory.createFile("import " + importTarget + "\n");
            List<KtImportDirective> tempImports = tempFile.getImportDirectives();
            if (tempImports.isEmpty()) continue;
            importList.add(tempImports.getFirst().copy());
        }
    }

    /** Returns {@code true} when {@code importTarget} is already in {@code ktFile}'s imports. */
    private static boolean isAlreadyPresent(@NotNull KtFile ktFile, @NotNull String importTarget) {
        boolean wildcard = importTarget.endsWith(".*");
        String pkg = wildcard ? importTarget.substring(0, importTarget.length() - 2) : null;

        for (KtImportDirective imp : ktFile.getImportDirectives()) {
            var fqName = imp.getImportedFqName();
            if (fqName == null) continue;
            // Strip backticks before comparing (e.g. `sealed` -> sealed)
            String fqStr = fqName.asString().replace("`", "");
            if (wildcard) {
                if (imp.isAllUnder() && pkg.equals(fqStr)) return true;
            } else {
                if (!imp.isAllUnder() && importTarget.equals(fqStr)) return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Predicates / utilities
    // -----------------------------------------------------------------------

    private static boolean hasMarkupAnnotation(@NotNull KtFile ktFile) {
        for (KtClassOrObject ktClass :
                PsiTreeUtil.findChildrenOfType(ktFile, KtClassOrObject.class)) {
            for (KtAnnotationEntry annotEntry : ktClass.getAnnotationEntries()) {
                var shortName = annotEntry.getShortName();
                if (shortName != null && "ComponentView".equals(shortName.getIdentifier())) return true;
            }
        }
        return false;
    }

    private static @NotNull List<KtClassOrObject> getMarkupClassesInFile(@NotNull KtFile ktFile) {
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
