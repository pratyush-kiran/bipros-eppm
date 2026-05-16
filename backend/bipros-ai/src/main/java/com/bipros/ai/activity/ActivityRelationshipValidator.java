package com.bipros.ai.activity;

import com.bipros.ai.activity.dto.ActivityAiNode;
import com.bipros.ai.activity.dto.AiPredecessor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministic safety net that takes whatever shape of activity DAG the LLM
 * returned and produces a clean, acyclic graph that the apply step can write
 * without partial-failure surprises.
 *
 * <p>Two validation passes:
 * <ol>
 *   <li><b>Drop invalid edges</b> — predecessors that reference activities not
 *       in this batch, self-references, and duplicate edges to the same
 *       predecessor.</li>
 *   <li><b>Break cycles</b> — iterative DFS with three-color marking. When a
 *       back-edge is detected, drop the edge that closes the cycle.</li>
 * </ol>
 *
 * <p>Idempotent: a clean DAG produces an identical output. The result also
 * carries counters for each kind of drop so callers can surface them in the
 * preview rationale or in logs.
 */
public final class ActivityRelationshipValidator {

    private ActivityRelationshipValidator() {}

    public record Result(
            List<ActivityAiNode> activities,
            int duplicateCodes,
            int droppedDanglingRefs,
            int droppedSelfLoops,
            int droppedDuplicateEdges,
            int droppedCycleEdges) {

        public int totalDroppedEdges() {
            return droppedDanglingRefs + droppedSelfLoops + droppedDuplicateEdges + droppedCycleEdges;
        }
    }

    public static Result validateAndClean(List<ActivityAiNode> activities) {
        if (activities == null || activities.isEmpty()) {
            return new Result(List.of(), 0, 0, 0, 0, 0);
        }

        // Build code -> activity map (first occurrence wins). Track duplicates.
        LinkedHashMap<String, ActivityAiNode> byCode = new LinkedHashMap<>();
        int duplicates = 0;
        for (ActivityAiNode a : activities) {
            if (a == null || a.code() == null || a.code().isBlank()) continue;
            if (byCode.containsKey(a.code())) {
                duplicates++;
                continue;
            }
            byCode.put(a.code(), a);
        }

        // Pass 1: drop invalid predecessors. Keep lag/type on surviving entries.
        // codeOnlyPreds is mutable — cycle-breaking mutates it in pass 2.
        Map<String, List<AiPredecessor>> cleanedPreds = new HashMap<>();
        Map<String, List<String>> codeOnlyPreds = new HashMap<>();
        int dropDangling = 0;
        int dropSelf = 0;
        int dropDup = 0;
        for (Map.Entry<String, ActivityAiNode> e : byCode.entrySet()) {
            String code = e.getKey();
            List<AiPredecessor> raw = e.getValue().predecessors();
            List<AiPredecessor> kept = new ArrayList<>(raw == null ? 0 : raw.size());
            List<String> keptCodes = new ArrayList<>(raw == null ? 0 : raw.size());
            if (raw != null) {
                Set<String> seen = new HashSet<>();
                for (AiPredecessor p : raw) {
                    if (p == null || p.code() == null || p.code().isBlank()) { dropDangling++; continue; }
                    if (p.code().equals(code)) { dropSelf++; continue; }
                    if (!byCode.containsKey(p.code())) { dropDangling++; continue; }
                    if (!seen.add(p.code())) { dropDup++; continue; }
                    kept.add(p);
                    keptCodes.add(p.code());
                }
            }
            cleanedPreds.put(code, kept);
            codeOnlyPreds.put(code, keptCodes);
        }

        // Pass 2: cycle detection + break on code-only map (mutates keptCodes lists).
        int dropCycle = breakCycles(codeOnlyPreds);

        // Rebuild activities with cleaned predecessor lists. For cycle-dropped edges,
        // filter cleanedPreds to only keep entries whose code survived cycle detection.
        List<ActivityAiNode> out = new ArrayList<>(byCode.size());
        for (Map.Entry<String, ActivityAiNode> e : byCode.entrySet()) {
            ActivityAiNode src = e.getValue();
            Set<String> survivingCodes = new HashSet<>(codeOnlyPreds.getOrDefault(src.code(), List.of()));
            List<AiPredecessor> finalPreds = cleanedPreds.getOrDefault(src.code(), List.of())
                    .stream()
                    .filter(p -> survivingCodes.contains(p.code()))
                    .collect(Collectors.toList());
            out.add(new ActivityAiNode(
                    src.code(), src.name(), src.description(),
                    src.wbsNodeCode(), src.originalDurationDays(),
                    List.copyOf(finalPreds)));
        }
        return new Result(out, duplicates, dropDangling, dropSelf, dropDup, dropCycle);
    }

    /**
     * Iterative DFS with three-color marking (WHITE=0, GRAY=1, BLACK=2).
     * When we encounter a GRAY neighbor while expanding a node, that edge
     * would close a cycle — drop it from the predecessor list and continue.
     *
     * <p>Edge semantics in our graph: a string {@code p} in
     * {@code preds[node]} represents the edge {@code p → node} (predecessor p
     * must finish before node can start). DFS walks from a node *to its
     * predecessors* (i.e. against edge direction); finding an in-progress
     * node means there's a directed cycle through the original edges.
     */
    private static int breakCycles(Map<String, List<String>> preds) {
        Map<String, Integer> color = new HashMap<>();
        for (String n : preds.keySet()) color.put(n, 0);

        int dropped = 0;
        for (String root : preds.keySet()) {
            if (color.get(root) != 0) continue;
            dropped += dfsFromRoot(root, preds, color);
        }
        return dropped;
    }

    private static int dfsFromRoot(String root,
                                   Map<String, List<String>> preds,
                                   Map<String, Integer> color) {
        int dropped = 0;
        Deque<Frame> stack = new ArrayDeque<>();
        color.put(root, 1);
        stack.push(new Frame(root));

        while (!stack.isEmpty()) {
            Frame top = stack.peek();
            List<String> ps = preds.get(top.node);
            if (ps == null || top.idx >= ps.size()) {
                color.put(top.node, 2);
                stack.pop();
                continue;
            }
            String p = ps.get(top.idx);
            Integer c = color.get(p);
            if (c == null) {
                // Shouldn't happen post-cleanup; skip defensively.
                top.idx++;
                continue;
            }
            if (c == 0) {
                top.idx++;
                color.put(p, 1);
                stack.push(new Frame(p));
            } else if (c == 1) {
                // Back-edge: drop top.node's predecessor pointer at index top.idx.
                // Don't advance idx — the next item slid into this position.
                ps.remove(top.idx);
                dropped++;
            } else {
                // BLACK — already-finished branch, no cycle.
                top.idx++;
            }
        }
        return dropped;
    }

    private static final class Frame {
        final String node;
        int idx = 0;
        Frame(String node) { this.node = node; }
    }
}
