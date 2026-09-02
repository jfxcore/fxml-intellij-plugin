// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.find.findUsages.FindUsagesHandler;
import com.intellij.find.findUsages.FindUsagesHandlerFactory;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Starts Find Usages from a class selector declared in an embedded {@code text/css} resource.
 *
 * <p>The selector is the declaration site of a style class, so what navigation from it has to
 * produce are its use sites: the {@code styleClass} tokens that name it.  The search runs on the
 * {@link CssSelectorElement} representing the selector, which
 * {@link Fxml2StyleClassSearcher} answers with those tokens alone; the stylesheet's own PSI, which
 * counts every same-named selector as a use of the style class, is left out of the result.
 */
public final class Fxml2StyleClassFindUsagesHandlerFactory extends FindUsagesHandlerFactory {

    @Override
    public boolean canFindUsages(@NotNull PsiElement element) {
        return Fxml2CssUtil.embeddedSelectorAt(element) != null;
    }

    @Override
    public @Nullable FindUsagesHandler createFindUsagesHandler(
            @NotNull PsiElement element, boolean forHighlightUsages) {
        CssSelectorElement selector = Fxml2CssUtil.embeddedSelectorAt(element);
        return selector == null ? null : new FindUsagesHandler(selector) {};
    }
}
