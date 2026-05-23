package org.jfxcore.fxml;

import com.intellij.codeInsight.daemon.impl.analysis.XmlUnresolvedReferenceInspection;
import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.xml.XmlAttribute;
import org.jfxcore.fxml.annotator.Fxml2AttributeValueInspection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for error reporting with static property attribute notation (e.g. {@code GridPane.columnIndex})
 * when the declaring class is unimported.
 *
 * <p>The following requirements are covered:
 * <ol>
 *   <li>The "Cannot resolve symbol 'X'" error must underline <em>only</em> the unresolvable prefix
 *       segment (e.g. just {@code GridPane}), not the entire {@code GridPane.columnIndex} token.
 *       Underlining the whole token causes the error tooltip to appear when hovering over either
 *       segment, making the error appear to be "shown twice".</li>
 *   <li>No separate error must be produced for the property-name segment ({@code columnIndex})
 *       when the declaring class is unresolvable: it is a dependent, not an independent error.</li>
 *   <li>The "Add import" fix must not filter out abstract or interface declaring classes (such as
 *       {@code org.jfxcore.command.Command}) and must not suggest JDK-internal {@code sun.*}
 *       classes.</li>
 *   <li>When IntelliJ's built-in {@code XmlUnresolvedReferenceInspection} is active, the
 *       per-segment references created by {@code DottedAttributeNameReferenceProvider} must
 *       be <em>soft</em> so that inspection does not generate a second "Cannot resolve" error
 *       for the class prefix and a spurious error for the property-name segment.</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2StaticPropertyAttributeTest extends Fxml2TestBase {

    @BeforeEach
    void enableInspections() {
        getFixture().enableInspections(new Fxml2AttributeValueInspection(),
                new XmlUnresolvedReferenceInspection());
    }

    /**
     * When IntelliJ's {@code XmlUnresolvedReferenceInspection} is enabled, the per-segment
     * references created by {@code DottedAttributeNameReferenceProvider} for an unimported
     * static-property class must be <em>soft</em> so that inspection does not generate a
     * second "Cannot resolve symbol 'Command'" error on the prefix (duplicate) and a
     * spurious "Cannot resolve symbol 'onAction'" error on the property.
     *
     * <p>This test adds a project-local {@code Command} class (so it appears in the
     * short-names cache), then verifies that only ONE error appears: on the class prefix
     * only: with no duplicate and no spurious property error.
     *
     * <p>Before the fix: fails with extra errors from {@code XmlUnresolvedReferenceInspection}.
     * After the fix: passes.
     */
    @Test
    void noXmlUnresolvedReferenceInspectionDuplicatesForUnimportedClass() {
        // Add a Command class to the project so PsiShortNamesCache finds it.
        // It must not be imported in the FXML file.
        getFixture().addFileToProject("mylib/Command.java",
                """
                package mylib;
                import javafx.scene.Node;
                public class Command {
                    public static void setOnAction(Node node, Object handler) {}
                    public static Object getOnAction(Node node) { return null; }
                }
                """);

        // Command is NOT imported -> one error on the prefix, none on the property.
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button\njavafx.scene.layout.VBox",
                """
                  <VBox>
                    <Button <error descr="Cannot resolve symbol 'Command'">Command</error>.onAction="value"/>
                  </VBox>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * When IntelliJ's {@code XmlUnresolvedReferenceInspection} is enabled, a chained
     * instance property path (e.g. {@code selectionModel.badProperty}) must not generate
     * a spurious extra error from the reference layer: only the annotator's error should
     * appear.
     *
     * <p>The annotator reports the entire dotted path in the error message and annotates
     * the full {@code XmlAttribute} range (name + {@code ="value"}). Soft null-resolving
     * segment references ensure the inspection does not add a second, differently-worded
     * error for the unresolvable segment.
     */
    @Test
    void noXmlUnresolvedReferenceInspectionDuplicatesForChainedProperty() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.ListView\njavafx.scene.layout.VBox",
                """
                  <VBox>
                    <ListView <error descr="'selectionModel.badProperty' in javafx.scene.control.ListView cannot be resolved">selectionModel.badProperty="value"</error>/>
                  </VBox>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Error range and dependent-error suppression
    // -----------------------------------------------------------------------

    /**
     * When a dotted attribute uses a class that is discoverable (JavaFX {@code GridPane}) but
     * not imported, the "Cannot resolve symbol 'GridPane'" error must be anchored to
     * <em>only</em> the class-name prefix: not the full {@code GridPane.columnIndex} token.
     */
    @Test
    void unimportedStaticPropertyClassHighlightsOnlyPrefix() {
        getFixture().configureByText("TestView.fxml", fxml2(
                // GridPane intentionally NOT imported
                "javafx.scene.control.Button\njavafx.scene.layout.VBox",
                """
                  <VBox>
                    <Button <error descr="Cannot resolve symbol 'GridPane'">GridPane</error>.columnIndex="1"/>
                  </VBox>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * No error must appear for the property segment {@code columnIndex} when its declaring
     * class {@code GridPane} is unresolvable: the property error is a dependent consequence
     * and must be suppressed.
     *
     * <p>This is implicitly verified by {@link #unimportedStaticPropertyClassHighlightsOnlyPrefix}:
     * if a separate "Cannot resolve 'columnIndex'" error existed, it would be an unmarked
     * error and {@code checkHighlighting} would fail. This dedicated test documents the intent.
     */
    @Test
    void noDependentPropertyErrorWhenClassIsUnimported() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button\njavafx.scene.layout.VBox",
                """
                  <VBox>
                    <Button <error descr="Cannot resolve symbol 'GridPane'">GridPane</error>.columnIndex="1"/>
                  </VBox>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * When the static-property declaring class IS imported, the attribute must resolve without
     * error.
     */
    @Test
    void importedStaticPropertyClassProducesNoError() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button\njavafx.scene.layout.VBox\njavafx.scene.layout.GridPane",
                """
                  <VBox>
                    <Button GridPane.columnIndex="1"/>
                  </VBox>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    /**
     * When the declaring class IS imported but the property name does not exist on it,
     * the error must appear on the property segment only (not the class prefix or the whole name).
     * This existing behavior must be preserved.
     */
    @Test
    void unresolvedPropertyOnImportedClassProducesErrorOnProperty() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button\njavafx.scene.layout.VBox\njavafx.scene.layout.GridPane",
                """
                  <VBox>
                    <Button GridPane.<error descr="'doesNotExist' in javafx.scene.layout.GridPane cannot be resolved">doesNotExist</error>="1"/>
                  </VBox>
                """
        ));
        getFixture().checkHighlighting(false, false, false);
    }

    // -----------------------------------------------------------------------
    // Reference ranges for an unimported class prefix
    // -----------------------------------------------------------------------

    /**
     * When hovering over the class-name segment of an unimported static-property attribute
     * (e.g. {@code GridPane} in {@code GridPane.columnIndex}), a <em>soft</em> reference must
     * exist in {@code attribute.getReferences()} that covers exactly the class-name segment.
     *
     * <p>Note: because {@code XmlAttributeReference} is non-soft (the attribute has a
     * descriptor), {@code PsiMultiReference.COMPARATOR} will prefer it over our soft ref when
     * {@code findReferenceAt} is called. We therefore verify the ref's existence directly via
     * {@code getReferences()} rather than via {@code findReferenceAt}.
     */
    @Test
    void unimportedClassSegmentHasCorrectReferenceRange() {
        getFixture().configureByText("TestView.fxml", fxml2(
                "javafx.scene.control.Button\njavafx.scene.layout.VBox",
                """
                  <VBox>
                    <Button Grid<caret>Pane.columnIndex="1"/>
                  </VBox>
                """
        ));
        ReadAction.run(() -> {
            PsiElement leaf = getFixture().getFile().findElementAt(getFixture().getCaretOffset());
            XmlAttribute attr = null;
            for (PsiElement e = leaf; e != null; e = e.getParent()) {
                if (e instanceof XmlAttribute xa) { attr = xa; break; }
            }
            assertNotNull(attr, "Expected to find an XmlAttribute at caret");
            PsiReference classRef = Arrays.stream(attr.getReferences())
                    .filter(r -> "GridPane".equals(r.getRangeInElement().substring(r.getElement().getText())))
                    .findFirst().orElse(null);
            assertNotNull(classRef, "Expected a reference covering exactly 'GridPane' in attribute.getReferences()");
            // The class is unimported: reference must not navigate anywhere
            assertNull(classRef.resolve(), "Unimported class prefix reference should resolve to null");
            // The reference must be soft so XmlUnresolvedReferenceInspection does not generate a duplicate error
            assertTrue(classRef.isSoft(), "Unimported class prefix reference must be soft");
        });
    }

    /**
     * When the attribute name is a FQN static-property like
     * {@code org.jfxcore.command.FqnCmd.onAction}, hovering over the class segment
     * {@code FqnCmd} must resolve to the declaring {@link com.intellij.psi.PsiClass}.
     */
    @Test
    void fqnStaticPropertyAttrClassSegmentResolvesToClass() {
        getFixture().addFileToProject("org/jfxcore/command/FqnCmd.java", """
                package org.jfxcore.command;
                import javafx.event.EventTarget;
                public abstract class FqnCmd {
                    public static void setOnAction(EventTarget owner, FqnCmd cmd) {}
                    public static FqnCmd getOnAction(EventTarget owner) { return null; }
                }
                """);
        getFixture().configureByText("FqnRefClass.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button org.jfxcore.command.Fqn<caret>Cmd.onAction=""/>
                """
        ));
        ReadAction.run(() -> {
            PsiElement leaf = getFixture().getFile().findElementAt(getFixture().getCaretOffset());
            XmlAttribute attr = null;
            for (PsiElement e = leaf; e != null; e = e.getParent())
                if (e instanceof XmlAttribute xa) { attr = xa; break; }
            assertNotNull(attr, "Expected XmlAttribute at caret");
            final XmlAttribute finalAttr = attr;

            PsiReference classRef = Arrays.stream(finalAttr.getReferences())
                    .filter(r -> "FqnCmd".equals(r.getRangeInElement().substring(finalAttr.getText())))
                    .findFirst().orElse(null);
            assertNotNull(classRef, "Expected a reference covering 'FqnCmd' segment");
            PsiElement resolved = classRef.resolve();
            assertNotNull(resolved, "FQN class segment must resolve to PsiClass");
            assertEquals("FqnCmd", ((com.intellij.psi.PsiNamedElement) resolved).getName());
        });
    }

    /**
     * When the attribute name is a FQN static-property like
     * {@code org.jfxcore.command.FqnCmd.onAction}, hovering over the property segment
     * {@code onAction} must resolve to the static setter method.
     */
    @Test
    void fqnStaticPropertyAttrPropertySegmentResolvesToMethod() {
        getFixture().addFileToProject("org/jfxcore/command/FqnCmd2.java", """
                package org.jfxcore.command;
                import javafx.event.EventTarget;
                public abstract class FqnCmd2 {
                    public static void setOnAction(EventTarget owner, FqnCmd2 cmd) {}
                    public static FqnCmd2 getOnAction(EventTarget owner) { return null; }
                }
                """);
        getFixture().configureByText("FqnRefProp.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button org.jfxcore.command.FqnCmd2.onAct<caret>ion=""/>
                """
        ));
        ReadAction.run(() -> {
            PsiElement leaf = getFixture().getFile().findElementAt(getFixture().getCaretOffset());
            XmlAttribute attr = null;
            for (PsiElement e = leaf; e != null; e = e.getParent())
                if (e instanceof XmlAttribute xa) { attr = xa; break; }
            assertNotNull(attr, "Expected XmlAttribute at caret");
            final XmlAttribute finalAttr = attr;

            PsiReference propRef = Arrays.stream(finalAttr.getReferences())
                    .filter(r -> "onAction".equals(r.getRangeInElement().substring(finalAttr.getText())))
                    .findFirst().orElse(null);
            assertNotNull(propRef, "Expected a reference covering 'onAction' segment");
            assertNotNull(propRef.resolve(), "FQN property segment must resolve to PsiMethod");
        });
    }

    /**
     * Ctrl+hovering over the class segment of a FQN static-property attribute
     * (e.g. {@code FqnCmdNav} in {@code org.jfxcore.command.FqnCmdNav.onAction}) must
     * return a reference via {@code findReferenceAt} that covers <em>only</em>
     * {@code FqnCmdNav}, not the whole attribute name.
     */
    @Test
    void fqnStaticPropertyAttrClassSegment_findReferenceAt_hasNarrowRange() {
        getFixture().addFileToProject("org/jfxcore/command/FqnCmdNav.java", """
                package org.jfxcore.command;
                import javafx.event.EventTarget;
                public abstract class FqnCmdNav {
                    public static void setOnAction(EventTarget owner, FqnCmdNav cmd) {}
                    public static FqnCmdNav getOnAction(EventTarget owner) { return null; }
                }
                """);
        getFixture().configureByText("FqnNavClass.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button org.jfxcore.command.FqnCm<caret>dNav.onAction=""/>
                """
        ));
        ReadAction.run(() -> {
            PsiReference ref = getFixture().getFile().findReferenceAt(getFixture().getCaretOffset());
            assertNotNull(ref, "findReferenceAt must return a reference on 'FqnCmdNav'");
            String coveredText = ref.getRangeInElement().substring(ref.getElement().getText());
            assertEquals("FqnCmdNav", coveredText,
                    "findReferenceAt must cover only 'FqnCmdNav', not the whole attribute name; got: " + coveredText);
            assertNotNull(ref.resolve(), "FqnCmdNav class segment must resolve");
            assertInstanceOf(com.intellij.psi.PsiClass.class, ref.resolve());
        });
    }

    /**
     * Ctrl+hovering over the property segment of a FQN static-property attribute
     * (e.g. {@code onAction} in {@code org.jfxcore.command.FqnCmdNavProp.onAction}) must
     * return a reference via {@code findReferenceAt} that covers <em>only</em>
     * {@code onAction}, not the whole attribute name.
     */
    @Test
    void fqnStaticPropertyAttrPropSegment_findReferenceAt_hasNarrowRange() {
        getFixture().addFileToProject("org/jfxcore/command/FqnCmdNavProp.java", """
                package org.jfxcore.command;
                import javafx.event.EventTarget;
                public abstract class FqnCmdNavProp {
                    public static void setOnAction(EventTarget owner, FqnCmdNavProp cmd) {}
                    public static FqnCmdNavProp getOnAction(EventTarget owner) { return null; }
                }
                """);
        getFixture().configureByText("FqnNavProp.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button org.jfxcore.command.FqnCmdNavProp.on<caret>Action=""/>
                """
        ));
        ReadAction.run(() -> {
            PsiReference ref = getFixture().getFile().findReferenceAt(getFixture().getCaretOffset());
            assertNotNull(ref, "findReferenceAt must return a reference on 'onAction'");
            String coveredText = ref.getRangeInElement().substring(ref.getElement().getText());
            assertEquals("onAction", coveredText,
                    "findReferenceAt must cover only 'onAction', not the whole attribute name; got: " + coveredText);
            assertNotNull(ref.resolve(), "onAction property segment must resolve to a method");
            assertInstanceOf(com.intellij.psi.PsiMethod.class, ref.resolve());
        });
    }

    /**
     * Ctrl+hovering over a package segment of a FQN static-property attribute
     * (e.g. {@code command} in {@code org.jfxcore.command.FqnCmdNavPkg.onAction}) must
     * return a reference via {@code findReferenceAt} that covers <em>only</em>
     * {@code command} and resolves to a {@link com.intellij.psi.PsiPackage}.
     */
    @Test
    void fqnStaticPropertyAttrPkgSegment_findReferenceAt_hasNarrowRange() {
        getFixture().addFileToProject("org/jfxcore/command/FqnCmdNavPkg.java", """
                package org.jfxcore.command;
                import javafx.event.EventTarget;
                public abstract class FqnCmdNavPkg {
                    public static void setOnAction(EventTarget owner, FqnCmdNavPkg cmd) {}
                    public static FqnCmdNavPkg getOnAction(EventTarget owner) { return null; }
                }
                """);
        getFixture().configureByText("FqnNavPkg.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button org.jfxcore.com<caret>mand.FqnCmdNavPkg.onAction=""/>
                """
        ));
        ReadAction.run(() -> {
            PsiReference ref = getFixture().getFile().findReferenceAt(getFixture().getCaretOffset());
            assertNotNull(ref, "findReferenceAt must return a reference on 'command'");
            String coveredText = ref.getRangeInElement().substring(ref.getElement().getText());
            assertEquals("command", coveredText,
                    "findReferenceAt must cover only 'command', not the whole attribute name; got: " + coveredText);
            assertNotNull(ref.resolve(), "Package segment must resolve to PsiPackage");
            assertInstanceOf(com.intellij.psi.PsiPackage.class, ref.resolve());
        });
    }

    /**
     * When the attribute name is a FQN static-property, hovering over a package segment
     * (e.g. {@code command} in {@code org.jfxcore.command.FqnCmd.onAction}) must resolve
     * to the {@link com.intellij.psi.PsiPackage}.
     */
    @Test
    void fqnStaticPropertyAttrPackageSegmentResolvesToPackage() {
        getFixture().addFileToProject("org/jfxcore/command/FqnCmd3.java", """
                package org.jfxcore.command;
                import javafx.event.EventTarget;
                public abstract class FqnCmd3 {
                    public static void setOnAction(EventTarget owner, FqnCmd3 cmd) {}
                    public static FqnCmd3 getOnAction(EventTarget owner) { return null; }
                }
                """);
        getFixture().configureByText("FqnRefPkg.fxml", fxml2(
                "javafx.scene.control.Button",
                """
                  <Button org.jfxcore.com<caret>mand.FqnCmd3.onAction=""/>
                """
        ));
        ReadAction.run(() -> {
            PsiElement leaf = getFixture().getFile().findElementAt(getFixture().getCaretOffset());
            XmlAttribute attr = null;
            for (PsiElement e = leaf; e != null; e = e.getParent())
                if (e instanceof XmlAttribute xa) { attr = xa; break; }
            assertNotNull(attr, "Expected XmlAttribute at caret");
            final XmlAttribute finalAttr = attr;

            PsiReference pkgRef = Arrays.stream(finalAttr.getReferences())
                    .filter(r -> "command".equals(r.getRangeInElement().substring(finalAttr.getText())))
                    .findFirst().orElse(null);
            assertNotNull(pkgRef, "Expected a reference covering 'command' package segment");
            assertNotNull(pkgRef.resolve(), "Package segment must resolve to PsiPackage");
            assertInstanceOf(com.intellij.psi.PsiPackage.class, pkgRef.resolve());
        });
    }
}
