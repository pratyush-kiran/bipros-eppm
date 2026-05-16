package com.bipros.analytics.etl.backfill;

import com.bipros.analytics.etl.batch.DimensionSyncJob;
import com.bipros.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/analytics")
@PreAuthorize("hasPermission(null, 'ADMIN_SETTINGS.UPDATE')")
@RequiredArgsConstructor
@Slf4j
public class AnalyticsBackfillController {

    private final AnalyticsBackfillService backfillService;
    private final DimensionSyncJob dimensionSyncJob;

    @PostMapping("/backfill")
    public ResponseEntity<ApiResponse<AnalyticsBackfillService.BackfillReport>> backfill(
            @RequestParam(defaultValue = "all") String fact,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(required = false) UUID projectId) {

        log.info("Admin backfill request: fact={} from={} to={} projectId={}", fact, from, to, projectId);

        AnalyticsBackfillService.BackfillReport report = switch (fact) {
            case "dpr" -> new AnalyticsBackfillService.BackfillReport(
                    backfillService.backfillDpr(from, to, projectId), 0, 0, 0, 0);
            case "activity" -> new AnalyticsBackfillService.BackfillReport(
                    0, backfillService.backfillActivityProgress(from, to, projectId), 0, 0, 0);
            case "cost" -> new AnalyticsBackfillService.BackfillReport(
                    0, 0, backfillService.backfillCost(from, to, projectId), 0, 0);
            case "evm" -> new AnalyticsBackfillService.BackfillReport(
                    0, 0, 0, backfillService.backfillEvm(from, to, projectId), 0);
            case "risk" -> new AnalyticsBackfillService.BackfillReport(
                    0, 0, 0, 0, backfillService.backfillRiskSnapshot(from, to, projectId));
            default -> backfillService.backfillAll(from, to, projectId);
        };

        return ResponseEntity.ok(ApiResponse.ok(report));
    }

    /**
     * Manually triggers the nightly {@link DimensionSyncJob}. Useful after a
     * local ClickHouse re-init wipes the dim tables — saves the user from
     * waiting for the 01:30 UTC cron. Refreshes every dim_* from OLTP.
     */
    @PostMapping("/resync-dimensions")
    public ResponseEntity<ApiResponse<Map<String, String>>> resyncDimensions() {
        log.info("Admin manual dim resync triggered");
        long start = System.currentTimeMillis();
        dimensionSyncJob.run();
        long elapsed = System.currentTimeMillis() - start;
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "status", "ok",
                "elapsed_ms", String.valueOf(elapsed))));
    }

    /**
     * Targeted backfill for the role-owned rate book + supervisor User dimensions added in the
     * 2026-05-13 role-rate rollout. Idempotent — re-runs cleanly via ReplacingMergeTree(_version).
     * Use after the Phase-2 ClickHouse DDL lands (or after a CH re-init) to populate the new
     * dim_resource_role / dim_*_role_*_rate / dim_project_rate_override / dim_work_activity /
     * dim_productivity_norm / dim_user tables along with supervisor_user_id + work_activity_id
     * columns on dim_activity. Wraps {@link DimensionSyncJob#runBackfill()}.
     */
    @PostMapping("/backfill-role-model")
    public ResponseEntity<ApiResponse<Map<String, String>>> backfillRoleModel() {
        log.info("Admin backfill-role-model triggered");
        long start = System.currentTimeMillis();
        dimensionSyncJob.runBackfill();
        long elapsed = System.currentTimeMillis() - start;
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "status", "ok",
                "elapsed_ms", String.valueOf(elapsed),
                "note", "Synced resource roles, role rates/variants, project rate overrides, "
                        + "work activities, productivity norms, and users to ClickHouse. "
                        + "dim_activity rows now carry supervisor_user_id + work_activity_id "
                        + "columns. Re-run anytime; ReplacingMergeTree(_version) dedupes.")));
    }
}
