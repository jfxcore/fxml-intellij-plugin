// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate;
import com.intellij.injected.editor.VirtualFileWindow;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.actions.EditorActionUtil;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.FileIndentOptionsProvider;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlText;
import com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Hosts three extension-point implementations that make Tab, Shift+Tab, and Enter
 * use the XML indent size (e.g. from EditorConfig {@code *.fxml} rules) and the correct
 * XML attribute alignment when the caret is inside embedded FXML markup in a
 * {@code @ComponentView} annotation.
 *
 * <h2>Tab: {@link IndentOptionsProvider}</h2>
 * <p>{@code TabAction} has {@code setInjectedContext(true)}, so it runs in the
 * injected editor and calls
 * {@link com.intellij.psi.codeStyle.CodeStyleSettings#getIndentOptionsByDocument}
 * with the injected {@code DocumentWindow}.  The document's PSI file is a
 * {@link LightVirtualFile} / {@link VirtualFileWindow} whose {@code getParent()}
 * is {@code null}, so EditorConfig's built-in provider cannot locate the
 * {@code .editorconfig} hierarchy.
 * {@link IndentOptionsProvider} intercepts this lookup and returns correctly
 * resolved XML indent options.
 *
 * <h2>Shift+Tab: {@link UnindentHandler}</h2>
 * <p>{@code UnindentSelectionAction} does <em>not</em> set
 * {@code setInjectedContext(true)}, so it always runs against the host
 * (Java/Kotlin) editor and calls
 * {@link com.intellij.application.options.CodeStyle#getIndentOptions(Project, Document)}
 * on the host document, which returns the Java indent size instead of the XML
 * indent size.
 * {@link UnindentHandler} overrides the action handler, detects when the caret
 * is inside an embedded FXML fragment, and applies the correct XML indent step.
 *
 * <h2>Enter: {@link EnterHandler}</h2>
 * <p>{@code EnterAction} has {@code setInjectedContext(true)}, so the whole Enter
 * handler chain receives the <em>injected</em> editor.  After the newline is
 * inserted, the platform's {@code scheduleIndentAdjustment} delegates to the
 * <em>host</em> (Java) formatter, which produces tag-indent alignment (wrong)
 * instead of first-attribute alignment (correct).
 * {@link EnterHandler} intercepts Enter between XML tag attributes in embedded
 * FXML markup and directly inserts the correct {@code \n + spaces} string -
 * matching the standalone-FXML attribute-alignment behavior: then returns
 * {@code Result.Stop} to suppress all further processing.
 */
public final class Fxml2EmbeddedIndentHandlers {

    private Fxml2EmbeddedIndentHandlers() {}

    // -----------------------------------------------------------------------
    // Tab: FileIndentOptionsProvider
    // -----------------------------------------------------------------------

    /**
     * {@link FileIndentOptionsProvider} registered with {@code order="first"} for the
     * {@code com.intellij.fileIndentOptionsProvider} extension point.
     *
     * <p>Returns correct XML indent options for injected {@link Fxml2EmbeddedXmlLanguage}
     * {@link VirtualFileWindow} fragments so that pressing Tab inside embedded FXML
     * markup uses the same indent size as {@code Ctrl+Alt+L} formatting.
     */
    public static final class IndentOptionsProvider extends FileIndentOptionsProvider {

        @Override
        public @Nullable CommonCodeStyleSettings.IndentOptions getIndentOptions(
                @NotNull Project project,
                @NotNull CodeStyleSettings settings,
                @NotNull VirtualFile file) {

            // Only handle injected virtual files (VirtualFileWindow is also a LightVirtualFile).
            if (!(file instanceof VirtualFileWindow vfw)) return null;
            if (!(file instanceof LightVirtualFile lvf)) return null;
            if (lvf.getLanguage() != Fxml2EmbeddedXmlLanguage.INSTANCE) return null;

            // Use the host file's directory for the EditorConfig lookup so that *.fxml /
            // *.{xml,fxml} rules in the host's .editorconfig hierarchy are honored.
            VirtualFile hostFile = vfw.getDelegate();
            int xmlIndentSize = Fxml2EmbedMarkupUtil.getEffectiveXmlIndentSize(project, hostFile);

            // Clone the project-level XML indent options so we inherit all other settings
            // (USE_TAB_CHARACTER, SMART_TABS, ...) and only override the indent/tab sizes.
            CommonCodeStyleSettings xmlCommon = settings.getCommonSettings(XMLLanguage.INSTANCE);
            CommonCodeStyleSettings.IndentOptions xmlOpts = xmlCommon.getIndentOptions();
            CommonCodeStyleSettings.IndentOptions result = xmlOpts != null
                    ? (CommonCodeStyleSettings.IndentOptions) xmlOpts.clone()
                    : new CommonCodeStyleSettings.IndentOptions();
            result.INDENT_SIZE = xmlIndentSize;
            result.TAB_SIZE   = xmlIndentSize;
            return result;
        }
    }

    // -----------------------------------------------------------------------
    // Shift+Tab: EditorActionHandler for EditorUnindentSelection
    // -----------------------------------------------------------------------

    /**
     * {@link EditorActionHandler} registered with {@code order="first"} for the
     * {@code com.intellij.editorActionHandler} extension point, action
     * {@code EditorUnindentSelection} (Shift+Tab).
     *
     * <p>When the caret is inside an embedded FXML fragment the handler removes
     * the XML indent size (resolved via
     * {@link Fxml2EmbedMarkupUtil#getEffectiveXmlIndentSize}) from the start of
     * each affected line.  For all other positions it falls through to the platform's
     * original {@code UnindentSelectionAction} handler.
     */
    public static final class UnindentHandler extends EditorActionHandler {

        private final EditorActionHandler myDelegate;

        /** Called by the IntelliJ platform: receives the previously-registered handler. */
        public UnindentHandler(@Nullable EditorActionHandler delegate) {
            myDelegate = delegate;
        }

        @Override
        protected boolean isEnabledForCaret(@NotNull Editor editor,
                                            @NotNull Caret caret,
                                            DataContext dataContext) {
            return myDelegate == null || myDelegate.isEnabled(editor, caret, dataContext);
        }

        @Override
        protected void doExecute(@NotNull Editor editor,
                                 @Nullable Caret caret,
                                 DataContext dataContext) {
            Project project = CommonDataKeys.PROJECT.getData(dataContext);
            if (project != null && tryHandleEmbeddedFxml2(editor, project)) {
                return;
            }
            // Not an embedded FXML context: let the original handler do its job.
            if (myDelegate != null) {
                myDelegate.execute(editor, caret, dataContext);
            }
        }

        /**
         * Returns {@code true} and performs the unindent when the caret is inside an
         * embedded FXML fragment; returns {@code false} to fall through to the delegate.
         */
        private static boolean tryHandleEmbeddedFxml2(@NotNull Editor editor,
                                                      @NotNull Project project) {
            // The handler receives the HOST editor (UnindentSelectionAction has no
            // injected context), so editor.getDocument() is the host Java/Kotlin document.
            PsiFile hostFile = PsiDocumentManager.getInstance(project)
                    .getPsiFile(editor.getDocument());
            if (hostFile == null) return false;

            int caretOffset = editor.getCaretModel().getOffset();
            PsiElement injectedElement = InjectedLanguageManager.getInstance(project)
                    .findInjectedElementAt(hostFile, caretOffset);
            if (injectedElement == null) return false;

            PsiFile injectedFile = injectedElement.getContainingFile();
            if (!(injectedFile instanceof XmlFile xmlFile)) return false;
            if (!Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile)) return false;

            VirtualFile hostVirtualFile = hostFile.getVirtualFile();
            int xmlIndentSize = Fxml2EmbedMarkupUtil.getEffectiveXmlIndentSize(
                    project, hostVirtualFile);

            WriteCommandAction.runWriteCommandAction(project,
                    () -> unindentLines(editor, project, xmlIndentSize));
            return true;
        }

        /**
         * Removes {@code xmlIndentSize} spaces from the start of each line in the
         * selection (or of the caret line when there is no selection).
         *
         * <p>Mirrors the range-computation logic of
         * {@code UnindentSelectionAction.unindentSelection} and the per-line
         * modification of {@code IndentSelectionAction.doIndent}.
         */
        private static void unindentLines(@NotNull Editor editor,
                                          @NotNull Project project,
                                          int xmlIndentSize) {
            Document document = editor.getDocument();

            int selStart;
            int selEnd;
            if (editor.getSelectionModel().hasSelection()) {
                selStart = editor.getSelectionModel().getSelectionStart();
                selEnd   = editor.getSelectionModel().getSelectionEnd();
            } else {
                selStart = editor.getCaretModel().getOffset();
                selEnd   = selStart;
            }

            int startLine = document.getLineNumber(selStart);
            if (startLine == -1) startLine = document.getLineCount() - 1;
            int endLine = document.getLineNumber(selEnd);
            if (endLine > 0
                    && document.getLineStartOffset(endLine) == selEnd
                    && endLine > startLine) {
                endLine--;
            }
            if (endLine == -1) endLine = document.getLineCount() - 1;
            if (startLine < 0 || endLine < 0) return;

            int[] caretOffset = {editor.getCaretModel().getOffset()};
            for (int i = startLine; i <= endLine; i++) {
                caretOffset[0] = EditorActionUtil.indentLine(
                        project, editor, i, -xmlIndentSize, caretOffset[0]);
            }
            editor.getCaretModel().moveToOffset(caretOffset[0]);
        }
    }

    // -----------------------------------------------------------------------
    // Enter: EnterHandlerDelegate for XML attribute alignment
    // -----------------------------------------------------------------------

    /**
     * {@link EnterHandlerDelegate} registered for the
     * {@code com.intellij.enterHandlerDelegate} extension point.
     *
     * <p>{@code EnterAction} has {@code setInjectedContext(true)}, so when the caret
     * is inside an embedded FXML fragment the whole Enter-handler chain receives the
     * <em>injected</em> editor.  After the newline is inserted, the platform's
     * {@code scheduleIndentAdjustment} delegates to the top-level (Java/Kotlin)
     * formatter which produces wrong "tag indent" alignment instead of the correct
     * "first attribute" alignment that standalone XML files produce.
     *
     * <p>This handler intercepts Enter when the caret is between XML attributes
     * (i.e. after at least one attribute and before the closing {@code >} or
     * {@code />}), directly inserts {@code "\n"} followed by enough spaces to align
     * the next token with the first attribute of the containing tag, and returns
     * {@code Result.Stop} to suppress all further indent-adjustment processing.
     */
    public static final class EnterHandler implements EnterHandlerDelegate {

        @Override
        public Result preprocessEnter(@NotNull PsiFile file,
                                      @NotNull Editor editor,
                                      @NotNull Ref<Integer> caretOffset,
                                      @NotNull Ref<Integer> caretAdvance,
                                      @NotNull DataContext dataContext,
                                      @Nullable EditorActionHandler originalHandler) {

            // Only act on embedded FXML injected XML files.
            // EnterAction has setInjectedContext(true), so editor/file are for the
            // injected fragment when the caret is inside one.
            if (!(file instanceof XmlFile xmlFile)) return Result.Continue;
            if (!Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile)) return Result.Continue;

            Project project = dataContext.getData(CommonDataKeys.PROJECT);
            if (project == null) return Result.Continue;

            // Commit the document so PSI reflects the current text.
            Document doc = editor.getDocument();
            PsiDocumentManager.getInstance(project).commitDocument(doc);

            int offset = caretOffset.get();

            // Find the leaf PSI element at the caret in the injected XML.
            PsiElement element = file.findElementAt(offset);
            if (element == null) return Result.Continue;

            // Walk up to the nearest XmlTag, bailing out if we pass through
            // an attribute value (caret inside value) or tag body text.
            PsiElement e = element;
            while (e != null && !(e instanceof XmlTag)) {
                if (e instanceof XmlAttributeValue) return Result.Continue;
                if (e instanceof XmlText) return Result.Continue;
                e = e.getParent();
            }
            if (!(e instanceof XmlTag tag)) return Result.Continue;

            // Don't interfere with the synthetic wrapper root element.
            if (Fxml2EmbeddedUtil.isWrapperRoot(tag)) return Result.Continue;

            // The tag must have at least one attribute.
            XmlAttribute[] attrs = tag.getAttributes();
            if (attrs.length == 0) return Result.Continue;

            // At least one attribute must end before the caret, meaning the caret
            // is after the first attribute and therefore between attributes (or after
            // the last attribute before /> or >).
            boolean hasAttrBeforeCaret = false;
            for (XmlAttribute attr : attrs) {
                if (attr.getTextRange().getEndOffset() <= offset) {
                    hasAttrBeforeCaret = true;
                    break;
                }
            }
            if (!hasAttrBeforeCaret) return Result.Continue;

            // Compute the column of the first attribute in the injected document.
            // Since the injection maps the text-block characters 1:1 (the prefix / suffix
            // contribute virtual characters only), the column is the same in the host
            // document, so we work directly in the injected document.
            int firstAttrOffset = attrs[0].getTextRange().getStartOffset();
            int attrLine        = doc.getLineNumber(firstAttrOffset);
            int attrLineStart   = doc.getLineStartOffset(attrLine);
            int column          = firstAttrOffset - attrLineStart;

            // Insert "\n" + alignment spaces at the caret position in the injected
            // document.  DocumentWindow maps writes back to the host document.
            String newText = "\n" + " ".repeat(column);
            doc.insertString(offset, newText);

            int newCaretOffset = offset + newText.length();
            editor.getCaretModel().moveToOffset(newCaretOffset);
            editor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);
            editor.getSelectionModel().removeSelection();

            // Return Stop to prevent the normal Enter logic (original handler +
            // DoEnterAction + scheduleIndentAdjustment) from running.
            return Result.Stop;
        }
    }
}
