package org.jfxcore.fxml.actions;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Quick fix that replaces the observable-selection operator {@code ::} with a plain
 * member-access separator {@code .} at a specific position within an XML attribute value.
 *
 * <p>This fix is offered wherever the fxml2 compiler would report
 * {@code INVALID_INVARIANT_REFERENCE}: a binding path segment accessed via {@code ::} whose
 * declared type is not an {@code ObservableValue} subtype.
 *
 * <p>When the FXML2 is embedded inside a Java or Kotlin {@code @ComponentView} text block
 * (an injected language fragment), the edit is applied directly to the host document at the
 * mapped host offset.  The editor caret is then restored to the same character within the
 * member name that the user's cursor occupied before the fix was invoked.  The target offset
 * is computed from the injected-document caret position saved during the most recent
 * {@link #isAvailable} call (which reliably reflects the cursor before Alt+Enter is pressed)
 * via a deferred callback that runs after both the PSI commit and the EditorWindowImpl caret
 * resync; a one-shot caret listener guards against any further resync that might fire
 * even later.  For standalone FXML2 files, {@link XmlAttribute#setValue} is used instead.
 */
public final class ReplaceObservableSelectorFix implements IntentionAction, PriorityAction {

    /** Pointer to the attribute value whose text contains the {@code ::} to replace. */
    private final SmartPsiElementPointer<XmlAttributeValue> attrValPtr;

    /**
     * Offset of the first {@code :} of the {@code ::} token within the decoded attribute
     * value string (i.e. relative to the first character after the opening quote).
     */
    private final int valueOffset;

    /**
     * The injected-document offset of the editor's caret as of the most recent
     * {@link #isAvailable} call.
     *
     * <p>{@link #isAvailable} is invoked by IntelliJ on each cursor movement while the
     * caret is within the annotated range, so the value stored here reflects the user's
     * last known cursor position before the fix is actually applied.  The offset is used
     * in {@link #applyToEmbeddedFxml} to restore the caret to the same character within
     * the member name after the {@code ::} is replaced.
     */
    private int savedInjectedCaretOffset = -1;

    private ReplaceObservableSelectorFix(
            @NotNull SmartPsiElementPointer<XmlAttributeValue> attrValPtr,
            int valueOffset) {
        this.attrValPtr = attrValPtr;
        this.valueOffset = valueOffset;
    }

    /**
     * Creates a fix for the {@code ::} operator that starts at {@code selectorDocOffset}
     * in the host document and belongs to the given attribute value element.
     *
     * @param attrVal           the attribute value PSI element containing the {@code ::}
     * @param selectorDocOffset host-document offset of the first {@code :} in {@code ::}
     * @return the fix, or {@code null} when the offset is out of range
     */
    public static @Nullable ReplaceObservableSelectorFix of(
            @NotNull XmlAttributeValue attrVal,
            int selectorDocOffset) {
        int valueOffset = selectorDocOffset - attrVal.getTextRange().getStartOffset() - 1;
        if (valueOffset < 0) return null;
        SmartPsiElementPointer<XmlAttributeValue> ptr =
                SmartPointerManager.getInstance(attrVal.getProject())
                        .createSmartPsiElementPointer(attrVal);
        return new ReplaceObservableSelectorFix(ptr, valueOffset);
    }

    /**
     * Returns the host-document offset of the {@code ::} operator that immediately precedes
     * the segment at the given host-document start offset.
     *
     * @param segmentDocStart host-document offset of the first character of the segment name
     * @return host-document offset of the first {@code :} of the preceding {@code ::}
     */
    public static int selectorOffsetBefore(int segmentDocStart) {
        return segmentDocStart - 2;
    }

    @Override public @NotNull String getText() { return "Replace '::' with '.'"; }
    @Override public @NotNull String getFamilyName() { return "Replace observable-selection operator"; }
    @Override public @NotNull Priority getPriority() { return Priority.HIGH; }
    @Override public boolean startInWriteAction() { return false; }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        XmlAttributeValue attrVal = attrValPtr.getElement();
        if (attrVal == null) return false;
        String value = attrVal.getValue();
        if (valueOffset < 0 || valueOffset + 1 >= value.length()) return false;
        if (value.charAt(valueOffset) != ':' || value.charAt(valueOffset + 1) != ':') return false;
        // Record the caret position in the injected document on every availability check.
        // This is the most recent position before the user triggers the fix, so it faithfully
        // reflects the user's cursor location within the member name.
        savedInjectedCaretOffset = editor.getCaretModel().getOffset();
        return true;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file)
            throws IncorrectOperationException {
        XmlAttributeValue attrVal = attrValPtr.getElement();
        if (attrVal == null) return;
        if (!(attrVal.getParent() instanceof XmlAttribute attr)) return;

        String value = attrVal.getValue();
        if (valueOffset < 0 || valueOffset + 2 > value.length()) return;
        if (value.charAt(valueOffset) != ':' || value.charAt(valueOffset + 1) != ':') return;

        PsiFile containingFile = attrVal.getContainingFile();
        InjectedLanguageManager ilm = InjectedLanguageManager.getInstance(project);

        if (ilm.isInjectedFragment(containingFile)) {
            applyToEmbeddedFxml(project, attrVal, containingFile, ilm);
        } else {
            String newValue = value.substring(0, valueOffset) + "." + value.substring(valueOffset + 2);
            WriteCommandAction.runWriteCommandAction(project, "Replace '::' with '.'", null,
                    () -> attr.setValue(newValue), containingFile);
        }
    }

    /**
     * Applies the fix when the FXML2 is embedded inside a Java or Kotlin
     * {@code @ComponentView} text block.
     *
     * <p>Direct host-document replacement is used instead of {@link XmlAttribute#setValue}
     * because PSI mutations on injected elements cause IntelliJ to reposition the host
     * editor's caret to the end of the injection range after the write action commits.
     *
     * <p>After the replacement the host editor's caret is restored to the character within
     * the member name that the user's cursor occupied before the fix was invoked.  The target
     * is computed from {@link #savedInjectedCaretOffset} (captured during the last
     * {@link #isAvailable} call) via {@link EmbeddedFxmlCaretRestorer#scheduleRestore}.
     */
    private void applyToEmbeddedFxml(
            @NotNull Project project,
            @NotNull XmlAttributeValue attrVal,
            @NotNull PsiFile containingFile,
            @NotNull InjectedLanguageManager ilm) {

        int injectedSelectorOffset = attrVal.getTextRange().getStartOffset() + 1 + valueOffset;
        int hostOffset = ilm.injectedToHost(containingFile, injectedSelectorOffset);
        if (hostOffset < 0) return;

        PsiFile hostFile = containingFile.getContext() != null
                ? containingFile.getContext().getContainingFile() : null;
        if (hostFile == null) return;
        Document hostDoc = PsiDocumentManager.getInstance(project).getDocument(hostFile);
        if (hostDoc == null) return;

        WriteCommandAction.runWriteCommandAction(project, "Replace '::' with '.'", null,
                () -> hostDoc.replaceString(hostOffset, hostOffset + 2, "."),
                hostFile);

        // After the replacement, position the caret at the same character within the member
        // name that the user's cursor was at before the fix was applied.
        //
        // savedInjectedCaretOffset was captured during the last isAvailable() call, which
        // runs while the user moves the caret within the annotated range and therefore
        // reliably reflects the cursor position before Alt+Enter is pressed.
        // After the replacement "::" -> ".", the member name shifts one character to the left
        // in the host document (hostOffset + 1), so the intra-member relative offset is preserved.
        int injectedMemberStart = injectedSelectorOffset + 2;
        String memberName = attrVal.getValue().substring(valueOffset + 2);
        int relativeOffset = (savedInjectedCaretOffset >= injectedMemberStart)
                ? Math.min(savedInjectedCaretOffset - injectedMemberStart, memberName.length())
                : 0;

        EmbeddedFxmlCaretRestorer.scheduleRestore(project, hostFile, hostOffset + 1 + relativeOffset);
    }
}
