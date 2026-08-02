package org.jfxcore.fxml.annotator;

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.XmlSuppressableInspectionTool;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.lang.Fxml2CodeBehindFileFactory;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.resolve.Fxml2CodeBehindName;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;

/**
 * Inspection that reports an {@code fx:subclass} directive whose code-behind class does
 * not exist, mirroring the compiler's {@code CLASS_NOT_FOUND} error
 * ({@code "'com.sample.MyControl' cannot be resolved"}).
 *
 * <p>Two quick-fixes are offered:
 * <ul>
 *   <li><em>Remove fx:subclass</em>, which compiles the markup down to a standalone class
 *       named after the FXML file.</li>
 *   <li><em>Create code-behind class</em>, which creates the missing Java or Kotlin class
 *       extending the compiler-generated markup base class. This fix is only offered when
 *       the declared name is a fully-qualified name built from valid Java identifiers whose
 *       simple name matches the FXML file name, because the compiler rejects any other
 *       code-behind name.</li>
 * </ul>
 *
 * <p>Embedded markup is not checked: there the code-behind class is the
 * {@code @ComponentView}-annotated class itself, and {@code fx:subclass} is not supported.
 */
public final class Fxml2UnresolvedSubclassInspection extends XmlSuppressableInspectionTool {

    /** Short name used for suppression comments and the inspection profile key. */
    public static final String SHORT_NAME = "Fxml2UnresolvedSubclass";

    @Override
    public @NotNull String getShortName() {
        return SHORT_NAME;
    }

    @Override
    public ProblemDescriptor @Nullable [] checkFile(
            @NotNull PsiFile file, @NotNull InspectionManager manager, boolean isOnTheFly) {
        if (!(file instanceof XmlFile xmlFile)) return null;
        if (!Fxml2FileType.isFxml2(xmlFile)) return null;
        // Embedded markup derives its code-behind class from the annotated class, and
        // fx:subclass is reported as unsupported there by Fxml2FxAttributeInspection.
        if (Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile)) return null;

        XmlAttribute attr = findSubclassAttribute(xmlFile);
        if (attr == null) return null;

        String declaredName = attr.getValue();
        if (declaredName == null || declaredName.isBlank()) return null;
        String trimmed = declaredName.trim();

        if (JavaPsiFacade.getInstance(file.getProject())
                .findClass(trimmed, xmlFile.getResolveScope()) != null) {
            return null;
        }

        LocalQuickFix[] fixes = createFixes(xmlFile, trimmed);
        ProblemDescriptor problem = createProblem(manager, attr, trimmed, fixes, isOnTheFly);
        return new ProblemDescriptor[]{problem};
    }

    /**
     * Returns the {@code fx:subclass} attribute of the document root element, or
     * {@code null} when the document has no root element or no such attribute.
     * Placement of {@code fx:subclass} on non-root elements is reported separately.
     */
    private static @Nullable XmlAttribute findSubclassAttribute(@NotNull XmlFile xmlFile) {
        XmlDocument document = xmlFile.getDocument();
        if (document == null) return null;
        XmlTag rootTag = document.getRootTag();
        if (rootTag == null) return null;

        for (XmlAttribute attr : rootTag.getAttributes()) {
            if ("subclass".equals(attr.getLocalName())
                    && Fxml2ImportResolver.FXML2_NAMESPACE.equals(attr.getNamespace())) {
                return attr;
            }
        }
        return null;
    }

    private static LocalQuickFix @NotNull [] createFixes(
            @NotNull XmlFile xmlFile, @NotNull String declaredName) {
        RemoveSubclassFix removeFix = new RemoveSubclassFix();
        Fxml2CodeBehindName name = Fxml2CodeBehindName.parse(declaredName);
        if (name == null || !name.simpleName().equals(fileNameWithoutExtension(xmlFile))) {
            return new LocalQuickFix[]{removeFix};
        }
        return new LocalQuickFix[]{new CreateCodeBehindFix(name), removeFix};
    }

    private static @NotNull String fileNameWithoutExtension(@NotNull XmlFile xmlFile) {
        return FileUtilRt.getNameWithoutExtension(xmlFile.getName());
    }

    /**
     * Creates the problem, highlighting the declared name inside the attribute value quotes
     * when possible, and the whole attribute otherwise.
     */
    private static @NotNull ProblemDescriptor createProblem(
            @NotNull InspectionManager manager,
            @NotNull XmlAttribute attr,
            @NotNull String declaredName,
            LocalQuickFix @NotNull [] fixes,
            boolean isOnTheFly) {
        String message = "'" + declaredName + "' cannot be resolved";
        XmlAttributeValue valueEl = attr.getValueElement();
        if (valueEl != null) {
            String text = valueEl.getText();
            int start = text.indexOf(declaredName);
            if (start >= 0) {
                TextRange range = TextRange.from(start, declaredName.length());
                return manager.createProblemDescriptor(
                        valueEl, range, message, ProblemHighlightType.GENERIC_ERROR, isOnTheFly, fixes);
            }
        }
        return manager.createProblemDescriptor(
                attr, message, isOnTheFly, fixes, ProblemHighlightType.GENERIC_ERROR);
    }

    // -----------------------------------------------------------------------
    // Quick-fixes
    // -----------------------------------------------------------------------

    /**
     * Removes the {@code fx:subclass} attribute, so that the markup is compiled down to a
     * standalone class named after the FXML file.
     */
    static final class RemoveSubclassFix implements LocalQuickFix {

        @Override
        public @NotNull String getFamilyName() {
            return "Remove fx:subclass";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            XmlAttribute attr = findAttribute(descriptor.getPsiElement());
            if (attr != null) attr.delete();
        }
    }

    /**
     * Creates the missing code-behind class next to the FXML file.
     */
    static final class CreateCodeBehindFix implements LocalQuickFix {

        private final Fxml2CodeBehindName name;

        CreateCodeBehindFix(@NotNull Fxml2CodeBehindName name) {
            this.name = name;
        }

        @Override
        public @NotNull String getName() {
            return "Create code-behind class '" + name.simpleName() + "'";
        }

        @Override
        public @NotNull String getFamilyName() {
            return "Create code-behind class";
        }

        /** The fix creates another file, which cannot be shown as a single-file diff. */
        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project, @NotNull ProblemDescriptor previewDescriptor) {
            return IntentionPreviewInfo.EMPTY;
        }

        @Override
        public boolean startInWriteAction() {
            return false; // the file is created outside the inspection's write action
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            if (element == null || !element.isValid()) return;
            PsiFile file = element.getContainingFile();
            if (file == null) return;
            Fxml2CodeBehindFileFactory.create(project, file, name);
        }
    }

    /** Returns the attribute a problem was registered on, whether that is the value or the attribute. */
    private static @Nullable XmlAttribute findAttribute(@Nullable PsiElement element) {
        if (element == null || !element.isValid()) return null;
        return element instanceof XmlAttribute attr
                ? attr
                : PsiTreeUtil.getParentOfType(element, XmlAttribute.class);
    }
}
