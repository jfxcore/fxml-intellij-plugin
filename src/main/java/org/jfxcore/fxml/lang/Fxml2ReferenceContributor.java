package org.jfxcore.fxml.lang;

import com.intellij.codeInsight.daemon.impl.analysis.PsiReferenceWithUnresolvedQuickFixes;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.LocalQuickFixProvider;
import com.intellij.lang.properties.references.PropertyReference;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.patterns.XmlPatterns;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.psi.PsiType;
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReferenceSet;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlProcessingInstruction;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.codeinsight.Fxml2AddImportFix;
import org.jfxcore.fxml.descriptors.Fxml2ClassTagDescriptor;
import org.jfxcore.fxml.descriptors.Fxml2StaticPropertyAttributeDescriptor;
import org.jfxcore.fxml.resolve.Fxml2AttributeValueResolver;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser.ContextSelector;
import org.jfxcore.fxml.resolve.Fxml2BindingPathResolver;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;
import org.jfxcore.fxml.resolve.Fxml2NamedArgResolver;
import org.jfxcore.fxml.resolve.Fxml2PropertyResolver;
import org.jfxcore.fxml.resolve.Fxml2TagResolver;
import org.jfxcore.fxml.resolve.Fxml2XmlUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.intellij.patterns.StandardPatterns.string;

/**
 * Provides per-segment {@link Fxml2BindingSegmentReference}s for FXML binding
 * expressions in attribute values (e.g. {@code $vm.field},
 * {@code {fx:Observe vm.property}}).
 *
 * <p>Each dot-separated segment gets its own reference, enabling Ctrl+click navigation
 * to the corresponding property declaration. Unresolved segments remain soft (no generic
 * "Cannot resolve" error from the IDE); {@link org.jfxcore.fxml.annotator.Fxml2AttributeAnnotator}
 * produces the diagnostics with compiler-matching messages.
 */
public final class Fxml2ReferenceContributor extends PsiReferenceContributor {

    /** FQN of the built-in {@code StaticResource} markup extension, the target of the {@code %} prefix. */
    private static final String STATIC_RESOURCE_FQN = "org.jfxcore.markup.resource.StaticResource";

    /** FQN of the built-in {@code ClassPathResource} markup extension, the target of the {@code @} prefix. */
    private static final String CLASSPATH_RESOURCE_FQN = "org.jfxcore.markup.resource.ClassPathResource";

    /** FQN of the built-in {@code DynamicResource} markup extension (no prefix shorthand). */
    private static final String DYNAMIC_RESOURCE_FQN = "org.jfxcore.markup.resource.DynamicResource";

    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {

        // Namespace URI values -> open docs in browser on Ctrl+click
        registrar.registerReferenceProvider(
                XmlPatterns.xmlAttributeValue()
                        .withValue(string().startsWith("http://jfxcore.org/")),
                new NamespaceUrlReferenceProvider(),
                PsiReferenceRegistrar.HIGHER_PRIORITY);

        registrar.registerReferenceProvider(
                XmlPatterns.xmlAttributeValue()
                        .withValue(string().startsWith("http://javafx.com/")),
                new NamespaceUrlReferenceProvider(),
                PsiReferenceRegistrar.HIGHER_PRIORITY);

        // xml:space attribute name and value -> open W3C whitespace-handling spec in browser.
        // Two registrations: one on the XmlAttribute (covers Ctrl+click on the name token)
        // and one on XmlAttributeValue (covers Ctrl+click on the quoted value).
        registrar.registerReferenceProvider(
                XmlPatterns.xmlAttribute()
                        .withLocalName("space"),
                new XmlSpaceAttrNameReferenceProvider(),
                PsiReferenceRegistrar.HIGHER_PRIORITY);

        registrar.registerReferenceProvider(
                XmlPatterns.xmlAttributeValue()
                        .withParent(XmlPatterns.xmlAttribute().withLocalName("space")),
                new XmlSpaceAttrValueReferenceProvider(),
                PsiReferenceRegistrar.HIGHER_PRIORITY);

        // Binding expressions: $path, ${path}, #{path}, {fx:...}
        registrar.registerReferenceProvider(
                XmlPatterns.xmlAttributeValue()
                        .withValue(string().startsWith("$")),
                new BindingReferenceProvider(),
                PsiReferenceRegistrar.HIGHER_PRIORITY);

        registrar.registerReferenceProvider(
                XmlPatterns.xmlAttributeValue()
                        .withValue(string().startsWith("{fx:")),
                new BindingReferenceProvider(),
                PsiReferenceRegistrar.HIGHER_PRIORITY);

        registrar.registerReferenceProvider(
                XmlPatterns.xmlAttributeValue()
                        .withValue(string().startsWith("#{")),
                new BindingReferenceProvider(),
                PsiReferenceRegistrar.HIGHER_PRIORITY);

        // Custom markup extension class name in {ClassName} or {ClassName param=val} syntax.
        // Excludes {fx:...} (handled above). Backslash-escaped values (e.g. \{ClassName}) start
        // with '\' rather than '{' and are therefore already excluded by the startsWith("{") guard.
        registrar.registerReferenceProvider(
                XmlPatterns.xmlAttributeValue()
                        .withValue(string().startsWith("{")
                                .andNot(string().startsWith("{fx:"))
                                .andNot(string().startsWith("\\"))),
                new MarkupExtensionReferenceProvider(),
                PsiReferenceRegistrar.HIGHER_PRIORITY);

        // fx:context attribute value (e.g. fx:context="$myContext"): binding against code-behind
        registrar.registerReferenceProvider(
                XmlPatterns.xmlAttributeValue()
                        .withParent(XmlPatterns.xmlAttribute().withLocalName("context")
                                .withNamespace(Fxml2ImportResolver.FXML2_NAMESPACE)),
                new FxContextReferenceProvider(),
                PsiReferenceRegistrar.HIGHER_PRIORITY);

        // Prefix-shorthand markup extension invocations: @path, %key, or custom-declared prefixes.
        // The provider handles any FXML attribute value and checks the prefix-mapping internally,
        // so we register it with the broadest pattern to support custom-prefix characters.
        registrar.registerReferenceProvider(
                XmlPatterns.xmlAttributeValue(),
                new PrefixShorthandReferenceProvider(),
                PsiReferenceRegistrar.HIGHER_PRIORITY);

        // Event-handler method references: a plain method name on an EventHandler-typed property
        // (e.g. onAction="handleClick"). Registered at HIGHER_PRIORITY so that when this
        // attribute value is also a candidate for LiteralValueReferenceProvider, our reference
        // is chosen first by PsiMultiReference and Ctrl+click navigates to the Java method.
        registrar.registerReferenceProvider(
                XmlPatterns.xmlAttributeValue(),
                new EventHandlerMethodReferenceProvider(),
                PsiReferenceRegistrar.HIGHER_PRIORITY);

        // Plain literal values (enum constants, static fields, Color, @NamedArg)
        registrar.registerReferenceProvider(
                XmlPatterns.xmlAttributeValue(),
                new LiteralValueReferenceProvider(),
                PsiReferenceRegistrar.DEFAULT_PRIORITY);

        // String-typed attributes whose value is a fully-qualified class name
        // (e.g. viewClassName="com.example.SomeView"): per-segment package+class navigation.
        registrar.registerReferenceProvider(
                XmlPatterns.xmlAttributeValue(),
                new StringClassNameReferenceProvider(),
                PsiReferenceRegistrar.DEFAULT_PRIORITY);

        // fx:typeArguments: per-segment class navigation
        registrar.registerReferenceProvider(
                XmlPatterns.xmlAttributeValue(),
                new TypeArgumentsReferenceProvider(),
                PsiReferenceRegistrar.HIGHER_PRIORITY);

        // styleClass attribute: per-token CSS class selector navigation
        registrar.registerReferenceProvider(
                XmlPatterns.xmlAttributeValue()
                        .withParent(XmlPatterns.xmlAttribute().withLocalName("styleClass")),
                new StyleClassReferenceProvider(),
                PsiReferenceRegistrar.HIGHER_PRIORITY);

        // Dotted attribute names: handles both static (ClassName.prop) and chained (prop1.prop2).
        // Registered on XmlAttribute. Our refs are non-soft so they beat XmlAttributeReference
        // in PsiMultiReference when XmlAttributeReference does not resolve (which we ensure by
        // having our attribute descriptor return null from getDeclaration()).
        registrar.registerReferenceProvider(
                XmlPatterns.xmlAttribute()
                        .withLocalName(string().contains(".")),
                new DottedAttributeNameReferenceProvider(),
                PsiReferenceRegistrar.HIGHER_PRIORITY);

        // Dotted static-property tag names (e.g. <Interaction.behaviors>):
        // emit separate references for the class part and the property part.
        registrar.registerReferenceProvider(
                XmlPatterns.xmlTag()
                        .withLocalName(string().contains(".")),
                new StaticPropertyTagNameReferenceProvider(),
                PsiReferenceRegistrar.HIGHER_PRIORITY);

        // fx:id attribute values: provides a self-reference (named-element declaration)
        // and optional navigation to the code-behind field.  The self-reference is what
        // makes "highlight usages" from the definition site work: IntelliJ calls
        // isReferenceTo(XmlAttributeValue) on all Fxml2BindingSegmentReferences, which
        // return true when their declaration's navigation element matches.
        registrar.registerReferenceProvider(
                XmlPatterns.xmlAttributeValue()
                        .withParent(XmlPatterns.xmlAttribute().withLocalName("id")
                                .withNamespace(Fxml2ImportResolver.FXML2_NAMESPACE)),
                new FxIdReferenceProvider(),
                PsiReferenceRegistrar.HIGHER_PRIORITY);

        // Element-notation binding source paths: <fx:Observe source="path"/>,
        // <fx:Evaluate source="path"/>, <fx:Push source="path"/>, <fx:Synchronize source="path"/>
        // The value is a plain path (no $/{} prefix) but supports context selectors and ".." prefix.
        registrar.registerReferenceProvider(
                XmlPatterns.xmlAttributeValue()
                        .withParent(XmlPatterns.xmlAttribute().withLocalName("source")),
                new ElementNotationSourceReferenceProvider(),
                PsiReferenceRegistrar.HIGHER_PRIORITY);

        // "source" attribute name on fx:* binding element tags -> link to expression-docs URL.
        registrar.registerReferenceProvider(
                XmlPatterns.xmlAttribute().withLocalName("source"),
                new ElementNotationSourceAttrNameReferenceProvider(),
                PsiReferenceRegistrar.HIGHER_PRIORITY);

        // Text content inside property element tags is handled by Fxml2XmlTextReferenceContributor.

        // Class name in <?prefix X = ClassName?> processing instructions:
        // navigates to the markup-extension class declared as the prefix target.
        // The "prefix" keyword itself opens the prefix-declarations documentation page.
        registrar.registerReferenceProvider(
                PlatformPatterns.psiElement(XmlProcessingInstruction.class),
                new PrefixDeclarationClassReferenceProvider(),
                PsiReferenceRegistrar.HIGHER_PRIORITY);

        // fx:* attribute names (e.g. fx:id, fx:subclass, fx:context): each name links
        // to the corresponding online language-reference page.
        registrar.registerReferenceProvider(
                XmlPatterns.xmlAttribute(),
                new FxIntrinsicAttributeNameReferenceProvider(),
                PsiReferenceRegistrar.HIGHER_PRIORITY);

        // fx:* element tags (e.g. <fx:define>): links to the online reference page.
        registrar.registerReferenceProvider(
                XmlPatterns.xmlTag(),
                new FxIntrinsicTagNameReferenceProvider(),
                PsiReferenceRegistrar.HIGHER_PRIORITY);
    }

    private static final Map<String, String> NAMESPACE_DOC_URLS = Map.of(
            Fxml2ImportResolver.FXML2_NAMESPACE,  "https://jfxcore.github.io/fxml-compiler/",
            Fxml2ImportResolver.JAVAFX_NAMESPACE, "https://openjfx.io/"
    );

    // -----------------------------------------------------------------------
    // Markup extension class name provider ({ClassName} attribute syntax)
    // -----------------------------------------------------------------------

    /**
     * Provides {@link PsiReference}s from within a {@code {ClassName}} markup-extension
     * attribute value:
     * <ul>
     *   <li>The extension class name -> the corresponding {@link PsiClass}.</li>
     *   <li>Type argument(s) in {@code <TypeArg>} or {@code &lt;TypeArg&gt;} ->
     *       per-segment class references (same logic as {@code fx:typeArguments}).</li>
     *   <li>Parameter names (key in {@code key=value} pairs) -> the {@link PsiParameter}
     *       or setter {@link PsiMethod} on the extension class.</li>
     *   <li>Inline binding expressions ({@code ${path}} or {@code $path}) inside the
     *       parameter section -> binding notation reference + per-segment path references
     *       (same code paths as {@link BindingReferenceProvider}).</li>
     *   <li>For {@code {DynamicResource key}} and {@code {StaticResource key}}: a
     *       {@link com.intellij.lang.properties.references.PropertyReference} spanning the
     *       positional default argument (resource key) so that Ctrl+click navigates to the
     *       resource bundle entry and the property is not flagged as unused in
     *       {@code .properties} files.  Applied to all FXML file types, since the bundled
     *       JavaFX plugin only handles the {@code %key} prefix shorthand (not long forms).</li>
     * </ul>
     *
     * <p>The class-name reference is soft so that IntelliJ does not emit an additional
     * "Cannot resolve" error on top of the one produced by
     * {@link org.jfxcore.fxml.annotator.Fxml2AttributeAnnotator}.
     * Despite being soft it <em>does</em> resolve to the class, which means
     * "Find Usages" of the class will discover this use site.
     */
    private static final class MarkupExtensionReferenceProvider extends PsiReferenceProvider {

        @Override
        public PsiReference @NotNull [] getReferencesByElement(
                @NotNull PsiElement element, @NotNull ProcessingContext context) {

            if (!(element instanceof XmlAttributeValue attrVal)) return PsiReference.EMPTY_ARRAY;
            if (!(attrVal.getContainingFile() instanceof XmlFile xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2FileType.isFxml2(xmlFile)) return PsiReference.EMPTY_ARRAY;

            String rawValue = attrVal.getValue();
            if (rawValue.isBlank()) return PsiReference.EMPTY_ARRAY;

            Object parsed = Fxml2BindingExpressionParser.parse(rawValue);
            if (!(parsed instanceof Fxml2BindingExpressionParser.MarkupExtensionExpression(
                    String extensionName, int nameOffset, boolean ignored))) {
                return PsiReference.EMPTY_ARRAY;
            }

            // Range within XmlAttributeValue text (which includes surrounding quotes): +1 for opening quote.
            TextRange classNameRange = new TextRange(1 + nameOffset, 1 + nameOffset + extensionName.length());

            PsiClass extClass = Fxml2ImportResolver.resolve(extensionName, xmlFile);
            if (extClass == null) {
                extClass = JavaPsiFacade.getInstance(xmlFile.getProject())
                        .findClass(extensionName, xmlFile.getResolveScope());
            }
            // Fallback: resolve built-in resource extension classes (DynamicResource,
            // StaticResource, ClassPathResource) by their FQN when the simple name is used
            // without an explicit import: common in embedded FXML where the class is
            // available on the classpath but not listed in the Java file's import declarations.
            if (extClass == null) {
                extClass = resolveBuiltInExtensionBySimpleName(extensionName, xmlFile.getProject());
            }

            List<PsiReference> refs = new ArrayList<>();

            // Extension class name: soft so IntelliJ doesn't add a second "Cannot resolve" indicator.
            // NOTE: no Fxml2ExpressionReference blocker here: LiteralValueReferenceProvider already
            // skips values where looksLikeBindingExpression() returns true (which it does for {...}).
            // Adding a full-range blocker as the *first* reference would cause getReference() to always
            // return it (null-resolving), breaking caret word-highlighting for "MyMarkupExtension" and
            // other tokens inside the expression.
            if (extClass != null) {
                refs.add(new ClassSegmentReference(attrVal, classNameRange, extClass, /* hard= */ false));
            } else {
                // Unresolved: soft so the IDE doesn't add its own "Cannot resolve" on top of the
                // diagnostic from Fxml2AttributeAnnotator.
                refs.add(softRef(attrVal, classNameRange, null));
            }

            // Type argument reference(s): {ClassName<TypeArg>} or {ClassName&lt;TypeArg&gt;}
            int afterName = nameOffset + extensionName.length();
            collectMarkupExtTypeArgRefs(refs, attrVal, rawValue, afterName, xmlFile);

            // Params section: everything after the class name and optional type args.
            // Uses the same whitespace-finding logic as annotateMarkupExtension in
            // Fxml2AttributeAnnotator so the two code paths stay in sync.
            if (rawValue.length() > 2 && extClass != null) {
                String inner = rawValue.substring(1, rawValue.length() - 1).trim();
                int firstSpace = indexOfWhitespaceMEInner(inner);
                if (firstSpace >= 0) {
                    String paramsPart = inner.substring(firstSpace).trim();
                    int paramsPartInRaw = rawValue.indexOf(paramsPart, 1 + firstSpace);
                    if (paramsPartInRaw >= 0) {
                        XmlTag contextTag = getContextTag(attrVal);
                        collectMarkupExtParamRefs(refs, attrVal, paramsPart, paramsPartInRaw,
                                extClass, contextTag, xmlFile);

                        // For resource key extensions (DynamicResource, StaticResource long form),
                        // add a PropertyReference for the positional default argument (the resource key).
                        // This enables Ctrl+click navigation to the resource bundle entry and prevents
                        // the key from being flagged as unused in .properties files.
                        // The bundled JavaFX plugin only handles %key (StaticResource prefix shorthand),
                        // NOT long-form {DynamicResource key} or {StaticResource key} invocations.
                        if (isResourceKeyExtension(extClass)) {
                            String resourceKey = extractPositionalDefaultArg(paramsPart);
                            if (resourceKey != null) {
                                // paramsPart is trimmed, so the key starts at offset 0 within it.
                                int keyStart = 1 + paramsPartInRaw; // +1 for opening quote in attrVal text
                                refs.add(new PropertyReference(
                                        resourceKey, attrVal, null, /* soft= */ false,
                                        new TextRange(keyStart, keyStart + resourceKey.length())));
                            }
                        }
                    }
                }
            }

            return refs.toArray(PsiReference.EMPTY_ARRAY);
        }
    }

