// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.formatting.Alignment;
import com.intellij.formatting.Block;
import com.intellij.formatting.Indent;
import com.intellij.formatting.Wrap;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.formatter.common.AbstractBlock;
import com.intellij.psi.formatter.xml.XmlBlock;
import com.intellij.psi.formatter.xml.XmlFormattingPolicy;
import com.intellij.psi.formatter.xml.XmlTagBlock;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The formatting block of a node of a standalone FXML/2 document.
 *
 * <p>It formats markup exactly as the platform's XML block does, and differs in one point: a
 * {@code <?resource ?>} declaration that carries a payload is a single unbreakable block.  The
 * payload is resource content rather than markup, so the layout it is written in belongs to the
 * author: the declaration is reproduced character for character, and the formatter only decides
 * where the declaration as a whole starts.
 *
 * <p>Without this, the declaration would be formatted as the sequence of tokens the XML lexer
 * splits it into, and the line breaks inside the payload would be turned into markup indentation,
 * which both destroys the layout of the payload and changes the content of the resource.
 *
 * @see Fxml2FormattingModelBuilder for how these blocks reach the formatter
 */
class Fxml2XmlBlock extends XmlBlock {

    Fxml2XmlBlock(@NotNull ASTNode node,
                  @Nullable Wrap wrap,
                  @Nullable Alignment alignment,
                  @NotNull XmlFormattingPolicy policy,
                  @Nullable Indent indent,
                  @Nullable TextRange textRange,
                  boolean preserveSpace) {
        super(node, wrap, alignment, policy, indent, textRange, preserveSpace);
    }

    /**
     * Returns whether {@code node} is a resource declaration whose payload the formatter must
     * reproduce as written.
     */
    static boolean isVerbatim(@NotNull ASTNode node) {
        return node.getPsi() instanceof Fxml2ResourceProcessingInstruction instruction
                && instruction.isValidHost();
    }

    @Override
    protected List<Block> buildChildren() {
        return isVerbatim(myNode) ? AbstractBlock.EMPTY : super.buildChildren();
    }

    @Override
    public boolean isLeaf() {
        return isVerbatim(myNode) || super.isLeaf();
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
