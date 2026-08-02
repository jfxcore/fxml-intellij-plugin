package org.jfxcore.fxml.resolve;

import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Validation of Java names as the FXML compiler understands them.
 */
public final class Fxml2JavaNames {

    /** Java identifier pattern, mirroring the compiler's {@code NameHelper.JAVA_IDENTIFIER}. */
    private static final Pattern JAVA_IDENTIFIER =
            Pattern.compile("^(\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)$");

    private Fxml2JavaNames() {
    }

    /** Returns {@code true} when {@code name} is a valid Java identifier. */
    public static boolean isIdentifier(@Nullable String name) {
        return name != null && JAVA_IDENTIFIER.matcher(name).matches();
    }
}
