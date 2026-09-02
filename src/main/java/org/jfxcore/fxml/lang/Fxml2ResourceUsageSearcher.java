// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.find.usages.api.PsiUsage;
import com.intellij.find.usages.api.SearchTarget;
import com.intellij.find.usages.api.Usage;
import com.intellij.find.usages.api.UsageSearchParameters;
import com.intellij.find.usages.api.UsageSearcher;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.searches.ReferencesSearch;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Collects the use sites of an embedded resource for the symbol-native Show Usages path, which is
 * what the Ctrl+click gesture on a resource declaration ends up in.
 *
 * <p>The search runs over the declaration element the reference machinery already resolves to, so
 * this path and the Find Usages action report the same set of {@code @name} and
 * {@code {ClassPathResource name}} usages.
 */
@SuppressWarnings("UnstableApiUsage")
public final class Fxml2ResourceUsageSearcher implements UsageSearcher {

    @Override
    public @Unmodifiable @NotNull Collection<? extends Usage> collectImmediateResults(
            @NotNull UsageSearchParameters parameters) {

        SearchTarget target = parameters.getTarget();
        if (!(target instanceof Fxml2ResourceSymbol symbol)) return List.of();

        Fxml2ResourceDeclarationElement declaration = symbol.getDeclaration();
        if (declaration == null) return List.of();

        List<Usage> usages = new ArrayList<>();
        for (PsiReference reference :
                ReferencesSearch.search(declaration, parameters.getSearchScope()).findAll()) {
            usages.add(PsiUsage.textUsage(reference.getElement(), reference.getRangeInElement()));
        }

        return usages;
    }
}
