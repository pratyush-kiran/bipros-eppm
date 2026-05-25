package com.bipros.hds.application.retrieval;

import java.util.List;

/**
 * Output of the planning phase. Captures the planner's classification of the
 * user's intent so the retrieval phase can choose the right strategy.
 *
 * @param isCompound      true if the question splits cleanly into sub-questions
 *                        (typically multi-version comparison)
 * @param subQuestions    populated only when isCompound; one per sub-question
 * @param searchQueries   1..3 short vector/keyword search strings; ignored for
 *                        intent=OVERVIEW (structural sampling is used instead)
 * @param intent          classification of the user's intent — drives the
 *                        retrieval strategy
 */
public record PlanResult(
        boolean isCompound,
        List<String> subQuestions,
        List<String> searchQueries,
        Intent intent) {

    public enum Intent {
        /** User asks a specific factual question — use vector + BM25 retrieval. */
        SPECIFIC,
        /** User asks what's in the document (overview / summary / TOC / scope). */
        OVERVIEW,
        /** User greets, chats, or asks an off-topic question — refuse politely. */
        OFF_TOPIC
    }

    /** Backwards-compatible factory for callers that don't pass intent. */
    public static PlanResult of(boolean isCompound, List<String> subQuestions, List<String> searchQueries) {
        return new PlanResult(isCompound, subQuestions, searchQueries, Intent.SPECIFIC);
    }
}
