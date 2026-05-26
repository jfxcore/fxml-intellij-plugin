// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.refactoring.rename.RenameHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.descriptors.Fxml2PropertyAttributeDescriptor;

/**
 * Rename handler for property attribute names in standalone and embedded FXML files.
 *
 * <p>When Shift+F6 is pressed with the caret on an XML attribute name that maps to a
 * JavaFX/JavaBeans property (e.g. {@code formatter} in
 * {@code <CustomLabel formatter="$fn"/>}), IntelliJ's default XML rename would resolve
 * the attribute to its accessor declaration via
 * {@link Fxml2PropertyAttributeDescriptor#getDeclaration()} (typically the setter, e.g.
 * {@code setFormatter}) and start an in-place rename pre-filled with the accessor name.
 * This would immediately replace the property name {@code formatter} with the setter
 * name {@code setFormatter} in the editor before the user even starts typing.
 *
 * <p>This handler intercepts the rename to instead redirect it to the Java backing
 * field (whose name equals the property name, e.g. {@code formatter}).  Renaming the
 * backing field is correct because:
 * <ul>
 *   <li>The field's name equals the property name, so the rename dialog is pre-filled
 *       with {@code formatter} rather than {@code setFormatter}.</li>
 *   <li>IntelliJ's standard Java field rename automatically offers to rename the
 *       accessor triad (getter, setter, {@code xProperty()} method).</li>
 *   <li>When each triad member is renamed, {@link Fxml2PropertyAttributeSearcher}
 *       emits a reference whose {@code handleElementRename} strips the accessor prefix
 *       to derive the new property name, keeping the attribute name in sync.</li>
 * </ul>
 *
 * <p>When no backing field can be found, the handler falls back to renaming the
 * accessor method that {@link Fxml2PropertyAttributeDescriptor#getDeclaration()}
 * returned.
 */
public final class Fxml2PropertyAttributeRenameHandler implements RenameHandler {

    @Override
    public boolean isAvailableOnDataContext(@NotNull DataContext dataContext) {
        return findPropertyAttribute(dataContext) != null;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file,
                       DataContext dataContext) {
        XmlAttribute attr = findPropertyAttribute(dataContext);
        if (attr == null) return;

        PsiElement toRename = findRenameTarget(attr);
        if (toRename == null) return;
        Fxml2RenameHandlerUtil.doRename(project, editor, toRename, dataContext);
    }

    @Override
    public void invoke(@NotNull Project project, PsiElement @NotNull [] elements,
                       DataContext dataContext) {
        // Editor-based invocation only; this overload is not used.
    }

    // -----------------------------------------------------------------------
    // Internal helpers (package-private for testing)
    // -----------------------------------------------------------------------

    /**
     * Returns the property-attribute {@link XmlAttribute} at the caret position, or
     * {@code null} if the caret is not on a property attribute name in an FXML file.
     *
     * <p>Returns {@code null} when:
     * <ul>
     *   <li>The file is not an FXML file.</li>
     *   <li>The caret is on the attribute value rather than the name.</li>
     *   <li>The attribute has a namespace prefix (e.g. {@code fx:id}).</li>
     *   <li>The attribute name contains a dot (static property, e.g.
     *       {@code GridPane.rowIndex}).</li>
     *   <li>The attribute does not have an {@link Fxml2PropertyAttributeDescriptor}.</li>
     * </ul>
     */
    public static @Nullable XmlAttribute findPropertyAttribute(@NotNull DataContext dataContext) {
        Editor editor = CommonDataKeys.EDITOR.getData(dataContext);
        PsiFile file = CommonDataKeys.PSI_FILE.getData(dataContext);
        if (editor == null || !(file instanceof XmlFile xmlFile)) return null;
        if (!Fxml2FileType.isFxml2(xmlFile)) return null;

        int offset = editor.getCaretModel().getOffset();
        PsiElement elementAtOffset = file.findElementAt(offset);
        if (elementAtOffset == null) return null;

        XmlAttribute attr = PsiTreeUtil.getParentOfType(elementAtOffset, XmlAttribute.class, false);
        if (attr == null) return null;

        // Caret must be on the name part: before the '=' and attribute value.
        XmlAttributeValue valueElement = attr.getValueElement();
        if (valueElement != null && offset >= valueElement.getTextRange().getStartOffset()) {
            return null;
        }

        // Skip prefixed attributes (fx:id, fx:type, ...) and dotted static properties.
        if (!attr.getNamespacePrefix().isEmpty()) return null;
        if (attr.getLocalName().contains(".")) return null;

        // Must resolve to a property-attribute descriptor.
        if (!(attr.getDescriptor() instanceof Fxml2PropertyAttributeDescriptor)) return null;

        return attr;
    }

    /**
     * Returns the best rename target for a property attribute.
     *
     * <p>Preference order:
     * <ol>
     *   <li>If the declaration is already a {@link PsiField}, rename it directly.</li>
     *   <li>If the declaration is a {@link PsiMethod}, look for a field with the same
     *       name as the property (= the attribute local name) in the declaring class.
     *       Renaming the field causes IntelliJ to also offer renaming the accessor
     *       triad, which is the desired UX.</li>
     *   <li>If no matching field exists, fall back to the accessor method returned by
     *       {@link Fxml2PropertyAttributeDescriptor#getDeclaration()}.</li>
     * </ol>
     */
    public static @Nullable PsiElement findRenameTarget(@NotNull XmlAttribute attr) {
        if (!(attr.getDescriptor() instanceof Fxml2PropertyAttributeDescriptor descriptor)) {
            return null;
        }
        PsiElement decl = descriptor.getDeclaration();
        switch (decl) {
            case null -> {
                return null;
            }
            case PsiField ignored -> {
                return decl;
            }
            case PsiMethod method -> {
                String propertyName = attr.getLocalName();
                PsiClass cls = method.getContainingClass();
                if (cls != null) {
                    PsiField field = cls.findFieldByName(propertyName, false);
                    if (field != null) return field;
                }
                return method;
            }
            default -> {
            }
        }

        return decl;
    }
}
