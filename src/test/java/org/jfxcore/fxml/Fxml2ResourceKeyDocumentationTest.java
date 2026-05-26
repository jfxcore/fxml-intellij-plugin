package org.jfxcore.fxml;

import com.intellij.lang.properties.IProperty;
import com.intellij.lang.properties.PropertiesDocumentationProvider;
import com.intellij.openapi.application.ReadAction;
import com.intellij.platform.backend.documentation.DocumentationTarget;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jfxcore.fxml.lang.Fxml2ResourceKeyDocumentationTargetProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link Fxml2ResourceKeyDocumentationTargetProvider} redirects documentation to the
 * matching {@link IProperty} so that hovering over a resource-key reference in FXML markup
 * shows the same tooltip as hovering over the key in the {@code .properties} file itself.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code %key} in embedded FXML ({@code @ComponentView} text block in a Java file)</li>
 *   <li>{@code %key} in a standalone {@code .fxmlx} file</li>
 *   <li>{@code {DynamicResource key}} in embedded FXML</li>
 *   <li>{@code {DynamicResource key}} in a standalone {@code .fxmlx} file</li>
 *   <li>{@code {StaticResource key}} (long form) in embedded FXML</li>
 *   <li>{@code generateDoc()} output includes the property value and file name</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class Fxml2ResourceKeyDocumentationTest extends Fxml2TestBase {

    @BeforeAll
    void addCommonMocks() {
        getFixture().addClass("""
                package org.jfxcore.markup;
                import java.lang.annotation.*;
                @Target(ElementType.TYPE)
                @Retention(RetentionPolicy.SOURCE)
                public @interface ComponentView {
                    String value();
                }
                """);

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

    /** Builds a Java @ComponentView text-block source file. */
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
     * Resolves the property key at the first occurrence of {@code keyToFind} in {@code file}.
     *
     * @return the resolved {@link IProperty}, or {@code null}
     */
    private IProperty resolvedPropertyAt(PsiFile file, String keyToFind) {
        int offset = file.getText().indexOf(keyToFind);
        assertTrue(offset >= 0, "Key '" + keyToFind + "' not found in: " + file.getText());
        return Fxml2ResourceKeyDocumentationTargetProvider.resolvePropertyAt(file, offset);
    }

    // -----------------------------------------------------------------------
    // Embedded FXML (%key shorthand)
    // -----------------------------------------------------------------------

    /**
     * {@code %greeting} in an embedded FXML text block must redirect documentation to the
     * {@link IProperty} for {@code greeting}.
     */
    @Test
    void staticResourceShortformInEmbeddedFxmlRedirectsToProperty() {
        getFixture().addFileToProject("test/doc.properties", "greeting=Hello, World!");
        getFixture().configureByText("DocView.java",
                javaWithMarkup("DocView", "    <Button text=\"%greeting\"/>\n"));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            IProperty prop = resolvedPropertyAt(getFixture().getFile(), "greeting");
            assertInstanceOf(IProperty.class, prop,
                    "Expected IProperty for %greeting in embedded FXML; got: " + prop);
            assertEquals("greeting", prop.getKey());
        });
    }

    /**
     * The property value must appear in the {@code generateDoc()} output that the
     * {@code PropertiesDocumentationProvider} generates for the resolved {@link IProperty}.
     */
    @Test
    void staticResourceShortformDocContainsPropertyValue() {
        getFixture().addFileToProject("test/docval.properties", "title=My Application");
        getFixture().configureByText("DocValView.java",
                javaWithMarkup("DocValView", "    <Button text=\"%title\"/>\n"));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            IProperty prop = resolvedPropertyAt(getFixture().getFile(), "title");
            assertNotNull(prop);

            String doc = new PropertiesDocumentationProvider().generateDoc((PsiElement) prop, null);
            assertNotNull(doc, "generateDoc must not return null for the resolved IProperty");
            assertTrue(doc.contains("My Application"),
                    "Generated documentation must contain the property value 'My Application'; got: " + doc);
            assertTrue(doc.contains("docval.properties"),
                    "Generated documentation must contain the properties file name; got: " + doc);
        });
    }

    // -----------------------------------------------------------------------
    // Embedded FXML ({DynamicResource key})
    // -----------------------------------------------------------------------

    /**
     * {@code {DynamicResource welcome}} in an embedded FXML text block must redirect
     * documentation to the {@link IProperty} for {@code welcome}.
     */
    @Test
    void dynamicResourceInEmbeddedFxmlRedirectsToProperty() {
        getFixture().addFileToProject("test/dynDoc.properties", "welcome=Welcome!");
        getFixture().configureByText("DynDocView.java",
                javaWithMarkup("DynDocView", "    <Button text=\"{DynamicResource welcome}\"/>\n"));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            IProperty prop = resolvedPropertyAt(getFixture().getFile(), "welcome");
            assertInstanceOf(IProperty.class, prop,
                    "Expected IProperty for {DynamicResource welcome} in embedded FXML; got: " + prop);
            assertEquals("welcome", prop.getKey());
        });
    }

    /**
     * {@code {StaticResource name}} (long form) in an embedded FXML text block must redirect
     * documentation to the {@link IProperty} for {@code name}.
     */
    @Test
    void staticResourceLongFormInEmbeddedFxmlRedirectsToProperty() {
        getFixture().addFileToProject("test/staticDoc.properties", "name=Alice");
        getFixture().configureByText("StaticDocView.java",
                javaWithMarkup("StaticDocView", "    <Button text=\"{StaticResource name}\"/>\n"));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            IProperty prop = resolvedPropertyAt(getFixture().getFile(), "name");
            assertInstanceOf(IProperty.class, prop,
                    "Expected IProperty for {StaticResource name} in embedded FXML; got: " + prop);
            assertEquals("name", prop.getKey());
        });
    }

    // -----------------------------------------------------------------------
    // Standalone .fxmlx file (%key shorthand)
    // -----------------------------------------------------------------------

    /**
     * {@code %label.text} in a standalone {@code .fxmlx} file must redirect documentation to
     * the {@link IProperty} for {@code label.text}.
     */
    @Test
    void staticResourceShortformInStandaloneFxml2RedirectsToProperty() {
        getFixture().addFileToProject("test/standalone.properties", "label.text=My Label");
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class StandaloneDocView extends BorderPane {}
                """);

        getFixture().configureByText("StandaloneDocView.fxmlx", fxml2(
                "javafx.scene.control.Label",
                "  <Label text=\"%label.text\"/>\n",
                "test.StandaloneDocView"
        ));

        ReadAction.run(() -> {
            IProperty prop = resolvedPropertyAt(getFixture().getFile(), "label.text");
            assertInstanceOf(IProperty.class, prop,
                    "Expected IProperty for %label.text in standalone .fxmlx; got: " + prop);
            assertEquals("label.text", prop.getKey());
        });
    }

    // -----------------------------------------------------------------------
    // Standalone .fxmlx file ({DynamicResource key})
    // -----------------------------------------------------------------------

    /**
     * {@code {DynamicResource header}} in a standalone {@code .fxmlx} file must redirect
     * documentation to the {@link IProperty} for {@code header}.
     */
    @Test
    void dynamicResourceInStandaloneFxml2RedirectsToProperty() {
        getFixture().addFileToProject("test/standaloneD.properties", "header=My Header");
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class StandaloneDynDocView extends BorderPane {}
                """);

        getFixture().configureByText("StandaloneDynDocView.fxmlx", fxml2(
                "javafx.scene.control.Label\norg.jfxcore.markup.resource.DynamicResource",
                "  <Label text=\"{DynamicResource header}\"/>\n",
                "test.StandaloneDynDocView"
        ));

        ReadAction.run(() -> {
            IProperty prop = resolvedPropertyAt(getFixture().getFile(), "header");
            assertInstanceOf(IProperty.class, prop,
                    "Expected IProperty for {DynamicResource header} in standalone .fxmlx; got: " + prop);
            assertEquals("header", prop.getKey());
        });
    }

    // -----------------------------------------------------------------------
    // Offset NOT on the key -> provider must return null
    // -----------------------------------------------------------------------

    /**
     * When the cursor is on {@code DynamicResource} (the class name) rather than on the key,
     * the provider must return {@code null}.
     */
    @Test
    void hoverOnDynamicResourceClassNameReturnsNull() {
        getFixture().addFileToProject("test/dynClass.properties", "button.text=Click");
        getFixture().configureByText("DynClassView.java",
                javaWithMarkup("DynClassView",
                        "    <Button text=\"{DynamicResource button.text; formatArguments=${vm.x}}\"/>\n"));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiFile file = getFixture().getFile();
            int offset = file.getText().indexOf("DynamicResource");
            assertTrue(offset >= 0, "DynamicResource not found in file text");
            IProperty prop = Fxml2ResourceKeyDocumentationTargetProvider.resolvePropertyAt(file, offset);
            assertNull(prop,
                    "Provider must return null when cursor is on 'DynamicResource', not on the key");
        });
    }

    /**
     * When the cursor is on {@code formatArguments} (the named-arg name) rather than on the key,
     * the provider must return {@code null}.
     */
    @Test
    void hoverOnFormatArgumentsNameReturnsNull() {
        getFixture().addFileToProject("test/dynFmt.properties", "button.text=Click");
        getFixture().configureByText("DynFmtView.java",
                javaWithMarkup("DynFmtView",
                        "    <Button text=\"{DynamicResource button.text; formatArguments=${vm.x}}\"/>\n"));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiFile file = getFixture().getFile();
            int offset = file.getText().indexOf("formatArguments");
            assertTrue(offset >= 0, "formatArguments not found in file text");
            IProperty prop = Fxml2ResourceKeyDocumentationTargetProvider.resolvePropertyAt(file, offset);
            assertNull(prop,
                    "Provider must return null when cursor is on 'formatArguments', not on the key");
        });
    }

    /**
     * When the cursor is on the binding expression argument (e.g. {@code vm.x}) rather than
     * on the key, the provider must return {@code null}.
     */
    @Test
    void hoverOnBindingArgumentReturnsNull() {
        getFixture().addFileToProject("test/dynBind.properties", "button.text=Click");
        getFixture().configureByText("DynBindView.java",
                javaWithMarkup("DynBindView",
                        "    <Button text=\"{DynamicResource button.text; formatArguments=${vm.x}}\"/>\n"));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiFile file = getFixture().getFile();
            int offset = file.getText().indexOf("vm.x");
            assertTrue(offset >= 0, "vm.x not found in file text");
            IProperty prop = Fxml2ResourceKeyDocumentationTargetProvider.resolvePropertyAt(file, offset);
            assertNull(prop,
                    "Provider must return null when cursor is on a binding argument, not on the key");
        });
    }

    // -----------------------------------------------------------------------
    // No property found -> provider must return null / empty
    // -----------------------------------------------------------------------

    /**
     * When no matching property exists in any resource bundle, the provider must return
     * {@code null} and must not throw.
     */
    @Test
    void noMatchingPropertyReturnsNull() {
        getFixture().configureByText("NullPropView.java",
                javaWithMarkup("NullPropView", "    <Button text=\"%nonexistent.key\"/>\n"));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            IProperty prop = resolvedPropertyAt(getFixture().getFile(), "nonexistent.key");
            assertNull(prop,
                    "Provider must return null when the property key cannot be resolved");
        });
    }

    // -----------------------------------------------------------------------
    // V2 DocumentationTargetProvider integration
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2ResourceKeyDocumentationTargetProvider#documentationTargets} must return a
     * non-empty list when the offset is on the key text of a {@code %key} reference in
     * embedded FXML, verifying end-to-end integration with IntelliJ's V2 documentation pipeline.
     */
    @Test
    void documentationTargetProviderReturnsTargetForStaticResourceInEmbeddedFxml() {
        getFixture().addFileToProject("test/mgr.properties", "button.label=Click me");
        getFixture().configureByText("MgrDocView.java",
                javaWithMarkup("MgrDocView", "    <Button text=\"%button.label\"/>\n"));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiFile file = getFixture().getFile();
            int offset = file.getText().indexOf("button.label");
            assertTrue(offset >= 0);

            Fxml2ResourceKeyDocumentationTargetProvider provider =
                    new Fxml2ResourceKeyDocumentationTargetProvider();
            List<? extends DocumentationTarget> targets = provider.documentationTargets(file, offset);

            assertFalse(targets.isEmpty(),
                    "documentationTargets must return a non-empty list for %button.label in embedded FXML");
        });
    }
}
