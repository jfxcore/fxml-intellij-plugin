// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.find.usages.api.SearchTarget;
import com.intellij.find.usages.api.UsageHandler;
import com.intellij.model.Pointer;
import com.intellij.navigation.NavigatableSymbol;
import com.intellij.navigation.SymbolNavigationService;
import com.intellij.openapi.project.Project;
import com.intellij.platform.backend.navigation.NavigationTarget;
import com.intellij.platform.backend.presentation.TargetPresentation;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resource.Fxml2ResourceEntry;
import org.jfxcore.fxml.resource.Fxml2ResourceModel;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The symbol an embedded resource declaration declares.
 *
 * <p>A resource is identified by its name and by the document that declares it, because that is
 * the whole of its scope: declarations apply to their own document only, and a name is matched
 * exactly within it.  The symbol therefore holds a pointer to the declaring file rather than to
 * the declaration text, which also keeps it valid while the declaration is being edited.
 *
 * <p>Implementing {@link SearchTarget} is what lets the Ctrl+click gesture on a declaration open
 * the Show Usages popup, with {@link Fxml2ResourceUsageSearcher} collecting the use sites.
 * {@link NavigatableSymbol} covers the other direction, from the popup back to the declaration.
 */
@SuppressWarnings("UnstableApiUsage")
final class Fxml2ResourceSymbol implements NavigatableSymbol, SearchTarget {

    private final @NotNull SmartPsiElementPointer<XmlFile> documentPointer;
    private final @NotNull String name;

    private Fxml2ResourceSymbol(@NotNull SmartPsiElementPointer<XmlFile> documentPointer,
                                @NotNull String name) {
        this.documentPointer = documentPointer;
        this.name = name;
    }

    /** Returns the symbol the resource named {@code name} in {@code document} declares. */
    static @NotNull Fxml2ResourceSymbol of(@NotNull XmlFile document, @NotNull String name) {
        return new Fxml2ResourceSymbol(SmartPointerManager.createPointer(document), name);
    }

    /**
     * Returns the declaration this symbol represents, or {@code null} when the document no longer
     * declares the name.
     */
    @Nullable Fxml2ResourceDeclarationElement getDeclaration() {
        XmlFile document = documentPointer.getElement();
        if (document == null) return null;

        Fxml2ResourceEntry entry = Fxml2ResourceModel.of(document).resolve(name);
        return entry == null ? null : new Fxml2ResourceDeclarationElement(entry);
    }

    // -----------------------------------------------------------------------
    // NavigatableSymbol
    // -----------------------------------------------------------------------

    @Override
    public @NotNull Collection<? extends NavigationTarget> getNavigationTargets(@NotNull Project project) {
        Fxml2ResourceDeclarationElement declaration = getDeclaration();
        return declaration == null
                ? List.of()
                : List.of(SymbolNavigationService.getInstance().psiElementNavigationTarget(declaration));
    }

    // -----------------------------------------------------------------------
    // SearchTarget
    // -----------------------------------------------------------------------

    @Override
    public @NotNull Pointer<Fxml2ResourceSymbol> createPointer() {
        return Pointer.delegatingPointer(documentPointer, document -> of(document, name));
    }

    @Override
    public @NotNull TargetPresentation presentation() {
        return TargetPresentation.builder(name).presentation();
    }

    @Override
    public @NotNull UsageHandler getUsageHandler() {
        return UsageHandler.createEmptyUsageHandler(name);
    }

    // -----------------------------------------------------------------------
    // equals / hashCode (required by the SearchTarget contract)
    // -----------------------------------------------------------------------

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Fxml2ResourceSymbol that
                && name.equals(that.name)
                && Objects.equals(documentPointer.getElement(), that.documentPointer.getElement());
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, documentPointer.getElement());
    }
}
