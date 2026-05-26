package org.jfxcore.fxml;

import com.intellij.find.findUsages.FindUsagesHandler;
import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jfxcore.fxml.lang.Fxml2BindingSegmentReference;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.jfxcore.fxml.lang.Fxml2FxIdCodeBehindGotoHandler;
import org.jfxcore.fxml.lang.Fxml2FxIdDeclarationProvider;
import org.jfxcore.fxml.lang.Fxml2FxIdFindUsagesHandlerFactory;
import org.jfxcore.fxml.lang.Fxml2FxIdReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the navigation model for {@code fx:id} in an FXML file whose code-behind
 * class extends a compiler-generated base class that declares the injected fields.
 *
 * <h3>Fixture layout</h3>
 * <ul>
 *   <li>{@code TestViewBase}: compiler-generated Java base class with
 *       {@code protected Button myButton1;}</li>
 *   <li>{@code TestView extends TestViewBase}: user-authored code-behind referenced by
 *       {@code fx:subclass="test.TestView"}, containing a use site in a method body.</li>
 *   <li>{@code TestView.fxml}: FXML file with {@code <Button fx:id="myButton1"/>}
 *       (the canonical declaration) and a binding use
 *       {@code disable="${!myButton1.disabled}"}.</li>
 * </ul>
 *
 * <h3>Navigation model</h3>
 * <pre>
 * Cursor on ...                        Ctrl+click navigates to ...
 * --------------------------------------------------------------------------
 * FXML fx:id declaration (canonical)  generated field + code use + binding use
 * FXML binding use                    FXML fx:id declaration
 * Code-behind use                     FXML fx:id declaration
 * Generated field declaration         FXML fx:id declaration
 * </pre>
 */
