package org.jfxcore.fxml.lang;

import com.intellij.find.usages.api.SearchTarget;
import com.intellij.find.usages.api.Usage;
import com.intellij.psi.search.SearchScope;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/** A symbol-native search target that can enumerate its own usages. */
@SuppressWarnings("UnstableApiUsage")
interface Fxml2UsageSearchTarget extends SearchTarget {

    void collectUsages(@NotNull SearchScope scope, @NotNull Consumer<? super Usage> consumer);
}
