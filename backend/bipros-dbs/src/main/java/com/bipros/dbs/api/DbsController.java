package com.bipros.dbs.api;

import com.bipros.common.dto.ApiResponse;
import com.bipros.dbs.api.dto.CumulativeDaysResponse;
import com.bipros.dbs.api.dto.DbsCmDayResponse;
import com.bipros.dbs.api.dto.DbsCmSummaryDto;
import com.bipros.dbs.api.dto.DbsEngineerDayResponse;
import com.bipros.dbs.api.dto.DbsEngineerPeriodResponse;
import com.bipros.dbs.api.dto.DbsProjectDayResponse;
import com.bipros.dbs.api.dto.DbsProjectPeriodResponse;
import com.bipros.dbs.api.dto.DbsSupervisorDayResponse;
import com.bipros.dbs.api.dto.DbsSupervisorPeriodResponse;
import com.bipros.dbs.api.dto.DbsSupervisorSummaryDto;
import com.bipros.dbs.api.dto.EquipmentRegisterResponse;
import com.bipros.dbs.api.dto.ManpowerRegisterResponse;
import com.bipros.dbs.domain.model.DbsDailyProject;
import com.bipros.dbs.export.DbsExcelWriter;
import com.bipros.dbs.export.DbsPdfWriter;
import com.bipros.dbs.service.DbsAggregationService;
import com.bipros.dbs.service.DbsQueryService;
import com.bipros.dbs.service.RegisterAggregationService;
import com.bipros.project.application.service.ProjectTeamService;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Read API + admin recompute endpoints for the Daily Balance Sheet aggregates.
 *
 * <p>Each GET accepts an optional {@code periodType} of {@code DAY | WEEK | MONTH}:
 * omit it (or pass DAY) for the single-day response shape, pass WEEK/MONTH for the
 * period envelope with totals + zero-filled daily rows. ISO Mon–Sun week, calendar
 * month bounds.
 */
@Slf4j
@RestController
@RequestMapping("/v1/projects/{projectId}/dbs")
@RequiredArgsConstructor
public class DbsController {

    private static final MediaType XLSX_MEDIA_TYPE =
        MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final DbsQueryService queryService;
    private final DbsAggregationService aggregationService;
    private final DailyProgressReportRepository dprRepository;
    private final ProjectTeamService projectTeamService;
    private final DbsExcelWriter excelWriter;
    private final DbsPdfWriter pdfWriter;
    private final RegisterAggregationService registerAggregationService;

    // ── supervisor ──────────────────────────────────────────────────────────────

