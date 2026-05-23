package org.jfxcore.fxml.annotator;

import com.intellij.codeInsight.daemon.QuickFixBundle;
import com.intellij.codeInspection.BatchQuickFix;
import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.XmlSuppressableInspectionTool;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlProcessingInstruction;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.descriptors.Fxml2ClassTagDescriptor;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.resolve.Fxml2AttributeValueResolver;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;
import org.jfxcore.fxml.resolve.Fxml2XmlUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports {@code <?import?>} processing instructions whose imported class or package
 * is not referenced anywhere in the FXML 2.0 document.
 *
 * <p>An import is considered used when:
 * <ul>
 *   <li>An exact import {@code foo.Bar} is used if {@code Bar} appears as a tag local name
 *       or as the class part of a {@code Class.property} attribute.</li>
 *   <li>A wildcard import {@code foo.*} is used if any referenced simple name resolves to
 *       a class in that package.</li>
 * </ul>
 */
public final class Fxml2UnusedImportsInspection extends XmlSuppressableInspectionTool {

    @Override
    public ProblemDescriptor @Nullable [] checkFile(
            @NotNull PsiFile file, @NotNull InspectionManager manager, boolean isOnTheFly) {
        if (!Fxml2FileType.isFxml2(file)) {
            return null;
        }

        XmlFile xmlFile = (XmlFile) file;

        if (Fxml2EmbeddedUtil.isEmbeddedFxml2(file)) {
            // For embedded FXML 2.0, the prolog holds synthesised Java-import PIs that are not
            // user-editable, so skip them.  However, user-written <?import?> PIs placed inside
            // the <fxml2:embedded> wrapper root ARE user-editable and should be checked.
            return checkEmbeddedFxmlImports(xmlFile, manager, isOnTheFly);
        }

        XmlDocument document = xmlFile.getDocument();
        if (document == null) {
            return null;
        }
        Set<String> usedSimpleNames = collectUsedSimpleNames(xmlFile);

        List<ProblemDescriptor> problems = new ArrayList<>();
        Map<String, XmlProcessingInstruction> imports = new LinkedHashMap<>();
        for (XmlProcessingInstruction pi : PsiTreeUtil.findChildrenOfType(document.getProlog(), XmlProcessingInstruction.class)) {
            String target = Fxml2ImportResolver.getImportTarget(pi);
            if (target != null) {
                imports.put(target.trim(), pi);
            }
        }

        for (Map.Entry<String, XmlProcessingInstruction> entry : imports.entrySet()) {
            String target = entry.getKey();
            XmlProcessingInstruction pi = entry.getValue();

            boolean used;
            if (target.endsWith(".*")) {
                String pkg = target.substring(0, target.length() - 2); // strip .*
                used = usedSimpleNames.stream().anyMatch(name -> {
                    PsiClass resolved = Fxml2ImportResolver.resolve(name, xmlFile);
                    if (resolved == null) {
                        return false;
                    }
                    String fqn = resolved.getQualifiedName();
                    if (fqn == null) {
                        return false;
                    }
                    // Class is in this package (not a sub-package)
                    return fqn.startsWith(pkg + ".") && !fqn.substring(pkg.length() + 1).contains(".");
                });
            } else {
                // Exact import: used if the simple name (last segment) appears
                String simpleName = target.contains(".")
                        ? target.substring(target.lastIndexOf('.') + 1)
                        : target;
                used = usedSimpleNames.contains(simpleName);
            }

            if (!used) {
                problems.add(manager.createProblemDescriptor(
                        pi,
                        "Unused import",
                        new RemoveImportFix(),
                        ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                        isOnTheFly));
            }
        }

        return problems.isEmpty() ? null : problems.toArray(ProblemDescriptor.EMPTY_ARRAY);
    }

