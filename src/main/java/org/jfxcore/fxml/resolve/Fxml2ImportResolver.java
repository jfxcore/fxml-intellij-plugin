package org.jfxcore.fxml.resolve;

import com.intellij.lang.ASTNode;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiImportStaticStatement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlProcessingInstruction;
import com.intellij.psi.xml.XmlProlog;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlTokenType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves unqualified or fully-qualified Java type names used as FXML2 element tag names
 * against the {@code <?import ...?>} processing instructions in the containing file.
 *
 * <p>Resolution order mirrors the fxml-compiler's {@code Resolver.getPotentialClassNames}:
 * <ol>
 *   <li>If the name is already fully-qualified (contains a {@code .}), resolve it directly.</li>
 *   <li>Otherwise, scan {@code <?import foo.Bar?>} exact imports: first match wins.</li>
 *   <li>Then scan {@code <?import foo.*?>} wildcard imports in declaration order.</li>
 *   <li>{@code java.lang.*} is always implicitly available (last resort).</li>
 * </ol>
 *
 * <p>Dotted names that look like property paths (e.g. {@code Button.text}, {@code VBox.margin})
 * are never passed to {@code facade.findClass()}.
 *
 * <p>Nested classes written with a {@code .} separator in the tag name (e.g. {@code Outer.Inner})
 * are handled by trying {@code $}-joined FQNs in addition to {@code .}-joined ones.
 */
public final class Fxml2ImportResolver {

    public static final String FXML2_NAMESPACE = "http://jfxcore.org/fxml/2.0";
    public static final String JAVAFX_NAMESPACE = "http://javafx.com/javafx";

    /**
     * Characters that are valid as a prefix character in a {@code <?prefix?>} declaration:
     * {@code @ % & ^ °(U+00B0) §(U+00A7) ? ~}.
     */
    public static final String VALID_PREFIX_CHARACTERS = "@%&^°§?~";


    /**
     * Implicit built-in prefix mappings that are always available without any
     * {@code <?prefix?>} declaration, provided the markup runtime library is on the
     * compile classpath.
     *
     * <p>{@code @} and {@code %} are both in {@link #VALID_PREFIX_CHARACTERS} and may also
     * be overridden by explicit {@code <?prefix?>} declarations.
     */
    public static final Map<Character, String> DEFAULT_PREFIX_MAPPINGS = Map.of(
            '@', "org.jfxcore.markup.resource.ClassPathResource",
            '%', "org.jfxcore.markup.resource.StaticResource");

    /** Per-file cache key for the result of {@link #parseImports}. */
    private static final Key<CachedValue<List<String>>> IMPORTS_CACHE =
            Key.create("fxml2.imports");

    /** Per-file cache key for the result of {@link #parsePrefixMappings}. */
    private static final Key<CachedValue<Map<Character, String>>> PREFIX_MAPPINGS_CACHE =
            Key.create("fxml2.prefixMappings");

    private Fxml2ImportResolver() {}

    /**
     * Parses all {@code <?prefix X = ClassName?>} processing instructions from the
     * prolog of {@code file} and returns a map of prefix-character -> FQN.
     *
     * <p>Explicit declarations take precedence over the implicit defaults ({@code @} ->
     * ClassPathResource, {@code %} -> StaticResource).  The implicit defaults are added
     * last with {@link Map#putIfAbsent} so that explicit declarations override them.
     *
     * @param file the FXML2 file to scan
     * @return an unmodifiable map, never {@code null}
     */
    public static @NotNull Map<Character, String> parsePrefixMappings(@NotNull XmlFile file) {
        return CachedValuesManager.getCachedValue(file, PREFIX_MAPPINGS_CACHE, () -> {
            // For embedded FXML2 files, the prefix mappings depend on the host Java file
            // (which the injection framework reflects into the XML prolog). Use the Java-language
            // modification tracker so that edits to the host file invalidate the cache, while
            // edits to unrelated non-Java files (CSS, resource bundles, other FXML) do not.
            // For standalone .fxml files, only the file itself matters.
            Object dependency = Fxml2EmbeddedUtil.isEmbeddedFxml2(file)
                    ? PsiModificationTracker.getInstance(file.getProject())
                            .forLanguage(JavaLanguage.INSTANCE)
                    : file;
            return CachedValueProvider.Result.create(parsePrefixMappingsImpl(file), dependency);
        });
    }

