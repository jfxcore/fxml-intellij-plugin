package org.jfxcore.fxml;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.EdtTestUtil;
import org.jfxcore.fxml.annotator.Fxml2UnresolvedSubclassInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Fxml2UnresolvedSubclassInspection}.
 *
 * <p>Doc feature ({@code reference/subclass.md}): {@code fx:subclass} names the
 * fully-qualified code-behind class, which must exist. The compiler reports
 * {@code "'com.sample.MyControl' cannot be resolved"} otherwise.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2UnresolvedSubclassInspectionTest extends Fxml2TestBase {

    private static final String CREATE_FIX = "Create code-behind class 'TestView'";
    private static final String REMOVE_FIX = "Remove fx:subclass";

    @BeforeEach
    void enableInspection() {
        getFixture().enableInspections(new Fxml2UnresolvedSubclassInspection());
    }

    /**
     * Builds a minimal FXML/2 document whose {@code fx:subclass} value is the given text,
     * which may carry highlighting markers.
     */
    private static String fxmlWithSubclassText(String subclassText) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            fx:subclass="%s"/>
                """.formatted(subclassText);
    }

    /**
     * Configures an FXML document whose {@code fx:subclass} is {@code subclass}, with the
     * caret inside the declared name so that its quick-fixes are collected.
     */
    private void configureWithCaretInSubclass(String subclass) {
        String withCaret = subclass.charAt(0) + "<caret>" + subclass.substring(1);
        getFixture().configureByText("TestView.fxml", fxmlWithSubclassText(withCaret));
    }

    // -----------------------------------------------------------------------
    // Highlighting
    // -----------------------------------------------------------------------

    /** An existing code-behind class produces no error. */
    @Test
    void existingCodeBehindClassProducesNoError() {
        getFixture().addClass("""
                package test;
                public class TestView extends javafx.scene.layout.BorderPane {}
                """);
        getFixture().configureByText("TestView.fxml", fxmlWithSubclassText("test.TestView"));
        getFixture().checkHighlighting(false, false, false);
    }

    /** A code-behind class that does not exist is reported as unresolvable. */
    @Test
    void missingCodeBehindClassProducesError() {
        getFixture().configureByText("TestView.fxml",
                fxmlWithSubclassText(error("'test.TestView' cannot be resolved", "test.TestView")));
        getFixture().checkHighlighting(false, false, false);
    }

    /** An FXML document without {@code fx:subclass} compiles standalone and produces no error. */
    @Test
    void missingSubclassAttributeProducesNoError() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"/>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Embedded markup takes its code-behind class from the annotated class, so the
     * synthetic {@code fx:subclass} of the injected wrapper is never reported.
     */
    @Test
    void embeddedMarkupProducesNoUnresolvedSubclassError() {
        getFixture().addClass("""
                package org.jfxcore.markup;
                import java.lang.annotation.*;
                @Target(ElementType.TYPE)
                @Retention(RetentionPolicy.SOURCE)
                public @interface ComponentView { String value(); }
                """);
        getFixture().addClass("""
                package test;
                public abstract class TestEmbedBase extends javafx.scene.layout.BorderPane {
                    protected void initializeComponent() {}
                }
                """);
        getFixture().configureByText("TestEmbed.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                @ComponentView(""\"
                    <javafx.scene.layout.BorderPane/>
                    ""\")
                public class TestEmbed extends TestEmbedBase {
                    public TestEmbed() { initializeComponent(); }
                }
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Quick-fix availability
    // -----------------------------------------------------------------------

    /** Both quick-fixes are offered for a valid name matching the FXML file name. */
    @Test
    void bothFixesAreOfferedForValidName() {
        configureWithCaretInSubclass("test.TestView");
        assertFalse(getFixture().filterAvailableIntentions(CREATE_FIX).isEmpty(),
                "'Create code-behind class' must be offered for a valid code-behind name");
        assertFalse(getFixture().filterAvailableIntentions(REMOVE_FIX).isEmpty(),
                "'Remove fx:subclass' must always be offered");
    }

    /**
     * The compiler rejects the unnamed package for a code-behind class, so creating the
     * class is not offered for an unqualified name.
     */
    @Test
    void createFixIsNotOfferedForUnqualifiedName() {
        configureWithCaretInSubclass("TestView");
        assertTrue(getFixture().filterAvailableIntentions(CREATE_FIX).isEmpty(),
                "'Create code-behind class' must not be offered for an unqualified name");
        assertFalse(getFixture().filterAvailableIntentions(REMOVE_FIX).isEmpty(),
                "'Remove fx:subclass' must always be offered");
    }

    /** Creating the class is not offered when a name segment is not a Java identifier. */
    @Test
    void createFixIsNotOfferedForInvalidIdentifier() {
        configureWithCaretInSubclass("test.9.TestView");
        assertTrue(getFixture().filterAvailableIntentions(CREATE_FIX).isEmpty(),
                "'Create code-behind class' must not be offered for an invalid identifier");
    }

    /**
     * The compiler requires the code-behind class name to match the FXML file name, so
     * creating a differently named class is not offered.
     */
    @Test
    void createFixIsNotOfferedWhenNameDoesNotMatchFileName() {
        configureWithCaretInSubclass("test.OtherView");
        assertTrue(getFixture().filterAvailableIntentions(
                        "Create code-behind class 'OtherView'").isEmpty(),
                "'Create code-behind class' must not be offered for a mismatched class name");
    }

    // -----------------------------------------------------------------------
    // Quick-fix behavior
    // -----------------------------------------------------------------------

    /** Removing {@code fx:subclass} leaves a document that compiles to a standalone class. */
    @Test
    void removeFixDeletesTheAttribute() {
        configureWithCaretInSubclass("test.TestView");
        List<IntentionAction> fixes = getFixture().filterAvailableIntentions(REMOVE_FIX);
        assertFalse(fixes.isEmpty(), "'Remove fx:subclass' must be offered");
        getFixture().launchAction(fixes.getFirst());
        String text = ReadAction.compute(() -> getFixture().getFile().getText());
        assertFalse(text.contains("fx:subclass"),
                "fx:subclass must be gone after the fix, but was: " + text);
        assertTrue(text.contains("xmlns:fx="), "the fx namespace declaration must be kept");
    }

    /**
     * Creating the code-behind class writes a Java class in the package of the FXML file,
     * extending the markup base class the compiler generates for it.
     */
    @Test
    void createFixCreatesJavaCodeBehindClass() {
        PsiFile fxmlFile = getFixture().addFileToProject(
                "test/TestView.fxml", fxmlWithSubclassText("test.TestView"));
        getFixture().configureFromExistingVirtualFile(fxmlFile.getVirtualFile());
        moveCaretIntoSubclassValue();

        List<IntentionAction> fixes = getFixture().filterAvailableIntentions(CREATE_FIX);
        assertFalse(fixes.isEmpty(), "'Create code-behind class' must be offered");
        getFixture().launchAction(fixes.getFirst());

        VirtualFile created = getFixture().findFileInTempDir("test/TestView.java");
        assertNotNull(created, "test/TestView.java must have been created");
        String text = ReadAction.compute(() -> {
            PsiFile psi = getFixture().getPsiManager().findFile(created);
            return psi != null ? psi.getText() : "";
        });
        assertEquals("""
                package test;

                public class TestView extends TestViewBase {
                    public TestView() {
                        initializeComponent();
                    }
                }""", text.trim());
    }

    /** Places the caret inside the declared {@code fx:subclass} name of the open document. */
    private void moveCaretIntoSubclassValue() {
        int offset = ReadAction.compute(
                () -> getFixture().getFile().getText().indexOf("test.TestView"));
        EdtTestUtil.runInEdtAndWait(
                () -> getFixture().getEditor().getCaretModel().moveToOffset(offset + 1));
    }
}
