package org.jfxcore.fxml.lang;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.lang.properties.IProperty;
import com.intellij.lang.properties.PropertiesDocumentationProvider;
import com.intellij.lang.properties.references.PropertyReferenceBase;
import com.intellij.model.Pointer;
import com.intellij.platform.backend.documentation.DocumentationResult;
import com.intellij.platform.backend.documentation.DocumentationTarget;
import com.intellij.platform.backend.documentation.DocumentationTargetProvider;
import com.intellij.platform.backend.presentation.TargetPresentation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
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
 *   <li>Looks for an {@link XmlAttributeValue} at the cursor position.
 *       <ul>
 *         <li>For <b>standalone FXML files</b>, the element at the offset is already an XML token
 *            : a simple parent walk suffices.</li>
 *         <li>For <b>embedded FXML</b> ({@code @ComponentView} text block), the platform calls
 *             this provider first on the injected XML fragment, so the element is also an XML token.</li>
 *       </ul></li>
 *   <li>Verifies the containing file is recognized as FXML/2.</li>
 *   <li>Finds the first {@link PropertyReferenceBase} on the attribute value and resolves it to
 *       an {@link IProperty} via {@code multiResolve()} (handles multiple locale variants).</li>
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
        PsiElement contextElement = file.findElementAt(offset);
        XmlAttributeValue attrVal = findAttrValueAtPosition(file, contextElement, offset);
        if (attrVal == null) return null;

        PsiFile xmlFile = attrVal.getContainingFile();
        if (!(xmlFile instanceof XmlFile xmlF) || !Fxml2FileType.isFxml2(xmlF)) return null;

        // Determine the effective offset *within the XML file* that contains the attrVal.
        // For standalone FXML files, the offset is already in the XML file's coordinate space.
        // For embedded FXML (injected into a Java text block), targetOffset is in the Java
        // host file's coordinate space, so we convert it via the injected language manager.
        int xmlOffset;
        if (xmlFile == file) {
            xmlOffset = offset;
        } else {
            // Map from host file offset to injected file offset.
            PsiElement injected = InjectedLanguageManager.getInstance(file.getProject())
                    .findInjectedElementAt(file, offset);
            xmlOffset = injected != null ? injected.getTextRange().getStartOffset() : -1;
        }
        if (xmlOffset < 0) return null;

        int attrStart = attrVal.getTextRange().getStartOffset();

        // Use multiResolve() because resolve() returns null when multiple targets exist
        // (e.g. the same key present in multiple bundle locales / languages).
        for (PsiReference ref : attrVal.getReferences()) {
            if (ref instanceof PropertyReferenceBase propRef) {
                // Only show resource-key docs when the cursor is actually over the key token.
                int keyStart = attrStart + propRef.getRangeInElement().getStartOffset();
                int keyEnd   = attrStart + propRef.getRangeInElement().getEndOffset();
                if (xmlOffset < keyStart || xmlOffset > keyEnd) continue;

                for (ResolveResult result : propRef.multiResolve(false)) {
                    PsiElement resolved = result.getElement();
                    if (resolved instanceof IProperty prop) return prop;
                }
            }
        }
        return null;
    }

    /**
     * Finds the {@link XmlAttributeValue} at {@code targetOffset} in {@code file}.
     *
     * <ul>
     *   <li>For standalone XML/FXML files: {@code contextElement} is already an XML token,
     *       so a simple parent walk finds the enclosing attribute value.</li>
     *   <li>For Java host files with embedded FXML: the platform's V2 hover pipeline calls
     *       this provider on the injected XML fragment first, so {@code contextElement} is
     *       also an XML token. The fallback {@link InjectedLanguageManager#findInjectedElementAt}
     *       handles any remaining cases where the host file offset is passed directly.</li>
     * </ul>
     */
    private static @Nullable XmlAttributeValue findAttrValueAtPosition(
            @NotNull PsiFile file,
            @Nullable PsiElement contextElement,
            int targetOffset) {

        // Case 1: context element is already inside an XML attribute value.
        if (contextElement != null) {
            XmlAttributeValue attrVal =
                    PsiTreeUtil.getParentOfType(contextElement, XmlAttributeValue.class, false);
            if (attrVal != null) return attrVal;
        }

        // Case 2: host file (e.g. Java): find the injected XML element at this offset.
        PsiElement injected = InjectedLanguageManager.getInstance(file.getProject())
                .findInjectedElementAt(file, targetOffset);
        if (injected != null) {
            return PsiTreeUtil.getParentOfType(injected, XmlAttributeValue.class, false);
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