    // -----------------------------------------------------------------------
    // Prefix-shorthand reference provider (@path, %key, custom-prefix forms)
    // -----------------------------------------------------------------------

    /**
     * Provides references for prefix-shorthand markup extension invocations such as
     * {@code @icons/app.png} or {@code %greeting; formatArguments=Jane, Doe}.
     *
     * <p>References provided:
     * <ul>
     *   <li>The prefix character (e.g. {@code @}) -> the mapped extension class
     *       ({@code ClassPathResource} or {@code StaticResource}), enabling Ctrl+click
     *       navigation and Find Usages discovery.</li>
     *   <li>{@code ; param=value} parameter names -> the {@link PsiParameter} or setter
     *       {@link PsiMethod} on the extension class (same logic as
     *       {@link MarkupExtensionReferenceProvider}).</li>
     *   <li>For {@code %key} (StaticResource): a
     *       {@link com.intellij.lang.properties.references.PropertyReference} spanning the
     *       key text, so that Ctrl+click navigates to the resource bundle entry and the
     *       property is not flagged as unused in {@code .properties} files.
     *       Not added for standalone {@code .fxml} files: the bundled JavaFX plugin's
     *       {@code FxmlResourceReferencesContributor} already provides this there.</li>
     *   <li>For {@code @path} (ClassPathResource): {@link com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReference}
     *       instances spanning the path text, so that Ctrl+click navigates to the
     *       referenced classpath resource file.
     *       Not added for standalone {@code .fxml} files: the bundled JavaFX plugin's
     *       {@code FxmlReferencesContributor} already provides this there.</li>
     * </ul>
     */
    private static final class PrefixShorthandReferenceProvider extends PsiReferenceProvider {

        @Override
        public PsiReference @NotNull [] getReferencesByElement(
                @NotNull PsiElement element, @NotNull ProcessingContext context) {

            if (!(element instanceof XmlAttributeValue attrVal)) return PsiReference.EMPTY_ARRAY;
            if (!(attrVal.getContainingFile() instanceof XmlFile xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2FileType.isFxml2(xmlFile)) return PsiReference.EMPTY_ARRAY;

            String rawValue = attrVal.getValue();
            if (rawValue.isBlank()) return PsiReference.EMPTY_ARRAY;

            java.util.Map<Character, String> prefixMappings =
                    Fxml2ImportResolver.parsePrefixMappings(xmlFile);
            if (prefixMappings.isEmpty()) return PsiReference.EMPTY_ARRAY;

            Object parsed = Fxml2BindingExpressionParser.parse(rawValue, prefixMappings);
            if (!(parsed instanceof Fxml2BindingExpressionParser.PrefixShorthandExpression pse)) {
                return PsiReference.EMPTY_ARRAY;
            }

            // Resolve the mapped extension class.
            // The prefix declaration may use a simple name (e.g. <?prefix % = MyExt?>) resolved
            // via <?import?>, so try Fxml2ImportResolver first, then fall back to a direct
            // findClass for FQNs (covers built-in defaults stored as FQNs).
            PsiClass extClass = Fxml2ImportResolver.resolve(pse.mappedClass(), xmlFile);
            if (extClass == null) {
                extClass = JavaPsiFacade.getInstance(xmlFile.getProject())
                        .findClass(pse.mappedClass(), GlobalSearchScope.allScope(xmlFile.getProject()));
            }

            List<PsiReference> refs = new ArrayList<>();

            // Reference for the prefix character itself (offset 0 in raw value, offset 1 in
            // attrVal text because of the opening quote).
            // The prefix character is a symbolic alias for the class; it does not contain the
            // class name as text and must therefore be excluded from rename refactoring.
            TextRange prefixRange = new TextRange(1, 2); // covers only the single prefix char
            if (extClass != null) {
                refs.add(new ClassSegmentReference(attrVal, prefixRange, extClass, /* hard= */ false,
                        /* participatesInRename= */ false));
            } else {
                refs.add(softRef(attrVal, prefixRange, null));
            }

            // Parameter references from the ; param=value section.
            if (pse.paramsPart() != null && extClass != null) {
                XmlTag contextTag = getContextTag(attrVal);
                collectMarkupExtParamRefs(refs, attrVal, pse.paramsPart(), pse.paramsOffset(),
                        extClass, contextTag, xmlFile);
            }

            // For resource-key extensions mapped to a prefix (e.g. %key), add a PropertyReference
            // so that Ctrl+click navigates to the resource bundle and the property is not reported
            // as unused in .properties files. The mapped extension is identified by the convention
            // in Fxml2MarkupExtensionUtil: when the class is PSI-resolvable the full check
            // (name + @DefaultProperty) is applied; otherwise the simple-name heuristic is used
            // as a fallback so that the built-in StaticResource still works even when its
            // compile-time class is absent from the fixture or cannot be indexed.
            // Standalone .fxml files whose % prefix maps to the built-in StaticResource are
            // already handled by the bundled JavaFX plugin's FxmlResourceReferencesContributor;
            // adding a second reference there is not needed.
            if (needsPluginResourceReferences(xmlFile)
                    && !pse.defaultArg().isEmpty()
                    && isPrefixMappedToResourceKeyExtension(pse.mappedClass(), extClass)) {
                int keyStart = 1 + pse.defaultArgOffset(); // +1 for opening quote in attrVal text
                refs.add(new PropertyReference(
                        pse.defaultArg(), attrVal, null, /* soft= */ false,
                        new TextRange(keyStart, keyStart + pse.defaultArg().length())));
            }

            // For ClassPathResource (@path notation), add FileReference instances so that
            // Ctrl+click navigates to the referenced classpath resource file.
            // Standalone .fxml files are already handled by the bundled JavaFX plugin's
            // FxmlReferencesContributor; adding a second reference there is not needed.
            if (needsPluginResourceReferences(xmlFile)
                    && CLASSPATH_RESOURCE_FQN.equals(pse.mappedClass())
                    && !pse.defaultArg().isEmpty()) {
                String path = pse.defaultArg();
                int pathStart = 1 + pse.defaultArgOffset(); // +1 for opening quote in attrVal text
                // Strip enclosing single quotes for quoted paths (@'path with spaces/img.png').
                if (path.length() >= 2
                        && path.charAt(0) == '\''
                        && path.charAt(path.length() - 1) == '\'') {
                    path = path.substring(1, path.length() - 1);
                    pathStart++; // skip the opening single quote
                }
                FileReferenceSet refSet = new FileReferenceSet(path, attrVal, pathStart, null, true);
                if (path.startsWith("/")) {
                    refSet.addCustomization(FileReferenceSet.DEFAULT_PATH_EVALUATOR_OPTION,
                            FileReferenceSet.ABSOLUTE_TOP_LEVEL);
                }
                java.util.Collections.addAll(refs, refSet.getAllReferences());
            }

            return refs.toArray(PsiReference.EMPTY_ARRAY);
        }
    }

    // -----------------------------------------------------------------------
    // Markup extension reference helpers
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when the FXML/2 plugin must add resource references for the given
     * file, i.e., the bundled JavaFX plugin's reference contributors do NOT already handle it.
     *
     * <p>Returns {@code true} for:
     * <ul>
     *   <li>Embedded FXML (injected language fragments: no real VirtualFile extension)</li>
     *   <li>Standalone {@code .fxmlx} files (not handled by the bundled JavaFX plugin)</li>
     * </ul>
     *
     * <p>Returns {@code false} for standalone {@code .fxml} files where the bundled JavaFX
     * plugin's reference contributors already fire, so duplicate references must not be added.
     */
    private static boolean needsPluginResourceReferences(@NotNull XmlFile xmlFile) {
        if (Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile)) return true;
        VirtualFile vf = xmlFile.getVirtualFile();
        return vf == null || !"fxml".equalsIgnoreCase(vf.getExtension());
    }

    /**
     * Resolves a known built-in resource extension class ({@code DynamicResource},
     * {@code StaticResource}, {@code ClassPathResource}) by its simple name, using
     * {@link GlobalSearchScope#allScope} to ensure the class is found even when it is not
     * explicitly imported in the containing file (e.g. embedded FXML where imports come
     * only from the host Java file's import declarations).
     *
     * @param simpleName the unqualified class name (e.g. {@code "DynamicResource"})
     * @param project    the current project
     * @return the resolved {@link PsiClass}, or {@code null} if the class is not on the classpath
     */
    private static @Nullable PsiClass resolveBuiltInExtensionBySimpleName(
            @NotNull String simpleName, @NotNull Project project) {
        GlobalSearchScope allScope = GlobalSearchScope.allScope(project);
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        for (String fqn : List.of(DYNAMIC_RESOURCE_FQN, STATIC_RESOURCE_FQN, CLASSPATH_RESOURCE_FQN)) {
            if (fqn.endsWith("." + simpleName)) {
                return facade.findClass(fqn, allScope);
            }
        }
        return null;
    }

    /**
     * Returns {@code true} if the given markup extension class is a resource-key extension,
     * meaning its positional default argument is a resource bundle key that should be exposed
     * as a {@link com.intellij.lang.properties.references.PropertyReference}.
     *
     * <p>Detection is delegated to {@link Fxml2MarkupExtensionUtil#isResourceKeyExtension},
     * which applies the project-wide convention. All class-specific knowledge lives there.
     */
    private static boolean isResourceKeyExtension(@NotNull PsiClass extClass) {
        return Fxml2MarkupExtensionUtil.isResourceKeyExtension(extClass);
    }

    /**
     * Returns {@code true} if the extension identified by {@code mappedClass} is a resource-key
     * extension, applying the full PSI-based convention check when {@code resolvedClass} is
     * available, or falling back to the name-only heuristic when PSI resolution failed.
     *
     * <p>The fallback ensures that the built-in {@code StaticResource} is still recognized as a
     * resource-key extension even when its compiled class is absent from the current analysis
     * scope (e.g., in test fixtures without a StaticResource mock on the classpath).
     *
     * @param mappedClass   the FQN or simple name of the mapped extension class
     * @param resolvedClass the resolved {@link PsiClass}, or {@code null} if not found
     */
    private static boolean isPrefixMappedToResourceKeyExtension(
            @NotNull String mappedClass, @Nullable PsiClass resolvedClass) {
        if (resolvedClass != null) {
            return isResourceKeyExtension(resolvedClass);
        }
        // Class not resolvable: apply name-only heuristic on the simple name portion of mappedClass.
        String simpleName = mappedClass.contains(".")
                ? mappedClass.substring(mappedClass.lastIndexOf('.') + 1)
                : mappedClass;
        return Fxml2MarkupExtensionUtil.simpleNameSuggestsResourceKeyExtension(simpleName);
    }

    /**
     * Extracts the positional default argument from a markup extension params string.
     *
     * <p>The positional default arg is the content before the first {@code ;} (if any),
     * trimmed. If that content contains {@code =} it is a named argument, not positional,
     * and {@code null} is returned.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "greeting"} -> {@code "greeting"}</li>
     *   <li>{@code "button.text"} -> {@code "button.text"} (dots are preserved)</li>
     *   <li>{@code "greeting; formatArguments=Jane"} -> {@code "greeting"}</li>
     *   <li>{@code "key=greeting"} -> {@code null} (named arg)</li>
     * </ul>
     *
     * @param paramsPart the params section of the markup extension, already trimmed
     * @return the positional default arg string, or {@code null} if absent or named
     */
    @Nullable
    private static String extractPositionalDefaultArg(@NotNull String paramsPart) {
        // Everything before the first ';' is the default arg candidate.
        int semi = paramsPart.indexOf(';');
        String candidate = (semi >= 0 ? paramsPart.substring(0, semi) : paramsPart).trim();
        if (candidate.isEmpty()) return null;
        // If the candidate contains '=', it is a named argument (key=value), not positional.
        if (candidate.contains("=")) return null;
        return candidate;
    }

    /**
     * Adds class references for type arguments immediately following the extension class
     * name (e.g. {@code <String>} in {@code {GenericExt<String> ...}} or the entity form
     * {@code &lt;String&gt;}).
     *
     * <p>Uses {@link #buildFqnSegmentRefs} for each comma-separated token: same code
     * path as {@code fx:typeArguments}.
     *
     * @param afterName offset in {@code rawValue} immediately after the extension class name
     */
    private static void collectMarkupExtTypeArgRefs(
            @NotNull List<PsiReference> refs,
            @NotNull XmlAttributeValue attrVal,
            @NotNull String rawValue,
            int afterName,
            @NotNull XmlFile xmlFile) {

        if (afterName >= rawValue.length()) return;

        boolean literal;
        int contentStart; // offset in rawValue of first char after opening bracket
        if (rawValue.charAt(afterName) == '<') {
            literal = true;
            contentStart = afterName + 1;
        } else if (rawValue.startsWith("&lt;", afterName)) {
            literal = false;
            contentStart = afterName + 4; // skip "&lt;"
        } else {
            return; // no type arg bracket
        }

        // Find the matching close bracket
        int closeOffset = literal
                ? findClosingAngleLiteral(rawValue, contentStart)
                : findClosingAngleEntity(rawValue, contentStart);
        if (closeOffset < 0) {
            return;
        }

        // Gap-filling: add soft null-resolving references for the bracket characters so that
        // findReferenceAt() never returns null for cursor positions on '<'/'&lt;' or '>'/'&gt;'.
        // Without them, the cursor landing exactly on the bracket triggers a non-deterministic
        // word-at-caret fallback that may highlight either the class name or the type arg.
        int openBracketLen  = literal ? 1 : 4; // '<' vs "&lt;"
        int closeBracketLen = literal ? 1 : 4; // '>' vs "&gt;"
        refs.add(softRef(attrVal, new TextRange(1 + afterName, 1 + afterName + openBracketLen), null));
        refs.add(softRef(attrVal, new TextRange(1 + closeOffset, 1 + closeOffset + closeBracketLen), null));

        String typeArgContent = rawValue.substring(contentStart, closeOffset);

        // Emit class refs for each comma-separated type token
        int cursor = 0;
        for (String token : typeArgContent.split(",", -1)) {
            int leadingSpaces = 0;
            while (leadingSpaces < token.length()
                    && Character.isWhitespace(token.charAt(leadingSpaces))) {
                leadingSpaces++;
            }
            String name = token.stripTrailing().substring(leadingSpaces);
            if (!name.isEmpty()) {
                // Strip nested angle brackets (e.g. "Map<K,V>" -> "Map")
                int innerAngle = name.indexOf('<');
                if (innerAngle < 0) innerAngle = name.indexOf("&lt;");
                if (innerAngle > 0) name = name.substring(0, innerAngle);
                int tokenOffset = contentStart + cursor + leadingSpaces;
                refs.addAll(List.of(buildFqnSegmentRefs(attrVal, name, tokenOffset, xmlFile, false)));
            }
            cursor += token.length() + 1; // +1 for comma
        }
    }

    /** Finds the offset of the matching {@code >} in a literal angle-bracket sequence. */
    private static int findClosingAngleLiteral(@NotNull String s, int from) {
        int depth = 1;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') { if (--depth == 0) return i; }
        }
        return -1;
    }

