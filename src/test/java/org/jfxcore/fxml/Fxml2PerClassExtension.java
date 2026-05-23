package org.jfxcore.fxml;

import com.intellij.application.options.CodeStyle;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzerSettings;
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiManager;
import com.intellij.psi.impl.PsiManagerEx;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.IndexingTestUtil;
import com.intellij.testFramework.InspectionsKt;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.TestApplicationManager;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture;
import com.intellij.testFramework.fixtures.JavaTestFixtureFactory;
import com.intellij.testFramework.fixtures.impl.LightTempDirTestFixtureImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * JUnit 5 extension that manages the IntelliJ test fixture at <em>class</em> scope,
 * calling {@code setUp}/{@code tearDown} only <strong>once per test class</strong>
 * instead of once per test.
 *
 * <p>This avoids the expensive {@code TOTAL_RESCAN} + indexing-wait that IntelliJ's
 * per-test {@code setUp()} triggers, while still providing full test isolation:
 * after each test the extension deletes every source file that was <em>newly added</em>
 * by that test (files added by a {@code @BeforeAll} setup method are preserved).
 *
 * <p>Usage: register as a field in a {@code @TestInstance(PER_CLASS)} test class:
 * <pre>{@code
 * @RegisterExtension
 * final Fxml2PerClassExtension fixtureExtension = new Fxml2PerClassExtension(DESCRIPTOR);
 * }</pre>
 * then expose the fixture via {@code fixtureExtension.getFixture()}.
 *
 * <p>Extension callback order with {@code @TestInstance(PER_CLASS)}:
 * <ol>
 *   <li>{@link #beforeAll}: create &amp; setUp fixture, once</li>
 *   <li>Test class {@code @BeforeAll} methods</li>
 *   <li>{@link #beforeEach}: take source-root snapshot, reset daemon/code-style</li>
 *   <li>Test class {@code @BeforeEach} methods</li>
 *   <li>Test method</li>
 *   <li>Test class {@code @AfterEach} methods</li>
 *   <li>{@link #afterEach}: delete new files, wait for indexes, PSI cleanup</li>
 *   <li>Test class {@code @AfterAll} methods</li>
 *   <li>{@link #afterAll}: tearDown fixture, once</li>
 * </ol>
 */
@SuppressWarnings("UnstableApiUsage")
final class Fxml2PerClassExtension
        implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {

    private final LightProjectDescriptor descriptor;

    /**
     * The shared fixture for all tests in the class.
     * Initialized in {@link #beforeAll}, torn down in {@link #afterAll}.
     * Non-null between beforeAll and afterAll.
     */
    private @Nullable JavaCodeInsightTestFixture fixture;

    /**
     * Deep snapshot of all paths inside the source root, taken at the start of each test
     * (after {@code @BeforeAll} but before the individual test runs). Used in
     * {@link #afterEach} to identify and delete only the files that <em>this test</em> added.
     */
    private @Nullable Set<String> preTestSnapshot;

    /**
     * Per-test disposable that owns a freshly-created empty inspection profile.
     * {@code fixture.enableInspections()} registers its cleanup on the fixture's
     * {@code testRootDisposable} (class-scoped), so the inspection would remain enabled
     * for all subsequent tests unless we proactively reset the profile before each test.
     * We do that by calling {@link InspectionsKt#configureInspections} in
     * {@link #beforeEach} with this disposable, then disposing it in {@link #afterEach},
     * which deletes the profile and sets the current profile to {@code null}.
     */
    private @Nullable Disposable perTestInspectionDisposable;

    Fxml2PerClassExtension(@NotNull LightProjectDescriptor descriptor) {
        this.descriptor = descriptor;
    }

    // ------------------------------------------------------------------
    // Class-level lifecycle

    @Override
    public void beforeAll(ExtensionContext ctx) throws Exception {
        // Start the IntelliJ platform application if not already running.
        TestApplicationManager.getInstance();

        // Create and setUp the code-insight fixture ONCE for this test class.
        String testName = ctx.getRequiredTestClass().getSimpleName();
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        var builder = factory.createLightFixtureBuilder(descriptor, testName);
        var projectFixture = builder.getFixture();

        fixture = JavaTestFixtureFactory.getFixtureFactory()
                .createCodeInsightFixture(projectFixture, new LightTempDirTestFixtureImpl(true));
        fixture.setTestDataPath("src/test/resources");
        fixture.setUp();
    }

    @Override
    public void afterAll(ExtensionContext ctx) throws Exception {
        if (fixture != null) {
            try {
                fixture.tearDown();
            } finally {
                fixture = null;
            }
        }
    }

    // ------------------------------------------------------------------
    // Per-test lifecycle (lightweight, no full setUp/tearDown)

    @Override
    public void beforeEach(ExtensionContext ctx) throws Exception {
        Project project = requireFixture().getProject();

        // Take a deep snapshot of every path inside the source root BEFORE the test
        // body runs. @BeforeAll-added files will be present here and therefore
        // excluded from the per-test cleanup in afterEach.
        VirtualFile sourceRoot = getSourceRoot();
        preTestSnapshot = (sourceRoot != null)
                ? collectAllPaths(sourceRoot)
                : Collections.emptySet();

        EdtTestUtil.runInEdtAndWait(() -> {
            // Create a fresh, empty inspection profile for this test so that any
            // inspection enabled by a previous test (via fixture.enableInspections())
            // does not bleed in.  fixture.enableInspections() registers its cleanup
            // on the class-scoped testRootDisposable; by giving each test its own
            // per-test disposable that owns the "active" profile we can dispose it in
            // afterEach and get a clean state regardless of what testRootDisposable does.
            perTestInspectionDisposable =
                    Disposer.newDisposable("Fxml2PerClassExtension.perTest");
            InspectionsKt.configureInspections(
                    LocalInspectionTool.EMPTY_ARRAY, project, perTestInspectionDisposable);

            // Per-test IntelliJ state reset (mirrors what CodeInsightTestFixtureImpl.setUp does).
            ((DaemonCodeAnalyzerImpl) DaemonCodeAnalyzer.getInstance(project)).prepareForTest();
            DaemonCodeAnalyzerSettings.getInstance().setImportHintEnabled(false);
            CodeStyle.setTemporarySettings(project, CodeStyle.createTestSettings());
        });
    }

    @Override
    public void afterEach(ExtensionContext ctx) {
        Project project = requireFixture().getProject();

        EdtTestUtil.runInEdtAndWait(() -> {
            CodeStyle.dropTemporarySettings(project);
            PsiDocumentManager.getInstance(project).commitAllDocuments();
            // Dispose the per-test inspection disposable.  This triggers the cleanup
            // registered by configureInspections (in beforeEach): deletes the per-test
            // inspection profile and calls setCurrentProfile(null).  Any inspections
            // that were enabled via fixture.enableInspections() during this test were
            // added to that per-test profile, so disposing it effectively un-enables them
            // even though fixture.enableInspections() registered their "undo" cleanup on
            // the longer-lived testRootDisposable.
            if (perTestInspectionDisposable != null) {
                Disposer.dispose(perTestInspectionDisposable);
                perTestInspectionDisposable = null;
            }
        });

        // Delete only the files added by this specific test, leaving @BeforeAll files intact.
        VirtualFile sourceRoot = getSourceRoot();
        if (sourceRoot != null && preTestSnapshot != null) {
            Set<String> snapshot = preTestSnapshot;
            WriteCommandAction.runWriteCommandAction(project,
                    () -> deleteNewFiles(sourceRoot, snapshot));
        }
        preTestSnapshot = null;

        // Wait for the incremental index update triggered by the deletions above.
        IndexingTestUtil.waitUntilIndexesAreReady(project);

        // Per-test PSI cleanup (mirrors what tearDownProjectAndApp does).
        EdtTestUtil.runInEdtAndWait(() ->
                ((PsiManagerEx) PsiManager.getInstance(project)).cleanupForNextTest());
    }

    // ------------------------------------------------------------------
    // Accessor

    @NotNull JavaCodeInsightTestFixture getFixture() {
        return requireFixture();
    }

    // ------------------------------------------------------------------
    // Private helpers

    /** Returns the first source root of the test module, or {@code null} if none. */
    @Nullable
    private VirtualFile getSourceRoot() {
        JavaCodeInsightTestFixture f = fixture;
        if (f == null) return null;
        VirtualFile[] roots =
                ModuleRootManager.getInstance(f.getModule()).getSourceRoots(false);
        return roots.length > 0 ? roots[0] : null;
    }

    @NotNull
    private JavaCodeInsightTestFixture requireFixture() {
        JavaCodeInsightTestFixture f = fixture;
        if (f == null) {
            throw new IllegalStateException(
                    "Fixture is null: did beforeAll run successfully?");
        }
        return f;
    }

    /**
     * Collects the paths of all files and directories inside {@code dir}
     * (the dir itself is NOT included: only its descendants).
     */
    @NotNull
    private static Set<String> collectAllPaths(@NotNull VirtualFile dir) {
        Set<String> paths = new HashSet<>();
        collectInto(dir, paths);
        return paths;
    }

    private static void collectInto(@NotNull VirtualFile dir, @NotNull Set<String> paths) {
        for (VirtualFile child : dir.getChildren()) {
            paths.add(child.getPath());
            if (child.isDirectory()) {
                collectInto(child, paths);
            }
        }
    }

    /**
     * Recursively deletes from {@code dir} every file or directory whose path is
     * <em>not</em> present in {@code snapshot}. Directories that existed before the
     * test are recursed into so that any new files added inside them are also removed.
     */
    private static void deleteNewFiles(@NotNull VirtualFile dir,
                                       @NotNull Set<String> snapshot) {
        for (VirtualFile child : dir.getChildren()) {
            if (!snapshot.contains(child.getPath())) {
                // New item (file or whole subtree): delete it.
                try {
                    child.delete(Fxml2PerClassExtension.class);
                } catch (IOException ignored) {
                    // Best-effort; next test will see a stale file but that's
                    // better than aborting the cleanup of other files.
                }
            } else if (child.isDirectory()) {
                // Pre-existing directory: recurse to clean new children inside it.
                deleteNewFiles(child, snapshot);
            }
            // Pre-existing regular file: leave it alone.
        }
    }
}
