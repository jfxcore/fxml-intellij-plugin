package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.XmlSuppressableInspectionTool;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.lang.Fxml2BindingSegmentReference;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.resolve.Fxml2BindingPathResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reports {@code fx:id} attributes whose value is never used in the FXML2 file
 * or in the code-behind class.
 *
 * <p>In FXML2, the compiler generates a protected field for each {@code fx:id} in the
 * generated base class. An {@code fx:id} is considered <em>used</em> when:
 * <ul>
 *   <li>The generated field is accessed from the code-behind class
 *       (e.g. {@code fx:id="myButton"} and the code-behind contains {@code myButton.setText(...)}), or</li>
 *   <li>The value appears as a binding-path segment in a binding expression
 *       in the same FXML2 file (e.g. {@code text="${myButton.text}"} or
 *       {@code Command.onAction="$myButton.fire"}).</li>
 * </ul>
 *
 * <p>Suppression: {@code @SuppressWarnings("Fxml2UnusedFxId")} on the attribute value element.
 */
public final class Fxml2UnusedFxIdInspection extends XmlSuppressableInspectionTool {

    /** Short name used for suppression annotations and the inspection profile key. */
    public static final String SHORT_NAME = "Fxml2UnusedFxId";

    @Override
    public @NotNull String getShortName() {
        return SHORT_NAME;
    }

    @Override
    public @NotNull String getGroupDisplayName() {
        return "FXML 2.0";
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Unused fx:id declaration";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public ProblemDescriptor @NotNull [] checkFile(
            @NotNull PsiFile file, @NotNull InspectionManager manager, boolean isOnTheFly) {
        if (!Fxml2FileType.isFxml2(file)) {
            return ProblemDescriptor.EMPTY_ARRAY;
        }

        XmlFile xmlFile = (XmlFile) file;
        XmlDocument document = xmlFile.getDocument();
        if (document == null) {
            return ProblemDescriptor.EMPTY_ARRAY;
        }

        // Collect all fx:id values and check each one.
        List<ProblemDescriptor> problems = new ArrayList<>();
        Set<PsiElement> reportedElements = new HashSet<>();
        for (XmlTag tag : PsiTreeUtil.findChildrenOfType(document, XmlTag.class)) {
            collectUnusedFxIds(tag, xmlFile, manager, isOnTheFly, problems, reportedElements);
        }

        return problems.toArray(ProblemDescriptor.EMPTY_ARRAY);
    }

    /**
     * Recursively walks the XML tag tree, checking each tag for fx:id attributes.
     */
    private static void collectUnusedFxIds(
            @NotNull XmlTag tag,
            @NotNull XmlFile xmlFile,
            @NotNull InspectionManager manager,
            boolean isOnTheFly,
            @NotNull List<ProblemDescriptor> problems,
            @NotNull Set<PsiElement> reportedElements) {

        XmlAttribute idAttr = tag.getAttribute("fx:id");
        if (idAttr != null) {
            String idValue = idAttr.getValue();
            if (idValue == null || idValue.isBlank()) return;

            XmlAttributeValue valueElement = idAttr.getValueElement();
            if (valueElement == null) return;

            // Skip if already reported for this element (prevents duplicates from
            // multiple inspection runs during on-the-fly checking).
            if (!reportedElements.add(valueElement)) return;

            if (isFxIdUnused(idValue, xmlFile)) {
                problems.add(manager.createProblemDescriptor(
                        valueElement,
                        "Unused fx:id",
                        (com.intellij.codeInspection.LocalQuickFix) null,
                        ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                        isOnTheFly));
            }
        }

        for (XmlTag subTag : tag.getSubTags()) {
            collectUnusedFxIds(subTag, xmlFile, manager, isOnTheFly, problems, reportedElements);
        }
    }

    /**
     * Returns {@code true} when the {@code fx:id} value is never used:
     * the generated field is not accessed from the code-behind class
     * and no binding-segment reference in the FXML2 file resolves to it.
     */
    private static boolean isFxIdUnused(
            @NotNull String idValue,
            @NotNull XmlFile xmlFile) {

        // Check 1: the generated field is accessed from the code-behind class.
        if (isFxIdUsedInCodeBehind(idValue, xmlFile)) {
            return false;
        }

        // Check 2: any Fxml2BindingSegmentReference in the file resolves to this fx:id.
        return !hasBindingSegmentReference(idValue, xmlFile);
    }

