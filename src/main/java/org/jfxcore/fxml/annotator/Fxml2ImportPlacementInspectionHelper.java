// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.annotator;

import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.codeInspection.InspectionProfile;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiImportStaticStatement;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlProcessingInstruction;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.lang.Fxml2EmbedMarkupUtil;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.jfxcore.fxml.lang.Fxml2ImportUtil;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared logic for {@link Fxml2PreferMarkupImportInspection} and
 * {@link Fxml2PreferCodeImportInspection}.
 *
 * <p>Provides helpers that:
 * <ul>
 *   <li>Detect whether a Java or Kotlin import is exclusively referenced inside embedded
 *       FXML2 markup (and therefore could be replaced by a {@code <?import?>} PI).</li>
 *   <li>Detect whether a {@code <?import?>} PI in embedded markup could be moved to the
 *       host Java or Kotlin file as a code import.</li>
 *   <li>Perform the actual insertion and deletion of import statements in both forms.</li>
 * </ul>
 */
public final class Fxml2ImportPlacementInspectionHelper {



    private Fxml2ImportPlacementInspectionHelper() {}

    // -----------------------------------------------------------------------
    // Detection helpers
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when {@code importStmt} in {@code javaFile} satisfies all of:
     * <ol>
     *   <li>It is not a static import.</li>
     *   <li>It is not a wildcard (on-demand) import (wildcard handling is deferred).</li>
     *   <li>The imported class has no references in the Java source code outside of
     *       {@code @ComponentView} annotation string values
     *       (determined by a PSI walk that skips the import list and all string literals).</li>
     *   <li>At least one {@code @ComponentView}-annotated class in the file uses the
     *       imported class inside its embedded FXML2 markup.  When the injected
     *       {@link XmlFile} is not yet available, a raw-text scan of the annotation
     *       value is used as a fallback to avoid a false negative.</li>
     * </ol>
     *
     * @param importStmt the import statement to evaluate
     * @param javaFile   the Java source file that contains the import
     */
    public static boolean isJavaImportOnlyUsedInMarkup(
            @NotNull PsiImportStatement importStmt,
            @NotNull PsiJavaFile javaFile) {

        if (importStmt instanceof PsiImportStaticStatement) return false;
        // Wildcard imports are not supported in this version; skip them.
        if (importStmt.isOnDemand()) return false;

        String fqn = importStmt.getQualifiedName();
        if (fqn == null) return false;
        String simpleName = Fxml2ImportUtil.simpleNameOf(fqn);

        // If the imported class appears in any Java code reference outside @ComponentView
        // annotation string values, it is NOT a markup-only import.
        if (isReferencedInJavaCodeOutsideMarkup(importStmt, javaFile)) return false;

        // Check every @ComponentView-annotated class (including inner classes).
        var markupClasses = getMarkupClassesInJavaFile(javaFile);
        for (PsiClass cls : markupClasses) {
            // Precise check: use the injected XML file when it is already available.
            XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(cls);
            if (xmlFile != null) {
                if (Fxml2ImportUtil.isImportNeededByXmlFile(fqn, false, xmlFile)) return true;
                continue; // injection available but class not used here; check next
            }
            // Fallback: scan the raw annotation text for a word-boundary occurrence of
            // the simple class name.  This avoids a false negative when the injection
            // has not been computed yet during the first inspection pass.
            PsiAnnotation annotation = cls.getAnnotation(Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN);
            if (annotation == null) continue;
            String markup = Fxml2EmbedMarkupUtil.extractMarkupFromJavaAnnotation(annotation);
            if (markup != null && containsSimpleNameUsage(markup, simpleName)) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if {@code importStmt}'s target class is referenced at least
     * once in {@code javaFile} outside the file's import list.
     *
     * <p>The check walks all PSI elements in the file, skipping the import section and
     * stopping at the first {@link PsiJavaCodeReferenceElement} that resolves to the
     * imported class.  String literals (including {@code @ComponentView} text-block values)
     * do not contain Java reference elements; no additional filtering is needed.
     *
     * <p>Returns {@code true} also when the import cannot be resolved, to avoid
     * false-positive "markup-only" detection for unresolvable imports.
     */
    private static boolean isReferencedInJavaCodeOutsideMarkup(
            @NotNull PsiImportStatement importStmt,
            @NotNull PsiJavaFile javaFile) {
        PsiElement resolved = importStmt.resolve();
        if (resolved == null) return true; // treat unresolvable as "used" to avoid false positives

        String fqn = importStmt.getQualifiedName();
        if (fqn == null) return true;
        String simpleName = Fxml2ImportUtil.simpleNameOf(fqn);

        boolean[] found = {false};
        javaFile.accept(new PsiRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (found[0]) return;
                // Skip the import section to avoid matching the import statement itself.
                if (element instanceof PsiImportList) return;
                if (element instanceof PsiJavaCodeReferenceElement ref) {
                    // Quick pre-filter: only resolve when the name matches the simple
                    // name of the imported class, avoiding unnecessary resolve() calls.
                    String refName = ref.getReferenceName();
                    if (simpleName.equals(refName)
                            && javaFile.getManager().areElementsEquivalent(
                                    ref.resolve(), resolved)) {
                        found[0] = true;
                        stopWalking();
                        return;
                    }
                }
                super.visitElement(element);
            }
        });
        return found[0];
    }

