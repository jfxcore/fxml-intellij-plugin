package org.jfxcore.fxml.resolve;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * The name of a code-behind class, as declared by the {@code fx:subclass} directive.
 *
 * <p>The compiler requires a fully-qualified name: the unnamed package is not supported,
 * so a code-behind name always carries a non-empty package name. Both the package name
 * and the simple name consist of valid Java identifiers.
 *
 * @param packageName the package of the code-behind class, never empty
 * @param simpleName  the simple name of the code-behind class
 */
public record Fxml2CodeBehindName(@NotNull String packageName, @NotNull String simpleName) {

    /** Suffix of the markup base class the compiler generates for a code-behind class. */
    private static final String MARKUP_CLASS_SUFFIX = "Base";

    /**
     * Parses a declared {@code fx:subclass} value, or returns {@code null} when the value
     * is not a fully-qualified name built from valid Java identifiers.
     */
    public static @Nullable Fxml2CodeBehindName parse(@Nullable String declaredName) {
        if (declaredName == null) return null;
        String trimmed = declaredName.trim();
        int lastDot = trimmed.lastIndexOf('.');
        // A qualified name is required: the compiler rejects the unnamed package.
        if (lastDot <= 0 || lastDot == trimmed.length() - 1) return null;

        if (!Arrays.stream(trimmed.split("\\.", -1)).allMatch(Fxml2JavaNames::isIdentifier)) {
            return null;
        }

        return new Fxml2CodeBehindName(trimmed.substring(0, lastDot), trimmed.substring(lastDot + 1));
    }

    /** Returns the simple name of the compiler-generated markup base class. */
    public @NotNull String markupBaseName() {
        return simpleName + MARKUP_CLASS_SUFFIX;
    }
}
