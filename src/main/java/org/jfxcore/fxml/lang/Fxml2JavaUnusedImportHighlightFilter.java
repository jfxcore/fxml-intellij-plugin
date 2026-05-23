// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiImportStaticStatement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@link HighlightInfoFilter} that suppresses false-positive "Unused import statement"
 * warnings in Java files that contain classes annotated with
 * {@code @org.jfxcore.markup.ComponentView}.
 *
 * <h2>Problem</h2>
 * IntelliJ's built-in Java highlighting (via {@code UnusedImportsVisitor}) analyzes only
 * the Java source to decide which imports are referenced.  It does <em>not</em> understand
 * that class names used inside the FXML2 markup string (the {@code @ComponentView}
 * annotation value) also constitute "uses" of those imports.  As a result, imports such as
 * {@code import javafx.scene.layout.*} are highlighted as "Unused import statement" even
 * though they are required to resolve the class tags inside the embedded markup.
 *
 * <h2>Solution</h2>
 * This filter intercepts every {@link HighlightInfo} before it is added to the editor's
 * highlight buffer.  For each highlight that covers a complete {@link PsiImportStatement}
 * in a Java file:
 * <ol>
 *   <li>It checks whether any class in that file carries {@code @ComponentView}.</li>
 *   <li>If so, it retrieves the injected FXML2 {@link com.intellij.psi.xml.XmlFile} and checks via
 *       {@link Fxml2ImportUtil#isImportNeededByXmlFile}: whether the import is
 *       needed to resolve a name used in the markup.</li>
 *   <li>If the import is needed it returns {@code false} (suppress), otherwise {@code true}
 *       (allow).</li>
 * </ol>
 *
 * <h2>Identification of highlights to suppress</h2>
 * The filter recognizes two kinds of import-related highlights without relying on
 * internal IntelliJ APIs:
 * <ul>
 *   <li><strong>Unused-import highlight</strong> ({@code UNUSED_IMPORT}): range exactly
 *       matches a {@link PsiImportStatement}.  Compile errors such as "cannot resolve
 *       symbol" use a sub-range, so they are never matched by mistake.  Static imports
 *       are excluded.  Suppressed for any import that is actually needed by FXML2
 *       markup.</li>
 *   <li><strong>Missorted-imports highlight</strong> ({@code MISSORTED_IMPORTS}): range
 *       exactly matches the {@link PsiImportList} (the entire import section).
 *       {@code UnusedImportsVisitor} creates this highlight whenever it marks at least
 *       one import as redundant from a Java perspective, and attaches an
 *       {@code OptimizeImportsFix} that calls {@code JavaImportOptimizer} directly,
 *       bypassing the {@code Fxml2EmbeddedJavaImportOptimizer} extension.  For
 *       {@code @ComponentView} files that fix would incorrectly remove FXML2-needed
 *       imports.  The correct optimizer is available via Ctrl+Alt+O or the
 *       {@code Fxml2OptimizeImportsAction} intention.</li>
 * </ul>
 *
 * <p>Registered via {@code daemon.highlightInfoFilter} in {@code plugin.xml} so it is
 * always active for Java files; it is a no-op on files without {@code @ComponentView}.
 *
 * <p>This is the Java counterpart of {@link Fxml2KotlinUnusedImportSuppressor} which
 * handles the same scenario for Kotlin files.
 */
public final class Fxml2JavaUnusedImportHighlightFilter implements HighlightInfoFilter {

    @Override
    public boolean accept(@NotNull HighlightInfo info, @Nullable PsiFile psiFile) {
        // Don't suppress WEAK_WARNING highlights: those come from local preference
        // inspections (e.g. Fxml2PreferMarkupImportInspection), not from the
        // built-in unused-import daemon which produces WARNING-level highlights.
        if (info.getSeverity().compareTo(HighlightSeverity.WARNING) < 0) return true;

        if (!(psiFile instanceof PsiJavaFile javaFile)) return true;

        int startOffset = info.getStartOffset();
        PsiElement element = javaFile.findElementAt(startOffset);
        if (element == null) return true;

        // -- Import-list-level highlight (MISSORTED_IMPORTS) ----------------
        // UnusedImportsVisitor creates a MISSORTED_IMPORTS highlight with
        // .range(importList), covering the entire import section, whenever it
        // considers at least one import redundant. Its attached OptimizeImportsFix
        // calls JavaImportOptimizer directly and would remove FXML2-needed imports
        // in @ComponentView files. Suppress it; Ctrl+Alt+O and Fxml2OptimizeImportsAction
        // provide the correct optimizer for these files.
        PsiImportList importList = javaFile.getImportList();
        if (importList != null) {
            TextRange listRange = importList.getTextRange();
            if (info.getStartOffset() == listRange.getStartOffset()
                    && info.getEndOffset() == listRange.getEndOffset()) {
                for (PsiClass cls : javaFile.getClasses()) {
                    if (cls.hasAnnotation(Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN)) {
                        return false;
                    }
                }
                return true;
            }
        }

        // -- Individual import statement highlight (UNUSED_IMPORT) ----------
        PsiImportStatement importStmt =
                PsiTreeUtil.getParentOfType(element, PsiImportStatement.class, false);
        if (importStmt == null) return true;

        // Skip static imports: FXML2 markup never references static members by import.
        if (importStmt instanceof PsiImportStaticStatement) return true;

        // Only suppress highlights that cover the ENTIRE import statement.
        // Compile errors (e.g. "cannot resolve symbol") highlight a sub-range.
        TextRange stmtRange = importStmt.getTextRange();
        if (info.getStartOffset() != stmtRange.getStartOffset()
                || info.getEndOffset() != stmtRange.getEndOffset()) return true;

        return !Fxml2ImportUtil.isImportNeededInJavaFile(importStmt, javaFile);
    }
}
