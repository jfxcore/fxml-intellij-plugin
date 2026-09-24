// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.model.Symbol;
import com.intellij.model.psi.PsiSymbolDeclaration;
import com.intellij.model.psi.PsiSymbolDeclarationProvider;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resource.Fxml2ResourceEntry;

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
 * <p>The shared declaration lookup converts the model's host ranges into the coordinates of the
 * file the platform calls this provider with, including an injected XML fragment.
 */
@SuppressWarnings("UnstableApiUsage")
public final class Fxml2ResourceDeclarationProvider implements PsiSymbolDeclarationProvider {

    @Override
    public @NotNull Collection<? extends PsiSymbolDeclaration> getDeclarations(
            @NotNull PsiElement element, int offsetInElement) {

        TextRange elementRange = element.getTextRange();
        if (elementRange == null) return List.of();

        Fxml2ResourceEntry entry = offsetInElement >= 0
                ? Fxml2ResourceDeclarations.at(
                        element.getContainingFile(), elementRange.getStartOffset() + offsetInElement)
                : Fxml2ResourceDeclarations.within(element);
        if (entry == null) return List.of();

        TextRange nameRange = entry.nameRangeIn(element.getContainingFile());
        if (nameRange == null || !elementRange.contains(nameRange)) return List.of();
        TextRange nameInElement = nameRange.shiftLeft(elementRange.getStartOffset());
        XmlFile xmlFile = Fxml2ResourceDeclarations.markupFileOf(element);
        if (xmlFile == null) return List.of();
        return List.of(new ResourceNameDeclaration(
                element, nameInElement, Fxml2ResourceSymbol.of(xmlFile, entry.name().value())));
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