    private static @NotNull Map<Character, String> parsePrefixMappingsImpl(@NotNull XmlFile file) {
        Map<Character, String> result = new LinkedHashMap<>();
        XmlDocument document = file.getDocument();
        if (document != null) {
            // Standalone FXML2: <?prefix?> PIs appear in the document prolog, before the
            // root element.
            XmlProlog prolog = document.getProlog();
            if (prolog != null) {
                for (XmlProcessingInstruction pi
                        : PsiTreeUtil.findChildrenOfType(prolog, XmlProcessingInstruction.class)) {
                    parsePrefixInstruction(pi, result);
                }
            }
            // Embedded FXML2: the injection wraps the annotation value inside a synthetic
            // root element (<fxml2:embedded>).  Any <?prefix?> PIs written by the user appear
            // as direct children of that wrapper element, before the user's own root tag.
            // The prolog only contains the synthetic <?xml?> and <?import?> PIs injected from
            // the host Java file, so the prolog scan above would miss them.
            if (Fxml2EmbeddedUtil.isEmbeddedFxml2(file)) {
                XmlTag wrapperRoot = document.getRootTag();
                if (wrapperRoot != null) {
                    for (PsiElement child : wrapperRoot.getChildren()) {
                        if (child instanceof XmlProcessingInstruction pi) {
                            parsePrefixInstruction(pi, result);
                        }
                    }
                }
            }
        }
        // Add implicit defaults (only if not overridden by explicit declarations)
        DEFAULT_PREFIX_MAPPINGS.forEach(result::putIfAbsent);
        return Collections.unmodifiableMap(result);
    }

    /**
     * Tries to extract a valid {@code prefix-char -> FQN} mapping from a single
     * {@code <?prefix?>} processing instruction and inserts it into {@code out} if
     * the PI is well-formed.  Duplicate declarations are silently ignored here; the
     * inspection {@code Fxml2PrefixDeclarationInspection} reports them separately.
     */
    public static void parsePrefixInstruction(
            @NotNull XmlProcessingInstruction pi,
            @NotNull Map<Character, String> out) {
        ASTNode nameNode = pi.getNode().findChildByType(XmlTokenType.XML_NAME);
        if (nameNode == null) return;
        if (!"prefix".equals(nameNode.getText())) return;
        String data = getPiData(pi, "prefix");
        int separator = data.indexOf('=');
        if (separator <= 0 || separator >= data.length() - 1) return;
        String prefixText = data.substring(0, separator).trim();
        String typeName   = data.substring(separator + 1).trim();
        if (prefixText.length() != 1 || typeName.isEmpty()) return;
        char prefixChar = prefixText.charAt(0);
        if (isInvalidPrefixCharacter(prefixChar)) return;
        out.putIfAbsent(prefixChar, typeName);
    }

    /**
     * Extracts the data portion of a processing instruction by stripping the
     * {@code <?name} prefix and {@code ?>} suffix from the PI's raw text.
     *
     * <p>Using {@code pi.getText()} avoids relying on {@code XML_TAG_CHARACTERS} node
     * retrieval, which only returns the first token and misses content that the XML lexer
     * tokenises as separate nodes (e.g. {@code =} becomes {@code XML_EQ}).
     *
     * @param pi     the processing instruction
     * @param piName the PI target name (e.g. {@code "prefix"} or {@code "import"})
     * @return the trimmed data string, or {@code ""} if the PI text is malformed
     */
    public static @NotNull String getPiData(
            @NotNull XmlProcessingInstruction pi,
            @NotNull String piName) {
        String text = pi.getText(); // e.g. "<?prefix ^ = test.MyExtension?>"
        int nameIdx = text.indexOf(piName);
        if (nameIdx < 0) return "";
        String data = text.substring(nameIdx + piName.length());
        if (data.endsWith("?>")) data = data.substring(0, data.length() - 2);
        return data.trim();
    }

