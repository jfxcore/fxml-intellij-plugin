package org.jfxcore.fxml;

import com.intellij.lang.properties.references.PropertyReferenceBase;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Arrays;
import java.util.List;

import static com.intellij.codeInsight.navigation.impl.GtdKt.gotoDeclaration;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the built-in resource markup extensions (ClassPathResource, StaticResource, DynamicResource)
 * and their compact prefix-shorthand notations ({@code @x} and {@code %x}).
 *
 * <p>Covers the following resource markup extension requirements:
 * <ul>
 *   <li>{@code @x} compact notation maps to ClassPathResource</li>
 *   <li>{@code %x} compact notation maps to StaticResource</li>
 *   <li>DynamicResource and {@code formatArguments} support</li>
 *   <li>Raw markup extension invocations bypass @ReturnType check</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings("UnstableApiUsage")
class Fxml2ResourceMarkupExtensionTest extends Fxml2TestBase {

    private void addMyListMock() {
        getFixture().addClass("""
                package test;
                import java.util.List;
                import javafx.scene.layout.Pane;
                public class MyList extends Pane {
                    public List<Object> getValues() { return null; }
                }
                """);
    }

    private XmlAttributeValue findAttributeValueStartingWith(String prefix) {
        return PsiTreeUtil.findChildrenOfType(
                        getFixture().getFile(), XmlAttributeValue.class).stream()
                .filter(attributeValue -> attributeValue.getValue().startsWith(prefix))
                .findFirst()
                .orElse(null);
    }

    private static List<PropertyReferenceBase> propertyReferences(
            XmlAttributeValue attributeValue) {
        return Arrays.stream(attributeValue.getReferences())
                .filter(PropertyReferenceBase.class::isInstance)
                .map(PropertyReferenceBase.class::cast)
                .toList();
    }

