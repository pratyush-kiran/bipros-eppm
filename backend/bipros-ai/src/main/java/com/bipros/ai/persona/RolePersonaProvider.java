package com.bipros.ai.persona;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Static lookup of {@link RolePersona} by profile code. Returns {@code null}
 * for unknown profiles so the orchestrator skips the persona block (generic
 * prompt only).
 */
@Component
public class RolePersonaProvider {

    private static final RolePersona QC_PERSONA = new RolePersona(
            "You are assisting a Quality Control Manager — focus on process adherence and traceability.",
            List.of(
                    "NCR rate per crew / source",
                    "Material lot ↔ operator ↔ location traceability",
                    "Quality data completeness"),
            List.of(
                    "analyze_ncr_trends",
                    "audit_traceability",
                    "analyze_quality_data_gaps",
                    "query_dpr"),
            "Frame answers as process compliance gaps, by crew or by source, with traceable links where possible."
    );

    private static final Map<String, RolePersona> PERSONAS = Map.of(
            "SITE_MANAGER", new RolePersona(
                    "You are assisting a Site Manager — focus on today's execution wins and losses.",
                    List.of(
                            "Labour utilization %",
                            "Machine idle time %",
                            "Material wastage %",
                            "Stockpile-to-need ratio"),
                    List.of(
                            "get_capacity_utilization",
                            "deployment_utilization",
                            "get_subcontractor_kpis",
                            "analyze_labour_utilization",
                            "analyze_machine_idle_time",
                            "analyze_material_wastage",
                            "check_stockpile_vs_plan",
                            "query_dpr"),
                    "Frame answers as today's wins or losses, by crew or by location, in plain operational terms."
            ),
            "PROJECT_ENGINEER", new RolePersona(
                    "You are assisting a Project Engineer — focus on output standards, yield, and method efficiency.",
                    List.of(
                            "Productivity factor (output / man-hour vs norm)",
                            "Yield variance % (actual vs design)",
                            "Cycle time"),
                    List.of(
                            "get_capacity_utilization",
                            "get_subcontractor_kpis",
                            "analyze_productivity_factor",
                            "analyze_yield_variance",
                            "analyze_equipment_cycle_time",
                            "query_dpr"),
                    "Frame answers as design vs actual, by activity, with the variance number leading."
            ),
            "QC_MANAGER", QC_PERSONA,
            "QA_QC_ENGINEER", QC_PERSONA,
            "PROJECT_MANAGER", new RolePersona(
                    "You are assisting a Project Manager — focus on cost, schedule, and overall delivery health.",
                    List.of(
                            "CPI / SPI",
                            "Labour cost per unit installed",
                            "Material burn rate",
                            "Equipment utilization %"),
                    List.of(
                            "portfolio_kpi",
                            "get_capacity_utilization",
                            "get_subcontractor_kpis",
                            "analyze_cost",
                            "analyze_labour_cost_per_unit",
                            "analyze_material_burn_rate",
                            "analyze_equipment_utilization_cost",
                            "forecast_completion"),
                    "Frame answers as money-and-time impact, with the headline number leading. "
                            + "Activities use a Draft / Locked lifecycle: Draft = plan still being "
                            + "edited (DPR submission rejected); Locked = plan frozen, DPRs flow. "
                            + "Suggest locking an activity once planning is complete."
            ),
            "BIM_DATA_COORDINATOR", new RolePersona(
                    "You are assisting a BIM / Data Coordinator — focus on data integrity and entry latency.",
                    List.of(
                            "DPR data completeness %",
                            "Site → system entry lag",
                            "Missing breakdown / linkage"),
                    List.of(
                            "audit_dpr_data_quality",
                            "report_data_lag",
                            "list_projects"),
                    "Frame answers as data-quality gaps to fix, by project and by missing-field category."
            )
    );

    public RolePersona forProfile(String profileCode) {
        if (profileCode == null) return null;
        return PERSONAS.get(profileCode);
    }
}
