package com.bipros.ai.activity;

import com.bipros.ai.activity.dto.ActivityAiNode;
import com.bipros.ai.activity.dto.AiPredecessor;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityRelationshipValidatorTest {

    private static AiPredecessor pred(String code) {
        return new AiPredecessor(code, 0.0, "FS");
    }

    private static ActivityAiNode act(String code, String name, String wbs, double dur, String... preds) {
        List<AiPredecessor> predecessors = Arrays.stream(preds).map(ActivityRelationshipValidatorTest::pred).collect(Collectors.toList());
        return new ActivityAiNode(code, name, null, wbs, dur, predecessors);
    }

    private static List<String> predCodes(ActivityAiNode node) {
        return node.predecessors().stream().map(AiPredecessor::code).collect(Collectors.toList());
    }

    @Test
    void emptyOrNullInputProducesEmptyResult() {
        ActivityRelationshipValidator.Result r = ActivityRelationshipValidator.validateAndClean(null);
        assertThat(r.activities()).isEmpty();
        assertThat(r.totalDroppedEdges()).isZero();
        assertThat(ActivityRelationshipValidator.validateAndClean(List.of()).activities()).isEmpty();
    }

    @Test
    void cleanInputIsIdempotent() {
        List<ActivityAiNode> in = List.of(
                act("A-001", "Mobilization",   "1.3", 14),
                act("A-002", "Site clearance", "2.1", 21, "A-001"),
                act("A-003", "Excavation",     "3.1", 60, "A-002")
        );
        ActivityRelationshipValidator.Result r = ActivityRelationshipValidator.validateAndClean(in);
        assertThat(r.totalDroppedEdges()).isZero();
        assertThat(r.duplicateCodes()).isZero();
        assertThat(r.activities()).hasSize(3);
        assertThat(predCodes(r.activities().get(2))).containsExactly("A-002");
    }

    @Test
    void unresolvedPredecessorIsDropped() {
        // A-002's predecessor "Z-999" doesn't exist in the batch.
        List<ActivityAiNode> in = List.of(
                act("A-001", "First",  "1.1", 5),
                act("A-002", "Second", "1.2", 5, "Z-999")
        );
        ActivityRelationshipValidator.Result r = ActivityRelationshipValidator.validateAndClean(in);
        assertThat(r.droppedDanglingRefs()).isEqualTo(1);
        assertThat(predCodes(r.activities().get(1))).isEmpty();
    }

    @Test
    void selfReferencingPredecessorIsDropped() {
        List<ActivityAiNode> in = List.of(
                act("A-001", "Self-loop", "1.1", 5, "A-001")
        );
        ActivityRelationshipValidator.Result r = ActivityRelationshipValidator.validateAndClean(in);
        assertThat(r.droppedSelfLoops()).isEqualTo(1);
        assertThat(predCodes(r.activities().get(0))).isEmpty();
    }

    @Test
    void duplicatePredecessorEdgeIsDropped() {
        // A-001 listed twice as predecessor of A-002 → second drops.
        List<ActivityAiNode> in = List.of(
                act("A-001", "First",  "1.1", 5),
                act("A-002", "Second", "1.2", 5, "A-001", "A-001")
        );
        ActivityRelationshipValidator.Result r = ActivityRelationshipValidator.validateAndClean(in);
        assertThat(r.droppedDuplicateEdges()).isEqualTo(1);
        assertThat(predCodes(r.activities().get(1))).containsExactly("A-001");
    }

    @Test
    void duplicateActivityCodeKeepsFirstOccurrence() {
        List<ActivityAiNode> in = List.of(
                act("A-001", "First",     "1.1", 5),
                act("A-001", "Duplicate", "1.2", 7)
        );
        ActivityRelationshipValidator.Result r = ActivityRelationshipValidator.validateAndClean(in);
        assertThat(r.duplicateCodes()).isEqualTo(1);
        assertThat(r.activities()).hasSize(1);
        assertThat(r.activities().get(0).name()).isEqualTo("First");
    }

    @Test
    void simpleTwoNodeCycleIsBroken() {
        // A-001 ← A-002 and A-002 ← A-001 → cycle of length 2.
        List<ActivityAiNode> in = List.of(
                act("A-001", "First",  "1.1", 5, "A-002"),
                act("A-002", "Second", "1.2", 5, "A-001")
        );
        ActivityRelationshipValidator.Result r = ActivityRelationshipValidator.validateAndClean(in);
        assertThat(r.droppedCycleEdges()).isEqualTo(1);
        // Exactly one of the two edges should remain.
        int totalEdges = predCodes(r.activities().get(0)).size()
                       + predCodes(r.activities().get(1)).size();
        assertThat(totalEdges).isEqualTo(1);
    }

    @Test
    void threeNodeCycleIsBroken() {
        // A-001 ← A-002 ← A-003 ← A-001 (transitive cycle)
        List<ActivityAiNode> in = List.of(
                act("A-001", "First",  "1.1", 5, "A-003"),
                act("A-002", "Second", "1.2", 5, "A-001"),
                act("A-003", "Third",  "1.3", 5, "A-002")
        );
        ActivityRelationshipValidator.Result r = ActivityRelationshipValidator.validateAndClean(in);
        assertThat(r.droppedCycleEdges()).isEqualTo(1);
        // Exactly two of the three edges should remain.
        int totalEdges = r.activities().stream()
                .mapToInt(a -> a.predecessors().size())
                .sum();
        assertThat(totalEdges).isEqualTo(2);
    }

    @Test
    void diamondShapedDagIsAcceptedAsAcyclic() {
        // A-001 → A-002, A-001 → A-003, A-002 → A-004, A-003 → A-004
        // Two paths from A-001 to A-004 — DAG, no cycle.
        List<ActivityAiNode> in = List.of(
                act("A-001", "Start",  "1.1", 5),
                act("A-002", "BranchA","1.2", 5, "A-001"),
                act("A-003", "BranchB","1.3", 5, "A-001"),
                act("A-004", "Merge",  "1.4", 5, "A-002", "A-003")
        );
        ActivityRelationshipValidator.Result r = ActivityRelationshipValidator.validateAndClean(in);
        assertThat(r.totalDroppedEdges()).isZero();
        assertThat(predCodes(r.activities().get(3))).containsExactly("A-002", "A-003");
    }

    @Test
    void mixedValidAndInvalidEdgesPreservesValidOnes() {
        // A-002 has [A-001 (valid), Z-999 (dangling), A-002 (self), A-001 (dup)]
        List<ActivityAiNode> in = List.of(
                act("A-001", "First",  "1.1", 5),
                act("A-002", "Second", "1.2", 5, "A-001", "Z-999", "A-002", "A-001")
        );
        ActivityRelationshipValidator.Result r = ActivityRelationshipValidator.validateAndClean(in);
        assertThat(r.droppedDanglingRefs()).isEqualTo(1);
        assertThat(r.droppedSelfLoops()).isEqualTo(1);
        assertThat(r.droppedDuplicateEdges()).isEqualTo(1);
        assertThat(predCodes(r.activities().get(1))).containsExactly("A-001");
    }

    @Test
    void blankAndNullCodesAreIgnored() {
        ActivityAiNode bad1 = new ActivityAiNode(null, "no code", null, "1.1", 5d, List.of());
        ActivityAiNode bad2 = new ActivityAiNode("", "blank code", null, "1.1", 5d, List.of());
        ActivityAiNode good = act("A-001", "good", "1.1", 5);
        ActivityRelationshipValidator.Result r = ActivityRelationshipValidator.validateAndClean(List.of(bad1, bad2, good));
        assertThat(r.activities()).hasSize(1);
        assertThat(r.activities().get(0).code()).isEqualTo("A-001");
    }

    @Test
    void lagIsPreservedAfterValidation() {
        // A-002 has A-001 as predecessor with 5 days lag — lag must survive validation.
        AiPredecessor predWithLag = new AiPredecessor("A-001", 5.0, "FS");
        ActivityAiNode a1 = new ActivityAiNode("A-001", "First",  null, "1.1", 10d, List.of());
        ActivityAiNode a2 = new ActivityAiNode("A-002", "Second", null, "1.2", 7d,  List.of(predWithLag));
        ActivityRelationshipValidator.Result r = ActivityRelationshipValidator.validateAndClean(List.of(a1, a2));
        assertThat(r.totalDroppedEdges()).isZero();
        AiPredecessor surviving = r.activities().get(1).predecessors().get(0);
        assertThat(surviving.lagDays()).isEqualTo(5.0);
        assertThat(surviving.type()).isEqualTo("FS");
    }

    @Test
    void totalDroppedEdgesAggregatesAllKinds() {
        ActivityRelationshipValidator.Result r = new ActivityRelationshipValidator.Result(
                List.of(), 0, 2, 1, 3, 1);
        assertThat(r.totalDroppedEdges()).isEqualTo(7);
    }
}