    /**
     * Returns {@code true} if {@code c} is <em>not</em> a valid prefix character.
     * Only the characters listed in {@link #VALID_PREFIX_CHARACTERS} are accepted;
     * all other characters, including letters, digits, whitespace, and punctuation
     * not in the allowlist, are rejected.
     */
    public static boolean isInvalidPrefixCharacter(char c) {
        return VALID_PREFIX_CHARACTERS.indexOf(c) < 0;
    }

    /**
     * Resolves a tag-name (simple, dotted-nested, or fully-qualified) to a {@link PsiClass}
     * using the imports declared in {@code contextFile}.
     *
     * @param tagName     the raw XML tag local name
     * @param contextFile the FXML2 file that provides the import declarations
     * @return the resolved class, or {@code null} if none could be found
     */
    public static @Nullable PsiClass resolve(@NotNull String tagName, @NotNull XmlFile contextFile) {
        Project project = contextFile.getProject();
        // XML files may be assigned a project-wide resolve scope by IntelliJ's XML support
        // rather than a module-specific one.  Computing the scope from the containing module
        // matches the fxml2 compiler's view: only types on the module's compile classpath
        // are resolvable, which excludes unrelated source sets and test-only roots.
        GlobalSearchScope scope = compileScope(contextFile);
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);

        if (tagName.contains(".")) {
            // Skip class resolution for names that are property paths (e.g. "Button.text",
            // "VBox.margin"). Calling facade.findClass() for those re-enters the PSI
            // reference-provider pipeline and causes a ProcessCanceledException retry loop.
            if (isPropertyPath(tagName, facade, scope)) {
                return null;
            }
            PsiClass direct = facade.findClass(tagName, scope);
            if (direct != null) {
                return direct;
            }
            return tryNestedVariant(facade, scope, tagName);
        }

        List<String> imports = parseImports(contextFile);

        for (String imp : imports) {
            if (!imp.endsWith(".*")) {
                String simpleName = imp.contains(".") ? imp.substring(imp.lastIndexOf('.') + 1) : imp;
                if (simpleName.equals(tagName)) {
                    PsiClass cls = facade.findClass(imp, scope);
                    if (cls != null) {
                        return cls;
                    }
                    PsiClass nested = tryNestedVariant(facade, scope, imp);
                    if (nested != null) {
                        return nested;
                    }
                }
            }
        }

        boolean hasJavaLang = false;
        for (String imp : imports) {
            if (imp.endsWith(".*")) {
                if (imp.equals("java.lang.*")) {
                    hasJavaLang = true;
                }
                String pkg = imp.substring(0, imp.length() - 1);
                PsiClass cls = facade.findClass(pkg + tagName, scope);
                if (cls != null) {
                    return cls;
                }
            }
        }

        if (!hasJavaLang) {
            return facade.findClass("java.lang." + tagName, scope);
        }

