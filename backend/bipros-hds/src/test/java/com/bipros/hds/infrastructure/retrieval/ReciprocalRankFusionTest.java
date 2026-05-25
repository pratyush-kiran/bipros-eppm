package com.bipros.hds.infrastructure.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReciprocalRankFusionTest {

    @Test
    void prefersDocsRankedInMultipleLists() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        var fused = ReciprocalRankFusion.fuse(List.of(
            List.of(a, b, c),
            List.of(b, a, d)
        ), 60, 4);
        // a is rank 0 in list1 and rank 1 in list2 → score = 1/61 + 1/62
        // b is rank 1 in list1 and rank 0 in list2 → score = 1/62 + 1/61  (same as a, ties)
        // c only in list1 at rank 2 → 1/63
        // d only in list2 at rank 2 → 1/63
        assertThat(fused).hasSize(4);
        // a/b score identically — they must be the top two in some order.
        assertThat(fused.subList(0, 2)).containsExactlyInAnyOrder(a, b);
        // c/d score identically — they must be the bottom two in some order.
        assertThat(fused.subList(2, 4)).containsExactlyInAnyOrder(c, d);
    }

    @Test
    void emptyInputsReturnsEmpty() {
        assertThat(ReciprocalRankFusion.fuse(List.<List<String>>of(), 60, 10)).isEmpty();
    }

    @Test
    void singleListEqualsItself() {
        var out = ReciprocalRankFusion.fuse(List.of(List.of("a","b","c")), 60, 3);
        assertThat(out).containsExactly("a","b","c");
    }
}
