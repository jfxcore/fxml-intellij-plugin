package org.jfxcore.fxml.lang;

import com.intellij.lang.Language;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.impl.source.xml.XmlFileImpl;
import com.intellij.psi.xml.XmlElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Custom {@link XmlFileImpl} subclass used for FXML markup injected into Java / Kotlin
 * source files via the {@code @ComponentView} annotation.
 *
 * <p>The only behavioral difference from a plain {@link XmlFileImpl} is the
 * {@link #findElementAt(int)} override, which applies the same identifier-segment narrowing
 * that {@link Fxml2FileViewProvider} provides for standalone {@code .fxml} files.
 *
 * <p>IntelliJ's identifier-under-caret highlighting caches its results using the text-range
 * of the element returned by {@code findElementAt(caretOffset)}.  Without this override the
 * returned element is a wide XML token (e.g. the entire {@code "${vm.message}"} attribute-value
 * token), so moving the caret from {@code vm} to {@code message} within the same token is
 * incorrectly treated as a cache hit, showing {@code vm}'s highlights instead of
 * {@code message}'s.  By returning a {@link Fxml2SegmentElement} whose range covers only the
 * single identifier at the caret, each identifier gets its own distinct cache key and
 * therefore its own correct highlight result.
 */
final class Fxml2EmbeddedXmlFile extends XmlFileImpl {

    Fxml2EmbeddedXmlFile(@NotNull FileViewProvider viewProvider) {
        super(viewProvider, XmlElementType.XML_FILE);
    }

    /**
     * Returns {@link Fxml2EmbeddedXmlLanguage#INSTANCE} instead of the element-type's
     * default {@link com.intellij.lang.xml.XMLLanguage#INSTANCE}.
     *
     * <p>IntelliJ's {@code InjectionRegistrarImpl}
     * checks {@code psiFile.getLanguage() == viewProvider.getBaseLanguage()} to decide
     * whether to add the injected file to the "result files" list.  The injected view
     * provider's base language is {@code Fxml2EmbeddedXmlLanguage.INSTANCE} (the language
     * passed to {@code startInjecting()}), so this override must return the same value to
     * keep the check true and let the injection machinery register the file correctly.
     */
    @Override
    public @NotNull Language getLanguage() {
        return Fxml2EmbeddedXmlLanguage.INSTANCE;
    }

    @Override
    public @Nullable PsiElement findElementAt(int offset) {
        PsiElement leaf = getViewProvider().findElementAt(offset);
        if (leaf == null) return null;
        return Fxml2FileViewProvider.narrowBinding(leaf, offset);
    }
}