        return null;
    }

    /**
     * Returns the compile-classpath scope for the module that contains {@code file}.
     *
     * <p>This is the scope that mirrors what the fxml2 compiler sees: the module's own
     * production source roots plus all declared compile-time dependencies and the SDK.
     * Test source roots of the same module are excluded.
     *
     * <p>Falls back to the file's own {@link XmlFile#getResolveScope()} when the module
     * cannot be determined (e.g. for virtual or scratch files).
     */
    public static @NotNull GlobalSearchScope compileScope(@NotNull XmlFile file) {
        VirtualFile vFile = file.getVirtualFile();
        if (vFile == null) {
            return file.getResolveScope();
        }
        Module module = ModuleUtilCore.findModuleForFile(vFile, file.getProject());
        if (module == null) {
            return file.getResolveScope();
        }
        return GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, false);
    }

    /**
     * Returns all import targets declared in the given XML file, following the same resolution
     * order as the fxml-compiler's {@code FxmlParser.parseDocument()}:
     *
     * <ol>
     *   <li><b>Standalone .fxml files</b>: returns the {@code <?import?>} PIs found in the
     *       XML prolog.</li>
     *   <li><b>Embedded FXML2 fragments</b> (injected via {@code @ComponentView}):
     *       <ul>
     *         <li>The prolog holds the <em>synthesised</em> Java/Kotlin import PIs added by
     *             {@link org.jfxcore.fxml.lang.Fxml2MarkupAnnotationInjector}.</li>
     *         <li>Any user-written {@code <?import?>} PIs placed at the top of the annotation
     *             value appear as children of the {@code <fxml2:embedded>} wrapper root (not in
     *             the prolog).  They are collected by {@link #parseImportsFromWrapperRoot}.</li>
     *         <li>The two sets are merged so that Java/Kotlin imports come first (matching the
     *             compiler's precedence rule), and fxml-only imports are appended.</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <p>For embedded fragments, if the XML parser did not create a prolog node (which can
     * happen in lightweight test fixtures), this method falls back to reading import declarations
     * directly from the host Java file.
     */
    public static @NotNull List<String> parseImports(@NotNull XmlFile file) {
        // Cache the result per XmlFile. For standalone .fxml files the import list is
        // entirely determined by the file's own processing instructions, so the file itself
        // is the only dependency. For embedded FXML2 files the injection framework folds the
        // host Java file's imports into the XML prolog, so changes to the host Java file
        // (and only Java changes) may also affect the result; use the Java-language
        // modification tracker to invalidate the cache precisely on Java PSI changes, while
        // leaving it intact for FXML, CSS, and resource-bundle edits.
        boolean embedded = Fxml2EmbeddedUtil.isEmbeddedFxml2(file);
        return CachedValuesManager.getCachedValue(file, IMPORTS_CACHE, () -> {
            Object dependency = embedded
                    ? PsiModificationTracker.getInstance(file.getProject())
                            .forLanguage(JavaLanguage.INSTANCE)
                    : file;
            return CachedValueProvider.Result.create(parseImportsImpl(file), dependency);
        });
    }

    private static @NotNull List<String> parseImportsImpl(@NotNull XmlFile file) {
        // For embedded FXML2 files, merge host imports (prolog) with any user-written fxml
        // <?import?> PIs placed inside the wrapper root element.
        if (Fxml2EmbeddedUtil.isEmbeddedFxml2(file)) {
            return parseImportsForEmbedded(file);
        }

        // Standalone .fxml files: scan the XML prolog.
        List<String> result = parseImportsFromProlog(file);
        if (!result.isEmpty()) return result;

        // Fallback for any injected XML file whose prolog is empty: try the host Java file.
        PsiLanguageInjectionHost host = InjectedLanguageManager.getInstance(file.getProject())
                .getInjectionHost(file.getViewProvider());
        if (host != null) {
            return parseImportsFromHostElement(host);
        }
        return result; // empty for standalone non-.fxml files
    }

    /**
     * Computes the merged import list for an embedded FXML2 file.
     *
     * <p>The result follows the fxml-compiler's order:
     * host (Java/Kotlin) imports first, then user-written fxml {@code <?import?>} PIs that
     * are not already covered by a host import.
     *
     * @param file an embedded FXML2 file (must satisfy {@link Fxml2EmbeddedUtil#isEmbeddedFxml2})
     * @return the merged, deduplicated import list
     */
    private static @NotNull List<String> parseImportsForEmbedded(@NotNull XmlFile file) {
        // Host imports: Java/Kotlin imports synthesised into the prolog by the injector.
        List<String> hostImports = parseImportsFromProlog(file);

        // If the prolog is empty (XML parser did not create a prolog node for the injected
        // fragment), fall back to reading directly from the host Java file.
        if (hostImports.isEmpty()) {
            PsiLanguageInjectionHost host = InjectedLanguageManager.getInstance(file.getProject())
                    .getInjectionHost(file.getViewProvider());
            if (host != null) {
                hostImports = parseImportsFromHostElement(host);
            }
        }

        // fxml imports: user-written <?import?> PIs inside the <fxml2:embedded> wrapper root.
        List<String> fxmlImports = parseImportsFromWrapperRoot(file);

        if (fxmlImports.isEmpty()) {
            return hostImports;
        }
        if (hostImports.isEmpty()) {
            return fxmlImports;
        }

        // Merge: host imports first, then fxml imports not already present in host imports.
        // This mirrors FxmlParser.parseDocument() which calls imports.addAll(0, newImports).
        Set<String> hostSet = new HashSet<>(hostImports);
        List<String> merged = new ArrayList<>(hostImports.size() + fxmlImports.size());
        merged.addAll(hostImports);
        for (String fxmlImport : fxmlImports) {
            if (!hostSet.contains(fxmlImport)) {
                merged.add(fxmlImport);
            }
        }
        return merged;
    }

    /**
     * Scans the {@code <fxml2:embedded>} wrapper root for user-written {@code <?import?>}
     * processing instructions.
     *
     * <p>In an embedded FXML2 file the user-supplied markup is injected as the <em>body</em>
     * of a synthetic {@code <fxml2:embedded>} wrapper element, so any {@code <?import?>} PIs
     * written at the top of the annotation value appear as children of that root element -
     * not in the XML prolog.  This method collects them by walking the root element's
     * immediate children and stopping at the first actual element child.
     *
     * @param file the embedded FXML2 file
     * @return the list of import targets found inside the wrapper root, in document order
     */
    public static @NotNull List<String> parseImportsFromWrapperRoot(@NotNull XmlFile file) {
        XmlDocument document = file.getDocument();
        if (document == null) return List.of();
        XmlTag root = document.getRootTag();
        if (root == null || !Fxml2EmbeddedUtil.isWrapperRoot(root)) return List.of();

        List<String> result = new ArrayList<>();
        for (PsiElement child : root.getChildren()) {
            if (child instanceof XmlTag) {
                // Stop at the first real element: imports must precede element content.
                break;
            }
            if (child instanceof XmlProcessingInstruction pi) {
                String target = getImportTarget(pi);
                if (target != null) result.add(target.trim());
            }
        }
        return result.isEmpty() ? List.of() : result;
    }

    /**
     * Reads {@code <?import?>} PIs from the XML document prolog.
     */
    private static @NotNull List<String> parseImportsFromProlog(@NotNull XmlFile file) {
        List<String> result = new ArrayList<>();
        XmlDocument document = file.getDocument();
        if (document == null) return result;
        XmlProlog prolog = document.getProlog();
        if (prolog == null) return result;
        for (XmlProcessingInstruction pi : PsiTreeUtil.findChildrenOfType(prolog, XmlProcessingInstruction.class)) {
            String target = getImportTarget(pi);
            if (target != null) result.add(target.trim());
        }
        return result;
    }

    /**
     * For an injected XML file, reads the non-static import declarations from the
     * host Java or Kotlin file (the class annotated with {@code @ComponentView}).
     */
    private static @NotNull List<String> parseImportsFromHostElement(@NotNull PsiLanguageInjectionHost host) {
        PsiFile containingFile = host.getContainingFile();
        if (containingFile instanceof PsiJavaFile javaFile) {
            PsiImportList importList = javaFile.getImportList();
            if (importList == null) return List.of();
            List<String> result = new ArrayList<>();
            for (PsiImportStatement imp : importList.getImportStatements()) {
                if (imp instanceof PsiImportStaticStatement) continue;
                String qn = imp.getQualifiedName();
                if (qn == null) continue;
                if (Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN.equals(qn)) continue;
                // PsiImportStatement.getQualifiedName() strips ".*" for on-demand (wildcard) imports.
                // Restore it so that Fxml2ImportResolver recognizes wildcard imports correctly.
                result.add(imp.isOnDemand() ? qn + ".*" : qn);
            }
            return result;
        }
        // Kotlin host: delegate to Fxml2KotlinImportHelper which is guarded by the optional
        // Kotlin plugin dependency.  If the Kotlin plugin is not present the class will not
        // be found and we fall through to the empty result.
        try {
            return Fxml2KotlinImportHelper.parseImports(containingFile);
        } catch (NoClassDefFoundError ignored) {
            return List.of();
        }
    }

    private static @Nullable PsiClass tryNestedVariant(JavaPsiFacade facade, GlobalSearchScope scope, String name) {
        // Try each dot position from right to left as a potential outer-class / inner-class boundary.
        // E.g. "com.example.Outer.Inner" -> try "com.example.Outer$Inner" first, then give up.
        // We iterate dot positions in the ORIGINAL name to avoid re-visiting already-tried positions.
        int dot = name.lastIndexOf('.');
        while (dot > 0) {
            String candidate = name.substring(0, dot) + "$" + name.substring(dot + 1);
            PsiClass cls = facade.findClass(candidate, scope);
            if (cls != null) {
                return cls;
            }
            // Move to the next dot to the LEFT in the original name, not in candidate
            dot = name.lastIndexOf('.', dot - 1);
        }
        return null;
    }

    /**
     * Returns {@code true} if the dotted name is a property path rather than a class FQN -
     * i.e., some prefix of the name resolves to a class and there are still segments remaining.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code javafx.scene.control.Button} -> {@code false} (resolves fully as a class)</li>
     *   <li>{@code Button.text}                 -> {@code true}  (Button resolves, .text is a property)</li>
     *   <li>{@code javafx.scene.control.Button.text} -> {@code true}  (Button resolves, .text is a property)</li>
     *   <li>{@code foo.bar.baz}                 -> {@code false} (nothing resolves as a class)</li>
     * </ul>
     */
    static boolean isPropertyPath(@NotNull String name,
                                   @NotNull JavaPsiFacade facade,
                                   @NotNull GlobalSearchScope scope) {
        String[] segments = name.split("\\.", -1);
        StringBuilder candidate = new StringBuilder();
        for (int i = 0; i < segments.length - 1; i++) {
            if (!candidate.isEmpty()) candidate.append('.');
            candidate.append(segments[i]);
            String fqn = candidate.toString();
            // Check as-is and with nested-class $ variant
            if (facade.findClass(fqn, scope) != null) return true;
            // Also try with the next segment joined via $ (nested class): if it resolves,
            // NOT a property path at this boundary (the next segment is still part of the class name).
        }
        return false;
    }

    public static @Nullable String getImportTarget(@NotNull XmlProcessingInstruction pi) {
        ASTNode node = pi.getNode();
        ASTNode nameNode = node.findChildByType(XmlTokenType.XML_NAME);
        ASTNode dataNode = node.findChildByType(XmlTokenType.XML_TAG_CHARACTERS);
        if (nameNode == null || dataNode == null) {
            return null;
        }
        if (!"import".equals(nameNode.getText())) {
            return null;
        }
        return dataNode.getText();
    }

    /**
     * Returns the class name from a {@code <?prefix X = ClassName?>} processing instruction,
     * or {@code null} if the PI is not a prefix declaration or is malformed.
     *
     * <p>The returned name is exactly as written in the PI: it may be a simple name
     * (resolved via imports) or a fully-qualified name.
     */
    public static @Nullable String getPrefixDeclarationTypeName(@NotNull XmlProcessingInstruction pi) {
        ASTNode nameNode = pi.getNode().findChildByType(XmlTokenType.XML_NAME);
        if (nameNode == null || !"prefix".equals(nameNode.getText())) return null;
        String data = getPiData(pi, "prefix");
        int separator = data.indexOf('=');
        if (separator <= 0 || separator >= data.length() - 1) return null;
        String typeName = data.substring(separator + 1).trim();
        return typeName.isEmpty() ? null : typeName;
    }

    /**
     * Returns the start offset of the class name within {@code pi.getText()} for a
     * {@code <?prefix X = ClassName?>} processing instruction, or {@code -1} if the PI
     * is not a well-formed prefix declaration.
     *
     * <p>The offset is suitable for constructing a {@link com.intellij.openapi.util.TextRange}
     * relative to the start of the {@code XmlProcessingInstruction} element.
     */
    public static int getPrefixDeclarationTypeNameOffset(@NotNull XmlProcessingInstruction pi) {
        String typeName = getPrefixDeclarationTypeName(pi);
        if (typeName == null) return -1;
        String piText = pi.getText();
        int prefixIdx = piText.indexOf("prefix");
        if (prefixIdx < 0) return -1;
        int eqIdx = piText.indexOf('=', prefixIdx + "prefix".length());
        if (eqIdx < 0) return -1;
        // Skip whitespace after '=' to reach the class name.
        int pos = eqIdx + 1;
        while (pos < piText.length() && Character.isWhitespace(piText.charAt(pos))) pos++;
        // Verify we found the expected class name at this position.
        if (!piText.startsWith(typeName, pos)) return -1;
        return pos;
    }
}