    /** Finds the offset of the matching {@code &gt;} entity in a string. */
    private static int findClosingAngleEntity(@NotNull String s, int from) {
        for (int i = from; i <= s.length() - 4; i++) {
            if (s.startsWith("&gt;", i)) return i;
        }
        return -1;
    }


    /**
     * Scans the params section of a markup extension and adds:
     * <ul>
     *   <li>A {@link Fxml2BindingSegmentReference} for each {@code key} in a
     *       {@code key=value} pair, resolving to the matching {@code @NamedArg}
     *       parameter or setter on {@code extClass}.</li>
     *   <li>Binding notation + segment references for any {@code ${path}} or
     *       {@code $path} expression (same paths as {@link BindingReferenceProvider}).</li>
     * </ul>
     *
     * @param paramsPart      the params substring (e.g. {@code "resourceKey; formatArguments=foo, ${vm.text}"})
     * @param paramsPartInRaw offset of {@code paramsPart[0]} within {@code rawValue} (0-indexed)
     */
    private static void collectMarkupExtParamRefs(
            @NotNull List<PsiReference> refs,
            @NotNull XmlAttributeValue attrVal,
            @NotNull String paramsPart,
            int paramsPartInRaw,
            @NotNull PsiClass extClass,
            @Nullable XmlTag contextTag,
            @NotNull XmlFile xmlFile) {

        int cursor = 0;
        int len = paramsPart.length();
        while (cursor < len) {
            char c = paramsPart.charAt(cursor);

            // Skip whitespace/separators
            if (Character.isWhitespace(c) || c == ',' || c == ';') {
                cursor++;
                continue;
            }

            // Binding expression: '$' prefix.
            // Must be checked before the Java-identifier branch because '$' is a valid
            // Java identifier start character, but here it always signals a binding.
            if (c == '$') {
                int exprEnd = findMarkupExtBindingEnd(paramsPart, cursor);
                if (exprEnd > cursor) {
                    addMarkupExtBindingRefs(refs, attrVal,
                            paramsPart.substring(cursor, exprEnd),
                            paramsPartInRaw + cursor, contextTag, xmlFile);
                    cursor = exprEnd;
                } else {
                    cursor++;
                }
                continue;
            }

            // Java identifier: either a parameter key (key=value) or a positional value
            if (Character.isJavaIdentifierStart(c)) {
                int identStart = cursor;
                while (cursor < len && Character.isJavaIdentifierPart(paramsPart.charAt(cursor))) {
                    cursor++;
                }
                String ident = paramsPart.substring(identStart, cursor);

                // Skip whitespace
                int wsEnd = cursor;
                while (wsEnd < len && Character.isWhitespace(paramsPart.charAt(wsEnd))) wsEnd++;

                if (wsEnd < len && paramsPart.charAt(wsEnd) == '=') {
                    // key=value pair: emit a reference for the key name
                    PsiElement decl = resolveMarkupExtParam(ident, extClass);
                    refs.add(new Fxml2BindingSegmentReference(attrVal,
                            new TextRange(1 + paramsPartInRaw + identStart,
                                    1 + paramsPartInRaw + cursor),
                            decl));
                    cursor = wsEnd + 1; // skip '='
                    // Advance past the value, collecting binding refs when needed
                    cursor = skipMarkupExtValue(refs, attrVal, paramsPart, cursor, len,
                            paramsPartInRaw, contextTag, xmlFile);
                } else {
                    // Positional default-property value: no reference emitted, advance past it
                    cursor = wsEnd;
                }
                continue;
            }

            cursor++;
        }
    }

    /**
     * Advances past a markup-extension parameter value starting at {@code start}.
     * If the value is a binding expression ({@code ${path}} or {@code $path}), binding
     * references are added via {@link #addMarkupExtBindingRefs}.
     *
     * @return the cursor position after the value
     */
    private static int skipMarkupExtValue(
            @NotNull List<PsiReference> refs,
            @NotNull XmlAttributeValue attrVal,
            @NotNull String paramsPart,
            int start,
            int limit,
            int paramsPartInRaw,
            @Nullable XmlTag contextTag,
            @NotNull XmlFile xmlFile) {

        if (start >= limit) return start;
        char c = paramsPart.charAt(start);

        // Binding expression value
        if (c == '$') {
            int exprEnd = findMarkupExtBindingEnd(paramsPart, start);
            if (exprEnd > start) {
                addMarkupExtBindingRefs(refs, attrVal,
                        paramsPart.substring(start, exprEnd),
                        paramsPartInRaw + start, contextTag, xmlFile);
                return exprEnd;
            }
        }

        // Quoted string
        if (c == '"' || c == '\'') {
            int cursor = start + 1;
            while (cursor < limit && paramsPart.charAt(cursor) != c) cursor++;
            return cursor < limit ? cursor + 1 : limit;
        }

        // Plain literal: scan to next whitespace/comma/semicolon
        int cursor = start;
        while (cursor < limit
                && !Character.isWhitespace(paramsPart.charAt(cursor))
                && paramsPart.charAt(cursor) != ','
                && paramsPart.charAt(cursor) != ';') {
            cursor++;
        }
        return cursor;
    }

    /**
     * Returns the end offset (exclusive) of a binding expression starting at {@code start}:
     * <ul>
     *   <li>{@code ${...}}: finds the matching {@code }</li>
     *   <li>{@code $path}: scans to the next whitespace, comma, or semicolon</li>
     * </ul>
     * Returns {@code start} if no binding expression is detected or the expression is malformed.
     */
    private static int findMarkupExtBindingEnd(@NotNull String s, int start) {
        if (start >= s.length() || s.charAt(start) != '$') return start;
        int next = start + 1;
        if (next < s.length() && s.charAt(next) == '{') {
            // ${...}: find the matching }
            int depth = 0;
            for (int i = next; i < s.length(); i++) {
                if (s.charAt(i) == '{') depth++;
                else if (s.charAt(i) == '}') { if (--depth == 0) return i + 1; }
            }
            return start; // unmatched brace
        }
        // $path: scan to next separator
        int end = next;
        while (end < s.length()
                && !Character.isWhitespace(s.charAt(end))
                && s.charAt(end) != ','
                && s.charAt(end) != ';') {
            end++;
        }
        return Math.max(end, start);
    }

    /**
     * Adds a {@link Fxml2BindingNotationReference} and per-segment
     * {@link Fxml2BindingSegmentReference}s for an inline binding expression inside a
     * markup extension parameter section.
     *
     * <p>Uses the identical resolution logic as {@link BindingReferenceProvider} so the
     * code paths are shared.
     *
     * @param subExpr            the binding expression string (e.g. {@code "${vm.labelText}"})
     * @param subExprOffsetInRaw offset of {@code subExpr[0]} within {@code rawValue} (0-indexed)
     */
    private static void addMarkupExtBindingRefs(
            @NotNull List<PsiReference> refs,
            @NotNull XmlAttributeValue attrVal,
            @NotNull String subExpr,
            int subExprOffsetInRaw,
            @Nullable XmlTag contextTag,
            @NotNull XmlFile xmlFile) {

        Fxml2BindingExpressionParser.ParsedExpression expr =
                Fxml2BindingExpressionParser.parseExpression(subExpr);
        if (expr == null) return;

        // Binding notation reference (e.g. covers "$" in "$path" or "${" in "${path}")
        int notationBase = 1 + subExprOffsetInRaw; // +1 for opening quote in attrVal text
        refs.add(new Fxml2BindingNotationReference(
                attrVal,
                new TextRange(notationBase, notationBase + expr.prefixLength())));

        String strippedPath = expr.strippedPath();
        ContextSelector selector = Fxml2BindingExpressionParser.parseContextSelector(strippedPath);
        String pathForResolution = selector != null ? selector.remainingPath() : strippedPath;
        if (pathForResolution.isBlank()) return;

        // Resolve the start class: identical logic to BindingReferenceProvider
        PsiClass startClass = null;
        if (contextTag != null) {
            startClass = Fxml2BindingPathResolver.resolveStartClass(selector, contextTag, xmlFile);
        }
        if (startClass == null && (selector == null || selector.isThis())) {
            startClass = Fxml2BindingPathResolver.resolveCodeBehindClass(xmlFile);
        }
        if (startClass == null) return;

        GlobalSearchScope scope = xmlFile.getResolveScope();
        List<Fxml2BindingPathResolver.Segment> segments =
                Fxml2BindingPathResolver.resolve(pathForResolution, startClass, scope,
                        expr.kind(), xmlFile);

        // Base offset in attrVal text for the first path segment
        int pathBase = 1 + subExprOffsetInRaw + expr.strippedPathOffset()
                + (selector != null ? selector.selectorLength() : 0);

        // When a binding path segment is unresolved and occupies a class-qualifier position
        // (i.e. it starts with an uppercase letter and no resolved instance-property segment
        // has been seen yet), it is treated as an unresolved class-name reference.
        // In that case a hard UnresolvedClassSegmentReference is emitted so that the IDE
        // reports "Cannot resolve symbol 'X'" and offers the "Add import for X" quick fix,
        // matching the behavior for unresolved names in fx:typeArguments attributes.
        emitPathSegmentRefs(refs, attrVal, segments, pathBase, pathForResolution);
    }

    /**
     * Resolves a markup extension parameter name to the best matching PSI declaration:
     * <ol>
     *   <li>{@code @NamedArg("paramName")} constructor parameter on {@code extClass}.</li>
     *   <li>JavaFX property method: {@code paramNameProperty()}.</li>
     *   <li>Setter method: {@code setParamName(T)}.</li>
     * </ol>
     * This mirrors the priority order used by
     * {@code Fxml2AttributeAnnotator.collectKnownExtensionParams}.
     */
    private static @Nullable PsiElement resolveMarkupExtParam(
            @NotNull String paramName, @NotNull PsiClass extClass) {

        // 1. @NamedArg constructor parameters
        for (PsiMethod ctor : extClass.getConstructors()) {
            for (PsiParameter p : ctor.getParameterList().getParameters()) {
                if (paramName.equals(Fxml2NamedArgResolver.namedArgValue(p))) return p;
            }
        }
        // 2. JavaFX property method: paramNameProperty()
        String propertyMethodName = paramName + "Property";
        for (PsiMethod m : extClass.findMethodsByName(propertyMethodName, true)) {
            if (m.getParameterList().getParametersCount() == 0) return m;
        }
        // 3. Setter method: setParamName(T)
        if (!paramName.isEmpty()) {
            String setterName = "set" + Character.toUpperCase(paramName.charAt(0))
                    + paramName.substring(1);
            for (PsiMethod m : extClass.findMethodsByName(setterName, true)) {
                if (m.getParameterList().getParametersCount() == 1) return m;
            }
        }
        return null;
    }

