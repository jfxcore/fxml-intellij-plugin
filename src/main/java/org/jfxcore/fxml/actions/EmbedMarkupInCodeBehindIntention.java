// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.actions;

import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlToken;
import com.intellij.psi.xml.XmlTokenType;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.lang.Fxml2EmbedMarkupUtil;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.jfxcore.fxml.lang.Fxml2FileType;

/**
 * Intention action that embeds an FXML2 file's markup into the code-behind class as a
 * {@code @ComponentView} annotation value and deletes the standalone FXML file.
 *
 * <h2>Trigger positions</h2>
 * The action is available when the caret is on:
 * <ul>
 *   <li>the root element's <em>opening tag name</em> (e.g. {@code <Border<caret>Pane ...>}),</li>
 *   <li>the {@code fx:subclass} <em>attribute name</em>, or</li>
 *   <li>the {@code fx:subclass} <em>attribute value</em>.</li>
 * </ul>
 *
 * <h2>Prerequisites</h2>
 * <ul>
 *   <li>The file must be a <em>standalone</em> FXML2 file, not an already-embedded fragment
 *       (i.e. {@link Fxml2EmbeddedUtil#isEmbeddedFxml2} must return {@code false}).</li>
 *   <li>A code-behind sibling ({@code .java} or {@code .kt}) must already exist next to
 *       the FXML file.</li>
 * </ul>
 *
 * <h2>What it produces</h2>
 * <ul>
 *   <li>A Java {@code @ComponentView("""...""")} text-block annotation, or a
 *       Kotlin {@code @ComponentView($$"""...""")} multi-dollar-string annotation, is inserted
 *       before the code-behind class declaration.</li>
 *   <li>The FXML {@code <?import?>} PIs are added as Java/Kotlin import statements so the
 *       language injector can re-synthesise them inside the injected XML fragment.</li>
 *   <li>All {@code xmlns:*} and {@code fx:subclass} attributes are stripped from the embedded
 *       root element (the language injector supplies them via the synthetic wrapper).</li>
 *   <li>The FXML file is deleted.</li>
 * </ul>
 *
 * <p>A confirmation dialog is shown before any changes are made.  In unit tests set
 * {@link #skipConfirmationForTesting} to {@code true} to bypass the dialog.
 *
 * <p>Registered as an {@code <intentionAction>} extension in {@code plugin.xml}.
 */
public final class EmbedMarkupInCodeBehindIntention implements IntentionAction, PriorityAction {

    /**
     * When {@code true}, the confirmation dialog is suppressed and the action proceeds
     * immediately on invocation.  Intended exclusively for unit tests.
     *
     * <p>Always reset to {@code false} in an {@code @AfterEach} / {@code try-finally} block.
     */
    @SuppressWarnings("StaticNonFinalField") // intentionally mutable, test injection point
    public static volatile boolean skipConfirmationForTesting = false;

    // -----------------------------------------------------------------------
    // IntentionAction identity

    @Override
    public @NotNull String getText() {
        return "Embed markup in code-behind file";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Embed markup in code-behind file";
    }

    // -----------------------------------------------------------------------
    // Availability

    /**
     * Returns {@code true} when all prerequisites are satisfied:
     * <ol>
     *   <li>The file is a standalone FXML2 file (not an injected embedded fragment).</li>
     *   <li>The caret is on the root element tag name, the {@code fx:subclass} attribute name,
     *       or the {@code fx:subclass} attribute value.</li>
     *   <li>A sibling code-behind file ({@code .java} or {@code .kt}) exists.</li>
     *   <li>The {@code Fxml2EmbedMarkup} inspection is <em>not</em> enabled, when it is
     *       enabled the inspection's quick-fix is offered instead, so the intention is
     *       suppressed to avoid showing the same action twice.</li>
     * </ol>
     */
    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        // 1. Must be a standalone FXML2 file
        if (!(file instanceof XmlFile)) return false;
        if (!Fxml2FileType.isFxml2(file)) return false;
        if (Fxml2EmbeddedUtil.isEmbeddedFxml2(file)) return false;

