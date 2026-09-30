// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.XmlSuppressableInspectionTool;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.XmlElementVisitor;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.resolve.Fxml2PropertyDependencies;

import java.util.stream.Collectors;

/** Reports cycles composed of one-time property assignment dependencies. */
public final class Fxml2CyclicPropertyAssignmentInspection extends XmlSuppressableInspectionTool {
    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        var file = Fxml2FileType.asFxml2(holder.getFile());
        if (file == null) return PsiElementVisitor.EMPTY_VISITOR;
        return new XmlElementVisitor() {
            @Override
            public void visitXmlTag(@NotNull XmlTag tag) {
                for (var cycle : Fxml2PropertyDependencies.cycles(tag, file)) {
                    String names = cycle.assignments().stream().map(Fxml2PropertyDependencies.Assignment::name)
                            .collect(Collectors.joining(" -> "));
                    holder.registerProblem(cycle.assignments().getFirst().anchor(),
                            "Cyclic property assignment: " + names, ProblemHighlightType.GENERIC_ERROR);
                }
            }
        };
    }
}
