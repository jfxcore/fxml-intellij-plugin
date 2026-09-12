// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.find.findUsages.FindUsagesHandler;
import com.intellij.find.findUsages.FindUsagesHandlerFactory;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Starts Find Usages from a class selector declared in an embedded CSS resource. */
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
