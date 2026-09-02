package org.jfxcore.fxml.lang;

import com.intellij.lang.properties.IProperty;
import com.intellij.lang.properties.PropertiesDocumentationProvider;
import com.intellij.lang.properties.references.PropertyReferenceBase;
import com.intellij.model.Pointer;
import com.intellij.platform.backend.documentation.DocumentationResult;
import com.intellij.platform.backend.documentation.DocumentationTarget;
import com.intellij.platform.backend.documentation.DocumentationTargetProvider;
import com.intellij.platform.backend.presentation.TargetPresentation;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Shows the property value from the resource bundle as a hover tooltip when the
 * cursor is on a {@code %key} or {@code {DynamicResource key}} / {@code {StaticResource key}}
 * attribute value in FXML markup: the same information that is already shown when
 * hovering over the property key in the {@code .properties} file itself.
 *
 * <h3>Mechanism</h3>
 * <p>This provider is registered as a
 * {@code com.intellij.platform.backend.documentation.targetProvider} (V2 documentation API)
 * so that it participates in the modern hover-documentation pipeline.
 *
 * <p>In {@link #documentationTargets} it:
 * <ol>
 *   <li>Looks for the FXML/2 {@link XmlAttributeValue} at the cursor position through
 *       {@link Fxml2AttributeValueAtOffset}, which covers a standalone document as well as
 *       markup embedded in a {@code @ComponentView} annotation value.</li>
 *   <li>Finds the {@link PropertyReferenceBase} the cursor is on and resolves it to an
 *       {@link IProperty} via {@code multiResolve()} (handles multiple locale variants).</li>
 *   <li>Returns an {@link IPropertyDocumentationTarget} that delegates HTML rendering to
 *       {@code PropertiesDocumentationProvider}, showing
 *       {@code key="value [file.properties]"}.</li>
 * </ol>
 */
@SuppressWarnings("UnstableApiUsage")
public final class Fxml2ResourceKeyDocumentationTargetProvider implements DocumentationTargetProvider {

    @Override
    public @NotNull List<? extends @NotNull DocumentationTarget> documentationTargets(
            @NotNull PsiFile file, int offset) {

        IProperty property = resolvePropertyAt(file, offset);
        if (property == null) return List.of();
        return List.of(new IPropertyDocumentationTarget(property));
    }

    /**
     * Resolves the property key reference at the given offset to an {@link IProperty}.
     * The offset must fall inside the text range of the property reference itself (i.e. the key
     * token), not just anywhere inside the enclosing attribute value, so that hovering over
     * other attributes does <em>not</em> accidentally show resource-bundle documentation.
     */
    public static @Nullable IProperty resolvePropertyAt(@NotNull PsiFile file, int offset) {
        Fxml2AttributeValueAtOffset position = Fxml2AttributeValueAtOffset.find(file, offset);
        if (position == null) return null;

        XmlAttributeValue attrVal = position.attributeValue();
        int offsetInAttrVal = position.offsetInAttributeValue();

        // Use multiResolve() because resolve() returns null when multiple targets exist
        // (e.g. the same key present in multiple bundle locales / languages).
        for (PsiReference ref : attrVal.getReferences()) {
            if (ref instanceof PropertyReferenceBase propRef) {
                // Only show resource-key docs when the cursor is actually over the key token.
                TextRange keyRange = propRef.getRangeInElement();
                if (offsetInAttrVal < keyRange.getStartOffset()
                        || offsetInAttrVal > keyRange.getEndOffset()) continue;

                for (ResolveResult result : propRef.multiResolve(false)) {
                    PsiElement resolved = result.getElement();
                    if (resolved instanceof IProperty prop) return prop;
                }
            }
        }
        return null;
    }

    /**
     * A {@link DocumentationTarget} that renders documentation for an {@link IProperty}
     * using {@link PropertiesDocumentationProvider#generateDoc}.
     */
    @SuppressWarnings("UnstableApiUsage")
    private static final class IPropertyDocumentationTarget implements DocumentationTarget {

        private final SmartPsiElementPointer<PsiElement> pointer;
        private final String key;

        IPropertyDocumentationTarget(@NotNull IProperty property) {
            this.pointer = SmartPointerManager.createPointer((PsiElement) property);
            this.key = property.getKey() != null ? property.getKey() : "";
        }

        @Override
        public @NotNull Pointer<IPropertyDocumentationTarget> createPointer() {
            return () -> {
                PsiElement element = pointer.getElement();
                if (element instanceof IProperty prop) return new IPropertyDocumentationTarget(prop);
                return null;
            };
        }

        @Override
        public @NotNull TargetPresentation computePresentation() {
            return TargetPresentation.builder(key).presentation();
        }

        @Override
        public @Nullable DocumentationResult computeDocumentation() {
            PsiElement element = pointer.getElement();
            if (!(element instanceof IProperty)) return null;
            String html = new PropertiesDocumentationProvider().generateDoc(element, null);
            if (html == null) return null;
            return DocumentationResult.documentation(html);
        }
    }
}
