package org.jfxcore.fxml;

import com.intellij.ide.projectView.ProjectViewNode;
import com.intellij.ide.projectView.ProjectViewSettings;
import com.intellij.ide.projectView.TreeStructureProvider;
import com.intellij.ide.projectView.ViewSettings;
import com.intellij.ide.projectView.impl.nodes.NestingTreeNode;
import com.intellij.ide.projectView.impl.nodes.PsiFileNode;
import com.intellij.ide.util.treeView.AbstractTreeNode;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.lang.Fxml2FileType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Project-view provider that nests Java/Kotlin code-behind files under actual FXML 2.0 documents.
 *
 * <p>{@link com.intellij.ide.projectView.ProjectViewNestingRulesProvider} is insufficient here
 * because it only accepts static suffix pairs ({@code addNestingRule(parentSuffix, childSuffix)})
 * and therefore cannot distinguish a plain JavaFX {@code .fxml} file from a JFXcore FXML 2.0
 * {@code .fxml} file.  We instead inspect the real {@link PsiFile} / {@link VirtualFile} nodes
 * that appear in the project view and only create nesting when the parent file is recognized as
 * FXML 2.0 by {@link Fxml2FileType#isFxml2(PsiFile)}.
 */
public final class Fxml2FileNestingProvider implements TreeStructureProvider, DumbAware {

    @Override
    public @NotNull Collection<AbstractTreeNode<?>> modify(
            @NotNull AbstractTreeNode<?> parent,
            @NotNull Collection<AbstractTreeNode<?>> children,
            @NotNull ViewSettings settings) {

        if (!(settings instanceof ProjectViewSettings projectViewSettings)
                || !projectViewSettings.isUseFileNestingRules()) {
            return children;
        }

        if (!(parent instanceof ProjectViewNode<?> projectViewNode)) {
            return children;
        }

        VirtualFile parentFile = projectViewNode.getVirtualFile();
        if (parentFile == null || !parentFile.isDirectory()) {
            return children;
        }

        List<PsiFileNode> fileNodes = new ArrayList<>();
        for (AbstractTreeNode<?> child : children) {
            if (child instanceof PsiFileNode fileNode) {
                fileNodes.add(fileNode);
            }
        }

        if (fileNodes.size() < 2) {
            return children;
        }

        Map<String, PsiFileNode> fxmlParentsByStem = new LinkedHashMap<>();
        for (PsiFileNode fileNode : fileNodes) {
            if (isFxml2MarkupFile(fileNode) && fileNode.getVirtualFile() instanceof VirtualFile virtualFile) {
                fxmlParentsByStem.putIfAbsent(stem(virtualFile.getName()), fileNode);
            }
        }

        if (fxmlParentsByStem.isEmpty()) {
            return children;
        }

        Map<PsiFileNode, List<PsiFileNode>> nestedChildrenByParent = new LinkedHashMap<>();
        Set<PsiFileNode> hiddenChildren = new HashSet<>();

        for (PsiFileNode fileNode : fileNodes) {
            VirtualFile virtualFile = fileNode.getVirtualFile();
            if (virtualFile == null || !isCodeBehindFileName(virtualFile.getName())) {
                continue;
            }

            PsiFileNode fxmlParent = fxmlParentsByStem.get(stem(virtualFile.getName()));
            if (fxmlParent == null || fxmlParent == fileNode) {
                continue;
            }

            nestedChildrenByParent.computeIfAbsent(fxmlParent, unused -> new ArrayList<>()).add(fileNode);
            hiddenChildren.add(fileNode);
        }

        if (hiddenChildren.isEmpty()) {
            return children;
        }

        List<AbstractTreeNode<?>> result = new ArrayList<>(children.size() - hiddenChildren.size());
        for (AbstractTreeNode<?> child : children) {
            if (!(child instanceof PsiFileNode fileNode)) {
                result.add(child);
                continue;
            }

            if (hiddenChildren.contains(fileNode)) {
                continue;
            }

            List<PsiFileNode> nestedChildren = nestedChildrenByParent.get(fileNode);
            result.add(nestedChildren == null || nestedChildren.isEmpty()
                    ? fileNode
                    : new NestingTreeNode(fileNode, nestedChildren));
        }

        return result;
    }

    private static boolean isCodeBehindFileName(@NotNull String fileName) {
        return fileName.endsWith(".java") || fileName.endsWith(".kt");
    }

    private static @NotNull String stem(@NotNull String fileName) {
        return FileUtilRt.getNameWithoutExtension(fileName);
    }

    private static boolean isFxml2MarkupFile(@NotNull PsiFileNode fileNode) {
        VirtualFile virtualFile = fileNode.getVirtualFile();
        if (virtualFile == null || virtualFile.isDirectory()) {
            return false;
        }

        PsiFile psiFile = fileNode.getValue();
        return psiFile != null ? Fxml2FileType.isFxml2(psiFile)
                               : virtualFile.getFileType() instanceof Fxml2FileType;
    }
}