    /**
     * Returns {@code true} when the code-behind class accesses a field
     * whose name matches the {@code fx:id} value.
     * <p>
     * In FXML2, the compiler generates a protected field for each {@code fx:id}
     * in the generated base class. This checks whether the code-behind class
     * actually references that field (e.g. {@code myButton.setText(...)}).
     */
    private static boolean isFxIdUsedInCodeBehind(
            @NotNull String idValue, @NotNull XmlFile xmlFile) {
        PsiClass codeBehind = Fxml2BindingPathResolver.resolveCodeBehindClass(xmlFile);
        if (codeBehind == null) return false;

        // If the compiler-generated base class is absent (not yet compiled), field lookups
        // via findFieldByName will return null even for fields that would be declared there.
        // Suppress the warning rather than report a false positive.
        if (hasUnresolvableGeneratedBaseClass(codeBehind)) {
            return true;
        }

        // Check for actual field access in the code-behind class body.
        // This covers Java field references like "myButton.setText(...)".
        if (findFieldAccess(codeBehind, idValue) != null) {
            return true;
        }

        // Check for method calls that may represent Kotlin property access
        // (e.g. "myButton.text" in Kotlin compiles to "getMyButton()" calls).
        PsiMethod[] methods = codeBehind.findMethodsByName(idValue, /* checkSuperClasses */ false);
        for (PsiMethod method : methods) {
            // Only count if the method is declared in the code-behind class itself
            // (not inherited from Base), suggesting it's a Kotlin property accessor
            // or a custom method that uses the fx:id.
            PsiClass methodClass = method.getContainingClass();
            if (methodClass != null
                    && methodClass.getQualifiedName() != null
                    && methodClass.getQualifiedName().equals(codeBehind.getQualifiedName())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns {@code true} when the code-behind's extends list contains a type matching the
     * compiler-generated base class name ({@code <ClassName>Base}) that cannot be resolved.
     * This indicates that the fxml2 compiler has not yet produced the generated class, so
     * field-based usage analysis cannot be trusted.
     */
    private static boolean hasUnresolvableGeneratedBaseClass(@NotNull PsiClass codeBehind) {
        String className = codeBehind.getName();
        if (className == null) return false;
        String expectedBaseName = className + "Base";
        for (PsiClassType superType : codeBehind.getExtendsListTypes()) {
            if (expectedBaseName.equals(superType.getClassName()) && superType.resolve() == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds a field access reference with the given name anywhere in the code-behind class body,
     * using {@link ReferencesSearch} for a more robust and IDE-integrated approach.
     * Returns the first matching field reference element, or {@code null} if none found.
     */
    private static PsiElement findFieldAccess(
            @NotNull PsiClass codeBehind, @NotNull String fieldName) {

        PsiField field = codeBehind.findFieldByName(fieldName, true);
        if (field == null) return null;

        // For Kotlin code-behind classes the resolved PsiClass is a light wrapper that
        // does not appear in the source file's PSI tree. Use the navigation element
        // (the underlying KtClass for Kotlin, or the PsiClass itself for Java) so the
        // ancestor check operates on source-backed PSI.
        PsiElement codeBehindNav = codeBehind.getNavigationElement();
        if (codeBehindNav == null) codeBehindNav = codeBehind;
        PsiFile codeBehindFile = codeBehindNav.getContainingFile();
        if (codeBehindFile == null) return null;

        // Local-scope the search to the code-behind file. The inspection runs under
        // {@code AstLoadingFilter.disallowTreeLoading}, so a global word-index search
        // would attempt to load AST for unrelated files and log errors.
        LocalSearchScope scope = new LocalSearchScope(codeBehindFile);
        for (PsiReference reference : ReferencesSearch.search(field, scope).findAll()) {
            PsiElement element = reference.getElement();
            if (PsiTreeUtil.isAncestor(codeBehindNav, element, false)) {
                return element;
            }
        }

        return null;
    }

   /**
      * Returns {@code true} when any {@link Fxml2BindingSegmentReference} in the FXML2
     * file resolves to the {@code fx:id} declaration identified by {@code idValue}.
     */
    private static boolean hasBindingSegmentReference(
            @NotNull String idValue,
            @NotNull XmlFile xmlFile) {

        boolean[] found = {false};

        xmlFile.accept(new com.intellij.psi.XmlRecursiveElementVisitor() {
            @Override
            public void visitXmlAttributeValue(@NotNull XmlAttributeValue attrValue) {
                if (found[0]) return;
                for (PsiReference ref : attrValue.getReferences()) {
                    if (!(ref instanceof Fxml2BindingSegmentReference segRef)) continue;

                    PsiElement declaration = segRef.resolve();
                    if (declaration == null) continue;

                    // The declaration is a synthetic LightFieldBuilder whose navigation
                    // element is the XmlAttributeValue of the fx:id declaration.
                    PsiElement navEl;
                    try {
                        navEl = declaration.getNavigationElement();
                    }
                    catch (com.intellij.psi.PsiInvalidElementAccessException e) {
                        continue;
                    }
                    if (navEl == null) continue;

                    // Compare by attribute value text, not element identity.
                    // navEl points to the fx:id declaration's XmlAttributeValue,
                    // and attrValue is the current attribute being visited.
                    // We need to check if navEl's value equals idValue.
                    if (navEl instanceof XmlAttributeValue navAttrVal) {
                        String navValue = navAttrVal.getValue();
                        if (idValue.equals(navValue)) {
                            found[0] = true;
                            return;
                        }
                    }
                }
            }
        });

        return found[0];
    }
}
