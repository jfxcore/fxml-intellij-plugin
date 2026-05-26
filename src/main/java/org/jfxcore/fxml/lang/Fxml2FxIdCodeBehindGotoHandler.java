package org.jfxcore.fxml.lang;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles "Go to Declaration" (Ctrl+click) when the cursor is on a Java/Kotlin field or
 * method that corresponds to an {@code fx:id}-injected member: whether accessed as a
 * <em>use site</em> (e.g. {@code myButton1.textProperty()} in the code-behind body) or as
 * the <em>generated field declaration</em> (e.g. {@code protected Button myButton1;} in the
 * compiler-generated base class).
 *
 * <p>In both cases the canonical declaration is the {@code fx:id} attribute value in the FXML
 * file.  This handler finds all FXML files in the project whose {@code fx:subclass} attribute
 * refers to a class that is the same as, or a subclass of, the field's containing class, and
 * returns the matching {@link XmlAttributeValue} elements as navigation targets.
 *
 * <p>When exactly one FXML target is found, IntelliJ navigates there directly; when multiple
 * targets are found (e.g. the same field used in multiple FXML views) the "Choose Target"
 * popup is shown.
 */
public final class Fxml2FxIdCodeBehindGotoHandler implements GotoDeclarationHandler {

    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(
            @Nullable PsiElement sourceElement, int offset, Editor editor) {

        if (sourceElement == null) return null;

        // Do not activate when the cursor is on an fx:id attribute value in an FXML file.
        // That element is the canonical declaration; the PsiSymbolDeclarationProvider handles
        // it and routes Ctrl+click to "Show Usages".  If we fired here we would intercept
        // fromGTDProviders and force the GTD/"Choose Declaration" path instead.
        if (isOnFxIdDeclaration(sourceElement)) {
            return null;
        }

        PsiElement resolved = resolveToMember(sourceElement);
        if (resolved == null) return null;

        String memberName;
        PsiClass containingClass;
        if (resolved instanceof PsiField f) {
            memberName      = f.getName();
            containingClass = f.getContainingClass();
        } else if (resolved instanceof PsiMethod m) {
            memberName      = m.getName();
            containingClass = m.getContainingClass();
        } else {
            return null;
        }
        if (containingClass == null) return null;

        String qualifiedName = containingClass.getQualifiedName();
        if (qualifiedName == null) return null;

        Project project = sourceElement.getProject();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

        List<PsiElement> targets = new ArrayList<>();

        // Search FXML files by the member name (the fx:id value string), which is always
        // present in any FXML file that declares it: regardless of whether the containing
        // class's simple name appears in the file.
        PsiSearchHelper.getInstance(project).processAllFilesWithWord(
                memberName, scope,
                file -> {
                    if (!(file instanceof XmlFile xmlFile)) return true;
                    if (!Fxml2FileType.isFxml2(xmlFile)) return true;

                    XmlTag root = xmlFile.getRootTag();
                    if (root == null) return true;

                    String fxClass = root.getAttributeValue("fx:subclass");
                    if (fxClass == null) return true;

                    // Accept if fx:subclass is the containing class itself OR a subclass of it.
                    // This handles the case where the field is in a compiler-generated base
                    // class (e.g. SomeViewBase) but fx:subclass points to the user class (SomeView).
                    if (!fxClass.equals(qualifiedName)
                            && !isSubclassOf(fxClass, qualifiedName, project, scope)) {
                        return true;
                    }

                    findFxIdValues(root, memberName, targets);
                    return true;
                },
                false);

        // Also search embedded FXML markup in @ComponentView-annotated classes.
        Fxml2EmbeddedUtil.findFxIdInEmbedded(memberName, containingClass, scope,
                attrVal -> {
                    targets.add(attrVal);
                    return true; // continue, same field may appear in multiple views
                });

        return targets.isEmpty() ? null : targets.toArray(PsiElement.EMPTY_ARRAY);
    }
    // -----------------------------------------------------------------------


    /** Returns {@code true} when {@code element} is inside an {@code fx:id} attribute value in an FXML file. */
    private static boolean isOnFxIdDeclaration(@NotNull PsiElement element) {
        PsiElement candidate = element instanceof XmlAttributeValue ? element : element.getParent();
        if (!(candidate instanceof XmlAttributeValue attrVal)) return false;
        if (!(attrVal.getContainingFile() instanceof XmlFile xmlFile)) return false;
        if (!Fxml2FileType.isFxml2(xmlFile)) return false;
        if (!(attrVal.getParent() instanceof XmlAttribute attr)) return false;
        return "id".equals(attr.getLocalName())
                && Fxml2ImportResolver.FXML2_NAMESPACE.equals(attr.getNamespace());
    }

    /**
     * Returns {@code true} when the class named {@code subclassFqn} is a subclass of
     * (or the same as) the class named {@code superclassFqn}.
     */
    private static boolean isSubclassOf(
            @NotNull String subclassFqn,
            @NotNull String superclassFqn,
            @NotNull Project project,
            @NotNull GlobalSearchScope scope) {
        if (subclassFqn.equals(superclassFqn)) return true;
        PsiClass sub = JavaPsiFacade.getInstance(project).findClass(subclassFqn, scope);
        if (sub == null) return false;
        return InheritanceUtil.isInheritor(sub, superclassFqn);
    }

    /**
     * Attempts to resolve the PSI element at the cursor to a {@link PsiField} or
     * {@link PsiMethod}. Handles:
     * <ul>
     *   <li>Identifier token whose parent is a {@link PsiField}/{@link PsiMethod} (declaration)</li>
     *   <li>{@link com.intellij.psi.PsiReferenceExpression} resolving to a field/method (Java use)</li>
     *   <li>Any element whose references resolve to a field/method (Kotlin use, language-agnostic)</li>
     * </ul>
     */
    private static @Nullable PsiElement resolveToMember(@Nullable PsiElement element) {
        if (element == null) return null;
        PsiElement parent = element.getParent();
        if (parent instanceof PsiField || parent instanceof PsiMethod) return parent;
        if (parent instanceof com.intellij.psi.PsiReferenceExpression ref) {
            PsiElement target = ref.resolve();
            if (target instanceof PsiField || target instanceof PsiMethod) return target;
        }
        for (PsiElement candidate : new PsiElement[]{element, parent}) {
            if (candidate == null) continue;
            for (PsiReference ref : candidate.getReferences()) {
                PsiElement target = ref.resolve();
                if (target instanceof PsiField || target instanceof PsiMethod) return target;
            }
        }
        return null;
    }

    /**
     * Recursively walks {@code tag} and its subtags collecting {@link XmlAttributeValue}s
     * of {@code fx:id} attributes whose value equals {@code idName}.
     */
    private static void findFxIdValues(
            @Nullable XmlTag tag,
            @NotNull String idName,
            @NotNull List<PsiElement> targets) {
        if (tag == null) return;
        XmlAttribute idAttr = tag.getAttribute("fx:id");
        if (idAttr != null && idName.equals(idAttr.getValue())) {
            XmlAttributeValue valEl = idAttr.getValueElement();
            if (valEl != null) targets.add(valEl);
        }
        for (XmlTag child : tag.getSubTags()) {
            findFxIdValues(child, idName, targets);
        }
    }
}
