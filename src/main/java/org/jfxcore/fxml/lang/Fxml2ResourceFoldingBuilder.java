package org.jfxcore.fxml.lang;

import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingBuilderEx;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2TextSpan;
import org.jfxcore.fxml.resource.Fxml2ResourceDeclaration;
import org.jfxcore.fxml.resource.Fxml2ResourceInstructionParser;
import org.jfxcore.fxml.resource.Fxml2ResourceParseResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Folds the payload of a multi-line {@code <?resource ?>} declaration away, leaving the part that
 * identifies it visible.
 *
 * <p>An embedded stylesheet can be dozens of lines long and sits in the document prolog, in front
 * of the markup the reader came for.  Folding collapses it to
 * {@code <?resource styles.css text/css: ... ?>}, which keeps the name and the media type, the two
 * things a reader scanning the prolog is looking for.
 *
 * <p>Only the payload folds, not the declaration: the name and the media type stay readable and
 * stay clickable while the declaration is collapsed.  A same-line declaration is not folded at
 * all, because there is nothing to gain.
 */
public final class Fxml2ResourceFoldingBuilder extends FoldingBuilderEx {

    /** What a collapsed payload is shown as. */
    private static final String PLACEHOLDER = " ... ";

    @Override
    public FoldingDescriptor @NotNull [] buildFoldRegions(@NotNull PsiElement root,
                                                          @NotNull Document document,
                                                          boolean quick) {
        List<FoldingDescriptor> descriptors = new ArrayList<>();

        for (Fxml2ResourceProcessingInstruction instruction :
                PsiTreeUtil.findChildrenOfType(root, Fxml2ResourceProcessingInstruction.class)) {
            FoldingDescriptor descriptor = foldPayloadOf(instruction);
            if (descriptor != null) descriptors.add(descriptor);
        }

        return descriptors.toArray(FoldingDescriptor.EMPTY_ARRAY);
    }

    /** Returns the fold region for {@code instruction}'s payload, or {@code null} when it has none to fold. */
    private static @Nullable FoldingDescriptor foldPayloadOf(
            @NotNull Fxml2ResourceProcessingInstruction instruction) {
        String text = instruction.getText();
        Fxml2ResourceParseResult result = Fxml2ResourceInstructionParser.parseAt(text, 0, text.length());
        if (result == null) return null;

        Fxml2ResourceDeclaration declaration = result.declaration();
        if (declaration == null) return null;

        Fxml2TextSpan payload = declaration.payloadSpan();
        if (payload.isEmpty() || payload.textOf(text).indexOf('\n') < 0) return null;

        int start = instruction.getTextRange().getStartOffset();
        return new FoldingDescriptor(
                instruction.getNode(),
                payload.shifted(start).toTextRange(),
                null,
                PLACEHOLDER);
    }

    @Override
    public @NotNull String getPlaceholderText(@NotNull ASTNode node) {
        return PLACEHOLDER;
    }

    /**
     * Leaves a resource declaration expanded when a file is opened.
     *
     * <p>The payload is content, not boilerplate: a reader who has an embedded stylesheet open is
     * often there for the stylesheet.  Folding stays available on demand.
     */
    @Override
    public boolean isCollapsedByDefault(@NotNull ASTNode node) {
        return false;
    }

    /** Returns {@code true} so that a collapsed payload can still be searched and navigated into. */
    @Override
    public boolean isDumbAware() {
        return true;
    }
}
