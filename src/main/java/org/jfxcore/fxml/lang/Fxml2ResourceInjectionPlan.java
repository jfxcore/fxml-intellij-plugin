package org.jfxcore.fxml.lang;

import com.intellij.lang.Language;
import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NotNull;
import org.jfxcore.fxml.resource.Fxml2ResourceDeclaration;
import org.jfxcore.fxml.resource.Fxml2ResourceInstruction;
import org.jfxcore.fxml.resource.Fxml2ResourceInstructionParser;
import org.jfxcore.fxml.resource.Fxml2ResourceParseResult;
import org.jfxcore.fxml.resource.Fxml2ResourcePayloadLanguage;
import org.jfxcore.fxml.resource.Fxml2ResourceScanner;

import java.util.ArrayList;
import java.util.List;

/**
 * How markup embedded in a {@code @ComponentView} annotation value is split into injected
 * fragments.
 *
 * <p>Markup embedded in an annotation value is itself an injected fragment, and the platform does
 * not run injectors inside an injected file, so a resource declaration in embedded markup can
 * never host a nested injection the way it does in a standalone document.  Two injected fragments
 * may also not overlap, and only the first injector registered for a host element is consulted at
 * all.  The way through all three constraints is for the single injector that owns the host to
 * produce both fragments itself, in one registration round:
 *
 * <ul>
 *   <li>the markup fragment is injected in several places that skip the payloads, so the XML
 *       parser sees {@code <?resource styles.css text/css:?>} with an empty payload;</li>
 *   <li>each payload is injected separately, in its own language, over the hole left behind.</li>
 * </ul>
 *
 * <p>Because the markup fragment and the payload fragments are planned together from the same
 * scan of the host text, the XML view of a document and the payload injected into it cannot
 * disagree about where the payload sits.
 *
 * <p>When a declaration cannot be read confidently, for instance while it is being typed, the plan
 * degrades to a single markup fragment covering everything and no payload injection at all.  A
 * malformed declaration must never break the XML view of the surrounding markup.
 *
 * @param markupRanges the ranges of the host that make up the markup fragment, in order
 * @param payloads     the payload fragments to inject, in order
 */
public record Fxml2ResourceInjectionPlan(@NotNull List<TextRange> markupRanges,
                                         @NotNull List<Fxml2PayloadInjection> payloads) {

    public Fxml2ResourceInjectionPlan {
        markupRanges = List.copyOf(markupRanges);
        payloads = List.copyOf(payloads);
    }

    /**
     * Plans how to inject the markup occupying {@code valueRange} of {@code hostText}.
     *
     * @param hostText   the raw text of the injection host
     * @param valueRange the range of {@code hostText} that holds the markup
     */
    public static @NotNull Fxml2ResourceInjectionPlan of(@NotNull String hostText, @NotNull TextRange valueRange) {
        List<Fxml2PayloadInjection> payloads = new ArrayList<>();

        for (Fxml2ResourceInstruction instruction : Fxml2ResourceScanner.scanAll(hostText)) {
            TextRange instructionRange = instruction.instruction().toTextRange();
            if (!valueRange.contains(instructionRange)) continue;

            if (!instruction.hasPayload()) {
                // A declaration with no content separator cannot be split on; leave the
                // document as one fragment rather than guessing where the payload would be.
                return single(valueRange);
            }

            Fxml2ResourceParseResult result = Fxml2ResourceInstructionParser.parse(hostText, instruction);
            Fxml2ResourceDeclaration declaration = result.declaration();
            if (declaration == null) return single(valueRange);
            if (declaration.payloadSpan().isEmpty()) continue;

            Language language = Fxml2ResourcePayloadLanguage.of(declaration).languageOrPlainText();
            payloads.add(new Fxml2PayloadInjection(declaration.payloadSpan().toTextRange(), language));
        }

        return payloads.isEmpty()
                ? single(valueRange)
                : new Fxml2ResourceInjectionPlan(markupRangesAround(valueRange, payloads), payloads);
    }

    /** Returns {@code true} when the markup is injected as one contiguous fragment. */
    public boolean isSingleFragment() {
        return markupRanges.size() == 1 && payloads.isEmpty();
    }

    private static @NotNull Fxml2ResourceInjectionPlan single(@NotNull TextRange valueRange) {
        return new Fxml2ResourceInjectionPlan(List.of(valueRange), List.of());
    }

    /**
     * Returns the parts of {@code valueRange} that are not covered by a payload, which is what is
     * left for the markup fragment.
     */
    private static @NotNull List<TextRange> markupRangesAround(@NotNull TextRange valueRange,
                                                               @NotNull List<Fxml2PayloadInjection> payloads) {
        List<TextRange> ranges = new ArrayList<>();
        int cursor = valueRange.getStartOffset();

        for (Fxml2PayloadInjection payload : payloads) {
            if (payload.range().getStartOffset() > cursor) {
                ranges.add(TextRange.create(cursor, payload.range().getStartOffset()));
            }
            cursor = payload.range().getEndOffset();
        }

        if (cursor < valueRange.getEndOffset()) {
            ranges.add(TextRange.create(cursor, valueRange.getEndOffset()));
        }

        return ranges.isEmpty() ? List.of(valueRange) : ranges;
    }

    /**
     * One payload fragment: the range of the host it occupies, and the language it is edited in.
     *
     * @param range    the range of the raw payload in the host text
     * @param language the language to inject
     */
    public record Fxml2PayloadInjection(@NotNull TextRange range, @NotNull Language language) {}
}
