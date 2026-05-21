package com.bipros.ai.tool.capacity;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.Tool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.project.domain.model.BoqItem;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprEquipment;
import com.bipros.project.domain.model.DprManpower;
import com.bipros.project.domain.model.DprMaterial;
import com.bipros.project.domain.repository.BoqItemRepository;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprEquipmentRepository;
import com.bipros.project.domain.repository.DprManpowerRepository;
import com.bipros.project.domain.repository.DprMaterialRepository;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceAssignment;
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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Deployment utilization (actual / available), distinct from the EFFICIENCY view
 * provided by {@code get_capacity_utilization} (actual / norm).
 *
 * <p>Manpower: actual person-days from DPR / planned person-days from ResourceAssignment.
 * Equipment: actual working hours from DPR / planned unit-hours (headcount × overlap-days
 * × {@link #HOURS_PER_DAY}). Material: BOQ planned qty vs DPR-window consumption.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeploymentUtilizationTool implements Tool {

    private static final double HOURS_PER_DAY = 8.0;
    private static final int DEFAULT_WINDOW_DAYS = 30;

    private final DailyProgressReportRepository dprRepository;
    private final DprManpowerRepository dprManpowerRepository;
    private final DprEquipmentRepository dprEquipmentRepository;
    private final DprMaterialRepository dprMaterialRepository;
    private final ResourceAssignmentRepository resourceAssignmentRepository;
    private final ResourceRepository resourceRepository;
    private final BoqItemRepository boqItemRepository;
    private final ObjectMapper mapper;

    @Override
    public String name() {
        return "deployment_utilization";
    }

    @Override
    public String description() {
        return "Capacity DEPLOYMENT view — answers \"how much of the AVAILABLE resource "
                + "capacity was actually USED\" (actual ÷ available). This is DIFFERENT from "
                + "`get_capacity_utilization`, which answers \"did the resource produce the "
                + "BUDGETED qty per day\" (actual ÷ productivity-norm) — that is EFFICIENCY. "
                + "Use both when the user asks a broad capacity question; use this one when "
                + "they ask about deployment, idle time, workforce utilisation, machine "
                + "uptime, hours used, headcount on site, or compare two supervisors' "
                + "deployment. "
                + "Returns three sections: "
                + "(1) manpower — available_person_days from ResourceAssignment.headcount × "
                + "overlap_days, actual_person_days from Σ dpr_manpower.nos, "
                + "deployment_pct and idle_pct, plus per-trade breakdown; "
                + "(2) equipment — available_unit_hours = headcount × overlap_days × 8h, "
                + "actual_working_hours = Σ (dpr_equipment.working_hours × nos), "
                + "deployment_pct + idle_pct + breakdown_pct from the DPR's own idle/breakdown "
                + "buckets, plus per-equipment-type breakdown; "
                + "(3) material — per-BOQ-item planned vs executed-to-date with consumption_pct, "
                + "plus a window-scoped consumption list grouped by material name from DPR rows. "
                + "Inputs: from_date (default = 30 days ago), to_date (default = today), "
                + "supervisor_user_id (optional — when set, scopes both numerator and denominator "
                + "to the activities for which the supervisor filed DPRs in the window; do NOT use "
                + "resolve_entity for the id — call list_project_supervisors(name_filter=…) first), "
                + "section ∈ {manpower, equipment, material, all} (default all). "
                + "All percentages reported at 2 decimal places. "
                + "Requires a project in scope.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = mapper.createObjectNode();
        props.set("from_date", str("ISO date (yyyy-MM-dd). Default: 30 days before to_date."));
        props.set("to_date", str("ISO date (yyyy-MM-dd). Default: today."));
        props.set("supervisor_user_id",
                str("Optional UUID — scopes both numerator and denominator to the activities "
                        + "where this supervisor filed DPRs in the window."));
        ArrayNode sectionEnum = mapper.createArrayNode();
        sectionEnum.add("manpower");
        sectionEnum.add("equipment");
        sectionEnum.add("material");
        sectionEnum.add("all");
        ObjectNode section = mapper.createObjectNode();
        section.put("type", "string");
        section.set("enum", sectionEnum);
        section.put("default", "all");
        props.set("section", section);
        schema.set("properties", props);
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(JsonNode input, AiContext ctx) {
        UUID projectId = ctx.projectId();
        if (projectId == null) {
            return ToolResult.error("deployment_utilization requires a project in scope.");
        }
        if (!"ADMIN".equals(ctx.role())
                && (ctx.scopedProjectIds() == null || !ctx.scopedProjectIds().contains(projectId))) {
            throw new AccessDeniedException("project not in user scope");
        }

        LocalDate toDate = parseDate(input, "to_date", LocalDate.now());
        LocalDate fromDate = parseDate(input, "from_date", toDate.minusDays(DEFAULT_WINDOW_DAYS));
        if (fromDate.isAfter(toDate)) {
            LocalDate t = fromDate;
            fromDate = toDate;
            toDate = t;
        }
        UUID supervisorUserId = parseUuid(text(input, "supervisor_user_id"));
        String section = orDefault(text(input, "section"), "all").toLowerCase();

        // 1. Window DPRs (optionally supervisor-scoped) → drives the actual side and the
        //    activity set for the denominator.
        List<DailyProgressReport> windowDprs = dprRepository
                .findByProjectIdAndReportDateBetweenOrderByReportDateAscIdAsc(projectId, fromDate, toDate);
        if (supervisorUserId != null) {
            List<DailyProgressReport> filtered = new ArrayList<>(windowDprs.size());
            for (DailyProgressReport d : windowDprs) {
                if (supervisorUserId.equals(d.getSupervisorUserId())) filtered.add(d);
            }
            windowDprs = filtered;
        }
        List<UUID> dprIds = new ArrayList<>(windowDprs.size());
        Set<UUID> scopedActivityIds = new HashSet<>();
        for (DailyProgressReport d : windowDprs) {
            dprIds.add(d.getId());
            if (d.getActivityId() != null) scopedActivityIds.add(d.getActivityId());
        }

        // 2. ResourceAssignments for the denominator. When a supervisor scope is set, restrict
        //    to assignments on activities that supervisor reported on; otherwise project-wide.
        List<ResourceAssignment> assignments;
        if (supervisorUserId != null) {
            if (scopedActivityIds.isEmpty()) {
                assignments = List.of();
            } else {
                assignments = resourceAssignmentRepository.findByActivityIdIn(new ArrayList<>(scopedActivityIds));
            }
        } else {
            assignments = resourceAssignmentRepository.findByProjectId(projectId);
        }

        ObjectNode out = mapper.createObjectNode();
        out.put("project_id", projectId.toString());
        out.put("from_date", fromDate.toString());
        out.put("to_date", toDate.toString());
        if (supervisorUserId != null) out.put("supervisor_user_id", supervisorUserId.toString());
        out.put("dpr_count", windowDprs.size());
        out.put("hours_per_day_base", HOURS_PER_DAY);

        boolean includeManpower = section.equals("all") || section.equals("manpower");
        boolean includeEquipment = section.equals("all") || section.equals("equipment");
        boolean includeMaterial = section.equals("all") || section.equals("material");

        Map<UUID, String> typeByAssignment = classifyAssignments(assignments);

        ManpowerResult mp = includeManpower
                ? computeManpower(fromDate, toDate, assignments, typeByAssignment, dprIds)
                : null;
        EquipmentResult eq = includeEquipment
                ? computeEquipment(fromDate, toDate, assignments, typeByAssignment, dprIds)
                : null;
        MaterialResult mt = includeMaterial
                ? computeMaterial(projectId, dprIds)
                : null;

        if (mp != null) out.set("manpower", renderManpower(mp));
        if (eq != null) out.set("equipment", renderEquipment(eq));
        if (mt != null) out.set("material", renderMaterial(mt));

        String summary = buildSummary(mp, eq, mt, fromDate, toDate);
        return ToolResult.ok(summary, out);
    }

    // ──────────────────────────────────────────────────────── manpower

    /** Classify each assignment as MANPOWER / EQUIPMENT / MATERIAL / UNKNOWN.
     *  Variant FKs win when populated (Role-Rate model); fall back to the Resource's
     *  ResourceType code for legacy assignments. */
    private Map<UUID, String> classifyAssignments(List<ResourceAssignment> assignments) {
        Map<UUID, String> out = new HashMap<>();
        Set<UUID> resourceIds = new HashSet<>();
        for (ResourceAssignment a : assignments) {
            if (a.getManpowerRoleRateId() != null) { out.put(a.getId(), "MANPOWER"); continue; }
            if (a.getEquipmentRoleVariantId() != null) { out.put(a.getId(), "EQUIPMENT"); continue; }
            if (a.getMaterialRoleVariantId() != null) { out.put(a.getId(), "MATERIAL"); continue; }
            if (a.getResourceId() != null) resourceIds.add(a.getResourceId());
        }
        if (!resourceIds.isEmpty()) {
            Map<UUID, String> typeByResource = new HashMap<>();
            for (Resource r : resourceRepository.findAllById(resourceIds)) {
                String code = r.getResourceType() == null ? null : r.getResourceType().getCode();
                if (code != null) typeByResource.put(r.getId(), code.toUpperCase());
            }
            for (ResourceAssignment a : assignments) {
                if (out.containsKey(a.getId())) continue;
                if (a.getResourceId() == null) continue;
                String code = typeByResource.get(a.getResourceId());
                if (code == null) continue;
                if (code.equals("LABOR") || code.equals("MANPOWER") || code.equals("LABOUR")) {
                    out.put(a.getId(), "MANPOWER");
                } else if (code.equals("EQUIPMENT") || code.equals("EQUIPMENT_RENTAL") || code.equals("PMV")) {
                    out.put(a.getId(), "EQUIPMENT");
                } else if (code.equals("MATERIAL")) {
                    out.put(a.getId(), "MATERIAL");
                }
            }
        }
        // Last-ditch fallback: a quantity-only row is material; a headcount-only row goes nowhere
        // (unclassified) and is excluded from the manpower/equipment denominator.
        for (ResourceAssignment a : assignments) {
            if (out.containsKey(a.getId())) continue;
            if (a.getQuantity() != null && a.getHeadcount() == null) out.put(a.getId(), "MATERIAL");
        }
        return out;
    }

    private ManpowerResult computeManpower(LocalDate from, LocalDate to,
                                            List<ResourceAssignment> assignments,
                                            Map<UUID, String> typeByAssignment,
                                            List<UUID> dprIds) {
        ManpowerResult r = new ManpowerResult();
        for (ResourceAssignment a : assignments) {
            if (!"MANPOWER".equals(typeByAssignment.get(a.getId()))) continue;
            int headcount = a.getHeadcount() == null ? 0 : a.getHeadcount();
            if (headcount <= 0) continue;
            long overlap = overlapDays(a.getPlannedStartDate(), a.getPlannedFinishDate(), from, to);
            if (overlap < 0) {
                r.assignmentsWithoutDates++;
                continue;
            }
            r.availablePersonDays += headcount * overlap;
            r.plannedAssignmentCount++;
        }

        if (!dprIds.isEmpty()) {
            List<DprManpower> rows = dprManpowerRepository.findByDprIdIn(dprIds);
            for (DprManpower row : rows) {
                int nos = row.getNos() == null ? 0 : row.getNos();
                r.actualPersonDays += nos;
                BigDecimal wh = nz(row.getWorkingHours());
                BigDecimal idle = nz(row.getIdleHours());
                r.actualWorkingHours = r.actualWorkingHours.add(wh.multiply(BigDecimal.valueOf(nos)));
                r.actualIdleHours = r.actualIdleHours.add(idle.multiply(BigDecimal.valueOf(nos)));
                String trade = row.getTrade() == null ? "(unspecified)" : row.getTrade();
                TradeRollup tr = r.byTrade.computeIfAbsent(trade, k -> new TradeRollup());
                tr.actualPersonDays += nos;
                tr.actualWorkingHours = tr.actualWorkingHours.add(wh.multiply(BigDecimal.valueOf(nos)));
                tr.actualIdleHours = tr.actualIdleHours.add(idle.multiply(BigDecimal.valueOf(nos)));
                tr.rowCount++;
            }
        }
        return r;
    }

    private ObjectNode renderManpower(ManpowerResult r) {
        ObjectNode m = mapper.createObjectNode();
        m.put("available_person_days", r.availablePersonDays);
        m.put("actual_person_days", r.actualPersonDays);
        Double dep = ratioPct(r.actualPersonDays, r.availablePersonDays);
        m.put("deployment_pct", dep == null ? null : round2(dep));
        m.put("idle_pct", dep == null ? null : round2(100.0 - dep));
        m.put("actual_working_hours", round2(r.actualWorkingHours.doubleValue()));
        m.put("actual_idle_hours", round2(r.actualIdleHours.doubleValue()));
        m.put("planned_assignment_count", r.plannedAssignmentCount);
        m.put("assignments_without_planned_dates", r.assignmentsWithoutDates);
        ArrayNode rows = mapper.createArrayNode();
        r.byTrade.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, TradeRollup> e) -> e.getValue().actualPersonDays).reversed())
                .forEach(e -> {
                    ObjectNode n = mapper.createObjectNode();
                    n.put("trade", e.getKey());
                    n.put("actual_person_days", e.getValue().actualPersonDays);
                    n.put("actual_working_hours", round2(e.getValue().actualWorkingHours.doubleValue()));
                    n.put("actual_idle_hours", round2(e.getValue().actualIdleHours.doubleValue()));
                    n.put("dpr_row_count", e.getValue().rowCount);
                    rows.add(n);
                });
        m.set("by_trade", rows);
        return m;
    }

    // ──────────────────────────────────────────────────────── equipment

    private EquipmentResult computeEquipment(LocalDate from, LocalDate to,
                                              List<ResourceAssignment> assignments,
                                              Map<UUID, String> typeByAssignment,
                                              List<UUID> dprIds) {
        EquipmentResult r = new EquipmentResult();
        for (ResourceAssignment a : assignments) {
            if (!"EQUIPMENT".equals(typeByAssignment.get(a.getId()))) continue;
            int headcount = a.getHeadcount() == null ? 0 : a.getHeadcount();
            if (headcount <= 0) continue;
            long overlap = overlapDays(a.getPlannedStartDate(), a.getPlannedFinishDate(), from, to);
            if (overlap < 0) {
                r.assignmentsWithoutDates++;
                continue;
            }
            r.availableUnitHours += headcount * overlap * HOURS_PER_DAY;
            r.plannedAssignmentCount++;
        }

        if (!dprIds.isEmpty()) {
            List<DprEquipment> rows = dprEquipmentRepository.findByDprIdIn(dprIds);
            for (DprEquipment row : rows) {
                int nos = row.getNos() == null ? 0 : row.getNos();
                BigDecimal wh = nz(row.getWorkingHours()).multiply(BigDecimal.valueOf(nos));
                BigDecimal idle = nz(row.getIdleHours()).multiply(BigDecimal.valueOf(nos));
                BigDecimal bd = nz(row.getBreakdownHours()).multiply(BigDecimal.valueOf(nos));
                r.actualWorkingHours = r.actualWorkingHours.add(wh);
                r.actualIdleHours = r.actualIdleHours.add(idle);
                r.actualBreakdownHours = r.actualBreakdownHours.add(bd);
                String type = row.getEquipmentType() == null ? "(unspecified)" : row.getEquipmentType();
                EquipmentRollup er = r.byType.computeIfAbsent(type, k -> new EquipmentRollup());
                er.workingHours = er.workingHours.add(wh);
                er.idleHours = er.idleHours.add(idle);
                er.breakdownHours = er.breakdownHours.add(bd);
                er.unitDeployments += nos;
                er.rowCount++;
            }
        }
        return r;
    }

    private ObjectNode renderEquipment(EquipmentResult r) {
        ObjectNode e = mapper.createObjectNode();
        e.put("available_unit_hours", round2(r.availableUnitHours));
        e.put("actual_working_hours", round2(r.actualWorkingHours.doubleValue()));
        e.put("actual_idle_hours", round2(r.actualIdleHours.doubleValue()));
        e.put("actual_breakdown_hours", round2(r.actualBreakdownHours.doubleValue()));
        Double dep = ratioPct(r.actualWorkingHours.doubleValue(), r.availableUnitHours);
        e.put("deployment_pct", dep == null ? null : round2(dep));
        Double idle = ratioPct(r.actualIdleHours.doubleValue(), r.availableUnitHours);
        e.put("idle_pct", idle == null ? null : round2(idle));
        Double bd = ratioPct(r.actualBreakdownHours.doubleValue(), r.availableUnitHours);
        e.put("breakdown_pct", bd == null ? null : round2(bd));
        e.put("planned_assignment_count", r.plannedAssignmentCount);
        e.put("assignments_without_planned_dates", r.assignmentsWithoutDates);
        ArrayNode rows = mapper.createArrayNode();
        r.byType.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, EquipmentRollup> en) ->
                        en.getValue().workingHours).reversed())
                .forEach(en -> {
                    ObjectNode n = mapper.createObjectNode();
                    n.put("equipment_type", en.getKey());
                    n.put("unit_deployments", en.getValue().unitDeployments);
                    n.put("working_hours", round2(en.getValue().workingHours.doubleValue()));
                    n.put("idle_hours", round2(en.getValue().idleHours.doubleValue()));
                    n.put("breakdown_hours", round2(en.getValue().breakdownHours.doubleValue()));
                    n.put("dpr_row_count", en.getValue().rowCount);
                    rows.add(n);
                });
        e.set("by_type", rows);
        return e;
    }

    // ──────────────────────────────────────────────────────── material

    private MaterialResult computeMaterial(UUID projectId, List<UUID> dprIds) {
        MaterialResult r = new MaterialResult();
        List<BoqItem> boqs = boqItemRepository.findByProjectIdOrderByItemNoAsc(projectId);
        for (BoqItem b : boqs) {
            BigDecimal planned = nz(b.getBoqQty());
            BigDecimal executed = nz(b.getQtyExecutedToDate());
            BoqRollup br = new BoqRollup();
            br.itemNo = b.getItemNo();
            br.description = b.getDescription();
            br.unit = b.getUnit();
            br.plannedQty = planned;
            br.executedToDateQty = executed;
            r.boqRows.add(br);
            r.boqPlannedTotal = r.boqPlannedTotal.add(planned);
            r.boqExecutedToDateTotal = r.boqExecutedToDateTotal.add(executed);
        }

        if (!dprIds.isEmpty()) {
            List<DprMaterial> rows = dprMaterialRepository.findByDprIdIn(dprIds);
            for (DprMaterial row : rows) {
                String name = row.getMaterialName() == null ? "(unspecified)" : row.getMaterialName();
                MaterialConsumption mc = r.byMaterial.computeIfAbsent(name, k -> new MaterialConsumption());
                mc.name = name;
                mc.unit = row.getUnit();
                mc.consumedQty = mc.consumedQty.add(nz(row.getQuantity()));
                mc.rowCount++;
                r.windowConsumedTotal = r.windowConsumedTotal.add(nz(row.getQuantity()));
            }
        }
        return r;
    }

    private ObjectNode renderMaterial(MaterialResult r) {
        ObjectNode m = mapper.createObjectNode();
        m.put("boq_planned_total", round2(r.boqPlannedTotal.doubleValue()));
        m.put("boq_executed_to_date_total", round2(r.boqExecutedToDateTotal.doubleValue()));
        Double pct = ratioPct(r.boqExecutedToDateTotal.doubleValue(), r.boqPlannedTotal.doubleValue());
        m.put("boq_consumption_pct", pct == null ? null : round2(pct));
        m.put("dpr_window_consumed_total", round2(r.windowConsumedTotal.doubleValue()));

        ArrayNode boqRows = mapper.createArrayNode();
        for (BoqRollup b : r.boqRows) {
            if (b.plannedQty.signum() == 0 && b.executedToDateQty.signum() == 0) continue;
            ObjectNode n = mapper.createObjectNode();
            n.put("item_no", b.itemNo);
            n.put("description", truncate(b.description, 120));
            n.put("unit", b.unit);
            n.put("planned_qty", round2(b.plannedQty.doubleValue()));
            n.put("executed_to_date_qty", round2(b.executedToDateQty.doubleValue()));
            Double cp = ratioPct(b.executedToDateQty.doubleValue(), b.plannedQty.doubleValue());
            n.put("consumption_pct", cp == null ? null : round2(cp));
            boqRows.add(n);
        }
        m.set("boq_rows", boqRows);

        ArrayNode windowRows = mapper.createArrayNode();
        r.byMaterial.values().stream()
                .sorted(Comparator.comparing((MaterialConsumption mc) -> mc.consumedQty).reversed())
                .forEach(mc -> {
                    ObjectNode n = mapper.createObjectNode();
                    n.put("material_name", mc.name);
                    n.put("unit", mc.unit);
                    n.put("consumed_qty_in_window", round2(mc.consumedQty.doubleValue()));
                    n.put("dpr_row_count", mc.rowCount);
                    windowRows.add(n);
                });
        m.set("dpr_window_by_material", windowRows);
        return m;
    }

    // ──────────────────────────────────────────────────────── helpers

    private String buildSummary(ManpowerResult mp, EquipmentResult eq, MaterialResult mt,
                                 LocalDate from, LocalDate to) {
        StringBuilder sb = new StringBuilder("Deployment ").append(from).append("..").append(to).append(": ");
        boolean first = true;
        if (mp != null) {
            Double dep = ratioPct(mp.actualPersonDays, mp.availablePersonDays);
            sb.append(first ? "" : "; ");
            sb.append("manpower ").append(mp.actualPersonDays).append("/").append(mp.availablePersonDays)
                    .append(" person-days (").append(dep == null ? "n/a" : String.format("%.2f%%", dep)).append(")");
            first = false;
        }
        if (eq != null) {
            Double dep = ratioPct(eq.actualWorkingHours.doubleValue(), eq.availableUnitHours);
            sb.append(first ? "" : "; ");
            sb.append("equipment ").append(round2(eq.actualWorkingHours.doubleValue()))
                    .append("/").append(round2(eq.availableUnitHours)).append(" hours (")
                    .append(dep == null ? "n/a" : String.format("%.2f%%", dep)).append(")");
            first = false;
        }
        if (mt != null) {
            Double pct = ratioPct(mt.boqExecutedToDateTotal.doubleValue(), mt.boqPlannedTotal.doubleValue());
            sb.append(first ? "" : "; ");
            sb.append("BOQ executed ").append(pct == null ? "n/a" : String.format("%.2f%%", pct));
        }
        return sb.toString();
    }

    /** Inclusive overlap of (assignmentStart, assignmentFinish) with (windowFrom, windowTo).
     *  Returns -1 when assignment dates are missing (caller flags it). */
    private static long overlapDays(LocalDate aStart, LocalDate aFinish,
                                     LocalDate winFrom, LocalDate winTo) {
        if (aStart == null || aFinish == null) return -1;
        LocalDate start = aStart.isAfter(winFrom) ? aStart : winFrom;
        LocalDate end = aFinish.isBefore(winTo) ? aFinish : winTo;
        if (end.isBefore(start)) return 0;
        return ChronoUnit.DAYS.between(start, end) + 1;
    }

    private static Double ratioPct(double num, double den) {
        if (den <= 0.0) return null;
        return (num / den) * 100.0;
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static BigDecimal nz(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }

    private static String text(JsonNode in, String field) {
        if (in == null) return null;
        JsonNode n = in.path(field);
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asText(null);
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String orDefault(String s, String d) {
        return s == null || s.isBlank() ? d : s;
    }

    private static LocalDate parseDate(JsonNode in, String field, LocalDate fallback) {
        String s = text(in, field);
        if (s == null) return fallback;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static UUID parseUuid(String s) {
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ObjectNode str(String description) {
        ObjectNode n = mapper.createObjectNode();
        n.put("type", "string");
        n.put("description", description);
        return n;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static class ManpowerResult {
        long availablePersonDays = 0;
        long actualPersonDays = 0;
        BigDecimal actualWorkingHours = BigDecimal.ZERO;
        BigDecimal actualIdleHours = BigDecimal.ZERO;
        int plannedAssignmentCount = 0;
        int assignmentsWithoutDates = 0;
        Map<String, TradeRollup> byTrade = new LinkedHashMap<>();
    }

    private static class TradeRollup {
        long actualPersonDays = 0;
        BigDecimal actualWorkingHours = BigDecimal.ZERO;
        BigDecimal actualIdleHours = BigDecimal.ZERO;
        int rowCount = 0;
    }

    private static class EquipmentResult {
        double availableUnitHours = 0.0;
        BigDecimal actualWorkingHours = BigDecimal.ZERO;
        BigDecimal actualIdleHours = BigDecimal.ZERO;
        BigDecimal actualBreakdownHours = BigDecimal.ZERO;
        int plannedAssignmentCount = 0;
        int assignmentsWithoutDates = 0;
        Map<String, EquipmentRollup> byType = new LinkedHashMap<>();
    }

    private static class EquipmentRollup {
        BigDecimal workingHours = BigDecimal.ZERO;
        BigDecimal idleHours = BigDecimal.ZERO;
        BigDecimal breakdownHours = BigDecimal.ZERO;
        int unitDeployments = 0;
        int rowCount = 0;
    }

    private static class MaterialResult {
        BigDecimal boqPlannedTotal = BigDecimal.ZERO;
        BigDecimal boqExecutedToDateTotal = BigDecimal.ZERO;
        BigDecimal windowConsumedTotal = BigDecimal.ZERO;
        List<BoqRollup> boqRows = new ArrayList<>();
        Map<String, MaterialConsumption> byMaterial = new LinkedHashMap<>();
    }

    private static class BoqRollup {
        String itemNo;
        String description;
        String unit;
        BigDecimal plannedQty = BigDecimal.ZERO;
        BigDecimal executedToDateQty = BigDecimal.ZERO;
    }

    private static class MaterialConsumption {
        String name;
        String unit;
        BigDecimal consumedQty = BigDecimal.ZERO;
        int rowCount = 0;
    }
}
