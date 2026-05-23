package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jfxcore.fxml.lang.Fxml2BindingSegmentReference;
import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.jfxcore.fxml.resolve.Fxml2PropertyResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that binding expressions resolve path segments correctly and report
 * errors for unresolvable segments.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2BindingPathTest extends Fxml2TestBase {
    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2AttributeValueInspection());
    }

    @BeforeEach
    void addCodeBehind() {
        // Minimal generated base class (normally produced by the fxml2 compiler)
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public abstract class TestViewBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        // Code-behind with a StringProperty "message"
        getFixture().addClass("""
                package test;
                import javafx.beans.property.StringProperty;
                import javafx.beans.property.SimpleStringProperty;
                public class TestView extends TestViewBase {
                    private final StringProperty message =
                            new SimpleStringProperty(this, "message", "");
                    public StringProperty messageProperty() { return message; }
                    public String getMessage() { return message.get(); }
                    public void setMessage(String v) { message.set(v); }
                }
                """);
    }

    // -----------------------------------------------------------------------
    // Bindings that should resolve without error
    // -----------------------------------------------------------------------

    /**
     * Package-private (default-access) fields are valid binding path segments per the fxml2
     * compiler's {@code tryResolveField} logic, which accepts all non-private fields.
     * The generated base class lives in the same package as the code-behind, so package-private
     * fields are always accessible to it.
     */
    @Test
    void packagePrivateFieldResolvesWithoutError() {
        getFixture().addClass("""
                package test;
                public class ViewModel {
                    public String getText() { return ""; }
                }
                """);
        // Temporarily add a code-behind with a package-private field
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class ViewWithPackagePrivateField extends TestViewBase {
                    ViewModel vm = new ViewModel();
                }
                """);
        getFixture().configureByText("ViewWithPackagePrivateField.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="${vm.text}"/>
                """,
                "test.ViewWithPackagePrivateField"
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Protected fields are valid binding path segments (non-private, accessible from subclass).
     */
    @Test
    void protectedFieldResolvesWithoutError() {
        getFixture().addClass("""
                package test;
                public class ViewModel2 {
                    public String getText() { return ""; }
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class ViewWithProtectedField extends TestViewBase {
                    protected ViewModel2 vm = new ViewModel2();
                }
                """);
        getFixture().configureByText("ViewWithProtectedField.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="${vm.text}"/>
                """,
                "test.ViewWithProtectedField"
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Private fields must NOT be valid binding path segments (the compiler rejects them).
     */
    @Test
    void privateFieldProducesError() {
        getFixture().addClass("""
                package test;
                public class ViewModel3 {
                    public String getText() { return ""; }
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class ViewWithPrivateField extends TestViewBase {
                    private ViewModel3 vm = new ViewModel3();
                }
                """);
        getFixture().configureByText("ViewWithPrivateField.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="${<error descr="'vm' in test.ViewWithPrivateField cannot be resolved">vm</error>.text}"/>
                """,
                "test.ViewWithPrivateField"
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void fxBindToKnownPropertyResolvesWithoutError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Observe message}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void fxEvaluateToKnownPropertyResolvesWithoutError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Evaluate message}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void dollarBindToKnownPropertyResolvesWithoutError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="$message"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Bindings that should produce errors
    // -----------------------------------------------------------------------

    @Test
    void fxBindToNonExistentPropertyProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Observe <error descr="'nonExistent' in test.TestView cannot be resolved">nonExistent</error>}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    @Test
    void dollarBindToNonExistentPropertyProducesError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="$<error descr="'nonExistent' in test.TestView cannot be resolved">nonExistent</error>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // -----------------------------------------------------------------------
    // Non-public static field references on the code-behind class
    // -----------------------------------------------------------------------

    /**
     * A package-private static field on the code-behind class must resolve without error.
     * The fxml2 compiler's {@code tryResolveField} only excludes private fields, so
     * package-private (and protected/public) static fields are all valid binding targets.
     */
    @Test
    void packagePrivateStaticFieldResolvesWithoutError() {
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class ViewWithPackagePrivateStaticField extends TestViewBase {
                    static final String staticText = "hello";
                }
                """);
        getFixture().configureByText("ViewWithPackagePrivateStaticField.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="$staticText"/>
                """,
                "test.ViewWithPackagePrivateStaticField"
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A protected static field on the code-behind class must resolve without error.
     */
    @Test
    void protectedStaticFieldResolvesWithoutError() {
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class ViewWithProtectedStaticField extends TestViewBase {
                    protected static final String staticText = "hello";
                }
                """);
        getFixture().configureByText("ViewWithProtectedStaticField.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="$staticText"/>
                """,
                "test.ViewWithProtectedStaticField"
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A private static field must NOT resolve (compiler rejects private fields).
     */
    @Test
    void privateStaticFieldProducesError() {
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class ViewWithPrivateStaticField extends TestViewBase {
                    private static final String staticText = "hello";
                }
                """);
        getFixture().configureByText("ViewWithPrivateStaticField.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="$<error descr="'staticText' in test.ViewWithPrivateStaticField cannot be resolved">staticText</error>"/>
                """,
                "test.ViewWithPrivateStaticField"
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // Fully-qualified static field references (e.g. $javafx.scene.layout.Region.USE_PREF_SIZE)
    // -----------------------------------------------------------------------

    /** FQN static field reference produces no error. */
    @Test
    void fqnStaticFieldReferenceProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.Region",
                """
                  <Region minHeight="$javafx.scene.layout.Region.USE_PREF_SIZE"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** Each package segment in a FQN static field reference navigates to its {@link PsiPackage}. */
    @Test
    void fqnStaticFieldPackageSegmentsAreNavigable() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.Region",
                """
                  <Region minHeight="$<caret>javafx.scene.layout.Region.USE_PREF_SIZE"/>
                """
        ));
        PsiElement resolved = resolveSegmentAtCaret();
        assertNotNull(resolved, "'javafx' should resolve to a PsiPackage");
        assertInstanceOf(PsiPackage.class, resolved, "'javafx' should navigate to the javafx package");
    }

    /** The class segment in a FQN static field reference navigates to the {@link PsiClass}. */
    @Test
    void fqnStaticFieldClassSegmentIsNavigable() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.Region",
                """
                  <Region minHeight="$javafx.scene.layout.<caret>Region.USE_PREF_SIZE"/>
                """
        ));
        PsiElement resolved = resolveSegmentAtCaret();
        assertNotNull(resolved, "'Region' should resolve to a PsiClass");
        assertInstanceOf(PsiClass.class, resolved, "'Region' should navigate to the Region class");
        assertEquals("javafx.scene.layout.Region", ((PsiClass) resolved).getQualifiedName());
    }

    /** The field segment in a FQN static field reference navigates to the {@link PsiField}. */
    @Test
    void fqnStaticFieldMemberSegmentIsNavigable() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.Region",
                """
                  <Region minHeight="$javafx.scene.layout.Region.<caret>USE_PREF_SIZE"/>
                """
        ));
        PsiElement resolved = resolveSegmentAtCaret();
        assertNotNull(resolved, "'USE_PREF_SIZE' should resolve to a PsiField");
        assertInstanceOf(PsiField.class, resolved);
        assertEquals("USE_PREF_SIZE", ((PsiField) resolved).getName());
    }

    /**
     * A static field defined only on an implemented interface must NOT resolve when
     * referenced via the implementing class name. The fxml2 compiler's field resolution
     * walks the superclass chain only, not interfaces, matching Java semantics that do
     * not expose static interface members through implementing class names.
     */
    @Test
    void importedClassStaticFieldOnlyOnInterfaceProducesError() {
        getFixture().addClass("""
                package test;
                public interface IItem {
                    String LABEL = "hello";
                }
                """);
        getFixture().addClass("""
                package test;
                public class Item implements IItem {}
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.Item\njavafx.scene.control.Label",
                """
                  <Label text="$Item.<error descr="'LABEL' in test.Item cannot be resolved">LABEL</error>"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A static field inherited from a superclass (not an interface) must resolve without
     * error. Superclass statics are accessible via subclass names in both Java and the
     * fxml2 compiler's field resolution.
     */
    @Test
    void importedClassStaticFieldInheritedFromSuperclassProducesNoError() {
        getFixture().addClass("""
                package test;
                public class ItemBase {
                    public static final String LABEL = "hello";
                }
                """);
        getFixture().addClass("""
                package test;
                public class DerivedItem extends ItemBase {}
                """);
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.DerivedItem\njavafx.scene.control.Label",
                """
                  <Label text="$DerivedItem.LABEL"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A static getter on an imported JavaFX class (e.g. {@code Button.classCssMetaData})
     * must resolve without error. The class is resolved via imports, and the static getter
     * ({@code getClassCssMetaData()}) is found on that class. This is the common pattern
     * for accessing static properties like CSS metadata on JavaFX control classes.
     *
     * <p>The class name {@code Button} is imported via {@code <?import javafx.scene.control.Button?>}
     * and the static property {@code classCssMetaData} is resolved via the static getter.
     */
    @Test
    void importedClassStaticGetterResolvesWithoutError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.TestView\njavafx.scene.control.Button\njavafx.scene.control.Label",
                """
                  <Label text="$Button.classCssMetaData"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Chained access after a static getter on an imported class must resolve correctly.
     * Example: {@code $Button.classCssMetaData.toString} resolves the static getter,
     * then resolves the method on the getter's return type.
     */
    @Test
    void staticGetterChainedMethodAccessResolves() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "test.TestView\njavafx.scene.control.Button\njavafx.scene.control.Label",
                """
                  <Label text="$Button.classCssMetaData.toString"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Observable selection operator ::: segment reference ranges
    // -----------------------------------------------------------------------

    /**
     * In {@code $vm::labelText}, Ctrl+click on {@code labelText} should navigate
     * to the property: and the underlined range must cover only {@code labelText},
     * not {@code :labelText} or {@code ::labelText}.
     */
    @Test
    void observableSelectionOperatorSegmentRangeIsCorrect() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.Region",
                """
                  <Region minHeight="$message::<caret>labelText"/>
                """
        ));
        ReadAction.run(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue attrVal = com.intellij.psi.util.PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            assertNotNull(attrVal);
            int relOffset = offset - attrVal.getTextRange().getStartOffset();
            for (PsiReference ref : attrVal.getReferences()) {
                if (!(ref instanceof Fxml2BindingSegmentReference)) continue;
                var range = ref.getRangeInElement();
                if (range.containsOffset(relOffset)) {
                    // The range must start exactly at 'l' of 'labelText',
                    // i.e. the :: must NOT be included in the underlined range.
                    String attrText = attrVal.getText(); // includes surrounding quotes
                    char charAtStart = attrText.charAt(range.getStartOffset());
                    assertEquals('l', charAtStart,
                            "Range should start at 'l' of 'labelText', not at ':'. "
                            + "Range=" + range + " text='" + attrText.substring(range.getStartOffset(), range.getEndOffset()) + "'");
                    return;
                }
            }
            fail("No Fxml2BindingSegmentReference found covering the caret position");
        });
    }

    /**
     * Finds the first {@link Fxml2BindingSegmentReference} at the caret that resolves to a
     * non-null element. Skips {@code Fxml2ExpressionReference} (always-null blocker) and
     * {@code LiteralValueReferenceProvider} references (which resolve to property setters).
     */
    private PsiElement resolveSegmentAtCaret() {
        return ReadAction.compute(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue attrVal = com.intellij.psi.util.PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            if (attrVal == null) return null;
            int relOffset = offset - attrVal.getTextRange().getStartOffset();
            for (PsiReference ref : attrVal.getReferences()) {
                if (!(ref instanceof Fxml2BindingSegmentReference)) continue;
                if (ref.getRangeInElement().containsOffset(relOffset)) {
                    return ref.resolve();
                }
            }
            return null;
        });
    }

    // -----------------------------------------------------------------------
    // AddImportForClassReferenceIntention
    // -----------------------------------------------------------------------

    /**
     * When the caret is on a class-name segment inside a binding expression that uses a
     * fully-qualified class name without a corresponding import, the
     * "Add import for 'ClassName'" intention action must be available and must insert
     * the correct {@code <?import fqn?>} PI.
     */
    @Test
    void addImportIntentionOfferedForUnimportedClassInBindingExpression() {
        getFixture().configureByText("AddImportBinding.fxml", fxml2(
                "javafx.scene.layout.Region",
                // Cursor on "KeyEvent": no import for it in the file
                """
                  <Region minHeight="$javafx.scene.input.Key<caret>Event.KEY_PRESSED"/>
                """
        ));
        IntentionAction action = getFixture().findSingleIntention("Add import for class reference");
        assertNotNull(action, "Expected 'Add import for class reference' intention to be available");
        getFixture().launchAction(action);
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("<?import javafx.scene.input.KeyEvent?>"),
                "Expected import to be added, got: " + result);
        assertTrue(result.contains("$KeyEvent.KEY_PRESSED"),
                "Expected redundant qualifier to be stripped, got: " + result);
    }

    /**
     * When the class referenced in a binding expression is already imported, the
     * "Add import" intention must NOT be available (there is nothing to add).
     */
    @Test
    void addImportIntentionNotOfferedWhenClassAlreadyImported() {
        getFixture().configureByText("AddImportBindingAlready.fxml", fxml2(
                "javafx.scene.layout.Region\njavafx.scene.input.KeyEvent",
                // KeyEvent IS imported, intention must not appear
                """
                  <Region minHeight="$javafx.scene.input.Key<caret>Event.KEY_PRESSED"/>
                """
        ));
        List<IntentionAction> actions = getFixture().getAvailableIntentions();
        assertFalse(actions.stream().anyMatch(f -> f.getText().equals("Add import for class reference")),
                "Should not offer 'Add import for class reference' when already imported, got: "
                        + actions.stream().map(IntentionAction::getText).toList());
    }

    /**
     * When the caret is on the class-name segment of a FQN static-property attribute
     * (e.g. {@code org.jfxcore.command.Com<caret>mand.onAction}) and the class is not
     * yet imported, the "Add import for class reference" intention must be available,
     * insert the correct {@code <?import?>} PI, and rename the attribute to the short form
     * (e.g. {@code Command.onAction}).
     */
    @Test
    void addImportIntentionOfferedForFqnStaticPropertyAttributeName() {
        getFixture().addFileToProject("org/jfxcore/command/Command.java", """
                package org.jfxcore.command;
                import javafx.beans.NamedArg;
                import javafx.event.EventTarget;
                public class Command {
                    public Command(@NamedArg("text") String text) {}
                    public static void setOnAction(EventTarget owner, Command cmd) {}
                    public static Command getOnAction(EventTarget owner) { return null; }
                }
                """);
        getFixture().configureByText("AddImportStaticAttr.fxml", fxml2(
                "javafx.scene.control.Button",
                // Command is NOT imported; cursor on "Command" in the attribute name
                """
                  <Button org.jfxcore.command.Com<caret>mand.onAction="$model.actionCommand"/>
                """
        ));
        IntentionAction action = getFixture().findSingleIntention("Add import for class reference");
        assertNotNull(action, "Expected 'Add import for class reference' intention to be available");
        getFixture().launchAction(action);
        String result = getFixture().getEditor().getDocument().getText();
        assertTrue(result.contains("<?import org.jfxcore.command.Command?>"),
                "Expected import to be added, got: " + result);
        assertTrue(result.contains("Command.onAction=\"$model.actionCommand\""),
                "Expected attribute renamed to short form, got: " + result);
        assertFalse(result.contains("org.jfxcore.command.Command.onAction"),
                "Expected FQN attribute name to be replaced with short form, got: " + result);
    }

    /**
     * When the class of a FQN static-property attribute is already imported,
     * the "Add import for class reference" intention must NOT be offered.
     */
    @Test
    void addImportIntentionNotOfferedForFqnStaticPropertyAttributeWhenAlreadyImported() {
        getFixture().addFileToProject("org/jfxcore/command/Command2.java", """
                package org.jfxcore.command;
                import javafx.beans.NamedArg;
                import javafx.event.EventTarget;
                public class Command2 {
                    public Command2(@NamedArg("text") String text) {}
                    public static void setOnAction(EventTarget owner, Command2 cmd) {}
                    public static Command2 getOnAction(EventTarget owner) { return null; }
                }
                """);
        getFixture().configureByText("AddImportStaticAttrAlready.fxml", fxml2(
                "javafx.scene.control.Button\norg.jfxcore.command.Command2", // Command2 IS imported
                """
                  <Button org.jfxcore.command.Com<caret>mand2.onAction="$model.actionCommand"/>
                """
        ));
        List<IntentionAction> actions = getFixture().getAvailableIntentions();
        assertFalse(
                actions.stream().anyMatch(f -> f.getText().equals("Add import for class reference")),
                "Should not offer 'Add import for class reference' when already imported, got: "
                        + actions.stream().map(IntentionAction::getText).toList());
    }

    // -----------------------------------------------------------------------
    // Context selector hover tooltip (LightFieldBuilder)
    // -----------------------------------------------------------------------

    /**
     * Hovering over {@code self} in {@code $self/foo} must resolve to a
     * {@link PsiField} whose type is the enclosing tag's class and whose name is
     * {@code "self"}: so IntelliJ's Java documentation provider renders
     * e.g. {@code VBox self} in the tooltip.
     */
    @Test
    void selfSelectorResolvesToLightFieldWithCorrectType() {
        // fxml2() wraps body in <BorderPane>, so the VBox is a child and self = VBox
        getFixture().configureByText("SelfHover.fxml", fxml2(
                "javafx.scene.layout.VBox",
                """
                  <VBox minHeight="$se<caret>lf/prefWidth"/>
                """
        ));
        ReadAction.run(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue attrVal = com.intellij.psi.util.PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            assertNotNull(attrVal);
            PsiElement resolved = resolveFirstReferenceAt(attrVal, offset);
            assertNotNull(resolved, "Expected a resolved reference at 'self'");
            assertInstanceOf(PsiField.class, resolved,
                    "Expected a PsiField (LightFieldBuilder) for self, got: " + resolved.getClass());
            PsiField field = (PsiField) resolved;
            assertEquals("self", field.getName(), "Field name should be the selector text");
            String typeName = field.getType().getPresentableText();
            assertEquals("VBox", typeName, "Field type should be the enclosing tag class (VBox)");
        });
    }

    /**
     * Hovering over {@code parent} in {@code $parent/foo} must resolve to a
     * {@link PsiField} whose type is the parent tag's class and whose name is
     * {@code "parent"}.
     */
    @Test
    void parentSelectorResolvesToLightFieldWithCorrectType() {
        // fxml2() wraps body in <BorderPane fx:subclass="test.TestView">; VBox's parent = root tag
        // For the root tag, the type shown is the fx:subclass code-behind (TestView), not BorderPane.
        getFixture().configureByText("ParentHover.fxml", fxml2(
                "javafx.scene.layout.VBox",
                """
                  <VBox minHeight="$par<caret>ent/prefWidth"/>
                """
        ));
        ReadAction.run(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue attrVal = com.intellij.psi.util.PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            assertNotNull(attrVal);
            PsiElement resolved = resolveFirstReferenceAt(attrVal, offset);
            assertNotNull(resolved, "Expected a resolved reference at 'parent'");
            assertInstanceOf(PsiField.class, resolved,
                    "Expected a PsiField (LightFieldBuilder) for parent, got: " + resolved.getClass());
            PsiField field = (PsiField) resolved;
            assertEquals("parent", field.getName(), "Field name should be 'parent'");
            String typeName = field.getType().getPresentableText();
            // VBox's parent is the root: use code-behind class TestView, not BorderPane
            assertEquals("TestView", typeName, "Field type for root target should be the code-behind class");
        });
    }

    /**
     * Hovering over {@code this} in {@code $this.foo} must resolve to a
     * {@link PsiField} whose type is the code-behind class and whose name is
     * {@code "this"}.
     */
    @Test
    void thisSelectorResolvesToLightFieldWithCorrectType() {
        // fxml2() always uses <BorderPane fx:subclass="test.TestView"> as root.
        // "this" navigates to the root tag: tooltip shows code-behind class TestView.
        getFixture().configureByText("ThisHover.fxml", fxml2(
                "javafx.scene.layout.VBox",
                """
                  <VBox minHeight="$thi<caret>s.prefWidth"/>
                """
        ));
        ReadAction.run(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue attrVal = com.intellij.psi.util.PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            assertNotNull(attrVal);
            PsiElement resolved = resolveFirstReferenceAt(attrVal, offset);
            assertNotNull(resolved, "Expected a resolved reference at 'this'");
            assertInstanceOf(PsiField.class, resolved,
                    "Expected a PsiField (LightFieldBuilder) for this, got: " + resolved.getClass());
            PsiField field = (PsiField) resolved;
            assertEquals("this", field.getName(), "Field name should be 'this'");
            String typeName = field.getType().getPresentableText();
            // Root target -> code-behind class TestView, not the XML tag class BorderPane
            assertEquals("TestView", typeName, "Field type for root target should be the code-behind class");
        });
    }

    /** Finds the first PsiReference covering {@code absoluteOffset} and returns its resolved element. */
    private @Nullable PsiElement resolveFirstReferenceAt(
            @NotNull XmlAttributeValue attrVal, int absoluteOffset) {
        int relOffset = absoluteOffset - attrVal.getTextRange().getStartOffset();
        for (PsiReference ref : attrVal.getReferences()) {
            if (ref.getRangeInElement().containsOffset(relOffset) && ref.resolve() != null) {
                return ref.resolve();
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Element-notation binding source references
    // (<fx:Observe source="path"/>, <fx:Evaluate source="path"/>, etc.)
    // -----------------------------------------------------------------------

    /**
     * The {@code source=} attribute value in an element-notation binding
     * (e.g. {@code <fx:Evaluate source="message"/>}) must be navigatable via Ctrl+click.
     * Each path segment should resolve to the corresponding field or property method.
     */
    @Test
    void elementNotationSourceValueResolvesPathSegment() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label>
                    <text><fx:Evaluate source="me<caret>ssage"/></text>
                  </Label>
                """
        ));
        ReadAction.run(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue attrVal = com.intellij.psi.util.PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            assertNotNull(attrVal, "Expected XmlAttributeValue at caret");
            PsiElement resolved = resolveFirstReferenceAt(attrVal, offset);
            assertNotNull(resolved,
                    "source=\"message\" on <fx:Evaluate> must resolve to message property");
            assertInstanceOf(PsiMethod.class, resolved,
                    "Expected PsiMethod (messageProperty or getMessage), got: " + resolved.getClass());
        });
    }

    /**
     * The {@code source=} attribute name on an element-notation binding tag must carry
     * a URL reference so that Ctrl+click on the name opens the expression-docs page.
     */
    @Test
    void elementNotationSourceAttrNameHasUrlReference() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label>
                    <text><fx:Evaluate sou<caret>rce="message"/></text>
                  </Label>
                """
        ));
        ReadAction.run(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttribute attr = com.intellij.psi.util.PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttribute.class, false);
            assertNotNull(attr, "Expected XmlAttribute at caret position on 'source='");
            boolean hasRef = false;
            for (PsiReference ref : attr.getReferences()) {
                if (ref.resolve() != null) {
                    hasRef = true;
                    break;
                }
            }
            assertTrue(hasRef, "source= attribute name on <fx:Evaluate> must have a reference");
        });
    }

    /**
     * The {@code parent/} context selector in an element-notation binding source value
     * (e.g. {@code <fx:Observe source="parent/prefWidth"/>}) must be navigatable via Ctrl+click.
     * The {@code parent} token should resolve to a LightFieldBuilder whose name is {@code "parent"}
     * and whose type is the nearest enclosing class ancestor tag.
     */
    @Test
    void elementNotationParentSelectorResolvesToLightField() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.layout.VBox",
                """
                  <VBox>
                    <minHeight><fx:Observe source="par<caret>ent/prefWidth"/></minHeight>
                  </VBox>
                """
        ));
        ReadAction.run(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue attrVal = com.intellij.psi.util.PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            assertNotNull(attrVal, "Expected XmlAttributeValue at caret");
            PsiElement resolved = resolveFirstReferenceAt(attrVal, offset);
            assertNotNull(resolved,
                    "source=\"parent/prefWidth\" - 'parent' token must resolve to a LightField");
            assertInstanceOf(PsiField.class, resolved,
                    "Expected a PsiField (LightFieldBuilder) for parent, got: " + resolved.getClass());
            PsiField field = (PsiField) resolved;
            assertEquals("parent", field.getName(), "Field name should be the selector text 'parent'");
        });
    }

    /**
     * A multi-segment path in an element-notation binding source value resolves each dot-separated
     * segment independently, enabling Ctrl+click navigation on each segment.
     */
    @Test
    void elementNotationMultiSegmentPathResolvesEachSegment() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.StringProperty;
                import javafx.beans.property.SimpleStringProperty;
                public class ViewModel {
                    private final StringProperty label = new SimpleStringProperty();
                    public StringProperty labelProperty() { return label; }
                    public String getLabel() { return label.get(); }
                }
                """);
        getFixture().addClass("""
                package test;
                public class TestViewWithVm extends TestViewBase {
                    public ViewModel viewModel = new ViewModel();
                }
                """);
        getFixture().configureByText("TestViewWithVm.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label>
                    <text><fx:Observe source="viewModel.la<caret>bel"/></text>
                  </Label>
                """,
                "test.TestViewWithVm"
        ));
        ReadAction.run(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue attrVal = com.intellij.psi.util.PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            assertNotNull(attrVal, "Expected XmlAttributeValue at caret");
            PsiElement resolved = resolveFirstReferenceAt(attrVal, offset);
            assertNotNull(resolved,
                    "source=\"viewModel.label\" - 'label' segment must resolve to a property member");
            assertInstanceOf(PsiMethod.class, resolved,
                    "Expected PsiMethod for 'label' (labelProperty or getLabel), got: "
                            + resolved.getClass());
        });
    }

    // -----------------------------------------------------------------------
    // Fxml2BindingSegmentSearcher: global search: property accessor finds FXML binding
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // String conversion: format= and converter= param paths
    // -----------------------------------------------------------------------

    /**
     * Each segment of the {@code format=} path in a bidirectional binding expression
     * (e.g. {@code #{value; format=myFormat}}) must carry a {@link Fxml2BindingSegmentReference}
     * that resolves to the corresponding property accessor.
     */
    @Test
    void formatParamPathSegmentResolvesViaCtrlClick() {
        getFixture().addClass("""
                package test;
                import java.text.Format;
                import java.text.NumberFormat;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                public class TestViewFmt extends TestViewBase {
                    private final ObjectProperty<Number> amount =
                            new SimpleObjectProperty<>(this, "amount", 0);
                    public ObjectProperty<Number> amountProperty() { return amount; }
                    public Number getAmount() { return amount.get(); }
                    public void setAmount(Number v) { amount.set(v); }
                    private final ObjectProperty<Format> numFmt =
                            new SimpleObjectProperty<>(this, "numFmt", NumberFormat.getInstance());
                    public ObjectProperty<Format> numFmtProperty() { return numFmt; }
                    public Format getNumFmt() { return numFmt.get(); }
                    public void setNumFmt(Format v) { numFmt.set(v); }
                }
                """);
        getFixture().configureByText("TestViewFmt.fxml", fxml2(
                "javafx.scene.control.TextField",
                """
                  <TextField text="#{amount; format=num<caret>Fmt}"/>
                """,
                "test.TestViewFmt"
        ));
        PsiElement resolved = resolveSegmentAtCaret();
        assertNotNull(resolved,
                "format= path segment 'numFmt' must resolve to numFmtProperty() or getNumFmt()");
        assertInstanceOf(PsiMethod.class, resolved,
                "Expected a PsiMethod for numFmt segment, got: " + resolved.getClass());
    }

    /**
     * Each segment of the {@code converter=} path in a bidirectional binding expression
     * (e.g. {@code #{value; converter=myConverter}}) must carry a {@link Fxml2BindingSegmentReference}
     * that resolves to the corresponding property accessor.
     */
    @Test
    void converterParamPathSegmentResolvesViaCtrlClick() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.IntegerProperty;
                import javafx.beans.property.SimpleIntegerProperty;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                import javafx.util.StringConverter;
                import javafx.util.converter.IntegerStringConverter;
                public class TestViewCvt extends TestViewBase {
                    private final IntegerProperty count =
                            new SimpleIntegerProperty(this, "count", 0);
                    public IntegerProperty countProperty() { return count; }
                    public int getCount() { return count.get(); }
                    public void setCount(int v) { count.set(v); }
                    private final ObjectProperty<StringConverter<Integer>> conv =
                            new SimpleObjectProperty<>(this, "conv", new IntegerStringConverter());
                    public ObjectProperty<StringConverter<Integer>> convProperty() { return conv; }
                    public StringConverter<Integer> getConv() { return conv.get(); }
                    public void setConv(StringConverter<Integer> v) { conv.set(v); }
                }
                """);
        getFixture().configureByText("TestViewCvt.fxml", fxml2(
                "javafx.scene.control.TextField",
                """
                  <TextField text="#{count; converter=con<caret>v}"/>
                """,
                "test.TestViewCvt"
        ));
        PsiElement resolved = resolveSegmentAtCaret();
        assertNotNull(resolved,
                "converter= path segment 'conv' must resolve to convProperty() or getConv()");
        assertInstanceOf(PsiMethod.class, resolved,
                "Expected a PsiMethod for conv segment, got: " + resolved.getClass());
    }

    /**
     * An unresolvable segment in a {@code format=} path must be reported as an error
     * by the attribute annotator.
     */
    @Test
    void formatParamPathUnresolvedSegmentIsError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.TextField",
                "  <TextField text=\"#{"
                + error("'doesNotExist' in test.TestView cannot be resolved", "doesNotExist")
                + "; format="
                + error("'noSuchFormat' in test.TestView cannot be resolved", "noSuchFormat")
                + "}\"/>\n"
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@link ReferencesSearch} on the property accessor used in a {@code format=} path must
     * find the usage in the FXML2 binding expression.
     */
    @Test
    void findUsagesOnPropertyFindsFormatParamPathUsage() {
        getFixture().addClass("""
                package test;
                import java.text.Format;
                import java.text.NumberFormat;
                import javafx.beans.property.ObjectProperty;
                import javafx.beans.property.SimpleObjectProperty;
                public class TestViewFmtSearch extends TestViewBase {
                    private final ObjectProperty<Number> amount =
                            new SimpleObjectProperty<>(this, "amount", 0);
                    public ObjectProperty<Number> amountProperty() { return amount; }
                    public Number getAmount() { return amount.get(); }
                    public void setAmount(Number v) { amount.set(v); }
                    private final ObjectProperty<Format> numFmt =
                            new SimpleObjectProperty<>(this, "numFmt", NumberFormat.getInstance());
                    public ObjectProperty<Format> numFmtProperty() { return numFmt; }
                    public Format getNumFmt() { return numFmt.get(); }
                    public void setNumFmt(Format v) { numFmt.set(v); }
                }
                """);
        getFixture().configureByText("TestViewFmtSearch.fxml", fxml2(
                "javafx.scene.control.TextField",
                """
                  <TextField text="#{amount; format=numFmt}"/>
                """,
                "test.TestViewFmtSearch"
        ));

        PsiMethod numFmtProperty = ReadAction.compute(() -> {
            PsiClass testView = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.TestViewFmtSearch",
                            GlobalSearchScope.allScope(getFixture().getProject()));
            assertNotNull(testView, "test.TestViewFmtSearch must be present");
            PsiMethod[] methods = testView.findMethodsByName("numFmtProperty", false);
            assertTrue(methods.length > 0, "numFmtProperty() must exist on TestViewFmtSearch");
            return methods[0];
        });

        ReadAction.run(() -> {
            var refs = ReferencesSearch.search(
                                    numFmtProperty,
                                    GlobalSearchScope.allScope(getFixture().getProject()))
                            .findAll();
            boolean foundInFxml = refs.stream().anyMatch(
                    ref -> ref instanceof Fxml2BindingSegmentReference
                           && ref.getElement().getContainingFile() instanceof XmlFile);
            assertTrue(foundInFxml,
                    "ReferencesSearch on numFmtProperty() must find the 'numFmt' segment in the "
                    + "format= param path.\nFound refs: "
                    + refs.stream()
                            .map(r -> r.getClass().getSimpleName()
                                      + " in " + r.getElement().getContainingFile().getName())
                            .toList());
        });
    }

    /**
     * {@link ReferencesSearch} on {@code messageProperty()} must find the binding-path
     * segment {@code "message"} in a standalone FXML2 file via
     * {@link org.jfxcore.fxml.lang.Fxml2BindingSegmentSearcher}'s global-search path.
     *
     * <p>Without the global-search handler, {@code CachesBasedRefSearcher} looks for the
     * literal word {@code "messageProperty"} in FXML files and finds nothing, because the
     * binding expression uses the short property name {@code "message"}.
     *
     * <p>The code-behind {@code test.TestView} with {@code messageProperty()} is supplied
     * by the {@code @BeforeEach addCodeBehind()} setup method.
     */
    @Test
    void findUsagesOnPropertyAccessorMethodFindsStandaloneFxmlBinding() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Observe message}"/>
                """
        ));

        PsiMethod messageProperty = ReadAction.compute(() -> {
            PsiClass testView = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.TestView",
                            GlobalSearchScope.allScope(getFixture().getProject()));
            assertNotNull(testView, "test.TestView must be present (added by addCodeBehind)");
            PsiMethod[] methods = testView.findMethodsByName("messageProperty", false);
            assertTrue(methods.length > 0, "messageProperty() must exist on TestView");
            return methods[0];
        });

        ReadAction.run(() -> {
            var refs = ReferencesSearch.search(
                                    messageProperty,
                                    GlobalSearchScope.allScope(getFixture().getProject()))
                            .findAll();

            boolean foundInFxml = refs.stream().anyMatch(
                    ref -> ref instanceof Fxml2BindingSegmentReference
                           && ref.getElement().getContainingFile() instanceof XmlFile);
            assertTrue(foundInFxml,
                    "ReferencesSearch on messageProperty() must find the 'message' binding segment "
                    + "in standalone FXML2.\nFound refs: "
                    + refs.stream()
                            .map(r -> r.getClass().getSimpleName()
                                      + " in " + r.getElement().getContainingFile().getName())
                            .toList());
        });
    }

    /**
     * When "Find Usages" is invoked on a property accessor method in the IDE, IntelliJ routes
     * the search through {@link MethodReferencesSearch}, not through {@link ReferencesSearch}.
     * {@link org.jfxcore.fxml.lang.Fxml2BindingSegmentSearcher} only handles
     * {@link ReferencesSearch}, so without a dedicated {@link MethodReferencesSearch} extension,
     * binding-segment uses of the method are never found.
     *
     * <p>This test verifies that {@code MethodReferencesSearch} on a property accessor finds
     * binding-path segments in a standalone FXML2 file, including the case where the property is
     * on a view-model class (a two-level path like {@code vm.message}) rather than directly on
     * the code-behind.
     */
    @Test
    void findUsagesViaMethodSearchOnPropertyAccessorFindsStandaloneFxmlBinding() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.StringProperty;
                import javafx.beans.property.SimpleStringProperty;
                public class BindingPathTestViewModel {
                    private final StringProperty vmMessage = new SimpleStringProperty();
                    public StringProperty vmMessageProperty() { return vmMessage; }
                    public String getVmMessage() { return vmMessage.get(); }
                    public void setVmMessage(String v) { vmMessage.set(v); }
                }
                """);
        getFixture().addClass("""
                package test;
                public class BindingPathTestView extends TestViewBase {
                    public BindingPathTestViewModel vm = new BindingPathTestViewModel();
                }
                """);
        getFixture().configureByText("BindingPathTestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="${vm.vmMessage}"/>
                """,
                "test.BindingPathTestView"
        ));

        PsiMethod vmMessageProperty = ReadAction.compute(() -> {
            PsiClass vm = JavaPsiFacade.getInstance(getFixture().getProject())
                    .findClass("test.BindingPathTestViewModel",
                            GlobalSearchScope.allScope(getFixture().getProject()));
            assertNotNull(vm, "test.BindingPathTestViewModel must be present");
            PsiMethod[] methods = vm.findMethodsByName("vmMessageProperty", false);
            assertTrue(methods.length > 0, "vmMessageProperty() must exist on BindingPathTestViewModel");
            return methods[0];
        });

        ReadAction.run(() -> {
            var refs = MethodReferencesSearch.search(
                            vmMessageProperty,
                            GlobalSearchScope.allScope(getFixture().getProject()),
                            /* checkAccessScope= */ true)
                    .findAll();
            boolean foundInFxml = refs.stream().anyMatch(
                    ref -> ref instanceof Fxml2BindingSegmentReference
                           && ref.getElement().getContainingFile() instanceof XmlFile);
            assertTrue(foundInFxml,
                    "MethodReferencesSearch on vmMessageProperty() must find the 'vmMessage' binding "
                    + "segment in the standalone FXML2 file.\nFound refs: "
                    + refs.stream()
                            .map(r -> r.getClass().getSimpleName()
                                      + " in " + r.getElement().getContainingFile().getName())
                            .toList());
        });
    }

    // -----------------------------------------------------------------------
    // Kotlin val property on the code-behind class (no vmProperty())
    // -----------------------------------------------------------------------

    /**
     * A Kotlin {@code val} property (without a corresponding {@code vmProperty()} function)
     * on the code-behind class must resolve as the first binding-path segment in a standalone
     * FXML2 file.
     *
     * <p>When the code-behind is a Kotlin class and its property {@code vm} is declared as
     * {@code val vm: ObjectProperty<Vm>} (no explicit {@code vmProperty()} companion),
     * the Kotlin compiler generates a public getter {@code getVm()}.  The plugin must
     * recognize this getter as the property accessor and resolve the {@code "vm"} segment
     * to it, enabling Ctrl+click navigation and suppressing false-positive "Property 'vm'
     * is never used" warnings.
     */
    @Test
    void kotlinValPropertyOnCodeBehindResolvesWithoutError() {
        getFixture().addClass("""
                package test;
                public class KtValCodeBehindVm {
                    public String getText() { return "hello"; }
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public abstract class KtValCodeBehindBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        // Kotlin code-behind with a plain val property (generates getVm(), no vmProperty())
        getFixture().addFileToProject("test/KtValCodeBehind.kt", """
                package test
                import javafx.beans.property.ObjectProperty
                import javafx.beans.property.SimpleObjectProperty
                class KtValCodeBehind : KtValCodeBehindBase() {
                    val vm: ObjectProperty<KtValCodeBehindVm> = SimpleObjectProperty(KtValCodeBehindVm())
                    init { initializeComponent() }
                }
                """);
        getFixture().configureByText("KtValCodeBehind.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="${vm.text}"/>
                """,
                "test.KtValCodeBehind"
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * When a Kotlin code-behind is edited live to add {@code fun vmProperty()}, the
     * resolver cache must be invalidated so that the next call to
     * {@link Fxml2PropertyResolver#resolveInstanceProperty} picks up {@code vmProperty()}
     * instead of returning the previously cached synthetic getter. This requires the Kotlin
     * language modification tracker to be included as a cache dependency in addition to the
     * Java one.
     */
    @Test
    void resolutionCacheIsInvalidatedAfterKotlinSourceEdit() {
        getFixture().addClass("""
                package test;
                public class KtLiveEditVm { public String getText() { return ""; } }
                """);
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public abstract class KtLiveEditBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        PsiFile ktFile = getFixture().addFileToProject("test/KtLiveEditView.kt", """
                package test
                import javafx.beans.property.ObjectProperty
                import javafx.beans.property.SimpleObjectProperty
                class KtLiveEditView : KtLiveEditBase() {
                    val vm: ObjectProperty<KtLiveEditVm> = SimpleObjectProperty(KtLiveEditVm())
                    init { initializeComponent() }
                }
                """);

        var project = getFixture().getProject();
        // Hold the class reference across the live edit: the same PsiClass instance is used
        // in the real IDE across multiple annotator passes, and the cache (stored in its user
        // data) must be invalidated by the Kotlin modification tracker when the source changes.
        var clsRef = new PsiClass[1];

        // Phase 1: resolve vm and populate the per-class resolver cache
        ReadAction.run(() -> {
            clsRef[0] = JavaPsiFacade.getInstance(project)
                    .findClass("test.KtLiveEditView", GlobalSearchScope.allScope(project));
            assertNotNull(clsRef[0]);
            PsiElement before = Fxml2PropertyResolver.resolveInstanceProperty(clsRef[0], "vm");
            assertNotNull(before, "vm must resolve before the live edit");
            assertFalse(
                    before instanceof PsiMethod m && "vmProperty".equals(m.getName()),
                    "vmProperty() must not exist before the live edit");
        });

        // Phase 2: simulate a live edit - insert vmProperty() into the Kotlin source
        WriteCommandAction.runWriteCommandAction(project, () -> {
            Document doc = PsiDocumentManager.getInstance(project).getDocument(ktFile);
            assertNotNull(doc);
            String src = doc.getText();
            int pos = src.indexOf("    init {");
            assertTrue(pos >= 0, "Source must contain an init block");
            doc.insertString(pos, "    fun vmProperty() = vm\n");
            PsiDocumentManager.getInstance(project).commitDocument(doc);
        });

        // Phase 3: re-resolve using the SAME cls instance - the cache must have been
        // invalidated by the Kotlin modification tracker so that vmProperty() is returned.
        ReadAction.run(() -> {
            PsiElement after = Fxml2PropertyResolver.resolveInstanceProperty(clsRef[0], "vm");
            assertTrue(
                    after instanceof PsiMethod m && "vmProperty".equals(m.getName()),
                    () -> "vm must resolve to vmProperty() after source edit; got: " + after);
        });
    }

    /**
     * A binding path with consecutive dots (an empty segment between them) must not throw
     * {@link StringIndexOutOfBoundsException} during reference resolution. Such paths can
     * appear transiently while editing, for example after deleting a segment between two dots.
     */
    @Test
    void consecutiveDotsInBindingPathDoesNotCrash() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="${message..sub}"/>
                """
        ));
        XmlFile file = (XmlFile) getFixture().getFile();
        // Trigger the reference provider directly; must not throw StringIndexOutOfBoundsException.
        assertDoesNotThrow(() -> ReadAction.compute(() -> {
            XmlTag root = file.getRootTag();
            assertNotNull(root);
            XmlTag label = root.findFirstSubTag("Label");
            assertNotNull(label);
            XmlAttribute attr = label.getAttribute("text");
            assertNotNull(attr);
            XmlAttributeValue attrVal = attr.getValueElement();
            assertNotNull(attrVal);
            var result = attrVal.getReferences();
            assertNotNull(result);
            return null;
        }));
    }
}
