package org.jfxcore.fxml.codeinsight;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ExportableOrderEntry;
import com.intellij.openapi.roots.JdkOrderEntry;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiParameterList;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlProcessingInstruction;
import com.intellij.psi.xml.XmlProlog;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtPsiFactory;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Intention action that inserts a {@code <?import fqn?>} processing instruction for an
 * unresolved class name in an FXML 2.0 file.
 *
 * <p>When exactly one candidate class is found, the import is inserted immediately.
 * When multiple candidates exist, a popup lets the user choose.
 */
public final class Fxml2AddImportFix implements IntentionAction, LocalQuickFix, PriorityAction {

    /** FQN prefixes that are always excluded as internal APIs (e.g. JDK/JavaFX sun packages). */
    private static final List<String> INTERNAL_PREFIXES = List.of("com.sun.", "sun.");

    /** Package segment names that indicate implementation-internal code in library JARs. */
    private static final List<String> INTERNAL_SEGMENTS = List.of("internal", "impl");

    /**
     * FQN prefixes ranked by priority (lower index = higher priority) after project-source
     * classes and {@code java.lang} classes. Candidates not matching any prefix end up last.
     */
    private static final List<String> PREFERRED_PREFIXES = List.of("org.jfxcore.", "javafx.");

    /**
     * FQN prefixes that are pushed to the bottom of the candidate list (after unrecognized
     * libraries), because they are legacy UI toolkits irrelevant to FXML 2.0.
     */
    private static final List<String> DEPRIORITIZED_PREFIXES = List.of("java.awt.", "javax.swing.");

    /** The unqualified class name for which an {@code <?import?>} should be inserted. */
    private final String simpleName;

    /**
     * When {@code true} (the default), classes that cannot be instantiated or used as FXML 2.0
     * tags (abstract classes, plain interfaces without static members, etc.) are filtered out.
     * Set to {@code false} when the fix is offered for a <em>declaring class</em> in a static
     * property attribute (e.g. {@code Command} in {@code Command.onAction}), in that context
     * the class does not need to be directly instantiable.
     */
    private final boolean checkUnusable;

    public Fxml2AddImportFix(@NotNull String simpleName) {
        this(simpleName, true);
    }

    public Fxml2AddImportFix(@NotNull String simpleName, boolean checkUnusable) {
        this.simpleName = simpleName;
        this.checkUnusable = checkUnusable;
    }

    // -----------------------------------------------------------------------
    // Static helpers
    // -----------------------------------------------------------------------

    /**
     * Finds all classes whose simple name matches {@code simpleName} and are reachable
     * from the module's production-runtime classpath (COMPILE + RUNTIME scope order entries).
     * This excludes annotation processors, {@code compileOnly} build tools, Gradle internals,
     * and other PROVIDED/TEST-only dependencies that should never appear as FXML 2.0 import choices.
     * Classes already imported in the file are also excluded.
     *
     * <p>Uses {@code checkUnusable = true} (the default; classes not usable as FXML2 tags
     * are filtered). Use {@link #findCandidates(String, XmlFile, boolean)} when the class is
     * sought as a static-property declaring class rather than an instantiatable tag.
     */
    @SuppressWarnings("unused") // public API, may be called from external code / plugins
    public static @NotNull List<PsiClass> findCandidates(
            @NotNull String simpleName,
            @NotNull XmlFile xmlFile) {
        return findCandidates(simpleName, xmlFile, true);
    }

