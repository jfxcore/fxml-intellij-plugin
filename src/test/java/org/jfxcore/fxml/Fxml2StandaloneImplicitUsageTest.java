package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiInvalidElementAccessException;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import java.util.ArrayList;
import java.util.Objects;
import org.jfxcore.fxml.lang.Fxml2BindingSegmentMethodSearcher;
import org.jfxcore.fxml.lang.Fxml2BindingSegmentReference;
import org.jfxcore.fxml.lang.Fxml2KotlinUnusedSymbolSuppressor;
import org.jfxcore.fxml.lang.Fxml2StandaloneImplicitUsageProvider;
import org.jfxcore.fxml.lang.Fxml2UseScopeEnlarger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Fxml2StandaloneImplicitUsageProvider}: the provider that suppresses
 * false-positive "Field/Method/Function 'X' is never used" warnings for fields and
 * property-accessor methods (including Kotlin functions) that are referenced in
 * <em>standalone</em> FXML2 files via binding expressions.
 *
 * <h3>Scenario</h3>
 * <p>A view class (Java or Kotlin) has a {@code vm} field of a ViewModel type.
 * The ViewModel exposes a JavaFX property via {@code labelTextProperty()}.
 * A standalone FXML2 file binds to this property as {@code {fx:Observe vm.labelText}}.
 *
 * <p>Without the provider, Kotlin's unused-declaration inspection would report
 * {@code fun labelTextProperty()} as "Function is never used" because Kotlin queries
 * the element's {@code useScope} via the Kotlin PSI element ({@code KtNamedFunction})
 * directly, bypassing {@link org.jfxcore.fxml.lang.Fxml2UseScopeEnlarger} which only
 * handles {@link PsiField} and {@link PsiMethod}.
 *
 * <p>With the provider, both Java and Kotlin property accessor methods are recognized as
 * implicitly used when referenced in standalone FXML2 files.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2StandaloneImplicitUsageTest extends Fxml2TestBase {

    /** Base class normally produced by the fxml2 compiler; needed so TestView extends it. */
    @BeforeAll
    void addCommonClasses() {
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public abstract class TestViewBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
    }

    // -----------------------------------------------------------------------
    // Java property-accessor method referenced in standalone FXML2 file
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2StandaloneImplicitUsageProvider} must report a Java
     * {@code xProperty()} method as implicitly used when the method is referenced
     * in a standalone FXML2 file via a binding expression (e.g.
     * {@code {fx:Observe vm.labelText}}).
     *
     * <p>Verifies: Java {@link PsiMethod} -> {@code true}.
     */
    @Test
    void javaPropertyMethodReferencedInStandaloneFxmlIsRecognizedAsUsed() {
        // ViewModel with a labelTextProperty() accessor
        getFixture().addClass("""
                package test;
                import javafx.beans.property.SimpleStringProperty;
                import javafx.beans.property.StringProperty;
                public class JavaStandaloneVm {
                    private final StringProperty labelText = new SimpleStringProperty("initial");
                    public StringProperty labelTextProperty() { return labelText; }
                    public String getLabelText() { return labelText.get(); }
                    public void setLabelText(String v) { labelText.set(v); }
                }
                """);
        // View class with a vm field of the ViewModel type
        getFixture().addClass("""
                package test;
                import javafx.scene.control.Label;
                public class JavaStandaloneView extends JavaStandaloneViewBase {
                    public JavaStandaloneVm vm = new JavaStandaloneVm();
                    public JavaStandaloneView() { initializeComponent(); }
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public abstract class JavaStandaloneViewBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        // Standalone FXML2 file that binds to vm.labelText
        getFixture().addFileToProject("test/JavaStandaloneView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Observe vm.labelText}"/>
                """,
                "test.JavaStandaloneView"
        ));

        // Trigger highlighting so that reference contributors and file indexing run.
        getFixture().configureByText("JavaStandaloneView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Observe vm.labelText}"/>
                """,
                "test.JavaStandaloneView"
        ));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var vmCls = JavaPsiFacade.getInstance(project)
                    .findClass("test.JavaStandaloneVm",
                            GlobalSearchScope.projectScope(project));
            assertNotNull(vmCls, "Must find JavaStandaloneVm class");

            PsiMethod[] methods = vmCls.findMethodsByName("labelTextProperty", false);
            assertTrue(methods.length > 0, "Must find method 'labelTextProperty'");
            PsiMethod labelTextProperty = methods[0];

            var provider = new Fxml2StandaloneImplicitUsageProvider();

            assertTrue(provider.isImplicitUsage(labelTextProperty),
                    "isImplicitUsage must return true for a Java xProperty() method "
                    + "referenced in a standalone FXML2 binding expression");
            assertTrue(provider.isImplicitRead(labelTextProperty),
                    "isImplicitRead must return true for a Java xProperty() method "
                    + "referenced in a standalone FXML2 binding expression");
        });
    }

    /**
     * {@link Fxml2StandaloneImplicitUsageProvider} must NOT report a Java method as
     * implicitly used when that method is NOT referenced in any standalone FXML2 file.
     */
    @Test
    void javaMethodNotReferencedInFxmlIsNotReportedAsUsed() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.SimpleStringProperty;
                import javafx.beans.property.StringProperty;
                public class JavaUnusedVm {
                    private final StringProperty hidden = new SimpleStringProperty("x");
                    public StringProperty hiddenProperty() { return hidden; }
                }
                """);
        // No FXML file references hiddenProperty()

        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var vmCls = JavaPsiFacade.getInstance(project)
                    .findClass("test.JavaUnusedVm",
                            GlobalSearchScope.projectScope(project));
            assertNotNull(vmCls, "Must find JavaUnusedVm class");

            PsiMethod[] methods = vmCls.findMethodsByName("hiddenProperty", false);
            assertTrue(methods.length > 0, "Must find method 'hiddenProperty'");
            PsiMethod hiddenProperty = methods[0];

            var provider = new Fxml2StandaloneImplicitUsageProvider();

            assertFalse(provider.isImplicitUsage(hiddenProperty),
                    "isImplicitUsage must return false for a Java method NOT referenced "
                    + "in any standalone FXML2 file");
            assertFalse(provider.isImplicitRead(hiddenProperty),
                    "isImplicitRead must return false for a Java method NOT referenced "
                    + "in any standalone FXML2 file");
        });
    }

    // -----------------------------------------------------------------------
    // Java field referenced in standalone FXML2 file
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2StandaloneImplicitUsageProvider} must report a Java field as
     * implicitly used when the field is referenced in a standalone FXML2 binding
     * expression (e.g. {@code {fx:Observe vm.labelText}}).
     *
     * <p>Verifies: Java {@link PsiField} -> {@code true}.
     */
    @Test
    void javaFieldReferencedInStandaloneFxmlIsRecognizedAsUsed() {
        getFixture().addClass("""
                package test;
                public class JavaFieldVm {
                    public String labelText = "initial";
                }
                """);
        getFixture().addClass("""
                package test;
                public class JavaFieldView extends JavaFieldViewBase {
                    public JavaFieldVm vm = new JavaFieldVm();
                    public JavaFieldView() { initializeComponent(); }
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public abstract class JavaFieldViewBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().configureByText("JavaFieldView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Observe vm.labelText}"/>
                """,
                "test.JavaFieldView"
        ));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var vmCls = JavaPsiFacade.getInstance(project)
                    .findClass("test.JavaFieldVm",
                            GlobalSearchScope.projectScope(project));
            assertNotNull(vmCls, "Must find JavaFieldVm class");

            PsiField field = vmCls.findFieldByName("labelText", false);
            assertNotNull(field, "Must find field 'labelText'");

            var provider = new Fxml2StandaloneImplicitUsageProvider();

            assertTrue(provider.isImplicitUsage(field),
                    "isImplicitUsage must return true for a Java field referenced "
                    + "in a standalone FXML2 binding expression");
            assertTrue(provider.isImplicitRead(field),
                    "isImplicitRead must return true for a Java field referenced "
                    + "in a standalone FXML2 binding expression");
        });
    }

    // -----------------------------------------------------------------------
    // Kotlin property function referenced in standalone FXML2 file
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2StandaloneImplicitUsageProvider} must report a Kotlin function with the
     * {@code xProperty()} naming pattern as implicitly used when the function is referenced
     * in a standalone FXML2 file via a binding expression (e.g.
     * {@code {fx:Observe vm.labelText}}).
     *
     * <p>A Kotlin {@code xProperty()} function bound via {@code {fx:Observe vm.x}} in a
     * standalone {@code .fxml} file must be reported as implicitly used.
     * Kotlin's unused-declaration inspection queries {@code useScope}
     * via the {@code KtNamedFunction} (not the {@code KtLightMethod} wrapper), so
     * {@link org.jfxcore.fxml.lang.Fxml2UseScopeEnlarger} must add FXML2 files to the
     * scope for {@code KtNamedFunction} elements as well.
     *
     * <p>Verifies: Kotlin {@code KtNamedFunction} (obtained via
     * {@link PsiElement#getNavigationElement()} of the {@code KtLightMethod}) -> {@code true}.
     */
    @Test
    void kotlinPropertyFunctionReferencedInStandaloneFxmlIsRecognizedAsUsed() {
        // Kotlin ViewModel with a labelTextProperty() function
        getFixture().addFileToProject("test/KtStandaloneVm.kt", """
                package test
                import javafx.beans.property.SimpleStringProperty
                import javafx.beans.property.StringProperty
                class KtStandaloneVm {
                    private val labelTextBacking: StringProperty = SimpleStringProperty("initial")
                    fun labelTextProperty(): StringProperty = labelTextBacking
                    fun getLabelText(): String = labelTextBacking.get()
                    fun setLabelText(v: String) { labelTextBacking.set(v) }
                }
                """);
        // Java view class that has a vm: KtStandaloneVm field
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class KtStandaloneView extends BorderPane {
                    @SuppressWarnings("unused")
                    public KtStandaloneVm vm = new KtStandaloneVm();
                    protected void initializeComponent() {}
                    public KtStandaloneView() { initializeComponent(); }
                }
                """);
        // Standalone FXML2 file that binds to vm.labelText
        getFixture().configureByText("KtStandaloneView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Observe vm.labelText}"/>
                """,
                "test.KtStandaloneView"
        ));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var vmCls = JavaPsiFacade.getInstance(project)
                    .findClass("test.KtStandaloneVm",
                            GlobalSearchScope.projectScope(project));
            assertNotNull(vmCls, "Must find KtStandaloneVm class");

            // Get the KtLightMethod (PsiMethod) for labelTextProperty()
            PsiMethod[] methods = vmCls.findMethodsByName("labelTextProperty", false);
            assertTrue(methods.length > 0,
                    "Must find method 'labelTextProperty' on KtStandaloneVm");
            PsiMethod lightMethod = methods[0];

            // Get the underlying Kotlin PSI element (KtNamedFunction): this is what
            // Kotlin's unused-declaration inspection queries.
            PsiElement ktFun = lightMethod.getNavigationElement();
            assertNotNull(ktFun, "KtLightMethod must have a navigation element (KtNamedFunction)");
            // Verify it's actually a Kotlin function, not the light method itself
            assertNotSame(lightMethod, ktFun,
                    "Navigation element should differ from the KtLightMethod for a Kotlin source");
            assertTrue(ktFun.getClass().getName().contains("KtNamedFunction"),
                    "Navigation element should be a KtNamedFunction, got: "
                    + ktFun.getClass().getName());

            var provider = new Fxml2StandaloneImplicitUsageProvider();

            // The KtNamedFunction is what Kotlin's inspection passes to isImplicitUsage.
            assertTrue(provider.isImplicitUsage(ktFun),
                    "isImplicitUsage must return true for a Kotlin fun xProperty() "
                    + "referenced as 'x' in a standalone FXML2 binding expression");
            assertTrue(provider.isImplicitRead(ktFun),
                    "isImplicitRead must return true for a Kotlin fun xProperty() "
                    + "referenced as 'x' in a standalone FXML2 binding expression");
        });
    }

    /**
     * {@link Fxml2StandaloneImplicitUsageProvider} must NOT report a Kotlin function as
     * implicitly used when that function is NOT referenced in any standalone FXML2 file.
     */
    @Test
    void kotlinFunctionNotReferencedInFxmlIsNotReportedAsUsed() {
        getFixture().addFileToProject("test/KtUnusedVm.kt", """
                package test
                import javafx.beans.property.SimpleStringProperty
                import javafx.beans.property.StringProperty
                class KtUnusedVm {
                    private val hidden: StringProperty = SimpleStringProperty("x")
                    fun hiddenProperty(): StringProperty = hidden
                }
                """);
        // No FXML file references hiddenProperty() on KtUnusedVm

        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var vmCls = JavaPsiFacade.getInstance(project)
                    .findClass("test.KtUnusedVm",
                            GlobalSearchScope.projectScope(project));
            assertNotNull(vmCls, "Must find KtUnusedVm class");

            PsiMethod[] methods = vmCls.findMethodsByName("hiddenProperty", false);
            assertTrue(methods.length > 0, "Must find method 'hiddenProperty'");
            PsiElement ktFun = methods[0].getNavigationElement();
            assertNotSame(methods[0], ktFun,
                    "Navigation element should differ from the KtLightMethod");

            var provider = new Fxml2StandaloneImplicitUsageProvider();

            assertFalse(provider.isImplicitUsage(ktFun),
                    "isImplicitUsage must return false for a Kotlin function NOT referenced "
                    + "in any standalone FXML2 file");
            assertFalse(provider.isImplicitRead(ktFun),
                    "isImplicitRead must return false for a Kotlin function NOT referenced "
                    + "in any standalone FXML2 file");
        });
    }

    // -----------------------------------------------------------------------
    // Kotlin val property (no @JvmField) referenced in standalone FXML2 file
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2StandaloneImplicitUsageProvider} must report a regular Kotlin {@code val}
     * property (without {@code @JvmField}) as implicitly used when the property is referenced
     * in a standalone FXML2 file via a binding expression (e.g.
     * {@code {fx:Observe vm.message}}).
     *
     * <p>For a Kotlin {@code val} property without {@code @JvmField}, the Kotlin compiler
     * generates a private backing field and a public getter (e.g. {@code getMessage()}).
     * Kotlin's unused-declaration inspection passes the {@code KtProperty} element (not the
     * getter's {@code KtLightMethod}) to {@link Fxml2StandaloneImplicitUsageProvider#isImplicitUsage}.
     * The provider must recognize the property as used via the navigation-element fallback:
     * the binding segment resolves to the getter {@code KtLightMethod}, whose
     * {@code getNavigationElement()} returns the {@code KtProperty}.
     *
     * <p>Verifies: {@code KtProperty} (obtained via the getter's navigation element) -> {@code true}.
     */
    @Test
    void kotlinValPropertyWithoutJvmFieldReferencedInStandaloneFxmlIsRecognizedAsUsed() {
        getFixture().addFileToProject("test/KtValVm.kt", """
                package test
                import javafx.beans.property.SimpleStringProperty
                import javafx.beans.property.StringProperty
                class KtValVm {
                    val message: StringProperty = SimpleStringProperty("hello")
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class KtValView extends BorderPane {
                    @SuppressWarnings("unused")
                    public KtValVm vm = new KtValVm();
                    protected void initializeComponent() {}
                    public KtValView() { initializeComponent(); }
                }
                """);
        getFixture().configureByText("KtValView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="${vm.message}"/>
                """,
                "test.KtValView"
        ));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var vmCls = JavaPsiFacade.getInstance(project)
                    .findClass("test.KtValVm",
                            GlobalSearchScope.projectScope(project));
            assertNotNull(vmCls, "Must find KtValVm class");

            // For a non-@JvmField val property, the getter KtLightMethod (getMessage())
            // has a navigation element that is the underlying KtProperty.
            PsiMethod[] methods = vmCls.findMethodsByName("getMessage", false);
            assertTrue(methods.length > 0, "Must find getter 'getMessage' on KtValVm");
            PsiElement ktProp = methods[0].getNavigationElement();
            assertNotNull(ktProp, "Getter must have a navigation element");
            assertNotSame(methods[0], ktProp,
                    "Navigation element must differ from the getter KtLightMethod");
            assertTrue(ktProp.getClass().getName().contains("KtProperty"),
                    "Navigation element must be a KtProperty, got: " + ktProp.getClass().getName());

            var provider = new Fxml2StandaloneImplicitUsageProvider();

            assertTrue(provider.isImplicitUsage(ktProp),
                    "isImplicitUsage must return true for a Kotlin val property "
                    + "referenced in a standalone FXML2 binding expression");
            assertTrue(provider.isImplicitRead(ktProp),
                    "isImplicitRead must return true for a Kotlin val property "
                    + "referenced in a standalone FXML2 binding expression");
        });
    }

    /**
     * {@link Fxml2StandaloneImplicitUsageProvider} must NOT report a Kotlin {@code val}
     * property as implicitly used when that property is NOT referenced in any standalone
     * FXML2 file.
     */
    @Test
    void kotlinValPropertyNotReferencedInFxmlIsNotReportedAsUsed() {
        getFixture().addFileToProject("test/KtUnusedValVm.kt", """
                package test
                import javafx.beans.property.SimpleStringProperty
                import javafx.beans.property.StringProperty
                class KtUnusedValVm {
                    val hidden: StringProperty = SimpleStringProperty("x")
                }
                """);
        // No FXML file references the "hidden" property on KtUnusedValVm

        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var vmCls = JavaPsiFacade.getInstance(project)
                    .findClass("test.KtUnusedValVm",
                            GlobalSearchScope.projectScope(project));
            assertNotNull(vmCls, "Must find KtUnusedValVm class");

            PsiMethod[] methods = vmCls.findMethodsByName("getHidden", false);
            assertTrue(methods.length > 0, "Must find getter 'getHidden'");
            PsiElement ktProp = methods[0].getNavigationElement();
            assertNotSame(methods[0], ktProp,
                    "Navigation element must differ from the getter KtLightMethod");

            var provider = new Fxml2StandaloneImplicitUsageProvider();

            assertFalse(provider.isImplicitUsage(ktProp),
                    "isImplicitUsage must return false for a Kotlin val property NOT "
                    + "referenced in any standalone FXML2 file");
            assertFalse(provider.isImplicitRead(ktProp),
                    "isImplicitRead must return false for a Kotlin val property NOT "
                    + "referenced in any standalone FXML2 file");
        });
    }

    /**
     * When a Kotlin code-behind class directly declares {@code val vm: ObjectProperty<Vm>}
     * (without a companion {@code vmProperty()} function), the {@code "vm"} binding-path
     * segment in the standalone FXML2 file must resolve and the property must be recognized
     * as implicitly used.
     *
     * <p>This covers the scenario where the code-behind class itself has a Kotlin
     * {@code val} property as a direct member (not inside a separate ViewModel class).
     * The fxml2 compiler accepts this because it generates a public getter
     * {@code getVm()} from the {@code val} declaration. The plugin must do the same.
     *
     * <p>Verifies: {@code KtProperty vm} directly on the code-behind class ->
     * {@link Fxml2StandaloneImplicitUsageProvider#isImplicitUsage} returns {@code true}.
     */
    @Test
    void kotlinValPropertyDirectlyOnCodeBehindIsRecognizedAsUsed() {
        getFixture().addClass("""
                package test;
                public class KtDirectValVm {
                    public String getText() { return "hello"; }
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public abstract class KtDirectValBase extends BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().addFileToProject("test/KtDirectValView.kt", """
                package test
                import javafx.beans.property.ObjectProperty
                import javafx.beans.property.SimpleObjectProperty
                class KtDirectValView : KtDirectValBase() {
                    val vm: ObjectProperty<KtDirectValVm> = SimpleObjectProperty(KtDirectValVm())
                    init { initializeComponent() }
                }
                """);
        getFixture().configureByText("KtDirectValView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="${vm.text}"/>
                """,
                "test.KtDirectValView"
        ));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var viewCls = JavaPsiFacade.getInstance(project)
                    .findClass("test.KtDirectValView",
                            GlobalSearchScope.projectScope(project));
            assertNotNull(viewCls, "Must find KtDirectValView class");

            // Find the 'vm' getter generated by the Kotlin compiler.
            // Through the getter's navigation element we obtain the underlying KtProperty.
            PsiMethod[] methods = viewCls.findMethodsByName("getVm", false);
            assertTrue(methods.length > 0,
                    "Must find generated getter 'getVm' on KtDirectValView");
            PsiElement ktProp = methods[0].getNavigationElement();
            assertNotNull(ktProp, "Getter must have a navigation element");
            assertNotSame(methods[0], ktProp,
                    "Navigation element must differ from the getter KtLightMethod");
            assertTrue(ktProp.getClass().getName().contains("KtProperty"),
                    "Navigation element must be a KtProperty, got: "
                    + ktProp.getClass().getName());

            var provider = new Fxml2StandaloneImplicitUsageProvider();

            assertTrue(provider.isImplicitUsage(ktProp),
                    "isImplicitUsage must return true for a Kotlin val property "
                    + "declared directly on the code-behind class and referenced in FXML2");
            assertTrue(provider.isImplicitRead(ktProp),
                    "isImplicitRead must return true for a Kotlin val property "
                    + "declared directly on the code-behind class and referenced in FXML2");
        });
    }

    // -----------------------------------------------------------------------
    // Provider robustness when a cached FXML binding reference holds a stale
    //     Kotlin PSI element (PsiInvalidElementAccessException guard)
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2StandaloneImplicitUsageProvider} must not throw
     * {@link PsiInvalidElementAccessException} when a cached binding-segment reference
     * in a standalone FXML2 file resolves to a {@code KtLightMethod} or
     * {@code KtLightField} whose underlying Kotlin source element has been invalidated.
     *
     * <p>This scenario arises when a Kotlin source file is edited while IntelliJ's
     * highlighting pass is scanning FXML2 files for implicit usages.  The FXML2 file's
     * {@link com.intellij.psi.PsiReference} cache retains the old
     * {@link Fxml2BindingSegmentReference} whose {@code myDeclaration} still points to
     * a now-invalid {@code KtLightMethod}.  Calling {@code getNavigationElement()} on
     * that element reaches the detached Kotlin stub and throws
     * {@link PsiInvalidElementAccessException}.  Critically, the element may report
     * {@code isValid() == true} while {@code getNavigationElement()} still throws,
     * so a validity check alone is insufficient.  The provider must guard the call with
     * a {@code try-catch(PsiInvalidElementAccessException)}.
     */
    @Test
    void providerDoesNotThrowWhenCachedBindingReferenceResolvesToInvalidatedKotlinElement() {
        // Kotlin ViewModel with a labelProperty() function
        getFixture().addFileToProject("test/KtInvalidNavVm.kt", """
                package test
                import javafx.beans.property.SimpleStringProperty
                import javafx.beans.property.StringProperty
                class KtInvalidNavVm {
                    private val labelBacking: StringProperty = SimpleStringProperty("x")
                    fun labelProperty(): StringProperty = labelBacking
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class KtInvalidNavView extends BorderPane {
                    @SuppressWarnings("unused")
                    public KtInvalidNavVm vm = new KtInvalidNavVm();
                    public KtInvalidNavView() {}
                }
                """);

        // Configure the FXML file with a binding to vm.label and warm up the reference cache.
        getFixture().configureByText("KtInvalidNavView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Observe vm.label}"/>
                """,
                "test.KtInvalidNavView"
        ));
        getFixture().doHighlighting();

        // Retrieve the Fxml2BindingSegmentReference whose myDeclaration points to
        // labelProperty(), then use reflection to replace myDeclaration with a
        // deliberately-broken stub element.  This faithfully simulates the production
        // scenario where:
        //   1. The FXML reference cache retains the old Fxml2BindingSegmentReference.
        //   2. The referenced KtLightMethod reports isValid() == true (the containing
        //      file is still alive) but getNavigationElement() throws because the
        //      underlying KtProperty stub was detached from the file tree during the
        //      concurrent document commit.

        // configureByText sets the FXML file as the current fixture file.
        XmlFile fxmlFile = ReadAction.compute(() -> (XmlFile) getFixture().getFile());

        ReadAction.run(() -> {
            XmlTag root = fxmlFile.getRootTag();
            assertNotNull(root, "Root tag must exist");
            XmlTag labelTag = Arrays.stream(root.getSubTags())
                    .filter(t -> t.getLocalName().equals("Label"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Label tag not found"));
            XmlAttributeValue attrValue = Objects.requireNonNull(labelTag.getAttribute("text")).getValueElement();
            assertNotNull(attrValue, "text attribute value must exist");

            // Find the segment reference for "label" (resolves to labelProperty()).
            Fxml2BindingSegmentReference labelRef = null;
            for (PsiReference ref : attrValue.getReferences()) {
                if (ref instanceof Fxml2BindingSegmentReference bsr) {
                    PsiElement decl = bsr.resolve();
                    if (decl instanceof PsiMethod m && m.getName().equals("labelProperty")) {
                        labelRef = bsr;
                        break;
                    }
                }
            }
            assertNotNull(labelRef,
                    "Must find a Fxml2BindingSegmentReference resolving to labelProperty()");

            // Inject a stub that mimics a stale KtLightMethod: isValid() returns true
            // (the light-wrapper appears alive) but getNavigationElement() throws
            // PsiInvalidElementAccessException (the underlying Kotlin stub is detached).
            // This is the precise state that triggers the production crash.
            try {
                Field f = Fxml2BindingSegmentReference.class.getDeclaredField("myDeclaration");
                f.setAccessible(true);
                PsiElement originalDecl = (PsiElement) f.get(labelRef);
                assertNotNull(originalDecl, "myDeclaration must be non-null before injection");

                f.set(labelRef, new StaleKtLightMethodStub(originalDecl.getManager()));
            } catch (ReflectiveOperationException e) {
                fail("Reflection-based stub injection failed: " + e.getMessage());
            }
        });

        // isImplicitUsage scans the FXML file, finds the patched reference, calls
        // ref.resolve() -> stub, then calls stub.getNavigationElement() -> throws.
        // Without the try-catch guard the exception propagates; with it the provider
        // must return false without throwing.
        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var vmCls = JavaPsiFacade.getInstance(project)
                    .findClass("test.KtInvalidNavVm", GlobalSearchScope.projectScope(project));
            assertNotNull(vmCls, "Must find KtInvalidNavVm");
            PsiMethod[] methods = vmCls.findMethodsByName("labelProperty", false);
            assertTrue(methods.length > 0, "Must find labelProperty");

            var provider = new Fxml2StandaloneImplicitUsageProvider();
            assertDoesNotThrow(
                    () -> provider.isImplicitUsage(methods[0]),
                    "isImplicitUsage must not propagate PsiInvalidElementAccessException "
                    + "when getNavigationElement() throws on a cached FXML binding target");
        });
    }

    // -----------------------------------------------------------------------
    // Fxml2UseScopeEnlarger adds FXML files to the use scope for Kotlin elements
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2UseScopeEnlarger} must return a non-null additional scope for a Kotlin
     * {@code val} property ({@code KtProperty}), so that IntelliJ's word-index-based
     * "cheap enough to search" pre-check includes FXML2 files when determining whether
     * a Kotlin property has any textual occurrences.
     *
     * <p>Without this, the K2 Kotlin unused-declaration inspection calls
     * {@code psiSearchHelper.getUseScope(KtProperty)} before running
     * {@link com.intellij.psi.search.searches.MethodReferencesSearch}: if FXML files
     * are absent from the scope, "sayHelloCommand" has zero occurrences in the word index
     * and the inspection immediately returns "never used" without consulting any
     * {@link com.intellij.codeInsight.daemon.ImplicitUsageProvider}.
     *
     * <p>Verifies: {@code Fxml2UseScopeEnlarger.getAdditionalUseScope(KtProperty)} -> non-null.
     */
    @Test
    void useScopeEnlargerAddsKotlinValPropertyToFxmlScope() {
        getFixture().addFileToProject("test/KtEnlargeVm.kt", """
                package test
                class KtEnlargeVm {
                    val sayHelloCommand: String = "hello"
                }
                """);

        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var vmCls = JavaPsiFacade.getInstance(project)
                    .findClass("test.KtEnlargeVm", GlobalSearchScope.projectScope(project));
            assertNotNull(vmCls, "Must find KtEnlargeVm");

            PsiMethod[] methods = vmCls.findMethodsByName("getSayHelloCommand", false);
            assertTrue(methods.length > 0, "Must find getter getSayHelloCommand");
            PsiElement ktProp = methods[0].getNavigationElement();
            assertNotSame(methods[0], ktProp, "Navigation element must differ from KtLightMethod");
            assertTrue(ktProp.getClass().getName().contains("KtProperty"),
                    "Navigation element must be KtProperty, got: " + ktProp.getClass().getName());

            var enlarger = new Fxml2UseScopeEnlarger();
            SearchScope scope = enlarger.getAdditionalUseScope(ktProp);
            assertNotNull(scope,
                    "UseScopeEnlarger must return a non-null scope for a public Kotlin val property "
                    + "so that FXML2 files are included in the word-index search");
        });
    }

    /**
     * {@link Fxml2UseScopeEnlarger} must return a non-null additional scope for a Kotlin
     * {@code fun xProperty()} function ({@code KtNamedFunction}), applying the same logic
     * as for {@code val} properties.
     *
     * <p>Verifies: {@code Fxml2UseScopeEnlarger.getAdditionalUseScope(KtNamedFunction)} -> non-null
     * for {@code xProperty()}-named functions.
     */
    @Test
    void useScopeEnlargerAddsKotlinPropertyFunctionToFxmlScope() {
        getFixture().addFileToProject("test/KtEnlargeFunVm.kt", """
                package test
                import javafx.beans.property.SimpleStringProperty
                import javafx.beans.property.StringProperty
                class KtEnlargeFunVm {
                    private val backing: StringProperty = SimpleStringProperty("x")
                    fun greetProperty(): StringProperty = backing
                }
                """);

        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var vmCls = JavaPsiFacade.getInstance(project)
                    .findClass("test.KtEnlargeFunVm", GlobalSearchScope.projectScope(project));
            assertNotNull(vmCls, "Must find KtEnlargeFunVm");

            PsiMethod[] methods = vmCls.findMethodsByName("greetProperty", false);
            assertTrue(methods.length > 0, "Must find greetProperty");
            PsiElement ktFun = methods[0].getNavigationElement();
            assertNotSame(methods[0], ktFun, "Navigation element must differ from KtLightMethod");
            assertTrue(ktFun.getClass().getName().contains("KtNamedFunction"),
                    "Navigation element must be KtNamedFunction, got: " + ktFun.getClass().getName());

            var enlarger = new Fxml2UseScopeEnlarger();
            SearchScope scope = enlarger.getAdditionalUseScope(ktFun);
            assertNotNull(scope,
                    "UseScopeEnlarger must return a non-null scope for a public Kotlin xProperty() "
                    + "function so that FXML2 files are included in the word-index search");
        });
    }

    // -----------------------------------------------------------------------
    // MethodReferencesSearch on a Kotlin getter finds binding references in FXML
    // -----------------------------------------------------------------------

    /**
     * {@link MethodReferencesSearch} on a Kotlin-generated getter method (e.g.
     * {@code getSayHelloCommand()} for {@code val sayHelloCommand}) must find the
     * {@link Fxml2BindingSegmentReference} for the corresponding binding-path segment
     * in a standalone FXML2 file.
     *
     * <p>This is the code path exercised by the K2 Kotlin unused-declaration inspection:
     * it calls {@code declaration.toLightMethods()} to get the getter, then calls
     * {@link MethodReferencesSearch#search} on that getter. If no references are found,
     * the inspection immediately marks the property as "never used".
     *
     * <p>For the search to succeed, two conditions must hold:
     * <ol>
     *   <li>The getter's effective use scope must include FXML files (ensured by
     *       {@link Fxml2UseScopeEnlarger} and {@link Fxml2BindingSegmentMethodSearcher}).</li>
     *   <li>{@link Fxml2BindingSegmentReference#isReferenceTo} must return {@code true}
     *       when the target is the getter {@code KtLightMethod} and the resolved declaration
     *       is also a {@code KtLightMethod} for the same property.</li>
     * </ol>
     *
     * <p>Verifies: {@code MethodReferencesSearch.search(getter)} -> at least one
     * {@link Fxml2BindingSegmentReference} found.
     */
    @Test
    void methodReferencesSearchForKotlinGetterFindsStandaloneFxmlReference() {
        getFixture().addFileToProject("test/KtMrsVm.kt", """
                package test
                import javafx.beans.property.SimpleStringProperty
                import javafx.beans.property.StringProperty
                class KtMrsVm {
                    val message: StringProperty = SimpleStringProperty("hello")
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class KtMrsView extends BorderPane {
                    @SuppressWarnings("unused")
                    public KtMrsVm vm = new KtMrsVm();
                    protected void initializeComponent() {}
                    public KtMrsView() { initializeComponent(); }
                }
                """);
        getFixture().configureByText("KtMrsView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="${vm.message}"/>
                """,
                "test.KtMrsView"
        ));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var vmCls = JavaPsiFacade.getInstance(project)
                    .findClass("test.KtMrsVm", GlobalSearchScope.projectScope(project));
            assertNotNull(vmCls, "Must find KtMrsVm");

            // The Kotlin val property generates a public getter getMessage().
            // The K2 inspection calls toLightMethods() to get this getter, then
            // uses MethodReferencesSearch.search(getter) to find usages.
            PsiMethod[] methods = vmCls.findMethodsByName("getMessage", false);
            assertTrue(methods.length > 0, "Must find getter getMessage on KtMrsVm");
            PsiMethod getter = methods[0];

            // MethodReferencesSearch.search(getter) must find the FXML binding reference.
            List<PsiReference> found = new ArrayList<>();
            MethodReferencesSearch.search(getter).forEach(ref -> {
                found.add(ref);
                return true;
            });

            assertFalse(found.isEmpty(),
                    "MethodReferencesSearch.search(getter) must find the Fxml2BindingSegmentReference "
                    + "for 'message' in the standalone FXML2 file; found none. "
                    + "This is the code path used by the K2 Kotlin unused-declaration inspection "
                    + "to determine whether a val property has usages.");

            boolean hasFxmlRef = found.stream()
                    .anyMatch(r -> r instanceof Fxml2BindingSegmentReference);
            assertTrue(hasFxmlRef,
                    "At least one reference found by MethodReferencesSearch must be a "
                    + "Fxml2BindingSegmentReference; found: " + found);
        });
    }

    /**
     * Minimal stub that mimics the invalid state of a stale {@code KtLightMethod}:
     * {@code isValid()} returns {@code true} (the light-wrapper appears alive) while
     * {@code getNavigationElement()} throws {@link PsiInvalidElementAccessException}
     * (the underlying Kotlin PSI stub is detached from the file tree).
     */
    private static final class StaleKtLightMethodStub
            extends com.intellij.psi.impl.light.LightElement {

        StaleKtLightMethodStub(@org.jetbrains.annotations.NotNull com.intellij.psi.PsiManager mgr) {
            super(mgr, com.intellij.lang.Language.ANY);
        }

        // isValid() deliberately returns true even though getNavigationElement() throws.
        // This reproduces the exact race condition observed in production where the
        // KtLightMethod wrapper has not yet been marked invalid while its underlying
        // Kotlin stub was already detached from the file tree.
        @Override
        public boolean isValid() { return true; }

        @Override
        public @org.jetbrains.annotations.NotNull com.intellij.psi.PsiElement getNavigationElement() {
            throw new PsiInvalidElementAccessException(
                    this, "simulated stale Kotlin PSI stub", null);
        }

        @Override
        public String toString() { return "StaleKtLightMethodStub"; }
    }

    /**
     * {@link Fxml2StandaloneImplicitUsageProvider} must report a Kotlin property as
     * implicitly used when the property is referenced in a standalone FXML2 binding
     * expression.
     *
     * <p>Verifies: Kotlin {@code KtProperty} (via navigation element of field or getter) ->
     * {@code true}.
     */
    @Test
    void kotlinPropertyReferencedInStandaloneFxmlIsRecognizedAsUsed() {
        getFixture().addFileToProject("test/KtPropertyVm.kt", """
                package test
                class KtPropertyVm {
                    @JvmField
                    var labelText: String = "initial"
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class KtPropertyView extends BorderPane {
                    @SuppressWarnings("unused")
                    public KtPropertyVm vm = new KtPropertyVm();
                    protected void initializeComponent() {}
                    public KtPropertyView() { initializeComponent(); }
                }
                """);
        getFixture().configureByText("KtPropertyView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Observe vm.labelText}"/>
                """,
                "test.KtPropertyView"
        ));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var vmCls = JavaPsiFacade.getInstance(project)
                    .findClass("test.KtPropertyVm",
                            GlobalSearchScope.projectScope(project));
            assertNotNull(vmCls, "Must find KtPropertyVm class");

            // @JvmField exposes the property as a Java field; nav element is KtProperty
            var field = vmCls.findFieldByName("labelText", false);
            assertNotNull(field, "Must find field 'labelText' on KtPropertyVm");

            PsiElement ktProp = field.getNavigationElement();
            assertNotNull(ktProp, "Field must have a navigation element");
            // For @JvmField, the navigation element is the KtProperty or the field itself;
            // either way the provider should handle it.

            var provider = new Fxml2StandaloneImplicitUsageProvider();

            assertTrue(provider.isImplicitUsage(field),
                    "isImplicitUsage must return true for a Kotlin @JvmField property "
                    + "referenced in a standalone FXML2 binding expression");
            assertTrue(provider.isImplicitRead(field),
                    "isImplicitRead must return true for a Kotlin @JvmField property "
                    + "referenced in a standalone FXML2 binding expression");
        });
    }

    // -----------------------------------------------------------------------
    // Fxml2KotlinUnusedSymbolSuppressor: per-element suppression gate
    // -----------------------------------------------------------------------

    /**
     * {@link Fxml2KotlinUnusedSymbolSuppressor} must return {@code true} for a
     * {@code KtProperty} that IS referenced in a standalone FXML2 binding expression,
     * so that K2's {@code KotlinUnusedHighlightingProcessor} suppresses the
     * "Property is never used" warning at its early {@code inspectionResultSuppressed}
     * check (before the unreliable {@code hasNonTrivialUsages} path).
     *
     * <p>Verifies: {@code isSuppressedFor(KtProperty, "unused")} -> {@code true}.
     */
    @Test
    void suppressorReturnsTrueForKtPropertyReferencedInStandaloneFxml() {
        getFixture().addFileToProject("test/KtSupprVm.kt", """
                package test
                import javafx.beans.property.SimpleStringProperty
                import javafx.beans.property.StringProperty
                class KtSupprVm {
                    val label: StringProperty = SimpleStringProperty("hi")
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class KtSupprView extends BorderPane {
                    @SuppressWarnings("unused")
                    public KtSupprVm vm = new KtSupprVm();
                    protected void initializeComponent() {}
                    public KtSupprView() { initializeComponent(); }
                }
                """);
        getFixture().configureByText("KtSupprView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="${vm.label}"/>
                """,
                "test.KtSupprView"
        ));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var vmCls = JavaPsiFacade.getInstance(project)
                    .findClass("test.KtSupprVm", GlobalSearchScope.projectScope(project));
            assertNotNull(vmCls, "Must find KtSupprVm");

            PsiMethod[] methods = vmCls.findMethodsByName("getLabel", false);
            assertTrue(methods.length > 0, "Must find getter getLabel");
            PsiElement ktProp = methods[0].getNavigationElement();
            assertNotSame(methods[0], ktProp, "Navigation element must differ from KtLightMethod");
            assertTrue(ktProp.getClass().getName().contains("KtProperty"),
                    "Navigation element must be KtProperty, got: " + ktProp.getClass().getName());

            var suppressor = new Fxml2KotlinUnusedSymbolSuppressor();

            assertTrue(suppressor.isSuppressedFor(ktProp, "unused"),
                    "isSuppressedFor must return true for a KtProperty referenced in a "
                    + "standalone FXML2 binding expression, so that the Kotlin unused-symbol "
                    + "inspection is suppressed at the early inspectionResultSuppressed check. "
                    + "The toolId is 'unused' because the K1 and K2 UnusedSymbolInspection "
                    + "registrations declare suppressId=\"unused\": the suppressor must match "
                    + "that exact id, not the inspection's short name 'UnusedSymbol'.");
        });
    }

    /**
     * {@link Fxml2KotlinUnusedSymbolSuppressor} must return {@code false} for a
     * {@code KtProperty} that is NOT referenced in any standalone FXML2 file.
     *
     * <p>Verifies: {@code isSuppressedFor(KtProperty, "unused")} -> {@code false}
     * when there is no FXML binding to the property.
     */
    @Test
    void suppressorReturnsFalseForKtPropertyNotReferencedInStandaloneFxml() {
        getFixture().addFileToProject("test/KtUnusedSupprVm.kt", """
                package test
                import javafx.beans.property.SimpleStringProperty
                import javafx.beans.property.StringProperty
                class KtUnusedSupprVm {
                    val hidden: StringProperty = SimpleStringProperty("x")
                }
                """);
        // No FXML file references "hidden" on KtUnusedSupprVm

        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var vmCls = JavaPsiFacade.getInstance(project)
                    .findClass("test.KtUnusedSupprVm", GlobalSearchScope.projectScope(project));
            assertNotNull(vmCls, "Must find KtUnusedSupprVm");

            PsiMethod[] methods = vmCls.findMethodsByName("getHidden", false);
            assertTrue(methods.length > 0, "Must find getter getHidden");
            PsiElement ktProp = methods[0].getNavigationElement();
            assertNotSame(methods[0], ktProp, "Navigation element must differ from KtLightMethod");
            assertTrue(ktProp.getClass().getName().contains("KtProperty"),
                    "Navigation element must be KtProperty, got: " + ktProp.getClass().getName());

            var suppressor = new Fxml2KotlinUnusedSymbolSuppressor();

            assertFalse(suppressor.isSuppressedFor(ktProp, "unused"),
                    "isSuppressedFor must return false for a KtProperty NOT referenced in "
                    + "any standalone FXML2 file: the suppressor must not suppress genuinely "
                    + "unused properties");
        });
    }

    /**
     * {@link Fxml2KotlinUnusedSymbolSuppressor} must return {@code false} when the
     * {@code toolId} is not {@code "unused"}, regardless of whether the element
     * is referenced in FXML2.
     *
     * <p>Verifies: {@code isSuppressedFor(KtProperty, "SomeOtherInspection")} -> {@code false}.
     */
    @Test
    void suppressorIgnoresWrongToolId() {
        getFixture().addFileToProject("test/KtWrongToolVm.kt", """
                package test
                import javafx.beans.property.SimpleStringProperty
                import javafx.beans.property.StringProperty
                class KtWrongToolVm {
                    val label: StringProperty = SimpleStringProperty("hi")
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class KtWrongToolView extends BorderPane {
                    @SuppressWarnings("unused")
                    public KtWrongToolVm vm = new KtWrongToolVm();
                    protected void initializeComponent() {}
                    public KtWrongToolView() { initializeComponent(); }
                }
                """);
        getFixture().configureByText("KtWrongToolView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="${vm.label}"/>
                """,
                "test.KtWrongToolView"
        ));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var vmCls = JavaPsiFacade.getInstance(project)
                    .findClass("test.KtWrongToolVm", GlobalSearchScope.projectScope(project));
            assertNotNull(vmCls, "Must find KtWrongToolVm");

            PsiMethod[] methods = vmCls.findMethodsByName("getLabel", false);
            assertTrue(methods.length > 0, "Must find getter getLabel");
            PsiElement ktProp = methods[0].getNavigationElement();
            assertNotSame(methods[0], ktProp, "Navigation element must differ from KtLightMethod");

            var suppressor = new Fxml2KotlinUnusedSymbolSuppressor();

            assertFalse(suppressor.isSuppressedFor(ktProp, "SomeOtherInspection"),
                    "isSuppressedFor must return false when toolId is not 'UnusedSymbol'");
            assertFalse(suppressor.isSuppressedFor(ktProp, "UnusedImport"),
                    "isSuppressedFor must return false when toolId is 'UnusedImport'");
        });
    }

    /**
     * {@link Fxml2KotlinUnusedSymbolSuppressor} must return {@code false} for a
     * {@code KtNamedFunction} element; the suppressor is intentionally scoped to
     * {@code KtProperty} only, because {@code KtNamedFunction} is already handled
     * correctly via K2's {@code isEntryPoint} -> {@code ImplicitUsageProvider} path.
     *
     * <p>Verifies: {@code isSuppressedFor(KtNamedFunction, "unused")} -> {@code false}.
     */
    @Test
    void suppressorReturnsFalseForKtNamedFunction() {
        getFixture().addFileToProject("test/KtFunSupprVm.kt", """
                package test
                import javafx.beans.property.SimpleStringProperty
                import javafx.beans.property.StringProperty
                class KtFunSupprVm {
                    private val backing: StringProperty = SimpleStringProperty("x")
                    fun labelProperty(): StringProperty = backing
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class KtFunSupprView extends BorderPane {
                    @SuppressWarnings("unused")
                    public KtFunSupprVm vm = new KtFunSupprVm();
                    protected void initializeComponent() {}
                    public KtFunSupprView() { initializeComponent(); }
                }
                """);
        getFixture().configureByText("KtFunSupprView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="{fx:Observe vm.label}"/>
                """,
                "test.KtFunSupprView"
        ));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var vmCls = JavaPsiFacade.getInstance(project)
                    .findClass("test.KtFunSupprVm", GlobalSearchScope.projectScope(project));
            assertNotNull(vmCls, "Must find KtFunSupprVm");

            PsiMethod[] methods = vmCls.findMethodsByName("labelProperty", false);
            assertTrue(methods.length > 0, "Must find labelProperty");
            PsiElement ktFun = methods[0].getNavigationElement();
            assertNotSame(methods[0], ktFun, "Navigation element must differ from KtLightMethod");
            assertTrue(ktFun.getClass().getName().contains("KtNamedFunction"),
                    "Navigation element must be KtNamedFunction, got: " + ktFun.getClass().getName());

            var suppressor = new Fxml2KotlinUnusedSymbolSuppressor();

            assertFalse(suppressor.isSuppressedFor(ktFun, "unused"),
                    "isSuppressedFor must return false for a KtNamedFunction: "
                    + "functions are already handled via K2's isEntryPoint/ImplicitUsageProvider "
                    + "path and do not need suppressor intervention");
        });
    }

    /**
     * The suppressor must be invoked by IntelliJ's standard suppression machinery for the
     * exact toolId that K2's {@code KotlinUnusedHighlightingProcessor} passes through
     * {@link com.intellij.codeInspection.SuppressionUtil#inspectionResultSuppressed}.
     *
     * <p>The toolId is determined by
     * {@link com.intellij.codeInspection.LocalInspectionTool#getSuppressId()}, which for the
     * Kotlin {@code UnusedSymbolInspection} returns the {@code suppressId} attribute declared
     * in its {@code <localInspection>} registration.  Both K1 ({@code inspections-fe10.xml})
     * and K2 ({@code kotlin.code-insight.inspections.k2.xml}) declare {@code suppressId="unused"},
     * so the suppressor must match that exact string.  A previous version of the suppressor
     * checked for {@code "UnusedSymbol"} (the inspection's short name) and was therefore
     * never invoked, leaving false-positive "Property is never used" warnings on Kotlin
     * properties referenced from standalone FXML2 binding expressions.
     *
     * <p>This test goes through the public IntelliJ API surface
     * (LangInspectionSuppressor
     * extension point) by invoking
     * {@link com.intellij.codeInspection.InspectionProfileEntry#isSuppressedFor(PsiElement)}
     * on a {@link com.intellij.codeInspection.LocalInspectionTool} whose {@code getSuppressId()}
     * is forced to {@code "unused"} (mirroring the production {@code <localInspection>} entry).
     * This validates the end-to-end suppressor wiring rather than just the suppressor's
     * internal toolId comparison.
     */
    @Test
    void suppressorIsInvokedThroughInspectionPipelineForUnusedToolId() {
        getFixture().addFileToProject("test/KtPipelineVm.kt", """
                package test
                import javafx.beans.property.SimpleStringProperty
                import javafx.beans.property.StringProperty
                class KtPipelineVm {
                    val label: StringProperty = SimpleStringProperty("hi")
                }
                """);
        getFixture().addClass("""
                package test;
                import javafx.scene.layout.BorderPane;
                public class KtPipelineView extends BorderPane {
                    @SuppressWarnings("unused")
                    public KtPipelineVm vm = new KtPipelineVm();
                    protected void initializeComponent() {}
                    public KtPipelineView() { initializeComponent(); }
                }
                """);
        getFixture().configureByText("KtPipelineView.fxml", fxml2(
                "javafx.scene.control.Label",
                """
                  <Label text="${vm.label}"/>
                """,
                "test.KtPipelineView"
        ));
        getFixture().doHighlighting();

        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var vmCls = JavaPsiFacade.getInstance(project)
                    .findClass("test.KtPipelineVm", GlobalSearchScope.projectScope(project));
            assertNotNull(vmCls, "Must find KtPipelineVm");

            PsiMethod[] methods = vmCls.findMethodsByName("getLabel", false);
            assertTrue(methods.length > 0, "Must find getter getLabel");
            PsiElement ktProp = methods[0].getNavigationElement();
            assertTrue(ktProp.getClass().getName().contains("KtProperty"),
                    "Navigation element must be KtProperty, got: " + ktProp.getClass().getName());

            // Drive the same code path that
            // KotlinUnusedHighlightingProcessor.handleDeclaration uses:
            // SuppressionUtil.inspectionResultSuppressed(ktProperty, deadCodeInspection)
            // which delegates to InspectionProfileEntry#isSuppressedFor(PsiElement),
            // which iterates registered InspectionSuppressors with toolId = getSuppressId().
            //
            // We use a stub LocalInspectionTool whose getSuppressId() returns the same
            // string that the production registration sets ("unused").  The full suppressor
            // chain must reach our Fxml2KotlinUnusedSymbolSuppressor and produce true.
            var stubInspection = new com.intellij.codeInspection.LocalInspectionTool() {
                @Override
                public @org.jetbrains.annotations.NotNull String getShortName() {
                    return "UnusedSymbol";
                }
                @Override
                public @org.jetbrains.annotations.NotNull String getDisplayName() {
                    return "Unused symbol (stub)";
                }
                @Override
                public @org.jetbrains.annotations.NotNull String getGroupDisplayName() {
                    return "Stub";
                }
            };

            // Sanity check: an inspection whose nameProvider is unset returns the short name
            // from getSuppressId().  We need the production "unused" id to flow through, so
            // attach a LocalInspectionEP nameProvider with id="unused".
            var ep = new com.intellij.codeInspection.LocalInspectionEP();
            ep.id = "unused";
            try {
                var nameProviderField = com.intellij.codeInspection.InspectionProfileEntry.class
                        .getDeclaredField("myNameProvider");
                nameProviderField.setAccessible(true);
                nameProviderField.set(stubInspection, ep);
            } catch (ReflectiveOperationException e) {
                fail("Could not inject LocalInspectionEP nameProvider into stub: " + e.getMessage());
            }
            assertEquals("unused", stubInspection.getSuppressId(),
                    "Stub inspection must report suppressId=\"unused\" to mirror production");

            assertTrue(
                    com.intellij.codeInspection.SuppressionUtil.inspectionResultSuppressed(
                            ktProp, stubInspection),
                    "SuppressionUtil.inspectionResultSuppressed must return true for a "
                    + "KtProperty referenced in standalone FXML2, when the inspection's "
                    + "suppressId is \"unused\" (matching the production K1/K2 registration).");
        });
    }
}
