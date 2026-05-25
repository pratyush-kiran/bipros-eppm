package com.bipros.hds.infrastructure.reranker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NoopRerankerTest {
    @Test
    void preservesOrderAndCapsAtTopK() {
        var r = new NoopReranker();
        var out = r.rerank("q", List.of("a", "b", "c", "d"), 2);
        assertThat(out).containsExactly(0, 1);
    }

    @Test
    void handlesEmpty() {
        var r = new NoopReranker();
        assertThat(r.rerank("q", List.of(), 5)).isEmpty();
    }
}
