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

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@code {DynamicResource key}} (and the long form {@code {StaticResource key}})
 * attribute values expose a {@link PropertyReferenceBase} so that:
 * <ul>
 *   <li>Ctrl+click navigates to the resource bundle entry.</li>
 *   <li>The property is not reported as "unused" by the properties plugin.</li>
 * </ul>
 *
 * <p>This verifies that {@code DynamicResource} is supported equally well as the
 * {@code %key} (StaticResource prefix shorthand) syntax in both standalone and embedded FXML.
 * The bundled JavaFX plugin only handles {@code %key} in standalone {@code .fxml} files;
 * for all other forms and file types the FXML/2 plugin must provide the references.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class Fxml2DynamicResourcePropertyReferenceTest extends Fxml2TestBase {

    @BeforeAll
    void addCommonMocks() {
        // @ComponentView annotation for embedded FXML tests
        getFixture().addClass("""
                package org.jfxcore.markup;
                import java.lang.annotation.*;
                @Target(ElementType.TYPE)
                @Retention(RetentionPolicy.SOURCE)
                public @interface ComponentView {
                    String value();
                }
                """);

        // DynamicResource mock
        getFixture().addClass("""
                package org.jfxcore.markup.resource;
                import javafx.beans.DefaultProperty;
                import javafx.beans.NamedArg;
                @DefaultProperty("key")
                public final class DynamicResource<T> {
                    public DynamicResource(@NamedArg("key") String key) {}
                    public DynamicResource(@NamedArg("key") String key,
                                         @NamedArg("formatArguments") Object... formatArguments) {}
                }
                """);

        // StaticResource mock (for long-form parity tests)
        getFixture().addClass("""
                package org.jfxcore.markup.resource;
                import javafx.beans.DefaultProperty;
                import javafx.beans.NamedArg;
                @DefaultProperty("key")
                public final class StaticResource<T> {
                    public StaticResource(@NamedArg("key") String key) {}
                    public StaticResource(@NamedArg("key") String key,
                                         @NamedArg("formatArguments") Object... formatArguments) {}
                }
                """);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Builds a Java file with an embedded FXML {@code @ComponentView}. */
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
     * Finds the first {@link XmlAttributeValue} in {@code xmlFile} whose raw attribute
     * value starts with the given expression prefix (e.g. {@code "{DynamicResource"}).
     */
    private static XmlAttributeValue findAttrValByValuePrefix(XmlFile xmlFile, String prefix) {
        for (XmlTag tag : PsiTreeUtil.findChildrenOfType(xmlFile, XmlTag.class)) {
            for (XmlAttribute attr : tag.getAttributes()) {
                String val = attr.getValue();
                if (val != null && val.startsWith(prefix)) {
                    return attr.getValueElement();
                }
            }
        }
        return null;
    }

    /** Returns the injected {@link XmlFile} for the first {@code @ComponentView} class in the fixture. */
    private XmlFile injectedXmlFile() {
        PsiClass cls = null;
        for (PsiClass c : PsiTreeUtil.findChildrenOfType(getFixture().getFile(), PsiClass.class)) {
            if (c.hasAnnotation(Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN)) {
                cls = c;
                break;
            }
        }
        assertNotNull(cls, "No @ComponentView class found in the fixture file");
        XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(cls);
        assertNotNull(xmlFile, "No injected XmlFile found - injection may not have run");
        return xmlFile;
    }

    // -----------------------------------------------------------------------
    // DynamicResource in embedded FXML
    // -----------------------------------------------------------------------

    /**
     * {@code {DynamicResource greeting}} in an embedded FXML Java file must expose at least
     * one {@link PropertyReferenceBase}: enabling Ctrl+click navigation to the resource bundle.
     */
    @Test
    void dynamicResourceInEmbeddedFxmlHasPropertyReference() {
        getFixture().addFileToProject("test/messages.properties", "greeting=Hello");
        getFixture().configureByText("TestView.java",
                javaWithMarkup("TestView",
                        "    <Button text=\"{DynamicResource greeting}\"/>\n"));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = injectedXmlFile();
            XmlAttributeValue attrVal = findAttrValByValuePrefix(xmlFile, "{DynamicResource");
            assertNotNull(attrVal, "Could not find text='{DynamicResource greeting}' in injected XML");

            boolean hasPropertyRef = Arrays.stream(attrVal.getReferences())
                    .anyMatch(r -> r instanceof PropertyReferenceBase);
            assertTrue(hasPropertyRef,
                    "Expected a PropertyReferenceBase for '{DynamicResource greeting}' in embedded FXML; "
                    + "references found: " + Arrays.toString(attrVal.getReferences()));
        });
    }

    /**
     * The {@link PropertyReferenceBase} for {@code {DynamicResource greeting}} must
     * resolve to the matching property in the {@code .properties} file.
     */
    @Test
    void dynamicResourceInEmbeddedFxmlResolvesToProperty() {
        getFixture().addFileToProject("test/labels.properties", "welcome=Welcome!");
        getFixture().configureByText("TestView2.java",
                javaWithMarkup("TestView2",
                        "    <Button text=\"{DynamicResource welcome}\"/>\n"));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = injectedXmlFile();
            XmlAttributeValue attrVal = findAttrValByValuePrefix(xmlFile, "{DynamicResource");
            assertNotNull(attrVal, "Could not find '{DynamicResource welcome}' in injected XML");

            for (PsiReference ref : attrVal.getReferences()) {
                if (!(ref instanceof PropertyReferenceBase)) continue;
                PsiElement resolved = ref.resolve();
                assertNotNull(resolved,
                        "PropertyReference for 'welcome' must resolve to the IProperty in labels.properties");
                return;
            }
            fail("No PropertyReferenceBase resolved for '{DynamicResource welcome}' in embedded FXML");
        });
    }

    /**
     * The range of the {@link PropertyReferenceBase} must span exactly the key text -
     * NOT the braces, the class name, or any params.
     */
    @Test
    void dynamicResourcePropertyReferenceRangeCoversKeyText() {
        getFixture().addFileToProject("test/app.properties", "title=My App");
        getFixture().configureByText("TestView3.java",
                javaWithMarkup("TestView3",
                        "    <Button text=\"{DynamicResource title}\"/>\n"));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = injectedXmlFile();
            XmlAttributeValue attrVal = findAttrValByValuePrefix(xmlFile, "{DynamicResource");
            assertNotNull(attrVal);

            // attrVal.getText() = "{DynamicResource title}"
            // "title" starts somewhere after "{DynamicResource "
            String attrText = attrVal.getText();
            int expectedStart = attrText.indexOf("title");
            assertTrue(expectedStart > 0, "Expected 'title' inside '" + attrText + "'");

            for (PsiReference ref : attrVal.getReferences()) {
                if (!(ref instanceof PropertyReferenceBase)) continue;
                var range = ref.getRangeInElement();
                assertEquals(expectedStart, range.getStartOffset(),
                        "PropertyReference range must start at 'title' in: " + attrText);
                assertEquals(expectedStart + "title".length(), range.getEndOffset(),
                        "PropertyReference range must end after 'title' in: " + attrText);
                return;
            }
            fail("No PropertyReferenceBase found for '{DynamicResource title}' in embedded FXML");
        });
    }

    /**
     * {@code {DynamicResource greeting; formatArguments=Jane, Doe}} must still expose a
     * {@link PropertyReferenceBase} for the key {@code greeting} even when {@code formatArguments}
     * are present.
     */
    @Test
    void dynamicResourceWithFormatArgumentsHasPropertyReference() {
        getFixture().addFileToProject("test/app.properties", "greetingFmt=Hello {0}!");
        getFixture().configureByText("TestView4.java",
                javaWithMarkup("TestView4",
                        "    <Button text=\"{DynamicResource greetingFmt; formatArguments=Jane, Doe}\"/>\n"));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = injectedXmlFile();
            XmlAttributeValue attrVal = findAttrValByValuePrefix(xmlFile, "{DynamicResource");
            assertNotNull(attrVal, "Could not find DynamicResource with formatArguments in injected XML");

            boolean hasPropertyRef = Arrays.stream(attrVal.getReferences())
                    .anyMatch(r -> r instanceof PropertyReferenceBase);
            assertTrue(hasPropertyRef,
                    "Expected a PropertyReferenceBase for '{DynamicResource greetingFmt; ...}'; "
                    + "references: " + Arrays.toString(attrVal.getReferences()));
        });
    }

    /**
     * {@code {DynamicResource button.text}}: dotted key with a dot in the name must produce
     * a {@link PropertyReferenceBase} for the full key {@code button.text}, not just {@code button}.
     */
    @Test
    void dynamicResourceWithDottedKeyHasPropertyReference() {
        getFixture().addFileToProject("test/app.properties", "button.text=Click me");
        getFixture().configureByText("TestView5.java",
                javaWithMarkup("TestView5",
                        "    <Button text=\"{DynamicResource button.text}\"/>\n"));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = injectedXmlFile();
            XmlAttributeValue attrVal = findAttrValByValuePrefix(xmlFile, "{DynamicResource");
            assertNotNull(attrVal, "Could not find '{DynamicResource button.text}' in injected XML");

            for (PsiReference ref : attrVal.getReferences()) {
                if (!(ref instanceof PropertyReferenceBase propRef)) continue;
                // The canonical text of the PropertyReference must be the full "button.text"
                assertEquals("button.text", propRef.getCanonicalText(),
                        "PropertyReference canonical text must be the full 'button.text' key");
                return;
            }
            fail("No PropertyReferenceBase found for '{DynamicResource button.text}' in embedded FXML");
        });
    }

    // -----------------------------------------------------------------------
    // DynamicResource in standalone FXML
    // -----------------------------------------------------------------------

    /**
     * {@code {DynamicResource greeting}} in a standalone {@code .fxml} FXML file must
     * expose a {@link PropertyReferenceBase}.
     *
     * <p>The bundled JavaFX plugin only handles {@code %key} in {@code .fxml} files;
     * it does NOT handle the long-form {@code {DynamicResource key}}.
     * The FXML/2 plugin must therefore add the {@link PropertyReferenceBase} here.
     */
    @Test
    void dynamicResourceInStandaloneFxmlHasPropertyReference() {
        getFixture().addFileToProject("test/standalone.properties", "label.text=Standalone");
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class DynView extends BorderPane {}
                """);

        getFixture().configureByText("DynView.fxml", fxml(
                "javafx.scene.control.Label\norg.jfxcore.markup.resource.DynamicResource",
                "  <Label text=\"{DynamicResource label.text}\"/>\n",
                "test.DynView"
        ));

        ReadAction.run(() -> {
            XmlAttributeValue attrVal = null;
            for (XmlTag tag : PsiTreeUtil.findChildrenOfType(getFixture().getFile(), XmlTag.class)) {
                XmlAttribute attr = tag.getAttribute("text");
                if (attr != null && attr.getValue() != null
                        && attr.getValue().startsWith("{DynamicResource")) {
                    attrVal = attr.getValueElement();
                    break;
                }
            }
            assertNotNull(attrVal, "Could not find '{DynamicResource label.text}' in DynView.fxml");

            boolean hasPropertyRef = Arrays.stream(attrVal.getReferences())
                    .anyMatch(r -> r instanceof PropertyReferenceBase);
            assertTrue(hasPropertyRef,
                    "Expected a PropertyReferenceBase for '{DynamicResource label.text}' in standalone .fxml; "
                    + "references: " + Arrays.toString(attrVal.getReferences()));
        });
    }

    // -----------------------------------------------------------------------
    // DynamicResource in standalone .fxmlx
    // -----------------------------------------------------------------------

    /**
     * {@code {DynamicResource greeting}} in a standalone {@code .fxmlx} file must
     * expose a {@link PropertyReferenceBase}.
     */
    @Test
    void dynamicResourceInStandaloneFxml2HasPropertyReference() {
        getFixture().addFileToProject("test/app2.properties", "header=My App");
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class DynView2 extends BorderPane {}
                """);

        getFixture().configureByText("DynView2.fxmlx", fxml(
                "javafx.scene.control.Label\norg.jfxcore.markup.resource.DynamicResource",
                "  <Label text=\"{DynamicResource header}\"/>\n",
                "test.DynView2"
        ));

        ReadAction.run(() -> {
            XmlAttributeValue attrVal = null;
            for (XmlTag tag : PsiTreeUtil.findChildrenOfType(getFixture().getFile(), XmlTag.class)) {
                XmlAttribute attr = tag.getAttribute("text");
                if (attr != null && attr.getValue() != null
                        && attr.getValue().startsWith("{DynamicResource")) {
                    attrVal = attr.getValueElement();
                    break;
                }
            }
            assertNotNull(attrVal, "Could not find '{DynamicResource header}' in DynView2.fxmlx");

            boolean hasPropertyRef = Arrays.stream(attrVal.getReferences())
                    .anyMatch(r -> r instanceof PropertyReferenceBase);
            assertTrue(hasPropertyRef,
                    "Expected a PropertyReferenceBase for '{DynamicResource header}' in standalone .fxmlx; "
                    + "references: " + Arrays.toString(attrVal.getReferences()));
        });
    }

    // -----------------------------------------------------------------------
    // StaticResource long form parity
    // -----------------------------------------------------------------------

    /**
     * {@code {StaticResource greeting}} (long form) in embedded FXML must also expose a
     * {@link PropertyReferenceBase}: the same as the {@code %greeting} shorthand.
     *
     * <p>This verifies symmetry between the short form and the long form of
     * {@code StaticResource}.
     */
    @Test
    void staticResourceLongFormInEmbeddedFxmlHasPropertyReference() {
        getFixture().addFileToProject("test/labels2.properties", "name=Alice");
        getFixture().configureByText("TestView6.java",
                javaWithMarkup("TestView6",
                        "    <Button text=\"{StaticResource name}\"/>\n"));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = injectedXmlFile();
            XmlAttributeValue attrVal = findAttrValByValuePrefix(xmlFile, "{StaticResource");
            assertNotNull(attrVal, "Could not find '{StaticResource name}' in injected XML");

            boolean hasPropertyRef = Arrays.stream(attrVal.getReferences())
                    .anyMatch(r -> r instanceof PropertyReferenceBase);
            assertTrue(hasPropertyRef,
                    "Expected a PropertyReferenceBase for '{StaticResource name}' in embedded FXML "
                    + "(long form parity with %name shorthand); "
                    + "references: " + Arrays.toString(attrVal.getReferences()));
        });
    }

    /**
     * {@code {StaticResource greeting}} (long form) in a standalone {@code .fxml} FXML file
     * must expose a {@link PropertyReferenceBase}.
     *
     * <p>The bundled JavaFX plugin handles {@code %greeting} via
     * {@code FxmlResourceReferencesContributor} but does NOT handle the long form.
     */
    @Test
    void staticResourceLongFormInStandaloneFxmlHasPropertyReference() {
        getFixture().addFileToProject("test/long.properties", "caption=Caption");
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class StaticLongView extends BorderPane {}
                """);

        getFixture().configureByText("StaticLongView.fxml", fxml(
                "javafx.scene.control.Label\norg.jfxcore.markup.resource.StaticResource",
                "  <Label text=\"{StaticResource caption}\"/>\n",
                "test.StaticLongView"
        ));

        ReadAction.run(() -> {
            XmlAttributeValue attrVal = null;
            for (XmlTag tag : PsiTreeUtil.findChildrenOfType(getFixture().getFile(), XmlTag.class)) {
                XmlAttribute attr = tag.getAttribute("text");
                if (attr != null && attr.getValue() != null
                        && attr.getValue().startsWith("{StaticResource")) {
                    attrVal = attr.getValueElement();
                    break;
                }
            }
            assertNotNull(attrVal, "Could not find '{StaticResource caption}' in StaticLongView.fxml");

            boolean hasPropertyRef = Arrays.stream(attrVal.getReferences())
                    .anyMatch(r -> r instanceof PropertyReferenceBase);
            assertTrue(hasPropertyRef,
                    "Expected a PropertyReferenceBase for '{StaticResource caption}' in standalone .fxml "
                    + "(long form not handled by bundled JavaFX plugin); "
                    + "references: " + Arrays.toString(attrVal.getReferences()));
        });
    }

    // -----------------------------------------------------------------------
    // Unused property detection and Find Usages for DynamicResource
    // -----------------------------------------------------------------------

    /**
     * {@code {DynamicResource find.this}} in an embedded FXML Java file must appear in the
     * results of {@link ReferencesSearch#search(PsiElement, SearchScope)} on the matching
     * {@link IProperty}: so that "Find Usages" on the property key in the
     * {@code .properties} file navigates to the embedded FXML use site.
     */
    @Test
    void dynamicResourceInEmbeddedFxmlIsFoundByReferencesSearch() {
        getFixture().addFileToProject("test/dynref.properties", "find.this=value");
        getFixture().configureByText("TestViewDynRef.java",
                javaWithMarkup("TestViewDynRef",
                        "    <Button text=\"{DynamicResource find.this}\"/>\n"));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiFile propFile = getFixture().findFileInTempDir("test/dynref.properties") != null
                    ? getFixture().getPsiManager().findFile(
                            getFixture().findFileInTempDir("test/dynref.properties"))
                    : null;
            assertNotNull(propFile, "test/dynref.properties must exist in the fixture");
            assertInstanceOf(PropertiesFile.class, propFile,
                    "test/dynref.properties must be a PropertiesFile");

            List<IProperty> properties = ((PropertiesFile) propFile).getProperties();
            IProperty prop = properties.stream()
                    .filter(p -> "find.this".equals(p.getKey()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(prop, "IProperty 'find.this' must exist in test/dynref.properties");

            GlobalSearchScope scope = GlobalSearchScope.projectScope(getFixture().getProject());
            Collection<PsiReference> refs = ReferencesSearch.search(
                    prop.getPsiElement(), scope, false).findAll();

            boolean foundInEmbeddedFxml = refs.stream().anyMatch(ref -> {
                PsiElement elem = ref.getElement();
                if (!(elem instanceof XmlAttributeValue attrVal)) return false;
                String val = attrVal.getValue();
                return val.startsWith("{DynamicResource");
            });
            assertTrue(foundInEmbeddedFxml,
                    "ReferencesSearch must find the '{DynamicResource find.this}' reference in embedded FXML; "
                    + "found references: " + refs);
        });
    }

    /**
     * A property that is referenced only via {@code {DynamicResource key}} in embedded FXML
     * must be reported as <em>used</em> by {@link UnusedPropertyInspection#isPropertyUsed} -
     * i.e. the {@code "UnusedProperty"} inspection must not flag it as unused.
     */
    @Test
    void dynamicResourceInEmbeddedFxmlNotMarkedAsUnused() {
        getFixture().addFileToProject("test/dynunused.properties", "live.key=Used");
        getFixture().configureByText("TestViewDynUnused.java",
                javaWithMarkup("TestViewDynUnused",
                        "    <Button text=\"{DynamicResource live.key}\"/>\n"));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiFile propFile = getFixture().findFileInTempDir("test/dynunused.properties") != null
                    ? getFixture().getPsiManager().findFile(
                            getFixture().findFileInTempDir("test/dynunused.properties"))
                    : null;
            assertNotNull(propFile, "test/dynunused.properties must exist in the fixture");
            assertInstanceOf(PropertiesFile.class, propFile,
                    "test/dynunused.properties must be a PropertiesFile");

            List<IProperty> properties = ((PropertiesFile) propFile).getProperties();
            IProperty prop = properties.stream()
                    .filter(p -> "live.key".equals(p.getKey()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(prop, "IProperty 'live.key' must exist in test/dynunused.properties");

            var module = ModuleUtilCore.findModuleForPsiElement(prop.getPsiElement());
            assertNotNull(module, "property must belong to a module");
            var helper = new UnusedPropertyInspection.UnusedPropertiesSearchHelper(module, propFile);

            assertTrue(
                    UnusedPropertyInspection.isPropertyUsed(
                            (com.intellij.lang.properties.psi.Property) prop, helper, true),
                    "UnusedPropertyInspection must not flag 'live.key' as unused - "
                    + "it is referenced via {DynamicResource live.key} in embedded FXML");
        });
    }
}
