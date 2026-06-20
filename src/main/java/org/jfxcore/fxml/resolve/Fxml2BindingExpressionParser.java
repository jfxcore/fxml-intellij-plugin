package org.jfxcore.fxml.resolve;

import org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Extracts the binding path string from the various FXML binding expression syntaxes
 * and validates that the expression is syntactically well-formed.
 *
 * <p>Compact notations: {@code $source}, {@code ${source}}, {@code >{source}}, {@code #{source}},
 * {@code $..source}, {@code ${..source}}, {@code >{..source}}, {@code #{..source}}.
 * Markup extensions: {@code {fx:Evaluate source}},
 * {@code {fx:Observe source}}, {@code {fx:Push source}}, {@code {fx:Synchronize source}}.
 * Content variants use {@code ..} prefix in the source argument:
 * {@code {fx:Evaluate ..source}}, {@code {fx:Observe ..source}},
 * {@code {fx:Push ..source}}, {@code {fx:Synchronize ..source}}.
 */
public final class Fxml2BindingExpressionParser {

    private Fxml2BindingExpressionParser() {}

    // -----------------------------------------------------------------------
    // Context selector
    // -----------------------------------------------------------------------

    /**
     * Parsed binding context selector: the {@code selector/} or {@code selector.../}
     * prefix that appears before the actual property path.
     *
     * <p>Examples of valid selector forms:
     * <pre>
     *   self/width                -> selector="self", searchType=null, level=null
     *   parent/prefWidth          -> selector="parent", searchType=null, level=null
     *   parent[0]/prefWidth       -> selector="parent", searchType=null, level=0
     *   parent&lt;Pane&gt;/prefWidth      -> selector="parent&lt;Pane&gt;", searchType="Pane", level=null
     *   parent&lt;Pane&gt;[1]/width -> selector="parent&lt;Pane&gt;[1]", searchType="Pane", level=1
     * </pre>
     *
     * @param selectorText raw text of the selector token (e.g. {@code "parent&lt;Pane&gt;[1]"})
     * @param selectorName {@code "self"} or {@code "parent"}
     * @param searchType   optional type name inside angle brackets (e.g. {@code "Pane"})
     * @param level        optional numeric level (e.g. {@code 1}); for bare
     *                     {@code parent/} this is {@code null} (defaults to 0 = immediate parent)
     * @param selectorLength number of characters the selector occupies in the stripped path
     *                       (length of {@code selectorText + "/"})
     * @param remainingPath  the path after {@code selector/}
     * @param remainingOffset offset of {@code remainingPath} within the stripped path
     */
    public record ContextSelector(
            @NotNull String selectorText,
            @NotNull String selectorName,
            @Nullable String searchType,
            @Nullable Integer level,
            int selectorLength,
            @NotNull String remainingPath,
            int remainingOffset) {

        /** {@code true} if the selector is {@code self}. */
        public boolean isSelf() { return "self".equals(selectorName); }
        /** {@code true} if the selector is {@code parent} (with optional type/level). */
        public boolean isParent() { return "parent".equals(selectorName); }
        /** {@code true} if the selector token is {@code this} (ROOT context, token consumed). */
        public boolean isThis() { return "this".equals(selectorText) && selectorName.isEmpty(); }
    }

    /**
     * If the given stripped path starts with a context selector ({@code self/},
     * {@code parent/}, {@code parent[N]/}, {@code parent<Type>/}, or
     * {@code parent<Type>[N]/}), parses and returns the selector; otherwise returns
     * {@code null}.
     *
     * <p>The angle brackets in the type context selector may appear as literal
     * {@code <}/{@code >} characters (as returned by the XML parser after unescaping)
     * or as XML entity references {@code &lt;}/{@code &gt;} (when
     * {@code XmlAttributeValue.getValue()} does not unescape entities).
     * Both forms are recognized.
     *
     * <p>This also handles the {@code this.} prefix which the compiler treats as
     * equivalent to {@code self/} when the first path segment is {@code "this"}.
     *
     * @param strippedPath path with boolean operators already removed
     */
    public static @Nullable ContextSelector parseContextSelector(@NotNull String strippedPath) {
        // "this.foo": the FXML compiler simply skips the "this" token and resolves
        // the rest against the ROOT context (code-behind class).  We represent this as
        // a null selector (default ROOT) with the "this." prefix consumed so callers
        // know the selector text to highlight.  We use selectorName="" to distinguish
        // from a real self/parent selector while still recording the token.
        if (strippedPath.equals("this") || strippedPath.startsWith("this.")) {
            String remaining = strippedPath.length() > 5 ? strippedPath.substring(5) : "";
            // selectorName="" signals: ROOT context, but highlight "this" as the token
            return new ContextSelector("this", "", null, null, 5, remaining, 5);
        }

        // "self/" -> self context
        if (strippedPath.startsWith("self/")) {
            String remaining = strippedPath.substring(5);
            return new ContextSelector("self", "self", null, null,
                    5, remaining, 5);
        }

        // "parent" variants
        if (!strippedPath.startsWith("parent")) return null;
        String afterParent = strippedPath.substring(6); // after "parent"

        // bare "parent/" -> immediate parent
        if (afterParent.startsWith("/")) {
            String remaining = afterParent.substring(1);
            return new ContextSelector("parent", "parent", null, null,
                    7, remaining, 7);
        }

        // "parent<Type>/" or "parent<Type>[N]/": type context selector using angle brackets.
        //
        // Both literal '<'/'>' (after XML-entity unescaping by XmlAttributeValue.getValue())
        // and the raw XML entity form '&lt;'/'&gt;' (when getValue() does not unescape) are
        // supported, so the plugin works regardless of which form the XML parser produces.
        {
            String openAngleMark  = afterParent.startsWith("<")    ? "<"    :
                                    afterParent.startsWith("&lt;") ? "&lt;" : null;
            if (openAngleMark != null) {
                String closeAngleMark = openAngleMark.equals("<") ? ">" : "&gt;";
                int openAngleLen  = openAngleMark.length();
                int closeAngleIdx = afterParent.indexOf(closeAngleMark, openAngleLen);
                if (closeAngleIdx < 0) return null;
                int closeAngleEnd = closeAngleIdx + closeAngleMark.length();

                String typeName = afterParent.substring(openAngleLen, closeAngleIdx).trim();
                if (typeName.isEmpty() || !isJavaIdentifier(typeName)) return null;

                String afterClose = afterParent.substring(closeAngleEnd); // after ">" or "&gt;"
                Integer level = null;
                int bracketLen = 0; // length of the optional "[N]" suffix

                if (afterClose.startsWith("[")) {
                    int bracketClose = afterClose.indexOf(']');
                    if (bracketClose < 0) return null;
                    String numStr = afterClose.substring(1, bracketClose).trim();
                    try { level = Integer.parseInt(numStr); } catch (NumberFormatException e) { return null; }
                    bracketLen = bracketClose + 1; // length of "[N]"
                    afterClose = afterClose.substring(bracketLen); // advance past "[N]"
                }

                if (!afterClose.startsWith("/")) return null;

                // selectorLen = "parent"(6) + "<Type>"(closeAngleEnd) + "[N]"(bracketLen) + "/"(1)
                int selectorLen = 6 + closeAngleEnd + bracketLen + 1;
                String selectorText = strippedPath.substring(0, selectorLen - 1); // without "/"
                String remaining = afterClose.substring(1); // after "/"
                return new ContextSelector(selectorText, "parent", typeName, level,
                        selectorLen, remaining, selectorLen);
            }
        }

        // "parent[N]/" -> numeric index only (N must be a number).
        //
        // The square-bracket form accepts ONLY a numeric index; non-numeric content
        // (e.g. "parent[Button]") is not a valid context selector and the compiler
        // generates UNEXPECTED_TOKEN for such expressions.
        if (!afterParent.startsWith("[")) return null;
        int close = afterParent.indexOf(']');
        if (close < 0) return null;
        if (close + 1 >= afterParent.length() || afterParent.charAt(close + 1) != '/') return null;

        String bracket = afterParent.substring(1, close); // content between [ and ]
        if (bracket.isEmpty()) return null;
        int level;
        try {
            level = Integer.parseInt(bracket.trim());
        } catch (NumberFormatException e) {
            return null; // Non-numeric content inside brackets is not a valid context selector
        }

        // selectorLen = "parent"(6) + "["(1) + content(close-1) + "]"(1) + "/"(1) = 6 + close + 2
        int selectorLen = 6 + close + 2;
        String selectorText = strippedPath.substring(0, selectorLen - 1); // without trailing "/"
        String remaining = afterParent.substring(close + 2); // after "]/"
        return new ContextSelector(selectorText, "parent", null, level,
                selectorLen, remaining, selectorLen);
    }

    /**
     * Returns {@code true} if {@code s} is a syntactically valid Java simple identifier
     * (starts with a Java identifier start character, followed by zero or more Java
     * identifier part characters, and contains no dots).
     */
    private static boolean isJavaIdentifier(@NotNull String s) {
        if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        }
        return true;
    }

    /**
     * Returns {@code true} when the {@code '<'} (or {@code '&lt;'}) at position
     * {@code anglePos} within {@code pathAfterOp} is the opening angle bracket of a
     * {@code parent<Type>} context selector, rather than the start of a type witness.
     *
     * <p>The angle bracket is part of a context selector if and only if it occurs
     * immediately after the keyword {@code "parent"} (i.e. at position 6): meaning the
     * expression is {@code parent<...>...} rather than {@code someMethod<...>...}.
     *
     * @param pathAfterOp the path string with any leading boolean operator already stripped
     * @param anglePos    position of the {@code '<'} or start of {@code '&lt;'} in {@code pathAfterOp}
     */
    private static boolean isParentContextSelectorAngle(@NotNull String pathAfterOp, int anglePos) {
        // The '<' (or '&lt;') must be immediately after the six characters of "parent".
        return anglePos == 6 && pathAfterOp.startsWith("parent");
    }

    /**
     * A secondary parameter of a binding expression: the {@code name=path} clause that follows the
     * primary binding path after a {@code ';'} separator (e.g. {@code format=path.to.format} in
     * {@code #{value; format=path.to.format}}).  A binding expression may carry any number of these;
     * which names are valid depends on the binding kind (see {@link #validSecondaryParams}).
     *
     * @param name       the parameter name (e.g. {@code "format"}); may be any identifier, including
     *                   one that is not valid for the binding kind
     * @param nameOffset offset of {@code name} within the raw value (quotes excluded, 0-based)
     * @param path       the parameter's path value (e.g. {@code "path.to.format"}); may be empty
     * @param pathOffset offset of {@code path} within the raw value, or {@code -1} when {@code path}
     *                   is empty
     */
    public record SecondaryParam(
            @NotNull String name,
            int nameOffset,
            @NotNull String path,
            int pathOffset) {}

    /**
     * Represents a successfully parsed binding expression.
     *
     * @param path         the raw path string (e.g. {@code "vm.field"})
     * @param pathOffset   offset of {@code path} within the raw value (quotes excluded, 0-based)
     * @param prefixLength length of the notation prefix within the raw value
     * @param kind         the binding kind (maps to the JavaFX method)
     * @param params       the secondary parameters ({@code name=path} clauses after the primary
     *                     path); empty when none are present
     */
    public record ParsedExpression(
            @NotNull String path,
            int pathOffset,
            int prefixLength,
            @NotNull Kind kind,
            @NotNull List<SecondaryParam> params) {

        /** Convenience constructor without secondary parameters. */
        public ParsedExpression(@NotNull String path, int pathOffset, int prefixLength,
                                @NotNull Kind kind) {
            this(path, pathOffset, prefixLength, kind, List.of());
        }

        /** {@code true} if this expression carries at least one secondary parameter. */
        public boolean hasParam() { return !params.isEmpty(); }

        /**
         * Returns the path with any leading boolean operator ({@code !} or {@code !!}) stripped,
         * and the corresponding operator length (0, 1, or 2).
         */
        public int operatorLength() {
            if (path.startsWith("!!")) return 2;
            if (path.startsWith("!"))  return 1;
            return 0;
        }

        /** The actual property path with any leading boolean operator stripped. */
        public @NotNull String strippedPath() {
            return path.substring(operatorLength());
        }

        /** The offset of {@link #strippedPath()} within the raw value (quotes excluded, 0-based). */
        public int strippedPathOffset() {
            return pathOffset + operatorLength();
        }
    }

    /**
     * Represents a syntactic error in a binding expression.
     *
     * @param message     human-readable error message
     * @param errorOffset offset within the raw value (quotes excluded) where the error is located
     * @param errorLength length of the erroneous token (1 for a missing/unexpected single char)
     * @param recoverableBraced {@code true} when the only defect is a not-yet-typed closing brace
     *                          on an otherwise braced binding notation, so a lenient re-parse with
     *                          a synthetic brace can still yield navigable references
     */
    public record ParseError(@NotNull String message, int errorOffset, int errorLength,
                             boolean recoverableBraced) {
        public ParseError(@NotNull String message, int errorOffset, int errorLength) {
            this(message, errorOffset, errorLength, false);
        }
    }

    /** Error message produced for an unterminated braced binding notation (no closing {@code }}). */
    private static final String MISSING_CLOSING_BRACE = "'}' expected";

    /**
     * Represents a missing binding path: returned when a binding expression requires a path
     * but none was provided (e.g. {@code ${}} or {@code {fx:Observe}} or {@code {fx:Synchronize}}).
     *
     * <p>When the fxml-compiler detects a missing path it emits a
     * {@code PROPERTY_MUST_BE_SPECIFIED} diagnostic (message: {@code "<intrinsic>.source must be specified"}).
     * The annotator uses this record together with the detected compiler version to decide
     * which error message to display.
     *
     * @param intrinsicName the binding intrinsic keyword (e.g. {@code "fx:Observe"})
     */
    public record MissingBindingPath(@NotNull String intrinsicName) {}

    /**
     * Represents a custom markup extension invocation: {@code {ClassName}} or
     * {@code {ClassName param1=val1 param2=val2}}.
     *
     * <p>The compiler resolves {@code ClassName} against the FXML imports and checks that the
     * class implements {@code org.jfxcore.markup.MarkupExtension}.
     *
     * @param extensionName  simple name of the extension class (e.g. {@code "MyExtension"})
     * @param nameOffset     offset of {@code extensionName} within the raw value (quotes excluded)
     * @param hasTypeArg     {@code true} if a generic type argument was present in the invocation
     *                       (e.g. {@code {StaticResource<String> key}})
     */
    public record MarkupExtensionExpression(
            @NotNull String extensionName,
            int nameOffset,
            boolean hasTypeArg) {
    }

    /**
     * Represents a prefix-shorthand markup extension invocation like {@code @icons/app.png}
     * or {@code %greeting; formatArguments=Jane, Doe}.
     *
     * <p>The {@code prefixChar} is mapped to an extension class via the file's prefix
     * declarations (explicit {@code <?prefix?>} PIs or implicit built-in defaults).
     *
     * @param prefixChar       the prefix character (e.g. {@code '@'} or {@code '%'})
     * @param mappedClass      fully-qualified name of the mapped markup extension class
     * @param defaultArg       argument after the prefix char and before any {@code ;} (trimmed)
     * @param defaultArgOffset offset of {@code defaultArg} within the raw value (quotes excluded)
     * @param paramsPart       everything after the first {@code ;}, or {@code null} if absent
     * @param paramsOffset     offset of {@code paramsPart} within the raw value, or {@code -1}
     */
    public record PrefixShorthandExpression(
            char prefixChar,
            @NotNull String mappedClass,
            @NotNull String defaultArg,
            int defaultArgOffset,
            @Nullable String paramsPart,
            int paramsOffset) {}

    /**
     * Returns {@code true} if {@code value} starts with a backslash escape that prevents
     * interpretation as a binding expression. A backslash is only an escape when the
     * character(s) immediately following it constitute a recognized binding-expression start
     * ({@code {}, {@code $}, {@code >}, or {@code #{}).
     *
     * <p>Examples: {@code \{MyExtension}} and {@code \$source} are escaped literals;
     * {@code \bar} is NOT an escape (backslash is kept as-is).
     */
    private static boolean startsWithBackslashEscape(@NotNull String value) {
        if (!value.startsWith("\\")) return false;
        return looksLikeBindingExpression(value.substring(1));
    }

    /**
     * Like {@link #startsWithBackslashEscape(String)}, but also considers prefix characters
     * from {@code prefixMappings} as recognized binding-expression starts. This covers
     * prefix-shorthand values such as {@code \%greeting} and {@code \@/path}.
     */
    private static boolean startsWithBackslashEscape(
            @NotNull String value,
            @NotNull java.util.Map<Character, String> prefixMappings) {
        if (!value.startsWith("\\")) return false;
        String rest = value.substring(1);
        return looksLikeBindingExpression(rest)
                || (!rest.isEmpty() && prefixMappings.containsKey(rest.charAt(0)));
    }

    /**
     * Parses the attribute value text, also recognising prefix-shorthand invocations
     * (e.g. {@code @icons/app.png} or {@code %greeting; formatArguments=Jane, Doe})
     * according to the supplied prefix mapping.
     *
     * <p>If the value starts with a character that is present in {@code prefixMappings}
     * (and is not preceded by a backslash escape), a {@link PrefixShorthandExpression} is
     * returned.  Otherwise this method delegates to {@link #parse(String)}.
     *
     * @param value          raw attribute value without surrounding quotes
     * @param prefixMappings prefix-char -> extension FQN map for the current FXML file
     * @return a {@link PrefixShorthandExpression}, {@link MarkupExtensionExpression},
     *         {@link ParsedExpression}, {@link ParseError}, or {@code null}
     */
    public static @Nullable Object parse(
            @NotNull String value,
            @NotNull java.util.Map<Character, String> prefixMappings) {
        if (!value.isEmpty() && !startsWithBackslashEscape(value, prefixMappings)
                && !prefixMappings.isEmpty()) {
            String mapped = prefixMappings.get(value.charAt(0));
            if (mapped != null) {
                return parsePrefixShorthand(value, value.charAt(0), mapped);
            }
        }
        return parse(value);
    }

    /**
     * Returns {@code true} if the value is a prefix-shorthand invocation (i.e. starts
     * with a character present in {@code prefixMappings} and is not backslash-escaped),
     * OR if {@link #looksLikeBindingExpression(String)} returns true.
     */
    public static boolean looksLikeBindingExpression(
            @NotNull String value,
            @NotNull java.util.Map<Character, String> prefixMappings) {
        if (startsWithBackslashEscape(value, prefixMappings)) return false;
        if (!prefixMappings.isEmpty() && !value.isEmpty()
                && prefixMappings.containsKey(value.charAt(0))) {
            return true;
        }
        return looksLikeBindingExpression(value);
    }

    /** Parses a prefix-shorthand attribute value into a {@link PrefixShorthandExpression}. */
    private static @NotNull PrefixShorthandExpression parsePrefixShorthand(
            @NotNull String value, char prefix, @NotNull String mappedClass) {
        // value = "@icons/app.png" or "%greeting; formatArguments=Jane, Doe"
        String rest = value.substring(1); // everything after the prefix char
        int semicolon = rest.indexOf(';');
        String defaultArg;
        String paramsPart;
        int paramsOffset;
        if (semicolon >= 0) {
            defaultArg   = rest.substring(0, semicolon).trim();
            String rawParams = rest.substring(semicolon + 1).trim();
            paramsPart   = rawParams.isEmpty() ? null : rawParams;
            paramsOffset = paramsPart != null ? value.indexOf(paramsPart, 1 + semicolon) : -1;
        } else {
            defaultArg   = rest.trim();
            paramsPart   = null;
            paramsOffset = -1;
        }
        int defaultArgOffset = defaultArg.isEmpty() ? 1
                : value.indexOf(defaultArg, 1);
        if (defaultArgOffset < 0) defaultArgOffset = 1;
        return new PrefixShorthandExpression(
                prefix, mappedClass, defaultArg, defaultArgOffset, paramsPart, paramsOffset);
    }

    /**
     * Parses the attribute value text (WITHOUT surrounding quotes).
     * Returns a {@link ParsedExpression} on success, a {@link ParseError} on failure,
     * or {@code null} if the value is not a binding expression at all.
     */
    public static @Nullable Object parse(@NotNull String value) {
        if (value.isEmpty()) {
            return null;
        }

        // A backslash followed by a recognized binding-expression start is an escape
        // for a literal string value: the compiler strips the leading backslash.
        if (startsWithBackslashEscape(value)) {
            return null;
        }

        // $..source  (fx:Evaluate ..source compact)
        if (value.startsWith("$..")) {
            String path = value.substring(3).trim();
            if (path.isEmpty()) {
                return new MissingBindingPath("fx:Evaluate");
            }
            return new ParsedExpression(path, value.indexOf(path), 3,
                    org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind.EVALUATE_CONTENT);
        }

        // ${..source}  (fx:Observe ..source compact)
        if (value.startsWith("${..")) {
            return parseBraced(value, 4, org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind.OBSERVE_CONTENT);
        }

        // >{..source}  (fx:Push ..source compact)
        if (value.startsWith(">{..")) {
            return parseBraced(value, 4, org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind.PUSH_CONTENT);
        }

        // #{..source}  (fx:Synchronize ..source compact)
        if (value.startsWith("#{..")) {
            return parseBraced(value, 4, org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind.SYNCHRONIZE_CONTENT);
        }

        // ${source}  (fx:Observe compact)
        if (value.startsWith("${")) {
            return parseBraced(value, 2, org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind.OBSERVE);
        }

        // >{source}  (fx:Push compact)
        if (value.startsWith(">{")) {
            return parseBraced(value, 2, org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind.PUSH);
        }

        // #{source}  (fx:Synchronize compact)
        if (value.startsWith("#{")) {
            return parseBraced(value, 2, org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind.SYNCHRONIZE);
        }

        // $path  (fx:Evaluate compact): no closing brace required
        if (value.startsWith("$")) {
            String path = value.substring(1).trim();
            if (path.isEmpty()) {
                return new MissingBindingPath("fx:Evaluate");
            }
            // A type-witness '<' or '&lt;' anywhere in the path is an error in compact
            // $-notation: UNLESS it is the opening of a 'parent<Type>' context selector.
            // E.g. $parent<Pane>/prefWidth is valid; $method<String> is not.
            int opLen = path.startsWith("!!") ? 2 : path.startsWith("!") ? 1 : 0;
            String pathAfterOp = path.substring(opLen); // stripped of any boolean operator
            int angleBracket = pathAfterOp.indexOf('<');
            if (angleBracket < 0) angleBracket = pathAfterOp.indexOf("&lt;");
            if (angleBracket >= 0 && !isParentContextSelectorAngle(pathAfterOp, angleBracket)) {
                return new ParseError("'>' expected", 0, value.length());
            }
            // Comma in a $path means mixing binding with comma-separated coercion: compiler error.
            if (path.contains(",")) {
                return new ParseError("A comma-separated argument list cannot contain binding expressions", 0, value.length());
            }
            return new ParsedExpression(path, value.indexOf(path), 1,
                    org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind.EVALUATE);
        }

        // {fx:Evaluate source}, {fx:Observe source}, {fx:Push source}, {fx:Synchronize source}, etc.
        if (value.startsWith("{")) {
            if (!value.endsWith("}")) {
                return new ParseError(MISSING_CLOSING_BRACE, value.length(), 0, true);
            }
            String inner = value.substring(1, value.length() - 1).trim();
            int ws = indexOfWhitespace(inner);

            if (!value.startsWith("{fx:")) {
                // Custom markup extension invocation: {ClassName} or {ClassName param=value ...}
                // Extract the class name (everything before the first whitespace, or the whole inner).
                String name = ws > 0 ? inner.substring(0, ws) : inner;
                int colonInName = name.indexOf(':');
                if (colonInName > 0) {
                    // Namespace-prefixed name: unknown namespace (compiler: UNKNOWN_NAMESPACE).
                    String ns = name.substring(0, colonInName);
                    return new ParseError("Unknown XML namespace: " + ns, 0, value.length());
                }
                // Strip generic type arguments: {MyMarkupExtension<String> ...} -> extensionName "MyMarkupExtension".
                // The FXML/2 compiler accepts both the literal '<' and the XML-escaped '&lt;' forms.
                // XmlAttributeValue.getValue() may return either form depending on the XML parser,
                // so check for both.
                int angleIdx = name.indexOf('<');
                if (angleIdx < 0) angleIdx = name.indexOf("&lt;");
                boolean hasTypeArg = angleIdx > 0;
                if (hasTypeArg) {
                    name = name.substring(0, angleIdx);
                }
                // No colon: treat as a custom markup extension class name.
                // The annotator will validate that the class exists and implements MarkupExtension.
                int nameOffset = value.indexOf(name); // 1 (past '{')
                return new MarkupExtensionExpression(name, nameOffset, hasTypeArg);
            }

                if (ws < 0) {
                // {fx:Null}, {fx:True}, and {fx:False} are literal values, not binding expressions.
                if ("fx:Null".equals(inner) || "fx:True".equals(inner) || "fx:False".equals(inner)) {
                    return null;
                }
                // No whitespace after the keyword, either {fx:keyword} with no path,
                // or {fx:keyword.dotted} which is a dotted name (UNEXPECTED_TOKEN).
                if (inner.contains(".")) {
                    return new ParseError("Unexpected token", 0, value.length());
                }
                // Known binding keyword with a missing source: report as MissingBindingPath so
                // the annotator can show a "source must be specified" message.
                if (KNOWN_BINDING_KEYWORDS.contains(inner)) {
                    return new MissingBindingPath(inner);
                }
                return new ParseError("Unexpected end of file", value.length() - 1, 0);
            }
            String keyword = inner.substring(0, ws);
            String args = inner.substring(ws).trim();

            // fx:Class is not a binding expression: it doesn't carry a binding path.
            // Return null so the annotator skips path resolution for it.
            // Note: fx:resource is not a recognized binding keyword; {fx:resource ...} is
            // correctly reported as an unknown binding keyword.
            if ("fx:Class".equals(keyword)) {
                return null;
            }

            org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind kind = switch (keyword) {
                case "fx:Evaluate"    -> org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind.EVALUATE;
                case "fx:Observe"     -> org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind.OBSERVE;
                case "fx:Push"        -> org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind.PUSH;
                case "fx:Synchronize" -> org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind.SYNCHRONIZE;
                default -> null;
            };
            if (kind == null) {
                int kwOffset = value.indexOf(keyword);
                return new ParseError("Unknown binding keyword '" + keyword + "'", kwOffset, keyword.length());
            }
            int prefixLength = 1 + keyword.length();

            // Strip optional "source=" keyword (the default property of all binding intrinsics).
            String argsForPath = args.startsWith("source=") ? args.substring(7).trim() : args;

            // Strip an optional list of secondary-parameter clauses ("; name=X; name2=Y; ...").
            // Validity of each name depends on the binding kind; the parser records them verbatim.
            SplitResult split = splitParams(value, argsForPath);
            String primaryPath = split.primaryPath();
            List<SecondaryParam> params = split.params();

            if (primaryPath.isEmpty()) {
                // keyword is always a known binding keyword at this point (unknown ones caught above)
                return new MissingBindingPath(keyword);
            }

            // Upgrade to the content variant when the source starts with ".."
            // e.g. {fx:Evaluate ..items} -> Kind.EVALUATE_CONTENT, stored source = "items"
            if (primaryPath.startsWith("..")) {
                kind = switch (kind) {
                    case EVALUATE -> org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind.EVALUATE_CONTENT;
                    case OBSERVE  -> org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind.OBSERVE_CONTENT;
                    case PUSH     -> org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind.PUSH_CONTENT;
                    case SYNCHRONIZE -> org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind.SYNCHRONIZE_CONTENT;
                    default -> kind;
                };
                primaryPath = primaryPath.substring(2).trim();
            }

            if (primaryPath.isEmpty()) {
                return new MissingBindingPath(keyword);
            }
            int pathOffset = value.indexOf(primaryPath, prefixLength + 1);
            return new ParsedExpression(primaryPath, pathOffset, prefixLength, kind, params);
        }


        return null;
    }

    /**
     * Convenience method that returns a {@link ParsedExpression} if parse succeeds,
     * or {@code null} if the value is not a binding expression or is malformed.
     */
    public static @Nullable ParsedExpression parseExpression(@NotNull String value) {
        Object result = parse(value);
        return result instanceof ParsedExpression pe ? pe : null;
    }

    /**
     * Like {@link #parseExpression(String)} but tolerant of a not-yet-typed closing brace, so
     * that the already-complete leading segments of a binding expression still produce navigable
     * references while the user is mid-edit. For example, a half-typed observe binding
     * <code>${String.fo</code> with no closing brace yet, or a function call
     * <code>${String.format('foo', value</code> with neither a closing parenthesis nor brace.
     *
     * <p>When the value uses a braced notation (<code>${</code>, <code>&gt;{</code>,
     * <code>#{</code>, or a <code>{fx:...}</code> keyword form) but does not end with a closing
     * brace, a synthetic closing brace is appended before parsing. Because the brace is appended
     * at the very end, every offset within the {@link ParsedExpression} stays valid for the
     * original, unbraced text. The missing-brace diagnostic is produced separately by the
     * annotator via the strict {@link #parse(String)} path, so navigation and validation stay
     * independent.
     *
     * @return a {@link ParsedExpression}, or {@code null} if the value is not a (possibly partial)
     *         binding expression
     */
    public static @Nullable ParsedExpression parseExpressionLenient(@NotNull String value) {
        Object result = parse(value);
        if (result instanceof ParsedExpression pe) {
            return pe;
        }
        // A still-being-typed braced expression fails strict parsing with a missing-brace error.
        // Retry once with a synthetic closing brace so the already-complete leading segments still
        // produce navigable references. The brace is appended at the very end, so every offset in
        // the result stays valid for the original, unbraced text.
        if (result instanceof ParseError error && error.recoverableBraced()) {
            return parseExpression(value + "}");
        }
        return null;
    }

    /**
     * Extracts the raw path portion of a binding expression, tolerating syntactically
     * incomplete values (e.g. {@code "${"} with no closing brace). Intended for use in
     * completion contributors where the value may contain the IDE completion dummy identifier.
     *
     * <p>Strips the binding notation prefix, any trailing {@code }}, and any leading boolean
     * operator ({@code !} or {@code !!}). Returns {@code null} when {@code value} does not
     * start with a recognized binding prefix.
     */
    public static @Nullable String extractPartialPath(@NotNull String value) {
        NotationPrefix prefix = matchNotationPrefix(value);
        if (prefix == null) return null;

        String path = value.substring(prefix.text().length());
        if (path.endsWith("}")) path = path.substring(0, path.length() - 1);
        path = path.trim();
        if (path.startsWith("!!"))      path = path.substring(2).trim();
        else if (path.startsWith("!")) path = path.substring(1).trim();
        return path;
    }

    /**
     * Returns the binding {@link Kind} implied by the notation prefix of a (possibly incomplete)
     * value, or {@code null} when the value does not start with a recognized binding prefix.
     * Intended for completion, where the value may be truncated at the caret.
     */
    public static @Nullable Kind notationKind(@NotNull String value) {
        NotationPrefix prefix = matchNotationPrefix(value);
        return prefix != null ? prefix.kind() : null;
    }

    /**
     * A recognized binding-notation prefix and the {@link Kind} it implies, e.g. {@code "${"} ->
     * {@code OBSERVE} or {@code "{fx:Synchronize "} -> {@code SYNCHRONIZE}.
     */
    private record NotationPrefix(@NotNull String text, @NotNull Kind kind) {}

    /**
     * The recognized binding-notation prefixes, ordered most-specific first so that a longer prefix
     * (e.g. {@code "${.."}, {@code "$.."}) is matched before a shorter one it contains
     * ({@code "${"}, {@code "$"}).  The bare {@code "$"} (fx:Evaluate compact) must be last.
     */
    private static final List<NotationPrefix> NOTATION_PREFIXES = List.of(
            new NotationPrefix("${..", Kind.OBSERVE_CONTENT),
            new NotationPrefix("#{..", Kind.SYNCHRONIZE_CONTENT),
            new NotationPrefix(">{..", Kind.PUSH_CONTENT),
            new NotationPrefix("$..",  Kind.EVALUATE_CONTENT),
            new NotationPrefix("${",   Kind.OBSERVE),
            new NotationPrefix("#{",   Kind.SYNCHRONIZE),
            new NotationPrefix(">{",   Kind.PUSH),
            new NotationPrefix("{fx:Observe ",     Kind.OBSERVE),
            new NotationPrefix("{fx:Evaluate ",    Kind.EVALUATE),
            new NotationPrefix("{fx:Push ",        Kind.PUSH),
            new NotationPrefix("{fx:Synchronize ", Kind.SYNCHRONIZE),
            new NotationPrefix("$",    Kind.EVALUATE));

    /** Returns the first {@link NotationPrefix} that {@code value} starts with, or {@code null}. */
    private static @Nullable NotationPrefix matchNotationPrefix(@NotNull String value) {
        for (NotationPrefix prefix : NOTATION_PREFIXES) {
            if (value.startsWith(prefix.text())) return prefix;
        }
        return null;
    }

    /**
     * Returns {@code true} if the value looks like an attempted binding expression
     * (starts with {@code $}, {@code >{}}, {@code #{}, or {@code {}}).
     * A backslash-escaped value (e.g. {@code \$source} or {@code \{...}}) is NOT a
     * binding expression and returns {@code false}.
     */
    public static boolean looksLikeBindingExpression(@NotNull String value) {
        if (startsWithBackslashEscape(value)) return false;
        return value.startsWith("$") || value.startsWith(">") || value.startsWith("#{") || value.startsWith("{");
    }

    private static int indexOfWhitespace(@NotNull String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Finds the first {@code ;} in {@code s} that is not inside an XML entity reference
     * (e.g. {@code &lt;}, {@code &gt;}, {@code &amp;}).  Returns {@code -1} if none.
     *
     * <p>An entity reference is {@code &name;} where {@code name} is one or more word chars.
     * We scan for {@code &} and skip forward to the matching {@code ;} to avoid treating
     * entity-internal semicolons as the binding-parameter separator.
     */
    public static int findParamSeparatorSemicolon(@NotNull String s) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '&') {
                // Skip the entity reference: &name;
                int j = i + 1;
                while (j < s.length() && Character.isLetterOrDigit(s.charAt(j))) j++;
                if (j < s.length() && s.charAt(j) == ';') {
                    i = j + 1; // skip over the ';' that closes the entity
                    continue;
                }
                // Malformed entity: treat as normal char, advance past '&'
                i++;
            } else if (c == ';') {
                return i;
            } else {
                i++;
            }
        }
        return -1;
    }

    /**
     * The result of splitting a binding expression's inner content on the secondary-parameter
     * separator {@code ';'}: the primary binding path, plus the list of {@code name=path} parameter
     * clauses that follow it.
     *
     * @param primaryPath the binding path before the first {@code ';'} separator
     * @param params      the secondary parameters; empty when none are present
     */
    private record SplitResult(@NotNull String primaryPath, @NotNull List<SecondaryParam> params) {}

    /**
     * Splits {@code content} (the inner text of a binding expression, with any {@code source=}
     * keyword already stripped) on every secondary-parameter separator {@code ';'} that is not part
     * of an XML entity reference.  The text before the first separator is the primary path; each
     * following {@code name=path} clause becomes a {@link SecondaryParam} whose {@code name} and
     * {@code path} offsets are located within {@code value} (the raw attribute value).
     *
     * <p>Names are recorded verbatim, including names that are not valid for the binding kind: name
     * validity is decided downstream so that the value path still resolves even when the name is
     * misspelled or nonexistent.  A clause without an {@code '='} is ignored.
     */
    private static @NotNull SplitResult splitParams(@NotNull String value, @NotNull String content) {
        List<String> segments = splitOnParamSeparators(content);
        String primaryPath = segments.getFirst().trim();
        if (segments.size() == 1) {
            return new SplitResult(primaryPath, List.of());
        }

        List<SecondaryParam> params = new ArrayList<>();
        // Search cursor within the raw value.  Start it at the first parameter separator so that a
        // primary path which happens to equal a parameter name is not mistaken for that name.
        int firstSep = findParamSeparatorSemicolon(value);
        int cursor = firstSep >= 0 ? firstSep + 1 : 0;
        for (int i = 1; i < segments.size(); i++) {
            String segment = segments.get(i);
            int eq = segment.indexOf('=');
            if (eq <= 0) {
                continue; // not a "name=path" clause
            }
            String name = segment.substring(0, eq).trim();
            String path = segment.substring(eq + 1).trim();
            if (name.isEmpty()) {
                continue;
            }
            int nameOffset = value.indexOf(name, cursor);
            if (nameOffset < 0) {
                continue;
            }
            int eqInValue = value.indexOf('=', nameOffset + name.length());
            int pathOffset = -1;
            if (!path.isEmpty() && eqInValue >= 0) {
                // Search for the path only after the '=' sign to avoid matching it as a substring of
                // the name (e.g. "conv" inside "converter").
                pathOffset = value.indexOf(path, eqInValue + 1);
            }
            params.add(new SecondaryParam(name, nameOffset, path, pathOffset));
            cursor = pathOffset >= 0 ? pathOffset + path.length()
                    : eqInValue >= 0 ? eqInValue + 1 : nameOffset + name.length();
        }
        return new SplitResult(primaryPath, params);
    }

    /**
     * Splits {@code content} into segments separated by {@code ';'} characters that are not part of
     * an XML entity reference (using {@link #findParamSeparatorSemicolon}).  The first segment is the
     * primary path; subsequent segments are the secondary-parameter clauses.  Segments are returned
     * untrimmed.
     */
    private static @NotNull List<String> splitOnParamSeparators(@NotNull String content) {
        List<String> segments = new ArrayList<>();
        int start = 0;
        while (true) {
            int rel = findParamSeparatorSemicolon(content.substring(start));
            if (rel < 0) {
                segments.add(content.substring(start));
                return segments;
            }
            int semicolon = start + rel;
            segments.add(content.substring(start, semicolon));
            start = semicolon + 1;
        }
    }

    /**
     * The secondary-parameter names that are valid for the given binding {@code kind}.
     *
     * <p>Only the bidirectional {@code fx:Synchronize} binding accepts secondary parameters
     * ({@code format}, {@code converter}, {@code inverseMethod}); every other binding kind accepts
     * none.  This mirrors the intrinsic-property declarations of the fxml-compiler and is the single
     * source of truth used to validate and to complete parameter names.
     */
    public static @NotNull Set<String> validSecondaryParams(@NotNull Kind kind) {
        return switch (kind) {
            case SYNCHRONIZE, SYNCHRONIZE_CONTENT -> Set.of("format", "converter", "inverseMethod");
            default -> Set.of();
        };
    }

    /**
     * The secondary-parameter names that cannot be used together for the given binding {@code kind}.
     * For {@code fx:Synchronize}, {@code format}, {@code converter}, and {@code inverseMethod} are
     * mutually exclusive.
     */
    public static @NotNull Set<String> conflictingSecondaryParams(@NotNull Kind kind) {
        return switch (kind) {
            case SYNCHRONIZE, SYNCHRONIZE_CONTENT -> Set.of("format", "converter", "inverseMethod");
            default -> Set.of();
        };
    }

    /**
     * Parses a braced compact notation of the form {@code <prefix>path}}, where the prefix
     * is {@code prefixLength} characters long and the value must end with {@code }}.
     * Strips a leading {@code source=} keyword if present (same as the markup-extension syntax).
     * Also splits off an optional list of secondary-parameter clauses ({@code ; name=X; name2=Y}).
     */
    private static @NotNull Object parseBraced(
            @NotNull String value,
            int prefixLength,
            @NotNull org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind kind) {
        if (!value.endsWith("}")) {
            return new ParseError(MISSING_CLOSING_BRACE, value.length(), 0, true);
        }
        String rawPath = value.substring(prefixLength, value.length() - 1).trim();
        if (rawPath.isEmpty()) {
            return new MissingBindingPath(kindToIntrinsicName(kind));
        }
        // Strip optional "source=" keyword (the default property of all binding intrinsics).
        String withoutPathKeyword = rawPath.startsWith("source=") ? rawPath.substring(7).trim() : rawPath;
        if (withoutPathKeyword.isEmpty()) {
            return new MissingBindingPath(kindToIntrinsicName(kind));
        }
        // Strip an optional list of secondary-parameter clauses ("; name=X; name2=Y; ...").
        SplitResult split = splitParams(value, withoutPathKeyword);
        String primaryPath = split.primaryPath();
        List<SecondaryParam> params = split.params();
        if (primaryPath.isEmpty()) {
            return new MissingBindingPath(kindToIntrinsicName(kind));
        }
        // The compact ${...} syntax does not support type witnesses: a leading '<' is an error.
        if (primaryPath.startsWith("<")) {
            return new ParseError("Identifier expected", 0, value.length());
        }
        int pathIdx = value.indexOf(primaryPath, prefixLength);
        return new ParsedExpression(primaryPath, pathIdx, prefixLength, kind, params);
    }

    /**
     * Maps a binding {@link org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind}
     * to its canonical {@code fx:} intrinsic keyword name.
     */
    public static @NotNull String kindToIntrinsicName(
            @NotNull Kind kind) {
        return switch (kind) {
            case EVALUATE, EVALUATE_CONTENT   -> "fx:Evaluate";
            case OBSERVE,  OBSERVE_CONTENT    -> "fx:Observe";
            case PUSH,     PUSH_CONTENT       -> "fx:Push";
            case SYNCHRONIZE, SYNCHRONIZE_CONTENT -> "fx:Synchronize";
        };
    }

    /**
     * Known binding keywords, used in the {@code ws < 0} branch
     * to distinguish a valid keyword with a missing path from an unknown keyword.
     */
    private static final java.util.Set<String> KNOWN_BINDING_KEYWORDS = java.util.Set.of(
            "fx:Evaluate", "fx:Observe", "fx:Push", "fx:Synchronize");
}
