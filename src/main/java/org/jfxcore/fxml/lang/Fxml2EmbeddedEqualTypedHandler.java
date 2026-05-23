// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.application.options.editor.WebEditorOptions;
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlToken;
import com.intellij.psi.xml.XmlTokenType;
import org.jetbrains.annotations.NotNull;

/**
 * Intercepts {@code =} typed after an XML attribute name inside embedded FXML2 markup
 * (a {@code @ComponentView} annotation text block) and inserts double quotes
 * ({@code =""}) with the caret positioned between them.
 *
 * <h2>Problem</h2>
 * <p>IntelliJ's built-in {@code XmlEqTypedHandler} chooses the quote character via
 * {@code file.getContext().getText()}, when that text starts with {@code '"'} it uses
 * single quotes ({@code =''}). For embedded FXML2 the injection host is a Java
 * text-block literal whose text begins with {@code '"""'}, so the first character is
 * {@code '"'}: causing the handler to (incorrectly) select single quotes.
 *
 * <h2>Fix</h2>
 * <p>This {@link TypedHandlerDelegate} runs <em>before</em> the default handler.  When
 * {@code =} is typed in an embedded FXML2 XML attribute-name position it:
 * <ol>
 *   <li>inserts {@code =""} and places the caret between the quotes;</li>
 *   <li>commits the document so the host Java PSI is up-to-date before inspections
 *       fire (omitting this allows the Java stub index to observe a stale file size,
 *       which causes a "PSI and index do not match" SEVERE);</li>
 *   <li>records the caret position in {@link #QUOTE_INSERTED_AT} caret user-data;</li>
 *   <li>returns {@link Result#STOP} so the default handler is skipped.</li>
 * </ol>
 * <p>When the <em>next</em> character typed is {@code "} and the caret is still at the
 * recorded position, the handler returns {@link Result#STOP} again: consuming that
 * quote so the user does not end up with {@code ="""}. This mirrors the technique used
 * by {@code XmlEqTypedHandler} in the IntelliJ platform.
 */
public final class Fxml2EmbeddedEqualTypedHandler extends TypedHandlerDelegate {

    /**
     * Caret user-data key that records the offset (between the two auto-inserted quotes)
     * where the closing {@code "} should be swallowed if the user types it immediately
     * after {@code =} was processed.
     */
    private static final Key<Integer> QUOTE_INSERTED_AT =
            Key.create("fxml2.eq-handler.inserted-quote-at");

    @Override
    public @NotNull Result beforeCharTyped(
            char c,
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull PsiFile file,
            @NotNull FileType fileType) {

        Caret caret = editor.getCaretModel().getCurrentCaret();

        // -- Swallow the closing " the user types after auto-inserted "" ------------------
        // If we previously inserted ="" and stored the between-quotes offset, and the user
        // now types " with the caret still at that offset, consume the keystroke so they
        // don't end up with ="""  (mirrors XmlEqTypedHandler.QUOTE_INSERTED_AT logic).
        Integer quoteInsertedAt = caret.getUserData(QUOTE_INSERTED_AT);
        caret.putUserData(QUOTE_INSERTED_AT, null);   // always clear after one keystroke
        if (c == '"' && quoteInsertedAt != null && quoteInsertedAt == caret.getOffset()) {
            return Result.STOP;
        }

        if (c != '=') return Result.CONTINUE;

        // Only act inside embedded FXML2 XML files.
        if (!(file instanceof XmlFile xmlFile)) return Result.CONTINUE;
        if (!Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile)) return Result.CONTINUE;

        // Respect the "Insert quotes for attribute value" preference.
        if (!WebEditorOptions.getInstance().isInsertQuotesForAttributeValue()) return Result.CONTINUE;

        // Check that the caret is on an XML attribute-name token.
        PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument());
        int offset = editor.getCaretModel().getOffset();
        PsiElement element = file.findElementAt(offset - 1);
        if (!(element instanceof XmlToken token)) return Result.CONTINUE;
        if (token.getTokenType() != XmlTokenType.XML_NAME) return Result.CONTINUE;
        // Make sure the parent is an XmlAttribute (not a tag name).
        if (!(token.getParent() instanceof XmlAttribute)) return Result.CONTINUE;

        // Insert ="" and place caret between the quotes.
        Document document = editor.getDocument();
        document.insertString(offset, "=\"\"");
        int caretBetweenQuotes = offset + 2;
        editor.getCaretModel().moveToOffset(caretBetweenQuotes);

        // Commit the document so the host Java PSI is updated before inspections fire.
        // Without this, the Java stub index observes a stale file size, leading to
        // a "PSI and index do not match" SEVERE in the IDE log.
        PsiDocumentManager.getInstance(project).commitDocument(document);

        // Record the between-quotes position so the next " typed by the user is swallowed.
        caret.putUserData(QUOTE_INSERTED_AT, caretBetweenQuotes);

        return Result.STOP;
    }
}
