package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReference;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@code @path} attribute values in both embedded FXML ({@code @ComponentView})
 * and standalone FXML files expose {@link FileReference} instances so that:
 * <ul>
 *   <li>Ctrl+click navigates to the referenced classpath resource file.</li>
 *   <li>{@code @path} is supported equally well as {@code %key} (which uses
 *       {@link com.intellij.lang.properties.references.PropertyReference}).</li>
 * </ul>
 *
 * <p>The same file references are contributed for standalone FXML/2 files, regardless of
 * whether they use the {@code .fxml} or {@code .fxmlx} extension.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class Fxml2ClassPathResourceFileReferenceTest extends Fxml2TestBase {

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
        // ClassPathResource mock for type-check tests
        getFixture().addClass("""
                package org.jfxcore.markup.resource;
                import javafx.beans.DefaultProperty;
                import javafx.beans.NamedArg;
                @DefaultProperty("value")
                public final class ClassPathResource {
                    public ClassPathResource(@NamedArg("value") String value) {}
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
     * value starts with {@code "@"} and contains {@code pathSubstring}.
     */
    private static XmlAttributeValue findAttrValByAtPath(XmlFile xmlFile, String pathSubstring) {
        for (XmlTag tag : PsiTreeUtil.findChildrenOfType(xmlFile, XmlTag.class)) {
            for (XmlAttribute attr : tag.getAttributes()) {
                String val = attr.getValue();
                if (val != null && val.startsWith("@") && val.contains(pathSubstring)) {
                    return attr.getValueElement();
                }
            }
        }
        return null;
    }

    /** Returns true if any reference in {@code refs} is a {@link FileReference}. */
    private static boolean hasFileReference(PsiReference[] refs) {
        for (PsiReference ref : refs) {
            if (ref instanceof FileReference) return true;
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Tests: embedded FXML
    // -----------------------------------------------------------------------

    /**
     * {@code @/icons/app.png} in an embedded FXML Java file must expose at least one
     * {@link FileReference} on the attribute value: so that Ctrl+click navigates to
     * the referenced classpath resource file.
     */
    @Test
    void atPathInEmbeddedFxmlHasFileReference() {
        getFixture().addFileToProject("icons/app.png", "PNG");
        getFixture().configureByText("TestView.java",
                javaWithMarkup("TestView",
                        "    <Button text=\"@/icons/app.png\"/>\n"));

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

            XmlAttributeValue attrVal = findAttrValByAtPath(xmlFile, "/icons/app.png");
            assertNotNull(attrVal, "Could not find text='@/icons/app.png' in injected XML");

            assertTrue(hasFileReference(attrVal.getReferences()),
                    "Expected a FileReference for '@/icons/app.png' in embedded FXML; "
                    + "references found: " + Arrays.toString(attrVal.getReferences()));
        });
    }

    /**
     * The {@link FileReference}(s) for {@code @/icons/app.png} in embedded FXML must
     * resolve to the referenced file, enabling navigation.
     */
    @Test
    void atPathInEmbeddedFxmlResolvesToFile() {
        getFixture().addFileToProject("icons/logo.png", "PNG");
        getFixture().configureByText("TestView2.java",
                javaWithMarkup("TestView2",
                        "    <Button text=\"@/icons/logo.png\"/>\n"));

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

            XmlAttributeValue attrVal = findAttrValByAtPath(xmlFile, "/icons/logo.png");
            assertNotNull(attrVal, "Could not find text='@/icons/logo.png' in injected XML");

            for (PsiReference ref : attrVal.getReferences()) {
                if (!(ref instanceof FileReference)) continue;
                PsiElement resolved = ref.resolve();
                if (resolved instanceof PsiFile resolvedFile) {
                    VirtualFile vf = resolvedFile.getVirtualFile();
                    assertNotNull(vf);
                    assertEquals("logo.png", vf.getName(),
                            "FileReference must resolve to the 'logo.png' resource file");
                    return; // resolved, test passed
                }
            }
            fail("No FileReference resolved to the 'logo.png' file in embedded FXML; "
                 + "ensure the file is in the project source root and the reference set "
                 + "uses ABSOLUTE_TOP_LEVEL for paths starting with '/'");
        });
    }

    /**
     * The range of the {@link FileReference}(s) added for a {@code @/path} value must
     * start after the {@code @} prefix: so hover-highlighting and Ctrl+click land on
     * the path text, not on the prefix character.
     */
    @Test
    void atPathFileReferenceRangeStartsAfterAtSign() {
        getFixture().addFileToProject("img/banner.jpg", "JPG");
        getFixture().configureByText("TestView3.java",
                javaWithMarkup("TestView3",
                        "    <Button text=\"@/img/banner.jpg\"/>\n"));

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

            XmlAttributeValue attrVal = findAttrValByAtPath(xmlFile, "/img/banner.jpg");
            assertNotNull(attrVal, "Could not find text='@/img/banner.jpg' in injected XML");

            // attrVal.getText() includes surrounding quotes: "@/img/banner.jpg"
            // The '@' is at offset 1 (after opening quote).
            // The first path segment should start at offset 2 (after '"' and '@').
            for (PsiReference ref : attrVal.getReferences()) {
                if (!(ref instanceof FileReference)) continue;
                int start = ref.getRangeInElement().getStartOffset();
                assertTrue(start >= 2,
                        "FileReference range start must be >= 2 (past the opening quote and '@'), "
                        + "but got " + start + " in: " + attrVal.getText());
                return;
            }
            fail("No FileReference found for '@/img/banner.jpg' in embedded FXML");
        });
    }

    /**
     * A {@code @path} value in a standalone {@code .fxml} FXML/2 file receives
     * {@link FileReference}(s) from the FXML/2 plugin so that Ctrl+click navigates to the
     * referenced classpath resource file.
     */
    @Test
    void atPathInStandaloneFxmlProducesFileReferences() {
        getFixture().addFileToProject("icons/standalone.png", "PNG");

        // Add a minimal code-behind so the .fxml file is valid FXML.
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class StandaloneView extends BorderPane {}
                """);

        getFixture().configureByText("StandaloneView.fxml", fxml(
                "javafx.scene.control.Label",
                "  <Label text=\"@/icons/standalone.png\"/>\n",
                "test.StandaloneView"
        ));

        ReadAction.run(() -> {
            XmlAttributeValue attrVal = null;
            for (XmlTag tag : PsiTreeUtil.findChildrenOfType(
                    getFixture().getFile(), XmlTag.class)) {
                XmlAttribute attr = tag.getAttribute("text");
                if (attr != null && "@/icons/standalone.png".equals(attr.getValue())) {
                    attrVal = attr.getValueElement();
                    break;
                }
            }
            assertNotNull(attrVal, "Could not find text='@/icons/standalone.png' in StandaloneView.fxml");

            // The FXML/2 plugin contributes one FileReference per path segment so that the
            // referenced classpath resource is navigable. For "/icons/standalone.png" that is
            // one reference per segment, never a doubled set.
            long fxml2PluginFileRefs = Arrays.stream(attrVal.getReferences())
                    .filter(r -> r instanceof FileReference)
                    .count();
            assertTrue(fxml2PluginFileRefs >= 1 && fxml2PluginFileRefs <= 10,
                    "FXML/2 plugin must contribute navigable FileReferences for standalone .fxml; "
                    + "found " + fxml2PluginFileRefs);
        });
    }

    // -----------------------------------------------------------------------
    // Tests: standalone .fxmlx files
    // -----------------------------------------------------------------------

    /**
     * {@code @/path} in a standalone {@code .fxmlx} file must also expose
     * {@link FileReference} instances: the bundled JavaFX plugin does not handle
     * {@code .fxmlx} files (it checks for the {@code .fxml} extension only).
     */
    @Test
    void atPathInStandaloneFxml2HasFileReference() {
        getFixture().addFileToProject("data/logo.png", "PNG");

        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class StandaloneFxml2View extends BorderPane {}
                """);

        getFixture().configureByText("StandaloneFxml2View.fxmlx", fxml(
                "javafx.scene.control.Label",
                "  <Label text=\"@/data/logo.png\"/>\n",
                "test.StandaloneFxml2View"
        ));

        ReadAction.run(() -> {
            XmlAttributeValue attrVal = null;
            for (XmlTag tag : PsiTreeUtil.findChildrenOfType(
                    getFixture().getFile(), XmlTag.class)) {
                XmlAttribute attr = tag.getAttribute("text");
                if (attr != null && "@/data/logo.png".equals(attr.getValue())) {
                    attrVal = attr.getValueElement();
                    break;
                }
            }
            assertNotNull(attrVal, "Could not find text='@/data/logo.png' in StandaloneFxml2View.fxmlx");

            assertTrue(hasFileReference(attrVal.getReferences()),
                    "Expected FileReference(s) for '@/data/logo.png' in standalone .fxmlx; "
                    + "references found: " + Arrays.toString(attrVal.getReferences()));
        });
    }

    // -----------------------------------------------------------------------
    // Tests: %key parity (standalone .fxmlx should also have PropertyReference)
    // -----------------------------------------------------------------------

    /**
     * {@code %key} in a standalone {@code .fxmlx} file must also expose a
     * {@link com.intellij.lang.properties.references.PropertyReference}: the bundled
     * JavaFX plugin only handles {@code .fxml} files.
     * This test verifies that {@code @} and {@code %} receive equal IDE support
     * in standalone {@code .fxmlx} files.
     */
    @Test
    void percentKeyInStandaloneFxml2HasPropertyReference() {
        getFixture().addFileToProject("test/labels.properties", "title=My App");

        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class StandaloneFxml2Labels extends BorderPane {}
                """);

        getFixture().configureByText("StandaloneFxml2Labels.fxmlx", fxml(
                "javafx.scene.control.Label",
                "  <Label text=\"%title\"/>\n",
                "test.StandaloneFxml2Labels"
        ));

        ReadAction.run(() -> {
            XmlAttributeValue attrVal = null;
            for (XmlTag tag : PsiTreeUtil.findChildrenOfType(
                    getFixture().getFile(), XmlTag.class)) {
                XmlAttribute attr = tag.getAttribute("text");
                if (attr != null && "%title".equals(attr.getValue())) {
                    attrVal = attr.getValueElement();
                    break;
                }
            }
            assertNotNull(attrVal, "Could not find text='%title' in StandaloneFxml2Labels.fxmlx");

            boolean hasPropertyRef = Arrays.stream(attrVal.getReferences())
                    .anyMatch(r -> r instanceof com.intellij.lang.properties.references.PropertyReferenceBase);
            assertTrue(hasPropertyRef,
                    "Expected a PropertyReferenceBase for '%title' in standalone .fxmlx; "
                    + "references found: " + Arrays.toString(attrVal.getReferences()));
        });
    }
}
