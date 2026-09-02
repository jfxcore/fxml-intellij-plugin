package org.jfxcore.fxml.resource;

import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlProcessingInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.jfxcore.fxml.lang.Fxml2FileType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The embedded resources of one FXML/2 document.
 *
 * <p>The model is the single place that knows how to read resource declarations out of either
 * document form.  In a standalone {@code .fxml} file the declarations come from the document's
 * {@code <?resource ?>} processing instructions.  In markup embedded in a {@code @ComponentView}
 * annotation value they come from the raw text of the injection host, because the injected XML
 * deliberately does not contain the payloads: the payload text is carved out of the XML fragment
 * so that the payload's own language can be injected into it instead.  Reading a payload out of
 * injected XML PSI would therefore read a hole.
 *
 * <p>Declarations are scoped to the whole document and their order and position are not
 * significant, which is why the model is keyed on the file rather than on the prolog.
 *
 * <p>Instances are cached per file and invalidated on any PSI change, mirroring how the import
 * resolver caches its view of a document.
 */
public final class Fxml2ResourceModel {

    private static final Key<CachedValue<Fxml2ResourceModel>> CACHE_KEY =
            Key.create("Fxml2ResourceModel");

    private final List<Fxml2ResourceEntry> entries;
    private final Map<String, Fxml2ResourceEntry> byName;

    private Fxml2ResourceModel(@NotNull List<Fxml2ResourceEntry> entries) {
        this.entries = List.copyOf(entries);

        Map<String, Fxml2ResourceEntry> index = new LinkedHashMap<>();
        for (Fxml2ResourceEntry entry : entries) {
            index.putIfAbsent(entry.name().value(), entry);
        }
        this.byName = Map.copyOf(index);
    }

    /** Returns the resource model of {@code file}, computing and caching it on first use. */
    public static @NotNull Fxml2ResourceModel of(@NotNull XmlFile file) {
        return CachedValuesManager.getManager(file.getProject()).getCachedValue(
                file,
                CACHE_KEY,
                () -> CachedValueProvider.Result.create(build(file), PsiModificationTracker.MODIFICATION_COUNT),
                false);
    }

    /**
     * Returns the resource model of the FXML/2 document {@code element} belongs to, or an empty
     * model when it belongs to none.
     */
    public static @NotNull Fxml2ResourceModel of(@NotNull PsiElement element) {
        PsiFile file = element.getContainingFile();
        return file instanceof XmlFile xmlFile && Fxml2FileType.isFxml2(xmlFile)
                ? of(xmlFile)
                : new Fxml2ResourceModel(List.of());
    }

    /** Returns every declaration of the document, in declaration order. */
    public @NotNull List<Fxml2ResourceEntry> entries() {
        return entries;
    }

    /** Returns {@code true} when the document declares no embedded resource. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Returns the declaration a reference written as {@code reference} resolves to, or
     * {@code null} when the document declares no such resource.
     *
     * <p>The match is exact in case and in interior whitespace, because the runtime derives the
     * resource file name from the logical name verbatim: a near miss would not resolve at runtime
     * either.  A name that is absolute or contains a path separator never names an embedded
     * resource, and is rejected here rather than at every call site.
     */
    public @Nullable Fxml2ResourceEntry resolve(@NotNull String reference) {
        return isEmbeddableReference(reference) ? byName.get(reference) : null;
    }

    /**
     * Returns {@code true} when {@code reference} could name an embedded resource at all.
     *
     * <p>A name beginning with {@code /} is resolved against the class loader and never performs
     * embedded lookup, and a relative name containing a path separator can only refer to an
     * external resource, because embedded declarations have single-component names.
     */
    public static boolean isEmbeddableReference(@NotNull String reference) {
        return !reference.isEmpty()
                && reference.charAt(0) != '/'
                && reference.indexOf('/') < 0
                && reference.indexOf('\\') < 0;
    }

    /** Returns every diagnostic of the document's declarations, in declaration order. */
    public @NotNull List<Fxml2ResourceProblem> problems() {
        return entries.stream().flatMap(entry -> entry.problems().stream()).toList();
    }

    // -----------------------------------------------------------------------
    // Building
    // -----------------------------------------------------------------------

    private static @NotNull Fxml2ResourceModel build(@NotNull XmlFile file) {
        List<Fxml2ResourceEntry> entries = Fxml2EmbeddedUtil.isEmbeddedFxml2(file)
                ? readFromInjectionHost(file)
                : readFromProcessingInstructions(file);

        return new Fxml2ResourceModel(withDuplicateProblems(entries));
    }

