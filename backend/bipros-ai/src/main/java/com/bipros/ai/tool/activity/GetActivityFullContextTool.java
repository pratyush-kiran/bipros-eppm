package com.bipros.ai.tool.activity;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.ai.context.AiContext;
import com.bipros.ai.resolver.EffectiveRate;
import com.bipros.ai.resolver.EffectiveRateResolver;
import com.bipros.ai.tool.Tool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.cost.domain.entity.ActivityExpense;
import com.bipros.cost.domain.repository.ActivityExpenseRepository;
import com.bipros.evm.domain.entity.EvmCalculation;
import com.bipros.evm.domain.repository.EvmCalculationRepository;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.WbsNode;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.WbsNodeRepository;
import com.bipros.resource.domain.model.ActivitySubContractorAssignment;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceAssignment;
import com.bipros.resource.domain.repository.ActivitySubContractorAssignmentRepository;
import com.bipros.resource.domain.repository.ResourceAssignmentRepository;
import com.bipros.resource.domain.repository.ResourceRepository;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One-call cross-entity drill-down for a single activity. Returns:
 * <ul>
 *   <li>The activity itself (status, dates, percent complete, critical flag, float)</li>
 *   <li>WBS path from root to the activity's wbs_node (so the LLM can name the parent chain)</li>
 *   <li>Resource assignments (count by category, planned vs actual cost rollup)</li>
 *   <li>Activity expense rows (budgeted, actual, ETC, percent complete by cost account)</li>
 *   <li>Latest EVM calculation (BAC, PV, EV, AC, CV, SV, CPI, SPI, EAC, ETC, VAC)</li>
 *   <li>Recent DPRs (configurable date window)</li>
 * </ul>
 *
 * <p>Designed so the LLM can answer "tell me everything about activity X"
 * questions in a single tool call instead of orchestrating a sequence.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetActivityFullContextTool implements Tool {

  private final ActivityRepository activityRepository;
  private final WbsNodeRepository wbsRepository;
  private final ResourceAssignmentRepository assignmentRepository;
  private final ResourceRepository resourceRepository;
  private final ActivitySubContractorAssignmentRepository subContractorAssignmentRepository;
  private final ActivityExpenseRepository expenseRepository;
  private final EvmCalculationRepository evmRepository;
  private final DailyProgressReportRepository dprRepository;
  private final EffectiveRateResolver rateResolver;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "get_activity_full_context";
  }

  @Override
  public String description() {
    return "Everything about one activity in a single call: activity record, WBS path "
        + "(root → leaf), resource assignment summary, sub-contractor assignments block "
        + "(per-SC + work-type planned vs actual qty/cost, qty_completion_pct), activity "
        + "expense (budgeted vs actual vs at-completion cost), latest EVM (CV, SV, CPI, SPI, "
        + "EAC, ETC, VAC), and recent DPRs. Identify by activity_id (UUID) OR activity_code. "
        + "Tunable: dpr_days (default 7), include_evm (default true), include_cost_variance "
        + "(default true). Use this for: \"Tell me about activity X\", \"What's the cost "
        + "variance and progress on this activity?\", \"Is a sub-contractor on this "
        + "activity?\", \"Drill into the foundation activity\". For richer sub-contractor "
        + "analysis across the project use get_subcontractor_kpis. Project-scoped.";
  }

  @Override
  public JsonNode inputSchema() {
    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = objectMapper.createObjectNode();
    props.set(
        "activity_id",
        objectMapper.createObjectNode().put("type", "string").put("format", "uuid"));
    props.set(
        "activity_code",
        objectMapper.createObjectNode().put("type", "string").put("description", "Activity short code, e.g. ACT-1.3.5(ii)."));
    props.set(
        "dpr_days",
        objectMapper.createObjectNode().put("type", "integer").put("minimum", 0).put("maximum", 90).put("default", 7).put("description", "Window for recent DPRs (days back from today)."));
    props.set(
        "include_evm",
        objectMapper.createObjectNode().put("type", "boolean").put("default", true));
    props.set(
        "include_cost_variance",
        objectMapper.createObjectNode().put("type", "boolean").put("default", true));
    schema.set("properties", props);
    return schema;
  }

  @Override
  @Transactional(readOnly = true)
  public ToolResult execute(JsonNode input, AiContext ctx) {
    UUID projectId = ctx.projectId();
    if (projectId == null) {
      return ToolResult.error("get_activity_full_context needs a project in scope.");
    }
    if (!"ADMIN".equals(ctx.role())
        && (ctx.scopedProjectIds() == null || !ctx.scopedProjectIds().contains(projectId))) {
      throw new AccessDeniedException("project not in user scope");
    }

    Activity activity = resolveActivity(input, projectId);
    if (activity == null) {
      return ToolResult.error(
          "Could not resolve the activity. Provide activity_id or activity_code, or call resolve_entity first.");
    }
    int dprDays = Math.max(0, Math.min(90, input.path("dpr_days").asInt(7)));
    boolean includeEvm = input.path("include_evm").asBoolean(true);
    boolean includeCost = input.path("include_cost_variance").asBoolean(true);

    ObjectNode wrapper = objectMapper.createObjectNode();

    ObjectNode act = objectMapper.createObjectNode();
    act.put("activity_id", activity.getId().toString());
    act.put("activity_code", activity.getCode());
    act.put("activity_name", activity.getName());
    act.put("status", activity.getStatus() == null ? null : activity.getStatus().name());
    act.put("edit_status", activity.getEditStatus() == null ? null : activity.getEditStatus().name());
    act.put("activity_type", activity.getActivityType() == null ? null : activity.getActivityType().name());
    act.put("planned_start", activity.getPlannedStartDate() == null ? null : activity.getPlannedStartDate().toString());
    act.put("planned_finish", activity.getPlannedFinishDate() == null ? null : activity.getPlannedFinishDate().toString());
    act.put("actual_start", activity.getActualStartDate() == null ? null : activity.getActualStartDate().toString());
    act.put("actual_finish", activity.getActualFinishDate() == null ? null : activity.getActualFinishDate().toString());
    act.put("original_duration", activity.getOriginalDuration());
    act.put("remaining_duration", activity.getRemainingDuration());
    act.put("percent_complete", activity.getPercentComplete());
    act.put("is_critical", activity.getIsCritical());
    act.put("total_float", activity.getTotalFloat());
    act.put("free_float", activity.getFreeFloat());
    act.put("chainage_from_m", activity.getChainageFromM());
    act.put("chainage_to_m", activity.getChainageToM());
    act.put("work_activity_id", activity.getWorkActivityId() == null ? null : activity.getWorkActivityId().toString());
    act.put("cost_account_id", activity.getCostAccountId() == null ? null : activity.getCostAccountId().toString());
    wrapper.set("activity", act);

    ArrayNode wbsPath = objectMapper.createArrayNode();
    UUID wbsId = activity.getWbsNodeId();
    int safety = 50;
    while (wbsId != null && safety-- > 0) {
      Optional<WbsNode> opt = wbsRepository.findById(wbsId);
      if (opt.isEmpty()) break;
      WbsNode w = opt.get();
      ObjectNode n = objectMapper.createObjectNode();
      n.put("wbs_node_id", w.getId().toString());
      n.put("code", w.getCode());
      n.put("name", w.getName());
      wbsPath.insert(0, n);
      wbsId = w.getParentId();
    }
    wrapper.set("wbs_path", wbsPath);

    List<ResourceAssignment> assignments = assignmentRepository.findByActivityId(activity.getId());
    Set<UUID> resourceIds = new HashSet<>();
    for (ResourceAssignment a : assignments) if (a.getResourceId() != null) resourceIds.add(a.getResourceId());
    Map<UUID, Resource> resourceById = new HashMap<>();
    if (!resourceIds.isEmpty()) {
      resourceRepository.findAllById(resourceIds).forEach(r -> resourceById.put(r.getId(), r));
    }
    Map<String, AssignmentRollup> byCategory = new HashMap<>();
    BigDecimal plannedCostTotal = BigDecimal.ZERO;
    BigDecimal actualCostTotal = BigDecimal.ZERO;
    int overrideAssignmentCount = 0;
    for (ResourceAssignment a : assignments) {
      Resource r = a.getResourceId() == null ? null : resourceById.get(a.getResourceId());
      String cat =
          r != null && r.getResourceType() != null && r.getResourceType().getCode() != null
              ? r.getResourceType().getCode()
              : "UNKNOWN";
      byCategory.computeIfAbsent(cat, k -> new AssignmentRollup(k)).add(a);
      if (a.getPlannedCost() != null) plannedCostTotal = plannedCostTotal.add(a.getPlannedCost());
      if (a.getActualCost() != null) actualCostTotal = actualCostTotal.add(a.getActualCost());
      EffectiveRate er = rateResolver.resolve(projectId, a.getResourceId());
      if (er.overrideApplied()) overrideAssignmentCount++;
    }
    ObjectNode assignSummary = objectMapper.createObjectNode();
    ArrayNode assignRows = objectMapper.createArrayNode();
    for (AssignmentRollup r : byCategory.values()) {
      ObjectNode n = objectMapper.createObjectNode();
      n.put("category", r.category);
      n.put("count", r.count);
      n.put("planned_cost", r.plannedCost);
      n.put("actual_cost", r.actualCost);
      assignRows.add(n);
    }
    assignSummary.set("by_category", assignRows);
    assignSummary.put("total_count", assignments.size());
    assignSummary.put("planned_cost_total", plannedCostTotal.doubleValue());
    assignSummary.put("actual_cost_total", actualCostTotal.doubleValue());
    assignSummary.put("override_assignment_count", overrideAssignmentCount);
    ArrayNode assignNotes = objectMapper.createArrayNode();
    if (overrideAssignmentCount > 0) assignNotes.add("totals_include_project_pool_overrides");
    assignSummary.set("formula_overrides", assignNotes);
    wrapper.set("resource_assignments", assignSummary);

    // Sub-contractor assignments for this activity (4th section in the Resource Demand panel).
    // For each (SC, work-type) pair we surface: planned vs actual qty + cost, the rate, the
    // master norm/day, and a derived qty_completion_pct so the LLM can answer
    // "what's the sub-contractor doing on this activity" without a second tool call.
    List<ActivitySubContractorAssignment> scAssignments =
        subContractorAssignmentRepository.findByProjectIdAndActivityId(projectId, activity.getId());
    ObjectNode scBlock = objectMapper.createObjectNode();
    ArrayNode scRows = objectMapper.createArrayNode();
    BigDecimal scPlannedUnitsTotal = BigDecimal.ZERO;
    BigDecimal scActualUnitsTotal = BigDecimal.ZERO;
    BigDecimal scPlannedCostTotal = BigDecimal.ZERO;
    BigDecimal scActualCostTotal = BigDecimal.ZERO;
    for (ActivitySubContractorAssignment sa : scAssignments) {
      ObjectNode n = objectMapper.createObjectNode();
      n.put("assignment_id", sa.getId() == null ? null : sa.getId().toString());
      n.put("sub_contractor_master_id",
          sa.getSubContractorMasterId() == null ? null : sa.getSubContractorMasterId().toString());
      n.put("sc_work_type_id",
          sa.getScWorkTypeId() == null ? null : sa.getScWorkTypeId().toString());
      n.put("work_type_name", sa.getWorkTypeName());
      n.put("unit", sa.getUnit());
      n.put("rate_per_unit", toDouble(sa.getRatePerUnit()));
      n.put("planned_units", toDouble(sa.getPlannedUnits()));
      n.put("planned_cost", toDouble(sa.getPlannedCost()));
      n.put("actual_units", toDouble(sa.getActualUnits()));
      n.put("actual_cost", toDouble(sa.getActualCost()));
      BigDecimal planned = sa.getPlannedUnits() == null ? BigDecimal.ZERO : sa.getPlannedUnits();
      BigDecimal actual = sa.getActualUnits() == null ? BigDecimal.ZERO : sa.getActualUnits();
      if (planned.signum() > 0) {
        n.put("qty_completion_pct",
            actual.multiply(new BigDecimal("100"))
                .divide(planned, 2, java.math.RoundingMode.HALF_UP)
                .doubleValue());
      }
      scRows.add(n);
      scPlannedUnitsTotal = scPlannedUnitsTotal.add(planned);
      scActualUnitsTotal = scActualUnitsTotal.add(actual);
      scPlannedCostTotal = scPlannedCostTotal.add(
          sa.getPlannedCost() == null ? BigDecimal.ZERO : sa.getPlannedCost());
      scActualCostTotal = scActualCostTotal.add(
          sa.getActualCost() == null ? BigDecimal.ZERO : sa.getActualCost());
    }
    scBlock.set("assignments", scRows);
    scBlock.put("count", scAssignments.size());
    scBlock.put("planned_units_total", scPlannedUnitsTotal.doubleValue());
    scBlock.put("actual_units_total", scActualUnitsTotal.doubleValue());
    scBlock.put("planned_cost_total", scPlannedCostTotal.doubleValue());
    scBlock.put("actual_cost_total", scActualCostTotal.doubleValue());
    BigDecimal scCostVariance = scPlannedCostTotal.subtract(scActualCostTotal);
    scBlock.put("cost_variance", scCostVariance.doubleValue());
    wrapper.set("sub_contractor", scBlock);

    if (includeCost) {
      List<ActivityExpense> expenses = expenseRepository.findByProjectIdAndActivityId(projectId, activity.getId());
      ArrayNode expRows = objectMapper.createArrayNode();
      double bud = 0;
      double act_ = 0;
      double rem = 0;
      double atc = 0;
      for (ActivityExpense e : expenses) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("expense_id", e.getId() == null ? null : e.getId().toString());
        n.put("name", e.getName());
        n.put("cost_account_id", e.getCostAccountId() == null ? null : e.getCostAccountId().toString());
        n.put("budgeted_cost", toDouble(e.getBudgetedCost()));
        n.put("actual_cost", toDouble(e.getActualCost()));
        n.put("remaining_cost", toDouble(e.getRemainingCost()));
        n.put("at_completion_cost", toDouble(e.getAtCompletionCost()));
        n.put("percent_complete", e.getPercentComplete());
        Double variance =
            e.getActualCost() == null || e.getBudgetedCost() == null
                ? null
                : e.getActualCost().subtract(e.getBudgetedCost()).doubleValue();
        n.put("variance", variance);
        expRows.add(n);
        bud += nz(e.getBudgetedCost());
        act_ += nz(e.getActualCost());
        rem += nz(e.getRemainingCost());
        atc += nz(e.getAtCompletionCost());
      }
      ObjectNode cost = objectMapper.createObjectNode();
      cost.set("rows", expRows);
      cost.put("budgeted_total", bud);
      cost.put("actual_total", act_);
      cost.put("remaining_total", rem);
      cost.put("at_completion_total", atc);
      cost.put("variance_total", act_ - bud);
      wrapper.set("cost_variance", cost);
    }

    if (includeEvm) {
      Optional<EvmCalculation> latest =
          evmRepository.findTopByProjectIdAndActivityIdOrderByDataDateDesc(projectId, activity.getId());
      if (latest.isEmpty() && activity.getWbsNodeId() != null) {
        latest =
            evmRepository.findTopByProjectIdAndWbsNodeIdOrderByDataDateDesc(projectId, activity.getWbsNodeId());
      }
      latest.ifPresent(
          e -> {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("data_date", e.getDataDate() == null ? null : e.getDataDate().toString());
            n.put("at_activity_level", e.getActivityId() != null && e.getActivityId().equals(activity.getId()));
            n.put("bac", toDouble(e.getBudgetAtCompletion()));
            n.put("pv", toDouble(e.getPlannedValue()));
            n.put("ev", toDouble(e.getEarnedValue()));
            n.put("ac", toDouble(e.getActualCost()));
            n.put("cv", toDouble(e.getCostVariance()));
            n.put("sv", toDouble(e.getScheduleVariance()));
            n.put("cpi", e.getCostPerformanceIndex());
            n.put("spi", e.getSchedulePerformanceIndex());
            n.put("tcpi", e.getToCompletePerformanceIndex());
            n.put("eac", toDouble(e.getEstimateAtCompletion()));
            n.put("etc", toDouble(e.getEstimateToComplete()));
            n.put("vac", toDouble(e.getVarianceAtCompletion()));
            wrapper.set("evm", n);
          });
    }

    if (dprDays > 0) {
      LocalDate from = LocalDate.now().minusDays(dprDays);
      List<DailyProgressReport> dprAll =
          dprRepository.findByProjectIdAndActivityNameIgnoreCaseOrderByReportDateAsc(
              projectId, activity.getName());
      ArrayNode dprRows = objectMapper.createArrayNode();
      int matchedDpr = 0;
      BigDecimal qtySum = BigDecimal.ZERO;
      // Running cumulative across ALL rows (incl. those before `from`) — that's the
      // P6 cumulative semantics: total qty for this activity through the row's date.
      BigDecimal runningCumulative = BigDecimal.ZERO;
      for (DailyProgressReport d : dprAll) {
        if (d.getQtyExecuted() != null) {
          runningCumulative = runningCumulative.add(d.getQtyExecuted());
        }
        if (d.getReportDate() == null || d.getReportDate().isBefore(from)) continue;
        matchedDpr++;
        if (d.getQtyExecuted() != null) qtySum = qtySum.add(d.getQtyExecuted());
        ObjectNode n = objectMapper.createObjectNode();
        n.put("report_date", d.getReportDate().toString());
        n.put("supervisor_name", d.getSupervisorName());
        n.put("qty_executed", toDouble(d.getQtyExecuted()));
        n.put("unit", d.getUnit());
        n.put("cumulative_qty", runningCumulative.doubleValue());
        n.put("weather", d.getWeatherCondition());
        dprRows.add(n);
      }
      Collections.reverse(dprRows.deepCopy() instanceof ArrayNode ? new java.util.ArrayList<>() : new java.util.ArrayList<>());
      ObjectNode dpr = objectMapper.createObjectNode();
      dpr.set("rows", dprRows);
      dpr.put("days", dprDays);
      dpr.put("count", matchedDpr);
      dpr.put("qty_total", qtySum.doubleValue());
      wrapper.set("recent_dpr", dpr);
    }

    Map<String, List<UUID>> links = new HashMap<>();
    if (activity.getWbsNodeId() != null) links.put("wbs", List.of(activity.getWbsNodeId()));
    if (!resourceIds.isEmpty()) links.put("resource", new ArrayList<>(resourceIds));
    if (activity.getCostAccountId() != null) links.put("cost_account", List.of(activity.getCostAccountId()));
    ToolResult.attachLinks(wrapper, links);

    String summary =
        activity.getCode()
            + " — "
            + activity.getName()
            + " · "
            + (activity.getStatus() == null ? "?" : activity.getStatus().name())
            + " · "
            + (activity.getPercentComplete() == null
                ? "0%"
                : String.format("%.0f%%", activity.getPercentComplete()))
            + (Boolean.TRUE.equals(activity.getIsCritical()) ? " · CRITICAL" : "")
            + (assignments.isEmpty() ? "" : " · " + assignments.size() + " assignments");
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

  private static Double toDouble(BigDecimal b) {
    return b == null ? null : b.doubleValue();
  }

  private static double nz(BigDecimal b) {
    return b == null ? 0.0 : b.doubleValue();
  }

  private static String orNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }

  private static class AssignmentRollup {
    final String category;
    int count = 0;
    double plannedCost = 0;
    double actualCost = 0;

    AssignmentRollup(String category) {
      this.category = category;
    }

    void add(ResourceAssignment a) {
      count++;
      if (a.getPlannedCost() != null) plannedCost += a.getPlannedCost().doubleValue();
      if (a.getActualCost() != null) actualCost += a.getActualCost().doubleValue();
    }
  }
}
