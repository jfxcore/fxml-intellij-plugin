package org.jfxcore.fxml.lang;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.actionSystem.impl.DynamicActionConfigurationCustomizer;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Installs the FXML-aware comment actions of {@link Fxml2EmbeddedCommentHandlers} in place of
 * the platform's "Comment with Line Comment" and "Comment with Block Comment" actions.
 *
 * <p>Both actions keep their platform action ID, so all keymap bindings, menu entries and
 * {@code performEditorAction} call sites continue to reach them; only the implementation
 * behind the ID changes.  The replaced platform actions are remembered and restored when
 * this extension is removed, which keeps the plugin dynamically loadable and unloadable.
 *
 * <p>Registering the replacement here rather than through an {@code overrides="true"} action
 * declaration keeps {@code plugin.xml} free of an attribute that the platform only accepts
 * from internal-mode IDEs, while producing the same runtime registration.
 */
public final class Fxml2EmbeddedCommentActionCustomizer implements DynamicActionConfigurationCustomizer {

    /**
     * A platform comment action and the FXML-aware action that takes its place.
     */
    private enum CommentActionOverride {

        LINE_COMMENT(IdeActions.ACTION_COMMENT_LINE,
                     Fxml2EmbeddedCommentHandlers.LineCommentAction::new),

        BLOCK_COMMENT(IdeActions.ACTION_COMMENT_BLOCK,
                      Fxml2EmbeddedCommentHandlers.BlockCommentAction::new);

        private final String actionId;
        private final Supplier<@NotNull AnAction> overridingActionFactory;

        CommentActionOverride(@NotNull String actionId,
                              @NotNull Supplier<@NotNull AnAction> overridingActionFactory) {
            this.actionId = actionId;
            this.overridingActionFactory = overridingActionFactory;
        }

        @NotNull String actionId() {
            return actionId;
        }

        @NotNull AnAction createOverridingAction() {
            return overridingActionFactory.get();
        }
    }

    private final Map<CommentActionOverride, AnAction> replacedActions =
            new EnumMap<>(CommentActionOverride.class);

    @Override
    public void registerActions(@NotNull ActionManager actionManager) {
        for (CommentActionOverride override : CommentActionOverride.values()) {
            if (replacedActions.containsKey(override)) {
                continue;
            }

            AnAction platformAction = actionManager.getAction(override.actionId());
            if (platformAction == null) {
                continue;
            }

            // Carry over text, description and icon so that the action is presented
            // exactly like the platform action it replaces.
            AnAction overridingAction = override.createOverridingAction();
            overridingAction.getTemplatePresentation().copyFrom(platformAction.getTemplatePresentation());

            replacedActions.put(override, platformAction);
            actionManager.replaceAction(override.actionId(), overridingAction);
        }
    }

    @Override
    public void unregisterActions(@NotNull ActionManager actionManager) {
        replacedActions.forEach((override, platformAction) ->
                actionManager.replaceAction(override.actionId(), platformAction));
        replacedActions.clear();
    }
}
