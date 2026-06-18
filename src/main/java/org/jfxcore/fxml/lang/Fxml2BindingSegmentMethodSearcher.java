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
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resolve.Fxml2PropertyNameUtil;

/**
 * {@link MethodReferencesSearch} extension that finds {@link Fxml2BindingSegmentReference}s
 * in FXML files that resolve to a given Java property accessor method.
 *
 * <p>IntelliJ routes "Find Usages" on a {@link com.intellij.psi.PsiMethod} through
 * {@link MethodReferencesSearch}, not through the generic {@link
 * com.intellij.psi.search.searches.ReferencesSearch}.  {@link Fxml2BindingSegmentSearcher}
 * handles {@code ReferencesSearch} and therefore never fires for the IDE's "Find Usages" action
 * on a method.  This searcher bridges that gap by delegating to
 * {@link Fxml2BindingSegmentSearcher#handleGlobalSearch} so that binding-path segment
 * uses of the method (e.g. the {@code "selectedItem"} segment in
 * {@code #{vm.selectedItem}} resolving to {@code selectedItemProperty()}) are included
 * in the "Find Usages" results.
 *
 * <p>Complements {@link Fxml2PropertyAttributeMethodSearcher} (which handles property
 * attribute names like {@code selectedItem="..."}) and {@link Fxml2BindingSegmentSearcher}
 * (which handles the {@link com.intellij.psi.search.searches.ReferencesSearch} path for
 * local highlighting and non-method elements).
 */
public final class Fxml2BindingSegmentMethodSearcher
        implements QueryExecutor<PsiReference, MethodReferencesSearch.SearchParameters> {

    @Override
    public boolean execute(
            @NotNull MethodReferencesSearch.SearchParameters params,
            @NotNull Processor<? super PsiReference> consumer) {

        var method = params.getMethod();

        SearchScope effectiveScope = params.getEffectiveSearchScope();
        if (!(effectiveScope instanceof GlobalSearchScope globalScope)) return true;

        String propertyName = ReadAction.nonBlocking(
                () -> Fxml2PropertyNameUtil.propertyNameFromElement(method)).executeSynchronously();
        if (propertyName == null) return true;

        Fxml2BindingSegmentSearcher.handleGlobalSearch(method, globalScope, consumer);
        return true;
    }
}
