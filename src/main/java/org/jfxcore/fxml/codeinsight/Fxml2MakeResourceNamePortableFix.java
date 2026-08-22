package org.jfxcore.fxml.codeinsight;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resource.Fxml2ResourceEntry;
import org.jfxcore.fxml.resource.Fxml2ResourceName;
import org.jfxcore.fxml.resource.Fxml2ResourceQuoting;

/**
 * Replaces a resource name that is not a portable file name with the nearest one that is.
 *
 * <p>The repair is purely mechanical, which is what makes it safe to offer as a fix: every
 * character that is not portable is dropped, a trailing space or dot is trimmed, a name that is
 * left empty becomes {@code resource}, and a name whose stem is a reserved device name gains a
 * leading underscore.  The resulting name is then written in the least intrusive quoting style
 * that fits it.
 *
 * <p>References to the old name are not rewritten, because a declaration whose name the compiler
 * rejects cannot have had a resolving reference in the first place.
 */
public final class Fxml2MakeResourceNamePortableFix implements LocalQuickFix {

    /** The name used when nothing portable is left of the declared one. */
    private static final String FALLBACK_NAME = "resource";

    private final String declaredName;
    private final String portableName;

    public Fxml2MakeResourceNamePortableFix(@NotNull String declaredName) {
        this.declaredName = declaredName;
        this.portableName = toPortable(declaredName);
    }

    /** Returns {@code true} when {@code entry}'s name can be repaired into a different, portable one. */
    public static boolean isApplicable(@NotNull Fxml2ResourceEntry entry) {
        String name = entry.name().value();
        String portable = toPortable(name);
        return !portable.equals(name) && Fxml2ResourceName.isPortable(portable);
    }

    /**
     * Returns the nearest portable file name to {@code name}.
     *
     * <p>Unportable characters are dropped rather than substituted, so that the result stays
     * predictable: substituting would have to pick a replacement character, and any pick would be
     * wrong for some names.
     */
    public static @NotNull String toPortable(@NotNull String name) {
        StringBuilder result = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); ++i) {
            char character = name.charAt(i);
            if (character > 0x1f && character != 0x7f && "/\\:*?\"<>|".indexOf(character) < 0) {
                result.append(character);
            }
        }

        while (!result.isEmpty()
                && (result.charAt(result.length() - 1) == ' ' || result.charAt(result.length() - 1) == '.')) {
            result.setLength(result.length() - 1);
        }

        if (result.isEmpty()) return FALLBACK_NAME;

        String candidate = result.toString();
        return Fxml2ResourceName.isPortable(candidate) ? candidate : "_" + candidate;
    }

    @Override
    public @NotNull String getName() {
        return "Rename resource to '" + portableName + "'";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Make resource name portable";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        Fxml2ResourceEntry entry =
                Fxml2ResourceDeclarationEditor.findDeclaration(descriptor.getPsiElement(), declaredName);
        if (entry == null) return;

        Fxml2ResourceDeclarationEditor.replace(project, entry,
                entry.declaration().quotedNameSpan(),
                Fxml2ResourceQuoting.required(portableName).write(portableName));
    }
}
