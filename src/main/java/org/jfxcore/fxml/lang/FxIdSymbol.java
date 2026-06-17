package org.jfxcore.fxml.lang;

import com.intellij.find.usages.api.SearchTarget;
import com.intellij.find.usages.api.UsageHandler;
import com.intellij.model.Pointer;
import com.intellij.navigation.NavigatableSymbol;
import com.intellij.navigation.SymbolNavigationService;
import com.intellij.openapi.project.Project;
import com.intellij.platform.backend.documentation.DocumentationSymbol;
import com.intellij.platform.backend.documentation.DocumentationTarget;
import com.intellij.platform.backend.navigation.NavigationTarget;
import com.intellij.platform.backend.presentation.TargetPresentation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2BindingPathResolver;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Symbol for an {@code fx:id} declaration that fully participates in IntelliJ's
 * symbol-native feature stack.
 *
 * <p>Implements:
 * <ul>
 *   <li>{@link NavigatableSymbol}: Ctrl+click / Go To Declaration navigates to
 *       the code-behind {@link PsiField} when present, or to the
 *       {@link XmlAttributeValue} declaration as a fallback.</li>
 *   <li>{@link DocumentationSymbol}: ctrl-hover shows the code-behind field's
 *       quick-documentation ("Button myButton1") rather than generic XML attribute docs.</li>
 *   <li>{@link SearchTarget}: enables the "Show Usages" popup when Ctrl+clicking
 *       on an {@code fx:id} declaration, delegating usage collection to
 *       {@link Fxml2FxIdUsageSearcher}.</li>
 * </ul>
 *
 * <p>Smart pointers are used for both the declaration and the field so that the
 * symbol remains valid across PSI reparses.
 */
@SuppressWarnings("UnstableApiUsage")
final class FxIdSymbol implements NavigatableSymbol, DocumentationSymbol, SearchTarget {

    private final @NotNull SmartPsiElementPointer<XmlAttributeValue> declPtr;
    private final @Nullable SmartPsiElementPointer<PsiField> fieldPtr;

    private FxIdSymbol(
            @NotNull SmartPsiElementPointer<XmlAttributeValue> declPtr,
            @Nullable SmartPsiElementPointer<PsiField> fieldPtr) {
        this.declPtr = declPtr;
        this.fieldPtr = fieldPtr;
    }

    /**
     * Creates an {@link FxIdSymbol} for the given {@code fx:id} declaration,
     * looking up the matching code-behind field from the FXML's {@code fx:subclass}.
     */
    static @NotNull FxIdSymbol of(@NotNull XmlAttributeValue declaration) {
        SmartPsiElementPointer<XmlAttributeValue> declPtr =
                SmartPointerManager.createPointer(declaration);
        SmartPsiElementPointer<PsiField> fieldPtr = null;

        if (declaration.getContainingFile() instanceof XmlFile xmlFile) {
            String idName = declaration.getValue();
            if (!idName.isBlank()) {
                PsiClass codeBehind = Fxml2BindingPathResolver.resolveCodeBehindClass(xmlFile);
                if (codeBehind != null) {
                    PsiField field = codeBehind.findFieldByName(idName, true);
                    if (field != null) {
                        fieldPtr = SmartPointerManager.createPointer(field);
                    }
                }
            }
        }

        return new FxIdSymbol(declPtr, fieldPtr);
    }

    // -----------------------------------------------------------------------
    // Accessors (used by Fxml2FxIdUsageSearcher)
    // -----------------------------------------------------------------------

    /** Returns the dereferenced {@link XmlAttributeValue}, or {@code null} if invalidated. */
    @Nullable XmlAttributeValue getDeclaration() {
        return declPtr.getElement();
    }

    /** Returns the dereferenced code-behind {@link PsiField}, or {@code null} if absent or invalidated. */
    @Nullable PsiField getField() {
        return fieldPtr != null ? fieldPtr.getElement() : null;
    }

    // -----------------------------------------------------------------------
    // NavigatableSymbol
    // -----------------------------------------------------------------------

    @Override
    public @NotNull Collection<? extends NavigationTarget> getNavigationTargets(@NotNull Project project) {
        PsiElement target = getField();
        if (target == null) {
            target = getDeclaration();
        }
        return target == null
                ? List.of()
                : List.of(SymbolNavigationService.getInstance().psiElementNavigationTarget(target));
    }

    // -----------------------------------------------------------------------
    // DocumentationSymbol
    // -----------------------------------------------------------------------

    @Override
    public @NotNull DocumentationTarget getDocumentationTarget() {
        PsiField f = getField();
        XmlAttributeValue decl = getDeclaration();
        if (f != null) {
            // Document the code-behind field, using the declaration as the original context.
            return Fxml2PsiDocumentationTarget.of(f, decl);
        }
        return Fxml2PsiDocumentationTarget.of(
                Objects.requireNonNull(decl,
                        "FxIdSymbol queried for documentation after its PSI was invalidated"),
                null);
    }

    // -----------------------------------------------------------------------
    // SearchTarget
    // -----------------------------------------------------------------------

    @Override
    public @NotNull Pointer<FxIdSymbol> createPointer() {
        return Pointer.delegatingPointer(declPtr, FxIdSymbol::of);
    }

    @Override
    public @NotNull TargetPresentation presentation() {
        XmlAttributeValue decl = getDeclaration();
        String idName = decl != null ? decl.getValue() : "";
        return TargetPresentation.builder(idName).presentation();
    }

    @Override
    public @NotNull UsageHandler getUsageHandler() {
        XmlAttributeValue decl = getDeclaration();
        String idName = decl != null ? decl.getValue() : "";
        return UsageHandler.createEmptyUsageHandler(idName);
    }

    // -----------------------------------------------------------------------
    // equals / hashCode (required by SearchTarget contract)
    // -----------------------------------------------------------------------

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof FxIdSymbol that)) return false;
        XmlAttributeValue thisDecl = getDeclaration();
        XmlAttributeValue thatDecl = that.getDeclaration();
        return thisDecl != null && thisDecl.equals(thatDecl);
    }

    @Override
    public int hashCode() {
        XmlAttributeValue decl = getDeclaration();
        return decl != null ? decl.hashCode() : 0;
    }
}
