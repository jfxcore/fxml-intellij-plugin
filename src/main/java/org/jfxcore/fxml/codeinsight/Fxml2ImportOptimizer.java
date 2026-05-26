package org.jfxcore.fxml.codeinsight;

import com.intellij.lang.ImportOptimizer;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.EmptyRunnable;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlProcessingInstruction;
import com.intellij.psi.xml.XmlProlog;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.annotator.Fxml2UnusedImportsInspection;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.lang.Fxml2ImportUtil;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Implements "Optimize Imports" for FXML files:
 * <ol>
 *   <li>Removes {@code <?import?>} PIs whose class is not referenced anywhere in the document.</li>
 *   <li>Sorts the remaining imports strictly alphabetically.</li>
 * </ol>
 *
 * <p>The write phase replaces the import block as a single string in the underlying
 * {@link Document}, which is robust against PSI sibling-manipulation quirks and guarantees
 * that nothing outside the import range (comments, {@code <?xml?>}, root tag) moves.
 *
 * <p>Registered as {@code lang.importOptimizer} for language {@code XML} in {@code plugin.xml},
 * ordered before the built-in XML optimizer so it runs first on FXML files.
 */
public final class Fxml2ImportOptimizer implements ImportOptimizer {

    @Override
    public boolean supports(@NotNull PsiFile file) {
        // Embedded FXML (injected XML) must not be processed here, as its imports are
        // backed by the host Java file and cannot be edited via the injected document.
        // Fxml2EmbeddedJavaImportOptimizer handles Java files with @ComponentView instead.
        if (Fxml2EmbeddedUtil.isEmbeddedFxml2(file)) return false;
        return Fxml2FileType.isFxml2(file);
    }

    @Override
    public @NotNull Runnable processFile(@NotNull PsiFile file) {
        if (!(file instanceof XmlFile xmlFile)) return EmptyRunnable.INSTANCE;
        XmlDocument document = xmlFile.getDocument();
        if (document == null) return EmptyRunnable.INSTANCE;

        // --- Read phase (runs in read action) ---

        XmlProlog prolog = document.getProlog();
        if (prolog == null) return EmptyRunnable.INSTANCE;

        // Collect the import PIs in document order
        List<XmlProcessingInstruction> importPis = new ArrayList<>();
        for (XmlProcessingInstruction pi : PsiTreeUtil.findChildrenOfType(prolog, XmlProcessingInstruction.class)) {
            if (Fxml2ImportResolver.getImportTarget(pi) != null) importPis.add(pi);
        }
        if (importPis.isEmpty()) return EmptyRunnable.INSTANCE;

        // Current import FQNs in document order
        List<String> allImports = new ArrayList<>();
        for (XmlProcessingInstruction pi : importPis) {
            String target = Fxml2ImportResolver.getImportTarget(pi);
            if (target != null) allImports.add(target.strip());
        }

        // Collect used simple names, filter and sort
        Set<String> usedSimpleNames = collectUsedSimpleNames(xmlFile);
        List<String> usedImports = new ArrayList<>();
        for (String imp : allImports) {
            if (isUsed(imp, usedSimpleNames, xmlFile)) usedImports.add(imp);
        }
        Collections.sort(usedImports);

        // No-op if nothing changed
        if (usedImports.equals(allImports)) return EmptyRunnable.INSTANCE;

        // The replacement range: from the start of the first import PI to just after
        // the trailing newlines following the last import PI.
        String fileText = xmlFile.getText();
        int blockStart = importPis.getFirst().getTextRange().getStartOffset();
        int blockEnd   = importPis.getLast().getTextRange().getEndOffset();
        // Consume trailing newlines so we can re-emit the correct blank line
        int scanEnd = blockEnd;
        while (scanEnd < fileText.length() && fileText.charAt(scanEnd) == '\n') scanEnd++;
        final int replaceStart = blockStart;
        final int replaceEnd   = scanEnd;

        // Build replacement: walk the existing block text line-by-line.
        // Non-import lines (comments, blank lines) are preserved verbatim in their
        // original relative positions. Import lines are replaced in sorted order.
        String blockText = fileText.substring(blockStart, blockEnd);
        String[] lines = blockText.split("\n", -1);

        // Walk lines: every import-line slot is replaced with the next sorted import;
        // non-import lines pass through unchanged. Surplus original import slots (removed
        // imports) are simply skipped.
        List<String> result = new ArrayList<>();
        int importIdx = 0;
        for (String line : lines) {
            if (line.trim().startsWith("<?import ")) {
                if (importIdx < usedImports.size()) {
                    result.add("<?import " + usedImports.get(importIdx++) + "?>");
                }
                // removed import; skip slot
            } else {
                result.add(line);
            }
        }
        // Append any remaining sorted imports (only if more sorted than original)
        while (importIdx < usedImports.size()) {
            result.add("<?import " + usedImports.get(importIdx++) + "?>");
        }

        // Remove trailing blank lines from result (we'll re-add exactly one blank line)
        while (!result.isEmpty() && result.getLast().isBlank()) {
            result.removeLast();
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < result.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(result.get(i));
        }
        sb.append('\n').append('\n'); // blank line after import block
        final String replacement = sb.toString();

        // --- Write phase (runs in write action) ---
        return () -> {
            Project project = xmlFile.getProject();
            PsiDocumentManager docManager = PsiDocumentManager.getInstance(project);
            docManager.commitAllDocuments();

            Document doc = docManager.getDocument(xmlFile);
            if (doc == null) return;

            doc.replaceString(replaceStart, replaceEnd, replacement);
            docManager.commitDocument(doc);
        };
    }

    /** Returns {@code true} if the import {@code fqn} (exact or wildcard) is used in the file. */
    private static boolean isUsed(@NotNull String imp, @NotNull Set<String> usedSimpleNames,
                                   @NotNull XmlFile xmlFile) {
        if (imp.endsWith(".*")) {
            String pkg = imp.substring(0, imp.length() - 2);
            return usedSimpleNames.stream()
                    .anyMatch(name -> Fxml2ImportUtil.isNameCoveredByWildcard(name, pkg, xmlFile));
        }
        String simpleName = imp.contains(".")
                ? imp.substring(imp.lastIndexOf('.') + 1)
                : imp;
        return usedSimpleNames.contains(simpleName);
    }

    private static Set<String> collectUsedSimpleNames(@NotNull XmlFile file) {
        return Fxml2UnusedImportsInspection.collectUsedSimpleNames(file);
    }
}
