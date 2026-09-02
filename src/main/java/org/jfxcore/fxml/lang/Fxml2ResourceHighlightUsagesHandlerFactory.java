// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase;
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactory;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.ProperTextRange;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jfxcore.fxml.resource.Fxml2ResourceEntry;
import org.jfxcore.fxml.resource.Fxml2ResourceModel;

import java.util.List;

/** Highlights an embedded-resource declaration and all its use sites under either caret. */
public final class Fxml2ResourceHighlightUsagesHandlerFactory
        implements HighlightUsagesHandlerFactory {

    @Override
    public @Nullable HighlightUsagesHandlerBase<?> createHighlightUsagesHandler(
            @NotNull Editor editor, @NotNull PsiFile file) {
        return create(editor, file);
    }

    @Override
    public @Nullable HighlightUsagesHandlerBase<?> createHighlightUsagesHandler(
            @NotNull Editor editor, @NotNull PsiFile file, @NotNull ProperTextRange visibleRange) {
        return create(editor, file);
    }

    private static @Nullable HighlightUsagesHandlerBase<?> create(
            @NotNull Editor editor, @NotNull PsiFile file) {
        if (!(file instanceof XmlFile xmlFile) || !Fxml2FileType.isFxml2(xmlFile)) return null;

        int offset = editor.getCaretModel().getOffset();
        Fxml2ResourceDeclarationElement declaration = declarationAt(xmlFile, offset);
        if (declaration == null) declaration = declarationFromReference(xmlFile, offset);
        return declaration == null ? null : new Handler(editor, file, declaration);
    }

    private static @Nullable Fxml2ResourceDeclarationElement declarationAt(
            @NotNull XmlFile file, int offset) {
        for (Fxml2ResourceEntry entry : Fxml2ResourceModel.of(file).entries()) {
            if (entry.nameRange().containsOffset(offset)) {
                return new Fxml2ResourceDeclarationElement(entry);
            }
        }
        return null;
    }

    private static @Nullable Fxml2ResourceDeclarationElement declarationFromReference(
            @NotNull XmlFile file, int offset) {
        XmlAttributeValue value = PsiTreeUtil.findElementOfClassAtOffset(
                file, offset, XmlAttributeValue.class, false);
        if (value == null) return null;

        int relativeOffset = offset - value.getTextRange().getStartOffset();
        for (PsiReference reference : value.getReferences()) {
            if (reference instanceof Fxml2ResourceNameReference resourceReference
                    && reference.getRangeInElement().containsOffset(relativeOffset)
                    && resourceReference.resolve() instanceof Fxml2ResourceDeclarationElement declaration) {
                return declaration;
            }
        }
        return null;
    }

    private static final class Handler
            extends HighlightUsagesHandlerBase<Fxml2ResourceDeclarationElement> {

        private final Fxml2ResourceDeclarationElement declaration;

        private Handler(@NotNull Editor editor, @NotNull PsiFile file,
                        @NotNull Fxml2ResourceDeclarationElement declaration) {
            super(editor, file);
            this.declaration = declaration;
        }

        @Override
        public @Unmodifiable @NotNull List<Fxml2ResourceDeclarationElement> getTargets() {
            return List.of(declaration);
        }

        @Override
        protected void selectTargets(
                @NotNull @Unmodifiable List<? extends Fxml2ResourceDeclarationElement> targets,
                @NotNull Consumer<? super List<? extends Fxml2ResourceDeclarationElement>> consumer) {
            consumer.consume(targets);
        }

        @Override
        public void computeUsages(
                @NotNull List<? extends Fxml2ResourceDeclarationElement> targets) {
            myReadUsages.add(declaration.getTextRange());
            ReferencesSearch.search(declaration, new LocalSearchScope(myFile)).forEach(reference -> {
                PsiElement element = reference.getElement();
                TextRange range = reference.getRangeInElement()
                        .shiftRight(element.getTextRange().getStartOffset());
                myReadUsages.add(InjectedLanguageManager.getInstance(element.getProject())
                        .injectedToHost(element, range));
                return true;
            });
            buildStatusText(declaration.getName(), myReadUsages.size() - 1);
        }
    }
}
