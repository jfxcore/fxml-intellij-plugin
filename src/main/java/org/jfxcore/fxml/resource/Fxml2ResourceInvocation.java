// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.resource;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfxcore.fxml.resolve.Fxml2MarkupExtensionContentParser;
import org.jfxcore.fxml.resolve.Fxml2TextSpan;

import java.util.ArrayList;
import java.util.List;

/** A resource name and optional loader expression, with ranges in extension content. */
public record Fxml2ResourceInvocation(@NotNull Fxml2ResourceName name,
                                      @NotNull Fxml2TextSpan nameSpan,
                                      @Nullable LoaderExpression loader,
                                      @NotNull List<Integer> sourceOffsets) {
    public Fxml2ResourceInvocation {
        sourceOffsets = List.copyOf(sourceOffsets);
    }

    /** Maps a decoded name segment back to its source content. */
    public @NotNull Fxml2TextSpan sourceSpan(int start, int end) {
        return new Fxml2TextSpan(sourceOffsets.get(start), sourceOffsets.get(end));
    }

    public record LoaderExpression(@NotNull String text) {}

    /** Parses the content following a resource extension name or prefix. */
    public static @Nullable Fxml2ResourceInvocation parse(@NotNull String content) {
        StringBuilder masked = new StringBuilder(content);
        boolean[] omitted = new boolean[content.length()];
        boolean quoted = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '\\' && quoted) { i++; continue; }
            if (ch == '\'') { quoted = !quoted; continue; }
            if (!quoted && content.startsWith("/*", i)) {
                int end = content.indexOf("*/", i + 2);
                if (end < 0) return null;
                end += 2;
                for (int j = i; j < end; j++) {
                    masked.setCharAt(j, ' ');
                    omitted[j] = true;
                }
                i = end - 1;
            }
        }
        Fxml2TextSpan nameSpan = null;
        LoaderExpression loader = null;
        for (var section : Fxml2MarkupExtensionContentParser.parse(masked.toString())) {
            if (section instanceof Fxml2MarkupExtensionContentParser.PositionalValue(var text, var offset)) {
                if (nameSpan != null) return null;
                nameSpan = new Fxml2TextSpan(offset, offset + text.length());
            } else if (section instanceof Fxml2MarkupExtensionContentParser.NamedParameter parameter) {
                if ("value".equals(parameter.name())) {
                    if (nameSpan != null) return null;
                    nameSpan = new Fxml2TextSpan(parameter.valueOffset(),
                            parameter.valueOffset() + parameter.value().length());
                } else if ("classLoader".equals(parameter.name())) {
                    loader = new LoaderExpression(parameter.value());
                }
            }
        }
        if (nameSpan == null || nameSpan.isEmpty()) return null;
        boolean stringLiteral = content.charAt(nameSpan.start()) == '\''
                && content.charAt(nameSpan.end() - 1) == '\'';
        int begin = nameSpan.start() + (stringLiteral ? 1 : 0);
        int finish = nameSpan.end() - (stringLiteral ? 1 : 0);
        StringBuilder literal = new StringBuilder();
        List<Integer> offsets = new ArrayList<>();
        for (int i = begin; i < finish; i++) {
            if (omitted[i]) continue;
            char ch = content.charAt(i);
            offsets.add(i);
            if (stringLiteral && ch == '\\' && i + 1 < finish) {
                ch = content.charAt(++i);
                ch = switch (ch) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    default -> ch;
                };
            }
            literal.append(ch);
        }
        offsets.add(finish);
        if (!stringLiteral && (literal.toString().startsWith("$") || literal.toString().startsWith("{"))) return null;
        return new Fxml2ResourceInvocation(new Fxml2ResourceName(literal.toString()), nameSpan, loader, offsets);
    }
}
