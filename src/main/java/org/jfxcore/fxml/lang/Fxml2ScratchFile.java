// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.lang.Language;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A light file that stands in a directory, so that the code style configured for that directory
 * applies to it.
 *
 * <p>Formatting a fragment means formatting a file: the fragment is put into a file of its own
 * language, that file is formatted, and the result is read back.  A light file has no place in the
 * file system, so nothing anchors the lookup of an {@code .editorconfig} hierarchy, and the
 * fragment would be formatted with the project-wide settings of its language rather than with the
 * settings that apply where it is actually written.  Naming a directory is what puts it back in
 * place: the platform resolves file-specific code style from the path, and this file reports the
 * path it would have if it lived there.
 */
final class Fxml2ScratchFile extends LightVirtualFile {

    private final @Nullable VirtualFile directory;

    /**
     * @param name      the file name, whose extension decides which {@code .editorconfig} sections
     *                  and file-type settings apply
     * @param language  the language the content is parsed and formatted in
     * @param text      the content
     * @param directory the directory the file stands in, or {@code null} when there is none, in
     *                  which case the project-wide settings of {@code language} apply
     */
    Fxml2ScratchFile(@NotNull String name,
                     @NotNull Language language,
                     @NotNull CharSequence text,
                     @Nullable VirtualFile directory) {
        super(name, language, text);
        this.directory = directory;
    }

    @Override
    public @Nullable VirtualFile getParent() {
        return directory;
    }

    @Override
    public @NotNull String getPath() {
        return directory != null ? directory.getPath() + "/" + getName() : super.getPath();
    }
}
