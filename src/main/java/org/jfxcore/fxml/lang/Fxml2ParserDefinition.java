package org.jfxcore.fxml.lang;

import com.intellij.lang.ASTFactory;
import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiParser;
import com.intellij.lang.xml.XMLParserDefinition;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.source.tree.CompositeElement;
import com.intellij.psi.impl.source.xml.XmlFileImpl;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.xml.XmlElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Parser definition for {@link Fxml2Language}, the language of standalone FXML/2 documents.
 *
 * <p>Parsing itself is the platform's XML parsing: every {@link ParserDefinition} method is
 * delegated to an {@link XMLParserDefinition}, so an FXML/2 document is lexed and parsed exactly
 * like any other XML document.  Only two things differ.
 *
 * <p>First, {@link #getFileNodeType()} returns {@link Fxml2FileElementType#INSTANCE} so that lazy
 * parsing of an FXML/2 file is routed back to this definition rather than to the platform's.
 *
 * <p>Second, this definition also extends {@link ASTFactory}.  {@code PsiBuilderImpl} consults the
 * parser definition as an AST factory before falling back to the language-keyed factory lookup,
 * which lets {@link #createComposite(IElementType)} substitute plugin-specific PSI for a standard
 * XML node type.  Because the substitution happens through the parser definition, it is scoped
 * strictly to files parsed as FXML/2 and cannot affect any other XML document.
 */
public final class Fxml2ParserDefinition extends ASTFactory implements ParserDefinition {

    private final XMLParserDefinition delegate = new XMLParserDefinition();

    @Override
    public @NotNull Lexer createLexer(@Nullable Project project) {
        return delegate.createLexer(project);
    }

    @Override
    public @NotNull PsiParser createParser(@Nullable Project project) {
        return delegate.createParser(project);
    }

    @Override
    public @NotNull IFileElementType getFileNodeType() {
        return Fxml2FileElementType.INSTANCE;
    }

    @Override
    public @NotNull TokenSet getWhitespaceTokens() {
        return delegate.getWhitespaceTokens();
    }

    @Override
    public @NotNull TokenSet getCommentTokens() {
        return delegate.getCommentTokens();
    }

    @Override
    public @NotNull TokenSet getStringLiteralElements() {
        return delegate.getStringLiteralElements();
    }

    @Override
    public @NotNull PsiElement createElement(@NotNull ASTNode node) {
        return delegate.createElement(node);
    }

    @Override
    public @NotNull PsiFile createFile(@NotNull FileViewProvider viewProvider) {
        return new XmlFileImpl(viewProvider, Fxml2FileElementType.INSTANCE);
    }

    /**
     * Delegates to the platform's XML rule, treating a missing left token as "no constraint".
     * The delegate expects both tokens, and only the parser's own error recovery ever passes none.
     */
    @Override
    public @NotNull SpaceRequirements spaceExistenceTypeBetweenTokens(@Nullable ASTNode left, @NotNull ASTNode right) {
        return left == null
                ? SpaceRequirements.MAY
                : delegate.spaceExistenceTypeBetweenTokens(left, right);
    }

    /**
     * Substitutes {@link Fxml2ResourceProcessingInstruction} for XML processing instructions, so
     * that a {@code <?resource ?>} declaration can host the injected payload language.
     *
     * <p>Every other node type returns {@code null}, letting the platform factory create the
     * standard XML composite unchanged.
     */
    @Override
    public @Nullable CompositeElement createComposite(@NotNull IElementType type) {
        return type == XmlElementType.XML_PROCESSING_INSTRUCTION
                ? new Fxml2ResourceProcessingInstruction()
                : null;
    }
}