    @GetMapping("/supervisor/{supervisorUserId}")
    public ResponseEntity<ApiResponse<?>> getSupervisor(
        @PathVariable UUID projectId,
        @PathVariable UUID supervisorUserId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @RequestParam(required = false) String periodType) {

        if (isPeriod(periodType)) {
            DbsSupervisorPeriodResponse body = queryService
                .getSupervisorPeriod(projectId, supervisorUserId, periodType, date);
            return ResponseEntity.ok(ApiResponse.ok(body));
        }
        DbsSupervisorDayResponse body = queryService.getSupervisorDay(projectId, supervisorUserId, date);
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    // ── engineer ────────────────────────────────────────────────────────────────

    @GetMapping("/engineer/{engineerUserId}")
    public ResponseEntity<ApiResponse<?>> getEngineer(
        @PathVariable UUID projectId,
        @PathVariable UUID engineerUserId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @RequestParam(required = false) String periodType) {

        if (isPeriod(periodType)) {
            DbsEngineerPeriodResponse body = queryService
                .getEngineerPeriod(projectId, engineerUserId, periodType, date);
            return ResponseEntity.ok(ApiResponse.ok(body));
        }
        DbsEngineerDayResponse body = queryService.getEngineerDay(projectId, engineerUserId, date);
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    // ── construction manager (CM) ───────────────────────────────────────────────

    /**
     * Single-day or period DBS payload for one Construction Manager.
     *
     * <p>{@code periodType=DAY} (default) returns the {@code dbs_daily_cm} row directly;
     * {@code WEEK} / {@code MONTH} return the summed rollup over the ISO Mon–Sun week or
     * calendar month containing {@code date}.
     */
    @GetMapping("/cm/{cmUserId}")
    public ResponseEntity<ApiResponse<DbsCmDayResponse>> getCmDay(
        @PathVariable UUID projectId,
        @PathVariable UUID cmUserId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @RequestParam(required = false, defaultValue = "DAY") String periodType) {

        DbsCmDayResponse body = isPeriod(periodType)
            ? queryService.getCmPeriod(projectId, cmUserId, periodType, date)
            : queryService.getCmDay(projectId, cmUserId, date);
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    /**
     * Compact per-CM summary list for the date. Powers the PM tab's CM drill-down menu.
     */
    @GetMapping("/cms")
    public ResponseEntity<ApiResponse<List<DbsCmSummaryDto>>> listCms(
        @PathVariable UUID projectId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        return ResponseEntity.ok(ApiResponse.ok(queryService.listCmsForDay(projectId, date)));
    }

    // ── project (PM tab) ────────────────────────────────────────────────────────

    @GetMapping("/project")
    public ResponseEntity<ApiResponse<?>> getProject(
        @PathVariable UUID projectId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @RequestParam(required = false) String periodType) {

        if (isPeriod(periodType)) {
            DbsProjectPeriodResponse body = queryService.getProjectPeriod(projectId, periodType, date);
            return ResponseEntity.ok(ApiResponse.ok(body));
        }
        DbsProjectDayResponse body = queryService.getProjectDay(projectId, date);
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    @GetMapping("/supervisors")
    public ResponseEntity<ApiResponse<List<DbsSupervisorSummaryDto>>> listSupervisors(
        @PathVariable UUID projectId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        return ResponseEntity.ok(ApiResponse.ok(queryService.listSupervisorsForDay(projectId, date)));
    }

    /**
     * Returns soft health-check alert codes (e.g. NEGATIVE_CONTRIBUTION, RUNAWAY_FUEL)
     * for the project rollup on a given date. Alerts are also embedded on the
     * project-day response, but a dedicated endpoint keeps the UI banner queryable
     * cheaply and independently of the full DBS payload.
     */
    @GetMapping("/alerts")
    public ResponseEntity<ApiResponse<List<String>>> getAlerts(
        @PathVariable UUID projectId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        return ResponseEntity.ok(ApiResponse.ok(queryService.getAlertsForProjectDay(projectId, date)));
    }

    // ── equipment & manpower register (Phase 5) ─────────────────────────────────

    /**
     * Equipment Deployment Register pivoted for the UI: one row per equipment type,
     * each broken down by CM × shift. Optionally filtered to a single CM.
     */
    @GetMapping("/register/equipment")
    public ResponseEntity<ApiResponse<EquipmentRegisterResponse>> getEquipmentRegister(
        @PathVariable UUID projectId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @RequestParam(required = false) UUID cmUserId) {

        EquipmentRegisterResponse body = registerAggregationService
            .getEquipmentRegister(projectId, date, cmUserId);
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    /**
     * Manpower Deployment Register pivoted for the UI: one row per trade, each broken
     * down by CM × shift. Optionally filtered to a single CM.
     */
    @GetMapping("/register/manpower")
    public ResponseEntity<ApiResponse<ManpowerRegisterResponse>> getManpowerRegister(
        @PathVariable UUID projectId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @RequestParam(required = false) UUID cmUserId) {

        ManpowerRegisterResponse body = registerAggregationService
            .getManpowerRegister(projectId, date, cmUserId);
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    /**
     * Phase 6 — cumulative equipment-days / manpower-days summed across all dates
     * {@code <= asOf}. Optionally restricted to a single CM's downline.
     */
    @GetMapping("/register/cumulative")
    public ResponseEntity<ApiResponse<CumulativeDaysResponse>> getCumulative(
        @PathVariable UUID projectId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
        @RequestParam(required = false) UUID cmUserId) {

        CumulativeDaysResponse body = registerAggregationService
            .cumulative(projectId, asOf, cmUserId);
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    // ── admin recompute ─────────────────────────────────────────────────────────

    @PostMapping("/recompute")
    public ResponseEntity<ApiResponse<DbsDailyProject>> recompute(
        @PathVariable UUID projectId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        log.info("Admin recompute requested projectId={} date={}", projectId, date);
        DbsDailyProject project = recomputeProjectDay(projectId, date);
        return ResponseEntity.ok(ApiResponse.ok(project));
    }

    @PostMapping("/recompute-range")
    public ResponseEntity<ApiResponse<List<DbsDailyProject>>> recomputeRange(
        @PathVariable UUID projectId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        log.info("Admin recompute-range requested projectId={} from={} to={}", projectId, from, to);
        if (to.isBefore(from)) {
            LocalDate tmp = from;
            from = to;
            to = tmp;
        }
        List<DbsDailyProject> results = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            results.add(recomputeProjectDay(projectId, d));
        }
        return ResponseEntity.ok(ApiResponse.ok(results));
    }

    // ── exports ─────────────────────────────────────────────────────────────────

    /**
     * Excel export of the DBS for a date.
     *
     * <p>{@code level=PM} (default) builds the full workbook — Summary-Financial sheet,
     * one PRE sheet per engineer, one Costing-Report sheet per supervisor. {@code level=SUPERVISOR}
     * builds a single supervisor sheet and requires {@code supervisorUserId}. Returned
     * as a binary attachment (not wrapped in {@code ApiResponse}).
     */
    @GetMapping("/export.xlsx")
    public ResponseEntity<byte[]> exportExcel(
        @PathVariable UUID projectId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @RequestParam(required = false, defaultValue = "PM") String level,
        @RequestParam(required = false) UUID supervisorUserId) {

        String lvl = normaliseLevel(level);
        byte[] body;
        if ("SUPERVISOR".equals(lvl)) {
            if (supervisorUserId == null) {
                return ResponseEntity.badRequest().build();
            }
            body = excelWriter.writeSupervisorReport(projectId, supervisorUserId, date);
        } else {
            body = excelWriter.writePmReport(projectId, date);
        }
        String filename = sanitiseFilename("dbs-" + date + "-" + lvl + ".xlsx");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(XLSX_MEDIA_TYPE);
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(body.length);
        return new ResponseEntity<>(body, headers, 200);
    }

    /**
     * PDF export — same shape as {@link #exportExcel} but renders via openhtmltopdf.
     */
    @GetMapping("/export.pdf")
    public ResponseEntity<byte[]> exportPdf(
        @PathVariable UUID projectId,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @RequestParam(required = false, defaultValue = "PM") String level,
        @RequestParam(required = false) UUID supervisorUserId) {

        String lvl = normaliseLevel(level);
        byte[] body;
        if ("SUPERVISOR".equals(lvl)) {
            if (supervisorUserId == null) {
                return ResponseEntity.badRequest().build();
            }
            body = pdfWriter.writeSupervisorReport(projectId, supervisorUserId, date);
        } else {
            body = pdfWriter.writePmReport(projectId, date);
        }
        String filename = sanitiseFilename("dbs-" + date + "-" + lvl + ".pdf");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(body.length);
        return new ResponseEntity<>(body, headers, 200);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private DbsDailyProject recomputeProjectDay(UUID projectId, LocalDate date) {
        // Bug 5/7 fix: when no DPRs exist for this date, do NOT synthesize a
        // null-supervisor row. The previous behaviour created a placeholder row
        // that inflated dprCount to 1 on empty dates and polluted the engineer
        // aggregation. Skipping the call lets recomputeProjectDay write an
        // honest empty row (supRows.isEmpty() ⇒ dprCount = 0).
        List<UUID> supervisorIds = dprRepository.findDistinctSupervisorUserIdsByProjectAndDate(projectId, date);
        Set<UUID> engineerIds = new LinkedHashSet<>();
        Set<UUID> cmIds = new LinkedHashSet<>();
        for (UUID sup : supervisorIds) {
            aggregationService.recomputeSupervisorDay(projectId, sup, date);
            // Bug 8 fix: admin recompute previously skipped the engineer-day rollup,
            // so the engineer tab stayed stale until the next event-driven recompute.
            // Resolve each supervisor's engineer-of-record and queue a recompute.
            projectTeamService.resolveEngineerFor(projectId, sup)
                .ifPresent(engineerIds::add);
            // Phase 4: same fan-out for the CM tier — admin recompute must refresh
            // dbs_daily_cm rows alongside engineer + project.
            projectTeamService.resolveCmFor(projectId, sup)
                .ifPresent(cmIds::add);
        }
        for (UUID eng : engineerIds) {
            aggregationService.recomputeEngineerDay(projectId, eng, date);
        }
        for (UUID cm : cmIds) {
            aggregationService.recomputeCmDay(projectId, cm, date);
        }
        return aggregationService.recomputeProjectDay(projectId, date);
    }

    private static boolean isPeriod(String periodType) {
        if (periodType == null) return false;
        String p = periodType.trim().toUpperCase();
        return p.equals("WEEK") || p.equals("MONTH");
    }

    private static String normaliseLevel(String level) {
        if (level == null) return "PM";
        String l = level.trim().toUpperCase(Locale.ROOT);
        return l.equals("SUPERVISOR") ? "SUPERVISOR" : "PM";
    }

    /** Strip path / disposition-hostile characters so filename headers stay well-formed. */
    private static String sanitiseFilename(String raw) {
        return raw.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "-");
    }
}
