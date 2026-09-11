package org.jfxcore.fxml.resource;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Set;

/**
 * The name of an embedded resource.
 *
 * <p>A resource name is a single portable file name.  The portability rules are the ones the
 * markup language applies: a name may not be empty, may not be {@code .} or {@code ..}, may not
 * contain a path separator, a character that is illegal in a file name on any supported platform,
 * or a control character, may not end in a space or a dot, and its stem may not be one of the
 * reserved device names.
 *
 * <p>Two names collide when they are equal ignoring case, because the resource file the compiler
 * writes is named case-insensitively.  Resolving a reference, on the other hand, matches exactly:
 * the runtime derives the resource file name from the logical name verbatim, so a name that
 * differs in case or in interior whitespace would not resolve at runtime either.
 *
 * <p>Quoting is a property of the declaration text only and never becomes part of the logical
 * name.  A name has to be quoted when it contains a character the unquoted form cannot express,
 * which is any XML whitespace character or the content separator.
 *
 * @param value the logical name, without any quotes
 */
public record Fxml2ResourceName(@NotNull String value) {

    private static final Set<String> RESERVED_DEVICE_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    /** The characters that are not portable in a file name. */
    private static final String ILLEGAL_CHARACTERS = "/\\:*?\"<>|";

    /** Returns {@code name} written in the least intrusive spelling a declaration accepts. */
    public static @NotNull String write(@NotNull String name) {
        if (!needsQuoting(name)) return name;
        char quote = name.indexOf('"') < 0 ? '"' : '\'';
        return quote + name + quote;
    }

    /**
     * Returns {@code true} when {@code name} cannot be written without quotes, which is the case
     * for an empty name and for any name containing XML whitespace or the content separator.
     */
    public static boolean needsQuoting(@NotNull String name) {
        if (name.isEmpty()) return true;
        for (int i = 0; i < name.length(); ++i) {
            char ch = name.charAt(i);
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == ':') return true;
        }
        return false;
    }

    /** Returns {@code true} when {@code value} satisfies every portability rule for a resource name. */
    public static boolean isPortable(@NotNull String value) {
        if (value.isEmpty() || value.equals(".") || value.equals("..")) return false;

        for (int i = 0; i < value.length(); ++i) {
            char ch = value.charAt(i);
            if (ch <= 0x1f || ch == 0x7f || ILLEGAL_CHARACTERS.indexOf(ch) >= 0) return false;
        }

        if (value.endsWith(" ") || value.endsWith(".")) return false;

        return !RESERVED_DEVICE_NAMES.contains(stemOf(value).toUpperCase(Locale.ROOT));
    }

    /** Returns the portion of {@code value} before its first dot. */
    private static @NotNull String stemOf(@NotNull String value) {
        int dot = value.indexOf('.');
        return dot >= 0 ? value.substring(0, dot) : value;
    }

    /** Returns {@code true} when this name satisfies every portability rule. */
    public boolean isPortable() {
        return isPortable(value);
    }

    /**
     * Returns the file name extension of this name in lower case and without its leading dot,
     * or an empty string when the name has no extension.
     */
    public @NotNull String extension() {
        int dot = value.lastIndexOf('.');
        return dot < 0 || dot == value.length() - 1
                ? ""
                : value.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

}
