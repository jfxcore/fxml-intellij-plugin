package org.jfxcore.fxml.resource;

import org.jetbrains.annotations.NotNull;

/**
 * The diagnostics a {@code <?resource ?>} declaration can carry.
 *
 * <p>Each constant corresponds to one diagnostic the compiler reports, so that a document the
 * editor accepts is a document the compiler accepts, and the other way round.  The message
 * template's {@code %s} placeholders are filled from the arguments of the
 * {@link Fxml2ResourceProblem} carrying the kind.
 */
public enum Fxml2ResourceProblemKind {

    /** The declaration does not follow the {@code <?resource name [media-type]:content?>} grammar. */
    INVALID_DECLARATION("Invalid resource declaration"),

    /** The declaration does not name the resource. */
    MISSING_NAME("Missing resource name"),

    /** The resource name is not a portable file name. */
    INVALID_NAME("Invalid resource name '%s'"),

    /** Another declaration in the same document already declares a resource with this name. */
    DUPLICATE_DECLARATION("Duplicate resource declaration '%s'; a resource with this name is already declared at line %s, column %s"),

    /** The media type does not follow the {@code type/subtype} grammar. */
    INVALID_MEDIA_TYPE("Invalid media type for resource '%s'"),

    /** The media type declares the same parameter twice. */
    DUPLICATE_MEDIA_TYPE_PARAMETER("Duplicate media type parameter '%s' for resource '%s'"),

    /** The {@code charset} parameter names a charset that is unknown or is not a legal charset name. */
    UNSUPPORTED_CHARSET("Unsupported charset '%s' for resource '%s'"),

    /** The payload contains a character the selected charset cannot encode. */
    UNREPRESENTABLE_CHARACTER("Resource '%s' contains a character that cannot be encoded with charset '%s'");

    private final String messageTemplate;

    Fxml2ResourceProblemKind(@NotNull String messageTemplate) {
        this.messageTemplate = messageTemplate;
    }

    /** Returns the diagnostic message for this kind, filled in with {@code arguments}. */
    public @NotNull String message(@NotNull Object @NotNull ... arguments) {
        return messageTemplate.formatted(arguments);
    }
}
