package com.bipros.analytics.read;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.analytics.etl.AnalyticsEtlService;
import com.bipros.analytics.store.ClickHouseTemplate;
import com.bipros.baseline.domain.Baseline;
import com.bipros.baseline.infrastructure.repository.BaselineRepository;
import com.bipros.common.dto.ApiResponse;
import com.bipros.common.security.ProjectAccessGuard;
import com.bipros.cost.domain.entity.CostAccount;
import com.bipros.cost.domain.repository.CostAccountRepository;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.model.WbsNode;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.project.domain.repository.WbsNodeRepository;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.repository.ResourceRepository;
import com.bipros.scheduling.domain.model.ScheduleResult;
import com.bipros.scheduling.domain.repository.ScheduleResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1/analytics")
@RequiredArgsConstructor
@Slf4j
public class AnalyticsReadController {

    /**
     * Tables monitored by {@link #health()} and replayed by
     * {@link #resyncProject(UUID)}. Listed once so the two endpoints agree on what
     * "everything" means, and so new dims can be added in one place.
     */
    private static final List<String> MONITORED_TABLES = List.of(
            "dim_project",
            "dim_wbs",
            "dim_activity",
            "dim_resource",
            "dim_cost_account",
            "dim_baseline",
            "dim_schedule_run",
            "dim_contract",
            "dim_calendar",
            "dim_risk",
            "dim_permit_type",
            "dim_permit",
            "dim_labour_designation",
            "fact_activity_progress_daily",
            "fact_resource_usage_daily",
            "fact_cost_daily",
            "fact_evm_daily",
            "fact_dpr_logs",
            "fact_dpr_manpower_daily",
            "fact_dpr_equipment_daily",
            "fact_dpr_material_daily",
            "fact_risk_snapshot_daily",
            "fact_permit_lifecycle",
            "fact_labour_daily"
    );

    private final ClickHouseTemplate clickHouse;
    private final ProjectAccessGuard projectAccess;
    private final AnalyticsEtlService etl;
    private final ProjectRepository projectRepository;
    private final WbsNodeRepository wbsNodeRepository;
    private final ActivityRepository activityRepository;
    private final ResourceRepository resourceRepository;
    private final CostAccountRepository costAccountRepository;
    private final BaselineRepository baselineRepository;
    private final ScheduleResultRepository scheduleResultRepository;

    @GetMapping("/kpi/{projectId}")
    @PreAuthorize("@projectAccess.canRead(#projectId)")
    public ResponseEntity<ApiResponse<ProjectKpiResponse>> getProjectKpi(
            @PathVariable UUID projectId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {

        if (from == null) from = LocalDate.now().minusMonths(1);
        if (to == null) to = LocalDate.now();

        String sql = """
            SELECT sum(total_actual) as ac,
                   sum(total_planned) as pv,
                   sum(total_earned) as ev,
                   count() as row_count
            FROM bipros_analytics.fact_cost_daily
            WHERE project_id = :projectId
              AND date BETWEEN :from AND :to
            """;

        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        params.put("from", from);
        params.put("to", to);

        List<Map<String, Object>> rows = clickHouse.queryForList(sql, params);
        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);

        ProjectKpiResponse kpi = new ProjectKpiResponse(
                projectId, from, to,
                toBigDecimal(row.get("ac")),
                toBigDecimal(row.get("pv")),
                toBigDecimal(row.get("ev")),
                row.get("row_count") != null ? ((Number) row.get("row_count")).longValue() : 0L
        );

        return ResponseEntity.ok(ApiResponse.ok(kpi));
    }

