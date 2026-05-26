package org.jfxcore.fxml.lang;

import com.intellij.lang.properties.codeInspection.unused.ImplicitPropertyUsageProvider;
import com.intellij.lang.properties.psi.Property;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;

/**
 * Suppresses false-positive "unused property" warnings for {@code .properties} file entries
 * that are referenced only via {@code %key} shorthand or {@code {DynamicResource key}} /
 * {@code {StaticResource key}} long form in embedded FXML markup
 * ({@code @ComponentView} annotations).
 *
 * <h3>Why is this needed?</h3>
 * <p>IntelliJ's {@code UnusedPropertyInspection} first calls
 * {@link com.intellij.psi.search.PsiSearchHelper#isCheapEnoughToSearch isCheapEnoughToSearch}
 * with the property key name. Because the Java word-index tokenises dotted names like
 * {@code "button.text"} into separate words ({@code "button"}, {@code "text"}), the key
 * is never found as a <em>whole word</em> in the index of any Java source file.
 * Consequently, {@code isCheapEnoughToSearch} returns {@code ZERO_OCCURRENCES} and the
 * inspection immediately marks the property as unused, without ever calling
 * {@link com.intellij.psi.search.searches.ReferencesSearch ReferencesSearch}.
 *
 * <p>This provider intercepts the {@link #isUsed isUsed} check that runs <em>before</em>
 * the word-index short-circuit. It searches all {@code @ComponentView}-annotated classes
 * in the project for injected FXML markup that contains a resource key reference. If at
 * least one such use site is found the property is reported as used, bypassing the broken
 * word-index path.
 */
public final class Fxml2EmbeddedPropertyUsageProvider implements ImplicitPropertyUsageProvider {

    @Override
    public boolean isUsed(@NotNull Property property) {
        String key = property.getKey();
        if (key == null || key.isEmpty()) return false;

        Project project = property.getProject();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

        boolean[] found = {false};
        Fxml2EmbeddedUtil.processAnnotatedClasses(project, scope, annotatedClass -> {
            if (found[0]) return false;

            XmlFile xmlFile = ReadAction.compute(() -> Fxml2EmbeddedUtil.getInjectedXmlFile(annotatedClass));
            if (xmlFile == null) return true;

            ReadAction.run(() -> xmlFile.accept(new com.intellij.psi.XmlRecursiveElementVisitor() {
                @Override
                public void visitXmlAttributeValue(@NotNull XmlAttributeValue attrValue) {
                    if (found[0]) return;
                    String value = attrValue.getValue();
                    if (isResourceKeyReference(value, key)) {
                        found[0] = true;
                    }
                }
            }));
            return true;
        });

        return found[0];
    }

    /**
     * Returns {@code true} when {@code attrValue} is a resource key reference to {@code key}.
     *
     * <p>Recognized forms:
     * <ul>
     *   <li>{@code %key}: prefix shorthand for whatever extension is mapped to {@code %}
     *       (typically {@code StaticResource})</li>
     *   <li>{@code {ClassName key[; namedArgs]}}: long form where {@code ClassName} satisfies
     *       the resource-key extension naming convention (simple name contains {@code "Resource"}),
     *       as defined by {@link Fxml2MarkupExtensionUtil#simpleNameSuggestsResourceKeyExtension}</li>
     *   <li>Optional namespace prefix before the class name, e.g. {@code {fx:DynamicResource key}}</li>
     * </ul>
     *
     * <p>The class-name check here is a name-only approximation because PSI resolution is not
     * available in this scanning context. The canonical check (which also verifies
     * {@code @DefaultProperty("key")}) is {@link Fxml2MarkupExtensionUtil#isResourceKeyExtension}.
     */
    static boolean isResourceKeyReference(@NotNull String attrValue, @NotNull String key) {
        // %key: prefix shorthand; the mapped extension is whatever the <?prefix?> PI declares.
        if (attrValue.equals("%" + key)) return true;

        // {[prefix:]ClassName key[; namedArgs]} long form
        if (!attrValue.startsWith("{") || !attrValue.endsWith("}")) return false;
        String inner = attrValue.substring(1, attrValue.length() - 1).stripLeading();

        // Strip optional namespace prefix (e.g. "fx:")
        int colon = inner.indexOf(':');
        int firstSpace = inner.indexOf(' ');
        if (colon >= 0 && (firstSpace < 0 || colon < firstSpace)) {
            inner = inner.substring(colon + 1).stripLeading();
        }

        // Extract the simple class name (the token before the first whitespace).
        int nameEnd = 0;
        while (nameEnd < inner.length() && !Character.isWhitespace(inner.charAt(nameEnd))) {
            nameEnd++;
        }
        if (nameEnd == 0 || nameEnd >= inner.length()) return false;
        String className = inner.substring(0, nameEnd);

        // Strip generic type arguments from the class name (e.g. "StaticResource<String>" -> "StaticResource").
        int angleBracket = className.indexOf('<');
        if (angleBracket >= 0) {
            className = className.substring(0, angleBracket);
        }

        if (!Fxml2MarkupExtensionUtil.simpleNameSuggestsResourceKeyExtension(className)) return false;

        // Confirm the positional default argument matches the key.
        String rest = inner.substring(nameEnd).stripLeading();
        int semi = rest.indexOf(';');
        String argKey = (semi >= 0 ? rest.substring(0, semi) : rest).strip();
        return key.equals(argKey);
    }
}
