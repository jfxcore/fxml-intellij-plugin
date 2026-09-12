package org.jfxcore.fxml.lang;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resource.Fxml2ResourceEntry;
import org.jfxcore.fxml.resource.Fxml2ResourceModel;

import java.util.List;

/** Finds resource declarations across host-file and injected-file coordinate spaces. */
final class Fxml2ResourceDeclarations {

    private Fxml2ResourceDeclarations() {}

    static @Nullable Fxml2ResourceEntry at(@NotNull PsiFile file, int offset) {
        XmlFile markupFile = markupFileAt(file, offset);
        if (markupFile == null) return null;

        for (Fxml2ResourceEntry entry : Fxml2ResourceModel.of(markupFile).entries()) {
            TextRange nameRange = entry.nameRangeIn(file);
            if (nameRange != null && nameRange.containsOffset(offset)) return entry;
        }
        return null;
    }

    static @Nullable Fxml2ResourceEntry within(@NotNull PsiElement element) {
        PsiFile coordinateFile = element.getContainingFile();
        TextRange elementRange = element.getTextRange();
        if (elementRange == null) return null;

        XmlFile markupFile = markupFileOf(element);
        if (markupFile == null) return null;
        for (Fxml2ResourceEntry entry : Fxml2ResourceModel.of(markupFile).entries()) {
            TextRange nameRange = entry.nameRangeIn(coordinateFile);
            if (nameRange != null && elementRange.contains(nameRange)) return entry;
        }
        return null;
    }

    static @Nullable XmlFile markupFileAt(@NotNull PsiFile file, int offset) {
        XmlFile direct = Fxml2FileType.asFxml2(file);
        if (direct != null) return direct;

        PsiElement injected = InjectedLanguageManager.getInstance(file.getProject())
                .findInjectedElementAt(file, offset);
        return injected == null ? null : Fxml2FileType.asFxml2(injected.getContainingFile());
    }

    static @Nullable XmlFile markupFileOf(@NotNull PsiElement element) {
        PsiFile file = element.getContainingFile();
        XmlFile direct = Fxml2FileType.asFxml2(file);
        if (direct != null) return direct;

        PsiLanguageInjectionHost host = element instanceof PsiLanguageInjectionHost candidate
                ? candidate
                : PsiTreeUtil.getParentOfType(element, PsiLanguageInjectionHost.class, false);
        if (host == null) return null;

        List<Pair<PsiElement, TextRange>> fragments = InjectedLanguageManager
                .getInstance(element.getProject()).getInjectedPsiFiles(host);
        if (fragments == null) return null;
        return fragments.stream()
                .map(fragment -> fragment.first)
                .filter(XmlFile.class::isInstance)
                .map(XmlFile.class::cast)
                .filter(Fxml2FileType::isFxml2)
                .findFirst()
                .orElse(null);
    }
}
