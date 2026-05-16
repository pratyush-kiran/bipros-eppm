package com.bipros.ai.persona;

import java.util.List;

/**
 * Per-role persona block appended to the system prompt. Anchors tone and
 * KPI focus for that profile so the LLM defaults to the questions the
 * profile cares about.
 *
 * @param headline      one-line "You are assisting a Site Manager."
 * @param primaryKpis   3-5 KPI names in business terms ("Labour utilization %", …)
 * @param preferTools   tool names this profile should reach for first
 * @param framingHint   one short sentence on how to frame answers
 */
public record RolePersona(
        String headline,
        List<String> primaryKpis,
        List<String> preferTools,
        String framingHint
) {
    /** Renders the persona as a prompt block. Returns "" for the null persona. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n────────────────────────────────────────\n");
        sb.append("ROLE PERSONA\n");
        sb.append("────────────────────────────────────────\n");
        sb.append(headline).append("\n");
        sb.append("Primary KPIs to lead with: ")
                .append(String.join(", ", primaryKpis)).append(".\n");
        sb.append("When the user's question is open-ended, prefer these tools first: ")
                .append(String.join(", ", preferTools)).append(".\n");
        sb.append(framingHint).append("\n");
        sb.append(constructionDomainSuffix());
        return sb.toString();
    }

    /**
     * Construction-domain rules that anchor the AI on real EPC site practice — SC-180
     * Khasab–Daba is the customer's flagship project. Included on every persona render so
     * the orchestrator can rely on the persona block for these guarantees regardless of
     * which role-specific persona was chosen. Static so the orchestrator can also splice
     * it in unconditionally when no role persona is matched.
     */
    public static String constructionDomainSuffix() {
        return """

            ────────────────────────────────────────
            CONSTRUCTION-DOMAIN RULES (ALWAYS APPLY)
            ────────────────────────────────────────
            You are advising on EPC construction project execution.

            **DEFAULT PROJECT (NON-NEGOTIABLE).**
            SC-180 — SC 180 — Khasab–Daba Asphalt Road & Link to Lima is the
            customer's flagship project and the **default scope for every
            project-scoped question** when the user has not named a specific
            project. This covers concrete pours, manpower, equipment,
            daily balance sheets, DPRs, cost, schedule, productivity, EVM
            metrics, norms, and any other site-execution question.

            How to apply:
              - If the user's wording explicitly names a different project
                (code or name), use that project — the user's choice always
                wins.
              - Otherwise, **silently adopt SC-180** as the project for this
                turn. Resolve its UUID from `list_projects` (it is in the
                accessible roster) and pass that UUID to every project-scoped
                tool you call.
              - Identify SC-180 once in your prose by `<code> — <name>`.
              - **NEVER** respond with "I need the project scope", "Please
                tell me which project", or any equivalent clarifying request
                when the user has asked a project-scoped question without
                naming one — that's a refusal-to-answer pattern and is
                explicitly forbidden here. SC-180 is the default; use it.

            When the user asks about supervisor performance, cost, variance,
            productivity, or any formula-related question, you MUST:
              1. Call `formula_validate` (or `supervisor` for supervisor-specific rollups)
                 before answering. Do not estimate or guess from training data.
              2. Show the formula in human-readable form (e.g., "CV = EV − AC"), list the numeric
                 inputs you used (with units), and report the computed value.
              3. Cite the data scope: number of DPR rows aggregated, date range, and which entity
                 (EvmCalculation / DprManpower / DprEquipment / ConcretePour) supplied each input.
              4. For Daily Balance Sheet questions, call `dbs_report`. For concrete production
                 questions, query ConcretePour aggregates.
              5. Convert chainage values to "km+m" format when presenting to users (45000 → "45+000").
              6. Default currency is OMR (Omani Rial); state it explicitly.
            """;
    }
}
