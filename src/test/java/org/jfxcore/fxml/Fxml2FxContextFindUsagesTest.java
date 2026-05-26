package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jfxcore.fxml.lang.Fxml2BindingSegmentReference;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Find-usages and navigation regression tests for the {@code fx:context} feature.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>The context class referenced via element notation ({@code <fx:context><MyCtx/></fx:context>})
 *       is found by "Find Usages" in both standalone and embedded FXML.</li>
 *   <li>Properties on the context class referenced in binding paths are found by "Find Usages"
 *       in both standalone and embedded FXML.</li>
 *   <li>Ctrl+click navigation from a class tag inside {@code <fx:context>} resolves to the
 *       class declaration.</li>
 *   <li>Ctrl+click navigation from a binding-path segment in {@code fx:context="$field"}
 *       resolves to the field declaration on the code-behind class.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class Fxml2FxContextFindUsagesTest extends Fxml2TestBase {

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

    @BeforeAll
    void addSharedClasses() {
        // Compiler-generated base class for standalone tests
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public abstract class FxuBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        // Code-behind for standalone tests
        getFixture().addClass("""
                package test;
                public class FxuView extends FxuBase {
                    public test.FxuContext myContext = new test.FxuContext();
                }
                """);
        // Context class with a property
        getFixture().addClass("""
                package test;
                import javafx.beans.property.StringProperty;
                import javafx.beans.property.SimpleStringProperty;
                public class FxuContext {
                    private final StringProperty userName =
                            new SimpleStringProperty(this, "userName", "");
                    public StringProperty userNameProperty() { return userName; }
                    public String getUserName() { return userName.get(); }
                    public void setUserName(String v) { userName.set(v); }
                }
                """);
    }

    // -----------------------------------------------------------------------
    // Feature 8: Find usages of context class in element notation
    // -----------------------------------------------------------------------

    /**
     * "Find Usages" on the context class must discover its use as an element-notation
     * context in a standalone FXML file.
     */
    @Test
    void findUsagesOfContextClassInElementNotationStandalone() {
        getFixture().configureByText("FxuFindCtxElem.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.Label?>
                <?import test.FxuContext?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.FxuView">
                  <fx:context>
                    <FxuContext/>
                  </fx:context>
                </BorderPane>
                """);
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass fxuCtx = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.FxuContext",
                            GlobalSearchScope.projectScope(getFixture().getProject()));
            assertNotNull(fxuCtx, "Must find class FxuContext");

            Collection<PsiReference> refs = ReferencesSearch.search(
                    fxuCtx,
                    GlobalSearchScope.projectScope(getFixture().getProject())).findAll();

            boolean foundInFxml = refs.stream().anyMatch(
                    ref -> ref.getElement().getContainingFile().getName().endsWith(".fxml"));
            assertTrue(foundInFxml,
                    "Find Usages on FxuContext must find a reference in the standalone FXML file. "
                    + "Found refs: "
                    + refs.stream().map(r -> r.getClass().getSimpleName()
                            + " in " + r.getElement().getContainingFile().getName()).toList());
        });
    }

    /**
     * "Find Usages" on the context class must discover its use as an element-notation
     * context inside embedded FXML markup.
     */
    @Test
    void findUsagesOfContextClassInElementNotationEmbedded() {
        getFixture().configureByText("FxuFindCtxEmbedded.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import test.FxuContext;
                @ComponentView(\"""
                    <?import test.FxuContext?>
                    <StackPane>
                      <fx:context>
                        <FxuContext/>
                      </fx:context>
                    </StackPane>
                    \""")
                public class FxuFindCtxEmbedded {
                    protected void initializeComponent() {}
                    public FxuFindCtxEmbedded() { initializeComponent(); }
                }
                """);
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass fxuCtx = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.FxuContext",
                            GlobalSearchScope.projectScope(getFixture().getProject()));
            assertNotNull(fxuCtx, "Must find class FxuContext");

            Collection<PsiReference> refs = ReferencesSearch.search(
                    fxuCtx,
                    GlobalSearchScope.projectScope(getFixture().getProject())).findAll();

            boolean foundInEmbedded = refs.stream().anyMatch(
                    ref -> ref.getElement().getContainingFile() instanceof XmlFile xmlFile
                           && Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile));
            assertTrue(foundInEmbedded,
                    "Find Usages on FxuContext must find a reference in the embedded FXML markup. "
                    + "Found refs: "
                    + refs.stream().map(r -> r.getClass().getSimpleName()
                            + " in " + r.getElement().getContainingFile().getName()).toList());
        });
    }

    // -----------------------------------------------------------------------
    // Feature 9: Find usages of context class properties in binding paths
    // -----------------------------------------------------------------------

    /**
     * "Find Usages" on a property accessor method of the context class must discover
     * the binding-path segment that references it in a standalone FXML file where
     * {@code fx:context} is set.
     */
    @Test
    void findUsagesOfContextPropertyInBindingPathStandalone() {
        getFixture().configureByText("FxuFindPropStandalone.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.Label?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.FxuView"
                            fx:context="$myContext">
                  <Label text="${userName}"/>
                </BorderPane>
                """);
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass fxuCtx = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.FxuContext",
                            GlobalSearchScope.projectScope(getFixture().getProject()));
            assertNotNull(fxuCtx, "Must find class FxuContext");

            PsiMethod userNameProp = Arrays.stream(fxuCtx.getMethods())
                    .filter(m -> "userNameProperty".equals(m.getName()))
                    .findFirst().orElse(null);
            assertNotNull(userNameProp, "Must find userNameProperty() method on FxuContext");

            Collection<PsiReference> refs = ReferencesSearch.search(
                    userNameProp,
                    GlobalSearchScope.projectScope(getFixture().getProject())).findAll();

            boolean foundBinding = refs.stream().anyMatch(
                    ref -> ref instanceof Fxml2BindingSegmentReference
                           && ref.getElement().getContainingFile().getName().endsWith(".fxml"));
            assertTrue(foundBinding,
                    "Find Usages on FxuContext.userNameProperty() must find the 'userName' "
                    + "binding segment in the standalone FXML file. Found refs: "
                    + refs.stream().map(r -> r.getClass().getSimpleName()
                            + " in " + r.getElement().getContainingFile().getName()).toList());
        });
    }

    /**
     * "Find Usages" on a property accessor method of the context class must discover
     * the binding-path segment that references it in embedded FXML markup where
     * {@code fx:context} is set (attribute form).
     */
    @Test
    void findUsagesOfContextPropertyInBindingPathEmbedded() {
        getFixture().configureByText("FxuFindPropEmbedded.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane fx:context="$myContext">
                      <Label text="${userName}"/>
                    </StackPane>
                    \""")
                public class FxuFindPropEmbedded {
                    public test.FxuContext myContext = new test.FxuContext();
                    protected void initializeComponent() {}
                    public FxuFindPropEmbedded() { initializeComponent(); }
                }
                """);
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            PsiClass fxuCtx = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.FxuContext",
                            GlobalSearchScope.projectScope(getFixture().getProject()));
            assertNotNull(fxuCtx, "Must find class FxuContext");

            PsiMethod userNameProp = Arrays.stream(fxuCtx.getMethods())
                    .filter(m -> "userNameProperty".equals(m.getName()))
                    .findFirst().orElse(null);
            assertNotNull(userNameProp, "Must find userNameProperty() method on FxuContext");

            Collection<PsiReference> refs = ReferencesSearch.search(
                    userNameProp,
                    GlobalSearchScope.projectScope(getFixture().getProject())).findAll();

            boolean foundBinding = refs.stream().anyMatch(
                    ref -> ref instanceof Fxml2BindingSegmentReference
                           && ref.getElement().getContainingFile() instanceof XmlFile xmlFile
                           && Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile));
            assertTrue(foundBinding,
                    "Find Usages on FxuContext.userNameProperty() must find the 'userName' "
                    + "binding segment in embedded FXML with fx:context set. Found refs: "
                    + refs.stream().map(r -> r.getClass().getSimpleName()
                            + " in " + r.getElement().getContainingFile().getName()).toList());
        });
    }

    // -----------------------------------------------------------------------
    // Feature 10: Navigation from context element/attribute to class/field
    // -----------------------------------------------------------------------

    /**
     * Ctrl+click on a class tag inside {@code <fx:context>} must resolve to the class
     * declaration in both standalone and embedded FXML.
     */
    @Test
    void navigationFromContextClassTagToClassDeclaration() {
        getFixture().configureByText("FxuNavClassTag.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import test.FxuContext?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.FxuView">
                  <fx:context>
                    <FxuContext/>
                  </fx:context>
                </BorderPane>
                """);
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = (XmlFile) getFixture().getFile();
            XmlTag xmlRoot = xmlFile.getRootTag();
            assertNotNull(xmlRoot, "Root tag must exist");
            XmlTag fxContextTag = null;
            for (XmlTag child : xmlRoot.getSubTags()) {
                if ("context".equals(child.getLocalName())) {
                    fxContextTag = child;
                    break;
                }
            }
            assertNotNull(fxContextTag, "Must find <fx:context> tag");

            XmlTag[] contextChildren = fxContextTag.getSubTags();
            assertTrue(contextChildren.length > 0, "Must have child tag inside <fx:context>");
            XmlTag classTag = contextChildren[0];

            PsiReference ref = classTag.getReference();
            assertNotNull(ref, "Class tag inside <fx:context> must have a reference");

            PsiElement resolved = ref.resolve();
            assertNotNull(resolved, "Reference on <FxuContext/> must resolve to a non-null element");
            assertInstanceOf(PsiClass.class, resolved,
                    "Reference must resolve to PsiClass, got: " + resolved.getClass().getSimpleName());
            PsiClass resolvedClass = (PsiClass) resolved;
            assertEquals("test.FxuContext", resolvedClass.getQualifiedName(),
                    "Reference must resolve to test.FxuContext");
        });
    }

    /**
     * Ctrl+click on a binding-path segment in {@code fx:context="$myContext"} must
     * resolve to the field declaration on the code-behind class.
     */
    @Test
    void navigationFromFxContextAttributeToCodeBehindField() {
        getFixture().configureByText("FxuNavContextAttr.fxml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="test.FxuView"
                            fx:context="$myContext"/>
                """);
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = (XmlFile) getFixture().getFile();
            XmlTag root = xmlFile.getRootTag();
            assertNotNull(root, "Root tag must exist");

            XmlAttribute fxContextAttr = root.getAttribute("fx:context");
            assertNotNull(fxContextAttr, "Must find fx:context attribute");
            XmlAttributeValue attrVal = fxContextAttr.getValueElement();
            assertNotNull(attrVal, "Must find fx:context attribute value");

            Fxml2BindingSegmentReference segRef = Arrays.stream(attrVal.getReferences())
                    .filter(r -> r instanceof Fxml2BindingSegmentReference s
                            && "myContext".equals(s.getCanonicalText()))
                    .map(r -> (Fxml2BindingSegmentReference) r)
                    .findFirst().orElse(null);
            assertNotNull(segRef,
                    "Must find Fxml2BindingSegmentReference for 'myContext' in fx:context attribute");

            PsiElement resolved = segRef.resolve();
            assertNotNull(resolved,
                    "Binding segment 'myContext' in fx:context attribute must resolve");
            assertInstanceOf(PsiField.class, resolved,
                    "Must resolve to a PsiField, got: " + resolved.getClass().getSimpleName());
            PsiField field = (PsiField) resolved;
            assertEquals("myContext", field.getName(),
                    "Resolved field must be named 'myContext'");
        });
    }
}
