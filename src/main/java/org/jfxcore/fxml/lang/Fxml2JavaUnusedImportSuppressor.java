// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.codeInspection.InspectionSuppressor;
import com.intellij.codeInspection.SuppressQuickFix;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiImportStaticStatement;
import com.intellij.psi.PsiJavaFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@link InspectionSuppressor} for the Java {@code UNUSED_IMPORT} inspection
 * ({@link com.intellij.codeInspection.unusedImport.UnusedImportInspection}).
 *
 * <h2>Problem</h2>
 * IntelliJ's "Inspect Code" action runs the {@code UnusedImportInspection} which analyzes
 * only the Java source to decide which imports are referenced.  It does <em>not</em>
 * understand that class names used inside the FXML2 markup string (the
 * {@code @ComponentView} annotation value) also constitute "uses" of those imports.
 * As a result, imports such as {@code import javafx.scene.layout.*} are reported as
 * "Unused import" even though they are required by the embedded markup.
 *
 * <p>This is the companion to {@link Fxml2JavaUnusedImportHighlightFilter}: while that
 * filter suppresses the real-time editor highlight, this suppressor is consulted by the
 * IDE's on-the-fly inspection daemon (e.g. the "Problems" tool window).
 *
 * <h2>Limitation</h2>
 * {@link com.intellij.codeInspection.unusedImport.UnusedImportInspection} is a
 * {@link com.intellij.codeInspection.GlobalSimpleInspectionTool}.  When "Inspect Code"
 * runs it passes {@code filterSuppressed=false}, which means this suppressor is
 * <em>not</em> consulted in batch mode.  The batch case is handled separately by
 * {@link Fxml2InspectionExtensionsFactory}.
 *
 * <h2>Solution</h2>
 * For every {@link PsiImportStatement} element in a Java file that contains a
 * {@code @ComponentView}-annotated class, this suppressor checks: via
 * {@link Fxml2ImportUtil#isImportNeededByXmlFile}: whether the import is needed
 * to resolve a name used in the embedded markup.  If so, it returns {@code true} and the
 * inspection problem is suppressed for real-time highlighting.
 *
 * <p>Registered via {@code lang.inspectionSuppressor language="JAVA"} in
 * {@code plugin.xml}.
 *
 * <p>This is the Java counterpart of {@link Fxml2KotlinUnusedImportSuppressor} which
 * handles the same scenario for Kotlin files.
 */
public final class Fxml2JavaUnusedImportSuppressor implements InspectionSuppressor {

    /**
     * The {@code shortName} of
     * {@link com.intellij.codeInspection.unusedImport.UnusedImportInspection}
     * as declared in its registration.
     */
    private static final String UNUSED_IMPORT_TOOL_ID = "UNUSED_IMPORT";

    // -----------------------------------------------------------------------
    // InspectionSuppressor
    // -----------------------------------------------------------------------

    @Override
    public boolean isSuppressedFor(@NotNull PsiElement element, @NotNull String toolId) {
        if (!UNUSED_IMPORT_TOOL_ID.equals(toolId)) return false;
        if (!(element instanceof PsiImportStatement importStmt)) return false;
        // Static imports are never referenced from FXML2 markup.
        if (importStmt instanceof PsiImportStaticStatement) return false;

        PsiFile containingFile = element.getContainingFile();
        if (!(containingFile instanceof PsiJavaFile javaFile)) return false;

        return Fxml2ImportUtil.isImportNeededInJavaFile(importStmt, javaFile);
    }

    @Override
    public SuppressQuickFix @NotNull [] getSuppressActions(@Nullable PsiElement element, @NotNull String toolId) {
        // We do not offer any quick-fix here: the import is intentionally kept.
        return SuppressQuickFix.EMPTY_ARRAY;
    }
}
