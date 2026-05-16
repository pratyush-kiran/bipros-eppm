package com.bipros.api.config.seeder;

import com.bipros.activity.domain.model.Activity;
import com.bipros.activity.domain.repository.ActivityRepository;
import com.bipros.api.config.seeder.OmanDemoWorkbookReader.PerformanceSnapshotRow;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprApprovalStatus;
import com.bipros.project.domain.model.DprManpower;
import com.bipros.project.domain.model.ManpowerCategory;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.model.SafetyIncidentType;
import com.bipros.project.domain.model.Shift;
import com.bipros.project.domain.model.Side;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprManpowerRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.security.domain.model.User;
import com.bipros.security.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Generates back-dated DPRs from the three SC180 performance snapshots (Oct 2024,
 * Nov 2024, Jan 2025). The AI assistant's history tooling (and the ClickHouse
 * {@code fact_dpr_logs} table) only reads DPR-shape data; embedding the snapshots as
 * DPRs lets the assistant answer "performance trend over the last 6 months" without
 * inventing new entities.
 *
 * <p>One DPR per snapshot date per trade row. The trade → activity heuristic uses
 * substring matches against the activity name; if no activity matches we fall back to
 * the first earthworks activity so every snapshot row produces a DPR.
 *
 * <p>Idempotent: if a DPR already exists for {@code (project_id, report_date)} we treat
 * the snapshot as already-ingested and skip that date.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("seed")
@Order(205)
public class OmanDemoHistoricalPerformanceSeeder implements CommandLineRunner {

    private static final String SUPERVISOR_LABEL = "Historical Snapshot";

    private final ProjectRepository projectRepository;
    private final ActivityRepository activityRepository;
    private final DailyProgressReportRepository dprRepository;
    private final DprManpowerRepository manpowerRepository;
    private final UserRepository userRepository;
    private final OmanDemoWorkbookReader reader;
    private final OmanDemoStaffDirectory directory;

    @Override
    public void run(String... args) {
        if (!reader.performanceAvailable()) {
            log.info("[oman-demo history] no SC180 performance workbooks on classpath; skipping");
            return;
        }

        Optional<Project> projectOpt =
                projectRepository.findByCode(OmanDemoProjectSeeder.PROJECT_CODE);
        if (projectOpt.isEmpty()) {
            log.warn("[oman-demo history] project {} not found, skipping",
                    OmanDemoProjectSeeder.PROJECT_CODE);
            return;
        }
        Project project = projectOpt.get();

        List<PerformanceSnapshotRow> rows;
        try {
            rows = reader.readPerformanceSnapshots();
        } catch (Exception e) {
            log.warn("[oman-demo history] failed to read snapshots: {}", e.getMessage());
            return;
        }
        if (rows.isEmpty()) {
            log.info("[oman-demo history] performance files present but no rows parsed");
            return;
        }

        List<Activity> activities = activityRepository.findByProjectId(project.getId());
        if (activities.isEmpty()) {
            log.warn("[oman-demo history] no activities for {}, skipping",
                    OmanDemoProjectSeeder.PROJECT_CODE);
            return;
        }

        // Index existing DPRs to detect already-ingested snapshot dates.
        Map<LocalDate, Boolean> existingByDate = new HashMap<>();
        for (DailyProgressReport d :
                dprRepository.findByProjectIdOrderByReportDateAscIdAsc(project.getId())) {
            existingByDate.put(d.getReportDate(), true);
        }

        UUID pmUserId = directory.anyPm();
        String pmDisplay = pmUserId == null ? SUPERVISOR_LABEL
                : userRepository.findById(pmUserId)
                        .map(OmanDemoHistoricalPerformanceSeeder::displayName)
                        .orElse(SUPERVISOR_LABEL);

        int dprCount = 0;
        int mpCount = 0;
        for (PerformanceSnapshotRow row : rows) {
            if (existingByDate.containsKey(row.snapshotDate())) {
                continue;
            }
            Activity target = resolveActivityForTrade(activities, row.trade());
            if (target == null) continue;

            DailyProgressReport dpr = DailyProgressReport.builder()
                    .projectId(project.getId())
                    .reportDate(row.snapshotDate())
                    .supervisorName(truncate(pmDisplay, 150))
                    .supervisorUserId(pmUserId)
                    .activityId(target.getId())
                    .activityName(truncate(target.getName(), 150))
                    .wbsNodeId(target.getWbsNodeId())
                    .boqItemNo(truncate(target.getCode(), 20))
                    .unit("Manday")
                    .qtyExecuted(row.actualMandays() != null
                            ? row.actualMandays() : BigDecimal.ZERO)
                    .weatherCondition("Clear")
                    .side(Side.LHS)
                    .shift(Shift.DAY)
                    .approvalStatus(DprApprovalStatus.APPROVED)
                    .contractorName("Sandou Construction")
                    .safetyIncidentType(SafetyIncidentType.NONE)
                    .remarks("Historical performance snapshot (back-dated import)")
                    .build();
            DailyProgressReport saved;
            try {
                saved = dprRepository.save(dpr);
            } catch (Exception e) {
                log.warn("[oman-demo history] DPR save failed for {} / {}: {}",
                        row.snapshotDate(), row.trade(), e.getMessage());
                continue;
            }
            dprCount++;

            // dpr_manpower.working_hours is precision 6, scale 2 (max 9999.99). The
            // performance snapshot is cumulative mandays for the trade across the project
            // to date, which can comfortably exceed that when multiplied by 8 hours.
            // Store a representative single-day equivalent (8h) so the row is valid; the
            // full cumulative mandays go into nos for analytics.
            BigDecimal workingHours =
                    row.actualMandays() != null && row.actualMandays().signum() > 0
                            ? BigDecimal.valueOf(8) : BigDecimal.ZERO;
            int nos = row.actualMandays() != null
                    ? row.actualMandays().intValue() : 0;
            DprManpower mp = DprManpower.builder()
                    .dprId(saved.getId())
                    .trade(truncate(row.trade(), 100))
                    .category(categorise(row.trade()))
                    .nos(nos > 0 ? nos : (row.actualNos() == null ? 1 : row.actualNos().intValue()))
                    .workingHours(workingHours)
                    .otHours(BigDecimal.ZERO)
                    .idleHours(BigDecimal.ZERO)
                    .unitRate(row.mmRate())
                    .unitRateBasis("MANDAY")
                    .lineCost(row.costImplication())
                    .contractorName("Sandou Construction")
                    .remarks(row.actualMandays() != null
                            ? "cumulative_mandays=" + row.actualMandays().stripTrailingZeros().toPlainString()
                            : null)
                    .build();
            try {
                manpowerRepository.save(mp);
                mpCount++;
            } catch (Exception e) {
                log.warn("[oman-demo history] manpower save failed for {}: {}",
                        row.trade(), e.getMessage());
            }
        }

        log.info("[oman-demo history] back-dated {} DPRs and {} manpower rows from "
                + "{} performance snapshots", dprCount, mpCount, rows.size());
    }

