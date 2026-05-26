// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.codeinsight;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.refactoring.RefactoringHelper;
import com.intellij.usageView.UsageInfo;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link RefactoringHelper} that prevents Kotlin's
 * {@code KotlinOptimizeImportsRefactoringHelper} from removing Kotlin imports that are
 * needed by embedded FXML markup in {@code @ComponentView}-annotated classes.
 *
 * <h2>Problem</h2>
 * After every refactoring Kotlin's {@code KotlinOptimizeImportsRefactoringHelper} calls
 * {@code KotlinOptimizeImportsFacility.analyzeImports()} which marks imports as unused
 * when they are not referenced in the Kotlin source code.  Imports that are only used
 * inside the {@code @ComponentView} string-literal annotation value are invisible to
 * that analysis and are therefore removed.
 *
 * <h2>Solution</h2>
 * Registered with {@code order="last"} in {@code fxml2-with-kotlin.xml} so it runs
 * <em>after</em> {@code KotlinOptimizeImportsRefactoringHelper}.
 *
 * <ul>
 *   <li><b>Read phase</b> ({@link #prepareOperation}): for every Kotlin file involved
 *       in the refactoring that contains a {@code @ComponentView}-annotated class,
 *       capture the list of import targets referenced by the embedded FXML markup.</li>
 *   <li><b>Write phase</b> ({@link #performOperation}): add back any of those import
 *       targets that are no longer present in the file.</li>
 * </ul>
 */
public final class Fxml2EmbeddedKotlinRefactoringHelper
        implements RefactoringHelper<Map<SmartPsiElementPointer<KtFile>, List<String>>> {

    // -----------------------------------------------------------------------
    // RefactoringHelper
    // -----------------------------------------------------------------------

    @Override
    public @NotNull Map<SmartPsiElementPointer<KtFile>, List<String>> prepareOperation(
            @NotNull UsageInfo @NotNull [] usages,
            @NotNull List<? extends @NotNull PsiElement> elements) {

        Map<SmartPsiElementPointer<KtFile>, List<String>> result = new LinkedHashMap<>();

        // Process the elements being refactored.
        for (PsiElement element : elements) {
            if (!element.isValid()) continue;
            PsiFile file = element.getContainingFile();
            if (file instanceof KtFile ktFile) {
                addIfNeeded(ktFile, result);
            }
        }

        // Process files containing usages.
        for (UsageInfo usage : usages) {
            if (usage.isNonCodeUsage) continue;
            PsiFile file = usage.getFile();
            if (file instanceof KtFile ktFile) {
                addIfNeeded(ktFile, result);
            }
        }

        return result;
    }

    @Override
    public void performOperation(
            @NotNull Project project,
            @NotNull Map<SmartPsiElementPointer<KtFile>, List<String>> operationData) {

        if (operationData.isEmpty()) return;

        boolean preferMarkup = Fxml2ImportPlacementInspectionHelper.isPreferMarkupImportEnabled(project);
        boolean preferCode = Fxml2ImportPlacementInspectionHelper.isPreferCodeImportEnabled(project);

        ApplicationManager.getApplication().runWriteAction(() -> {
            PsiDocumentManager.getInstance(project).commitAllDocuments();
            for (Map.Entry<SmartPsiElementPointer<KtFile>, List<String>> entry
                    : operationData.entrySet()) {
                KtFile ktFile = entry.getKey().getElement();
                if (ktFile == null || !ktFile.isValid()) continue;
                restoreMissingImports(ktFile, entry.getValue());

                // Apply the active import-placement preference (if any).
                List<KtClassOrObject> markupClasses = getMarkupClassesInFile(ktFile);
                if (preferMarkup) {
                    applyPreferMarkupToKotlinFile(ktFile, markupClasses, project);
                } else if (preferCode) {
                    List<PsiClass> psiClasses = toPsiClasses(markupClasses, project);
                    Fxml2ImportPlacementInspectionHelper.moveAllMarkupImportsToKotlinCode(
                            ktFile, psiClasses, project);
                }
            }
        });
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void addIfNeeded(
            @NotNull KtFile ktFile,
            @NotNull Map<SmartPsiElementPointer<KtFile>, List<String>> result) {

        if (!hasMarkupAnnotation(ktFile)) return;

        for (SmartPsiElementPointer<KtFile> ptr : result.keySet()) {
            KtFile existing = ptr.getElement();
            if (existing != null && existing.equals(ktFile)) return;
        }

        List<String> needed = collectNeededImports(ktFile);
        if (!needed.isEmpty()) {
            SmartPsiElementPointer<KtFile> ptr =
                    SmartPointerManager.getInstance(ktFile.getProject())
                            .createSmartPsiElementPointer(ktFile);
            result.put(ptr, needed);
        }
    }

    private static @NotNull List<String> collectNeededImports(@NotNull KtFile ktFile) {
        List<KtClassOrObject> markupClasses = getMarkupClassesInFile(ktFile);
        if (markupClasses.isEmpty()) return Collections.emptyList();

        // Primary strategy: from existing import list + injected XML.
        List<String> needed = collectNeededFromImportList(markupClasses, ktFile);

        // Fallback: derive from XML content / annotation regex.
        if (needed.isEmpty()) {
            needed = collectNeededFromXmlContent(markupClasses, ktFile);
        }

        return needed;
    }

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
                if (imp.getAliasName() != null) continue; // aliased imports not forwarded
                String fqStr = fqName.asString().replace("`", "");

                if (!Fxml2ImportUtil.isImportNeededByXmlFile(
                        fqStr, imp.isAllUnder(), xmlFile)) {
                    continue;
                }

                String target = imp.isAllUnder() ? fqStr + ".*" : fqStr;
                if (!needed.contains(target)) {
                    needed.add(target);
                }
            }
        }
        return needed;
    }

    private static @NotNull List<String> collectNeededFromXmlContent(
            @NotNull List<KtClassOrObject> markupClasses,
            @NotNull KtFile ktFile) {

        List<String> needed = new ArrayList<>();
        PsiShortNamesCache cache = PsiShortNamesCache.getInstance(ktFile.getProject());
        GlobalSearchScope scope = ktFile.getResolveScope();

        for (KtClassOrObject ktClass : markupClasses) {
            var lightClass = KotlinAsJavaSupport.getInstance(ktClass.getProject())
                    .getLightClass(ktClass);
            if (lightClass == null) continue;

            XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(lightClass);
            Set<String> usedNames;
            if (xmlFile != null) {
                usedNames = Fxml2UnusedImportsInspection.collectUsedSimpleNames(xmlFile);
            } else {
                usedNames = extractClassNamesFromAnnotation(ktClass);
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
                if (fqn.startsWith("java.lang.")
                        && !fqn.substring("java.lang.".length()).contains(".")) {
                    continue;
                }
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

    private static @NotNull Set<String> extractClassNamesFromAnnotation(
            @NotNull KtClassOrObject ktClass) {
        for (KtAnnotationEntry entry : ktClass.getAnnotationEntries()) {
            var shortName = entry.getShortName();
            if (shortName == null
                    || !"ComponentView".equals(shortName.getIdentifier())) continue;
            var args = entry.getValueArguments();
            if (args.isEmpty()) continue;
            var argExpr = args.getFirst().getArgumentExpression();
            String markup = Fxml2EmbedMarkupUtil.extractMarkupFromKotlinExpression(argExpr);
            if (markup != null) return extractClassNamesFromMarkup(markup);
        }
        return Collections.emptySet();
    }

    private static @NotNull Set<String> extractClassNamesFromMarkup(@NotNull String markup) {
        Set<String> names = new HashSet<>();
        Matcher m = TAG_NAME_PATTERN.matcher(markup);
        while (m.find()) names.add(m.group(1));
        m = STATIC_ATTR_PATTERN.matcher(markup);
        while (m.find()) names.add(m.group(1));
        return names;
    }

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
        return null;
    }

    private static void restoreMissingImports(
            @NotNull KtFile ktFile,
            @NotNull List<String> neededImports) {

        var importList = ktFile.getImportList();
        if (importList == null) return;

        KtPsiFactory factory = new KtPsiFactory(ktFile.getProject());

        for (String importTarget : neededImports) {
            if (isAlreadyPresent(ktFile, importTarget)) continue;

            KtFile tempFile = factory.createFile("import " + importTarget + "\n");
            List<KtImportDirective> tempImports = tempFile.getImportDirectives();
            if (tempImports.isEmpty()) continue;
            importList.add(tempImports.getFirst().copy());
        }
    }

    private static boolean isAlreadyPresent(
            @NotNull KtFile ktFile,
            @NotNull String importTarget) {
        boolean wildcard = importTarget.endsWith(".*");
        String pkg = wildcard ? importTarget.substring(0, importTarget.length() - 2) : null;

        for (KtImportDirective imp : ktFile.getImportDirectives()) {
            var fqName = imp.getImportedFqName();
            if (fqName == null) continue;
            String fqStr = fqName.asString().replace("`", "");
            if (wildcard) {
                if (imp.isAllUnder() && pkg.equals(fqStr)) return true;
            } else {
                if (!imp.isAllUnder() && importTarget.equals(fqStr)) return true;
            }
        }
        return false;
    }

    /**
     * If "prefer markup imports" is active, moves any Kotlin imports that are exclusively
     * used inside embedded FXML markup to {@code <?import?>} PIs inside those blocks,
     * and removes the corresponding Kotlin import directives.
     */
    private static void applyPreferMarkupToKotlinFile(
            @NotNull KtFile ktFile,
            @NotNull List<KtClassOrObject> markupClasses,
            @NotNull Project project) {

        List<KtImportDirective> toConvert = new ArrayList<>();
        for (KtImportDirective directive : ktFile.getImportDirectives()) {
            if (!directive.isValidImport() || directive.isAllUnder()) continue;
            if (Fxml2ImportPlacementInspectionHelper.isKotlinImportOnlyUsedInMarkup(directive, ktFile)) {
                toConvert.add(directive);
            }
        }

        if (toConvert.isEmpty()) return;
        List<PsiClass> psiClasses = toPsiClasses(markupClasses, project);

        for (KtImportDirective directive : toConvert) {
            if (!directive.isValid()) continue;
            var fqName = directive.getImportedFqName();
            if (fqName == null) continue;
            String fqn = fqName.asString();
            Fxml2ImportPlacementInspectionHelper.insertFqnIntoAllMarkupBlocks(
                    psiClasses, fqn, project);
            directive.delete();
        }
    }

    private static @NotNull List<PsiClass> toPsiClasses(
            @NotNull List<KtClassOrObject> ktClasses,
            @NotNull Project project) {
        List<PsiClass> result = new ArrayList<>(ktClasses.size());
        for (KtClassOrObject cls : ktClasses) {
            PsiClass light = KotlinAsJavaSupport.getInstance(project).getLightClass(cls);
            if (light != null) result.add(light);
        }
        return result;
    }

    private static boolean hasMarkupAnnotation(@NotNull KtFile ktFile) {
        for (KtClassOrObject ktClass :
                PsiTreeUtil.findChildrenOfType(ktFile, KtClassOrObject.class)) {
            for (KtAnnotationEntry entry : ktClass.getAnnotationEntries()) {
                var shortName = entry.getShortName();
                if (shortName != null
                        && "ComponentView".equals(shortName.getIdentifier())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static @NotNull List<KtClassOrObject> getMarkupClassesInFile(
            @NotNull KtFile ktFile) {
        List<KtClassOrObject> result = new ArrayList<>();
        for (KtClassOrObject ktClass :
                PsiTreeUtil.findChildrenOfType(ktFile, KtClassOrObject.class)) {
            for (KtAnnotationEntry entry : ktClass.getAnnotationEntries()) {
                var shortName = entry.getShortName();
                if (shortName != null
                        && "ComponentView".equals(shortName.getIdentifier())) {
                    result.add(ktClass);
                    break;
                }
            }
        }
        return result;
    }
}
