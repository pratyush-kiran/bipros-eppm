package com.bipros.cost.application.service;

import com.bipros.cost.domain.entity.FinancialPeriod;
import com.bipros.cost.domain.repository.FinancialPeriodRepository;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazily generates the {@link FinancialPeriod} rows for a project by walking the calendar
 * quarters that overlap the project's {@code planned_start_date → planned_finish_date} window.
 *
 * <p>Period naming: {@code "<projectCode> Q<n> <year>"} (e.g. {@code "HIGHWAY-301 Q2 2026"}).
 * Quarter boundaries follow the calendar year (Jan-Mar = Q1, Apr-Jun = Q2, Jul-Sep = Q3,
 * Oct-Dec = Q4). Each period is {@code QUARTERLY}; {@code isClosed} is true iff the entire
 * quarter ended before today.
 *
 * <p>Idempotent — re-running adds only newly-needed quarters and never deletes existing rows
 * (which would orphan their {@code StorePeriodPerformance} data). If the project shrinks its
 * date range, old periods stick around as harmless zero rows.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FinancialPeriodAutoGenerator {

    private final ProjectRepository projectRepository;
    private final FinancialPeriodRepository financialPeriodRepository;

    /**
     * Per-project intrinsic locks to serialize concurrent calls to {@link #ensureForProject} for
     * the same project. Two parallel HTTP requests (Cost-tab S-Curve + Period Breakdown) both
     * trigger ensure; with {@code REQUIRES_NEW} transactions each sees the other's pre-commit
     * empty state and inserts duplicate quarters. Java-level synchronization on a per-project
     * lock prevents that without needing a DB unique constraint + retry/catch dance.
     *
     * <p>Single-JVM only — if this app ever runs clustered, swap for a DB advisory lock.
     */
    private final ConcurrentHashMap<UUID, Object> projectLocks = new ConcurrentHashMap<>();

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureForProject(UUID projectId) {
        if (projectId == null) return;
        Object lock = projectLocks.computeIfAbsent(projectId, k -> new Object());
        synchronized (lock) {
            ensureLocked(projectId);
        }
    }

    private void ensureLocked(UUID projectId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null) return;
        LocalDate plannedStart = project.getPlannedStartDate();
        LocalDate plannedFinish = project.getPlannedFinishDate();
        if (plannedStart == null || plannedFinish == null) {
            log.debug("FinancialPeriodAutoGenerator: project {} has no planned dates — skipping", projectId);
            return;
        }
        if (plannedFinish.isBefore(plannedStart)) return;

        String code = project.getCode() != null ? project.getCode() : "PROJ";
        LocalDate today = LocalDate.now();

        // Build the set of (year, quarter) tuples spanning the project window.
        List<int[]> targetQuarters = quartersInRange(plannedStart, plannedFinish);

        // Existing rows keyed by sortOrder so we can skip duplicates.
        List<FinancialPeriod> existing = financialPeriodRepository.findByProjectIdOrderBySortOrderAsc(projectId);
        Set<Integer> existingSortOrders = new HashSet<>();
        for (FinancialPeriod p : existing) {
            if (p.getSortOrder() != null) existingSortOrders.add(p.getSortOrder());
        }

        int created = 0;
        for (int[] yq : targetQuarters) {
            int year = yq[0];
            int quarter = yq[1];
            int sortOrder = year * 10 + quarter;   // stable, unique ordering: 2026×10 + 2 = 20262
            if (existingSortOrders.contains(sortOrder)) continue;

            int firstMonth = (quarter - 1) * 3 + 1;        // Q1→1, Q2→4, Q3→7, Q4→10
            LocalDate start = LocalDate.of(year, firstMonth, 1);
            YearMonth lastMonthYm = YearMonth.of(year, firstMonth + 2);
            LocalDate end = lastMonthYm.atEndOfMonth();

            FinancialPeriod fp = new FinancialPeriod();
            fp.setProjectId(projectId);
            fp.setName(code + " Q" + quarter + " " + year);
            fp.setStartDate(start);
            fp.setEndDate(end);
            fp.setPeriodType("QUARTERLY");
            fp.setIsClosed(end.isBefore(today));
            fp.setSortOrder(sortOrder);
            financialPeriodRepository.save(fp);
            created++;
        }
        if (created > 0) {
            log.info("FinancialPeriodAutoGenerator: project {} ({}): created {} quarter(s) spanning {} → {}",
                    projectId, code, created, plannedStart, plannedFinish);
        }
    }

    /** All (year, quarter) tuples whose quarter overlaps the range. */
    private static List<int[]> quartersInRange(LocalDate from, LocalDate to) {
        List<int[]> out = new java.util.ArrayList<>();
        int startYear = from.getYear();
        int startQuarter = (from.getMonthValue() - 1) / 3 + 1;
        int endYear = to.getYear();
        int endQuarter = (to.getMonthValue() - 1) / 3 + 1;
        int y = startYear;
        int q = startQuarter;
        while (y < endYear || (y == endYear && q <= endQuarter)) {
            out.add(new int[]{y, q});
            q++;
            if (q > 4) { q = 1; y++; }
        }
        return out;
    }
}
