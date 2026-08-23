// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.find.findUsages.FindUsagesHandler;
import com.intellij.find.findUsages.FindUsagesHandlerFactory;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resource.Fxml2ResourceEntry;
import org.jfxcore.fxml.resource.Fxml2ResourceModel;

/** Starts Find Usages from the name in an embedded-resource declaration. */
public final class Fxml2ResourceFindUsagesHandlerFactory extends FindUsagesHandlerFactory {

    @Override
    public boolean canFindUsages(@NotNull PsiElement element) {
        return declarationAt(element) != null;
    }

    @Override
    public @Nullable FindUsagesHandler createFindUsagesHandler(
            @NotNull PsiElement element, boolean forHighlightUsages) {
        Fxml2ResourceEntry entry = declarationAt(element);
        return entry == null ? null : new FindUsagesHandler(new Fxml2ResourceDeclarationElement(entry)) {};
    }

    /**
     * Finds the declaration whose name is contained by the PSI element at the caret.
     *
     * <p>The platform also offers synthetic elements here, such as the navigation target a
     * documentation-link reference resolves to.  Those report a containing file but occupy no
     * range in it, so an element without a range declares nothing.
     */
    private static @Nullable Fxml2ResourceEntry declarationAt(@NotNull PsiElement element) {
        if (!(element.getContainingFile() instanceof XmlFile xmlFile)) return null;
        if (!Fxml2FileType.isFxml2(xmlFile)) return null;

        TextRange elementRange = element.getTextRange();
        if (elementRange == null) return null;

        for (Fxml2ResourceEntry entry : Fxml2ResourceModel.of(xmlFile).entries()) {
            if (elementRange.contains(entry.nameRange())) return entry;
        }
        return null;
    }
}
