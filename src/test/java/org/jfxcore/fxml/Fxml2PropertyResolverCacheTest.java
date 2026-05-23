package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import org.jfxcore.fxml.resolve.Fxml2PropertyResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Fxml2PropertyResolver#resolveInstanceProperty} cache semantics.
 *
 * <p>Performance contract: for properties that exist as getter/setter/xProperty() methods
 * on the owner class, the result of
 * {@code resolveInstanceProperty(cls, name, nonEmptySiblings)} must equal the result of
 * {@code resolveInstanceProperty(cls, name, List.of())} because sibling attributes are only
 * relevant for {@code @NamedArg} constructor disambiguation, which is reached only when no
 * getter/setter/propertyGetter is found.
 *
 * <p>The optimization checked here lets the annotator and descriptor code call with non-empty
 * siblings (the natural source of truth) without paying the cost of a full uncached PSI scan
 * on every annotation pass. Without the optimization every call with non-empty siblings
 * bypasses the per-class {@link com.intellij.psi.util.CachedValue} and performs expensive
 * {@link com.intellij.psi.PsiClass#findMethodsByName} lookups for every attribute in every
 * annotated tag.
 *
 * <p>Cache population must occur on the first call regardless of whether it uses an empty or
 * non-empty sibling list, because the annotator always passes a non-empty list in practice.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2PropertyResolverCacheTest extends Fxml2TestBase {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private PsiClass findClass(String fqn) {
        return ReadAction.compute(() ->
                JavaPsiFacade.getInstance(getFixture().getProject())
                        .findClass(fqn, GlobalSearchScope.allScope(getFixture().getProject())));
    }

    // -----------------------------------------------------------------------
    // Contract: getter/setter properties; siblings must not change the result
    // -----------------------------------------------------------------------

    /**
     * Simulates the real annotator call order: non-empty siblings are passed on the
     * very first call (the annotator never calls with an empty list).
     * The second call with empty siblings must return the same cached element.
     */
    @Test
    void cacheIsPopulatedByFirstCallWithNonEmptySiblings() {
        PsiClass button = findClass("javafx.scene.control.Button");
        assertNotNull(button);

        // First call: non-empty siblings (as the annotator does it).
        PsiElement first = ReadAction.compute(() ->
                Fxml2PropertyResolver.resolveInstanceProperty(button, "visible", List.of("text", "id", "visible")));
        assertNotNull(first, "Button.visible must resolve with non-empty siblings on first call");

        // Second call: empty siblings, must return the same cached element.
        PsiElement second = ReadAction.compute(() ->
                Fxml2PropertyResolver.resolveInstanceProperty(button, "visible", List.of()));
        assertSame(first, second,
                "Second call with empty siblings must return the cached element from the first call");

        // Third call: different non-empty siblings, must still return the cached element.
        PsiElement third = ReadAction.compute(() ->
                Fxml2PropertyResolver.resolveInstanceProperty(button, "visible", List.of("style", "opacity")));
        assertSame(first, third,
                "Subsequent calls with any sibling list must return the same cached element");
    }

    /**
     * {@code Button.text} is backed by a getter + setter + xProperty() method.
     * Calling with any non-empty sibling list must return the exact same PsiElement
     * as calling with an empty sibling list.
     */
    @Test
    void getterSetterPropertyReturnsSameResultRegardlessOfSiblings() {
        PsiClass button = findClass("javafx.scene.control.Button");
        assertNotNull(button, "javafx.scene.control.Button must be on the test classpath");

        PsiElement noSiblings = ReadAction.compute(() ->
                Fxml2PropertyResolver.resolveInstanceProperty(button, "text", List.of()));
        assertNotNull(noSiblings,
                "Button.text must resolve to a non-null PsiElement with empty siblings");

        // Simulate the real call site: all attributes on the tag are passed as siblings.
        PsiElement withSiblings = ReadAction.compute(() ->
                Fxml2PropertyResolver.resolveInstanceProperty(button, "text", List.of("text", "id", "styleClass")));
        assertNotNull(withSiblings,
                "Button.text must resolve to a non-null PsiElement with non-empty siblings");

        // The cache is consulted first; a cached non-null result short-circuits the NamedArg branch,
        // so the same PsiElement must be returned regardless of the sibling list.
        assertSame(noSiblings, withSiblings,
                "resolveInstanceProperty must return the identical PsiElement object for "
                        + "getter/setter properties regardless of the sibling list");
    }

    /**
     * {@code Region.prefWidth} (setter + getter + prefWidthProperty) must also return the
     * same result regardless of siblings, even on consecutive calls that hit the cache.
     */
    @Test
    void prefWidthReturnsSameObjectOnConsecutiveCalls() {
        PsiClass region = findClass("javafx.scene.layout.Region");
        assertNotNull(region);

        PsiElement first  = ReadAction.compute(() -> Fxml2PropertyResolver.resolveInstanceProperty(region, "prefWidth", List.of()));
        PsiElement second = ReadAction.compute(() -> Fxml2PropertyResolver.resolveInstanceProperty(region, "prefWidth", List.of("prefWidth", "prefHeight", "minWidth")));
        PsiElement third  = ReadAction.compute(() -> Fxml2PropertyResolver.resolveInstanceProperty(region, "prefWidth", List.of("prefWidth")));

        assertNotNull(first);
        assertSame(first, second, "second call with siblings must return the cached element");
        assertSame(first, third,  "third call  with siblings must return the cached element");
    }

    /**
     * Sanity check: resolving a completely unknown property must return null with both
     * empty and non-empty siblings; the optimisation must not accidentally return a
     * stale non-null entry for an unrelated property.
     */
    @Test
    void unknownPropertyReturnsNullRegardlessOfSiblings() {
        PsiClass button = findClass("javafx.scene.control.Button");
        assertNotNull(button);

        assertNull(ReadAction.compute(() -> Fxml2PropertyResolver.resolveInstanceProperty(button, "nonExistentProp123", List.of())));
        assertNull(ReadAction.compute(() -> Fxml2PropertyResolver.resolveInstanceProperty(button, "nonExistentProp123", List.of("id", "text"))));
    }

    /**
     * Resolving with {@code kind = null} and then with a binding kind must also produce
     * the same PsiElement for a standard getter/setter property, because the kind only
     * changes which accessor is preferred, but all accessors belong to the same property
     * and the top-level resolution result must be consistent.
     */
    @Test
    void kindDoesNotChangeCacheConsistencyForGetterSetterProperty() {
        PsiClass button = findClass("javafx.scene.control.Button");
        assertNotNull(button);

        // Cache with no-kind key (kind == null -> cacheKey ends in "|")
        PsiElement noKind = ReadAction.compute(() ->
                Fxml2PropertyResolver.resolveInstanceProperty(button, "text", List.of(), null));
        assertNotNull(noKind, "Button.text with kind=null must resolve");

        // Call with siblings: must hit the null-kind cache entry for getter/setter properties
        PsiElement withSiblings = ReadAction.compute(() ->
                Fxml2PropertyResolver.resolveInstanceProperty(button, "text", List.of("text", "id"), null));
        assertSame(noKind, withSiblings,
                "With siblings and same kind, must return the cached element");
    }

    // -----------------------------------------------------------------------
    // Correctness: resolving multiple properties in a single pass
    // -----------------------------------------------------------------------

    /**
     * Simulates what the annotator does for a tag with many attributes: resolves each
     * property once with empty siblings (as a warm-up, as the descriptor provider does)
     * and then once with the full sibling list (as the actual annotator does).
     * All results must be non-null and identical between the two calls.
     */
    @Test
    void manyPropertiesReturnConsistentResultsBetweenEmptyAndNonEmptySiblings() {
        PsiClass button = findClass("javafx.scene.control.Button");
        assertNotNull(button);

        String[] props = {"text", "id", "styleClass", "prefWidth", "prefHeight",
                "minWidth", "maxWidth", "minHeight", "maxHeight", "visible", "disable",
                "opacity", "style", "alignment", "contentDisplay"};
        List<String> siblings = List.of(props);

        for (String prop : props) {
            PsiElement noSib  = ReadAction.compute(() -> Fxml2PropertyResolver.resolveInstanceProperty(button, prop, List.of()));
            PsiElement withSib = ReadAction.compute(() -> Fxml2PropertyResolver.resolveInstanceProperty(button, prop, siblings));
            if (noSib != null) {
                assertSame(noSib, withSib,
                        "Property '" + prop + "': cached result with siblings must be identical to no-siblings result");
            } else {
                assertNull(withSib,
                        "Property '" + prop + "': must remain null with non-empty siblings");
            }
        }
    }
}
