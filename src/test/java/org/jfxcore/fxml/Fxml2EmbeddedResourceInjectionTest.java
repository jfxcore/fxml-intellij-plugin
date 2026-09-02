// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import org.jfxcore.fxml.resource.Fxml2ResourceModel;
import org.jfxcore.fxml.resource.Fxml2ResourcePayloadLanguage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a {@code <?resource ?>} declaration works in markup embedded in a
 * {@code @ComponentView} annotation value.
 *
 * <p>Markup embedded in an annotation value is itself an injected fragment, so a resource
 * declaration inside it cannot host a nested injection.  The injector therefore carves the payload
 * out of the markup fragment and injects it separately, which is what these tests check: the XML
 * the parser sees has an empty payload, the payload is its own fragment in its own language, and
 * the two do not overlap.
 *
 * <p>Implementation under test: {@link org.jfxcore.fxml.lang.Fxml2ResourceInjectionPlan} and the
 * markup annotation injector.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class Fxml2EmbeddedResourceInjectionTest extends Fxml2TestBase {

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

    /** One resource declaration produces two fragments: the markup and the payload. */
    @Test
    void resourceDeclarationProducesTwoFragments() {
        configure("""
                <?resource styles.css text/css:
                    .root { -fx-font-size: 1.1em; }
                ?>
                <BorderPane stylesheets="@styles.css"/>
                """);

        assertEquals(2, injectedFileCount(), "one markup fragment and one payload fragment");
    }

    /** The XML the parser sees carries the declaration with an empty payload. */
    @Test
    void markupFragmentCarriesTheDeclarationWithAnEmptyPayload() {
        configure("""
                <?resource styles.css text/css:
                    .root { -fx-font-size: 1.1em; }
                ?>
                <BorderPane/>
                """);

        String markup = markupFragment().getText();
        assertTrue(markup.contains("<?resource styles.css text/css:") && markup.contains("?>"),
                "the payload is carved out of the markup fragment, leaving a well-formed instruction: " + markup);
        assertFalse(markup.contains("-fx-font-size"), "the payload text is not part of the markup fragment");
    }

    /** The payload fragment is the raw payload, in the language its media type names. */
    @Test
    void payloadFragmentIsInjectedInItsOwnLanguage() {
        configure("""
                <?resource data.json application/json:{"key": 1}?>
                <BorderPane/>
                """);

        PsiFile payload = payloadFragment();
        assertEquals("{\"key\": 1}", payload.getText());
        assertSame(Fxml2ResourcePayloadLanguage.JSON.languageOrPlainText(), payload.getLanguage());
    }

    /** Complete payload lines belong to the payload fragment, including their layout whitespace. */
    @Test
    void multilinePayloadFragmentCoversCompleteResourceLines() {
        configure("""
                <?resource data.json application/json:%s
                    {
                        "key": 1
                    }
                  ?>
                <BorderPane/>
                """.formatted("   "));

        Pair<PsiElement, TextRange> payload = ReadAction.compute(() -> injectedFragments().stream()
                .filter(fragment -> !(fragment.getFirst() instanceof XmlFile))
                .findFirst()
                .orElseThrow());
        ReadAction.run(() -> {
            PsiLanguageInjectionHost host = findHost();
            assertNotNull(host);
            assertEquals("            {\n                \"key\": 1\n            }\n",
                    payload.getSecond().substring(host.getText()));
        });
    }

    /** Every declaration gets its own payload fragment, and no two fragments overlap. */
    @Test
    void severalDeclarationsProduceSeveralNonOverlappingFragments() {
        configure("""
                <?resource a.json application/json:{"a": 1}?>
                <?resource b.json application/json:{"b": 2}?>
                <BorderPane/>
                """);

        List<TextRange> ranges = ReadAction.compute(() -> injectedFragments().stream()
                .map(fragment -> fragment.getSecond())
                .sorted(Comparator.comparingInt(TextRange::getStartOffset))
                .toList());

        assertEquals(3, injectedFileCount(), "one markup fragment and two payload fragments");
        for (int i = 1; i < ranges.size(); ++i) {
            assertTrue(ranges.get(i - 1).getEndOffset() <= ranges.get(i).getStartOffset(),
                    "fragments do not overlap: " + ranges);
        }
    }

    /** A resource declared inside an element is carved out just as one in the prolog is. */
    @Test
    void declarationInsideAnElementIsAlsoCarvedOut() {
        configure("""
                <BorderPane>
                    <?resource nested.json application/json:{"nested": true}?>
                </BorderPane>
                """);

        assertEquals("{\"nested\": true}", payloadFragment().getText());
    }

    /** A declaration with no content separator leaves the markup as one fragment. */
    @Test
    void malformedDeclarationFallsBackToASingleFragment() {
        configure("""
                <?resource styles.css text/css?>
                <BorderPane/>
                """);

        assertEquals(1, injectedFileCount(), "the XML view of the markup is left intact");
    }

    /** Markup without resource declarations is injected exactly as it always was. */
    @Test
    void markupWithoutResourcesIsASingleFragment() {
        configure("<BorderPane/>\n");

        assertEquals(1, injectedFileCount());
    }

    /** The resource model reads the declaration through the host, not through the carved-out XML. */
    @Test
    void resourceModelReadsThePayloadFromTheHost() {
        configure("""
                <?resource styles.css text/css:
                    .root { -fx-font-size: 1.1em; }
                ?>
                <BorderPane/>
                """);

        var entries = ReadAction.compute(() -> Fxml2ResourceModel.of((XmlFile)markupFragment()).entries());

        assertEquals(1, entries.size());
        assertEquals("styles.css", entries.getFirst().name().value());
        assertEquals(".root { -fx-font-size: 1.1em; }", entries.getFirst().declaration().content());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Writes a Java class whose {@code @ComponentView} annotation holds {@code markup}. */
    private void configure(String markup) {
        getFixture().configureByText("MainView.java", """
                import org.jfxcore.markup.ComponentView;

                @ComponentView(\"""
                %s\""")
                public class MainView {
                }
                """.formatted(markup.indent(8)));
    }

    /**
     * Returns the number of distinct injected files.
     *
     * <p>The platform reports one entry per shred rather than per file, and the markup fragment is
     * made of several shreds once a payload has been carved out of it, so counting entries would
     * count the markup fragment more than once.
     */
    private long injectedFileCount() {
        return ReadAction.compute(() -> injectedFragments().stream()
                .map(fragment -> fragment.getFirst())
                .distinct()
                .count());
    }

    private PsiFile markupFragment() {
        return ReadAction.compute(() -> injectedFragments().stream()
                .map(fragment -> (PsiFile)fragment.getFirst())
                .filter(XmlFile.class::isInstance)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no markup fragment was injected")));
    }

    private PsiFile payloadFragment() {
        return ReadAction.compute(() -> injectedFragments().stream()
                .map(fragment -> (PsiFile)fragment.getFirst())
                .filter(fragment -> !(fragment instanceof XmlFile))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no payload fragment was injected")));
    }

    private List<Pair<PsiElement, TextRange>> injectedFragments() {
        return ReadAction.compute(() -> {
            PsiLanguageInjectionHost host = findHost();
            assertNotNull(host, "the annotation value is an injection host");

            List<Pair<PsiElement, TextRange>> injected =
                    InjectedLanguageManager.getInstance(host.getProject()).getInjectedPsiFiles(host);
            assertNotNull(injected, "markup is injected into the annotation value");
            return injected;
        });
    }

    private PsiLanguageInjectionHost findHost() {
        return PsiTreeUtil.findChildrenOfType(getFixture().getFile(), PsiLiteralExpression.class).stream()
                .map(PsiLanguageInjectionHost.class::cast)
                .filter(PsiLanguageInjectionHost::isValidHost)
                .findFirst()
                .orElse(null);
    }
}
