// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.xml.impl.XmlAttributeDescriptorEx;
import org.jfxcore.fxml.descriptors.Fxml2PropertyAttributeDescriptor;
import org.jfxcore.fxml.lang.Fxml2PropertyAttributeRenameHandler;
import org.jfxcore.fxml.lang.Fxml2StandaloneImplicitUsageProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that setter methods and other property accessors referenced via plain XML
 * attribute names in standalone FXML files are not incorrectly flagged as "never used".
 *
 * <h3>Scenario</h3>
 * <p>A custom node class {@code FormattedLabel<T>} exposes a {@code formatter} property
 * via the standard JavaFX convention:
 * <pre>
 *   public ObjectProperty&lt;Function&lt;T,String&gt;&gt; formatterProperty() { ... }
 *   public Function&lt;T,String&gt; getFormatter() { ... }
 *   public void setFormatter(Function&lt;T,String&gt; fn) { ... }
 * </pre>
 *
 * <p>A standalone FXML file assigns the property via:
 * <pre>
 *   &lt;FormattedLabel formatter="$doubleFormatter"/&gt;
 * </pre>
 *
 * <p>Without the fix, IntelliJ would report {@code setFormatter} (and possibly
 * {@code formatterProperty} and {@code getFormatter}) as "Method is never used" because
 * no {@link org.jfxcore.fxml.lang.Fxml2BindingSegmentReference} points to them: the
 * attribute name is not a binding expression but a plain property assignment.
 *
 * <p>With the fix ({@link org.jfxcore.fxml.lang.Fxml2PropertyAttributeSearcher} +
 * {@link Fxml2StandaloneImplicitUsageProvider} checking property attribute descriptors),
 * all three accessors must be reported as implicitly used.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2PropertyAttributeUsageTest extends Fxml2TestBase {

    @BeforeAll
    void addCustomNode() {
        // The custom node with a JavaFX property following the standard naming convention.
        getFixture().addClass("""
                package test;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                import javafx.scene.control.Label;
                import java.util.function.Function;
                public class FormattedLabel<T> extends Label {
                    private final ObjectProperty<Function<T, String>> formatter =
                            new SimpleObjectProperty<>(this, "formatter", Object::toString);
                    public ObjectProperty<Function<T, String>> formatterProperty() { return formatter; }
                    public Function<T, String> getFormatter() { return formatter.get(); }
                    public void setFormatter(Function<T, String> fn) { formatter.set(fn); }
                }
                """);
        // The code-behind class.
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                import java.util.function.Function;
                public class TestView extends BorderPane {
                    public Function<Double, String> doubleFormatter = String::valueOf;
                    protected void initializeComponent() {}
                    public TestView() { initializeComponent(); }
                }
                """);
    }

    // -----------------------------------------------------------------------
    // Setter (setFormatter): property-attribute usage via setter
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2StandaloneImplicitUsageProvider} must report {@code setFormatter}
     * as implicitly used when the {@code formatter} attribute appears in a standalone
     * FXML file (even if no {@code Fxml2BindingSegmentReference} points to it).
     */
    @Test
    void setterReferencedViaPropertyAttributeIsRecognizedAsUsed() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.FormattedLabel",
                "  <FormattedLabel formatter=\"$doubleFormatter\"/>\n",
                "test.TestView"
        ));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var cls = JavaPsiFacade.getInstance(project)
                    .findClass("test.FormattedLabel", GlobalSearchScope.projectScope(project));
            assertNotNull(cls, "Must find FormattedLabel class");

            PsiMethod[] setters = cls.findMethodsByName("setFormatter", false);
            assertTrue(setters.length > 0, "Must find setFormatter method");
            PsiMethod setFormatter = setters[0];

            var provider = new Fxml2StandaloneImplicitUsageProvider();

            assertTrue(provider.isImplicitUsage(setFormatter),
                    "isImplicitUsage must return true for setFormatter referenced via "
                    + "property attribute in standalone FXML");
            assertTrue(provider.isImplicitRead(setFormatter),
                    "isImplicitRead must return true for setFormatter referenced via "
                    + "property attribute in standalone FXML");
        });
    }

    // -----------------------------------------------------------------------
    // Find Usages via ReferencesSearch
    // -----------------------------------------------------------------------

    /**
     * {@link ReferencesSearch} must return the FXML attribute {@code formatter}
     * as a usage of {@code setFormatter} when "Find Usages" is invoked.
     */
    @Test
    void findUsagesOnSetterFindsPropertyAttributeInFxml2() {
        getFixture().configureByText("TestView2.fxml", fxml2(
                "test.FormattedLabel",
                "  <FormattedLabel formatter=\"$doubleFormatter\"/>\n",
                "test.TestView"
        ));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var cls = JavaPsiFacade.getInstance(project)
                    .findClass("test.FormattedLabel", GlobalSearchScope.projectScope(project));
            assertNotNull(cls, "Must find FormattedLabel class");

            PsiMethod[] setters = cls.findMethodsByName("setFormatter", false);
            assertTrue(setters.length > 0, "Must find setFormatter method");
            PsiMethod setFormatter = setters[0];

            Collection<PsiReference> refs = ReferencesSearch.search(
                    setFormatter, GlobalSearchScope.allScope(project)).findAll();

            assertFalse(refs.isEmpty(), "ReferencesSearch must find at least one usage of setFormatter in FXML");
        });
    }

    /**
     * IntelliJ may invoke the searcher with the backing field as the target when the
     * user runs "Find Usages" on a setter method. The searcher must still find the
     * FXML attribute by recognising both as part of the same property.
     */
    @Test
    void findUsagesOnBackingFieldFindsPropertyAttributeInFxml2() {
        getFixture().configureByText("TestView3.fxml", fxml2(
                "test.FormattedLabel",
                "  <FormattedLabel formatter=\"$doubleFormatter\"/>\n",
                "test.TestView"
        ));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var cls = JavaPsiFacade.getInstance(project)
                    .findClass("test.FormattedLabel", GlobalSearchScope.projectScope(project));
            assertNotNull(cls, "Must find FormattedLabel class");

            // Simulate IntelliJ redirecting the "Find Usages" query to the backing field.
            PsiField field = cls.findFieldByName("formatter", false);
            assertNotNull(field, "Must find backing field 'formatter'");

            Collection<PsiReference> refs = ReferencesSearch.search(
                    field, GlobalSearchScope.allScope(project)).findAll();

            assertTrue(refs.stream().anyMatch(r -> r.getElement().getText().equals("formatter")
                    && r.getElement().getContainingFile().getName().endsWith(".fxml")),
                    "ReferencesSearch on backing field must find formatter attribute in FXML");
        });
    }

    // -----------------------------------------------------------------------
    // Getter (getFormatter): also accessible via fxml2 plain attribute
    // -----------------------------------------------------------------------

    /**
     * When the {@code formatter} attribute is assigned via a binding that the fxml2
     * compiler resolves using the property accessor ({@code formatterProperty()}), that
     * accessor must also be recognized as used.
     *
     * <p>Uses a {@code {fx:Observe}} binding which the compiler resolves to the
     * {@code xProperty()} method rather than the setter.
     */
    @Test
    void propertyAccessorReferencedViaObserveBindingIsRecognizedAsUsed() {
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class ObserveView extends BorderPane {
                    protected void initializeComponent() {}
                    public ObserveView() { initializeComponent(); }
                }
                """);
        getFixture().configureByText("ObserveView.fxml", fxml2(
                "test.FormattedLabel",
                "  <FormattedLabel formatter=\"{fx:Observe doubleFormatterProp}\"/>\n",
                "test.ObserveView"
        ));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var cls = JavaPsiFacade.getInstance(project)
                    .findClass("test.FormattedLabel", GlobalSearchScope.projectScope(project));
            assertNotNull(cls, "Must find FormattedLabel class");

            PsiMethod[] accessors = cls.findMethodsByName("formatterProperty", false);
            assertTrue(accessors.length > 0, "Must find formatterProperty method");
            PsiMethod formatterProperty = accessors[0];

            var provider = new Fxml2StandaloneImplicitUsageProvider();

            assertTrue(provider.isImplicitUsage(formatterProperty),
                    "isImplicitUsage must return true for formatterProperty() referenced via "
                    + "fx:Observe binding in standalone FXML");
        });
    }

    // -----------------------------------------------------------------------
    // Negative: unrelated method must NOT be suppressed
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // Rename: handleElementRename must use property name, not accessor name
    // -----------------------------------------------------------------------

    /**
     * When a setter method (e.g. {@code setProperty1}) is renamed to a new setter name
     * (e.g. {@code setProperty2}), the FXML property-attribute reference must rename
     * the XML attribute to the derived property name ({@code property2}), not to the
     * raw new setter name ({@code setProperty2}).
     *
     * <p>This verifies that the synthetic {@link PsiReference} emitted by the
     * property-attribute searcher correctly strips the {@code set} prefix when
     * {@code handleElementRename} is called during a rename refactoring.
     */
    @Test
    void renamingSetterUpdatesAttributeToPropertyName() {
        getFixture().configureByText("RenameView.fxml", fxml2(
                "test.FormattedLabel",
                "  <FormattedLabel formatter=\"$doubleFormatter\"/>\n",
                "test.TestView"
        ));
        getFixture().doHighlighting();

        // Obtain the synthetic reference on the "formatter" attribute name.
        PsiReference[] refHolder = new PsiReference[1];
        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var cls = java.util.Objects.requireNonNull(
                    JavaPsiFacade.getInstance(project)
                            .findClass("test.FormattedLabel", GlobalSearchScope.projectScope(project)),
                    "Must find FormattedLabel class");
            PsiMethod[] setters = cls.findMethodsByName("setFormatter", false);
            assertTrue(setters.length > 0, "Must find setFormatter method");
            for (PsiReference r : ReferencesSearch.search(setters[0], GlobalSearchScope.allScope(project)).findAll()) {
                var file = r.getElement().getContainingFile();
                if (file != null && file.getName().endsWith(".fxml")) {
                    refHolder[0] = r;
                    break;
                }
            }
        });
        PsiReference ref = refHolder[0];
        assertNotNull(ref, "Must find a FXML attribute reference to setFormatter");

        // Simulate what IntelliJ calls during rename refactoring when the setter is
        // renamed to setFormatter2: the reference must produce the property name.
        com.intellij.openapi.application.ApplicationManager.getApplication()
                .invokeAndWait(() -> com.intellij.openapi.command.WriteCommandAction
                        .runWriteCommandAction(getFixture().getProject(), () -> {
                            try { ref.handleElementRename("setFormatter2"); }
                            catch (com.intellij.util.IncorrectOperationException e) {
                                throw new RuntimeException(e);
                            }
                        }));

        // The attribute in the FXML file must now be "formatter2", not "setFormatter2".
        String fxmlText = ReadAction.compute(() -> getFixture().getFile().getText());
        assertTrue(fxmlText.contains("formatter2="),
                "Attribute must be renamed to 'formatter2' (property name), not 'setFormatter2'.\n"
                + "Actual FXML text:\n" + fxmlText);
        assertFalse(fxmlText.contains("setFormatter2"),
                "Attribute must NOT contain the raw setter name 'setFormatter2'.\n"
                + "Actual FXML text:\n" + fxmlText);
    }

    // -----------------------------------------------------------------------
    // Rename from use site: handler must use backing field as rename target
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2PropertyAttributeDescriptor} must implement
     * {@link XmlAttributeDescriptorEx} and its {@code handleTargetRename} must convert
     * an accessor name to a property name.
     *
     * <p>{@link com.intellij.psi.impl.source.xml.XmlAttributeReference#handleElementRename}
     * calls {@code handleTargetRename} when the descriptor implements
     * {@link XmlAttributeDescriptorEx}.  Without this, renaming the declared accessor
     * method (e.g. {@code setFormatter} -> {@code setFormatter2}) would cause
     * {@code XmlAttributeReference} to rename the attribute to the raw accessor name
     * ({@code setFormatter2}) instead of the property name ({@code formatter2}).
     */
    @Test
    void descriptorHandleTargetRenameConvertsAccessorNameToPropertyName() {
        getFixture().configureByText("HandleTargetView.fxml", fxml2(
                "test.FormattedLabel",
                "  <FormattedLabel formatter=\"$doubleFormatter\"/>\n",
                "test.TestView"
        ));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            XmlFile xmlFile = (XmlFile) getFixture().getFile();
            XmlTag root = xmlFile.getRootTag();
            assertNotNull(root);
            XmlAttribute formatterAttr = null;
            for (XmlTag child : root.getSubTags()) {
                formatterAttr = child.getAttribute("formatter");
                if (formatterAttr != null) break;
            }
            assertNotNull(formatterAttr, "Must find formatter attribute");

            var descriptor = formatterAttr.getDescriptor();
            assertInstanceOf(XmlAttributeDescriptorEx.class, descriptor,
                    "Fxml2PropertyAttributeDescriptor must implement XmlAttributeDescriptorEx");

            XmlAttributeDescriptorEx descEx = (XmlAttributeDescriptorEx) descriptor;

            // Setter rename: setFormatter2 -> property name formatter2
            assertEquals("formatter2", descEx.handleTargetRename("setFormatter2"),
                    "handleTargetRename must strip 'set' prefix for setter names");

            // Getter rename: getFormatter2 -> property name formatter2
            assertEquals("formatter2", descEx.handleTargetRename("getFormatter2"),
                    "handleTargetRename must strip 'get' prefix for getter names");

            // Property method rename: formatter2Property -> property name formatter2
            assertEquals("formatter2", descEx.handleTargetRename("formatter2Property"),
                    "handleTargetRename must strip 'Property' suffix for xProperty() names");

            // Plain property name: formatter2 -> formatter2 (no conversion needed)
            assertEquals("formatter2", descEx.handleTargetRename("formatter2"),
                    "handleTargetRename must return plain names unchanged");
        });
    }

    /**
     * When the caret is on a property attribute name (e.g. {@code formatter}) in an
     * FXML file, {@link Fxml2PropertyAttributeRenameHandler#findRenameTarget} must
     * return the backing field (not the setter method), so that IntelliJ pre-fills
     * the rename dialog with the property name rather than the accessor name.
     */
    @Test
    void renameHandlerIdentifiesBackingFieldAsRenameTarget() {
        getFixture().configureByText("HandlerTargetView.fxml", fxml2(
                "test.FormattedLabel",
                "  <FormattedLabel formatter=\"$doubleFormatter\"/>\n",
                "test.TestView"
        ));
        getFixture().doHighlighting();

        // Find the formatter attribute in the FXML file.
        XmlAttribute[] attrHolder = new XmlAttribute[1];
        ReadAction.run(() -> {
            XmlFile xmlFile = (XmlFile) getFixture().getFile();
            XmlTag root = xmlFile.getRootTag();
            assertNotNull(root);
            for (XmlTag child : root.getSubTags()) {
                XmlAttribute attr = child.getAttribute("formatter");
                if (attr != null) { attrHolder[0] = attr; break; }
            }
        });
        assertNotNull(attrHolder[0], "Must find formatter attribute in FXML");

        // The rename handler must redirect to the backing field, not the setter.
        PsiElement renameTarget = ReadAction.compute(() ->
                Fxml2PropertyAttributeRenameHandler.findRenameTarget(attrHolder[0]));

        ReadAction.run(() -> {
            assertNotNull(renameTarget, "findRenameTarget must return non-null");
            assertInstanceOf(PsiField.class, renameTarget,
                    "Rename target must be the backing field, not a setter method.\n"
                    + "Got: " + renameTarget);
            assertEquals("formatter", ((PsiField) renameTarget).getName(),
                    "Backing field name must equal the property name");
        });
    }

    /**
     * Renaming the backing field (the target selected by the rename handler) to a new
     * property name must update the FXML attribute to the new property name, not to
     * the setter name.
     *
     * <p>This is the functional end-to-end variant of the rename-from-use-site scenario:
     * the handler redirects to the field, the field is renamed, and all three accessors
     * (setter, getter, {@code xProperty()} method) are renamed via the standard Java
     * triad rename.  The FXML attribute must follow with the property name, not the
     * raw setter name.
     */
    @Test
    void renamingViaBackingFieldUpdatesPropertyAttribute() {
        getFixture().configureByText("BackingFieldRenView.fxml", fxml2(
                "test.FormattedLabel",
                "  <FormattedLabel formatter=\"$doubleFormatter\"/>\n",
                "test.TestView"
        ));
        getFixture().doHighlighting();

        // Locate the backing field in the declaring class.
        PsiField[] fieldHolder = new PsiField[1];
        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var cls = java.util.Objects.requireNonNull(
                    JavaPsiFacade.getInstance(project)
                            .findClass("test.FormattedLabel", GlobalSearchScope.projectScope(project)),
                    "Must find FormattedLabel class");
            fieldHolder[0] = cls.findFieldByName("formatter", false);
        });
        assertNotNull(fieldHolder[0], "Must find backing field 'formatter'");

        // Rename the backing field: this is exactly what the rename handler does.
        com.intellij.openapi.application.ApplicationManager.getApplication()
                .invokeAndWait(() -> getFixture().renameElement(fieldHolder[0], "formatter2"));

        // The FXML attribute must now read "formatter2", not "setFormatter2".
        String fxmlText = ReadAction.compute(() -> getFixture().getFile().getText());
        assertTrue(fxmlText.contains("formatter2="),
                "Attribute must be renamed to 'formatter2' after backing-field rename.\n"
                + "Actual FXML text:\n" + fxmlText);
        assertFalse(fxmlText.contains("setFormatter2"),
                "Attribute must NOT contain the raw setter name 'setFormatter2'.\n"
                + "Actual FXML text:\n" + fxmlText);
    }

    /**
     * A setter on a class that does NOT appear in any FXML file must NOT be
     * reported as implicitly used.
     */
    @Test
    void setterNotReferencedInFxmlIsNotReportedAsUsed() {
        getFixture().addClass("""
                package test;
                public class UnrelatedNode {
                    private String hidden;
                    public String getHidden() { return hidden; }
                    public void setHidden(String v) { this.hidden = v; }
                }
                """);

        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var cls = JavaPsiFacade.getInstance(project)
                    .findClass("test.UnrelatedNode", GlobalSearchScope.projectScope(project));
            assertNotNull(cls, "Must find UnrelatedNode class");

            PsiMethod[] setters = cls.findMethodsByName("setHidden", false);
            assertTrue(setters.length > 0, "Must find setHidden method");
            PsiMethod setHidden = setters[0];

            var provider = new Fxml2StandaloneImplicitUsageProvider();

            assertFalse(provider.isImplicitUsage(setHidden),
                    "isImplicitUsage must return false for a setter NOT referenced in any FXML file");
        });
    }
}
