package org.jfxcore.fxml.lang;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.XmlRecursiveElementVisitor;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.Processor;
import com.intellij.util.QueryExecutor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * {@link ReferencesSearch} extension that finds {@link Fxml2StyleClassReference}s in
 * FXML2 files (both standalone and embedded) that reference a CSS class selector.
 *
 * <h3>Why is this needed?</h3>
 * <p>When "Find Usages" (Alt+F7) is invoked on a CSS class selector (e.g. {@code .my-style1}
 * in a {@code .css} file), IntelliJ's standard reference search does not discover usages
 * in FXML2 files because:
 * <ul>
 *   <li>The FXML2 plugin represents CSS class selectors as {@link CssSelectorElement}
 *       (a {@code FakePsiElement}), which is not identity-equal to the CSS plugin's PSI
 *       element for the same selector.</li>
 *   <li>CSS class names with hyphens (e.g. {@code my-style1}) are not indexed as a single
 *       word in IntelliJ's word index, so {@code CachesBasedRefSearcher} cannot find them.</li>
 *   <li>Embedded FXML2 markup inside {@code @ComponentView} text-block literals is never
 *       indexed in the word index at all.</li>
 * </ul>
 *
 * <p>This searcher handles two cases for the target element:
 * <ol>
 *   <li><b>{@link CssSelectorElement}</b>: our own fake element created by
 *       {@link Fxml2StyleClassReference#resolve()}.  The class name is obtained via
 *       {@link CssSelectorElement#getName()}.</li>
 *   <li><b>CSS plugin PSI element</b>: any element whose containing file has the
 *       {@code .css} extension.  The class name is extracted heuristically from
 *       {@link PsiNamedElement#getName()} or from the element's text (stripping the
 *       leading dot if present), without requiring a compile-time dependency on the
 *       CSS plugin's closed API.</li>
 * </ol>
 *
 * <p>For each candidate CSS class name, this searcher:
 * <ol>
 *   <li>Iterates all standalone FXML2 files ({@code .fxml} and {@code .fxml2}) via
 *       {@link FilenameIndex}.</li>
 *   <li>Iterates all embedded FXML2 fragments in {@code @ComponentView}-annotated
 *       classes via {@link Fxml2EmbeddedUtil#processAnnotatedClasses}.</li>
 *   <li>For each file, visits {@code styleClass} attributes and returns the
 *       {@link Fxml2StyleClassReference} tokens whose class name matches.</li>
 * </ol>
 */
public final class Fxml2StyleClassSearcher
        implements QueryExecutor<PsiReference, ReferencesSearch.SearchParameters> {

    /** Pattern matching a valid CSS identifier (class name without leading dot). */
    private static final Pattern CSS_IDENT = Pattern.compile("-?[_a-zA-Z][\\w-]*");

    @Override
    public boolean execute(
            @NotNull ReferencesSearch.SearchParameters params,
            @NotNull Processor<? super PsiReference> consumer) {

        String className = ReadAction.compute(() -> extractClassName(params.getElementToSearch()));
        if (className == null) return true;

        SearchScope scope = params.getEffectiveSearchScope();
        if (!(scope instanceof GlobalSearchScope globalScope)) return true;

        Project project = ReadAction.compute(() -> params.getElementToSearch().getProject());

        // Search standalone .fxml and .fxml2 files
        ReadAction.run(() -> {
            for (String ext : new String[]{"fxml", "fxml2"}) {
                for (VirtualFile vf : FilenameIndex.getAllFilesByExt(project, ext, globalScope)) {
                    PsiFile psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vf);
                    if (!(psiFile instanceof XmlFile xmlFile)) continue;
                    if (!Fxml2FileType.isFxml2(xmlFile)) continue;
                    if (!searchInXmlFile(xmlFile, className, consumer)) return;
                }
            }
        });

        // Search embedded FXML2 markup in @ComponentView-annotated classes.
        // Java text-block content is indexed as IN_PLAIN_TEXT; Kotlin raw strings as IN_STRINGS.
        // For hyphenated class names (e.g. "my-style1") the word tokenizer splits at hyphens,
        // so we use the first segment as the pre-filter word to narrow candidate files,
        // relying on the per-attribute walk to confirm the full name.
        String wordKey = className.contains("-") ? className.substring(0, className.indexOf('-')) : className;
        ReadAction.run(() ->
            Fxml2EmbeddedUtil.processAnnotatedClassesContainingWord(wordKey, project, globalScope, annotatedClass -> {
                XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(annotatedClass);
                if (xmlFile == null) return true;
                return searchInXmlFile(xmlFile, className, consumer);
            })
        );

        return true;
    }

    /**
     * Visits all {@code styleClass} attribute values in {@code xmlFile} and feeds each
     * {@link Fxml2StyleClassReference} whose class name equals {@code className} to
     * {@code consumer}.
     *
     * @return {@code false} if the consumer signalled early termination, {@code true} otherwise
     */
    private static boolean searchInXmlFile(
            @NotNull XmlFile xmlFile,
            @NotNull String className,
            @NotNull Processor<? super PsiReference> consumer) {

        boolean[] proceed = {true};
        xmlFile.accept(new XmlRecursiveElementVisitor() {
            @Override
            public void visitXmlAttribute(@NotNull XmlAttribute attr) {
                super.visitXmlAttribute(attr);
                if (!proceed[0]) return;
                if (!Fxml2CssUtil.isStyleClassAttribute(attr)) return;
                XmlAttributeValue attrVal = attr.getValueElement();
                if (attrVal == null) return;
                // Quick pre-filter: the raw value must contain the class name
                String raw = attrVal.getValue();
                if (!raw.contains(className)) return;

                for (PsiReference ref : attrVal.getReferences()) {
                    if (!(ref instanceof Fxml2StyleClassReference scr)) continue;
                    if (!className.equals(scr.getClassName())) continue;
                    if (!consumer.process(scr)) {
                        proceed[0] = false;
                        return;
                    }
                    // don't break: a single styleClass value may have multiple tokens
                    // with the same name (unusual but valid CSS)
                }
            }
        });
        return proceed[0];
    }

    /**
     * Extracts the CSS class name from the search target element.
     *
     * <p>Handles:
     * <ol>
     *   <li>{@link CssSelectorElement}: returns {@link CssSelectorElement#getName()}.</li>
     *   <li>CSS plugin PSI element (any element in a {@code .css} file): tries
     *       {@link PsiNamedElement#getName()} first, then falls back to stripping the
     *       leading dot from the element's text.</li>
     * </ol>
     *
     * @return the CSS class name (without leading dot), or {@code null} if not applicable
     */
    private static @Nullable String extractClassName(@NotNull PsiElement element) {
        // Case 1: our own CssSelectorElement
        if (element instanceof CssSelectorElement cse) {
            return cse.getName();
        }

        // Case 2: CSS plugin's PSI element: identified by containing .css file
        PsiFile file = element.getContainingFile();
        if (file == null) return null;
        VirtualFile vf = file.getVirtualFile();
        if (vf == null || !"css".equalsIgnoreCase(vf.getExtension())) return null;

        // Try PsiNamedElement.getName() first (works for CSS plugin class-selector PSI)
        if (element instanceof PsiNamedElement named) {
            String name = named.getName();
            if (name != null && !name.isBlank() && CSS_IDENT.matcher(name).matches()) {
                return name;
            }
        }

        // Fallback: extract from element text (may include leading dot)
        String text = element.getText().strip();
        if (text.startsWith(".")) text = text.substring(1);
        if (!text.isBlank() && CSS_IDENT.matcher(text).matches()) {
            return text;
        }

        // Last resort: scan parent element text for a CSS class selector
        PsiElement parent = element.getParent();
        if (parent != null) {
            java.util.regex.Matcher m =
                    Fxml2CssUtil.CLASS_SELECTOR_PATTERN.matcher(parent.getText());
            if (m.find()) return m.group(1);
        }

        return null;
    }
}