        // 2. Caret must be on the root tag name, the fx:subclass attr name, or its value
        String fxClass = getFxClassForCaret(editor, file);
        if (StringUtil.isEmptyOrSpaces(fxClass)) return false;

        // 4. A code-behind sibling must already exist (VFS-only check, no PSI class lookup)
        String simpleName = StringUtil.getShortName(fxClass);
        VirtualFile fxmlVF = file.getVirtualFile();
        if (Fxml2EmbedMarkupUtil.findCodeBehindFile(fxmlVF, simpleName) == null) return false;

        // 4. Hide when the inspection is enabled: its quickfix already covers this action
        return !isInspectionEnabled(project, file);
    }

    // -----------------------------------------------------------------------
    // Invocation

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file)
            throws IncorrectOperationException {
        if (!(file instanceof XmlFile xmlFile)) return;

        if (!skipConfirmationForTesting) {
            int result = Messages.showOkCancelDialog(
                    project,
                    """
                            This will embed the FXML markup into the code-behind file as a \
                            @ComponentView annotation and delete the FXML file.
                            
                            Proceed?""",
                    "Embed Markup in Code-Behind File",
                    "Embed",
                    "Cancel",
                    Messages.getQuestionIcon());
            if (result != Messages.OK) return;
        }

        WriteCommandAction.runWriteCommandAction(
                project,
                "Embed Markup in Code-Behind File",
                null,
                () -> {
                    PsiDocumentManager.getInstance(project).commitAllDocuments();
                    Fxml2EmbedMarkupUtil.applyEmbed(project, xmlFile);
                },
                file);
    }

    @Override
    public boolean startInWriteAction() {
        // We manage our own WriteCommandAction inside invoke()
        return false;
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.NORMAL;
    }

    // -----------------------------------------------------------------------
    // Helpers

    /**
     * Returns {@code true} when the {@code Fxml2EmbedMarkup} inspection is currently
     * enabled in the project's active inspection profile for {@code file}.
     */
    private static boolean isInspectionEnabled(
            @NotNull Project project, @NotNull PsiFile file) {
        HighlightDisplayKey key = HighlightDisplayKey.findOrRegister(
                "Fxml2EmbedMarkup", "Fxml2EmbedMarkup");
        return InspectionProjectProfileManager.getInstance(project)
                .getCurrentProfile().isToolEnabled(key, file);
    }

    /**
     * Returns the {@code fx:subclass} attribute value when the caret is positioned on:
     * <ul>
     *   <li>the {@code fx:subclass} attribute name or value (at any depth inside the attribute
     *       PSI subtree), or</li>
     *   <li>the opening tag-name token of the root element.</li>
     * </ul>
     * Returns {@code null} for all other caret positions.
     */
    private static @Nullable String getFxClassForCaret(
            @Nullable Editor editor, @NotNull PsiFile file) {
        if (editor == null) return null;
        int offset = editor.getCaretModel().getOffset();
        PsiElement element = file.findElementAt(offset);
        if (element == null) return null;

        // Case A: caret is inside an attribute element (name, '=', value)
        XmlAttribute attr = PsiTreeUtil.getParentOfType(element, XmlAttribute.class);
        if (attr != null) {
            if (!"fx:subclass".equals(attr.getName())) return null;
            XmlTag tag = attr.getParent();
            if (tag == null || tag.getParentTag() != null) return null; // must be root tag
            return attr.getValue();
        }

        // Case B: caret is on the opening tag-name token of the root element
        XmlTag tag = PsiTreeUtil.getParentOfType(element, XmlTag.class);
        if (tag == null || tag.getParentTag() != null) return null;
        if (element instanceof XmlToken tok
                && tok.getTokenType() == XmlTokenType.XML_NAME
                && tok.getParent() == tag) {
            // Distinguish the opening-tag name (preceded by '<') from the closing-tag name
            PsiElement prev = tok.getPrevSibling();
            if (prev instanceof XmlToken prevTok
                    && prevTok.getTokenType() == XmlTokenType.XML_START_TAG_START) {
                return tag.getAttributeValue("fx:subclass");
            }
        }

        return null;
    }
}