    /**
     * Checks for unused {@code <?import?>} PIs written by the user inside the
     * {@code <fxml2:embedded>} wrapper root of an embedded FXML 2.0 file.
     *
     * <p>The synthesised Java-import PIs in the XML prolog are not checked here because they
     * are managed by the language injector and are not user-editable.
     */
    private ProblemDescriptor @Nullable [] checkEmbeddedFxmlImports(
            @NotNull XmlFile xmlFile,
            @NotNull InspectionManager manager,
            boolean isOnTheFly) {

        List<String> fxmlImports = Fxml2ImportResolver.parseImportsFromWrapperRoot(xmlFile);
        if (fxmlImports.isEmpty()) {
            return null; // no user-written fxml imports, nothing to check
        }

        Set<String> usedSimpleNames = collectUsedSimpleNames(xmlFile);
        List<ProblemDescriptor> problems = new ArrayList<>();

        // Collect the actual XmlProcessingInstruction nodes inside the wrapper root.
        XmlDocument document = xmlFile.getDocument();
        if (document == null) return null;
        XmlTag root = document.getRootTag();
        if (root == null || !Fxml2EmbeddedUtil.isWrapperRoot(root)) return null;

        for (PsiElement child : root.getChildren()) {
            if (child instanceof XmlTag) break;
            if (!(child instanceof XmlProcessingInstruction pi)) continue;
            String target = Fxml2ImportResolver.getImportTarget(pi);
            if (target == null) continue;
            target = target.trim();

            boolean used;
            if (target.endsWith(".*")) {
                String pkg = target.substring(0, target.length() - 2); // strip .*
                used = usedSimpleNames.stream().anyMatch(name -> {
                    PsiClass resolved = Fxml2ImportResolver.resolve(name, xmlFile);
                    if (resolved == null) return false;
                    String fqn = resolved.getQualifiedName();
                    return fqn != null
                            && fqn.startsWith(pkg + ".")
                            && !fqn.substring(pkg.length() + 1).contains(".");
                });
            } else {
                String simpleName = target.contains(".")
                        ? target.substring(target.lastIndexOf('.') + 1)
                        : target;
                used = usedSimpleNames.contains(simpleName);
            }

            if (!used) {
                problems.add(manager.createProblemDescriptor(
                        pi,
                        "Unused import",
                        new RemoveImportFix(),
                        ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                        isOnTheFly));
            }
        }

        return problems.isEmpty() ? null : problems.toArray(ProblemDescriptor.EMPTY_ARRAY);
    }

    /**
     * Collects every simple class name (and static-property class prefix) referenced
     * in the body of the FXML 2.0 document.
     */
    public static Set<String> collectUsedSimpleNames(XmlFile file) {
        Set<String> names = new HashSet<>();
        for (XmlTag tag : PsiTreeUtil.findChildrenOfType(file, XmlTag.class)) {
            collectFromTag(tag, names, file);
        }
        // Also collect class names referenced in <?prefix X = ClassName?> declarations.
        // Such declarations bind a prefix character to a markup-extension class; the class
        // name counts as a use of the corresponding import.
        for (XmlProcessingInstruction pi : PsiTreeUtil.findChildrenOfType(file, XmlProcessingInstruction.class)) {
            String typeName = Fxml2ImportResolver.getPrefixDeclarationTypeName(pi);
            if (typeName != null) {
                String simpleName = typeName.contains(".")
                        ? typeName.substring(typeName.lastIndexOf('.') + 1)
                        : typeName;
                if (!simpleName.isEmpty()) {
                    names.add(simpleName);
                }
            }
        }
        return names;
    }

    public static void collectFromTag(XmlTag tag, Set<String> names, XmlFile xmlFile) {
        String localName = tag.getLocalName();

        // Tag name: add if it resolves to a class (imported or resolvable by short name)
        if (!localName.isEmpty() && !localName.contains(".")) {
            if (isResolvableClass(localName, xmlFile)) {
                names.add(simpleNameOf(localName));
            }
        }

        // Static property tag: "ClassName.propName" (e.g. <GridPane.rowIndex>)
        int dot = localName.lastIndexOf('.');
        if (dot > 0) {
            String prefix = localName.substring(0, dot);
            if (isResolvableClass(prefix, xmlFile)) {
                names.add(simpleNameOf(prefix));
            }
        }

        // Attributes: static property references like Command.onAction, and values
        for (XmlAttribute attr : tag.getAttributes()) {
            String attrName = attr.getName();
            if (Fxml2XmlUtil.isNonPropertyAttribute(attrName)) {
                // fx:typeArguments references class names that must be treated as "used".
                // All other fx:/xmlns attributes are structural and do not reference imported classes.
                if ("typeArguments".equals(attr.getLocalName())
                        && Fxml2ImportResolver.FXML2_NAMESPACE.equals(attr.getNamespace())) {
                    String rawValue = attr.getValue();
                    if (rawValue != null && !rawValue.isBlank()) {
                        for (String token : rawValue.split(",", -1)) {
                            String fqn = token.strip();
                            if (!fqn.isEmpty()) {
                                String simpleName = fqn.contains(".")
                                        ? fqn.substring(fqn.lastIndexOf('.') + 1)
                                        : fqn;
                                if (isResolvableClass(simpleName, xmlFile) || isResolvableClass(fqn, xmlFile)) {
                                    names.add(simpleName);
                                }
                            }
                        }
                    }
                }
                continue;
            }
            // Dotted attribute name prefix (e.g. StackPane in StackPane.alignment)
            int attrDot = attrName.lastIndexOf('.');
            if (attrDot > 0) {
                String prefix = attrName.substring(0, attrDot);
                if (isResolvableClass(prefix, xmlFile)) {
                    names.add(simpleNameOf(prefix));
                }
            }
            // Attribute value: scan binding expressions for static class references
            String rawValue = attr.getValue();
            if (rawValue != null) {
                collectClassNamesFromBindingValue(rawValue, names, xmlFile);
            }

            // Plain (non-binding) attribute value for a Class<T> property: the value is
            // a class name resolved via the file's imports, just as the compiler does.
            if (rawValue != null && !rawValue.isBlank() && attrDot < 0
                    && !Fxml2BindingExpressionParser.looksLikeBindingExpression(rawValue)) {
                collectClassNamesFromClassTypedAttr(attrName, rawValue, tag, names, xmlFile);
            }
        }
    }

