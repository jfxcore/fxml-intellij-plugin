package org.jfxcore.fxml;

import com.intellij.lang.properties.IProperty;
import com.intellij.lang.properties.codeInspection.unused.UnusedPropertyInspection;
import com.intellij.lang.properties.psi.PropertiesFile;
import com.intellij.lang.properties.references.PropertyReferenceBase;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@code %key} attribute values in embedded FXML ({@code @ComponentView})
 * expose a {@link PropertyReferenceBase} so that:
 * <ul>
 *   <li>Ctrl+click navigates to the resource bundle entry.</li>
 *   <li>The property is not reported as "unused" by the properties plugin.</li>
 * </ul>
 *
 * <p>Standalone {@code .fxml} files are already handled by the bundled JavaFX plugin's
 * {@code FxmlResourceReferencesContributor}; these tests therefore focus exclusively on
 * the embedded case.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class Fxml2StaticResourcePropertyReferenceTest extends Fxml2TestBase {

    /** Add the {@code @ComponentView} annotation so the injector activates. */
    @BeforeAll
    void addMarkupAnnotation() {
        getFixture().addClass("""
                package org.jfxcore.markup;
                import java.lang.annotation.*;
                @Target(ElementType.TYPE)
                @Retention(RetentionPolicy.SOURCE)
                public @interface ComponentView {
                    String value();
                }
                """);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Builds a Java file containing a class annotated with {@code @ComponentView}. */
    private static String javaWithMarkup(String cls, String markupBody) {
        return "package test;\n"
                + "import org.jfxcore.markup.ComponentView;\n"
                + "import javafx.scene.control.*;\n"
                + "@ComponentView(\"\"\"\n"
                + markupBody
                + "\"\"\")\n"
                + "public class " + cls + " {}\n";
    }

    /**
     * Returns the first {@link XmlAttributeValue} in {@code xmlFile} whose raw attribute
     * value equals {@code "%" + key}.
     */
    private static XmlAttributeValue findAttrValByPercentKey(XmlFile xmlFile, String key) {
        String expected = "%" + key;
        for (XmlTag tag : PsiTreeUtil.findChildrenOfType(xmlFile, XmlTag.class)) {
            for (XmlAttribute attr : tag.getAttributes()) {
                if (expected.equals(attr.getValue())) {
                    return attr.getValueElement();
                }
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /**
     * {@code %button.text} in an embedded FXML Java file must expose at least one
     * {@link PropertyReferenceBase} on the attribute value: so that Ctrl+click
     * navigates to the matching entry in the properties file.
     */
    @Test
    void percentKeyInEmbeddedFxmlHasPropertyReference() {
        getFixture().addFileToProject("test/messages.properties", "button.text=Hello");
        getFixture().configureByText("TestView.java",
                javaWithMarkup("TestView",
                        "    <Button text=\"%button.text\"/>\n"));

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass cls = null;
            for (PsiClass c : PsiTreeUtil.findChildrenOfType(
                    getFixture().getFile(), PsiClass.class)) {
                if (c.hasAnnotation(Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN)) {
                    cls = c;
                    break;
                }
            }
            assertNotNull(cls, "No @ComponentView class found in TestView.java");

            XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(cls);
            assertNotNull(xmlFile, "No injected XmlFile found - injection may not have run");

            XmlAttributeValue attrVal = findAttrValByPercentKey(xmlFile, "button.text");
            assertNotNull(attrVal, "Could not find text='%button.text' in injected XML");

            boolean hasPropertyRef = false;
            for (PsiReference ref : attrVal.getReferences()) {
                if (ref instanceof PropertyReferenceBase) {
                    hasPropertyRef = true;
                    break;
                }
            }
            assertTrue(hasPropertyRef,
                    "Expected a PropertyReferenceBase for '%button.text' in embedded FXML; "
                    + "references found: " + java.util.Arrays.toString(attrVal.getReferences()));
        });
    }

    /**
     * The {@link PropertyReferenceBase} for {@code %greeting} in embedded FXML must
     * resolve to the {@code IProperty} in the matching properties file, enabling navigation.
     */
    @Test
    void percentKeyInEmbeddedFxmlResolvesToProperty() {
        getFixture().addFileToProject("test/labels.properties", "greeting=Hello, World!");
        getFixture().configureByText("TestView2.java",
                javaWithMarkup("TestView2",
                        "    <Button text=\"%greeting\"/>\n"));

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass cls = null;
            for (PsiClass c : PsiTreeUtil.findChildrenOfType(
                    getFixture().getFile(), PsiClass.class)) {
                if (c.hasAnnotation(Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN)) {
                    cls = c;
                    break;
                }
            }
            assertNotNull(cls);

            XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(cls);
            assertNotNull(xmlFile);

            XmlAttributeValue attrVal = findAttrValByPercentKey(xmlFile, "greeting");
            assertNotNull(attrVal, "Could not find text='%greeting' in injected XML");

            for (PsiReference ref : attrVal.getReferences()) {
                if (!(ref instanceof PropertyReferenceBase)) continue;
                PsiElement resolved = ref.resolve();
                assertNotNull(resolved,
                        "PropertyReference for 'greeting' must resolve to the IProperty in labels.properties; "
                        + "ensure the properties file is in the project scope");
                return; // resolved, test passed
            }
            fail("No PropertyReferenceBase found for '%greeting' in embedded FXML");
        });
    }

    /**
     * The range of the {@link PropertyReferenceBase} added for a {@code %key} value must
     * span exactly the key text (after the {@code %} prefix): so hover-highlighting and
     * Ctrl+click land on the right token.
     */
    @Test
    void percentKeyPropertyReferenceRangeCoversKeyText() {
        getFixture().addFileToProject("test/app.properties", "title=My App");
        getFixture().configureByText("TestView3.java",
                javaWithMarkup("TestView3",
                        "    <Button text=\"%title\"/>\n"));

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass cls = null;
            for (PsiClass c : PsiTreeUtil.findChildrenOfType(
                    getFixture().getFile(), PsiClass.class)) {
                if (c.hasAnnotation(Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN)) {
                    cls = c;
                    break;
                }
            }
            assertNotNull(cls);

            XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(cls);
            assertNotNull(xmlFile);

            XmlAttributeValue attrVal = findAttrValByPercentKey(xmlFile, "title");
            assertNotNull(attrVal, "Could not find text='%title' in injected XML");

            // attrVal.getText() includes surrounding quotes: "%title"
            // The key "title" sits at offset 2..7 (after '"' and '%')
            String attrText = attrVal.getText(); // "%title"
            int expectedStart = attrText.indexOf("title");
            assertTrue(expectedStart > 0,
                    "Expected 'title' inside attribute text '" + attrText + "'");

            for (PsiReference ref : attrVal.getReferences()) {
                if (!(ref instanceof PropertyReferenceBase)) continue;
                var range = ref.getRangeInElement();
                assertEquals(expectedStart, range.getStartOffset(),
                        "PropertyReference range must start at 'title' in: " + attrText);
                assertEquals(expectedStart + "title".length(), range.getEndOffset(),
                        "PropertyReference range must end after 'title' in: " + attrText);
                return;
            }
            fail("No PropertyReferenceBase found for '%title' in embedded FXML");
        });
    }

    /**
     * {@code %find.this} in an embedded FXML Java file must appear in the results of
     * {@link ReferencesSearch#search(PsiElement, SearchScope)} on the matching
     * {@link IProperty}: so that "Find Usages" on the property key in the
     * {@code .properties} file navigates to the embedded FXML use site.
     *
     * <p>This is the <em>reverse direction</em> counterpart of
     * {@link #percentKeyInEmbeddedFxmlResolvesToProperty}: instead of starting from the
     * XML attribute and resolving to the property, we start from the property and verify
     * that the search engine can find the XML attribute reference.
     */
    @Test
    void percentKeyInEmbeddedFxmlIsFoundByReferencesSearch() {
        getFixture().addFileToProject("test/ref.properties", "find.this=value");
        getFixture().configureByText("TestViewRef.java",
                javaWithMarkup("TestViewRef",
                        "    <Button text=\"%find.this\"/>\n"));

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            // Locate the IProperty for "find.this"
            PsiFile propFile = getFixture().findFileInTempDir("test/ref.properties") != null
                    ? getFixture().getPsiManager().findFile(
                            getFixture().findFileInTempDir("test/ref.properties"))
                    : null;
            assertNotNull(propFile, "test/ref.properties must exist in the fixture");
            assertInstanceOf(PropertiesFile.class, propFile,
                    "test/ref.properties must be a PropertiesFile");

            List<IProperty> properties = ((PropertiesFile) propFile).getProperties();
            IProperty prop = properties.stream()
                    .filter(p -> "find.this".equals(p.getKey()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(prop, "IProperty 'find.this' must exist in test/ref.properties");

            // Search for all references to the property in the project scope.
            GlobalSearchScope scope = GlobalSearchScope.projectScope(getFixture().getProject());
            Collection<PsiReference> refs = ReferencesSearch.search(
                    prop.getPsiElement(), scope, false).findAll();

            boolean foundInEmbeddedFxml = refs.stream().anyMatch(ref -> {
                PsiElement elem = ref.getElement();
                if (!(elem instanceof XmlAttributeValue attrVal)) return false;
                return attrVal.getValue().startsWith("%");
            });
            assertTrue(foundInEmbeddedFxml,
                    "ReferencesSearch must find the '%find.this' reference in embedded FXML; "
                    + "found references: " + refs);
        });
    }

    /**
     * A property that is referenced only via {@code %key} in embedded FXML must be
     * reported as <em>used</em> by {@link UnusedPropertyInspection#isPropertyUsed} -
     * i.e. the {@code "UnusedProperty"} inspection must not flag it as unused.
     *
     * <p>This tests the {@code ImplicitPropertyUsageProvider} path: even though the
     * Java word-index splits {@code "button.text"} at the dot and
     * {@code isCheapEnoughToSearch} returns {@code ZERO_OCCURRENCES}, the provider
     * must intercept the check and return {@code true}.
     */
    @Test
    void percentKeyInEmbeddedFxmlNotMarkedAsUnused() {
        getFixture().addFileToProject("test/unused.properties", "live.key=Used");
        getFixture().configureByText("TestViewUnused.java",
                javaWithMarkup("TestViewUnused",
                        "    <Button text=\"%live.key\"/>\n"));

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiFile propFile = getFixture().findFileInTempDir("test/unused.properties") != null
                    ? getFixture().getPsiManager().findFile(
                            getFixture().findFileInTempDir("test/unused.properties"))
                    : null;
            assertNotNull(propFile, "test/unused.properties must exist in the fixture");
            assertInstanceOf(PropertiesFile.class, propFile,
                    "test/unused.properties must be a PropertiesFile");

            List<IProperty> properties = ((PropertiesFile) propFile).getProperties();
            IProperty prop = properties.stream()
                    .filter(p -> "live.key".equals(p.getKey()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(prop, "IProperty 'live.key' must exist in test/unused.properties");

            // Verify that the UnusedPropertyInspection considers the property used.
            var module = ModuleUtilCore.findModuleForPsiElement(prop.getPsiElement());
            assertNotNull(module, "property must belong to a module");
            var helper = new UnusedPropertyInspection.UnusedPropertiesSearchHelper(module, propFile);

            assertTrue(
                    UnusedPropertyInspection.isPropertyUsed(
                            (com.intellij.lang.properties.psi.Property) prop, helper, true),
                    "UnusedPropertyInspection must not flag 'live.key' as unused - "
                    + "it is referenced via %live.key in embedded FXML");
        });
    }

    /**
     * When the {@code %} prefix is overridden via {@code <?prefix % = CustomExtension?>} to a class
     * that satisfies the resource-key extension convention (class name contains {@code "Resource"}
     * and the class carries {@code @DefaultProperty("key")}), {@code %key} must still expose a
     * {@link PropertyReferenceBase}: the detection must be convention-based, not hard-coded to the
     * built-in {@code StaticResource} FQN.
     */
    @Test
    void percentKeyInEmbeddedFxmlHasPropertyReferenceWithCustomResourceExtension() {
        getFixture().addClass("""
                package test;
                import javafx.beans.DefaultProperty;
                import javafx.beans.NamedArg;
                @DefaultProperty("key")
                public final class MyResourceExtension {
                    public MyResourceExtension(@NamedArg("key") String key) {}
                    public String get() { return key; }
                    private final String key;
                }
                """);
        getFixture().addFileToProject("test/custom.properties", "my.label=Custom");
        getFixture().configureByText("CustomExtView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.control.*;
                @ComponentView(""\"
                    <?prefix % = MyResourceExtension?>
                    <Label text="%my.label"/>
                ""\")
                public class CustomExtView {}
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass cls = null;
            for (PsiClass c : PsiTreeUtil.findChildrenOfType(
                    getFixture().getFile(), PsiClass.class)) {
                if (c.hasAnnotation(Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN)) {
                    cls = c;
                    break;
                }
            }
            assertNotNull(cls, "No @ComponentView class found in CustomExtView.java");

            XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(cls);
            assertNotNull(xmlFile, "No injected XmlFile found");

            XmlAttributeValue attrVal = findAttrValByPercentKey(xmlFile, "my.label");
            assertNotNull(attrVal, "Could not find text='%my.label' in injected XML");

            boolean hasPropertyRef = false;
            for (PsiReference ref : attrVal.getReferences()) {
                if (ref instanceof PropertyReferenceBase) {
                    hasPropertyRef = true;
                    break;
                }
            }
            assertTrue(hasPropertyRef,
                    "Expected a PropertyReferenceBase for '%my.label' when % is overridden to "
                    + "MyResourceExtension (satisfies resource-key convention); "
                    + "references found: " + java.util.Arrays.toString(attrVal.getReferences()));
        });
    }

    /**
     * When the {@code %} prefix is overridden to a class that does NOT satisfy the
     * resource-key extension convention (class name does not contain {@code "Resource"}),
     * {@code %key} must not receive a {@link PropertyReferenceBase}: the value is not a
     * resource key reference.
     */
    @Test
    void percentKeyInEmbeddedFxmlHasNoPropertyReferenceWithNonResourceExtension() {
        getFixture().addClass("""
                package test;
                import javafx.beans.NamedArg;
                public final class MyFormattingExtension {
                    public MyFormattingExtension(@NamedArg("value") String value) {}
                    public String get() { return value; }
                    private final String value;
                }
                """);
        getFixture().addFileToProject("test/nonres.properties", "some.key=Value");
        getFixture().configureByText("NonResExtView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.control.*;
                @ComponentView(""\"
                    <?prefix % = MyFormattingExtension?>
                    <Label text="%some.key"/>
                ""\")
                public class NonResExtView {}
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass cls = null;
            for (PsiClass c : PsiTreeUtil.findChildrenOfType(
                    getFixture().getFile(), PsiClass.class)) {
                if (c.hasAnnotation(Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN)) {
                    cls = c;
                    break;
                }
            }
            assertNotNull(cls, "No @ComponentView class found in NonResExtView.java");

            XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(cls);
            assertNotNull(xmlFile, "No injected XmlFile found");

            XmlAttributeValue attrVal = findAttrValByPercentKey(xmlFile, "some.key");
            assertNotNull(attrVal, "Could not find text='%some.key' in injected XML");

            long propertyRefCount = java.util.Arrays.stream(attrVal.getReferences())
                    .filter(r -> r instanceof PropertyReferenceBase)
                    .count();
            assertEquals(0, propertyRefCount,
                    "Expected no PropertyReferenceBase for '%some.key' when % is overridden to "
                    + "MyFormattingExtension (does not satisfy resource-key convention); "
                    + "references found: " + java.util.Arrays.toString(attrVal.getReferences()));
        });
    }

    /**
     * A {@code %key} value in a standalone {@code .fxml} FXML file must NOT receive
     * a second {@link PropertyReferenceBase} from the fxml2 plugin: the bundled JavaFX
     * plugin's {@code FxmlResourceReferencesContributor} already handles those files.
     * The fxml2 plugin must not produce duplicates.
     */
    @Test
    void percentKeyInStandaloneFxmlDoesNotProduceDuplicatePropertyReference() {
        getFixture().addFileToProject("test/standalone.properties", "label.text=Standalone");

        // Add a minimal code-behind so the .fxml file is valid FXML.
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class StandaloneView extends BorderPane {}
                """);

        getFixture().configureByText("StandaloneView.fxml", fxml2(
                "javafx.scene.control.Label",
                "  <Label text=\"%label.text\"/>\n",
                "test.StandaloneView"
        ));

        ReadAction.run(() -> {
            XmlAttributeValue attrVal = null;
            for (XmlTag tag : PsiTreeUtil.findChildrenOfType(
                    getFixture().getFile(), XmlTag.class)) {
                XmlAttribute attr = tag.getAttribute("text");
                if (attr != null && "%label.text".equals(attr.getValue())) {
                    attrVal = attr.getValueElement();
                    break;
                }
            }
            assertNotNull(attrVal, "Could not find text='%label.text' in StandaloneView.fxml");

            // Count PropertyReferenceBase instances on the attribute.
            // The fxml2 plugin adds zero for standalone .fxml files; the JavaFX plugin may add one.
            // Either way the total must be <= 1 (no duplicates from the fxml2 plugin).
            long count = java.util.Arrays.stream(attrVal.getReferences())
                    .filter(r -> r instanceof PropertyReferenceBase)
                    .count();
            assertTrue(count <= 1,
                    "fxml2 plugin must not produce a duplicate PropertyReference for standalone .fxml; "
                    + "found " + count + " PropertyReferenceBase instance(s)");
        });
    }
}
