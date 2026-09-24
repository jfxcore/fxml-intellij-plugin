package org.jfxcore.fxml.resource;

import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resolve.Fxml2TextSpan;

/**
 * One {@code ; name = value} parameter of a resource media type.
 *
 * <p>Parameter names are compared ignoring case, both for detecting duplicates and for finding
 * the {@code charset} parameter.  The value is the parsed value: a quoted value is reported with
 * its quotes removed and its backslash escapes resolved.
 *
 * @param name  the parameter name as written
 * @param value the parsed parameter value
 * @param span  the span of the whole {@code name = value} parameter in the source
 */
public record Fxml2MediaTypeParameter(@NotNull String name, @NotNull String value, @NotNull Fxml2TextSpan span) {

    /** The name of the parameter that selects the charset a resource payload is encoded with. */
    public static final String CHARSET = "charset";

    /** Returns {@code true} when this parameter is named {@code name}, ignoring case. */
    public boolean hasName(@NotNull String name) {
        return this.name.equalsIgnoreCase(name);
    }

    /** Returns {@code true} when this parameter selects the resource charset. */
    public boolean isCharset() {
        return hasName(CHARSET);
    }
}
