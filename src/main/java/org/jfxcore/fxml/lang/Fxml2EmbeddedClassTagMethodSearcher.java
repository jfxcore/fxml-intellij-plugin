// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.util.Processor;
import com.intellij.util.QueryExecutor;
import org.jetbrains.annotations.NotNull;

/**
 * {@link MethodReferencesSearch} extension that finds embedded FXML element-tag and
 * markup-extension usages when "Find Usages" is invoked on a constructor.
 *
 * <p>IntelliJ routes "Find Usages" on a {@link PsiMethod} (including constructors) through
 * {@link MethodReferencesSearch}, not through the generic {@link
 * com.intellij.psi.search.searches.ReferencesSearch}.  {@link Fxml2EmbeddedClassTagSearcher}
 * handles the constructor case only via {@code ReferencesSearch} and therefore never fires
 * for the IDE's "Find Usages" action on a constructor.
 *
 * <p>This searcher bridges that gap: when the target is a constructor, it resolves the
 * containing class and delegates to {@link Fxml2EmbeddedClassTagSearcher#collectInScope},
 * so that element-tag usages (e.g. {@code <MyControl/>}) and markup-extension usages
 * (e.g. {@code {MyExtension value=foo}}) in {@code @ComponentView}-annotated embedded
 * FXML markup appear in the "Find Usages" results alongside normal Java call sites.
 *
 * <p>Complements {@link Fxml2EmbeddedClassTagSearcher} (which handles the
 * {@code ReferencesSearch} path for class-level "Find Usages" and for non-IDE callers).
 */
public final class Fxml2EmbeddedClassTagMethodSearcher
        implements QueryExecutor<PsiReference, MethodReferencesSearch.SearchParameters> {

    @Override
    public boolean execute(
            @NotNull MethodReferencesSearch.SearchParameters params,
            @NotNull Processor<? super PsiReference> consumer) {

        PsiMethod method = params.getMethod();

        boolean isConstructor = ReadAction.compute(method::isConstructor);
        if (!isConstructor) return true;

        PsiClass psiClass = ReadAction.compute(method::getContainingClass);
        if (psiClass == null) return true;

        SearchScope effectiveScope = params.getEffectiveSearchScope();
        if (!(effectiveScope instanceof GlobalSearchScope globalScope)) return true;

        Project project = ReadAction.compute(method::getProject);
        Fxml2EmbeddedClassTagSearcher.collectInScope(psiClass, project, globalScope, consumer);
        return true;
    }
}
