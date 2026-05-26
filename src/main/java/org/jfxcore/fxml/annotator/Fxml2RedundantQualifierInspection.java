package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.BatchQuickFix;
import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.XmlSuppressableInspectionTool;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.actions.EmbeddedFxmlCaretRestorer;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2BindingPathResolver;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;
import org.jfxcore.fxml.resolve.Fxml2XmlUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reports fully-qualified class names whose qualifying prefix is redundant because the
 * class (or its outermost enclosing class) is already accessible via an
 * {@code <?import?>} declaration.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code fx:typeArguments} attribute values</li>
 *   <li>FQN element tag names (e.g. {@code <javafx.scene.control.TextField/>})</li>
 *   <li>FQN class prefix in static-property element tags
 *       (e.g. {@code <javafx.scene.layout.VBox.margin>})</li>
 *   <li>FQN class prefix in static-property attribute names
 *       (e.g. {@code javafx.scene.layout.VBox.vgrow="ALWAYS"})</li>
 *   <li>Binding-expression paths whose class prefix is redundant
 *       (e.g. {@code $javafx.scene.layout.Region.USE_PREF_SIZE})</li>
 * </ul>
 *
 * <p>Enabled by default at WEAK_WARNING (grey) level.
 * "Fix all in file" and "Fix all in scope" are available via the family name.
 */
public final class Fxml2RedundantQualifierInspection extends XmlSuppressableInspectionTool {

    @Override
    public ProblemDescriptor @Nullable [] checkFile(
            @NotNull PsiFile file, @NotNull InspectionManager manager, boolean isOnTheFly) {
        if (!Fxml2FileType.isFxml2(file)) return null;
        XmlFile xmlFile = (XmlFile) file;

        List<ProblemDescriptor> problems = new ArrayList<>();

        checkAttributeValues(xmlFile, manager, isOnTheFly, problems);
        checkStaticPropertyAttributes(xmlFile, manager, isOnTheFly, problems);
        checkTags(xmlFile, manager, isOnTheFly, problems);

        return problems.isEmpty() ? null : problems.toArray(ProblemDescriptor.EMPTY_ARRAY);
    }

    // -----------------------------------------------------------------------
    // Attribute value checks: fx:typeArguments, binding expressions, markup extensions
    // -----------------------------------------------------------------------

    private static void checkAttributeValues(
            @NotNull XmlFile xmlFile,
            @NotNull InspectionManager manager,
            boolean isOnTheFly,
            @NotNull List<ProblemDescriptor> problems) {

        for (XmlAttributeValue attrVal : PsiTreeUtil.findChildrenOfType(xmlFile, XmlAttributeValue.class)) {
            if (!(attrVal.getParent() instanceof XmlAttribute attr)) continue;
            String attrName = attr.getName();
            if (attrName.startsWith("xmlns") || "fx:className".equals(attrName) || attrName.startsWith("fx:subclass")) {
                continue;
            }

            String rawValue = attrVal.getValue();
            if (rawValue.isBlank()) continue;

            boolean isFxTypeArguments = "typeArguments".equals(attr.getLocalName())
                    && Fxml2ImportResolver.FXML2_NAMESPACE.equals(attr.getNamespace());

            if (isFxTypeArguments) {
                checkFqnTokens(rawValue, attrVal, xmlFile, manager, isOnTheFly, problems);
            } else if (Fxml2BindingExpressionParser.looksLikeBindingExpression(rawValue)) {
                checkBindingExpressionFqn(rawValue, attrVal, xmlFile, manager, isOnTheFly, problems);
                checkMarkupExtensionValues(rawValue, attrVal, xmlFile, manager, isOnTheFly, problems);
            }
        }
    }

