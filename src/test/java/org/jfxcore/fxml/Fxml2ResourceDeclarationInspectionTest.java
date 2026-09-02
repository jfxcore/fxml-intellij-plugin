// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import org.jfxcore.fxml.annotator.Fxml2DuplicateResourceInspection;
import org.jfxcore.fxml.annotator.Fxml2ProcessingInstructionPlacementInspection;
import org.jfxcore.fxml.annotator.Fxml2ResourceDeclarationInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

/**
 * Verifies that the diagnostics of a {@code <?resource ?>} declaration reach the editor.
 *
 * <p>The declaration grammar itself is covered exhaustively by
 * {@link Fxml2ResourceInstructionParserTest}; what is tested here is that the inspections read
 * the document, report on the right span, and stay quiet on valid declarations.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class Fxml2ResourceDeclarationInspectionTest extends Fxml2TestBase {

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(
                new Fxml2ResourceDeclarationInspection(),
                new Fxml2DuplicateResourceInspection(),
                new Fxml2ProcessingInstructionPlacementInspection());
    }

    /** A well-formed declaration is reported as valid, wherever in the document it appears. */
    @Test
    void validDeclarationsAreAccepted() {
        configure("""
                <?resource styles.css text/css:
                    .root { -fx-font-size: 1.1em; }
                ?>
                <?resource "dark theme.css" text/css;charset=UTF-8:.root { -fx-base: black; }?>
                """, """
                  <BorderPane>
                    <?resource nested.txt:declared inside an element?>
                  </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, true);
    }

    /** A name that is not a portable file name is reported on the name itself. */
    @Test
    void unportableNameIsReported() {
        configure("""
                <?resource "<error descr="Invalid resource name 'sub/dir.css'">sub/dir.css</error>":body?>
                """, "");
        getFixture().checkHighlighting(false, false, true);
    }

    /** A malformed media type is reported. */
    @Test
    void malformedMediaTypeIsReported() {
        configure("""
                <?resource styles.css <error descr="Invalid media type for resource 'styles.css'">text/</error>:body?>
                """, "");
        getFixture().checkHighlighting(false, false, true);
    }

    /** A charset the JVM does not know is reported on the parameter that names it. */
    @Test
    void unsupportedCharsetIsReported() {
        configure("""
                <?resource m.txt text/plain;<error descr="Unsupported charset 'x-nope' for resource 'm.txt'">charset=x-nope</error>:body?>
                """, "");
        getFixture().checkHighlighting(false, false, true);
    }

    /** Two declarations of the same resource are reported, comparing names ignoring case. */
    @Test
    void caseOnlyDuplicateIsReported() {
        configure("""
                <?resource Foo.txt:first?>
                <?resource <error descr="Duplicate resource declaration 'foo.txt'; a resource with this name is already declared at line 3, column 12">foo.txt</error>:second?>
                """, "");
        getFixture().checkHighlighting(false, false, true);
    }

    /** An import written inside an element is reported, because it is read only from the prolog. */
    @Test
    void importInsideAnElementIsReported() {
        configure("", """
                  <BorderPane>
                    <warning descr="<?import?> inside an element is ignored; it is read only from the document prolog"><?import javafx.scene.control.Button?></warning>
                  </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, true);
    }

    /** A resource declaration inside an element is not reported: it is scoped to the whole document. */
    @Test
    void resourceInsideAnElementIsAccepted() {
        configure("", """
                  <BorderPane>
                    <?resource nested.txt:body?>
                  </BorderPane>
                """);
        getFixture().checkHighlighting(false, false, true);
    }

    /**
     * Writes an FXML/2 document with {@code prolog} between the imports and the root element,
     * and {@code body} inside it.
     */
    private void configure(String prolog, String body) {
        getFixture().configureByText("TestView.fxml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <?import javafx.scene.layout.BorderPane?>
                %s<BorderPane xmlns="http://javafx.com/javafx"
                            xmlns:fx="http://jfxcore.org/fxml/2.0">
                %s</BorderPane>
                """.formatted(prolog, body));
    }
}
