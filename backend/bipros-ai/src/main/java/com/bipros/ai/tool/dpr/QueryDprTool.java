package com.bipros.ai.tool.dpr;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.Tool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprApprovalStatus;
import com.bipros.project.domain.model.DprSubContractor;
import com.bipros.project.domain.model.WbsNode;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprSubContractorRepository;
import com.bipros.project.domain.repository.WbsNodeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Row-level Daily Progress Report query — what users actually call "DPR".
 * Filters: date range, activity (code or id), supervisor name, WBS code,
 * weather, chainage range. Returns rows + per-date / per-activity rollups.
 *
 * <p>Supersedes the older {@code read_dpr_summary} which only returns
 * date-bucketed aggregates (still kept for back-compat with existing chats).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryDprTool implements Tool {

  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 500;

  private final DailyProgressReportRepository dprRepository;
  private final ActivityRepository activityRepository;
  private final WbsNodeRepository wbsRepository;
  private final DprSubContractorRepository dprSubContractorRepository;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "query_dpr";
  }

  @Override
  public String description() {
    return "Query Daily Progress Report (DPR) entries — supervisor field reports on what was "
        + "physically executed each day (qty, chainage, weather, remarks). Filter by date range, "
        + "activity (code OR id), supervisor name, WBS code, weather, or chainage band. "
        + "Returns matching rows plus rollups by date and by activity. Use this when the user "
        + "asks about DPRs, daily reports, what was reported, or wants to drill into a "
        + "specific day's work. Examples: \"DPRs for last week\", \"What did Foreman John report "
        + "on April 15?\", \"All concrete activity DPRs in March\", \"DPRs at chainage 145+000 "
        + "to 146+000\". Requires a current project in scope. "
        + "NOTE: this tool returns DPR headline rows (qty, chainage, weather, supervisor). "
        + "For per-resource cost questions (\"why does this equipment row cost ₹47.55?\") use "
        + "get_dpr_details, which exposes unit_rate, unit_rate_basis, line_cost, cost_formula, "
        + "and rate-drift flags on every manpower/equipment/material child row. "
        + "LIFECYCLE NOTE: activities in DRAFT edit_status reject DPR submissions, so they "
        + "have zero DPRs by definition — don't waste a call querying DPRs for an activity "
        + "you already know is Draft; check edit_status via list_activities / "
        + "get_activity_full_context first. "
        + "SUB-CONTRACTOR BREAKDOWN: each returned row includes sub_contractor_rows[] "
        + "(sc_code, sc_name, quantity, remarks) for any sub-contractor work on that DPR, "
        + "plus workdone_breakdown{gross_qty, sub_contractor_qty, effective_company_qty} "
        + "showing the canonical split (effective = gross - sub_contractor). The wrapper "
        + "also returns total_sub_contractor_qty and total_effective_company_qty so the LLM "
        + "can answer \"how much workdone\" correctly: ALWAYS report both numbers when "
        + "sub_contractor_qty > 0.";
  }

  @Override
  public JsonNode inputSchema() {
    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = objectMapper.createObjectNode();
    props.set(
        "date_from",
        objectMapper
            .createObjectNode()
            .put("type", "string")
            .put("format", "date")
            .put("description", "ISO date (YYYY-MM-DD). Default: 30 days before date_to."));
    props.set(
        "date_to",
        objectMapper
            .createObjectNode()
            .put("type", "string")
            .put("format", "date")
            .put("description", "ISO date (YYYY-MM-DD). Default: today."));
    props.set(
        "activity_code",
        objectMapper
            .createObjectNode()
            .put("type", "string")
            .put("description", "Activity short code, e.g. ACT-1.3.5(ii). Resolves to an activity name match."));
    props.set(
        "activity_id",
        objectMapper
            .createObjectNode()
            .put("type", "string")
            .put("format", "uuid")
            .put("description", "Activity UUID. Resolved through activities to filter DPRs by activity name."));
    props.set(
        "supervisor_name",
        objectMapper
            .createObjectNode()
            .put("type", "string")
            .put("description", "Filter by supervisor name (case-insensitive substring match)."));
    props.set(
        "wbs_code",
        objectMapper
            .createObjectNode()
            .put("type", "string")
            .put("description", "Filter by WBS node code; matches DPRs with that wbs_node_id."));
    props.set(
        "weather",
        objectMapper
            .createObjectNode()
            .put("type", "string")
            .put("description", "Case-insensitive substring on weather_condition (\"rain\", \"clear\", etc.)."));
    props.set(
        "chainage_from_m",
        objectMapper.createObjectNode().put("type", "integer").put("description", "Minimum chainage in metres."));
    props.set(
        "chainage_to_m",
        objectMapper.createObjectNode().put("type", "integer").put("description", "Maximum chainage in metres."));
    props.set(
        "limit",
        objectMapper
            .createObjectNode()
            .put("type", "integer")
            .put("minimum", 1)
            .put("maximum", MAX_LIMIT)
            .put("default", DEFAULT_LIMIT)
            .put("description", "Max rows to return. Default 100, capped at 500."));
    props.set(
        "include_remarks",
        objectMapper
            .createObjectNode()
            .put("type", "boolean")
            .put("default", true)
            .put("description", "When true, returns the remarks column on each row."));
    schema.set("properties", props);
    return schema;
  }

  @Override
  @Transactional(readOnly = true)
  public ToolResult execute(JsonNode input, AiContext ctx) {
    UUID projectId = ctx.projectId();
    if (projectId == null) {
      return ToolResult.error(
          "query_dpr needs a project in scope. Pick a specific project, then re-ask.");
    }
    if (!"ADMIN".equals(ctx.role())
        && (ctx.scopedProjectIds() == null || !ctx.scopedProjectIds().contains(projectId))) {
      throw new AccessDeniedException("project not in user scope");
    }

    LocalDate dateTo = parseDate(input.path("date_to").asText(null), LocalDate.now());
    LocalDate dateFrom = parseDate(input.path("date_from").asText(null), dateTo.minusDays(30));
    if (dateFrom.isAfter(dateTo)) {
      LocalDate tmp = dateFrom;
      dateFrom = dateTo;
      dateTo = tmp;
    }
    int limit = Math.max(1, Math.min(MAX_LIMIT, input.path("limit").asInt(DEFAULT_LIMIT)));
    boolean includeRemarks = input.path("include_remarks").asBoolean(true);

    String supervisorFilter = orNull(input.path("supervisor_name").asText(null));
    String weatherFilter = orNull(input.path("weather").asText(null));
    Long chainageFrom =
        input.path("chainage_from_m").isMissingNode() || input.path("chainage_from_m").isNull()
            ? null
            : input.path("chainage_from_m").asLong();
    Long chainageTo =
        input.path("chainage_to_m").isMissingNode() || input.path("chainage_to_m").isNull()
            ? null
            : input.path("chainage_to_m").asLong();

    Activity matchedActivity = resolveActivity(input, projectId);
    String activityNameFilter = matchedActivity != null ? matchedActivity.getName() : null;

    UUID wbsId = null;
    String wbsCode = orNull(input.path("wbs_code").asText(null));
    if (wbsCode != null) {
      Optional<WbsNode> w = wbsRepository.findByProjectIdAndCode(projectId, wbsCode);
      if (w.isPresent()) wbsId = w.get().getId();
    }

    List<DailyProgressReport> baseRows =
        dprRepository.findByProjectIdAndApprovalStatusAndReportDateBetweenOrderByReportDateAscIdAsc(
            projectId, DprApprovalStatus.APPROVED, dateFrom, dateTo);

    List<DailyProgressReport> filtered = new ArrayList<>();
    for (DailyProgressReport d : baseRows) {
      if (activityNameFilter != null
          && (d.getActivityName() == null
              || !d.getActivityName().equalsIgnoreCase(activityNameFilter))) continue;
      if (supervisorFilter != null
          && (d.getSupervisorName() == null
              || !d.getSupervisorName().toLowerCase().contains(supervisorFilter.toLowerCase()))) continue;
      if (weatherFilter != null
          && (d.getWeatherCondition() == null
              || !d.getWeatherCondition().toLowerCase().contains(weatherFilter.toLowerCase()))) continue;
      if (wbsId != null && (d.getWbsNodeId() == null || !d.getWbsNodeId().equals(wbsId))) continue;
      if (chainageFrom != null && (d.getChainageFromM() == null || d.getChainageFromM() < chainageFrom)) continue;
      if (chainageTo != null && (d.getChainageToM() == null || d.getChainageToM() > chainageTo)) continue;
      filtered.add(d);
    }

    int totalMatched = filtered.size();
    List<DailyProgressReport> capped =
        filtered.size() > limit ? filtered.subList(0, limit) : filtered;

    Map<String, ActivityRollup> activityRollups = new HashMap<>();
    Map<LocalDate, BigDecimal> dateRollups = new LinkedHashMap<>();
    BigDecimal totalQty = BigDecimal.ZERO;
    int distinctSupervisors = 0;
    java.util.Set<String> supervisors = new java.util.HashSet<>();

    for (DailyProgressReport d : filtered) {
      if (d.getQtyExecuted() != null) totalQty = totalQty.add(d.getQtyExecuted());
      if (d.getSupervisorName() != null) supervisors.add(d.getSupervisorName());
      if (d.getActivityName() != null) {
        activityRollups
            .computeIfAbsent(d.getActivityName(), k -> new ActivityRollup(k))
            .add(d);
      }
      dateRollups.merge(
          d.getReportDate(),
          d.getQtyExecuted() != null ? d.getQtyExecuted() : BigDecimal.ZERO,
          BigDecimal::add);
    }
    distinctSupervisors = supervisors.size();

    // Batch-load sub-contractor rows for the capped DPRs and group by dprId so each row
    // can carry its own sub_contractor_rows[] + workdone_breakdown. Empty list when none.
    Map<UUID, List<DprSubContractor>> scByDpr = new HashMap<>();
    BigDecimal totalScQty = BigDecimal.ZERO;
    if (!capped.isEmpty()) {
      List<UUID> dprIds = new ArrayList<>(capped.size());
      for (DailyProgressReport d : capped) if (d.getId() != null) dprIds.add(d.getId());
      if (!dprIds.isEmpty()) {
        List<DprSubContractor> scRows = dprSubContractorRepository.findByDprIdIn(dprIds);
        for (DprSubContractor sc : scRows) {
          if (sc.getDprId() == null) continue;
          scByDpr.computeIfAbsent(sc.getDprId(), k -> new ArrayList<>()).add(sc);
          if (sc.getQuantity() != null) totalScQty = totalScQty.add(sc.getQuantity());
        }
      }
    }

    ArrayNode rows = objectMapper.createArrayNode();
    for (DailyProgressReport d : capped) {
      ObjectNode row = objectMapper.createObjectNode();
      row.put("dpr_id", d.getId() == null ? null : d.getId().toString());
      row.put("report_date", d.getReportDate() != null ? d.getReportDate().toString() : null);
      row.put("supervisor_name", d.getSupervisorName());
      row.put("activity_name", d.getActivityName());
      row.put("wbs_node_id", d.getWbsNodeId() == null ? null : d.getWbsNodeId().toString());
      row.put("boq_item_no", d.getBoqItemNo());
      row.put("unit", d.getUnit());
      row.put("qty_executed", d.getQtyExecuted() == null ? null : d.getQtyExecuted().doubleValue());
      // cumulative_qty dropped — see GetDprDetailsTool for rationale.
      row.put("chainage_from_m", d.getChainageFromM());
      row.put("chainage_to_m", d.getChainageToM());
      row.put("weather_condition", d.getWeatherCondition());
      if (includeRemarks) row.put("remarks", d.getRemarks());

      // Sub-contractor rows + workdone_breakdown.
      List<DprSubContractor> scRows = scByDpr.getOrDefault(d.getId(), List.of());
      ArrayNode scArr = objectMapper.createArrayNode();
      BigDecimal scQtyForDpr = BigDecimal.ZERO;
      for (DprSubContractor sc : scRows) {
        ObjectNode scNode = objectMapper.createObjectNode();
        if (sc.getSubContractorCode() != null) scNode.put("sc_code", sc.getSubContractorCode());
        if (sc.getSubContractorName() != null) scNode.put("sc_name", sc.getSubContractorName());
        if (sc.getSubContractorMasterId() != null)
          scNode.put("sc_master_id", sc.getSubContractorMasterId().toString());
        if (sc.getActivitySubContractorAssignmentId() != null)
          scNode.put("assignment_id", sc.getActivitySubContractorAssignmentId().toString());
        if (sc.getQuantity() != null) {
          scNode.put("quantity", sc.getQuantity().doubleValue());
          scQtyForDpr = scQtyForDpr.add(sc.getQuantity());
        }
        if (sc.getRemarks() != null) scNode.put("remarks", sc.getRemarks());
        scArr.add(scNode);
      }
      row.set("sub_contractor_rows", scArr);

      ObjectNode breakdown = objectMapper.createObjectNode();
      BigDecimal gross = d.getQtyExecuted() == null ? BigDecimal.ZERO : d.getQtyExecuted();
      BigDecimal effective = gross.subtract(scQtyForDpr);
      if (effective.signum() < 0) effective = BigDecimal.ZERO;
      breakdown.put("gross_qty", gross.doubleValue());
      breakdown.put("sub_contractor_qty", scQtyForDpr.doubleValue());
      breakdown.put("effective_company_qty", effective.doubleValue());
      row.set("workdone_breakdown", breakdown);

      rows.add(row);
    }

    ArrayNode activityRows = objectMapper.createArrayNode();
    activityRollups.values().stream()
        .sorted(Comparator.comparing(ActivityRollup::totalQtyOrZero).reversed())
        .forEach(
            ar -> {
              ObjectNode n = objectMapper.createObjectNode();
              n.put("activity_name", ar.activityName);
              n.put("dpr_count", ar.count);
              n.put("total_qty", ar.totalQty == null ? null : ar.totalQty.doubleValue());
              n.put("first_date", ar.firstDate == null ? null : ar.firstDate.toString());
              n.put("last_date", ar.lastDate == null ? null : ar.lastDate.toString());
              activityRows.add(n);
            });

    ArrayNode dateRows = objectMapper.createArrayNode();
    for (Map.Entry<LocalDate, BigDecimal> e : dateRollups.entrySet()) {
      ObjectNode n = objectMapper.createObjectNode();
      n.put("date", e.getKey().toString());
      n.put("qty", e.getValue() == null ? null : e.getValue().doubleValue());
      dateRows.add(n);
    }

    ObjectNode wrapper = objectMapper.createObjectNode();
    wrapper.set("rows", rows);
    wrapper.set("by_activity", activityRows);
    wrapper.set("by_date", dateRows);
    wrapper.put("date_from", dateFrom.toString());
    wrapper.put("date_to", dateTo.toString());
    wrapper.put("matched", totalMatched);
    wrapper.put("returned", capped.size());
    wrapper.put("distinct_supervisors", distinctSupervisors);
    wrapper.put("total_qty_executed", totalQty.doubleValue());
    wrapper.put("total_sub_contractor_qty", totalScQty.doubleValue());
    BigDecimal totalEffective = totalQty.subtract(totalScQty);
    if (totalEffective.signum() < 0) totalEffective = BigDecimal.ZERO;
    wrapper.put("total_effective_company_qty", totalEffective.doubleValue());
    if (matchedActivity != null) {
      wrapper.put("filtered_activity_code", matchedActivity.getCode());
      wrapper.put("filtered_activity_name", matchedActivity.getName());
    }

    Map<String, List<UUID>> links = new HashMap<>();
    if (matchedActivity != null) links.put("activity", List.of(matchedActivity.getId()));
    if (wbsId != null) links.put("wbs", List.of(wbsId));
    ToolResult.attachLinks(wrapper, links);

    String summary =
        String.format(
            "%d DPR%s%s between %s and %s (%d activity%s, %d supervisor%s, total qty %s)",
            totalMatched,
            totalMatched == 1 ? "" : "s",
            matchedActivity != null ? " on " + matchedActivity.getCode() : "",
            dateFrom,
            dateTo,
            activityRollups.size(),
            activityRollups.size() == 1 ? "" : "ies",
            distinctSupervisors,
            distinctSupervisors == 1 ? "" : "s",
            totalQty);
    return ToolResult.ok(summary, wrapper);
  }

  private Activity resolveActivity(JsonNode input, UUID projectId) {
    String idStr = orNull(input.path("activity_id").asText(null));
    if (idStr != null) {
      try {
        UUID id = UUID.fromString(idStr);
        return activityRepository.findById(id).filter(a -> projectId.equals(a.getProjectId())).orElse(null);
      } catch (IllegalArgumentException ignored) {
        // fall through
      }
    }
    String code = orNull(input.path("activity_code").asText(null));
    if (code != null) {
      return activityRepository.findByProjectIdAndCode(projectId, code).orElse(null);
    }
    return null;
  }

  private static LocalDate parseDate(String raw, LocalDate fallback) {
    if (raw == null || raw.isBlank()) return fallback;
    try {
      return LocalDate.parse(raw.trim());
    } catch (Exception e) {
      return fallback;
    }
  }

  private static String orNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  private static class ActivityRollup {
    final String activityName;
    int count = 0;
    BigDecimal totalQty = BigDecimal.ZERO;
    LocalDate firstDate;
    LocalDate lastDate;

    ActivityRollup(String activityName) {
      this.activityName = activityName;
    }

    void add(DailyProgressReport d) {
      count++;
      if (d.getQtyExecuted() != null) totalQty = totalQty.add(d.getQtyExecuted());
      if (firstDate == null || (d.getReportDate() != null && d.getReportDate().isBefore(firstDate))) {
        firstDate = d.getReportDate();
      }
      if (lastDate == null || (d.getReportDate() != null && d.getReportDate().isAfter(lastDate))) {
        lastDate = d.getReportDate();
      }
    }

    double totalQtyOrZero() {
      return totalQty == null ? 0.0 : totalQty.doubleValue();
    }
  }

  @Override
  public java.util.Set<String> allowedRoles() {
    return java.util.Set.of(
            "PROJECT_MANAGER", "PORTFOLIO_MANAGER",
            "SITE_MANAGER", "PROJECT_ENGINEER", "QC_MANAGER", "QA_QC_ENGINEER",
            "BIM_DATA_COORDINATOR",
            "SITE_ENGINEER", "RESOURCE_MANAGER", "SCHEDULER",
            "EXECUTIVE_VIEWER"
    );
  }
}
