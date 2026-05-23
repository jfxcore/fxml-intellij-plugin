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
import com.intellij.testFramework.EdtTestUtil;
import org.jfxcore.fxml.lang.Fxml2BindingSegmentReference;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.jfxcore.fxml.lang.Fxml2FxIdDeclarationProvider;
import org.jfxcore.fxml.lang.Fxml2FxIdFindUsagesHandlerFactory;
import org.jfxcore.fxml.lang.Fxml2FxIdReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@code fx:id} attribute value references:
 * <ul>
 *   <li>Self-reference on the definition site (for "highlight usages")</li>
 *   <li>Navigation to the code-behind field when one exists</li>
 *   <li>{@link Fxml2BindingSegmentReference#isReferenceTo} matches the fx:id definition</li>
 *   <li>Embedded FXML2 ({@code @ComponentView} annotation): equivalent coverage for injected XML</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2FxIdReferenceTest extends Fxml2TestBase {

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
    void addCodeBehind() {
        // Minimal compiler-generated base class
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public abstract class TestViewBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        // Code-behind with an fx:id-injected field "myBtn"
        getFixture().addClass("""
                package test;
                import javafx.fxml.FXML;
                import javafx.scene.control.Button;
                public class TestView extends TestViewBase {
                    @FXML public Button myBtn;
                }
                """);
    }

    // -----------------------------------------------------------------------
    // Self-reference (definition site)
    // -----------------------------------------------------------------------

    /**
     * A {@link Fxml2FxIdReference} should be present on the fx:id attribute value.
     * Its {@link Fxml2FxIdReference#resolve()} must return {@code null}: the fx:id is
     * a declaration, not a use-site reference.  Returning null prevents the platform from
     * treating it as a reference with a navigation target, which would override the
     * {@link Fxml2FxIdDeclarationProvider} declaration and block the "Show Usages" popup.
     */
    @Test
    void fxIdDefinitionHasSelfReference() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button fx:id="my<caret>Btn"/>
                """
        ));

        ReadAction.run(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue attrVal = com.intellij.psi.util.PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            assertNotNull(attrVal, "No XmlAttributeValue at caret");

            Fxml2FxIdReference selfRef = Arrays.stream(attrVal.getReferences())
                    .filter(r -> r instanceof Fxml2FxIdReference)
                    .map(r -> (Fxml2FxIdReference) r)
                    .findFirst().orElse(null);
            assertNotNull(selfRef, "No Fxml2FxIdReference found on fx:id value");
            assertNull(selfRef.resolve(),
                    "Self-reference must resolve to null - the fx:id is a declaration, " +
                    "not a reference; navigation is handled by Fxml2FxIdDeclarationProvider");
        });
    }

    // -----------------------------------------------------------------------
    // Code-behind field: FindUsagesHandlerFactory
    // -----------------------------------------------------------------------

    /**
     * Navigation from the {@code fx:id} declaration to the code-behind field is provided
     * by {@link Fxml2FxIdFindUsagesHandlerFactory}, not by a direct reference on the
     * {@link XmlAttributeValue}.  No reference resolving to a {@link PsiField} should be
     * present: having such a reference would cause IntelliJ's default GotoDeclarationHandler
     * to intercept Ctrl+click on the declaration and navigate straight to the generated field
     * instead of showing the "Show Usages" popup.
     */
    @Test
    void fxIdDefinitionHasNoDirectFieldReference() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button fx:id="my<caret>Btn"/>
                """
        ));

        ReadAction.run(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue attrVal = com.intellij.psi.util.PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            assertNotNull(attrVal, "No XmlAttributeValue at caret");

            // There must be NO reference resolving to a PsiField: that would hijack Ctrl+click.
            PsiReference fieldRef = Arrays.stream(attrVal.getReferences())
                    .filter(r -> r.resolve() instanceof PsiField)
                    .findFirst().orElse(null);
            assertNull(fieldRef,
                    "fx:id declaration must not carry a direct field reference; "
                    + "use FindUsagesHandlerFactory instead");
        });
    }

    // -----------------------------------------------------------------------
    // isReferenceTo: binding uses should reference the definition
    // -----------------------------------------------------------------------

    /**
     * A binding expression {@code ${myBtn.text}} in the FXML file creates a
     * {@link Fxml2BindingSegmentReference} for the {@code myBtn} segment.
     * That reference's {@link Fxml2BindingSegmentReference#isReferenceTo} should return
     * {@code true} for the fx:id {@link XmlAttributeValue} of {@code <Button fx:id="myBtn"/>}.
     */
    @Test
    void bindingSegmentIsReferenceToFxIdDefinition() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button\njavafx.scene.control.Label",
                """
                  <Button fx:id="myBtn"/>
                  <Label text="${<caret>myBtn.text}"/>
                """
        ));

        ReadAction.run(() -> {
            // Find the XmlAttributeValue for fx:id="myBtn"
            XmlAttributeValue fxIdValueEl = findFxIdValueElement("myBtn");
            assertNotNull(fxIdValueEl, "Could not find fx:id=\"myBtn\" value element");

            // Find the binding segment reference at the caret
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue bindingAttrVal = com.intellij.psi.util.PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            assertNotNull(bindingAttrVal, "No XmlAttributeValue at caret");

            int relOffset = offset - bindingAttrVal.getTextRange().getStartOffset();
            Fxml2BindingSegmentReference segRef = Arrays.stream(bindingAttrVal.getReferences())
                    .filter(r -> r instanceof Fxml2BindingSegmentReference)
                    .map(r -> (Fxml2BindingSegmentReference) r)
                    .filter(r -> r.getRangeInElement().containsOffset(relOffset))
                    .findFirst().orElse(null);
            assertNotNull(segRef, "No Fxml2BindingSegmentReference at caret");

            // The segment reference's resolve() should return a LightFieldBuilder pointing to the fx:id value
            PsiElement resolved = segRef.resolve();
            assertNotNull(resolved, "Binding segment for 'myBtn' should resolve (to a LightFieldBuilder)");
            assertInstanceOf(PsiField.class, resolved, "Should resolve to a PsiField (LightFieldBuilder)");

            // The key assertion: isReferenceTo(fxIdValueEl) must return true
            assertTrue(segRef.isReferenceTo(fxIdValueEl),
                    "Binding segment reference should isReferenceTo the fx:id XmlAttributeValue");
        });
    }

    /**
     * When there is no code-behind or the code-behind has no matching field,
     * a binding expression {@code ${unknownId.text}} should still create a
     * binding segment reference with the correct navigation target.
     */
    @Test
    void bindingSegmentNavigatesToFxIdValueElement() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button\njavafx.scene.control.Label",
                """
                  <Button fx:id="myBtn"/>
                  <Label text="${<caret>myBtn.text}"/>
                """
        ));

        ReadAction.run(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue bindingAttrVal = com.intellij.psi.util.PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            assertNotNull(bindingAttrVal);
            int relOffset = offset - bindingAttrVal.getTextRange().getStartOffset();

            Fxml2BindingSegmentReference segRef = Arrays.stream(bindingAttrVal.getReferences())
                    .filter(r -> r instanceof Fxml2BindingSegmentReference)
                    .map(r -> (Fxml2BindingSegmentReference) r)
                    .filter(r -> r.getRangeInElement().containsOffset(relOffset))
                    .findFirst().orElse(null);
            assertNotNull(segRef, "No Fxml2BindingSegmentReference at caret");

            PsiElement resolved = segRef.resolve();
            assertNotNull(resolved, "Binding segment for 'myBtn' should resolve");
            assertInstanceOf(PsiField.class, resolved);

            // Navigation element of the LightFieldBuilder should be the XmlAttributeValue
            PsiField field = (PsiField) resolved;
            PsiElement navEl = field.getNavigationElement();
            assertInstanceOf(XmlAttributeValue.class, navEl,
                    "LightFieldBuilder navigation target should be the fx:id XmlAttributeValue, not XmlAttribute");
        });
    }

    /**
     * Real-world scenario: {@code disable="${!myButton1.disabled}"}: binding with {@code !}
     * unary operator, fx:id on a deeply-nested tag.  The segment reference for
     * {@code myButton1} must resolve and {@code isReferenceTo} must match the declaration.
     */
    @Test
    void negatedBindingSegmentIsReferenceToFxIdDefinition() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button\njavafx.scene.layout.VBox",
                """
                  <VBox>
                    <Button fx:id="myButton1"/>
                  </VBox>
                  <Button disable="${!<caret>myButton1.disabled}"/>
                """
        ));

        ReadAction.run(() -> {
            XmlAttributeValue fxIdValueEl = findFxIdValueElement("myButton1");
            assertNotNull(fxIdValueEl, "Could not find fx:id=\"myButton1\" value element");

            int offset = getFixture().getCaretOffset();
            XmlAttributeValue bindingAttrVal = com.intellij.psi.util.PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            assertNotNull(bindingAttrVal, "No XmlAttributeValue at caret");

            int relOffset = offset - bindingAttrVal.getTextRange().getStartOffset();
            Fxml2BindingSegmentReference segRef = Arrays.stream(bindingAttrVal.getReferences())
                    .filter(r -> r instanceof Fxml2BindingSegmentReference)
                    .map(r -> (Fxml2BindingSegmentReference) r)
                    .filter(r -> r.getRangeInElement().containsOffset(relOffset))
                    .findFirst().orElse(null);
            assertNotNull(segRef,
                    "No Fxml2BindingSegmentReference covering caret inside ${!myButton1.disabled}");

            PsiElement resolved = segRef.resolve();
            assertNotNull(resolved, "Segment 'myButton1' should resolve (LightFieldBuilder)");
            assertInstanceOf(PsiField.class, resolved);

            assertTrue(segRef.isReferenceTo(fxIdValueEl),
                    "Binding segment isReferenceTo must return true for the fx:id XmlAttributeValue");
        });
    }

    /**
     * Highlight-usages from a <em>use</em> site: when the cursor is on {@code myButton1}
     * inside {@code ${!myButton1.disabled}}, IntelliJ resolves to a {@code LightFieldBuilder}
     * and calls {@code isReferenceTo(LightFieldBuilder)} on every reference in the file.
     * The {@link Fxml2FxIdReference} (definition) and any other segment reference for the
     * same id must both return {@code true}.
     */
    @Test
    void highlightFromUseSiteMatchesDefinitionAndOtherUses() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button\njavafx.scene.layout.VBox",
                """
                  <VBox>
                    <Button fx:id="myButton1"/>
                  </VBox>
                  <Button disable="${!myButton1.<caret>disabled}"/>
                  <Button text="${myButton1.text}"/>
                """
        ));

        ReadAction.run(() -> {
            // Resolve what the cursor (on "disabled") segment points back to via the id chain.
            // We want the LightFieldBuilder for "myButton1": find it by walking all refs on
            // the 'disable' attribute value and locating the segment for "myButton1".
            XmlAttributeValue fxIdValueEl = findFxIdValueElement("myButton1");
            assertNotNull(fxIdValueEl);

            // Find the LightFieldBuilder via the segment for "myButton1" in the first binding.
            XmlAttributeValue disableAttrVal = findAttrValueContaining("${!myButton1.disabled}");
            assertNotNull(disableAttrVal, "disable attribute value not found");

            Fxml2BindingSegmentReference myBtn1SegRef = Arrays.stream(disableAttrVal.getReferences())
                    .filter(r -> r instanceof Fxml2BindingSegmentReference s
                            && "myButton1".equals(s.getCanonicalText()))
                    .map(r -> (Fxml2BindingSegmentReference) r)
                    .findFirst().orElse(null);
            assertNotNull(myBtn1SegRef, "No segment ref for 'myButton1' in disable binding");

            PsiField lightField = (PsiField) myBtn1SegRef.resolve();
            assertNotNull(lightField);

            // 1. The Fxml2FxIdReference (definition) must report isReferenceTo(LightFieldBuilder)==true.
            Fxml2FxIdReference selfRef = Arrays.stream(fxIdValueEl.getReferences())
                    .filter(r -> r instanceof Fxml2FxIdReference)
                    .map(r -> (Fxml2FxIdReference) r)
                    .findFirst().orElse(null);
            assertNotNull(selfRef, "No Fxml2FxIdReference on fx:id value");
            assertTrue(selfRef.isReferenceTo(lightField),
                    "Fxml2FxIdReference.isReferenceTo(LightFieldBuilder) must be true");

            // 2. A segment reference in a second binding must also match.
            XmlAttributeValue textAttrVal = findAttrValueContaining("${myButton1.text}");
            assertNotNull(textAttrVal, "text attribute value not found");
            Fxml2BindingSegmentReference otherSegRef = Arrays.stream(textAttrVal.getReferences())
                    .filter(r -> r instanceof Fxml2BindingSegmentReference s
                            && "myButton1".equals(s.getCanonicalText()))
                    .map(r -> (Fxml2BindingSegmentReference) r)
                    .findFirst().orElse(null);
            assertNotNull(otherSegRef, "No segment ref for 'myButton1' in text binding");
            assertTrue(otherSegRef.isReferenceTo(lightField),
                    "Second segment reference isReferenceTo(LightFieldBuilder) must be true");
        });
    }

    // -----------------------------------------------------------------------
    // FindUsagesHandlerFactory: primary elements
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2FxIdFindUsagesHandlerFactory#canFindUsages} should return {@code true}
     * for an fx:id {@link XmlAttributeValue}.
     */
    @Test
    void findUsagesFactoryCanHandleFxIdValue() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button fx:id="myBtn"/>
                """
        ));

        ReadAction.run(() -> {
            XmlAttributeValue fxIdVal = findFxIdValueElement("myBtn");
            assertNotNull(fxIdVal);
            Fxml2FxIdFindUsagesHandlerFactory factory = new Fxml2FxIdFindUsagesHandlerFactory();
            assertTrue(factory.canFindUsages(fxIdVal),
                    "Factory should accept fx:id XmlAttributeValue");
        });
    }

    /**
     * The {@link FindUsagesHandler#getPrimaryElements()} for an fx:id value should include
     * both the {@link XmlAttributeValue} itself and the code-behind {@link PsiField}.
     */
    @Test
    void findUsagesHandlerPrimaryElementsIncludesCodeBehindField() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button fx:id="myBtn"/>
                """
        ));

        ReadAction.run(() -> {
            XmlAttributeValue fxIdVal = findFxIdValueElement("myBtn");
            assertNotNull(fxIdVal);

            Fxml2FxIdFindUsagesHandlerFactory factory = new Fxml2FxIdFindUsagesHandlerFactory();
            FindUsagesHandler handler = factory.createFindUsagesHandler(fxIdVal, false);
            assertNotNull(handler, "Handler should be created for fx:id value");

            PsiElement[] primaries = handler.getPrimaryElements();
            assertNotNull(primaries);
            assertTrue(primaries.length >= 2,
                    "Should have at least 2 primary elements (XmlAttributeValue + PsiField)");

            boolean hasAttrVal = Arrays.stream(primaries).anyMatch(e -> e == fxIdVal);
            boolean hasField   = Arrays.stream(primaries)
                    .anyMatch(e -> e instanceof PsiField f && "myBtn".equals(f.getName()));
            assertTrue(hasAttrVal, "Primaries should include the XmlAttributeValue");
            assertTrue(hasField,   "Primaries should include the code-behind PsiField");
        });
    }

    /**
     * When there is no code-behind class, {@link FindUsagesHandler#getPrimaryElements()}
     * should return only the {@link XmlAttributeValue}.
     */
    @Test
    void findUsagesHandlerPrimaryElementsOnlyAttrValWhenNoCodeBehind() {
        // Use a different fx:subclass so no code-behind is found via addCodeBehind()
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button fx:id="myBtn"/>
                """,
                "test.NoSuchClass"
        ));

        ReadAction.run(() -> {
            XmlAttributeValue fxIdVal = findFxIdValueElement("myBtn");
            assertNotNull(fxIdVal);

            Fxml2FxIdFindUsagesHandlerFactory factory = new Fxml2FxIdFindUsagesHandlerFactory();
            FindUsagesHandler handler = factory.createFindUsagesHandler(fxIdVal, false);
            assertNotNull(handler);

            PsiElement[] primaries = handler.getPrimaryElements();
            assertEquals(1, primaries.length, "Should have exactly 1 primary when no code-behind");
            assertSame(fxIdVal, primaries[0]);
        });
    }

    // -----------------------------------------------------------------------
    // Fxml2FxIdFieldSearcher: code-behind field finds fx:id as usage
    // -----------------------------------------------------------------------

    /**
     * {@link ReferencesSearch} on the code-behind field {@code myBtn} should include
     * the {@code fx:id="myBtn"} {@link XmlAttributeValue} as a usage (via
     * {@link org.jfxcore.fxml.lang.Fxml2FxIdFieldSearcher}).
     */
    @Test
    void referencesSearchOnFieldFindsXmlFxIdValue() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button fx:id="myBtn"/>
                """
        ));

        // Find the code-behind field and the fx:id value element
        PsiField field = ReadAction.compute(() -> {
            com.intellij.psi.JavaPsiFacade facade =
                    com.intellij.psi.JavaPsiFacade.getInstance(getFixture().getProject());
            com.intellij.psi.PsiClass codeBehind = facade.findClass(
                    "test.TestView", com.intellij.psi.search.GlobalSearchScope.allScope(getFixture().getProject()));
            assertNotNull(codeBehind, "Code-behind class test.TestView not found");
            return codeBehind.findFieldByName("myBtn", false);
        });
        assertNotNull(field, "Field myBtn not found in TestView");

        XmlAttributeValue fxIdVal = findFxIdValueElement("myBtn");
        assertNotNull(fxIdVal, "fx:id=\"myBtn\" value element not found");

        // Run ReferencesSearch: our Fxml2FxIdFieldSearcher should add the fx:id ref
        Collection<PsiReference> refs = ReadAction.compute(() ->
                ReferencesSearch.search(field,
                        com.intellij.psi.search.GlobalSearchScope.allScope(getFixture().getProject()))
                        .findAll());

        boolean foundFxId = refs.stream().anyMatch(ref ->
                ref instanceof Fxml2FxIdReference fxRef
                && fxRef.getElement() == fxIdVal);
        assertTrue(foundFxId,
                "ReferencesSearch on code-behind field should find the fx:id XmlAttributeValue as a usage");
    }

    // -----------------------------------------------------------------------
    // Rename refactoring of fx:id
    // -----------------------------------------------------------------------

    /**
     * Renaming an {@code fx:id} value via the platform rename action must update
     * the {@code fx:id} attribute value itself in the document.
     *
     * <p>Note: {@code renameElementAtCaret()} requires read access internally. In JUnit 5
     * tests the test-worker thread does not have read access by default, so we obtain the
     * element in a {@link ReadAction} and run the rename via
     * {@link EdtTestUtil#runInEdtAndWait} (which grants write-intent read access on the EDT).
     */
    @Test
    void renameFxIdUpdatesAttributeValue() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button fx:id="my<caret>Btn"/>
                """
        ));
        XmlAttributeValue attrVal = ReadAction.compute(() -> {
            int offset = getFixture().getEditor().getCaretModel().getOffset();
            return com.intellij.psi.util.PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
        });
        assertNotNull(attrVal, "Expected XmlAttributeValue at caret");
        EdtTestUtil.runInEdtAndWait(() -> getFixture().renameElement(attrVal, "renamedBtn"));
        String text = getFixture().getEditor().getDocument().getText();
        assertTrue(text.contains("fx:id=\"renamedBtn\""),
                "Expected fx:id value to be renamed to 'renamedBtn', document: " + text);
        assertFalse(text.contains("fx:id=\"myBtn\""),
                "Old fx:id value 'myBtn' should no longer be present, document: " + text);
    }

    /**
     * Renaming an {@code fx:id} that is also used in a binding path within the
     * same FXML file must update the binding path as well.
     *
     * <p>See threading note in {@link #renameFxIdUpdatesAttributeValue()}.
     */
    @Test
    void renameFxIdUpdatesBindingPathUsageInSameFile() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button\njavafx.scene.control.Label",
                """
                  <Button fx:id="my<caret>Btn"/>
                  <Label text="$myBtn.text"/>
                """
        ));
        XmlAttributeValue attrVal = ReadAction.compute(() -> {
            int offset = getFixture().getEditor().getCaretModel().getOffset();
            return com.intellij.psi.util.PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
        });
        assertNotNull(attrVal, "Expected XmlAttributeValue at caret");
        EdtTestUtil.runInEdtAndWait(() -> getFixture().renameElement(attrVal, "renamedBtn"));
        String text = getFixture().getEditor().getDocument().getText();
        assertTrue(text.contains("fx:id=\"renamedBtn\""),
                "Expected fx:id value to be renamed to 'renamedBtn', document: " + text);
        assertTrue(text.contains("$renamedBtn.text"),
                "Expected binding path to be updated to use 'renamedBtn', document: " + text);
    }

    // -----------------------------------------------------------------------
    // Rename handler (Shift+F6 triggered from within the FXML file)
    // -----------------------------------------------------------------------

    /**
     * The rename handler must report itself as available when the caret is positioned
     * on an {@code fx:id} attribute value in an FXML2 file.
     */
    @Test
    void renameHandlerAvailableOnFxIdPosition() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button fx:id="my<caret>Btn"/>
                """
        ));
        EdtTestUtil.runInEdtAndWait(() -> {
            var dataContext = buildDataContext();
            assertTrue(new org.jfxcore.fxml.lang.Fxml2FxIdRenameHandler()
                            .isAvailableOnDataContext(dataContext),
                    "Fxml2FxIdRenameHandler should be available when caret is on fx:id value");
        });
    }

    /**
     * The rename handler must report itself as available when the caret is positioned
     * on a binding-path segment that resolves to an {@code fx:id} declaration.
     */
    @Test
    void renameHandlerAvailableOnBindingSegmentPosition() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button\njavafx.scene.control.Label",
                """
                  <Button fx:id="myBtn"/>
                  <Label text="$my<caret>Btn.text"/>
                """
        ));
        EdtTestUtil.runInEdtAndWait(() -> {
            var dataContext = buildDataContext();
            assertTrue(new org.jfxcore.fxml.lang.Fxml2FxIdRenameHandler()
                            .isAvailableOnDataContext(dataContext),
                    "Fxml2FxIdRenameHandler should be available when caret is on binding segment for fx:id");
        });
    }

    /**
     * Invoking the rename handler with the caret on an {@code fx:id} attribute value must
     * rename both the {@code fx:id} value and any binding-path references to it in the
     * same file.
     */
    @Test
    void renameFromFxIdPositionUpdatesFxIdAndBindings() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button\njavafx.scene.control.Label",
                """
                  <Button fx:id="my<caret>Btn"/>
                  <Label text="$myBtn.text"/>
                """
        ));
        EdtTestUtil.runInEdtAndWait(() -> {
            var dataContext = buildDataContextWithName("renamedBtn");
            new org.jfxcore.fxml.lang.Fxml2FxIdRenameHandler()
                    .invoke(getFixture().getProject(), getFixture().getEditor(), null, dataContext);
        });
        String text = getFixture().getEditor().getDocument().getText();
        assertTrue(text.contains("fx:id=\"renamedBtn\""),
                "Expected fx:id to be updated; document: " + text);
        assertTrue(text.contains("$renamedBtn.text"),
                "Expected binding reference to be updated; document: " + text);
        assertFalse(text.contains("myBtn"),
                "Old name 'myBtn' should no longer appear; document: " + text);
    }

    /**
     * Invoking the rename handler with the caret on a binding-path segment that references
     * an {@code fx:id} must rename both the {@code fx:id} value and the binding segment.
     */
    @Test
    void renameFromBindingSegmentPositionUpdatesFxIdAndSegment() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button\njavafx.scene.control.Label",
                """
                  <Button fx:id="myBtn"/>
                  <Label text="$my<caret>Btn.text"/>
                """
        ));
        EdtTestUtil.runInEdtAndWait(() -> {
            var dataContext = buildDataContextWithName("renamedBtn");
            new org.jfxcore.fxml.lang.Fxml2FxIdRenameHandler()
                    .invoke(getFixture().getProject(), getFixture().getEditor(), null, dataContext);
        });
        String text = getFixture().getEditor().getDocument().getText();
        assertTrue(text.contains("fx:id=\"renamedBtn\""),
                "Expected fx:id to be updated; document: " + text);
        assertTrue(text.contains("$renamedBtn.text"),
                "Expected binding segment to be updated; document: " + text);
        assertFalse(text.contains("myBtn"),
                "Old name 'myBtn' should no longer appear; document: " + text);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Builds a DataContext containing the current editor and PSI file. */
    private com.intellij.openapi.actionSystem.DataContext buildDataContext() {
        return com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR, getFixture().getEditor())
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE, getFixture().getFile())
                .build();
    }

    /** Builds a DataContext that also carries the given new name for test-mode rename. */
    @SuppressWarnings("SameParameterValue")
    private com.intellij.openapi.actionSystem.DataContext buildDataContextWithName(String newName) {
        return com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR, getFixture().getEditor())
                .add(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE, getFixture().getFile())
                .add(com.intellij.refactoring.rename.PsiElementRenameHandler.DEFAULT_NAME, newName)
                .build();
    }

    /**
     * Walks the PSI tree to find the {@link XmlAttributeValue} of the first
     * {@code fx:id} attribute whose value equals {@code name}.
     */
    private XmlAttributeValue findFxIdValueElement(String name) {
        return ReadAction.compute(() -> {
            for (var tag : com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                    getFixture().getFile(), com.intellij.psi.xml.XmlTag.class)) {
                XmlAttribute idAttr = tag.getAttribute("fx:id");
                if (idAttr != null && name.equals(idAttr.getValue())) {
                    return idAttr.getValueElement();
                }
            }
            return null;
        });
    }

    /**
     * Walks the PSI tree to find the first {@link XmlAttributeValue} whose text
     * (including quotes) contains {@code substring}.
     */
    private XmlAttributeValue findAttrValueContaining(String substring) {
        return ReadAction.compute(() -> {
            for (var attrVal : com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                    getFixture().getFile(), XmlAttributeValue.class)) {
                if (attrVal.getText().contains(substring)) return attrVal;
            }
            return null;
        });
    }

    // -----------------------------------------------------------------------
    // Embedded FXML2 (@ComponentView): fx:id reference tests
    // -----------------------------------------------------------------------

    /**
     * Returns the {@link XmlFile} injected from the {@code @ComponentView} annotation of the
     * first annotated class found in the current fixture file.  Requires
     * {@link com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture#doHighlighting()}
     * to have been called first so that the injection is computed.
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
    private static XmlAttributeValue findEmbeddedFxIdValue(String name, XmlFile xmlFile) {
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
     * A {@link Fxml2FxIdReference} must be present on the {@code fx:id} attribute value
     * inside embedded FXML2 (injected XML from a {@code @ComponentView} annotation), and its
     * {@link Fxml2FxIdReference#resolve()} must return {@code null}: the fx:id is a
     * declaration, not a use-site reference.
     */
    @Test
    void fxIdDefinitionInEmbeddedFxml2HasSelfReference() {
        getFixture().addClass("""
                package test.emb;
                import javafx.scene.control.Button;
                import javafx.scene.layout.BorderPane;
                public abstract class EmbRefBase1 extends BorderPane {
                    public Button myEmbBtn;
                    protected void initializeComponent() {}
                }
                """);
        getFixture().configureByText("EmbRefView1.java", """
                package test.emb;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <BorderPane>
                      <Button fx:id="myEmbBtn"/>
                    </BorderPane>
                    \""")
                public class EmbRefView1 extends EmbRefBase1 {
                    public EmbRefView1() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = getEmbeddedXmlFile();
            assertNotNull(xmlFile, "Injected XmlFile must be present");
            XmlAttributeValue fxIdVal = findEmbeddedFxIdValue("myEmbBtn", xmlFile);
            assertNotNull(fxIdVal, "fx:id='myEmbBtn' not found in injected XML");

            Fxml2FxIdReference selfRef = Arrays.stream(fxIdVal.getReferences())
                    .filter(r -> r instanceof Fxml2FxIdReference)
                    .map(r -> (Fxml2FxIdReference) r)
                    .findFirst().orElse(null);
            assertNotNull(selfRef, "No Fxml2FxIdReference on embedded fx:id value");
            assertNull(selfRef.resolve(),
                    "Self-reference on embedded fx:id must resolve to null "
                    + "(it is a declaration, not a use-site reference)");
        });
    }

    /**
     * There must be NO reference on the embedded {@code fx:id} value that resolves
     * directly to a {@link PsiField}: that would hijack Ctrl+click.  Navigation to the
     * field is provided by {@link Fxml2FxIdFindUsagesHandlerFactory} instead.
     */
    @Test
    void fxIdDefinitionInEmbeddedFxml2HasNoDirectFieldReference() {
        getFixture().addClass("""
                package test.emb;
                import javafx.scene.control.Button;
                import javafx.scene.layout.BorderPane;
                public abstract class EmbRefBase2 extends BorderPane {
                    public Button myEmbBtn2;
                    protected void initializeComponent() {}
                }
                """);
        getFixture().configureByText("EmbRefView2.java", """
                package test.emb;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <BorderPane>
                      <Button fx:id="myEmbBtn2"/>
                    </BorderPane>
                    \""")
                public class EmbRefView2 extends EmbRefBase2 {
                    public EmbRefView2() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = getEmbeddedXmlFile();
            assertNotNull(xmlFile);
            XmlAttributeValue fxIdVal = findEmbeddedFxIdValue("myEmbBtn2", xmlFile);
            assertNotNull(fxIdVal);

            PsiReference fieldRef = Arrays.stream(fxIdVal.getReferences())
                    .filter(r -> r.resolve() instanceof PsiField)
                    .findFirst().orElse(null);
            assertNull(fieldRef,
                    "Embedded fx:id declaration must not carry a direct PsiField reference; "
                    + "field navigation is provided by FindUsagesHandlerFactory");
        });
    }

    /**
     * A binding segment in embedded FXML2 that references an {@code fx:id} name must have
     * its {@link Fxml2BindingSegmentReference#isReferenceTo} return {@code true} for the
     * fx:id {@link XmlAttributeValue} in the same injected file.
     */
    @Test
    void bindingSegmentInEmbeddedFxml2IsReferenceToFxIdDefinition() {
        getFixture().addClass("""
                package test.emb;
                import javafx.scene.control.Button;
                import javafx.scene.layout.BorderPane;
                public abstract class EmbBindBase extends BorderPane {
                    public Button myBindBtn;
                    protected void initializeComponent() {}
                }
                """);
        getFixture().configureByText("EmbBindView.java", """
                package test.emb;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <BorderPane>
                      <Button fx:id="myBindBtn"/>
                      <Button disable="${myBindBtn.disabled}"/>
                    </BorderPane>
                    \""")
                public class EmbBindView extends EmbBindBase {
                    public EmbBindView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = getEmbeddedXmlFile();
            assertNotNull(xmlFile);
            XmlAttributeValue fxIdVal = findEmbeddedFxIdValue("myBindBtn", xmlFile);
            assertNotNull(fxIdVal, "fx:id='myBindBtn' not found in injected XML");

            // Locate the binding attribute value
            XmlAttributeValue bindingAttrVal = null;
            for (var av : PsiTreeUtil.findChildrenOfType(xmlFile, XmlAttributeValue.class)) {
                if (av.getText().contains("myBindBtn.disabled")) {
                    bindingAttrVal = av;
                    break;
                }
            }
            assertNotNull(bindingAttrVal, "Binding attribute value not found in injected XML");

            final XmlAttributeValue finalBinding = bindingAttrVal;
            Fxml2BindingSegmentReference segRef = Arrays.stream(finalBinding.getReferences())
                    .filter(r -> r instanceof Fxml2BindingSegmentReference s
                            && "myBindBtn".equals(s.getCanonicalText()))
                    .map(r -> (Fxml2BindingSegmentReference) r)
                    .findFirst().orElse(null);
            assertNotNull(segRef,
                    "No Fxml2BindingSegmentReference for 'myBindBtn' in embedded binding");
            assertTrue(segRef.isReferenceTo(fxIdVal),
                    "Binding segment in embedded FXML2 must isReferenceTo the fx:id XmlAttributeValue");
        });
    }

    /**
     * {@link ReferencesSearch} on a code-behind field must find the {@code fx:id}
     * {@link XmlAttributeValue} in embedded FXML2 as a {@link Fxml2FxIdReference} usage -
     * via {@link org.jfxcore.fxml.lang.Fxml2FxIdFieldSearcher}.
     */
    @Test
    void referencesSearchOnFieldFindsEmbeddedXmlFxIdValue() {
        getFixture().addClass("""
                package test.emb;
                import javafx.scene.control.Button;
                import javafx.scene.layout.BorderPane;
                public abstract class EmbSearchBase extends BorderPane {
                    public Button mySearchBtn;
                    protected void initializeComponent() {}
                }
                """);
        getFixture().configureByText("EmbSearchView.java", """
                package test.emb;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <BorderPane>
                      <Button fx:id="mySearchBtn"/>
                    </BorderPane>
                    \""")
                public class EmbSearchView extends EmbSearchBase {
                    public EmbSearchView() { initializeComponent(); }
                }
                """);

        getFixture().doHighlighting();

        PsiField field = ReadAction.compute(() -> {
            var facade = com.intellij.psi.JavaPsiFacade.getInstance(getFixture().getProject());
            var scope = com.intellij.psi.search.GlobalSearchScope.allScope(getFixture().getProject());
            var base = facade.findClass("test.emb.EmbSearchBase", scope);
            assertNotNull(base, "EmbSearchBase not found");
            return base.findFieldByName("mySearchBtn", false);
        });
        assertNotNull(field, "Field 'mySearchBtn' not found in EmbSearchBase");

        XmlFile xmlFile = getEmbeddedXmlFile();
        XmlAttributeValue fxIdVal = findEmbeddedFxIdValue("mySearchBtn", xmlFile);
        assertNotNull(fxIdVal, "fx:id='mySearchBtn' not found in injected XML");

        Collection<PsiReference> refs = ReadAction.compute(() ->
                ReferencesSearch.search(field,
                        com.intellij.psi.search.GlobalSearchScope.projectScope(
                                getFixture().getProject())).findAll());

        ReadAction.run(() -> {
            boolean found = refs.stream().anyMatch(
                    r -> r instanceof Fxml2FxIdReference ref && ref.getElement() == fxIdVal);
            assertTrue(found,
                    "ReferencesSearch on 'mySearchBtn' must find the embedded fx:id "
                    + "as a Fxml2FxIdReference.\nRefs found: "
                    + refs.stream()
                            .map(r -> r.getClass().getSimpleName()
                                      + " in " + r.getElement().getContainingFile().getName())
                            .toList());
        });
    }
}
