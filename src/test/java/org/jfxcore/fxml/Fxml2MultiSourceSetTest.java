package org.jfxcore.fxml;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.IndexingTestUtil;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.VfsTestUtil;
import org.jfxcore.fxml.resolve.Fxml2AttributeValueResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that fx:typeArguments type resolution respects module source-set boundaries.
 *
 * <p>When the same fully-qualified class name exists in multiple source sets (e.g.
 * a production source set and a test-only source set), the type substitutor and
 * navigation must resolve to the class that is visible from the FXML file's own
 * resolve scope, not an arbitrary class found via a project-wide search.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2MultiSourceSetTest extends Fxml2TestBase {

    /**
     * A directory added as a standalone test-only content root (test source root)
     * for this test class.  It holds {@code sample.app.TypeArgTarget}, a class
     * that is accessible only from test code, not from production source files.
     */
    private VirtualFile testSourceRoot;

    @BeforeAll
    void setUpTestSourceRoot() {
        EdtTestUtil.runInEdtAndWait(() -> {
            // Create a separate directory alongside the fixture's production temp dir
            // so that it is NOT nested inside the production source root.
            VirtualFile tempDir = getFixture().getTempDirFixture().getFile(".");
            assertNotNull(tempDir, "Fixture temp dir must be non-null");
            VirtualFile parent = tempDir.getParent();
            assertNotNull(parent, "Fixture temp dir must have a parent");

            ApplicationManager.getApplication().runWriteAction(() -> {
                try {
                    testSourceRoot = parent.createChildDirectory(
                            Fxml2MultiSourceSetTest.class,
                            "fxml2-test-src-" + getClass().getSimpleName());
                    VfsTestUtil.createFile(testSourceRoot, "sample/app/TypeArgTarget.java",
                            """
                            package sample.app;
                            public class TypeArgTarget {
                                public String getTestValue() { return ""; }
                            }
                            """);
                    PsiTestUtil.addSourceRoot(getFixture().getModule(), testSourceRoot, true);
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            });
        });

        IndexingTestUtil.waitUntilIndexesAreReady(getFixture().getProject());
    }

    @AfterAll
    void tearDownTestSourceRoot() {
        if (testSourceRoot != null) {
            EdtTestUtil.runInEdtAndWait(() -> {
                PsiTestUtil.removeSourceRoot(getFixture().getModule(), testSourceRoot);
                ApplicationManager.getApplication().runWriteAction(() -> {
                    try {
                        testSourceRoot.delete(Fxml2MultiSourceSetTest.class);
                    } catch (java.io.IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            });
            IndexingTestUtil.waitUntilIndexesAreReady(getFixture().getProject());
            testSourceRoot = null;
        }
    }

    // -----------------------------------------------------------------------
    // fx:typeArguments: type substitutor must use the file's resolve scope
    // -----------------------------------------------------------------------

    /**
     * When {@code fx:typeArguments} names a class by fully-qualified name and that
     * class exists only in a test source root (not in the production source set),
     * {@link Fxml2AttributeValueResolver#buildTagTypeSubstitutor} must return
     * {@link PsiSubstitutor#EMPTY}.  It must not silently pick up the test-only
     * class via a project-wide search scope.
     */
    @Test
    void typeSubstitutorIgnoresTestSourceRootClass_fqn() {
        getFixture().addFileToProject("test/GenericComponent.java",
                """
                package test;
                public class GenericComponent<T> {
                    public void setItem(T item) {}
                }
                """);

        var fxmlFile = (XmlFile) getFixture().addFileToProject("TestView.fxml", fxml(
                "test.GenericComponent",
                """
                  <GenericComponent fx:typeArguments="sample.app.TypeArgTarget"/>
                """
        ));

        ReadAction.run(() -> {
            XmlTag tag = requireGenericComponentTag(fxmlFile);
            PsiClass ownerClass = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.GenericComponent", fxmlFile.getResolveScope());
            assertNotNull(ownerClass, "GenericComponent must be in the production resolve scope");

            PsiSubstitutor substitutor =
                    Fxml2AttributeValueResolver.buildTagTypeSubstitutor(ownerClass, tag, fxmlFile);

            // sample.app.TypeArgTarget lives only in the test source root and is
            // invisible to production code.  The substitutor must not resolve it.
            assertSame(PsiSubstitutor.EMPTY, substitutor,
                    "Type substitutor must not resolve a class from a test-only source root");
        });
    }

    /**
     * Same requirement when the type argument is given as a simple name resolved
     * through a file-level {@code <?import?>} declaration.  The import maps the
     * simple name to a fully-qualified name, but the underlying class still lives
     * only in the test source root and must not be used for type substitution.
     */
    @Test
    void typeSubstitutorIgnoresTestSourceRootClass_simpleName() {
        getFixture().addFileToProject("test/GenericComponent.java",
                """
                package test;
                public class GenericComponent<T> {
                    public void setItem(T item) {}
                }
                """);

        var fxmlFile = (XmlFile) getFixture().addFileToProject("TestView.fxml", fxml(
                "test.GenericComponent\nsample.app.TypeArgTarget",
                """
                  <GenericComponent fx:typeArguments="TypeArgTarget"/>
                """
        ));

        ReadAction.run(() -> {
            XmlTag tag = requireGenericComponentTag(fxmlFile);
            PsiClass ownerClass = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.GenericComponent", fxmlFile.getResolveScope());
            assertNotNull(ownerClass, "GenericComponent must be in the production resolve scope");

            PsiSubstitutor substitutor =
                    Fxml2AttributeValueResolver.buildTagTypeSubstitutor(ownerClass, tag, fxmlFile);

            // sample.app.TypeArgTarget is imported by name but lives only in the test
            // source root.  The substitutor must not resolve it.
            assertSame(PsiSubstitutor.EMPTY, substitutor,
                    "Type substitutor must not resolve a simple-name import from a test-only source root");
        });
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    /** Returns the first {@link XmlTag} with the local name "GenericComponent" inside {@code file}. */
    private static XmlTag requireGenericComponentTag(XmlFile file) {
        XmlTag root = file.getRootTag();
        assertNotNull(root, "FXML root tag must not be null");
        XmlTag found = findTag(root, "GenericComponent");
        assertNotNull(found, "Tag <" + "GenericComponent" + "> not found in FXML");
        return found;
    }

    private static XmlTag findTag(XmlTag parent, String localName) {
        if (localName.equals(parent.getLocalName())) {
            return parent;
        }
        for (XmlTag child : parent.getSubTags()) {
            XmlTag found = findTag(child, localName);
            if (found != null) return found;
        }
        return null;
    }
}
