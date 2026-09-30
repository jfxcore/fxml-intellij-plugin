// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.resolve;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiLiteralExpression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/** The property receiving an object's implicit content, including inherited declarations. */
public record Fxml2DefaultProperty(@NotNull String name) {
    public static @Nullable Fxml2DefaultProperty resolve(@NotNull PsiClass owner) {
        return resolve(owner, new HashSet<>());
    }

    private static @Nullable Fxml2DefaultProperty resolve(@NotNull PsiClass owner, Set<PsiClass> visited) {
        if (!visited.add(owner)) return null;
        var annotation = owner.getAnnotation("javafx.beans.DefaultProperty");
        if (annotation != null) {
            var value = annotation.findAttributeValue("value");
            return value instanceof PsiLiteralExpression literal && literal.getValue() instanceof String name
                    ? new Fxml2DefaultProperty(name) : null;
        }
        for (var parent : owner.getSupers()) {
            var property = resolve(parent, visited);
            if (property != null) return property;
        }
        return null;
    }
}
