// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.openapi.application.ReadAction;
import org.jfxcore.fxml.lang.Fxml2ProcessingInstructionTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies completion of a processing-instruction target.
 *
 * <p>A target is offered where the language reads an instruction with that target, and nowhere
 * else: {@code <?resource ?>} is scoped to the whole document and may be written inside an
 * element, while {@code <?import ?>} and {@code <?prefix ?>} are read only from the document's
 * direct children and would be silently ignored inside one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class Fxml2ProcessingInstructionCompletionTest extends Fxml2TestBase {

    // -----------------------------------------------------------------------
    // Valid positions
    // -----------------------------------------------------------------------

    /** A partially typed target in the prolog completes to the instruction it names. */
    @Test
    void partialTargetInPrologIsCompleted() {
        assertEquals(List.of("resource"), completeInProlog("<?re<caret>"));
        assertEquals(List.of("import"), completeInProlog("<?im<caret>"));
        assertEquals(List.of("prefix"), completeInProlog("<?pre<caret>"));
    }

    /** With nothing typed after {@code <?}, every target the prolog reads is offered. */
    @Test
    void bareInstructionStartInPrologOffersEveryTarget() {
        assertEquals(List.of("import", "prefix", "resource"),
                completeInProlog("<?<caret>").stream().sorted().toList());
    }

    /** A target is also completed after the root element, which is still document level. */
    @Test
    void targetAfterTheRootElementIsCompleted() {
        configure("""
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"/>
                <?im<caret>
                """);

        assertEquals(List.of("import"), lookupStrings());
    }

    /** Inside an element only the resource declaration is read, so only it is offered. */
    @Test
    void insideAnElementOnlyTheResourceTargetIsOffered() {
        assertEquals(List.of("resource"), completeInsideElement("<?re<caret>"));
        assertEquals(List.of("resource"), completeInsideElement("<?<caret>"));
        assertTrue(completeInsideElement("<?im<caret>").isEmpty(),
                "an import inside an element would be ignored, so it is not offered");
        assertTrue(completeInsideElement("<?pre<caret>").isEmpty(),
                "a prefix inside an element would be ignored, so it is not offered");
    }

    /** Markup embedded in a {@code @ComponentView} annotation value completes the same way. */
    @Test
    void targetIsCompletedInEmbeddedMarkup() {
        getFixture().addClass("""
                package org.jfxcore.markup;
                import java.lang.annotation.*;
                @Target(ElementType.TYPE)
                @Retention(RetentionPolicy.SOURCE)
                public @interface ComponentView {
                    String value();
                }
                """);
        getFixture().configureByText("EmbeddedView.java", """
                import org.jfxcore.markup.ComponentView;

                @ComponentView(\"""
                        <?re<caret>
                        <BorderPane xmlns="http://javafx.com/javafx"
                                    xmlns:fx="http://jfxcore.org/fxml/2.0"/>
                        \""")
                public class EmbeddedView {
                }
                """);

        assertEquals(List.of("resource"), lookupStrings());
    }

    // -----------------------------------------------------------------------
    // Invalid positions
    // -----------------------------------------------------------------------

    /** The same two characters inside an attribute value are text, not an instruction start. */
    @Test
    void noCompletionInsideAnAttributeValue() {
        configure("""
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"
                            accessibleText="<?re<caret>"/>
                """);

        assertNoTargetOffered();
    }

    /** A comment is text as well. */
    @Test
    void noCompletionInsideAComment() {
        configure("""
                <!-- <?re<caret> -->
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"/>
                """);

        assertNoTargetOffered();
    }

    /** An instruction that is already open does not start another one in its data. */
    @Test
    void noCompletionInsideTheDataOfAnOpenInstruction() {
        configure("""
                <?prefix % = <?re<caret>?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"/>
                """);

        assertNoTargetOffered();
    }

    /** An XML document that is not FXML/2 keeps the platform's own completion. */
    @Test
    void noCompletionInAPlainXmlDocument() {
        getFixture().configureByText("plain.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?re<caret>
                <root/>
                """);

        assertNoTargetOffered();
    }

    // -----------------------------------------------------------------------
    // Insertion
    // -----------------------------------------------------------------------

    /** Selecting a target writes the whole skeleton and leaves the caret where the name goes. */
    @Test
    void insertionWritesTheInstructionSkeleton() {
        configure("""
                <?res<caret>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"/>
                """);

        getFixture().completeBasic();

        assertTrue(documentText().contains("<?resource ?>"), documentText());
        assertEquals(documentText().indexOf("<?resource ") + "<?resource ".length(),
                caretOffset(), "the caret waits where the resource name goes");
    }

    /** A closing {@code ?>} that is already written is reused rather than duplicated. */
    @Test
    void insertionReusesAnExistingInstructionEnd() {
        configure("""
                <?res<caret>?>
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"/>
                """);

        getFixture().completeBasic();

        assertTrue(documentText().contains("<?resource ?>"), documentText());
        assertFalse(documentText().contains("?>?>"), documentText());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private List<String> completeInProlog(String typed) {
        configure("""
                %s
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"/>
                """.formatted(typed));
        return lookupStrings();
    }

    private List<String> completeInsideElement(String typed) {
        configure("""
                <BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0">
                  %s
                </BorderPane>
                """.formatted(typed));
        return lookupStrings();
    }

    private void configure(String body) {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                %s""".formatted(body));
    }

    /**
     * Returns the targets the completion offers at the caret, empty when it offers none.
     *
     * <p>A completion with a single match inserts it instead of showing a popup, which is a hit
     * as much as a popup entry is: the inserted target is read back out of the document.
     */
    private List<String> lookupStrings() {
        LookupElement[] items = getFixture().completeBasic();
        if (items != null) {
            return Arrays.stream(items).map(LookupElement::getLookupString).toList();
        }

        String text = documentText();
        int caret = caretOffset();
        int start = text.lastIndexOf("<?", caret);
        if (start < 0) return List.of();

        int end = start + 2;
        while (end < text.length() && Character.isLetterOrDigit(text.charAt(end))) ++end;

        String inserted = text.substring(start + 2, end);
        return Fxml2ProcessingInstructionTarget.of(inserted) == null ? List.of() : List.of(inserted);
    }

    /** Asserts that no processing-instruction target is offered at the caret. */
    private void assertNoTargetOffered() {
        List<String> offered = lookupStrings();
        for (Fxml2ProcessingInstructionTarget target : Fxml2ProcessingInstructionTarget.values()) {
            assertFalse(offered.contains(target.targetName()),
                    "'" + target.targetName() + "' must not be offered here, got: " + offered);
        }
    }

    private String documentText() {
        return ReadAction.compute(() -> getFixture().getEditor().getDocument().getText());
    }

    private int caretOffset() {
        return ReadAction.compute(() -> getFixture().getEditor().getCaretModel().getOffset());
    }
}
