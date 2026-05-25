package com.bipros.ai.tool.resource;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.Tool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.resource.application.dto.SubContractorMasterResponse;
import com.bipros.resource.application.dto.SubContractorWorkActivityMappingRow;
import com.bipros.resource.application.service.SubContractorMasterService;
import com.bipros.resource.domain.model.ActivitySubContractorAssignment;
import com.bipros.resource.domain.repository.ActivitySubContractorAssignmentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Lists the sub-contractor master directory — every sub-contractor configured in the system,
 * including the inactive ones — with their work-type rate book (work type, unit, rate per unit,
 * output per day). Optionally enriches each row with the activities on the in-scope project that
 * have planned this sub-contractor, so the LLM can answer reverse-lookup questions
 * ("which activities use Apex Infrastructure").
 *
 * <p>Distinct from {@code get_subcontractor_kpis}, which only returns sub-contractors that have
 * appeared in DPRs / activity plans within a date window — this tool returns the configured
 * master regardless of usage.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListSubContractorsTool implements Tool {

    private final SubContractorMasterService masterService;
    private final ActivitySubContractorAssignmentRepository assignmentRepository;
    private final ActivityRepository activityRepository;
    private final ObjectMapper mapper;

    @Override
    public String name() {
        return "list_sub_contractors";
    }

    @Override
    public String description() {
        return "Lists ALL sub-contractors configured in the master directory (active + inactive) "
                + "with their work-type rate book — for each sub-contractor: code, name, location, "
                + "primary contact, active flag, and per-work-type rows of {work type, unit, rate per "
                + "unit, output per day (productivity norm)}. When a project is in scope, each "
                + "sub-contractor is also annotated with the activities on that project that have "
                + "PLANNED this sub-contractor (activity code/name, planned units, planned cost) so "
                + "you can answer 'which activities use SC X' without scanning every activity. "
                + "Use this for: 'list sub-contractors', 'who are the sub-contractors', 'show the "
                + "sub-contractor master', 'which activities use SC X', 'what work types is SC X "
                + "configured for', 'what's the rate for SC X on work type Y'. For PERFORMANCE / "
                + "actual vs planned / cost variance / productivity factor, use `get_subcontractor_kpis` "
                + "instead — that tool joins DPR actuals. Inputs: projectId (optional, falls back "
                + "to scope; activity usage is empty when no project is in scope), activeOnly "
                + "(boolean, default false — when true, hides inactive masters), includeActivityUsage "
                + "(boolean, default true).";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        props.set("projectId", mapper.createObjectNode()
                .put("type", "string").put("format", "uuid")
                .put("description", "Project UUID. Optional — falls back to the project in scope. "
                        + "When null, activity usage is omitted (master is global)."));
        props.set("activeOnly", mapper.createObjectNode()
                .put("type", "boolean").put("default", false)
                .put("description", "When true, only active sub-contractors are returned."));
        props.set("includeActivityUsage", mapper.createObjectNode()
                .put("type", "boolean").put("default", true)
                .put("description", "When true (and projectId is set), each sub-contractor carries an "
                        + "activitiesUsing[] array of the project activities that planned it."));
        schema.set("properties", props);
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(JsonNode input, AiContext ctx) {
        UUID projectId = parseUuid(input.path("projectId").asText(null));
        if (projectId == null) projectId = ctx.projectId();
        boolean activeOnly = input.path("activeOnly").asBoolean(false);
        boolean includeUsage = input.path("includeActivityUsage").asBoolean(true);

        List<SubContractorMasterResponse> masters = masterService.list();
        if (activeOnly) {
            masters = masters.stream()
                    .filter(m -> Boolean.TRUE.equals(m.active()))
                    .toList();
        }

        // Reverse lookup: SC master id → activity usage rows (on this project).
        Map<UUID, List<ActivityUsage>> usageByScId = (includeUsage && projectId != null)
                ? buildUsageIndex(projectId)
                : Map.of();

        ArrayNode list = mapper.createArrayNode();
        for (SubContractorMasterResponse m : masters) {
            ObjectNode row = mapper.createObjectNode();
            row.put("id", m.id() == null ? null : m.id().toString());
            row.put("code", m.code());
            row.put("name", m.name());
            row.put("location", m.location());
            row.put("primaryContactName", m.primaryContactName());
            row.put("primaryContactNumber", m.primaryContactNumber());
            row.put("active", m.active());
            row.put("remarks", m.remarks());

            ArrayNode workTypes = mapper.createArrayNode();
            List<SubContractorWorkActivityMappingRow> mappings = m.workActivityMappings();
            if (mappings != null) {
                for (SubContractorWorkActivityMappingRow wt : mappings) {
                    ObjectNode wtNode = mapper.createObjectNode();
                    wtNode.put("workTypeName", wt.workTypeName());
                    wtNode.put("unit", wt.unit());
                    wtNode.put("ratePerUnit", wt.ratePerUnit() == null ? null : wt.ratePerUnit().toPlainString());
                    wtNode.put("outputPerDay", wt.outputPerDay() == null ? null : wt.outputPerDay().toPlainString());
                    workTypes.add(wtNode);
                }
            }
            row.set("workTypes", workTypes);

            if (includeUsage && projectId != null) {
                ArrayNode used = mapper.createArrayNode();
                for (ActivityUsage u : usageByScId.getOrDefault(m.id(), List.of())) {
                    ObjectNode uNode = mapper.createObjectNode();
                    uNode.put("activityId", u.activityId == null ? null : u.activityId.toString());
                    uNode.put("activityCode", u.activityCode);
                    uNode.put("activityName", u.activityName);
                    uNode.put("workTypeName", u.workTypeName);
                    uNode.put("unit", u.unit);
                    uNode.put("plannedUnits", u.plannedUnits == null ? null : u.plannedUnits.toPlainString());
                    uNode.put("plannedCost", u.plannedCost == null ? null : u.plannedCost.toPlainString());
                    used.add(uNode);
                }
                row.set("activitiesUsing", used);
            }
            list.add(row);
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.set("subContractors", list);
        payload.put("total", masters.size());
        payload.put("activeOnly", activeOnly);
        payload.put("includesActivityUsage", includeUsage && projectId != null);

        String summary = summarise(masters, usageByScId, includeUsage && projectId != null);
        return ToolResult.ok(summary, payload);
    }

    private Map<UUID, List<ActivityUsage>> buildUsageIndex(UUID projectId) {
        List<ActivitySubContractorAssignment> assignments;
        try {
            assignments = assignmentRepository.findByProjectId(projectId);
        } catch (Exception ex) {
            log.warn("list_sub_contractors usage lookup failed projectId={}: {}", projectId, ex.toString());
            return Map.of();
        }
        if (assignments.isEmpty()) return Map.of();

        // Batch-resolve activity code/name.
        List<UUID> activityIds = assignments.stream()
                .map(ActivitySubContractorAssignment::getActivityId)
                .distinct()
                .toList();
        Map<UUID, Activity> activityById;
        try {
            activityById = activityRepository.findAllById(activityIds).stream()
                    .collect(Collectors.toMap(Activity::getId, a -> a, (a, b) -> a));
        } catch (Exception ex) {
            log.warn("list_sub_contractors activity name lookup failed: {}", ex.toString());
            activityById = Map.of();
        }

        Map<UUID, List<ActivityUsage>> index = new HashMap<>();
        for (ActivitySubContractorAssignment a : assignments) {
            Activity act = activityById.get(a.getActivityId());
            ActivityUsage u = new ActivityUsage(
                    a.getActivityId(),
                    act == null ? null : act.getCode(),
                    act == null ? null : act.getName(),
                    a.getWorkTypeName(),
                    a.getUnit(),
                    a.getPlannedUnits(),
                    a.getPlannedCost());
            index.computeIfAbsent(a.getSubContractorMasterId(), k -> new java.util.ArrayList<>()).add(u);
        }
        return index;
    }

    private String summarise(List<SubContractorMasterResponse> masters,
                              Map<UUID, List<ActivityUsage>> usageByScId,
                              boolean includesUsage) {
        long active = masters.stream().filter(m -> Boolean.TRUE.equals(m.active())).count();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "Sub-contractor master: %d total (%d active, %d inactive).%n",
                masters.size(), active, masters.size() - active));
        for (SubContractorMasterResponse m : masters) {
            List<SubContractorWorkActivityMappingRow> wt = m.workActivityMappings();
            int wtCount = wt == null ? 0 : wt.size();
            sb.append(String.format(Locale.ROOT, "  · %s (%s)%s — %s, %d work-type(s)",
                    nz(m.name()), nz(m.code()),
                    Boolean.TRUE.equals(m.active()) ? "" : " [INACTIVE]",
                    nz(m.location()), wtCount));
            if (includesUsage) {
                int used = usageByScId.getOrDefault(m.id(), List.of()).size();
                sb.append(String.format(Locale.ROOT, ", planned on %d activity row(s)", used));
            }
            sb.append('\n');
            if (wt != null) {
                for (SubContractorWorkActivityMappingRow row : wt) {
                    sb.append(String.format(Locale.ROOT, "      - %s: %s %s/unit, %s %s/day%n",
                            nz(row.workTypeName()),
                            fmt(row.ratePerUnit()), nz(row.unit()),
                            fmt(row.outputPerDay()), nz(row.unit())));
                }
            }
        }
        return sb.toString().stripTrailing();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String fmt(BigDecimal v) {
        if (v == null) return "—";
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s.trim()); } catch (IllegalArgumentException ex) { return null; }
    }

    private record ActivityUsage(
            UUID activityId,
            String activityCode,
            String activityName,
            String workTypeName,
            String unit,
            BigDecimal plannedUnits,
            BigDecimal plannedCost) {}
}
