package com.bipros.ai.tool.dbs;

import com.bipros.ai.context.AiContext;
import com.bipros.ai.tool.Tool;
import com.bipros.ai.tool.ToolResult;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprEquipment;
import com.bipros.project.domain.model.DprManpower;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprEquipmentRepository;
import com.bipros.project.domain.repository.DprManpowerRepository;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Daily Balance Sheet — the SC-180 site office's daily resource ledger.
 *
 * <p>For each trade and each equipment type on a project, reports:
 * yesterday's closing → today's additions / releases → today's closing → working / idle hours
 * → utilization %. For a multi-day window we aggregate hours across all days, but opening and
 * closing are taken from the first and last day so a "week-of" snapshot reads naturally.
 *
 * <p>The model deliberately uses the per-day deployed count (Σ nos for that resource on that
 * day) as the "closing deployed" number — this matches how site offices reconcile manpower /
 * equipment headcounts at the end of each shift.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyBalanceSheetTool implements Tool {

    /** Shift length assumed when computing capacity (utilization denominator). */
    private static final BigDecimal SHIFT_HOURS = new BigDecimal("11");

    private final DailyProgressReportRepository dprRepository;
    private final DprManpowerRepository manpowerRepository;
    private final DprEquipmentRepository equipmentRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "dbs_report";
    }

    @Override
    public String description() {
        return "Resource (manpower + equipment) ledger derived from DPR rows — opening headcount, "
                + "additions/releases, closing headcount, working/idle hours, utilization %. Use "
                + "for questions about who/what is on site, trade counts, equipment ins-and-outs, "
                + "utilization. This is NOT the financial Daily Balance Sheet — for expense, "
                + "income, contribution margin, and BOQ planned-vs-achieved use `dbs_financial`.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = objectMapper.createObjectNode();

        props.set("projectId", objectMapper.createObjectNode()
                .put("type", "string").put("format", "uuid")
                .put("description", "Project UUID. Required (or rely on the project in scope)."));
        props.set("date", objectMapper.createObjectNode()
                .put("type", "string").put("format", "date")
                .put("description", "Single day — ISO date. If omitted, use fromDate + toDate."));
        props.set("fromDate", objectMapper.createObjectNode()
                .put("type", "string").put("format", "date")
                .put("description", "Inclusive lower bound when 'date' is not supplied."));
        props.set("toDate", objectMapper.createObjectNode()
                .put("type", "string").put("format", "date")
                .put("description", "Inclusive upper bound when 'date' is not supplied."));
        props.set("supervisorUserId", objectMapper.createObjectNode()
                .put("type", "string").put("format", "uuid")
                .put("description", "Optional supervisor (user) filter."));

        schema.set("properties", props);
        return schema;
    }

    @Override
    @Transactional(readOnly = true)
    public ToolResult execute(JsonNode input, AiContext ctx) {
        UUID projectId = parseUuid(input.path("projectId").asText(null));
        if (projectId == null) projectId = ctx.projectId();
        if (projectId == null) {
            return ToolResult.error("dbs_report needs a projectId (or a project in scope).");
        }
        if (!"ADMIN".equals(ctx.role())
                && (ctx.scopedProjectIds() == null || !ctx.scopedProjectIds().contains(projectId))) {
            throw new AccessDeniedException("project not in user scope");
        }

        LocalDate single = parseDate(input.path("date").asText(null));
        LocalDate fromDate;
        LocalDate toDate;
        if (single != null) {
            fromDate = single;
            toDate = single;
        } else {
            fromDate = parseDate(input.path("fromDate").asText(null));
            toDate = parseDate(input.path("toDate").asText(null));
            if (fromDate == null && toDate == null) {
                toDate = LocalDate.now();
                fromDate = toDate.minusDays(6); // default = last 7 days
            } else if (fromDate == null) {
                fromDate = toDate;
            } else if (toDate == null) {
                toDate = fromDate;
            } else if (fromDate.isAfter(toDate)) {
                LocalDate t = fromDate;
                fromDate = toDate;
                toDate = t;
            }
        }
        UUID supervisorUserId = parseUuid(input.path("supervisorUserId").asText(null));

        List<DailyProgressReport> dprs = dprRepository
                .findByProjectIdAndReportDateBetweenOrderByReportDateAscIdAsc(projectId, fromDate, toDate);
        if (supervisorUserId != null) {
            UUID supId = supervisorUserId;
            dprs = dprs.stream()
                    .filter(d -> supId.equals(d.getSupervisorUserId()))
                    .toList();
        }
        if (dprs.isEmpty()) {
            ObjectNode out = objectMapper.createObjectNode();
            out.put("projectId", projectId.toString());
            out.set("dateRange", renderRange(fromDate, toDate));
            out.set("summary", emptySummary());
            out.set("manpower", objectMapper.createArrayNode());
            out.set("equipment", objectMapper.createArrayNode());
            return ToolResult.ok("Daily Balance Sheet: no DPR rows in window.", out);
        }

        // Bucket DPRs by date and group child rows.
        Map<UUID, LocalDate> dprDate = new HashMap<>();
        for (DailyProgressReport d : dprs) dprDate.put(d.getId(), d.getReportDate());
        List<UUID> dprIds = new ArrayList<>(dprDate.keySet());
        List<DprManpower> manRows = manpowerRepository.findByDprIdIn(dprIds);
        List<DprEquipment> eqRows = equipmentRepository.findByDprIdIn(dprIds);

        // (resource, date) → bucket
        Map<String, Map<LocalDate, ResourceDayBucket>> manpowerByTradeDate = new HashMap<>();
        for (DprManpower r : manRows) {
            LocalDate d = dprDate.get(r.getDprId());
            if (d == null) continue;
            String key = r.getTrade() == null ? "(unknown trade)" : r.getTrade();
            manpowerByTradeDate
                    .computeIfAbsent(key, k -> new TreeMap<>())
                    .computeIfAbsent(d, k -> new ResourceDayBucket())
                    .addManpower(r);
        }
        Map<String, Map<LocalDate, ResourceDayBucket>> equipByTypeDate = new HashMap<>();
        for (DprEquipment r : eqRows) {
            LocalDate d = dprDate.get(r.getDprId());
            if (d == null) continue;
            String key = r.getEquipmentType() == null ? "(unknown equipment)" : r.getEquipmentType();
            equipByTypeDate
                    .computeIfAbsent(key, k -> new TreeMap<>())
                    .computeIfAbsent(d, k -> new ResourceDayBucket())
                    .addEquipment(r);
        }

        ArrayNode manpowerRows = renderResourceRows(manpowerByTradeDate, fromDate, toDate, "MANPOWER");
        ArrayNode equipmentRows = renderResourceRows(equipByTypeDate, fromDate, toDate, "EQUIPMENT");

        ObjectNode out = objectMapper.createObjectNode();
        out.put("projectId", projectId.toString());
        out.set("dateRange", renderRange(fromDate, toDate));
        out.set("summary", summarize(manpowerRows, equipmentRows, dprs.size()));
        out.set("manpower", manpowerRows);
        out.set("equipment", equipmentRows);

        String summaryLine = "DBS " + fromDate + "→" + toDate + ": "
                + manpowerRows.size() + " trades, "
                + equipmentRows.size() + " equipment types, "
                + dprs.size() + " DPR rows";
        return ToolResult.ok(summaryLine, out);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Aggregation
    // ──────────────────────────────────────────────────────────────────────

    private ArrayNode renderResourceRows(Map<String, Map<LocalDate, ResourceDayBucket>> byResourceDate,
                                         LocalDate fromDate, LocalDate toDate, String kind) {
        ArrayNode rows = objectMapper.createArrayNode();
        List<String> resources = new ArrayList<>(byResourceDate.keySet());
        resources.sort(Comparator.naturalOrder());
        for (String resource : resources) {
            Map<LocalDate, ResourceDayBucket> perDay = byResourceDate.get(resource);
            if (perDay == null || perDay.isEmpty()) continue;

            LocalDate firstDay = perDay.keySet().stream().min(Comparator.naturalOrder()).orElse(fromDate);
            LocalDate lastDay = perDay.keySet().stream().max(Comparator.naturalOrder()).orElse(toDate);
            ResourceDayBucket first = perDay.get(firstDay);
            ResourceDayBucket last = perDay.get(lastDay);

            int opening = 0;
            // "Yesterday's closing" — for the first day in window we don't know prior state,
            // so opening = same day's closing minus additions (best available proxy when range > 1).
            if (firstDay.equals(toDate)) {
                opening = first.nos;
            } else {
                // For multi-day windows, take the first day's nos as the opening reference and
                // walk forward to derive additions/releases day-on-day.
                opening = first.nos;
            }
            int closing = last.nos;
            int additions = 0;
            int releases = 0;
            LocalDate prevDay = null;
            int prevNos = 0;
            BigDecimal workingHours = BigDecimal.ZERO;
            BigDecimal idleHours = BigDecimal.ZERO;
            BigDecimal otHours = BigDecimal.ZERO;
            BigDecimal breakdownHours = BigDecimal.ZERO;
            BigDecimal nosDays = BigDecimal.ZERO; // for utilization denominator: Σ (nos × shift_hours)
            for (Map.Entry<LocalDate, ResourceDayBucket> e : perDay.entrySet()) {
                ResourceDayBucket b = e.getValue();
                if (prevDay != null) {
                    int delta = b.nos - prevNos;
                    if (delta > 0) additions += delta;
                    else if (delta < 0) releases += -delta;
                }
                prevDay = e.getKey();
                prevNos = b.nos;
                workingHours = workingHours.add(b.workingHours);
                idleHours = idleHours.add(b.idleHours);
                otHours = otHours.add(b.otHours);
                breakdownHours = breakdownHours.add(b.breakdownHours);
                nosDays = nosDays.add(BigDecimal.valueOf(b.nos));
            }
            BigDecimal capacity = nosDays.multiply(SHIFT_HOURS);
            BigDecimal utilization;
            if (capacity.signum() == 0) {
                utilization = null;
            } else {
                utilization = workingHours
                        .divide(capacity, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                        .setScale(2, RoundingMode.HALF_UP);
            }

            ObjectNode row = objectMapper.createObjectNode();
            row.put("resource", resource);
            row.put("kind", kind);
            row.put("openingDeployed", opening);
            row.put("additions", additions);
            row.put("releases", releases);
            row.put("closingDeployed", closing);
            row.put("workingHours", workingHours.setScale(2, RoundingMode.HALF_UP).toPlainString());
            row.put("idleHours", idleHours.setScale(2, RoundingMode.HALF_UP).toPlainString());
            if ("MANPOWER".equals(kind)) {
                row.put("otHours", otHours.setScale(2, RoundingMode.HALF_UP).toPlainString());
            } else {
                row.put("breakdownHours", breakdownHours.setScale(2, RoundingMode.HALF_UP).toPlainString());
            }
            if (utilization != null) {
                row.put("utilizationPct", utilization.toPlainString());
            } else {
                row.putNull("utilizationPct");
            }
            row.put("daysObserved", perDay.size());
            row.put("firstDay", firstDay.toString());
            row.put("lastDay", lastDay.toString());
            rows.add(row);
        }
        return rows;
    }

    private ObjectNode summarize(ArrayNode manpowerRows, ArrayNode equipmentRows, int dprCount) {
        int totalManpower = 0;
        int totalEquipment = 0;
        BigDecimal totalManWorking = BigDecimal.ZERO;
        BigDecimal totalManIdle = BigDecimal.ZERO;
        BigDecimal totalEqWorking = BigDecimal.ZERO;
        BigDecimal totalEqIdle = BigDecimal.ZERO;
        for (JsonNode r : manpowerRows) {
            totalManpower += r.path("closingDeployed").asInt(0);
            totalManWorking = totalManWorking.add(parseBd(r.path("workingHours").asText("0")));
            totalManIdle = totalManIdle.add(parseBd(r.path("idleHours").asText("0")));
        }
        for (JsonNode r : equipmentRows) {
            totalEquipment += r.path("closingDeployed").asInt(0);
            totalEqWorking = totalEqWorking.add(parseBd(r.path("workingHours").asText("0")));
            totalEqIdle = totalEqIdle.add(parseBd(r.path("idleHours").asText("0")));
        }
        ObjectNode s = objectMapper.createObjectNode();
        s.put("manpower_trades", manpowerRows.size());
        s.put("manpower_closing_total", totalManpower);
        s.put("manpower_working_hours", totalManWorking.setScale(2, RoundingMode.HALF_UP).toPlainString());
        s.put("manpower_idle_hours", totalManIdle.setScale(2, RoundingMode.HALF_UP).toPlainString());
        s.put("equipment_types", equipmentRows.size());
        s.put("equipment_closing_total", totalEquipment);
        s.put("equipment_working_hours", totalEqWorking.setScale(2, RoundingMode.HALF_UP).toPlainString());
        s.put("equipment_idle_hours", totalEqIdle.setScale(2, RoundingMode.HALF_UP).toPlainString());
        s.put("dpr_rows_aggregated", dprCount);
        s.put("currency", "OMR");
        return s;
    }

    private ObjectNode emptySummary() {
        ObjectNode s = objectMapper.createObjectNode();
        s.put("manpower_trades", 0);
        s.put("equipment_types", 0);
        s.put("dpr_rows_aggregated", 0);
        return s;
    }

    private ObjectNode renderRange(LocalDate from, LocalDate to) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("from", from.toString());
        n.put("to", to.toString());
        return n;
    }

    private static BigDecimal parseBd(String s) {
        try {
            return new BigDecimal(s);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Per-(resource, day) bucket. {@code nos} is the sum of deployed counts across DPR rows for
     * that resource on that day; hour fields sum the matching child-row fields without scaling
     * by nos — the same convention the site office uses on its paper Daily Balance Sheet.
     */
    private static final class ResourceDayBucket {
        int nos;
        BigDecimal workingHours = BigDecimal.ZERO;
        BigDecimal idleHours = BigDecimal.ZERO;
        BigDecimal otHours = BigDecimal.ZERO;
        BigDecimal breakdownHours = BigDecimal.ZERO;

        void addManpower(DprManpower r) {
            if (r.getNos() != null) nos += r.getNos();
            workingHours = workingHours.add(nz(r.getWorkingHours()));
            idleHours = idleHours.add(nz(r.getIdleHours()));
            otHours = otHours.add(nz(r.getOtHours()));
        }

        void addEquipment(DprEquipment r) {
            if (r.getNos() != null) nos += r.getNos();
            workingHours = workingHours.add(nz(r.getWorkingHours()));
            idleHours = idleHours.add(nz(r.getIdleHours()));
            breakdownHours = breakdownHours.add(nz(r.getBreakdownHours()));
        }

        private static BigDecimal nz(BigDecimal v) {
            return v == null ? BigDecimal.ZERO : v;
        }
    }
}
