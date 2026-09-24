// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml;

import com.intellij.application.options.CodeStyle;
import com.intellij.openapi.Disposable;
import com.intellij.lang.Language;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.codeStyle.modifier.CodeStyleSettingsModifier;
import com.intellij.psi.codeStyle.modifier.CodeStyleStatusBarUIContributor;
import com.intellij.psi.codeStyle.modifier.TransientCodeStyleSettings;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.lang.Fxml2EffectiveIndent;
import org.jfxcore.fxml.lang.Fxml2IndentStep;
import org.jfxcore.fxml.lang.Fxml2IndentSteps;
import org.jfxcore.fxml.resource.Fxml2ResourcePayloadLanguage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
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

    /** Undoes the registration of a directory rule after the test that registered it. */
    private Disposable ruleDisposable;

    @AfterEach
    void unregisterDirectoryRule() {
        if (ruleDisposable != null) {
            Disposer.dispose(ruleDisposable);
            ruleDisposable = null;
        }
    }

    /** The step a rule of the host directory contributes, distinct from every configured step. */
    private static final int DIRECTORY_RULE_STEP = 6;

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

    /**
     * A rule that applies to the directory a file lives in wins over the code style configured
     * for its language, which is how an {@code .editorconfig} section reaches a document.
     *
     * <p>The rule is contributed by a modifier registered for this test rather than by an
     * {@code .editorconfig} file, so that the assertion holds wherever the suite runs: what is
     * under test is that the steps are resolved through the modifiers that apply where the markup
     * lives, not which file a rule happens to come from.
     */
    @Test
    void aRuleForTheDirectoryWinsOverTheConfiguredStep() {
        registerModifier(jsonLanguage());
        VirtualFile contextFile = getFixture().addFileToProject("TestView.java", "class TestView {}").getVirtualFile();
        assertNotNull(contextFile, "the context file lives in the fixture file system");

        assertEquals(new Fxml2IndentStep(DIRECTORY_RULE_STEP),
                     ReadAction.compute(() -> Fxml2EffectiveIndent.ofPayload(
                             getFixture().getProject(), contextFile, Fxml2ResourcePayloadLanguage.JSON)),
                     "the payload step must come from the rule that applies where the markup lives");
    }

    /** The markup step is resolved the same way, through the rules of the host directory. */
    @Test
    void aRuleForTheDirectoryAlsoDecidesTheMarkupStep() {
        registerModifier(XMLLanguage.INSTANCE);
        VirtualFile contextFile = getFixture().addFileToProject("TestView.java", "class TestView {}").getVirtualFile();
        assertNotNull(contextFile, "the context file lives in the fixture file system");

        assertEquals(new Fxml2IndentStep(DIRECTORY_RULE_STEP),
                     ReadAction.compute(() ->
                             Fxml2EffectiveIndent.ofMarkup(getFixture().getProject(), contextFile)));
    }

    /** Registers a modifier that gives {@code language} the step of a directory rule. */
    @SuppressWarnings("UnstableApiUsage") // CodeStyleSettingsModifier is how a rule reaches a file.
    private void registerModifier(@NotNull Language language) {
        CodeStyleSettingsModifier modifier = new CodeStyleSettingsModifier() {
            @Override
            public boolean modifySettings(@NotNull TransientCodeStyleSettings settings, @NotNull PsiFile file) {
                CommonCodeStyleSettings.IndentOptions options =
                        settings.getCommonSettings(language).getIndentOptions();
                if (options == null) return false;
                options.INDENT_SIZE = DIRECTORY_RULE_STEP;
                return true;
            }

            @Override
            public CodeStyleStatusBarUIContributor getStatusBarUiContributor(
                    @NotNull TransientCodeStyleSettings settings) {
                return null;
            }

            @Override
            public String getName() {
                return "Test Directory Rule";
            }
        };

        ruleDisposable = Disposer.newDisposable("fxml2.test.directoryRule");
        CodeStyleSettingsModifier.EP_NAME.getPoint().registerExtension(modifier, ruleDisposable);
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
