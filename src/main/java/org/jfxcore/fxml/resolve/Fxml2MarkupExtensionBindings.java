package org.jfxcore.fxml.resolve;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.intellij.psi.PsiClass;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jfxcore.fxml.lang.Fxml2BindingNotationReference.Kind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Binding values within extension configuration, excluding literal and quoted text. */
public final class Fxml2MarkupExtensionBindings {
    private Fxml2MarkupExtensionBindings() {}

    public record Binding(@NotNull Fxml2BindingExpressionParser.ParsedExpression expression, int offset) {}

    public record ResolvedBinding(@NotNull PsiClass startClass,
                                  @NotNull List<Fxml2BindingPathResolver.Segment> segments, int pathOffset) {
        public ResolvedBinding {
            segments = List.copyOf(segments);
        }
    }

    /** Resolves a configuration binding and locates its path within the configuration text. */
    public static @Nullable ResolvedBinding resolve(
            @NotNull Binding binding, @Nullable XmlTag context, @NotNull XmlFile file) {
        var expression = binding.expression();
        var selector = Fxml2BindingExpressionParser.parseContextSelector(expression.path());
        String path = selector != null ? selector.remainingPath() : expression.path();
        PsiClass start = context != null ? Fxml2BindingPathResolver.resolveStartClass(selector, context, file)
                : Fxml2BindingPathResolver.resolveCodeBehindClass(file);
        if (start == null || path.isBlank()) return null;
        return new ResolvedBinding(start,
                Fxml2BindingPathResolver.resolve(path, start, file.getResolveScope(), Kind.EVALUATE, file),
                binding.offset() + expression.pathOffset() + (selector != null ? selector.selectorLength() : 0));
    }

    public static @NotNull List<Binding> parse(
            @NotNull String content, @NotNull Map<Character, String> prefixes) {
        List<Binding> bindings = new ArrayList<>();
        collect(content, 0, prefixes, bindings, 0);
        return List.copyOf(bindings);
    }

    private static void collect(String content, int base, Map<Character, String> prefixes,
                                List<Binding> bindings, int depth) {
        if (depth > 128) return;
        for (var section : Fxml2MarkupExtensionContentParser.parse(content)) {
            String value;
            int offset;
            if (section instanceof Fxml2MarkupExtensionContentParser.NamedParameter parameter) {
                value = parameter.value();
                offset = parameter.valueOffset();
            } else {
                var positional = (Fxml2MarkupExtensionContentParser.PositionalValue)section;
                value = positional.text();
                offset = positional.offset();
            }
            for (var item : Fxml2ValueSequenceParser.split(value, prefixes)) {
                if (!item.isMarkupExtension()) continue;
                int itemOffset = base + offset + item.offset();
                var expression = Fxml2BindingExpressionParser.parseExpressionLenient(item.text());
                if (expression != null) {
                    bindings.add(new Binding(expression, itemOffset));
                } else {
                    var extension = Fxml2MarkupExtensionParser.parse(item.text());
                    if (extension != null) {
                        collect(extension.content().textOf(item.text()), itemOffset + extension.content().start(),
                                prefixes, bindings, depth + 1);
                    } else if (Fxml2BindingExpressionParser.parse(item.text(), prefixes)
                            instanceof Fxml2BindingExpressionParser.PrefixShorthandExpression) {
                        collect(item.text().substring(1), itemOffset + 1, prefixes, bindings, depth + 1);
                    }
                }
            }
        }
    }
}
