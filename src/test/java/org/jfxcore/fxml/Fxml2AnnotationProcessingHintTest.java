package org.jfxcore.fxml;

import com.intellij.lang.annotation.HighlightSeverity;
import org.jfxcore.fxml.annotator.Fxml2AnnotationProcessingHintInspection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Fxml2AnnotationProcessingHintInspection}: the inspection that adds
 * an actionable hint when the FXML compiler's annotation processor has not yet generated
 * the {@code {ClassName}Base} base class.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2AnnotationProcessingHintTest extends Fxml2TestBase {

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
        getFixture().enableInspections(new Fxml2AnnotationProcessingHintInspection());
    }

    // -----------------------------------------------------------------------
    // Helpers

    /** Returns all WARNING-severity highlights produced by our inspection. */
    private List<com.intellij.codeInsight.daemon.impl.HighlightInfo> getHints() {
        return getFixture().doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.WARNING
                          && h.getDescription() != null
                          && h.getDescription().contains("annotation processing"))
                .toList();
    }

    // -----------------------------------------------------------------------
    // Hint on unresolved base class

    /**
     * When a {@code @ComponentView}-annotated class extends {@code {ClassName}Base} and
     * that class cannot be resolved, the hint must appear on the unresolved reference.
     */
    @Test
    void hintAppearsOnUnresolvedBaseClass() {
        getFixture().configureByText("SampleView.java", """
                package sample;
                import org.jfxcore.markup.ComponentView;
                @ComponentView(\"""
                    <StackPane/>
                    \""")
                public class SampleView extends SampleViewBase {
                }
                """);
        var hints = getHints();
        assertEquals(1, hints.size(),
                "Expected exactly one annotation-processing hint (on SampleViewBase)");
        assertTrue(hints.getFirst().getDescription().contains("SampleViewBase"),
                "Hint should mention 'SampleViewBase'");
    }

    /**
     * A class without {@code @ComponentView} that extends an unresolved {@code XxxBase}
     * must NOT produce a hint: the class is not an fxml2 component.
     */
    @Test
    void noHintForUnresolvedBaseClassWithoutAnnotation() {
        getFixture().configureByText("PlainView.java", """
                package sample;
                public class PlainView extends PlainViewBase {
                }
                """);
        assertTrue(getHints().isEmpty(),
                "No hint expected for class without @ComponentView");
    }

    /**
     * When the base class IS resolved (defined in the same compilation unit), no hint
     * must appear.
     */
    @Test
    void noHintWhenBaseClassIsResolved() {
        getFixture().configureByText("ResolvedView.java", """
                package sample;
                import org.jfxcore.markup.ComponentView;
                @ComponentView(\"""
                    <StackPane/>
                    \""")
                public class ResolvedView extends ResolvedViewBase {
                }
                class ResolvedViewBase extends javafx.scene.layout.StackPane {
                    protected void initializeComponent() {}
                }
                """);
        assertTrue(getHints().isEmpty(),
                "No hint expected when base class is resolved");
    }

    // -----------------------------------------------------------------------
    // initializeComponent(): no hint regardless of resolution

    /**
     * Even when {@code initializeComponent()} cannot be resolved (because the base class
     * was not yet generated), no hint must appear on the call: only the base-class hint
     * is shown.
     */
    @Test
    void noHintOnUnresolvedInitializeComponentCall() {
        getFixture().configureByText("MainView2.java", """
                package sample;
                import org.jfxcore.markup.ComponentView;
                @ComponentView(\"""
                    <StackPane/>
                    \""")
                public class MainView2 extends MainView2Base {
                    public MainView2() {
                        initializeComponent();
                    }
                }
                """);
        var hints = getHints();
        assertTrue(hints.stream().noneMatch(h -> h.getDescription().contains("initializeComponent")),
                "No hint expected on initializeComponent()");
    }

    /**
     * When {@code initializeComponent()} IS resolved (base class generated), no hint
     * must appear anywhere.
     */
    @Test
    void noHintWhenInitializeComponentIsResolved() {
        getFixture().configureByText("ResolvedView2.java", """
                package sample;
                import org.jfxcore.markup.ComponentView;
                @ComponentView(\"""
                    <StackPane/>
                    \""")
                public class ResolvedView2 extends ResolvedView2Base {
                    public ResolvedView2() {
                        initializeComponent();
                    }
                }
                class ResolvedView2Base extends javafx.scene.layout.StackPane {
                    protected void initializeComponent() {}
                }
                """);
        assertTrue(getHints().isEmpty(),
                "No hint expected when initializeComponent() is resolved");
    }

    // -----------------------------------------------------------------------
    // Suppression

    /**
     * {@code @SuppressWarnings("Fxml2AnnotationProcessingHint")} on the class must
     * suppress the base-class hint.
     */
    @Test
    void suppressWarningsOnClassSuppressesHint() {
        getFixture().configureByText("SuppressedView.java", """
                package sample;
                import org.jfxcore.markup.ComponentView;
                @SuppressWarnings("Fxml2AnnotationProcessingHint")
                @ComponentView(\"""
                    <StackPane/>
                    \""")
                public class SuppressedView extends SuppressedViewBase {
                    public SuppressedView() {
                        initializeComponent();
                    }
                }
                """);
        assertTrue(getHints().isEmpty(),
                "Hint should be suppressed by @SuppressWarnings on the class");
    }

    // -----------------------------------------------------------------------
    // No annotation: no hints

    /**
     * A class without {@code @ComponentView} must never produce any annotation-processing
     * hint, even if it extends an unresolved class or calls {@code initializeComponent()}.
     */
    @Test
    void noHintWithoutComponentViewAnnotation() {
        getFixture().configureByText("NoAnnotation.java", """
                package sample;
                public class NoAnnotation extends NoAnnotationBase {
                    public NoAnnotation() {
                        initializeComponent();
                    }
                }
                """);
        assertTrue(getHints().isEmpty(),
                "No hint expected for class without @ComponentView");
    }

    /**
     * An unresolved {@code initializeComponent()} call in a class that has no
     * {@code @ComponentView} annotation must not produce a hint.
     */
    @Test
    void noHintForUnresolvedInitCallWithoutAnnotation() {
        getFixture().configureByText("NoAnnotationInit.java", """
                package sample;
                public class NoAnnotationInit {
                    public NoAnnotationInit() {
                        initializeComponent();
                    }
                }
                """);
        assertTrue(getHints().isEmpty(),
                "No hint on initializeComponent() without @ComponentView");
    }

    // -----------------------------------------------------------------------
    // Only the base-class location produces a hint

    /**
     * When both the base class is unresolved AND {@code initializeComponent()} is called,
     * only the base-class hint must appear: not a second hint on the method call.
     */
    @Test
    void onlyBaseClassHintWhenBothUnresolved() {
        getFixture().configureByText("BothView.java", """
                package sample;
                import org.jfxcore.markup.ComponentView;
                @ComponentView(\"""
                    <StackPane/>
                    \""")
                public class BothView extends BothViewBase {
                    public BothView() {
                        initializeComponent();
                    }
                }
                """);
        var hints = getHints();
        assertEquals(1, hints.size(),
                "Expected exactly one hint (on BothViewBase only)");
        assertTrue(hints.getFirst().getDescription().contains("BothViewBase"),
                "The single hint should mention 'BothViewBase'");
    }

    // -----------------------------------------------------------------------
    // Kotlin: hint on unresolved base class

    /**
     * When a Kotlin {@code @ComponentView}-annotated class extends an unresolved
     * {@code {ClassName}Base} supertype, the hint must appear on the unresolved reference.
     */
    @Test
    void hintAppearsOnUnresolvedBaseClassInKotlin() {
        getFixture().configureByText("KtSampleView.kt", """
                package sample
                import org.jfxcore.markup.ComponentView
                @ComponentView(\"""
                    <StackPane/>
                    \""")
                open class KtSampleView : KtSampleViewBase()
                """);
        var hints = getHints();
        assertEquals(1, hints.size(),
                "Expected exactly one annotation-processing hint (on KtSampleViewBase) in Kotlin");
        assertTrue(hints.getFirst().getDescription().contains("KtSampleViewBase"),
                "Hint should mention 'KtSampleViewBase'");
    }

    /**
     * A Kotlin class without {@code @ComponentView} that extends an unresolved
     * {@code XxxBase} supertype must NOT produce a hint.
     */
    @Test
    void noHintForUnresolvedBaseClassWithoutAnnotationInKotlin() {
        getFixture().configureByText("KtPlainView.kt", """
                package sample
                open class KtPlainView : KtPlainViewBase()
                """);
        assertTrue(getHints().isEmpty(),
                "No hint expected for Kotlin class without @ComponentView");
    }

    /**
     * When the Kotlin base class IS resolved (defined in the same file), no hint must appear.
     */
    @Test
    void noHintWhenBaseClassIsResolvedInKotlin() {
        getFixture().configureByText("KtResolvedView.kt", """
                package sample
                import org.jfxcore.markup.ComponentView
                @ComponentView(\"""
                    <StackPane/>
                    \""")
                open class KtResolvedView : KtResolvedViewBase()
                open class KtResolvedViewBase : javafx.scene.layout.StackPane() {
                    protected fun initializeComponent() {}
                }
                """);
        assertTrue(getHints().isEmpty(),
                "No hint expected when Kotlin base class is resolved");
    }

    /**
     * {@code @Suppress("Fxml2AnnotationProcessingHint")} on a Kotlin class must suppress
     * the base-class hint.
     */
    @Test
    void suppressOnKotlinClassSuppressesHint() {
        getFixture().configureByText("KtSuppressedView.kt", """
                package sample
                import org.jfxcore.markup.ComponentView
                @Suppress("Fxml2AnnotationProcessingHint")
                @ComponentView(\"""
                    <StackPane/>
                    \""")
                open class KtSuppressedView : KtSuppressedViewBase()
                """);
        assertTrue(getHints().isEmpty(),
                "Hint should be suppressed by @Suppress on the Kotlin class");
    }
}
