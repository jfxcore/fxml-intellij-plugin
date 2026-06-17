package org.jfxcore.fxml;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.IdeaTestUtil;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.common.ThreadLeakTracker;
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor;
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture;
import com.intellij.testFramework.fixtures.MavenDependencyUtil;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.lang.Fxml2BindingSegmentReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Base class for FXML/2 plugin JUnit5 tests.
 *
 * <p>Uses a lightweight in-memory project with JavaFX 25.0.1 added as a module
 * library via the local Gradle/Maven cache, giving tests access to real JavaFX
 * PSI (Button, Region, Pos, SelectionMode, ...).
 *
 * <p>{@code @TestInstance(PER_CLASS)} combined with {@link Fxml2PerClassExtension}
 * ensures the IntelliJ fixture (project + module + libraries) is set up
 * <em>once per test class</em>. Between tests only a lightweight reset is performed
 * (delete newly-added source files, refresh PSI, reset the daemon), avoiding the
 * expensive {@code TOTAL_RESCAN} that a full per-test {@code setUp}/{@code tearDown}
 * cycle would trigger.
 *
 * <p>Subclasses may add common files in a {@code @BeforeAll} method; those files
 * will survive between tests and be cleaned up only in {@code @AfterAll}.
 * Files added inside individual {@code @Test} methods are automatically deleted
 * after each test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class Fxml2TestBase {

    /** Adds JavaFX 25.0.1 (already in the local Gradle cache) as a module library. */
    static final LightProjectDescriptor JAVAFX_DESCRIPTOR =
            new DefaultLightProjectDescriptor() {
                @Override
                public @NotNull Sdk getSdk() {
                    // Use the real JDK from java.home so that java.lang, java.util and other
                    // standard-library classes are fully resolvable via PSI in tests.
                    // Falls back to the mock JDK if java.home is not set.
                    String javaHome = System.getProperty("java.home");
                    if (javaHome != null) {
                        return JavaSdk.getInstance().createJdk("Test JDK", javaHome, false);
                    }
                    return IdeaTestUtil.getMockJdk21();
                }

                @Override
                public void configureModule(@NotNull Module module,
                                            @NotNull ModifiableRootModel model,
                                            @NotNull ContentEntry contentEntry) {
                    super.configureModule(module, model, contentEntry);
                    MavenDependencyUtil.addFromMaven(model, "org.openjfx:javafx-controls:25.0.1");
                    MavenDependencyUtil.addFromMaven(model, "org.openjfx:javafx-fxml:25.0.1");
                }
            };

    /**
     * Per-class fixture extension. Manages the IntelliJ test fixture at class scope,
     * calling {@code setUp}/{@code tearDown} only once per test class.
     *
     * <p>Because this is a {@code @RegisterExtension} field on a
     * {@code @TestInstance(PER_CLASS)} class, one instance is shared across all
     * tests in the class.
     */
    @SuppressWarnings("JUnitMalformedDeclaration") // non-static field is fine with PER_CLASS
    @RegisterExtension
    final Fxml2PerClassExtension fixtureExtension =
            new Fxml2PerClassExtension(JAVAFX_DESCRIPTOR);

    // -----------------------------------------------------------------------
    // Thread-leak tracking

    private Disposable leakTrackerDisposable;

    @BeforeAll
    void registerKnownBackgroundThreads() {
        leakTrackerDisposable = Disposer.newDisposable("Fxml2TestBase.leakTrackerDisposable");
        // AWT on Linux starts SystemPropertyWatcher before the IntelliJ app fully boots,
        // so the thread-leak snapshot may see it as "new". Whitelist it before snapshot.
        //noinspection UnstableApiUsage
        ThreadLeakTracker.longRunningThreadCreated(leakTrackerDisposable,
                "SystemPropertyWatcher",
                "DefaultDispatcher-worker-",
                "I/O pool ",
                "kotlinx.coroutines.DefaultExecutor",
                "Coroutines Debugger Cleaner");
    }

    @AfterAll
    void unregisterKnownBackgroundThreads() {
        if (leakTrackerDisposable != null) {
            Disposer.dispose(leakTrackerDisposable);
            leakTrackerDisposable = null;
        }
    }

    // -----------------------------------------------------------------------
    // Fixture accessor

    /**
     * Returns the shared {@link JavaCodeInsightTestFixture} for this test class.
     * Available after {@code @BeforeAll} has run (i.e., in all {@code @Test} and
     * {@code @BeforeEach}/{@code @AfterEach} methods).
     */
    protected JavaCodeInsightTestFixture getFixture() {
        return fixtureExtension.getFixture();
    }

    // -----------------------------------------------------------------------
    // Helpers

    /**
     * Wraps {@code value} in an IntelliJ highlighting test error marker.
     *
     * <p>Using this helper avoids embedding literal {@code <error descr="...">...</error>}
     * tags directly inside XML string literals, where the angle brackets visually
     * collide with the surrounding FXML markup.
     *
     * @param descr the expected inspection message
     * @param value the source text that should be highlighted
     * @return the marker string, e.g. {@code <error descr="...">value</error>}
     */
    protected static String error(String descr, String value) {
        return "<error descr=\"" + descr + "\">" + value + "</error>";
    }

    /**
     * Builds a minimal FXML/2 document.
     *
     * @param imports newline-separated fully-qualified class names to import
     *                (without the {@code <?import ...?>} wrapper); may be empty
     * @param body    XML content to place inside the root {@code <BorderPane>} tag
     */
    protected static String fxml(String imports, String body) {
        return fxml(imports, body, "test.TestView");
    }

    /**
     * Finds the first {@link Fxml2BindingSegmentReference} at the caret position and
     * returns its resolved target, or {@code null} if none resolves.
     */
    protected PsiElement resolveSegmentAtCaret() {
        return ReadAction.compute(() -> {
            int offset = getFixture().getCaretOffset();
            XmlAttributeValue attrVal = PsiTreeUtil.findElementOfClassAtOffset(
                    getFixture().getFile(), offset, XmlAttributeValue.class, false);
            if (attrVal == null) return null;
            int relOffset = offset - attrVal.getTextRange().getStartOffset();
            for (PsiReference ref : attrVal.getReferences()) {
                if (!(ref instanceof Fxml2BindingSegmentReference)) continue;
                if (ref.getRangeInElement().containsOffset(relOffset)) {
                    return ref.resolve();
                }
            }
            return null;
        });
    }

    protected static String fxml(String imports, String body, String fxClass) {
        var sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        // Always import the root tag class so it never triggers a false-positive error
        sb.append("<?import javafx.scene.layout.BorderPane?>\n");
        for (String imp : imports.split("\\R")) {
            String trimmed = imp.trim();
            if (!trimmed.isEmpty()) {
                sb.append("<?import ").append(trimmed).append("?>\n");
            }
        }
        sb.append("<BorderPane xmlns=\"http://javafx.com/javafx\"\n");
        sb.append("            xmlns:fx=\"http://jfxcore.org/fxml/2.0\"\n");
        sb.append("            fx:subclass=\"").append(fxClass).append("\">\n");
        sb.append(body);
        sb.append("</BorderPane>\n");
        return sb.toString();
    }
}
