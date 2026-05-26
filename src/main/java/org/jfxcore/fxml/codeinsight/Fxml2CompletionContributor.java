package org.jfxcore.fxml.codeinsight;

import com.intellij.lang.ASTNode;
import com.intellij.application.options.editor.WebEditorOptions;
import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.completion.CompletionUtilCore;
import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.completion.PlainPrefixMatcher;
import com.intellij.codeInsight.completion.PrioritizedLookupElement;
import com.intellij.codeInsight.completion.XmlTagInsertHandler;
import com.intellij.codeInsight.editorActions.TabOutScopesTracker;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.codeInsight.lookup.LookupElementDecorator;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.util.text.CharArrayUtil;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlProcessingInstruction;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlTokenType;
import com.intellij.util.ProcessingContext;
import com.intellij.xml.XmlAttributeDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.descriptors.Fxml2ClassTagDescriptor;
import org.jfxcore.fxml.descriptors.Fxml2PropertyAttributeDescriptor;
import org.jfxcore.fxml.descriptors.Fxml2StaticPropertyAttributeDescriptor;
import org.jfxcore.fxml.lang.Fxml2ImportUtil;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.resolve.Fxml2AttributeValueResolver;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2BindingPathResolver;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;
import org.jfxcore.fxml.resolve.Fxml2PropertyNameUtil;
import org.jfxcore.fxml.resolve.Fxml2PropertyResolver;
import org.jfxcore.fxml.resolve.Fxml2TagResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.intellij.patterns.PlatformPatterns.psiElement;
import static com.intellij.patterns.XmlPatterns.xmlAttributeValue;
import static com.intellij.patterns.XmlPatterns.xmlTag;

/**
 * Provides autocomplete for FXML files:
 * <ol>
 *   <li><b>Tag names</b>: imported classes (and classes resolvable from the project via auto-import)</li>
 *   <li><b>Attribute literal values</b>: enum constants and public static fields on the property type</li>
 *   <li><b>Binding path segments</b>: property names on the resolved class at each path level</li>
 * </ol>
 */
public final class Fxml2CompletionContributor extends CompletionContributor {

    public Fxml2CompletionContributor() {
        // Tag name completion (simple names from imports, and dotted FQN tag names)
        extend(CompletionType.BASIC, psiElement().inside(xmlTag()),
                new TagNameCompletionProvider());

        // FQN class name completion for fx:typeArguments, String class-name attributes,
        // and the class-prefix part of static property attributes (e.g. VBox.vgrow)
        extend(CompletionType.BASIC,
                psiElement().inside(xmlAttributeValue()),
                new FqnClassNameCompletionProvider());

        // Attribute value completion (literal values & binding paths)
        extend(CompletionType.BASIC,
                psiElement().inside(xmlAttributeValue()),
                new AttributeValueCompletionProvider());
    }

    /**
     * Override to intercept {@code <?import ...?>} and dotted-FQN tag completion.
     * <p>
     * For these two special contexts we call {@code result.runRemainingContributors} with a
     * no-op consumer, effectively suppressing every other contributor, then add only
     * our FQN items.  For all other positions we delegate normally.
     * <p>
     * This contributor is registered with {@code order="first"} in {@code plugin.xml} so
     * that it runs before the built-in XML completion contributor.
     */
    @Override
    public void fillCompletionVariants(@NotNull CompletionParameters parameters,
                                       @NotNull CompletionResultSet result) {
        PsiFile file = parameters.getPosition().getContainingFile();
        if (!(file instanceof XmlFile xmlFile) || !Fxml2FileType.isFxml2(xmlFile)) {
            super.fillCompletionVariants(parameters, result);
            return;
        }

        int caretOffset = parameters.getOffset();

        // -- Import PI: <?import javafx.scene.List<caret>?> -----------------
        PsiElement origPos = parameters.getOriginalFile().findElementAt(caretOffset);
        if (origPos != null) {
            XmlProcessingInstruction pi = ImportPiCompletionProvider.findEnclosingImportPi(origPos);
            if (pi != null) {
                ASTNode dataNode = pi.getNode().findChildByType(XmlTokenType.XML_TAG_CHARACTERS);
                String typed = "";
                if (dataNode != null) {
                    int dataStart = dataNode.getStartOffset();
                    if (caretOffset >= dataStart) {
                        int caretInData = caretOffset - dataStart;
                        String dataText = dataNode.getText();
                        if (caretInData <= dataText.length()) {
                            typed = dataText.substring(0, caretInData).trim();
                        }
                    }
                }
                // Suppress all other contributors (no-op consumer), then provide our FQN items.
                result.runRemainingContributors(parameters, r -> { /* discard all default items */ });
                FqnClassNameCompletionProvider.completeFqn(parameters, result, typed);
                return;
            }
        }

        // -- Dotted tag name: <javafx.scene.List<caret> .../> or <selectionModel.selectionMo<caret>>
        PsiElement pos = parameters.getPosition();
        XmlTag tag = findEnclosingTagForName(pos);
        if (tag != null && isInTagName(pos, tag) && tag.getName().contains(".")) {
            int nameStart = tag.getTextOffset() + 1;
            String origText = parameters.getOriginalFile().getText();
            if (caretOffset >= nameStart && caretOffset <= origText.length()) {
                String typedTagName = origText.substring(nameStart, caretOffset);
                result.runRemainingContributors(parameters, r -> { /* discard all default items */ });
                // Distinguish FQN class tags (e.g. javafx.scene.control.List) from
                // property chains (e.g. selectionModel.selectionMo): if the first segment
                // resolves as a known package, treat it as an FQN; otherwise as a property chain.
                String firstSegment = typedTagName.contains(".")
                        ? typedTagName.substring(0, typedTagName.indexOf('.'))
                        : typedTagName;
                boolean isPackagePrefix = JavaPsiFacade.getInstance(xmlFile.getProject())
                        .findPackage(firstSegment) != null;
                if (isPackagePrefix) {
                    FqnClassNameCompletionProvider.completeFqn(parameters, result, typedTagName);
                } else {
                    addPropertyChainTagNameCompletions(tag, typedTagName, result);
                }
                return;
            }
        }

        // -- XML text content: >MULTIP<caret></selectionModel.selectionMode> --
        // The caret is in XmlText inside a property element tag; offer enum/static-field values.
        if (isInXmlText(pos)) {
            XmlTag propertyTag = findEnclosingPropertyTag(pos);
            if (propertyTag != null) {
                result.runRemainingContributors(parameters, r -> { /* discard all default items */ });
                addXmlTextLiteralCompletions(propertyTag, parameters, result);
                return;
            }
        }

        // -- Dotted attribute name: VBox.vgro<caret>="ALWAYS" ------------------
        // IntelliJ's default XML attribute-name prefix starts at the last non-identifier
        // character (the '.'), so the prefix would be just "vgro" instead of "VBox.vgro".
        // That means descriptors named "VBox.vgrow" don't match.  We intercept here,
        // compute the full dotted prefix, and emit matching attribute name variants.
        if (isInAttributeName(pos)) {
            XmlTag enclosingTag = findEnclosingTagForName(pos);
            if (enclosingTag != null) {
                // Compute the full attribute name typed so far (from the start of the name token
                // to the caret), including the part before any dot.
                String origText = parameters.getOriginalFile().getText();
                // Walk up to the XmlAttribute to find where the attribute name starts.
                if (pos.getParent() instanceof XmlAttribute xmlAttr) {
                    int attrNameStart = xmlAttr.getTextRange().getStartOffset();
                    if (attrNameStart <= caretOffset && caretOffset <= origText.length()) {
                        String typedAttrName = origText.substring(attrNameStart, caretOffset);
                        // Only handle dotted names (static property attributes like VBox.vgrow).
                        // Plain attribute names (e.g. "text", "prefWidth") are handled by the
                        // built-in XML contributor perfectly well.
                        if (typedAttrName.contains(".")) {
                            result.runRemainingContributors(parameters, r -> { /* suppress defaults */ });
                            addStaticPropertyAttributeNameCompletions(
                                    enclosingTag, typedAttrName, parameters, result);
                            return;
                        }
                    }
                }
            }
        }

        // -- Plain attribute name in embedded FXML ----------------------------------------
        // XmlAttributeInsertHandler.handleInsert() inspects file.getContext().getText() to
        // choose between ' and " as the attribute-value delimiter.  For embedded FXML the
        // context element is the host Java PsiLiteralExpression (a text block) whose text
        // starts with '"""' (i.e. the first char is '"'), so the handler incorrectly uses
        // single quotes ('=\'\'') instead of double quotes ('=""').
        // We intercept all attribute-name completions in this context and wrap them with a
        // custom handler (EmbeddedFxml2AttributeInsertHandler) that:
        //   1. Always inserts double quotes.
        //   2. Commits the document before the auto-popup fires, preventing the
        //      "Inconsistent completion tree: range=(X,Y); fileLength=Z" error that occurs
        //      when the injected PSI is stale at auto-popup time.
        if (isInAttributeName(pos) && Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile)) {
            result.runRemainingContributors(parameters, r ->
                    result.passResult(r.withLookupElement(
                            LookupElementDecorator.withInsertHandler(r.getLookupElement(),
                                    EmbeddedFxml2AttributeInsertHandler.INSTANCE))));
            return;
        }

        // -- Plain literal attribute value: text="f<caret>", editable="f<caret>", etc. --
        // IntelliJ's default XML contributor adds attribute value suggestions derived from
        // the attribute descriptor's enum values, which are not meaningful for FXML and
        // produce noise (e.g. "cellFactory", "prefHeight").  Suppress all other contributors
        // and invoke our literal-value completion directly, but only for property attributes
        // with a non-String type (String/FQN attributes still need FqnClassNameCompletionProvider).
        if (pos.getParent() instanceof XmlAttributeValue attrVal
                && attrVal.getParent() instanceof XmlAttribute attr
                && attr.getParent() instanceof XmlTag attrTag) {
            String raw = attrVal.getValue();
            int dummyIdx = raw.indexOf(CompletionUtilCore.DUMMY_IDENTIFIER);
            if (dummyIdx < 0) dummyIdx = raw.indexOf(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED);
            String value = (dummyIdx >= 0 ? raw.substring(0, dummyIdx) : raw).trim();
            if (!Fxml2BindingExpressionParser.looksLikeBindingExpression(value)
                    && shouldSuppressDefaultAttrValueCompletion(attr, attrTag)) {
                result.runRemainingContributors(parameters, r -> { /* suppress default XML completion */ });
                AttributeValueCompletionProvider.addLiteralValueCompletions(attr, attrTag, value, result);
                return;
            }
        }

