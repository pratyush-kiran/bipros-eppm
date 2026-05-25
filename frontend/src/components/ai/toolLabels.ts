// Shared progress-label map for AI tool execution. Add new tool labels here.
//
// Friendly progress labels — keep tool-name plumbing out of the UI while still
// telling the user what's underway. Unknown tools fall back to "Working".

export const TOOL_PROGRESS_LABELS: Record<string, string> = {
  list_projects: "Looking up projects",
  list_activities: "Checking activities",
  list_activity_resources: "Checking activity resources",
  find_resource_deployment: "Checking resource deployment",
  summarize_activity_resources: "Rolling up resource costs by type",
  analyze_schedule: "Reading schedule health",
  analyze_cost: "Reading cost performance",
  analyze_risk: "Reading risk register",
  forecast_completion: "Running forecast",
  portfolio_kpi: "Reading portfolio KPIs",
  read_dpr_summary: "Reading daily progress",
  query_clickhouse: "Querying analytics",
  describe_schema: "Inspecting data shape",
  // Site Manager
  analyze_labour_utilization: "Reading crew utilization",
  analyze_machine_idle_time: "Checking machine idle time",
  analyze_material_wastage: "Reading material wastage",
  check_stockpile_vs_plan: "Comparing stockpile vs plan",
  // Project Engineer
  analyze_productivity_factor: "Reading productivity vs norm",
  analyze_yield_variance: "Reading yield variance",
  analyze_equipment_cycle_time: "Reading equipment cycle times",
  // QC Manager
  analyze_ncr_trends: "Reading NCR trends",
  audit_traceability: "Auditing traceability",
  analyze_quality_data_gaps: "Looking for quality data gaps",
  // Project Manager
  analyze_labour_cost_per_unit: "Reading labour cost per unit",
  analyze_material_burn_rate: "Reading material burn rate",
  analyze_equipment_utilization_cost: "Reading equipment utilization cost",
  // BIM / Data Coordinator
  audit_dpr_data_quality: "Auditing DPR data quality",
  report_data_lag: "Reading data entry lag",
  // HDS standards retrieval (search_hds_standards tool phases)
  "search_hds_standards: planning": "Planning HDS retrieval…",
  "search_hds_standards: retrieving (round 1 of 2)": "Searching HDS standards…",
  "search_hds_standards: retrieving (round 2 of 2)": "Searching HDS standards (deeper)…",
  "search_hds_standards: drafting answer": "Drafting answer…",
  "search_hds_standards: verifying grounding": "Verifying citations…",
};

export function friendlyToolLabel(name: string): string {
  return TOOL_PROGRESS_LABELS[name] ?? "Working";
}