    /**
     * Like {@link #findCandidates(String, XmlFile)} but allows callers to control whether
     * classes that cannot be used as FXML2 tags are excluded.
     *
     * @param checkUnusable if {@code true}, classes filtered by {@link #isUnusableInFxml} are
     *                      excluded (suitable for tag-name imports); if {@code false}, abstract
     *                      classes and interfaces that carry static property methods are also
     *                      included (suitable for static-property declaring-class imports
     *                      or {@code Class<T>} literal value imports).
     */
    public static @NotNull List<PsiClass> findCandidates(
            @NotNull String simpleName,
            @NotNull XmlFile xmlFile,
            boolean checkUnusable) {
        Project project = xmlFile.getProject();
        PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
        PsiClass[] classes = cache.getClassesByName(simpleName, xmlFile.getResolveScope());

        // Build a set of class roots that are on the production runtime classpath.
        // Entries with DependencyScope.PROVIDED (annotationProcessor, compileOnly) and
        // DependencyScope.TEST are excluded; only COMPILE and RUNTIME entries remain.
        Set<VirtualFile> runtimeRoots = buildProductionRuntimeRoots(xmlFile);

        List<String> existingImports = Fxml2ImportResolver.parseImports(xmlFile);

        List<PsiClass> candidates = new ArrayList<>();
        for (PsiClass cls : classes) {
            String fqn = cls.getQualifiedName();
            if (shouldSkipClass(cls, fqn, runtimeRoots, checkUnusable)) continue;
            boolean alreadyImported = existingImports.contains(fqn)
                    || existingImports.contains(packageOf(fqn) + ".*");
            if (!alreadyImported) candidates.add(cls);
        }
        candidates.sort(Comparator
                .comparingInt(Fxml2AddImportFix::candidateTier)
                .thenComparing(c -> c.getQualifiedName() != null ? c.getQualifiedName() : ""));
        return candidates;
    }

    /**
     * Returns a sort tier for a candidate class (lower = higher priority):
     * <ol>
     *   <li>Project source classes</li>
     *   <li>Classes in exactly {@code java.lang}</li>
     *   <li>Classes matching a {@link #PREFERRED_PREFIXES} entry, in prefix-list order</li>
     *   <li>Everything else</li>
     * </ol>
     */
    static int candidateTier(@NotNull PsiClass cls) {
        PsiFile psiFile = cls.getContainingFile();
        if (psiFile != null) {
            VirtualFile vf = psiFile.getVirtualFile();
            if (vf != null && ProjectFileIndex.getInstance(cls.getProject()).isInSource(vf)) {
                return 0; // project sources: highest priority
            }
        }
        String fqn = cls.getQualifiedName();
        if (fqn != null) {
            // Exact java.lang package only: java.lang.String matches, java.lang.invoke.MethodHandle does not.
            if (fqn.startsWith("java.lang.") && fqn.indexOf('.', 10) < 0) return 1;
            for (int i = 0; i < PREFERRED_PREFIXES.size(); i++) {
                if (fqn.startsWith(PREFERRED_PREFIXES.get(i))) return 2 + i;
            }
            int restTier = 2 + PREFERRED_PREFIXES.size();
            for (int i = 0; i < DEPRIORITIZED_PREFIXES.size(); i++) {
                if (fqn.startsWith(DEPRIORITIZED_PREFIXES.get(i))) return restTier + 1 + i;
            }
            return restTier; // everything else
        }
        return 2 + PREFERRED_PREFIXES.size();
    }

    /**
     * Collects the class roots of all order entries that contribute to the production runtime
     * classpath (scope COMPILE or RUNTIME). JDK entries are always included.
     * Returns an empty set when the module cannot be determined (fallback: no filtering).
     */
    static @NotNull Set<VirtualFile> buildProductionRuntimeRoots(@NotNull XmlFile xmlFile) {
        Module module = ModuleUtilCore.findModuleForFile(xmlFile);
        if (module == null) return Set.of();

        // satisfying: keep the JDK entry (always runtime) and any ExportableOrderEntry whose
        // scope is COMPILE or RUNTIME (i.e. isForProductionRuntime() == true).
        VirtualFile[] classRoots = OrderEnumerator.orderEntries(module)
                .satisfying(entry ->
                        entry instanceof JdkOrderEntry
                        || (entry instanceof ExportableOrderEntry e
                                && e.getScope().isForProductionRuntime()))
                .recursively()
                .classes()
                .getRoots();
        return new HashSet<>(Arrays.asList(classRoots));
    }

