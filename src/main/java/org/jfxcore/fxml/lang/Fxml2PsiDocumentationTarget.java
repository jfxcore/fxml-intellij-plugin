package org.jfxcore.fxml.lang;

import com.intellij.lang.LanguageDocumentation;
import com.intellij.lang.documentation.DocumentationProvider;
import com.intellij.model.Pointer;
import com.intellij.platform.backend.documentation.DocumentationResult;
import com.intellij.platform.backend.documentation.DocumentationTarget;
import com.intellij.platform.backend.presentation.TargetPresentation;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A {@link DocumentationTarget} backed by a {@link PsiElement}.
 *
 * <p>This class uses {@link LanguageDocumentation} and the public {@link DocumentationProvider} extension:
 * the ctrl-hover hint comes from {@link DocumentationProvider#getQuickNavigateInfo} and the
 * Quick Documentation popup from {@link DocumentationProvider#generateDoc}.
 *
 * <p>Used by symbol-native {@code DocumentationSymbol}s (e.g. {@link FxIdSymbol}) so hovering an
 * {@code fx:id} shows the code-behind field's documentation ("Button myButton1") rather than
 * generic XML attribute docs.  Smart pointers keep the target valid across PSI reparses.
 */
@SuppressWarnings("UnstableApiUsage")
final class Fxml2PsiDocumentationTarget implements DocumentationTarget {

    private final @NotNull SmartPsiElementPointer<PsiElement> targetPtr;
    private final @Nullable SmartPsiElementPointer<PsiElement> sourcePtr;

    private Fxml2PsiDocumentationTarget(
            @NotNull SmartPsiElementPointer<PsiElement> targetPtr,
            @Nullable SmartPsiElementPointer<PsiElement> sourcePtr) {
        this.targetPtr = targetPtr;
        this.sourcePtr = sourcePtr;
    }

    /**
     * @param target the element to document (e.g. the code-behind field)
     * @param source the element under the cursor, passed as the "original element" context to
     *               the documentation provider, or {@code null}
     */
    static @NotNull Fxml2PsiDocumentationTarget of(@NotNull PsiElement target, @Nullable PsiElement source) {
        return new Fxml2PsiDocumentationTarget(
                SmartPointerManager.createPointer(target),
                source != null ? SmartPointerManager.createPointer(source) : null);
    }

    @Override
    public @NotNull Pointer<? extends DocumentationTarget> createPointer() {
        SmartPsiElementPointer<PsiElement> capturedSource = sourcePtr;
        return Pointer.delegatingPointer(targetPtr,
                target -> new Fxml2PsiDocumentationTarget(
                        SmartPointerManager.createPointer(target), capturedSource));
    }

    @Override
    public @NotNull TargetPresentation computePresentation() {
        PsiElement target = targetPtr.getElement();
        String name = target instanceof PsiNamedElement named && named.getName() != null
                ? named.getName()
                : String.valueOf(target);
        return TargetPresentation.builder(name).presentation();
    }

    @Override
    public @Nullable Navigatable getNavigatable() {
        PsiElement target = targetPtr.getElement();
        return target instanceof Navigatable nav ? nav : null;
    }

    @Override
    public @Nullable String computeDocumentationHint() {
        PsiElement target = targetPtr.getElement();
        if (target == null) return null;
        PsiElement source = sourcePtr != null ? sourcePtr.getElement() : null;
        for (DocumentationProvider provider :
                LanguageDocumentation.INSTANCE.allForLanguage(target.getLanguage())) {
            String info = provider.getQuickNavigateInfo(target, source);
            if (info != null) return info;
        }
        return null;
    }

    @Override
    public @Nullable DocumentationResult computeDocumentation() {
        PsiElement target = targetPtr.getElement();
        if (target == null) return null;
        PsiElement source = sourcePtr != null ? sourcePtr.getElement() : null;
        for (DocumentationProvider provider :
                LanguageDocumentation.INSTANCE.allForLanguage(target.getLanguage())) {
            String html = provider.generateDoc(target, source);
            if (html != null) return DocumentationResult.documentation(html);
        }
        return null;
    }
}
