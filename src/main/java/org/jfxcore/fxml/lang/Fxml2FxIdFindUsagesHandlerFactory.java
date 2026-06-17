package org.jfxcore.fxml.lang;

import com.intellij.find.findUsages.FindUsagesHandler;
import com.intellij.find.findUsages.FindUsagesHandlerFactory;
import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.XmlRecursiveElementVisitor;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2BindingPathResolver;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides a custom {@link FindUsagesHandler} when "Find Usages" is invoked on an
 * {@code fx:id} attribute value in an FXML file.
 *
 * <p>The handler's {@link FindUsagesHandler#getPrimaryElements()} returns both:
 * <ol>
 *   <li>The {@link XmlAttributeValue} itself (the declaration): finds all in-FXML
 *       binding-expression references via {@link Fxml2BindingSegmentReference#isReferenceTo}
 *       and the {@link Fxml2FxIdReference} self-reference.</li>
 *   <li>The code-behind field with the same name (if present): finds all Java/Kotlin
 *       usages of that field, and emits the field declaration as the final entry.</li>
 * </ol>
 * <p>The factory also accepts a real Java {@link PsiField} from a compiler-generated base
 * class, which is the case when the declaration symbol wraps that field for hover
 * documentation.  In that case the corresponding {@link XmlAttributeValue} is found via a
 * project-index lookup.
 */
public final class Fxml2FxIdFindUsagesHandlerFactory extends FindUsagesHandlerFactory {

    @Override
    public boolean canFindUsages(@NotNull PsiElement element) {
        return ReadAction.nonBlocking(() -> isFxIdValue(element) || isFxIdField(element)).executeSynchronously();
    }

    @Override
    public @Nullable FindUsagesHandler createFindUsagesHandler(
            @NotNull PsiElement element, boolean forHighlightUsages) {
        XmlAttributeValue attrVal = ReadAction.nonBlocking(() -> {
            XmlAttributeValue fromDirect = toFxIdAttrVal(element);
            if (fromDirect != null) return fromDirect;
            return fxIdAttrValForField(element);
        }).executeSynchronously();
        if (attrVal == null) return null;
        return new Fxml2FxIdFindUsagesHandler(attrVal);
    }

    // -----------------------------------------------------------------------

    /** Returns {@code true} when {@code element} is the value of an {@code fx:id} attribute,
     *  or a direct child token of such a value. */
    private static boolean isFxIdValue(@NotNull PsiElement element) {
        XmlAttributeValue attrVal = unwrapToAttrVal(element);
        if (attrVal == null) return false;
        if (!(attrVal.getContainingFile() instanceof XmlFile xmlFile)) return false;
        if (!Fxml2FileType.isFxml2(xmlFile)) return false;
        if (!(attrVal.getParent() instanceof XmlAttribute attr)) return false;
        return "id".equals(attr.getLocalName())
                && Fxml2ImportResolver.FXML2_NAMESPACE.equals(attr.getNamespace());
    }

    /** Unwraps a leaf token to its parent {@link XmlAttributeValue}, or returns the element
     *  itself if it already is one. Returns {@code null} for anything else. */
    private static @Nullable XmlAttributeValue unwrapToAttrVal(@NotNull PsiElement element) {
        if (element instanceof XmlAttributeValue av) return av;
        if (element.getParent() instanceof XmlAttributeValue av) return av;
        return null;
    }

    private static @Nullable XmlAttributeValue toFxIdAttrVal(@NotNull PsiElement element) {
        return unwrapToAttrVal(element);
    }

    /**
     * Returns {@code true} when {@code element} is a {@link PsiField} that corresponds to
     * an {@code fx:id} declaration in an FXML file.
     *
     * <p>Two cases are handled:
     * <ol>
     *   <li>Synthetic fields whose {@code getNavigationElement()} returns an
     *       {@link XmlAttributeValue}: checked directly.</li>
     *   <li>Real Java fields from a compiler-generated base class whose navigation element
     *       is the field itself: checked by searching FXML files via the project index.</li>
     * </ol>
     */
    private static boolean isFxIdField(@NotNull PsiElement element) {
        if (!(element instanceof PsiField field)) return false;
        PsiElement nav = field.getNavigationElement();
        if (nav instanceof XmlAttributeValue av) return isFxIdValue(av);
        if (nav instanceof XmlAttribute attr) {
            XmlAttributeValue val = attr.getValueElement();
            return val != null && isFxIdValue(val);
        }
        return findFxIdAttrValForRealField(field) != null;
    }

    /**
     * When {@code element} is a {@link PsiField} corresponding to an {@code fx:id}, returns
     * the matching {@link XmlAttributeValue}, otherwise {@code null}.
     */
    private static @Nullable XmlAttributeValue fxIdAttrValForField(@NotNull PsiElement element) {
        if (!(element instanceof PsiField field)) return null;
        PsiElement nav = field.getNavigationElement();
        if (nav instanceof XmlAttributeValue av && isFxIdValue(av)) return av;
        if (nav instanceof XmlAttribute attr) {
            XmlAttributeValue val = attr.getValueElement();
            if (val != null && isFxIdValue(val)) return val;
        }
        return findFxIdAttrValForRealField(field);
    }

    /**
     * Searches FXML files via the project index for an {@code fx:id} whose value matches
     * {@code field.getName()} and whose {@code fx:subclass} is or extends the field's containing
     * class.  Returns the first matching {@link XmlAttributeValue}, or {@code null}.
     */
    private static @Nullable XmlAttributeValue findFxIdAttrValForRealField(@NotNull PsiField field) {
        PsiClass containingClass = field.getContainingClass();
        if (containingClass == null) return null;
        String qualifiedName = containingClass.getQualifiedName();
        if (qualifiedName == null) return null;
        String fieldName = field.getName();

        GlobalSearchScope scope = GlobalSearchScope.allScope(field.getProject());
        XmlAttributeValue[] result = {null};

        PsiSearchHelper.getInstance(field.getProject()).processAllFilesWithWord(
                fieldName, scope, file -> {
                    if (!(file instanceof XmlFile xmlFile)) return true;
                    if (!Fxml2FileType.isFxml2(xmlFile)) return true;

                    XmlTag root = xmlFile.getRootTag();
                    String declaredClass = root != null ? root.getAttributeValue("fx:subclass") : null;
                    if (declaredClass == null) return true;

                    boolean matches = declaredClass.equals(qualifiedName);
                    if (!matches) {
                        PsiClass sub = JavaPsiFacade.getInstance(field.getProject())
                                .findClass(declaredClass, scope);
                        matches = InheritanceUtil.isInheritor(sub, qualifiedName);
                    }
                    if (!matches) return true;

                    xmlFile.accept(new XmlRecursiveElementVisitor() {
                        @Override
                        public void visitXmlAttribute(@NotNull XmlAttribute attr) {
                            super.visitXmlAttribute(attr);
                            if (result[0] != null) return;
                            if (!"id".equals(attr.getLocalName())) return;
                            if (!Fxml2ImportResolver.FXML2_NAMESPACE.equals(attr.getNamespace())) return;
                            if (!fieldName.equals(attr.getValue())) return;
                            XmlAttributeValue val = attr.getValueElement();
                            if (val != null) result[0] = val;
                        }
                    });
                    return result[0] == null;
                }, false);

        // Also check embedded FXML markup in @ComponentView-annotated classes.
        if (result[0] == null) {
            result[0] = Fxml2EmbeddedUtil.findFxIdInEmbedded(field);
        }

        return result[0];
    }

    // -----------------------------------------------------------------------
    // Handler
    // -----------------------------------------------------------------------

    private static final class Fxml2FxIdFindUsagesHandler extends FindUsagesHandler {

        Fxml2FxIdFindUsagesHandler(@NotNull XmlAttributeValue attrVal) {
            super(attrVal);
        }

        /**
         * Returns the fx:id {@link XmlAttributeValue} plus the code-behind field (if any)
         * as primary elements.  IntelliJ searches for usages of every primary element and
         * merges the results into a single "Find Usages" display.
         */
        @Override
        public PsiElement @NotNull [] getPrimaryElements() {
            return ReadAction.nonBlocking(this::computePrimaryElements).executeSynchronously();
        }

        private PsiElement @NotNull [] computePrimaryElements() {
            XmlAttributeValue attrVal = (XmlAttributeValue) getPsiElement();
            if (!(attrVal.getContainingFile() instanceof XmlFile)) {
                return new PsiElement[]{attrVal};
            }

            String idName = attrVal.getValue();
            if (idName.isBlank()) return new PsiElement[]{attrVal};

            List<PsiElement> primaries = new ArrayList<>();
            primaries.add(attrVal);

            PsiClass codeBehind = Fxml2BindingPathResolver.resolveCodeBehindClass(
                    (XmlFile) attrVal.getContainingFile());
            if (codeBehind != null) {
                PsiField field = codeBehind.findFieldByName(idName, true);
                if (field != null) {
                    primaries.add(field);
                } else {
                    for (PsiMethod m : codeBehind.findMethodsByName(idName, true)) {
                        if (m.getParameterList().isEmpty()) {
                            primaries.add(m);
                            break;
                        }
                    }
                }
            }

            return primaries.toArray(PsiElement.EMPTY_ARRAY);
        }

        /**
         * For each primary element:
         * <ul>
         *   <li>If it is the fx:id {@link XmlAttributeValue}: calls {@link Fxml2FxIdUsageCollector}
         *       which handles binding-expression uses (FXML walk), code-behind field/method usages,
         *       and the field declaration in a single pass.</li>
         *   <li>If it is a {@link PsiField}: no-op — the collector already handled all field-related
         *       usages during the {@link XmlAttributeValue} primary pass.</li>
         *   <li>Otherwise (e.g. a no-arg method primary): runs the standard reference search.</li>
         * </ul>
         */
        @Override
        public boolean processElementUsages(
                @NotNull PsiElement element,
                @NotNull Processor<? super UsageInfo> processor,
                @NotNull com.intellij.find.findUsages.FindUsagesOptions options) {

            XmlAttributeValue declaration = ReadAction.nonBlocking(
                    () -> (XmlAttributeValue) getPsiElement()).executeSynchronously();

            boolean isAttrValPrimary = ReadAction.nonBlocking(
                    () -> declaration.getManager().areElementsEquivalent(element, declaration)).executeSynchronously();

            if (isAttrValPrimary) {
                // The collector handles: binding-segment references, code-behind field usages,
                // and the field declaration — all in one pass via the shared collector.
                boolean[] ok = {true};
                ok[0] = Fxml2FxIdUsageCollector.collect(
                        declaration,
                        options.searchScope,
                        ref -> processor.process(new UsageInfo(ref)),
                        el -> {
                            UsageInfo info = ReadAction.nonBlocking(() -> new UsageInfo(el))
                                    .executeSynchronously();
                            return processor.process(info);
                        }
                );
                return ok[0];
            }

            if (element instanceof PsiField) {
                // All field usages were already collected in the attrVal primary pass above.
                return true;
            }

            // Fallback for other primaries (e.g. no-arg method when no field exists).
            return super.processElementUsages(element, usageInfo -> {
                PsiReference ref = ReadAction.nonBlocking(usageInfo::getReference).executeSynchronously();
                if (ref != null) {
                    PsiElement refEl = ReadAction.nonBlocking(ref::getElement).executeSynchronously();
                    boolean isDecl = ReadAction.nonBlocking(
                            () -> declaration.getManager().areElementsEquivalent(refEl, declaration)).executeSynchronously();
                    if (isDecl) return true;
                }
                return processor.process(usageInfo);
            }, options);
        }
    }
}
