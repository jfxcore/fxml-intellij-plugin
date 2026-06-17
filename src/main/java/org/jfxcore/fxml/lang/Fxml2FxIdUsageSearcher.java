package org.jfxcore.fxml.lang;

import com.intellij.find.usages.api.PsiUsage;
import com.intellij.find.usages.api.SearchTarget;
import com.intellij.find.usages.api.Usage;
import com.intellij.find.usages.api.UsageSearchParameters;
import com.intellij.find.usages.api.UsageSearcher;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * {@link UsageSearcher} that finds all usages of an {@code fx:id} declaration when
 * triggered via IntelliJ's symbol-native "Show Usages" path.
 *
 * <p>This searcher is activated when the {@link FxIdSymbol} returned by
 * {@link Fxml2FxIdDeclarationProvider} is passed as the search target.  It delegates
 * to {@link Fxml2FxIdUsageCollector} — the same collector used by the PSI
 * {@link Fxml2FxIdFindUsagesHandlerFactory} — so that both paths always return
 * identical results.
 *
 * <p>Registered at {@code com.intellij.searcher} with
 * {@code forClass="com.intellij.find.usages.api.UsageSearchParameters"}.
 */
@SuppressWarnings("UnstableApiUsage")
public final class Fxml2FxIdUsageSearcher implements UsageSearcher {

    @Override
    public @Unmodifiable @NotNull Collection<? extends Usage> collectImmediateResults(
            @NotNull UsageSearchParameters parameters) {

        SearchTarget target = parameters.getTarget();
        if (!(target instanceof FxIdSymbol symbol)) {
            return List.of();
        }

        XmlAttributeValue declaration = symbol.getDeclaration();
        if (declaration == null) {
            return List.of();
        }

        List<Usage> result = new ArrayList<>();

        Fxml2FxIdUsageCollector.collect(
                declaration,
                parameters.getSearchScope(),
                ref -> {
                    result.add(PsiUsage.textUsage(ref.getElement(), ref.getRangeInElement()));
                    return true;
                },
                el -> {
                    result.add(PsiUsage.textUsage(el, TextRange.from(0, el.getTextLength())));
                    return true;
                }
        );

        return result;
    }
}
