package org.jfxcore.fxml;

import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tests that correspond to the FXML compiler's FxmlParserTest (both unit and
 * integration variants).
 *
 * <p>Source compiler tests:
 * <ul>
 *   <li>{@code src/test/java/org/jfxcore/compiler/parse/FxmlParserTest.java}
 *   <li>{@code src/compilerTest/java/org/jfxcore/compiler/FxmlParserTest.java}
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2ParserTest extends Fxml2TestBase {

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2AttributeValueInspection());
    }

    // -----------------------------------------------------------------------
    // Namespace / xmlns handling: compiler: FxmlParserTest (compilerTest)
    // -----------------------------------------------------------------------

    /**
     * Compiler: JavaFX_Namespace_Can_Contain_Trailing_Slash.
     * The compiler accepts {@code http://javafx.com/javafx/} as a valid namespace URI.
     * The IntelliJ XML validator reports "URI is not registered" for non-standard URIs
     * in a plain XML file, so this test is disabled until the plugin suppresses that
     * warning for recognized FXML namespace URIs.
     */
    @Test
    void javafxNamespaceWithTrailingSlashIsValid() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.*?>
                <GridPane xmlns="http://javafx.com/javafx/" xmlns:fx="http://jfxcore.org/fxml/2.0"/>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Compiler: JavaFX_Namespace_Can_Contain_SubPath.
     * The compiler accepts {@code http://javafx.com/javafx/21} as a valid namespace URI.
     */
    @Test
    void javafxNamespaceWithVersionSubPathIsValid() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.*?>
                <GridPane xmlns="http://javafx.com/javafx/21" xmlns:fx="http://jfxcore.org/fxml/2.0"/>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Compiler: FXML_Namespace_Can_Contain_Trailing_Slash.
     * The compiler accepts {@code http://jfxcore.org/fxml/2.0/} as a valid fx namespace URI.
     */
    @Test
    void fxmlNamespaceWithTrailingSlashIsValid() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.*?>
                <GridPane xmlns="http://javafx.com/javafx/" xmlns:fx="http://jfxcore.org/fxml/2.0/"/>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /** Compiler: FxNamespace_Is_Not_Required (unit test) */
    @Test
    void fxNamespaceIsNotRequired() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.*?>
                <GridPane xmlns="http://javafx.com/javafx" prefWidth="10"/>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Compiler: Unknown_Namespace_Fails (unit test): an unknown XML namespace prefix is an error.
     * The compiler emits UNKNOWN_NAMESPACE; IntelliJ's XML validator emits its own diagnostics
     * for the unbound namespace prefix: "Attribute not allowed" on the attribute name, and
     * "Property is read-only" on the full attribute.
     */
    @Test
    void unknownNamespacePrefixProducesError() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.*?>
                <GridPane xmlns="http://javafx.com/javafx" <error descr="Property 'foo:prefWidth' is read-only"><error descr="Attribute foo:prefWidth is not allowed here">foo:prefWidth</error>="10"</error>/>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * Compiler: Unmatched_Tags_Throws_Exception.
     * IntelliJ's XML validator fires "Start tag has wrong closing tag" / "Wrong closing tag name"
     * rather than the compiler's UNMATCHED_TAG code.  The test verifies those IntelliJ
     * diagnostics are present (not suppressed by the plugin).
     */
    @Test
    void unmatchedTagsProduceError() {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.*?>
                <<error descr="Start tag has wrong closing tag">GridPane</error> xmlns="http://javafx.com/javafx">
                </<error descr="Wrong closing tag name">Button</error>>
                """);
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Processing instructions / imports
    // -----------------------------------------------------------------------

    /** Compiler: ProcessingInstructions_Are_Parsed_Correctly (unit test) */
    @Test
    void processingInstructionImportsAreRecognized() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.layout.GridPane\njavafx.scene.control.Label",
                """
                  <GridPane/>
                  <Label text="hi"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // CDATA / character entities: no plugin error expected
    // -----------------------------------------------------------------------

    /** Compiler: CDataSection_Is_Not_Processed */
    @Test
    void cdataSectionIsPassedThroughWithoutError() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label><![CDATA[ < > & ]]></Label>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /** Compiler: Unescape_Character_Entity_References */
    @Test
    void characterEntityReferencesAreAccepted() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="&gt;&lt;&amp;&quot;&apos;"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Escaped opening curly: binding expression escaping
    // -----------------------------------------------------------------------

    /**
     * A backslash followed by {@code {identifier}} is a binding-expression escape:
     * the value {@code \{foo}} is a literal string, not a binding expression.
     * No error must be reported.
     */
    @Test
    void backslashEscapeBeforeCurlyIsNotTreatedAsBindingExpression() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="\\{foo}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A backslash followed by {@code $source} is a binding-expression escape:
     * the value {@code \$source} is a literal string, not an evaluate binding.
     * No error must be reported.
     */
    @Test
    void backslashEscapeBeforeDollarIsLiteralString() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="\\$source"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A backslash followed by {@code >{source}} is a binding-expression escape:
     * the value {@code \>{source}} is a literal string, not a push binding.
     * No error must be reported.
     */
    @Test
    void backslashEscapeBeforePushNotationIsLiteralString() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="\\>{source}"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * A backslash NOT followed by a binding-expression start character is not an escape:
     * the value {@code \bar} is a literal string (backslash is kept as-is).
     * No error must be reported.
     */
    @Test
    void backslashNotFollowedByBindingStartIsLiteralString() {
        getFixture().configureByText("TestView.fxml", fxml(
                "javafx.scene.control.Label",
                """
                  <Label text="\\bar"/>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }
}
