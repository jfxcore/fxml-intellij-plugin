// Copyright (c) 2026, JFXcore. All rights reserved.
// Use of this source code is governed by the BSD-3-Clause license.

package org.jfxcore.fxml.resolve;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A directed graph of definite dependencies, preserving declaration order. */
public final class Fxml2DependencyGraph<N> {
    private final Map<N, Set<N>> edges = new LinkedHashMap<>();

    public void addDependency(@NotNull N target, @NotNull N source) {
        edges.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(source);
        edges.computeIfAbsent(source, ignored -> new LinkedHashSet<>());
    }

    /** Returns closed cycles, including their closing node. */
    public @NotNull List<List<N>> cycles() {
        List<List<N>> cycles = new ArrayList<>();
        Set<N> visited = new LinkedHashSet<>();
        List<N> path = new ArrayList<>();
        for (N node : edges.keySet()) visit(node, visited, path, cycles);
        return List.copyOf(cycles);
    }

    private void visit(N node, Set<N> visited, List<N> path, List<List<N>> cycles) {
        int begin = path.indexOf(node);
        if (begin >= 0) {
            List<N> cycle = new ArrayList<>(path.subList(begin, path.size()));
            cycle.add(node);
            cycles.add(List.copyOf(cycle));
            return;
        }
        if (!visited.add(node)) return;
        path.add(node);
        for (N source : edges.get(node)) visit(source, visited, path, cycles);
        path.removeLast();
    }
}
