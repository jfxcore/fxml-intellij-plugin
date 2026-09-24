// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.lang;

import com.intellij.lang.ASTNode;
import com.intellij.lang.xml.XmlFormattingModelBuilder;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.formatter.FormattingDocumentModelImpl;
import com.intellij.psi.formatter.xml.XmlBlock;
import com.intellij.psi.formatter.xml.XmlPolicy;

/**
 * Builds the formatting model of a standalone FXML/2 document.
 *
 * <p>The model is the platform's XML model, built from {@link Fxml2XmlBlock} instead of the
 * standard XML block so that a {@code <?resource ?>} declaration keeps the layout it is written
 * in.  Every other aspect of formatting an FXML/2 document is XML formatting, and is governed by
 * the XML code-style settings that apply to the file.
 */
public final class Fxml2FormattingModelBuilder extends XmlFormattingModelBuilder {

    @Override
    protected XmlBlock createBlock(CodeStyleSettings settings,
                                   ASTNode root,
                                   FormattingDocumentModelImpl documentModel) {
        return new Fxml2XmlBlock(root, null, null, new XmlPolicy(settings, documentModel), null, null, false);
    }
}
