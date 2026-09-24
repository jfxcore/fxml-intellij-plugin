package org.jfxcore.fxml.codeinsight;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resource.Fxml2ResourceEntry;
import org.jfxcore.fxml.resource.Fxml2ResourceMediaType;
import org.jfxcore.fxml.resource.Fxml2ResourceName;

/** Resolves the current resource declaration and applies one typed edit to it. */
public final class Fxml2ResourceDeclarationFix implements LocalQuickFix {

    @com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview
    private final Fxml2ResourceName declaredName;
    @com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview
    private final Fxml2ResourceEdit edit;

    private Fxml2ResourceDeclarationFix(@NotNull Fxml2ResourceName declaredName,
                                        @NotNull Fxml2ResourceEdit edit) {
        this.declaredName = declaredName;
        this.edit = edit;
    }

    public static @NotNull Fxml2ResourceDeclarationFix makePortable(@NotNull Fxml2ResourceName name) {
        return new Fxml2ResourceDeclarationFix(name, new Fxml2ResourceEdit.Rename(name.toPortable()));
    }

    public static @NotNull Fxml2ResourceDeclarationFix setMediaType(
            @NotNull Fxml2ResourceName name, @NotNull Fxml2ResourceMediaType mediaType) {
        return new Fxml2ResourceDeclarationFix(name, new Fxml2ResourceEdit.SetMediaType(mediaType));
    }

    public static @NotNull Fxml2ResourceDeclarationFix removeParameter(
            @NotNull Fxml2ResourceName name, @NotNull String parameterName) {
        return new Fxml2ResourceDeclarationFix(name, new Fxml2ResourceEdit.RemoveParameter(parameterName));
    }

    public static @NotNull Fxml2ResourceDeclarationFix remove(@NotNull Fxml2ResourceName name) {
        return new Fxml2ResourceDeclarationFix(name, new Fxml2ResourceEdit.RemoveDeclaration());
    }

    @Override
    public @NotNull String getName() {
        return edit instanceof Fxml2ResourceEdit.RemoveDeclaration
                ? "Remove embedded resource '" + declaredName.value() + "'"
                : edit.name();
    }

    @Override
    public @NotNull String getFamilyName() {
        return edit.familyName();
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        Fxml2ResourceEntry entry = Fxml2ResourceDeclarationEditor.findDeclaration(
                descriptor.getPsiElement(), declaredName);
        if (entry != null) edit.apply(project, entry);
    }
}
