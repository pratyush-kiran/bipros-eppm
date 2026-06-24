package com.bipros.ai.activity.dto;

import com.bipros.ai.wbs.dto.ApplyMode;

import java.util.List;
import java.util.Map;

/**
 * Apply request for AI-generated activities.
 *
 * <p>{@code wbsRemap} lets the user accept near-match suggestions surfaced in
 * the preview: keys are the original {@code wbsNodeCode} values returned by the
 * model, values are the existing project WBS codes the user wants to use
 * instead. Apply substitutes per-key before resolving wbsNodeId.
 *
 * <p>{@code strictWbs} switches the unresolved-WBS handling. {@code false}
 * (default, recommended) skips unresolvable activities and reports them in
 * {@code wbsResolutionFailures}. {@code true} causes apply to throw
 * {@code STRICT_WBS_FAILED} before any insert happens — useful when callers
 * cannot tolerate a partial apply.
 *
 * <p>{@code fromScratch} marks a "from scratch" generation (vs. from a document).
 * When {@code true}, apply compresses the computed schedule to fit inside the
 * project window instead of hard-rejecting an overrun. Null/false preserves the
 * existing behavior (the from-document path).
 */
public record ActivityAiApplyRequest(
        ApplyMode mode,
        List<ActivityAiNode> activities,
        Map<String, String> wbsRemap,
        Boolean strictWbs,
        Boolean fromScratch
) {
    /** Back-compat: defaults mode to MERGE, no remap, non-strict, not-from-scratch. */
    public ActivityAiApplyRequest(List<ActivityAiNode> activities) {
        this(ApplyMode.MERGE, activities, null, null, null);
    }

    /** Back-compat: explicit mode, no remap, non-strict, not-from-scratch. */
    public ActivityAiApplyRequest(ApplyMode mode, List<ActivityAiNode> activities) {
        this(mode, activities, null, null, null);
    }
}
