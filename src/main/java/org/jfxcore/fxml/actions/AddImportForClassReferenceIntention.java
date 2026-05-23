package org.jfxcore.fxml.actions;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.codeinsight.Fxml2AddImportFix;
import org.jfxcore.fxml.lang.Fxml2ImportUtil;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;

/**
 * Intention action that offers to add an {@code <?import fqn?>} processing instruction
 * for a fully-qualified class name that the user has typed inline (e.g.
 * {@code $javafx.scene.input.KeyEvent.KEY_PRESSED},
 * {@code <javafx.scene.control.Label .../>}, or
 * {@code org.jfxcore.command.Command.onAction="..."}) without a corresponding import.
 *
 * <p>Available wherever the caret is on a resolved class-segment reference inside any
 * FXML 2.0 attribute value (binding expressions, {@code fx:typeArguments},
 * {@code String}-typed class-name attributes), on a class segment of a FQN element tag
 * name, or on the class segment of a FQN static-property attribute name, and the class
 * is not yet imported in the file.
 *
 * <p>For <em>standalone</em> FXML 2.0 files the import is inserted as a
 * {@code <?import fqn?>} processing instruction.
 * For <em>embedded</em> FXML 2.0 (from a {@code @ComponentView} annotation) a Java
 * {@code import} statement is inserted into the host Java file instead.
 *
 * <p>This is an intention (not a quick fix) because there is nothing wrong with using a
 * fully-qualified name, the import is simply a convenience to allow using the short form.
 */
public final class AddImportForClassReferenceIntention implements IntentionAction, PriorityAction {

    private static final String COMMAND_NAME = "Add Import for Class Reference";

    @Override
    public @NotNull String getFamilyName() {
        return "Add FXML 2.0 import";
    }

