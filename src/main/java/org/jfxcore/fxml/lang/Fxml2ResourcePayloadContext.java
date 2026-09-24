// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

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
import org.jfxcore.fxml.resource.Fxml2ResourceInstructionParser;
import org.jfxcore.fxml.resource.Fxml2ResourceModel;
import org.jfxcore.fxml.resource.Fxml2ResourceParseResult;
import org.jfxcore.fxml.resource.Fxml2ResourcePayloadLanguage;

import java.util.List;

/** The owning declaration and coordinates of a resource payload at an editor position. */
record Fxml2ResourcePayloadContext(@NotNull XmlFile markupFile,
                                   @NotNull PsiFile topLevelFile,
                                   @NotNull PsiLanguageInjectionHost textOwner,
                                   @NotNull Fxml2ResourceEntry entry,
                                   @NotNull TextRange rawRange,
                                   @NotNull TextRange injectionRange,
                                   @NotNull Fxml2ResourcePayloadMarker marker) {

    static @Nullable Fxml2ResourcePayloadContext find(@NotNull PsiFile file, int offset) {
        InjectedLanguageManager manager = InjectedLanguageManager.getInstance(file.getProject());
        PsiFile topLevelFile = manager.getTopLevelFile(file);
        int hostOffset = manager.isInjectedFragment(file) ? manager.injectedToHost(file, offset) : offset;

        PsiFile payloadFile = markedFileAt(manager, file, topLevelFile, hostOffset);
        Fxml2ResourcePayloadMarker marker = payloadFile != null
                ? Fxml2ResourcePayloadMarker.of(payloadFile)
                : null;
        PsiLanguageInjectionHost owner = payloadFile != null
                ? manager.getInjectionHost(payloadFile)
                : ownerAt(manager, file, topLevelFile, hostOffset);
        if (owner == null) return null;
        XmlFile markupFile = markupFileOf(manager, file, topLevelFile, owner);
        if (markupFile == null) return null;

        for (Fxml2ResourceEntry entry : Fxml2ResourceModel.of(markupFile).entries()) {
            if (!entry.declaration().hasName()) continue;
            if (marker != null && !entry.name().equals(marker.name())) continue;
            TextRange rawRange = entry.fileRangeOf(entry.declaration().payloadSpan());
            if (rawRange.getStartOffset() <= hostOffset && hostOffset <= rawRange.getEndOffset()) {
                TextRange injectionRange = entry.fileRangeOf(
                        entry.declaration().injectionSpan(owner.getText()));
                if (marker == null) {
                    marker = new Fxml2ResourcePayloadMarker(
                            entry.name(), Fxml2ResourcePayloadLanguage.of(entry.declaration()));
                }
                return new Fxml2ResourcePayloadContext(
                        markupFile, topLevelFile, owner, entry, rawRange, injectionRange, marker);
            }
        }

        return incompleteAt(markupFile, topLevelFile, owner, hostOffset, marker);
    }

    @NotNull Fxml2ResourcePayloadLanguage language() {
        return marker.language();
    }

    int payloadStart() {
        return rawRange.getStartOffset();
    }

    int toHostOffset(@NotNull PsiFile file, int offset) {
        return InjectedLanguageManager.getInstance(file.getProject()).isInjectedFragment(file)
                ? InjectedLanguageManager.getInstance(file.getProject()).injectedToHost(file, offset)
                : offset;
    }

    @NotNull TextRange toHostRange(@NotNull PsiFile file, @NotNull TextRange range) {
        return TextRange.create(
                toHostOffset(file, range.getStartOffset()),
                toHostOffset(file, range.getEndOffset()));
    }

    boolean isPayloadFile(@NotNull PsiFile file) {
        if (Fxml2ResourcePayloadMarker.of(file) == null) return false;
        InjectedLanguageManager manager = InjectedLanguageManager.getInstance(file.getProject());
        return manager.isInjectedFragment(file)
                && manager.injectedToHost(file, 0) == injectionRange.getStartOffset();
    }

    private static @Nullable PsiFile markedFileAt(@NotNull InjectedLanguageManager manager,
                                                  @NotNull PsiFile file,
                                                  @NotNull PsiFile topLevelFile,
                                                  int hostOffset) {
        if (Fxml2ResourcePayloadMarker.of(file) != null) return file;
        PsiElement injected = manager.findInjectedElementAt(topLevelFile, hostOffset);
        if (injected == null) return null;
        PsiFile injectedFile = injected.getContainingFile();
        return Fxml2ResourcePayloadMarker.of(injectedFile) != null ? injectedFile : null;
    }

    private static @Nullable XmlFile markupFileOf(@NotNull InjectedLanguageManager manager,
                                                  @NotNull PsiFile file,
                                                  @NotNull PsiFile topLevelFile,
                                                  @NotNull PsiLanguageInjectionHost owner) {
        if (file instanceof XmlFile xmlFile && Fxml2FileType.isFxml2(xmlFile)) return xmlFile;
        if (topLevelFile instanceof XmlFile xmlFile && Fxml2FileType.isFxml2(xmlFile)) return xmlFile;

        @Nullable List<Pair<PsiElement, TextRange>> fragments = manager.getInjectedPsiFiles(owner);
        if (fragments == null) return null;
        for (Pair<PsiElement, TextRange> fragment : fragments) {
            PsiFile fragmentFile = fragment.first.getContainingFile();
            if (fragmentFile instanceof XmlFile xmlFile && Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile)) {
                return xmlFile;
            }
        }
        return null;
    }

    private static @Nullable PsiLanguageInjectionHost ownerAt(
            @NotNull InjectedLanguageManager manager,
            @NotNull PsiFile file,
            @NotNull PsiFile topLevelFile,
            int hostOffset) {
        if (manager.isInjectedFragment(file)) {
            PsiLanguageInjectionHost owner = manager.getInjectionHost(file);
            if (owner != null) return owner;
        }

        PsiElement element = topLevelFile.findElementAt(Math.clamp(
                hostOffset, 0, Math.max(0, topLevelFile.getTextLength() - 1)));
        return PsiTreeUtil.getParentOfType(element, PsiLanguageInjectionHost.class, false);
    }

    private static @Nullable Fxml2ResourcePayloadContext incompleteAt(
            @NotNull XmlFile markupFile,
            @NotNull PsiFile topLevelFile,
            @NotNull PsiLanguageInjectionHost owner,
            int hostOffset,
            @Nullable Fxml2ResourcePayloadMarker marker) {
        int ownerStart = owner.getTextRange().getStartOffset();
        Fxml2ResourceParseResult result = Fxml2ResourceInstructionParser.parseContaining(
                owner.getText(), hostOffset - ownerStart);
        if (result == null || !result.declaration().hasName()) return null;
        Fxml2ResourceEntry entry = new Fxml2ResourceEntry(result, owner);
        TextRange rawRange = entry.fileRangeOf(entry.declaration().payloadSpan());
        if (hostOffset < rawRange.getStartOffset() || hostOffset > rawRange.getEndOffset()) return null;

        Fxml2ResourcePayloadMarker actualMarker = marker != null
                ? marker
                : new Fxml2ResourcePayloadMarker(
                        entry.name(), Fxml2ResourcePayloadLanguage.of(entry.declaration()));
        TextRange injectionRange = entry.fileRangeOf(entry.declaration().injectionSpan(owner.getText()));
        return new Fxml2ResourcePayloadContext(
                markupFile, topLevelFile, owner, entry, rawRange, injectionRange, actualMarker);
    }
}
