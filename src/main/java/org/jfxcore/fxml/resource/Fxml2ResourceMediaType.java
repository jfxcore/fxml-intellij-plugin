package org.jfxcore.fxml.resource;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The media type of an embedded resource: a type, a subtype and an ordered list of parameters.
 *
 * <p>Apart from the {@code charset} parameter the media type does not change how the compiler
 * processes a resource.  It is, however, the signal the editor uses to decide which language to
 * inject into the payload, which makes it load-bearing for the IDE.
 *
 * @param type       the media type's type, for example {@code text}
 * @param subtype    the media type's subtype, for example {@code css}
 * @param parameters the parameters, in declaration order
 */
public record Fxml2ResourceMediaType(@NotNull String type,
                                     @NotNull String subtype,
                                     @NotNull List<Fxml2MediaTypeParameter> parameters) {

    /** The media type a declaration without an explicit one is treated as. */
    public static final Fxml2ResourceMediaType TEXT_PLAIN = new Fxml2ResourceMediaType("text", "plain", List.of());

    /** The charset a resource payload is encoded with when the declaration selects none. */
    public static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

    public Fxml2ResourceMediaType {
        parameters = List.copyOf(parameters);
    }

    /** Returns the {@code type/subtype} essence of this media type in lower case, without parameters. */
    public @NotNull String essence() {
        return (type + "/" + subtype).toLowerCase(Locale.ROOT);
    }

    /** Returns the {@code charset} parameter, or {@code null} when the declaration selects none. */
    public @Nullable Fxml2MediaTypeParameter charsetParameter() {
        return parameters.stream().filter(Fxml2MediaTypeParameter::isCharset).findFirst().orElse(null);
    }

    /**
     * Returns the charset the payload is encoded with, or {@code null} when the declaration names
     * a charset that is unknown to this JVM or is not a legal charset name.
     */
    public @Nullable Charset charset() {
        Fxml2MediaTypeParameter parameter = charsetParameter();
        if (parameter == null) return DEFAULT_CHARSET;

        try {
            return Charset.forName(parameter.value());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Returns the declaration text of this media type, including its parameters. */
    public @NotNull String text() {
        String essence = type + "/" + subtype;
        if (parameters.isEmpty()) return essence;

        return essence + parameters.stream()
                .map(parameter -> "; " + parameter.name() + "="
                        + Fxml2MediaTypeWriter.writeParameterValue(parameter.value()))
                .collect(Collectors.joining());
    }
}
