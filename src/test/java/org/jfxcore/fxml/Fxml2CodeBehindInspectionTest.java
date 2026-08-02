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

    /** Expected warning text on a constructor, XML-escaped for {@code checkHighlighting}. */
    private static final String CTOR_WARNING =
            "Constructor does not call initializeComponent(). "
            + "Add a call to initializeComponent() in this constructor, or suppress with "
            + "@SuppressWarnings(&quot;Fxml2InitializeComponent&quot;).";

    /** Expected warning text on a class that declares no constructor. */
    private static final String CLASS_WARNING =
            "Class does not call initializeComponent(). "
            + "Add a constructor that calls initializeComponent(), or suppress with "
            + "@SuppressWarnings(&quot;Fxml2InitializeComponent&quot;).";

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
    // Helper: build a minimal FXML file with a given fx:subclass
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
     * A class that does NOT have a corresponding FXML file is not a code-behind class
     * and must never produce a warning, regardless of whether it calls initializeComponent.
     */
    @Test
    void constructorWithoutFxmlFileProducesNoWarning() {
        // No addFileToProject: no FXML file paired with this class
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
                    public <warning descr="%s">BadControl</warning>() {
                        // initializeComponent() not called
                    }
                }
                class BadControlBase extends javafx.scene.layout.BorderPane {
                    protected void initializeComponent() {}
                }
                """.formatted(CTOR_WARNING));
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
                    public <warning descr="%s">MultiCtorControl</warning>() {
                    }
                    public <warning descr="%s">MultiCtorControl</warning>(String name) {
                    }
                }
                class MultiCtorControlBase extends javafx.scene.layout.BorderPane {
                    protected void initializeComponent() {}
                }
                """.formatted(CTOR_WARNING, CTOR_WARNING));
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
                    public <warning descr="%s">PartialControl</warning>(String extra) {
                        // forgot initializeComponent()
                    }
                }
                class PartialControlBase extends javafx.scene.layout.BorderPane {
                    protected void initializeComponent() {}
                }
                """.formatted(CTOR_WARNING));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A code-behind class that declares no constructor at all is warned on the class name,
     * because its implicit constructor cannot call {@code initializeComponent()}.
     */
    @Test
    void classWithoutAnyConstructorProducesWarningOnClassName() {
        getFixture().addFileToProject("test/NoCtorControl.fxml",
                fxml2WithClass("test.NoCtorControl"));
        getFixture().configureByText("NoCtorControl.java",
                """
                package test;
                public class <warning descr="%s">NoCtorControl</warning> extends NoCtorControlBase {
                }
                class NoCtorControlBase extends javafx.scene.layout.BorderPane {
                    protected void initializeComponent() {}
                }
                """.formatted(CLASS_WARNING));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Constructor delegation chains
    // -----------------------------------------------------------------------

    /**
     * A constructor that delegates via {@code this(...)} to a constructor calling
     * {@code initializeComponent()} is not warned.
     */
    @Test
    void constructorDelegatingToInitializingConstructorProducesNoWarning() {
        getFixture().addFileToProject("test/ChainControl.fxml",
                fxml2WithClass("test.ChainControl"));
        getFixture().configureByText("ChainControl.java",
                """
                package test;
                public class ChainControl extends ChainControlBase {
                    public ChainControl() {
                        this("default");
                    }
                    public ChainControl(String name) {
                        initializeComponent();
                    }
                }
                class ChainControlBase extends javafx.scene.layout.BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Delegation chains of arbitrary length are followed: the call may be several
     * {@code this(...)} hops away.
     */
    @Test
    void longDelegationChainProducesNoWarning() {
        getFixture().addFileToProject("test/LongChainControl.fxml",
                fxml2WithClass("test.LongChainControl"));
        getFixture().configureByText("LongChainControl.java",
                """
                package test;
                public class LongChainControl extends LongChainControlBase {
                    public LongChainControl() {
                        this(1);
                    }
                    public LongChainControl(int count) {
                        this(count, "default");
                    }
                    public LongChainControl(int count, String name) {
                        initializeComponent();
                    }
                }
                class LongChainControlBase extends javafx.scene.layout.BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A delegation chain that never reaches a call to {@code initializeComponent()} is
     * warned on every constructor in the chain.
     */
    @Test
    void delegationChainWithoutCallProducesWarningOnEachConstructor() {
        getFixture().addFileToProject("test/BrokenChainControl.fxml",
                fxml2WithClass("test.BrokenChainControl"));
        getFixture().configureByText("BrokenChainControl.java",
                """
                package test;
                public class BrokenChainControl extends BrokenChainControlBase {
                    public <warning descr="%s">BrokenChainControl</warning>() {
                        this("default");
                    }
                    public <warning descr="%s">BrokenChainControl</warning>(String name) {
                    }
                }
                class BrokenChainControlBase extends javafx.scene.layout.BorderPane {
                    protected void initializeComponent() {}
                }
                """.formatted(CTOR_WARNING, CTOR_WARNING));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A superclass constructor reached through an explicit {@code super(...)} call satisfies
     * the requirement for the delegating subclass constructor.
     */
    @Test
    void explicitSuperConstructorCallingInitializeComponentProducesNoWarning() {
        getFixture().addFileToProject("test/SuperCallControl.fxml",
                fxml2WithClass("test.SuperCallControl"));
        getFixture().configureByText("SuperCallControl.java",
                """
                package test;
                public class SuperCallControl extends SuperCallControlBase {
                    public SuperCallControl() {
                        super("default");
                    }
                }
                class SuperCallControlBase extends javafx.scene.layout.BorderPane {
                    SuperCallControlBase(String name) {
                        initializeComponent();
                    }
                    protected void initializeComponent() {}
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A superclass no-argument constructor reached through the implicit {@code super()} call
     * satisfies the requirement, both for a declared constructor and for a class that
     * declares no constructor at all.
     */
    @Test
    void implicitSuperConstructorCallingInitializeComponentProducesNoWarning() {
        getFixture().addFileToProject("test/ImplicitSuperControl.fxml",
                fxml2WithClass("test.ImplicitSuperControl"));
        getFixture().addFileToProject("test/ImplicitSuperNoCtorControl.fxml",
                fxml2WithClass("test.ImplicitSuperNoCtorControl"));
        getFixture().configureByText("ImplicitSuperControl.java",
                """
                package test;
                public class ImplicitSuperControl extends ImplicitSuperControlBase {
                    public ImplicitSuperControl() {
                    }
                }
                class ImplicitSuperNoCtorControl extends ImplicitSuperControlBase {
                }
                class ImplicitSuperControlBase extends javafx.scene.layout.BorderPane {
                    ImplicitSuperControlBase() {
                        initializeComponent();
                    }
                    protected void initializeComponent() {}
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Quick-fixes
    // -----------------------------------------------------------------------

    /**
     * The quick-fix on a constructor inserts {@code initializeComponent()} as the first
     * statement of the constructor body.
     */
    @Test
    void quickFixAddsCallAsFirstStatementOfConstructor() {
        getFixture().addFileToProject("test/FixCtorControl.fxml",
                fxml2WithClass("test.FixCtorControl"));
        getFixture().configureByText("FixCtorControl.java",
                """
                package test;
                public class FixCtorControl extends FixCtorControlBase {
                    private final String name;
                    public FixCtorControl(String name) {
                        this.name = name;
                    }
                }
                class FixCtorControlBase extends javafx.scene.layout.BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        applyFix("Add initializeComponent() call");
        getFixture().checkResult("""
                package test;
                public class FixCtorControl extends FixCtorControlBase {
                    private final String name;
                    public FixCtorControl(String name) {
                        initializeComponent();
                        this.name = name;
                    }
                }
                class FixCtorControlBase extends javafx.scene.layout.BorderPane {
                    protected void initializeComponent() {}
                }
                """);
    }

    /**
     * The quick-fix keeps a leading {@code super(...)} delegation in place and inserts the
     * call directly after it.
     */
    @Test
    void quickFixInsertsCallAfterSuperCall() {
        getFixture().addFileToProject("test/FixSuperControl.fxml",
                fxml2WithClass("test.FixSuperControl"));
        getFixture().configureByText("FixSuperControl.java",
                """
                package test;
                public class FixSuperControl extends FixSuperControlBase {
                    public FixSuperControl() {
                        super("default");
                    }
                }
                class FixSuperControlBase extends javafx.scene.layout.BorderPane {
                    FixSuperControlBase(String name) {}
                    protected void initializeComponent() {}
                }
                """);
        applyFix("Add initializeComponent() call");
        getFixture().checkResult("""
                package test;
                public class FixSuperControl extends FixSuperControlBase {
                    public FixSuperControl() {
                        super("default");
                        initializeComponent();
                    }
                }
                class FixSuperControlBase extends javafx.scene.layout.BorderPane {
                    FixSuperControlBase(String name) {}
                    protected void initializeComponent() {}
                }
                """);
    }

    /**
     * The quick-fix on a class without any constructor adds a no-argument constructor that
     * calls {@code initializeComponent()}.
     */
    @Test
    void quickFixAddsConstructorToClassWithoutConstructor() {
        getFixture().addFileToProject("test/FixClassControl.fxml",
                fxml2WithClass("test.FixClassControl"));
        getFixture().configureByText("FixClassControl.java",
                """
                package test;
                public class FixClassControl extends FixClassControlBase {
                    private String name;
                }
                class FixClassControlBase extends javafx.scene.layout.BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        applyFix("Add constructor calling initializeComponent()");
        getFixture().checkResult("""
                package test;
                public class FixClassControl extends FixClassControlBase {
                    private String name;

                    public FixClassControl() {
                        initializeComponent();
                    }
                }
                class FixClassControlBase extends javafx.scene.layout.BorderPane {
                    protected void initializeComponent() {}
                }
                """);
    }

    /** Applies the single quick-fix in the configured file whose name matches. */
    private void applyFix(String familyName) {
        var fixes = getFixture().getAllQuickFixes().stream()
                .filter(fix -> familyName.equals(fix.getText()))
                .toList();
        org.junit.jupiter.api.Assertions.assertEquals(1, fixes.size(),
                "Expected exactly one \"" + familyName + "\" quick-fix");
        getFixture().launchAction(fixes.getFirst());
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
                """.formatted(CTOR_WARNING, CTOR_WARNING));
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
                """.formatted(CTOR_WARNING));
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
    // isCodeBehindClass unit check (standalone FXML)
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code isCodeBehindClass} returns {@code true} for a class whose
     * FXML file has the matching {@code fx:subclass} attribute, and {@code false} for
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
