package org.jfxcore.fxml.highlighting;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.PsiRecursiveElementWalkingVisitor;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.lang.Fxml2FileType;

import java.util.List;
import java.util.ArrayList;

/** Runs symbol classification after regular annotation passes and applies scheme keys. */
public final class Fxml2SemanticAnnotator
        extends ExternalAnnotator<Fxml2SemanticAnnotator.Input, Fxml2SemanticAnnotator.Result>
        implements DumbAware {

    public record Input(@NotNull SmartPsiElementPointer<PsiFile> file, long modificationStamp,
                        @NotNull List<SmartPsiElementPointer<XmlFile>> markup) {
        public Input {
            markup = List.copyOf(markup);
        }
    }

    public record Result(long modificationStamp, @NotNull List<Fxml2SemanticSpan> spans) {
        public Result {
            spans = List.copyOf(spans);
        }
    }

    @Override
    public @Nullable Input collectInformation(@NotNull PsiFile file, @NotNull Editor editor, boolean hasErrors) {
        return collectInformation(file);
    }

    @Override
    public @Nullable Input collectInformation(@NotNull PsiFile file) {
        List<SmartPsiElementPointer<XmlFile>> markup = new ArrayList<>();
        if (file instanceof XmlFile xml) {
            if (Fxml2FileType.isFxml2(file)) markup.add(SmartPointerManager.createPointer(xml));
        } else {
            InjectedLanguageManager manager = InjectedLanguageManager.getInstance(file.getProject());
            file.accept(new PsiRecursiveElementWalkingVisitor() {
                @Override
                public void visitElement(@NotNull PsiElement element) {
                    ProgressManager.checkCanceled();
                    if (element instanceof PsiLanguageInjectionHost) {
                        var injections = manager.getInjectedPsiFiles(element);
                        if (injections != null) {
                            for (var injection : injections) {
                                if (injection.first instanceof XmlFile xml && Fxml2FileType.isFxml2(xml)) {
                                    markup.add(SmartPointerManager.createPointer(xml));
                                }
                            }
                        }
                    }
                    super.visitElement(element);
                }
            });
        }
        return markup.isEmpty() ? null : new Input(
                SmartPointerManager.createPointer(file), file.getModificationStamp(), markup.stream().distinct().toList());
    }

    @Override
    public @Nullable Result doAnnotate(Input input) {
        return ReadAction.nonBlocking(() -> {
            PsiFile file = input.file().getElement();
            if (file == null || file.getModificationStamp() != input.modificationStamp()) return null;
            InjectedLanguageManager manager = InjectedLanguageManager.getInstance(file.getProject());
            List<Fxml2SemanticSpan> spans = new ArrayList<>();
            for (var pointer : input.markup()) {
                ProgressManager.checkCanceled();
                XmlFile markup = pointer.getElement();
                if (markup == null) return null;
                for (Fxml2SemanticSpan span : Fxml2SemanticAnalyzer.analyze(markup)) {
                    if (markup == file) {
                        spans.add(span);
                    } else {
                        for (var editable : manager.intersectWithAllEditableFragments(markup, span.range())) {
                            spans.add(new Fxml2SemanticSpan(manager.injectedToHost(markup, editable), span.role()));
                        }
                    }
                }
            }
            return new Result(input.modificationStamp(), spans);
        }).expireWhen(() -> input.file().getProject().isDisposed()).executeSynchronously();
    }

    @Override
    public void apply(@NotNull PsiFile file, Result result, @NotNull AnnotationHolder holder) {
        if (file.getModificationStamp() != result.modificationStamp()) return;
        for (Fxml2SemanticSpan span : result.spans()) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(span.range()).enforcedTextAttributes(TextAttributes.ERASE_MARKER).create();
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(span.range()).textAttributes(span.role().key()).create();
        }
    }
}
