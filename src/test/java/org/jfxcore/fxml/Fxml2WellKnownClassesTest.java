package org.jfxcore.fxml;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiClass;
import org.jfxcore.fxml.resolve.Fxml2WellKnownClasses;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Fxml2WellKnownClasses} - the per-project cache for commonly-used
 * JavaFX and markup-extension PSI class lookups.
 *
 * <p>The cache must return the correct {@link PsiClass} for each well-known type and,
 * for performance, must return the exact same instance on repeated calls within the
 * same project without re-invoking {@code JavaPsiFacade.findClass}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2WellKnownClassesTest extends Fxml2TestBase {

    // -----------------------------------------------------------------------
    // Correctness: expected FQNs resolve to the right classes
    // -----------------------------------------------------------------------

    /**
     * {@code javafx.beans.value.ObservableValue} must resolve to a non-null class
     * when JavaFX is on the test classpath.
     */
    @Test
    void observableValueResolvesCorrectly() {
        PsiClass cls = ReadAction.compute(() ->
                Fxml2WellKnownClasses.observableValue(getFixture().getProject()));
        assertNotNull(cls, "javafx.beans.value.ObservableValue must resolve on the test classpath");
        assertEquals("javafx.beans.value.ObservableValue", cls.getQualifiedName());
    }

    /**
     * {@code javafx.event.EventHandler} must resolve to a non-null class when JavaFX
     * is on the test classpath.
     */
    @Test
    void eventHandlerResolvesCorrectly() {
        PsiClass cls = ReadAction.compute(() ->
                Fxml2WellKnownClasses.eventHandler(getFixture().getProject()));
        assertNotNull(cls, "javafx.event.EventHandler must resolve on the test classpath");
        assertEquals("javafx.event.EventHandler", cls.getQualifiedName());
    }

    // -----------------------------------------------------------------------
    // Cache identity: the same PsiClass instance must be returned on
    // repeated calls within the same PSI state (no Java modifications).
    // -----------------------------------------------------------------------

    /**
     * Repeated calls to {@link Fxml2WellKnownClasses#observableValue} must return
     * the identical {@code PsiClass} object, demonstrating that the per-project cache
     * is active and no redundant {@code findClass} lookup is performed.
     */
    @Test
    void observableValueReturnsSameInstanceOnRepeatedCalls() {
        PsiClass first  = ReadAction.compute(() ->
                Fxml2WellKnownClasses.observableValue(getFixture().getProject()));
        PsiClass second = ReadAction.compute(() ->
                Fxml2WellKnownClasses.observableValue(getFixture().getProject()));
        PsiClass third  = ReadAction.compute(() ->
                Fxml2WellKnownClasses.observableValue(getFixture().getProject()));

        assertNotNull(first);
        assertSame(first, second, "second call must return the cached PsiClass instance");
        assertSame(first, third,  "third call must return the cached PsiClass instance");
    }

    /**
     * Repeated calls to {@link Fxml2WellKnownClasses#eventHandler} must return
     * the identical {@code PsiClass} object.
     */
    @Test
    void eventHandlerReturnsSameInstanceOnRepeatedCalls() {
        PsiClass first  = ReadAction.compute(() ->
                Fxml2WellKnownClasses.eventHandler(getFixture().getProject()));
        PsiClass second = ReadAction.compute(() ->
                Fxml2WellKnownClasses.eventHandler(getFixture().getProject()));

        assertNotNull(first);
        assertSame(first, second, "second call must return the cached PsiClass instance");
    }

    // -----------------------------------------------------------------------
    // Sanity: unknown FQN must return null without throwing
    // -----------------------------------------------------------------------

    /**
     * Classes that are not on the test classpath (the markup-extension library is not
     * included in the test module) must return {@code null} without throwing.
     * This verifies that the cache correctly stores and returns {@code null}.
     */
    @Test
    void missingLibraryClassReturnsNullGracefully() {
        PsiClass me = ReadAction.compute(() ->
                Fxml2WellKnownClasses.markupExtension(getFixture().getProject()));
        // The markup runtime library is not added to the test module, so null is expected.
        assertNull(me, "markupExtension must return null when the library is absent");

        // Repeated calls to a null-cached entry must also return null (not NPE).
        PsiClass me2 = ReadAction.compute(() ->
                Fxml2WellKnownClasses.markupExtension(getFixture().getProject()));
        assertNull(me2);
    }
}
