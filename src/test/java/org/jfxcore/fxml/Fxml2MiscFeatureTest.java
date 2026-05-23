package org.jfxcore.fxml;

import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.util.TextRange;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.jfxcore.fxml.annotator.Fxml2FxAttributeInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2MiscFeatureTest extends Fxml2TestBase {

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2AttributeValueInspection(), new Fxml2FxAttributeInspection());
    }


    // -----------------------------------------------------------------------
    // Wildcard imports
    // -----------------------------------------------------------------------

    /**
     * Doc ({@code markup-deployment.md}): {@code <?import javafx.scene.control.*?>}
     *: wildcard import should be recognized and used for tag resolution.
     */
    @Test
    void wildcardImportAllowsTagResolution() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <?import javafx.scene.control.*?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0">
                  <Button text="Hello"/>
                  <Label text="World"/>
                </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A wildcard import that resolves a nested element should produce no error.
     */
    @Test
    void wildcardImportForLayoutPackageProducesNoError() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.*?>
                <VBox xmlns="http://javafx.com/javafx"
                      xmlns:fx="http://jfxcore.org/fxml/2.0">
                  <HBox/>
                </VBox>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // fx:context on non-root element
    // -----------------------------------------------------------------------

    /**
     * Doc ({@code reference/context.md}): {@code fx:context} can only be set on the root
     * node. Using it on a child element should produce an error.
     */
    @Test
    void fxContextOnNonRootElementProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane <error descr="Unexpected intrinsic: context">fx:context="$foo"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // fx:constant: unknown field
    // -----------------------------------------------------------------------

    /**
     * Doc ({@code reference/constant.md}): {@code fx:constant} refers to a static field.
     * Using an unknown field name should produce an error.
     */
    @Test
    void fxConstantWithUnknownFieldProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane>
                    <fx:define>
                      <GridPane fx:constant=<error descr="Cannot resolve static field 'NON_EXISTENT_FIELD' in javafx.scene.layout.GridPane">"NON_EXISTENT_FIELD"</error>/>
                    </fx:define>
                  </GridPane>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A known {@code fx:constant} value should produce no error.
     */
    @Test
    void fxConstantWithKnownFieldProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.TableView",
                """
                  <TableView>
                    <columnResizePolicy>
                      <TableView fx:constant="UNCONSTRAINED_RESIZE_POLICY"/>
                    </columnResizePolicy>
                  </TableView>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Default property
    // -----------------------------------------------------------------------

    /**
     * Doc ({@code property-notation.md}): {@code <Button>Hello</Button>}: text content
     * is assigned to the default property ({@code text} via {@code @DefaultProperty}).
     * Should produce no error.
     */
    @Test
    void defaultPropertyTextContentProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button>Hello</Button>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Standalone FXML file without fx:subclass
    // -----------------------------------------------------------------------

    /**
     * Doc ({@code reference/class.md}): When {@code fx:subclass} is omitted, the FXML file
     * compiles to a class with the same name as the file. The plugin should not produce
     * false-positive errors in this case.
     */
    @Test
    void standaloneFileWithoutFxClassProducesNoError() {
        getFixture().configureByText("MyButton.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.control.Button?>
                <Button xmlns="http://javafx.com/javafx"
                        xmlns:fx="http://jfxcore.org/fxml/2.0"
                        text="Click me"/>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // fx:controller / fx:root: FXML1 directives unsupported in FXML2
    // -----------------------------------------------------------------------

    /**
     * Doc ({@code code-behind.md}): {@code fx:controller} is an FXML 1.0 directive and
     * should not be valid in an FXML 2.0 file. It should be treated as an unknown intrinsic.
     */
    @Test
    void fxControllerAttributeIsUnknownIntrinsic() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane",
                """
                  <GridPane <error descr="Unknown intrinsic: controller">fx:controller="some.Controller"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // fx:define: objects not treated as scene-graph children
    // -----------------------------------------------------------------------

    /**
     * Doc ({@code reference/define.md}): Objects inside {@code <fx:define>} are not part of
     * the scene graph. The plugin must not produce an "element cannot be placed here"
     * error for such elements.
     */
    @Test
    void fxDefineObjectsAreNotFlaggedAsInvalidChildren() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.geometry.Insets",
                """
                  <fx:define>
                    <Insets fx:id="margins" topRightBottomLeft="8"/>
                  </fx:define>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // HashMap / HashSet in fx:define
    // -----------------------------------------------------------------------

    /**
     * Doc ({@code binding/binding-types.md}, collections): A {@code HashMap} can be
     * created inside {@code <fx:define>} using {@code fx:typeArguments}.
     */
    @Test
    void hashMapInsideFxDefineProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "java.util.HashMap",
                """
                  <fx:define>
                    <HashMap fx:typeArguments="String, Object" fx:id="myMap"/>
                  </fx:define>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A {@code HashSet} created inside {@code <fx:define>} should produce no error.
     */
    @Test
    void hashSetInsideFxDefineProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "java.util.HashSet",
                """
                  <fx:define>
                    <HashSet fx:typeArguments="String" fx:id="mySet"/>
                  </fx:define>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Comma-separated coercion cannot contain binding expressions
    // -----------------------------------------------------------------------

    /**
     * Doc ({@code type-coercion.md}): A comma-separated constructor coercion list may
     * only contain literal values, not binding expressions.
     */
    @Test
    void commaSeparatedCoercionWithBindingExpressionProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.GridPane\njavafx.geometry.Insets",
                """
                  <GridPane padding=<error descr="A comma-separated argument list cannot contain binding expressions">"$foo,2,3,4"</error>/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // xml:space attribute
    // -----------------------------------------------------------------------

    /**
     * {@code xml:space="preserve"} is a valid compiler intrinsic, no error should appear.
     */
    @Test
    void xmlSpacePreserveProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label xml:space="preserve" text="hello  world"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code xml:space="default"} is a valid compiler intrinsic, no error should appear.
     */
    @Test
    void xmlSpaceDefaultProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label xml:space="default" text="hello"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code xml:space="invalid"} mirrors the compiler's
     * {@code cannotCoercePropertyValue("xml:space", "invalid")} error.
     */
    @Test
    void xmlSpaceInvalidValueProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label xml:space=<error descr="Cannot coerce 'invalid' to xml:space">"invalid"</error> text="hello"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@code xml:space="preserve"} must not be flagged as an unknown {@code fx:} intrinsic
     * or as an unresolvable property: it is a compiler-reserved attribute, not a JavaFX property.
     */
    @Test
    void xmlSpacePreserveIsNotFlaggedAsUnknownAttribute() {
        // Verify separately that the fx-attribute inspection does not produce an "Unknown intrinsic"
        // error and that the attribute-value inspection does not raise a "cannot resolve" error.
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.VBox",
                """
                  <VBox xml:space="preserve">
                    <javafx.scene.control.Label text="  indented  "/>
                  </VBox>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // xml:space Ctrl+click navigation (opens W3C whitespace spec in browser)
    // -----------------------------------------------------------------------

    private static final String XML_SPACE_SPEC_URL = "https://www.w3.org/TR/xml/#sec-white-space";

    /**
     * Ctrl+clicking the {@code xml:space} <em>attribute name</em> should return a reference
     * that resolves to a navigable element whose name is the W3C whitespace spec URL.
     * Only the local-name part {@code "space"} (after the colon) should be a link;
     * hovering over {@code "xml"} must not produce a navigation underline.
     */
    @Test
    void xmlSpace_ctrlClickNavigation_attrName() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label xml:space="preserve" text="hello"/>
                """
        ));
        ReadAction.run(() -> {
            XmlAttribute spaceAttr = null;
            for (XmlTag tag : PsiTreeUtil.findChildrenOfType(getFixture().getFile(), XmlTag.class)) {
                for (XmlAttribute attr : tag.getAttributes()) {
                    if ("xml:space".equals(attr.getName())) {
                        spaceAttr = attr;
                        break;
                    }
                }
                if (spaceAttr != null) break;
            }
            assertNotNull(spaceAttr, "Could not find xml:space attribute");

            // "xml:space": colon is at index 3, so "space" starts at index 4.
            // Iterate all references and categorise by whether they cover the "space" local-name
            // part (offset 4) or the "xml" prefix part (offset 0-2).
            boolean spacePartNavigable = false;
            boolean xmlPrefixNavigable = false;
            for (PsiReference r : spaceAttr.getReferences()) {
                TextRange range = r.getRangeInElement();
                PsiElement resolved = r.resolve();
                if (resolved instanceof Navigatable nav && nav.canNavigate()) {
                    if (range.containsOffset(4)) {
                        // Verify it points to the correct W3C URL.
                        assertInstanceOf(NavigationItem.class, resolved);
                        assertEquals(XML_SPACE_SPEC_URL,
                                ((NavigationItem) resolved).getName(),
                                "Navigation link on 'space' should point to W3C whitespace spec");
                        spacePartNavigable = true;
                    }
                    if (range.containsOffset(0)) {
                        xmlPrefixNavigable = true;
                    }
                }
            }
            assertTrue(spacePartNavigable,
                    "Hovering over 'space' part of xml:space must produce a navigation link");
            assertFalse(xmlPrefixNavigable,
                    "Hovering over 'xml' prefix of xml:space must NOT produce a navigation link");
        });
    }

    /**
     * Ctrl+clicking the {@code xml:space} <em>attribute value</em> (e.g. {@code "preserve"})
     * should return a reference that resolves to a navigable element pointing to the W3C spec.
     */
    @Test
    void xmlSpace_ctrlClickNavigation_attrValue() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label xml:space="preserve" text="hello"/>
                """
        ));
        ReadAction.run(() -> {
            XmlAttributeValue attrVal = null;
            for (XmlTag tag : PsiTreeUtil.findChildrenOfType(getFixture().getFile(), XmlTag.class)) {
                for (XmlAttribute attr : tag.getAttributes()) {
                    if ("xml:space".equals(attr.getName())) {
                        attrVal = attr.getValueElement();
                        break;
                    }
                }
                if (attrVal != null) break;
            }
            assertNotNull(attrVal, "Could not find xml:space attribute value");

            // The value text is e.g. "preserve" (with quotes); offset 1 is inside the value.
            PsiReference ref = attrVal.findReferenceAt(1);
            assertNotNull(ref, "No reference found on xml:space attribute value");

            PsiElement resolved = ref.resolve();
            assertNotNull(resolved, "xml:space attribute value reference should resolve");
            assertInstanceOf(Navigatable.class, resolved,
                    "xml:space attribute value reference should be Navigatable");
            assertTrue(((Navigatable) resolved).canNavigate(),
                    "xml:space attribute value reference should navigate to W3C spec");
            assertInstanceOf(NavigationItem.class, resolved,
                    "xml:space attribute value reference should be a NavigationItem");
            assertEquals(XML_SPACE_SPEC_URL, ((NavigationItem) resolved).getName(),
                    "xml:space attribute value reference should point to W3C whitespace spec URL");
        });
    }
}