    /**
     * Reads the declarations of a standalone document from its processing instructions.
     *
     * <p>Each instruction is parsed from its own text, so the spans of a declaration are relative
     * to the instruction that carries it.
     */
    private static @NotNull List<Fxml2ResourceEntry> readFromProcessingInstructions(@NotNull XmlFile file) {
        List<Fxml2ResourceEntry> entries = new ArrayList<>();

        Collection<XmlProcessingInstruction> instructions =
                PsiTreeUtil.findChildrenOfType(file, XmlProcessingInstruction.class);

        for (XmlProcessingInstruction instruction : instructions) {
            String text = instruction.getText();
            Fxml2ResourceParseResult result =
                    Fxml2ResourceInstructionParser.parseAt(text, 0, text.length());
            if (result == null || result.declaration() == null) continue;

            entries.add(new Fxml2ResourceEntry(result.declaration(), result.problems(), instruction));
        }

        return entries;
    }

    /**
     * Reads the declarations of embedded markup from the raw text of its injection host.
     *
     * <p>The host text is the only place the payloads still exist in full: the injected XML
     * fragment has them carved out.  Scanning raw text also means a declaration that is currently
     * malformed still yields whatever could be read of it, which keeps navigation and completion
     * working while a declaration is being typed.
     */
    private static @NotNull List<Fxml2ResourceEntry> readFromInjectionHost(@NotNull XmlFile file) {
        PsiLanguageInjectionHost host = Fxml2EmbeddedUtil.getInjectionHost(file);
        if (host == null) return List.of();

        List<Fxml2ResourceEntry> entries = new ArrayList<>();
        String text = host.getText();

        for (Fxml2ResourceInstruction instruction : Fxml2ResourceScanner.scanAll(text)) {
            Fxml2ResourceParseResult result = Fxml2ResourceInstructionParser.parse(text, instruction);
            if (result.declaration() == null) continue;

            entries.add(new Fxml2ResourceEntry(result.declaration(), result.problems(), host));
        }

        return entries;
    }

    /**
     * Adds a duplicate diagnostic to every declaration whose name collides with an earlier one.
     *
     * <p>Collision is case-insensitive across the whole document, because the resource file the
     * compiler writes is named case-insensitively: {@code value.txt} and {@code Value.txt} would
     * be the same file.  The diagnostic names the earlier declaration's one-based position, which
     * is how the compiler reports it.
     */
    private static @NotNull List<Fxml2ResourceEntry> withDuplicateProblems(@NotNull List<Fxml2ResourceEntry> entries) {
        Map<String, Fxml2ResourceEntry> seen = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<Fxml2ResourceEntry> result = new ArrayList<>(entries.size());

        for (Fxml2ResourceEntry entry : entries) {
            Fxml2ResourceEntry previous = seen.putIfAbsent(entry.name().value(), entry);
            if (previous == null) {
                result.add(entry);
                continue;
            }

            List<Fxml2ResourceProblem> problems = new ArrayList<>(entry.problems());
            problems.add(duplicateProblem(entry, previous));
            result.add(new Fxml2ResourceEntry(entry.declaration(), problems, entry.anchor()));
        }

        return result;
    }

    private static @NotNull Fxml2ResourceProblem duplicateProblem(@NotNull Fxml2ResourceEntry entry,
                                                                  @NotNull Fxml2ResourceEntry previous) {
        LineColumn position = positionOf(previous);

        return Fxml2ResourceProblem.of(
                Fxml2ResourceProblemKind.DUPLICATE_DECLARATION,
                entry.declaration().nameSpan(),
                entry.name().value(),
                position.line(),
                position.column());
    }

    /** Returns the one-based position of {@code entry}'s name in the file it is declared in. */
    private static @NotNull LineColumn positionOf(@NotNull Fxml2ResourceEntry entry) {
        CharSequence text = entry.declaringFile().getViewProvider().getContents();
        int offset = Math.min(entry.nameRange().getStartOffset(), text.length());

        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < offset; ++i) {
            if (text.charAt(i) == '\n') {
                ++line;
                lineStart = i + 1;
            }
        }

        return new LineColumn(line, offset - lineStart + 1);
    }

    /** A one-based position in a source file. */
    private record LineColumn(int line, int column) {}
}