    /**
     * Checks comma-separated FQN tokens in {@code fx:typeArguments} attribute values.
     */
    private static void checkFqnTokens(
            @NotNull String rawValue,
            @NotNull XmlAttributeValue attrVal,
            @NotNull XmlFile xmlFile,
            @NotNull InspectionManager manager,
            boolean isOnTheFly,
            @NotNull List<ProblemDescriptor> problems) {

        int cursor = 0;
        for (String token : rawValue.split(",", -1)) {
            int leadingSpaces = 0;
            while (leadingSpaces < token.length() && Character.isWhitespace(token.charAt(leadingSpaces))) {
                leadingSpaces++;
            }
            String fqn = token.strip();
            int tokenStart = cursor + leadingSpaces;

            if (!fqn.isBlank() && fqn.contains(".") && Character.isJavaIdentifierStart(fqn.charAt(0))) {
                RedundantPrefix rp = findRedundantClassPrefix(fqn, xmlFile);
                if (rp != null) {
                    // +1 to account for the opening quote in XmlAttributeValue text
                    TextRange range = TextRange.create(1 + tokenStart, 1 + tokenStart + rp.prefixLength());
                    problems.add(manager.createProblemDescriptor(
                            attrVal, range,
                            "Redundant qualifier '" + rp.prefix() + "'",
                            ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                            isOnTheFly,
                            new RemoveAttributeValuePrefixFix(rp.prefix(), tokenStart)));
                }
            }
            cursor += token.length() + 1; // +1 for the comma
        }
    }

    /**
     * Checks FQN class names in custom markup extension attribute values:
     * <ol>
     *   <li>The extension class name itself (e.g. {@code test.} in
     *       {@code {test.MyExt ...}} when {@code MyExt} is imported).</li>
     *   <li>Each comma-separated type argument (e.g. {@code java.lang.} in
     *       {@code {GenExt<java.lang.String>}} since {@code String} is implicitly
     *       available via {@code java.lang.*}).</li>
     * </ol>
     */
    private static void checkMarkupExtensionValues(
            @NotNull String rawValue,
            @NotNull XmlAttributeValue attrVal,
            @NotNull XmlFile xmlFile,
            @NotNull InspectionManager manager,
            boolean isOnTheFly,
            @NotNull List<ProblemDescriptor> problems) {

        // Only handle custom markup extensions: {ClassName ...}, not {fx:...}.
        Object parsed = Fxml2BindingExpressionParser.parse(rawValue);
        if (!(parsed instanceof Fxml2BindingExpressionParser.MarkupExtensionExpression(
                String extensionName, int nameOffset, boolean ignored))) {
            return;
        }

        // ---- 1. Extension class name ----
        if (extensionName.contains(".") && Character.isJavaIdentifierStart(extensionName.charAt(0))) {
            RedundantPrefix rp = findRedundantClassPrefix(extensionName, xmlFile);
            if (rp != null) {
                // +1 for the opening quote in XmlAttributeValue text
                TextRange range = TextRange.create(1 + nameOffset, 1 + nameOffset + rp.prefixLength());
                problems.add(manager.createProblemDescriptor(
                        attrVal, range,
                        "Redundant qualifier '" + rp.prefix() + "'",
                        ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                        isOnTheFly,
                        new RemoveAttributeValuePrefixFix(rp.prefix(), nameOffset)));
            }
        }

        // ---- 2. Type arguments ----
        int afterName = nameOffset + extensionName.length();
        if (afterName >= rawValue.length()) return;

        // Accept both literal '<' and XML-escaped '&lt;' forms.
        boolean literal;
        int contentStart;
        if (rawValue.charAt(afterName) == '<') {
            literal = true;
            contentStart = afterName + 1;
        } else if (rawValue.startsWith("&lt;", afterName)) {
            literal = false;
            contentStart = afterName + 4;
        } else {
            return; // no type arguments
        }

        int closeOffset = literal
                ? markupExtFindClosingAngleLiteral(rawValue, contentStart)
                : markupExtFindClosingAngleEntity(rawValue, contentStart);
        if (closeOffset < 0) return;

        String typeArgContent = rawValue.substring(contentStart, closeOffset);
        int cursor = 0;
        for (String token : typeArgContent.split(",", -1)) {
            int leadingSpaces = 0;
            while (leadingSpaces < token.length() && Character.isWhitespace(token.charAt(leadingSpaces))) {
                leadingSpaces++;
            }
            String typeArgFqn = token.stripTrailing().substring(leadingSpaces);
            // Strip nested angle brackets (e.g. "Map<K,V>" -> "Map")
            int innerAngle = typeArgFqn.indexOf('<');
            if (innerAngle < 0) innerAngle = typeArgFqn.indexOf("&lt;");
            if (innerAngle > 0) typeArgFqn = typeArgFqn.substring(0, innerAngle);

            if (!typeArgFqn.isBlank() && typeArgFqn.contains(".")
                    && Character.isJavaIdentifierStart(typeArgFqn.charAt(0))) {
                int tokenStart = contentStart + cursor + leadingSpaces; // offset in rawValue
                RedundantPrefix rp = findRedundantClassPrefix(typeArgFqn, xmlFile);
                if (rp != null) {
                    TextRange range = TextRange.create(1 + tokenStart, 1 + tokenStart + rp.prefixLength());
                    problems.add(manager.createProblemDescriptor(
                            attrVal, range,
                            "Redundant qualifier '" + rp.prefix() + "'",
                            ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                            isOnTheFly,
                            new RemoveAttributeValuePrefixFix(rp.prefix(), tokenStart)));
                }
            }
            cursor += token.length() + 1; // +1 for comma
        }
    }

