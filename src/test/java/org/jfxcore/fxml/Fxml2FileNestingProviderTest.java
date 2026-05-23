package org.jfxcore.fxml;

import com.intellij.ide.projectView.ProjectViewSettings;
import com.intellij.ide.projectView.impl.nodes.NestingTreeNode;
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode;
import com.intellij.ide.projectView.impl.nodes.PsiFileNode;
import com.intellij.ide.util.treeView.AbstractTreeNode;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Fxml2FileNestingProviderTest extends Fxml2TestBase {

    private static final ProjectViewSettings NESTING_ENABLED = new ProjectViewSettings() {
        @Override
        public boolean isUseFileNestingRules() {
            return true;
        }
    };

    private static final ProjectViewSettings NESTING_DISABLED = new ProjectViewSettings() {
        @Override
        public boolean isUseFileNestingRules() {
            return false;
        }
    };

    @Test
    void nestsJavaCodeBehindOnlyUnderFxml2Documents() {
        PsiFile fxml2 = getFixture().addFileToProject(
            "test/View.fxml",
            fxml2("javafx.scene.control.Button", "  <Button text=\"Hello\"/>\n"));
        PsiFile javaCodeBehind = getFixture().addFileToProject(
            "test/View.java",
            "package test; class View {}\n");
        PsiFile plainFxml = getFixture().addFileToProject(
            "test/Plain.fxml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <?import javafx.scene.layout.BorderPane?>
            <BorderPane xmlns="http://javafx.com/javafx"
                        xmlns:fx="http://javafx.com/fxml"/>
            """);
        PsiFile plainJava = getFixture().addFileToProject(
            "test/Plain.java",
            "package test; class Plain {}\n");

        List<AbstractTreeNode<?>> result = ReadAction.compute(() -> {
            Project project = getFixture().getProject();
            PsiDirectory directory = Objects.requireNonNull(fxml2.getContainingDirectory());
            var provider = new Fxml2FileNestingProvider();
            var parentNode = new PsiDirectoryNode(project, directory, NESTING_ENABLED);
            Collection<AbstractTreeNode<?>> children = List.of(
                new PsiFileNode(project, fxml2, NESTING_ENABLED),
                new PsiFileNode(project, javaCodeBehind, NESTING_ENABLED),
                new PsiFileNode(project, plainFxml, NESTING_ENABLED),
                new PsiFileNode(project, plainJava, NESTING_ENABLED));
            return new ArrayList<>(provider.modify(parentNode, children, NESTING_ENABLED));
        });

        assertEquals(List.of("View.fxml", "Plain.fxml", "Plain.java"), nodeNames(result));

        NestingTreeNode nestingNode = assertInstanceOf(NestingTreeNode.class, result.get(0));
        assertEquals(List.of("View.java"), nodeNames(nestingNode.getNestedFileNodes()));
        assertInstanceOf(PsiFileNode.class, result.get(1), "plain FXML should remain a normal file node");
        assertInstanceOf(PsiFileNode.class, result.get(2), "plain Java file should remain visible at top level");
    }

    @Test
    void leavesProjectViewFlatWhenFileNestingIsDisabled() {
        PsiFile fxml2 = getFixture().addFileToProject(
                "disabled/DisabledView.fxml",
                fxml2("javafx.scene.control.Button", "  <Button/>\n"));
        PsiFile javaCodeBehind = getFixture().addFileToProject(
                "disabled/DisabledView.java",
                "package disabled; class DisabledView {}\n");

        List<AbstractTreeNode<?>> result = ReadAction.compute(() -> {
            Project project = getFixture().getProject();
            PsiDirectory directory = Objects.requireNonNull(fxml2.getContainingDirectory());
            var provider = new Fxml2FileNestingProvider();
            var parentNode = new PsiDirectoryNode(project, directory, NESTING_DISABLED);
            Collection<AbstractTreeNode<?>> children = List.of(
                new PsiFileNode(project, fxml2, NESTING_DISABLED),
                new PsiFileNode(project, javaCodeBehind, NESTING_DISABLED));
            return new ArrayList<>(provider.modify(parentNode, children, NESTING_DISABLED));
        });

        assertEquals(List.of("DisabledView.fxml", "DisabledView.java"), nodeNames(result));
        assertTrue(result.stream().noneMatch(NestingTreeNode.class::isInstance),
                "no nesting node should be created when the user disables file nesting");
    }

    private static List<String> nodeNames(Collection<? extends AbstractTreeNode<?>> nodes) {
        return nodes.stream()
            .map(node -> {
                Object value = node.getValue();
                if (value instanceof PsiFile psiFile) {
                    return psiFile.getName();
                }

                String name = node.getName();
                return name != null ? name : String.valueOf(value);
            })
            .toList();
    }
}
