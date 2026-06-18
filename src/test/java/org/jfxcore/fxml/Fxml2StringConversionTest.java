package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.impl.source.resolve.reference.impl.PsiMultiReference;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.jfxcore.fxml.lang.Fxml2BindingParamNameReference;
import org.jfxcore.fxml.lang.Fxml2NamespaceUrlReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for string-conversion parameters in {@code fx:Synchronize} expressions.
 *
 * <p>When a {@code StringProperty} is bidirectionally bound to a property of a different
 * type, a {@code java.text.Format} or {@code javafx.util.StringConverter} can be used
 * to convert between the two representations.
 *
 * <p>Syntax:
 * <pre>{@code
 * <TextField text="#{path.to.value; format=path.to.format}"/>
 * <TextField text="#{path.to.value; converter=path.to.converter}"/>
 * }</pre>
 *
 * <p>Corresponds to {@code binding/string-conversion.md} and
 * {@code reference/synchronize.md}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2StringConversionTest extends Fxml2TestBase {

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
                import javafx.beans.property.DoubleProperty;
                import javafx.beans.property.SimpleDoubleProperty;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                import javafx.util.StringConverter;
                import java.text.NumberFormat;
                public class TestView extends TestViewBase {
                    private final DoubleProperty amount = new SimpleDoubleProperty(0);
                    public DoubleProperty amountProperty() { return amount; }
                    public double getAmount() { return amount.get(); }

                    private final ObjectProperty<StringConverter<Double>> converter =
                        new SimpleObjectProperty<>(null);
                    public ObjectProperty<StringConverter<Double>> converterProperty() { return converter; }
                    public StringConverter<Double> getConverter() { return converter.get(); }

                    private final ObjectProperty<NumberFormat> format =
                        new SimpleObjectProperty<>(null);
                    public ObjectProperty<NumberFormat> formatProperty() { return format; }
                    public NumberFormat getFormat() { return format.get(); }
                }
                """);
    }

    // -----------------------------------------------------------------------
    // format= parameter
    // -----------------------------------------------------------------------

    /**
     * {@code #{amount; format=format}}: bidirectional binding on a StringProperty
     * with a {@code java.text.Format} specified via the {@code format=} parameter.
     * Should resolve without error.
     */
    @Test
    void synchronizeWithFormatParameterProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.TextField",
                """
                  <TextField text="#{amount; format=format}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * The {@code format=} parameter with an unresolvable path should produce an error.
     */
    @Test
    void synchronizeWithUnresolvableFormatPathProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.TextField",
                """
                  <TextField text="#{amount; format=<error descr="'nonExistentFormat' in test.TestView cannot be resolved">nonExistentFormat</error>}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // converter= parameter
    // -----------------------------------------------------------------------

    /**
     * {@code #{amount; converter=converter}}: bidirectional binding on a StringProperty
     * with a {@code javafx.util.StringConverter} specified via the {@code converter=} parameter.
     * Should resolve without error.
     */
    @Test
    void synchronizeWithConverterParameterProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.TextField",
                """
                  <TextField text="#{amount; converter=converter}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * The {@code converter=} parameter with an unresolvable path should produce an error.
     */
    @Test
    void synchronizeWithUnresolvableConverterPathProducesError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.TextField",
                """
                  <TextField text="#{amount; converter=<error descr="'nonExistentConverter' in test.TestView cannot be resolved">nonExistentConverter</error>}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Ctrl+click on format=/converter= param names: online docs navigation
    // -----------------------------------------------------------------------

    /**
     * Ctrl+click on {@code format} in {@code #{amount; format=format}} opens the
     * string-conversion documentation page.
     */
    @Test
    void ctrlClick_onFormatParamName_opensConversionDocs() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.TextField",
                """
                  <TextField text="#{amount; format=format}"/>
                """
        ));
        ReadAction.run(() -> {
            String url = resolveConversionParamNameUrl("#{amount; format=format}", "format");
            assertEquals(Fxml2BindingParamNameReference.CONVERSION_DOCS_URL, url,
                    "format param name should open conversion docs");
        });
    }

    /**
     * Ctrl+click on {@code converter} in {@code #{amount; converter=converter}} opens the
     * string-conversion documentation page.
     */
    @Test
    void ctrlClick_onConverterParamName_opensConversionDocs() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.TextField",
                """
                  <TextField text="#{amount; converter=converter}"/>
                """
        ));
        ReadAction.run(() -> {
            String url = resolveConversionParamNameUrl("#{amount; converter=converter}", "converter");
            assertEquals(Fxml2BindingParamNameReference.CONVERSION_DOCS_URL, url,
                    "converter param name should open conversion docs");
        });
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Finds the {@code text} attribute with the given value, then resolves the reference
     * at the position of {@code paramName} (before the {@code =} separator) to the URL
     * reported by its {@link Fxml2NamespaceUrlReference.UrlNavigationTarget}.
     */
    private String resolveConversionParamNameUrl(String expectedValue, String paramName) {

        XmlFile xmlFile = (XmlFile) getFixture().getFile();
        XmlAttributeValue attrVal = null;
        for (XmlTag tag : PsiTreeUtil.findChildrenOfType(xmlFile, XmlTag.class)) {
            XmlAttribute attr = tag.getAttribute("text");
            if (attr != null && expectedValue.equals(attr.getValue())) {
                attrVal = attr.getValueElement();
                break;
            }
        }
        assertNotNull(attrVal, "text attribute with value " + expectedValue + " not found");

        // Locate the param name token inside the attribute value text (includes surrounding quotes).
        // The first occurrence of paramName in the attribute text is the param name keyword,
        // which appears before the '=' separator. The occurrence after '=' is the path value.
        String attrText = attrVal.getText();
        int semicolonPos = attrText.indexOf(';');
        assertTrue(semicolonPos > 0, "No ';' separator in " + attrText);
        int paramNamePos = attrText.indexOf(paramName, semicolonPos);
        assertTrue(paramNamePos > 0, "Param name '" + paramName + "' not found after ';' in " + attrText);

        // Use a position in the middle of the param name token.
        int midOffset = paramNamePos + paramName.length() / 2;
        PsiReference ref = attrVal.findReferenceAt(midOffset);
        assertNotNull(ref, "No reference at param name '" + paramName + "' in " + attrText);

        Fxml2NamespaceUrlReference.UrlNavigationTarget urlTarget = findConversionUrlTarget(ref);
        assertNotNull(urlTarget, "No conversion URL reference found at '" + paramName + "' in " + attrText);
        return urlTarget.getName();
    }

    /**
     * Extracts a {@link Fxml2NamespaceUrlReference.UrlNavigationTarget} from the resolved
     * reference, searching through {@link PsiMultiReference} constituents if needed.
     */
    private static Fxml2NamespaceUrlReference.UrlNavigationTarget findConversionUrlTarget(
            PsiReference ref) {

        if (!(ref instanceof PsiMultiReference multi)) {
            PsiElement resolved = ref.resolve();
            return resolved instanceof Fxml2NamespaceUrlReference.UrlNavigationTarget t ? t : null;
        }
        for (PsiReference inner : multi.getReferences()) {
            if (inner instanceof Fxml2BindingParamNameReference) {
                PsiElement resolved = inner.resolve();
                if (resolved instanceof Fxml2NamespaceUrlReference.UrlNavigationTarget t) return t;
            }
        }
        return null;
    }
}
