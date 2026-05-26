package org.jfxcore.fxml.actions;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlToken;
import com.intellij.psi.xml.XmlTokenType;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser.ParsedExpression;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;
import org.jfxcore.fxml.resolve.Fxml2XmlUtil;

/**
 * Two inverse intention actions for converting between attribute notation and
 * short element notation for FXML properties.
 *
 * <p>Binding compact-notation mapping (attribute <-> element):
 * <ul>
 *   <li>{@code $source}          <-> {@code <fx:Evaluate source="source"/>}</li>
 *   <li>{@code ${source}}        <-> {@code <fx:Observe source="source"/>}</li>
 *   <li>{@code >{source}}        <-> {@code <fx:Push source="source"/>}</li>
 *   <li>{@code #{source}}        <-> {@code <fx:Synchronize source="source"/>}</li>
 *   <li>{@code $..source}        <-> {@code <fx:Evaluate source="..source"/>}</li>
 *   <li>{@code ${..source}}      <-> {@code <fx:Observe source="..source"/>}</li>
 *   <li>{@code >{..source}}      <-> {@code <fx:Push source="..source"/>}</li>
 *   <li>{@code #{..source}}      <-> {@code <fx:Synchronize source="..source"/>}</li>
 * </ul>
 */
public final class ConvertPropertyNotationIntention {

    private ConvertPropertyNotationIntention() {}

    // -----------------------------------------------------------------------
    // Attribute -> Short element notation
    // -----------------------------------------------------------------------

    /** Converts an attribute to short element notation. */
    public static final class ToElement implements IntentionAction, PriorityAction {

        @Override public @NotNull String getText() { return "Convert to element notation"; }
        @Override public @NotNull String getFamilyName() { return "Convert property notation"; }

        @Override
        public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
            if (!(file instanceof XmlFile)) return false;
            if (!Fxml2FileType.isFxml2(file)) return false;
            return findTargetAttribute(editor, file) != null;
        }

        @Override
        public void invoke(@NotNull Project project, Editor editor, PsiFile file)
                throws IncorrectOperationException {
            XmlAttribute attr = findTargetAttribute(editor, file);
            if (attr == null) return;
            WriteCommandAction.runWriteCommandAction(project, "Convert to Element Notation", null,
                    () -> convertAttributeToElement(attr, project), file);
        }

        @Override public boolean startInWriteAction() { return false; }
        @Override public @NotNull Priority getPriority() { return Priority.NORMAL; }

