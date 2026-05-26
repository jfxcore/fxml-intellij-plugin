// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.application.options.CodeStyle;
import com.intellij.lang.ImportOptimizer;
import com.intellij.lang.LanguageImportStatements;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.editor.Document;
import org.ec4j.core.ResourceProperties;
import org.editorconfig.Utils;
import org.editorconfig.plugincomponents.EditorConfigPropertiesService;
import org.jfxcore.fxml.codeinsight.Fxml2ImportOptimizer;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiImportStaticStatement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiManager;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlProcessingInstruction;
import com.intellij.psi.xml.XmlProlog;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.asJava.KotlinAsJavaSupport;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtImportDirective;
import org.jetbrains.kotlin.psi.KtStringTemplateExpression;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared utilities for the "Embed markup in code-behind file" intention and its
 * inverse ("Create FXML file").
 *
 * <p>Provides:
 * <ul>
 *   <li>Code-behind sibling-file lookup (VFS-only, no PSI class index).</li>
 *   <li>Markup body extraction: strips {@code xmlns:*} / {@code fx:subclass} from the
 *       root element and produces the clean string that goes inside a
 *       {@code @ComponentView} annotation value.</li>
 *   <li>{@link #formatXmlContent}: formats a raw XML string using IntelliJ's XML
 *       formatter and re-anchors the indentation to a given base column.  Shared by
 *       {@link Fxml2EmbeddedMarkupFormattingProcessor} and the embed direction so that
 *       both always produce identically-formatted output.</li>
 *   <li>{@link #reindent}: low-level line re-indentation helper.</li>
 *   <li>The core {@link #applyEmbed} routine that rewrites the code-behind file,
 *       adds the necessary imports, and deletes the FXML file, all within the
 *       caller-supplied write action.</li>
 *   <li>FXML file construction: restores {@code xmlns:*} / {@code fx:subclass} into
 *       an extracted markup body and builds the full FXML document text.</li>
 *   <li>The core {@link #applyExtractJava} / {@link #applyExtractKotlin} routines
 *       that create the FXML file, remove the annotation and its import.</li>
 *   <li>Import-addition helpers for both Java and Kotlin host files.</li>
 * </ul>
 */
public final class Fxml2EmbedMarkupUtil {

    private Fxml2EmbedMarkupUtil() {}

    // -----------------------------------------------------------------------
    // Code-behind file lookup
    // -----------------------------------------------------------------------

    /**
     * Returns the code-behind {@link VirtualFile} ({@code simpleName.java} or
     * {@code simpleName.kt}) that lives in the same directory as {@code fxmlFile},
     * or {@code null} if neither exists.
     *
     * <p>This is a pure VFS operation, no PSI class-index lookup.
     */
    public static @Nullable VirtualFile findCodeBehindFile(
            @Nullable VirtualFile fxmlFile, @NotNull String simpleName) {
        if (fxmlFile == null) return null;
        VirtualFile parent = fxmlFile.getParent();
        if (parent == null) return null;
        VirtualFile java = parent.findChild(simpleName + ".java");
        if (java != null) return java;
        return parent.findChild(simpleName + ".kt");
    }

    /**
     * Returns {@code true} when {@code file} has a {@code .kt} extension
     * (case-insensitive).
     */
    public static boolean isKotlinFile(@NotNull VirtualFile file) {
        return "kt".equalsIgnoreCase(file.getExtension());
    }

    // -----------------------------------------------------------------------
    // Markup body extraction  (embed direction)
    // -----------------------------------------------------------------------

    /**
     * Collects processing instructions and XML comments from the FXML document prolog
     * that must be preserved in the embedded markup, in document order.
     *
     * <p>The FXML prolog typically contains {@code <?xml?>} and {@code <?import?>} PIs
     * followed by optional user-defined PIs (e.g. {@code <?prefix?>}) and comments before
     * the root element. The {@code <?xml?>} and {@code <?import?>} PIs are handled
     * separately (the former is implicit; the latter are converted to Java/Kotlin imports),
     * so only the remaining PIs and all XML comments need to be embedded verbatim.
     *
     * @param xmlDoc the FXML document whose prolog is inspected
     * @return the collected PIs and comments as a single string ending with a newline, or
     *         an empty string when no such content is present
     */
    static @NotNull String buildDocumentLeadingPis(@NotNull XmlDocument xmlDoc) {
        StringBuilder sb = new StringBuilder();
        // Walk direct children of the document and its prolog in document order,
        // collecting non-import processing instructions and XML comments.
        // Stopping at the root element ensures we only pick up prolog content.
        for (PsiElement child : xmlDoc.getChildren()) {
            if (child instanceof XmlTag) break; // reached the root element - stop
            collectPrologContent(child, sb);
        }
        return sb.toString();
    }

    private static void collectPrologContent(@NotNull PsiElement node, @NotNull StringBuilder sb) {
        switch (node) {
            case XmlProlog prolog -> {
                // Descend into the prolog wrapper, preserving document order
                for (PsiElement child : prolog.getChildren()) {
                    collectPrologContent(child, sb);
                }
            }
            case XmlProcessingInstruction pi -> {
                String text = pi.getText();
                // Skip the XML declaration and <?import?> PIs; both are handled elsewhere.
                if (!text.startsWith("<?xml") && !text.startsWith("<?import")) {
                    sb.append(text).append('\n');
                }
            }
            case com.intellij.psi.xml.XmlComment comment ->
                    sb.append(comment.getText()).append('\n');
            default -> { /* ignore whitespace, text, and other nodes */ }
        }
    }

    /**
     * Builds the clean markup body for use inside a {@code @ComponentView} annotation value.
     *
     * <p>The root element of {@code rootTag} is returned with all {@code xmlns:*} and
     * {@code fx:subclass} attributes stripped.  The body content (child elements, text) is
     * preserved verbatim, so the original FXML formatting is kept.
     *
     * <p>The returned string contains no leading {@code <?import?>} processing instructions
     * and no namespace declarations.  Those are represented as Java/Kotlin import statements
     * in the host file; the language injector re-synthesises the {@code <?import?>} PIs from
     * those imports at IDE level.
     */
    public static @NotNull String buildMarkupBody(@NotNull XmlTag rootTag) {
        String fullText = rootTag.getText();

        // Collect names of attributes to strip (xmlns:* and fx:subclass)
        List<String> removeNames = new ArrayList<>();
        for (XmlAttribute attr : rootTag.getAttributes()) {
            String name = attr.getName();
            if (name.startsWith("xmlns") || "fx:subclass".equals(name)) {
                removeNames.add(name);
            }
        }

        // Locate the '>' that ends the opening tag (skipping quoted attribute values)
        int openTagEnd = findOpenTagEnd(fullText);
        if (openTagEnd < 0) return fullText;

        boolean selfClosing = openTagEnd >= 1 && fullText.charAt(openTagEnd - 1) == '/';

        // Rebuild the opening tag keeping only the non-namespace/non-fx:subclass attributes
        StringBuilder sb = new StringBuilder("<").append(rootTag.getName());
        for (XmlAttribute attr : rootTag.getAttributes()) {
            if (!removeNames.contains(attr.getName())) {
                sb.append(' ').append(attr.getText());
            }
        }

        if (selfClosing) {
            sb.append("/>");
        } else {
            sb.append('>');
            // Append body + closing tag verbatim (preserves original formatting)
            sb.append(fullText, openTagEnd + 1, fullText.length());
        }

        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // XML content formatter  (shared by the embed direction and
    //                         Fxml2EmbeddedMarkupFormattingProcessor)
    // -----------------------------------------------------------------------

    /**
     * Formats a raw XML string using IntelliJ's built-in XML formatter and
     * re-anchors the resulting indentation so that the root-level elements
     * sit at column {@code baseIndent}.
     *
     * <p>The content is wrapped in a synthetic {@code <_fxml2_r_>} element to allow
     * multi-root content, formatted by {@link CodeStyleManager#reformatText} (which
     * honors all project XML code-style settings: attribute wrapping, CDATA sections,
     * XML comments, etc.), and then re-indented via {@link #reindent}.
     *
     * <p>The XML indent size is determined as follows:
     * <ol>
     *   <li>If {@code hostVirtualFile} is non-null and the EditorConfig plugin is available,
     *       the effective indent size for a {@code .fxml} file in the host file's directory
     *       is read from {@code EditorConfigPropertiesService} (synchronous).</li>
     *   <li>Otherwise, the project-level XML indent size from
     *       {@link CodeStyle#getSettings(Project)} is used.</li>
     * </ol>
     *
     * <p>The returned string ends with {@code "\n" + " ".repeat(annotationColumn)} so
     * that a directly following {@code """} delimiter sits on its own line at
     * {@code annotationColumn}.
     *
     * @param project          the project (used by {@link CodeStyleManager})
     * @param rawContent       the raw XML content (no outer {@code """} delimiters)
     * @param baseIndent       the desired column for the root-level XML element(s)
     * @param annotationColumn the column of the host annotation's {@code @} sign,
     *                         used to align the closing {@code """}
     * @param hostVirtualFile  the VirtualFile of the Java/Kotlin file that contains the
     *                         annotation (used for EditorConfig lookup); may be
     *                         {@code null}
     * @return the formatted string, or {@code null} if the content cannot be parsed
     */
    public static @Nullable String formatXmlContent(
            @NotNull Project project,
            @NotNull String rawContent,
            int baseIndent,
            int annotationColumn,
            @Nullable VirtualFile hostVirtualFile) {

        // Normalize rawContent: strip whatever indentation the Java formatter may have
        // applied (e.g. continuation indent from TextBlockBlock) so the XML formatter
        // always starts from a clean baseline with the root element at column 0.
        int currentRootIndent = getActualRootIndent(rawContent);
        if (currentRootIndent > 0) {
            rawContent = reindent(rawContent, currentRootIndent, 0);
        }

        // Wrap in a synthetic root that declares the FXML/2 namespaces so that
        // fx:-prefixed elements inside the user markup resolve without errors.
        final String wrappedXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<_fxml2_r_ xmlns=\"http://javafx.com/javafx\""
                + " xmlns:fx=\"http://jfxcore.org/fxml/2.0\">"
                + rawContent
                + "</_fxml2_r_>";

        // Determine the XML indent size that applies to FXML files in the host
        // directory (from EditorConfig, or from project XML code-style settings).
        final int xmlIndentSize = getEffectiveXmlIndentSize(project, hostVirtualFile);

        // Run the XML formatter inside computeWithLocalSettings so that:
        //   a) The formatter's CodeStyle.getSettings(xmlPsiFile) call falls through to
        //      getSettings(project) -> getLocalOrTemporarySettings(), picking up our
        //      local settings copy rather than whatever per-file settings happen to be
        //      active for the host Java file.
        //   b) We can safely override the XML indent size in the local copy without
        //      disturbing the project-wide settings.
        return CodeStyle.computeWithLocalSettings(project, CodeStyle.getSettings(project),
                localSettings -> {
                    // Override the XML language indent in the local settings copy.
                    CommonCodeStyleSettings xmlCommon =
                            localSettings.getCommonSettings(XMLLanguage.INSTANCE);
                    CommonCodeStyleSettings.IndentOptions xmlOpts =
                            xmlCommon.getIndentOptions();
                    if (xmlOpts != null) xmlOpts.INDENT_SIZE = xmlIndentSize;

                    // Create a temporary XML PSI file.  runWithLocalSettings ensures that
                    // CodeStyle.getSettings(xmlPsiFile) returns our local copy via
                    //   getSettings(project) -> getLocalOrTemporarySettings().
                    LightVirtualFile tempVf = new LightVirtualFile(
                            "_fxml2_format_tmp.fxml", XMLLanguage.INSTANCE, wrappedXml);
                    PsiFile xmlPsiFile = PsiManager.getInstance(project).findFile(tempVf);
                    if (!(xmlPsiFile instanceof XmlFile xmlFile)) return null;

                    // Guard: ensure the XML is parseable before formatting.
                    XmlDocument xmlDoc = xmlFile.getDocument();
                    if (xmlDoc == null) return null;
                    XmlTag rootBefore = xmlDoc.getRootTag();
                    if (rootBefore == null || rootBefore.getSubTags().length == 0) return null;

                    // Delegate to IntelliJ's XML formatter, which handles attribute wrapping,
                    // CDATA, comments, PIs, and all other XML constructs correctly.
                    CodeStyleManager.getInstance(project)
                            .reformatText(xmlPsiFile, 0, xmlPsiFile.getTextLength());

                    // Get the root tag AFTER formatting (reformatText commits the PSI).
                    XmlTag root = xmlFile.getDocument().getRootTag();
                    if (root == null) return null;

                    // Extract the inner content: everything between the wrapper's opening
                    // '>' and its closing tag, including surrounding newlines.
                    String innerContent = root.getValue().getText();
                    if (innerContent.startsWith("\n")) innerContent = innerContent.substring(1);
                    if (innerContent.endsWith("\n"))
                        innerContent = innerContent.substring(0, innerContent.length() - 1);
                    if (innerContent.isEmpty()) return null;

                    // Detect the formatter's own indent so we can re-anchor to baseIndent
                    // without hard-coding the project XML indent setting.
                    int formatterIndent = getActualRootIndent(innerContent);

                    // Re-anchor and append closing-delimiter alignment.
                    return reindent(innerContent, formatterIndent, baseIndent)
                            + "\n" + " ".repeat(annotationColumn);
                });
    }

    /**
     * Returns the effective XML indent size for FXML files in the directory of
     * {@code hostVirtualFile}.
     *
     * <p>Strategy (in priority order):
     * <ol>
     *   <li>If EditorConfig is enabled for the project and the host directory has an
     *       {@code indent_size} entry matching {@code *.fxml} patterns, that value is used.
     *       This is read synchronously from {@code EditorConfigPropertiesService} so it is
     *       always up-to-date (no async warming required).</li>
     *   <li>The project-level XML indent size from
     *       {@link CodeStyle#getSettings(Project)} is used as fallback.</li>
     * </ol>
     *
     * @param project         the current project
     * @param hostVirtualFile the {@code .java} / {@code .kt} file that owns the
     *                        {@code @ComponentView} annotation; may be {@code null}
     * @return the resolved XML indent size (positive integer, typically 2 or 4)
     */
    static int getEffectiveXmlIndentSize(
            @NotNull Project project, @Nullable VirtualFile hostVirtualFile) {

        // Base: project-wide XML indent (IntelliJ default for XML is 2).
        CodeStyleSettings projectSettings = CodeStyle.getSettings(project);
        CommonCodeStyleSettings xmlCommon = projectSettings.getCommonSettings(XMLLanguage.INSTANCE);
        CommonCodeStyleSettings.IndentOptions projectXmlOpts =
                xmlCommon.getIndentOptions();
        int fallback = (projectXmlOpts != null) ? projectXmlOpts.INDENT_SIZE : 2;

        if (hostVirtualFile == null) return fallback;
        VirtualFile hostDir = hostVirtualFile.getParent();
        if (hostDir == null) return fallback;

        try {
            // Respect the user's "Enable EditorConfig support" project setting.
            if (!Utils.isEnabled(projectSettings)) return fallback;

            // Create a virtual probe file with the host directory as its logical parent.
            // EditorConfigPropertiesService.getProperties() uses file.path for glob
            // matching and file.parent for .editorconfig traversal; both are overridden
            // here so the service finds the real .editorconfig hierarchy and matches
            // *.fxml / *.{xml,fxml} patterns against the probe's file name.
            LightVirtualFile probe = new LightVirtualFile(
                    "_fxml2_indent_probe.fxml", XMLLanguage.INSTANCE, "") {
                @Override
                public VirtualFile getParent() { return hostDir; }
                @Override
                public @NotNull String getPath() {
                    return hostDir.getPath() + "/" + getName();
                }
            };

            ResourceProperties props =
                    EditorConfigPropertiesService.getInstance(project).getProperties(probe);
            var indentProp = props.getProperties().get("indent_size");
            if (indentProp != null && !indentProp.getSourceValue().isBlank()) {
                return Integer.parseInt(indentProp.getSourceValue().trim());
            }
        } catch (NumberFormatException ignored) {
            // Malformed indent_size value: fall through to project default.
        } catch (Exception | NoClassDefFoundError ignored) {
            // EditorConfig plugin not available or unexpected error: fall through.
        }

        return fallback;
    }

    /**
     * Returns the leading-space count of the first non-blank line that represents the
     * root XML element, skipping processing instructions and XML comments.
     *
     * <p>Processing instructions (lines whose trimmed form starts with {@code <?}) and XML
     * comments (lines whose trimmed form starts with {@code <!--}) are skipped when searching
     * for the representative indent. IntelliJ's XML formatter may place both PIs and comments
     * inside a wrapper element at column 0 (the same as a document-level node) even when the
     * wrapper's child elements are indented by the XML indent size. Using such a node's indent
     * in that case would produce incorrect results for the re-anchoring step.
     *
     * <p>If no non-PI non-comment non-blank line is found, the method falls back to the indent
     * of the first non-blank line (regardless of whether it is a PI or comment).
     */
    private static int getActualRootIndent(@NotNull String content) {
        String[] lines = content.split("\n", -1);
        // Prefer the indent of the first non-blank line that is not a PI or comment.
        for (String line : lines) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("<") && !trimmed.startsWith("<?") && !trimmed.startsWith("<!--")) {
                return line.length() - trimmed.length();
            }
        }
        // Fallback: use the indent of the first non-blank line (including PIs and comments).
        for (String line : lines) {
            if (!line.isBlank()) {
                int count = 0;
                while (count < line.length() && line.charAt(count) == ' ') count++;
                return count;
            }
        }
        return 0;
    }

    /**
     * Re-indents {@code content} by replacing {@code stripIndent} leading spaces on each
     * non-blank line with {@code addIndent} spaces.
     *
     * <p>Lines that have fewer than {@code stripIndent} leading spaces have all leading
     * whitespace stripped before the new indent is added (defensive fallback).
     * Blank lines are kept empty (no spurious indent is added).
     */
    public static @NotNull String reindent(
            @NotNull String content, int stripIndent, int addIndent) {
        String[] lines = content.split("\n", -1);
        String strip = " ".repeat(Math.max(0, stripIndent));
        String add   = " ".repeat(Math.max(0, addIndent));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            String line = lines[i];
            if (!line.isBlank()) {
                if (line.startsWith(strip)) {
                    sb.append(add).append(line.substring(stripIndent));
                } else {
                    sb.append(add).append(line.stripLeading());
                }
            }
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Applying the embedding  (embed direction)
    // -----------------------------------------------------------------------

    /**
     * Embeds the FXML markup from {@code xmlFile} into the code-behind class's
     * {@code @ComponentView} annotation, adds the required Java/Kotlin import statements,
     * and deletes the FXML file.
     *
     * <p><b>Must be called from within a write action.</b>
     */
    public static void applyEmbed(@NotNull Project project, @NotNull XmlFile xmlFile) {
        XmlDocument xmlDoc = xmlFile.getDocument();
        if (xmlDoc == null) return;
        XmlTag rootTag = xmlDoc.getRootTag();
        if (rootTag == null) return;

        String fxClass = rootTag.getAttributeValue("fx:subclass");
        if (StringUtil.isEmptyOrSpaces(fxClass)) return;

        String simpleName = StringUtil.getShortName(fxClass);
        VirtualFile codeBehindVF = findCodeBehindFile(xmlFile.getVirtualFile(), simpleName);
        if (codeBehindVF == null) return;

        boolean isKotlin = isKotlinFile(codeBehindVF);
        PsiFile codeBehindPsi = PsiManager.getInstance(project).findFile(codeBehindVF);
        if (codeBehindPsi == null) return;

        // Raw markup: root element with xmlns/fx:subclass stripped, optionally preceded by
        // non-xml/non-import processing instructions from the document prolog.
        // Indentation and exact formatting are handled inside addAnnotationAndImports*,
        // which has access to the class's column and the project's code-style settings.
        String leadingDocPis = buildDocumentLeadingPis(xmlDoc);
        String rawMarkup = leadingDocPis + buildMarkupBody(rootTag);

        // Collect imports declared in the FXML file
        List<String> fxmlImports = Fxml2ImportResolver.parseImports(xmlFile);

        // Insert annotation + imports into code-behind
        if (isKotlin) {
            addAnnotationAndImportsToKotlin(project, codeBehindPsi, rawMarkup, fxmlImports);
        } else {
            addAnnotationAndImportsToJava(project, codeBehindPsi, rawMarkup, fxmlImports);
        }

        // Delete the FXML source file
        VirtualFile fxmlVF = xmlFile.getVirtualFile();
        if (fxmlVF != null) {
            try {
                fxmlVF.delete(Fxml2EmbedMarkupUtil.class);
            } catch (IOException ignored) {}
        }

        // Navigate to the code-behind file that now contains the embedded markup
        FileEditorManager.getInstance(project).openFile(codeBehindVF, true);
    }

    private static void addAnnotationAndImportsToJava(
            @NotNull Project project,
            @NotNull PsiFile codeBehindPsi,
            @NotNull String rawMarkup,
            @NotNull List<String> fxmlImports) {
        if (!(codeBehindPsi instanceof PsiJavaFile javaFile)) return;
        var classes = javaFile.getClasses();
        if (classes.length == 0) return;
        var psiClass = classes[0];

        Document document = PsiDocumentManager.getInstance(project).getDocument(javaFile);
        if (document == null) return;

        // Determine the column at which the annotation will be inserted
        // (same column as the class declaration it precedes).
        int classStart = psiClass.getTextRange().getStartOffset();
        int classLine  = document.getLineNumber(classStart);
        int annotationColumn = classStart - document.getLineStartOffset(classLine);

        // Compute base indent using the file's own indent settings
        int indentSize = CodeStyle.getIndentOptions(javaFile).INDENT_SIZE;
        int baseIndent = annotationColumn + indentSize;

        // Format the XML using the real XML formatter, falling back to simple reindent.
        String formattedContent = formatXmlContent(project, rawMarkup, baseIndent, annotationColumn,
                javaFile.getVirtualFile());
        if (formattedContent == null) {
            formattedContent = reindent(rawMarkup, 0, baseIndent) + "\n" + " ".repeat(annotationColumn);
        }

        String annotationText = "@ComponentView(\"\"\"\n" + formattedContent + "\"\"\")\n";

        // Insert the annotation text immediately before the class declaration
        document.insertString(classStart, annotationText);
        PsiDocumentManager.getInstance(project).commitDocument(document);

        // Add import for @ComponentView itself
        addJavaImport(project, javaFile, Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN);

        // Add imports derived from the FXML <?import?> PIs
        for (String fqn : fxmlImports) {
            if (fqn.endsWith(".*")) {
                addJavaImportOnDemand(project, javaFile, fqn.substring(0, fqn.length() - 2));
            } else {
                addJavaImport(project, javaFile, fqn);
            }
        }
    }

    private static void addAnnotationAndImportsToKotlin(
            @NotNull Project project,
            @NotNull PsiFile codeBehindPsi,
            @NotNull String rawMarkup,
            @NotNull List<String> fxmlImports) {
        try {
            if (!(codeBehindPsi instanceof org.jetbrains.kotlin.psi.KtFile ktFile)) return;
            var ktClass = PsiTreeUtil.findChildOfType(
                    ktFile, org.jetbrains.kotlin.psi.KtClassOrObject.class);
            if (ktClass == null) return;

            Document document = PsiDocumentManager.getInstance(project).getDocument(ktFile);
            if (document == null) return;

            int classStart = ktClass.getTextRange().getStartOffset();
            int classLine  = document.getLineNumber(classStart);
            int annotationColumn = classStart - document.getLineStartOffset(classLine);

            CommonCodeStyleSettings.IndentOptions indentOpts = CodeStyle.getIndentOptions(ktFile);
            int indentSize = indentOpts.INDENT_SIZE;
            int baseIndent = annotationColumn + indentSize;

            String formattedContent = formatXmlContent(project, rawMarkup, baseIndent, annotationColumn,
                    ktFile.getVirtualFile());
            if (formattedContent == null) {
                formattedContent = reindent(rawMarkup, 0, baseIndent) + "\n" + " ".repeat(annotationColumn);
            }

            // $$"""...""": Kotlin 2.x multi-dollar string; $ characters in the markup
            // are treated as literals, allowing FXML binding shortcuts unescaped.
            String annotationText = "@ComponentView($$\"\"\"\n" + formattedContent + "\"\"\")\n";

            // Insert annotation immediately before the class declaration
            document.insertString(classStart, annotationText);
            PsiDocumentManager.getInstance(project).commitDocument(document);

            // Add import for @ComponentView itself
            addKotlinImport(project, ktFile, Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN);

            // Add imports derived from the FXML <?import?> PIs
            for (String fqn : fxmlImports) {
                addKotlinImport(project, ktFile, fqn);
            }
        } catch (NoClassDefFoundError ignored) {}
    }

    // -----------------------------------------------------------------------
    // FXML file construction  (extract direction)
    // -----------------------------------------------------------------------

    /**
     * Splits a stripped markup body into its leading processing instructions and the
     * actual root element markup.
     *
     * <p>A markup body may begin with one or more {@code <?...?>} processing instructions
     * (e.g. {@code <?prefix?>}) before the root XML element. This method peels those off
     * so that callers can place them in the correct position in the generated standalone
     * FXML file (after {@code <?import?>} PIs but before the root element).
     *
     * @param markupBody the stripped markup body, potentially starting with PIs
     * @return a two-element array where index 0 is the (possibly empty) leading PI block
     *         (trailing newline included) and index 1 is the root element markup
     */
    static @NotNull String[] splitLeadingPis(@NotNull String markupBody) {
        StringBuilder pis = new StringBuilder();
        String remaining = markupBody;
        boolean progress = true;
        while (progress) {
            progress = false;
            // Strip leading processing instructions (excluding the <?xml?> declaration)
            while (remaining.startsWith("<?") && !remaining.startsWith("<?xml")) {
                int end = remaining.indexOf("?>");
                if (end < 0) break;
                pis.append(remaining, 0, end + 2).append('\n');
                remaining = remaining.substring(end + 2).stripLeading();
                progress = true;
            }
            // Strip leading XML comments (<!-- ... -->)
            while (remaining.startsWith("<!--")) {
                int end = remaining.indexOf("-->");
                if (end < 0) break;
                pis.append(remaining, 0, end + 3).append('\n');
                remaining = remaining.substring(end + 3).stripLeading();
                progress = true;
            }
        }
        return new String[] { pis.toString(), remaining };
    }

    /**
     * Builds the complete content of a standalone FXML file from a (stripped) markup
     * body string, the host class FQN, and a list of import targets.
     *
     * <p>The markup body may contain leading processing instructions (e.g.
     * {@code <?prefix?>}) before the root element. These are placed in the output after
     * the {@code <?import?>} PIs but before the root element, which receives the
     * {@code xmlns} and {@code fx:subclass} namespace attributes.
     *
     * @param markupBody the root element markup without {@code xmlns:*} / {@code fx:subclass},
     *                   already stripped of leading/trailing whitespace; may be preceded by
     *                   non-{@code xml} processing instructions
     * @param fqn        the fully-qualified class name for the {@code fx:subclass} attribute
     * @param imports    FQNs (or wildcard FQNs ending with {@code .*}) to emit as
     *                   {@code <?import?>} processing instructions
     * @return the full FXML file text
     */
    public static @NotNull String buildFxmlContent(
            @NotNull String markupBody,
            @NotNull String fqn,
            @NotNull List<String> imports) {
        String[] parts = splitLeadingPis(markupBody);
        String leadingPis = parts[0];
        String rootMarkup = parts[1];

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("\n");
        if (!imports.isEmpty()) {
            for (String imp : imports) {
                sb.append("<?import ").append(imp).append("?>\n");
            }
            sb.append("\n");
        }
        if (!leadingPis.isEmpty()) {
            sb.append(leadingPis);
            sb.append("\n");
        }
        sb.append(insertNamespaceAttributes(rootMarkup, fqn));
        sb.append("\n");
        return sb.toString();
    }

    /**
     * Inserts {@code xmlns}, {@code xmlns:fx}, and {@code fx:subclass} attributes immediately
     * after the root element's tag name in {@code markup}.
     *
     * <p>Example: {@code "<BorderPane/>"} -> {@code "<BorderPane xmlns=... fx:subclass=.../>"}
     *
     * @param markup the root-element markup string (must start with {@code <TagName})
     * @param fqn    the fully-qualified class name for the {@code fx:subclass} attribute
     * @return the markup string with namespace and class attributes inserted
     */
    public static @NotNull String insertNamespaceAttributes(
            @NotNull String markup, @NotNull String fqn) {
        if (markup.isEmpty() || markup.charAt(0) != '<') return markup;
        // Scan past the tag name to find the insertion point
        int i = 1;
        while (i < markup.length()) {
            char c = markup.charAt(i);
            if (Character.isWhitespace(c) || c == '>' || c == '/') break;
            i++;
        }
        String nsAttrs = " xmlns=\"http://javafx.com/javafx\""
                + " xmlns:fx=\"http://jfxcore.org/fxml/2.0\""
                + " fx:subclass=\"" + fqn + "\"";
        return markup.substring(0, i) + nsAttrs + markup.substring(i);
    }

    /**
     * Extracts the markup string from a Java {@code @ComponentView} annotation whose
     * value is a text-block string literal.
     *
     * <p>Java's text-block cooked value already has incidental whitespace stripped, so the
     * returned string is further {@link String#strip() stripped} of any remaining leading /
     * trailing whitespace.
     *
     * @param annotation the {@code @ComponentView} annotation
     * @return the trimmed markup string, or {@code null} if extraction fails or the value is blank
     */
    public static @Nullable String extractMarkupFromJavaAnnotation(
            @NotNull PsiAnnotation annotation) {
        PsiAnnotationMemberValue value = annotation.findAttributeValue("value");
        if (!(value instanceof PsiLiteralExpression literal)) return null;
        Object val = literal.getValue();
        if (!(val instanceof String s)) return null;
        // stripIndent() removes the common leading whitespace from all lines (the
        // incidental indentation from the text-block closing-delimiter position),
        // then strip() removes any remaining leading/trailing whitespace.
        String stripped = s.stripIndent().strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /**
     * Extracts the markup content from a Kotlin {@link KtStringTemplateExpression}
     * that is the value of a {@code @ComponentView} annotation.
     *
     * <p>Handles both {@code """..."""} and {@code $$"""..."""} triple-quoted strings.
     * Calls {@link String#stripIndent()} on the raw content to remove the common leading
     * whitespace, then strips the result.
     *
     * @param expr the PSI element (must be a {@link KtStringTemplateExpression})
     * @return the trimmed markup string, or {@code null} if extraction fails or the value is blank
     */
    public static @Nullable String extractMarkupFromKotlinExpression(@Nullable PsiElement expr) {
        if (expr == null) return null;
        try {
            if (!(expr instanceof KtStringTemplateExpression ktStr)) return null;
            String text = ktStr.getText();
            int tripleQuoteStart = text.indexOf("\"\"\"");
            if (tripleQuoteStart < 0) return null;
            int contentStart = tripleQuoteStart + 3;
            int tripleQuoteEnd = text.lastIndexOf("\"\"\"");
            if (tripleQuoteEnd <= tripleQuoteStart) return null;
            String content = text.substring(contentStart, tripleQuoteEnd);
            String stripped = content.stripIndent().strip();
            return stripped.isEmpty() ? null : stripped;
        } catch (NoClassDefFoundError ignored) {
            return null;
        }
    }

    /**
     * Collects all non-static, non-{@code ComponentView} import targets from a Java file
     * for conversion to {@code <?import?>} processing instructions in the generated FXML file.
     *
     * @return a mutable list of FQNs (wildcard imports have the {@code .*} suffix restored)
     */
    public static @NotNull List<String> collectJavaImportsForFxml(@NotNull PsiJavaFile javaFile) {
        PsiImportStatement[] statements = (javaFile.getImportList() != null)
                ? javaFile.getImportList().getImportStatements()
                : new PsiImportStatement[0];
        List<String> result = new ArrayList<>();
        for (PsiImportStatement imp : statements) {
            if (imp instanceof PsiImportStaticStatement) continue;
            String qn = imp.getQualifiedName();
            if (qn == null) continue;
            if (Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN.equals(qn)) continue;
            result.add(imp.isOnDemand() ? qn + ".*" : qn);
        }
        return result;
    }

    /**
     * Collects all non-{@code ComponentView} import targets from a Kotlin file
     * for conversion to {@code <?import?>} processing instructions in the generated FXML file.
     *
     * <p>This method is a no-op when the Kotlin plugin is not present at runtime.
     *
     * @param file the host Kotlin file (silently ignored if not a {@code KtFile})
     * @return a mutable list of FQNs (wildcard imports have the {@code .*} suffix restored)
     */
    public static @NotNull List<String> collectKotlinImportsForFxml(@NotNull PsiFile file) {
        try {
            if (!(file instanceof KtFile ktFile)) return List.of();
            List<String> result = new ArrayList<>();
            for (KtImportDirective imp : ktFile.getImportDirectives()) {
                if (!imp.isValidImport()) continue;
                var fqName = imp.getImportedFqName();
                if (fqName == null) continue;
                String fqStr = fqName.asString();
                if (Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN.equals(fqStr)) continue;
                result.add(imp.isAllUnder() ? fqStr + ".*" : fqStr);
            }
            return result;
        } catch (NoClassDefFoundError ignored) {
            return List.of();
        }
    }

    // -----------------------------------------------------------------------
    // Applying the extraction  (extract direction)
    // -----------------------------------------------------------------------

    /**
     * Creates a sibling FXML file from the markup embedded in a Java
     * {@code @ComponentView} annotation, removes the annotation, and removes the
     * {@code ComponentView} import if it is no longer used.
     *
     * <p>The new file is opened in the editor on success.
     *
     * <p><b>Must be called from within a write action.</b>
     *
     * @param project    the current project
     * @param javaFile   the Java file that owns the annotated class
     * @param annotation the {@code @ComponentView} annotation to extract from
     */
    public static void applyExtractJava(
            @NotNull Project project,
            @NotNull PsiJavaFile javaFile,
            @NotNull PsiAnnotation annotation) {
        PsiClass hostClass = PsiTreeUtil.getParentOfType(annotation, PsiClass.class);
        if (hostClass == null) return;
        String fqn = hostClass.getQualifiedName();
        if (fqn == null) return;
        String simpleName = StringUtil.getShortName(fqn);

        String markupBody = extractMarkupFromJavaAnnotation(annotation);
        if (markupBody == null) return;

        VirtualFile javaVF = javaFile.getVirtualFile();
        if (javaVF == null) return;
        VirtualFile dir = javaVF.getParent();
        if (dir == null) return;

        // Reformat the markup with the correct XML indent size (respects EditorConfig for
        // *.fxml files in the host directory) so the generated standalone file has consistent
        // indentation regardless of the indentation inside the @ComponentView text block.
        String reformattedMarkup = formatXmlContent(project, markupBody, 0, 0, javaVF);
        if (reformattedMarkup != null) {
            markupBody = reformattedMarkup.stripTrailing();
        }

        List<String> imports = collectJavaImportsForFxml(javaFile);
        String fxmlContent = buildFxmlContent(markupBody, fqn, imports);

        VirtualFile fxmlVF;
        try {
            fxmlVF = dir.createChildData(Fxml2EmbedMarkupUtil.class, simpleName + ".fxml");
            fxmlVF.setBinaryContent(fxmlContent.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            return;
        }

        // Run import optimizer on the newly created FXML file to remove any imports that
        // are only needed by Java code but not referenced in the FXML markup.
        PsiFile fxmlPsiFile = PsiManager.getInstance(project).findFile(fxmlVF);
        if (fxmlPsiFile != null) {
            var fxmlOptimizer = new Fxml2ImportOptimizer();
            if (fxmlOptimizer.supports(fxmlPsiFile)) {
                fxmlOptimizer.processFile(fxmlPsiFile).run();
            }
        }

        annotation.delete();
        JavaCodeStyleManager.getInstance(project).optimizeImports(javaFile);
        FileEditorManager.getInstance(project).openFile(fxmlVF, true);
    }

    /**
     * Creates a sibling FXML file from the markup embedded in a Kotlin
     * {@code @ComponentView} annotation entry, removes the annotation entry, and removes
     * the {@code ComponentView} import if it is no longer used.
     *
     * <p>The new file is opened in the editor on success.
     *
     * <p><b>Must be called from within a write action.</b>
     *
     * <p>This method is a no-op when the Kotlin plugin is not present at runtime.
     *
     * @param project               the current project
     * @param hostFile              the Kotlin file that owns the annotated class
     *                              (silently ignored if not a {@link KtFile})
     * @param annotationEntryElement the {@code KtAnnotationEntry} to extract from
     *                              (silently ignored if not a {@link KtAnnotationEntry})
     */
    public static void applyExtractKotlin(
            @NotNull Project project,
            @NotNull PsiFile hostFile,
            @NotNull PsiElement annotationEntryElement) {
        try {
            if (!(annotationEntryElement instanceof KtAnnotationEntry annotEntry)) return;
            if (!(hostFile instanceof KtFile ktFile)) return;

            KtClassOrObject ktClass = PsiTreeUtil.getParentOfType(annotEntry, KtClassOrObject.class);
            if (ktClass == null) return;
            var lightClass = KotlinAsJavaSupport.getInstance(ktClass.getProject())
                    .getLightClass(ktClass);
            if (lightClass == null) return;
            String fqn = lightClass.getQualifiedName();
            if (fqn == null) return;
            String simpleName = StringUtil.getShortName(fqn);

            var args = annotEntry.getValueArguments();
            if (args.isEmpty()) return;
            var argExpr = args.getFirst().getArgumentExpression();
            String markupBody = extractMarkupFromKotlinExpression(argExpr);
            if (markupBody == null) return;

            VirtualFile ktVF = ktFile.getVirtualFile();
            if (ktVF == null) return;
            VirtualFile dir = ktVF.getParent();
            if (dir == null) return;

            // Reformat with the correct XML indent size (respects EditorConfig for *.fxml
            // files in the host directory).
            String reformattedMarkup = formatXmlContent(project, markupBody, 0, 0, ktVF);
            if (reformattedMarkup != null) {
                markupBody = reformattedMarkup.stripTrailing();
            }

            List<String> imports = collectKotlinImportsForFxml(ktFile);
            String fxmlContent = buildFxmlContent(markupBody, fqn, imports);

            VirtualFile fxmlVF;
            try {
                fxmlVF = dir.createChildData(Fxml2EmbedMarkupUtil.class, simpleName + ".fxml");
                fxmlVF.setBinaryContent(fxmlContent.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                return;
            }

            // Run import optimizer on the newly created FXML file to remove any imports
            // that are only needed by Kotlin code but not referenced in the FXML markup.
            PsiFile fxmlPsiFile = PsiManager.getInstance(project).findFile(fxmlVF);
            if (fxmlPsiFile != null) {
                var fxmlOptimizer = new Fxml2ImportOptimizer();
                if (fxmlOptimizer.supports(fxmlPsiFile)) {
                    fxmlOptimizer.processFile(fxmlPsiFile).run();
                }
            }

            annotEntry.delete();
            for (ImportOptimizer optimizer :
                    LanguageImportStatements.INSTANCE.allForLanguage(ktFile.getLanguage())) {
                optimizer.processFile(ktFile).run();
            }
            FileEditorManager.getInstance(project).openFile(fxmlVF, true);
        } catch (NoClassDefFoundError ignored) {}
    }

    // -----------------------------------------------------------------------
    // Import utilities (also consumed by the reverse action in the future)
    // -----------------------------------------------------------------------

    /**
     * Adds a non-wildcard {@code import} statement for {@code fqn} to {@code javaFile},
     * unless the import is already present or covered by an existing on-demand import.
     */
    public static void addJavaImport(
            @NotNull Project project,
            @NotNull PsiJavaFile javaFile,
            @NotNull String fqn) {
        var importList = javaFile.getImportList();
        if (importList == null) return;

        // Skip if an exact import for this FQN already exists
        for (PsiImportStatement s : importList.getImportStatements()) {
            if (!s.isOnDemand() && fqn.equals(s.getQualifiedName())) return;
        }
        // Skip if a wildcard import for the enclosing package already covers it
        int dot = fqn.lastIndexOf('.');
        if (dot > 0) {
            String pkg = fqn.substring(0, dot);
            for (PsiImportStatement s : importList.getImportStatements()) {
                if (s.isOnDemand() && pkg.equals(s.getQualifiedName())) return;
            }
        }

        var psiClass = JavaPsiFacade.getInstance(project)
                .findClass(fqn, javaFile.getResolveScope());
        if (psiClass == null) return;
        try {
            importList.add(PsiElementFactory.getInstance(project).createImportStatement(psiClass));
        } catch (IncorrectOperationException ignored) {}
    }

    /**
     * Adds a wildcard (on-demand) {@code import packageName.*;} statement to
     * {@code javaFile}, unless already present.
     */
    public static void addJavaImportOnDemand(
            @NotNull Project project,
            @NotNull PsiJavaFile javaFile,
            @NotNull String packageName) {
        var importList = javaFile.getImportList();
        if (importList == null) return;
        for (PsiImportStatement s : importList.getImportStatements()) {
            if (s.isOnDemand() && packageName.equals(s.getQualifiedName())) return;
        }
        try {
            importList.add(PsiElementFactory.getInstance(project)
                    .createImportStatementOnDemand(packageName));
        } catch (IncorrectOperationException ignored) {}
    }

    /**
     * Adds an import directive for {@code fqn} to the Kotlin {@code ktFile},
     * unless it is already present or covered by a wildcard import.
     *
     * <p>Supports both exact imports and wildcard imports ({@code fqn} ending with
     * {@code .*}).
     *
     * <p>This method is a no-op when the Kotlin plugin is not present at runtime.
     */
    public static void addKotlinImport(
            @NotNull Project project,
            @NotNull PsiFile file,
            @NotNull String fqn) {
        try {
            if (!(file instanceof org.jetbrains.kotlin.psi.KtFile ktFile)) return;
            var importList = ktFile.getImportList();
            if (importList == null) return;

            boolean isWildcard = fqn.endsWith(".*");
            String baseName = isWildcard ? fqn.substring(0, fqn.length() - 2) : fqn;

            for (var imp : ktFile.getImportDirectives()) {
                if (!imp.isValidImport()) continue;
                var name = imp.getImportedFqName();
                if (name == null) continue;
                String nameStr = name.asString();
                if (isWildcard) {
                    // Exact wildcard match
                    if (imp.isAllUnder() && baseName.equals(nameStr)) return;
                } else {
                    if (!imp.isAllUnder() && fqn.equals(nameStr)) return; // exact match
                    // Covered by an existing wildcard?
                    if (imp.isAllUnder()) {
                        int dot = fqn.lastIndexOf('.');
                        if (dot > 0 && nameStr.equals(fqn.substring(0, dot))) return;
                    }
                }
            }

            var factory = new org.jetbrains.kotlin.psi.KtPsiFactory(project);
            String importText = isWildcard
                    ? "import " + baseName + ".*"
                    : "import " + fqn;
            var tempFile = factory.createFile(importText + "\n");
            var directives = tempFile.getImportDirectives();
            if (!directives.isEmpty()) {
                importList.add(directives.getFirst().copy());
            }
        } catch (NoClassDefFoundError ignored) {
            // Kotlin plugin not present at runtime: skip
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the index of the {@code >} character that terminates the XML opening tag,
     * correctly skipping any {@code >} characters that appear inside quoted attribute values.
     *
     * @param text the full text of an XML element (starting with {@code <})
     * @return the index of the closing {@code >}, or {@code -1} if not found
     */
    private static int findOpenTagEnd(@NotNull String text) {
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuote) {
                if (c == quoteChar) inQuote = false;
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                quoteChar = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }
}
