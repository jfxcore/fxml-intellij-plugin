// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.formatting.Alignment;
import com.intellij.formatting.Indent;
import com.intellij.formatting.Wrap;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.formatter.xml.XmlBlock;
import com.intellij.psi.formatter.xml.XmlFormattingPolicy;
import com.intellij.psi.formatter.xml.XmlTagBlock;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The formatting block of an element of a standalone FXML/2 document.
 *
 * <p>It exists so that the blocks built for the content of an element are FXML/2 blocks as well,
 * which is what keeps a {@code <?resource ?>} declaration verbatim wherever it is written.
 *
 * @see Fxml2XmlBlock for the rule the declaration is formatted by
 */
final class Fxml2XmlTagBlock extends XmlTagBlock {

    Fxml2XmlTagBlock(@NotNull ASTNode node,
                     @Nullable Wrap wrap,
                     @Nullable Alignment alignment,
                     @NotNull XmlFormattingPolicy policy,
                     @Nullable Indent indent,
                     boolean preserveSpace) {
        super(node, wrap, alignment, policy, indent, preserveSpace);
    }

    @Override
    protected @NotNull XmlBlock createSimpleChild(@NotNull ASTNode child,
                                                  @Nullable Indent indent,
                                                  @Nullable Wrap wrap,
                                                  @Nullable Alignment alignment,
                                                  @Nullable TextRange range) {
        return new Fxml2XmlBlock(child, wrap, alignment, myXmlFormattingPolicy, indent, range, isPreserveSpace());
    }

    @Override
    protected XmlTagBlock createTagBlock(@NotNull ASTNode child,
                                         @Nullable Indent indent,
                                         Wrap wrap,
                                         Alignment alignment) {
        return new Fxml2XmlTagBlock(child, wrap, alignment, myXmlFormattingPolicy,
                                    indent != null ? indent : Indent.getNoneIndent(), isPreserveSpace());
    }
}
