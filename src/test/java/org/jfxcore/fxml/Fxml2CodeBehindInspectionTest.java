package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import org.jfxcore.fxml.annotator.Fxml2InitializeComponentInspection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tests for {@link Fxml2InitializeComponentInspection}: the Java-language inspection
 * that warns when a code-behind constructor does not call {@code initializeComponent()}.
 *
 * <p>Doc feature ({@code code-behind.md}): The compiler-generated
 * {@code initializeComponent()} method initializes the scene graph; it <em>must</em> be
 * called in the constructor of the code-behind class.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2CodeBehindInspectionTest extends Fxml2TestBase {

    /** Add the {@code @ComponentView} annotation class once for all tests in this class. */
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
    void enableInspection() {
        getFixture().enableInspections(new Fxml2InitializeComponentInspection());
    }

    // -----------------------------------------------------------------------
    // Helper: build a minimal FXML2 file with a given fx:subclass
    // -----------------------------------------------------------------------

    private static String fxml2WithClass(String fxClass) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="%s"/>
                """.formatted(fxClass);
    }

    // -----------------------------------------------------------------------
    // Happy paths: no warning expected
    // -----------------------------------------------------------------------

    /**
     * A code-behind constructor that calls {@code initializeComponent()} produces no warning.
     */
    @Test
    void constructorWithInitializeComponentProducesNoWarning() {
        getFixture().addFileToProject("test/MyControl.fxml",
                fxml2WithClass("test.MyControl"));
        getFixture().configureByText("MyControl.java",
                """
                package test;
                public class MyControl extends MyControlBase {
                    public MyControl() {
                        initializeComponent();
                    }
                }
                class MyControlBase extends javafx.scene.layout.BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A class that does NOT have a corresponding FXML2 file is not a code-behind class
     * and must never produce a warning, regardless of whether it calls initializeComponent.
     */
    @Test
    void constructorWithoutFxmlFileProducesNoWarning() {
        // No addFileToProject: no FXML2 file paired with this class
        getFixture().configureByText("PlainClass.java",
                """
                package test;
                public class PlainClass extends javafx.scene.layout.BorderPane {
                    public PlainClass() {
                        // Not a code-behind class: no warning
                    }
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A code-behind constructor that calls {@code initializeComponent()} after some
     * other statements produces no warning.
     */
    @Test
    void constructorWithInitializeComponentAfterOtherStatementsProducesNoWarning() {
        getFixture().addFileToProject("test/MyControl2.fxml",
                fxml2WithClass("test.MyControl2"));
        getFixture().configureByText("MyControl2.java",
                """
                package test;
                public class MyControl2 extends MyControl2Base {
                    private final String name;
                    public MyControl2(String name) {
                        this.name = name;
                        initializeComponent();
                    }
                }
                class MyControl2Base extends javafx.scene.layout.BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Warning cases
    // -----------------------------------------------------------------------

    /**
     * A code-behind constructor that does NOT call {@code initializeComponent()} produces
     * a warning on the constructor name.
     */
    @Test
    void constructorWithoutInitializeComponentProducesWarning() {
        getFixture().addFileToProject("test/BadControl.fxml",
                fxml2WithClass("test.BadControl"));
        getFixture().configureByText("BadControl.java",
                """
                package test;
                public class BadControl extends BadControlBase {
                    public <warning descr="Constructor does not call initializeComponent(). Add a call to initializeComponent() in this constructor, or suppress with @SuppressWarnings(&quot;Fxml2InitializeComponent&quot;).">BadControl</warning>() {
                        // initializeComponent() not called
                    }
                }
                class BadControlBase extends javafx.scene.layout.BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * All constructors in a code-behind class that do not call {@code initializeComponent()}
     * are warned, including overloaded constructors.
     */
    @Test
    void multipleConstructorsWithoutInitializeComponentAllProduceWarning() {
        getFixture().addFileToProject("test/MultiCtorControl.fxml",
                fxml2WithClass("test.MultiCtorControl"));
        getFixture().configureByText("MultiCtorControl.java",
                """
                package test;
                public class MultiCtorControl extends MultiCtorControlBase {
                    public <warning descr="Constructor does not call initializeComponent(). Add a call to initializeComponent() in this constructor, or suppress with @SuppressWarnings(&quot;Fxml2InitializeComponent&quot;).">MultiCtorControl</warning>() {
                    }
                    public <warning descr="Constructor does not call initializeComponent(). Add a call to initializeComponent() in this constructor, or suppress with @SuppressWarnings(&quot;Fxml2InitializeComponent&quot;).">MultiCtorControl</warning>(String name) {
                    }
                }
                class MultiCtorControlBase extends javafx.scene.layout.BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * When one constructor calls {@code initializeComponent()} and another does not, only
     * the one without the call is warned.
     */
    @Test
    void onlyConstructorMissingCallIsWarned() {
        getFixture().addFileToProject("test/PartialControl.fxml",
                fxml2WithClass("test.PartialControl"));
        getFixture().configureByText("PartialControl.java",
                """
                package test;
                public class PartialControl extends PartialControlBase {
                    public PartialControl() {
                        initializeComponent();
                    }
                    public <warning descr="Constructor does not call initializeComponent(). Add a call to initializeComponent() in this constructor, or suppress with @SuppressWarnings(&quot;Fxml2InitializeComponent&quot;).">PartialControl</warning>(String extra) {
                        // forgot initializeComponent()
                    }
                }
                class PartialControlBase extends javafx.scene.layout.BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Suppression
    // -----------------------------------------------------------------------

    /**
     * A constructor annotated with {@code @SuppressWarnings("Fxml2InitializeComponent")}
     * produces no warning.
     */
    @Test
    void suppressWarningsOnConstructorSuppressesWarning() {
        getFixture().addFileToProject("test/SuppressedControl.fxml",
                fxml2WithClass("test.SuppressedControl"));
        getFixture().configureByText("SuppressedControl.java",
                """
                package test;
                public class SuppressedControl extends SuppressedControlBase {
                    @SuppressWarnings("Fxml2InitializeComponent")
                    public SuppressedControl() {
                        // intentionally not calling initializeComponent()
                    }
                }
                class SuppressedControlBase extends javafx.scene.layout.BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A class annotated with {@code @SuppressWarnings("Fxml2InitializeComponent")} suppresses
     * the warning on all constructors in that class.
     */
    @Test
    void suppressWarningsOnClassSuppressesAllConstructorWarnings() {
        getFixture().addFileToProject("test/SuppressedControl2.fxml",
                fxml2WithClass("test.SuppressedControl2"));
        getFixture().configureByText("SuppressedControl2.java",
                """
                package test;
                @SuppressWarnings("Fxml2InitializeComponent")
                public class SuppressedControl2 extends SuppressedControl2Base {
                    public SuppressedControl2() {
                    }
                    public SuppressedControl2(String name) {
                    }
                }
                class SuppressedControl2Base extends javafx.scene.layout.BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // @ComponentView-annotated class: inspection behavior
    // -----------------------------------------------------------------------

    /**
     * A {@code @ComponentView}-annotated class with two constructors that both omit
     * {@code initializeComponent()} must produce a warning on <em>each</em> constructor.
     */
    @Test
    void markupAnnotatedClassMultipleConstructorsAllProduceWarning() {
        String warning = "Constructor does not call initializeComponent(). "
                + "Add a call to initializeComponent() in this constructor, or suppress with "
                + "@SuppressWarnings(&quot;Fxml2InitializeComponent&quot;).";
        getFixture().configureByText("MarkupMultiCtor.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane/>
                    \""")
                public class MarkupMultiCtor {
                    public <warning descr="%s">MarkupMultiCtor</warning>() {}
                    public <warning descr="%s">MarkupMultiCtor</warning>(String name) {}
                }
                """.formatted(warning, warning));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A {@code @ComponentView}-annotated class annotated with
     * {@code @SuppressWarnings("Fxml2InitializeComponent")} at the class level must
     * produce no warnings on any constructor.
     */
    @Test
    void markupAnnotatedClassSuppressWarningsOnClassSuppressesAllWarnings() {
        getFixture().configureByText("MarkupSuppressed.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @SuppressWarnings("Fxml2InitializeComponent")
                @ComponentView(\"""
                    <StackPane/>
                    \""")
                public class MarkupSuppressed {
                    public MarkupSuppressed() {}
                    public MarkupSuppressed(String name) {}
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * When only one constructor of a {@code @ComponentView}-annotated class calls
     * {@code initializeComponent()}, only the one that omits the call is warned.
     */
    @Test
    void markupAnnotatedClassOnlyConstructorMissingCallIsWarned() {
        String warning = "Constructor does not call initializeComponent(). "
                + "Add a call to initializeComponent() in this constructor, or suppress with "
                + "@SuppressWarnings(&quot;Fxml2InitializeComponent&quot;).";
        getFixture().configureByText("MarkupPartial.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane/>
                    \""")
                public class MarkupPartial {
                    protected void initializeComponent() {}
                    public MarkupPartial() { initializeComponent(); }
                    public <warning descr="%s">MarkupPartial</warning>(String name) {}
                }
                """.formatted(warning));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * {@link Fxml2InitializeComponentInspection#isCodeBehindClass} must return
     * {@code true} for any class annotated with {@code @ComponentView}, even without a
     * corresponding standalone {@code .fxml} file on disk.
     */
    @Test
    void isCodeBehindClassReturnsTrueForMarkupAnnotatedClass() {
        getFixture().addClass("""
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.StackPane;
                @ComponentView(\"""
                    <StackPane/>
                    \""")
                public class MarkupDetect extends StackPane {}
                """);
        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var facade = com.intellij.psi.JavaPsiFacade.getInstance(project);
            var scope = com.intellij.psi.search.GlobalSearchScope.allScope(project);
            var cls = facade.findClass("test.MarkupDetect", scope);
            org.junit.jupiter.api.Assertions.assertNotNull(cls,
                    "test.MarkupDetect must be resolvable");
            org.junit.jupiter.api.Assertions.assertTrue(
                    Fxml2InitializeComponentInspection.isCodeBehindClass(cls, project),
                    "@ComponentView-annotated class must be recognized as a code-behind class");
        });
    }

    // -----------------------------------------------------------------------
    // isCodeBehindClass unit check (standalone FXML2)
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code isCodeBehindClass} returns {@code true} for a class whose
     * FXML2 file has the matching {@code fx:subclass} attribute, and {@code false} for
     * a class without one.
     */
    @Test
    void isCodeBehindClassDetectsCorrectly() {
        getFixture().addFileToProject("test/DetectControl.fxml",
                fxml2WithClass("test.DetectControl"));
        getFixture().addClass("""
                package test;
                public class DetectControl extends javafx.scene.layout.BorderPane {}
                """);
        getFixture().addClass("""
                package test;
                public class NonCodeBehindClass extends javafx.scene.layout.BorderPane {}
                """);
        ReadAction.run(() -> {
            var project = getFixture().getProject();
            var facade = com.intellij.psi.JavaPsiFacade.getInstance(project);
            var scope = com.intellij.psi.search.GlobalSearchScope.allScope(project);

            var codeBehind = facade.findClass("test.DetectControl", scope);
            org.junit.jupiter.api.Assertions.assertNotNull(codeBehind,
                    "Expected test.DetectControl to be resolvable");
            org.junit.jupiter.api.Assertions.assertTrue(
                    Fxml2InitializeComponentInspection.isCodeBehindClass(codeBehind, project),
                    "Expected test.DetectControl to be detected as a code-behind class");

            var plain = facade.findClass("test.NonCodeBehindClass", scope);
            org.junit.jupiter.api.Assertions.assertNotNull(plain,
                    "Expected test.NonCodeBehindClass to be resolvable");
            org.junit.jupiter.api.Assertions.assertFalse(
                    Fxml2InitializeComponentInspection.isCodeBehindClass(plain, project),
                    "Expected test.NonCodeBehindClass NOT to be a code-behind class");
        });
    }
}