    /**
     * Returns the index of the first whitespace character in {@code s} that is not inside
     * a literal {@code <...>} generic type-argument block, or {@code -1} if none.
     *
     * <p>Mirrors {@code Fxml2AttributeAnnotator.indexOfWhitespaceME} so both the annotator
     * and the reference provider use the same splitting logic.
     *
     * <p>Two angle-bracket forms are handled:
     * <ul>
     *   <li><b>Literal</b>: {@code MyMarkupExtension<String> ...}: depth tracking prevents
     *       splitting inside the {@code <...>} block.</li>
     *   <li><b>XML-entity</b>: {@code MyMarkupExtension&lt;String&gt; ...}: no literal
     *       {@code <}/{@code >} characters, so depth stays 0 and the first whitespace
     *       found is correctly after the closing {@code &gt;}.</li>
     * </ul>
     */
    private static int indexOfWhitespaceMEInner(@NotNull String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') { if (depth > 0) depth--; }
            else if (depth == 0 && Character.isWhitespace(c)) return i;
        }
        return -1;
    }

    // -----------------------------------------------------------------------
    // fx:id reference provider
    // -----------------------------------------------------------------------

    /**
     * Provides references for each {@code fx:id} attribute value:
     * <ol>
     *   <li>A {@link Fxml2FxIdReference} (self-reference): marks this element as a PSI
     *       "named declaration" so that "highlight usages" lights up all binding-expression
     *       occurrences of the id within the FXML file.</li>
     *   <li>An optional code-behind field reference: when the code-behind class (given by
     *       {@code fx:subclass}) contains a field or no-arg method with the same name, a soft
     *       reference resolving to that member is also emitted.  This allows Ctrl+click on
     *       the fx:id value to offer navigation to the injected field, and makes
     *       "Find Usages" of the fx:id show the code-behind field as a related element.</li>
     * </ol>
     */
    private static final class FxIdReferenceProvider extends PsiReferenceProvider {

        @Override
        public PsiReference @NotNull [] getReferencesByElement(
                @NotNull PsiElement element, @NotNull ProcessingContext context) {

            if (!(element instanceof XmlAttributeValue attrVal)) return PsiReference.EMPTY_ARRAY;
            if (!(attrVal.getContainingFile() instanceof XmlFile xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2FileType.isFxml2(xmlFile)) return PsiReference.EMPTY_ARRAY;

            String value = attrVal.getValue();
            if (value.isBlank()) return PsiReference.EMPTY_ARRAY;

            // Self-reference: used for "highlight usages" anchoring.
            // No field reference here: adding a reference that resolves to the generated
            // PsiField would cause IntelliJ's default GotoDeclarationHandler to intercept
            // Ctrl+click on the fx:id declaration and navigate directly to the generated
            // base class field, bypassing our PsiSymbolDeclarationProvider which routes
            // the cursor to the "Show Usages" popup instead.
            return new PsiReference[]{ new Fxml2FxIdReference(attrVal) };
        }
    }

    // -----------------------------------------------------------------------
    // Binding expression provider
    // -----------------------------------------------------------------------

    private static final class BindingReferenceProvider extends PsiReferenceProvider {

        @Override
        public PsiReference @NotNull [] getReferencesByElement(
                @NotNull PsiElement element, @NotNull ProcessingContext context) {

            if (!(element instanceof XmlAttributeValue attrVal)) return PsiReference.EMPTY_ARRAY;
            if (!(attrVal.getContainingFile() instanceof XmlFile xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2FileType.isFxml2(xmlFile)) return PsiReference.EMPTY_ARRAY;

            // fx:context attribute values are handled exclusively by FxContextReferenceProvider,
            // which resolves the binding path against the code-behind class (not the context class).
            if (attrVal.getParent() instanceof XmlAttribute parentAttr
                    && "context".equals(parentAttr.getLocalName())
                    && Fxml2ImportResolver.FXML2_NAMESPACE.equals(parentAttr.getNamespace())) {
                return PsiReference.EMPTY_ARRAY;
            }

            String rawValue = attrVal.getValue();
            if (rawValue.isBlank()) return PsiReference.EMPTY_ARRAY;

            Fxml2BindingExpressionParser.ParsedExpression expr =
                    Fxml2BindingExpressionParser.parseExpression(rawValue);
            if (expr == null) return PsiReference.EMPTY_ARRAY;

            // Get the context tag (parent XmlTag of the attribute)
            XmlTag contextTag = getContextTag(attrVal);

            String strippedPath = expr.strippedPath();
            // The :: (observable-selection) operator is handled directly by the resolver,
            // which splits on both '.' and '::' and keeps raw property types where needed.

            // Parse context selector from stripped path
            ContextSelector selector = Fxml2BindingExpressionParser.parseContextSelector(strippedPath);
            String pathForResolution = selector != null ? selector.remainingPath() : strippedPath;
            // Offset within rawValue (1-based because quotes), for the path after the selector.
            int pathBase = 1 + expr.strippedPathOffset()
                    + (selector != null ? selector.selectorLength() : 0);

            // Determine start class based on context selector
            PsiClass startClass;
            if (contextTag != null) {
                startClass = Fxml2BindingPathResolver.resolveStartClass(selector, contextTag, xmlFile);
            } else {
                startClass = Fxml2BindingPathResolver.resolveCodeBehindClass(xmlFile);
            }
            if (startClass == null && (selector == null || selector.isThis())) {
                startClass = Fxml2BindingPathResolver.resolveCodeBehindClass(xmlFile);
            }

            // The notation reference on the binding prefix (e.g. $, ${, #{, >{) always points to the
            // online expression-docs page regardless of whether the path resolves to a Java declaration.
            // It is emitted here, before the startClass guard, so Ctrl+click on the prefix works even
            // when no code-behind class is present in the file.
            List<PsiReference> refs = new ArrayList<>(8);
            refs.add(new Fxml2ExpressionReference(attrVal));
            refs.add(new Fxml2BindingNotationReference(
                    attrVal,
                    new TextRange(1, 1 + expr.prefixLength())));

            if (startClass == null) {
                // Even without a resolvable code-behind class, try to resolve the first
                // binding segment against fx:id declarations in the file. This ensures
                // that binding expressions create references even when the Kotlin/Java
                // code-behind class cannot be resolved.
                if (!pathForResolution.isEmpty()) {
                    List<Fxml2BindingPathResolver.Segment> fxIdSegments =
                            resolveFxIdOnlySegments(pathForResolution, xmlFile, expr.kind(), pathBase);
                    emitPathSegmentRefs(refs, attrVal, fxIdSegments, pathBase, pathForResolution);
                }
                return refs.toArray(PsiReference.EMPTY_ARRAY);
            }

            GlobalSearchScope scope = xmlFile.getResolveScope();
            List<Fxml2BindingPathResolver.Segment> segments = pathForResolution.isEmpty()
                    ? List.of()
                    : Fxml2BindingPathResolver.resolve(pathForResolution, startClass, scope,
                            expr.kind(), xmlFile);

            // Boolean operator reference:
            //   ! (NOT)    -> BooleanBindings.isNot  (Boolean), isZero  (Number), isNull  (other)
            //   !! (BOOLIFY) -> BooleanBindings.isNotZero (Number), isNotNull (other);
            //                  no wrapping on Boolean (compiler yields child directly)
            // Falls back to BooleanExpression.not() when the markup library is absent.
            int opLen = expr.operatorLength();
            if (opLen > 0) {
                int opStart = 1 + expr.pathOffset();
                int opEnd = opStart + opLen;
                PsiClass resultType = segments.isEmpty() ? null : segments.getLast().resultType();
                PsiMethod method = resolveBooleanOperatorMethod(
                        xmlFile.getProject(), opLen, resultType);
                if (method != null) {
                    // soft=false: the operator reference must sort FIRST in PsiMultiReference
                    // tie-breaking (hard < soft) because the binding-notation reference range
                    // ends exactly at opStart, and IntelliJ's containsOffset() uses an
                    // inclusive end check: meaning both references are collected at opStart.
                    // Making this reference hard (not soft) ensures chooseReference() returns
                    // it and cannotChoose() stays false.
                    refs.add(new Fxml2AttributeValueReference(attrVal,
                            new TextRange(opStart, opEnd), method, /* soft= */ false));
                }
            }

            // Context selector reference: self/parent -> navigate to the target XmlTag
            if (selector != null && contextTag != null) {
                // Position of the selector token within the attribute value text (1-based, after quote)
                int selectorStart = 1 + expr.strippedPathOffset();
                addSelectorRef(refs, attrVal, selectorStart, selector, contextTag, xmlFile.getProject());
            }

            // Path segment references.
            // Walk strippedPath to get correct offsets: :: is a 2-char separator, . is 1 char.
            emitPathSegmentRefs(refs, attrVal, segments, pathBase, pathForResolution);

            // Secondary param path references (format= / converter=).
            // "inverseMethod" is a function-call path, not a property path, so it is skipped here;
            // the annotator handles it separately via annotateFunctionCallPath().
            if (expr.hasParam() && !"inverseMethod".equals(expr.paramName())) {
                String paramName = expr.paramName();
                int paramPathOffset = expr.paramPathOffset();

                // Param name keyword reference: Ctrl+click on "format" or "converter" opens
                // the string-conversion documentation page.
                if (paramName != null && paramPathOffset >= 0) {
                    int eqPos = rawValue.lastIndexOf('=', paramPathOffset - 1);
                    if (eqPos > 0) {
                        String beforeEq = rawValue.substring(0, eqPos).stripTrailing();
                        int nameEnd = beforeEq.length();
                        int nameStart = nameEnd - paramName.length();
                        if (nameStart >= 0
                                && rawValue.regionMatches(nameStart, paramName, 0, paramName.length())) {
                            refs.add(new Fxml2ConversionParamNameReference(
                                    attrVal,
                                    new TextRange(1 + nameStart, 1 + nameStart + paramName.length())));
                        }
                    }
                }

                String paramPath = expr.paramPath();
                if (paramPath != null && !paramPath.isBlank() && paramPathOffset >= 0) {
                    // Use OBSERVE kind so the resolver prefers xProperty() over setter, matching
                    // the navigation behavior of other read-access binding paths.
                    List<Fxml2BindingPathResolver.Segment> paramSegments =
                            Fxml2BindingPathResolver.resolve(
                                    paramPath, startClass, scope,
                                    Fxml2BindingNotationReference.Kind.OBSERVE, xmlFile);
                    emitPathSegmentRefs(refs, attrVal, paramSegments, 1 + paramPathOffset, paramPath);
                }
            }

            return refs.toArray(PsiReference.EMPTY_ARRAY);
        }

        /**
         * Returns the method that the fxml-compiler will call for a {@code !} ({@code opLen=1})
         * or {@code !!} ({@code opLen=2}) boolean operator on a binding path whose last resolved
         * segment has the unwrapped type {@code resultType}.
         *
         * <p>Mirrors {@code EmitMapToBooleanNode.emitSharedImpl()} logic:
         * <ul>
         *   <li>Boolean type: {@code !} -> {@code BooleanBindings.isNot}; {@code !!} -> no-op
         *       (compiler yields the child observable directly), so {@code null} is returned.</li>
         *   <li>Number subtype: {@code !} -> {@code BooleanBindings.isZero};
         *       {@code !!} -> {@code BooleanBindings.isNotZero}.</li>
         *   <li>Other / unknown: {@code !} -> {@code BooleanBindings.isNull};
         *       {@code !!} -> {@code BooleanBindings.isNotNull}.</li>
         * </ul>
         *
         * <p>Falls back to {@code BooleanExpression.not()} for {@code !} when the
         * {@code BooleanBindings} class is absent (the markup runtime library is not on the classpath).
         *
         * @param project    the current project
         * @param opLen      1 for {@code !} (NOT), 2 for {@code !!} (BOOLIFY)
         * @param resultType unwrapped result type of the bound path segment (may be {@code null})
         * @return the best-matching {@link PsiMethod}, or {@code null} if none applies
         */
        private static @Nullable PsiMethod resolveBooleanOperatorMethod(
                @NotNull Project project,
                int opLen,
                @Nullable PsiClass resultType) {

            boolean isSingleBang = (opLen == 1); // ! = NOT, !! = BOOLIFY

            // Prefer BooleanBindings from the markup runtime library when available:
            // this is the primary resolution target when the markup library is on the classpath.
            // Use allScope so the class is always found regardless of whether it is
            // in a library or on the project's source root.
            GlobalSearchScope allScope = GlobalSearchScope.allScope(project);
            PsiClass bindings = JavaPsiFacade.getInstance(project)
                    .findClass("org.jfxcore.markup.runtime.BooleanBindings", allScope);
            if (bindings != null) {
                String methodName = chooseBooleanBindingsMethod(isSingleBang, resultType);
                if (methodName == null) return null; // e.g. !! on Boolean -> compiler no-op
                PsiMethod[] methods = bindings.findMethodsByName(methodName, false);
                if (methods.length > 0) return methods[0];
                return null;
            }

            // Fallback: BooleanExpression.not() for ! only, when the markup runtime library
            // is not on the classpath.
            if (isSingleBang) {
                PsiClass boolExpr = JavaPsiFacade.getInstance(project)
                        .findClass("javafx.beans.binding.BooleanExpression", allScope);
                if (boolExpr != null) {
                    PsiMethod[] methods = boolExpr.findMethodsByName("not", false);
                    if (methods.length > 0) return methods[0];
                }
            }
            return null;
        }

        /**
         * Chooses the {@code BooleanBindings} method name for the given operator and result type,
         * mirroring the {@code EmitMapToBooleanNode.emitSharedImpl()} dispatch table.
         *
         * @return the method name, or {@code null} when the operator is a compile-time no-op
         *         (i.e., {@code !!} on a {@code Boolean} observable: the compiler yields the
         *         child directly without any wrapper)
         */
        private static @Nullable String chooseBooleanBindingsMethod(
                boolean isSingleBang,
                @Nullable PsiClass resultType) {

            if (resultType != null) {
                String qn = resultType.getQualifiedName();
                // Boolean: ! -> isNot, !! -> no-op (compiler skips wrapping)
                if ("java.lang.Boolean".equals(qn)) {
                    return isSingleBang ? "isNot" : null;
                }
                // Number subtype: ! -> isZero, !! -> isNotZero
                if (InheritanceUtil.isInheritor(resultType, "java.lang.Number")) {
                    return isSingleBang ? "isZero" : "isNotZero";
                }
            }
            // Unknown or other reference type: ! -> isNull, !! -> isNotNull
            return isSingleBang ? "isNull" : "isNotNull";
        }

        /**
         * Resolves the first segment of a binding path against {@code fx:id} declarations
         * in the file, without requiring a resolvable code-behind class.
         *
         * <p>This is used when {@code startClass} is null (e.g., Kotlin class not yet compiled)
         * but we still need to create {@link Fxml2BindingSegmentReference} instances for
         * binding expressions like {@code "$btn2.text"} so that tools like the
         * "unused fx:id" inspection can correctly identify unused declarations.
         */
        private static List<Fxml2BindingPathResolver.Segment> resolveFxIdOnlySegments(
                @NotNull String path,
                @NotNull com.intellij.psi.xml.XmlFile xmlFile,
                @Nullable Fxml2BindingNotationReference.Kind kind,
                int pathBase) {

            List<Fxml2BindingPathResolver.Segment> result = new ArrayList<>();
            String[] parts = path.split("\\.(?![^{}]*})", -1);
            if (parts.length == 0) return result;

            String firstSegment = parts[0];
            if (firstSegment.isEmpty()) return result;

            // Try to resolve against fx:id declarations in the file.
            PsiField fxIdField = Fxml2BindingPathResolver.resolveFxId(firstSegment, xmlFile);
            if (fxIdField != null) {
                PsiClass nextClass = PsiUtil.resolveClassInType(fxIdField.getType());
                result.add(new Fxml2BindingPathResolver.Segment(
                        firstSegment, fxIdField, nextClass,
                        false, false, pathBase));

                // If there are more segments, try to resolve them against the field's type.
                if (parts.length > 1 && nextClass != null) {
                    GlobalSearchScope scope = xmlFile.getResolveScope();
                    String remaining = String.join(".", Arrays.copyOfRange(parts, 1, parts.length));
                    List<Fxml2BindingPathResolver.Segment> moreSegments =
                            Fxml2BindingPathResolver.resolve(remaining, nextClass, scope, kind, xmlFile);
                    result.addAll(moreSegments);
                }
            }

            return result;
        }

    }

    /**
     * Builds a {@link com.intellij.psi.impl.light.LightFieldBuilder} whose type is the
     * resolved class of {@code targetTag} and whose name is {@code selectorText}
     * (e.g. {@code "self"}, {@code "parent[1]"}, {@code "this"}).
     * The light field navigates to {@code targetTag} on Ctrl+click and causes
     * IntelliJ's Java documentation provider to render a {@code <Type> <selector>}
     * tooltip on hover, matching the behavior of {@code fx:id} references.
     *
     * <p>Shared between {@link BindingReferenceProvider} and
     * {@link ElementNotationSourceReferenceProvider}.
     */
    private static @NotNull com.intellij.psi.impl.light.LightFieldBuilder makeSelectorNavElement(
            @NotNull String selectorText,
            @NotNull XmlTag targetTag,
            @NotNull com.intellij.openapi.project.Project project) {
        // When the target is the root tag, the actual runtime type is the fx:subclass
        // code-behind class (which extends the root element type).  Use that for the
        // tooltip so the user sees e.g. "MyView this" rather than "BorderPane this".
        PsiClass tagClass = null;
        if (targetTag.getParentTag() == null
                && targetTag.getContainingFile() instanceof com.intellij.psi.xml.XmlFile xmlFile) {
            tagClass = Fxml2BindingPathResolver.resolveCodeBehindClass(xmlFile);
        }
        if (tagClass == null) {
            tagClass = targetTag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd
                    ? cd.getPsiClass() : null;
        }
        PsiType fieldType;
        if (tagClass != null) {
            fieldType = com.intellij.psi.JavaPsiFacade.getElementFactory(project).createType(tagClass);
        } else {
            fieldType = PsiType.getJavaLangObject(
                    com.intellij.psi.PsiManager.getInstance(project),
                    com.intellij.psi.search.GlobalSearchScope.allScope(project));
        }
        return new com.intellij.psi.impl.light.LightFieldBuilder(selectorText, fieldType, targetTag)
                .setModifiers(com.intellij.psi.PsiModifier.PUBLIC);
    }

    // -----------------------------------------------------------------------
    // fx:context attribute reference provider
    // -----------------------------------------------------------------------

    /**
     * Provides references for the {@code fx:context="$myContext"} attribute value.
     * Resolves the path against the code-behind class, same as a regular binding.
     */
    private static final class FxContextReferenceProvider extends PsiReferenceProvider {

        @Override
        public PsiReference @NotNull [] getReferencesByElement(
                @NotNull PsiElement element, @NotNull ProcessingContext context) {

            if (!(element instanceof XmlAttributeValue attrVal)) return PsiReference.EMPTY_ARRAY;
            if (!(attrVal.getContainingFile() instanceof XmlFile xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2FileType.isFxml2(xmlFile)) return PsiReference.EMPTY_ARRAY;

            String rawValue = attrVal.getValue();
            if (rawValue.isBlank()) return PsiReference.EMPTY_ARRAY;

            Fxml2BindingExpressionParser.ParsedExpression expr =
                    Fxml2BindingExpressionParser.parseExpression(rawValue);
            if (expr == null || expr.strippedPath().isEmpty()) return PsiReference.EMPTY_ARRAY;

            PsiClass codeBehind = Fxml2BindingPathResolver.resolveCodeBehindClass(xmlFile);
            if (codeBehind == null) return PsiReference.EMPTY_ARRAY;

            GlobalSearchScope scope = xmlFile.getResolveScope();
            List<Fxml2BindingPathResolver.Segment> segments =
                    Fxml2BindingPathResolver.resolve(expr.strippedPath(), codeBehind, scope, null, xmlFile);
            if (segments.isEmpty()) return PsiReference.EMPTY_ARRAY;

            // Base offset of the stripped path within the attribute value text (1 for opening quote).
            int pathBase = 1 + expr.strippedPathOffset();
            List<PsiReference> refs = new ArrayList<>(segments.size() + 1);
            refs.add(new Fxml2ExpressionReference(attrVal));

            for (Fxml2BindingPathResolver.Segment seg : segments) {
                int segStart = pathBase + seg.pathOffset();
                int segEnd   = segStart + seg.name().length();
                refs.add(new Fxml2BindingSegmentReference(
                        attrVal,
                        new TextRange(segStart, segEnd),
                        seg.declaration()));
            }

            return refs.toArray(PsiReference.EMPTY_ARRAY);
        }
    }

    // -----------------------------------------------------------------------
    // Element-notation binding source reference provider
    // (<fx:Observe source="path"/>, <fx:Evaluate source="path"/>, etc.)
    // -----------------------------------------------------------------------

    /**
     * Provides a soft URL reference on the {@code source} attribute NAME of element-notation
     * binding tags ({@code <fx:Observe>}, {@code <fx:Evaluate>}, {@code <fx:Push>},
     * {@code <fx:Synchronize>}) so that Ctrl+click on the {@code source=} name opens the
     * compiled-expressions documentation page.
     */
    private static final class ElementNotationSourceAttrNameReferenceProvider extends PsiReferenceProvider {

        private static final java.util.Set<String> FX_BINDING_TAG_NAMES =
                java.util.Set.of("Evaluate", "Observe", "Push", "Synchronize");

        @Override
        public PsiReference @NotNull [] getReferencesByElement(
                @NotNull PsiElement element, @NotNull ProcessingContext context) {

            if (!(element instanceof XmlAttribute attr)) return PsiReference.EMPTY_ARRAY;
            if (!(attr.getContainingFile() instanceof XmlFile xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2FileType.isFxml2(xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!"source".equals(attr.getName())) return PsiReference.EMPTY_ARRAY;
            if (!(attr.getParent() instanceof XmlTag fxTag)) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2ImportResolver.FXML2_NAMESPACE.equals(fxTag.getNamespace())) return PsiReference.EMPTY_ARRAY;
            if (!FX_BINDING_TAG_NAMES.contains(fxTag.getLocalName())) return PsiReference.EMPTY_ARRAY;

            // Cover only the attribute name token "source" (not the "=" or value).
            TextRange range = new TextRange(0, "source".length());
            return new PsiReference[]{
                new PsiReferenceBase<>(attr, range, /* soft= */ true) {
                    @Override
                    public @NotNull PsiElement resolve() {
                        return new Fxml2NamespaceUrlReference.UrlNavigationTarget(
                                attr, Fxml2BindingNotationReference.EXPRESSION_DOCS_URL);
                    }
                }
            };
        }
    }

    /**
     * Provides per-segment {@link Fxml2BindingSegmentReference}s for the {@code source=}
     * attribute values of element-notation binding tags:
     * {@code <fx:Evaluate>}, {@code <fx:Observe>}, {@code <fx:Push>}, and
     * {@code <fx:Synchronize>}.
     *
     * <p>Unlike short-form attribute bindings ({@code $path}, {@code ${path}}, etc.),
     * the element-notation {@code source=} value is a plain binding path with no prefix
     * character.  It still supports:
     * <ul>
     *   <li>Context selectors: {@code self/prop}, {@code parent/prop},
     *       {@code parent[N]/prop}, {@code parent<Type>/prop}</li>
     *   <li>The {@code ..} content prefix: {@code ..items}</li>
     *   <li>Class-qualifier prefixes: {@code ClassName.staticProp}</li>
     * </ul>
     *
     * <p>For each resolved segment a soft {@link Fxml2BindingSegmentReference} is emitted;
     * for unresolved uppercase-starting segments (class qualifier positions) a hard
     * {@link UnresolvedClassSegmentReference} is emitted so that the IDE offers the
     * "Add import" quick-fix.  Context-selector tokens receive a soft reference that
     * navigates to the matching {@link XmlTag}.
     */
    private static final class ElementNotationSourceReferenceProvider extends PsiReferenceProvider {

        /** Binding kinds for the fx:* element-notation binding tags. */
        private static final Map<String, Fxml2BindingNotationReference.Kind> FX_BINDING_TAG_KINDS = Map.of(
                "Evaluate",    Fxml2BindingNotationReference.Kind.EVALUATE,
                "Observe",     Fxml2BindingNotationReference.Kind.OBSERVE,
                "Push",        Fxml2BindingNotationReference.Kind.PUSH,
                "Synchronize", Fxml2BindingNotationReference.Kind.SYNCHRONIZE);

        @Override
        public PsiReference @NotNull [] getReferencesByElement(
                @NotNull PsiElement element, @NotNull ProcessingContext context) {

            if (!(element instanceof XmlAttributeValue attrVal)) return PsiReference.EMPTY_ARRAY;
            if (!(attrVal.getContainingFile() instanceof XmlFile xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2FileType.isFxml2(xmlFile)) return PsiReference.EMPTY_ARRAY;

            if (!(attrVal.getParent() instanceof XmlAttribute attr)) return PsiReference.EMPTY_ARRAY;
            if (!"source".equals(attr.getName())) return PsiReference.EMPTY_ARRAY;
            if (!(attr.getParent() instanceof XmlTag fxTag)) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2ImportResolver.FXML2_NAMESPACE.equals(fxTag.getNamespace())) return PsiReference.EMPTY_ARRAY;

            Fxml2BindingNotationReference.Kind baseKind = FX_BINDING_TAG_KINDS.get(fxTag.getLocalName());
            if (baseKind == null) return PsiReference.EMPTY_ARRAY;

            String rawPath = attrVal.getValue();
            if (rawPath.isBlank()) return PsiReference.EMPTY_ARRAY;

            // Resolve the default start class (no selector yet)
            PsiClass startClass = Fxml2BindingPathResolver.resolveStartClass(null, fxTag, xmlFile);
            if (startClass == null) return PsiReference.EMPTY_ARRAY;

            // Parse context selector (self/, parent/, this., etc.)
            ContextSelector selector = Fxml2BindingExpressionParser.parseContextSelector(rawPath);
            String remainingPath = selector != null ? selector.remainingPath() : rawPath;
            if (remainingPath.isBlank()) return PsiReference.EMPTY_ARRAY;

            if (selector != null) {
                startClass = Fxml2BindingPathResolver.resolveStartClass(selector, fxTag, xmlFile);
                if (startClass == null) return PsiReference.EMPTY_ARRAY;
            }

            // Handle ".." content prefix
            Fxml2BindingNotationReference.Kind kind = baseKind;
            int dotDotLen = 0;
            if (remainingPath.startsWith("..")) {
                kind = switch (baseKind) {
                    case EVALUATE    -> Fxml2BindingNotationReference.Kind.EVALUATE_CONTENT;
                    case OBSERVE     -> Fxml2BindingNotationReference.Kind.OBSERVE_CONTENT;
                    case PUSH        -> Fxml2BindingNotationReference.Kind.PUSH_CONTENT;
                    case SYNCHRONIZE -> Fxml2BindingNotationReference.Kind.SYNCHRONIZE_CONTENT;
                    default -> baseKind;
                };
                dotDotLen = 2;
                remainingPath = remainingPath.substring(2).trim();
                if (remainingPath.isBlank()) return PsiReference.EMPTY_ARRAY;
            }

            GlobalSearchScope scope = xmlFile.getResolveScope();
            List<Fxml2BindingPathResolver.Segment> segments =
                    Fxml2BindingPathResolver.resolve(remainingPath, startClass, scope, kind, xmlFile);

            List<PsiReference> refs = new ArrayList<>();

            // Context selector reference: self/parent/this -> navigate to the target XmlTag
            if (selector != null) {
                // +1 for opening quote in XmlAttributeValue text
                addSelectorRef(refs, attrVal, 1, selector, fxTag, xmlFile.getProject());
            }

            // Path segment references.
            // pathBase = 1 (opening quote) + selectorLength + dotDotLen
            int selectorLength = selector != null ? selector.selectorLength() : 0;
            int pathBase = 1 + selectorLength + dotDotLen;

            emitPathSegmentRefs(refs, attrVal, segments, pathBase, remainingPath);

            return refs.isEmpty() ? PsiReference.EMPTY_ARRAY : refs.toArray(PsiReference.EMPTY_ARRAY);
        }
    }

    // -----------------------------------------------------------------------
    // Literal value provider (enum constants, static fields, Color, @NamedArg)
    // -----------------------------------------------------------------------


    private static final class LiteralValueReferenceProvider extends PsiReferenceProvider {

        @Override
        public PsiReference @NotNull [] getReferencesByElement(
                @NotNull PsiElement element, @NotNull ProcessingContext context) {

            if (!(element instanceof XmlAttributeValue attrVal)) return PsiReference.EMPTY_ARRAY;
            if (!(attrVal.getContainingFile() instanceof XmlFile xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2FileType.isFxml2(xmlFile)) return PsiReference.EMPTY_ARRAY;

            String rawValue = attrVal.getValue();
            if (rawValue.isBlank()) return PsiReference.EMPTY_ARRAY;

            // Skip binding expressions and prefix-shorthand values: handled by dedicated providers.
            java.util.Map<Character, String> prefixMappings = Fxml2ImportResolver.parsePrefixMappings(xmlFile);
            if (Fxml2BindingExpressionParser.looksLikeBindingExpression(rawValue, prefixMappings)) return PsiReference.EMPTY_ARRAY;

            if (!(attrVal.getParent() instanceof XmlAttribute attr)) return PsiReference.EMPTY_ARRAY;
            String attrName = attr.getName();

            // Skip namespace and fx: attributes.
            if (Fxml2XmlUtil.isNonPropertyAttribute(attrName)) return PsiReference.EMPTY_ARRAY;
            if (!(attr.getParent() instanceof XmlTag tag)) return PsiReference.EMPTY_ARRAY;

            GlobalSearchScope scope = xmlFile.getResolveScope();
            Fxml2AttributeValueResolver.Result result = resolveAttributeValue(
                    attrName, rawValue, tag, scope);

            // For Class<T> properties the base resolver (without XmlFile) returns STRING
            // (no navigation target). Try to resolve and navigate here where XmlFile is available.
            if ((result == null || result.declaration() == null) && !attrName.contains(".")) {
                if (tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd) {
                    PsiClass ownerClass = cd.getPsiClass();
                    if (ownerClass != null) {
                        PsiType propType = Fxml2AttributeValueResolver.propertyType(
                                ownerClass, attrName, List.of());
                        if (propType != null) {
                            PsiClass propClass = PsiUtil.resolveClassInType(propType);
                            if (propClass != null && "java.lang.Class".equals(propClass.getQualifiedName())) {
                                PsiClass literalClass = Fxml2AttributeValueResolver.resolveClassLiteralRef(
                                        rawValue, propType, xmlFile, scope);
                                if (literalClass != null) {
                                    TextRange range = new TextRange(1, 1 + rawValue.length());
                                    return new PsiReference[]{
                                            new Fxml2AttributeValueReference(attrVal, range, literalClass) };
                                }
                                return PsiReference.EMPTY_ARRAY;
                            }
                        }
                    }
                }
            }

            if (result == null || result.declaration() == null) return PsiReference.EMPTY_ARRAY;

            TextRange range = new TextRange(1, 1 + rawValue.length());
            return new PsiReference[]{ new Fxml2AttributeValueReference(attrVal, range, result.declaration()) };
        }
    }

    /**
     * Shared logic: resolves a literal attribute value for a named property on a tag.
     * Handles static, chained-instance, and plain-instance property names.
     */
    public static @Nullable Fxml2AttributeValueResolver.Result resolveAttributeValue(
            @NotNull String attrName,
            @NotNull String rawValue,
            @NotNull XmlTag tag,
            @NotNull GlobalSearchScope scope) {

        if (attrName.contains(".")) {
            XmlAttribute attr = tag.getAttribute(attrName);
            if (attr != null && attr.getDescriptor() instanceof Fxml2StaticPropertyAttributeDescriptor sd) {
                PsiClass declaringClass = sd.getDeclaringClass();
                if (declaringClass == null) return null;
                String propName = attrName.substring(attrName.lastIndexOf('.') + 1);
                return Fxml2AttributeValueResolver.resolveStatic(declaringClass, propName, rawValue);
            }
            // Chained instance property
            if (!(tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd)) return null;
            PsiClass ownerClass = cd.getPsiClass();
            if (ownerClass == null) return null;
            Object[] chain = Fxml2XmlUtil.resolveChainedPropertyOwner(ownerClass, attrName);
            if (chain == null) return null;
            PsiClass finalClass = (PsiClass) chain[0];
            String lastProp = (String) chain[1];
            return Fxml2AttributeValueResolver.resolve(finalClass, lastProp, rawValue, scope);
        }

        // Plain instance property
        if (!(tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd)) return null;
        PsiClass ownerClass = cd.getPsiClass();
        if (ownerClass == null) return null;
        return Fxml2AttributeValueResolver.resolve(ownerClass, attrName, rawValue, scope);
    }

    private static final class NamespaceUrlReferenceProvider extends PsiReferenceProvider {

        @Override
        public PsiReference @NotNull [] getReferencesByElement(
                @NotNull PsiElement element, @NotNull ProcessingContext context) {
            if (!(element instanceof XmlAttributeValue attrVal)) return PsiReference.EMPTY_ARRAY;
            if (!(attrVal.getContainingFile() instanceof XmlFile xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2FileType.isFxml2(xmlFile)) return PsiReference.EMPTY_ARRAY;
            String docUrl = NAMESPACE_DOC_URLS.get(attrVal.getValue());
            if (docUrl == null) return PsiReference.EMPTY_ARRAY;
            return new PsiReference[] { new Fxml2NamespaceUrlReference(attrVal, docUrl) };
        }
    }

    // -----------------------------------------------------------------------
    // xml:space attribute name + value -> open W3C whitespace spec in browser
    // -----------------------------------------------------------------------

    private static final String XML_SPACE_SPEC_URL = "https://www.w3.org/TR/xml/#sec-white-space";

    /**
     * Reference on the {@code xml:space} attribute <em>name</em> token.
     * Registered on {@link XmlAttribute}; covers the full {@code "xml:space"} name range.
     * Ctrl+click opens the W3C XML whitespace-handling specification.
     */
    private static final class XmlSpaceAttrNameReferenceProvider extends PsiReferenceProvider {

        @Override
        public PsiReference @NotNull [] getReferencesByElement(
                @NotNull PsiElement element, @NotNull ProcessingContext context) {
            if (!(element instanceof XmlAttribute attr)) return PsiReference.EMPTY_ARRAY;
            if (!(attr.getContainingFile() instanceof XmlFile xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2FileType.isFxml2(xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!"xml:space".equals(attr.getName())) return PsiReference.EMPTY_ARRAY;

            // Cover only the local-name part "space" (after the "xml:" prefix),
            // so that hovering over "xml" does not produce an underline link -
            // only "space" and the attribute value are navigatable.
            String attrName = attr.getName(); // "xml:space"
            int colonIdx = attrName.indexOf(':');
            int localStart = colonIdx >= 0 ? colonIdx + 1 : 0;
            TextRange range = TextRange.create(localStart, attrName.length());
            return new PsiReference[] {
                    new PsiReferenceBase<>(attr, range, /* soft= */ true) {
                        @Override
                        public @NotNull PsiElement resolve() {
                            return new Fxml2NamespaceUrlReference.UrlNavigationTarget(
                                    attr, XML_SPACE_SPEC_URL);
                        }
                    }
            };
        }
    }

    /**
     * Reference on the {@code xml:space} attribute <em>value</em> (e.g. {@code "preserve"}).
     * Registered on {@link XmlAttributeValue} whose parent is {@code xml:space}.
     * Ctrl+click opens the W3C XML whitespace-handling specification.
     */
    private static final class XmlSpaceAttrValueReferenceProvider extends PsiReferenceProvider {

        @Override
        public PsiReference @NotNull [] getReferencesByElement(
                @NotNull PsiElement element, @NotNull ProcessingContext context) {
            if (!(element instanceof XmlAttributeValue attrVal)) return PsiReference.EMPTY_ARRAY;
            if (!(attrVal.getContainingFile() instanceof XmlFile xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2FileType.isFxml2(xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!(attrVal.getParent() instanceof XmlAttribute attr)) return PsiReference.EMPTY_ARRAY;
            if (!"xml:space".equals(attr.getName())) return PsiReference.EMPTY_ARRAY;
            return new PsiReference[] { new Fxml2NamespaceUrlReference(attrVal, XML_SPACE_SPEC_URL) };
        }
    }

    // -----------------------------------------------------------------------
    // fx:typeArguments provider: per-segment class navigation
    // -----------------------------------------------------------------------

    /**
     * Produces one {@link ClassSegmentReference} per dot-separated segment of an
     * {@code fx:typeArguments} value that can be resolved to a Java class.
     *
     * <p>Example: {@code com.example.OuterViewModel.Row}
     * <ul>
     *   <li>{@code com} -> navigates to the {@code com} package</li>
     *   <li>{@code example} -> navigates to the {@code com.example} package</li>
     *   <li>{@code OuterViewModel} -> navigates to that class</li>
     *   <li>{@code Row} -> navigates to the nested class {@code OuterViewModel$Row}</li>
     * </ul>
     */
    private static final class TypeArgumentsReferenceProvider extends PsiReferenceProvider {

        @Override
        public PsiReference @NotNull [] getReferencesByElement(
                @NotNull PsiElement element, @NotNull ProcessingContext context) {
            if (!(element instanceof XmlAttributeValue attrVal)) return PsiReference.EMPTY_ARRAY;
            if (!(attrVal.getContainingFile() instanceof XmlFile xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2FileType.isFxml2(xmlFile)) return PsiReference.EMPTY_ARRAY;

            // Only handle fx:typeArguments attributes.
            if (!(attrVal.getParent() instanceof XmlAttribute attr)) return PsiReference.EMPTY_ARRAY;
            if (!"typeArguments".equals(attr.getLocalName())) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2ImportResolver.FXML2_NAMESPACE.equals(attr.getNamespace())) return PsiReference.EMPTY_ARRAY;

            String rawValue = attrVal.getValue();
            if (rawValue.isBlank()) return PsiReference.EMPTY_ARRAY;

            // fx:typeArguments may be a comma-separated list of FQNs, e.g.
            //   "javafx.scene.control.Button, javafx.scene.control.Label"
            // Split by comma and emit segment refs for each token independently.
            List<PsiReference> refs = new ArrayList<>();
            int cursor = 0;
            for (String token : rawValue.split(",", -1)) {
                // Strip leading whitespace, track how many chars we skipped.
                int leadingSpaces = 0;
                while (leadingSpaces < token.length()
                        && Character.isWhitespace(token.charAt(leadingSpaces))) {
                    leadingSpaces++;
                }
                String trimmed = token.stripTrailing();
                String fqn     = leadingSpaces <= trimmed.length() ? trimmed.substring(leadingSpaces) : "";

                if (!fqn.isBlank()) {
                    // tokenStart is the offset of the first non-whitespace char within rawValue.
                    int tokenStart = cursor + leadingSpaces;
                    refs.addAll(List.of(
                            buildFqnSegmentRefs(attrVal, fqn, tokenStart, xmlFile, true)));
                }
                cursor += token.length() + 1; // +1 for the comma
            }
            return refs.isEmpty() ? PsiReference.EMPTY_ARRAY : refs.toArray(PsiReference.EMPTY_ARRAY);
        }
    }

    /**
     * Produces per-segment navigation references for {@code String}-typed attributes whose
     * value is a fully-qualified Java class name (e.g. {@code viewClassName="com.example.SomeView"}).
     * Emits one {@link PackageSegmentReference} per package segment and one
     * {@link ClassSegmentReference} for each class/nested-class segment.
     */
    private static final class StringClassNameReferenceProvider extends PsiReferenceProvider {

        @Override
        public PsiReference @NotNull [] getReferencesByElement(
                @NotNull PsiElement element, @NotNull ProcessingContext context) {
            if (!(element instanceof XmlAttributeValue attrVal)) return PsiReference.EMPTY_ARRAY;
            if (!(attrVal.getContainingFile() instanceof XmlFile xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2FileType.isFxml2(xmlFile)) return PsiReference.EMPTY_ARRAY;

            if (!(attrVal.getParent() instanceof XmlAttribute attr)) return PsiReference.EMPTY_ARRAY;
            String attrName = attr.getName();
            // Skip namespace / fx: attributes and binding expressions.
            if (Fxml2XmlUtil.isNonPropertyAttribute(attrName)) return PsiReference.EMPTY_ARRAY;
            if (!(attr.getParent() instanceof XmlTag tag)) return PsiReference.EMPTY_ARRAY;

            String rawValue = attrVal.getValue();
            if (rawValue.isBlank()) return PsiReference.EMPTY_ARRAY;
            java.util.Map<Character, String> prefixMappings = Fxml2ImportResolver.parsePrefixMappings(xmlFile);
            if (Fxml2BindingExpressionParser.looksLikeBindingExpression(rawValue, prefixMappings)) return PsiReference.EMPTY_ARRAY;

            // Only act when the property type is String.
            GlobalSearchScope scope = xmlFile.getResolveScope();
            Fxml2AttributeValueResolver.Result result = resolveAttributeValue(attrName, rawValue, tag, scope);
            if (result != Fxml2AttributeValueResolver.Result.STRING) return PsiReference.EMPTY_ARRAY;

            // Only treat as a class name if it contains at least one dot (package-qualified)
            // and looks like a Java identifier (not a numeric literal like "1.5").
            if (!rawValue.contains(".")) return PsiReference.EMPTY_ARRAY;
            if (!Character.isJavaIdentifierStart(rawValue.charAt(0))) return PsiReference.EMPTY_ARRAY;

            return buildFqnSegmentRefs(attrVal, rawValue, 0, xmlFile, false);
        }
    }

    /**
     * Shared helper: walks each dot-separated segment of {@code fqn}, emitting a
     * {@link PackageSegmentReference} for package-only prefixes and a
     * {@link ClassSegmentReference} once a class (or nested class) is resolved.
     *
     * @param attrVal          the enclosing {@link XmlAttributeValue} that hosts the references
     * @param fqn              the fully-qualified name fragment to walk (no leading/trailing whitespace)
     * @param baseOffset       offset of {@code fqn}'s first character within the raw attribute value
     *                         (i.e. within {@code attrVal.getValue()}. For a single-token value this
     *                         is 0; for the N-th token in a comma-separated list it is the position of
     *                         that token's first non-whitespace character within the full value string.
     * @param xmlFile          the containing FXML file (for project/scope resolution)
     * @param reportUnresolved if {@code true}, emits a hard {@link UnresolvedClassSegmentReference}
     *                         when a segment cannot be resolved (used by {@code fx:typeArguments}).
     *                         If {@code false}, unresolvable segments are silently skipped, no error
     *                         is shown (used by String-typed attributes like {@code viewClassName}).
     */
    private static PsiReference @NotNull [] buildFqnSegmentRefs(
            @NotNull XmlAttributeValue attrVal,
            @NotNull String fqn,
            int baseOffset,
            @NotNull XmlFile xmlFile,
            boolean reportUnresolved) {

        GlobalSearchScope scope = xmlFile.getResolveScope();
        JavaPsiFacade facade = JavaPsiFacade.getInstance(xmlFile.getProject());
        List<PsiReference> refs = new ArrayList<>();

        String[] segments = fqn.split("\\.", -1);
        int cursor = 0;
        PsiClass lastResolvedClass = null;
        boolean unresolvedEarly = false; // true once a segment fails to resolve

        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            boolean isLast = (i == segments.length - 1);
            int segStart = baseOffset + cursor;
            int segEnd   = segStart + seg.length();
            // +1 accounts for the opening quote in XmlAttributeValue.getText()
            TextRange range = new TextRange(1 + segStart, 1 + segEnd);
            String prefix = fqn.substring(0, cursor + seg.length());

            if (unresolvedEarly) {
                // A prior segment didn't resolve: skip remaining segments silently.
                cursor += seg.length() + 1;
                continue;
            }

            if (lastResolvedClass != null) {
                // After the first class is found, subsequent segments are nested classes.
                PsiClass nested = findNestedClass(facade, scope, lastResolvedClass, seg);
                if (nested != null) {
                    // Hard for the last segment (when reporting), soft for intermediate ones.
                    refs.add(new ClassSegmentReference(attrVal, range, nested, isLast && reportUnresolved));
                } else {
                    if (reportUnresolved) {
                        refs.add(new UnresolvedClassSegmentReference(attrVal, range, seg));
                    }
                    unresolvedEarly = true;
                }
                lastResolvedClass = nested;
            } else {
                PsiClass cls = facade.findClass(prefix, scope);
                if (cls == null && isLast) {
                    // Fallback: resolve via file imports. This handles:
                    //  * simple names like "Object", "Integer" (java.lang.* implicit import)
                    //  * single-token names resolvable via the file's <?import ...?> entries
                    // Only applied on the last segment to avoid misinterpreting intermediate
                    // package prefixes (e.g. "java" or "java.lang") as class names.
                    cls = Fxml2ImportResolver.resolve(fqn, xmlFile);
                }
                if (cls != null) {
                    refs.add(new ClassSegmentReference(attrVal, range, cls, isLast && reportUnresolved));
                    lastResolvedClass = cls;
                } else {
                    // Not a class yet: try as a package.
                    PsiPackage pkg = facade.findPackage(prefix);
                    if (pkg != null) {
                        // Package segments are always soft (navigable but no error if missing).
                        refs.add(new PackageSegmentReference(attrVal, range, pkg));
                    } else {
                        if (reportUnresolved) {
                            refs.add(new UnresolvedClassSegmentReference(attrVal, range, seg));
                        }
                        unresolvedEarly = true;
                    }
                }
            }

            cursor += seg.length() + 1; // +1 for the dot separator
        }

        return refs.isEmpty() ? PsiReference.EMPTY_ARRAY : refs.toArray(PsiReference.EMPTY_ARRAY);
    }

    /**
     * Finds a nested class named {@code simpleName} inside {@code outerClass}.
     * <p>
     * Tries in order:
     * <ol>
     *   <li>{@code OuterFqn.SimpleName} via {@link JavaPsiFacade#findClass}: works for
     *       Kotlin inner/nested classes whose PSI qualified name uses {@code .}</li>
     *   <li>{@code OuterFqn$SimpleName} via {@link JavaPsiFacade#findClass}: works for
     *       Java nested classes and Kotlin classes accessed via bytecode names</li>
     *   <li>{@link PsiClass#getAllInnerClasses()} scan by simple name: ultimate fallback
     *       that handles Kotlin light-class wrappers where {@code getInnerClasses()} may
     *       return an empty array but {@code getAllInnerClasses()} is complete</li>
     * </ol>
     */
    private static @Nullable PsiClass findNestedClass(
            @NotNull JavaPsiFacade facade,
            @NotNull GlobalSearchScope scope,
            @NotNull PsiClass outerClass,
            @NotNull String simpleName) {
        String fqn = outerClass.getQualifiedName();
        if (fqn != null) {
            // Try '.' first: Kotlin PSI light classes register with dotted names.
            PsiClass cls = facade.findClass(fqn + "." + simpleName, scope);
            if (cls != null) return cls;
            // Try '$': Java nested classes and Kotlin bytecode convention.
            cls = facade.findClass(fqn + "$" + simpleName, scope);
            if (cls != null) return cls;
        }
        // Fallback: scan all inner classes by simple name.
        // getAllInnerClasses() is more complete than getInnerClasses() for Kotlin light
        // classes, where getInnerClasses() may return PsiClass.EMPTY_ARRAY.
        for (PsiClass inner : outerClass.getAllInnerClasses()) {
            if (simpleName.equals(inner.getName())) return inner;
        }
        return null;
    }

    /** A {@link PsiReference} that navigates to a {@link PsiClass}.
     *  Hard (soft=false) when {@code hard} is true: causes IntelliJ to show
     *  "Cannot resolve symbol" if {@link #resolve()} returns {@code null}. */
    private static final class ClassSegmentReference extends PsiReferenceBase<XmlAttributeValue> {

        private final @NotNull PsiClass myClass;
        /** When {@code false} this reference provides navigation only and is excluded from rename. */
        private final boolean myParticipatesInRename;

        ClassSegmentReference(@NotNull XmlAttributeValue element,
                              @NotNull TextRange rangeInElement,
                              @NotNull PsiClass cls,
                              boolean hard) {
            this(element, rangeInElement, cls, hard, /* participatesInRename= */ true);
        }

        ClassSegmentReference(@NotNull XmlAttributeValue element,
                              @NotNull TextRange rangeInElement,
                              @NotNull PsiClass cls,
                              boolean hard,
                              boolean participatesInRename) {
            super(element, rangeInElement, /* soft= */ !hard);
            this.myClass = cls;
            this.myParticipatesInRename = participatesInRename;
        }

        @Override
        public @NotNull PsiElement resolve() {
            return myClass;
        }

        @Override
        public boolean isReferenceTo(@NotNull PsiElement element) {
            if (!myParticipatesInRename) return false;
            return getElement().getManager().areElementsEquivalent(myClass, element);
        }

        @Override
        public @NotNull PsiElement handleElementRename(@NotNull String newElementName)
                throws com.intellij.util.IncorrectOperationException {
            if (!myParticipatesInRename) return getElement();
            return super.handleElementRename(newElementName);
        }
    }

    /**
     * A hard {@link PsiReference} for a class-name segment that could not be resolved.
     * Resolves to {@code null}, which causes IntelliJ to highlight the segment with
     * "Cannot resolve symbol 'X'".
     * Implements {@link PsiReferenceWithUnresolvedQuickFixes} so that
     * {@code XmlHighlightVisitor.shouldCheckResolve} returns {@code true} for it, and
     * {@link LocalQuickFixProvider} so that {@code XmlHighlightVisitor} attaches the
     * "Add import" quick fix to the error highlight.
     */
    private static final class UnresolvedClassSegmentReference
            extends PsiReferenceBase<XmlAttributeValue>
            implements PsiReferenceWithUnresolvedQuickFixes, LocalQuickFixProvider {

        private final @NotNull String mySegmentName;

        UnresolvedClassSegmentReference(@NotNull XmlAttributeValue element,
                                        @NotNull TextRange rangeInElement,
                                        @NotNull String segmentName) {
            super(element, rangeInElement, /* soft= */ false);
            this.mySegmentName = segmentName;
        }

        @Override
        public @Nullable PsiElement resolve() {
            return null;
        }

        @Override
        public @NotNull LocalQuickFix @NotNull [] getQuickFixes() {
            // Type arguments don't need to be instantiable, so allow abstract classes and
            // interfaces (checkUnusable=false).
            return new LocalQuickFix[] { new Fxml2AddImportFix(mySegmentName, false) };
        }
    }

    /** A soft {@link PsiReference} that navigates to a {@link PsiPackage}. */
    private static final class PackageSegmentReference extends PsiReferenceBase<XmlAttributeValue> {

        private final @NotNull PsiPackage myPackage;

        PackageSegmentReference(@NotNull XmlAttributeValue element,
                                @NotNull TextRange rangeInElement,
                                @NotNull PsiPackage pkg) {
            super(element, rangeInElement, /* soft= */ true);
            this.myPackage = pkg;
        }

        @Override
        public @NotNull PsiElement resolve() {
            return myPackage;
        }
    }

    // -----------------------------------------------------------------------
    // Static property attribute name provider (e.g. Command.onAction)
    // -----------------------------------------------------------------------

    /**
     * For a dotted static-property attribute like {@code Command.onAction}, emits two
     * separate references on the {@link XmlAttribute} element:
     * <ul>
     *   <li>The class part ({@code Command}) -> the resolved {@link PsiClass}.</li>
     *   <li>The property part ({@code onAction}) -> the static setter/getter method.</li>
     * </ul>
     * Ranges are relative to the start of the {@link XmlAttribute} text (which begins
     * with the attribute name).
     * <p>
     * For dotted attribute names, emits per-segment references enabling Ctrl+click on each part.
     * </p>
     * <p>Handles two cases:
     * <ul>
     *   <li><b>Static property</b> ({@code StackPane.alignment}): first segment resolves to
     *       an imported class; last segment resolves to the static getter/setter.</li>
     *   <li><b>Chained instance property</b> ({@code selectionModel.selectionMode}): each
     *       segment resolves as an instance property on the type of the previous segment,
     *       starting from the tag's owner class.</li>
     * </ul>
     */
    private static final class DottedAttributeNameReferenceProvider extends PsiReferenceProvider {

        @Override
        public PsiReference @NotNull [] getReferencesByElement(
                @NotNull PsiElement element, @NotNull ProcessingContext context) {

            if (!(element instanceof XmlAttribute attr)) return PsiReference.EMPTY_ARRAY;
            if (!(attr.getContainingFile() instanceof XmlFile xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2FileType.isFxml2(xmlFile)) return PsiReference.EMPTY_ARRAY;

            String attrName = attr.getName();
            if (!attrName.contains(".")) return PsiReference.EMPTY_ARRAY;

            String[] parts = attrName.split("\\.", -1);
            if (parts.length < 2) return PsiReference.EMPTY_ARRAY;

            // Case 1: first segment is an imported class -> static property notation.
            PsiClass declaringClass = Fxml2ImportResolver.resolve(parts[0], xmlFile);
            if (declaringClass != null) {
                PsiElement propDecl = attr.getDescriptor() instanceof Fxml2StaticPropertyAttributeDescriptor sd
                        ? sd.resolveProperty()
                        : Fxml2PropertyResolver.resolveStaticProperty(declaringClass, parts[parts.length - 1]);
                return staticPropertyRefs(element, attrName, declaringClass, propDecl);
            }

            // Case 1b: prefix not imported, but found via the short-name cache -> the attribute
            // is likely a static-property notation with an unimported declaring class.
            // Return SOFT null-resolving references so that:
            //   * Ctrl+hover underlines only the prefix/property segment (per-segment ranges).
            //   * findReferenceAt prefers these narrower refs over the wider XmlAttributeReference.
            //   * Fxml2AttributeAnnotator remains the sole source of the "Cannot resolve symbol"
            //     error; IntelliJ's XmlUnresolvedReferenceInspection specifically targets
            //     references that are non-soft AND not checked by XmlHighlightVisitor, so
            //     making them soft prevents duplicate/spurious errors from that inspection.
            {
                com.intellij.psi.search.PsiShortNamesCache shortNamesCache =
                        com.intellij.psi.search.PsiShortNamesCache.getInstance(xmlFile.getProject());
                PsiClass[] shortNameMatches =
                        shortNamesCache.getClassesByName(parts[0], xmlFile.getResolveScope());
                if (shortNameMatches.length > 0) {
                    int dot = attrName.lastIndexOf('.');
                    return new PsiReference[]{
                            softRef(element, new TextRange(0, dot), null),
                            softRef(element, new TextRange(dot + 1, attrName.length()), null)
                    };
                }
            }

            // Case 1c: FQN static-property notation: first segment is a known package
            // (e.g. "org" in "org.jfxcore.command.Command.onAction").
            // Refs are NON-soft so that findReferenceAt returns them rather than the
            // competing XmlAttributeReference (which is non-soft and covers the whole name).
            {
                JavaPsiFacade facade = JavaPsiFacade.getInstance(xmlFile.getProject());
                if (facade.findPackage(parts[0]) != null && parts.length >= 3) {
                    int lastDot = attrName.lastIndexOf('.');
                    String classFqn = attrName.substring(0, lastDot);
                    String propName = attrName.substring(lastDot + 1);
                    PsiClass fqnClass = facade.findClass(classFqn, xmlFile.getResolveScope());
                    if (fqnClass != null) {
                        List<PsiReference> refs = new ArrayList<>();
                        addFqnClassSegmentRefs(refs, element, 0, classFqn, fqnClass, facade, /* soft= */ false);
                        PsiElement propDecl = Fxml2PropertyResolver.resolveStaticProperty(fqnClass, propName);
                        // Non-soft when resolved so findReferenceAt returns it on the prop segment.
                        // Soft when unresolved so XmlUnresolvedReferenceInspection stays quiet.
                        boolean propSoft = (propDecl == null);
                        refs.add(new PsiReferenceBase<>(element,
                                new TextRange(lastDot + 1, attrName.length()), propSoft) {
                            @Override public @Nullable PsiElement resolve() { return propDecl; }
                        });
                        return refs.toArray(PsiReference.EMPTY_ARRAY);
                    }
                }
            }

            // Case 2: chained instance property: walk segments against owner class.
            if (!(attr.getParent() instanceof XmlTag tag)) return PsiReference.EMPTY_ARRAY;
            PsiClass ownerClass = tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd
                    ? cd.getPsiClass() : null;
            if (ownerClass == null) return PsiReference.EMPTY_ARRAY;

            List<PsiReference> refs = new ArrayList<>();
            PsiClass current = ownerClass;
            int offset = 0;
            for (int i = 0; i < parts.length; i++) {
                String seg = parts[i];
                int segStart = offset;
                int segEnd   = offset + seg.length();
                boolean isLast = (i == parts.length - 1);
                PsiElement decl = current != null
                        ? Fxml2PropertyResolver.resolveInstanceProperty(current, seg) : null;
                // SOFT when unresolved: annotator reports the error; non-soft null ref would
                // cause XmlUnresolvedReferenceInspection to add a duplicate error.
                refs.add(softRef(element, new TextRange(segStart, segEnd), decl));
                if (decl != null && !isLast) current = Fxml2PropertyResolver.resolvePropertyType(decl);
                else if (!isLast) current = null;
                offset = segEnd + 1;
            }
            return refs.isEmpty() ? PsiReference.EMPTY_ARRAY : refs.toArray(PsiReference.EMPTY_ARRAY);
        }
    }

    // -----------------------------------------------------------------------
    // styleClass attribute provider: per-token CSS class navigation
    // -----------------------------------------------------------------------

    /**
     * For a {@code styleClass} attribute value like {@code "accent, elevated-1"}, emits one
     * {@link Fxml2StyleClassReference} per comma-separated token so that Ctrl+click on each
     * token navigates to the matching {@code .name} CSS selector in any {@code .css} file
     * within the project scope.
     *
      * <p>Binding-expression values (starting with {@code $}, {@code {}, {@code >}, or {@code #}) are
      * silently skipped: they are handled by the binding provider.
     */
    private static final class StyleClassReferenceProvider extends PsiReferenceProvider {

        @Override
        public PsiReference @NotNull [] getReferencesByElement(
                @NotNull PsiElement element, @NotNull ProcessingContext context) {

            if (!(element instanceof XmlAttributeValue attrVal)) return PsiReference.EMPTY_ARRAY;
            if (!(attrVal.getContainingFile() instanceof XmlFile xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2FileType.isFxml2(xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!(attrVal.getParent() instanceof XmlAttribute attr)) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2CssUtil.isStyleClassAttribute(attr)) return PsiReference.EMPTY_ARRAY;

            String rawValue = attrVal.getValue();
            if (rawValue.isBlank()) return PsiReference.EMPTY_ARRAY;

            List<Fxml2CssUtil.Token> tokens = Fxml2CssUtil.splitStyleClassTokens(rawValue);
            if (tokens.isEmpty()) return PsiReference.EMPTY_ARRAY;

            // CSS type name: lowercase simple tag name, e.g. "Notification" -> "notification"
            String cssTypeName = null;
            if (attr.getParent() instanceof XmlTag ownerTag) {
                String localName = ownerTag.getLocalName();
                if (!localName.isEmpty()) {
                    cssTypeName = Character.toLowerCase(localName.charAt(0)) + localName.substring(1);
                }
            }

            PsiReference[] refs = new PsiReference[tokens.size()];
            for (int i = 0; i < tokens.size(); i++) {
                Fxml2CssUtil.Token token = tokens.get(i);
                // +1 because XmlAttributeValue text includes the opening quote
                TextRange range = new TextRange(
                        1 + token.offsetInValue(),
                        1 + token.offsetInValue() + token.name().length());
                refs[i] = new Fxml2StyleClassReference(attrVal, range, token.name(), xmlFile, cssTypeName);
            }
            return refs;
        }
    }

    // -----------------------------------------------------------------------
    // Static property tag name provider (e.g. <Interaction.behaviors>)
    // -----------------------------------------------------------------------

    /**
     * For a dotted static-property tag like {@code <Interaction.behaviors>}, emits two
     * separate references on the {@link XmlTag} element:
     * <ul>
     *   <li>The class part ({@code Interaction}) -> the resolved {@link PsiClass}.</li>
     *   <li>The property part ({@code behaviors}) -> the static getter/setter method.</li>
     * </ul>
     * Ranges are relative to the start of the {@link XmlTag} text, which begins with
     * {@code <}, so the tag name starts at offset 1.
     */
    private static final class StaticPropertyTagNameReferenceProvider extends PsiReferenceProvider {

        @Override
        public PsiReference @NotNull [] getReferencesByElement(
                @NotNull PsiElement element, @NotNull ProcessingContext context) {

            if (!(element instanceof XmlTag tag)) return PsiReference.EMPTY_ARRAY;
            if (!(tag.getContainingFile() instanceof XmlFile xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2FileType.isFxml2(xmlFile)) return PsiReference.EMPTY_ARRAY;

            String localName = tag.getLocalName();
            int dot = localName.lastIndexOf('.');
            if (dot <= 0) return PsiReference.EMPTY_ARRAY;

            // Emit per-segment references for both the opening and closing tag name occurrences.
            String tagText = tag.getText();
            List<Integer> nameBases = new ArrayList<>();
            nameBases.add(1); // opening tag
            int searchFrom = 2;
            while (true) {
                int idx = tagText.indexOf(localName, searchFrom);
                if (idx < 0) break;
                if (idx >= 2 && tagText.charAt(idx - 1) == '/' && tagText.charAt(idx - 2) == '<')
                    nameBases.add(idx);
                searchFrom = idx + 1;
            }

            String className = localName.substring(0, dot);
            String propName  = localName.substring(dot + 1);
            List<PsiReference> refs = new ArrayList<>();

            PsiClass declaringClass = Fxml2ImportResolver.resolve(className, xmlFile);
            if (declaringClass != null) {
                PsiClass parentClass = Fxml2TagResolver.resolveContextClass(tag, xmlFile);
                PsiElement propDecl = (parentClass != null && Fxml2TagResolver.isSubtype(parentClass, declaringClass))
                        ? Fxml2PropertyResolver.resolveInstanceProperty(parentClass, propName)
                        : Fxml2PropertyResolver.resolveStaticProperty(declaringClass, propName);
                JavaPsiFacade facade = JavaPsiFacade.getInstance(xmlFile.getProject());
                for (int nameBase : nameBases) {
                    if (className.contains(".")) {
                        // FQN class name imported via alias: per-segment package + class refs
                        addFqnClassSegmentRefs(refs, element, nameBase, className, declaringClass, facade, /* soft= */ true);
                    } else {
                        refs.add(softRef(element, new TextRange(nameBase, nameBase + dot), declaringClass));
                    }
                    refs.add(softRef(element,
                            new TextRange(nameBase + dot + 1, nameBase + localName.length()), propDecl));
                }
                return refs.toArray(PsiReference.EMPTY_ARRAY);
            }

            // FQN class tag: e.g. <javafx.scene.control.TextField/>: first segment is a
            // known package. Emit per-segment refs so Ctrl+click on each segment navigates.
            {
                JavaPsiFacade facade = JavaPsiFacade.getInstance(xmlFile.getProject());
                GlobalSearchScope scope = xmlFile.getResolveScope();
                String firstSeg = localName.substring(0, localName.indexOf('.'));
                if (facade.findPackage(firstSeg) != null) {
                    String[] segments = localName.split("\\.", -1);
                    for (int nameBase : nameBases) {
                        int cursor = 0;
                        PsiClass lastClass = null;
                        boolean unresolvedEarly = false;
                        for (int i = 0; i < segments.length; i++) {
                            String seg = segments[i];
                            boolean isLast = (i == segments.length - 1);
                            int segStart = nameBase + cursor;
                            int segEnd   = segStart + seg.length();
                            TextRange range = new TextRange(segStart, segEnd);
                            String prefix = localName.substring(0, cursor + seg.length());
                            if (!unresolvedEarly) {
                                if (lastClass != null) {
                                    PsiClass nested = findNestedClass(facade, scope, lastClass, seg);
                                    if (nested != null) { lastClass = nested; refs.add(softRef(element, range, nested)); }
                                    else unresolvedEarly = true;
                                } else {
                                    PsiClass cls = isLast ? facade.findClass(prefix, scope) : null;
                                    if (cls == null && isLast) cls = Fxml2ImportResolver.resolve(localName, xmlFile);
                                    if (cls != null) { lastClass = cls; refs.add(softRef(element, range, cls)); }
                                    else {
                                        PsiPackage pkg = facade.findPackage(prefix);
                                        if (pkg != null) refs.add(softRef(element, range, pkg));
                                        else unresolvedEarly = true;
                                    }
                                }
                            }
                            cursor += seg.length() + 1;
                        }
                    }
                    return refs.isEmpty() ? PsiReference.EMPTY_ARRAY : refs.toArray(PsiReference.EMPTY_ARRAY);
                }
            }

            // Chained instance property: e.g. selectionModel.selectionMode
            PsiClass parentClass = Fxml2TagResolver.resolveContextClass(tag, xmlFile);
            if (parentClass == null) return PsiReference.EMPTY_ARRAY;
            String[] segments = localName.split("\\.", -1);
            for (int nameBase : nameBases) {
                PsiClass current = parentClass;
                int segOffset = nameBase;
                for (String seg : segments) {
                    PsiElement decl = current != null
                            ? Fxml2PropertyResolver.resolveInstanceProperty(current, seg) : null;
                    refs.add(softRef(element, new TextRange(segOffset, segOffset + seg.length()), decl));
                    current = decl != null ? Fxml2PropertyResolver.resolvePropertyType(decl) : null;
                    segOffset += seg.length() + 1;
                }
            }
            return refs.isEmpty() ? PsiReference.EMPTY_ARRAY : refs.toArray(PsiReference.EMPTY_ARRAY);
        }
    }

    // -----------------------------------------------------------------------
    // <?prefix X = ClassName?> class name reference provider
    // -----------------------------------------------------------------------

    /**
     * Provides a {@link PsiReference} from the class-name portion of a
     * {@code <?prefix X = ClassName?>} processing instruction to the named
     * {@link PsiClass}.
     *
     * <p>This enables:
     * <ul>
     *   <li>Ctrl+click navigation from the class name in the PI to the class declaration.</li>
     *   <li>"Find Usages" discovery: the class is recognized as referenced from the PI.</li>
     *   <li>Suppression of the false-positive "Unused import statement" Java warning:
     *       because the reference resolves to the class, IntelliJ's import-usage analysis
     *       counts the import as used.</li>
     * </ul>
     *
     * <p>The reference is soft to avoid a spurious "Cannot resolve" error from
     * {@code XmlUnresolvedReferenceInspection}; resolution errors in prefix declarations
     * are reported by {@link org.jfxcore.fxml.annotator.Fxml2PrefixDeclarationInspection}.
     *
     * <p>In addition to the class-name reference, a soft reference is emitted for the
     * {@code prefix} keyword itself (at offset 2 in the PI text, after {@code <?}).
     * Ctrl+click on the keyword opens the prefix-declarations section of the online docs.
     */
    private static final class PrefixDeclarationClassReferenceProvider extends PsiReferenceProvider {

        private static final String PREFIX_DOCS_URL =
                "https://jfxcore.github.io/fxml-compiler/markup-extension.html#prefix-declarations";

        @Override
        public PsiReference @NotNull [] getReferencesByElement(
                @NotNull PsiElement element, @NotNull ProcessingContext context) {

            if (!(element instanceof XmlProcessingInstruction pi)) return PsiReference.EMPTY_ARRAY;
            if (!(pi.getContainingFile() instanceof XmlFile xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2FileType.isFxml2(xmlFile)) return PsiReference.EMPTY_ARRAY;

            // The PI text is "<?prefix X = ClassName?>"; "prefix" starts at offset 2.
            String piText = pi.getText();
            int prefixKeywordStart = piText.indexOf("prefix");
            if (prefixKeywordStart < 0) return PsiReference.EMPTY_ARRAY;
            TextRange prefixKeywordRange =
                    new TextRange(prefixKeywordStart, prefixKeywordStart + "prefix".length());
            PsiReference prefixKeywordRef = new PsiReferenceBase<>(pi, prefixKeywordRange, /* soft= */ true) {
                @Override
                public @NotNull PsiElement resolve() {
                    return new Fxml2NamespaceUrlReference.UrlNavigationTarget(pi, PREFIX_DOCS_URL);
                }
            };

            String typeName = Fxml2ImportResolver.getPrefixDeclarationTypeName(pi);
            if (typeName == null) return new PsiReference[]{ prefixKeywordRef };

            int offset = Fxml2ImportResolver.getPrefixDeclarationTypeNameOffset(pi);
            if (offset < 0) return new PsiReference[]{ prefixKeywordRef };

            // Resolve the class: try FQN first, then via the file's imports for simple names.
            GlobalSearchScope allScope = GlobalSearchScope.allScope(xmlFile.getProject());
            PsiClass resolved = JavaPsiFacade.getInstance(xmlFile.getProject())
                    .findClass(typeName, allScope);
            if (resolved == null) {
                resolved = Fxml2ImportResolver.resolve(typeName, xmlFile);
            }

            if (typeName.contains(".") && resolved != null) {
                // FQN: emit per-segment package + class references for each dot-separated part,
                // so that Ctrl+click on any segment navigates to the corresponding package or class.
                List<PsiReference> refs = new ArrayList<>();
                refs.add(prefixKeywordRef);
                JavaPsiFacade facade = JavaPsiFacade.getInstance(xmlFile.getProject());
                addFqnClassSegmentRefs(refs, pi, offset, typeName, resolved, facade, /* soft= */ true);
                return refs.toArray(PsiReference.EMPTY_ARRAY);
            }

            if (resolved == null) return new PsiReference[]{ prefixKeywordRef };

            // Simple name: a single soft reference spanning the entire class name token.
            TextRange range = new TextRange(offset, offset + typeName.length());
            final PsiClass finalResolved = resolved;
            return new PsiReference[]{
                prefixKeywordRef,
                new PsiReferenceBase<>(pi, range, /* soft= */ true) {
                    @Override
                    public @NotNull PsiElement resolve() { return finalResolved; }

                    @Override
                    public boolean isReferenceTo(@NotNull PsiElement candidate) {
                        return pi.getManager().areElementsEquivalent(finalResolved, candidate);
                    }
                }
            };
        }
    }

    // -----------------------------------------------------------------------
    // fx:* intrinsic attribute name provider
    // -----------------------------------------------------------------------

    /** Base URL for the FXML/2 language reference pages. */
    private static final String FX_REFERENCE_BASE_URL =
            "https://jfxcore.github.io/fxml-compiler/reference/";

    /**
     * Maps each known {@code fx:} intrinsic attribute local name to its online reference URL.
     * Attributes not present in this map have no URL reference (typically because they resolve
     * to actual Java code via a descriptor).
     */
    private static final Map<String, String> FX_INTRINSIC_ATTR_URLS = Map.ofEntries(
            Map.entry("fx:id",               FX_REFERENCE_BASE_URL + "id.html"),
            Map.entry("fx:subclass",         FX_REFERENCE_BASE_URL + "subclass.html"),
            Map.entry("fx:className",        FX_REFERENCE_BASE_URL + "className.html"),
            Map.entry("fx:classModifier",    FX_REFERENCE_BASE_URL + "classModifier.html"),
            Map.entry("fx:classParameters",  FX_REFERENCE_BASE_URL + "classParameters.html"),
            Map.entry("fx:context",          FX_REFERENCE_BASE_URL + "context.html"),
            Map.entry("fx:typeArguments",    FX_REFERENCE_BASE_URL + "typeArguments.html"),
            Map.entry("fx:factory",          FX_REFERENCE_BASE_URL + "factory.html"),
            Map.entry("fx:value",            FX_REFERENCE_BASE_URL + "value.html"),
            Map.entry("fx:constant",         FX_REFERENCE_BASE_URL + "constant.html")
    );

    /**
     * Provides a soft {@link PsiReference} on the name token of every {@code fx:} intrinsic
     * attribute in an FXML file. Ctrl+click opens the corresponding online language-reference page.
     *
     * <p>The reference covers the full attribute name (e.g. {@code fx:id}) so that positioning
     * the caret anywhere on the name and pressing Ctrl+click opens the docs. The reference
     * is soft so that IntelliJ does not add an extra "Cannot resolve" underline.
     */
    private static final class FxIntrinsicAttributeNameReferenceProvider extends PsiReferenceProvider {

        @Override
        public PsiReference @NotNull [] getReferencesByElement(
                @NotNull PsiElement element, @NotNull ProcessingContext context) {

            if (!(element instanceof XmlAttribute attr)) return PsiReference.EMPTY_ARRAY;
            if (!(attr.getContainingFile() instanceof XmlFile xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2FileType.isFxml2(xmlFile)) return PsiReference.EMPTY_ARRAY;

            String attrName = attr.getName();
            String url = FX_INTRINSIC_ATTR_URLS.get(attrName);
            if (url == null) return PsiReference.EMPTY_ARRAY;

            // Cover only the attribute name portion (before '='), not the value.
            TextRange range = new TextRange(0, attrName.length());
            return new PsiReference[]{
                new PsiReferenceBase<>(attr, range, /* soft= */ true) {
                    @Override
                    public @NotNull PsiElement resolve() {
                        return new Fxml2NamespaceUrlReference.UrlNavigationTarget(attr, url);
                    }
                }
            };
        }
    }

    // -----------------------------------------------------------------------
    // fx:* intrinsic element-tag name provider
    // -----------------------------------------------------------------------

    /**
     * Maps each known {@code fx:} intrinsic element tag local name to its online reference URL.
     * Markup extension tags that have a corresponding Java class (e.g. {@code fx:Observe}) are
     * included so that Ctrl+click on the tag name navigates to the language docs rather than
     * falling through to an unresolved element.
     */
    private static final Map<String, String> FX_INTRINSIC_TAG_URLS = Map.ofEntries(
            Map.entry("define",      FX_REFERENCE_BASE_URL + "define.html"),
            Map.entry("Null",        FX_REFERENCE_BASE_URL + "null.html"),
            Map.entry("True",        FX_REFERENCE_BASE_URL + "true.html"),
            Map.entry("False",       FX_REFERENCE_BASE_URL + "false.html"),
            Map.entry("Class",       FX_REFERENCE_BASE_URL + "class.html"),
            Map.entry("Evaluate",    FX_REFERENCE_BASE_URL + "evaluate.html"),
            Map.entry("Observe",     FX_REFERENCE_BASE_URL + "observe.html"),
            Map.entry("Push",        FX_REFERENCE_BASE_URL + "push.html"),
            Map.entry("Synchronize", FX_REFERENCE_BASE_URL + "synchronize.html")
    );

    /**
     * Provides a soft {@link PsiReference} on the name token of every {@code fx:} intrinsic
     * element tag in an FXML file. Ctrl+click opens the corresponding online language-reference page.
     *
     * <p>The reference covers only the local name part of the tag (after the {@code fx:} prefix)
     * so that the prefix part can carry a separate namespace reference if needed.
     */
    private static final class FxIntrinsicTagNameReferenceProvider extends PsiReferenceProvider {

        @Override
        public PsiReference @NotNull [] getReferencesByElement(
                @NotNull PsiElement element, @NotNull ProcessingContext context) {

            if (!(element instanceof XmlTag tag)) return PsiReference.EMPTY_ARRAY;
            if (!(tag.getContainingFile() instanceof XmlFile xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2FileType.isFxml2(xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2ImportResolver.FXML2_NAMESPACE.equals(tag.getNamespace())) return PsiReference.EMPTY_ARRAY;

            String localName = tag.getLocalName();
            String url = FX_INTRINSIC_TAG_URLS.get(localName);
            if (url == null) return PsiReference.EMPTY_ARRAY;

            // The tag element text starts with '<', so the name begins at offset 1.
            // Include the "fx:" prefix in the reference range so that the full "fx:define"
            // token is navigatable (offset 1 = '<' is skipped, name is "fx:localName").
            String tagText = tag.getText();
            // Find the start of the tag name within the element text: skip '<'.
            int nameStart = 1;
            // Skip '/' for closing tags (not expected here, but guard defensively).
            if (nameStart < tagText.length() && tagText.charAt(nameStart) == '/') nameStart++;
            String fullTagName = "fx:" + localName;
            if (!tagText.startsWith(fullTagName, nameStart)) return PsiReference.EMPTY_ARRAY;

            TextRange range = new TextRange(nameStart, nameStart + fullTagName.length());
            return new PsiReference[]{
                new PsiReferenceBase<>(tag, range, /* soft= */ true) {
                    @Override
                    public @NotNull PsiElement resolve() {
                        return new Fxml2NamespaceUrlReference.UrlNavigationTarget(tag, url);
                    }
                }
            };
        }
    }

    // -----------------------------------------------------------------------
    // Reference-building utilities shared by multiple providers
    // -----------------------------------------------------------------------

    /** Returns the {@link XmlTag} parent of {@code attrVal}'s owning attribute, or {@code null}. */
    private static @Nullable XmlTag getContextTag(@NotNull XmlAttributeValue attrVal) {
        return attrVal.getParent() instanceof XmlAttribute attr
                && attr.getParent() instanceof XmlTag t ? t : null;
    }

    /**
     * Appends a soft context-selector reference that navigates to the matching {@link XmlTag}.
     * Does nothing when the selector cannot be resolved to a tag.
     *
     * @param selectorStart offset of the selector's first character in {@code attrVal.getText()}
     *                      (already adjusted for the opening quote, i.e. 1-based)
     */
    private static void addSelectorRef(
            @NotNull List<PsiReference> refs,
            @NotNull XmlAttributeValue attrVal,
            int selectorStart,
            @NotNull ContextSelector selector,
            @NotNull XmlTag contextTag,
            @NotNull Project project) {
        XmlTag targetTag = Fxml2BindingPathResolver.resolveContextSelectorTag(selector, contextTag);
        if (targetTag == null) return;
        PsiElement resolved = makeSelectorNavElement(selector.selectorText(), targetTag, project);
        int selectorEnd = selectorStart + selector.selectorText().length();
        refs.add(new PsiReferenceBase<>(attrVal, new TextRange(selectorStart, selectorEnd), /* soft= */ true) {
            @Override public @NotNull PsiElement resolve() { return resolved; }
        });
    }

    /**
     * Appends per-segment binding path references for the given resolved segments.
     * <p>Unresolved segments in class-qualifier positions (first letter uppercase, no declaration)
     * become hard {@link UnresolvedClassSegmentReference}s so the IDE offers "Add import".
     * All other segments become soft {@link Fxml2BindingSegmentReference}s.
     *
     * @param pathBase offset of the first segment's first character in {@code attrVal.getText()}
     * @param path     the path string used for separator-width computation ({@code ::} = 2, {@code .} = 1)
     */
    private static void emitPathSegmentRefs(
            @NotNull List<PsiReference> refs,
            @NotNull XmlAttributeValue attrVal,
            @NotNull List<Fxml2BindingPathResolver.Segment> segments,
            int pathBase,
            @NotNull String path) {
        boolean inClassQualifierChain = true;
        int cursor = 0;
        for (Fxml2BindingPathResolver.Segment seg : segments) {
            String segName = seg.name();
            int segStart = pathBase + cursor;
            int segEnd   = segStart + segName.length();
            // Emit a hard UnresolvedClassSegmentReference only for segments that are plausible
            // unimported class-name qualifiers: must be in the leading class-qualifier chain,
            // have an uppercase initial letter, have no declaration and no partial resolution
            // (resultType == null; when resultType != null the class was found but the member was
            // not, and the annotator already reports that error), must not be a function/constructor
            // call expression (segName containing "(" is a call, not a navigatable class name),
            // and must be followed by more path (a bare unresolved identifier at the end of the
            // path is an instance-property or field access, already flagged by the annotator).
            if (inClassQualifierChain && seg.declaration() == null && seg.resultType() == null
                    && !segName.isEmpty() && Character.isUpperCase(segName.charAt(0))
                    && !segName.contains("(")
                    && cursor + segName.length() < path.length()) {
                refs.add(new UnresolvedClassSegmentReference(attrVal, new TextRange(segStart, segEnd), segName));
            } else {
                refs.add(new Fxml2BindingSegmentReference(attrVal, new TextRange(segStart, segEnd), seg.declaration()));
            }
            if (!seg.classQualifier()) {
                inClassQualifierChain = false;
            }
            cursor += segName.length();
            if (cursor < path.length()) {
                cursor += path.startsWith("::", cursor) ? 2 : 1;
            }
        }
    }

    /**
     * Creates a <em>soft</em> {@link PsiReferenceBase} resolving to {@code target} (nullable).
     * Soft references are never flagged by {@code XmlUnresolvedReferenceInspection}, preventing
     * duplicate "Cannot resolve" errors alongside the annotator's own diagnostics.
     */
    private static @NotNull PsiReference softRef(
            @NotNull PsiElement element, @NotNull TextRange range, @Nullable PsiElement target) {
        return new PsiReferenceBase<>(element, range, /* soft= */ true) {
            @Override public @Nullable PsiElement resolve() { return target; }
        };
    }

    /**
     * Returns two references for a static-property attribute name of the form
     * {@code ClassName.propertyName}: one for the class portion and one for the property portion.
     *
     * <p>The class reference is <b>non-soft</b> so that {@code findReferenceAt} returns it
     * (rather than the competing {@code XmlAttributeReference}) when the cursor is on the
     * class segment of an attribute like {@code VBox.vgrow}.
     *
     * @param element        the owning {@link XmlAttribute} PSI element
     * @param name           the full attribute name (class part + "." + property part)
     * @param declaringClass the resolved declaring class
     * @param propDecl       the resolved property declaration, or {@code null} if unresolved
     */
    private static PsiReference @NotNull [] staticPropertyRefs(
            @NotNull PsiElement element,
            @NotNull String name,
            @NotNull PsiClass declaringClass,
            @Nullable PsiElement propDecl) {
        int dot = name.lastIndexOf('.');
        // Class ref: non-soft so findReferenceAt prefers it over XmlAttributeReference.
        PsiReference classRef = new PsiReferenceBase<>(element,
                new TextRange(0, dot), /* soft= */ false) {
            @Override public @NotNull PsiElement resolve() { return declaringClass; }
        };
        // Prop ref: non-soft when resolved (so findReferenceAt returns it on the prop segment);
        // soft when unresolved so XmlUnresolvedReferenceInspection stays quiet.
        boolean propSoft = (propDecl == null);
        PsiReference propRef = new PsiReferenceBase<>(element,
                new TextRange(dot + 1, name.length()), propSoft) {
            @Override public @Nullable PsiElement resolve() { return propDecl; }
        };
        return new PsiReference[]{ classRef, propRef };
    }

    // -----------------------------------------------------------------------
    // Event-handler method reference provider
    // -----------------------------------------------------------------------

    /**
     * Provides a {@link Fxml2AttributeValueReference} from a plain method-name attribute
     * value on an {@code EventHandler}-typed property to the corresponding method in the
     * code-behind class.
     *
     * <p>Example: {@code <Button onAction="handleClick"/>} where {@code onAction} is of type
     * {@code ObjectProperty<EventHandler<ActionEvent>>} and {@code handleClick} is a method
     * on the code-behind class. The reference enables Ctrl+click navigation and contributes
     * to "Find Usages" via the standard reference infrastructure.
     *
     * <p>Registered at {@link PsiReferenceRegistrar#HIGHER_PRIORITY} so that when
     * {@link LiteralValueReferenceProvider} also runs on the same attribute value (it
     * always does, at DEFAULT_PRIORITY), our reference is chosen first by
     * {@link com.intellij.psi.impl.source.resolve.reference.impl.PsiMultiReference}.
     */
    private static final class EventHandlerMethodReferenceProvider extends PsiReferenceProvider {

        @Override
        public PsiReference @NotNull [] getReferencesByElement(
                @NotNull PsiElement element, @NotNull ProcessingContext context) {

            if (!(element instanceof XmlAttributeValue attrVal)) return PsiReference.EMPTY_ARRAY;
            if (!(attrVal.getContainingFile() instanceof XmlFile xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!Fxml2FileType.isFxml2(xmlFile)) return PsiReference.EMPTY_ARRAY;
            if (!(attrVal.getParent() instanceof XmlAttribute attr)) return PsiReference.EMPTY_ARRAY;

            String rawValue = attrVal.getValue();
            if (rawValue.isBlank()) return PsiReference.EMPTY_ARRAY;
            if (Fxml2BindingExpressionParser.looksLikeBindingExpression(rawValue)) return PsiReference.EMPTY_ARRAY;

            String attrLocalName = attr.getLocalName();
            if (Fxml2XmlUtil.isNonPropertyAttribute(attr.getName())) return PsiReference.EMPTY_ARRAY;
            if (attrLocalName.contains(".")) return PsiReference.EMPTY_ARRAY;
            if (!(attr.getParent() instanceof XmlTag tag)) return PsiReference.EMPTY_ARRAY;
            if (!(tag.getDescriptor() instanceof Fxml2ClassTagDescriptor cd)) return PsiReference.EMPTY_ARRAY;
            PsiClass ownerClass = cd.getPsiClass();
            if (ownerClass == null) return PsiReference.EMPTY_ARRAY;

            List<String> siblings = Fxml2NamedArgResolver.collectAttributeNames(tag);
            PsiType propType = Fxml2AttributeValueResolver.propertyType(ownerClass, attrLocalName, siblings);
            if (!Fxml2EventHandlerUtil.isEventHandlerType(propType, xmlFile.getProject())) return PsiReference.EMPTY_ARRAY;

            PsiClass codeBehind = Fxml2BindingPathResolver.resolveCodeBehindClass(xmlFile);
            if (codeBehind == null) return PsiReference.EMPTY_ARRAY;

            // Compute the method name and its offset within the attribute value text.
            // The attribute value text includes surrounding quotes, so offset 0 is the opening quote.
            String methodName = rawValue.strip();
            if (methodName.isEmpty()) return PsiReference.EMPTY_ARRAY;
            int leadingSpaces = rawValue.length() - rawValue.stripLeading().length();
            // +1 for the opening quote character in the XmlAttributeValue text
            TextRange range = new TextRange(1 + leadingSpaces, 1 + leadingSpaces + methodName.length());

            PsiClass eventType = Fxml2EventHandlerUtil.extractEventTypeClass(propType);
            PsiMethod bestMethod = Fxml2EventHandlerUtil.findBestHandlerMethod(codeBehind, methodName, eventType);

            return new PsiReference[]{ new Fxml2AttributeValueReference(attrVal, range, bestMethod) };
        }
    }

    /**
     * Appends per-segment package and class references for a fully-qualified class name
     * that has already been resolved to {@code fqnClass}.
     *
     * <p>For each dot-separated segment of {@code classFqn}:
     * <ul>
     *   <li>Package segments -> ref resolving to the corresponding {@link PsiPackage}.</li>
     *   <li>The final (class-name) segment -> ref resolving to {@code fqnClass}.</li>
     * </ul>
     *
     * @param refs      list to append to
     * @param element   the owning PSI element
     * @param nameBase  base offset within {@code element}'s text (0 for attributes, 1 for tags)
     * @param classFqn  the dot-separated FQN (e.g. {@code "org.jfxcore.command.Command"})
     * @param fqnClass  the already-resolved {@link PsiClass}
     * @param facade    {@link JavaPsiFacade} for package lookup
     * @param soft      if {@code true}, all refs are soft (safe for tags where no competing
     *                  non-soft reference exists); if {@code false}, all refs are non-soft
     *                  (required for attributes, so {@code findReferenceAt} returns them
     *                  instead of the wider non-soft {@code XmlAttributeReference})
     */
    private static void addFqnClassSegmentRefs(
            @NotNull List<PsiReference> refs,
            @NotNull PsiElement element,
            int nameBase,
            @NotNull String classFqn,
            @NotNull PsiClass fqnClass,
            @NotNull JavaPsiFacade facade,
            boolean soft) {
        String[] segs = classFqn.split("\\.", -1);
        int cursor = 0;
        for (int i = 0; i < segs.length; i++) {
            String seg = segs[i];
            int segStart = nameBase + cursor;
            int segEnd   = segStart + seg.length();
            TextRange range = new TextRange(segStart, segEnd);
            if (i == segs.length - 1) {
                if (soft) {
                    refs.add(softRef(element, range, fqnClass));
                } else {
                    refs.add(new PsiReferenceBase<>(element, range, false) {
                        @Override public @NotNull PsiElement resolve() { return fqnClass; }
                    });
                }
            } else {
                PsiPackage pkg = facade.findPackage(classFqn.substring(0, cursor + seg.length()));
                if (pkg != null) {
                    if (soft) {
                        refs.add(softRef(element, range, pkg));
                    } else {
                        refs.add(new PsiReferenceBase<>(element, range, false) {
                            @Override public @NotNull PsiElement resolve() { return pkg; }
                        });
                    }
                }
            }
            cursor += seg.length() + 1;
        }
    }
}