    @Override
    public @NotNull String getText() {
        return "Add import for class reference";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        return resolveTarget(editor, file) != null;
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file)
            throws IncorrectOperationException {
        Target target = resolveTarget(editor, file);
        if (target == null) return;
        XmlFile xmlFile = (XmlFile) file;

        if (Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile)) {
            // Embedded FXML 2.0: extract host Java file BEFORE any write actions (while the
            // injected XML PSI is still valid), then:
            //   1. Shorten the FQN in the embedded markup (writes through to Java literal).
            //   2. Add a Java import statement to the host Java file.
            PsiJavaFile javaFile = getHostJavaFile(xmlFile);
            doFqnCleanup(project, target, file);
            if (javaFile != null) {
                insertJavaImport(project, target.fqn(), javaFile);
            }
        } else {
            // Standalone FXML 2.0: add <?import?> PI first, then shorten the FQN.
            Fxml2AddImportFix.insertImport(project, xmlFile, target.fqn());
            doFqnCleanup(project, target, file);
        }
    }

    @Override
    public boolean startInWriteAction() {
        return false;
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.TOP;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Holds everything needed to perform the "add import + clean up" action.
     *
     * <p>Exactly one of the three clean-up fields will be non-null:
     * <ul>
     *   <li>{@code attrVal} + {@code redundantPrefix}: attribute value with a redundant FQN prefix</li>
     *   <li>{@code fqnTag}: element tag whose local name is the FQN class</li>
     *   <li>{@code fqnAttr}: static-property attribute whose name starts with a FQN class</li>
     * </ul>
     */
    private record Target(
            @NotNull String fqn,
            @Nullable XmlAttributeValue attrVal,
            @Nullable String redundantPrefix,
            int redundantPrefixStart,
            @Nullable XmlTag fqnTag,
            @Nullable XmlAttribute fqnAttr) {

        /** Constructor for the attribute-value case. */
        Target(@NotNull String fqn,
               @Nullable XmlAttributeValue attrVal,
               @Nullable String redundantPrefix,
               int redundantPrefixStart) {
            this(fqn, attrVal, redundantPrefix, redundantPrefixStart, null, null);
        }

        /** Constructor for the FQN element-tag case. */
        Target(@NotNull String fqn, @NotNull XmlTag fqnTag) {
            this(fqn, null, null, -1, fqnTag, null);
        }

        /** Constructor for the FQN static-property attribute case. */
        Target(@NotNull String fqn, @NotNull XmlAttribute fqnAttr) {
            this(fqn, null, null, -1, null, fqnAttr);
        }
    }

    /**
     * Returns {@code true} if {@code fqn} is already accessible under its simple name
     * via the file's {@code <?import?>} declarations (including implicit {@code java.lang.*}).
     */
    private static boolean isAlreadyImported(@NotNull String fqn, @NotNull XmlFile xmlFile) {
        PsiClass already = Fxml2ImportResolver.resolve(Fxml2ImportUtil.simpleNameOf(fqn), xmlFile);
        return already != null && fqn.equals(already.getQualifiedName());
    }

    /**
     * Resolves a FQN to a {@link PsiClass}, also trying the nested-class {@code $} variant
     * when the plain dot-separated lookup fails.
     */
    private static @Nullable PsiClass findClassByFqn(@NotNull String fqn,
                                                      @NotNull JavaPsiFacade facade,
                                                      @NotNull GlobalSearchScope scope) {
        PsiClass cls = facade.findClass(fqn, scope);
        if (cls != null) return cls;
        int dot = fqn.lastIndexOf('.');
        if (dot > 0) {
            cls = facade.findClass(fqn.substring(0, dot) + "$" + fqn.substring(dot + 1), scope);
        }
        return cls;
    }

    /**
     * Finds the class FQN that should be imported given the current caret position, or
     * {@code null} if the intention does not apply.
     *
     * <p>Three kinds of use sites are handled:
     * <ol>
     *   <li><b>Attribute value</b>: caret inside an {@link XmlAttributeValue} on a resolved
     *       class-segment reference for a not-yet-imported class.</li>
     *   <li><b>FQN element tag</b>: caret on the class segment of a FQN element tag name.</li>
     *   <li><b>FQN static-property attribute name</b>: caret on the class segment of a FQN
     *       static-property attribute (e.g. {@code org.jfxcore.command.Command.onAction}).</li>
     * </ol>
     */
    private static @Nullable Target resolveTarget(@Nullable Editor editor, @NotNull PsiFile file) {
        if (!(file instanceof XmlFile xmlFile)) return null;
        if (!Fxml2FileType.isFxml2(xmlFile)) return null;
        if (editor == null) return null;

        int caretOffset = editor.getCaretModel().getOffset();
        PsiElement el = file.findElementAt(caretOffset);
        if (el == null) return null;

        // Walk up to XmlAttributeValue, XmlAttribute, or XmlTag.
        XmlAttributeValue attrVal = null;
        XmlAttribute attrEl = null;
        XmlTag tagEl = null;
        PsiElement cur = el;
        outer:
        while (cur != null) {
            switch (cur) {
                case XmlAttributeValue v -> { attrVal = v; break outer; }
                case XmlAttribute a      -> { attrEl  = a; break outer; }
                case XmlTag t            -> { tagEl   = t; break outer; }
                default -> cur = cur.getParent();
            }
        }

        if (attrVal != null) return resolveTargetFromAttrVal(attrVal, caretOffset, xmlFile);
        if (attrEl  != null) return resolveTargetFromFqnStaticPropertyAttr(attrEl, caretOffset, xmlFile);
        if (tagEl   != null) return resolveTargetFromFqnTag(tagEl, caretOffset, xmlFile);
        return null;
    }

    /**
     * Handles the attribute-value case: looks for a reference on {@code attrVal} that
     * covers the caret offset and resolves to an un-imported class.
     */
    private static @Nullable Target resolveTargetFromAttrVal(
            @NotNull XmlAttributeValue attrVal,
            int caretOffset,
            @NotNull XmlFile xmlFile) {

        int elementStart = attrVal.getTextRange().getStartOffset();
        int relOffset = caretOffset - elementStart;

        for (PsiReference ref : attrVal.getReferences()) {
            if (!ref.getRangeInElement().containsOffset(relOffset)) continue;
            PsiElement resolved = ref.resolve();
            if (!(resolved instanceof PsiClass cls)) continue;
            String fqn = cls.getQualifiedName();
            if (fqn == null || cls.getName() == null) continue;
            if (isAlreadyImported(fqn, xmlFile)) continue;

            // Compute the redundant package prefix to strip after adding the import.
            // A) Per-segment ref: prefix precedes the reference range.
            // B) Full-FQN ref (e.g. markup extension class): prefix is at the start of the range.
            String packagePrefix = fqn.contains(".") ? fqn.substring(0, fqn.lastIndexOf('.') + 1) : null;
            int prefixStartInValue = -1;
            if (packagePrefix != null) {
                String rawValue = attrVal.getValue();
                int caseA = (ref.getRangeInElement().getStartOffset() - 1) - packagePrefix.length();
                int caseB =  ref.getRangeInElement().getStartOffset() - 1;
                if      (caseA >= 0 && rawValue.startsWith(packagePrefix, caseA)) prefixStartInValue = caseA;
                else if (caseB >= 0 && rawValue.startsWith(packagePrefix, caseB)) prefixStartInValue = caseB;
                else packagePrefix = null;
            }

            return new Target(fqn, attrVal, packagePrefix, prefixStartInValue);
        }
        return null;
    }

    /**
     * Handles the FQN static-property attribute name case: if {@code attr} has a dotted name
     * where the class part is a FQN (e.g. {@code org.jfxcore.command.Command.onAction}) and
     * the caret is on the class-name segment, and the class is not yet imported, returns a
     * target for adding the import and renaming the attribute to {@code Command.onAction}.
     */
    private static @Nullable Target resolveTargetFromFqnStaticPropertyAttr(
            @NotNull XmlAttribute attr,
            int caretOffset,
            @NotNull XmlFile xmlFile) {

        String attrName = attr.getName();
        if (!attrName.contains(".")) return null;

        // The caret must be inside the attribute name element (not the value).
        PsiElement nameElem = attr.getNameElement();
        if (nameElem == null) return null;
        int nameStart = nameElem.getTextRange().getStartOffset();
        if (caretOffset < nameStart || caretOffset > nameElem.getTextRange().getEndOffset()) return null;

        // Everything before the last dot is the class candidate (the last segment is the property).
        int lastDot = attrName.lastIndexOf('.');
        if (lastDot <= 0) return null;
        String classCandidate = attrName.substring(0, lastDot);

        // Must be a multi-segment FQN starting with a known package.
        if (!classCandidate.contains(".")) return null;
        JavaPsiFacade facade = JavaPsiFacade.getInstance(xmlFile.getProject());
        GlobalSearchScope scope = GlobalSearchScope.allScope(xmlFile.getProject());
        if (facade.findPackage(classCandidate.substring(0, classCandidate.indexOf('.'))) == null) return null;

        PsiClass cls = findClassByFqn(classCandidate, facade, scope);
        if (cls == null) return null;

        String fqn = cls.getQualifiedName();
        if (fqn == null || cls.getName() == null) return null;

        // Caret must be within the class portion, not the property segment after the last dot.
        if (caretOffset > nameStart + classCandidate.length()) return null;

        if (isAlreadyImported(fqn, xmlFile)) return null;
        return new Target(fqn, attr);
    }

    /**
     * Handles the FQN element-tag case: if {@code tag} has a FQN local name and the caret
     * is on the class segment, and the class is not yet imported, returns a target for adding
     * the import and renaming the tag.
     */
    private static @Nullable Target resolveTargetFromFqnTag(
            @NotNull XmlTag tag,
            int caretOffset,
            @NotNull XmlFile xmlFile) {

        String localName = tag.getLocalName();
        if (!localName.contains(".")) return null;

        int relOffset = caretOffset - tag.getTextRange().getStartOffset();

        for (PsiReference ref : tag.getReferences()) {
            if (!ref.getRangeInElement().containsOffset(relOffset)) continue;
            PsiElement resolved = ref.resolve();
            if (!(resolved instanceof PsiClass cls)) continue;
            String fqn = cls.getQualifiedName();
            if (fqn == null || cls.getName() == null) continue;
            if (!localName.equals(fqn)) continue;
            if (isAlreadyImported(fqn, xmlFile)) return null;
            return new Target(fqn, tag);
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // FQN cleanup (shortens the FQN to a simple name in the XML)
    // -----------------------------------------------------------------------

    /**
     * Performs the "shorten FQN" write action on the XML element identified by
     * {@code target}.  For embedded FXML2 this writes through the injection host
     * back into the Java string literal.
     */
    private static void doFqnCleanup(
            @NotNull Project project,
            @NotNull Target target,
            @NotNull PsiFile file) {

        if (target.redundantPrefix() != null && target.attrVal() != null) {
            XmlAttributeValue attrVal = target.attrVal();
            String redundantPrefix = target.redundantPrefix();
            int startInValue = target.redundantPrefixStart();
            WriteCommandAction.runWriteCommandAction(project, COMMAND_NAME, null, () -> {
                if (!(attrVal.getParent() instanceof XmlAttribute attr)) return;
                String currentValue = attrVal.getValue();
                if (currentValue.startsWith(redundantPrefix, startInValue)) {
                    attr.setValue(currentValue.substring(0, startInValue)
                            + currentValue.substring(startInValue + redundantPrefix.length()));
                }
            }, file);
        } else if (target.fqnTag() != null) {
            XmlTag tag = target.fqnTag();
            String shortName = Fxml2ImportUtil.simpleNameOf(target.fqn());
            WriteCommandAction.runWriteCommandAction(project, COMMAND_NAME, null, () -> {
                if (tag.getLocalName().equals(target.fqn())) {
                    try {
                        tag.setName(shortName);
                    } catch (IncorrectOperationException ignored) {}
                }
            }, file);
        } else if (target.fqnAttr() != null) {
            XmlAttribute attr = target.fqnAttr();
            String fqnAttrName = attr.getName();
            String shortAttrName = Fxml2ImportUtil.simpleNameOf(target.fqn()) + fqnAttrName.substring(target.fqn().length());
            WriteCommandAction.runWriteCommandAction(project, COMMAND_NAME, null, () -> {
                if (fqnAttrName.equals(attr.getName())) {
                    try {
                        attr.setName(shortAttrName);
                    } catch (IncorrectOperationException ignored) {}
                }
            }, file);
        }
    }

    // -----------------------------------------------------------------------
    // Embedded FXML2 helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the {@link PsiJavaFile} that hosts the injection for the given
     * embedded FXML2 file, or {@code null} if it cannot be determined.
     */
    private static @Nullable PsiJavaFile getHostJavaFile(@NotNull XmlFile embeddedXmlFile) {
        PsiLanguageInjectionHost host = Fxml2EmbeddedUtil.getInjectionHost(embeddedXmlFile);
        if (host == null) return null;
        return PsiTreeUtil.getParentOfType(host, PsiJavaFile.class, false);
    }

    /**
     * Adds {@code import fqn;} to the host Java file's import list.
     * A no-op if the class is already imported (specific or wildcard).
     */
    private static void insertJavaImport(
            @NotNull Project project,
            @NotNull String fqn,
            @NotNull PsiJavaFile javaFile) {

        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        PsiClass cls = facade.findClass(fqn, scope);
        if (cls == null) return;

        WriteCommandAction.runWriteCommandAction(project, COMMAND_NAME, null, () -> {
            PsiDocumentManager.getInstance(project).commitAllDocuments();
            PsiImportList importList = javaFile.getImportList();
            if (importList == null) return;

            // Skip if already present as a specific import.
            if (importList.findSingleClassImportStatement(fqn) != null) return;
            // Skip if already covered by a wildcard import for the same package.
            int lastDot = fqn.lastIndexOf('.');
            if (lastDot > 0) {
                String pkg = fqn.substring(0, lastDot);
                if (importList.findOnDemandImportStatement(pkg) != null) return;
            }

            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
            importList.add(factory.createImportStatement(cls));
        }, javaFile);
    }
}
