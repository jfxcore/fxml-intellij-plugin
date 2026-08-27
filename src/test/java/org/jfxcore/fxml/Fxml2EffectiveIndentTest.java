// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.application.options.CodeStyle;
import com.intellij.lang.Language;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import org.jfxcore.fxml.lang.Fxml2EffectiveIndent;
import org.jfxcore.fxml.lang.Fxml2IndentStep;
import org.jfxcore.fxml.lang.Fxml2IndentSteps;
import org.jfxcore.fxml.resource.Fxml2ResourcePayloadLanguage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies which indentation steps apply to an FXML/2 document.
 *
 * <p>Markup and each payload language nest in steps of their own, and a document is resolved for
 * all of them at once so that the answer can be taken before a reformat installs code style
 * settings of its own.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class Fxml2EffectiveIndentTest extends Fxml2TestBase {

    @BeforeEach
    void useDistinctSteps() {
        setIndentSize(XMLLanguage.INSTANCE, 2);
        setIndentSize(jsonLanguage(), 4);
    }

    /** Markup nests in the step configured for it. */
    @Test
    void markupStepIsTheConfiguredMarkupStep() {
        assertEquals(new Fxml2IndentStep(2),
                     Fxml2EffectiveIndent.ofMarkup(getFixture().getProject(), null));
    }

    /** A payload nests in the step configured for the language its media type names. */
    @Test
    void payloadStepIsTheConfiguredStepOfItsLanguage() {
        assertEquals(new Fxml2IndentStep(4),
                     Fxml2EffectiveIndent.ofPayload(getFixture().getProject(), null,
                                                    Fxml2ResourcePayloadLanguage.JSON));
    }

    /** A payload with no language of its own is written on markup lines, so it nests as markup. */
    @Test
    void plainTextPayloadNestsAsMarkup() {
        assertEquals(new Fxml2IndentStep(2),
                     Fxml2EffectiveIndent.ofPayload(getFixture().getProject(), null,
                                                    Fxml2ResourcePayloadLanguage.PLAIN_TEXT));
    }

    /** A document is resolved for the markup and for every payload language it declares. */
    @Test
    void documentStepsCoverTheLanguagesItDeclares() {
        Fxml2IndentSteps steps = Fxml2EffectiveIndent.stepsFor(getFixture().getProject(), null, """
                <?resource data.json application/json:
                {"a":1}
                ?>
                <?resource greeting.txt:Hello?>
                """);

        assertEquals(new Fxml2IndentStep(2), steps.markup());
        assertEquals(new Fxml2IndentStep(4), steps.payload(Fxml2ResourcePayloadLanguage.JSON));
        assertEquals(new Fxml2IndentStep(2), steps.payload(Fxml2ResourcePayloadLanguage.PLAIN_TEXT));
    }

    /** A language the document declares no resource in falls back to the markup step. */
    @Test
    void unresolvedLanguageFallsBackToTheMarkupStep() {
        Fxml2IndentSteps steps = Fxml2EffectiveIndent.stepsFor(getFixture().getProject(), null, "<BorderPane/>");

        assertEquals(new Fxml2IndentStep(2), steps.payload(Fxml2ResourcePayloadLanguage.JSON));
    }

    private static Language jsonLanguage() {
        Language json = Language.findLanguageByID("JSON");
        assertNotNull(json, "JSON is bundled with every IDE the plugin runs in");
        return json;
    }

    private void setIndentSize(Language language, int indentSize) {
        CodeStyleSettings settings = CodeStyle.getSettings(getFixture().getProject());
        var options = settings.getCommonSettings(language).getIndentOptions();
        assertNotNull(options, language.getID() + " has indent options");
        options.INDENT_SIZE = indentSize;
    }
}