    /**
     * Returns {@code true} if {@code markup} contains {@code simpleName} as a word-boundary
     * token (not adjacent to a letter, digit, or underscore).
     */
    private static boolean containsSimpleNameUsage(@NotNull String markup, @NotNull String simpleName) {
        int idx = 0;
        while ((idx = markup.indexOf(simpleName, idx)) >= 0) {
            char before = idx > 0 ? markup.charAt(idx - 1) : '\0';
            int end = idx + simpleName.length();
            char after = end < markup.length() ? markup.charAt(end) : '\0';
            if (!Character.isLetterOrDigit(before) && before != '_'
                    && !Character.isLetterOrDigit(after) && after != '_') {
                return true;
            }
            idx++;
        }
        return false;
    }

    /**
     * Returns {@code true} when {@code importDirective} in a Kotlin file satisfies all of:
     * <ol>
     *   <li>It is not an aliased import.</li>
     *   <li>It is not a wildcard import (deferred to a later revision).</li>
     *   <li>The imported class is used by at least one {@code @ComponentView}-annotated
     *       class in the Kotlin file's embedded markup.</li>
     *   <li>The imported class has no references OUTSIDE embedded markup strings in the
     *       Kotlin file (determined by inspecting Kotlin PSI text outside annotation
     *       string values).</li>
     * </ol>
     *
     * <p>This method always returns {@code false} when the Kotlin plugin is not available.
     */
    public static boolean isKotlinImportOnlyUsedInMarkup(
            @NotNull PsiElement importDirective,
            @NotNull PsiFile ktFile) {
        try {
            return isKotlinImportOnlyUsedInMarkupImpl(importDirective, ktFile);
        } catch (NoClassDefFoundError ignored) {
            return false;
        }
    }

    private static boolean isKotlinImportOnlyUsedInMarkupImpl(
            @NotNull PsiElement importDirective,
            @NotNull PsiFile ktFile) {
        if (!(importDirective instanceof org.jetbrains.kotlin.psi.KtImportDirective imp)) return false;
        if (!(ktFile instanceof org.jetbrains.kotlin.psi.KtFile kf)) return false;
        // Aliased imports cannot be used as markup imports.
        if (imp.getAliasName() != null) return false;
        // Wildcard imports are not supported in this version.
        if (imp.isAllUnder()) return false;

        var fqName = imp.getImportedFqName();
        if (fqName == null) return false;
        String fqn = fqName.asString().replace("`", "");
        String simpleName = Fxml2ImportUtil.simpleNameOf(fqn);

        // Check that the import is needed by embedded markup and not used in Kotlin code.
        boolean neededByMarkup = false;
        for (org.jetbrains.kotlin.psi.KtClassOrObject ktClass :
                PsiTreeUtil.findChildrenOfType(kf, org.jetbrains.kotlin.psi.KtClassOrObject.class)) {
            boolean hasAnnotation = ktClass.getAnnotationEntries().stream().anyMatch(e -> {
                var sn = e.getShortName();
                return sn != null && "ComponentView".equals(sn.getIdentifier());
            });
            if (!hasAnnotation) continue;

            // Precise check using injected file when available.
            var lightClass = org.jetbrains.kotlin.asJava.KotlinAsJavaSupport.getInstance(kf.getProject())
                    .getLightClass(ktClass);
            if (lightClass != null) {
                XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(lightClass);
                if (xmlFile != null) {
                    if (Fxml2ImportUtil.isImportNeededByXmlFile(fqn, false, xmlFile)) {
                        neededByMarkup = true;
                        break;
                    }
                    continue;
                }
            }

            // Fallback: scan the raw annotation text.
            for (org.jetbrains.kotlin.psi.KtAnnotationEntry annotEntry : ktClass.getAnnotationEntries()) {
                var sn = annotEntry.getShortName();
                if (sn == null || !"ComponentView".equals(sn.getIdentifier())) continue;
                var args = annotEntry.getValueArguments();
                if (args.isEmpty()) continue;
                var argExpr = args.getFirst().getArgumentExpression();
                String markup = Fxml2EmbedMarkupUtil.extractMarkupFromKotlinExpression(argExpr);
                if (markup != null && containsSimpleNameUsage(markup, simpleName)) {
                    neededByMarkup = true;
                }
            }
            if (neededByMarkup) break;
        }
        if (!neededByMarkup) return false;

        // Verify the import is not used outside markup annotation strings.
        return !hasNonMarkupReferenceInKotlinFile(simpleName, kf);
    }

