// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileTypes.PlainTextLanguage;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import org.jfxcore.fxml.lang.Fxml2ResourceProcessingInstruction;
import org.jfxcore.fxml.resource.Fxml2ResourceModel;
import org.jfxcore.fxml.resource.Fxml2ResourcePayloadLanguage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the payload of a {@code <?resource ?>} declaration in a standalone FXML/2 document
 * is an editable fragment of the language its media type names.
 *
 * <p>Implementation under test: {@link Fxml2ResourceProcessingInstruction}, the injection host the
 * FXML/2 parser definition substitutes for the standard XML processing instruction, and
 * {@link org.jfxcore.fxml.lang.Fxml2ResourceInjector}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class Fxml2ResourceInjectionTest extends Fxml2TestBase {

    @BeforeAll
    void addTestClasses() {
        installComponentViewAnnotation();
    }

    /** A resource declaration with a payload is an injection host. */
    @Test
    void resourceDeclarationIsAnInjectionHost() {
        configure("<?resource styles.css text/css:.root { -fx-base: black; }?>");

        Fxml2ResourceProcessingInstruction host = ReadAction.compute(this::findResourceInstruction);
        assertNotNull(host, "the resource declaration is substituted with an injection host");
        assertTrue(ReadAction.compute(host::isValidHost));
    }

    /** The injected fragment is the raw payload, exactly as written. */
    @ParameterizedTest
    @EnumSource(Fxml2DocumentForm.class)
    void injectedFragmentIsTheRawPayload(Fxml2DocumentForm form) {
        configure(form, "<?resource styles.css text/css:.root { -fx-base: black; }?>", "");

        assertEquals(".root { -fx-base: black; }", ReadAction.compute(() -> form.payloadFile(getFixture()).getText()));
    }

    /** A multi-line payload retains its layout as generated virtual-file text. */
    @Test
    void multilinePayloadIsInjectedVerbatim() {
        configure("""
                <?resource styles.css text/css:
                    .root {
                        -fx-font-size: 1.1em;
                    }
                ?>""");

        assertEquals("""

                    .root {
                        -fx-font-size: 1.1em;
                    }
                """, injectedText());
    }

    /** Complete payload lines include their indentation and terminating line break. */
    @Test
    void multilineInjectionRangeCoversCompleteResourceLines() {
        configure("""
                <?resource styles.css text/css:%s
                    .root {
                        -fx-font-size: 1.1em;
                    }
                  ?>""".formatted("   "));

        ReadAction.run(() -> {
            Fxml2ResourceProcessingInstruction host = findResourceInstruction();
            assertNotNull(host);
            List<Pair<PsiElement, TextRange>> injected =
                    InjectedLanguageManager.getInstance(host.getProject()).getInjectedPsiFiles(host);
            assertNotNull(injected);
            assertEquals(1, injected.size());

            TextRange range = injected.getFirst().second;
            assertEquals("""
                    .root {
                        -fx-font-size: 1.1em;
                    }
                """, range.substring(host.getText()));
        });
    }

    /** A resource sharing both boundary lines excludes adjacent declaration whitespace. */
    @Test
    void sameLineInjectionBoundariesExcludeDeclarationWhitespace() {
        configure("""
                <?resource styles.css text/css:   .root {
                    -fx-font-size: 1.1em;
                }   ?>""");

        assertEquals("""
                .root {
                    -fx-font-size: 1.1em;
                }""", injectionRangeText());
    }

    /**
     * The media type picks the language.  CSS is not bundled with IntelliJ IDEA Community, so the
     * assertion is that the fragment is CSS where CSS exists and plain text where it does not,
     * which is exactly the degradation the feature promises.
     */
    @ParameterizedTest
    @EnumSource(Fxml2DocumentForm.class)
    void mediaTypeSelectsTheInjectedLanguage(Fxml2DocumentForm form) {
        configure(form, "<?resource data.json application/json:{}?>", "");

        assertSame(Fxml2ResourcePayloadLanguage.JSON.languageOrPlainText(),
                ReadAction.compute(() -> form.payloadFile(getFixture()).getLanguage()));
    }

    /** With no media type, the resource name's extension selects the language. */
    @ParameterizedTest
    @EnumSource(Fxml2DocumentForm.class)
    void extensionSelectsTheLanguageWhenTheMediaTypeIsOmitted(Fxml2DocumentForm form) {
        configure(form, "<?resource data.json:{}?>", "");

        assertSame(Fxml2ResourcePayloadLanguage.JSON.languageOrPlainText(),
                ReadAction.compute(() -> form.payloadFile(getFixture()).getLanguage()));
    }

    /** An unmapped media type and an unmapped extension both fall back to plain text. */
    @ParameterizedTest
    @EnumSource(Fxml2DocumentForm.class)
    void unmappedPayloadFallsBackToPlainText(Fxml2DocumentForm form) {
        configure(form, "<?resource notes.unknown application/x-unknown:body?>", "");

        assertSame(PlainTextLanguage.INSTANCE,
                ReadAction.compute(() -> form.payloadFile(getFixture()).getLanguage()));
    }

    /** Each declaration of a document gets its own fragment. */
    @Test
    void everyDeclarationIsInjectedSeparately() {
        configure("""
                <?resource a.json application/json:{"a": 1}?>
                <?resource b.json application/json:{"b": 2}?>""");

        assertEquals(List.of("{\"a\": 1}", "{\"b\": 2}"), ReadAction.compute(() ->
                allResourceInstructions().stream().map(Fxml2ResourceInjectionTest::injectedTextOf).toList()));
    }

    /** An import instruction is not a host: there is nothing in it to inject. */
    @Test
    void importInstructionIsNotAnInjectionHost() {
        configure("");

        assertTrue(ReadAction.compute(() -> allProcessingInstructions().stream()
                .filter(instruction -> instruction.getText().startsWith("<?import"))
                .noneMatch(PsiLanguageInjectionHost::isValidHost)));
    }

    /** A declaration that is still being typed has no content separator, and hosts nothing. */
    @Test
    void declarationWithoutAContentSeparatorIsNotAHost() {
        configure("<?resource styles.css text/css?>");

        Fxml2ResourceProcessingInstruction host = ReadAction.compute(this::findResourceInstruction);
        assertNotNull(host);
        assertFalse(ReadAction.compute(host::isValidHost));
    }

    /** Replacing the fragment text rebuilds the declaration around the new payload. */
    @Test
    void updateTextRoundTripsThroughTheDeclaration() {
        configure("<?resource styles.css text/css:.root { -fx-base: black; }?>");

        String replacement = "<?resource styles.css text/css:.root { -fx-base: white; }?>";
        WriteCommandAction.runWriteCommandAction(getFixture().getProject(), () -> {
            Fxml2ResourceProcessingInstruction host = findResourceInstruction();
            assertNotNull(host);
            assertEquals(replacement, host.updateText(replacement).getText());
        });

        assertTrue(ReadAction.compute(() -> getFixture().getFile().getText().contains(replacement)),
                "the document carries the replaced declaration");
    }

    @ParameterizedTest
    @EnumSource(value = Fxml2DocumentForm.class, names = {"JAVA", "KOTLIN"})
    void embeddedResourceProducesMarkupAndPayloadFragments(Fxml2DocumentForm form) {
        configure(form, "<?resource styles.css text/css:.root {}?>\n", "");

        assertEquals(2, injectedFileCount(form));
    }

    @ParameterizedTest
    @EnumSource(value = Fxml2DocumentForm.class, names = {"JAVA", "KOTLIN"})
    void embeddedMarkupCarriesTheDeclarationWithoutItsPayload(Fxml2DocumentForm form) {
        configure(form, "<?resource styles.css text/css:.root { -fx-font-size: 1.1em; }?>\n", "");

        String markup = ReadAction.compute(() -> form.markupFile(getFixture()).getText());
        assertTrue(markup.contains("<?resource styles.css text/css:?>"), markup);
        assertFalse(markup.contains("-fx-font-size"), markup);
    }

    @ParameterizedTest
    @EnumSource(value = Fxml2DocumentForm.class, names = {"JAVA", "KOTLIN"})
    void embeddedMultilinePayloadRangeCoversCompleteLines(Fxml2DocumentForm form) {
        configure(form, """
                <?resource data.json application/json:%s
                    {
                        "key": 1
                    }
                  ?>
                """.formatted("   "), "");

        Pair<PsiElement, TextRange> payload = payloadFragment(form);
        ReadAction.run(() -> assertEquals(
                "        {\n            \"key\": 1\n        }\n",
                payload.second.substring(form.host(getFixture()).getText())));
    }

    @ParameterizedTest
    @EnumSource(value = Fxml2DocumentForm.class, names = {"JAVA", "KOTLIN"})
    void embeddedDeclarationsProduceNonOverlappingFragments(Fxml2DocumentForm form) {
        configure(form, """
                <?resource a.json application/json:{"a": 1}?>
                <?resource b.json application/json:{"b": 2}?>
                """, "");

        List<TextRange> ranges = ReadAction.compute(() -> form.injectedFragments(getFixture()).stream()
                .map(fragment -> fragment.second)
                .sorted(Comparator.comparingInt(TextRange::getStartOffset))
                .toList());
        assertEquals(3, injectedFileCount(form));
        for (int index = 1; index < ranges.size(); ++index) {
            assertTrue(ranges.get(index - 1).getEndOffset() <= ranges.get(index).getStartOffset(), ranges.toString());
        }
    }

    @ParameterizedTest
    @EnumSource(value = Fxml2DocumentForm.class, names = {"JAVA", "KOTLIN"})
    void resourceInsideEmbeddedElementIsCarvedOut(Fxml2DocumentForm form) {
        configure(form, "", "<?resource nested.json application/json:{\"nested\": true}?>\n");

        assertEquals("{\"nested\": true}", ReadAction.compute(() -> form.payloadFile(getFixture()).getText()));
    }

    @ParameterizedTest
    @EnumSource(value = Fxml2DocumentForm.class, names = {"JAVA", "KOTLIN"})
    void malformedEmbeddedDeclarationLeavesOneMarkupFragment(Fxml2DocumentForm form) {
        configure(form, "<?resource styles.css text/css?>\n", "");

        assertEquals(1, injectedFileCount(form));
    }

    @ParameterizedTest
    @EnumSource(value = Fxml2DocumentForm.class, names = {"JAVA", "KOTLIN"})
    void embeddedMarkupWithoutResourcesIsOneFragment(Fxml2DocumentForm form) {
        configure(form, "", "");

        assertEquals(1, injectedFileCount(form));
    }

    @ParameterizedTest
    @EnumSource(value = Fxml2DocumentForm.class, names = {"JAVA", "KOTLIN"})
    void embeddedResourceModelReadsPayloadFromHost(Fxml2DocumentForm form) {
        configure(form, "<?resource styles.css text/css:.root { -fx-font-size: 1.1em; }?>\n", "");

        var entries = ReadAction.compute(() -> Fxml2ResourceModel.of(form.markupFile(getFixture())).entries());
        assertEquals(1, entries.size());
        assertEquals("styles.css", entries.getFirst().name().value());
        assertEquals(".root { -fx-font-size: 1.1em; }", entries.getFirst().declaration().content());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Writes an FXML/2 document whose prolog contains {@code declarations}. */
    private void configure(String declarations) {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                %s
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"/>
                """.formatted(declarations));
    }

    private void configure(Fxml2DocumentForm form, String declarations, String body) {
        form.configure(getFixture(), declarations, body);
    }

    private String injectedText() {
        return ReadAction.compute(() -> {
            Fxml2ResourceProcessingInstruction host = findResourceInstruction();
            assertNotNull(host);
            return injectedTextOf(host);
        });
    }

    private String injectionRangeText() {
        return ReadAction.compute(() -> {
            Fxml2ResourceProcessingInstruction host = findResourceInstruction();
            assertNotNull(host);
            List<Pair<PsiElement, TextRange>> injected =
                    InjectedLanguageManager.getInstance(host.getProject()).getInjectedPsiFiles(host);
            assertNotNull(injected);
            assertEquals(1, injected.size());
            return injected.getFirst().second.substring(host.getText());
        });
    }

    private static String injectedTextOf(Fxml2ResourceProcessingInstruction host) {
        return injectedFileOf(host).getText();
    }

    private static PsiFile injectedFileOf(Fxml2ResourceProcessingInstruction host) {
        List<Pair<PsiElement, TextRange>> injected =
                InjectedLanguageManager.getInstance(host.getProject()).getInjectedPsiFiles(host);

        assertNotNull(injected, "the payload is injected");
        assertEquals(1, injected.size(), "one fragment per declaration");
        return (PsiFile)injected.getFirst().first;
    }

    private Pair<PsiElement, TextRange> payloadFragment(Fxml2DocumentForm form) {
        return ReadAction.compute(() -> form.injectedFragments(getFixture()).stream()
                .filter(fragment -> !(fragment.first instanceof XmlFile))
                .findFirst()
                .orElseThrow());
    }

    private long injectedFileCount(Fxml2DocumentForm form) {
        return ReadAction.compute(() -> form.injectedFragments(getFixture()).stream()
                .map(fragment -> fragment.first)
                .distinct()
                .count());
    }

    private Fxml2ResourceProcessingInstruction findResourceInstruction() {
        return allResourceInstructions().stream().findFirst().orElse(null);
    }

    private List<Fxml2ResourceProcessingInstruction> allResourceInstructions() {
        return allProcessingInstructions().stream()
                .filter(instruction -> instruction.getText().startsWith("<?resource"))
                .toList();
    }

    private List<Fxml2ResourceProcessingInstruction> allProcessingInstructions() {
        PsiFile file = getFixture().getFile();
        assertInstanceOf(XmlFile.class, file);

        return List.copyOf(PsiTreeUtil.findChildrenOfType(file, Fxml2ResourceProcessingInstruction.class));
    }
}
