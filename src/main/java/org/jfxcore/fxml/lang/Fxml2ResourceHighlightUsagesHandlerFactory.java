package org.jfxcore.fxml.lang;

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase;
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactory;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.ProperTextRange;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jfxcore.fxml.resource.Fxml2ResourceEntry;
import org.jfxcore.fxml.resource.Fxml2ResourceModel;

import java.util.List;

/** Highlights an embedded-resource declaration and all its use sites under either caret. */
public final class Fxml2ResourceHighlightUsagesHandlerFactory implements HighlightUsagesHandlerFactory {

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
        int offset = editor.getCaretModel().getOffset();
        Fxml2ResourceEntry entry = Fxml2ResourceDeclarations.at(file, offset);
        Fxml2ResourceDeclarationElement declaration = entry == null
                ? declarationFromReference(file, offset)
                : new Fxml2ResourceDeclarationElement(entry);
        if (declaration == null) return null;

        if (entry == null) {
            var markupFile = Fxml2ResourceDeclarations.markupFileAt(file, offset);
            entry = markupFile == null ? null : Fxml2ResourceModel.of(markupFile).resolve(declaration.getName());
        }
        TextRange declarationRange = entry == null ? null : entry.nameRangeIn(file);
        return declarationRange == null ? null : new Handler(editor, file, declaration, declarationRange);
    }

    private static @Nullable Fxml2ResourceDeclarationElement declarationFromReference(
            @NotNull PsiFile file, int offset) {
        Fxml2AttributeValueAtOffset position = Fxml2AttributeValueAtOffset.find(file, offset);
        if (position == null) return null;
        return position.referencesAt(Fxml2ResourceNameReference.class).stream()
                .map(Fxml2ResourceNameReference::resolve)
                .filter(Fxml2ResourceDeclarationElement.class::isInstance)
                .map(Fxml2ResourceDeclarationElement.class::cast)
                .findFirst()
                .orElse(null);
    }

    private static final class Handler extends HighlightUsagesHandlerBase<Fxml2ResourceDeclarationElement> {
        private final Fxml2ResourceDeclarationElement declaration;
        private final TextRange declarationRange;

        private Handler(@NotNull Editor editor, @NotNull PsiFile file,
                        @NotNull Fxml2ResourceDeclarationElement declaration,
                        @NotNull TextRange declarationRange) {
            super(editor, file);
            this.declaration = declaration;
            this.declarationRange = declarationRange;
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
        public void computeUsages(@NotNull List<? extends Fxml2ResourceDeclarationElement> targets) {
            myReadUsages.add(declarationRange);
            ReferencesSearch.search(declaration, new LocalSearchScope(myFile)).forEach(reference -> {
                PsiElement element = reference.getElement();
                TextRange range = reference.getRangeInElement().shiftRight(element.getTextRange().getStartOffset());
                InjectedLanguageManager manager = InjectedLanguageManager.getInstance(element.getProject());
                myReadUsages.add(element.getContainingFile().equals(myFile)
                        ? range : manager.injectedToHost(element, range));
                return true;
            });
            buildStatusText(declaration.getName(), myReadUsages.size() - 1);
        }
    }
}
