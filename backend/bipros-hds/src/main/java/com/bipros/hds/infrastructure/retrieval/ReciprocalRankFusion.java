package com.bipros.hds.infrastructure.retrieval;

import java.util.*;

/**
 * Reciprocal Rank Fusion (RRF) — combines multiple ranked lists into one.
 * Standard formula: score(d) = sum over ranklists of 1 / (k + rank(d))
 * where k smooths the impact of high ranks. k=60 is the canonical default.
 */
public final class ReciprocalRankFusion {

    private ReciprocalRankFusion() {}

    /**
     * @param ranklists each is an ordered list of doc IDs (rank 0 = highest)
     * @param k smoothing constant (60 by convention)
     * @param limit max items in output
     * @return fused doc IDs in best-first order
     */
    public static <T> List<T> fuse(List<List<T>> ranklists, int k, int limit) {
        Map<T, Double> scores = new HashMap<>();
        for (List<T> list : ranklists) {
            for (int i = 0; i < list.size(); i++) {
                T doc = list.get(i);
                scores.merge(doc, 1.0 / (k + i + 1), Double::sum);
            }
        }
        return scores.entrySet().stream()
            .sorted(Map.Entry.<T, Double>comparingByValue().reversed())
            .limit(limit)
            .map(Map.Entry::getKey)
            .toList();
    }
}
