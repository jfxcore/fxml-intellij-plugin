package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlProcessingInstruction;
import com.intellij.psi.xml.XmlTag;
import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.jfxcore.fxml.annotator.Fxml2PrefixDeclarationInspection;
import org.jfxcore.fxml.annotator.Fxml2UnusedImportsInspection;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@code <?prefix?>} processing-instruction support in FXML files.
 *
 * <p>Covers the following prefix-declaration requirements:
 * <ul>
 *   <li>Implicit built-in defaults: {@code @} -> ClassPathResource, {@code %} -> StaticResource</li>
 *   <li>Explicit {@code <?prefix X = ClassName?>} overrides and custom characters</li>
 *   <li>Inspection for invalid/duplicate prefix declarations</li>
  *   <li>Escaping: {@code \%greeting} is treated as a literal string, not a prefix invocation</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2PrefixDeclarationTest extends Fxml2TestBase {

    @BeforeAll
    void addComponentViewAnnotation() {
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

    @BeforeEach
    void enableInspections() {        getFixture().enableInspections(
                new Fxml2AttributeValueInspection(),
                new Fxml2PrefixDeclarationInspection(),
                new Fxml2UnusedImportsInspection());
    }

    @BeforeEach
    void addMarkupExtensionMocks() {
        getFixture().addClass("""
                package org.jfxcore.markup;
                public interface MarkupExtension {
                    interface Supplier<T> extends MarkupExtension {
                        @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
                        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                        @interface ReturnType {
                            Class<?>[] value() default {};
                        }
                        T get(MarkupContext context) throws Exception;
                    }
                }
                """);
        getFixture().addClass("package org.jfxcore.markup; public interface MarkupContext {}");
        getFixture().addClass("""
                package org.jfxcore.markup.resource;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                import javafx.beans.DefaultProperty;
                import javafx.beans.NamedArg;
                @DefaultProperty("value")
                public final class ClassPathResource implements MarkupExtension.Supplier<Object> {
                    public ClassPathResource(@NamedArg("value") String value) {}
                    @Override
                    @MarkupExtension.Supplier.ReturnType({String.class, java.net.URI.class, java.net.URL.class})
                    public Object get(MarkupContext context) { return null; }
                }
                """);
        getFixture().addClass("""
                package org.jfxcore.markup.resource;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                import javafx.beans.DefaultProperty;
                import javafx.beans.NamedArg;
                @DefaultProperty("key")
                public final class StaticResource<T> implements MarkupExtension.Supplier<T> {
                    public StaticResource(@NamedArg("key") String key) {}
                    @Override
                    public T get(MarkupContext context) { return null; }
                }
                """);
        // A custom extension for testing custom prefix declarations
        getFixture().addClass("""
                package test;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                public class MyExtension implements MarkupExtension.Supplier<String> {
                    @Override
                    public String get(MarkupContext context) { return null; }
                }
                """);
    }

    // -----------------------------------------------------------------------
    // 5a: Implicit built-in prefix defaults
    // -----------------------------------------------------------------------

    /**
     * {@code @icons/app.png} is resolved via the implicit {@code @} -> ClassPathResource
     * mapping, without any {@code <?import ClassPathResource?>} or {@code <?prefix?>} PI.
     * The value is valid on a String property -> no error.
     */
    @Test
    void builtinAtPrefix_classPathResource_noImport_noError() {
        // Note: no import for ClassPathResource: the built-in default applies
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="@icons/app.png"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code %greeting} is resolved via the implicit {@code %} -> StaticResource mapping,
     * without any {@code <?import StaticResource?>} or {@code <?prefix?>} PI.
     * StaticResource has no @ReturnType restriction -> no error for a String property.
     */
    @Test
    void builtinPercentPrefix_staticResource_noImport_noError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="%greeting"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // 5b: Explicit <?prefix?> overrides
    // -----------------------------------------------------------------------

    /**
     * An explicit {@code <?prefix ^ = test.MyExtension?>} declaration makes {@code ^value}
     * a valid markup extension invocation -> no error.
     */
    @Test
    void explicitPrefixDeclaration_customCharacter_noError() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.Label?>
                <?prefix ^ = test.MyExtension?>
                <Label xmlns="http://javafx.com/javafx"
                       xmlns:fx="http://jfxcore.org/fxml/2.0"
                       text="^greeting"/>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * An explicit {@code <?prefix @ = test.MyExtension?>} overrides the built-in
     * {@code @} -> ClassPathResource mapping. After the override, {@code @value} invokes
     * {@code MyExtension} instead. MyExtension has no @ReturnType -> accepted for any type.
     */
    @Test
    void explicitPrefixDeclaration_overridesBuiltin_noError() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.Label?>
                <?prefix @ = test.MyExtension?>
                <Label xmlns="http://javafx.com/javafx"
                       xmlns:fx="http://jfxcore.org/fxml/2.0"
                       opacity="@key"/>
                """);
        // @key maps to MyExtension (no @ReturnType) -> no error even on double opacity
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // 5c: Inspection: invalid prefix declarations
    // -----------------------------------------------------------------------

    /**
     * Declaring the same prefix character twice must produce a "Duplicate prefix declaration"
     * error on the second declaration.
     */
    @Test
    void explicitPrefixDeclaration_duplicateChar_error() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.Label?>
                <?prefix ^ = test.MyExtension?>
                <warning descr="Duplicate prefix declaration for '^'"><?prefix ^ = org.jfxcore.markup.resource.ClassPathResource?></warning>
                <Label xmlns="http://javafx.com/javafx"
                       xmlns:fx="http://jfxcore.org/fxml/2.0"
                       text="^key"/>
                """);
        getFixture().checkHighlighting(true, false, false, true);
    }

    /**
     * {@code !} is not in the documented set of allowed prefix characters.
     * Previously it was accepted by the blocklist-based validator; the allowlist-based
     * validator must reject it with an "invalid prefix character" error.
     */
    @Test
    void explicitPrefixDeclaration_nonWhitelistChar_error() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.Label?>
                <error descr="Invalid prefix character '!': valid prefix characters are @, %, &, ^, °, §, ?, ~"><?prefix ! = test.MyExtension?></error>
                <Label xmlns="http://javafx.com/javafx"
                       xmlns:fx="http://jfxcore.org/fxml/2.0"
                       text="hello"/>
                """);
        getFixture().checkHighlighting(false, false, false, true);
    }

    /**
     * A prefix character that is a letter ({@code A}) is not in the allowed set.
     */
    @Test
    void explicitPrefixDeclaration_letterChar_error() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.Label?>
                <error descr="Invalid prefix character 'A': valid prefix characters are @, %, &, ^, °, §, ?, ~"><?prefix A = test.MyExtension?></error>
                <Label xmlns="http://javafx.com/javafx"
                       xmlns:fx="http://jfxcore.org/fxml/2.0"
                       text="hello"/>
                """);
        getFixture().checkHighlighting(false, false, false, true);
    }

    /**
     * A character outside the allowed set ({@code {}) as prefix is invalid.
     */
    @Test
    void explicitPrefixDeclaration_reservedChar_error() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.Label?>
                <error descr="Invalid prefix character '{': valid prefix characters are @, %, &, ^, °, §, ?, ~"><?prefix { = test.MyExtension?></error>
                <Label xmlns="http://javafx.com/javafx"
                       xmlns:fx="http://jfxcore.org/fxml/2.0"
                       text="hello"/>
                """);
        getFixture().checkHighlighting(false, false, false, true);
    }

    /**
     * A prefix declaration referencing an unresolvable class must produce
     * a "Cannot resolve class" error.
     */
    @Test
    void explicitPrefixDeclaration_unresolvedClass_error() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.Label?>
                <error descr="Cannot resolve class 'test.NonExistentExtension'"><?prefix ^ = test.NonExistentExtension?></error>
                <Label xmlns="http://javafx.com/javafx"
                       xmlns:fx="http://jfxcore.org/fxml/2.0"
                       text="hello"/>
                """);
        getFixture().checkHighlighting(false, false, false, true);
    }

    // -----------------------------------------------------------------------
    // Escape mechanism
    // -----------------------------------------------------------------------

    /**
     * {@code \%greeting}: the backslash escape causes the value to be treated
     * as a literal string {@code %greeting}, not a markup-extension invocation.
     * No markup-extension errors must be produced even if {@code %} is a declared prefix.
     */
    @Test
    void escapePrefix_literalString_noError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="\\%greeting"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code \@/path}: the backslash escape produces the literal string {@code @/path}.
     * No markup-extension errors, even though {@code @} is a built-in prefix.
     */
    @Test
    void escapeAtPrefix_literalString_noError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="\\@/path/to/resource"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Unused-import: prefix declaration counts as a use of the imported class
    // -----------------------------------------------------------------------

    /**
     * A {@code <?import test.MyExtension?>} whose class is referenced only in a
     * {@code <?prefix ^ = MyExtension?>} declaration must NOT be reported as "Unused import".
     */
    @Test
    void importUsedOnlyByPrefixDeclaration_notReportedAsUnused() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.Label?>
                <?import test.MyExtension?>
                <?prefix ^ = MyExtension?>
                <Label xmlns="http://javafx.com/javafx"
                       xmlns:fx="http://jfxcore.org/fxml/2.0"
                       text="hello"/>
                """);
        // No "Unused import" warning expected for "<?import test.MyExtension?>".
        getFixture().checkHighlighting(false, false, false, true);
    }

    // -----------------------------------------------------------------------
    // Navigation
    // -----------------------------------------------------------------------

    /**
     * Ctrl+click on the class name in a {@code <?prefix ^ = test.MyExtension?>}
     * processing instruction navigates to {@code test.MyExtension}.
     */
    @Test
    void ctrlClickOnClassNameInPrefixDeclaration_navigatesToClass() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.Label?>
                <?prefix ^ = test.MyExtension?>
                <Label xmlns="http://javafx.com/javafx"
                       xmlns:fx="http://jfxcore.org/fxml/2.0"
                       text="^greeting"/>
                """);
        ReadAction.run(() -> {
            XmlProcessingInstruction pi = null;
            for (XmlProcessingInstruction candidate : PsiTreeUtil.findChildrenOfType(
                    getFixture().getFile(), XmlProcessingInstruction.class)) {
                if (candidate.getText().contains("prefix")
                        && candidate.getText().contains("MyExtension")) {
                    pi = candidate;
                    break;
                }
            }
            assertNotNull(pi, "Could not find <?prefix?> PI");

            String piText = pi.getText();
            int classNameOffset = piText.indexOf("test.MyExtension");
            assertTrue(classNameOffset >= 0, "Class name not found in PI text: " + piText);

            // Find a reference at a position inside the class name.
            PsiReference ref = pi.findReferenceAt(classNameOffset + 5);
            assertNotNull(ref, "No reference found on class name in <?prefix?> PI");

            PsiElement resolved = ref.resolve();
            assertNotNull(resolved, "Reference should resolve to MyExtension class");
            assertInstanceOf(PsiClass.class, resolved);
            assertEquals("test.MyExtension", ((PsiClass) resolved).getQualifiedName(),
                    "Class name in <?prefix?> PI should navigate to MyExtension");
        });
    }

    /**
     * Ctrl+click on the custom prefix character ({@code ^}) in {@code ^greeting}
     * navigates to {@code test.MyExtension}.
     */
    @Test
    void ctrlClickOnCustomPrefixChar_navigatesToExtensionClass() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.Label?>
                <?prefix ^ = test.MyExtension?>
                <Label xmlns="http://javafx.com/javafx"
                       xmlns:fx="http://jfxcore.org/fxml/2.0"
                       text="^greeting"/>
                """);
        ReadAction.run(() -> {
            XmlAttributeValue attrVal = null;
            for (XmlTag tag : PsiTreeUtil.findChildrenOfType(
                    getFixture().getFile(), XmlTag.class)) {
                XmlAttribute textAttr = tag.getAttribute("text");
                if (textAttr != null && "^greeting".equals(textAttr.getValue())) {
                    attrVal = textAttr.getValueElement();
                    break;
                }
            }
            assertNotNull(attrVal, "Could not find text=^greeting attribute value");

            // The prefix '^' is at offset 1 in attrVal.getText() (after opening quote).
            PsiReference prefixRef = attrVal.findReferenceAt(1);
            assertNotNull(prefixRef, "No reference found at '^' prefix character");

            PsiElement resolved = prefixRef.resolve();
            assertNotNull(resolved, "Reference at '^' should resolve to MyExtension class");
            assertInstanceOf(PsiClass.class, resolved);
            assertEquals("test.MyExtension", ((PsiClass) resolved).getQualifiedName(),
                    "^ prefix should navigate to MyExtension");
        });
    }

    // -----------------------------------------------------------------------
    // Embedded FXML with <?prefix?> override
    // -----------------------------------------------------------------------

    /**
     * In embedded FXML, {@code <?prefix % = test.MyExtension?>} must override the
     * built-in {@code %} -> StaticResource default so that
     * {@link Fxml2ImportResolver#parsePrefixMappings} returns {@code test.MyExtension}
     * for {@code %}.
     *
     * <p>The {@code <?prefix?>} PI appears inside the injection wrapper element in the
     * injected XML document (not in the prolog), so the resolver must look for it there.
     */
    @Test
    void parsePrefixMappings_findsOverrideInEmbeddedFxml2() {
        getFixture().configureByText("TestView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.control.Label;
                @ComponentView(\"""
                    <?prefix % = test.MyExtension?>
                    <Label text="%greeting"/>
                \""")
                public class TestView {}
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
            assertNotNull(cls, "No @ComponentView class found");

            XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(cls);
            assertNotNull(xmlFile, "No injected XmlFile found");

            java.util.Map<Character, String> mappings =
                    Fxml2ImportResolver.parsePrefixMappings(xmlFile);
            String mapped = mappings.get('%');
            assertEquals("test.MyExtension", mapped,
                    "<?prefix % = test.MyExtension?> in embedded FXML must override "
                    + "the default % -> StaticResource; got: " + mapped);
        });
    }

    /**
     * In embedded FXML, ctrl-clicking the {@code %} prefix character in a
     * {@code %key} attribute value must navigate to the class declared by an explicit
     * {@code <?prefix % = ClassName?>} PI, not to the built-in {@code StaticResource}.
     *
     * <p>The reference must be placed at offset&nbsp;1 in the attribute value text
     * (immediately after the opening quote), so the caret position of {@code %} resolves
     * to the declared extension class.
     */
    @Test
    void ctrlClickOnOverriddenPrefixChar_embeddedFxml2_navigatesToDeclaredClass() {
        getFixture().configureByText("TestView2.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.control.Label;
                @ComponentView(\"""
                    <?prefix % = test.MyExtension?>
                    <Label text="%greeting"/>
                \""")
                public class TestView2 {}
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
            assertNotNull(cls, "No @ComponentView class found");

            XmlFile xmlFile = Fxml2EmbeddedUtil.getInjectedXmlFile(cls);
            assertNotNull(xmlFile, "No injected XmlFile found");

            // Find the attribute value for text="%greeting"
            XmlAttributeValue attrVal = null;
            for (XmlTag tag : PsiTreeUtil.findChildrenOfType(xmlFile, XmlTag.class)) {
                XmlAttribute textAttr = tag.getAttribute("text");
                if (textAttr != null && "%greeting".equals(textAttr.getValue())) {
                    attrVal = textAttr.getValueElement();
                    break;
                }
            }
            assertNotNull(attrVal, "Could not find text=%greeting in injected XML");

            // The prefix '%' is at offset 1 in attrVal.getText() (after the opening quote).
            PsiReference prefixRef = attrVal.findReferenceAt(1);
            assertNotNull(prefixRef,
                    "No reference found at '%' prefix character (offset 1) in embedded FXML");

            PsiElement resolved = prefixRef.resolve();
            assertNotNull(resolved,
                    "Reference at '%' must resolve to the declared extension class (test.MyExtension), "
                    + "not null; the <?prefix?> PI override must be visible from embedded FXML");
            assertInstanceOf(PsiClass.class, resolved);
            assertEquals("test.MyExtension", ((PsiClass) resolved).getQualifiedName(),
                    "% prefix override in embedded FXML must navigate to test.MyExtension, "
                    + "not to the default StaticResource");
        });
    }

    /**
     * Renaming the class declared in a {@code <?prefix ^ = test.MyExtension?>} PI must NOT
     * rename the {@code ^} prefix character in attribute values such as {@code ^greeting}.
     * The {@code ^} character is a symbolic alias; it carries no text that reflects the
     * class name and must therefore be excluded from the set of references updated by the
     * rename refactoring.
     */
    @Test
    void renamePrefixExtensionClass_doesNotRenamePrefixCharInAttributeValues() {
        getFixture().configureByText("TestView3.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.Label?>
                <?prefix ^ = test.MyExtension?>
                <Label xmlns="http://javafx.com/javafx"
                       xmlns:fx="http://jfxcore.org/fxml/2.0"
                       text="^greeting"/>
                """);

        PsiClass myExtCls = ReadAction.compute(() ->
                com.intellij.psi.JavaPsiFacade.getInstance(getFixture().getProject())
                        .findClass("test.MyExtension",
                                com.intellij.psi.search.GlobalSearchScope.allScope(
                                        getFixture().getProject())));
        assertNotNull(myExtCls, "Could not find test.MyExtension");

        EdtTestUtil.runInEdtAndWait(() -> getFixture().renameElement(myExtCls, "MyRenamedExtension"));

        String text = getFixture().getEditor().getDocument().getText();
        // The <?prefix?> PI itself should be updated (the class name text appears there literally).
        assertTrue(text.contains("MyRenamedExtension"),
                "Rename should update the class name in the <?prefix?> PI, document: " + text);
        // The attribute value prefix char must remain unchanged.
        assertTrue(text.contains("text=\"^greeting\""),
                "Rename must NOT change the '^' prefix character in attribute values, document: " + text);
    }

    /**
     * In embedded FXML, renaming the class declared in a {@code <?prefix % = MyExtension?>} PI
     * must NOT rename the {@code %} prefix character in attribute values such as {@code %greeting}.
     * The {@code %} is a symbolic alias for the class, not a textual occurrence of the class name,
     * and must therefore be excluded from the set of references updated by the rename refactoring.
     */
    @Test
    void renamePrefixExtensionClass_embeddedFxml2_doesNotRenamePrefixCharInAttributeValues() {
        getFixture().configureByText("TestView4.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.control.Label;
                @ComponentView(\"""
                    <?prefix % = test.MyExtension?>
                    <Label text="%greeting"/>
                \""")
                public class TestView4 {}
                """);

        getFixture().doHighlighting();

        PsiClass myExtCls = ReadAction.compute(() ->
                com.intellij.psi.JavaPsiFacade.getInstance(getFixture().getProject())
                        .findClass("test.MyExtension",
                                com.intellij.psi.search.GlobalSearchScope.allScope(
                                        getFixture().getProject())));
        assertNotNull(myExtCls, "Could not find test.MyExtension");

        EdtTestUtil.runInEdtAndWait(() -> getFixture().renameElement(myExtCls, "MyRenamedExtension"));

        String text = getFixture().getEditor().getDocument().getText();
        // The attribute value prefix char must remain unchanged after rename.
        // Renaming MyExtension must not corrupt "%greeting" into something like "%MyRenamedExtension".
        assertTrue(text.contains("\"%greeting\""),
                "Rename must NOT change the '%' prefix character in attribute values, document: " + text);
    }
}
