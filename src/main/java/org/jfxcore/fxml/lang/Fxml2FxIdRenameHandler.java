package org.jfxcore.fxml.lang;

import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.refactoring.rename.RenameHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2BindingPathResolver;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;

/**
 * Rename handler for {@code fx:id} attribute values and binding-path segments in FXML files.
 *
 * <p>Enables the rename refactoring (Shift+F6) when the caret is at:
 * <ul>
 *   <li>An {@code fx:id} attribute value (e.g. {@code myButton} inside
 *       {@code fx:id="myButton"}), or</li>
 *   <li>A binding-path segment that resolves back to an {@code fx:id} declaration
 *       (e.g. {@code myButton} inside {@code ${myButton.text}}).</li>
 * </ul>
 *
 * <p>When a matching code-behind field exists (resolved via {@code fx:subclass}), the rename
 * is delegated to that field.  This causes the field's rename to cascade via
 * {@link Fxml2FxIdReference#isReferenceTo} and
 * {@link Fxml2BindingSegmentReference#isReferenceTo} to update all occurrences: including
 * the {@code fx:id} value itself, all binding-expression uses in the FXML file, and the
 * field name in the code-behind class.
 *
 * <p>When no code-behind field is found, the {@code fx:id} {@link XmlAttributeValue} is
 * renamed directly; {@link Fxml2FxIdRenameProcessor} then updates any binding-path usages
 * within the same file.
 */
public final class Fxml2FxIdRenameHandler implements RenameHandler {

    @Override
    public boolean isAvailableOnDataContext(@NotNull DataContext dataContext) {
        return findFxIdAttrVal(dataContext) != null;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file, DataContext dataContext) {
        XmlAttributeValue fxIdVal = findFxIdAttrVal(dataContext);
        if (fxIdVal == null) return;

        // Prefer to rename the code-behind field: its rename cascades back to all FXML
        // occurrences (fx:id value and binding expressions) via isReferenceTo().
        PsiField codeBehindField = resolveCodeBehindField(fxIdVal);
        PsiElement toRename = codeBehindField != null ? codeBehindField : fxIdVal;
        Fxml2RenameHandlerUtil.doRename(project, editor, toRename, dataContext);
    }

    @Override
    public void invoke(@NotNull Project project, PsiElement @NotNull [] elements, DataContext dataContext) {
        // Editor-based invocation only; this overload is not used.
    }

    // -----------------------------------------------------------------------
    // Internal helpers (package-private for testing)
    // -----------------------------------------------------------------------

    /**
     * Returns the {@code fx:id} {@link XmlAttributeValue} that the caret is logically "on":
     * <ol>
     *   <li>If the caret is inside a {@link Fxml2BindingSegmentReference} whose resolved
     *       {@code LightFieldBuilder} navigates to an {@link XmlAttributeValue}, that
     *       attribute value (the declaration site) is returned.</li>
     *   <li>Otherwise, if the caret is inside an {@code fx:id} attribute value in an FXML
     *       file, that attribute value is returned directly.</li>
     * </ol>
     *
     * @return the logical fx:id declaration, or {@code null} if neither condition applies
     */
    static @Nullable XmlAttributeValue findFxIdAttrVal(@NotNull DataContext dataContext) {
        Editor editor = CommonDataKeys.EDITOR.getData(dataContext);
        PsiFile file = CommonDataKeys.PSI_FILE.getData(dataContext);
        if (editor == null || !(file instanceof XmlFile xmlFile)) return null;
        if (!Fxml2FileType.isFxml2(xmlFile)) return null;

        int offset = editor.getCaretModel().getOffset();

        // Look for the XmlAttributeValue that contains the caret.
        PsiElement elementAtOffset = file.findElementAt(offset);
        XmlAttributeValue attrValAtCaret = PsiTreeUtil.getParentOfType(
                elementAtOffset, XmlAttributeValue.class, false);

        if (attrValAtCaret != null) {
            int relativeOffset = offset - attrValAtCaret.getTextRange().getStartOffset();

            // Case 1: caret is on a Fxml2BindingSegmentReference that resolves to a
            // synthetic field whose navigation element is an fx:id XmlAttributeValue.
            // We iterate all references instead of using file.findReferenceAt(), which would
            // return the first reference matching the offset (e.g. Fxml2ExpressionReference),
            // not necessarily the BindingSegmentReference.
            for (PsiReference ref : attrValAtCaret.getReferences()) {
                if (!(ref instanceof Fxml2BindingSegmentReference segRef)) continue;
                if (!segRef.getRangeInElement().containsOffset(relativeOffset)) continue;
                PsiElement resolved = segRef.resolve();
                if (resolved instanceof PsiField field) {
                    PsiElement navEl = field.getNavigationElement();
                    if (navEl instanceof XmlAttributeValue fxIdAttrVal
                            && fxIdAttrVal.getParent() instanceof XmlAttribute fxIdAttr
                            && "id".equals(fxIdAttr.getLocalName())
                            && Fxml2ImportResolver.FXML2_NAMESPACE.equals(fxIdAttr.getNamespace())) {
                        return fxIdAttrVal;
                    }
                }
            }

            // Case 2: caret is directly on an fx:id attribute value.
            if (attrValAtCaret.getParent() instanceof XmlAttribute attr
                    && "id".equals(attr.getLocalName())
                    && Fxml2ImportResolver.FXML2_NAMESPACE.equals(attr.getNamespace())) {
                return attrValAtCaret;
            }
        }

        return null;
    }

    /**
     * Returns the code-behind field whose name matches the given {@code fx:id} value,
     * or {@code null} if no code-behind class is declared or the field is not found.
     */
    private static @Nullable PsiField resolveCodeBehindField(@NotNull XmlAttributeValue fxIdVal) {
        if (!(fxIdVal.getContainingFile() instanceof XmlFile xmlFile)) return null;
        String idName = fxIdVal.getValue();
        if (idName.isBlank()) return null;
        var codeBehind = Fxml2BindingPathResolver.resolveCodeBehindClass(xmlFile);
        if (codeBehind == null) return null;
        return codeBehind.findFieldByName(idName, true);
    }
}
