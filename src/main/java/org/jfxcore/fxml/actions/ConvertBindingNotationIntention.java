package org.jfxcore.fxml.actions;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.PriorityAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind;
import org.jfxcore.fxml.lang.Fxml2FileType;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser;
import org.jfxcore.fxml.resolve.Fxml2BindingExpressionParser.ParsedExpression;

/**
 * Two inverse intention actions for converting between the compact (short) and long-form
 * (markup-extension) notations for FXML binding expressions in attribute values.
 *
 * <p>Compact <-> long-form mapping:
 * <ul>
 *   <li>{@code $source}              <-> {@code {fx:Evaluate source}}</li>
 *   <li>{@code ${source}}            <-> {@code {fx:Observe source}}</li>
 *   <li>{@code >{source}}            <-> {@code {fx:Push source}}</li>
 *   <li>{@code #{source}}            <-> {@code {fx:Synchronize source}}</li>
 *   <li>{@code $..source}            <-> {@code {fx:Evaluate ..source}}</li>
 *   <li>{@code ${..source}}          <-> {@code {fx:Observe ..source}}</li>
 *   <li>{@code >{..source}}          <-> {@code {fx:Push ..source}}</li>
 *   <li>{@code #{..source}}          <-> {@code {fx:Synchronize ..source}}</li>
 * </ul>
 *
 * <p>{@link ToLongForm} is offered when the attribute value uses compact notation.
 * {@link ToShortForm} is offered when the attribute value uses {@code {fx:...}} markup-extension
 * notation.
 */
public final class ConvertBindingNotationIntention {

    private ConvertBindingNotationIntention() {}

    // -----------------------------------------------------------------------
    // Compact -> long form  (e.g. $foo  ->  {fx:Evaluate foo})
    // -----------------------------------------------------------------------

    /** Converts a compact binding expression to long {@code {fx:...}} markup-extension form. */
    public static final class ToLongForm implements IntentionAction, PriorityAction {

        @Override public @NotNull String getFamilyName() { return "Convert binding notation"; }

        @Override
        public @NotNull String getText() {
            return "Convert to long-form binding notation";
        }

        @Override
        public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
            if (!(file instanceof XmlFile)) return false;
            if (!Fxml2FileType.isFxml2(file)) return false;
            ParsedExpression expr = expressionAt(editor, file);
            return expr != null && isCompactNotation(expr);
        }

        @Override
        public void invoke(@NotNull Project project, Editor editor, PsiFile file)
                throws IncorrectOperationException {
            XmlAttributeValue attrVal = attrValueAt(editor, file);
            if (attrVal == null) return;
            ParsedExpression expr = Fxml2BindingExpressionParser.parseExpression(
                    attrVal.getValue());
            if (expr == null || !isCompactNotation(expr)) return;
            String newValue = toLongForm(expr.kind(), expr.path());
            WriteCommandAction.runWriteCommandAction(project, "Convert to Long-Form Binding", null,
                    () -> setValue(attrVal, newValue), file);
        }

