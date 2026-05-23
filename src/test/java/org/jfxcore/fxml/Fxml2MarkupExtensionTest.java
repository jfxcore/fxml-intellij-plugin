package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.jfxcore.fxml.lang.Fxml2BindingSegmentReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for custom markup extensions in FXML2 attribute values and element notation.
 *
 * <p>A custom markup extension is a class that implements
 * {@code org.jfxcore.markup.MarkupExtension} and is invoked with the
 * {@code {ClassName}} syntax in attribute values, e.g.:
 * <pre>{@code
 * <Label text="{MyExtension}"/>
 * }</pre>
 *
 * <p>In element notation, the extension appears as a child of a property element:
 * <pre>{@code
 * <Label>
 *     <text><MyExtension/></text>
 * </Label>
 * }</pre>
 *
 * <p>Covers the following documentation features from {@code markup-extension.md}:
 * <ul>
 *   <li>5.1: Attribute notation {@code {ClassName}} and element notation</li>
 *   <li>5.4: Parameter configuration via {@code @NamedArg}, getter/setter</li>
 *   <li>5.5: {@code @DefaultProperty} on a markup extension class</li>
 *   <li>5.6: {@code @ReturnType} annotation for compile-time type checking</li>
 *   <li>Navigation: Ctrl+click navigates to the class; Find Usages finds FXML2 use sites</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2MarkupExtensionTest extends Fxml2TestBase {

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2AttributeValueInspection());
    }

    @BeforeEach
    void addCodeBehind() {
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public abstract class TestViewBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().addClass("""
                package test;
                public class TestView extends TestViewBase {}
                """);

        // Mock the org.jfxcore.markup.MarkupExtension interface (not on classpath normally)
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
                    }
                    interface BooleanSupplier extends MarkupExtension {}
                    interface IntSupplier extends MarkupExtension {}
                    interface LongSupplier extends MarkupExtension {}
                    interface FloatSupplier extends MarkupExtension {}
                    interface DoubleSupplier extends MarkupExtension {}
                }
                """);
        getFixture().addClass("""
                package org.jfxcore.markup;
                public interface MarkupContext {}
                """);

        // A PropertyConsumer markup extension with @NamedArg param + getter/setter
        getFixture().addClass("""
                package test;
                import javafx.beans.NamedArg;
                import javafx.beans.property.Property;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                public class MyExtension implements MarkupExtension.PropertyConsumer<String> {
                    private int param3;
                    public MyExtension() {}
                    public MyExtension(@NamedArg("param1") int param1) {}
                    public void setParam3(int v) { param3 = v; }
                    public int getParam3() { return param3; }
                    @Override
                    public void accept(Property<String> property, MarkupContext context) {}
                }
                """);

        // A Supplier markup extension with @DefaultProperty and @ReturnType restriction
        getFixture().addClass("""
                package test;
                import javafx.beans.NamedArg;
                import javafx.beans.DefaultProperty;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                @DefaultProperty("value")
                public class ResourceExtension implements MarkupExtension.Supplier<Object> {
                    private final String value;
                    public ResourceExtension(@NamedArg("value") String value) { this.value = value; }
                    @Override
                    @MarkupExtension.Supplier.ReturnType({String.class, java.net.URL.class})
                    public Object get(MarkupContext context) { return value; }
                }
                """);

        // A class that does NOT implement MarkupExtension (used for negative test)
        getFixture().addClass("""
                package test;
                public class NotAnExtension {}
                """);

        // A Supplier<String> without @ReturnType restriction
        getFixture().addClass("""
                package test;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                public class StringSupplier implements MarkupExtension.Supplier<String> {
                    @Override
                    public String get(MarkupContext context) { return "hello"; }
                }
                """);

        // A generic PropertyConsumer with a @NamedArg "key" parameter: used to test that
        // {GenericExtension<String> key=hello} (literal '<') and
        // {GenericExtension&lt;String&gt; key=hello} (XML-escaped) both resolve correctly.
        getFixture().addClass("""
                package test;
                import javafx.beans.NamedArg;
                import javafx.beans.property.Property;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                public class GenericExtension<T> implements MarkupExtension.PropertyConsumer<T> {
                    private final String key;
                    public GenericExtension(@NamedArg("key") String key) { this.key = key; }
                    @Override
                    public void accept(Property<T> property, MarkupContext context) {}
                }
                """);

        // A dual-interface extension implementing BOTH PropertyConsumer<String> AND Supplier<Object>
        // with @ReturnType({URL.class}).  When used on a Property-typed attribute, PropertyConsumer
        // takes precedence and the @ReturnType incompatibility must NOT be reported as an error.
        getFixture().addClass("""
                package test;
                import javafx.beans.property.Property;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                public class DualExtension
                        implements MarkupExtension.PropertyConsumer<String>,
                                   MarkupExtension.Supplier<Object> {
                    @Override
                    public void accept(Property<String> property, MarkupContext context) {}
                    @Override
                    @MarkupExtension.Supplier.ReturnType({java.net.URL.class})
                    public Object get(MarkupContext context) { return null; }
                }
                """);
    }

    // -----------------------------------------------------------------------
    // 5.1: Attribute notation: basic usage
    // -----------------------------------------------------------------------

    /**
     * 5.1: A class implementing {@code MarkupExtension} can be used in attribute notation
     * {@code {ClassName}} without any error.
     */
    @Test
    void validMarkupExtensionInAttributeNotation_noError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.MyExtension",
                """
                  <Label text="{MyExtension}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A backslash escape before a markup extension invocation ({@code \{ClassName}}) produces
     * a literal string value and must not be treated as a markup extension, no error even
     * if {@code ClassName} is unknown.
     */
    @Test
    void backslashEscapeProducesLiteralString_noError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="\\{UnknownClass}"/>
                """
        ));
        // \{UnknownClass} is a literal string: no markup extension processing, no error
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A markup extension invocation with an unresolvable class name reports
     * "Cannot resolve symbol 'ClassName'".
     */
    @Test
    void unresolvableMarkupExtensionClass_error() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="{%s}"/>
                """.formatted(error("Cannot resolve symbol 'UnknownExt'", "UnknownExt"))
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A class that does NOT implement {@code org.jfxcore.markup.MarkupExtension} used in
     * markup-extension notation reports "'ClassName' is used like a markup extension".
     */
    @Test
    void classNotImplementingMarkupExtension_unexpectedMarkupExtensionError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.NotAnExtension",
                """
                  <Label text="{%s}"/>
                """.formatted(error("'NotAnExtension' is used like a markup extension", "NotAnExtension"))
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A non-fx namespace prefix like {@code {ns:ClassName}} produces
     * "Unknown XML namespace: ns".
     */
    @Test
    void unknownXmlNamespace_error() {
        // The error spans the entire attribute value including quotes ({errorOffset=0, length=fullValue})
        // so the annotation uses attrRange which includes the surrounding quotes.
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text=%s/>
                """.formatted(error("Unknown XML namespace: ns", "\"{ns:MyExtension}\""))
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // 5.1: PropertyConsumer and Supplier both tested
    // -----------------------------------------------------------------------

    /**
     * 5.1: A {@code PropertyConsumer} extension works in attribute notation without error.
     */
    @Test
    void propertyConsumerExtensionInAttributeNotation_noError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.MyExtension",
                """
                  <Label text="{MyExtension}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * 5.1: A {@code Supplier} extension works in attribute notation without error.
     */
    @Test
    void supplierExtensionInAttributeNotation_noError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.StringSupplier",
                """
                  <Label text="{StringSupplier}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // 5.1: Element notation
    // -----------------------------------------------------------------------

    /**
     * 5.1: A {@code PropertyConsumer} markup extension can be used in element notation.
     * Doc example:
     * <pre>{@code
     * <Label>
     *     <text>
     *         <MyExtension/>
     *     </text>
     * </Label>
     * }</pre>
     */
    @Test
    void propertyConsumerExtensionInElementNotation_noError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.MyExtension",
                """
                  <Label>
                      <text>
                          <MyExtension/>
                      </text>
                  </Label>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * 5.1: A {@code Supplier} markup extension can be used in element notation.
     */
    @Test
    void supplierExtensionInElementNotation_noError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.StringSupplier",
                """
                  <Label>
                      <text>
                          <StringSupplier/>
                      </text>
                  </Label>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * 5.1: A markup extension with additional parameters works in attribute notation without error.
     */
    @Test
    void validMarkupExtensionWithParams_noError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.MyExtension",
                """
                  <Label text="{MyExtension param1=42}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // 5.4: Parameter configuration via @NamedArg, JavaFX properties, getter/setter
    // -----------------------------------------------------------------------

    /**
     * 5.4: A markup extension with a valid {@code @NamedArg} parameter works without error.
     */
    @Test
    void namedArgParam_noError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.MyExtension",
                """
                  <Label text="{MyExtension param1=42}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * 5.4: A getter/setter pair parameter (e.g. {@code getParam3}/{@code setParam3})
     * can be used as a markup extension parameter without error.
     */
    @Test
    void getterSetterParam_noError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.MyExtension",
                """
                  <Label text="{MyExtension param3=5}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * 5.4: An unknown parameter name reports "Unknown markup extension parameter 'X'".
     */
    @Test
    void unknownParam_error() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.MyExtension",
                """
                  <Label text="{MyExtension %s=value}"/>
                """.formatted(error("Unknown markup extension parameter 'unknownParam'", "unknownParam"))
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * 5.4: Multiple valid parameters (from both constructor @NamedArg and setter) work without error.
     */
    @Test
    void multipleValidParams_noError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.MyExtension",
                """
                  <Label text="{MyExtension param1=5 param3=10}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // 5.5: @DefaultProperty
    // -----------------------------------------------------------------------

    /**
     * 5.5: When the extension class has {@code @DefaultProperty("value")},
     * the value can be passed without naming the property.
     * Doc example: {@code {Resource /path/to/image.jpg}}.
     */
    @Test
    void defaultPropertyOmitsPropertyName_noError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.ResourceExtension",
                """
                  <Label id="{ResourceExtension /path/to/image.jpg}"/>
                """
        ));
        // The positional value uses @DefaultProperty, no error
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * 5.5: Explicit property name can always be used even with {@code @DefaultProperty}.
     */
    @Test
    void defaultPropertyWithExplicitName_noError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.ResourceExtension",
                """
                  <Label id="{ResourceExtension value=/path/to/image.jpg}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // 5.6: @ReturnType validation
    // -----------------------------------------------------------------------

    /**
     * 5.6: A {@code Supplier} extension with {@code @ReturnType({String.class, URL.class})}
     * applied to a {@code String} property is valid: {@code String} is in the allowed types.
     */
    @Test
    void returnTypeCompatibleWithStringProperty_noError() {
        // Label.text is String: ResourceExtension declares @ReturnType({String.class, URL.class})
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.ResourceExtension",
                """
                  <Label text="{ResourceExtension}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * 5.6: A {@code Supplier} extension with {@code @ReturnType({String.class, URL.class})}
     * applied to an incompatible property type (e.g. {@code boolean}) reports an error.
     */
    @Test
    void returnTypeIncompatibleWithPropertyType_error() {
        // Label.disable is boolean: ResourceExtension @ReturnType is {String, URL}: not compatible
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.ResourceExtension",
                """
                  <Label disable="{%s}"/>
                """.formatted(error(
                        "Markup extension 'ResourceExtension' is not applicable to 'disable':"
                        + " supported types are String, URL",
                        "ResourceExtension"))
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * 5.6: A {@code Supplier} extension without {@code @ReturnType} has no restriction
     * and can be applied to any property without a type error.
     */
    @Test
    void supplierWithoutReturnType_noRestriction_noError() {
        // StringSupplier has no @ReturnType: no restriction
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.StringSupplier",
                """
                  <Label text="{StringSupplier}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // 5.7: Dual-interface: PropertyConsumer + Supplier with @ReturnType
    // -----------------------------------------------------------------------

    /**
     * 5.7: When an extension implements BOTH {@code PropertyConsumer<T>} AND
     * {@code Supplier<Object>} with a {@code @ReturnType} that would be incompatible
     * with the target attribute type, but the target attribute <em>has</em> an underlying
     * JavaFX property method ({@code xProperty()}), {@code PropertyConsumer} takes
     * precedence and NO {@code @ReturnType} error must be reported.
     *
     * <p>In the test: {@code DualExtension} declares {@code @ReturnType({URL.class})},
     * which is incompatible with {@code Label.text} (a {@code String}).
     * But {@code Label.textProperty()} exists, so {@code PropertyConsumer<String>} wins.
     */
    @Test
    void dualInterfaceExtensionOnPropertyAttribute_propertyConsumerWins_noError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.DualExtension",
                """
                  <Label text="{DualExtension}"/>
                """
        ));
        // Label.text has textProperty() -> PropertyConsumer<String> takes precedence.
        // The Supplier @ReturnType({URL.class}) is NOT checked, no error.
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Navigation: Ctrl+click and Find Usages
    // -----------------------------------------------------------------------


    /**
     * Ctrl+click on the extension class name in {@code {MyExtension}} navigates to
     * the {@link PsiClass} for {@code MyExtension}.
     */
    @Test
    void attributeNotationExtensionName_resolvesToClass() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.MyExtension",
                """
                  <Label text="{MyExtension}"/>
                """
        ));

        ReadAction.run(() -> {
            // Find the attribute value for text="{MyExtension}"
            XmlAttributeValue attrVal = null;
            for (XmlTag tag : com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                    getFixture().getFile(), XmlTag.class)) {
                XmlAttribute textAttr = tag.getAttribute("text");
                if (textAttr != null && "{MyExtension}".equals(textAttr.getValue())) {
                    attrVal = textAttr.getValueElement();
                    break;
                }
            }
            assertNotNull(attrVal, "Could not find text={MyExtension} attribute value");

            // Find the reference that resolves to the class
            final XmlAttributeValue finalAttrVal = attrVal;
            PsiReference classRef = Arrays.stream(finalAttrVal.getReferences())
                    .filter(r -> {
                        PsiElement resolved = r.resolve();
                        return resolved instanceof PsiClass c
                                && "test.MyExtension".equals(c.getQualifiedName());
                    })
                    .findFirst().orElse(null);

            assertNotNull(classRef,
                    "No reference to test.MyExtension found in text=\"{MyExtension}\"");
            PsiElement resolved = classRef.resolve();
            assertInstanceOf(PsiClass.class, resolved);
            assertEquals("test.MyExtension", ((PsiClass) resolved).getQualifiedName());
        });
    }

    /**
     * Find Usages of the markup extension class discovers the FXML2 attribute value use site.
     */
    @Test
    void findUsagesOfExtensionClass_showsFxmlUseSite() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.MyExtension",
                """
                  <Label text="{MyExtension}"/>
                """
        ));

        PsiClass myExtensionClass = ReadAction.compute(() -> {
            com.intellij.psi.JavaPsiFacade facade =
                    com.intellij.psi.JavaPsiFacade.getInstance(getFixture().getProject());
            return facade.findClass("test.MyExtension",
                    com.intellij.psi.search.GlobalSearchScope.allScope(getFixture().getProject()));
        });
        assertNotNull(myExtensionClass, "test.MyExtension class not found");

        Collection<PsiReference> usages = ReadAction.compute(() ->
                ReferencesSearch.search(myExtensionClass,
                        com.intellij.psi.search.GlobalSearchScope.allScope(getFixture().getProject()))
                        .findAll());

        assertTrue(
                ReadAction.compute(() ->
                        usages.stream().anyMatch(ref ->
                                ref.getElement().getContainingFile().getName().endsWith(".fxml"))),
                "Find Usages of test.MyExtension must include the FXML2 file use site");
    }

    // -----------------------------------------------------------------------
    // Generic type arguments  (typeArguments.md)
    // The FXML 2.0 compiler accepts {ClassName<TypeArg>} with a literal '<' as a
    // non-standard convenience form; the standard XML form uses &lt; / &gt; escapes.
    // In both cases IntelliJ decodes XML entities before handing the value to the
    // plugin, so both arrive at the parser as a literal '<' and are treated identically.
    // -----------------------------------------------------------------------

    /**
     * An import used <em>only</em> via a markup extension invocation with a literal
     * {@code <} type argument ({@code {GenericExtension<String> ...}}) must NOT be reported
     * as "Unused import".
     *
     * <p>The import optimizer must scan markup-extension attribute values when collecting
     * used simple names, so that classes referenced only in this position are not
     * incorrectly flagged as unused.
     */
    @Test
    void importUsedOnlyViaMarkupExtension_literalAngle_notUnused() {
        getFixture().enableInspections(new org.jfxcore.fxml.annotator.Fxml2UnusedImportsInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.GenericExtension",
                """
                  <Label text="{GenericExtension<String> key=hello}"/>
                """
        ));
        // GenericExtension import must NOT produce "Unused import" (warnings=true).
        getFixture().checkHighlighting(true, false, false);
    }

    /**
     * An import used <em>only</em> via a markup extension invocation with an XML-escaped
     * type argument ({@code {GenericExtension&lt;String&gt; ...}}) must NOT be reported as
     * "Unused import".
     */
    @Test
    void importUsedOnlyViaMarkupExtension_escapedAngle_notUnused() {
        getFixture().enableInspections(new org.jfxcore.fxml.annotator.Fxml2UnusedImportsInspection());
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.GenericExtension",
                """
                  <Label text="{GenericExtension&lt;String&gt; key=hello}"/>
                """
        ));
        getFixture().checkHighlighting(true, false, false);
    }

    /**
     * A markup extension invocation with a literal {@code <} generic type argument
     * ({@code {GenericExtension<String> key=hello}}) must resolve the class correctly
     * and produce no error.
     */
    @Test
    void genericTypeArgLiteralAngle_noError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.GenericExtension",
                """
                  <Label text="{GenericExtension<String> key=hello}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A markup extension invocation with an XML-escaped type argument
     * ({@code {GenericExtension&lt;String&gt; key=hello}}) must resolve the class correctly
     * and produce no error.
     */
    @Test
    void genericTypeArgEscapedAngle_noError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.GenericExtension",
                """
                  <Label text="{GenericExtension&lt;String&gt; key=hello}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A generic markup extension used without any parameters resolves the class correctly.
     */
    @Test
    void genericTypeArgNoParams_noError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.GenericExtension",
                """
                  <Label text="{GenericExtension<String>}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A generic extension with a type argument must still report unknown parameters.
     */
    @Test
    void genericTypeArgUnknownParam_error() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.GenericExtension",
                """
                  <Label text="{GenericExtension<String> %s=hello}"/>
                """.formatted(error("Unknown markup extension parameter 'badParam'", "badParam"))
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Ctrl+click on the class name inside a generic markup extension expression
     * ({@code {GenericExtension<String>}}) must resolve to the {@code GenericExtension} class.
     */
    @Test
    void genericTypeArgNavigation_resolvesToClass() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.GenericExtension",
                """
                  <Label text="{GenericExtension<String>}"/>
                """
        ));
        ReadAction.run(() -> {
            XmlAttributeValue attrVal = findMarkupExtAttrVal("{GenericExtension<String>}");
            assertNotNull(attrVal, "Could not find text={GenericExtension<String>} attribute value");

            PsiReference classRef = Arrays.stream(attrVal.getReferences())
                    .filter(r -> {
                        PsiElement resolved = r.resolve();
                        return resolved instanceof PsiClass c
                                && "test.GenericExtension".equals(c.getQualifiedName());
                    })
                    .findFirst().orElse(null);

            assertNotNull(classRef,
                    "No reference to test.GenericExtension found in text=\"{GenericExtension<String>}\"");
            PsiElement resolvedGeneric = classRef.resolve();
            assertInstanceOf(PsiClass.class, resolvedGeneric);
            assertEquals("test.GenericExtension", ((PsiClass) resolvedGeneric).getQualifiedName());
        });
    }

    // -----------------------------------------------------------------------
    // Navigation: type arg, parameter name, inline binding
    // -----------------------------------------------------------------------

    /**
     * Placing the caret inside the extension class name (e.g. {@code GenericExtension}
     * in {@code {GenericExtension<String> key=hello}}) must let {@code findReferenceAt} return
     * the class-name reference, <em>not</em> a null-resolving blocker reference covering the whole
     * value.  If the blocker is returned first, IntelliJ's caret word-highlighting falls back to
     * text-based search and may highlight the wrong word (e.g. {@code String} instead of
     * {@code GenericExtension}).
     */
    @Test
    void classNameRef_findReferenceAt_returnsClassNameRef() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.GenericExtension",
                """
                  <Label text="{GenericExtension<String> key=hello}"/>
                """
        ));
        ReadAction.run(() -> {
            XmlAttributeValue attrVal = findMarkupExtAttrVal("{GenericExtension<String> key=hello}");
            assertNotNull(attrVal, "Could not find markup extension attribute value");

            // "GenericExtension" starts at rawValue offset 1 (after '{').
            // In attrVal.getText() the offset is +1 for the opening quote -> position 2.
            // We probe a position in the middle of "GenericExtension" (the 'r' at offset 2+7=9).
            int probeOffset = 2 + 7; // inside "GenericExtension"
            PsiReference refAtCaret = attrVal.findReferenceAt(probeOffset);

            assertNotNull(refAtCaret,
                    "findReferenceAt inside class name must return a reference");
            PsiElement resolved = refAtCaret.resolve();
            assertInstanceOf(PsiClass.class, resolved,
                    "findReferenceAt inside class name must resolve to a PsiClass, got: " + resolved);
            assertEquals("test.GenericExtension", ((PsiClass) resolved).getQualifiedName(),
                    "findReferenceAt inside class name must resolve to GenericExtension");
        });
    }

    /**
     * The {@code <} and {@code >} bracket characters around a type argument
     * (e.g. in {@code {GenericExtension<String> key=hello}}) must NOT be gaps where
     * {@code findReferenceAt} returns {@code null}.  A {@code null} result causes IntelliJ
     * to fall back to non-deterministic word-at-caret scanning and may highlight the wrong
     * word ("String" when caret is near "GenericExtension", or vice-versa).
     *
     * <p>This test checks the literal-{@code <} form; the XML-entity {@code &lt;} form
     * is implicitly covered by the same code path (same bracket-filling logic).
     */
    @Test
    void bracketChars_findReferenceAt_notNull() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.GenericExtension",
                """
                  <Label text="{GenericExtension<String> key=hello}"/>
                """
        ));
        ReadAction.run(() -> {
            XmlAttributeValue attrVal = findMarkupExtAttrVal("{GenericExtension<String> key=hello}");
            assertNotNull(attrVal, "Could not find markup extension attribute value");

            // In rawValue = "{GenericExtension<String> key=hello}" the < is at rawValue[17]
            // and the > is at rawValue[24]. In attrVal.getText() add +1 for the opening quote.
            String rawValue = attrVal.getValue();
            int openBracketPosInRaw  = rawValue.indexOf('<');
            int closeBracketPosInRaw = rawValue.indexOf('>');
            assertTrue(openBracketPosInRaw  > 0, "rawValue should contain '<'");
            assertTrue(closeBracketPosInRaw > 0, "rawValue should contain '>'");

            // +1 for the opening quote character in attrVal.getText()
            int openBracketOffset  = 1 + openBracketPosInRaw;
            int closeBracketOffset = 1 + closeBracketPosInRaw;

            PsiReference openRef  = attrVal.findReferenceAt(openBracketOffset);
            PsiReference closeRef = attrVal.findReferenceAt(closeBracketOffset);

            assertNotNull(openRef,
                    "findReferenceAt on '<' must return a (bracket gap-filling) reference, "
                    + "not null - null triggers non-deterministic word-at-caret fallback");
            assertNotNull(closeRef,
                    "findReferenceAt on '>' must return a (bracket gap-filling) reference, "
                    + "not null - null triggers non-deterministic word-at-caret fallback");
        });
    }

    /**
     * Ctrl+click on the type argument of a markup extension ({@code String} in
     * {@code {GenericExtension<String> key=hello}}) must navigate to {@code java.lang.String}.
     */
    @Test
    void typeArgNavigation_resolvesToClass() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.GenericExtension",
                """
                  <Label text="{GenericExtension<String> key=hello}"/>
                """
        ));
        ReadAction.run(() -> {
            XmlAttributeValue attrVal = findMarkupExtAttrVal("{GenericExtension<String> key=hello}");
            assertNotNull(attrVal, "Could not find markup extension attribute value");

            PsiReference stringRef = Arrays.stream(attrVal.getReferences())
                    .filter(r -> {
                        PsiElement resolved = r.resolve();
                        return resolved instanceof PsiClass c
                                && "java.lang.String".equals(c.getQualifiedName());
                    })
                    .findFirst().orElse(null);

            assertNotNull(stringRef,
                    "No reference to java.lang.String found in type argument <String>");
            PsiElement resolvedString = stringRef.resolve();
            assertInstanceOf(PsiClass.class, resolvedString);
            assertEquals("java.lang.String", ((PsiClass) resolvedString).getQualifiedName());
        });
    }

    /**
     * Ctrl+click on a {@code @NamedArg} parameter key in a markup extension param list
     * ({@code key} in {@code {GenericExtension<String> key=hello}}) must navigate to the
     * constructor {@link PsiParameter} annotated with {@code @NamedArg("key")}.
     */
    @Test
    void namedArgParamNameRef_resolvesToConstructorParam() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.GenericExtension",
                """
                  <Label text="{GenericExtension<String> key=hello}"/>
                """
        ));
        ReadAction.run(() -> {
            XmlAttributeValue attrVal = findMarkupExtAttrVal("{GenericExtension<String> key=hello}");
            assertNotNull(attrVal, "Could not find markup extension attribute value");

            Fxml2BindingSegmentReference keyRef = findSegmentRefByName(attrVal, "key");
            assertNotNull(keyRef, "No Fxml2BindingSegmentReference found for 'key' in params");

            PsiElement resolved = keyRef.resolve();
            assertNotNull(resolved, "'key' should resolve to a PsiParameter");
            assertInstanceOf(PsiParameter.class, resolved,
                    "'key' should resolve to the @NamedArg PsiParameter, got: " + resolved);
            assertEquals("key", ((PsiParameter) resolved).getName());
        });
    }

    /**
     * Ctrl+click on a setter-based parameter key in a markup extension param list
     * ({@code param3} in {@code {MyExtension param3=5}}) must navigate to
     * the {@code setParam3} setter method.
     */
    @Test
    void setterParamNameRef_resolvesToSetterMethod() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.MyExtension",
                """
                  <Label text="{MyExtension param3=5}"/>
                """
        ));
        ReadAction.run(() -> {
            XmlAttributeValue attrVal = findMarkupExtAttrVal("{MyExtension param3=5}");
            assertNotNull(attrVal, "Could not find markup extension attribute value");

            Fxml2BindingSegmentReference param3Ref = findSegmentRefByName(attrVal, "param3");
            assertNotNull(param3Ref, "No Fxml2BindingSegmentReference found for 'param3' in params");

            PsiElement resolved = param3Ref.resolve();
            assertNotNull(resolved, "'param3' should resolve to the setParam3 method");
            assertInstanceOf(PsiMethod.class, resolved,
                    "'param3' should resolve to a setter PsiMethod, got: " + resolved);
            assertEquals("setParam3", ((PsiMethod) resolved).getName());
        });
    }

    /**
     * An inline {@code ${path}} binding expression inside a markup extension parameter
     * value provides {@link Fxml2BindingSegmentReference}s for each path segment -
     * same as a regular binding expression.
     *
     * <p>Example: in {@code {GenericExtension<String> key=${message}}}, Ctrl+click on
     * {@code message} navigates to the code-behind {@code messageProperty()} method.
     */
    @Test
    void inlineBindingInMarkupExtParam_resolvesSegments() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.StringProperty;
                import javafx.beans.property.SimpleStringProperty;
                public class MarkupBindingTestView extends TestViewBase {
                    private final StringProperty message = new SimpleStringProperty();
                    public StringProperty messageProperty() { return message; }
                    public String getMessage() { return message.get(); }
                    public void setMessage(String v) { message.set(v); }
                }
                """);
        getFixture().configureByText("MarkupBindingTestView.fxml", fxml2(
                "javafx.scene.control.Label\ntest.GenericExtension",
                """
                  <Label text="{GenericExtension<String> key=${message}}"/>
                """,
                "test.MarkupBindingTestView"
        ));

        ReadAction.run(() -> {
            XmlAttributeValue attrVal = findMarkupExtAttrVal("{GenericExtension<String> key=${message}}");
            assertNotNull(attrVal, "Could not find markup extension attribute value");

            // "message" is inside ${...}; find the Fxml2BindingSegmentReference covering it.
            String rawValue = attrVal.getValue();
            int messageIdx = rawValue.indexOf("message");
            assertTrue(messageIdx >= 0, "rawValue should contain 'message', got: " + rawValue);

            int messageRelStart = 1 + messageIdx; // +1 for opening quote in attrVal text
            Fxml2BindingSegmentReference messageRef = Arrays.stream(attrVal.getReferences())
                    .filter(r -> r instanceof Fxml2BindingSegmentReference)
                    .map(r -> (Fxml2BindingSegmentReference) r)
                    .filter(r -> r.getRangeInElement().getStartOffset() == messageRelStart)
                    .findFirst().orElse(null);

            assertNotNull(messageRef,
                    "No Fxml2BindingSegmentReference for 'message' at offset "
                    + messageRelStart + " in rawValue='" + rawValue + "'");

            PsiElement resolved = messageRef.resolve();
            assertNotNull(resolved,
                    "'message' segment should resolve to the code-behind messageProperty()");
        });
    }

    // -----------------------------------------------------------------------
    // Unresolved class reference inside a markup extension parameter binding
    // -----------------------------------------------------------------------

    /**
     * When a markup extension parameter binding refers to a class type that is not imported
     * (e.g. {@code $Row.titleGetter} in {@code {CellFactory $Row.titleGetter}}), the IDE
     * must report an "unresolved symbol" error on {@code Row} and offer the
     * "Add import for Row" quick fix, consistent with the behavior for unresolved class names
     * in {@code fx:typeArguments} values.
     */
    @Test
    void unresolvedClassInMarkupExtParamBinding_offersAddImportQuickfix() {
        // ItemType is a known class but NOT imported in the FXML2 file.
        getFixture().addClass("""
                package test;
                import java.util.function.Function;
                public class ItemType {
                    public static final Function<ItemType, String> labelAccessor = item -> item.label;
                    public String label;
                }
                """);
        // ItemSupplier is a markup extension that accepts a static class member reference.
        getFixture().addClass("""
                package test;
                import javafx.beans.property.Property;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                public class ItemSupplier implements MarkupExtension.PropertyConsumer<Object> {
                    public ItemSupplier() {}
                    @Override
                    public void accept(Property<Object> property, MarkupContext context) {}
                }
                """);
        // ItemType is NOT listed in the imports: only ItemSupplier is imported.
        getFixture().configureByText("UnresolvedClassInParamBinding.fxml", fxml2(
                "javafx.scene.control.Label\ntest.ItemSupplier",
                """
                  <Label text="{ItemSupplier $<caret>ItemType.labelAccessor}"/>
                """
        ));
        var action = getFixture().findSingleIntention("Add import for 'ItemType'");
        assertNotNull(action,
                "Expected 'Add import for ItemType' quick fix to be offered on the unresolved class 'ItemType'");
    }

    /**
     * A static field defined only on an implemented interface must NOT resolve when referenced
     * via the implementing class name inside a markup extension parameter binding.
     * Field resolution walks the superclass chain only, mirroring the fxml2 compiler, so
     * interface fields are invisible through implementing class names.
     */
    @Test
    void markupExtParamBindingStaticFieldOnlyOnInterfaceProducesError() {
        getFixture().addClass("""
                package test;
                import java.util.function.Function;
                public interface IItem {
                    Function<Item, String> labelGetter = Item::getLabel;
                }
                """);
        getFixture().addClass("""
                package test;
                public class Item implements IItem {
                    public String getLabel() { return ""; }
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.beans.property.Property;
                import org.jfxcore.markup.MarkupExtension;
                import org.jfxcore.markup.MarkupContext;
                public class ItemSupplier implements MarkupExtension.PropertyConsumer<Object> {
                    public ItemSupplier() {}
                    @Override
                    public void accept(Property<Object> property, MarkupContext context) {}
                }
                """);
        getFixture().configureByText("StaticFieldOnInterface.fxml", fxml2(
                "javafx.scene.control.Label\ntest.Item\ntest.ItemSupplier",
                """
                  <Label text="{ItemSupplier $Item.<error descr="'labelGetter' in test.Item cannot be resolved">labelGetter</error>}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // AddImportForClassReferenceIntention: FQN markup extension class name
    // -----------------------------------------------------------------------

    /**
     * When the caret is on the class-name portion of a FQN markup extension invocation
     * (e.g. {@code {sample.app.framework.BundledResource...}}) that is not yet imported,
     * the "Add import for class reference" intention must be available, must insert the
     * correct {@code <?import?>} PI, and must strip the package prefix from the value.
     */
    @Test
    void addImportIntentionOfferedForFqnMarkupExtensionClassName() {
        getFixture().configureByText("AddImportMarkupExt.fxml", fxml2(
                "javafx.scene.control.Label", // GenericExtension is NOT imported
                """
                  <Label text="{test.Generic<caret>Extension<java.lang.String> key=hello}"/>
                """
        ));
        com.intellij.codeInsight.intention.IntentionAction action =
                getFixture().findSingleIntention("Add import for class reference");
        assertNotNull(action, "Expected 'Add import for class reference' intention to be available");
        getFixture().launchAction(action);
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("<?import test.GenericExtension?>"),
                "Expected import to be added, got: " + result);
        assertTrue(result.contains("{GenericExtension<java.lang.String> key=hello}"),
                "Expected package prefix to be stripped from markup extension value, got: " + result);
        assertFalse(result.contains("{test.GenericExtension"),
                "Expected FQN prefix to be removed from markup extension value, got: " + result);
    }

    /**
     * When the markup extension class is already imported, the intention must NOT be offered.
     */
    @Test
    void addImportIntentionNotOfferedForMarkupExtensionWhenAlreadyImported() {
        getFixture().configureByText("AddImportMarkupExtAlready.fxml", fxml2(
                "javafx.scene.control.Label\ntest.GenericExtension",
                """
                  <Label text="{test.Generic<caret>Extension<java.lang.String> key=hello}"/>
                """
        ));
        var actions = getFixture().getAvailableIntentions();
        assertFalse(
                actions.stream().anyMatch(f -> f.getText().equals("Add import for class reference")),
                "Should not offer 'Add import for class reference' when already imported, got: "
                        + actions.stream().map(com.intellij.codeInsight.intention.IntentionAction::getText).toList());
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /** Returns the first {@link XmlAttributeValue} whose raw (un-quoted) value equals {@code raw}. */
    private @org.jetbrains.annotations.Nullable XmlAttributeValue findMarkupExtAttrVal(String raw) {
        for (XmlTag tag : com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                getFixture().getFile(), XmlTag.class)) {
            for (XmlAttribute attr : tag.getAttributes()) {
                if (raw.equals(attr.getValue())) {
                    return attr.getValueElement();
                }
            }
        }
        return null;
    }

    /**
     * Finds the first {@link Fxml2BindingSegmentReference} on {@code attrVal} whose
     * range in the raw value (quotes excluded) matches exactly the given token name.
     */
    private static @org.jetbrains.annotations.Nullable Fxml2BindingSegmentReference
            findSegmentRefByName(XmlAttributeValue attrVal, String tokenText) {
        String rawValue = attrVal.getValue();
        return Arrays.stream(attrVal.getReferences())
                .filter(r -> r instanceof Fxml2BindingSegmentReference)
                .map(r -> (Fxml2BindingSegmentReference) r)
                .filter(r -> {
                    TextRange range = r.getRangeInElement();
                    int start = range.getStartOffset() - 1; // -1 for opening quote
                    int end   = range.getEndOffset()   - 1;
                    if (start < 0 || end > rawValue.length()) return false;
                    return tokenText.equals(rawValue.substring(start, end));
                })
                .findFirst().orElse(null);
    }
}
