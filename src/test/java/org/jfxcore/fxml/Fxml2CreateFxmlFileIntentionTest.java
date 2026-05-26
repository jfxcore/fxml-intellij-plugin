// Copyright (c) 2025, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.IntentionActionDelegate;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.jfxcore.fxml.actions.CreateFxmlFileIntention;
import org.jfxcore.fxml.actions.CreateFxmlFileIntentionJava;
import org.jfxcore.fxml.annotator.Fxml2CreateFxmlFileInspection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CreateFxmlFileIntention}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Availability when caret is on the annotation name in a Java file.</li>
 *   <li>Non-availability when prerequisites are not met (FXML already exists,
 *       caret inside annotation value).</li>
 *   <li>Integration: the FXML file is created with correct namespace attributes,
 *       fx:subclass, and {@code <?import?>} PIs; the @ComponentView annotation is removed.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2CreateFxmlFileIntentionTest extends Fxml2TestBase {

    // -----------------------------------------------------------------------
    // Java source templates used across tests

    /**
     * Java class with a @ComponentView text-block annotation (self-closing root element).
     * {@code ""\} inside the outer text block produces three consecutive {@code "} chars,
     * which form the inner text-block delimiters in the generated Java source string.
     */
    private static final String JAVA_WITH_ANNOTATION = """
            package test;
            import org.jfxcore.markup.ComponentView;
            import javafx.scene.layout.BorderPane;
            @ComponentView(""\"
                <BorderPane/>
                ""\")
            public class TestView extends TestViewBase {
                public TestView() { initializeComponent(); }
            }
            """;

    /**
     * Java class with a @ComponentView annotation containing child elements.
     * {@code ""\} inside the outer text block produces three consecutive {@code "} chars.
     */
    private static final String JAVA_WITH_CHILDREN = """
            package test;
            import org.jfxcore.markup.ComponentView;
            import javafx.scene.layout.BorderPane;
            import javafx.scene.control.Label;
            @ComponentView(""\"
                <BorderPane prefHeight="400.0">
                    <Label text="Hello"/>
                </BorderPane>
                ""\")
            public class TestEmbed extends TestEmbedBase {
                public TestEmbed() { initializeComponent(); }
            }
            """;

    // -----------------------------------------------------------------------
    // @BeforeAll setup

    @BeforeAll
    void addComponentViewAnnotation() {
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

    // -----------------------------------------------------------------------
    // @AfterEach cleanup

    @AfterEach
    void resetState() {
        CreateFxmlFileIntention.skipConfirmationForTesting = false;
    }

    // -----------------------------------------------------------------------
    // Availability: valid cases

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void availableOnAnnotationName() {
        // Caret on "ComponentView" in @ComponentView(...)
        getFixture().configureByText("TestView.java",
                JAVA_WITH_ANNOTATION.replace("@ComponentView", "@Compon<caret>entView"));
        assertTrue(hasCreateFxmlIntention(),
                "Should be available when caret is on the annotation name");
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void availableOnAtSign() {
        // Caret on the @ token
        getFixture().configureByText("TestView.java",
                JAVA_WITH_ANNOTATION.replace("@ComponentView", "<caret>@ComponentView"));
        assertTrue(hasCreateFxmlIntention(),
                "Should be available when caret is on the '@' token");
    }


    // -----------------------------------------------------------------------
    // Availability: invalid cases

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void notAvailableWhenFxmlAlreadyExists() {
        getFixture().addFileToProject("TestView.fxml", "<?xml version=\"1.0\"?>");
        getFixture().configureByText("TestView.java",
                JAVA_WITH_ANNOTATION.replace("@ComponentView", "@Compon<caret>entView"));
        assertFalse(hasCreateFxmlIntention(),
                "Should NOT be available when a sibling FXML file already exists");
    }


    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void notAvailableInsideAnnotationValue() {
        // Caret is inside the text-block value ("<BorderPane/>"), not on the annotation name
        getFixture().configureByText("TestView.java",
                JAVA_WITH_ANNOTATION.replace("<BorderPane/>", "<Border<caret>Pane/>"));
        assertFalse(hasCreateFxmlIntention(),
                "Should NOT be available when caret is inside the annotation value string");
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void notAvailableOnUnrelatedAnnotation() {
        // Caret on @SuppressWarnings, not @ComponentView
        getFixture().configureByText("TestView.java",
                """
                        package test;
                        import org.jfxcore.markup.ComponentView;
                        @Suppress<caret>Warnings("unused")
                        @ComponentView(""\"
                            <BorderPane/>
                            ""\")
                        public class TestView {}
                        """);
        assertFalse(hasCreateFxmlIntention(),
                "Should NOT be available when caret is on an unrelated annotation");
    }

    // -----------------------------------------------------------------------
    // Integration: creating the FXML file produces correct output

    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void createsFxmlFileFromJavaSelfClosingAnnotation() {
        // Arrange: Java class with self-closing @ComponentView markup
        getFixture().configureByText("TestView.java",
                JAVA_WITH_ANNOTATION.replace("@ComponentView", "@Compon<caret>entView"));

        // Act
        CreateFxmlFileIntention.skipConfirmationForTesting = true;
        IntentionAction action = getFixture().findSingleIntention("Create FXML/2 file");
        assertNotNull(action, "Create FXML/2 file intention must be available");
        getFixture().launchAction(action);

        // Assert: FXML file was created
        VirtualFile fxmlVF = getFixture().findFileInTempDir("TestView.fxml");
        assertNotNull(fxmlVF, "TestView.fxml must be created");

        String fxmlText = ReadAction.compute(() -> {
            PsiFile psi = getFixture().getPsiManager().findFile(fxmlVF);
            return psi != null ? psi.getText() : "";
        });
        assertFalse(fxmlText.isEmpty(), "FXML file must have content");

        // Namespace attributes must be present
        assertTrue(fxmlText.contains("xmlns=\"http://javafx.com/javafx\""),
                "FXML must have xmlns attribute");
        assertTrue(fxmlText.contains("xmlns:fx=\"http://jfxcore.org/fxml/2.0\""),
                "FXML must have xmlns:fx attribute");
        assertTrue(fxmlText.contains("fx:subclass=\"test.TestView\""),
                "FXML must have correct fx:subclass attribute");

        // Root element must be present
        assertTrue(fxmlText.contains("<BorderPane"),
                "FXML must contain the root element");

        // Import derived from Java import must be present
        assertTrue(fxmlText.contains("<?import javafx.scene.layout.BorderPane?>"),
                "FXML must have <?import?> PI for BorderPane");

        // Assert: @ComponentView annotation is removed from the Java file
        VirtualFile javaVF = getFixture().findFileInTempDir("TestView.java");
        assertNotNull(javaVF, "Java file must still exist");
        String javaText = ReadAction.compute(() -> {
            PsiFile psi = getFixture().getPsiManager().findFile(javaVF);
            return psi != null ? psi.getText() : "";
        });
        assertFalse(javaText.isEmpty(), "Java file must have content");
        assertFalse(javaText.contains("@ComponentView"),
                "@ComponentView annotation must be removed from Java file");
        // ComponentView import must also be removed
        assertFalse(javaText.contains("import org.jfxcore.markup.ComponentView"),
                "ComponentView import must be removed");
    }

    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void createsFxmlFileFromJavaAnnotationWithChildren() {
        // Arrange: Java class with multi-element @ComponentView markup
        getFixture().configureByText("TestEmbed.java",
                JAVA_WITH_CHILDREN.replace("@ComponentView", "@Compon<caret>entView"));

        // Act
        CreateFxmlFileIntention.skipConfirmationForTesting = true;
        IntentionAction action = getFixture().findSingleIntention("Create FXML/2 file");
        assertNotNull(action, "Create FXML/2 file intention must be available");
        getFixture().launchAction(action);

        // Assert: FXML file was created
        VirtualFile fxmlVF = getFixture().findFileInTempDir("TestEmbed.fxml");
        assertNotNull(fxmlVF, "TestEmbed.fxml must be created");

        String fxmlText = ReadAction.compute(() -> {
            PsiFile psi = getFixture().getPsiManager().findFile(fxmlVF);
            return psi != null ? psi.getText() : "";
        });

        // Root element and child must be present
        assertTrue(fxmlText.contains("<BorderPane"), "Root element must be present");
        assertTrue(fxmlText.contains("<Label"), "Child element must be preserved");
        assertTrue(fxmlText.contains("prefHeight=\"400.0\""),
                "Existing attributes must be preserved");

        // Namespace and fx:subclass must be present on the root element
        assertTrue(fxmlText.contains("xmlns=\"http://javafx.com/javafx\""),
                "xmlns attribute must be present");
        assertTrue(fxmlText.contains("fx:subclass=\"test.TestEmbed\""),
                "fx:subclass must reference the correct class");

        // Both imports must appear as <?import?> PIs
        assertTrue(fxmlText.contains("<?import javafx.scene.layout.BorderPane?>"),
                "BorderPane import must be present");
        assertTrue(fxmlText.contains("<?import javafx.scene.control.Label?>"),
                "Label import must be present");

        // @ComponentView must be removed from Java file
        VirtualFile javaVF = getFixture().findFileInTempDir("TestEmbed.java");
        assertNotNull(javaVF, "Java file must still exist");
        String javaText = ReadAction.compute(() -> {
            PsiFile psi = getFixture().getPsiManager().findFile(javaVF);
            return psi != null ? psi.getText() : "";
        });
        assertFalse(javaText.contains("@ComponentView"),
                "@ComponentView annotation must be removed");
    }

    /**
     * Verifies that the root element in the generated FXML file starts at column 0 and that
     * child elements are indented consistently, regardless of how the embedded markup was
     * indented inside the {@code @ComponentView} text block.
     *
     * <p>The markup body must be extracted with {@code stripIndent()} so that all lines are
     * de-indented by the common leading-whitespace amount; using {@code strip()} instead
     * would leave inner lines with their original text-block indentation while the root
     * element is placed at column 0.
     */
    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void generatedFxmlHasCorrectIndentation() {
        getFixture().configureByText("TestEmbed.java",
                JAVA_WITH_CHILDREN.replace("@ComponentView", "@Compon<caret>entView"));

        CreateFxmlFileIntention.skipConfirmationForTesting = true;
        IntentionAction action = getFixture().findSingleIntention("Create FXML/2 file");
        assertNotNull(action, "Create FXML/2 file intention must be available");
        getFixture().launchAction(action);

        VirtualFile fxmlVF = getFixture().findFileInTempDir("TestEmbed.fxml");
        assertNotNull(fxmlVF, "TestEmbed.fxml must be created");

        String fxmlText = ReadAction.compute(() -> {
            PsiFile psi = getFixture().getPsiManager().findFile(fxmlVF);
            return psi != null ? psi.getText() : "";
        });

        // Root element must start at column 0 (no leading spaces)
        String rootLine = fxmlText.lines()
                .filter(l -> l.contains("<BorderPane"))
                .findFirst().orElse(null);
        assertNotNull(rootLine, "FXML must contain <BorderPane line");
        assertFalse(rootLine.startsWith(" "),
                "Root element must be at column 0, but was: [" + rootLine + "]");

        // Child element must be indented relative to root (> 0 leading spaces)
        String childLine = fxmlText.lines()
                .filter(l -> l.contains("<Label"))
                .findFirst().orElse(null);
        assertNotNull(childLine, "FXML must contain <Label line");
        assertTrue(childLine.startsWith(" "),
                "Child element must be indented, but was: [" + childLine + "]");

        // Child must be less indented than its own text-block indentation
        // (8 spaces for a child would indicate that stripIndent() was not applied)
        int childIndent = childLine.length() - childLine.stripLeading().length();
        assertTrue(childIndent <= 4,
                "Child element indent must be <= 4 spaces (got " + childIndent + "); "
                + "markup body was not de-indented correctly (stripIndent() must be used)");
    }

    // -----------------------------------------------------------------------
    // Import pruning: unused Java imports must not appear in the FXML file

    /**
     * Verifies that imports present in the Java file but not referenced by the embedded
     * FXML markup are excluded from the generated {@code .fxml} file.
     *
     * <p>Imports that are present in the Java file but not referenced by the embedded
     * FXML markup must be excluded from the generated {@code .fxml} file.
     */
    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void unusedJavaImportNotIncludedInFxmlFile() {
        // Add a class that is only used by Java code, not by the FXML markup
        getFixture().addClass("""
                package test;
                public class RelayCommand {}
                """);

        getFixture().configureByText("MainView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.StackPane;
                import javafx.scene.control.Label;
                import test.RelayCommand;
                @Compon<caret>entView(""\"
                    <StackPane>
                        <Label text="hello"/>
                    </StackPane>
                    ""\")
                public class MainView {
                    protected void initializeComponent() {}
                    public MainView() { initializeComponent(); }
                    private final RelayCommand cmd = new RelayCommand();
                }
                """);

        CreateFxmlFileIntention.skipConfirmationForTesting = true;
        IntentionAction action = getFixture().findSingleIntention("Create FXML/2 file");
        assertNotNull(action, "Create FXML/2 file intention must be available");
        getFixture().launchAction(action);

        VirtualFile fxmlVF = getFixture().findFileInTempDir("MainView.fxml");
        assertNotNull(fxmlVF, "MainView.fxml must be created");

        String fxmlText = ReadAction.compute(() -> {
            PsiFile psi = getFixture().getPsiManager().findFile(fxmlVF);
            return psi != null ? psi.getText() : "";
        });

        // RelayCommand is not referenced in the FXML: it must NOT appear
        assertFalse(fxmlText.contains("RelayCommand"),
                "Unused import 'RelayCommand' must NOT appear in the FXML file.\n"
                + "Actual FXML:\n" + fxmlText);

        // StackPane and Label ARE used as tag names: their imports must be present
        assertTrue(fxmlText.contains("<?import javafx.scene.layout.StackPane?>")
                        || fxmlText.contains("<?import javafx.scene.layout.*?>"),
                "StackPane import must be present in FXML.\nActual FXML:\n" + fxmlText);
        assertTrue(fxmlText.contains("<?import javafx.scene.control.Label?>")
                        || fxmlText.contains("<?import javafx.scene.control.*?>"),
                "Label import must be present in FXML.\nActual FXML:\n" + fxmlText);
    }

    // -----------------------------------------------------------------------
    // Processing instructions in the embedded markup body

    /**
     * Verifies that leading processing instructions (e.g. {@code <?prefix?>}) in the
     * embedded markup body are placed before the root element in the generated FXML file,
     * not treated as the root element itself.
     *
     * <p>When the annotation value starts with a {@code <?...?>} PI followed by the actual
     * root element, the generated standalone FXML must:
     * <ul>
     *   <li>Have {@code <?xml?>} as the first declaration.</li>
     *   <li>Have any {@code <?import?>} PIs next.</li>
     *   <li>Have the markup-body PIs (e.g. {@code <?prefix?>}) after the imports.</li>
     *   <li>Have the root element with namespace attributes last.</li>
     * </ul>
     */
    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void prefixProcessingInstructionPlacedBeforeRootElement() {
        getFixture().configureByText("MainView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.StackPane;
                import test.MyMarkupExtension;
                @Compon<caret>entView(""\"
                    <?prefix % = MyMarkupExtension?>
                    <StackPane/>
                    ""\")
                public class MainView {
                    protected void initializeComponent() {}
                    public MainView() { initializeComponent(); }
                }
                """);

        getFixture().addClass("""
                package test;
                public class MyMarkupExtension {}
                """);

        CreateFxmlFileIntention.skipConfirmationForTesting = true;
        IntentionAction action = getFixture().findSingleIntention("Create FXML/2 file");
        assertNotNull(action, "Create FXML/2 file intention must be available");
        getFixture().launchAction(action);

        VirtualFile fxmlVF = getFixture().findFileInTempDir("MainView.fxml");
        assertNotNull(fxmlVF, "MainView.fxml must be created");

        String fxmlText = ReadAction.compute(() -> {
            PsiFile psi = getFixture().getPsiManager().findFile(fxmlVF);
            return psi != null ? psi.getText() : "";
        });

        // The <?prefix?> PI must appear before the root element
        int prefixIdx = fxmlText.indexOf("<?prefix");
        int rootIdx   = fxmlText.indexOf("<StackPane");
        assertTrue(prefixIdx >= 0,
                "<?prefix?> PI must be present in the generated FXML.\nActual:\n" + fxmlText);
        assertTrue(rootIdx >= 0,
                "<StackPane> root element must be present.\nActual:\n" + fxmlText);
        assertTrue(prefixIdx < rootIdx,
                "<?prefix?> PI must come before <StackPane> root element.\nActual:\n" + fxmlText);

        // The root element must have xmlns and fx:subclass, not <?prefix?>
        String rootLine = fxmlText.lines()
                .filter(l -> l.stripLeading().startsWith("<StackPane"))
                .findFirst().orElse(null);
        assertNotNull(rootLine, "FXML must contain a <StackPane line.\nActual:\n" + fxmlText);
        assertTrue(rootLine.contains("xmlns="),
                "<StackPane> must carry xmlns attribute.\nActual line: " + rootLine);
        assertTrue(rootLine.contains("fx:subclass="),
                "<StackPane> must carry fx:subclass attribute.\nActual line: " + rootLine);
        assertFalse(rootLine.contains("?prefix"),
                "<?prefix?> content must NOT be merged into the root element tag.\nActual line: " + rootLine);
    }

    /**
     * Verifies that leading XML comments in the embedded markup body are placed before the
     * root element in the generated FXML file and do not get merged into the root element's
     * opening tag.
     *
     * <p>When the annotation value starts with a {@code <!-- ... -->} comment followed by the
     * actual root element, the generated standalone FXML must:
     * <ul>
     *   <li>Preserve the comment text verbatim, placed before the root element.</li>
     *   <li>Have the root element with namespace and {@code fx:subclass} attributes.</li>
     *   <li>Not include any comment text inside the root element's opening tag.</li>
     * </ul>
     */
    @Test @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void leadingXmlCommentPlacedBeforeRootElement() {
        getFixture().configureByText("MainView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.layout.StackPane;
                @Compon<caret>entView(""\"
                    <!-- a leading comment -->
                    <StackPane/>
                    ""\")
                public class MainView {
                    protected void initializeComponent() {}
                    public MainView() { initializeComponent(); }
                }
                """);

        CreateFxmlFileIntention.skipConfirmationForTesting = true;
        IntentionAction action = getFixture().findSingleIntention("Create FXML/2 file");
        assertNotNull(action, "Create FXML/2 file intention must be available");
        getFixture().launchAction(action);

        VirtualFile fxmlVF = getFixture().findFileInTempDir("MainView.fxml");
        assertNotNull(fxmlVF, "MainView.fxml must be created");

        String fxmlText = ReadAction.compute(() -> {
            PsiFile psi = getFixture().getPsiManager().findFile(fxmlVF);
            return psi != null ? psi.getText() : "";
        });

        int commentIdx = fxmlText.indexOf("<!-- a leading comment -->");
        int rootIdx    = fxmlText.indexOf("<StackPane");
        assertTrue(commentIdx >= 0,
                "Comment must be present in the generated FXML.\nActual:\n" + fxmlText);
        assertTrue(rootIdx >= 0,
                "<StackPane> root element must be present.\nActual:\n" + fxmlText);
        assertTrue(commentIdx < rootIdx,
                "Comment must come before <StackPane> root element.\nActual:\n" + fxmlText);

        // The root element must have xmlns and fx:subclass, not comment content
        String rootLine = fxmlText.lines()
                .filter(l -> l.stripLeading().startsWith("<StackPane"))
                .findFirst().orElse(null);
        assertNotNull(rootLine, "FXML must contain a <StackPane line.\nActual:\n" + fxmlText);
        assertTrue(rootLine.contains("xmlns="),
                "<StackPane> must carry xmlns attribute.\nActual line: " + rootLine);
        assertTrue(rootLine.contains("fx:subclass="),
                "<StackPane> must carry fx:subclass attribute.\nActual line: " + rootLine);
        assertFalse(rootLine.contains("<!--") || rootLine.contains("-->"),
                "Comment content must NOT be merged into the root element tag.\nActual line: " + rootLine);
    }

    // -----------------------------------------------------------------------
    // Intention context menu: preview/documentation

    /**
     * Verifies that the "Create FXML file" intention provides documentation in the
     * intention context menu (Alt+Enter popup) by returning an HTML description from
     * {@code generatePreview()}.
     *
     * <p>Without the {@code generatePreview()} override, IntelliJ falls back to
     * {@code IntentionPreviewInfo.EMPTY} because {@code startInWriteAction()} returns
     * {@code false}, showing no documentation at all in the context menu.
     */
    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void contextMenuShowsDocumentation() {
        getFixture().configureByText("TestView.java",
                JAVA_WITH_ANNOTATION.replace("@ComponentView", "@Compon<caret>entView"));
        IntentionAction action = getFixture().findSingleIntention("Create FXML/2 file");
        assertNotNull(action, "Create FXML/2 file intention must be available");

        getFixture().checkIntentionPreviewHtml(action,
                "Creates a new <code>.fxml</code> file from the embedded markup " +
                "and removes the <code>@ComponentView</code> annotation.");
    }

    // -----------------------------------------------------------------------
    // Interaction with Fxml2CreateFxmlFileInspection

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void intentionHiddenWhenInspectionIsEnabled() {
        getFixture().enableInspections(new Fxml2CreateFxmlFileInspection());
        getFixture().configureByText("TestView.java",
                JAVA_WITH_ANNOTATION.replace("@ComponentView", "@Compon<caret>entView"));
        assertFalse(hasCreateFxmlIntention(),
                "Intention must be hidden when the Fxml2CreateFxmlFile inspection is enabled");
    }

    @Test @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void intentionVisibleWhenInspectionIsDisabled() {
        // Inspection NOT enabled (default): intention must be available
        getFixture().configureByText("TestView.java",
                JAVA_WITH_ANNOTATION.replace("@ComponentView", "@Compon<caret>entView"));
        assertTrue(hasCreateFxmlIntention(),
                "Intention must be visible when the Fxml2CreateFxmlFile inspection is disabled");
    }

    // -----------------------------------------------------------------------
    // Helpers

    private boolean hasCreateFxmlIntention() {
        return getFixture().getAvailableIntentions().stream()
                .map(IntentionActionDelegate::unwrap)
                .anyMatch(i -> i instanceof CreateFxmlFileIntentionJava);
    }
}
