// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jfxcore.fxml.lang.Fxml2ResourceNameReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2ResourceLookupTest extends Fxml2TestBase {
    private Fxml2DocumentForm documentForm = Fxml2DocumentForm.STANDALONE;

    @org.junit.jupiter.api.BeforeEach
    void resetDocumentForm() {
        documentForm = Fxml2DocumentForm.STANDALONE;
    }

    @BeforeAll
    void installClasses() {
        installComponentViewAnnotation();
        addResourceMarkupExtensions();
        getFixture().addClass("""
                package views;
                public class ResourceView extends javafx.scene.layout.BorderPane {
                    public final ClassLoader loader = new ClassLoader() {};
                    public final ClassLoader nullLoader = null;
                }
                """);
    }

    @Test
    void dynamicLoaderDoesNotResolveToEmbeddedResource() {
        configure("{ClassPathResource styles.css; classLoader=$loader}");
        assertFalse(hasEmbeddedReference());
    }

    @Test
    void prefixLoaderDoesNotResolveToEmbeddedResource() {
        configure("@styles.css; classLoader=$loader");
        assertFalse(hasEmbeddedReference());
    }

    @Test
    void nullLoaderRetainsEmbeddedLookup() {
        configure("{ClassPathResource styles.css; classLoader={fx:Null}}");
        assertTrue(hasEmbeddedReference());
    }

    @Test
    void namedValueResolvesToEmbeddedResource() {
        configure("{ClassPathResource value=styles.css}");
        assertTrue(hasEmbeddedReference());
    }

    @Test
    void literalCommentsAreRemovedWithoutCollapsingWhitespace() {
        getFixture().configureByText("ResourceView.fxml", document(
                "{ClassPathResource dark /* comment */ theme.css}", "dark  theme.css"));
        assertTrue(hasEmbeddedReference());
    }

    @Test
    void relativeResourceUsesRootClassPackage() {
        getFixture().addFileToProject("views/icons/logo.png", "PNG");
        configure("@icons/logo.png");
        assertResolvedFile("logo.png");
    }

    @Test
    void longFormExternalResourceHasFileReference() {
        getFixture().addFileToProject("views/icons/logo.png", "PNG");
        configure("{ClassPathResource icons/logo.png}");
        assertResolvedFile("logo.png");
    }

    @Test
    void customLoaderDoesNotCountAsAnEmbeddedUsage() {
        getFixture().enableInspections(new org.jfxcore.fxml.annotator.Fxml2UnusedResourceInspection());
        configure("{ClassPathResource styles.css; classLoader=$loader}");
        assertTrue(getFixture().doHighlighting().stream().anyMatch(
                info -> "Embedded resource 'styles.css' is never used in this document".equals(info.getDescription())));
    }

    @Test
    void namedValueCountsAsAnEmbeddedUsage() {
        getFixture().enableInspections(new org.jfxcore.fxml.annotator.Fxml2UnusedResourceInspection());
        configure("{ClassPathResource value=styles.css}");
        assertFalse(getFixture().doHighlighting().stream().anyMatch(
                info -> "Embedded resource 'styles.css' is never used in this document".equals(info.getDescription())));
    }

    @Test
    void externalResourceRangesFollowSourceAfterComments() {
        getFixture().addFileToProject("views/icons/logo.png", "PNG");
        configure("{ClassPathResource icons//* comment */logo.png}");
        assertResolvedFile("logo.png");
        ReadAction.run(() -> {
            for (var value : PsiTreeUtil.findChildrenOfType(documentForm.markupFile(getFixture()), XmlAttributeValue.class)) {
                for (PsiReference reference : value.getReferences()) {
                    if (reference.resolve() instanceof PsiFile file && "logo.png".equals(file.getName())) {
                        assertEquals("logo.png", reference.getRangeInElement().substring(value.getText()));
                        return;
                    }
                }
            }
            fail("No resource file reference");
        });
    }

    @Test
    void generatedRootUsesMatchingPackageInSeparateResourceRoot() throws Exception {
        var markup = getFixture().getTempDirFixture().findOrCreateDir("markup");
        var resources = getFixture().getTempDirFixture().findOrCreateDir("resources");
        com.intellij.testFramework.EdtTestUtil.runInEdtAndWait(() -> {
            com.intellij.testFramework.PsiTestUtil.addSourceRoot(getFixture().getModule(), markup);
            com.intellij.testFramework.PsiTestUtil.addSourceRoot(getFixture().getModule(), resources);
        });
        getFixture().addFileToProject("resources/views/icons/generated.png", "PNG");
        var source = getFixture().addFileToProject("markup/views/GeneratedView.fxml",
                document("@icons/generated.png", "styles.css").replace(" fx:subclass=\"views.ResourceView\"", ""));
        getFixture().configureFromExistingVirtualFile(source.getVirtualFile());
        assertResolvedFile("generated.png");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = Fxml2DocumentForm.class, names = {"JAVA", "KOTLIN"})
    void embeddedExternalResourceUsesHostPackage(Fxml2DocumentForm form) {
        documentForm = form;
        getFixture().addFileToProject("test/icons/embedded.png", "PNG");
        form.configure(getFixture(), "<?import org.jfxcore.markup.resource.ClassPathResource?>\n",
                "<BorderPane stylesheets=\"{ClassPathResource icons/embedded.png}\"/>");
        assertResolvedFile("embedded.png");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(Fxml2DocumentForm.class)
    void unknownLoaderHasNoDefiniteEmbeddedTarget(Fxml2DocumentForm form) {
        documentForm = form;
        form.configure(getFixture(), """
                <?import org.jfxcore.markup.resource.ClassPathResource?>
                <?resource styles.css:body?>
                """,
                "<BorderPane stylesheets=\"{ClassPathResource styles.css; classLoader=$userData}\"/>");
        assertFalse(hasEmbeddedReference());
    }

    @Test
    void nestedUnknownLoaderCountsAsAPotentialEmbeddedUsage() {
        getFixture().enableInspections(new org.jfxcore.fxml.annotator.Fxml2UnusedResourceInspection());
        configure("{StaticResource greeting; formatArguments={ClassPathResource styles.css; classLoader=$userData}}");
        assertFalse(getFixture().doHighlighting().stream().anyMatch(
                info -> "Embedded resource 'styles.css' is never used in this document".equals(info.getDescription())));
    }

    private void configure(String invocation) {
        getFixture().configureByText("ResourceView.fxml", document(invocation, "styles.css"));
    }

    private static String document(String invocation, String resourceName) {
        return "<?import views.ResourceView?>\n"
                + "<?import org.jfxcore.markup.resource.ClassPathResource?>\n"
                + "<?resource \"" + resourceName + "\":body?>\n"
                + "<ResourceView xmlns=\"http://javafx.com/javafx\" xmlns:fx=\"http://jfxcore.org/fxml/2.0\""
                + " fx:subclass=\"views.ResourceView\" stylesheets=\"" + invocation + "\"/>";
    }

    private boolean hasEmbeddedReference() {
        return ReadAction.compute(() -> {
            for (var value : PsiTreeUtil.findChildrenOfType(documentForm.markupFile(getFixture()), XmlAttributeValue.class)) {
                if (value.getValue().contains("ClassPathResource") || value.getValue().startsWith("@")) {
                    for (PsiReference reference : value.getReferences()) {
                        if (reference instanceof Fxml2ResourceNameReference) return true;
                    }
                }
            }
            return false;
        });
    }

    @SuppressWarnings("SameParameterValue")
    private void assertResolvedFile(String expectedName) {
        ReadAction.run(() -> {
            for (var value : PsiTreeUtil.findChildrenOfType(documentForm.markupFile(getFixture()), XmlAttributeValue.class)) {
                for (PsiReference reference : value.getReferences()) {
                    if (reference.resolve() instanceof PsiFile file && expectedName.equals(file.getName())) return;
                }
            }
            fail("No reference resolved to " + expectedName);
        });
    }
}
