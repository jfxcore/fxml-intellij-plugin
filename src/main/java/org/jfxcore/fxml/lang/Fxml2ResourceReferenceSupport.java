// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFileSystemItem;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiReference;
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReferenceSet;
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReference;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resource.Fxml2ResourceInvocation;
import org.jfxcore.fxml.resource.Fxml2ResourceLoader;
import org.jfxcore.fxml.resolve.Fxml2BindingPathResolver;
import org.jfxcore.fxml.resolve.Fxml2CodeBehindName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared resource navigation for prefix and brace notation. */
public final class Fxml2ResourceReferenceSupport {
    private Fxml2ResourceReferenceSupport() {}

    public static void contribute(@NotNull List<PsiReference> references,
                                  @NotNull XmlAttributeValue value,
                                  @NotNull Fxml2ResourceInvocation invocation,
                                  int contentOffset, @NotNull XmlFile file) {
        if (Fxml2ResourceLoader.resolve(invocation, file) != Fxml2ResourceLoader.DEFAULT) return;
        var range = invocation.nameSpan().shifted(contentOffset).toTextRange();
        var embedded = new Fxml2ResourceNameReference(value, range, invocation.name().value(), file);
        if (embedded.isDeclared()) {
            references.add(embedded);
            return;
        }
        String path = invocation.name().value();
        if (path.isEmpty()) return;
        FileReferenceSet set = new ResourceFileReferenceSet(value, invocation, contentOffset);
        set.addCustomization(FileReferenceSet.DEFAULT_PATH_EVALUATOR_OPTION,
                ignored -> resourceContexts(file, path.startsWith("/")));
        Collections.addAll(references, set.getAllReferences());
    }

    private static final class ResourceFileReferenceSet extends FileReferenceSet {
        private final Fxml2ResourceInvocation invocation;
        private final int contentOffset;

        private ResourceFileReferenceSet(XmlAttributeValue value, Fxml2ResourceInvocation invocation, int contentOffset) {
            super(invocation.name().value(), value, contentOffset + invocation.sourceOffsets().getFirst(),
                    null, true, false, null, false);
            this.invocation = invocation;
            this.contentOffset = contentOffset;
            reparse();
        }

        @Override
        protected List<FileReference> reparse(String path, int startInElement) {
            List<FileReference> references = new ArrayList<>();
            int begin = path.startsWith("/") ? 1 : 0;
            for (int end = begin; end <= path.length(); end++) {
                if (end == path.length() || path.charAt(end) == '/') {
                    var range = invocation.sourceSpan(begin, end).shifted(contentOffset).toTextRange();
                    references.add(new FileReference(this, range, references.size(), path.substring(begin, end)));
                    begin = end + 1;
                }
            }
            return references;
        }
    }

    private static @NotNull List<PsiFileSystemItem> resourceContexts(@NotNull XmlFile file, boolean absolute) {
        var host = Fxml2EmbeddedUtil.getHostClass(file);
        var codeBehind = host != null ? host : Fxml2BindingPathResolver.resolveCodeBehindClass(file);
        var topFile = host != null ? host.getContainingFile() : file.getOriginalFile();
        var module = ModuleUtilCore.findModuleForPsiElement(codeBehind != null ? codeBehind : topFile);
        if (module == null) return List.of();
        String packagePath = "";
        if (!absolute) {
            String declaredName = codeBehind != null ? codeBehind.getQualifiedName() : null;
            var root = Fxml2BindingPathResolver.effectiveRootTag(file);
            if (declaredName == null && root != null) declaredName = root.getAttributeValue("fx:subclass");
            var name = Fxml2CodeBehindName.parse(declaredName);
            if (name != null) packagePath = name.packageName().replace('.', '/');
            else {
                VirtualFile virtualFile = topFile.getVirtualFile();
                if (virtualFile == null || virtualFile.getParent() == null) return List.of();
                var sourceRoot = ProjectFileIndex.getInstance(file.getProject()).getSourceRootForFile(virtualFile);
                var relative = sourceRoot == null ? null : VfsUtilCore.getRelativePath(virtualFile.getParent(), sourceRoot, '/');
                if (relative == null) {
                    var directory = PsiManager.getInstance(file.getProject()).findDirectory(virtualFile.getParent());
                    return directory != null ? List.of(directory) : List.of();
                }
                packagePath = relative;
            }
        }
        List<PsiFileSystemItem> contexts = new ArrayList<>();
        for (VirtualFile root : ModuleRootManager.getInstance(module).getSourceRoots()) {
            VirtualFile directory = packagePath.isEmpty() ? root : root.findFileByRelativePath(packagePath);
            if (directory == null || !directory.isDirectory()) continue;
            var context = PsiManager.getInstance(file.getProject()).findDirectory(directory);
            if (context != null) contexts.add(context);
        }
        return contexts;
    }
}