    /**
     * Returns {@code true} if {@code simpleName} appears in {@code ktFile} outside of
     * {@code @ComponentView} annotation string values.
     */
    private static boolean hasNonMarkupReferenceInKotlinFile(
            @NotNull String simpleName,
            @NotNull org.jetbrains.kotlin.psi.KtFile ktFile) {
        for (PsiElement element :
                PsiTreeUtil.findChildrenOfType(ktFile, org.jetbrains.kotlin.psi.KtSimpleNameExpression.class)) {
            if (!simpleName.equals(element.getText())) continue;
            if (isInsideMarkupAnnotationValue(element)) continue;
            return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if {@code element} is located inside the value of a
     * {@code @ComponentView} annotation (i.e., inside a text block or Kotlin
     * triple-quoted string that is the annotation value).
     */
    static boolean isInsideMarkupAnnotationValue(@NotNull PsiElement element) {
        // Java path: inside a PsiLiteralExpression that is the value of @ComponentView
        var literal = PsiTreeUtil.getParentOfType(element, com.intellij.psi.PsiLiteralExpression.class);
        if (literal != null) {
            var annotation = PsiTreeUtil.getParentOfType(literal, PsiAnnotation.class);
            if (annotation != null
                    && Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN.equals(annotation.getQualifiedName())) {
                return true;
            }
        }
        // Kotlin path: inside a KtStringTemplateExpression that is the value of @ComponentView
        try {
            var ktStr = PsiTreeUtil.getParentOfType(element,
                    org.jetbrains.kotlin.psi.KtStringTemplateExpression.class);
            if (ktStr != null) {
                var annotEntry = PsiTreeUtil.getParentOfType(ktStr,
                        org.jetbrains.kotlin.psi.KtAnnotationEntry.class);
                if (annotEntry != null) {
                    var shortName = annotEntry.getShortName();
                    return shortName != null && "ComponentView".equals(shortName.getIdentifier());
                }
            }
        } catch (NoClassDefFoundError ignored) {}
        return false;
    }

    // -----------------------------------------------------------------------
    // Conflict / duplicate checks
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if {@code javaFile} already has a code import that either
     * exactly matches {@code fqn} or covers it with a wildcard, making a new import
     * for the same class redundant.
     */
    static boolean javaFileAlreadyImports(@NotNull String fqn, @NotNull PsiJavaFile javaFile) {
        PsiImportList list = javaFile.getImportList();
        if (list == null) return false;
        if (list.findSingleClassImportStatement(fqn) != null) return true;
        int dot = fqn.lastIndexOf('.');
        return dot > 0 && list.findOnDemandImportStatement(fqn.substring(0, dot)) != null;
    }

    /**
     * Returns {@code true} if {@code javaFile} has an import for a class whose
     * simple name matches that of {@code fqn} but whose FQN is different.
     *
     * <p>Such a conflict prevents adding a new import because it would introduce
     * an ambiguous simple name.
     */
    static boolean javaFileHasConflictingSimpleName(@NotNull String fqn, @NotNull PsiJavaFile javaFile) {
        PsiImportList list = javaFile.getImportList();
        if (list == null) return false;
        String simpleName = Fxml2ImportUtil.simpleNameOf(fqn);
        for (PsiImportStatement imp : list.getImportStatements()) {
            if (imp instanceof PsiImportStaticStatement) continue;
            if (imp.isOnDemand()) continue;
            String existingFqn = imp.getQualifiedName();
            if (existingFqn == null) continue;
            String existingSimple = Fxml2ImportUtil.simpleNameOf(existingFqn);
            if (simpleName.equals(existingSimple) && !fqn.equals(existingFqn)) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if {@code ktFile} already imports {@code fqn} (exactly or
     * via a wildcard).
     *
     * <p>Always returns {@code false} when the Kotlin plugin is not available.
     */
    static boolean kotlinFileAlreadyImports(@NotNull String fqn, @NotNull PsiFile ktFile) {
        try {
            if (!(ktFile instanceof org.jetbrains.kotlin.psi.KtFile kf)) return false;
            for (org.jetbrains.kotlin.psi.KtImportDirective imp : kf.getImportDirectives()) {
                if (!imp.isValidImport()) continue;
                var name = imp.getImportedFqName();
                if (name == null) continue;
                String nameStr = name.asString();
                if (!imp.isAllUnder() && fqn.equals(nameStr)) return true;
                if (imp.isAllUnder()) {
                    int dot = fqn.lastIndexOf('.');
                    if (dot > 0 && nameStr.equals(fqn.substring(0, dot))) return true;
                }
            }
        } catch (NoClassDefFoundError ignored) {}
        return false;
    }

    /**
     * Returns {@code true} if {@code ktFile} has an import for a different class that
     * shares the same simple name as the class identified by {@code fqn}.
     *
     * <p>Always returns {@code false} when the Kotlin plugin is not available.
     */
    static boolean kotlinFileHasConflictingSimpleName(@NotNull String fqn, @NotNull PsiFile ktFile) {
        try {
            if (!(ktFile instanceof org.jetbrains.kotlin.psi.KtFile kf)) return false;
            String simpleName = Fxml2ImportUtil.simpleNameOf(fqn);
            for (org.jetbrains.kotlin.psi.KtImportDirective imp : kf.getImportDirectives()) {
                if (!imp.isValidImport() || imp.isAllUnder()) continue;
                if (imp.getAliasName() != null) continue;
                var name = imp.getImportedFqName();
                if (name == null) continue;
                String nameStr = name.asString();
                String existingSimple = Fxml2ImportUtil.simpleNameOf(nameStr);
                if (simpleName.equals(existingSimple) && !fqn.equals(nameStr)) return true;
            }
        } catch (NoClassDefFoundError ignored) {}
        return false;
    }

    /**
     * Returns {@code true} if the embedded FXML2 file already contains a
     * {@code <?import?>} PI for the exact fully-qualified name {@code fqn},
     * or if the raw annotation text already contains {@code <?import fqn?>}
     * when the injection is not yet available.
     */
    static boolean markupAlreadyContainsImport(
            @NotNull PsiClass markupClass, @NotNull String fqn) {
        // Precise check using the injected XML file when available.
        XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(markupClass);
        if (xmlFile != null) {
            return Fxml2ImportResolver.parseImportsFromWrapperRoot(xmlFile).contains(fqn);
        }
        // Fallback: scan the raw annotation text.
        PsiAnnotation annotation = markupClass.getAnnotation(Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN);
        if (annotation == null) return false;
        String markup = Fxml2EmbedMarkupUtil.extractMarkupFromJavaAnnotation(annotation);
        return markup != null && markup.contains("<?import " + fqn + "?>");
    }

    // -----------------------------------------------------------------------
    // Markup import insertion
    // -----------------------------------------------------------------------

    /**
     * Computes the host-document insertion point for a {@code <?import fqn?>} PI without
     * modifying any document.  Returns {@code null} if the insertion cannot be determined.
     */
    private static @Nullable MarkupInsertPoint computeMarkupInsertPoint(
            @NotNull XmlFile embeddedXmlFile,
            @NotNull String fqn,
            @NotNull Project project) {
        var doc = embeddedXmlFile.getDocument();
        if (doc == null) return null;
        XmlTag wrapperRoot = doc.getRootTag();
        if (wrapperRoot == null || !Fxml2EmbeddedUtil.isWrapperRoot(wrapperRoot)) return null;

        InjectedLanguageManager ilm = InjectedLanguageManager.getInstance(project);
        PsiLanguageInjectionHost host = ilm.getInjectionHost(embeddedXmlFile.getViewProvider());
        if (host == null) return null;

        PsiFile hostFile = host.getContainingFile();
        Document hostDoc = PsiDocumentManager.getInstance(project).getDocument(hostFile);
        if (hostDoc == null) return null;

        // Determine the insertion point in the injected document:
        // after the last existing <?import?> PI, or just after the wrapper root opening tag.
        XmlProcessingInstruction lastImportPi = null;
        for (PsiElement child : wrapperRoot.getChildren()) {
            if (child instanceof XmlTag) break;
            if (child instanceof XmlProcessingInstruction pi
                    && Fxml2ImportResolver.getImportTarget(pi) != null) {
                lastImportPi = pi;
            }
        }

        // The injected TextRange we want to map to the host document.
        TextRange injectedInsertRange;
        if (lastImportPi != null) {
            // Insert after the end of the last <?import?> PI.
            injectedInsertRange = new TextRange(
                    lastImportPi.getTextRange().getEndOffset(),
                    lastImportPi.getTextRange().getEndOffset());
        } else {
            // No existing imports: insert at the very start of the wrapper root's content.
            // The content starts right after the closing '>' of the wrapper root opening tag.
            // Skip the '\n' that immediately follows '>' so that the insert point lands on
            // the first real content line: this gives computeIndentAtOffset the correct
            // indentation to measure and avoids a spurious blank line before the PI.
            String rootText = wrapperRoot.getText();
            int gtPos = rootText.indexOf('>');
            if (gtPos < 0) return null;
            int startOfContent = wrapperRoot.getTextRange().getStartOffset() + gtPos + 1;
            if (gtPos + 1 < rootText.length() && rootText.charAt(gtPos + 1) == '\n') {
                startOfContent++;
            }
            injectedInsertRange = new TextRange(startOfContent, startOfContent);
        }

        // Map from injected coordinates to host-document coordinates.
        TextRange hostRange = ilm.injectedToHost(wrapperRoot, injectedInsertRange);
        int hostInsertOffset = hostRange.getStartOffset();

        // Determine indentation.
        // - After an existing PI: the insert point is in the MIDDLE of a line, so scan backward
        //   to find the line start and measure leading whitespace from there.
        // - Before the first content line: the insert point is at the START of that line
        //   (right after a '\n'), so scanning backward would land on the previous line and
        //   return wrong indentation. Instead, scan forward to read the indent directly.
        String indent = (lastImportPi != null)
                ? computeIndentAtOffset(hostDoc, hostInsertOffset)
                : readIndentStartingAt(hostDoc, hostInsertOffset);
        String piText = indent + "<?import " + fqn + "?>";
        // When inserting after an existing PI, prepend a newline (the PI text has no trailing newline).
        // When inserting before the first content line, append a newline so the existing content
        // is pushed down rather than appended onto the same line.
        String insertText = (lastImportPi != null) ? "\n" + piText : piText + "\n";
        return new MarkupInsertPoint(hostDoc, hostInsertOffset, insertText);
    }

    private record MarkupInsertPoint(@NotNull Document doc, int offset, @NotNull String text) {
        void apply() { doc.insertString(offset, text); }
    }

    /**
     * Computes the leading-whitespace indentation that applies at {@code offset}
     * in {@code doc} by scanning backward to the nearest newline.
     * Use this when the offset is in the middle of a line (e.g. after an existing PI).
     */
    private static @NotNull String computeIndentAtOffset(@NotNull Document doc, int offset) {
        String text = doc.getText();
        // Scan backward to find the start of the current line.
        int lineStart = offset;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        // Count leading spaces/tabs on that line.
        int ind = lineStart;
        while (ind < offset && (text.charAt(ind) == ' ' || text.charAt(ind) == '\t')) {
            ind++;
        }
        return text.substring(lineStart, ind);
    }

    /**
     * Reads the leading-whitespace indentation starting at {@code offset} in {@code doc}
     * by scanning forward over spaces and tabs.
     * Use this when the offset is at the very start of a line (right after a newline),
     * where {@link #computeIndentAtOffset} would return an empty string.
     */
    private static @NotNull String readIndentStartingAt(@NotNull Document doc, int offset) {
        String text = doc.getText();
        int end = offset;
        while (end < text.length() && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) {
            end++;
        }
        return text.substring(offset, end);
    }

    // -----------------------------------------------------------------------
    // Markup import deletion
    // -----------------------------------------------------------------------

    /**
     * Removes {@code pi} ({@code <?import?>}) from embedded FXML2 markup by deleting
     * the corresponding source range (including the leading indentation and trailing
     * newline) from the host Java or Kotlin document.
     *
     * <p><strong>Must be called from within a write action.</strong>
     */
    static void deleteMarkupImport(
            @NotNull XmlProcessingInstruction pi,
            @NotNull Project project) {

        InjectedLanguageManager ilm = InjectedLanguageManager.getInstance(project);
        PsiFile injectedFile = pi.getContainingFile();
        PsiLanguageInjectionHost host = ilm.getInjectionHost(injectedFile.getViewProvider());
        if (host == null) {
            // Not in an injected file; fall back to direct PSI deletion.
            pi.delete();
            return;
        }

        // Map the PI's range from injected to host document coordinates.
        TextRange injectedRange = pi.getTextRange();
        TextRange hostRange = ilm.injectedToHost(pi, injectedRange);

        PsiFile hostFile = host.getContainingFile();
        Document hostDoc = PsiDocumentManager.getInstance(project).getDocument(hostFile);
        if (hostDoc == null) return;

        String hostText = hostDoc.getText();
        int start = hostRange.getStartOffset();
        int end = hostRange.getEndOffset();

        // Expand backward to include leading whitespace (indentation of the PI line).
        int lineStart = start;
        while (lineStart > 0 && hostText.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        if (hostText.substring(lineStart, start).isBlank()) {
            start = lineStart;
        }

        // Expand forward to include the trailing newline.
        if (end < hostText.length() && hostText.charAt(end) == '\n') {
            end++;
        }

        hostDoc.deleteString(start, end);
    }

    // -----------------------------------------------------------------------
    // Helpers used by both inspections
    // -----------------------------------------------------------------------

    /**
     * Returns all {@code @ComponentView}-annotated {@link PsiClass} objects in
     * {@code javaFile}, including inner/nested classes.
     */
    static @NotNull List<PsiClass> getMarkupClassesInJavaFile(@NotNull PsiJavaFile javaFile) {
        List<PsiClass> result = new ArrayList<>();
        for (PsiClass cls : javaFile.getClasses()) {
            collectMarkupClasses(cls, result);
        }
        return result;
    }

    private static void collectMarkupClasses(@NotNull PsiClass cls, @NotNull List<PsiClass> out) {
        if (cls.hasAnnotation(Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN)) {
            out.add(cls);
        }
        for (PsiClass inner : cls.getInnerClasses()) {
            collectMarkupClasses(inner, out);
        }
    }

    /**
     * Returns all {@code @ComponentView}-annotated Kotlin classes in {@code ktFile}.
     * Returns an empty list when the Kotlin plugin is unavailable.
     */
    static @NotNull List<PsiClass> getMarkupClassesInKotlinFile(@NotNull PsiFile ktFile) {
        try {
            if (!(ktFile instanceof org.jetbrains.kotlin.psi.KtFile kf)) return List.of();
            List<PsiClass> result = new ArrayList<>();
            for (org.jetbrains.kotlin.psi.KtClassOrObject ktClass :
                    PsiTreeUtil.findChildrenOfType(kf, org.jetbrains.kotlin.psi.KtClassOrObject.class)) {
                boolean hasAnnotation = ktClass.getAnnotationEntries().stream().anyMatch(e -> {
                    var sn = e.getShortName();
                    return sn != null && "ComponentView".equals(sn.getIdentifier());
                });
                if (!hasAnnotation) continue;
                var lightClass = org.jetbrains.kotlin.asJava.KotlinAsJavaSupport.getInstance(kf.getProject())
                        .getLightClass(ktClass);
                if (lightClass != null) result.add(lightClass);
            }
            return result;
        } catch (NoClassDefFoundError ignored) {
            return List.of();
        }
    }

    /**
     * Returns the FQN declared by {@code importStmt}.
     * For wildcard imports returns the package name WITHOUT the trailing {@code .*}.
     */
    static @Nullable String getFqn(@NotNull PsiImportStatement importStmt) {
        return importStmt.getQualifiedName();
    }

    // -----------------------------------------------------------------------
    // Preference inspection checks
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when the {@code Fxml2PreferMarkupImportInspection} is
     * currently enabled in the project's active inspection profile.
     */
    public static boolean isPreferMarkupImportEnabled(@NotNull Project project) {
        return isInspectionEnabled(project, Fxml2PreferMarkupImportInspection.SHORT_NAME);
    }

    /**
     * Returns {@code true} when the {@code Fxml2PreferCodeImportInspection} is
     * currently enabled in the project's active inspection profile.
     */
    public static boolean isPreferCodeImportEnabled(@NotNull Project project) {
        return isInspectionEnabled(project, Fxml2PreferCodeImportInspection.SHORT_NAME);
    }

    private static boolean isInspectionEnabled(@NotNull Project project, @NotNull String shortName) {
        HighlightDisplayKey key = HighlightDisplayKey.find(shortName);
        if (key == null) return false;
        InspectionProfile profile =
                InspectionProjectProfileManager.getInstance(project).getCurrentProfile();
        return profile.isToolEnabled(key);
    }

    // -----------------------------------------------------------------------
    // Batch preference helpers (used by optimizer and refactoring helper)
    // -----------------------------------------------------------------------

    /**
     * Inserts {@code <?import fqn?>} into every markup block in {@code markupClasses}
     * that does not already contain an import for {@code fqn}.
     *
     * <p><strong>Must be called from within a write action.</strong>
     */
    public static void insertFqnIntoAllMarkupBlocks(
            @NotNull List<PsiClass> markupClasses,
            @NotNull String fqn,
            @NotNull Project project) {
        // Collect all insertion points BEFORE modifying any document: direct document writes
        // (insertString) leave PSI uncommitted, which would cause subsequent injection lookups
        // for other markup classes to fail.  Computing all points first avoids this.
        List<MarkupInsertPoint> pts = new ArrayList<>();
        for (PsiClass cls : markupClasses) {
            if (markupAlreadyContainsImport(cls, fqn)) continue;
            XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(cls);
            if (xmlFile == null) continue;
            MarkupInsertPoint pt = computeMarkupInsertPoint(xmlFile, fqn, project);
            if (pt != null) pts.add(pt);
        }
        // Apply in descending offset order so bottom-of-file insertions don't shift the
        // host-document offsets of insertions that appear earlier in the file.
        pts.sort((a, b) -> Integer.compare(b.offset(), a.offset()));
        for (MarkupInsertPoint pt : pts) pt.apply();
    }

    /**
     * Moves every non-wildcard {@code <?import fqn?>} PI from the embedded markup of
     * each class in {@code markupClasses} to the host {@code javaFile} as a regular code
     * import, then removes the PI from the markup.
     *
     * <p>Skips imports that already exist as code imports, or that would introduce a
     * simple-name conflict.
     *
     * <p><strong>Must be called from within a write action.</strong>
     */
    public static void moveAllMarkupImportsToJavaCode(
            @NotNull PsiJavaFile javaFile,
            @NotNull List<PsiClass> markupClasses,
            @NotNull Project project) {

        PsiDocumentManager.getInstance(project).commitAllDocuments();

        for (PsiClass cls : markupClasses) {
            applyPreferCodeForClass(javaFile, cls, project);
        }
    }

    /**
     * Moves every non-wildcard {@code <?import fqn?>} PI from the embedded markup of
     * each class in {@code markupClasses} to the host Kotlin {@code ktFile} as a regular
     * import directive, then removes the PI from the markup.
     *
     * <p><strong>Must be called from within a write action.</strong>
     */
    public static void moveAllMarkupImportsToKotlinCode(
            @NotNull PsiFile ktFile,
            @NotNull List<PsiClass> markupClasses,
            @NotNull Project project) {

        PsiDocumentManager.getInstance(project).commitAllDocuments();

        for (PsiClass cls : markupClasses) {
            applyPreferCodeForClassKotlin(ktFile, cls, project);
        }
    }

    private static void applyPreferCodeForClass(
            @NotNull PsiJavaFile javaFile,
            @NotNull PsiClass cls,
            @NotNull Project project) {

        XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(cls);
        if (xmlFile == null) return;
        var doc = xmlFile.getDocument();
        if (doc == null) return;
        var wrapperRoot = doc.getRootTag();
        if (wrapperRoot == null) return;

        // Snapshot the FQNs to process (avoid ConcurrentModification while deleting PIs).
        List<String> fqnsToMove = new ArrayList<>();
        for (PsiElement child : wrapperRoot.getChildren()) {
            if (child instanceof XmlTag) break;
            if (!(child instanceof XmlProcessingInstruction pi)) continue;
            String target = Fxml2ImportResolver.getImportTarget(pi);
            if (target == null) continue;
            target = target.trim();
            if (!target.endsWith(".*")) fqnsToMove.add(target);
        }

        for (String fqn : fqnsToMove) {
            if (javaFileHasConflictingSimpleName(fqn, javaFile)) continue;

            if (!javaFileAlreadyImports(fqn, javaFile)) {
                Fxml2EmbedMarkupUtil.addJavaImport(project, javaFile, fqn);
                // Flush postponed PSI changes so injected offsets stay valid.
                Document hostDoc =
                        PsiDocumentManager.getInstance(project).getDocument(javaFile);
                if (hostDoc != null) {
                    PsiDocumentManager.getInstance(project)
                            .doPostponedOperationsAndUnblockDocument(hostDoc);
                }
            }

            // Re-fetch after host-document modification (offsets shifted).
            xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(cls);
            if (xmlFile == null) break;
            doc = xmlFile.getDocument();
            if (doc == null) break;
            wrapperRoot = doc.getRootTag();
            if (wrapperRoot == null) break;

            for (PsiElement child : wrapperRoot.getChildren()) {
                if (child instanceof XmlTag) break;
                if (!(child instanceof XmlProcessingInstruction pi)) continue;
                String piTarget = Fxml2ImportResolver.getImportTarget(pi);
                if (piTarget != null && fqn.equals(piTarget.trim())) {
                    deleteMarkupImport(pi, project);
                    break;
                }
            }
        }
    }

    private static void applyPreferCodeForClassKotlin(
            @NotNull PsiFile ktFile,
            @NotNull PsiClass cls,
            @NotNull Project project) {

        XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(cls);
        if (xmlFile == null) return;
        var doc = xmlFile.getDocument();
        if (doc == null) return;
        var wrapperRoot = doc.getRootTag();
        if (wrapperRoot == null) return;

        List<String> fqnsToMove = new ArrayList<>();
        for (PsiElement child : wrapperRoot.getChildren()) {
            if (child instanceof XmlTag) break;
            if (!(child instanceof XmlProcessingInstruction pi)) continue;
            String target = Fxml2ImportResolver.getImportTarget(pi);
            if (target == null) continue;
            target = target.trim();
            if (!target.endsWith(".*")) fqnsToMove.add(target);
        }

        for (String fqn : fqnsToMove) {
            if (kotlinFileHasConflictingSimpleName(fqn, ktFile)) continue;

            if (!kotlinFileAlreadyImports(fqn, ktFile)) {
                Fxml2EmbedMarkupUtil.addKotlinImport(project, ktFile, fqn);
                Document hostDoc =
                        PsiDocumentManager.getInstance(project).getDocument(ktFile);
                if (hostDoc != null) {
                    PsiDocumentManager.getInstance(project)
                            .doPostponedOperationsAndUnblockDocument(hostDoc);
                }
            }

            // Re-fetch after host-document modification.
            xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(cls);
            if (xmlFile == null) break;
            doc = xmlFile.getDocument();
            if (doc == null) break;
            wrapperRoot = doc.getRootTag();
            if (wrapperRoot == null) break;

            for (PsiElement child : wrapperRoot.getChildren()) {
                if (child instanceof XmlTag) break;
                if (!(child instanceof XmlProcessingInstruction pi)) continue;
                String piTarget = Fxml2ImportResolver.getImportTarget(pi);
                if (piTarget != null && fqn.equals(piTarget.trim())) {
                    deleteMarkupImport(pi, project);
                    break;
                }
            }
        }
    }
}
