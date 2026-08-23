// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlProcessingInstruction;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlTokenType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The processing instructions the markup language defines, and where each one is read.
 *
 * <p>XML permits a processing instruction anywhere in element content, but the language reads
 * only some of them there.  A {@code <?resource ?>} declaration is scoped to the whole document
 * and may appear before, inside, or after the root element; an {@code <?import ?>} or
 * {@code <?prefix ?>} declaration is read only from the document's direct children, so one
 * written inside an element parses cleanly and is then silently ignored.
 *
 * <p>Keeping that rule in one place is what lets the placement inspection and completion agree:
 * completion offers a target exactly where the inspection would not report it.
 */
public enum Fxml2ProcessingInstructionTarget {

    /** Declares a class or package to resolve tag names against. */
    IMPORT("import", Placement.DOCUMENT_LEVEL, "getting-started/standalone.html"),

    /** Declares a markup extension prefix such as {@code %} or {@code @}. */
    PREFIX("prefix", Placement.DOCUMENT_LEVEL, "markup-extension.html#prefix-declarations"),

    /** Declares an embedded resource. */
    RESOURCE("resource", Placement.ANYWHERE, "embedded-resource.html");

    /** Base URL of the online language documentation. */
    private static final String DOCS_BASE_URL = "https://jfxcore.github.io/fxml-compiler/";


    /** Where the language reads a processing instruction. */
    public enum Placement {

        /** Read only from the document's direct children. */
        DOCUMENT_LEVEL,

        /** Read wherever XML permits a processing instruction. */
        ANYWHERE
    }

    private final @NotNull String targetName;
    private final @NotNull Placement placement;
    private final @NotNull String documentationPage;

    Fxml2ProcessingInstructionTarget(
            @NotNull String targetName, @NotNull Placement placement, @NotNull String documentationPage) {
        this.targetName = targetName;
        this.placement = placement;
        this.documentationPage = documentationPage;
    }

    /** Returns the name written after {@code <?}. */
    public @NotNull String targetName() {
        return targetName;
    }

    /** Returns the online documentation page that describes this processing instruction. */
    public @NotNull String documentationUrl() {
        return DOCS_BASE_URL + documentationPage;
    }

    /**
     * Returns {@code true} when an instruction with this target is read at a position enclosed by
     * {@code enclosingElement}, which is {@code null} for a direct child of the document.
     */
    public boolean isReadInside(@Nullable XmlTag enclosingElement) {
        return placement == Placement.ANYWHERE || enclosingElement == null;
    }

    /** Returns the target named {@code targetName}, or {@code null} when the language has none. */
    public static @Nullable Fxml2ProcessingInstructionTarget of(@NotNull String targetName) {
        for (Fxml2ProcessingInstructionTarget target : values()) {
            if (target.targetName.equals(targetName)) return target;
        }
        return null;
    }

    /** Returns the target of {@code instruction}, or {@code null} when the language has none. */
    public static @Nullable Fxml2ProcessingInstructionTarget of(@NotNull XmlProcessingInstruction instruction) {
        ASTNode name = instruction.getNode().findChildByType(XmlTokenType.XML_NAME);
        return name == null ? null : of(name.getText());
    }

    /**
     * Returns the element that encloses {@code element}, or {@code null} when it is at document
     * level.
     *
     * <p>In embedded markup the synthetic wrapper the injector adds around the user's markup is
     * not an element the user wrote, so its direct children are at document level as far as the
     * user is concerned.
     */
    public static @Nullable XmlTag enclosingElement(@NotNull PsiElement element) {
        XmlTag tag = PsiTreeUtil.getParentOfType(element, XmlTag.class);
        return tag != null && Fxml2EmbeddedUtil.isWrapperRoot(tag) ? null : tag;
    }
}
