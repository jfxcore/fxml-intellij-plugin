package org.jfxcore.fxml.codeinsight;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resolve.Fxml2TextSpan;
import org.jfxcore.fxml.resource.Fxml2MediaTypeParameter;
import org.jfxcore.fxml.resource.Fxml2ResourceEntry;
import org.jfxcore.fxml.resource.Fxml2ResourceMediaType;

import java.util.List;

/**
 * Writes an explicit media type into a {@code <?resource ?>} declaration, replacing the declared
 * one when there is one and adding it after the name when there is not.
 *
 * <p>Existing media-type parameters are carried over, so that a declaration that selects a charset
 * keeps selecting it: the charset is the one part of a media type the compiler acts on, and losing
 * it would change the bytes the resource is compiled to.
 */
public final class Fxml2SetResourceMediaTypeFix implements LocalQuickFix {

    private final String declaredName;
    private final String mediaType;

    public Fxml2SetResourceMediaTypeFix(@NotNull String declaredName, @NotNull String mediaType) {
        this.declaredName = declaredName;
        this.mediaType = mediaType;
    }

    @Override
    public @NotNull String getName() {
        return "Set media type to '" + mediaType + "'";
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Set embedded resource media type";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        Fxml2ResourceEntry entry =
                Fxml2ResourceDeclarationEditor.findDeclaration(descriptor.getPsiElement(), declaredName);
        if (entry == null) return;

        String replacement = replacementFor(entry);

        if (entry.declaration().hasExplicitMediaType()) {
            Fxml2ResourceDeclarationEditor.replace(
                    project, entry, entry.declaration().mediaTypeSpan(), replacement);
            return;
        }

        // No media type yet: write it after the name, separated by a space.
        Fxml2TextSpan name = entry.declaration().quotedNameSpan();
        Fxml2ResourceDeclarationEditor.replace(
                project, entry,
                new Fxml2TextSpan(name.end(), name.end()),
                " " + replacement);
    }

    /**
     * Returns the declaration text of the new media type, carrying over the parameters of the
     * declared one.
     *
     * <p>The text is written by the media type itself, so that a parameter value needing quotes or
     * escapes keeps them: a second serializer here would be a second set of escaping rules.
     */
    private @NotNull String replacementFor(@NotNull Fxml2ResourceEntry entry) {
        List<Fxml2MediaTypeParameter> parameters = entry.declaration().hasExplicitMediaType()
                ? entry.declaration().effectiveMediaType().parameters()
                : List.of();

        int slash = mediaType.indexOf('/');
        return new Fxml2ResourceMediaType(
                mediaType.substring(0, slash), mediaType.substring(slash + 1), parameters).text();
    }
}
