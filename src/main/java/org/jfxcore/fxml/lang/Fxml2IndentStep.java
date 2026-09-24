// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import org.jetbrains.annotations.NotNull;

/**
 * The width of one indentation step, in spaces.
 *
 * <p>Several steps of different languages meet in an FXML/2 document: markup nests in markup
 * steps, and the payload of a {@code <?resource ?>} declaration nests in the steps of the language
 * its media type names.  Naming the step rather than passing an {@code int} is what keeps the two
 * apart where they are used together.
 *
 * @param width the number of spaces one step is wide
 */
public record Fxml2IndentStep(int width) {

    public Fxml2IndentStep {
        if (width < 0) throw new IllegalArgumentException("width must not be negative: " + width);
    }

    /** Returns the step as the whitespace it prefixes a line with. */
    public @NotNull String text() {
        return " ".repeat(width);
    }
}
