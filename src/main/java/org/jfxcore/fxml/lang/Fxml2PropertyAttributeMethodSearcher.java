// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.util.Processor;
import com.intellij.util.QueryExecutor;
import org.jfxcore.fxml.resolve.Fxml2PropertyNameUtil;
import org.jetbrains.annotations.NotNull;

/**
 * {@link MethodReferencesSearch} extension that finds FXML2 property-attribute usages
 * of a Java property-setter (or other accessor) method.
 *
 * <p>IntelliJ routes "Find Usages" on a {@link com.intellij.psi.PsiMethod} through
 * {@link MethodReferencesSearch}, not through the generic {@link
 * com.intellij.psi.search.searches.ReferencesSearch}.  This searcher bridges that gap
 * by delegating to {@link Fxml2PropertyAttributeSearcher#collectInScope}.
 *
 * <p>Complements {@link Fxml2PropertyAttributeSearcher} (which handles the generic
 * {@code ReferencesSearch} path for fields and Kotlin elements).
 */
public final class Fxml2PropertyAttributeMethodSearcher
        implements QueryExecutor<PsiReference, MethodReferencesSearch.SearchParameters> {

    @Override
    public boolean execute(
            @NotNull MethodReferencesSearch.SearchParameters params,
            @NotNull Processor<? super PsiReference> consumer) {

        var method = params.getMethod();

        SearchScope effectiveScope = params.getEffectiveSearchScope();
        if (!(effectiveScope instanceof GlobalSearchScope globalScope)) return true;

        String propertyName = ReadAction.compute(
                () -> Fxml2PropertyNameUtil.propertyNameFromElement(method));
        if (propertyName == null) return true;

        Fxml2PropertyAttributeSearcher.collectInScope(method, propertyName, globalScope, consumer);
        return true;
    }
}