    /**
     * Returns {@code true} if the class's virtual file is NOT inside any of the given
     * class roots AND is not a project source file.
     * Project source files are always considered "inside" the project and must never be
     * excluded by the runtime-roots check (which only covers compiled class roots / JARs).
     */
    static boolean isClassOutsideRoots(@NotNull PsiClass cls, @NotNull Set<VirtualFile> roots) {
        PsiFile psiFile = cls.getContainingFile();
        if (psiFile == null) return true;
        VirtualFile vf = psiFile.getVirtualFile();
        if (vf == null) return true;
        // Project source files are always usable regardless of the runtime class roots
        if (ProjectFileIndex.getInstance(cls.getProject()).isInSource(vf)) return false;
        for (VirtualFile root : roots) {
            if (VfsUtilCore.isAncestor(root, vf, false)) return false;
        }
        return true;
    }

    /**
     * Returns {@code true} if the FQN belongs to an internal/implementation package that
     * should never appear as an FXML2 import suggestion:
     * <ul>
     *   <li>{@code com.sun.*}: JDK/JavaFX internal APIs (always excluded)</li>
     *   <li>any package segment named {@code internal} or {@code impl} in a <em>library</em>
     *        e.g. {@code org.gradle.internal.*}, {@code com.intellij.*.impl.*}</li>
     * </ul>
     * Classes whose virtual file is in the project's own source roots are never excluded by
     * the {@code impl}/{@code internal} check, so user code in e.g. {@code com.example.impl}
     * is always offered.
     */
    static boolean isInternalPackage(@NotNull PsiClass cls, @NotNull String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        if (lastDot <= 0) return false;
        String pkg = fqn.substring(0, lastDot);

        // Always-excluded prefixes (e.g. com.sun.*): match pkg == "com.sun" or pkg starts with "com.sun."
        for (String prefix : INTERNAL_PREFIXES) {
            if (pkg.startsWith(prefix) || pkg.equals(prefix.substring(0, prefix.length() - 1))) return true;
        }

        // impl/internal segments are only filtered for library classes, not project sources
        for (String segment : pkg.split("\\.")) {
            if (INTERNAL_SEGMENTS.contains(segment)) {
                // Allow if the class lives in the project's own source roots
                PsiFile psiFile = cls.getContainingFile();
                VirtualFile vf = psiFile != null ? psiFile.getVirtualFile() : null;
                return vf == null || !ProjectFileIndex.getInstance(cls.getProject()).isInSource(vf);
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if {@code cls} cannot be used as an FXML2 tag.
     * A class is usable if it matches any of the fxml2 compiler's construction strategies:
     *
     * <ul>
     *   <li>Public no-arg constructor -> default-object instantiation</li>
     *   <li>Public constructor with all params annotated {@code @NamedArg} -> named-arg instantiation</li>
     *   <li>Enum -> literal coercion via {@code Enum.valueOf()} and {@code fx:value}</li>
     *   <li>Public static {@code valueOf(String)} method -> {@code fx:value} instantiation
     *       (e.g. {@code Color}, {@code Duration})</li>
     *   <li>Public static fields -> usable via {@code fx:constant} (e.g. singleton instances)</li>
     *   <li>Public static no-arg methods -> usable via {@code fx:factory}</li>
     * </ul>
     *
     * Abstract classes and plain interfaces without any of the above are excluded.
     */
    static boolean isUnusableInFxml(@NotNull PsiClass cls) {
        boolean isAbstractOrInterface = cls.isInterface()
                || cls.hasModifierProperty(PsiModifier.ABSTRACT);

        if (!isAbstractOrInterface) {
            // Concrete class: usable if it has a public no-arg or @NamedArg constructor.
            // If getConstructors() is empty the compiler inserts an implicit public no-arg
            // constructor, which is always usable.
            PsiMethod[] ctors = cls.getConstructors();
            if (ctors.length == 0) return false; // implicit public no-arg ctor
            for (PsiMethod ctor : ctors) {
                if (!ctor.hasModifierProperty(PsiModifier.PUBLIC)) continue;
                PsiParameterList params = ctor.getParameterList();
                if (params.isEmpty()) return false; // default constructor
                boolean allNamed = true;
                for (var param : params.getParameters()) {
                    if (param.getAnnotation("javafx.beans.NamedArg") == null) {
                        allNamed = false;
                        break;
                    }
                }
                if (allNamed) return false;
            }
        }

        // Enum: always usable via literal coercion / fx:value
        if (cls.isEnum()) return false;

        // Public static valueOf(String) -> fx:value (e.g. Color, Duration, Insets)
        for (PsiMethod m : cls.findMethodsByName("valueOf", false)) {
            if (!m.hasModifierProperty(PsiModifier.PUBLIC)) continue;
            if (!m.hasModifierProperty(PsiModifier.STATIC)) continue;
            PsiParameterList params = m.getParameterList();
            if (params.getParametersCount() == 1) {
                String typeName = params.getParameters()[0].getType().getCanonicalText();
                if ("java.lang.String".equals(typeName) || "String".equals(typeName)) return false;
            }
        }

        // Public static fields -> fx:constant (e.g. Region.USE_PREF_SIZE, singleton instances)
        for (PsiField f : cls.getFields()) {
            if (f.hasModifierProperty(PsiModifier.PUBLIC) && f.hasModifierProperty(PsiModifier.STATIC)) {
                return false;
            }
        }

        // Public static no-arg methods -> fx:factory
        for (PsiMethod m : cls.getMethods()) {
            if (!m.hasModifierProperty(PsiModifier.PUBLIC)) continue;
            if (!m.hasModifierProperty(PsiModifier.STATIC)) continue;
            if (m.getParameterList().isEmpty()) return false;
        }

        return true;
    }

    /**
     * Returns {@code true} when a class should be excluded from FXML import/completion
     * candidates: null FQN, annotation type, outside the runtime roots, internal package,
     * or (when {@code checkUnusable}) excluded by {@link #isUnusableInFxml}.
     */
    static boolean shouldSkipClass(
            @NotNull PsiClass cls,
            @org.jetbrains.annotations.Nullable String fqn,
            @NotNull Set<VirtualFile> runtimeRoots,
            boolean checkUnusable) {
        return fqn == null
                || cls.isAnnotationType()
                || (!runtimeRoots.isEmpty() && isClassOutsideRoots(cls, runtimeRoots))
                || isInternalPackage(cls, fqn)
                || (checkUnusable && isUnusableInFxml(cls));
    }

    /**
     * Inserts {@code <?import fqn?>} into the prolog of {@code xmlFile}, sorted
     * alphabetically among existing imports. Safe to call from intention actions
     * (outside any write action), wraps the PSI work in a {@link WriteCommandAction}.
     *
     * <p>When called from a completion insert handler (already inside a write action),
     * call {@link #doInsertImportPsi} directly after {@code context.commitDocument()}.
     */
    public static void insertImport(@NotNull Project project,
                                    @NotNull XmlFile xmlFile,
                                    @NotNull String fqn) {
        WriteCommandAction.runWriteCommandAction(project, "Add Import", null, () -> {
            PsiDocumentManager.getInstance(project).commitAllDocuments();
            doInsertImportPsi(project, xmlFile, fqn);
        }, xmlFile);
    }

    /**
     * Raw PSI insertion: must be called from within an existing write action after the
     * document has been committed (e.g. via {@code context.commitDocument()}).
     */
    public static void doInsertImportPsi(@NotNull Project project,
                                         @NotNull XmlFile xmlFile,
                                         @NotNull String fqn) {
        // Skip if already imported (exact or wildcard)
        List<String> existing = Fxml2ImportResolver.parseImports(xmlFile);
        String pkg = packageOf(fqn);
        if (existing.contains(fqn) || (!pkg.isEmpty() && existing.contains(pkg + ".*"))) return;

        // For embedded FXML2 (injected into a Kotlin/Java @ComponentView annotation string), the
        // <?import?> PIs are part of the synthetic injection prefix: they have no backing
        // text in the host document.  Attempting to modify them via PSI triggers an
        // AssertionError in DocumentWindowImpl.calculateMinEditSequence.
        // Instead, add the import to the host source file; the injector will re-synthesize
        // the corresponding <?import?> PI in the prefix on the next refresh.
        if (Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile)) {
            PsiLanguageInjectionHost host = Fxml2EmbeddedUtil.getInjectionHost(xmlFile);
            if (host != null) {
                insertImportIntoHostFile(project, host, fqn);
            }
            return;
        }

        XmlDocument document = xmlFile.getDocument();
        if (document == null) return;

        XmlProcessingInstruction newPi = createImportPI(project, fqn);
        if (newPi == null) return;

        XmlProlog prolog = document.getProlog();
        if (prolog != null) {
            // Collect existing import PIs in order
            List<XmlProcessingInstruction> importPis = new ArrayList<>();
            for (PsiElement child = prolog.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof XmlProcessingInstruction pi
                        && Fxml2ImportResolver.getImportTarget(pi) != null) {
                    importPis.add(pi);
                }
            }

            if (importPis.isEmpty()) {
                prolog.add(newPi);
            } else {
                // Find the correct alphabetical insertion point
                XmlProcessingInstruction insertBefore = null;
                for (XmlProcessingInstruction pi : importPis) {
                    String target = Fxml2ImportResolver.getImportTarget(pi);
                    if (target != null && fqn.compareTo(target) < 0) {
                        insertBefore = pi;
                        break;
                    }
                }

                if (insertBefore != null) {
                    PsiElement inserted = prolog.addBefore(newPi, insertBefore);
                    com.intellij.psi.PsiParserFacade parserFacade =
                            com.intellij.psi.PsiParserFacade.getInstance(project);
                    prolog.addAfter(parserFacade.createWhiteSpaceFromText("\n"), inserted);
                } else {
                    XmlProcessingInstruction last = importPis.getLast();
                    PsiElement inserted = prolog.addAfter(newPi, last);
                    com.intellij.psi.PsiParserFacade parserFacade =
                            com.intellij.psi.PsiParserFacade.getInstance(project);
                    prolog.addBefore(parserFacade.createWhiteSpaceFromText("\n"), inserted);
                }
            }
        } else {
            XmlTag rootTag = document.getRootTag();
            if (rootTag != null) {
                document.addBefore(newPi, rootTag);
            }
        }

        PsiDocumentManager.getInstance(project).commitAllDocuments();
    }

    /**
     * Adds a source-level import for {@code fqn} to the Java or Kotlin file that contains
     * the {@code @ComponentView} annotation string.
     *
     * <p>This is called instead of modifying the injected XML PSI directly, which fails when
     * the XML is injected (the {@code <?import?>} PIs live in a synthetic prefix that has no
     * backing text in the host document).  After this method adds the import to the host file,
     * the language injector will include the corresponding {@code <?import?>} PI in the
     * synthesised prefix on the next PSI refresh.
     */
    private static void insertImportIntoHostFile(
            @NotNull Project project,
            @NotNull PsiLanguageInjectionHost host,
            @NotNull String fqn) {

        PsiFile hostFile = host.getContainingFile();

        // Kotlin host path, guarded by try/catch in case the Kotlin plugin is absent.
        try {
            if (hostFile instanceof KtFile ktFile) {
                var importList = ktFile.getImportList();
                if (importList == null) return;
                // Skip if the import is already present.
                for (var imp : ktFile.getImportDirectives()) {
                    if (!imp.isValidImport()) continue;
                    var name = imp.getImportedFqName();
                    if (name != null && fqn.equals(name.asString())) return;
                }
                var factory = new KtPsiFactory(project);
                var tempFile = factory.createFile("import " + fqn + "\n");
                var directives = tempFile.getImportDirectives();
                if (!directives.isEmpty()) {
                    importList.add(directives.getFirst().copy());
                }
                return;
            }
        } catch (NoClassDefFoundError ignored) {
            // Kotlin plugin not present at runtime, fall through to Java path.
        }

        // Java host path.
        if (hostFile instanceof PsiJavaFile javaFile) {
            var importList = javaFile.getImportList();
            if (importList == null) return;
            // Skip if already present.
            for (PsiImportStatement stmt : importList.getImportStatements()) {
                if (fqn.equals(stmt.getQualifiedName())) return;
            }
            PsiClass psiClass = JavaPsiFacade.getInstance(project)
                    .findClass(fqn, javaFile.getResolveScope());
            if (psiClass == null) return;
            try {
                importList.add(PsiElementFactory.getInstance(project).createImportStatement(psiClass));
            } catch (IncorrectOperationException ignored) {}
        }
    }

    static @Nullable XmlProcessingInstruction createImportPI(
            @NotNull Project project, @NotNull String fqn) {
        String dummyContent = "<?import " + fqn + "?><r/>";
        try {
            XmlFile dummy = (XmlFile) PsiFileFactory.getInstance(project)
                    .createFileFromText("dummy.fxml", XMLLanguage.INSTANCE, dummyContent);
            XmlDocument doc = dummy.getDocument();
            if (doc == null) return null;
            XmlProlog prolog = doc.getProlog();
            if (prolog == null) return null;
            for (PsiElement child = prolog.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof XmlProcessingInstruction pi) return pi;
            }
        } catch (IncorrectOperationException ignored) {}
        return null;
    }

