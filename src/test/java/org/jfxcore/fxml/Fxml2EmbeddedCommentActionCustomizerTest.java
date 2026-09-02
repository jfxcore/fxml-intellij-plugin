package org.jfxcore.fxml;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.LoggedErrorProcessor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.lang.Fxml2EmbeddedCommentActionCustomizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that installing and removing the FXML-aware comment actions is quiet.
 *
 * <p>An action that has been registered once carries the shortcut set the action manager gave it,
 * and registering it a second time is reported as a shortcut change made outside the keymap.  The
 * plugin restores the platform's comment actions when it is unloaded, which is exactly such a
 * second registration, so the restore has to hand the action back without a shortcut set of its
 * own and let the action manager install the keymap-backed one again.
 *
 * <p>Implementation under test: {@link Fxml2EmbeddedCommentActionCustomizer}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class Fxml2EmbeddedCommentActionCustomizerTest extends Fxml2TestBase {

    /** Installing the overrides and removing them again logs nothing and restores the actions. */
    @Test
    void installingAndRemovingTheOverridesIsQuiet() {
        ActionManager actionManager = ActionManager.getInstance();
        List<String> warnings = new ArrayList<>();

        AnAction lineCommentBefore = actionManager.getAction(IdeActions.ACTION_COMMENT_LINE);
        AnAction blockCommentBefore = actionManager.getAction(IdeActions.ACTION_COMMENT_BLOCK);
        assertNotNull(lineCommentBefore);
        assertNotNull(blockCommentBefore);

        LoggedErrorProcessor.executeWith(new LoggedErrorProcessor() {
            @Override
            public boolean processWarn(@NotNull String category, @NotNull String message, @Nullable Throwable t) {
                warnings.add(message);
                return false;
            }
        }, () -> EdtTestUtil.runInEdtAndWait(() -> {
            Fxml2EmbeddedCommentActionCustomizer customizer = new Fxml2EmbeddedCommentActionCustomizer();
            customizer.registerActions(actionManager);
            customizer.unregisterActions(actionManager);
        }));

        assertTrue(warnings.stream().noneMatch(warning -> warning.contains("ShortcutSet")),
                "restoring a replaced action is not a keymap change: " + warnings);
        assertSame(lineCommentBefore, actionManager.getAction(IdeActions.ACTION_COMMENT_LINE),
                "the replaced line comment action is restored");
        assertSame(blockCommentBefore, actionManager.getAction(IdeActions.ACTION_COMMENT_BLOCK),
                "the replaced block comment action is restored");
        assertTrue(lineCommentBefore.getShortcutSet().getShortcuts().length > 0,
                "the restored action still answers to its keymap shortcut");
    }
}
