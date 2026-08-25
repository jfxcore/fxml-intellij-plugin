package org.jfxcore.fxml.resource;

import com.intellij.lang.Language;
import com.intellij.openapi.fileTypes.PlainTextLanguage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The languages a resource payload can be edited in, and the rules that pick one.
 *
 * <p>The media type is informational to the compiler but load-bearing here: it is the only signal
 * that says "treat this text as CSS".  Keeping the mapping a closed enum rather than a string
 * switch means a payload can only ever be injected as a language this plugin has considered.
 *
 * <p>Resolution order is media type first, extension second.  An explicit media type is a
 * statement the author made and always wins.  When the declaration omits the media type, or names
 * the {@code text/plain} default, the resource name's extension is consulted instead, so that
 * {@code <?resource styles.css: ...?>} still lights up as CSS.  Anything unmapped falls back to
 * plain text.
 *
 * <p>Every language is looked up by its identifier at the moment it is needed, because which
 * languages exist depends on the IDE the plugin is running in.  CSS in particular is not bundled
 * with IntelliJ IDEA Community: there, a CSS payload is edited as plain text, and every feature
 * that does not need CSS PSI keeps working.
 */
public enum Fxml2ResourcePayloadLanguage {

    /** Cascading style sheets; the media type this feature exists for. */
    CSS("CSS", List.of("text/css"), List.of("css")),

    /** JSON, in both its registered and its historical media type. */
    JSON("JSON", List.of("application/json", "text/json"), List.of("json")),

    /** XML, including every {@code +xml} structured suffix such as {@code image/svg+xml}. */
    XML("XML", List.of("application/xml", "text/xml"), List.of("xml", "svg", "fxml")),

    /** HTML. */
    HTML("HTML", List.of("text/html"), List.of("html", "htm")),

    /** YAML. */
    YAML("yaml", List.of("application/yaml", "text/yaml", "application/x-yaml"), List.of("yaml", "yml")),

    /** Markdown. */
    MARKDOWN("Markdown", List.of("text/markdown"), List.of("md", "markdown")),

    /** TOML. */
    TOML("TOML", List.of("application/toml", "text/toml"), List.of("toml")),

    /** Java properties files. */
    PROPERTIES("Properties", List.of("text/x-java-properties"), List.of("properties")),

    /** The fallback, and the language of the default {@code text/plain} media type. */
    PLAIN_TEXT(PlainTextLanguage.INSTANCE.getID(), List.of("text/plain"), List.of("txt", "text"));

    /** The structured suffix that marks a media type as an XML dialect regardless of its subtype. */
    private static final String XML_STRUCTURED_SUFFIX = "+xml";

    private final String languageId;
    private final List<String> mediaTypes;
    private final List<String> extensions;

    Fxml2ResourcePayloadLanguage(@NotNull String languageId,
                                 @NotNull List<String> mediaTypes,
                                 @NotNull List<String> extensions) {
        this.languageId = languageId;
        this.mediaTypes = mediaTypes;
        this.extensions = extensions;
    }

    /**
     * Returns the language {@code declaration}'s payload should be edited in.
     *
     * <p>Never returns {@code null}: a declaration whose media type and extension are both
     * unmapped is edited as plain text.
     */
    public static @NotNull Fxml2ResourcePayloadLanguage of(@NotNull Fxml2ResourceDeclaration declaration) {
        Fxml2ResourcePayloadLanguage fromMediaType = ofMediaType(declaration.effectiveMediaType());
        if (fromMediaType != null && fromMediaType != PLAIN_TEXT) return fromMediaType;

        Fxml2ResourcePayloadLanguage fromExtension = ofExtension(declaration.name().extension());
        return fromExtension != null ? fromExtension : PLAIN_TEXT;
    }

    /** Returns the language {@code mediaType} names, or {@code null} when it names none. */
    public static @Nullable Fxml2ResourcePayloadLanguage ofMediaType(@NotNull Fxml2ResourceMediaType mediaType) {
        String essence = mediaType.essence();

        for (Fxml2ResourcePayloadLanguage candidate : values()) {
            if (candidate.mediaTypes.contains(essence)) return candidate;
        }

        return essence.endsWith(XML_STRUCTURED_SUFFIX) ? XML : null;
    }

    /** Returns the language the file extension {@code extension} implies, or {@code null} for none. */
    public static @Nullable Fxml2ResourcePayloadLanguage ofExtension(@NotNull String extension) {
        if (extension.isEmpty()) return null;

        String lowerCase = extension.toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(candidate -> candidate.extensions.contains(lowerCase))
                .findFirst()
                .orElse(null);
    }

    /** Returns the media type that most directly names this language. */
    public @NotNull String canonicalMediaType() {
        return mediaTypes.getFirst();
    }

    /** Returns the file extension that most directly names this language. */
    public @NotNull String defaultExtension() {
        return extensions.getFirst();
    }

    /**
     * Returns the platform language to inject, or {@code null} when this IDE does not have it.
     *
     * <p>A missing language is not an error: it means the payload is edited as plain text, which
     * is what happens to a CSS payload in IntelliJ IDEA Community.
     */
    public @Nullable Language language() {
        return Language.findLanguageByID(languageId);
    }

    /**
     * Returns the platform language to inject, falling back to plain text when this IDE does not
     * have the preferred one.
     */
    public @NotNull Language languageOrPlainText() {
        Language language = language();
        return language != null ? language : PlainTextLanguage.INSTANCE;
    }

    /** Returns {@code true} when this IDE can edit a payload in this language. */
    public boolean isAvailable() {
        return language() != null;
    }
}
