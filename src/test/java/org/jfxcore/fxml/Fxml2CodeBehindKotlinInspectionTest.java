package org.jfxcore.fxml;

import org.jfxcore.fxml.annotator.Fxml2InitializeComponentInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tests for the Kotlin side of {@link Fxml2InitializeComponentInspection}: warns when a
 * Kotlin code-behind class does not call {@code initializeComponent()}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2CodeBehindKotlinInspectionTest extends Fxml2TestBase {

    /** Expected warning text on a class, XML-escaped for {@code checkHighlighting}. */
    private static final String CLASS_WARNING =
            "Class does not call initializeComponent(). "
            + "Add a call to initializeComponent() in an init block or constructor, "
            + "or suppress with @Suppress(&quot;Fxml2InitializeComponent&quot;).";

    /** Expected warning text on a secondary constructor. */
    private static final String CTOR_WARNING =
            "Constructor does not call initializeComponent(). "
            + "Add a call to initializeComponent() in this constructor, or suppress with "
            + "@Suppress(&quot;Fxml2InitializeComponent&quot;).";

    @BeforeEach
    void enableInspection() {
        getFixture().enableInspections(new Fxml2InitializeComponentInspection());
    }

    // -----------------------------------------------------------------------
    // Helper
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
     * A code-behind class whose {@code init} block calls {@code initializeComponent()}
     * produces no warning.
     */
    @Test
    void initBlockWithInitializeComponentProducesNoWarning() {
        getFixture().addFileToProject("test/MyKtControl.fxml",
                fxml2WithClass("test.MyKtControl"));
        getFixture().configureByText("MyKtControl.kt",
                """
                package test
                class MyKtControl : MyKtControlBase() {
                    init {
                        initializeComponent()
                    }
                }
                open class MyKtControlBase : javafx.scene.layout.BorderPane() {
                    protected fun initializeComponent() {}
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A code-behind class with a secondary constructor body that calls
     * {@code initializeComponent()} produces no warning.
     */
    @Test
    void primaryConstructorBodyWithInitializeComponentProducesNoWarning() {
        getFixture().addFileToProject("test/MyKtControl2.fxml",
                fxml2WithClass("test.MyKtControl2"));
        // Secondary constructor calling initializeComponent(): valid Kotlin pattern
        getFixture().configureByText("MyKtControl2.kt",
                """
                package test
                class MyKtControl2 : MyKtControl2Base {
                    constructor() : super() {
                        initializeComponent()
                    }
                }
                open class MyKtControl2Base : javafx.scene.layout.BorderPane() {
                    protected fun initializeComponent() {}
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A Kotlin class without a paired FXML file is not a code-behind class
     * and must never produce a warning.
     */
    @Test
    void kotlinClassWithoutFxmlFileProducesNoWarning() {
        getFixture().configureByText("PlainKtClass.kt",
                """
                package test
                class PlainKtClass : javafx.scene.layout.BorderPane() {
                    init {
                        // Not a code-behind class: no warning
                    }
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A secondary constructor that calls {@code initializeComponent()} produces no warning.
     */
    @Test
    void secondaryConstructorWithInitializeComponentProducesNoWarning() {
        getFixture().addFileToProject("test/MyKtControl3.fxml",
                fxml2WithClass("test.MyKtControl3"));
        getFixture().configureByText("MyKtControl3.kt",
                """
                package test
                class MyKtControl3 : MyKtControl3Base {
                    constructor() : super() {
                        initializeComponent()
                    }
                }
                open class MyKtControl3Base : javafx.scene.layout.BorderPane() {
                    protected fun initializeComponent() {}
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Warning cases
    // -----------------------------------------------------------------------

    /**
     * A code-behind class with an {@code init} block that does NOT call
     * {@code initializeComponent()} produces a warning on the class name.
     */
    @Test
    void initBlockWithoutInitializeComponentProducesWarning() {
        getFixture().addFileToProject("test/BadKtControl.fxml",
                fxml2WithClass("test.BadKtControl"));
        getFixture().configureByText("BadKtControl.kt",
                """
                package test
                class <warning descr="%s">BadKtControl</warning> : BadKtControlBase() {
                    init {
                        // initializeComponent() not called
                    }
                }
                open class BadKtControlBase : javafx.scene.layout.BorderPane() {
                    protected fun initializeComponent() {}
                }
                """.formatted(CLASS_WARNING));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A code-behind class with no init block and no constructor body at all
     * produces a warning on the class name.
     */
    @Test
    void classWithNoInitBlockProducesWarning() {
        getFixture().addFileToProject("test/NoInitKtControl.fxml",
                fxml2WithClass("test.NoInitKtControl"));
        getFixture().configureByText("NoInitKtControl.kt",
                """
                package test
                class <warning descr="%s">NoInitKtControl</warning> : NoInitKtControlBase()
                open class NoInitKtControlBase : javafx.scene.layout.BorderPane() {
                    protected fun initializeComponent() {}
                }
                """.formatted(CLASS_WARNING));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A secondary constructor that delegates via {@code this(...)} to a constructor calling
     * {@code initializeComponent()} produces no warning.
     */
    @Test
    void secondaryConstructorDelegatingToInitializingConstructorProducesNoWarning() {
        getFixture().addFileToProject("test/ChainKtControl.fxml",
                fxml2WithClass("test.ChainKtControl"));
        getFixture().configureByText("ChainKtControl.kt",
                """
                package test
                class ChainKtControl : ChainKtControlBase {
                    constructor() : this("default")
                    constructor(name: String) : super() {
                        initializeComponent()
                    }
                }
                open class ChainKtControlBase : javafx.scene.layout.BorderPane {
                    constructor() : super()
                    protected fun initializeComponent() {}
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A secondary constructor whose delegation chain never reaches
     * {@code initializeComponent()} is warned on the {@code constructor} keyword.
     */
    @Test
    void secondaryConstructorWithoutCallProducesWarning() {
        getFixture().addFileToProject("test/BadChainKtControl.fxml",
                fxml2WithClass("test.BadChainKtControl"));
        getFixture().configureByText("BadChainKtControl.kt",
                """
                package test
                class BadChainKtControl : BadChainKtControlBase {
                    <warning descr="%s">constructor</warning>() : super()
                }
                open class BadChainKtControlBase : javafx.scene.layout.BorderPane {
                    constructor() : super()
                    protected fun initializeComponent() {}
                }
                """.formatted(CTOR_WARNING));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * With a primary constructor present, every construction path runs the init blocks, so a
     * call that appears only in a secondary constructor still leaves the primary path
     * uninitialized and the class is warned.
     */
    @Test
    void primaryConstructorPathWithoutCallProducesClassWarning() {
        getFixture().addFileToProject("test/PrimaryKtControl.fxml",
                fxml2WithClass("test.PrimaryKtControl"));
        getFixture().configureByText("PrimaryKtControl.kt",
                """
                package test
                class <warning descr="%s">PrimaryKtControl</warning>() : PrimaryKtControlBase() {
                    constructor(name: String) : this() {
                        initializeComponent()
                    }
                }
                open class PrimaryKtControlBase : javafx.scene.layout.BorderPane() {
                    protected fun initializeComponent() {}
                }
                """.formatted(CLASS_WARNING));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Quick-fixes
    // -----------------------------------------------------------------------

    /**
     * The class-level quick-fix adds an {@code init} block calling
     * {@code initializeComponent()} as the first declaration of the class body.
     */
    @Test
    void quickFixAddsInitBlock() {
        getFixture().addFileToProject("test/FixKtControl.fxml",
                fxml2WithClass("test.FixKtControl"));
        getFixture().configureByText("FixKtControl.kt",
                """
                package test
                class FixKtControl : FixKtControlBase() {
                    private val name: String = "x"
                }
                open class FixKtControlBase : javafx.scene.layout.BorderPane() {
                    protected fun initializeComponent() {}
                }
                """);
        applyFix("Add init block calling initializeComponent()");
        getFixture().checkResult("""
                package test
                class FixKtControl : FixKtControlBase() {
                    init {
                        initializeComponent()
                    }

                    private val name: String = "x"
                }
                open class FixKtControlBase : javafx.scene.layout.BorderPane() {
                    protected fun initializeComponent() {}
                }
                """);
    }

    /**
     * The constructor-level quick-fix inserts {@code initializeComponent()} as the first
     * statement of a secondary constructor body.
     */
    @Test
    void quickFixAddsCallToSecondaryConstructor() {
        getFixture().addFileToProject("test/FixKtCtorControl.fxml",
                fxml2WithClass("test.FixKtCtorControl"));
        getFixture().configureByText("FixKtCtorControl.kt",
                """
                package test
                class FixKtCtorControl : FixKtCtorControlBase {
                    constructor() : super() {
                        println("hello")
                    }
                }
                open class FixKtCtorControlBase : javafx.scene.layout.BorderPane {
                    constructor() : super()
                    protected fun initializeComponent() {}
                }
                """);
        applyFix("Add initializeComponent() call");
        getFixture().checkResult("""
                package test
                class FixKtCtorControl : FixKtCtorControlBase {
                    constructor() : super() {
                        initializeComponent()
                        println("hello")
                    }
                }
                open class FixKtCtorControlBase : javafx.scene.layout.BorderPane {
                    constructor() : super()
                    protected fun initializeComponent() {}
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
     * A class annotated with {@code @Suppress("Fxml2InitializeComponent")} produces
     * no warning even if {@code initializeComponent()} is not called.
     */
    @Test
    void suppressAnnotationOnClassSuppressesWarning() {
        getFixture().addFileToProject("test/SuppressedKtControl.fxml",
                fxml2WithClass("test.SuppressedKtControl"));
        getFixture().configureByText("SuppressedKtControl.kt",
                """
                package test
                @Suppress("%s")
                class SuppressedKtControl : SuppressedKtControlBase()
                open class SuppressedKtControlBase : javafx.scene.layout.BorderPane() {
                    protected fun initializeComponent() {}
                }
                """.formatted(Fxml2InitializeComponentInspection.SHORT_NAME));
        getFixture().checkHighlighting(false, false, false);
    }
}
