// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.FoldRegion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the payload of a multi-line {@code <?resource ?>} declaration can be folded away.
 *
 * <p>Implementation under test: {@link org.jfxcore.fxml.lang.Fxml2ResourceFoldingBuilder}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class Fxml2ResourceFoldingTest extends Fxml2TestBase {

    /** A multi-line payload gets a fold region covering exactly the payload. */
    @Test
    void multilinePayloadIsFoldable() {
        configure("""
                <?resource styles.css text/css:
                    .root {
                        -fx-font-size: 1.1em;
                    }
                ?>
                """);

        List<String> folded = foldedTexts();
        assertEquals(1, folded.size(), "one fold region for the payload");
        assertTrue(folded.getFirst().contains("-fx-font-size"), "the payload folds: " + folded);
        assertTrue(folded.getFirst().startsWith("\n"), "the fold starts after the colon: " + folded);
    }

    /** A same-line declaration is short enough to leave alone. */
    @Test
    void sameLinePayloadIsNotFoldable() {
        configure("<?resource styles.css text/css:.root { -fx-base: black; }?>\n");

        assertEquals(List.of(), foldedTexts());
    }

    /** Every multi-line declaration of a document gets its own fold region. */
    @Test
    void everyMultilineDeclarationIsFoldable() {
        configure("""
                <?resource a.css text/css:
                    .a {}
                ?>
                <?resource b.css text/css:
                    .b {}
                ?>
                """);

        assertEquals(2, foldedTexts().size());
    }

    /** Fold regions start collapsed only when the user collapses them. */
    @Test
    void payloadIsNotCollapsedByDefault() {
        configure("""
                <?resource styles.css text/css:
                    .root {}
                ?>
                """);

        FoldRegion[] regions = regions();
        assertTrue(ReadAction.compute(() -> Arrays.stream(regions).allMatch(FoldRegion::isExpanded)),
                "the payload is expanded when the document is opened");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void configure(String prolog) {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                %s<BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0"/>
                """.formatted(prolog));
    }

    /** Returns the text each fold region of the document covers. */
    private List<String> foldedTexts() {
        FoldRegion[] regions = regions();
        return ReadAction.compute(() -> {
            String text = getFixture().getEditor().getDocument().getText();
            return Arrays.stream(regions)
                    .map(region -> text.substring(region.getStartOffset(), region.getEndOffset()))
                    .toList();
        });
    }

    /**
     * Returns the document's fold regions, after running highlighting so that the folding builder
     * has been asked for them.  Highlighting must not run inside a read action of its own.
     */
    private FoldRegion[] regions() {
        getFixture().doHighlighting();
        return ReadAction.compute(() -> getFixture().getEditor().getFoldingModel().getAllFoldRegions());
    }
}
