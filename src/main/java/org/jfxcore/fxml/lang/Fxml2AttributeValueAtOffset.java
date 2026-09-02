package org.jfxcore.fxml.lang;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The attribute value of an FXML/2 document that a caret offset points into, together with that
 * offset expressed in the coordinates of the XML file the attribute value belongs to.
 *
 * <p>Both document forms are covered: a standalone FXML/2 file, where the offset is already in
 * the coordinates of the XML file, and markup embedded in a {@code @ComponentView} annotation
 * value, where the offset given by the platform may still be in the coordinates of the host
 * file and is translated to the injected fragment here.
 *
 * @param attributeValue the attribute value the offset points into
 * @param offsetInXmlFile the offset as a position in {@code attributeValue}'s file
 */
public record Fxml2AttributeValueAtOffset(@NotNull XmlAttributeValue attributeValue,
                                          int offsetInXmlFile) {

    /**
     * Returns the FXML/2 attribute value at {@code offset} in {@code file}, or {@code null} when
     * the offset points at no attribute value or the document is not FXML/2.
     */
    public static @Nullable Fxml2AttributeValueAtOffset find(@NotNull PsiFile file, int offset) {
        XmlAttributeValue attributeValue = findAttributeValue(file, offset);
        if (attributeValue == null) return null;

        PsiFile xmlFile = attributeValue.getContainingFile();
        if (!(xmlFile instanceof XmlFile xml) || !Fxml2FileType.isFxml2(xml)) return null;

        int offsetInXmlFile;
        if (xmlFile == file) {
            offsetInXmlFile = offset;
        } else {
            PsiElement injected = InjectedLanguageManager.getInstance(file.getProject())
                    .findInjectedElementAt(file, offset);
            if (injected == null) return null;
            offsetInXmlFile = injected.getTextRange().getStartOffset();
        }

        return new Fxml2AttributeValueAtOffset(attributeValue, offsetInXmlFile);
    }

    /** Returns the offset as a position within {@link #attributeValue}. */
    public int offsetInAttributeValue() {
        return offsetInXmlFile - attributeValue.getTextRange().getStartOffset();
    }

    private static @Nullable XmlAttributeValue findAttributeValue(@NotNull PsiFile file, int offset) {
        // In a standalone document, and whenever the platform calls in on the injected fragment
        // of embedded markup, the element at the offset is already an XML token.
        PsiElement contextElement = file.findElementAt(offset);
        if (contextElement != null) {
            XmlAttributeValue attributeValue =
                    PsiTreeUtil.getParentOfType(contextElement, XmlAttributeValue.class, false);
            if (attributeValue != null) return attributeValue;
        }

        // Otherwise the offset is a position in the host file of embedded markup.
        PsiElement injected = InjectedLanguageManager.getInstance(file.getProject())
                .findInjectedElementAt(file, offset);
        return injected == null
                ? null
                : PsiTreeUtil.getParentOfType(injected, XmlAttributeValue.class, false);
    }
}