        super.fillCompletionVariants(parameters, result);
    }

    /**
     * Returns {@code true} when the default XML attribute-value completion should be
     * suppressed in favor of our own literal-value completion (or no completion at all).
     * This covers:
     * <ul>
     *   <li>Static property attributes (e.g. {@code VBox.vgrow}): always suppressed.</li>
     *   <li>Instance property attributes whose type is {@code String}: suppressed to avoid
     *       the default XML contributor offering noisy class-name / enum suggestions for
     *       free-form text properties like {@code text}, {@code title}, {@code prompt}.</li>
     *   <li>Instance property attributes with any other known type (enum, numeric, boolean)
     *        suppressed so our enum/static-field completions take over.</li>
     * </ul>
     */
    private static boolean shouldSuppressDefaultAttrValueCompletion(
            @NotNull XmlAttribute attr, @NotNull XmlTag tag) {
        XmlAttributeDescriptor desc = attr.getDescriptor();
        // Static property attribute (e.g. VBox.vgrow): always suppress
        if (desc instanceof Fxml2StaticPropertyAttributeDescriptor) return true;
        // Instance property on a class tag: suppressed for all known-typed properties,
        // including String (which has no meaningful static-field completions but also
        // must not show the default XML noise).
        if (!(tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd)) return false;
        PsiClass ownerClass = cd.getPsiClass();
        if (ownerClass == null) return false;
        String attrName = attr.getLocalName();
        PsiType propType = Fxml2AttributeValueResolver.propertyType(
                ownerClass, attrName, java.util.List.of());
        // If the property type is resolvable, always suppress the default contributor.
        // Our own AttributeValueCompletionProvider will add relevant completions (or none
        // for String properties, which is correct (they're free-form text).
        return propType != null;
    }

    /**
     * Returns true when {@code pos} is the XML_NAME leaf of an {@link XmlAttribute}
     * (i.e. the user is typing an attribute name, not a value or tag name).
     */
    private static boolean isInAttributeName(@NotNull PsiElement pos) {
        PsiElement parent = pos.getParent();
        return parent instanceof XmlAttribute;
    }

    /**
     * Emits completion items for static-property attribute names whose name starts with the
     * already-typed prefix.  Handles two cases:
     *
     * <ol>
     *   <li><b>Short-form prefix</b> (e.g. {@code "VBox.vgro"}): matches against descriptors
     *       provided by the tag descriptor, works for all imported classes.</li>
     *   <li><b>FQN prefix</b> (e.g. {@code "org.jfxcore.command.Comman"} or
     *       {@code "org.jfxcore.command.Command5."}): delegates to
     *       {@link FqnClassNameCompletionProvider#completeFqn} for the package/class-name
     *       portion, and additionally offers {@code FQNClass.propertyName} items when the
     *       class part is fully resolved.</li>
     * </ol>
     *
     * @param tag           the enclosing XML element tag
     * @param typedAttrName the attribute name typed so far
     * @param parameters    the original completion parameters (needed by {@code completeFqn})
     * @param result        the completion result set
     */
    private static void addStaticPropertyAttributeNameCompletions(
            @NotNull XmlTag tag,
            @NotNull String typedAttrName,
            @NotNull CompletionParameters parameters,
            @NotNull CompletionResultSet result) {

        // -- Short-form (imported class prefix): e.g. "VBox.vgro" -------------
        // Determine if the first segment is a known package to decide which path to take.
        String firstSegment = typedAttrName.substring(0, typedAttrName.indexOf('.'));
        JavaPsiFacade facade = JavaPsiFacade.getInstance(tag.getProject());
        boolean isFqnPrefix = facade.findPackage(firstSegment) != null;

        if (!isFqnPrefix) {
            // Short-form: iterate tag descriptors (imported classes only).
            var tagDescriptor = tag.getDescriptor();
            if (tagDescriptor == null) return;
            XmlAttributeDescriptor[] descriptors = tagDescriptor.getAttributesDescriptors(tag);
            if (descriptors == null) return;

            CompletionResultSet attrResult = result.withPrefixMatcher(
                    new PlainPrefixMatcher(typedAttrName, true));
            for (XmlAttributeDescriptor descriptor : descriptors) {
                String name = descriptor.getName(tag);
                if (name == null || !name.contains(".") || !name.startsWith(typedAttrName)) continue;
                attrResult.addElement(LookupElementBuilder.create(name)
                        .withIcon(AllIcons.Nodes.Property));
            }
            return;
        }

        // -- FQN prefix: e.g. "org.", "org.jfxcore.command.Comman",
        //    or "org.jfxcore.command.Command5." ------------------------------

        // Split at the last dot to determine what has been fully typed vs. what's partial.
        int lastDot = typedAttrName.lastIndexOf('.');
        String beforeLastDot = typedAttrName.substring(0, lastDot);  // e.g. "org.jfxcore.command"
        String afterLastDot  = typedAttrName.substring(lastDot + 1); // e.g. "Comman" or "" or "onAct"

        GlobalSearchScope allScope = GlobalSearchScope.allScope(tag.getProject());

        // Check if the part before the last dot is already a fully-resolved class.
        // If so, we're completing a property name on that class.
        PsiClass resolvedClass = facade.findClass(beforeLastDot, allScope);
        if (resolvedClass == null) {
            // Try nested-class $ variant.
            int dot = beforeLastDot.lastIndexOf('.');
            if (dot > 0) {
                resolvedClass = facade.findClass(
                        beforeLastDot.substring(0, dot) + "$" + beforeLastDot.substring(dot + 1),
                        allScope);
            }
        }

        if (resolvedClass != null) {
            // The class is resolved: offer "FQNClass.propertyName" completions.
            String fqnClass = resolvedClass.getQualifiedName();
            if (fqnClass == null) return;
            CompletionResultSet propResult = result.withPrefixMatcher(
                    new PlainPrefixMatcher(typedAttrName, true));
            for (PsiMethod method : resolvedClass.getAllMethods()) {
                if (!method.hasModifierProperty(PsiModifier.PUBLIC)) continue;
                if (!method.hasModifierProperty(PsiModifier.STATIC)) continue;
                String mName = method.getName();
                // Accept setXxx(owner, value): matches the static setter pattern for static properties.
                if (!Fxml2PropertyNameUtil.isSetterName(mName)) continue;
                if (method.getParameterList().getParametersCount() != 2) continue;
                String propName = Fxml2PropertyNameUtil.setterToPropertyName(mName);
                if (!propName.startsWith(afterLastDot)) continue;
                String fullAttrName = fqnClass + "." + propName;
                propResult.addElement(LookupElementBuilder.create(fullAttrName)
                        .withPresentableText(propName)
                        .withIcon(AllIcons.Nodes.Property)
                        .withTailText(" (" + fqnClass + ")", true));
            }
        } else {
            // The class is not yet fully typed; complete the FQN (package/class segment).
            FqnClassNameCompletionProvider.completeFqn(parameters, result, typedAttrName);
        }
    }

    private static @Nullable XmlTag findEnclosingTagForName(@NotNull PsiElement pos) {
        PsiElement cur = pos;
        while (cur != null) {
            if (cur instanceof XmlTag t) return t;
            cur = cur.getParent();
        }
        return null;
    }

    private static boolean isInTagName(@NotNull PsiElement pos, @NotNull XmlTag tag) {
        PsiElement cur = pos.getParent();
        while (cur != null && cur != tag) {
            if (cur instanceof XmlAttribute) return false;
            if (cur instanceof com.intellij.psi.xml.XmlText) return false;
            cur = cur.getParent();
        }
        return true;
    }

    private static @Nullable XmlAttributeValue findEnclosingAttributeValue(@NotNull PsiElement pos) {
        PsiElement cur = pos;
        while (cur != null) {
            if (cur instanceof XmlAttributeValue v) return v;
            if (cur instanceof XmlTag) return null;
            cur = cur.getParent();
        }
        return null;
    }

    /** Returns true when {@code pos} is inside an {@code XmlText} node (element text content). */
    private static boolean isInXmlText(@NotNull PsiElement pos) {
        PsiElement cur = pos;
        while (cur != null) {
            if (cur instanceof com.intellij.psi.xml.XmlText) return true;
            if (cur instanceof XmlTag) return false;
            cur = cur.getParent();
        }
        return false;
    }

    /**
     * Returns the enclosing {@link XmlTag} if it is a property tag (i.e. its descriptor is
     * a {@link org.jfxcore.fxml.descriptors.Fxml2PropertyTagDescriptor}).  Returns {@code null}
     * when the enclosing tag is a class tag or has no descriptor.
     */
    private static @Nullable XmlTag findEnclosingPropertyTag(@NotNull PsiElement pos) {
        PsiElement cur = pos.getParent();
        while (cur != null) {
            if (cur instanceof XmlTag t) {
                if (t.getDescriptor() instanceof org.jfxcore.fxml.descriptors.Fxml2PropertyTagDescriptor) {
                    return t;
                }
                return null; // class tag or root, stop
            }
            cur = cur.getParent();
        }
        return null;
    }

    /**
     * Completes dotted property-chain tag names, e.g. {@code selectionModel.selectionMo} ->
     * {@code selectionModel.selectionMode}.
     *
     * <p>The algorithm:
     * <ol>
     *   <li>Walk segments up to (but not including) the last one, resolving each as an
     *       instance property to arrive at the type on which to complete.</li>
     *   <li>Offer all property names on that type that start with the last partial segment.</li>
     * </ol>
     */
    private static void addPropertyChainTagNameCompletions(
            @NotNull XmlTag tag,
            @NotNull String typedTagName,
            @NotNull CompletionResultSet result) {

        // Resolve the parent element's class.
        PsiClass parentClass = Fxml2TagResolver.resolveParentClass(tag);
        if (parentClass == null) return;


        String[] segments = typedTagName.split("\\.", -1);
        PsiClass current = parentClass;

        // Walk all segments except the last one.
        for (int i = 0; i < segments.length - 1; i++) {
            String seg = segments[i];
            if (seg.isEmpty()) return;
            PsiElement decl = Fxml2PropertyResolver.resolveInstanceProperty(current, seg);
            if (decl == null) return;
            current = Fxml2PropertyResolver.resolvePropertyType(decl);
            if (current == null) return;
        }

        // The last (partial) segment is what the user is completing.
        String partial = segments[segments.length - 1];
        // Build the completed prefix (all segments except the last) to prepend to each result.
        String completedPrefix = typedTagName.contains(".")
                ? typedTagName.substring(0, typedTagName.lastIndexOf('.') + 1) // includes trailing dot
                : "";

        CompletionResultSet prefixResult = result.withPrefixMatcher(
                new PlainPrefixMatcher(typedTagName, true));

        for (String propName : Fxml2PropertyResolver.getAllPropertyNames(current)) {
            if (propName.startsWith(partial)) {
                String fullName = completedPrefix + propName;
                prefixResult.addElement(LookupElementBuilder.create(fullName)
                        .withPresentableText(propName)
                        .withIcon(AllIcons.Nodes.Property));
            }
        }
    }

    /**
     * Offers enum / static-field completions for XML text content inside a property element tag,
     * e.g. {@code <selectionModel.selectionMode>MULTIP<caret></selectionModel.selectionMode>}.
     */
    private static void addXmlTextLiteralCompletions(
            @NotNull XmlTag propertyTag,
            @NotNull CompletionParameters parameters,
            @NotNull CompletionResultSet result) {

        // Resolve the property type via the tag descriptor.
        var descriptor = propertyTag.getDescriptor();
        if (!(descriptor instanceof org.jfxcore.fxml.descriptors.Fxml2PropertyTagDescriptor propDesc)) return;

        PsiClass propClass;
        PsiType propType;

        if (propDesc.isStatic()) {
            // Static property tag (e.g. <VBox.vgrow>): derive value type from the static setter's
            // second parameter, since resolvePropertyType() on a 2-param static setter returns null.
            PsiClass ownerClass = propDesc.getOwnerClass();
            if (ownerClass == null) return;
            propType = Fxml2AttributeValueResolver.staticPropertyType(ownerClass, propDesc.getPropertyName());
            if (propType == null) return;
            propClass = PsiUtil.resolveClassInType(propType);
        } else {
            PsiElement decl = propDesc.getDeclaration();
            if (decl == null) return;
            propClass = Fxml2PropertyResolver.resolvePropertyType(decl);
            if (propClass == null) return;
            // Also get PsiType for assignability checks on non-enum types.
            propType = Fxml2AttributeValueResolver.resolveDeclarationPsiType(decl);
        }

        // Compute the typed prefix from the completion-token text (the leaf that contains the caret).
        // The token's text includes the IntelliJ dummy identifier appended at the caret position;
        // stripping it gives us exactly what the user has typed.
        String partial = parameters.getPosition().getText()
                .replace(CompletionUtilCore.DUMMY_IDENTIFIER, "")
                .replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "")
                .trim();

        CompletionResultSet prefixResult = result.withPrefixMatcher(
                new PlainPrefixMatcher(partial, true));

        if (propClass != null && propClass.isEnum()) {
            for (PsiField field : propClass.getFields()) {
                if (field.hasModifierProperty(PsiModifier.STATIC)
                        && field.getName().startsWith(partial)) {
                    prefixResult.addElement(LookupElementBuilder.create(field, field.getName())
                            .withIcon(AllIcons.Nodes.Enum)
                            .withTypeText(propClass.getName()));
                }
            }
        } else if (propClass != null && propType != null) {
            // Non-enum: offer public static fields assignable to the property type.
            for (PsiField field : propClass.getAllFields()) {
                if (!field.hasModifierProperty(PsiModifier.PUBLIC)) continue;
                if (!field.hasModifierProperty(PsiModifier.STATIC)) continue;
                if (!propType.isAssignableFrom(field.getType())) continue;
                if (field.getName().startsWith(partial)) {
                    prefixResult.addElement(LookupElementBuilder.create(field, field.getName())
                            .withIcon(AllIcons.Nodes.Field)
                            .withTypeText(propClass.getName()));
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Tag name completion
    // -----------------------------------------------------------------------

    private static final class TagNameCompletionProvider extends CompletionProvider<CompletionParameters> {

        @Override
        protected void addCompletions(@NotNull CompletionParameters parameters,
                                      @NotNull ProcessingContext context,
                                      @NotNull CompletionResultSet result) {
            PsiFile file = parameters.getPosition().getContainingFile();
            if (!(file instanceof XmlFile xmlFile)) return;
            if (!Fxml2FileType.isFxml2(xmlFile)) return;

            PsiElement pos = parameters.getPosition();
            XmlTag tag = findEnclosingTagForName(pos);
            if (tag == null) return;
            if (!isInTagName(pos, tag)) return;

            // Dotted tag names (FQN element tags) are handled in fillCompletionVariants.
            if (tag.getName().contains(".")) return;

            String prefix = result.getPrefixMatcher().getPrefix();

            // 1. Complete from existing imports
            for (String importFqn : Fxml2ImportResolver.parseImports(xmlFile)) {
                if (importFqn.endsWith(".*")) {
                    // Wildcard import (common in embedded FXML synthesised from Java imports):
                    // expand to all classes in the package so they appear in completion.
                    String pkgName = importFqn.substring(0, importFqn.length() - 2);
                    PsiPackage pkg = JavaPsiFacade.getInstance(xmlFile.getProject())
                            .findPackage(pkgName);
                    if (pkg != null) {
                        for (PsiClass cls : pkg.getClasses(xmlFile.getResolveScope())) {
                            if (cls.getName() != null) {
                                addClassElement(result, cls, cls.getName(), false);
                            }
                        }
                    }
                    continue;
                }
                String simpleName = importFqn.substring(importFqn.lastIndexOf('.') + 1);
                PsiClass cls = Fxml2ImportResolver.resolve(simpleName, xmlFile);
                if (cls != null) {
                    addClassElement(result, cls, simpleName, false);
                }
            }

            // 2. Complete from project classes (with auto-import): only when prefix is non-empty
            if (!prefix.isEmpty()) {
                Project project = xmlFile.getProject();
                GlobalSearchScope scope = xmlFile.getResolveScope();
                PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
                XmlFile originalFile = parameters.getOriginalFile() instanceof XmlFile xf ? xf : xmlFile;
                java.util.Set<com.intellij.openapi.vfs.VirtualFile> runtimeRoots =
                        Fxml2AddImportFix.buildProductionRuntimeRoots(originalFile);
                String[] allNames = cache.getAllClassNames();
                for (String name : allNames) {
                    if (!name.startsWith(prefix)) continue;
                    PsiClass existing = Fxml2ImportResolver.resolve(name, xmlFile);
                    if (existing != null) continue;
                    PsiClass[] classes = cache.getClassesByName(name, scope);
                    for (PsiClass cls : classes) {
                        String fqn = cls.getQualifiedName();
                        if (Fxml2AddImportFix.shouldSkipClass(cls, fqn, runtimeRoots, true)) continue;
                        addClassElement(result, cls, name, true);
                    }
                }
            }
        }

        private static void addClassElement(@NotNull CompletionResultSet result,
                                            @NotNull PsiClass cls,
                                            @NotNull String insertName,
                                            boolean needsImport) {
            LookupElementBuilder element = LookupElementBuilder
                    .create(cls, insertName)
                    .withIcon(AllIcons.Nodes.Class)
                    .withTailText(needsImport && cls.getQualifiedName() != null
                            ? " (" + cls.getQualifiedName() + ")" : "", true)
                    .withInsertHandler(new TagInsertHandler(needsImport, cls));
            int tier = Fxml2AddImportFix.candidateTier(cls);
            result.addElement(PrioritizedLookupElement.withPriority(element, -tier));
        }
    }

    /**
     * Insert handler for class tags: adds the closing tag, positions the cursor,
     * and inserts the import PI when the class is not yet imported.
     */
    private static final class TagInsertHandler extends XmlTagInsertHandler {
        private final boolean myNeedsImport;
        private final PsiClass myClass;

        TagInsertHandler(boolean needsImport, PsiClass cls) {
            this.myNeedsImport = needsImport;
            this.myClass = cls;
        }

        @Override
        public void handleInsert(@NotNull InsertionContext context, @NotNull LookupElement item) {
            super.handleInsert(context, item);
            if (myNeedsImport) {
                String fqn = myClass.getQualifiedName();
                if (fqn != null) {
                    PsiFile file = context.getFile();
                    if (file instanceof XmlFile xmlFile) {
                        // Commit document before PSI modification: we are already inside
                        // a write action from the completion framework, so we must NOT nest
                        // another WriteCommandAction. Just commit then modify PSI directly.
                        context.commitDocument();
                        Fxml2AddImportFix.doInsertImportPsi(context.getProject(), xmlFile, fqn);
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // FQN class name completion (fx:typeArguments and String class-name attrs)
    // -----------------------------------------------------------------------
    // <?import ...?> FQN completion: helper only (actual dispatch is in fillCompletionVariants)
    // -----------------------------------------------------------------------

    /**
     * Insert handler for markup extension completions inside attribute values:
     * inserts the {@code {ClassName } text (already placed by the lookup item) and
     * adds an {@code <?import fqn?>} PI for the class if not yet present.
     */
    private static final class MarkupExtensionImportInsertHandler implements InsertHandler<LookupElement> {
        private final PsiClass myClass;

        MarkupExtensionImportInsertHandler(@NotNull PsiClass cls) {
            this.myClass = cls;
        }

        @Override
        public void handleInsert(@NotNull InsertionContext context, @NotNull LookupElement item) {
            String fqn = myClass.getQualifiedName();
            if (fqn == null) return;
            PsiFile file = context.getFile();
            if (!(file instanceof XmlFile xmlFile)) return;
            context.commitDocument();
            Fxml2AddImportFix.doInsertImportPsi(context.getProject(), xmlFile, fqn);
        }
    }

    /**
     * Insert handler for {@code fx:typeArguments} completions of classes that are not yet
     * imported.  The lookup element uses the simple name as its lookup string (so the prefix
     * matcher can match it), so the handler only needs to add the {@code <?import?>} PI.
     */
    private static final class TypeArgImportInsertHandler implements InsertHandler<LookupElement> {
        private final PsiClass myClass;

        TypeArgImportInsertHandler(@NotNull PsiClass cls) {
            this.myClass = cls;
        }

        @Override
        public void handleInsert(@NotNull InsertionContext context, @NotNull LookupElement item) {
            String fqn = myClass.getQualifiedName();
            if (fqn == null) return;
            PsiFile file = context.getFile();
            if (!(file instanceof XmlFile xmlFile)) return;
            context.commitDocument();
            Fxml2AddImportFix.doInsertImportPsi(context.getProject(), xmlFile, fqn);
        }
    }

    /** Walks up from {@code pos} to find the enclosing {@code <?import ...?>} PI, or {@code null}. */
    private static final class ImportPiCompletionProvider {
        static @Nullable XmlProcessingInstruction findEnclosingImportPi(@NotNull PsiElement pos) {
            PsiElement cur = pos;
            while (cur != null) {
                if (cur instanceof XmlProcessingInstruction pi) {
                    ASTNode nameNode = pi.getNode().findChildByType(XmlTokenType.XML_NAME);
                    if (nameNode != null && "import".equals(nameNode.getText())) return pi;
                    return null;
                }
                if (cur instanceof XmlTag) return null;
                cur = cur.getParent();
            }
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // FQN class name completion (fx:typeArguments, String class-name attrs,
    //                            static-property class prefix, import PIs, dotted tags)
    // -----------------------------------------------------------------------

    /**
     * Completes dot-separated fully-qualified class names in:
     * <ul>
     *   <li>{@code fx:typeArguments="javafx.scene.Conv<caret>"}</li>
     *   <li>{@code viewClassName="org.example.Conv<caret>"}: {@code String}-typed {@code @NamedArg} attrs</li>
     *   <li>{@code <?import javafx.scene.control.But<caret>ton?>}: dispatched from {@link #fillCompletionVariants}</li>
     *   <li>{@code <javafx.scene.control.But<caret>ton/>}: dotted FQN element tags, dispatched from {@link #fillCompletionVariants}</li>
     * </ul>
     */
    private static final class FqnClassNameCompletionProvider extends CompletionProvider<CompletionParameters> {

        @Override
        protected void addCompletions(@NotNull CompletionParameters parameters,
                                      @NotNull ProcessingContext context,
                                      @NotNull CompletionResultSet result) {
            PsiFile file = parameters.getPosition().getContainingFile();
            if (!(file instanceof XmlFile xmlFile)) return;
            if (!Fxml2FileType.isFxml2(xmlFile)) return;

            PsiElement pos = parameters.getPosition();
            XmlAttributeValue attrVal = findEnclosingAttributeValue(pos);
            if (attrVal == null) return;
            if (!(attrVal.getParent() instanceof XmlAttribute attr)) return;

            // This provider only handles fx:typeArguments.
            // All other attribute values, including plain String properties, must not get
            // FQN class-name completions (String properties are free-form text; binding
            // expressions are handled by AttributeValueCompletionProvider).
            boolean isFxTypeArguments = "typeArguments".equals(attr.getLocalName())
                    && Fxml2ImportResolver.FXML2_NAMESPACE.equals(attr.getNamespace());
            if (!isFxTypeArguments) return;

            // Binding expressions in fx:typeArguments: skip (shouldn't happen, but guard it).
            int preValueStart = attrVal.getValueTextRange().getStartOffset();
            int preCaretOffset = parameters.getOffset();
            String preTyped = preCaretOffset >= preValueStart
                    ? parameters.getOriginalFile().getText().substring(preValueStart, Math.min(preCaretOffset, parameters.getOriginalFile().getTextLength()))
                    : "";
            if (Fxml2BindingExpressionParser.looksLikeBindingExpression(preTyped)) return;

            // Compute what the user has typed inside the attribute value up to the caret.
            int valueStart = attrVal.getValueTextRange().getStartOffset();
            int caretOffset = parameters.getOffset();
            if (caretOffset < valueStart) return;
            String fileText = parameters.getOriginalFile().getText();
            if (caretOffset > fileText.length()) return;
            String typed = fileText.substring(valueStart, caretOffset);

            // fx:typeArguments accepts comma-separated type names (e.g. "Row, Button").
            // Extract only the segment after the last comma so completeFqn sees just the
            // current argument being typed (e.g. "Bu" from "Row, Bu").
            int lastComma = typed.lastIndexOf(',');
            if (lastComma >= 0) {
                typed = typed.substring(lastComma + 1).stripLeading();
            }

            completeFqn(parameters, result, typed);
        }

        /**
         * Core FQN completion logic, shared by attribute values, import PIs, and dotted tag names.
         * <p>
         * Uses {@link com.intellij.psi.PsiPackage} for direct package/class enumeration so that
         * completion is O(package-size) rather than O(all-classes-in-project).
         *
         * @param typed the text already typed up to the caret (may contain dots)
         */
        static void completeFqn(@NotNull CompletionParameters parameters,
                                 @NotNull CompletionResultSet result,
                                 @NotNull String typed) {
            PsiFile file = parameters.getPosition().getContainingFile();
            if (!(file instanceof XmlFile)) return;
            XmlFile originalFile = parameters.getOriginalFile() instanceof XmlFile xf ? xf : (XmlFile) file;
            Project project = originalFile.getProject();

            int lastDot  = typed.lastIndexOf('.');
            String parentFqn = lastDot >= 0 ? typed.substring(0, lastDot) : "";
            String partial   = lastDot >= 0 ? typed.substring(lastDot + 1) : typed;

            CompletionResultSet segResult = result.withPrefixMatcher(
                    new PlainPrefixMatcher(typed, true));

            java.util.Set<com.intellij.openapi.vfs.VirtualFile> runtimeRoots =
                    Fxml2AddImportFix.buildProductionRuntimeRoots(originalFile);

            JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
            GlobalSearchScope allScope = GlobalSearchScope.allScope(project);

            if (parentFqn.isEmpty()) {
                // Offer root packages (javafx, java, org, ...).
                com.intellij.psi.PsiPackage root = facade.findPackage("");
                if (root != null) {
                    for (com.intellij.psi.PsiPackage sub : root.getSubPackages(allScope)) {
                        String pkgName = sub.getName();
                        if (pkgName == null) continue;
                        if (!partial.isEmpty() && !pkgName.startsWith(partial)) continue;
                        segResult.addElement(packageLookup(sub.getQualifiedName(), pkgName));
                    }
                }

                // Also offer imported class simple names (e.g. "Button" for <?import sample.app.Button?>).
                // Track the simple names offered here so the short-names-cache loop below can skip
                // duplicates without calling Fxml2ImportResolver.resolve(), which also resolves
                // java.lang.* classes via an implicit fallback and would cause those classes to be
                // silently dropped (they are not listed in any explicit import section above).
                Set<String> alreadyOfferedSimpleNames = new HashSet<>();
                for (String importFqn : Fxml2ImportResolver.parseImports(originalFile)) {
                    if (importFqn.endsWith(".*")) continue;
                    String simpleName = importFqn.substring(importFqn.lastIndexOf('.') + 1);
                    alreadyOfferedSimpleNames.add(simpleName);
                    if (!partial.isEmpty() && !simpleName.startsWith(partial)) continue;
                    PsiClass cls = facade.findClass(importFqn, allScope);
                    if (cls == null) cls = Fxml2ImportResolver.resolve(simpleName, originalFile);
                    if (cls == null) continue;
                    if (Fxml2AddImportFix.shouldSkipClass(cls, cls.getQualifiedName(), runtimeRoots, false)) continue;
                    int tier = Fxml2AddImportFix.candidateTier(cls);
                    LookupElement imported = LookupElementBuilder.create(cls, simpleName)
                            .withPresentableText(simpleName)
                            .withIcon(AllIcons.Nodes.Class)
                            .withTailText(" (" + importFqn + ")", true);
                    segResult.addElement(PrioritizedLookupElement.withPriority(imported, -tier));
                }

                // Short-names cache for classes not yet explicitly imported.
                // The lookup string is the simple name so that the prefix matcher (which
                // operates on the lookup string) can match it against the user's simple-name
                // prefix.  The insert handler adds the <?import?> PI so the inserted simple
                // name resolves correctly, but is omitted for classes that are already
                // accessible without an import (java.lang.* and wildcard-imported classes).
                if (!partial.isEmpty()) {
                    PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
                    for (String name : cache.getAllClassNames()) {
                        if (!name.startsWith(partial)) continue;
                        if (alreadyOfferedSimpleNames.contains(name)) continue;
                        for (PsiClass cls : cache.getClassesByName(name, allScope)) {
                            String fqn = cls.getQualifiedName();
                            if (Fxml2AddImportFix.shouldSkipClass(cls, fqn, runtimeRoots, false)) continue;
                            int tier = Fxml2AddImportFix.candidateTier(cls);
                            boolean needsImport = Fxml2ImportResolver.resolve(name, originalFile) == null;
                            LookupElement element = LookupElementBuilder.create(cls, name)
                                    .withPresentableText(name)
                                    .withIcon(AllIcons.Nodes.Class)
                                    .withTailText(" (" + fqn + ")", true)
                                    .withInsertHandler(needsImport ? new TypeArgImportInsertHandler(cls) : null);
                            segResult.addElement(PrioritizedLookupElement.withPriority(element, -tier));
                        }
                    }
                }
            } else {
                // Check if parentFqn is a class (for nested-class completion like "Outer.")
                PsiClass parentClass = facade.findClass(parentFqn, allScope);
                if (parentClass == null) {
                    parentClass = Fxml2ImportResolver.resolve(parentFqn, originalFile);
                }
                if (parentClass != null) {
                    // parentFqn is a class: offer its inner classes
                    for (PsiClass inner : parentClass.getInnerClasses()) {
                        String name = inner.getName();
                        if (name == null || (!partial.isEmpty() && !name.startsWith(partial))) continue;
                        addFilteredClassLookup(segResult, inner, inner.getQualifiedName(), name, runtimeRoots, false);
                    }
                } else {
                    // parentFqn is a package: use PsiPackage for O(pkg-size) enumeration
                    com.intellij.psi.PsiPackage pkg = facade.findPackage(parentFqn);
                    if (pkg != null) {
                        for (com.intellij.psi.PsiPackage sub : pkg.getSubPackages(allScope)) {
                            String name = sub.getName();
                            if (name == null) continue;
                            if (!partial.isEmpty() && !name.startsWith(partial)) continue;
                            segResult.addElement(packageLookup(sub.getQualifiedName(), name));
                        }
                        for (PsiClass cls : pkg.getClasses(allScope)) {
                            String name = cls.getName();
                            if (name == null || (!partial.isEmpty() && !name.startsWith(partial))) continue;
                            // checkUnusable=false: abstract classes are valid in FQN/import contexts.
                            addFilteredClassLookup(segResult, cls, cls.getQualifiedName(), name, runtimeRoots, false);
                        }
                    }
                }
            }

            segResult.stopHere();
        }

        /**
         * Applies the standard FXML class filter and, if the class passes, adds a
         * {@link #classLookup} element with priority to {@code result}.
         * Does nothing when {@code fqn} is {@code null}, the class is an annotation type,
         * is outside the runtime roots, is in an internal package, or is unusable in FXML.
         *
         * @param checkUnusable {@code true} to also apply {@link Fxml2AddImportFix#isUnusableInFxml};
         *                      pass {@code false} when the caller already knows the class is usable
         *                      (e.g. it came from the file's import list).
         */
        static void addFilteredClassLookup(
                @NotNull CompletionResultSet result,
                @NotNull PsiClass cls,
                @Nullable String fqn,
                @NotNull String displayName,
                @NotNull java.util.Set<com.intellij.openapi.vfs.VirtualFile> runtimeRoots,
                boolean checkUnusable) {
            if (Fxml2AddImportFix.shouldSkipClass(cls, fqn, runtimeRoots, checkUnusable)) return;
            int tier = Fxml2AddImportFix.candidateTier(cls);
            result.addElement(PrioritizedLookupElement.withPriority(
                    classLookup(cls, fqn, displayName), -tier));
        }


        /**
         * Lookup element for a package segment.
         * @param lookupString full FQN used for prefix matching (e.g. "javafx.scene.control")
         * @param displayName  segment shown to the user (e.g. "control")
         */
        private static @NotNull LookupElement packageLookup(@NotNull String lookupString,
                                                             @NotNull String displayName) {
            return LookupElementBuilder.create(lookupString)
                    .withPresentableText(displayName)
                    .withIcon(AllIcons.Nodes.Package)
                    .withTailText(" (package)", true);
        }

        /**
         * Lookup element for a class.
         * @param cls          PSI class
         * @param lookupString full FQN used for prefix matching
         * @param displayName  segment shown to the user (simple name)
         */
        private static @NotNull LookupElement classLookup(@NotNull PsiClass cls,
                                                           @NotNull String lookupString,
                                                           @NotNull String displayName) {
            return LookupElementBuilder.create(cls, lookupString)
                    .withPresentableText(displayName)
                    .withIcon(AllIcons.Nodes.Class)
                    .withTailText(" (" + lookupString + ")", true);
        }
    }

    // -----------------------------------------------------------------------
    // Attribute value completion
    // -----------------------------------------------------------------------

    private static final class AttributeValueCompletionProvider extends CompletionProvider<CompletionParameters> {

        @Override
        protected void addCompletions(@NotNull CompletionParameters parameters,
                                      @NotNull ProcessingContext context,
                                      @NotNull CompletionResultSet result) {
            PsiFile file = parameters.getPosition().getContainingFile();
            if (!(file instanceof XmlFile xmlFile)) return;
            if (!Fxml2FileType.isFxml2(xmlFile)) return;

            PsiElement pos = parameters.getPosition();
            XmlAttributeValue attrVal = findEnclosingAttributeValue(pos);
            if (attrVal == null) return;
            XmlAttribute attr = (attrVal.getParent() instanceof XmlAttribute a) ? a : null;
            if (attr == null) return;
            XmlTag tag = attr.getParent();
            if (tag == null) return;

            String rawValue = attrVal.getValue();
            // Truncate at the IntelliJ dummy completion identifier inserted at the caret.
            // Everything after the dummy is text that follows the cursor; we must not
            // include it, or the "lastDot" calculation will see segments that the user
            // hasn't typed yet and produce incorrect completions.
            String value = truncateAtDummy(rawValue).trim();

            // Check if this is a binding expression or markup extension invocation.
            // All open-brace expressions "{...}" are routed to addBindingPathCompletions,
            // which handles both intrinsic keywords and custom MarkupExtension classes.
            if (Fxml2BindingExpressionParser.looksLikeBindingExpression(value)) {
                addBindingPathCompletions(value, tag, attr, xmlFile, result);
                return;
            }

            // fx: intrinsic attribute values
            String attrLocalName = attr.getLocalName();
            String attrNs = attr.getNamespace();
            if (Fxml2ImportResolver.FXML2_NAMESPACE.equals(attrNs)) {
                switch (attrLocalName) {
                    case "classModifier" -> {
                        // valid values are "protected" and "package"
                        result.addElement(LookupElementBuilder.create("protected")
                                .withIcon(AllIcons.Nodes.KeymapOther)
                                .withTypeText("fx:classModifier"));
                        result.addElement(LookupElementBuilder.create("package")
                                .withIcon(AllIcons.Nodes.KeymapOther)
                                .withTypeText("fx:classModifier"));
                        return;
                    }
                    case "factory" -> {
                        // Offer public static no-arg methods from the tag's class.
                        XmlTag tagEl = attr.getParent();
                        if (tagEl != null && tagEl.getContainingFile() instanceof XmlFile xf) {
                            PsiClass tagClass = Fxml2ImportResolver.resolve(tagEl.getLocalName(), xf);
                            if (tagClass != null) {
                                addFactoryMethodCompletions(tagClass, result);
                            }
                        }
                        return;
                    }
                    default -> { /* fall through to literal completions */ }
                }
            }

            // Otherwise offer enum/field constants for the property type
            addLiteralValueCompletions(attr, tag, value, result);
        }

        // ----- Binding path completions -----

    private static void addBindingPathCompletions(
             @NotNull String rawValue,
             @NotNull XmlTag tag,
             @Nullable XmlAttribute bindingAttr,
             @NotNull XmlFile xmlFile,
             @NotNull CompletionResultSet result) {

        // Determine the target property type from the attribute descriptor,
        // used for type-compatibility filtering of static-field completions.
        PsiType targetPropType = null;
        if (bindingAttr != null) {
            XmlAttributeDescriptor desc = bindingAttr.getDescriptor();
            if (desc instanceof Fxml2StaticPropertyAttributeDescriptor staticDesc) {
                PsiClass declaringClass = staticDesc.getDeclaringClass();
                if (declaringClass != null) {
                    targetPropType = Fxml2AttributeValueResolver.staticPropertyType(
                            declaringClass, Fxml2ImportUtil.simpleNameOf(bindingAttr.getLocalName()));
                }
            } else if (desc instanceof Fxml2PropertyAttributeDescriptor propDesc) {
                // Use the owner class from the property attribute descriptor directly
                PsiClass ownerClass = propDesc.getOwnerClass();
                if (ownerClass != null) {
                    targetPropType = Fxml2AttributeValueResolver.propertyType(
                            ownerClass, bindingAttr.getLocalName(), List.of());
                }
            } else if (tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd) {
                PsiClass ownerClass = cd.getPsiClass();
                if (ownerClass != null) {
                    List<String> siblings = new ArrayList<>();
                    for (XmlAttribute a : tag.getAttributes()) siblings.add(a.getLocalName());
                    targetPropType = Fxml2AttributeValueResolver.propertyType(
                            ownerClass, bindingAttr.getLocalName(), siblings);
                }
            }
        }
      
  

            // -- {...} extension-name / intrinsic-keyword completion ------------------
            // When the user has typed "{" (or more, like "{fx", "{fx:", "{fx:Sy",
            // "{MyExt") without any whitespace yet, offer the built-in fx: intrinsics
            // AND imported MarkupExtension classes so the user can pick what goes here.
            //
            // Examples:
            //   "{"      -> offer all: Evaluate, Observe, Synchronize, Null, Type + ext classes
            //   "{}"     -> same (auto-closed brace from editor, empty inner content)
            //   "{fx"    -> only items starting with "fx"
            //   "{fx:"   -> all fx: intrinsics (Evaluate, Observe, Synchronize, Null, Type)
            //   "{fx:Sy" -> Synchronize only
            //   "{MyExt" -> MarkupExtension classes starting with "MyExt"
            if (rawValue.startsWith("{")) {
                // Extract inner content: strip leading "{" and optional trailing "}"
                String inner = rawValue.endsWith("}")
                        ? rawValue.substring(1, rawValue.length() - 1)   // "" / "fx:" / "fx:Sy" / "MyExt"
                        : rawValue.substring(1);                          // "" / "fx:" / "fx:Sy" / "MyExt"
                boolean hasWhitespace = false;
                int firstWhitespaceIdx = -1;
                for (int i = 0; i < inner.length(); i++) {
                    if (Character.isWhitespace(inner.charAt(i))) {
                        hasWhitespace = true;
                        firstWhitespaceIdx = i;
                        break;
                    }
                }
                if (!hasWhitespace) {
                    // "inner" is what the user typed between "{" and the caret.
                    // For "{}" it is "" (empty); for "{fx:Sy}" it is "fx:Sy".
                    // The prefix in the document that will be replaced on selection:
                    // everything before the trailing "}" (which stays in place).
                    String fullPrefix = rawValue.endsWith("}")
                            ? rawValue.substring(0, rawValue.length() - 1)  // "{"  / "{fx:" / "{fx:Sy"
                            : rawValue;                                       // "{"  / "{fx:" / "{fx:Sy"
                    CompletionResultSet kwResult = result.withPrefixMatcher(
                            new PlainPrefixMatcher(fullPrefix, true));

                    // -- Built-in fx: intrinsics -----------------------------------
                    // Binding intrinsics that take a path argument
                    for (String kw : List.of("Evaluate", "Observe", "Push", "Synchronize")) {
                        if (("fx:" + kw).startsWith(inner)) {
                            kwResult.addElement(LookupElementBuilder
                                    .create("{fx:" + kw)
                                    .withPresentableText("fx:" + kw)
                                    .withIcon(AllIcons.Nodes.KeymapOther)
                                    .withTypeText("fx: binding"));
                        }
                    }
                    // Intrinsics without a binding path
                    if ("fx:Null".startsWith(inner)) {
                        // When the document already has a closing "}", don't add another one.
                        String nullInsert = rawValue.endsWith("}") ? "{fx:Null" : "{fx:Null}";
                        kwResult.addElement(LookupElementBuilder
                                .create(nullInsert)
                                .withPresentableText("fx:Null")
                                .withIcon(AllIcons.Nodes.KeymapOther)
                                .withTypeText("fx: intrinsic"));
                    }
                    if ("fx:True".startsWith(inner)) {
                        String trueInsert = rawValue.endsWith("}") ? "{fx:True" : "{fx:True}";
                        kwResult.addElement(LookupElementBuilder
                                .create(trueInsert)
                                .withPresentableText("fx:True")
                                .withIcon(AllIcons.Nodes.KeymapOther)
                                .withTypeText("fx: intrinsic"));
                    }
                    if ("fx:False".startsWith(inner)) {
                        String falseInsert = rawValue.endsWith("}") ? "{fx:False" : "{fx:False}";
                        kwResult.addElement(LookupElementBuilder
                                .create(falseInsert)
                                .withPresentableText("fx:False")
                                .withIcon(AllIcons.Nodes.KeymapOther)
                                .withTypeText("fx: intrinsic"));
                    }
                    if ("fx:Class".startsWith(inner)) {
                        kwResult.addElement(LookupElementBuilder
                                .create("{fx:Class")
                                .withPresentableText("fx:Class")
                                .withIcon(AllIcons.Nodes.KeymapOther)
                                .withTypeText("fx: intrinsic"));
                    }

                    // -- Imported MarkupExtension classes -------------------------
                    addMarkupExtensionClassCompletions(inner, xmlFile, kwResult);

                    return;
                }

                // hasWhitespace == true: the cursor is somewhere in the params section of a
                // markup extension invocation (e.g. "{ColumnCellFactory $Row.titleGet}").
                // If there is a '$' after the first whitespace, the user is completing a
                // binding sub-expression inside the params; delegate to the standard
                // binding-path completion with just that sub-expression.
                int lastDollarInner = inner.lastIndexOf('$');
                if (lastDollarInner > firstWhitespaceIdx) {
                    String bindingSubExpr = inner.substring(lastDollarInner);
                    addBindingPathCompletions(bindingSubExpr, tag, null, xmlFile, result);
                    return;
                }
            }

            Fxml2BindingExpressionParser.ParsedExpression expr =
                    Fxml2BindingExpressionParser.parseExpression(rawValue);

            // For completion purposes, fall back to extracting the path even when the
            // expression is syntactically incomplete (e.g. "${}" or "${}").
            // We strip well-known prefixes and work with whatever partial path remains.
            String strippedPath;
            if (expr != null) {
                strippedPath = expr.strippedPath();
            } else {
                String partial = Fxml2BindingExpressionParser.extractPartialPath(rawValue);
                strippedPath = partial != null ? partial : "";
            }

            // Parse context selector (self/, parent/, etc.) from the stripped path
            Fxml2BindingExpressionParser.ContextSelector selector =
                    Fxml2BindingExpressionParser.parseContextSelector(strippedPath);

            // Offer context selectors when the user hasn't typed one yet and the partial
            // text could match "self" or "parent".
            if (selector == null) {
                CompletionResultSet selectorResult = result.withPrefixMatcher(
                        new PlainPrefixMatcher(strippedPath, true));
                if ("self".startsWith(strippedPath)) {
                    selectorResult.addElement(LookupElementBuilder.create("self/")
                            .withPresentableText("self/")
                            .withIcon(AllIcons.Nodes.Unknown)
                            .withTypeText("context selector"));
                }
                if ("parent".startsWith(strippedPath)) {
                    selectorResult.addElement(LookupElementBuilder.create("parent/")
                            .withPresentableText("parent/")
                            .withIcon(AllIcons.Nodes.Unknown)
                            .withTypeText("context selector"));
                }
            }

            // The actual property path is what follows the selector
            String propertyPath = selector != null ? selector.remainingPath() : strippedPath;

            // --- Attached-property completion: (ClassName.partial<caret>) ---
            // When the user is typing inside an attached-property group "(ClassName.propPart)",
            // the opening '(' has not been closed yet (the closing ')' is past the caret or absent).
            // Offer static property names of ClassName so that e.g. "(VBox.mar" -> "margin".
            int lastOpenParen = propertyPath.lastIndexOf('(');
            int lastCloseParen = propertyPath.lastIndexOf(')');
            if (lastOpenParen >= 0 && lastOpenParen > lastCloseParen) {
                String attachedContent = propertyPath.substring(lastOpenParen + 1); // "VBox.mar"
                int innerDot = attachedContent.lastIndexOf('.');
                if (innerDot >= 0) {
                    String className = attachedContent.substring(0, innerDot).trim();
                    String propPartial = attachedContent.substring(innerDot + 1);
                    GlobalSearchScope attachedScope = xmlFile.getResolveScope();
                    PsiClass attachedClass = Fxml2ImportResolver.resolve(className, xmlFile);
                    if (attachedClass == null) {
                        attachedClass = JavaPsiFacade.getInstance(xmlFile.getProject())
                                .findClass(className, attachedScope);
                    }
                    if (attachedClass != null) {
                        CompletionResultSet attachedResult = result.withPrefixMatcher(
                                new PlainPrefixMatcher(propPartial, true));
                        for (String propName : Fxml2PropertyResolver.getAllStaticPropertyNames(attachedClass)) {
                            if (propName.startsWith(propPartial)) {
                                attachedResult.addElement(LookupElementBuilder.create(propName)
                                        .withIcon(AllIcons.Nodes.Property)
                                        .withTypeText(attachedClass.getName()));
                            }
                        }
                    }
                }
                return;
            }

            // Determine the last complete segment and the partial name being typed.
            // Both '.' and '::' are valid path separators; '::' is the observable-selection
            // operator, which keeps the raw property type (e.g. ListProperty) instead of
            // unwrapping it to the contained value type (e.g. ObservableList).
            int lastDot = propertyPath.lastIndexOf('.');
            int lastObsSel = propertyPath.lastIndexOf("::");
            boolean lastSepIsObservableSel = lastObsSel >= 0 && lastObsSel > lastDot;
            int lastSep = lastSepIsObservableSel ? lastObsSel : lastDot;
            String completedPrefix = lastSep >= 0 ? propertyPath.substring(0, lastSep) : "";
            String partialName = lastSep < 0 ? propertyPath
                    : lastSepIsObservableSel ? propertyPath.substring(lastSep + 2)
                    : propertyPath.substring(lastSep + 1);

            GlobalSearchScope scope = xmlFile.getResolveScope();

            // Resolve start class using context selector
            PsiClass startClass = Fxml2BindingPathResolver.resolveStartClass(selector, tag, xmlFile);

            // Walk completed prefix segments to find the current class.
            // Fast path: if completedPrefix resolves directly as a FQN or imported class name,
            // use it immediately as a class qualifier without calling resolve().
            PsiClass currentClass = startClass;
            boolean lastIsClassQualifier = false;
            if (!completedPrefix.isEmpty()) {
                PsiClass directClass = JavaPsiFacade.getInstance(xmlFile.getProject())
                        .findClass(completedPrefix, scope);
                if (directClass == null) {
                    directClass = Fxml2ImportResolver.resolve(completedPrefix, xmlFile);
                }
                if (directClass == null && !completedPrefix.contains(".")) {
                    // Last resort: look up by short name (handles non-imported classes like $Row.field).
                    // Only use an unambiguous single-class result to avoid false positives.
                    PsiClass[] byShortName = PsiShortNamesCache.getInstance(xmlFile.getProject())
                            .getClassesByName(completedPrefix, scope);
                    if (byShortName.length == 1) {
                        directClass = byShortName[0];
                    }
                }
                if (directClass != null) {
                    currentClass = directClass;
                    lastIsClassQualifier = true;
                } else {
                    // completedPrefix is not a class: check if it's a package name (or a
                    // partial package segment, e.g. "javafx.scene" when typing "javafx.scene.lay").
                    // Enumerate the package's sub-packages and classes using a prefix matcher
                    // on the full propertyPath (same technique as completeFqn) so that:
                    //   * insertion replaces the typed prefix correctly (no duplication)
                    //   * partial segment filtering works (e.g. "lay" matches "layout")
                    // Note: isUnusableInFxml is NOT applied here because the user is typing a
                    // class-qualifier for static field access (e.g. Region.USE_PREF_SIZE), not
                    // trying to instantiate the class: abstract classes are valid qualifiers.
                    JavaPsiFacade facade = JavaPsiFacade.getInstance(xmlFile.getProject());
                    GlobalSearchScope allScope = GlobalSearchScope.allScope(xmlFile.getProject());
                    com.intellij.psi.PsiPackage pkg = facade.findPackage(completedPrefix);
                    if (pkg != null) {
                        java.util.Set<com.intellij.openapi.vfs.VirtualFile> runtimeRoots =
                                Fxml2AddImportFix.buildProductionRuntimeRoots(xmlFile);
                        CompletionResultSet pkgResult = result.withPrefixMatcher(
                                new PlainPrefixMatcher(propertyPath, true));
                        for (com.intellij.psi.PsiPackage sub : pkg.getSubPackages(allScope)) {
                            String name = sub.getName();
                            if (name == null) continue;
                            if (!partialName.isEmpty() && !name.startsWith(partialName)) continue;
                            pkgResult.addElement(FqnClassNameCompletionProvider.packageLookup(
                                    sub.getQualifiedName(), name));
                        }
                        for (PsiClass cls : pkg.getClasses(allScope)) {
                            String name = cls.getName();
                            if (name == null || (!partialName.isEmpty() && !name.startsWith(partialName))) continue;
                            FqnClassNameCompletionProvider.addFilteredClassLookup(
                                    pkgResult, cls, cls.getQualifiedName(), name, runtimeRoots, true);
                        }
                        return;
                    }

                    // Not a package either: try walking as a property chain on the code-behind class.
                    if (startClass != null) {
                        List<Fxml2BindingPathResolver.Segment> segments =
                                Fxml2BindingPathResolver.resolve(completedPrefix, startClass, scope, null, xmlFile);
                        if (segments.isEmpty()) return;
                        Fxml2BindingPathResolver.Segment lastSeg = segments.getLast();
                        // When '::' was the last separator, keep the raw property type (e.g.
                        // ListProperty<String>) rather than its unwrapped value type (ObservableList<String>),
                        // so that members of the observable/property object itself are offered.
                        if (lastSepIsObservableSel && lastSeg.declaration() != null) {
                            PsiClass contextForRaw = segments.size() >= 2
                                    ? segments.get(segments.size() - 2).resultType()
                                    : startClass;
                            if (contextForRaw == null) contextForRaw = startClass;
                            currentClass = Fxml2BindingPathResolver.propertyTypeRaw(
                                    lastSeg.declaration(), contextForRaw);
                        } else {
                            currentClass = lastSeg.resultType();
                        }
                        if (currentClass == null) return;
                        lastIsClassQualifier = lastSeg.classQualifier();
                    } else {
                        return;
                    }
                }
            } else if (startClass == null) {
                // No resolvable start class, but at the first-segment root-context level
                // we can still offer fx:id-declared elements, since the FXML compiler will
                // inject them into the base class once the project is built.
                if (selector == null || selector.isThis()) {
                    CompletionResultSet fxIdPrefixResult = result.withPrefixMatcher(
                            new PlainPrefixMatcher(partialName, true));
                    addFxIdCompletions(xmlFile, partialName, fxIdPrefixResult);
                }
                return;
            }

            // Override the prefix matcher with a plain starts-with matcher using the clean
            // partialName.  The default IntelliJ prefix includes the completion dummy suffix
            // ("IntellijIdeaRulezzz"), which would filter out our items.
            CompletionResultSet prefixResult = result.withPrefixMatcher(
                    new PlainPrefixMatcher(partialName, true));

            if (lastIsClassQualifier) {
                // The prefix resolved to a class name (e.g. "javafx.scene.layout.Region").
                // The user is now completing a static field on that class.
                for (PsiField field : currentClass.getAllFields()) {
                    if (!field.hasModifierProperty(PsiModifier.PUBLIC)) continue;
                    if (!field.hasModifierProperty(PsiModifier.STATIC)) continue;
                    if (targetPropType != null
                            && !Fxml2AttributeValueResolver.isTypeCompatible(targetPropType, field.getType())) continue;
                    if (field.getName().startsWith(partialName)) {
                        prefixResult.addElement(LookupElementBuilder
                                .create(field, field.getName())
                                .withIcon(AllIcons.Nodes.Field)
                                .withTypeText(currentClass.getName()));
                    }
                }
            } else {
                // Offer all instance property names on currentClass matching the partial name
                for (String propName : Fxml2PropertyResolver.getAllPropertyNames(currentClass)) {
                    if (propName.startsWith(partialName)) {
                        LookupElementBuilder elem = LookupElementBuilder
                                .create(propName)
                                .withIcon(AllIcons.Nodes.Property)
                                .withTypeText(getPropertyTypeText(currentClass, propName));
                        prefixResult.addElement(elem);
                    }
                }

                // At the first segment level of the root / code-behind context, also
                // offer elements declared with fx:id anywhere in the file.  The FXML
                // compiler injects a public field for each fx:id into the generated
                // base class, so these names are valid binding sources.
                // We offer them here (in addition to the code-behind's instance
                // properties above) because the code-behind base class may not yet
                // have been compiled (missing fx:id fields), and the user's intent
                // is clear from the XML structure alone.
                if (completedPrefix.isEmpty() && (selector == null || selector.isThis())) {
                    addFxIdCompletions(xmlFile, partialName, prefixResult);
                }

                // Root-tag class fallback: when the FXML compiler-generated base class is absent
                // (stale build), the code-behind's supertype chain does not include the root-element
                // type. The compiler always generates "class FooBase extends <rootTagType>", making
                // the root-tag class an effective supertype of the code-behind. Mirror this here so
                // that root-element properties appear in completion even before the project is built.
                // Only applied at the first segment level and when fx:context is not explicitly set.
                if (completedPrefix.isEmpty() && (selector == null || selector.isThis())
                        && Fxml2BindingPathResolver.resolveContextClass(xmlFile) == null) {
                    PsiClass rootTagClass = Fxml2BindingPathResolver.resolveRootTagClass(xmlFile);
                    if (rootTagClass != null && !rootTagClass.equals(currentClass)
                            && !currentClass.isInheritor(rootTagClass, true)) {
                        for (String propName : Fxml2PropertyResolver.getAllPropertyNames(rootTagClass)) {
                            if (propName.startsWith(partialName)) {
                                prefixResult.addElement(LookupElementBuilder.create(propName)
                                        .withIcon(AllIcons.Nodes.Property)
                                        .withTypeText(rootTagClass.getName()));
                            }
                        }
                        for (PsiField field : rootTagClass.getAllFields()) {
                            if (!field.hasModifierProperty(PsiModifier.PUBLIC)) continue;
                            if (!field.hasModifierProperty(PsiModifier.STATIC)) continue;
                            if (targetPropType != null
                                    && !Fxml2AttributeValueResolver.isTypeCompatible(targetPropType, field.getType())) continue;
                            if (field.getName().startsWith(partialName)) {
                                prefixResult.addElement(LookupElementBuilder
                                        .create(field, field.getName())
                                        .withIcon(AllIcons.Nodes.Field)
                                        .withTypeText(rootTagClass.getName()));
                            }
                        }
                    }
                }
            }
        }

        /**
         * Scans the XML document for all tags that carry an {@code fx:id} attribute and
         * adds a completion item for each whose value starts with {@code partialName}.
         *
         * <p>The lookup string is the fx:id value (e.g. {@code "myButton3"}), the type
         * text is the element's tag class name (e.g. {@code "Button"}), and the icon is
         * the field icon, matching how the FXML compiler exposes these as injected
         * fields in the generated base class.
         *
         * @param xmlFile     the FXML file to scan
         * @param partialName the partial name typed so far; only names starting with this
         *                    prefix are offered (empty string means all)
         * @param result      the completion result set to add items to
         */
        private static void addFxIdCompletions(
                @NotNull XmlFile xmlFile,
                @NotNull String partialName,
                @NotNull CompletionResultSet result) {
            var doc = xmlFile.getDocument();
            if (doc == null) return;
            XmlTag root = doc.getRootTag();
            if (root == null) return;
            collectFxIdInTag(root, partialName, result);
        }

        private static void collectFxIdInTag(
                @NotNull XmlTag tag,
                @NotNull String partialName,
                @NotNull CompletionResultSet result) {
            XmlAttribute idAttr = tag.getAttribute("fx:id");
            if (idAttr != null) {
                String name = idAttr.getValue();
                if (name != null && !name.isBlank() && name.startsWith(partialName)) {
                    String typeText = null;
                    if (tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd) {
                        PsiClass tagClass = cd.getPsiClass();
                        if (tagClass != null) typeText = tagClass.getName();
                    }
                    result.addElement(LookupElementBuilder.create(name)
                            .withIcon(AllIcons.Nodes.Field)
                            .withTypeText(typeText));
                }
            }
            for (XmlTag child : tag.getSubTags()) {
                collectFxIdInTag(child, partialName, result);
            }
        }

        /**
         * Offers completion items for every {@code <?import?>}-declared class that
         * (a) implements {@code org.jfxcore.markup.MarkupExtension}, and
         * (b) has a simple name that starts with {@code partial}.
         *
         * <p>Wildcard imports ({@code foo.*}) are skipped because resolving them would
         * require scanning the full package, which is too expensive during completion.
         *
         * <p>Each item is inserted as {@code "{ClassName "} (with a trailing space so
         * the user can continue typing constructor parameters), except when
         * {@code partial} already starts with {@code "fx:"}: in that case no class
         * names are expected to match and the method returns immediately.
         */
        private static void addMarkupExtensionClassCompletions(
                @NotNull String partial,
                @NotNull XmlFile xmlFile,
                @NotNull CompletionResultSet result) {
            // "fx:" prefix is exclusively for built-in intrinsics handled earlier
            if (partial.startsWith("fx:")) return;

            String markupExtFqn = "org.jfxcore.markup.MarkupExtension";
            GlobalSearchScope allScope = GlobalSearchScope.allScope(xmlFile.getProject());
            PsiClass markupExtClass = JavaPsiFacade.getInstance(xmlFile.getProject())
                    .findClass(markupExtFqn, allScope);

            // Collect already-imported simple names to avoid offering duplicates
            Set<String> importedSimpleNames = new HashSet<>();
            for (String imp : Fxml2ImportResolver.parseImports(xmlFile)) {
                if (!imp.endsWith(".*")) {
                    importedSimpleNames.add(imp.substring(imp.lastIndexOf('.') + 1));
                }
            }

            // 1. Already-imported MarkupExtension classes (no auto-import needed)
            for (String imp : Fxml2ImportResolver.parseImports(xmlFile)) {
                if (imp.endsWith(".*")) continue;
                String simpleName = imp.contains(".")
                        ? imp.substring(imp.lastIndexOf('.') + 1)
                        : imp;
                if (!simpleName.startsWith(partial)) continue;
                PsiClass cls = Fxml2ImportResolver.resolve(simpleName, xmlFile);
                if (cls == null) continue;
                if (markupExtClass != null && !cls.isInheritor(markupExtClass, true)) continue;
                result.addElement(LookupElementBuilder
                        .create("{" + simpleName + " ")
                        .withPresentableText(simpleName)
                        .withIcon(AllIcons.Nodes.Class)
                        .withTypeText("MarkupExtension"));
            }

            // 2. Built-in extensions from org.jfxcore.markup.resource (auto-import if not imported)
            if (markupExtClass != null) {
                PsiPackage resourcePkg = JavaPsiFacade.getInstance(xmlFile.getProject())
                        .findPackage("org.jfxcore.markup.resource");
                if (resourcePkg != null) {
                    for (PsiClass cls : resourcePkg.getClasses(allScope)) {
                        String simpleName = cls.getName();
                        if (simpleName == null) continue;
                        if (!simpleName.startsWith(partial)) continue;
                        if (importedSimpleNames.contains(simpleName)) continue; // already in loop 1
                        if (!cls.isInheritor(markupExtClass, true)) continue;
                        String fqn = cls.getQualifiedName();
                        result.addElement(LookupElementBuilder
                                .create("{" + simpleName + " ")
                                .withPresentableText(simpleName)
                                .withIcon(AllIcons.Nodes.Class)
                                .withTypeText("MarkupExtension")
                                .withTailText(fqn != null ? " (" + fqn + ")" : "", true)
                                .withInsertHandler(new MarkupExtensionImportInsertHandler(cls)));
                    }
                }

                // 3. Other project/classpath MarkupExtension classes: only when prefix typed
                //    to avoid enumerating every class in the project for the empty-prefix case.
                if (!partial.isEmpty()) {
                    PsiShortNamesCache cache = PsiShortNamesCache.getInstance(xmlFile.getProject());
                    Set<VirtualFile> runtimeRoots = Fxml2AddImportFix.buildProductionRuntimeRoots(xmlFile);
                    for (String name : cache.getAllClassNames()) {
                        if (!name.startsWith(partial)) continue;
                        if (importedSimpleNames.contains(name)) continue;
                        for (PsiClass cls : cache.getClassesByName(name, allScope)) {
                            if (!cls.isInheritor(markupExtClass, true)) continue;
                            String fqn = cls.getQualifiedName();
                            // Avoid duplicates with the resource-package loop above
                            if (fqn != null && fqn.startsWith("org.jfxcore.markup.resource.")) continue;
                            if (Fxml2AddImportFix.shouldSkipClass(cls, fqn, runtimeRoots, false)) continue;
                            result.addElement(LookupElementBuilder
                                    .create("{" + name + " ")
                                    .withPresentableText(name)
                                    .withIcon(AllIcons.Nodes.Class)
                                    .withTypeText("MarkupExtension")
                                    .withTailText(" (" + fqn + ")", true)
                                    .withInsertHandler(new MarkupExtensionImportInsertHandler(cls)));
                        }
                    }
                }
            }
        }

        private static @Nullable String getPropertyTypeText(@NotNull PsiClass cls, @NotNull String propName) {
            PsiElement decl = Fxml2PropertyResolver.resolveInstanceProperty(cls, propName);
            if (decl == null) return null;
            PsiClass type = Fxml2PropertyResolver.resolvePropertyType(decl);
            return type != null ? type.getName() : null;
        }

        // ----- Literal value completions -----

        private static void addLiteralValueCompletions(
                @NotNull XmlAttribute attr,
                @NotNull XmlTag tag,
                @NotNull String typedPrefix,
                @NotNull CompletionResultSet result) {

            String attrName = attr.getLocalName();

            PsiType propType;
            PsiClass ownerClass;

            // Determine property type from the attribute descriptor
            XmlAttributeDescriptor descriptor = attr.getDescriptor();
            if (descriptor instanceof Fxml2StaticPropertyAttributeDescriptor staticDesc) {
                // Static property: get the second parameter type of the static setter
                PsiClass declaringClass = staticDesc.getDeclaringClass();
                if (declaringClass == null) return;
                propType = Fxml2AttributeValueResolver.staticPropertyType(declaringClass, Fxml2ImportUtil.simpleNameOf(attrName));
                ownerClass = declaringClass;
            } else {
                // Instance property
                ownerClass = tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd ? cd.getPsiClass() : null;
                if (ownerClass == null) return;

                // For dotted names (e.g. "selectionModel.selectionMode") walk the property
                // chain to reach the owner class of the terminal segment.
                String terminalPropName = attrName;
                PsiClass terminalOwner = ownerClass;
                int lastDot = attrName.lastIndexOf('.');
                if (lastDot > 0) {
                    String[] segments = attrName.split("\\.", -1);
                    for (int i = 0; i < segments.length - 1; i++) {
                        PsiElement decl = Fxml2PropertyResolver.resolveInstanceProperty(terminalOwner, segments[i]);
                        if (decl == null) return;
                        terminalOwner = Fxml2PropertyResolver.resolvePropertyType(decl);
                        if (terminalOwner == null) return;
                    }
                    terminalPropName = segments[segments.length - 1];
                }

                List<String> siblings = new ArrayList<>();
                for (XmlAttribute a : tag.getAttributes()) siblings.add(a.getLocalName());
                propType = Fxml2AttributeValueResolver.propertyType(terminalOwner, terminalPropName, siblings);
                ownerClass = terminalOwner;
            }

            if (propType == null) return;

            // Apply the fx:typeArguments substitutor so that Class<T> resolves to the concrete
            // type (e.g. Class<String> when fx:typeArguments="String" is present).
            if (tag.getContainingFile() instanceof XmlFile tagXmlFile) {
                com.intellij.psi.PsiSubstitutor tagSubst =
                        Fxml2AttributeValueResolver.buildTagTypeSubstitutor(ownerClass, tag, tagXmlFile);
                if (!tagSubst.getSubstitutionMap().isEmpty()) {
                    PsiType substituted = tagSubst.substitute(propType);
                    if (substituted != null) propType = substituted;
                }
            }

            // Resolve enum/static field candidates.
            // propClass is null for primitive types (double, int, ...): those have no PsiClass.
            PsiClass propClass = PsiUtil.resolveClassInType(propType);

            // Class<T> property: offer imported class names that are assignable to the type argument.
            if (propClass != null && "java.lang.Class".equals(propClass.getQualifiedName())) {
                if (tag.getContainingFile() instanceof XmlFile xmlFile) {
                    addClassLiteralCompletions(propType, ownerClass.getResolveScope(), xmlFile, typedPrefix, result);
                }
                return;
            }

            if (propClass != null && propClass.isEnum()) {
                // Offer all enum constants
                for (PsiField field : propClass.getFields()) {
                    if (field.hasModifierProperty(PsiModifier.STATIC)) {
                        result.addElement(LookupElementBuilder.create(field, field.getName())
                                .withIcon(AllIcons.Nodes.Enum)
                                .withTypeText(propClass.getName()));
                    }
                }
            } else {
                // Offer public static fields that are assignment-compatible (e.g. USE_PREF_SIZE,
                // USE_COMPUTED_SIZE on Region for a double-typed property like minHeight).
                // Search on the owner class (which includes supertypes like Region) and on the
                // property type class (or its boxed form for primitives, e.g. Double).
                PsiClass searchPropClass = propClass;
                String canonicalType = propType.getCanonicalText();
                String boxedFqn = Fxml2AttributeValueResolver.boxedFqn(canonicalType);
                if (searchPropClass == null && boxedFqn != null) {
                    // Primitive: resolve the boxed class (double -> Double) so we also find
                    // constants like Double.POSITIVE_INFINITY.
                    GlobalSearchScope scope = ownerClass.getResolveScope();
                    searchPropClass = JavaPsiFacade.getInstance(ownerClass.getProject())
                            .findClass(boxedFqn, scope);
                }

                // For boolean/Boolean, add "true" and "false" as literal completions.
                boolean isBooleanType = "boolean".equals(canonicalType)
                        || "java.lang.Boolean".equals(canonicalType);
                if (isBooleanType) {
                    result.addElement(LookupElementBuilder.create("true")
                            .withIcon(AllIcons.Nodes.Field)
                            .withTypeText("boolean"));
                    result.addElement(LookupElementBuilder.create("false")
                            .withIcon(AllIcons.Nodes.Field)
                            .withTypeText("boolean"));
                }

                // Use a display-type name for the lookup element (e.g. "double" for primitives).
                String typeText = propClass != null
                        ? java.util.Objects.requireNonNullElse(propClass.getName(), propType.getPresentableText())
                        : propType.getPresentableText();
                addStaticFieldsForType(ownerClass, propType, typeText, result);
                if (searchPropClass != null) {
                    addStaticFieldsForType(searchPropClass, propType, typeText, result);
                }
            }
        }

        /**
         * Offers public static no-arg methods on {@code tagClass} (and its supertypes)
         * as completion candidates for the {@code fx:factory} attribute value.
         *
         * <p>The FXML compiler uses the factory method to obtain the instance instead of
         * calling a constructor, so the method must return an instance of (or assignable to)
         * the declaring class. We simply offer all public static no-arg methods and let the
         * developer pick the right one.
         */
        private static void addFactoryMethodCompletions(
                @NotNull PsiClass tagClass,
                @NotNull CompletionResultSet result) {
            for (PsiMethod method : tagClass.getAllMethods()) {
                if (!method.hasModifierProperty(PsiModifier.PUBLIC)) continue;
                if (!method.hasModifierProperty(PsiModifier.STATIC)) continue;
                if (method.getParameterList().isEmpty()) {
                    String name = method.getName();
                    result.addElement(LookupElementBuilder.create(name)
                            .withIcon(AllIcons.Nodes.Method)
                            .withTypeText(tagClass.getName()));
                }
            }
        }

        /**
         * Offers class names from the file's {@code <?import?>} directives (and the type argument
         * class itself, which may come from {@code java.lang.*} and not appear in explicit imports)
         * as completion candidates for a {@code Class<T>} property.  Candidates are filtered to
         * those assignable to the type argument {@code T}.
         *
         * <p>When {@code typedPrefix} is non-empty, also offers non-imported project/library
         * classes whose simple name starts with the prefix and is assignable to the type
         * argument; selecting one auto-inserts an {@code <?import?>} PI.
         */
        private static void addClassLiteralCompletions(
                @NotNull PsiType classType,
                @NotNull GlobalSearchScope scope,
                @NotNull XmlFile xmlFile,
                @NotNull String typedPrefix,
                @NotNull CompletionResultSet result) {
            Set<String> added = new HashSet<>();
            // When the type argument is a concrete class (not a wildcard or type parameter),
            // offer it by simple name directly.  This covers implicitly available classes
            // such as java.lang.String that are not listed in explicit <?import?> directives.
            if (classType instanceof PsiClassType ct) {
                PsiType[] params = ct.getParameters();
                if (params.length > 0 && params[0] instanceof PsiClassType typeArgCt) {
                    PsiClass typeArgClass = typeArgCt.resolve();
                    if (typeArgClass != null && !(typeArgClass instanceof PsiTypeParameter)) {
                        String simpleName = typeArgClass.getName();
                        if (simpleName != null
                                && Fxml2ImportResolver.resolve(simpleName, xmlFile) != null) {
                            result.addElement(LookupElementBuilder.create(typeArgClass, simpleName)
                                    .withIcon(AllIcons.Nodes.Class)
                                    .withTypeText("Class"));
                            added.add(simpleName);
                        }
                    }
                }
            }
            // Offer explicitly-imported classes filtered by type-argument assignability.
            List<String> imports = Fxml2ImportResolver.parseImports(xmlFile);
            for (String importEntry : imports) {
                // Wildcard imports (e.g. "javafx.scene.*"): skip, too many candidates.
                if (importEntry.endsWith(".*")) continue;
                JavaPsiFacade facade = JavaPsiFacade.getInstance(xmlFile.getProject());
                PsiClass cls = Fxml2ImportResolver.resolve(importEntry, xmlFile);
                if (cls == null) cls = facade.findClass(importEntry, scope);
                if (cls == null) continue;
                String simpleName = cls.getName();
                if (simpleName == null || added.contains(simpleName)) continue;
                // Filter by assignability to the type argument.
                PsiClass resolved = Fxml2AttributeValueResolver.resolveClassLiteralRef(
                        simpleName, classType, xmlFile, scope);
                if (resolved == null) continue;
                result.addElement(LookupElementBuilder.create(cls, simpleName)
                        .withIcon(AllIcons.Nodes.Class)
                        .withTypeText("Class"));
                added.add(simpleName);
            }

            // Non-imported classes (auto-import on selection): only when a prefix is typed,
            // to avoid enumerating every class in the project for the empty-prefix case.
            if (!typedPrefix.isEmpty()) {
                Project project = xmlFile.getProject();
                PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
                GlobalSearchScope allScope = GlobalSearchScope.allScope(project);
                Set<VirtualFile> runtimeRoots =
                        Fxml2AddImportFix.buildProductionRuntimeRoots(xmlFile);
                for (String name : cache.getAllClassNames()) {
                    if (!name.startsWith(typedPrefix)) continue;
                    if (added.contains(name)) continue;
                    // Already imported under this simple name: handled by the imported-class loop.
                    if (Fxml2ImportResolver.resolve(name, xmlFile) != null) continue;
                    for (PsiClass cls : cache.getClassesByName(name, allScope)) {
                        String fqn = cls.getQualifiedName();
                        // checkUnusable=false: class literals don't require an instantiable class.
                        if (Fxml2AddImportFix.shouldSkipClass(cls, fqn, runtimeRoots, false)) continue;
                        if (!Fxml2AttributeValueResolver.isClassLiteralBoundCompatible(cls, classType)) continue;
                        int tier = Fxml2AddImportFix.candidateTier(cls);
                        LookupElement element = LookupElementBuilder.create(cls, name)
                                .withIcon(AllIcons.Nodes.Class)
                                .withTypeText("Class")
                                .withTailText(" (" + fqn + ")", true)
                                .withInsertHandler(new TypeArgImportInsertHandler(cls));
                        result.addElement(PrioritizedLookupElement.withPriority(element, -tier));
                        added.add(name);
                        break; // one candidate per simple name is enough
                    }
                }
            }
        }

        private static void addStaticFieldsForType(
                @NotNull PsiClass searchClass,
                @NotNull PsiType targetType,
                @NotNull String typeText,
                @NotNull CompletionResultSet result) {
            for (PsiField field : searchClass.getAllFields()) {
                if (!field.hasModifierProperty(PsiModifier.PUBLIC)) continue;
                if (!field.hasModifierProperty(PsiModifier.STATIC)) continue;
                if (Fxml2AttributeValueResolver.isTypeCompatible(targetType, field.getType())) {
                    result.addElement(LookupElementBuilder.create(field, field.getName())
                            .withIcon(AllIcons.Nodes.Field)
                            .withTypeText(typeText));
                }
            }
        }




        /**
         * Returns the portion of {@code rawValue} before the IntelliJ completion dummy
         * identifier (whichever form is present), discarding any text that follows the
         * caret.  For brace-delimited binding expressions ({@code {fx:...}} or
         * {@code #{...}}) the closing {@code }} is re-appended so the expression parser
         * can still recognize the form.
         */
        private static @NotNull String truncateAtDummy(@NotNull String rawValue) {
            int idx = rawValue.indexOf(CompletionUtilCore.DUMMY_IDENTIFIER);
            if (idx < 0) idx = rawValue.indexOf(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED);
            if (idx < 0) return rawValue;
            String before = rawValue.substring(0, idx);
            // Re-append the closing brace for all brace-delimited expression forms so the
            // parser still recognizes them: {fx:...}, #{...}, ${...}, >{...}, and content variants.
            if (rawValue.endsWith("}") && (before.startsWith("{")
                    || before.startsWith("#{")
                    || before.startsWith("${")
                    || before.startsWith(">{"))) {
                before = before + "}";
            }
            return before;
        }
    }

    // -----------------------------------------------------------------------
    // Embedded FXML attribute-name insert handler
    // -----------------------------------------------------------------------

    /**
     * Attribute-name insert handler for embedded FXML.
     *
     * <p>Overrides {@code XmlAttributeInsertHandler} in embedded contexts to handle two
     * embedded-specific requirements:
     *
     * <ol>
     *   <li><b>Wrong quote character</b>: {@code XmlAttributeInsertHandler} checks whether
     *       {@code file.getContext().getText()} starts with {@code '"'} and, if so, uses
     *       single quotes ({@code =''}).  For embedded FXML the context element is the host
     *       Java {@code PsiLiteralExpression} (a text block, {@code """..."""}), which also
     *       starts with {@code '"'}, so the handler incorrectly uses single quotes.
     *       This handler always uses double quotes ({@code =""}).</li>
     *
     *   <li><b>PSI staleness on auto-popup</b>: After inserting {@code =""}, the
     *       {@code DocumentWindow} (injected document) is modified, which modifies the host
     *       (Java) document.  The original handler schedules an auto-popup immediately -
     *       before the host PSI is committed: causing an {@code "Inconsistent completion
     *       tree"} exception.  This handler commits the document before scheduling the
     *       auto-popup.</li>
     * </ol>
     */
    private static final class EmbeddedFxml2AttributeInsertHandler implements InsertHandler<LookupElement> {

        static final EmbeddedFxml2AttributeInsertHandler INSTANCE = new EmbeddedFxml2AttributeInsertHandler();

        @Override
        public void handleInsert(@NotNull InsertionContext context, @NotNull LookupElement item) {
            final Editor editor = context.getEditor();
            final Document document = editor.getDocument();
            final int caretOffset = editor.getCaretModel().getOffset();
            final CharSequence chars = document.getCharsSequence();

            // Respect the "Insert quotes for attribute value" preference, but always use
            // double quotes (not single quotes) for embedded FXML.
            final boolean insertQuotes =
                    WebEditorOptions.getInstance().isInsertQuotesForAttributeValue();

            final boolean hasQuotes = CharArrayUtil.regionMatches(chars, caretOffset, "=\"") ||
                                      CharArrayUtil.regionMatches(chars, caretOffset, "='");
            if (!hasQuotes) {
                if (CharArrayUtil.regionMatches(chars, caretOffset, "=")) {
                    document.deleteString(caretOffset, caretOffset + 1);
                }
                String toInsert = insertQuotes ? "=\"\"" : "=";
                if (caretOffset < document.getTextLength() &&
                        "/> \n\t\r".indexOf(document.getCharsSequence().charAt(caretOffset)) < 0) {
                    document.insertString(caretOffset, toInsert + " ");
                } else {
                    document.insertString(caretOffset, toInsert);
                }
                if ('=' == context.getCompletionChar()) {
                    context.setAddCompletionChar(false);
                }
            }

            editor.getCaretModel().moveToOffset(caretOffset + (insertQuotes || hasQuotes ? 2 : 1));
            TabOutScopesTracker.getInstance().registerEmptyScopeAtCaret(editor);
            editor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);
            editor.getSelectionModel().removeSelection();

            // Commit the document before scheduling the auto-popup.
            // The default XmlAttributeInsertHandler skips this commit, causing the host PSI
            // to be stale when the auto-popup fires, which produces:
            // "Inconsistent completion tree: range=(X,Y); fileLength=Z".
            context.commitDocument();
            var project = editor.getProject();
            if (project != null) {
                AutoPopupController.getInstance(project).scheduleAutoPopup(editor);
            }
        }
    }
}
