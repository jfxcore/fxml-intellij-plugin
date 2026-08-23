// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.codeinsight;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.completion.PlainPrefixMatcher;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlProcessingInstruction;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlTokenType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.lang.Fxml2ProcessingInstructionTarget;

/**
 * Completes the target of a processing instruction, so that typing {@code <?re} offers
 * {@code <?resource ?>} and typing {@code <?im} offers {@code <?import ?>}.
 *
 * <p>The typed text is read from the document rather than from the completion position, because a
 * processing instruction whose target is still being typed is not yet a construct the XML parser
 * gives a useful prefix for.  The position is validated through the PSI instead: the {@code <?}
 * before the caret must actually open a processing instruction, which is what distinguishes a
 * real one from the same two characters written inside an attribute value, a comment, or the data
 * of an instruction that is already open.
 *
 * <p>Only the targets the language reads at that position are offered, mirroring
 * {@link Fxml2ProcessingInstructionTarget}: an import or prefix declaration inside an element
 * would be silently ignored, so completion does not propose one there.
 */
final class Fxml2ProcessingInstructionCompletion {

    private Fxml2ProcessingInstructionCompletion() {}

    /**
     * Adds the processing-instruction targets available at the caret.
     *
     * @return {@code true} when the caret is completing a processing-instruction target, whether
     *         or not any target is available there
     */
    static boolean complete(@NotNull CompletionParameters parameters,
                            @NotNull CompletionResultSet result) {
        PsiFile file = parameters.getOriginalFile();
        String text = file.getText();
        int caret = parameters.getOffset();
        if (caret > text.length()) return false;

        int nameStart = caret;
        while (nameStart > 0 && isTargetNameChar(text.charAt(nameStart - 1))) --nameStart;

        int instructionStart = nameStart - 2;
        if (instructionStart < 0 || text.charAt(instructionStart) != '<' || text.charAt(nameStart - 1) != '?') {
            return false;
        }
        PsiElement instructionStartLeaf = instructionStartAt(file, instructionStart);
        if (instructionStartLeaf == null) return false;

        XmlTag enclosingElement =
                Fxml2ProcessingInstructionTarget.enclosingElement(instructionStartLeaf);

        // A target is matched by what it starts with: "<?pre" names the prefix declaration, and
        // must not also offer the resource declaration on the strength of a shared substring.
        CompletionResultSet targets = result.withPrefixMatcher(
                new PlainPrefixMatcher(text.substring(nameStart, caret), /* prefixMatchesOnly= */ true));

        for (Fxml2ProcessingInstructionTarget target : Fxml2ProcessingInstructionTarget.values()) {
            if (!target.isReadInside(enclosingElement)) continue;

            targets.addElement(LookupElementBuilder.create(target.targetName())
                    .withPresentableText("<?" + target.targetName() + " ?>")
                    .withIcon(AllIcons.Nodes.Tag)
                    .withInsertHandler(Fxml2ProcessingInstructionCompletion::insertSkeleton));
        }

        return true;
    }

    /** Returns {@code true} when {@code character} may appear in a processing-instruction target. */
    private static boolean isTargetNameChar(char character) {
        return Character.isLetterOrDigit(character) || character == '-' || character == '_';
    }

    /**
     * Returns the token at {@code offset} when it opens a processing instruction, and {@code null}
     * when the {@code <?} there is literal text of an attribute value, a comment, or the data of
     * an instruction that is already open.
     */
    private static @Nullable PsiElement instructionStartAt(@NotNull PsiFile file, int offset) {
        PsiElement leaf = file.findElementAt(offset);
        return leaf != null
                && leaf.getNode().getElementType() == XmlTokenType.XML_PI_START
                && leaf.getParent() instanceof XmlProcessingInstruction
                ? leaf
                : null;
    }

    /**
     * Completes the skeleton around the inserted target, leaving the caret where the declaration
     * continues and reusing a {@code ?>} that is already there.
     */
    private static void insertSkeleton(@NotNull InsertionContext context,
                                       @NotNull LookupElement item) {
        Document document = context.getDocument();
        int tail = context.getTailOffset();
        String rest = document.getText().substring(tail);

        StringBuilder skeleton = new StringBuilder();
        if (!rest.startsWith(" ")) skeleton.append(' ');
        if (!rest.stripLeading().startsWith("?>")) skeleton.append("?>");

        document.insertString(tail, skeleton);
        context.getEditor().getCaretModel().moveToOffset(tail + 1);

        if (Fxml2ProcessingInstructionTarget.IMPORT.targetName().equals(item.getLookupString())) {
            context.setLaterRunnable(() -> AutoPopupController.getInstance(context.getProject())
                    .scheduleAutoPopup(context.getEditor()));
        }
    }
}