    private static @NotNull String packageOf(@NotNull String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot > 0 ? fqn.substring(0, dot) : "";
    }

    // -----------------------------------------------------------------------
    // IntentionAction implementation
    // -----------------------------------------------------------------------

    @Override
    public @NotNull String getText() {
        return "Add import for '" + simpleName + "'";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Add FXML2 import";
    }

    @Override
    public @NotNull Priority getPriority() {
        return Priority.TOP;
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        if (!(file instanceof XmlFile xmlFile)) return false;
        if (!Fxml2FileType.isFxml2(xmlFile)) return false;
        return !findCandidates(simpleName, xmlFile, checkUnusable).isEmpty();
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file)
            throws IncorrectOperationException {
        if (!(file instanceof XmlFile xmlFile)) return;

        List<PsiClass> candidates = findCandidates(simpleName, xmlFile, checkUnusable);
        if (candidates.isEmpty()) return;

        if (candidates.size() == 1) {
            String fqn = candidates.getFirst().getQualifiedName();
            if (fqn != null) insertImport(project, xmlFile, fqn);
            return;
        }

        // Multiple candidates: use FQN strings instead of PsiClass to avoid PSI-on-EDT errors
        List<String> fqns = new ArrayList<>();
        for (PsiClass cls : candidates) {
            String fqn = cls.getQualifiedName();
            if (fqn != null) fqns.add(fqn);
        }
        if (fqns.isEmpty()) return;

        JBPopupFactory.getInstance().createListPopup(
                new BaseListPopupStep<>("Choose Class to Import", fqns) {
                    @Override
                    public @NotNull String getTextFor(@NotNull String fqn) {
                        return fqn;
                    }

                    @Override
                    public PopupStep<?> onChosen(@NotNull String fqn, boolean finalChoice) {
                        if (finalChoice) {
                            ApplicationManager.getApplication().invokeLater(
                                    () -> insertImport(project, xmlFile, fqn));
                        }
                        return FINAL_CHOICE;
                    }
                }
        ).showInBestPositionFor(editor);
    }

    // LocalQuickFix: invoked from inspection results / XmlHighlightVisitor-attached fixes.
    // When there is exactly one candidate, insert immediately; multi-candidate case
    // cannot show a popup without an editor, so we pick the best-ranked candidate.
    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiFile file = descriptor.getPsiElement().getContainingFile();
        if (!(file instanceof XmlFile xmlFile)) return;
        List<PsiClass> candidates = findCandidates(simpleName, xmlFile, checkUnusable);
        if (candidates.isEmpty()) return;
        String fqn = candidates.getFirst().getQualifiedName();
        if (fqn != null) insertImport(project, xmlFile, fqn);
    }

    @Override
    public boolean startInWriteAction() {
        // We manage our own write action inside invoke()
        return false;
    }
}