    @GetMapping("/evm/{projectId}")
    @PreAuthorize("@projectAccess.canRead(#projectId)")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getEvmSeries(
            @PathVariable UUID projectId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {

        if (from == null) from = LocalDate.now().minusMonths(3);
        if (to == null) to = LocalDate.now();

        String sql = """
            SELECT date, sum(pv) as pv, sum(ev) as ev, sum(ac) as ac,
                   sum(cv) as cv, sum(sv) as sv,
                   avg(cpi) as cpi, avg(spi) as spi
            FROM bipros_analytics.fact_evm_daily
            WHERE project_id = :projectId
              AND date BETWEEN :from AND :to
            GROUP BY date
            ORDER BY date
            """;

        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        params.put("from", from);
        params.put("to", to);

        return ResponseEntity.ok(ApiResponse.ok(clickHouse.queryForList(sql, params)));
    }

    @GetMapping("/scurve/{projectId}")
    @PreAuthorize("@projectAccess.canRead(#projectId)")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getScurve(
            @PathVariable UUID projectId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {

        if (from == null) from = LocalDate.now().minusMonths(6);
        if (to == null) to = LocalDate.now();

        String sql = """
            SELECT date, sum(pv) as pv, sum(ev) as ev, sum(ac) as ac
            FROM bipros_analytics.fact_evm_daily
            WHERE project_id = :projectId
              AND date BETWEEN :from AND :to
            GROUP BY date
            ORDER BY date
            """;

        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);
        params.put("from", from);
        params.put("to", to);

        return ResponseEntity.ok(ApiResponse.ok(clickHouse.queryForList(sql, params)));
    }

    @GetMapping("/risk-heatmap/{projectId}")
    @PreAuthorize("@projectAccess.canRead(#projectId)")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getRiskHeatmap(
            @PathVariable UUID projectId) {

        String sql = """
            SELECT risk_id, date, probability, impact_cost, impact_days, rag, status,
                   monte_carlo_p50, monte_carlo_p80, monte_carlo_p95
            FROM bipros_analytics.fact_risk_snapshot_daily
            WHERE project_id = :projectId
              AND date = (SELECT max(date) FROM bipros_analytics.fact_risk_snapshot_daily WHERE project_id = :projectId)
            ORDER BY impact_cost DESC
            """;

        Map<String, Object> params = new HashMap<>();
        params.put("projectId", projectId);

        return ResponseEntity.ok(ApiResponse.ok(clickHouse.queryForList(sql, params)));
    }

    /**
     * Per-table freshness snapshot. For each monitored dim/fact table, query
     * {@code count()}, {@code max(_version)}, and {@code max(event_ts|updated_at|...)}
     * via FINAL so we see post-merge truth. Used by the UI's "analytics sync" pill and
     * by ops dashboards.
     *
     * <p>Tables that don't exist yet (older schema) return {@code rowCount=null} rather
     * than failing the whole endpoint — the caller can render that as "—".
     */
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<List<TableHealth>>> health() {
        List<TableHealth> out = new ArrayList<>(MONITORED_TABLES.size());
        for (String table : MONITORED_TABLES) {
            out.add(probeTable(table));
        }
        return ResponseEntity.ok(ApiResponse.ok(out));
    }

    /**
     * Admin: replay dimension upserts for one project end-to-end. Useful when a node
     * missed events (e.g. ETL DLQ replay, post-deploy reconciliation) or when a user
     * is investigating "why is ClickHouse stale for project X".
     */
    @PostMapping("/projects/{id}/resync")
    @PreAuthorize("hasPermission(null, 'ADMIN_SETTINGS.UPDATE')")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> resyncProject(@PathVariable UUID id) {
        Map<String, Integer> touched = new LinkedHashMap<>();

        // dim_project — one row.
        Project p = projectRepository.findById(id).orElse(null);
        if (p == null) {
            return ResponseEntity.ok(ApiResponse.error(
                    "PROJECT_NOT_FOUND", "No project with id " + id));
        }
        etl.upsertProjectDimension(p);
        touched.put("dim_project", 1);

        // dim_wbs — every WBS node under the project.
        List<WbsNode> wbsNodes = wbsNodeRepository.findByProjectIdOrderBySortOrder(id);
        for (WbsNode w : wbsNodes) {
            etl.upsertWbsDimension(w);
        }
        touched.put("dim_wbs", wbsNodes.size());

        // dim_activity — every activity in this project, bulk path so we don't fan
        // out N round-trips when the project is large.
        List<Activity> activities = activityRepository.findByProjectId(id);
        etl.upsertActivitiesBulkDimension(activities);
        touched.put("dim_activity", activities.size());

        // dim_resource — resources referenced by this project's activities. The Resource
        // entity has no project_id today (resources are global), so we resolve via the
        // responsibleResourceId on each activity to get a project-relevant set.
        Set<UUID> referencedResourceIds = activities.stream()
                .map(Activity::getResponsibleResourceId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        List<Resource> resources = referencedResourceIds.isEmpty()
                ? List.of()
                : resourceRepository.findAllById(referencedResourceIds);
        for (Resource r : resources) {
            etl.upsertResourceDimension(r);
        }
        touched.put("dim_resource", resources.size());

        // dim_cost_account — CostAccount has no project_id. Replay all cost accounts
        // so the project's downstream cost queries can resolve them; this is cheap
        // (small reference table).
        List<CostAccount> costAccounts = costAccountRepository.findAll();
        for (CostAccount ca : costAccounts) {
            etl.upsertCostAccountDimension(ca);
        }
        touched.put("dim_cost_account", costAccounts.size());

        // dim_baseline — all baselines for the project.
        List<Baseline> baselines = baselineRepository.findByProjectId(id);
        for (Baseline b : baselines) {
            etl.upsertBaselineDimension(b);
        }
        touched.put("dim_baseline", baselines.size());

        // dim_schedule_run — every CPM result recorded for the project.
        List<ScheduleResult> runs = scheduleResultRepository.findByProjectId(id);
        for (ScheduleResult s : runs) {
            etl.upsertScheduleRunDimension(s);
        }
        touched.put("dim_schedule_run", runs.size());

        // dim_contract is event-only (VariationOrderApprovedEvent) — no replay here.
        touched.put("dim_contract", 0);

        log.info("Analytics resync for project {} -> {}", id, touched);
        return ResponseEntity.ok(ApiResponse.ok(touched));
    }

    private TableHealth probeTable(String table) {
        // Each ClickHouse dim/fact table has a different "freshness" column. Try the
        // most common ones in order and pick whichever the table actually exposes;
        // unknown columns return null without breaking the rest of the rollup.
        String freshExpr = freshnessExprFor(table);
        String sql = "SELECT count() AS row_count, max(_version) AS max_version, "
                + freshExpr + " AS last_updated "
                + "FROM bipros_analytics." + table + " FINAL";
        try {
            List<Map<String, Object>> rows = clickHouse.queryForList(sql, Map.of());
            if (rows.isEmpty()) {
                return new TableHealth(table, 0L, 0L, null);
            }
            Map<String, Object> r = rows.get(0);
            Long rowCount = r.get("row_count") instanceof Number n ? n.longValue() : 0L;
            Long maxVersion = r.get("max_version") instanceof Number n ? n.longValue() : 0L;
            Object lastUpdatedRaw = r.get("last_updated");
            Instant lastUpdated = lastUpdatedRaw == null ? null
                    : Instant.parse(lastUpdatedRaw.toString().replace(' ', 'T') + "Z");
            return new TableHealth(table, rowCount, maxVersion, lastUpdated);
        } catch (Exception e) {
            log.warn("Health probe failed for table {}: {}", table, e.getMessage());
            return new TableHealth(table, null, null, null);
        }
    }

    /**
     * Map each known table to its freshness column. ClickHouse rejects unknown columns
     * at parse time, so we route fact tables to {@code event_ts} (DateTime64), dim_project
     * to {@code updated_at}, and the rest to {@code toDateTime(_version / 1000)} which
     * derives a wall-clock instant from the millis epoch we wrote in.
     */
    private String freshnessExprFor(String table) {
        if (table.startsWith("fact_")) {
            return "toString(max(event_ts))";
        }
        if (table.equals("dim_project") || table.equals("dim_baseline") || table.equals("dim_contract")) {
            return "toString(max(updated_at))";
        }
        if (table.equals("dim_schedule_run")) {
            return "toString(max(calculated_at))";
        }
        return "toString(toDateTime(max(_version) / 1000))";
    }

    private BigDecimal toBigDecimal(Object val) {
        if (val == null) return BigDecimal.ZERO;
        if (val instanceof BigDecimal bd) return bd;
        if (val instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(val.toString());
    }

    public record ProjectKpiResponse(UUID projectId, LocalDate from, LocalDate to,
                                     BigDecimal totalActual, BigDecimal totalPlanned,
                                     BigDecimal totalEarned, Long rowCount) {
    }

    public record TableHealth(String table, Long rowCount, Long maxVersion, Instant lastUpdated) {
    }
}
