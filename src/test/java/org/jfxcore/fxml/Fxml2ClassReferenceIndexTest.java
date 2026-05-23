package org.jfxcore.fxml;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.GlobalSearchScope;
import org.jfxcore.fxml.indexing.Fxml2ClassReferenceIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Fxml2ClassReferenceIndex}: verifies that the persistent index
 * correctly extracts import declarations from standalone FXML2 files and from
 * embedded FXML2 fragments in Java source files.
 *
 * <p>These tests exercise the "Find Usages of Java class in FXML" data path,
 * which depends on an accurate index of which files import which fully-qualified
 * class names.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2ClassReferenceIndexTest extends Fxml2TestBase {

    // -----------------------------------------------------------------------
    // Standalone FXML2 file tests
    // -----------------------------------------------------------------------

    @Test
    void exactImportIsIndexed() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                "<Label text=\"Hello\"/>"
        ));

        Collection<VirtualFile> files = Fxml2ClassReferenceIndex.getFilesImporting(
                "javafx.scene.control.Label",
                GlobalSearchScope.projectScope(getFixture().getProject())
        );

        assertFalse(files.isEmpty(),
                "Exact import 'javafx.scene.control.Label' should be indexed");
        assertTrue(files.stream().anyMatch(f -> f.getName().equals("TestView.fxml")),
                "TestView.fxml should be returned for the indexed import");
    }

    @Test
    void wildcardImportIsIndexed() {
        getFixture().configureByText("WildcardView.fxml", fxml2(
                "javafx.scene.control.*",
                "<Label/>"
        ));

        Collection<VirtualFile> files = Fxml2ClassReferenceIndex.getFilesImporting(
                "javafx.scene.control.*",
                GlobalSearchScope.projectScope(getFixture().getProject())
        );

        assertFalse(files.isEmpty(),
                "Wildcard import 'javafx.scene.control.*' should be indexed");
        assertTrue(files.stream().anyMatch(f -> f.getName().equals("WildcardView.fxml")),
                "WildcardView.fxml should be returned for the wildcard import");
    }

    @Test
    void multipleImportsAreAllIndexed() {
        getFixture().configureByText("MultiView.fxml", fxml2(
                "javafx.scene.control.Button\njavafx.scene.control.Label",
                "<Label/>"
        ));

        var scope = GlobalSearchScope.projectScope(getFixture().getProject());

        Collection<VirtualFile> buttonFiles = Fxml2ClassReferenceIndex.getFilesImporting(
                "javafx.scene.control.Button", scope);
        Collection<VirtualFile> labelFiles  = Fxml2ClassReferenceIndex.getFilesImporting(
                "javafx.scene.control.Label",  scope);

        assertTrue(buttonFiles.stream().anyMatch(f -> f.getName().equals("MultiView.fxml")),
                "MultiView.fxml should be indexed for Button");
        assertTrue(labelFiles.stream().anyMatch(f -> f.getName().equals("MultiView.fxml")),
                "MultiView.fxml should be indexed for Label");
    }

    @Test
    void nonFxml2FileIsNotIndexed() {
        // A plain XML file without the FXML2 namespace must not appear in the index.
        getFixture().configureByText("plain.xml", """
                <?xml version="1.0"?>
                <?import javafx.scene.control.Label?>
                <root/>
                """);

        Collection<VirtualFile> files = Fxml2ClassReferenceIndex.getFilesImporting(
                "javafx.scene.control.Label",
                GlobalSearchScope.projectScope(getFixture().getProject())
        );

        assertTrue(files.stream().noneMatch(f -> f.getName().equals("plain.xml")),
                "A file without the FXML2 namespace must not be indexed");
    }

    @Test
    void classNotImportedInFileIsNotFound() {
        getFixture().configureByText("OtherView.fxml", fxml2(
                "javafx.scene.control.Button",
                "<Button/>"
        ));

        Collection<VirtualFile> files = Fxml2ClassReferenceIndex.getFilesImporting(
                "javafx.scene.control.ComboBox",
                GlobalSearchScope.projectScope(getFixture().getProject())
        );

        assertTrue(files.stream().noneMatch(f -> f.getName().equals("OtherView.fxml")),
                "OtherView.fxml must not appear for a class it does not import");
    }

    // -----------------------------------------------------------------------
    // Embedded FXML2 (Java @ComponentView) tests
    // -----------------------------------------------------------------------

    @Test
    void embeddedImportInJavaAnnotationIsIndexed() {
        // A Java file that embeds FXML2 via @ComponentView; its imports should
        // be picked up by the embedded indexer.
        getFixture().configureByText("EmbeddedView.java", """
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.control.Label;

                @ComponentView(\"""
                    <?import javafx.scene.control.TextField?>
                    <Label/>
                    \""")
                public class EmbeddedView extends Label {}
                """);

        Collection<VirtualFile> files = Fxml2ClassReferenceIndex.getFilesImporting(
                "javafx.scene.control.TextField",
                GlobalSearchScope.projectScope(getFixture().getProject())
        );

        assertFalse(files.isEmpty(),
                "TextField import inside @ComponentView annotation should be indexed");
        assertTrue(files.stream().anyMatch(f -> f.getName().equals("EmbeddedView.java")),
                "EmbeddedView.java should be returned for its embedded <?import?> declaration");
    }

    @Test
    void javaHostImportIsIndexedForEmbeddedFxml() {
        // Java imports in the host class are available to the embedded FXML2 fragment
        // and should therefore also be indexed.
        getFixture().configureByText("HostImportView.java", """
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.control.Button;

                @ComponentView("<Button/>")
                public class HostImportView extends javafx.scene.control.Button {}
                """);

        Collection<VirtualFile> files = Fxml2ClassReferenceIndex.getFilesImporting(
                "javafx.scene.control.Button",
                GlobalSearchScope.projectScope(getFixture().getProject())
        );

        assertFalse(files.isEmpty(),
                "Java import 'javafx.scene.control.Button' from a @ComponentView host file should be indexed");
        assertTrue(files.stream().anyMatch(f -> f.getName().equals("HostImportView.java")),
                "HostImportView.java should appear for its host-language import");
    }
}