@SuppressWarnings("UnstableApiUsage")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2FxIdNavigationTest extends Fxml2TestBase {

    /** Add the {@code @ComponentView} annotation class once for all tests. */
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

    @BeforeEach
    void addClasses() {
        // Compiler-generated base class: declares the injected field.
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                import javafx.scene.control.Button;
                public abstract class TestViewBase extends BorderPane {
                    protected Button myButton1;
                    protected void initializeComponent() {}
                }
                """);

        // User-authored code-behind: uses myButton1 in a method body.
        getFixture().addClass("""
                package test;
                public class TestView extends TestViewBase {
                    public void setup() {
                        myButton1.setDisable(true);
                    }
                }
                """);
    }

    private static final String FXML_IMPORTS =
            "javafx.scene.control.Button\njavafx.scene.layout.VBox";

    /** FXML body containing a declaration site and a binding use site. */
    private static final String FXML_BODY =
            """
              <VBox>
                <Button fx:id="myButton1" text="Click me"/>
              </VBox>
              <Button disable="${!myButton1.disabled}"/>
            """;

    // -----------------------------------------------------------------------
    // Declaration provider
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2FxIdDeclarationProvider} must return a declaration for the {@code fx:id}
     * attribute value: both when called with the {@link XmlAttributeValue} node itself and
     * with its leaf child token (the platform walks the PSI tree bottom-up and calls the
     * provider with tokens first).
     * This declaration is what tells IntelliJ to show the "Show Usages" popup instead of
     * the bare "Choose Declaration" list when the user Ctrl+clicks on the fx:id name.
     */
    @Test
    void fxIdValueIsRegisteredAsSymbolDeclaration() {
        getFixture().configureByText("TestView.fxml",
                fxml(FXML_IMPORTS, FXML_BODY));

        ReadAction.run(() -> {
            XmlAttributeValue fxIdVal = findFxIdValueElement();
            assertNotNull(fxIdVal, "fx:id value element not found");

            var provider = new Fxml2FxIdDeclarationProvider();

            // XmlAttributeValue node itself.
            var decls = provider.getDeclarations(fxIdVal, -1);
            assertFalse(decls.isEmpty(),
                    "Provider must return a declaration for the fx:id XmlAttributeValue");
            assertSame(fxIdVal, decls.iterator().next().getDeclaringElement());
            assertNotNull(decls.iterator().next().getSymbol());

            // Leaf child token (platform walks bottom-up and passes tokens first).
            PsiElement leafToken = fxIdVal.getFirstChild();
            assertNotNull(leafToken, "XmlAttributeValue must have a child token");
            var declsFromToken = provider.getDeclarations(leafToken, -1);
            assertFalse(declsFromToken.isEmpty(),
                    "Provider must also match when called with the child token of the fx:id value");
        });
    }

    /**
     * {@link Fxml2FxIdDeclarationProvider} must return no declarations for an
     * {@link XmlAttributeValue} that is not an {@code fx:id} attribute.
     */
    @Test
    void nonFxIdAttributeValueIsNotADeclaration() {
        getFixture().configureByText("TestView.fxml",
                fxml(FXML_IMPORTS, FXML_BODY));

        ReadAction.run(() -> {
            XmlAttributeValue textVal = null;
            for (var tag : com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                    getFixture().getFile(), XmlTag.class)) {
                XmlAttribute textAttr = tag.getAttribute("text");
                if (textAttr != null) {
                    textVal = textAttr.getValueElement();
                    break;
                }
            }
            assertNotNull(textVal, "Could not find a 'text' attribute value in the FXML");

            var provider = new Fxml2FxIdDeclarationProvider();
            assertTrue(provider.getDeclarations(textVal, -1).isEmpty(),
                    "Non-fx:id attributes must not be registered as declarations");
        });
    }

    /**
     * The {@link com.intellij.model.psi.PsiSymbolDeclaration#getRangeInDeclaringElement()}
     * must cover the value text only, excluding the surrounding quote characters.
     * This range controls which text is underlined on Ctrl+hover and which range is
     * highlighted at the declaration site during "Highlight Usages".
     */
    @Test
    void declarationRangeExcludesQuotes() {
        getFixture().configureByText("TestView.fxml",
                fxml(FXML_IMPORTS, FXML_BODY));

        ReadAction.run(() -> {
            XmlAttributeValue fxIdVal = findFxIdValueElement();
            assertNotNull(fxIdVal);

            var decls = new Fxml2FxIdDeclarationProvider().getDeclarations(fxIdVal, -1);
            assertFalse(decls.isEmpty());

            var range = decls.iterator().next().getRangeInDeclaringElement();
            assertEquals(1, range.getStartOffset(),
                    "Declaration range must start at 1 (after the opening quote), got: " + range);
            assertEquals(fxIdVal.getTextLength() - 1, range.getEndOffset(),
                    "Declaration range must end before the closing quote, got: " + range);
        });
    }

    /**
     * The declaration provider must fire for a child value-text token at offset 0
     * and for the {@link XmlAttributeValue} itself at offset 1 (first char of value text).
     * The returned range must contain offset 1, so the platform's offset-containment filter
     * passes and the declaration site is highlighted together with use sites.
     */
    @Test
    void declarationSiteIsHighlightedTogether() {
        getFixture().configureByText("TestView.fxml",
                fxml(FXML_IMPORTS, FXML_BODY));

        ReadAction.run(() -> {
            XmlAttributeValue fxIdVal = findFxIdValueElement();
            assertNotNull(fxIdVal);

            var provider = new Fxml2FxIdDeclarationProvider();

            PsiElement token = fxIdVal.getFirstChild();           // opening quote token
            PsiElement valueToken = token != null ? token.getNextSibling() : null; // value text token
            assertNotNull(valueToken, "Expected a child value token inside XmlAttributeValue");

            assertFalse(provider.getDeclarations(valueToken, 0).isEmpty(),
                    "Declaration provider must fire for the value text token");

            var declsAtOffset1 = provider.getDeclarations(fxIdVal, 1);
            assertFalse(declsAtOffset1.isEmpty(),
                    "Declaration provider must fire at offset 1 (first char of value text)");
            assertTrue(declsAtOffset1.iterator().next().getRangeInDeclaringElement().containsOffset(1),
                    "Declaration range must contain offset 1");
        });
    }

    /**
     * Ctrl+hover over the fx:id value must show documentation for the code-behind
     * {@link PsiField} (e.g. "Button myButton1"), not generic XML attribute documentation.
     * The hover content is derived from the symbol's PSI element via
     * {@code psiDocumentationTargets}, so the symbol must wrap the {@link PsiField}.
     */
    @Test
    void declarationSymbolIsFieldForHoverDocumentation() {
        getFixture().configureByText("TestView.fxml",
                fxml(FXML_IMPORTS, FXML_BODY));

        ReadAction.run(() -> {
            XmlAttributeValue fxIdVal = findFxIdValueElement();
            assertNotNull(fxIdVal);

            var provider = new Fxml2FxIdDeclarationProvider();
            var symbolService = com.intellij.model.psi.PsiSymbolService.getInstance();

            // Token-level call (how the platform calls during ctrl-hover).
            PsiElement token = fxIdVal.getFirstChild();
            assertNotNull(token, "XmlAttributeValue must have child tokens");
            var decls = provider.getDeclarations(token, 0);
            assertFalse(decls.isEmpty(), "Provider must return a declaration for the token");
            PsiElement extracted = symbolService.extractElementFromSymbol(
                    decls.iterator().next().getSymbol());
            assertInstanceOf(PsiField.class, extracted,
                    "Symbol must wrap the PsiField for hover docs, not "
                    + (extracted == null ? "null" : extracted.getClass().getSimpleName()));
            assertEquals("myButton1", ((PsiField) extracted).getName());

            // XmlAttributeValue-level call.
            var declsFromAttrVal = provider.getDeclarations(fxIdVal, 1);
            assertFalse(declsFromAttrVal.isEmpty());
            PsiElement extractedFromAttrVal = symbolService.extractElementFromSymbol(
                    declsFromAttrVal.iterator().next().getSymbol());
            assertInstanceOf(PsiField.class, extractedFromAttrVal,
                    "Symbol from XmlAttributeValue-level call must also wrap PsiField");
        });
    }

    // -----------------------------------------------------------------------
    // Self-reference on the fx:id declaration
    // -----------------------------------------------------------------------

    /**
     * The {@link Fxml2FxIdReference} placed on an {@code fx:id} attribute value must
     * resolve to {@code null}: the fx:id is a declaration, not a use-site reference.
     * Returning a non-null target would cause the platform to route Ctrl+click to GTD
     * instead of the "Show Usages" popup.
     */
    @Test
    void fxIdSelfReferenceResolvesToNull() {
        getFixture().configureByText("TestView.fxml",
                fxml(FXML_IMPORTS, FXML_BODY));

        ReadAction.run(() -> {
            XmlAttributeValue fxIdVal = findFxIdValueElement();
            assertNotNull(fxIdVal);

            Fxml2FxIdReference selfRef = Arrays.stream(fxIdVal.getReferences())
                    .filter(r -> r instanceof Fxml2FxIdReference)
                    .map(r -> (Fxml2FxIdReference) r)
                    .findFirst().orElse(null);
            assertNotNull(selfRef, "No Fxml2FxIdReference on fx:id value");
            assertNull(selfRef.resolve(),
                    "Self-reference must resolve to null so Ctrl+click shows 'Show Usages', not GTD");
        });
    }

    // -----------------------------------------------------------------------
    // Ctrl+click navigation
    // -----------------------------------------------------------------------

    /**
     * Ctrl+clicking on the first path segment of a binding expression that references
     * an {@code fx:id} name must navigate to the canonical fx:id declaration
     * ({@link XmlAttributeValue} of {@code fx:id="myButton1"}).
     */
    @Test
    void ctrlClickFxmlBindingUseNavigatesToFxIdDeclaration() {
        getFixture().configureByText("TestView.fxml",
                fxml(FXML_IMPORTS, FXML_BODY));

        ReadAction.run(() -> {
            XmlAttributeValue fxIdVal = findFxIdValueElement();
            assertNotNull(fxIdVal);

            XmlAttributeValue bindingAttrVal = findAttrValueContaining();
            assertNotNull(bindingAttrVal, "Binding attribute value not found");

            Fxml2BindingSegmentReference segRef = Arrays.stream(bindingAttrVal.getReferences())
                    .filter(r -> r instanceof Fxml2BindingSegmentReference s
                            && "myButton1".equals(s.getCanonicalText()))
                    .map(r -> (Fxml2BindingSegmentReference) r)
                    .findFirst().orElse(null);
            assertNotNull(segRef, "No segment reference for 'myButton1'");

            PsiElement resolved = segRef.resolve();
            assertNotNull(resolved);
            assertInstanceOf(PsiField.class, resolved);
            assertEquals(fxIdVal, resolved.getNavigationElement(),
                    "Binding segment must navigate to the fx:id XmlAttributeValue");
        });
    }

    /**
     * Ctrl+clicking on an {@code fx:id}-injected field reference in the code-behind body
     * must navigate to the canonical fx:id declaration in the FXML file.
     */
    @Test
    void ctrlClickCodeBehindUseNavigatesToFxIdDeclaration() {
        getFixture().configureByText("TestView.fxml",
                fxml(FXML_IMPORTS, FXML_BODY));

        PsiField generatedField = ReadAction.compute(() -> {
            var facade = com.intellij.psi.JavaPsiFacade.getInstance(getFixture().getProject());
            var base = facade.findClass("test.TestViewBase",
                    com.intellij.psi.search.GlobalSearchScope.allScope(getFixture().getProject()));
            assertNotNull(base, "TestViewBase not found");
            return base.findFieldByName("myButton1", false);
        });
        assertNotNull(generatedField, "myButton1 field not found in TestViewBase");

        ReadAction.run(() -> {
            XmlAttributeValue fxIdVal = findFxIdValueElement();
            assertNotNull(fxIdVal);

            PsiElement[] targets = new Fxml2FxIdCodeBehindGotoHandler()
                    .getGotoDeclarationTargets(generatedField.getNameIdentifier(), 0, null);
            assertNotNull(targets, "Handler must return targets for a code-behind use");
            assertTrue(Arrays.asList(targets).contains(fxIdVal),
                    "Target must be the fx:id XmlAttributeValue");
        });
    }

    /**
     * Ctrl+clicking on the generated field declaration in the compiler-generated base class
     * must navigate to the canonical fx:id declaration in the FXML file, even though
     * {@code fx:subclass} points to the user-authored subclass, not the base class.
     */
    @Test
    void ctrlClickGeneratedFieldDeclarationNavigatesToFxIdDeclaration() {
        getFixture().configureByText("TestView.fxml",
                fxml(FXML_IMPORTS, FXML_BODY));

        PsiField generatedField = ReadAction.compute(() -> {
            var facade = com.intellij.psi.JavaPsiFacade.getInstance(getFixture().getProject());
            var base = facade.findClass("test.TestViewBase",
                    com.intellij.psi.search.GlobalSearchScope.allScope(getFixture().getProject()));
            assertNotNull(base);
            return base.findFieldByName("myButton1", false);
        });
        assertNotNull(generatedField);

        ReadAction.run(() -> {
            XmlAttributeValue fxIdVal = findFxIdValueElement();
            assertNotNull(fxIdVal);

            PsiElement[] targets = new Fxml2FxIdCodeBehindGotoHandler()
                    .getGotoDeclarationTargets(generatedField.getNameIdentifier(), 0, null);
            assertNotNull(targets,
                    "Handler must return targets for the generated field in TestViewBase");
            assertTrue(Arrays.asList(targets).contains(fxIdVal),
                    "Target must be the fx:id XmlAttributeValue in the FXML file");
        });
    }

    // -----------------------------------------------------------------------
    // Find Usages
    // -----------------------------------------------------------------------

    /**
     * The {@link FindUsagesHandler} from {@link Fxml2FxIdFindUsagesHandlerFactory} must
     * return both the {@link XmlAttributeValue} and the generated field as primary elements,
     * so that a single "Find Usages" invocation shows FXML binding uses and Java/Kotlin
     * code usages together.
     */
    @Test
    void findUsagesHandlerIncludesGeneratedFieldAsPrimary() {
        getFixture().configureByText("TestView.fxml",
                fxml(FXML_IMPORTS, FXML_BODY));

        ReadAction.run(() -> {
            XmlAttributeValue fxIdVal = findFxIdValueElement();
            assertNotNull(fxIdVal);

            var factory = new Fxml2FxIdFindUsagesHandlerFactory();
            assertTrue(factory.canFindUsages(fxIdVal));

            FindUsagesHandler handler = factory.createFindUsagesHandler(fxIdVal, false);
            assertNotNull(handler);

            List<PsiElement> primaries = Arrays.asList(handler.getPrimaryElements());
            assertTrue(primaries.contains(fxIdVal),
                    "Primaries must include the XmlAttributeValue");
            assertTrue(primaries.stream()
                            .anyMatch(e -> e instanceof PsiField f && "myButton1".equals(f.getName())),
                    "Primaries must include the generated field myButton1");
        });
    }

    /**
     * When "Find Usages" is invoked on the fx:id declaration, the results must include the
     * compiler-generated field declaration in the base class, found by searching up through
     * the code-behind inheritance hierarchy (the field may be on the superclass even though
     * {@code fx:subclass} points to the user subclass).
     */
    @Test
    void findUsagesIncludesGeneratedBaseClassField() {
        getFixture().configureByText("TestView.fxml",
                fxml(FXML_IMPORTS, FXML_BODY));

        ReadAction.run(() -> {
            XmlAttributeValue fxIdVal = findFxIdValueElement();
            assertNotNull(fxIdVal);

            var factory = new Fxml2FxIdFindUsagesHandlerFactory();
            assertTrue(factory.canFindUsages(fxIdVal));
            var handler = factory.createFindUsagesHandler(fxIdVal, false);
            assertNotNull(handler);

            boolean hasField = Arrays.stream(handler.getPrimaryElements())
                    .anyMatch(e -> e instanceof PsiField f && "myButton1".equals(f.getName()));
            assertTrue(hasField,
                    "getPrimaryElements() must include the generated field 'myButton1' from TestViewBase");
        });
    }

    /**
     * "Find Usages" on the fx:id declaration must return all three entries:
     * the FXML binding use site, the Java code-behind use site, and the compiler-generated
     * field declaration: and must not include the fx:id declaration line itself.
     */
    @Test
    void findUsagesFromFxIdContainsAllThreeEntries() {
        getFixture().configureByText("TestView.fxml",
                fxml(FXML_IMPORTS, FXML_BODY));

        XmlAttributeValue fxIdVal = findFxIdValueElement();
        assertNotNull(fxIdVal);

        var usages = ReadAction.compute(() -> getFixture().findUsages(fxIdVal));

        ReadAction.run(() -> {
            String dump = buildUsageDump(usages);

            assertTrue(usages.stream().anyMatch(u ->
                    u.getElement() != null
                    && u.getElement().getContainingFile().getName().endsWith(".fxml")
                    && u.getElement().getText().contains("myButton1")
                    && !u.getElement().getTextRange().intersects(fxIdVal.getTextRange())),
                    "Find Usages must include the FXML binding use site.\n" + dump);

            assertTrue(usages.stream().anyMatch(u ->
                    u.getElement() != null
                    && !u.getElement().getContainingFile().getName().endsWith(".fxml")
                    && u.getElement().getText().contains("myButton1")),
                    "Find Usages must include the Java code-behind use.\n" + dump);

            assertTrue(usages.stream().anyMatch(u ->
                    u.getElement() != null
                    && u.getElement().getContainingFile().getName().contains("TestViewBase")
                    && u.getElement().getText().contains("myButton1")),
                    "Find Usages must include the generated field declaration in TestViewBase.\n" + dump);

            assertFalse(usages.stream().anyMatch(u ->
                    u.getElement() != null
                    && fxIdVal.getTextRange().contains(u.getElement().getTextRange())),
                    "Find Usages must NOT include the fx:id declaration line itself.\n" + dump);
        });
    }

    /**
     * "Find Usages" invoked from the declaration site must contain both FXML and Java
     * use sites, and must not include the declaration itself.
     */
    @Test
    void highlightUsagesFromDeclarationContainsBothUseSitesNotDeclaration() {
        getFixture().configureByText("TestView.fxml",
                fxml(FXML_IMPORTS, FXML_BODY));

        XmlAttributeValue fxIdVal = findFxIdValueElement();
        assertNotNull(fxIdVal, "fx:id value element not found");

        var usages = ReadAction.compute(() -> getFixture().findUsages(fxIdVal));

        ReadAction.run(() -> {
            String dump = buildUsageDump(usages);

            assertFalse(usages.stream().anyMatch(u ->
                    u.getElement() != null
                    && u.getElement().getContainingFile() == fxIdVal.getContainingFile()
                    && fxIdVal.getTextRange().contains(u.getElement().getTextRange())),
                    "Highlight Usages must NOT include the declaration line itself.\n" + dump);

            assertTrue(usages.stream().anyMatch(u ->
                    u.getElement() != null
                    && u.getElement().getContainingFile().getName().equals("TestView.fxml")
                    && u.getElement().getText().contains("myButton1")
                    && !fxIdVal.getTextRange().contains(u.getElement().getTextRange())),
                    "Highlight Usages must include the FXML binding use site.\n" + dump);

            assertTrue(usages.stream().anyMatch(u ->
                    u.getElement() != null
                    && !u.getElement().getContainingFile().getName().endsWith(".fxml")
                    && u.getElement().getText().contains("myButton1")),
                    "Highlight Usages must include the Java code-behind use.\n" + dump);
        });
    }

    /**
     * "Find Usages" must not list the fx:id declaration as a usage of itself.
     * {@link ReferencesSearch} on the declaration element must not return a reference
     * whose element is the declaration.
     */
    @Test
    void findUsagesDoesNotIncludeDeclaration() {
        getFixture().configureByText("TestView.fxml",
                fxml(FXML_IMPORTS, FXML_BODY));

        ReadAction.run(() -> {
            XmlAttributeValue fxIdVal = findFxIdValueElement();
            assertNotNull(fxIdVal);

            Collection<PsiReference> refs = new java.util.ArrayList<>(
                    ReferencesSearch.search(fxIdVal,
                            com.intellij.psi.search.GlobalSearchScope.fileScope(
                                    getFixture().getProject(),
                                    fxIdVal.getContainingFile().getVirtualFile())).findAll());

            assertFalse(refs.stream().anyMatch(ref ->
                    ref.getElement() instanceof XmlAttributeValue av && av == fxIdVal),
                    "The fx:id declaration must not appear in its own Find Usages results");
        });
    }

    /**
     * {@link ReferencesSearch} on the generated field must include the
     * {@code fx:id} {@link XmlAttributeValue} as a usage, even though the field is
     * declared in the base class while {@code fx:subclass} points to the subclass.
     */
    @Test
    void referencesSearchOnGeneratedFieldFindsXmlFxIdValue() {
        getFixture().configureByText("TestView.fxml",
                fxml(FXML_IMPORTS, FXML_BODY));

        PsiField generatedField = ReadAction.compute(() -> {
            var facade = com.intellij.psi.JavaPsiFacade.getInstance(getFixture().getProject());
            var base = facade.findClass("test.TestViewBase",
                    com.intellij.psi.search.GlobalSearchScope.allScope(getFixture().getProject()));
            assertNotNull(base);
            return base.findFieldByName("myButton1", false);
        });
        assertNotNull(generatedField);

        XmlAttributeValue fxIdVal = findFxIdValueElement();
        assertNotNull(fxIdVal);

        Collection<PsiReference> refs = ReadAction.compute(() ->
                ReferencesSearch.search(generatedField,
                        com.intellij.psi.search.GlobalSearchScope.allScope(
                                getFixture().getProject())).findAll());

        assertTrue(refs.stream()
                        .anyMatch(r -> r instanceof Fxml2FxIdReference ref
                                && ref.getElement() == fxIdVal),
                "ReferencesSearch on the generated field must find the fx:id XmlAttributeValue");
    }

    /**
     * {@link Fxml2FxIdFindUsagesHandlerFactory#canFindUsages} must accept the leaf
     * {@code XmlToken} child of an fx:id value, not just the {@link XmlAttributeValue}
     * itself.  The platform passes the leaf token when highlight-usages fires from the
     * declaration site, so both must be accepted for the declaration to be highlighted.
     */
    @Test
    void canFindUsagesAcceptsChildToken() {
        getFixture().configureByText("TestView.fxml",
                fxml(FXML_IMPORTS, FXML_BODY));

        ReadAction.run(() -> {
            XmlAttributeValue fxIdVal = findFxIdValueElement();
            assertNotNull(fxIdVal);

            PsiElement valueToken = fxIdVal.getFirstChild() != null
                    ? fxIdVal.getFirstChild().getNextSibling()
                    : null;
            if (valueToken == null) valueToken = fxIdVal.getFirstChild();
            assertNotNull(valueToken, "XmlAttributeValue must have a child token");

            var factory = new Fxml2FxIdFindUsagesHandlerFactory();
            assertTrue(factory.canFindUsages(valueToken),
                    "canFindUsages must accept a child XmlToken of an fx:id value. "
                    + "Token class: " + valueToken.getClass().getSimpleName()
                    + ", text: '" + valueToken.getText() + "'");
        });
    }

    /**
     * When the declaration symbol wraps the real {@link PsiField} from the
     * compiler-generated base class (for hover documentation), the platform extracts that
     * field and calls {@link Fxml2FxIdFindUsagesHandlerFactory#canFindUsages} with it.
     * Unlike a synthetic {@code LightFieldBuilder}, a real Java field's
     * {@code getNavigationElement()} returns itself, so the factory must fall back to a
     * project-index lookup to identify it as an fx:id field.
     */
    @Test
    void canFindUsagesAcceptsRealJavaFieldAndIncludesItsDeclaration() {
        getFixture().configureByText("TestView.fxml",
                fxml(FXML_IMPORTS, FXML_BODY));

        PsiField realField = ReadAction.compute(() -> {
            var facade = com.intellij.psi.JavaPsiFacade.getInstance(getFixture().getProject());
            var base = facade.findClass("test.TestViewBase",
                    com.intellij.psi.search.GlobalSearchScope.allScope(getFixture().getProject()));
            assertNotNull(base, "TestViewBase not found");
            PsiField f = base.findFieldByName("myButton1", false);
            assertNotNull(f, "myButton1 field not found in TestViewBase");
            assertSame(f, f.getNavigationElement(),
                    "Precondition: real Java field's nav element must be itself");
            return f;
        });

        var factory = new Fxml2FxIdFindUsagesHandlerFactory();
        assertTrue(ReadAction.compute(() -> factory.canFindUsages(realField)),
                "canFindUsages must accept a real Java PsiField corresponding to an fx:id");

        FindUsagesHandler handler = ReadAction.compute(
                () -> factory.createFindUsagesHandler(realField, false));
        assertNotNull(handler);

        var usages = ReadAction.compute(() -> getFixture().findUsages(realField));
        ReadAction.run(() -> {
            String dump = buildUsageDump(usages);
            assertTrue(usages.stream().anyMatch(u ->
                    u.getElement() != null
                    && u.getElement().getContainingFile().getName().contains("TestViewBase")
                    && u.getElement().getText().contains("myButton1")),
                    "Find Usages on the real Java field must include the field declaration.\n" + dump);
        });
    }

    /**
     * When the declaration symbol wraps the real {@link PsiField}, the platform calls
     * {@code isReferenceTo(realField)} on every reference in the file to collect
     * highlight targets.  {@link Fxml2BindingSegmentReference#isReferenceTo} must return
     * {@code true} in this case so that binding-expression uses are highlighted when the
     * cursor is placed on the fx:id declaration.
     */
    @Test
    void bindingSegmentIsReferenceToRealJavaFieldHighlightsFromDeclaration() {
        getFixture().configureByText("TestView.fxml",
                fxml(FXML_IMPORTS, FXML_BODY));

        ReadAction.run(() -> {
            XmlAttributeValue fxIdVal = findFxIdValueElement();
            assertNotNull(fxIdVal);

            var facade = com.intellij.psi.JavaPsiFacade.getInstance(getFixture().getProject());
            var base = facade.findClass("test.TestViewBase",
                    com.intellij.psi.search.GlobalSearchScope.allScope(getFixture().getProject()));
            assertNotNull(base, "TestViewBase not found");
            PsiField realField = base.findFieldByName("myButton1", false);
            assertNotNull(realField, "myButton1 not found in TestViewBase");

            XmlAttributeValue bindingVal = findAttrValueContaining();
            assertNotNull(bindingVal, "Binding attribute value not found");

            Fxml2BindingSegmentReference segRef = Arrays.stream(bindingVal.getReferences())
                    .filter(r -> r instanceof Fxml2BindingSegmentReference s
                            && "myButton1".equals(s.getCanonicalText()))
                    .map(r -> (Fxml2BindingSegmentReference) r)
                    .findFirst().orElse(null);
            assertNotNull(segRef, "No Fxml2BindingSegmentReference for 'myButton1'");

            assertTrue(segRef.isReferenceTo(realField),
                    "Fxml2BindingSegmentReference.isReferenceTo must return true for the real "
                    + "Java field from the generated base class");
        });
    }

    // -----------------------------------------------------------------------
    // Threading
    // -----------------------------------------------------------------------

    /**
     * {@link com.intellij.find.findUsages.FindUsagesHandler#processElementUsages} is called
     * by IntelliJ from a background thread without a read lock.  All PSI access inside the
     * method must be wrapped in {@code ReadAction.compute/run}: in particular,
     * constructing {@code UsageInfo} requires the read lock because the constructor calls
     * {@code getContainingFile()} and {@code createSmartPsiElementPointer()}.
     */
    @Test
    void processElementUsagesIsSafeOffReadAction() throws Exception {
        getFixture().configureByText("TestView.fxml",
                fxml(FXML_IMPORTS, FXML_BODY));

        XmlAttributeValue fxIdVal = ReadAction.compute(this::findFxIdValueElement);
        assertNotNull(fxIdVal);

        var factory = new Fxml2FxIdFindUsagesHandlerFactory();
        FindUsagesHandler handler = ReadAction.compute(
                () -> factory.createFindUsagesHandler(fxIdVal, false));
        assertNotNull(handler);

        var options = ReadAction.compute(
                () -> new com.intellij.find.findUsages.FindUsagesOptions(getFixture().getProject()));

        PsiElement[] primaries = ReadAction.compute(handler::getPrimaryElements);
        PsiElement fieldPrimary = Arrays.stream(primaries)
                .filter(e -> e instanceof PsiField)
                .findFirst().orElse(null);
        assertNotNull(fieldPrimary, "Handler must have a field primary");

        var errors = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var thread = new Thread(() -> {
            try {
                handler.processElementUsages(fieldPrimary, u -> true, options);
            } catch (Throwable t) {
                errors.set(t);
            }
        });
        thread.start();
        thread.join(10_000);
        assertFalse(thread.isAlive(), "processElementUsages did not finish in 10 s");
        assertNull(errors.get(),
                "processElementUsages must not throw when called without a read lock: "
                + errors.get());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Returns the {@link XmlAttributeValue} of {@code fx:id="myButton1"} in the test file. */
    private XmlAttributeValue findFxIdValueElement() {
        return ReadAction.compute(() -> {
            for (var tag : com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                    getFixture().getFile(), XmlTag.class)) {
                XmlAttribute idAttr = tag.getAttribute("fx:id");
                if (idAttr != null && "myButton1".equals(idAttr.getValue())) {
                    return idAttr.getValueElement();
                }
            }
            return null;
        });
    }

    /** Returns the {@link XmlAttributeValue} of the {@code disable} binding attribute. */
    private XmlAttributeValue findAttrValueContaining() {
        return ReadAction.compute(() -> {
            for (var attrVal : com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                    getFixture().getFile(), XmlAttributeValue.class)) {
                if (attrVal.getText().contains("${!myButton1.disabled}")) return attrVal;
            }
            return null;
        });
    }

    /** Builds a diagnostic dump of usages for assertion failure messages. */
    private static String buildUsageDump(java.util.Collection<com.intellij.usageView.UsageInfo> usages) {
        return usages.stream()
                .map(u -> "  file=" + (u.getElement() == null ? "null" :
                        u.getElement().getContainingFile().getName())
                        + " text='" + (u.getElement() == null ? "null" : u.getElement().getText()) + "'")
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    // -----------------------------------------------------------------------
    // Embedded FXML (@ComponentView): navigation tests
    // -----------------------------------------------------------------------

    /**
     * Returns the {@link XmlFile} injected from the {@code @ComponentView} annotation of the
     * first annotated class found in the current fixture file.
     */
    private XmlFile getEmbeddedXmlFile() {
        return ReadAction.compute(() -> {
            for (PsiClass c : PsiTreeUtil.findChildrenOfType(
                    getFixture().getFile(), PsiClass.class)) {
                if (c.hasAnnotation(Fxml2EmbeddedUtil.MARKUP_ANNOTATION_FQN)) {
                    return Fxml2EmbeddedUtil.getInjectedXmlFile(c);
                }
            }
            return null;
        });
    }

    /** Returns the {@link XmlAttributeValue} of the first {@code fx:id} attribute
     *  whose value matches {@code name} in the given injected {@link XmlFile}. */
    @SuppressWarnings("SameParameterValue")
    private static XmlAttributeValue findEmbeddedFxId(String name, XmlFile xmlFile) {
        return ReadAction.compute(() -> {
            for (var tag : PsiTreeUtil.findChildrenOfType(xmlFile, XmlTag.class)) {
                XmlAttribute idAttr = tag.getAttribute("fx:id");
                if (idAttr != null && name.equals(idAttr.getValue())) {
                    return idAttr.getValueElement();
                }
            }
            return null;
        });
    }

    /**
     * {@link Fxml2FxIdDeclarationProvider} must return a declaration for an {@code fx:id}
     * attribute value in embedded FXML: just as it does for standalone FXML files.
     */
    @Test
    void fxIdInEmbeddedFxml2IsRegisteredAsDeclaration() {
        // TestViewBase (with myButton1) is added by @BeforeEach addClasses().
        getFixture().configureByText("EmbNavDecl.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <BorderPane>
                      <Button fx:id="myButton1"/>
                    </BorderPane>
                    \""")
                public class EmbNavDecl extends TestViewBase {
                    public EmbNavDecl() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = getEmbeddedXmlFile();
            assertNotNull(xmlFile, "Injected XmlFile must be present");
            XmlAttributeValue fxIdVal = findEmbeddedFxId("myButton1", xmlFile);
            assertNotNull(fxIdVal, "fx:id='myButton1' not found in injected XML");

            var provider = new Fxml2FxIdDeclarationProvider();
            var decls = provider.getDeclarations(fxIdVal, -1);
            assertFalse(decls.isEmpty(),
                    "FxIdDeclarationProvider must return a declaration for the embedded fx:id value");
            assertSame(fxIdVal, decls.iterator().next().getDeclaringElement(),
                    "Declaration element must be the embedded fx:id XmlAttributeValue");
        });
    }

    /**
     * {@link Fxml2FxIdCodeBehindGotoHandler#getGotoDeclarationTargets} invoked on the
     * name identifier of a generated field must return the {@code fx:id}
     * {@link XmlAttributeValue} from embedded FXML.
     */
    @Test
    void ctrlClickFieldNavigatesToEmbeddedFxId() {
        // TestViewBase (with myButton1) is added by @BeforeEach addClasses().
        getFixture().configureByText("EmbNavGoto.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <BorderPane>
                      <Button fx:id="myButton1"/>
                    </BorderPane>
                    \""")
                public class EmbNavGoto extends TestViewBase {
                    public EmbNavGoto() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        XmlAttributeValue fxIdVal = ReadAction.compute(() -> {
            XmlFile xmlFile = getEmbeddedXmlFile();
            assertNotNull(xmlFile);
            return findEmbeddedFxId("myButton1", xmlFile);
        });
        assertNotNull(fxIdVal, "fx:id='myButton1' not found in injected XML");

        ReadAction.run(() -> {
            var facade = com.intellij.psi.JavaPsiFacade.getInstance(getFixture().getProject());
            var scope = com.intellij.psi.search.GlobalSearchScope.allScope(getFixture().getProject());
            var base = facade.findClass("test.TestViewBase", scope);
            assertNotNull(base, "TestViewBase not found");
            PsiField field = base.findFieldByName("myButton1", false);
            assertNotNull(field, "myButton1 not found in TestViewBase");

            PsiElement[] targets = new Fxml2FxIdCodeBehindGotoHandler()
                    .getGotoDeclarationTargets(field.getNameIdentifier(), 0, null);
            assertNotNull(targets, "GotoDeclarationHandler must return targets for embedded fx:id");
            assertTrue(Arrays.asList(targets).contains(fxIdVal),
                    "Target must include the embedded fx:id XmlAttributeValue.\n"
                    + "Targets: " + Arrays.stream(targets)
                            .map(t -> t.getClass().getSimpleName() + " in "
                                      + t.getContainingFile().getName())
                            .toList());
        });
    }

    /**
     * {@link Fxml2FxIdFindUsagesHandlerFactory} invoked on an {@code fx:id} in embedded
     * FXML must return a handler whose primary elements include the code-behind field
     * from the host class hierarchy: the same as for standalone FXML.
     */
    @Test
    void findUsagesHandlerOnEmbeddedFxIdIncludesCodeBehindField() {
        // TestViewBase (with myButton1) is added by @BeforeEach addClasses().
        getFixture().configureByText("EmbNavFu.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <BorderPane>
                      <Button fx:id="myButton1"/>
                    </BorderPane>
                    \""")
                public class EmbNavFu extends TestViewBase {
                    public EmbNavFu() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = getEmbeddedXmlFile();
            assertNotNull(xmlFile);
            XmlAttributeValue fxIdVal = findEmbeddedFxId("myButton1", xmlFile);
            assertNotNull(fxIdVal, "fx:id='myButton1' not found in injected XML");

            var factory = new Fxml2FxIdFindUsagesHandlerFactory();
            assertTrue(factory.canFindUsages(fxIdVal),
                    "FindUsagesHandlerFactory must accept embedded fx:id XmlAttributeValue");
            FindUsagesHandler handler = factory.createFindUsagesHandler(fxIdVal, false);
            assertNotNull(handler);

            java.util.List<PsiElement> primaries = Arrays.asList(handler.getPrimaryElements());
            assertTrue(primaries.contains(fxIdVal),
                    "Primaries must include the embedded fx:id XmlAttributeValue");
            assertTrue(primaries.stream()
                            .anyMatch(e -> e instanceof PsiField f && "myButton1".equals(f.getName())),
                    "Primaries must include the code-behind field 'myButton1'.\nPrimaries: "
                    + primaries.stream()
                            .map(p -> p.getClass().getSimpleName() + "(" + p.getText() + ")")
                            .toList());
        });
    }
}
