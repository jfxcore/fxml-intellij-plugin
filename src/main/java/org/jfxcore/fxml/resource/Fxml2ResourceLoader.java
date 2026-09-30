// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.resource;

import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.jfxcore.fxml.resolve.Fxml2BindingPathResolver;

/** Whether a loader expression definitely uses the default lookup or a custom loader. */
public enum Fxml2ResourceLoader {
    DEFAULT, CUSTOM, UNKNOWN;

    public static @NotNull Fxml2ResourceLoader resolve(@NotNull Fxml2ResourceInvocation invocation,
                                                       @NotNull XmlFile file) {
        var loader = invocation.loader();
        if (loader == null || "{fx:Null}".equals(loader.text())) return DEFAULT;
        if (!loader.text().startsWith("$")) return UNKNOWN;
        var rootClass = Fxml2EmbeddedUtil.getHostClass(file);
        if (rootClass == null) rootClass = Fxml2BindingPathResolver.resolveCodeBehindClass(file);
        if (rootClass == null) return UNKNOWN;
        var segments = Fxml2BindingPathResolver.resolve(loader.text().substring(1), rootClass,
                GlobalSearchScope.allScope(file.getProject()));
        if (segments.isEmpty() || !(segments.getLast().declaration() instanceof PsiField field)
                || !field.hasModifierProperty(PsiModifier.FINAL)) return UNKNOWN;
        var initializer = field.getInitializer();
        if (initializer instanceof PsiNewExpression) return CUSTOM;
        if (initializer instanceof PsiLiteralExpression literal && "null".equals(literal.getText())) return DEFAULT;
        return UNKNOWN;
    }
}