    private static List<String> referenceTexts(
            XmlAttributeValue attributeValue, List<PropertyReferenceBase> references) {
        return references.stream()
                .map(reference -> reference.getRangeInElement().substring(attributeValue.getText()))
                .toList();
    }

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2AttributeValueInspection());
    }

    @BeforeEach
    void addMarkupExtensionMocks() {
        getFixture().addClass("""
                package org.jfxcore.markup;
                public interface MarkupExtension {
                    interface PropertyConsumer<T> extends MarkupExtension {}
                    interface ReadOnlyPropertyConsumer<T> extends MarkupExtension {}
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
        getFixture().addClass("""
                package org.jfxcore.markup;
                public interface MarkupContext {}
                """);

        // ClassPathResource: applicable to String, URI, URL (has @ReturnType restriction)
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

        // StaticResource<T>: raw generic supplier, no @ReturnType, so raw form accepts any type
        getFixture().addClass("""
                package org.jfxcore.markup.resource;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                import javafx.beans.DefaultProperty;
                import javafx.beans.NamedArg;
                @DefaultProperty("key")
                public final class StaticResource<T> implements MarkupExtension.Supplier<T> {
                    public StaticResource(@NamedArg("key") String key) {}
                    public StaticResource(@NamedArg("key") String key,
                                         @NamedArg("formatArguments") Object... formatArguments) {}
                    @Override
                    public T get(MarkupContext context) { return null; }
                }
                """);
    }

    // -----------------------------------------------------------------------
    // 4a: @x compact notation (ClassPathResource)
    // -----------------------------------------------------------------------

    /**
     * {@code @path/to/image.jpg} on a URL property: ClassPathResource supports URL -> no error.
     */
    @Test
    void classPathResource_urlProperty_noError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.image.Image\njavafx.scene.image.ImageView",
                """
                  <ImageView>
                    <image><Image url="@/icons/icon.png"/></image>
                  </ImageView>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code @path} on a {@code String} property: ClassPathResource supports String -> no error.
     */
    @Test
    void classPathResource_stringProperty_noError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="@/messages/hello.txt"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** A single resource value supplies one item of a collection-valued property. */
    @Test
    void classPathResource_collectionItemType_noError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.BorderPane",
                """
                  <BorderPane stylesheets="@styles.css"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code @path} on an {@code opacity} property (double): ClassPathResource supports only
     * String/URI/URL, not double -> type-mismatch error.
     */
    @Test
    void classPathResource_incompatibleProperty_error() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label opacity="%s"/>
                """.formatted(error(
                        "Markup extension 'ClassPathResource' is not applicable to 'opacity': supported types are String, URI, URL",
                        "@path"))
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Long-form {@code {ClassPathResource path/to/image.jpg}}: must resolve the class without error.
     */
    @Test
    void classPathResource_longForm_noError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label\norg.jfxcore.markup.resource.ClassPathResource",
                """
                  <Label text="{ClassPathResource /messages/hello.txt}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Ctrl+click on the {@code @} prefix character navigates to the
     * {@code ClassPathResource} class.
     */
    @Test
    void classPathResource_ctrlClickNavigation() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="@/messages/hello.txt"/>
                """
        ));
        ReadAction.run(() -> {
            // Find the attribute value "@/messages/hello.txt"
            com.intellij.psi.xml.XmlAttributeValue attrVal = null;
            for (com.intellij.psi.xml.XmlTag tag : com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                    getFixture().getFile(), com.intellij.psi.xml.XmlTag.class)) {
                com.intellij.psi.xml.XmlAttribute textAttr = tag.getAttribute("text");
                if (textAttr != null && "@/messages/hello.txt".equals(textAttr.getValue())) {
                    attrVal = textAttr.getValueElement();
                    break;
                }
            }
            assertNotNull(attrVal, "Could not find text=@/messages/hello.txt attribute value");

            // The prefix '@' is at offset 1 in the attrVal text (after opening quote).
            PsiReference prefixRef = attrVal.findReferenceAt(1);
            assertNotNull(prefixRef, "No reference found at '@' prefix character");

            PsiElement resolved = prefixRef.resolve();
            assertNotNull(resolved, "Reference at '@' should resolve to ClassPathResource class");
            assertInstanceOf(PsiClass.class, resolved,
                    "Reference at '@' should resolve to PsiClass");
            assertEquals("org.jfxcore.markup.resource.ClassPathResource",
                    ((PsiClass) resolved).getQualifiedName(),
                    "@ prefix should navigate to ClassPathResource");
        });
    }

    /**
     * {@code \@/path}: backslash escape: treated as the literal string {@code @/path},
     * not a ClassPathResource invocation -> no markup extension errors.
     */
    @Test
    void escapePrefix_atNotation_literalString_noError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="\\@/some/path"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // 4b: %x compact notation (StaticResource)
    // -----------------------------------------------------------------------

    /**
     * {@code %greeting} on a {@code String} property: StaticResource raw form -> no @ReturnType
     * restriction -> no error.
     */
    @Test
    void staticResource_percentNotation_stringProperty_noError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="%greeting"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code %key} on an {@code Orientation} property: StaticResource is a raw generic
     * {@code Supplier<T>} with no {@code @ReturnType} restriction.  In raw form the compiler
     * treats the return type as the "bottom type" (compatible with any type), so the plugin
     * must also accept it without error.
     */
    @Test
    void staticResource_percentNotation_anyType_noError() {
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class TestView extends BorderPane {}
                """);
        // Node.opacity is a double: StaticResource raw form must be accepted for any type.
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label opacity="%opacityKey"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code %greeting} with {@code ; formatArguments=Jane, Doe}: the named parameter
     * {@code formatArguments} must be recognized without error.
     */
    @Test
    void staticResource_formatArguments_literalList_noError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="%greetingWithArgs; formatArguments=Jane, Doe, 1234.5"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A parameterized prefix-notation extension consumes the remaining comma-separated values,
     * even when the attribute itself targets a list.  All three format arguments therefore belong
     * to {@code StaticResource}; they are not additional items of {@code MyList.values}.
     */
    @Test
    void staticResource_formatArguments_areGreedyInsideListProperty() {
        addMyListMock();
        getFixture().configureByText("TestView.fxml", fxml(
                "test.MyList",
                """
                  <MyList values="%formattedMessage; formatArguments=firstName, lastName, @fallback-message.txt"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void staticResource_formatArguments_resourceKeyResolvesInsideListProperty() {
        addMyListMock();
        getFixture().addFileToProject("messages.properties",
                "formattedMessage=Hello {0} {1}. Fallback resource: {2}");
        getFixture().configureByText("TestView.fxml", fxml(
                "test.MyList",
                """
                  <MyList values="%formattedMessage; formatArguments=firstName, lastName, @fallback-message.txt"/>
                """
        ));

        ReadAction.run(() -> {
            XmlAttributeValue attributeValue =
                    findAttributeValueStartingWith("%formattedMessage");
            assertNotNull(attributeValue);
            var references = propertyReferences(attributeValue);
            assertFalse(references.isEmpty());
            assertTrue(references.stream().allMatch(reference -> reference.resolve() != null),
                    "Every property-key reference must resolve only the positional key: "
                            + references);
        });
    }

    @Test
    void staticResource_commaCompletesPrefixExpressionAndResumesValueMode() {
        addMyListMock();
        getFixture().addFileToProject("messages.properties", """
                primaryMessage=Primary
                secondaryMessage=Secondary
                """);
        getFixture().configureByText("TestView.fxml", fxml(
                "test.MyList",
                """
                  <MyList values="%primaryMessage, %secondaryMessage"/>
                """
        ));

        ReadAction.run(() -> {
            XmlAttributeValue attributeValue = findAttributeValueStartingWith("%primaryMessage");
            assertNotNull(attributeValue);
            var references = propertyReferences(attributeValue);
            assertEquals(List.of("primaryMessage", "secondaryMessage"),
                    referenceTexts(attributeValue, references));
            assertTrue(references.stream().allMatch(reference -> reference.resolve() != null));
        });
    }

    @Test
    void staticResource_closingBraceCompletesExpressionAndResumesValueMode() {
        addMyListMock();
        getFixture().addFileToProject("messages.properties", """
                primaryMessage=Primary {0} {1}
                secondaryMessage=Secondary
                """);
        getFixture().configureByText("TestView.fxml", fxml(
                "test.MyList\norg.jfxcore.markup.resource.StaticResource",
                """
                  <MyList values="{StaticResource primaryMessage; formatArguments=firstName, lastName}, %secondaryMessage"/>
                """
        ));

        ReadAction.run(() -> {
            XmlAttributeValue attributeValue =
                    findAttributeValueStartingWith("{StaticResource primaryMessage");
            assertNotNull(attributeValue);
            var references = propertyReferences(attributeValue);
            assertEquals(List.of("primaryMessage", "secondaryMessage"),
                    referenceTexts(attributeValue, references));
            assertTrue(references.stream().allMatch(reference -> reference.resolve() != null));
        });
    }

    @Test
    void staticResource_formatArguments_ctrlHoverCoversParameterName() {
        addMyListMock();
        getFixture().configureByText("TestView.fxml", fxml(
                "test.MyList",
                """
                  <MyList values="%formattedMessage; formatArg<caret>uments=firstName, lastName, @fallback-message.txt"/>
                """
        ));

        TextRange hoverRange = ReadAction.compute(() -> {
            var navigationData = gotoDeclaration(
                    getFixture().getFile(), getFixture().getCaretOffset());
            if (navigationData == null) {
                return null;
            }
            var ctrlMouseData = navigationData.ctrlMouseData();
            if (ctrlMouseData == null || ctrlMouseData.getRanges().isEmpty()) {
                return null;
            }
            return ctrlMouseData.getRanges().getFirst();
        });
        assertNotNull(hoverRange);
        assertEquals("formatArguments", hoverRange.substring(getFixture().getFile().getText()));
    }

    /**
     * {@code <StaticResource key="greeting" formatArguments="Jane, Doe, 1234.5"/>} as a
     * direct tag: the {@code formatArguments} attribute has type {@code Object[]}, and a
     * comma-separated list is valid for it: no "invalid value" error.
     */
    @Test
    void staticResource_formatArguments_asTagAttribute_noError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label\norg.jfxcore.markup.resource.StaticResource",
                """
                  <Label>
                    <text>
                      <StaticResource key="greeting" formatArguments="Jane, Doe, 1234.5"/>
                    </text>
                  </Label>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Ctrl+click on the {@code %} prefix character navigates to the
     * {@code StaticResource} class.
     */
    @Test
    void staticResource_ctrlClickNavigation() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="%greeting"/>
                """
        ));
        ReadAction.run(() -> {
            com.intellij.psi.xml.XmlAttributeValue attrVal = null;
            for (com.intellij.psi.xml.XmlTag tag : com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                    getFixture().getFile(), com.intellij.psi.xml.XmlTag.class)) {
                com.intellij.psi.xml.XmlAttribute textAttr = tag.getAttribute("text");
                if (textAttr != null && "%greeting".equals(textAttr.getValue())) {
                    attrVal = textAttr.getValueElement();
                    break;
                }
            }
            assertNotNull(attrVal, "Could not find text=%greeting attribute value");

            // The prefix '%' is at offset 1 in the attrVal text (after opening quote).
            PsiReference prefixRef = attrVal.findReferenceAt(1);
            assertNotNull(prefixRef, "No reference found at '%' prefix character");

            PsiElement resolved = prefixRef.resolve();
            assertNotNull(resolved, "Reference at '%' should resolve to StaticResource class");
            assertInstanceOf(PsiClass.class, resolved);
            assertEquals("org.jfxcore.markup.resource.StaticResource",
                    ((PsiClass) resolved).getQualifiedName(),
                    "% prefix should navigate to StaticResource");
        });
    }

    /**
     * {@code \%greeting}: backslash escape: treated as the literal string {@code %greeting},
     * not a StaticResource invocation -> no markup extension errors.
     */
    @Test
    void escapePrefix_percentNotation_literalString_noError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="\\%greeting"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // 4d: Raw markup extension invocations bypass @ReturnType check
    // -----------------------------------------------------------------------

    /**
     * {@code {StaticResource greeting}} (raw form, no type argument): StaticResource has no
     * {@code @ReturnType}, so the check is skipped and the value is accepted for any type.
     */
    @Test
    void staticResource_rawBraceForm_anyType_noError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label\norg.jfxcore.markup.resource.StaticResource",
                """
                  <Label opacity="{StaticResource opacityKey}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }
}
