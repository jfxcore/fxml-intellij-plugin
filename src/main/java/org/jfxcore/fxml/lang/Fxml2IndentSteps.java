// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resource.Fxml2ResourcePayloadLanguage;

import java.util.EnumMap;
import java.util.Map;

/**
 * The indentation steps an FXML/2 document is written in: one for the markup, and one for each
 * payload language a {@code <?resource ?>} declaration of it carries.
 *
 * <p>The steps are resolved once, for the file the markup lives in, and then travel with the
 * document being formatted.  Resolving them together is what lets embedded markup be formatted
 * with the steps configured where the annotation is written: the reformat of an annotation value
 * runs with a local copy of the code style settings installed, and a lookup made while that copy
 * is installed answers from the copy instead of from the rules that apply to the file.  The steps
 * are therefore attached to the document under {@link #KEY} before that copy is installed, and
 * read back from there by whoever needs them.
 *
 * @param markup   the step markup nests in
 * @param payloads the step each payload language nests in
 */
public record Fxml2IndentSteps(@NotNull Fxml2IndentStep markup,
                               @NotNull Map<Fxml2ResourcePayloadLanguage, Fxml2IndentStep> payloads) {

    /** Where a document being formatted carries the steps that apply to it. */
    public static final Key<Fxml2IndentSteps> KEY = Key.create("fxml2.indent.steps");

    public Fxml2IndentSteps {
        payloads = payloads.isEmpty() ? Map.of() : new EnumMap<>(payloads);
    }

    /**
     * Returns the step {@code payloadLanguage} nests in, which is the markup step for a language
     * that was not resolved because the document carries no payload written in it.
     */
    public @NotNull Fxml2IndentStep payload(@NotNull Fxml2ResourcePayloadLanguage payloadLanguage) {
        return payloads.getOrDefault(payloadLanguage, markup);
    }
}
