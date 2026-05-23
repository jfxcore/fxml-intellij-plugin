package org.jfxcore.fxml.resolve;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Per-project cache for well-known JavaFX and markup-extension PSI class lookups.
 *
 * <p>{@link JavaPsiFacade#findClass} is individually inexpensive (it uses the IDE's
 * class-name index), but when called hundreds of times per annotation pass - once per
 * attribute for methods such as {@code isEventHandlerType} and
 * {@code isNotObservableDeclaration} - the accumulated cost is visible in profiles.
 * This utility caches the results per project with a Java-language modification tracker
 * so the cache survives edits to FXML, CSS, and resource-bundle files but is correctly
 * invalidated when a library containing one of the well-known types is added, removed,
 * or changed.
 *
 * <p>All methods return {@code null} when the corresponding library is not present on
 * the module classpath; callers must guard against {@code null}.
 */
public final class Fxml2WellKnownClasses {

    private static final Key<CachedValue<PsiClass>> OBSERVABLE_VALUE_KEY =
            Key.create("fxml2.wkc.ObservableValue");
    private static final Key<CachedValue<PsiClass>> OBSERVABLE_LIST_KEY =
            Key.create("fxml2.wkc.ObservableList");
    private static final Key<CachedValue<PsiClass>> OBSERVABLE_SET_KEY =
            Key.create("fxml2.wkc.ObservableSet");
    private static final Key<CachedValue<PsiClass>> OBSERVABLE_MAP_KEY =
            Key.create("fxml2.wkc.ObservableMap");
    private static final Key<CachedValue<PsiClass>> EVENT_HANDLER_KEY =
            Key.create("fxml2.wkc.EventHandler");
    private static final Key<CachedValue<PsiClass>> MARKUP_EXTENSION_KEY =
            Key.create("fxml2.wkc.MarkupExtension");
    private static final Key<CachedValue<PsiClass>> MARKUP_SUPPLIER_KEY =
            Key.create("fxml2.wkc.MarkupExtension.Supplier");
    private static final Key<CachedValue<PsiClass>> MARKUP_CONTEXT_KEY =
            Key.create("fxml2.wkc.MarkupContext");
    private static final Key<CachedValue<PsiClass>> PROPERTY_CONSUMER_KEY =
            Key.create("fxml2.wkc.MarkupExtension.PropertyConsumer");
    private static final Key<CachedValue<PsiClass>> PROPERTY_KEY =
            Key.create("fxml2.wkc.Property");

    private Fxml2WellKnownClasses() {}

    /**
     * Returns the {@code javafx.beans.value.ObservableValue} class, or {@code null}
     * if JavaFX is not on the module classpath.
     */
    public static @Nullable PsiClass observableValue(@NotNull Project project) {
        return findCached(project, OBSERVABLE_VALUE_KEY,
                "javafx.beans.value.ObservableValue");
    }

    /**
     * Returns the {@code javafx.collections.ObservableList} class, or {@code null}
     * if JavaFX is not on the module classpath.
     */
    public static @Nullable PsiClass observableList(@NotNull Project project) {
        return findCached(project, OBSERVABLE_LIST_KEY,
                "javafx.collections.ObservableList");
    }

    /**
     * Returns the {@code javafx.collections.ObservableSet} class, or {@code null}
     * if JavaFX is not on the module classpath.
     */
    public static @Nullable PsiClass observableSet(@NotNull Project project) {
        return findCached(project, OBSERVABLE_SET_KEY,
                "javafx.collections.ObservableSet");
    }

    /**
     * Returns the {@code javafx.collections.ObservableMap} class, or {@code null}
     * if JavaFX is not on the module classpath.
     */
    public static @Nullable PsiClass observableMap(@NotNull Project project) {
        return findCached(project, OBSERVABLE_MAP_KEY,
                "javafx.collections.ObservableMap");
    }

    /**
     * Returns the {@code javafx.event.EventHandler} class, or {@code null}
     * if JavaFX is not on the module classpath.
     */
    public static @Nullable PsiClass eventHandler(@NotNull Project project) {
        return findCached(project, EVENT_HANDLER_KEY,
                "javafx.event.EventHandler");
    }

    /**
     * Returns the {@code org.jfxcore.markup.MarkupExtension} class, or {@code null}
     * if the markup runtime library is not on the module classpath.
     */
    public static @Nullable PsiClass markupExtension(@NotNull Project project) {
        return findCached(project, MARKUP_EXTENSION_KEY,
                "org.jfxcore.markup.MarkupExtension");
    }

    /**
     * Returns the {@code org.jfxcore.markup.MarkupExtension.Supplier} class, or
     * {@code null} if the markup runtime library is not on the module classpath.
     */
    public static @Nullable PsiClass markupExtensionSupplier(@NotNull Project project) {
        return findCached(project, MARKUP_SUPPLIER_KEY,
                "org.jfxcore.markup.MarkupExtension.Supplier");
    }

    /**
     * Returns the {@code org.jfxcore.markup.MarkupContext} class, or {@code null}
     * if the markup runtime library is not on the module classpath.
     */
    public static @Nullable PsiClass markupContext(@NotNull Project project) {
        return findCached(project, MARKUP_CONTEXT_KEY,
                "org.jfxcore.markup.MarkupContext");
    }

    /**
     * Returns the {@code org.jfxcore.markup.MarkupExtension.PropertyConsumer} class,
     * or {@code null} if the markup runtime library is not on the module classpath.
     */
    public static @Nullable PsiClass markupExtensionPropertyConsumer(@NotNull Project project) {
        return findCached(project, PROPERTY_CONSUMER_KEY,
                "org.jfxcore.markup.MarkupExtension.PropertyConsumer");
    }

    /**
     * Returns the {@code javafx.beans.property.Property} interface, or {@code null}
     * if JavaFX is not on the module classpath.
     *
     * <p>A binding source satisfies the writable requirement only when its declaration
     * type is a subtype of this interface. Read-only wrappers such as
     * {@code ReadOnlyStringProperty} are observable but are not subtypes of
     * {@code Property}, and therefore cannot be used as bidirectional binding sources.
     */
    public static @Nullable PsiClass property(@NotNull Project project) {
        return findCached(project, PROPERTY_KEY,
                "javafx.beans.property.Property");
    }

    /**
     * Looks up a class by its fully-qualified name and caches the result (including
     * {@code null}) on the project. The cache is invalidated when the Java-language
     * PSI changes (class additions, library changes) but survives unrelated edits.
     */
    private static @Nullable PsiClass findCached(
            @NotNull Project project,
            @NotNull Key<CachedValue<PsiClass>> key,
            @NotNull String fqn) {
        return CachedValuesManager.getManager(project).getCachedValue(
                project, key,
                () -> {
                    PsiClass cls = JavaPsiFacade.getInstance(project)
                            .findClass(fqn, GlobalSearchScope.allScope(project));
                    return CachedValueProvider.Result.create(
                            cls,
                            PsiModificationTracker.getInstance(project)
                                    .forLanguage(JavaLanguage.INSTANCE));
                },
                false);
    }
}
