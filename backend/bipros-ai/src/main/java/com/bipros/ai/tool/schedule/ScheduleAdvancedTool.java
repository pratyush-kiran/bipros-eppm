package com.bipros.ai.tool.schedule;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.Tool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.risk.domain.model.MonteCarloActivityStat;
import com.bipros.risk.domain.model.MonteCarloMilestoneStat;
import com.bipros.risk.domain.model.MonteCarloSimulation;
import com.bipros.risk.domain.repository.MonteCarloActivityStatRepository;
import com.bipros.risk.domain.repository.MonteCarloMilestoneStatRepository;
import com.bipros.risk.domain.repository.MonteCarloSimulationRepository;
import com.bipros.scheduling.domain.model.CompressionAnalysis;
import com.bipros.scheduling.domain.model.ScheduleActivityResult;
import com.bipros.scheduling.domain.model.ScheduleResult;
import com.bipros.scheduling.domain.model.ScheduleScenario;
import com.bipros.scheduling.domain.repository.CompressionAnalysisRepository;
import com.bipros.scheduling.domain.repository.ScheduleActivityResultRepository;
import com.bipros.scheduling.domain.repository.ScheduleResultRepository;
import com.bipros.scheduling.domain.repository.ScheduleScenarioRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Advanced schedule analytics. Operations: critical_path, scenarios, compression, monte_carlo.
 * Reads the latest ScheduleResult for criticality and joins to activities for human-readable rows.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleAdvancedTool implements Tool {

  private final ScheduleResultRepository scheduleResultRepository;
  private final ScheduleActivityResultRepository activityResultRepository;
  private final ScheduleScenarioRepository scenarioRepository;
  private final CompressionAnalysisRepository compressionRepository;
  private final MonteCarloSimulationRepository simulationRepository;
  private final MonteCarloActivityStatRepository mcActivityRepository;
  private final MonteCarloMilestoneStatRepository mcMilestoneRepository;
  private final ActivityRepository activityRepository;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "schedule_advanced";
  }

  @Override
  public String description() {
    return "Use this for deep schedule analytics that go beyond simple activity listing. Operations "
        + "via op param: 'critical_path' (latest ScheduleResult; lists activities with isCritical=true "
        + "from the schedule_activity_results snapshot, joined to activity codes/names), "
        + "'negative_float' (activities with total_float < 0 in the latest schedule run — USE THIS "
        + "for any 'which activities have negative float', 'activities behind on float', or "
        + "'is this activity in slip' question; do NOT filter list_activities for this because "
        + "activity.total_float is the live OLTP column and can lag the latest scheduler run, so "
        + "they routinely disagree by design), 'scenarios' "
        + "(named what-if schedule scenarios with duration / cost / status), 'compression' "
        + "(crashing/fast-track analyses — original vs compressed duration, additional cost, "
        + "recommendations), 'monte_carlo' (latest simulation: P10/P50/P80/P90 duration & cost; "
        + "milestone P50/P80/P90 dates; top activities by criticality index). Examples: 'what's on "
        + "the critical path', 'which activities have negative float', 'show me the schedule "
        + "scenarios', 'what does the Monte Carlo say about the finish date'. Project-scoped.";
  }

  @Override
  public JsonNode inputSchema() {
    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = objectMapper.createObjectNode();
    ArrayNode opEnum = objectMapper.createArrayNode();
    opEnum.add("critical_path");
    opEnum.add("negative_float");
    opEnum.add("scenarios");
    opEnum.add("compression");
    opEnum.add("monte_carlo");
    ObjectNode opNode = objectMapper.createObjectNode();
    opNode.put("type", "string");
    opNode.set("enum", opEnum);
    props.set("op", opNode);
    props.set("limit", objectMapper.createObjectNode().put("type", "integer").put("minimum", 1).put("maximum", 200).put("default", 50)
        .put("description", "Cap on row count where applicable (critical_path activities, top monte-carlo activities)."));
    schema.set("properties", props);
    ArrayNode required = objectMapper.createArrayNode();
    required.add("op");
    schema.set("required", required);
    return schema;
  }

  @Override
  @Transactional(readOnly = true)
  public ToolResult execute(JsonNode input, AiContext ctx) {
    UUID projectId = ctx.projectId();
    if (projectId == null) return ToolResult.error("schedule_advanced needs a project in scope.");
    if (!"ADMIN".equals(ctx.role())
        && (ctx.scopedProjectIds() == null || !ctx.scopedProjectIds().contains(projectId))) {
      throw new AccessDeniedException("project not in user scope");
    }
    String op = orNull(input.path("op").asText(null));
    if (op == null) return ToolResult.error("op is required");
    int limit = Math.max(1, Math.min(200, input.path("limit").asInt(50)));

    return switch (op) {
      case "critical_path" -> doCriticalPath(projectId, limit);
      case "scenarios" -> doScenarios(projectId);
      case "compression" -> doCompression(projectId);
      case "monte_carlo" -> doMonteCarlo(projectId, limit);
      case "negative_float" -> doNegativeFloat(projectId, limit);
      default -> ToolResult.error("Unknown op: " + op);
    };
  }

  /**
   * Negative-float activities from the latest scheduler run. This is the canonical
   * source — activity.activities.total_float is the live OLTP field which may lag
   * the latest scheduler run until reconciliation. The schedule_activity_results
   * row is what the scheduler actually computed last time it ran, so this is what
   * the user means by "activities with negative float".
   */
  private ToolResult doNegativeFloat(UUID projectId, int limit) {
    Optional<ScheduleResult> latest = scheduleResultRepository.findTopByProjectIdOrderByCalculatedAtDesc(projectId);
    if (latest.isEmpty()) {
      return ToolResult.error("No ScheduleResult exists for this project — run a schedule first.");
    }
    ScheduleResult sr = latest.get();
    List<ScheduleActivityResult> all = activityResultRepository.findByScheduleResultId(sr.getId());
    List<ScheduleActivityResult> negative = new ArrayList<>();
    for (ScheduleActivityResult r : all) {
      if (r.getTotalFloat() != null && r.getTotalFloat() < 0) negative.add(r);
    }
    negative.sort((a, b) -> Double.compare(
            a.getTotalFloat() == null ? 0.0 : a.getTotalFloat(),
            b.getTotalFloat() == null ? 0.0 : b.getTotalFloat()));

    List<UUID> ids = new ArrayList<>();
    for (ScheduleActivityResult r : negative) ids.add(r.getActivityId());
    Map<UUID, Activity> actBy = new HashMap<>();
    if (!ids.isEmpty()) activityRepository.findAllById(ids).forEach(a -> actBy.put(a.getId(), a));

    ArrayNode rows = objectMapper.createArrayNode();
    int n = 0;
    for (ScheduleActivityResult r : negative) {
      if (n++ >= limit) break;
      Activity a = actBy.get(r.getActivityId());
      ObjectNode o = objectMapper.createObjectNode();
      o.put("activity_id", r.getActivityId().toString());
      o.put("activity_code", a == null ? null : a.getCode());
      o.put("activity_name", a == null ? null : a.getName());
      o.put("total_float", r.getTotalFloat());
      o.put("free_float", r.getFreeFloat());
      o.put("is_critical", r.getIsCritical());
      o.put("late_finish", r.getLateFinish() == null ? null : r.getLateFinish().toString());
      o.put("early_finish", r.getEarlyFinish() == null ? null : r.getEarlyFinish().toString());
      o.put("remaining_duration", r.getRemainingDuration());
      rows.add(o);
    }
    ObjectNode w = objectMapper.createObjectNode();
    w.set("rows", rows);
    w.put("schedule_result_id", sr.getId().toString());
    w.put("data_date", sr.getDataDate() == null ? null : sr.getDataDate().toString());
    w.put("source", "scheduling.schedule_activity_results (latest schedule run)");
    w.put("note", "This is the scheduler-authoritative source for negative float. "
            + "activity.activities.total_float is the live OLTP column and may show 0 "
            + "until the next scheduler-to-OLTP reconciliation, so it can disagree "
            + "with this row's total_float by design.");
    w.put("total_negative", negative.size());
    w.put("returned", rows.size());
    if (!ids.isEmpty()) ToolResult.attachLinks(w, Map.of("activity", ids));
    return ToolResult.ok(
            negative.size() + " activities with negative float in the latest schedule run"
                    + (negative.isEmpty() ? "." : " (worst: " + negative.get(0).getTotalFloat() + " days)."),
            w);
  }

  private ToolResult doCriticalPath(UUID projectId, int limit) {
    Optional<ScheduleResult> latest = scheduleResultRepository.findTopByProjectIdOrderByCalculatedAtDesc(projectId);
    if (latest.isEmpty()) return ToolResult.error("No ScheduleResult exists for this project — run a schedule first.");
    ScheduleResult sr = latest.get();
    List<ScheduleActivityResult> critical = activityResultRepository.findByScheduleResultIdAndIsCritical(sr.getId(), true);
    List<UUID> ids = new ArrayList<>();
    for (ScheduleActivityResult r : critical) ids.add(r.getActivityId());
    Map<UUID, Activity> actBy = new HashMap<>();
    if (!ids.isEmpty()) activityRepository.findAllById(ids).forEach(a -> actBy.put(a.getId(), a));

    ArrayNode rows = objectMapper.createArrayNode();
    int n = 0;
    for (ScheduleActivityResult r : critical) {
      if (n++ >= limit) break;
      Activity a = actBy.get(r.getActivityId());
      ObjectNode o = objectMapper.createObjectNode();
      o.put("activity_id", r.getActivityId().toString());
      o.put("activity_code", a == null ? null : a.getCode());
      o.put("activity_name", a == null ? null : a.getName());
      o.put("early_start", r.getEarlyStart() == null ? null : r.getEarlyStart().toString());
      o.put("early_finish", r.getEarlyFinish() == null ? null : r.getEarlyFinish().toString());
      o.put("late_start", r.getLateStart() == null ? null : r.getLateStart().toString());
      o.put("late_finish", r.getLateFinish() == null ? null : r.getLateFinish().toString());
      o.put("total_float", r.getTotalFloat());
      o.put("free_float", r.getFreeFloat());
      o.put("remaining_duration", r.getRemainingDuration());
      rows.add(o);
    }
    ObjectNode w = objectMapper.createObjectNode();
    w.set("rows", rows);
    w.put("schedule_result_id", sr.getId().toString());
    w.put("data_date", sr.getDataDate() == null ? null : sr.getDataDate().toString());
    w.put("project_start", sr.getProjectStartDate() == null ? null : sr.getProjectStartDate().toString());
    w.put("project_finish", sr.getProjectFinishDate() == null ? null : sr.getProjectFinishDate().toString());
    w.put("critical_path_length", sr.getCriticalPathLength());
    w.put("total_activities", sr.getTotalActivities());
    w.put("critical_activities", sr.getCriticalActivities());
    w.put("returned", rows.size());
    if (!ids.isEmpty()) ToolResult.attachLinks(w, Map.of("activity", ids));
    return ToolResult.ok(critical.size() + " critical activities (showing " + rows.size() + ")", w);
  }

  private ToolResult doScenarios(UUID projectId) {
    List<ScheduleScenario> scenarios = scenarioRepository.findByProjectId(projectId);
    ArrayNode rows = objectMapper.createArrayNode();
    for (ScheduleScenario s : scenarios) {
      ObjectNode o = objectMapper.createObjectNode();
      o.put("scenario_id", s.getId().toString());
      o.put("name", s.getScenarioName());
      o.put("description", s.getDescription());
      o.put("type", s.getScenarioType() == null ? null : s.getScenarioType().name());
      o.put("status", s.getStatus() == null ? null : s.getStatus().name());
      o.put("project_duration", s.getProjectDuration());
      o.put("critical_path_length", s.getCriticalPathLength());
      o.put("total_cost", s.getTotalCost() == null ? null : s.getTotalCost().doubleValue());
      o.put("base_schedule_result_id", s.getBaseScheduleResultId() == null ? null : s.getBaseScheduleResultId().toString());
      o.put("created_at", s.getCreatedAt() == null ? null : s.getCreatedAt().toString());
      rows.add(o);
    }
    ObjectNode w = objectMapper.createObjectNode();
    w.set("rows", rows);
    w.put("count", scenarios.size());
    return ToolResult.ok(scenarios.size() + " schedule scenario" + (scenarios.size() == 1 ? "" : "s"), w);
  }

  private ToolResult doCompression(UUID projectId) {
    List<CompressionAnalysis> rows = compressionRepository.findByProjectId(projectId);
    ArrayNode out = objectMapper.createArrayNode();
    for (CompressionAnalysis c : rows) {
      ObjectNode o = objectMapper.createObjectNode();
      o.put("compression_id", c.getId().toString());
      o.put("scenario_id", c.getScenarioId() == null ? null : c.getScenarioId().toString());
      o.put("analysis_type", c.getAnalysisType() == null ? null : c.getAnalysisType().name());
      o.put("original_duration", c.getOriginalDuration());
      o.put("compressed_duration", c.getCompressedDuration());
      o.put("duration_saved", c.getDurationSaved());
      o.put("additional_cost", c.getAdditionalCost() == null ? null : c.getAdditionalCost().doubleValue());
      o.put("recommendations", c.getRecommendations());
      out.add(o);
    }
    ObjectNode w = objectMapper.createObjectNode();
    w.set("rows", out);
    w.put("count", rows.size());
    return ToolResult.ok(rows.size() + " compression analysis row" + (rows.size() == 1 ? "" : "s"), w);
  }

  private ToolResult doMonteCarlo(UUID projectId, int limit) {
    Optional<MonteCarloSimulation> latest = simulationRepository.findLatestByProjectId(projectId);
    if (latest.isEmpty()) return ToolResult.error("No Monte Carlo simulation has been run for this project.");
    MonteCarloSimulation sim = latest.get();
    List<MonteCarloActivityStat> acts = mcActivityRepository.findBySimulationIdOrderByCriticalityIndexDesc(sim.getId());
    List<MonteCarloMilestoneStat> mils = mcMilestoneRepository.findBySimulationId(sim.getId());

    ObjectNode summary = objectMapper.createObjectNode();
    summary.put("simulation_id", sim.getId().toString());
    summary.put("simulation_name", sim.getSimulationName());
    summary.put("status", sim.getStatus() == null ? null : sim.getStatus().name());
    summary.put("iterations", sim.getIterations());
    summary.put("iterations_completed", sim.getIterationsCompleted());
    summary.put("data_date", sim.getDataDate() == null ? null : sim.getDataDate().toString());
    summary.put("baseline_duration", sim.getBaselineDuration());
    summary.put("baseline_cost", sim.getBaselineCost() == null ? null : sim.getBaselineCost().doubleValue());
    summary.put("p10_duration", sim.getP10Duration());
    summary.put("p50_duration", sim.getConfidenceP50Duration());
    summary.put("p80_duration", sim.getConfidenceP80Duration());
    summary.put("p90_duration", sim.getP90Duration());
    summary.put("mean_duration", sim.getMeanDuration());
    summary.put("p10_cost", sim.getP10Cost() == null ? null : sim.getP10Cost().doubleValue());
    summary.put("p50_cost", sim.getConfidenceP50Cost() == null ? null : sim.getConfidenceP50Cost().doubleValue());
    summary.put("p80_cost", sim.getConfidenceP80Cost() == null ? null : sim.getConfidenceP80Cost().doubleValue());
    summary.put("p90_cost", sim.getP90Cost() == null ? null : sim.getP90Cost().doubleValue());
    summary.put("mean_cost", sim.getMeanCost() == null ? null : sim.getMeanCost().doubleValue());

    ArrayNode topActs = objectMapper.createArrayNode();
    int n = 0;
    for (MonteCarloActivityStat s : acts) {
      if (n++ >= limit) break;
      ObjectNode o = objectMapper.createObjectNode();
      o.put("activity_id", s.getActivityId() == null ? null : s.getActivityId().toString());
      o.put("activity_code", s.getActivityCode());
      o.put("activity_name", s.getActivityName());
      o.put("criticality_index", s.getCriticalityIndex());
      o.put("duration_mean", s.getDurationMean());
      o.put("duration_stddev", s.getDurationStddev());
      o.put("duration_p10", s.getDurationP10());
      o.put("duration_p90", s.getDurationP90());
      o.put("duration_sensitivity", s.getDurationSensitivity());
      o.put("cost_sensitivity", s.getCostSensitivity());
      o.put("cruciality", s.getCruciality());
      topActs.add(o);
    }

    ArrayNode milestones = objectMapper.createArrayNode();
    for (MonteCarloMilestoneStat m : mils) {
      ObjectNode o = objectMapper.createObjectNode();
      o.put("activity_id", m.getActivityId() == null ? null : m.getActivityId().toString());
      o.put("activity_code", m.getActivityCode());
      o.put("activity_name", m.getActivityName());
      o.put("planned_finish", m.getPlannedFinishDate() == null ? null : m.getPlannedFinishDate().toString());
      o.put("p50_finish", m.getP50FinishDate() == null ? null : m.getP50FinishDate().toString());
      o.put("p80_finish", m.getP80FinishDate() == null ? null : m.getP80FinishDate().toString());
      o.put("p90_finish", m.getP90FinishDate() == null ? null : m.getP90FinishDate().toString());
      milestones.add(o);
    }

    ObjectNode w = objectMapper.createObjectNode();
    w.set("simulation", summary);
    w.set("top_activities_by_criticality", topActs);
    w.set("milestones", milestones);
    w.put("activity_count", acts.size());
    w.put("milestone_count", mils.size());
    return ToolResult.ok("MC " + sim.getSimulationName() + " · P50 dur=" + sim.getConfidenceP50Duration() + ", P80 dur=" + sim.getConfidenceP80Duration(), w);
  }

  private static String orNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