    private static void collectClassNamesFromClassTypedAttr(
            @NotNull String attrName,
            @NotNull String rawValue,
            @NotNull XmlTag tag,
            @NotNull Set<String> names,
            @NotNull XmlFile xmlFile) {
        if (!(tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd)) return;
        PsiClass ownerClass = cd.getPsiClass();
        if (ownerClass == null) return;
        PsiType propType = Fxml2AttributeValueResolver.propertyType(ownerClass, attrName, List.of());
        if (propType == null) return;
        PsiClass propClass = PsiUtil.resolveClassInType(propType);
        if (propClass == null || !"java.lang.Class".equals(propClass.getQualifiedName())) return;
        PsiClass resolved = Fxml2AttributeValueResolver.resolveClassLiteralRef(
                rawValue.trim(), propType, xmlFile, xmlFile.getResolveScope());
        if (resolved == null) return;
        String simpleName = resolved.getName();
        if (simpleName != null) {
            names.add(simpleName);
        }
    }

    /**
     * Returns true if {@code name} resolves (or could resolve) to a Java class, either
     * already imported in the file, or findable via the short-name index in project scope.
     */
    private static boolean isResolvableClass(@NotNull String name, @NotNull XmlFile xmlFile) {
        if (Fxml2ImportResolver.resolve(name, xmlFile) != null) return true;
        var cache = com.intellij.psi.search.PsiShortNamesCache.getInstance(xmlFile.getProject());
        return cache.getClassesByName(name, xmlFile.getResolveScope()).length > 0;
    }

    /**
     * Scans a raw attribute value for class names used in static field references inside
     * binding expressions (e.g. {@code $KeyEvent.KEY_PRESSED} -> adds {@code "KeyEvent"}),
     * for markup extension class names (e.g. {@code {MyMarkupExtension<String> ...}} ->
     * adds {@code "MyMarkupExtension"}), and for class names used as qualifiers in
     * secondary parameter paths (e.g. {@code converter=RowConverter.INSTANCE} ->
     * adds {@code "RowConverter"}).
     */
    private static void collectClassNamesFromBindingValue(String rawValue, Set<String> names,
                                                           XmlFile xmlFile) {
        Object parsed = Fxml2BindingExpressionParser.parse(rawValue);
        if (parsed instanceof Fxml2BindingExpressionParser.MarkupExtensionExpression(
                String extensionName, int ignored, boolean ignored2)) {
            if (!extensionName.isEmpty() && isResolvableClass(extensionName, xmlFile)) {
                names.add(extensionName);
            }
            return;
        }

        Fxml2BindingExpressionParser.ParsedExpression expr =
                Fxml2BindingExpressionParser.parseExpression(rawValue);
        if (expr == null) return;
        String path = expr.strippedPath();
        if (path.isEmpty()) return;

        // Walk all dot-separated prefixes; any segment followed by more segments that
        // resolves as a class is a class reference. Applied to both the primary path and
        // the secondary parameter path (converter= or format=).
        collectClassNamesFromPath(path, names, xmlFile);
        String paramPath = expr.paramPath();
        if (expr.hasParam() && paramPath != null) {
            collectClassNamesFromPath(paramPath, names, xmlFile);
        }
    }

    private static void collectClassNamesFromPath(String path, Set<String> names, XmlFile xmlFile) {
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i].trim();
            if (!part.isEmpty() && isResolvableClass(part, xmlFile)) {
                names.add(simpleNameOf(part));
            }
        }
    }

    /** Returns the outermost simple name (before any nested '$' or '.'). */
    private static String simpleNameOf(String name) {
        int dollar = name.indexOf('$');
        if (dollar > 0) {
            return name.substring(0, dollar);
        }
        return name;
    }

    private static final class RemoveImportFix implements LocalQuickFix, BatchQuickFix {

        @Override
        public @NotNull String getFamilyName() {
            return QuickFixBundle.message("optimize.imports.fix");
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement pi = descriptor.getPsiElement();
            if (pi != null) {
                pi.delete();
            }
        }

        @Override
        public void applyFix(
                @NotNull Project project,
                CommonProblemDescriptor @NotNull [] descriptors,
                @NotNull List<PsiElement> psiElementsToIgnore,
                @Nullable Runnable refreshViews) {
            for (CommonProblemDescriptor descriptor : descriptors) {
                if (descriptor instanceof ProblemDescriptor pd) {
                    applyFix(project, pd);
                    PsiElement elem = pd.getPsiElement();
                    if (elem != null) psiElementsToIgnore.add(elem);
                }
            }
            if (refreshViews != null) refreshViews.run();
        }
    }
}
