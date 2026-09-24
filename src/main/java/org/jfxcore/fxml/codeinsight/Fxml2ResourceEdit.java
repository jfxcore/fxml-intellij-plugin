package org.jfxcore.fxml.codeinsight;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resolve.Fxml2TextSpan;
import org.jfxcore.fxml.resource.Fxml2MediaTypeParameter;
import org.jfxcore.fxml.resource.Fxml2ResourceEntry;
import org.jfxcore.fxml.resource.Fxml2ResourceMediaType;
import org.jfxcore.fxml.resource.Fxml2ResourceName;
import org.jfxcore.fxml.resource.Fxml2ResourceProblem;
import org.jfxcore.fxml.resource.Fxml2ResourceSyntax;

import java.util.List;

/** A typed text edit offered for an embedded resource declaration. */
sealed interface Fxml2ResourceEdit {

    @NotNull String name();

    @NotNull String familyName();

    void apply(@NotNull Project project, @NotNull Fxml2ResourceEntry entry);

    record Rename(@NotNull Fxml2ResourceName replacement) implements Fxml2ResourceEdit {
        @Override
        public @NotNull String name() {
            return "Rename resource to '" + replacement.value() + "'";
        }

        @Override
        public @NotNull String familyName() {
            return "Make resource name portable";
        }

        @Override
        public void apply(@NotNull Project project, @NotNull Fxml2ResourceEntry entry) {
            Fxml2ResourceDeclarationEditor.rename(project, entry, replacement);
        }
    }

    record SetMediaType(@NotNull Fxml2ResourceMediaType mediaType) implements Fxml2ResourceEdit {
        @Override
        public @NotNull String name() {
            return "Set media type to '" + mediaType.essence() + "'";
        }

        @Override
        public @NotNull String familyName() {
            return "Set embedded resource media type";
        }

        @Override
        public void apply(@NotNull Project project, @NotNull Fxml2ResourceEntry entry) {
            List<Fxml2MediaTypeParameter> parameters = entry.declaration().hasExplicitMediaType()
                    ? entry.declaration().effectiveMediaType().parameters()
                    : List.of();
            String replacement = new Fxml2ResourceMediaType(
                    mediaType.type(), mediaType.subtype(), parameters).text();

            Fxml2TextSpan span = entry.declaration().mediaTypeSpan();
            if (!entry.declaration().hasExplicitMediaType()) {
                int afterName = entry.declaration().quotedNameSpan().end();
                span = new Fxml2TextSpan(afterName, afterName);
                replacement = " " + replacement;
            }

            Fxml2ResourceDeclarationEditor.replace(project, entry, span, replacement);
        }
    }

    record RemoveParameter(@NotNull String parameterName) implements Fxml2ResourceEdit {
        @Override
        public @NotNull String name() {
            return "Remove duplicate media type parameter '" + parameterName + "'";
        }

        @Override
        public @NotNull String familyName() {
            return "Remove duplicate media type parameter";
        }

        @Override
        public void apply(@NotNull Project project, @NotNull Fxml2ResourceEntry entry) {
            Fxml2TextSpan parameter = entry.problems().stream()
                    .map(Fxml2ResourceProblem::duplicateParameter)
                    .filter(value -> value != null && value.hasName(parameterName))
                    .map(Fxml2MediaTypeParameter::span)
                    .findFirst()
                    .orElse(null);
            if (parameter == null) return;

            String text = entry.anchor().getText();
            int start = parameter.start();
            int end = Math.min(parameter.end(), text.length());
            if (start < 0 || start >= end) return;

            while (start > 0 && Fxml2ResourceSyntax.isHorizontalWhitespace(text.charAt(start - 1))) --start;
            if (start > 0 && text.charAt(start - 1) == ';') --start;
            while (start > 0 && Fxml2ResourceSyntax.isHorizontalWhitespace(text.charAt(start - 1))) --start;
            Fxml2ResourceDeclarationEditor.replace(project, entry, new Fxml2TextSpan(start, end), "");
        }
    }

    record RemoveDeclaration() implements Fxml2ResourceEdit {
        @Override
        public @NotNull String name() {
            return "Remove embedded resource declaration";
        }

        @Override
        public @NotNull String familyName() {
            return name();
        }

        @Override
        public void apply(@NotNull Project project, @NotNull Fxml2ResourceEntry entry) {
            Fxml2ResourceDeclarationEditor.replace(project, entry, spanWithOwnLine(entry), "");
        }

        private static @NotNull Fxml2TextSpan spanWithOwnLine(@NotNull Fxml2ResourceEntry entry) {
            int anchorStart = entry.anchor().getTextRange().getStartOffset();
            Fxml2TextSpan instruction = entry.instructionSpan().shifted(anchorStart);
            return surroundingLineSpan(entry.declaringFile().getText(), instruction).shifted(-anchorStart);
        }

        private static @NotNull Fxml2TextSpan surroundingLineSpan(@NotNull String text,
                                                                  @NotNull Fxml2TextSpan instruction) {
            int start = instruction.start();
            while (start > 0 && Fxml2ResourceSyntax.isHorizontalWhitespace(text.charAt(start - 1))) --start;
            boolean startsLine = start == 0 || text.charAt(start - 1) == '\n';

            int end = Math.min(instruction.end(), text.length());
            while (end < text.length() && Fxml2ResourceSyntax.isHorizontalWhitespace(text.charAt(end))) ++end;
            boolean endsLine = end == text.length() || text.charAt(end) == '\n';

            return startsLine && endsLine
                    ? new Fxml2TextSpan(start, end < text.length() ? end + 1 : end)
                    : instruction;
        }
    }
}
