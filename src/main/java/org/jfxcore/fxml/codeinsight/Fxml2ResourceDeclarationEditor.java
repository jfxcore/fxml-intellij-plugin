package org.jfxcore.fxml.codeinsight;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2TextSpan;
import org.jfxcore.fxml.resource.Fxml2ResourceEntry;
import org.jfxcore.fxml.resource.Fxml2ResourceModel;

/**
 * Rewrites part of a {@code <?resource ?>} declaration in place.
 *
 * <p>Quick fixes and intentions that change a declaration all face the same two problems.  First,
 * the declaration may live in either document form, so there is no single PSI shape to edit: in a
 * standalone document it is a processing instruction, and in embedded markup it is a stretch of
 * text inside a Java or Kotlin string literal.  Editing the declaring file's document by offset
 * works for both, and is what this class does.
 *
 * <p>Second, a fix runs later than the inspection that offered it, by which time the offsets the
 * inspection saw may have moved.  A fix therefore identifies its declaration by name and looks it
 * up again through {@link Fxml2ResourceModel} at the moment it is applied, rather than
 * remembering an offset.
 */
public final class Fxml2ResourceDeclarationEditor {

    private Fxml2ResourceDeclarationEditor() {}

    /**
     * Returns the declaration named {@code name} in the FXML/2 document {@code context} belongs
     * to, or {@code null} when the document no longer declares it.
     */
    public static @Nullable Fxml2ResourceEntry findDeclaration(@NotNull PsiElement context, @NotNull String name) {
        PsiFile file = context.getContainingFile();
        if (!(file instanceof XmlFile xmlFile)) return null;

        return Fxml2ResourceModel.of(xmlFile).entries().stream()
                .filter(entry -> entry.name().value().equals(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * Replaces the text of {@code span}, which is relative to {@code entry}'s anchor, with
     * {@code replacement}.
     *
     * @return {@code true} when the replacement was applied
     */
    public static boolean replace(@NotNull Project project,
                                  @NotNull Fxml2ResourceEntry entry,
                                  @NotNull Fxml2TextSpan span,
                                  @NotNull String replacement) {
        PsiFile file = entry.declaringFile();
        PsiDocumentManager documentManager = PsiDocumentManager.getInstance(project);

        Document document = documentManager.getDocument(file);
        if (document == null) return false;

        // An edit made through PSI earlier in the same refactoring leaves the document locked
        // until those changes are written back.  Writing by offset into a locked document would
        // apply the replacement to text the document does not have yet.
        documentManager.doPostponedOperationsAndUnblockDocument(document);

        var range = entry.fileRangeOf(span);
        if (range.getEndOffset() > document.getTextLength()) return false;

        document.replaceString(range.getStartOffset(), range.getEndOffset(), replacement);
        documentManager.commitDocument(document);
        return true;
    }
}