    /** Finds the offset of the matching {@code >} for a literal angle-bracket sequence. */
    private static int markupExtFindClosingAngleLiteral(@NotNull String s, int from) {
        int depth = 1;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') { if (--depth == 0) return i; }
        }
        return -1;
    }

    /** Finds the offset of the matching {@code &gt;} entity in a string. */
    private static int markupExtFindClosingAngleEntity(@NotNull String s, int from) {
        for (int i = from; i <= s.length() - 4; i++) {
            if (s.startsWith("&gt;", i)) return i;
        }
        return -1;
    }

    /**
     * For a binding-expression attribute value, checks whether the path contains a
     * FQN class prefix that can be shortened.  Two cases:
     *
     * <ol>
     *   <li><b>Field in scope via start class</b>: e.g.
     *       {@code $javafx.scene.layout.Region.USE_PREF_SIZE} on a {@code <TextArea>}
     *       whose binding context (code-behind) extends {@code Region}.  The entire
     *       {@code javafx.scene.layout.Region.} prefix is redundant: {@code $USE_PREF_SIZE}
     *       works directly.</li>
     *   <li><b>Class accessible via import</b>: e.g.
     *       {@code $javafx.scene.layout.Region.USE_PREF_SIZE} when
     *       {@code <?import javafx.scene.layout.Region?>} is present.  Only the package
     *       prefix {@code javafx.scene.layout.} is redundant: {@code $Region.USE_PREF_SIZE}
     *       works.</li>
     * </ol>
     *
     * <p>Case 1 wins (longer reduction) when both apply.
     */
    private static void checkBindingExpressionFqn(
            @NotNull String rawValue,
            @NotNull XmlAttributeValue attrVal,
            @NotNull XmlFile xmlFile,
            @NotNull InspectionManager manager,
            boolean isOnTheFly,
            @NotNull List<ProblemDescriptor> problems) {

        Object parsed = Fxml2BindingExpressionParser.parse(rawValue);
        if (!(parsed instanceof Fxml2BindingExpressionParser.ParsedExpression expr)) return;

        String path = expr.path();
        int pathOffsetInValue = expr.pathOffset();

        // Strip any context selector (self/, parent/, etc.)
        Fxml2BindingExpressionParser.ContextSelector sel =
                Fxml2BindingExpressionParser.parseContextSelector(path);
        String effectivePath;
        int effectiveOffset;
        if (sel != null) {
            effectivePath = sel.remainingPath();
            effectiveOffset = pathOffsetInValue + sel.remainingOffset();
        } else {
            effectivePath = path;
            effectiveOffset = pathOffsetInValue;
        }

        if (!effectivePath.contains(".")) return;

        String[] segments = effectivePath.split("\\.", -1);
        JavaPsiFacade facade = JavaPsiFacade.getInstance(xmlFile.getProject());
        GlobalSearchScope scope = GlobalSearchScope.allScope(xmlFile.getProject());

        // Resolve the start class so we can check whether the field is already in scope.
        PsiClass startClass = resolveBindingStartClass(attrVal, sel, xmlFile);

        // Scan right-to-left: find the rightmost uppercase-first segment that is a class boundary.
        // segments[0..split-1] = candidate class FQN, segments[split] = field/member name.
        for (int split = segments.length - 1; split >= 1; split--) {
            if (segments[split - 1].isEmpty() || !Character.isUpperCase(segments[split - 1].charAt(0))) continue;
            String classFqn = String.join(".", Arrays.copyOf(segments, split));
            PsiClass cls = facade.findClass(classFqn, scope);
            if (cls == null) {
                int dot = classFqn.lastIndexOf('.');
                if (dot > 0) cls = facade.findClass(classFqn.substring(0, dot) + "$" + classFqn.substring(dot + 1), scope);
            }
            if (cls == null) continue;

            // The member name is everything after the class FQN in the path.
            // For "Region.USE_PREF_SIZE" that is just "USE_PREF_SIZE";
            // for a chain "Region.USE_PREF_SIZE.foo" the first field is "USE_PREF_SIZE".
            String memberName = segments[split];

            // --- Case 1: field already accessible via start class ---
            if (startClass != null) {
                PsiField inherited = startClass.findFieldByName(memberName, true);
                if (inherited != null
                        && inherited.hasModifierProperty(PsiModifier.PUBLIC)
                        && inherited.hasModifierProperty(PsiModifier.STATIC)) {
                    // The entire "classFqn." prefix (package + class name + dot) is redundant.
                    // prefixToRemove covers from the start of effectivePath up to and including
                    // the class name and its trailing dot.
                    String prefixToRemove = classFqn + "."; // e.g. "javafx.scene.layout.Region."
                    int rangeStart = 1 + effectiveOffset; // +1 for opening quote
                    int rangeEnd   = rangeStart + prefixToRemove.length();
                    problems.add(manager.createProblemDescriptor(
                            attrVal, TextRange.create(rangeStart, rangeEnd),
                            "Redundant qualifier '" + prefixToRemove + "'",
                            ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                            isOnTheFly,
                            new RemoveAttributeValuePrefixFix(prefixToRemove, effectiveOffset)));
                    break; // Case 1 is the longest reduction; don't also apply case 2
                }
            }

            // --- Case 2: class accessible via import (package prefix redundant) ---
            RedundantPrefix rp = findRedundantClassPrefix(classFqn, xmlFile);
            if (rp != null) {
                int rangeStart = 1 + effectiveOffset; // +1 for opening quote
                int rangeEnd   = rangeStart + rp.prefixLength();
                problems.add(manager.createProblemDescriptor(
                        attrVal, TextRange.create(rangeStart, rangeEnd),
                        "Redundant qualifier '" + rp.prefix() + "'",
                        ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                        isOnTheFly,
                        new RemoveAttributeValuePrefixFix(rp.prefix(), effectiveOffset)));
            }
            break; // Only process the outermost class in the path
        }
    }

    /**
     * Resolves the binding start class for the given attribute value, respecting context
     * selectors ({@code self/}, {@code parent/}, etc.).  Returns {@code null} if it cannot
     * be determined.
     */
    private static @Nullable PsiClass resolveBindingStartClass(
            @NotNull XmlAttributeValue attrVal,
            @Nullable Fxml2BindingExpressionParser.ContextSelector sel,
            @NotNull XmlFile xmlFile) {
        if (!(attrVal.getParent() instanceof XmlAttribute attr)) return null;
        if (!(attr.getParent() instanceof XmlTag contextTag)) return null;
        return Fxml2BindingPathResolver.resolveStartClass(sel, contextTag, xmlFile);
    }

    // -----------------------------------------------------------------------
    // Static-property attribute name checks (e.g. javafx.scene.layout.VBox.vgrow)
    // -----------------------------------------------------------------------

    /**
     * Reports static-property attribute names (e.g. {@code VBox.vgrow}) where the class
     * part itself is a FQN that can be shortened.  Example:
     * {@code javafx.scene.layout.VBox.vgrow="ALWAYS"} -> flag {@code "javafx.scene.layout."}
     * when {@code VBox} is imported.
     */
    private static void checkStaticPropertyAttributes(
            @NotNull XmlFile xmlFile,
            @NotNull InspectionManager manager,
            boolean isOnTheFly,
            @NotNull List<ProblemDescriptor> problems) {

        JavaPsiFacade facade = JavaPsiFacade.getInstance(xmlFile.getProject());

        for (XmlAttribute attr : PsiTreeUtil.findChildrenOfType(xmlFile, XmlAttribute.class)) {
            String attrName = attr.getName();
            if (Fxml2XmlUtil.isNonPropertyAttribute(attrName)) continue;

            int lastDot = attrName.lastIndexOf('.');
            if (lastDot <= 0) continue; // no dot or dot at start, not a static-property name

            String classCandidate = attrName.substring(0, lastDot);
            // Only interested in FQN class names; first segment must be a known package.
            String firstSeg = classCandidate.contains(".")
                    ? classCandidate.substring(0, classCandidate.indexOf('.'))
                    : classCandidate;
            if (!classCandidate.contains(".")) continue; // simple ClassName.prop, not FQN
            if (facade.findPackage(firstSeg) == null) continue;

            // Verify classCandidate actually resolves as a class (FQN lookup).
            GlobalSearchScope scope = GlobalSearchScope.allScope(xmlFile.getProject());
            PsiClass cls = facade.findClass(classCandidate, scope);
            if (cls == null) continue; // not a valid class, don't flag

            // Check if the class prefix can be shortened via imports.
            RedundantPrefix rp = findRedundantClassPrefix(classCandidate, xmlFile);
            if (rp == null) continue;

            // The problem element is the XmlAttribute's name element.
            // We use a TextRange relative to the attribute's name element covering the redundant prefix.
            PsiElement nameElem = attr.getNameElement();
            TextRange range = TextRange.create(0, rp.prefixLength());
            problems.add(manager.createProblemDescriptor(
                    nameElem, range,
                    "Redundant qualifier '" + rp.prefix() + "'",
                    ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                    isOnTheFly,
                    new RenameAttributeFix(attrName, attrName.substring(rp.prefixLength()))));
        }
    }

    // -----------------------------------------------------------------------
    // Element tag checks: FQN class tags and FQN-prefixed static-property tags
    // -----------------------------------------------------------------------

    /**
     * Handles two tag-name patterns:
     * <ol>
     *   <li><b>Pure FQN class tag</b>: {@code <javafx.scene.control.TextField/>},
     *       the entire local name resolves to a class; flag the redundant package prefix.</li>
     *   <li><b>FQN static-property tag</b>: {@code <javafx.scene.layout.VBox.margin>},
     *       local name is {@code FQN.propertyName}; flag the redundant package prefix on
     *       the class part.</li>
     * </ol>
     */
    private static void checkTags(
            @NotNull XmlFile xmlFile,
            @NotNull InspectionManager manager,
            boolean isOnTheFly,
            @NotNull List<ProblemDescriptor> problems) {

        JavaPsiFacade facade = JavaPsiFacade.getInstance(xmlFile.getProject());
        GlobalSearchScope scope = GlobalSearchScope.allScope(xmlFile.getProject());

        for (XmlTag tag : PsiTreeUtil.findChildrenOfType(xmlFile, XmlTag.class)) {
            String localName = tag.getLocalName();
            if (!localName.contains(".")) continue;
            if (Fxml2ImportResolver.FXML2_NAMESPACE.equals(tag.getNamespace())) continue;

            // Only handle tags whose name starts with a known package segment.
            String firstSeg = localName.substring(0, localName.indexOf('.'));
            if (facade.findPackage(firstSeg) == null) continue;

            String tagText = tag.getText();

            // --- Case 1: pure FQN class tag (entire localName is a class) ---
            RedundantPrefix rp = findRedundantClassPrefix(localName, xmlFile);
            if (rp != null) {
                addTagNamePrefixProblems(tag, tagText, localName, rp,
                        new RenameFqnTagFix(localName, localName.substring(rp.prefixLength())),
                        manager, isOnTheFly, problems);
                continue; // don't also check as static-property tag
            }

            // --- Case 2: FQN static-property tag (localName = FQN.propertyName) ---
            // The class part is everything up to the last dot; the property part is after it.
            int lastDot = localName.lastIndexOf('.');
            String classCandidate = localName.substring(0, lastDot);

            // classCandidate must itself start with a package (already guaranteed by firstSeg check above).
            PsiClass cls = facade.findClass(classCandidate, scope);
            if (cls == null) continue;

            RedundantPrefix classRp = findRedundantClassPrefix(classCandidate, xmlFile);
            if (classRp == null) continue;

            String shortTagName = localName.substring(classRp.prefixLength()); // e.g. "VBox.margin"
            addTagNamePrefixProblems(tag, tagText, localName, classRp,
                    new RenameFqnTagFix(localName, shortTagName),
                    manager, isOnTheFly, problems);
        }
    }

    /**
     * Adds problem descriptors for the redundant prefix in the opening (and, if present,
     * closing) occurrence of {@code localName} within {@code tagText}.
     */
    private static void addTagNamePrefixProblems(
            @NotNull XmlTag tag,
            @NotNull String tagText,
            @NotNull String localName,
            @NotNull RedundantPrefix rp,
            @NotNull LocalQuickFix fix,
            @NotNull InspectionManager manager,
            boolean isOnTheFly,
            @NotNull List<ProblemDescriptor> problems) {

        // Opening tag: localName starts at offset 1 (after '<').
        problems.add(manager.createProblemDescriptor(
                tag, TextRange.create(1, 1 + rp.prefixLength()),
                "Redundant qualifier '" + rp.prefix() + "'",
                ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                isOnTheFly, fix));

        // Closing tag: look for "</localName" occurrences.
        int searchFrom = 2;
        while (true) {
            int idx = tagText.indexOf(localName, searchFrom);
            if (idx < 0) break;
            if (idx >= 2 && tagText.charAt(idx - 1) == '/' && tagText.charAt(idx - 2) == '<') {
                problems.add(manager.createProblemDescriptor(
                        tag, TextRange.create(idx, idx + rp.prefixLength()),
                        "Redundant qualifier '" + rp.prefix() + "'",
                        ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                        isOnTheFly, fix));
            }
            searchFrom = idx + 1;
        }
    }

    // -----------------------------------------------------------------------
    // Qualifier analysis
    // -----------------------------------------------------------------------

    /**
     * Given a fully-qualified class name, returns the shortest suffix that still resolves
     * to the same class via the file's imports (plus nested-class resolution from the
     * imported outer class).  Returns {@code null} if no prefix is redundant (e.g. no
     * matching import, or the name is already a simple name).
     */
    private static @Nullable RedundantPrefix findRedundantClassPrefix(
            @NotNull String fqn, @NotNull XmlFile xmlFile) {
        PsiClass target = resolveFullyQualified(fqn, xmlFile);
        if (target == null) return null;

        String[] segments = fqn.split("\\.", -1);
        for (int keep = 1; keep < segments.length; keep++) {
            String suffix = String.join(".", Arrays.copyOfRange(segments, segments.length - keep, segments.length));
            PsiClass suffixResolved = Fxml2ImportResolver.resolve(suffix, xmlFile);
            if (suffixResolved == null && suffix.contains(".")) {
                // For "OuterClass.InnerClass": try resolving the outer via imports then walk inners.
                int firstDot = suffix.indexOf('.');
                PsiClass outer = Fxml2ImportResolver.resolve(suffix.substring(0, firstDot), xmlFile);
                if (outer != null) {
                    suffixResolved = resolveNested(outer, suffix.substring(firstDot + 1));
                }
            }
            if (suffixResolved != null && isSameClass(suffixResolved, target)) {
                int prefixLength = fqn.length() - suffix.length();
                String prefix = fqn.substring(0, prefixLength);
                return new RedundantPrefix(prefix, prefixLength);
            }
        }
        return null;
    }

    private static @Nullable PsiClass resolveNested(@NotNull PsiClass outer, @NotNull String path) {
        PsiClass current = outer;
        for (String seg : path.split("\\.", -1)) {
            PsiClass found = null;
            for (PsiClass inner : current.getInnerClasses()) {
                if (seg.equals(inner.getName())) { found = inner; break; }
            }
            if (found == null) return null;
            current = found;
        }
        return current;
    }

    private static @Nullable PsiClass resolveFullyQualified(@NotNull String fqn, @NotNull XmlFile xmlFile) {
        JavaPsiFacade facade = JavaPsiFacade.getInstance(xmlFile.getProject());
        GlobalSearchScope scope = GlobalSearchScope.allScope(xmlFile.getProject());
        PsiClass cls = facade.findClass(fqn, scope);
        if (cls != null) return cls;
        int dot = fqn.lastIndexOf('.');
        if (dot > 0) {
            return facade.findClass(fqn.substring(0, dot) + "$" + fqn.substring(dot + 1), scope);
        }
        return null;
    }

    private static boolean isSameClass(@NotNull PsiClass a, @NotNull PsiClass b) {
        String qa = a.getQualifiedName();
        String qb = b.getQualifiedName();
        return qa != null && qa.equals(qb);
    }

    private record RedundantPrefix(@NotNull String prefix, int prefixLength) {}

    /**
     * When the fix is offered inside an embedded FXML fragment (injected language), returning
     * the host PSI element (text-block / string literal) from {@code getElementToMakeWritable}
     * causes the preview system to base the copy on the host Java file rather than on the
     * injected XML file.  That makes {@code originalFile != psiFile} true inside
     * {@code QuickFixWrapper.generatePreview}, so the wrapper routes directly to our
     * {@code generatePreview(Project, ProblemDescriptor)} override which returns EMPTY: instead
     * of crashing inside {@code ProblemDescriptor.getDescriptorForPreview()} when it tries to
     * locate an injected XmlTag inside the copy.
     */
    private static @NotNull PsiElement writableElementFor(@NotNull PsiFile file) {
        InjectedLanguageManager ilm = InjectedLanguageManager.getInstance(file.getProject());
        if (ilm.isInjectedFragment(file)) {
            PsiElement host = file.getContext();
            if (host != null) return host;
        }
        return file;
    }

    // -----------------------------------------------------------------------
    // Quick-fixes
    // -----------------------------------------------------------------------

    /** Removes a redundant prefix from an attribute value string (fx:typeArguments, binding). */
    static final class RemoveAttributeValuePrefixFix implements LocalQuickFix, BatchQuickFix {

        private final @NotNull String prefix;
        private final int startInValue;

        RemoveAttributeValuePrefixFix(@NotNull String prefix, int startInValue) {
            this.prefix = prefix;
            this.startInValue = startInValue;
        }

        @Override public @NotNull String getName() { return "Remove redundant qualifier"; }
        @Override public @NotNull String getFamilyName() { return "Remove redundant qualifier"; }

        // For injected FXML: redirect the preview-copy to the host Java file so that
        // QuickFixWrapper routes to our generatePreview override instead of crashing in
        // ProblemDescriptor.getDescriptorForPreview when it can't find the injected XmlTag
        // in the copy (injected PSI trees have no parent beyond the injected file boundary).
        @Override
        public @NotNull PsiElement getElementToMakeWritable(@NotNull PsiFile file) {
            return writableElementFor(file);
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project, @NotNull ProblemDescriptor previewDescriptor) {
            return IntentionPreviewInfo.EMPTY;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement elem = descriptor.getPsiElement();
            if (!(elem instanceof XmlAttributeValue attrVal)) return;
            if (!(attrVal.getParent() instanceof XmlAttribute attr)) return;

            String currentValue = attrVal.getValue();
            int prefixEnd = startInValue + prefix.length();
            if (prefixEnd > currentValue.length()) return;
            if (!currentValue.startsWith(prefix, startInValue)) return;

            // For embedded FXML, schedule host-editor caret restoration to the position
            // immediately after the removed prefix (= start of the retained class/member name).
            PsiFile containingFile = attrVal.getContainingFile();
            InjectedLanguageManager ilm = InjectedLanguageManager.getInstance(project);
            if (ilm.isInjectedFragment(containingFile)) {
                PsiFile hostFile = containingFile.getContext() != null
                        ? containingFile.getContext().getContainingFile() : null;
                if (hostFile != null) {
                    // The prefix occupies [startInValue, startInValue + prefix.length()) within
                    // the attribute value string.  After removal the retained text moves to
                    // startInValue, so the caret target is the host offset of that position.
                    // +1 because getStartOffset() points to the opening quote of the value.
                    int injectedPrefixStart =
                            attrVal.getTextRange().getStartOffset() + 1 + startInValue;
                    int targetHostOffset = ilm.injectedToHost(containingFile, injectedPrefixStart);
                    EmbeddedFxmlCaretRestorer.scheduleRestore(project, hostFile, targetHostOffset);
                }
            }

            attr.setValue(currentValue.substring(0, startInValue) + currentValue.substring(prefixEnd));
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

    /** Renames a static-property attribute from FQN form to shortened form. */
    static final class RenameAttributeFix implements LocalQuickFix, BatchQuickFix {

        private final @NotNull String fqnAttrName;
        private final @NotNull String shortAttrName;

        RenameAttributeFix(@NotNull String fqnAttrName, @NotNull String shortAttrName) {
            this.fqnAttrName = fqnAttrName;
            this.shortAttrName = shortAttrName;
        }

        @Override public @NotNull String getName() { return "Remove redundant qualifier"; }
        @Override public @NotNull String getFamilyName() { return "Remove redundant qualifier"; }

        // Injected XML elements cannot be found in a file copy; suppress the preview to
        // avoid an exception when the fix is offered inside an embedded FXML fragment.
        @Override
        public @NotNull PsiElement getElementToMakeWritable(@NotNull PsiFile file) {
            return writableElementFor(file);
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project, @NotNull ProblemDescriptor previewDescriptor) {
            return IntentionPreviewInfo.EMPTY;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement elem = descriptor.getPsiElement();
            XmlAttribute attr = null;
            if (elem instanceof XmlAttribute a) {
                attr = a;
            } else if (elem.getParent() instanceof XmlAttribute a) {
                attr = a;
            }
            if (attr == null) return;
            if (!fqnAttrName.equals(attr.getName())) return;

            // For embedded FXML, schedule host-editor caret restoration to the start of the
            // attribute name (where the short name begins after the FQN prefix is removed).
            PsiFile containingFile = attr.getContainingFile();
            InjectedLanguageManager ilm = InjectedLanguageManager.getInstance(project);
            if (ilm.isInjectedFragment(containingFile)) {
                PsiFile hostFile = containingFile.getContext() != null
                        ? containingFile.getContext().getContainingFile() : null;
                if (hostFile != null) {
                    PsiElement nameElem = attr.getNameElement();
                    int injectedNameStart = nameElem != null
                            ? nameElem.getTextRange().getStartOffset()
                            : attr.getTextRange().getStartOffset();
                    int targetHostOffset = ilm.injectedToHost(containingFile, injectedNameStart);
                    EmbeddedFxmlCaretRestorer.scheduleRestore(project, hostFile, targetHostOffset);
                }
            }

            attr.setName(shortAttrName);
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

    /** Renames a FQN element tag (or FQN-prefixed static-property tag) to shortened form. */
    static final class RenameFqnTagFix implements LocalQuickFix, BatchQuickFix {

        private final @NotNull String fqnLocalName;
        private final @NotNull String shortName;

        RenameFqnTagFix(@NotNull String fqnLocalName, @NotNull String shortName) {
            this.fqnLocalName = fqnLocalName;
            this.shortName = shortName;
        }

        @Override public @NotNull String getName() { return "Remove redundant qualifier"; }
        @Override public @NotNull String getFamilyName() { return "Remove redundant qualifier"; }

        // Injected XML elements cannot be found in a file copy; suppress the preview to
        // avoid an exception when the fix is offered inside an embedded FXML fragment.
        @Override
        public @NotNull PsiElement getElementToMakeWritable(@NotNull PsiFile file) {
            return writableElementFor(file);
        }

        @Override
        public @NotNull IntentionPreviewInfo generatePreview(
                @NotNull Project project, @NotNull ProblemDescriptor previewDescriptor) {
            return IntentionPreviewInfo.EMPTY;
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement elem = descriptor.getPsiElement();
            if (!(elem instanceof XmlTag tag)) return;
            if (!fqnLocalName.equals(tag.getLocalName())) return;

            // For embedded FXML, schedule host-editor caret restoration to the start of the
            // short tag name.  The tag name begins one character after '<', so the injected
            // offset is tag.getTextRange().getStartOffset() + 1.  After the FQN prefix is
            // removed the short name occupies that same host position.
            PsiFile containingFile = tag.getContainingFile();
            InjectedLanguageManager ilm = InjectedLanguageManager.getInstance(project);
            if (ilm.isInjectedFragment(containingFile)) {
                PsiFile hostFile = containingFile.getContext() != null
                        ? containingFile.getContext().getContainingFile() : null;
                if (hostFile != null) {
                    int injectedTagNameStart = tag.getTextRange().getStartOffset() + 1;
                    int targetHostOffset = ilm.injectedToHost(containingFile, injectedTagNameStart);
                    EmbeddedFxmlCaretRestorer.scheduleRestore(project, hostFile, targetHostOffset);
                }
            }

            tag.setName(shortName);
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
