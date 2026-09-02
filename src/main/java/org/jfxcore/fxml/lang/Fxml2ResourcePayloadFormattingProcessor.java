// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resource.Fxml2ResourceDeclaration;
import org.jfxcore.fxml.resource.Fxml2ResourceInstructionParser;
import org.jfxcore.fxml.resource.Fxml2ResourceParseResult;
import org.jfxcore.fxml.resource.Fxml2ResourcePayloadLanguage;
import org.jfxcore.fxml.resource.Fxml2ResourcePayloadLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Reformats the payload of every {@code <?resource ?>} declaration of an FXML/2 document, once the
 * markup around it has been formatted.
 *
 * <p>A payload is a document of the language its media type names, written inside markup.  Two code
 * styles therefore meet in a declaration, and each governs what it owns: the content is formatted
 * in the code style of the payload language, and where the declaration and its payload sit is
 * decided by the markup indentation, one step in from the declaration for a payload that starts on
 * a line of its own.  This is the same rule that {@link Fxml2PayloadIndent} applies while the
 * payload is being typed, so reformatting confirms the layout that typing produces.
 *
 * <p>The formatter of the enclosing document cannot do this itself: it knows the declaration as a
 * run of markup tokens, and the line breaks inside a payload are content rather than markup
 * whitespace.  {@link Fxml2XmlBlock} therefore keeps the declaration out of its reach entirely,
 * and this processor performs the rewrite afterwards. This also ensures that any payload the IDE
 * cannot format (such as CSS in IntelliJ IDEA Community, or any media type without a backing formatter)
 * remains exactly as originally written.
 *
 * <p>Being a post-format processor is what makes the rewrite reach both forms of markup: a
 * standalone document is formatted directly, and markup embedded in a {@code @ComponentView}
 * annotation value is formatted as an FXML/2 document by
 * {@link Fxml2EmbeddedMarkupFormattingProcessor} before it is written back into the annotation.
 */
public final class Fxml2ResourcePayloadFormattingProcessor implements PostFormatProcessor {

    @Override
    public @NotNull PsiElement processElement(@NotNull PsiElement source, @NotNull CodeStyleSettings settings) {
        return source;
    }

    @Override
    public @NotNull TextRange processText(@NotNull PsiFile source,
                                          @NotNull TextRange rangeToReformat,
                                          @NotNull CodeStyleSettings settings) {

        if (source.getLanguage() != Fxml2Language.INSTANCE) return rangeToReformat;

        Project project = source.getProject();
        PsiDocumentManager documentManager = PsiDocumentManager.getInstance(project);
        Document document = documentManager.getDocument(source);
        if (document == null) return rangeToReformat;

        List<Rewrite> rewrites = collect(source, document, rangeToReformat);
        if (rewrites.isEmpty()) return rangeToReformat;

        documentManager.doPostponedOperationsAndUnblockDocument(document);

        int delta = 0;
        // Back to front, so that the offsets of the rewrites still to come stay valid.
        for (int index = rewrites.size() - 1; index >= 0; --index) {
            Rewrite rewrite = rewrites.get(index);
            document.replaceString(rewrite.range().getStartOffset(), rewrite.range().getEndOffset(), rewrite.payload());
            delta += rewrite.payload().length() - rewrite.range().getLength();
        }

        documentManager.commitDocument(document);
        return TextRange.create(rangeToReformat.getStartOffset(),
                                Math.min(rangeToReformat.getEndOffset() + delta, document.getTextLength()));
    }

    /**
     * Payload rewriting only moves whitespace within a document that is already laid out, so it
     * belongs to the whitespace-only pass as much as to the full one.
     */
    @Override
    public boolean isWhitespaceOnly() {
        return true;
    }

    /** One payload to replace, in the coordinates of the document it is written in. */
    private record Rewrite(@NotNull TextRange range, @NotNull String payload) {}

    /** Collects the payloads of {@code file} that reformatting changes, in document order. */
    private static @NotNull List<Rewrite> collect(@NotNull PsiFile file,
                                                  @NotNull Document document,
                                                  @NotNull TextRange rangeToReformat) {

        Project project = file.getProject();
        VirtualFile contextFile = file.getVirtualFile();
        VirtualFile directory = contextFile != null ? contextFile.getParent() : null;
        Fxml2IndentSteps steps = stepsOf(file, contextFile);
        List<Rewrite> rewrites = new ArrayList<>();

        for (Fxml2ResourceProcessingInstruction instruction :
                PsiTreeUtil.findChildrenOfType(file, Fxml2ResourceProcessingInstruction.class)) {

            String text = instruction.getText();
            Fxml2ResourceParseResult result = Fxml2ResourceInstructionParser.parseAt(text, 0, text.length());
            if (result == null) continue;

            Fxml2ResourceDeclaration declaration = result.declaration();
            if (declaration == null || declaration.payloadSpan().isEmpty()) continue;

            int instructionStart = instruction.getTextRange().getStartOffset();
            TextRange payloadRange = declaration.payloadSpan().toTextRange().shiftRight(instructionStart);
            if (!rangeToReformat.intersects(payloadRange)) continue;

            String rawPayload = declaration.payloadSpan().textOf(text);
            Fxml2ResourcePayloadLayout layout = Fxml2ResourcePayloadLayout.of(rawPayload);
            String content = layout.withoutSeparator(declaration.content());

            Fxml2ResourcePayloadLanguage payloadLanguage = Fxml2ResourcePayloadLanguage.of(declaration);

            String formatted = Fxml2ResourcePayloadFormatter.format(
                    project, payloadLanguage, content, directory, steps.payload(payloadLanguage));
            if (formatted == null) continue;

            String declarationIndent = " ".repeat(columnOf(document, instructionStart));
            String payload = layout.write(formatted, declarationIndent,
                                          declarationIndent + steps.markup().text());
            if (!payload.equals(rawPayload)) {
                rewrites.add(new Rewrite(payloadRange, payload));
            }
        }

        return rewrites;
    }

    /**
     * Returns the steps {@code file} is written in.
     *
     * <p>A document being formatted on behalf of an annotation value carries them, resolved before
     * the reformat installed its own code style settings; a standalone document is formatted with
     * the settings of the file itself, so its steps can be resolved here.
     */
    private static @NotNull Fxml2IndentSteps stepsOf(@NotNull PsiFile file, @Nullable VirtualFile contextFile) {
        Fxml2IndentSteps carried = contextFile != null ? contextFile.getUserData(Fxml2IndentSteps.KEY) : null;
        return carried != null
                ? carried
                : Fxml2EffectiveIndent.stepsFor(file.getProject(), contextFile, file.getText());
    }

    /** Returns the column {@code offset} sits at, which is the indentation a declaration starts at. */
    private static int columnOf(@NotNull Document document, int offset) {
        return offset - document.getLineStartOffset(document.getLineNumber(offset));
    }
}
