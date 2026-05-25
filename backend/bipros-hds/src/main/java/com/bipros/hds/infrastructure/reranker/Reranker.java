package com.bipros.hds.infrastructure.reranker;

import java.util.List;

public interface Reranker {
    /** Returns indices into {@code candidates} in best-first order. May return fewer than topK. */
    List<Integer> rerank(String query, List<String> candidates, int topK);
}
