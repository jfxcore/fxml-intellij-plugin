package org.jfxcore.fxml.lang;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.List;

/** One replacement in a document being rewritten after formatting. */
record Fxml2DocumentEdit(@NotNull TextRange range, @NotNull String text) {

    static int apply(@NotNull Document document,
                     @NotNull PsiDocumentManager documentManager,
                     @NotNull List<Fxml2DocumentEdit> edits) {
        if (edits.isEmpty()) return 0;

        documentManager.doPostponedOperationsAndUnblockDocument(document);
        edits.sort(Comparator.comparingInt(edit -> -edit.range().getStartOffset()));

        int delta = 0;
        for (Fxml2DocumentEdit edit : edits) {
            document.replaceString(edit.range().getStartOffset(), edit.range().getEndOffset(), edit.text());
            delta += edit.text().length() - edit.range().getLength();
        }
        documentManager.commitDocument(document);
        return delta;
    }
}