        private static @Nullable XmlAttribute findTargetAttribute(@Nullable Editor editor, @NotNull PsiFile file) {
            if (editor == null) return null;
            PsiElement el = file.findElementAt(editor.getCaretModel().getOffset());
            if (el == null) return null;
            XmlAttribute attr = findAttributeAt(el);
            return isConvertibleAttribute(attr) ? attr : null;
        }
    }

    // -----------------------------------------------------------------------
    // Short/Qualified element notation -> Attribute notation
    // -----------------------------------------------------------------------

    /** Converts a foldable property element back to an attribute. */
    public static final class ToAttribute implements IntentionAction, PriorityAction {

        @Override public @NotNull String getText() { return "Convert to attribute notation"; }
        @Override public @NotNull String getFamilyName() { return "Convert property notation"; }

        @Override
        public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
            if (!(file instanceof XmlFile)) return false;
            if (!Fxml2FileType.isFxml2(file)) return false;
            return findTargetTag(editor, file) != null;
        }

        @Override
        public void invoke(@NotNull Project project, Editor editor, PsiFile file)
                throws IncorrectOperationException {
            XmlTag tag = findTargetTag(editor, file);
            if (tag == null) return;
            WriteCommandAction.runWriteCommandAction(project, "Convert to Attribute Notation", null,
                    () -> convertElementToAttribute(tag, project), file);
        }

        @Override public boolean startInWriteAction() { return false; }
        @Override public @NotNull Priority getPriority() { return Priority.NORMAL; }

        private static @Nullable XmlTag findTargetTag(@Nullable Editor editor, @NotNull PsiFile file) {
            if (editor == null) return null;
            PsiElement el = file.findElementAt(editor.getCaretModel().getOffset());
            if (el == null) return null;
            XmlTag tag = findTagAt(el);
            return isFoldablePropertyTag(tag) ? tag : null;
        }
    }

    // -----------------------------------------------------------------------
    // Core conversion: attribute -> element
    // -----------------------------------------------------------------------

    /**
     * Converts {@code attr} to a short element child of its parent tag, then
     * asks IntelliJ's code formatter to reformat the parent tag so whitespace
     * is correct.
     */
    static void convertAttributeToElement(@NotNull XmlAttribute attr, @NotNull Project project) {
        XmlTag parentTag = attr.getParent();
        if (parentTag == null) return;

        String attrName  = attr.getName();
        String attrValue = attr.getValue();
        if (attrValue == null) attrValue = "";
        ParsedExpression expr = Fxml2BindingExpressionParser.parseExpression(attrValue);

        // Remove the attribute from the parent tag
        attr.delete();

        // After deletion the '>' of the opening tag may now be the only thing on its line
        // (e.g. when the attribute was the last one on a continuation line).  The formatter
        // does not collapse that, so we do it explicitly via the document before reformatting.
        PsiDocumentManager docMgr = PsiDocumentManager.getInstance(project);
        docMgr.commitAllDocuments();
        Document doc = docMgr.getDocument(parentTag.getContainingFile());
        if (doc != null) {
            collapseOrphanedTagClose(doc, parentTag);
            docMgr.commitDocument(doc);
        }

        try {
            if (expr != null) {
                // Binding: <propName><fx:KIND source="SOURCE"/></propName>
                String fxLocalName = ConvertBindingNotationIntention.kindToKeyword(expr.kind());
                String path        = expr.path();

                XmlTag innerTag = parentTag.createChildTag(
                        fxLocalName, Fxml2ImportResolver.FXML2_NAMESPACE, null, true);
                innerTag.setAttribute("source", path);

                XmlTag propTag = parentTag.createChildTag(attrName, "", null, false);
                XmlTag[] existing = parentTag.getSubTags();
                XmlTag inserted = existing.length > 0
                        ? (XmlTag) parentTag.addBefore(propTag, existing[0])
                        : parentTag.addSubTag(propTag, true);
                inserted.addSubTag(innerTag, true);
            } else {
                // Plain value: <propName>ESCAPED_VALUE</propName>
                XmlTag propTag = parentTag.createChildTag(
                        attrName, "", escapeXmlContent(attrValue), false);
                XmlTag[] existing = parentTag.getSubTags();
                if (existing.length > 0) {
                    parentTag.addBefore(propTag, existing[0]);
                } else {
                    parentTag.addSubTag(propTag, true);
                }
            }
        } catch (Exception ignored) {
            return;
        }

        // Let IntelliJ reformat the parent tag to fix indentation / whitespace
        docMgr.commitAllDocuments();
        CodeStyleManager.getInstance(project).reformat(parentTag);
    }

    /**
     * If the {@code >} or {@code />} that closes the opening tag of {@code tag} is currently
     * the first non-whitespace character on its line (i.e. it was left stranded after an
     * attribute deletion), remove the newline and leading whitespace before it so it joins
     * the previous line.
     */
    private static void collapseOrphanedTagClose(@NotNull Document doc, @NotNull XmlTag tag) {
        // Find the > / /> token
        int tagCloseOffset = -1;
        for (PsiElement c = tag.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof XmlToken ct
                    && (ct.getTokenType() == XmlTokenType.XML_TAG_END
                        || ct.getTokenType() == XmlTokenType.XML_EMPTY_ELEMENT_END)) {
                tagCloseOffset = ct.getTextRange().getStartOffset();
                break;
            }
        }
        if (tagCloseOffset < 0) return;

        String text = doc.getText();
        // Walk backwards from the close token past any spaces/tabs on the same line
        int pos = tagCloseOffset - 1;
        while (pos >= 0 && (text.charAt(pos) == ' ' || text.charAt(pos) == '\t')) pos--;
        // If we hit a newline, the close token is the only thing on this line -> collapse it
        if (pos >= 0 && text.charAt(pos) == '\n') {
            // Delete from the newline up to (but not including) the close token
            doc.deleteString(pos, tagCloseOffset);
        }
    }

    // -----------------------------------------------------------------------
    // Core conversion: element -> attribute
    // -----------------------------------------------------------------------

    /**
     * Converts a property element tag back to an attribute on its parent, then
     * reformats the parent tag.
     */
    static void convertElementToAttribute(@NotNull XmlTag propTag, @NotNull Project project) {
        XmlTag parentTag = propTag.getParentTag();
        if (parentTag == null) return;

        String attrName  = getAttributeName(propTag);
        String attrValue;

        XmlTag fxChild = getSingleFxBindingChild(propTag);
        if (fxChild != null) {
            Kind kind = ConvertBindingNotationIntention.fxTagNameToKind(fxChild.getLocalName());
            String path = fxChild.getAttributeValue("source");
            if (kind == null || path == null) return;
            attrValue = ConvertBindingNotationIntention.toShortForm(kind, path);
        } else {
            attrValue = unescapeXmlContent(propTag.getValue().getTrimmedText());
        }

        parentTag.setAttribute(attrName, attrValue);
        removeTagWithSurroundingWhitespace(propTag);

        // If the parent now has no child elements, collapse it to self-closing form.
        if (parentTag.getSubTags().length == 0
                && parentTag instanceof com.intellij.psi.impl.source.xml.XmlTagImpl impl) {
            impl.collapseIfEmpty();
        }

        PsiDocumentManager.getInstance(project).commitAllDocuments();
        CodeStyleManager.getInstance(project).reformat(parentTag);
    }

    // -----------------------------------------------------------------------
    // Predicates
    // -----------------------------------------------------------------------

    /** Returns {@code true} if the attribute is a regular property attribute (not fx:*, not xmlns*). */
    static boolean isConvertibleAttribute(@Nullable XmlAttribute attr) {
        if (attr == null) return false;
        String name = attr.getName();
        if (Fxml2XmlUtil.isNonPropertyAttribute(name)) return false;
        if (!(attr.getContainingFile() instanceof XmlFile xmlFile)) return false;
        if (!Fxml2FileType.isFxml2(xmlFile)) return false;
        return attr.getParent() != null;
    }

    /**
     * Returns {@code true} if {@code tag} is a property element tag that can be folded
     * back to an attribute (plain text, or exactly one {@code <fx:KIND source="..."/>} child).
     */    static boolean isFoldablePropertyTag(@Nullable XmlTag tag) {
        if (tag == null) return false;
        if (tag.getParentTag() == null) return false;
        String localName = tag.getLocalName();
        if (localName.isEmpty()) return false;
        if (Fxml2ImportResolver.FXML2_NAMESPACE.equals(tag.getNamespace())) return false;
        if (!(tag.getContainingFile() instanceof XmlFile xmlFile)) return false;
        if (!Fxml2FileType.isFxml2(xmlFile)) return false;
        if (Fxml2ImportResolver.resolve(localName, xmlFile) != null) return false;
        XmlTag[] subTags = tag.getSubTags();
        if (subTags.length == 0) return true;
        return subTags.length == 1 && getSingleFxBindingChild(tag) != null;
    }

    // -----------------------------------------------------------------------
    // Binding helpers
    // -----------------------------------------------------------------------

    private static @Nullable XmlTag getSingleFxBindingChild(@NotNull XmlTag propTag) {
        XmlTag[] children = propTag.getSubTags();
        if (children.length != 1) return null;
        XmlTag child = children[0];
        if (!Fxml2ImportResolver.FXML2_NAMESPACE.equals(child.getNamespace())) return null;
        if (ConvertBindingNotationIntention.fxTagNameToKind(child.getLocalName()) == null) return null;
        if (child.getAttributeValue("source") == null) return null;
        return child;
    }

    // -----------------------------------------------------------------------
    // Generic helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the XML attribute name to use when converting a property element tag back to
     * an attribute.
     *
     * <ul>
     *   <li>Plain name ({@code text}) -> {@code "text"}</li>
     *   <li>Qualified instance notation ({@code Button.text} inside {@code <Button>})
     *       -> {@code "text"} (prefix is the parent's own class, redundant in attribute form)</li>
     *   <li>Static property notation ({@code Command.onAction}, {@code VBox.vgrow})
     *       -> {@code "Command.onAction"} / {@code "VBox.vgrow"} (prefix must be kept)</li>
     * </ul>
     */
    private static @NotNull String getAttributeName(@NotNull XmlTag propTag) {
        String localName = propTag.getLocalName();
        int dot = localName.lastIndexOf('.');
        if (dot < 0) return localName;  // no dot, plain name

        // Dotted: check whether the prefix is the parent element's class
        // (qualified instance notation) or a different class (static notation).
        String prefix = localName.substring(0, dot);
        XmlTag parentTag = propTag.getParentTag();
        if (parentTag != null && prefix.equals(parentTag.getLocalName())) {
            // Qualified instance notation: strip the redundant prefix
            return localName.substring(dot + 1);
        }
        // Static property: keep the full dotted name as the attribute name
        return localName;
    }

    private static @Nullable XmlAttribute findAttributeAt(@NotNull PsiElement element) {
        if (element instanceof XmlToken t
                && t.getTokenType() == XmlTokenType.XML_NAME
                && t.getParent() instanceof XmlAttribute attr) {
            return attr;
        }
        if (element.getParent() instanceof XmlAttribute attr) {
            int caretInAttr = element.getTextOffset() - attr.getTextOffset();
            int eq = attr.getText().indexOf('=');
            if (eq < 0 || caretInAttr <= eq) return attr;
        }
        return null;
    }

    private static @Nullable XmlTag findTagAt(@NotNull PsiElement element) {
        if (element instanceof XmlToken t && t.getTokenType() == XmlTokenType.XML_NAME
                && t.getParent() instanceof XmlTag tag) {
            for (PsiElement c = tag.getFirstChild(); c != null; c = c.getNextSibling()) {
                if (c instanceof XmlToken ct) {
                    if (ct.getTokenType() == XmlTokenType.XML_NAME) return ct == t ? tag : null;
                    if (ct.getTokenType() == XmlTokenType.XML_TAG_END
                            || ct.getTokenType() == XmlTokenType.XML_EMPTY_ELEMENT_END) break;
                }
            }
        }
        if (element.getParent() instanceof XmlTag tag) return tag;
        return null;
    }

    private static void removeTagWithSurroundingWhitespace(@NotNull XmlTag tag) {
        PsiElement prev = tag.getPrevSibling();
        PsiElement next = tag.getNextSibling();
        tag.delete();
        if (prev instanceof PsiWhiteSpace && prev.isValid()) {
            prev.delete();
        } else if (next instanceof PsiWhiteSpace && next.isValid()) {
            next.delete();
        }
    }


    private static @NotNull String escapeXmlContent(@NotNull String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static @NotNull String unescapeXmlContent(@NotNull String value) {
        return value.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&apos;", "'").replace("&quot;", "\"");
    }
}