    /** Pick an Activity that semantically matches the trade label. */
    private Activity resolveActivityForTrade(List<Activity> activities, String trade) {
        if (trade == null) return activities.get(0);
        String t = trade.toLowerCase(Locale.ROOT);

        String[] keywords;
        if (t.contains("mason") || t.contains("carpenter") || t.contains("concrete")) {
            keywords = new String[]{"concrete", "culvert", "retaining", "barrier", "structure"};
        } else if (t.contains("steel") || t.contains("rebar") || t.contains("fixer")) {
            keywords = new String[]{"steel", "rebar", "structure", "concrete"};
        } else if (t.contains("electrician") || t.contains("plumber") || t.contains("a/c")) {
            keywords = new String[]{"electrical", "plumbing", "service", "ancillary"};
        } else if (t.contains("survey")) {
            keywords = new String[]{"survey", "setting", "investigation"};
        } else if (t.contains("operator") || t.contains("driver")
                || t.contains("rigger") || t.contains("excavator")) {
            keywords = new String[]{"excavation", "fill", "embankment", "clearing"};
        } else {
            keywords = new String[]{"clearing", "earthwork", "excavation"};
        }

        for (String kw : keywords) {
            for (Activity a : activities) {
                if (a.getName() != null
                        && a.getName().toLowerCase(Locale.ROOT).contains(kw)) {
                    return a;
                }
            }
        }
        return activities.get(0);
    }

    private static ManpowerCategory categorise(String trade) {
        if (trade == null) return ManpowerCategory.SKILLED;
        String t = trade.toLowerCase(Locale.ROOT).trim();
        if (t.contains("helper") || t.contains("watchman") || t.contains("tyre")) {
            return ManpowerCategory.UNSKILLED;
        }
        if (t.contains("chargehand") || t.contains("rigger")
                || t.contains("scaffolder") || t.contains("painter")) {
            return ManpowerCategory.SEMI_SKILLED;
        }
        return ManpowerCategory.SKILLED;
    }

    private static String displayName(User u) {
        String first = u.getFirstName();
        String last = u.getLastName();
        if (first == null && last == null) return u.getUsername();
        String composed = ((first == null ? "" : first) + " "
                + (last == null ? "" : last)).trim();
        return composed.isEmpty() ? u.getUsername() : composed;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
