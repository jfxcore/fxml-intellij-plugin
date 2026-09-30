package org.jfxcore.fxml;

import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiDocumentManager;
import org.jfxcore.fxml.highlighting.Fxml2SemanticAnnotator;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.psi.xml.XmlFile;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.EdtTestUtil;
import org.jfxcore.fxml.highlighting.Fxml2SemanticAnalyzer;
import org.jfxcore.fxml.highlighting.Fxml2SemanticRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Fxml2SemanticHighlightingTest extends Fxml2TestBase {
    private record Color(TextRange range, String key) {}
    private List<Color> colors;

    @BeforeEach
    void resetColors() {
        colors = null;
    }

    @Test
    void extensionConfigurationUsesPropertyAndTypeColors() {
        getFixture().addClass("""
                package test;
                import javafx.beans.DefaultProperty;
                import javafx.beans.NamedArg;
                @DefaultProperty("key")
                public class PersistedWidth extends javafx.scene.layout.Pane {
                    public PersistedWidth(@NamedArg("key") String key) {}
                    public void setDefaultValue(double value) {}
                    public void setColumnWidth(double value) {}
                }
                """);
        getFixture().configureByText("View.fxml", """
                <?import test.PersistedWidth?>
                <PersistedWidth xmlns="http://javafx.com/javafx"
                                xmlns:fx="http://jfxcore.org/fxml/2.0"
                                columnWidth="{PersistedWidth view.columns.title; defaultValue=200}"/>
                """);
        assertColor("columnWidth", "FXML2_PROPERTY_ASSIGNMENT");
        assertColor("defaultValue", "FXML2_PROPERTY_ASSIGNMENT");
        assertColor("200", "FXML2_NUMBER");
        assertColor("{PersistedWidth", 1, "PersistedWidth".length(), "FXML2_TYPE");
        assertColor("<PersistedWidth", 1, "PersistedWidth".length(), "FXML2_TYPE");
    }

    @BeforeEach
    void addModel() {
        getFixture().addClass("""
                package test;
                public class Model {
                    public int amount;
                    public static int current;
                    public static final int LIMIT = 20;
                    public String getMessage() { return "message"; }
                    public int getCount() { return 3; }
                    public String label(String value) { return value; }
                    public Model(String value) {}
                }
                """);
        getFixture().addClass("""
                package test;
                public class SemanticView extends javafx.scene.layout.Pane {
                    public Model model;
                    public void handleAction(javafx.event.ActionEvent event) {}
                }
                """);
    }

    @Test
    void bindingFragmentsFollowTheirResolvedRoles() {
        configure("""
                <Label text="${String.format('Count: %d', model.count + Model.LIMIT)}"/>
                <Label text="$model.label('Title')"/>
                <Label text="$Model.current"/>
                """);
        assertColor("String.format", 0, 6, "FXML2_TYPE");
        assertColor("format", "FXML2_STATIC_METHOD_CALL");
        assertColor("'Count: %d'", "FXML2_STRING");
        assertColor("model.count", 0, 5, "FXML2_INSTANCE_FIELD");
        assertColor("count", "FXML2_PROPERTY_READ");
        assertColor("LIMIT", "FXML2_CONSTANT");
        assertColor("+", "FXML2_OPERATOR");
        assertColor("label(", 0, 5, "FXML2_METHOD_CALL");
        assertColor("current", "FXML2_STATIC_FIELD");
    }

    @ParameterizedTest
    @ValueSource(strings = {"$model.amount", "${model.amount}", "#{model.amount}", ">{model.amount}",
            "{fx:Evaluate model.amount}", "{fx:Observe model.amount}",
            "{fx:Push model.amount}", "{fx:Synchronize model.amount}"})
    void everyBindingNotationColorsItsPath(String expression) {
        configure("<Label text=\"" + expression + "\"/>");
        assertColor("model.amount", 0, 5, "FXML2_INSTANCE_FIELD");
        assertColor("amount", "FXML2_INSTANCE_FIELD");
    }

    @Test
    void selectorsTypesDepthAndEscapedOperatorsHaveExactSourceRanges() {
        configure("""
                <Label visible="${:parent&lt;SemanticView&gt;(1).visible &amp;&amp; model.amount &lt; 20}"/>
                """);
        assertColor(":parent", "FXML2_SELECTOR");
        assertColor("&lt;SemanticView", 4, 12, "FXML2_TYPE");
        assertColor("(1)", 1, 1, "FXML2_NUMBER");
        assertColor("&amp;&amp;", "FXML2_OPERATOR");
        assertColor("amount", "FXML2_INSTANCE_FIELD");
        assertColor("20", "FXML2_NUMBER");
    }

    @Test
    void typedLiteralsAndEventHandlersUseCodeColors() {
        configure("""
                <Label alignment="CENTER" prefWidth="1e2" visible="false" text="123, Model.LIMIT"/>
                <Button onAction="handleAction"/>
                """);
        assertColor("CENTER", "FXML2_CONSTANT");
        assertColor("1e2", "FXML2_NUMBER");
        assertColor("false", "FXML2_KEYWORD");
        assertColor("123, Model.LIMIT", "FXML2_STRING");
        assertColor("handleAction", "FXML2_METHOD_CALL");
    }

    @Test
    void propertyElementTextUsesTheSameRolesAsAttributes() {
        configure("""
                <Label><alignment>CENTER</alignment><prefWidth>42</prefWidth><text>$model.message</text></Label>
                """);
        assertColor("CENTER", "FXML2_CONSTANT");
        assertColor("42", "FXML2_NUMBER");
        assertColor("message", "FXML2_PROPERTY_READ");
        assertColor("<alignment", 1, 9, "FXML2_PROPERTY_ASSIGNMENT");
        assertColor("</alignment", 2, 9, "FXML2_PROPERTY_ASSIGNMENT");
    }

    @Test
    void nestedExtensionValuesKeepTheirOwnArgumentTypes() {
        getFixture().addClass("""
                package test;
                import javafx.beans.NamedArg;
                public class ValueSupplier {
                    public ValueSupplier(@NamedArg("value") Object value, @NamedArg("limit") double limit) {}
                }
                """);
        configure("""
                <Label text="{ValueSupplier value={ValueSupplier value=$model.count; limit=2.5}; limit=10}"/>
                """);
        assertColor("value=", 0, 5, "FXML2_PROPERTY_ASSIGNMENT");
        assertColor("limit=2.5", 0, 5, "FXML2_PROPERTY_ASSIGNMENT");
        assertColor("2.5", "FXML2_NUMBER");
        assertColor("count", "FXML2_PROPERTY_READ");
        assertColor("10}", 0, 2, "FXML2_NUMBER");
    }

    @Test
    void escapedExtensionsAndStringsRemainLiteral() {
        configure("""
                <Label text="\\{Model value=200}"/>
                <Label text="${'true, Model.LIMIT'}"/>
                """);
        assertColor("\\{", "FXML2_ESCAPE");
        assertColor("'true, Model.LIMIT'", "FXML2_STRING");
        assertFalse(colorSnapshot().stream().anyMatch(color -> color.key().equals("FXML2_CONSTANT")));
    }

    @Test
    void plainXmlDoesNotReceiveFxmlSemanticColors() {
        getFixture().configureByText("View.xml", "<Label text=\"${model.amount}\"/>");
        assertFalse(colorSnapshot().stream().anyMatch(color -> color.key().startsWith("FXML2_")));
    }

    @Test
    void javaInjectedMarkupReceivesSemanticColorsInTheHostEditor() {
        installComponentViewAnnotation();
        getFixture().configureByText("EmbeddedView.java", """
                package test;
                import org.jfxcore.markup.ComponentView;
                import javafx.scene.control.Label;
                @ComponentView("<Label prefWidth='42' alignment='CENTER'/>")
                public class EmbeddedView {}
                """);
        assertColor("42", "FXML2_NUMBER");
        assertColor("CENTER", "FXML2_CONSTANT");
        assertColor("prefWidth", "FXML2_PROPERTY_ASSIGNMENT");
    }

    @Test
    void kotlinInjectedMarkupUsesTheSameSemanticRoles() {
        installComponentViewAnnotation();
        getFixture().configureByText("EmbeddedView.kt", """
                package test
                import org.jfxcore.markup.ComponentView
                import javafx.scene.control.Label
                @ComponentView("<Label prefWidth='42' alignment='CENTER'/>")
                class EmbeddedView
                """);
        assertColor("42", "FXML2_NUMBER");
        assertColor("CENTER", "FXML2_CONSTANT");
        assertColor("prefWidth", "FXML2_PROPERTY_ASSIGNMENT");
        assertColor("<Label", 1, 5, "FXML2_TYPE");
    }

    @Test
    void customPrefixArgumentsUseTheMappedExtensionTypes() {
        getFixture().addClass("""
                package test;
                import javafx.beans.DefaultProperty;
                import javafx.beans.NamedArg;
                @DefaultProperty("key")
                public class WidthSetting {
                    public WidthSetting(@NamedArg("key") String key, @NamedArg("defaultValue") double value) {}
                }
                """);
        getFixture().configureByText("View.fxml", """
                <?import javafx.scene.control.Label?>
                <?prefix ~ = test.WidthSetting?>
                <Label xmlns="http://javafx.com/javafx" xmlns:fx="http://jfxcore.org/fxml/2.0"
                       prefWidth="~view.columns.title; defaultValue=200"/>
                """);
        assertColor("~view", 0, 1, "FXML2_PREFIX");
        assertColor("defaultValue", "FXML2_PROPERTY_ASSIGNMENT");
        assertColor("200", "FXML2_NUMBER");
        assertColor("view.columns.title", "FXML2_STRING");
    }

    @Test
    void namespaceAliasesKeepIntrinsicExpressionSemantics() {
        getFixture().configureByText("View.fxml", """
                <?import test.SemanticView?>
                <?import javafx.scene.control.Label?>
                <SemanticView xmlns="http://javafx.com/javafx" xmlns:x="http://jfxcore.org/fxml/2.0">
                    <Label text="{x:Observe source=model.message}"/>
                </SemanticView>
                """);
        assertColor("Observe", "FXML2_INTRINSIC");
        assertColor("source", "FXML2_PROPERTY_ASSIGNMENT");
        assertColor("message", "FXML2_PROPERTY_READ");
    }

    @Test
    void implicitConstructorArgumentsAndCollectionsColorEachItem() {
        configure("""
                <Label padding="1, 2, 3, 4"/>
                <Polygon points="10, 20, 30, 40"/>
                """);
        assertColor("1,", 0, 1, "FXML2_NUMBER");
        assertColor("4\"", 0, 1, "FXML2_NUMBER");
        assertColor("10", "FXML2_NUMBER");
        assertColor("40", "FXML2_NUMBER");
        assertColor(",", "FXML2_COMMA");
    }

    @Test
    void genericMethodAndConstructorCallsColorTheirIndividualNames() {
        getFixture().addClass("""
                package test;
                public class GenericModel {
                    public static <T> String format(T value) { return value.toString(); }
                }
                """);
        configure("""
                <Label text="$GenericModel.format&lt;String&gt;('value')"/>
                <Label userData="$Model('value')"/>
                """);
        assertColor("GenericModel.format", 0, 12, "FXML2_TYPE");
        assertColor("format", "FXML2_STATIC_METHOD_CALL");
        assertColor("&lt;String", 4, 6, "FXML2_TYPE");
        assertColor("$Model(", 1, 5, "FXML2_CONSTRUCTOR_CALL");
    }

    @Test
    void resourcesColorKeysPathsAndNamedArguments() {
        addResourceMarkupExtensions();
        getFixture().addFileToProject("messages.properties", "message.title=Title\n");
        getFixture().configureByText("View.fxml", """
                <?import javafx.scene.control.Label?>
                <?import org.jfxcore.markup.resource.StaticResource?>
                <Label xmlns="http://javafx.com/javafx" xmlns:fx="http://jfxcore.org/fxml/2.0"
                       text="{StaticResource message.title; formatArguments=Jane, Doe}"/>
                """);
        assertColor("message.title", "FXML2_RESOURCE_KEY");
        assertColor("formatArguments", "FXML2_PROPERTY_ASSIGNMENT");
        colors = null;
        getFixture().configureByText("View.fxml", """
                <?import javafx.scene.control.Label?>
                <?resource logo type="text/plain" content="logo"?>
                <Label xmlns="http://javafx.com/javafx" xmlns:fx="http://jfxcore.org/fxml/2.0" text="@logo"/>
                """);
        assertColor("@logo", 0, 1, "FXML2_PREFIX");
        assertColor("@logo", 1, 4, "FXML2_RESOURCE_PATH");
    }

    @Test
    void syntaxRolesRemainAvailableDuringIndexing() {
        configure("<Label visible=\"${:root.visible &amp;&amp; true}\"/>");
        DumbModeTestUtils.runInDumbModeSynchronously(getFixture().getProject(), () -> ReadAction.run(() -> {
            var spans = Fxml2SemanticAnalyzer.analyze((XmlFile)getFixture().getFile());
            assertTrue(spans.stream().anyMatch(span -> span.role() == Fxml2SemanticRole.SELECTOR));
            assertTrue(spans.stream().anyMatch(span -> span.role() == Fxml2SemanticRole.KEYWORD));
            assertTrue(spans.stream().anyMatch(span -> span.role() == Fxml2SemanticRole.OPERATOR));
        }));
    }

    @Test
    void codeColorsEraseTheUnderlyingXmlStringFormatting() {
        configure("<Label text=\"$model.message\"/>");
        String source = getFixture().getFile().getText();
        int begin = source.indexOf("message");
        assertTrue(getFixture().doHighlighting().stream().anyMatch(info ->
                info.getStartOffset() == begin && info.getEndOffset() == begin + "message".length()
                        && info.forcedTextAttributes == TextAttributes.ERASE_MARKER));
    }

    @Test
    void incompleteMarkupRetainsRecognizedSyntaxWithoutCrashing() {
        configure("<Label text=\"${model.amount +\"/>");
        assertColor("+", "FXML2_OPERATOR");
        assertColor("${", 0, 1, "FXML2_PREFIX");
    }

    @Test
    void attachedPropertiesAndQualifiedAssignmentsDistinguishTypesFromProperties() {
        configure("""
                <Label fx:id="targetLabel" VBox.margin="10"/>
                <Label padding="$targetLabel.(VBox.margin)"/>
                """);
        assertColor("VBox.margin", 0, 4, "FXML2_TYPE");
        assertColor("VBox.margin", 5, 6, "FXML2_PROPERTY_ASSIGNMENT");
        assertColor("(VBox.margin)", 1, 4, "FXML2_TYPE");
        assertColor("(VBox.margin)", 6, 6, "FXML2_PROPERTY_READ");
        assertColor("fx:id=\"targetLabel", 7, 11, "FXML2_INSTANCE_FIELD");
        assertColor("fx:id", 3, 2, "FXML2_INTRINSIC_ATTRIBUTE");
    }

    @Test
    void longFormBindingsColorSecondaryPathsAndLiteralKeywords() {
        configure("""
                <Label text="{fx:Observe source=String.format('Value', model.amount)}"/>
                <Label userData="${true || false || null === model}"/>
                """);
        assertColor("source=", 0, 6, "FXML2_PROPERTY_ASSIGNMENT");
        assertColor("format", "FXML2_STATIC_METHOD_CALL");
        assertColor("true", "FXML2_KEYWORD");
        assertColor("false", "FXML2_KEYWORD");
        assertColor("null", "FXML2_KEYWORD");
        assertColor("===", "FXML2_OPERATOR");
    }

    @Test
    void expressionArgumentsCanContainValueProducingExtensions() {
        getFixture().addClass("""
                package test;
                import javafx.beans.NamedArg;
                public class ArgumentValue {
                    public ArgumentValue(@NamedArg("amount") int amount) {}
                }
                """);
        configure("<Label text=\"${String.valueOf({ArgumentValue amount=2})}\"/>");
        assertColor("ArgumentValue", "FXML2_TYPE");
        assertColor("amount=", 0, 6, "FXML2_PROPERTY_ASSIGNMENT");
        assertColor("2}", 0, 1, "FXML2_NUMBER");
        assertColor("valueOf", "FXML2_STATIC_METHOD_CALL");
    }

    @Test
    void staleBackgroundResultsAreDiscardedAfterAnEdit() {
        configure("<Label prefWidth=\"10\"/>");
        var annotator = new Fxml2SemanticAnnotator();
        var input = ReadAction.compute(() -> annotator.collectInformation(getFixture().getFile()));
        assertNotNull(input);
        var original = annotator.doAnnotate(input);
        assertNotNull(original);
        var document = getFixture().getEditor().getDocument();
        EdtTestUtil.runInEdtAndWait(() -> {
            WriteCommandAction.runWriteCommandAction(getFixture().getProject(), () ->
                    document.setText(document.getText().replace("prefWidth=\"10\"", "prefWidth=\"200\"")));
            PsiDocumentManager.getInstance(getFixture().getProject()).commitAllDocuments();
        });
        assertNull(annotator.doAnnotate(input));
        assertColor("200", "FXML2_NUMBER");
    }

    private void configure(String content) {
        getFixture().configureByText("View.fxml", """
                <?import javafx.scene.control.*?>
                <?import javafx.scene.layout.*?>
                <?import javafx.scene.shape.*?>
                <?import test.*?>
                <SemanticView xmlns="http://javafx.com/javafx" xmlns:fx="http://jfxcore.org/fxml/2.0"
                              fx:subclass="test.SemanticView">
                """ + content + "</SemanticView>");
    }

    private List<Color> colorSnapshot() {
        if (colors == null) {
            colors = getFixture().doHighlighting().stream()
                    .filter(info -> info.forcedTextAttributesKey != null)
                    .map(info -> new Color(new TextRange(info.getStartOffset(), info.getEndOffset()),
                            info.forcedTextAttributesKey.getExternalName())).toList();
        }
        return colors;
    }

    private void assertColor(String token, String key) {
        assertColor(token, 0, token.length(), key);
    }

    private void assertColor(String context, int offset, int length, String key) {
        String source = getFixture().getFile().getText();
        int start = source.indexOf(context) + offset;
        assertTrue(start >= offset, "Missing test token: " + context);
        assertTrue(colorSnapshot().contains(new Color(new TextRange(start, start + length), key)),
                "Expected " + key + " for " + source.substring(start, start + length) + " at " + start + ".." + (start + length) + ": " + colors);
    }
}
