package org.jfxcore.fxml.annotator;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalInspectionToolSession;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.lang.ASTNode;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlProcessingInstruction;
import com.intellij.psi.xml.XmlTokenType;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.lang.Fxml2EmbeddedUtil;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.resolve.Fxml2ImportResolver;

import java.util.HashMap;
import java.util.Map;

/**
 * Validates {@code <?prefix X = ClassName?>} processing instructions in FXML files.
 *
 * <p>Reported problems:
 * <ul>
 *   <li>Invalid prefix character: any character not in the allowlist
 *       {@link Fxml2ImportResolver#VALID_PREFIX_CHARACTERS}.</li>
 *   <li>Duplicate prefix declaration for the same character.</li>
 *   <li>Unresolvable class name in the declaration.</li>
 * </ul>
 */
public final class Fxml2PrefixDeclarationInspection extends LocalInspectionTool {

    private static final String VALID_PREFIX_CHARS_DISPLAY =
            String.join(", ", Fxml2ImportResolver.VALID_PREFIX_CHARACTERS.chars()
                    .mapToObj(c -> String.valueOf((char) c))
                    .toList());

    @Override
    public @NotNull PsiElementVisitor buildVisitor(
            @NotNull ProblemsHolder holder,
            boolean isOnTheFly,
            @NotNull LocalInspectionToolSession session) {

        if (!(holder.getFile() instanceof XmlFile xmlFile)) return PsiElementVisitor.EMPTY_VISITOR;
        if (!Fxml2FileType.isFxml2(xmlFile)) return PsiElementVisitor.EMPTY_VISITOR;
        // For embedded FXML the <?prefix?> PIs (if any) come from the auto-generated
        // prefix, not from the user, so there is nothing to validate here.
        if (Fxml2EmbeddedUtil.isEmbeddedFxml2(xmlFile)) return PsiElementVisitor.EMPTY_VISITOR;

        return new PsiElementVisitor() {

            // Track which prefix characters have already been declared in this file.
            private final Map<Character, XmlProcessingInstruction> seenPrefixes = new HashMap<>();

            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (element instanceof XmlProcessingInstruction pi) {
                    checkPrefixInstruction(pi);
                }
            }

            private void checkPrefixInstruction(@NotNull XmlProcessingInstruction pi) {
                ASTNode nameNode = pi.getNode().findChildByType(XmlTokenType.XML_NAME);
                if (nameNode == null) return;
                if (!"prefix".equals(nameNode.getText())) return;


                String data = Fxml2ImportResolver.getPiData(pi, "prefix");
                int separator = data.indexOf('=');
                if (separator <= 0) {
                    holder.registerProblem(pi, "Malformed <?prefix?> declaration: missing '='");
                    return;
                }
                if (separator >= data.length() - 1) {
                    holder.registerProblem(pi, "Malformed <?prefix?> declaration: missing class name");
                    return;
                }

                String prefixText = data.substring(0, separator).trim();
                String typeName   = data.substring(separator + 1).trim();

                if (prefixText.length() != 1) {
                    holder.registerProblem(pi,
                            "Prefix must be exactly one character, got: '" + prefixText + "'");
                    return;
                }

                char prefixChar = prefixText.charAt(0);

                // Validate the prefix character.
                if (Fxml2ImportResolver.isInvalidPrefixCharacter(prefixChar)) {
                    holder.registerProblem(pi,
                            "Invalid prefix character '" + prefixChar
                            + "': valid prefix characters are "
                            + VALID_PREFIX_CHARS_DISPLAY);
                    return;
                }

                // Duplicate detection.
                XmlProcessingInstruction previous = seenPrefixes.put(prefixChar, pi);
                if (previous != null) {
                    holder.registerProblem(pi,
                            "Duplicate prefix declaration for '" + prefixChar + "'",
                            ProblemHighlightType.WARNING);
                    return;
                }

                // Validate the class name.
                if (typeName.isEmpty()) {
                    holder.registerProblem(pi,
                            "Malformed <?prefix?> declaration: missing class name");
                    return;
                }

                // Try to resolve the type against both the file scope and allScope.
                PsiClass resolved = JavaPsiFacade.getInstance(xmlFile.getProject())
                        .findClass(typeName, xmlFile.getResolveScope());
                if (resolved == null) {
                    resolved = JavaPsiFacade.getInstance(xmlFile.getProject())
                            .findClass(typeName, GlobalSearchScope.allScope(xmlFile.getProject()));
                }
                if (resolved == null) {
                    // Try resolving via import resolver (handles simple names + imports).
                    resolved = Fxml2ImportResolver.resolve(typeName, xmlFile);
                }
                if (resolved == null) {
                    holder.registerProblem(pi,
                            "Cannot resolve class '" + typeName + "'");
                }
            }
        };
    }
}
