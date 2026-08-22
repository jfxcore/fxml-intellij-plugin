package org.jfxcore.fxml.resource;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Set;

/**
 * The name of an embedded resource, together with the quoting style it is written in.
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
 * @param value   the logical name, without any quotes
 * @param quoting how the name is written in the declaration
 */
public record Fxml2ResourceName(@NotNull String value, @NotNull Fxml2ResourceQuoting quoting) {

    private static final Set<String> RESERVED_DEVICE_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    /** The characters that are not portable in a file name. */
    private static final String ILLEGAL_CHARACTERS = "/\\:*?\"<>|";

    /** Returns the name {@code value} written in the least intrusive quoting style that fits it. */
    public static @NotNull Fxml2ResourceName of(@NotNull String value) {
        return new Fxml2ResourceName(value, Fxml2ResourceQuoting.required(value));
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

    /** Returns {@code true} when this name resolves a reference written as {@code reference}. */
    public boolean matches(@NotNull String reference) {
        return value.equals(reference);
    }

    /** Returns the declaration text of this name, including quotes when its quoting style needs them. */
    public @NotNull String text() {
        return quoting.write(value);
    }
}
