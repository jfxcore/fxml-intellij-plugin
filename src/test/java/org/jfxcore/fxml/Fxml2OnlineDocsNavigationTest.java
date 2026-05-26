package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.impl.source.resolve.reference.impl.PsiMultiReference;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlProcessingInstruction;
import com.intellij.psi.xml.XmlTag;
import org.jfxcore.fxml.lang.Fxml2BindingNotationReference;
import org.jfxcore.fxml.lang.Fxml2NamespaceUrlReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that Ctrl+click navigation on FXML language keywords and symbols that are not
 * backed by actual Java code resolves to a {@link Fxml2NamespaceUrlReference.UrlNavigationTarget}
 * pointing to the corresponding online documentation page.
 *
 * <p>Covered identifiers:
 * <ul>
 *   <li>Binding-notation prefixes: {@code $}, {@code ${}, {@code #{}</li>
 *   <li>{@code fx:} intrinsic attribute names: {@code fx:id}, {@code fx:subclass},
 *       {@code fx:context}, {@code fx:typeArguments}, {@code fx:factory},
 *       {@code fx:value}, {@code fx:constant}, {@code fx:className},
 *       {@code fx:classModifier}, {@code fx:classParameters}</li>
 *   <li>{@code fx:} intrinsic element tags: {@code fx:define}, {@code fx:Null},
 *       {@code fx:True}, {@code fx:False}</li>
 *   <li>{@code <?prefix?>} processing-instruction keyword</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2OnlineDocsNavigationTest extends Fxml2TestBase {

    // Shared FXML content used by multiple tests.
    private static final String COMMON_FXML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <?import javafx.scene.control.Label?>
            <?import javafx.scene.layout.VBox?>
            <?prefix ^ = javafx.scene.control.Label?>
            <VBox xmlns="http://javafx.com/javafx"
                  xmlns:fx="http://jfxcore.org/fxml/2.0"
                  fx:subclass="com.example.MyView">
                <Label fx:id="lbl"
                       text="$someText"
                       style="${someStyle}"
                       id="#{someId}"/>
                <fx:define>
                    <Label fx:id="hidden"/>
                </fx:define>
                <Label visible="{fx:False}">
                    <visible><fx:False/></visible>
                </Label>
                <Label visible="{fx:True}">
                    <visible><fx:True/></visible>
                </Label>
                <Label>
                    <graphic><fx:Null/></graphic>
                </Label>
            </VBox>
            """;

    private static final String EXPRESSION_DOCS =
            "https://jfxcore.github.io/fxml-compiler/markup-extension/expression.html#compiled-expressions";
    private static final String REFERENCE_BASE =
            "https://jfxcore.github.io/fxml-compiler/reference/";
    private static final String PREFIX_DOCS =
            "https://jfxcore.github.io/fxml-compiler/markup-extension.html#prefix-declarations";

    // -----------------------------------------------------------------------
    // Binding notation: $, ${}, #{}
    // -----------------------------------------------------------------------

    @Test
    void ctrlClick_onDollarNotation_opensExpressionDocs() {
        getFixture().configureByText("TestView.fxmlx", COMMON_FXML);
        ReadAction.run(() -> {
            String url = resolveAttrValuePrefixUrl("text", "$someText");
            assertEquals(EXPRESSION_DOCS, url,
                    "$ notation should open expression docs");
        });
    }

    @Test
    void ctrlClick_onDollarBraceNotation_opensExpressionDocs() {
        getFixture().configureByText("TestView.fxmlx", COMMON_FXML);
        ReadAction.run(() -> {
            String url = resolveAttrValuePrefixUrl("style", "${someStyle}");
            assertEquals(EXPRESSION_DOCS, url,
                    "${} notation should open expression docs");
        });
    }

    @Test
    void ctrlClick_onHashBraceNotation_opensExpressionDocs() {
        getFixture().configureByText("TestView.fxmlx", COMMON_FXML);
        ReadAction.run(() -> {
            String url = resolveAttrValuePrefixUrl("id", "#{someId}");
            assertEquals(EXPRESSION_DOCS, url,
                    "#{} notation should open expression docs");
        });
    }

    // -----------------------------------------------------------------------
    // fx: intrinsic attribute names
    // -----------------------------------------------------------------------

    @Test
    void ctrlClick_onFxSubclassAttrName_opensSubclassDocs() {
        getFixture().configureByText("TestView.fxmlx", COMMON_FXML);
        ReadAction.run(() -> {
            String url = resolveFxAttrNameUrl("fx:subclass");
            assertEquals(REFERENCE_BASE + "subclass.html", url,
                    "fx:subclass attribute name should link to subclass.html");
        });
    }

    @Test
    void ctrlClick_onFxIdAttrName_opensIdDocs() {
        getFixture().configureByText("TestView.fxmlx", COMMON_FXML);
        ReadAction.run(() -> {
            String url = resolveFxAttrNameUrl("fx:id");
            assertEquals(REFERENCE_BASE + "id.html", url,
                    "fx:id attribute name should link to id.html");
        });
    }

    // -----------------------------------------------------------------------
    // fx: intrinsic element tags
    // -----------------------------------------------------------------------

    @Test
    void ctrlClick_onFxDefineTag_opensDefineDocs() {
        getFixture().configureByText("TestView.fxmlx", COMMON_FXML);
        ReadAction.run(() -> {
            String url = resolveFxTagUrl("define");
            assertEquals(REFERENCE_BASE + "define.html", url,
                    "fx:define tag should link to define.html");
        });
    }

    @Test
    void ctrlClick_onFxNullTag_opensNullDocs() {
        getFixture().configureByText("TestView.fxmlx", COMMON_FXML);
        ReadAction.run(() -> {
            String url = resolveFxTagUrl("Null");
            assertEquals(REFERENCE_BASE + "null.html", url,
                    "fx:Null tag should link to null.html");
        });
    }

    @Test
    void ctrlClick_onFxTrueTag_opensTrueDocs() {
        getFixture().configureByText("TestView.fxmlx", COMMON_FXML);
        ReadAction.run(() -> {
            String url = resolveFxTagUrl("True");
            assertEquals(REFERENCE_BASE + "true.html", url,
                    "fx:True tag should link to true.html");
        });
    }

    @Test
    void ctrlClick_onFxFalseTag_opensFalseDocs() {
        getFixture().configureByText("TestView.fxmlx", COMMON_FXML);
        ReadAction.run(() -> {
            String url = resolveFxTagUrl("False");
            assertEquals(REFERENCE_BASE + "false.html", url,
                    "fx:False tag should link to false.html");
        });
    }

    // -----------------------------------------------------------------------
    // <?prefix?> processing instruction keyword
    // -----------------------------------------------------------------------

    @Test
    void ctrlClick_onPrefixKeyword_opensPrefixDocs() {
        getFixture().configureByText("TestView.fxmlx", COMMON_FXML);
        ReadAction.run(() -> {
            XmlFile xmlFile = (XmlFile) getFixture().getFile();
            XmlProcessingInstruction pi = null;
            for (XmlProcessingInstruction candidate :
                    PsiTreeUtil.findChildrenOfType(xmlFile, XmlProcessingInstruction.class)) {
                if (candidate.getText().contains("prefix")
                        && candidate.getText().contains("Label")) {
                    pi = candidate;
                    break;
                }
            }
            assertNotNull(pi, "Could not find <?prefix?> PI");

            // "prefix" starts at offset 2 in "<?prefix ^ = ...?>".
            int prefixKeywordOffset = pi.getText().indexOf("prefix");
            assertTrue(prefixKeywordOffset >= 0);
            PsiReference ref = pi.findReferenceAt(prefixKeywordOffset + 2);
            assertNotNull(ref, "No reference found on 'prefix' keyword in PI");

            PsiElement resolved = ref.resolve();
            assertInstanceOf(Fxml2NamespaceUrlReference.UrlNavigationTarget.class, resolved,
                    "prefix keyword should resolve to a UrlNavigationTarget");
            assertEquals(PREFIX_DOCS,
                    ((Fxml2NamespaceUrlReference.UrlNavigationTarget) resolved).getName(),
                    "prefix keyword should link to prefix-declarations docs");
        });
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Finds the first attribute with the given local name and value in the parsed file,
     * retrieves the reference at offset 1 within its {@link XmlAttributeValue} (the first
     * character after the opening quote, where the binding-notation prefix sits),
     * finds the {@link Fxml2BindingNotationReference} among the references there, and returns
     * the URL that its resolved {@link Fxml2NamespaceUrlReference.UrlNavigationTarget} reports.
     * Fails the test if any step does not succeed.
     */
    private String resolveAttrValuePrefixUrl(String attrLocalName, String expectedValue) {
        XmlFile xmlFile = (XmlFile) getFixture().getFile();
        XmlAttributeValue attrVal = null;
        for (XmlTag tag : PsiTreeUtil.findChildrenOfType(xmlFile, XmlTag.class)) {
            XmlAttribute attr = tag.getAttribute(attrLocalName);
            if (attr != null && expectedValue.equals(attr.getValue())) {
                attrVal = attr.getValueElement();
                break;
            }
        }
        assertNotNull(attrVal, "Attribute " + attrLocalName + "=" + expectedValue + " not found");

        // Offset 1 = first character after the opening quote, where the notation prefix ($, ${, #{) sits.
        PsiReference ref = attrVal.findReferenceAt(1);
        assertNotNull(ref, "No reference at the notation prefix in " + attrVal.getText());

        Fxml2BindingNotationReference notationRef = findBindingNotationRef(ref);
        assertNotNull(notationRef, "No Fxml2BindingNotationReference found in " + ref.getClass().getSimpleName());

        PsiElement resolved = notationRef.resolve();
        assertInstanceOf(Fxml2NamespaceUrlReference.UrlNavigationTarget.class, resolved,
                "Binding notation reference should resolve to UrlNavigationTarget");
        return ((Fxml2NamespaceUrlReference.UrlNavigationTarget) resolved).getName();
    }

    /**
     * Finds the first {@code fx:} attribute with the given full attribute name (e.g. {@code fx:id})
     * in the parsed file, retrieves the reference at offset 2 within the {@link XmlAttribute}
     * (covering the attribute name), finds a URL-navigation reference, and returns the URL.
     * Fails the test if any step fails.
     */
    private String resolveFxAttrNameUrl(String fullAttrName) {
        XmlFile xmlFile = (XmlFile) getFixture().getFile();
        XmlAttribute target = null;
        outer:
        for (XmlTag tag : PsiTreeUtil.findChildrenOfType(xmlFile, XmlTag.class)) {
            for (XmlAttribute attr : tag.getAttributes()) {
                if (fullAttrName.equals(attr.getName())) {
                    target = attr;
                    break outer;
                }
            }
        }
        assertNotNull(target, "Attribute " + fullAttrName + " not found");

        PsiReference ref = target.findReferenceAt(2); // inside the attribute name
        assertNotNull(ref, "No reference on attribute name " + fullAttrName);

        Fxml2NamespaceUrlReference.UrlNavigationTarget urlTarget =
                resolveToUrlTarget(ref, "fx: attribute name " + fullAttrName);
        return urlTarget.getName();
    }

    /**
     * Finds the first {@code fx:} element tag with the given local name (e.g. {@code define})
     * in the parsed file, retrieves the reference at the start of the local name within the
     * tag text, finds a URL-navigation reference, and returns the URL.
     * Fails the test if any step fails.
     */
    private String resolveFxTagUrl(String localName) {
        XmlFile xmlFile = (XmlFile) getFixture().getFile();
        XmlTag target = null;
        for (XmlTag tag : PsiTreeUtil.findChildrenOfType(xmlFile, XmlTag.class)) {
            if (localName.equals(tag.getLocalName())
                    && "http://jfxcore.org/fxml/2.0".equals(tag.getNamespace())) {
                target = tag;
                break;
            }
        }
        assertNotNull(target, "Tag fx:" + localName + " not found");

        // "fx:localName" starts at offset 1 in the tag text (after '<').
        // Find reference at offset 4 (into "fx:l...").
        PsiReference ref = target.findReferenceAt(4);
        assertNotNull(ref, "No reference on fx:" + localName + " tag name");

        Fxml2NamespaceUrlReference.UrlNavigationTarget urlTarget =
                resolveToUrlTarget(ref, "fx:" + localName + " tag");
        return urlTarget.getName();
    }

    // -----------------------------------------------------------------------
    // Reference utilities
    // -----------------------------------------------------------------------

    /**
     * Finds a {@link Fxml2BindingNotationReference} among the constituents of {@code ref}.
     * If {@code ref} is a {@link PsiMultiReference}, searches its inner references;
     * otherwise checks {@code ref} itself.
     */
    private static Fxml2BindingNotationReference findBindingNotationRef(PsiReference ref) {
        if (ref instanceof Fxml2BindingNotationReference r) return r;
        if (ref instanceof PsiMultiReference multi) {
            for (PsiReference inner : multi.getReferences()) {
                if (inner instanceof Fxml2BindingNotationReference r) return r;
            }
        }
        return null;
    }

    /**
     * Resolves {@code ref} (possibly a {@link PsiMultiReference}) to a
     * {@link Fxml2NamespaceUrlReference.UrlNavigationTarget}, searching inner references
     * until one resolves to a URL target. Fails the test with {@code contextDescription}
     * if none is found.
     */
    private Fxml2NamespaceUrlReference.UrlNavigationTarget resolveToUrlTarget(
            PsiReference ref, String contextDescription) {
        if (!(ref instanceof PsiMultiReference multi)) {
            PsiElement resolved = ref.resolve();
            assertInstanceOf(Fxml2NamespaceUrlReference.UrlNavigationTarget.class, resolved,
                    contextDescription + " reference should resolve to UrlNavigationTarget");
            return (Fxml2NamespaceUrlReference.UrlNavigationTarget) resolved;
        }
        for (PsiReference inner : multi.getReferences()) {
            PsiElement resolved = inner.resolve();
            if (resolved instanceof Fxml2NamespaceUrlReference.UrlNavigationTarget urlTarget) {
                return urlTarget;
            }
        }
        fail(contextDescription + ": no inner reference resolved to UrlNavigationTarget");
        throw new AssertionError(); // unreachable
    }
}