        @Override public boolean startInWriteAction() { return false; }
        @Override public @NotNull Priority getPriority() { return Priority.NORMAL; }
    }

    // -----------------------------------------------------------------------
    // Long form -> compact  (e.g. {fx:Evaluate foo}  ->  $foo)
    // -----------------------------------------------------------------------

    /** Converts a long-form {@code {fx:...}} binding expression to the equivalent compact notation. */
    public static final class ToShortForm implements IntentionAction, PriorityAction {

        @Override public @NotNull String getFamilyName() { return "Convert binding notation"; }

        @Override
        public @NotNull String getText() {
            return "Convert to short-form binding notation";
        }

        @Override
        public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
            if (!(file instanceof XmlFile)) return false;
            if (!Fxml2FileType.isFxml2(file)) return false;
            ParsedExpression expr = expressionAt(editor, file);
            return expr != null && !isCompactNotation(expr);
        }

        @Override
        public void invoke(@NotNull Project project, Editor editor, PsiFile file)
                throws IncorrectOperationException {
            XmlAttributeValue attrVal = attrValueAt(editor, file);
            if (attrVal == null) return;
            ParsedExpression expr = Fxml2BindingExpressionParser.parseExpression(
                    attrVal.getValue());
            if (expr == null || isCompactNotation(expr)) return;
            String newValue = toShortForm(expr.kind(), expr.path());
            WriteCommandAction.runWriteCommandAction(project, "Convert to Short-Form Binding", null,
                    () -> setValue(attrVal, newValue), file);
        }

        @Override public boolean startInWriteAction() { return false; }
        @Override public @NotNull Priority getPriority() { return Priority.NORMAL; }
    }

    // -----------------------------------------------------------------------
    // Notation helpers
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when the parsed expression uses compact notation
     * ({@code $source}, {@code ${source}}, {@code #{source}}, {@code $..source}, etc.)
     * rather than the long {@code {fx:keyword source}} markup-extension form.
     *
     * <p>The prefix lengths are:
     * <pre>
     *   compact  : 1 ($source), 2 (${...}, >{...}, #{...}), 3 ($..source), 4 (${..}, >{..}, #{..})
     *   long-form: >= 11  ({fx:Evaluate ...} -> prefixLength = 1 + "fx:Evaluate".length() = 12)
     * </pre>
     */
    public static boolean isCompactNotation(@NotNull ParsedExpression expr) {
        return expr.prefixLength() <= 4;
    }

    /** Converts a parsed compact expression to {@code {fx:keyword source}} long form. */
    public static @NotNull String toLongForm(@NotNull Kind kind, @NotNull String path) {
        // For content-type bindings the compact form strips the ".." into the prefix
        // (e.g. $..source has prefix="$.." len=3, stored source="source").
        // The long form uses ".." as part of the source argument: {fx:Evaluate ..source}.
        String longPath = switch (kind) {
            case EVALUATE_CONTENT, OBSERVE_CONTENT, PUSH_CONTENT, SYNCHRONIZE_CONTENT -> ".." + path;
            default -> path;
        };
        return "{fx:" + kindToKeyword(kind) + " " + longPath + "}";
    }

    /** Converts a parsed long-form expression to compact notation. */
    public static @NotNull String toShortForm(@NotNull Kind kind, @NotNull String path) {
        return switch (kind) {
            case EVALUATE                -> "$" + path;
            case OBSERVE                 -> "${" + path + "}";
            case PUSH                    -> ">{" + path + "}";
            case SYNCHRONIZE             -> "#{" + path + "}";
            // Content variants: the parser strips ".." from the stored source, so add it back.
            case EVALUATE_CONTENT        -> "$.." + path;
            case OBSERVE_CONTENT         -> "${.." + path + "}";
            case PUSH_CONTENT            -> ">{.." + path + "}";
            case SYNCHRONIZE_CONTENT     -> "#{.." + path + "}";
        };
    }

    public static @NotNull String kindToKeyword(@NotNull Kind kind) {
        return switch (kind) {
            case EVALUATE, EVALUATE_CONTENT             -> "Evaluate";
            case OBSERVE,  OBSERVE_CONTENT              -> "Observe";
            case PUSH,     PUSH_CONTENT                 -> "Push";
            case SYNCHRONIZE, SYNCHRONIZE_CONTENT       -> "Synchronize";
        };
    }

    /**
     * Converts an {@code fx:} child tag local name (e.g. {@code "Observe"}) back to
     * the corresponding binding {@link Kind}, or {@code null} for unrecognized names.
     * Returns the base (non-content) kind; the content variant is determined by the source expression.
     */
    public static @Nullable Kind fxTagNameToKind(@NotNull String localName) {
        return switch (localName) {
            case "Evaluate"    -> Kind.EVALUATE;
            case "Observe"     -> Kind.OBSERVE;
            case "Push"        -> Kind.PUSH;
            case "Synchronize" -> Kind.SYNCHRONIZE;
            default -> null;
        };
    }

    // -----------------------------------------------------------------------
    // PSI helpers
    // -----------------------------------------------------------------------

    private static @Nullable ParsedExpression expressionAt(@Nullable Editor editor,
                                                            @NotNull PsiFile file) {
        XmlAttributeValue attrVal = attrValueAt(editor, file);
        if (attrVal == null) return null;
        return Fxml2BindingExpressionParser.parseExpression(attrVal.getValue());
    }

    private static @Nullable XmlAttributeValue attrValueAt(@Nullable Editor editor,
                                                             @NotNull PsiFile file) {
        if (editor == null) return null;
        PsiElement el = file.findElementAt(editor.getCaretModel().getOffset());
        if (el == null) return null;
        // Walk up to XmlAttributeValue, stopping at XmlAttribute / XmlTag boundaries.
        PsiElement cur = el;
        while (cur != null) {
            if (cur instanceof XmlAttributeValue v) return v;
            if (cur instanceof XmlAttribute) return null;
            cur = cur.getParent();
        }
        return null;
    }

    /** Replaces the attribute value (without surrounding quotes) with {@code newValue}. */
    private static void setValue(@NotNull XmlAttributeValue attrVal, @NotNull String newValue) {
        if (!(attrVal.getParent() instanceof XmlAttribute attr)) return;
        attr.setValue(newValue);
    }
}
