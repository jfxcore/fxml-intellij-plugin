// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.model.Symbol;
import com.intellij.model.psi.PsiSymbolDeclaration;
import com.intellij.model.psi.PsiSymbolDeclarationProvider;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlProcessingInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resource.Fxml2ResourceDeclaration;
import org.jfxcore.fxml.resource.Fxml2ResourceEntry;
import org.jfxcore.fxml.resource.Fxml2ResourceInstructionParser;
import org.jfxcore.fxml.resource.Fxml2ResourceModel;
import org.jfxcore.fxml.resource.Fxml2ResourceParseResult;

import java.util.Collection;
import java.util.List;

/**
 * Registers the name of a {@code <?resource ?>} declaration as a declaration in IntelliJ's
 * Symbol/Declaration API.
 *
 * <p>This is what makes Ctrl+click on a resource name behave the way it does on any other
 * declaration site: because a declaration is found under the cursor, "Go To Declaration Or Usages"
 * switches to Show Usages and lists the {@code @name} and {@code {ClassPathResource name}} use
 * sites instead of doing nothing.  {@link Fxml2ResourceFindUsagesHandlerFactory} serves the
 * explicit Find Usages action; this provider serves the navigation gesture.
 *
 * <p>The name span is read from the text of the processing instruction under the cursor rather
 * than from {@link Fxml2ResourceModel}, so that the span is always in the coordinates of the file
 * the cursor is in.  In embedded markup the model anchors its spans to the injection host, while
 * the cursor sits in the injected XML fragment, and the two do not share an origin.
 */
@SuppressWarnings("UnstableApiUsage")
public final class Fxml2ResourceDeclarationProvider implements PsiSymbolDeclarationProvider {

    @Override
    public @NotNull Collection<? extends PsiSymbolDeclaration> getDeclarations(
            @NotNull PsiElement element, int offsetInElement) {

        XmlProcessingInstruction instruction = instructionOf(element);
        if (instruction == null) return List.of();
        if (!(instruction.getContainingFile() instanceof XmlFile xmlFile)) return List.of();
        if (!Fxml2FileType.isFxml2(xmlFile)) return List.of();

        String text = instruction.getText();
        Fxml2ResourceParseResult result = Fxml2ResourceInstructionParser.parseAt(text, 0, text.length());
        if (result == null) return List.of();

        Fxml2ResourceDeclaration declaration = result.declaration();
        if (declaration == null) return List.of();

        // The name span is relative to the instruction, while the declaration must be relative to
        // the element the platform called us with.  The platform walks every element around the
        // cursor, most of which do not contain the name at all, so the offsets are checked before
        // a range is built from them.
        int elementStart = element.getTextRange().getStartOffset();
        int nameStart = instruction.getTextRange().getStartOffset()
                + declaration.nameSpan().start() - elementStart;
        int nameEnd = nameStart + declaration.nameSpan().length();

        if (nameStart < 0 || nameEnd > element.getTextLength()) return List.of();

        TextRange nameInElement = new TextRange(nameStart, nameEnd);
        if (offsetInElement >= 0 && !nameInElement.containsOffset(offsetInElement)) {
            return List.of();
        }

        Fxml2ResourceEntry entry = Fxml2ResourceModel.of(xmlFile).resolve(declaration.name().value());
        if (entry == null) return List.of();

        return List.of(new ResourceNameDeclaration(
                element, nameInElement, Fxml2ResourceSymbol.of(xmlFile, entry.name().value())));
    }

    /** Returns the processing instruction {@code element} is part of, or {@code null}. */
    private static @Nullable XmlProcessingInstruction instructionOf(@NotNull PsiElement element) {
        return element instanceof XmlProcessingInstruction instruction
                ? instruction
                : PsiTreeUtil.getParentOfType(element, XmlProcessingInstruction.class);
    }

    // -----------------------------------------------------------------------

    /**
     * @param declaringElement        the element the platform passed in, used for its identity check
     * @param rangeInDeclaringElement the name's range within that element, used for the highlight
     * @param symbol                  the embedded resource the name declares
     */
    private record ResourceNameDeclaration(@NotNull PsiElement declaringElement,
                                           @NotNull TextRange rangeInDeclaringElement,
                                           @NotNull Fxml2ResourceSymbol symbol)
            implements PsiSymbolDeclaration {

        @Override
        public @NotNull PsiElement getDeclaringElement() { return declaringElement; }

        @Override
        public @NotNull TextRange getRangeInDeclaringElement() { return rangeInDeclaringElement; }

        @Override
        public @NotNull Symbol getSymbol() {
            return symbol;
        }
    }
}
