package org.jfxcore.fxml.lang;

import com.intellij.find.usages.api.Usage;
import com.intellij.find.usages.api.UsageSearchParameters;
import com.intellij.find.usages.api.UsageSearcher;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Collects usages from every FXML/2 symbol-native search target. */
@SuppressWarnings("UnstableApiUsage")
public final class Fxml2UsageSearcher implements UsageSearcher {

    @Override
    public @Unmodifiable @NotNull Collection<? extends Usage> collectImmediateResults(
            @NotNull UsageSearchParameters parameters) {
        if (!(parameters.getTarget() instanceof Fxml2UsageSearchTarget target)) return List.of();

        List<Usage> usages = new ArrayList<>();
        target.collectUsages(parameters.getSearchScope(), usages::add);
        return usages;
    }
}
