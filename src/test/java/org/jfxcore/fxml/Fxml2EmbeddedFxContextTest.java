package org.jfxcore.fxml;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.jfxcore.fxml.annotator.Fxml2UnusedImportsInspection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code fx:context} binding-context resolution in embedded FXML2.
 *
 * <p>These tests mirror {@link Fxml2FxContextTest} for the embedded ({@code @ComponentView})
 * form.  In embedded FXML2 the user's root element is wrapped in a synthetic
 * {@code <fxml2:embedded>} element by the language injector; {@code fx:context} on the
 * user's root must still be recognized and must redirect binding path resolution to
 * the designated context class.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class Fxml2EmbeddedFxContextTest extends Fxml2TestBase {

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

    @BeforeAll
    void addContextClass() {
        getFixture().addClass("""
                package test;
                import javafx.beans.property.StringProperty;
                import javafx.beans.property.SimpleStringProperty;
                public class MyEmbeddedContext {
                    private final StringProperty userName =
                            new SimpleStringProperty(this, "userName", "");
                    public StringProperty userNameProperty() { return userName; }
                    public String getUserName() { return userName.get(); }
                    public void setUserName(String v) { userName.set(v); }
                }
                """);
    }

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2AttributeValueInspection());
    }

    // -----------------------------------------------------------------------
    // Attribute (binding-path) form: fx:context="$myContext"
    // -----------------------------------------------------------------------

    /**
     * When {@code fx:context="$myContext"} is set on the embedded root element, a
     * binding path like {@code ${userName}} is resolved against the context class,
     * not the code-behind class.
     */
    @Test
    void bindingPathResolvesAgainstContextClassAttributeForm() {
        getFixture().configureByText("FxContextAttr.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane fx:context="$myContext">
                      <Label text="${userName}"/>
                    </StackPane>
                    \""")
                public class FxContextAttr {
                    public test.MyEmbeddedContext myContext = new test.MyEmbeddedContext();
                    protected void initializeComponent() {}
                    public FxContextAttr() { initializeComponent(); }
                }
                """);

        var errors = getFixture().doHighlighting(HighlightSeverity.ERROR);
        assertFalse(errors.stream().anyMatch(h ->
                h.getDescription() != null && h.getDescription().contains("userName")),
                "userName is a property on MyEmbeddedContext; it must resolve without error " +
                "when fx:context is set. Errors: " + errors);
    }

    /**
     * When {@code fx:context} is set, a property that exists only on the code-behind
     * class (and not on the context class) must produce an unresolved-binding error.
     */
    @Test
    void bindingPathOnCodeBehindOnlyPropertyProducesErrorWhenContextSet() {
        getFixture().configureByText("FxContextAttrErr.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <StackPane fx:context="$myContext">
                      <Label text="${myContext}"/>
                    </StackPane>
                    \""")
                public class FxContextAttrErr {
                    public test.MyEmbeddedContext myContext = new test.MyEmbeddedContext();
                    protected void initializeComponent() {}
                    public FxContextAttrErr() { initializeComponent(); }
                }
                """);

        var errors = getFixture().doHighlighting(HighlightSeverity.ERROR);
        assertTrue(errors.stream().anyMatch(h ->
                h.getDescription() != null && h.getDescription().contains("myContext")),
                "myContext is a field on the code-behind class, not on MyEmbeddedContext; " +
                "it must produce an error when fx:context is set. Errors: " + errors);
    }

    // -----------------------------------------------------------------------
    // Element-notation form: <fx:context><MyContext/></fx:context>
    // -----------------------------------------------------------------------

    /**
     * When {@code fx:context} uses element notation, binding paths are resolved against
     * the class of the inline context object, not the code-behind class.
     */
    @Test
    void bindingPathResolvesAgainstContextClassElementForm() {
        getFixture().configureByText("FxContextElem.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <?import test.MyEmbeddedContext?>
                    <StackPane>
                      <fx:context>
                        <MyEmbeddedContext/>
                      </fx:context>
                      <Label text="${userName}"/>
                    </StackPane>
                    \""")
                public class FxContextElem {
                    protected void initializeComponent() {}
                    public FxContextElem() { initializeComponent(); }
                }
                """);

        var errors = getFixture().doHighlighting(HighlightSeverity.ERROR);
        assertFalse(errors.stream().anyMatch(h ->
                h.getDescription() != null && h.getDescription().contains("userName")),
                "userName is a property on MyEmbeddedContext; it must resolve without error " +
                "when the element-notation context is set. Errors: " + errors);
    }

    /**
     * An unresolvable property against the element-notation context class must produce
     * an unresolved-binding error.
     */
    @Test
    void bindingPathOnMissingPropertyProducesErrorWithElementContextForm() {
        getFixture().configureByText("FxContextElemErr.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                @ComponentView(\"""
                    <?import test.MyEmbeddedContext?>
                    <StackPane>
                      <fx:context>
                        <MyEmbeddedContext/>
                      </fx:context>
                      <Label text="${noSuchProp}"/>
                    </StackPane>
                    \""")
                public class FxContextElemErr {
                    protected void initializeComponent() {}
                    public FxContextElemErr() { initializeComponent(); }
                }
                """);

        var errors = getFixture().doHighlighting(HighlightSeverity.ERROR);
        assertTrue(errors.stream().anyMatch(h ->
                h.getDescription() != null && h.getDescription().contains("noSuchProp")),
                "noSuchProp does not exist on MyEmbeddedContext; it must produce an error. " +
                "Errors: " + errors);
    }

    // -----------------------------------------------------------------------
    // Observable binding form: fx:context="${observableProperty}"
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // Unresolvable class inside <fx:context> element notation
    // -----------------------------------------------------------------------

    /**
     * A class name inside {@code <fx:context>} that cannot be imported must produce a
     * "Cannot resolve symbol" error so that the auto-import quick-fix is offered.
     */
    @Test
    void unresolvableClassInsideFxContextElementProducesError() {
        getFixture().configureByText("FxContextBadClass.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <StackPane>
                      <fx:context>
                        <CannotResolveMe123/>
                      </fx:context>
                    </StackPane>
                    \""")
                public class FxContextBadClass {
                    protected void initializeComponent() {}
                    public FxContextBadClass() { initializeComponent(); }
                }
                """);

        var errors = getFixture().doHighlighting(HighlightSeverity.ERROR);
        assertTrue(errors.stream().anyMatch(h ->
                h.getDescription() != null && h.getDescription().contains("CannotResolveMe123")),
                "An unresolvable class inside <fx:context> must produce a 'Cannot resolve symbol' error. " +
                "Errors: " + errors);
    }

    /**
     * A valid (imported) class inside {@code <fx:context>} must produce no error.
     */
    @Test
    void importedClassInsideFxContextElementProducesNoError() {
        getFixture().configureByText("FxContextGoodClass.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <?import test.MyEmbeddedContext?>
                    <StackPane>
                      <fx:context>
                        <MyEmbeddedContext/>
                      </fx:context>
                    </StackPane>
                    \""")
                public class FxContextGoodClass {
                    protected void initializeComponent() {}
                    public FxContextGoodClass() { initializeComponent(); }
                }
                """);

        var errors = getFixture().doHighlighting(HighlightSeverity.ERROR);
        assertFalse(errors.stream().anyMatch(h ->
                h.getDescription() != null && h.getDescription().contains("MyEmbeddedContext")),
                "An imported class inside <fx:context> must not produce an error. Errors: " + errors);
    }

    /**
     * {@code fx:context} can be set via an observable binding ({@code ${...}}).
     * The attribute value must be accepted without error.
     */
    @Test
    void fxContextWithObservableBindingProducesNoError() {
        getFixture().configureByText("FxContextObs.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import javafx.scene.control.*;
                import javafx.beans.property.*;
                @ComponentView(\"""
                    <StackPane fx:context="${ctx}">
                      <Label text="${userName}"/>
                    </StackPane>
                    \""")
                public class FxContextObs {
                    private final ObjectProperty<test.MyEmbeddedContext> ctx =
                            new SimpleObjectProperty<>(this, "ctx", new test.MyEmbeddedContext());
                    public ObjectProperty<test.MyEmbeddedContext> ctxProperty() { return ctx; }
                    public test.MyEmbeddedContext getCtx() { return ctx.get(); }
                    protected void initializeComponent() {}
                    public FxContextObs() { initializeComponent(); }
                }
                """);

        var errors = getFixture().doHighlighting(HighlightSeverity.ERROR);
        assertFalse(errors.stream().anyMatch(h ->
                h.getDescription() != null && h.getDescription().contains("userName")),
                "userName must resolve against the observable context class. Errors: " + errors);
    }

    // -----------------------------------------------------------------------
    // Unused import: fxml <?import?> and Java import for element-notation context
    // -----------------------------------------------------------------------

    /**
     * An {@code <?import?>} PI inside embedded markup for a class referenced only in
     * {@code <fx:context><MyEmbeddedContext/></fx:context>} must NOT be flagged as unused.
     */
    @Test
    void fxmlImportOfContextClassInElementNotationIsNotUnused() {
        getFixture().enableInspections(new Fxml2UnusedImportsInspection());
        getFixture().configureByText("FxContextImportCheck.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                @ComponentView(\"""
                    <?import test.MyEmbeddedContext?>
                    <StackPane>
                      <fx:context>
                        <MyEmbeddedContext/>
                      </fx:context>
                    </StackPane>
                    \""")
                public class FxContextImportCheck {
                    protected void initializeComponent() {}
                    public FxContextImportCheck() { initializeComponent(); }
                }
                """);

        var warnings = getFixture().doHighlighting(HighlightSeverity.WARNING);
        assertFalse(warnings.stream().anyMatch(h ->
                h.getDescription() != null && h.getDescription().contains("MyEmbeddedContext")),
                "The fxml import for the context class must not be reported as unused. Warnings: " + warnings);
    }

    /**
     * A Java {@code import} for a class referenced only inside
     * {@code <fx:context><MyEmbeddedContext/></fx:context>} in embedded FXML2 must NOT be
     * reported as "Unused import statement" by the IDE's Java unused-import highlight pass.
     *
     * <p>The {@link org.jfxcore.fxml.lang.Fxml2JavaUnusedImportHighlightFilter} consults
     * {@link org.jfxcore.fxml.annotator.Fxml2UnusedImportsInspection#collectUsedSimpleNames},
     * which collects class-tag names from all XML tags including those inside {@code <fx:context>}.
     */
    @Test
    void javaImportOfContextClassInElementNotationIsNotUnused() {
        getFixture().configureByText("FxContextJavaImport.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.*;
                import test.MyEmbeddedContext;
                @ComponentView(\"""
                    <?import test.MyEmbeddedContext?>
                    <StackPane>
                      <fx:context>
                        <MyEmbeddedContext/>
                      </fx:context>
                    </StackPane>
                    \""")
                public class FxContextJavaImport {
                    protected void initializeComponent() {}
                    public FxContextJavaImport() { initializeComponent(); }
                }
                """);

        var highlights = getFixture().doHighlighting();
        var unusedImports = highlights.stream()
                .filter(h -> "Unused import statement".equals(h.getDescription()))
                .map(HighlightInfo::getText)
                .toList();
        assertFalse(unusedImports.contains("import test.MyEmbeddedContext;"),
                "Java import for the context class must not be reported as unused. "
                + "Unused imports: " + unusedImports);
    }
}
