package org.jfxcore.fxml.highlighting;

import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NotNull;

/** A source range and its primary semantic color role. */
public record Fxml2SemanticSpan(@NotNull TextRange range, @NotNull Fxml2SemanticRole role) {
}
