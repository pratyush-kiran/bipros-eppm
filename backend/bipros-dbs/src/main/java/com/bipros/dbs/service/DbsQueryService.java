package com.bipros.dbs.service;

import com.bipros.dbs.api.dto.DbsCmDayResponse;
import com.bipros.dbs.api.dto.DbsCmSummaryDto;
import com.bipros.dbs.api.dto.DbsEngineerDayResponse;
import com.bipros.dbs.api.dto.DbsEngineerPeriodResponse;
import com.bipros.dbs.api.dto.DbsProjectDayResponse;
import com.bipros.dbs.api.dto.DbsProjectPeriodResponse;
import com.bipros.dbs.api.dto.DbsSectionLineDto;
import com.bipros.dbs.api.dto.DbsSubContractLineDto;
import com.bipros.dbs.api.dto.DbsSupervisorDayResponse;
import com.bipros.dbs.api.dto.DbsSupervisorPeriodResponse;
import com.bipros.dbs.api.dto.DbsSupervisorSummaryDto;
import com.bipros.dbs.domain.model.DbsDailyCm;
import com.bipros.dbs.domain.model.DbsDailyEngineer;
import com.bipros.dbs.domain.model.DbsDailyProject;
import com.bipros.dbs.domain.model.DbsDailySupervisor;
import com.bipros.dbs.domain.repository.DbsDailyCmRepository;
import com.bipros.dbs.domain.repository.DbsDailyEngineerRepository;
import com.bipros.dbs.domain.repository.DbsDailyProjectRepository;
import com.bipros.dbs.domain.repository.DbsDailySupervisorRepository;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read-side projection for the Daily Balance Sheet. Returns zero-filled DTOs for
 * missing days (never 404) so the frontend can render a stable grid layout. Period
 * windows follow ISO week (Mon–Sun) and calendar-month conventions.
 *
 * <p>Cumulative metrics on the project-day response are computed at read time by
 * SUMing all project rows with {@code report_date &lt;= date} — this keeps cumulative
 * totals correct after late edits without persisting brittle running totals.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DbsQueryService {

    private static final TypeReference<List<DbsSectionLineDto>> LINE_LIST_TYPE =
        new TypeReference<>() {};
    private static final TypeReference<List<DbsSubContractLineDto>> SC_LINE_LIST_TYPE =
        new TypeReference<>() {};

    private final DbsDailySupervisorRepository supervisorRepo;
    private final DbsDailyEngineerRepository engineerRepo;
    private final DbsDailyProjectRepository projectRepo;
    private final DbsDailyCmRepository cmRepo;
    private final DbsAggregationService aggregationService;
    private final DailyProgressReportRepository dprRepository;
    private final ObjectMapper objectMapper;
    private final DbsAlertEvaluator alertEvaluator;
    private final com.bipros.dbs.config.DbsProperties dbsProperties;

    /**
     * Used for lightweight cross-schema lookups (e.g. resolving supervisor display
     * names from {@code public.users} without taking a runtime dependency on the
     * bipros-security module — Bug 8 fix).
     */
    @PersistenceContext
    private EntityManager em;

    // ── single-day reads ────────────────────────────────────────────────────────

    public DbsSupervisorDayResponse getSupervisorDay(UUID projectId, UUID supervisorUserId, LocalDate date) {
        return supervisorRepo
            .findByProjectIdAndSupervisorUserIdAndReportDate(projectId, supervisorUserId, date)
            .map(this::toResponse)
            .orElseGet(() -> zeroSupervisor(projectId, supervisorUserId, date));
    }

    public DbsEngineerDayResponse getEngineerDay(UUID projectId, UUID engineerUserId, LocalDate date) {
        return engineerRepo
            .findByProjectIdAndEngineerUserIdAndReportDate(projectId, engineerUserId, date)
            .map(this::toResponse)
            .orElseGet(() -> zeroEngineer(projectId, engineerUserId, date));
    }

    public DbsCmDayResponse getCmDay(UUID projectId, UUID cmUserId, LocalDate date) {
        return cmRepo
            .findByProjectIdAndCmUserIdAndReportDate(projectId, cmUserId, date)
            .map(this::toResponse)
            .orElseGet(() -> zeroCm(projectId, cmUserId, date));
    }

    /**
     * Period-window rollup for one CM. Period bounds follow the same ISO-Mon week / calendar
     * month convention as the supervisor / engineer counterparts.
     */
    public DbsCmDayResponse getCmPeriod(UUID projectId, UUID cmUserId, String periodType, LocalDate referenceDate) {
        LocalDate[] bounds = boundsFor(periodType, referenceDate);
        DbsDailyCm totals = aggregationService.computeCmPeriod(projectId, cmUserId, bounds[0], bounds[1]);
        return toResponse(totals);
    }

    /**
     * Compact per-CM summaries for a project on a given date — powers the PM tab CM drill-down.
     */
    public List<DbsCmSummaryDto> listCmsForDay(UUID projectId, LocalDate date) {
        List<DbsDailyCm> rows = cmRepo.findByProjectIdAndReportDate(projectId, date);
        Map<UUID, String> nameByUser = resolveUserNames(rows.stream()
            .map(DbsDailyCm::getCmUserId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new)));
        return rows.stream()
            .map(r -> new DbsCmSummaryDto(
                r.getCmUserId(),
                nameByUser.get(r.getCmUserId()),
                r.getSupervisorCount() == null ? 0 : r.getSupervisorCount(),
                nz(r.getDirectCost()),
                nz(r.getPrelimCost()),
                nz(r.getTotalCostInclPrelims()),
                nz(r.getContributionPct()),
                nz(r.getPctAchieved())
            ))
            .toList();
    }

    public DbsProjectDayResponse getProjectDay(UUID projectId, LocalDate date) {
        DbsDailyProject row = projectRepo.findByProjectIdAndReportDate(projectId, date).orElse(null);
        List<DbsDailyProject> historical = projectRepo
            .findByProjectIdAndReportDateBetween(projectId, LocalDate.of(1970, 1, 1), date);
        BigDecimal cumExpense = historical.stream()
            .map(h -> liveExpense(h.getTotalExpense(), h.getFuelAmount(), h.getMachineryAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cumIncome = sum(historical, DbsDailyProject::getTotalIncome);
        BigDecimal cumContribution = cumIncome.subtract(cumExpense).setScale(2, RoundingMode.HALF_UP);
        return row == null
            ? zeroProject(projectId, date, cumExpense, cumIncome, cumContribution)
            : toResponse(row, cumExpense, cumIncome, cumContribution);
    }

    /**
     * Evaluates the alert codes for a project on a given date. Returns an empty list
     * when the row is missing (zero-filled stub triggers no alerts).
     */
    public List<String> getAlertsForProjectDay(UUID projectId, LocalDate date) {
        DbsDailyProject row = projectRepo.findByProjectIdAndReportDate(projectId, date).orElse(null);
        return alertEvaluator.evaluate(row);
    }

    // ── period reads ────────────────────────────────────────────────────────────

    public DbsSupervisorPeriodResponse getSupervisorPeriod(UUID projectId, UUID supervisorUserId,
                                                            String periodType, LocalDate referenceDate) {
        LocalDate[] bounds = boundsFor(periodType, referenceDate);
        LocalDate from = bounds[0];
        LocalDate to = bounds[1];

        List<DbsDailySupervisor> rows = supervisorRepo
            .findByProjectIdAndReportDateBetween(projectId, from, to).stream()
            .filter(r -> supervisorUserId == null
                ? r.getSupervisorUserId() == null
                : supervisorUserId.equals(r.getSupervisorUserId()))
            .toList();

        List<DbsSupervisorDayResponse> daily = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            final LocalDate cur = d;
            DbsSupervisorDayResponse dayDto = rows.stream()
                .filter(r -> cur.equals(r.getReportDate()))
                .findFirst()
                .map(this::toResponse)
                .orElseGet(() -> zeroSupervisor(projectId, supervisorUserId, cur));
            daily.add(dayDto);
        }

        DbsSupervisorDayResponse totals = supervisorTotals(projectId, supervisorUserId, from, to, daily);
        return new DbsSupervisorPeriodResponse(normalisePeriod(periodType), from, to, totals, daily);
    }

    public DbsEngineerPeriodResponse getEngineerPeriod(UUID projectId, UUID engineerUserId,
                                                       String periodType, LocalDate referenceDate) {
        LocalDate[] bounds = boundsFor(periodType, referenceDate);
        LocalDate from = bounds[0];
        LocalDate to = bounds[1];

        List<DbsDailyEngineer> rows = engineerRepo
            .findByProjectIdAndReportDateBetween(projectId, from, to).stream()
            .filter(r -> engineerUserId == null
                ? r.getEngineerUserId() == null
                : engineerUserId.equals(r.getEngineerUserId()))
            .toList();

        List<DbsEngineerDayResponse> daily = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            final LocalDate cur = d;
            DbsEngineerDayResponse dayDto = rows.stream()
                .filter(r -> cur.equals(r.getReportDate()))
                .findFirst()
                .map(this::toResponse)
                .orElseGet(() -> zeroEngineer(projectId, engineerUserId, cur));
            daily.add(dayDto);
        }

        DbsEngineerDayResponse totals = engineerTotals(projectId, engineerUserId, from, to, daily);
        return new DbsEngineerPeriodResponse(normalisePeriod(periodType), from, to, totals, daily);
    }

    public DbsProjectPeriodResponse getProjectPeriod(UUID projectId, String periodType, LocalDate referenceDate) {
        LocalDate[] bounds = boundsFor(periodType, referenceDate);
        LocalDate from = bounds[0];
        LocalDate to = bounds[1];

        List<DbsDailyProject> rows = projectRepo.findByProjectIdAndReportDateBetween(projectId, from, to);

        // Cumulative across history up to each row's date is heavy to compute per day; we
        // only attach cumulative to the "to" date totals — daily rows carry the day's metrics.
        List<DbsProjectDayResponse> daily = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            final LocalDate cur = d;
            DbsProjectDayResponse dayDto = rows.stream()
                .filter(r -> cur.equals(r.getReportDate()))
                .findFirst()
                .map(r -> toResponse(r, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))
                .orElseGet(() -> zeroProject(projectId, cur, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
            daily.add(dayDto);
        }

        DbsProjectDayResponse totals = projectTotals(projectId, from, to, daily);
        return new DbsProjectPeriodResponse(normalisePeriod(periodType), from, to, totals, daily);
    }

    // ── list ────────────────────────────────────────────────────────────────────

    public List<DbsSupervisorSummaryDto> listSupervisorsForDay(UUID projectId, LocalDate date) {
        return listSupervisorsForScope(projectId, date, null);
    }

    /**
     * Period-aware roster query. When {@code periodType} is null or {@code "DAY"},
     * behaves like the single-day list. When {@code periodType} is {@code "WEEK"} or
     * {@code "MONTH"}, expands the search to the period bounds (Mon-Sun ISO week or
     * calendar-month, mirroring {@link #boundsFor}), groups by supervisor across the
     * range, and aggregates the totals so the picker shows one entry per supervisor
     * with the period's combined figures.
     *
     * <p>Why this exists: with date-only roster filtering, the Supervisor tab would
     * render empty on any selected day with no DPRs — even if the surrounding week/month
     * had plenty of activity. PM tab works in period mode because it has no roster gate.
     */
    public List<DbsSupervisorSummaryDto> listSupervisorsForScope(
        UUID projectId, LocalDate referenceDate, String periodType) {

        String normalised = normalisePeriod(periodType);
        if (normalised == null || "DAY".equals(normalised)) {
            return listSupervisorsForDayInternal(projectId, referenceDate);
        }

        LocalDate[] bounds = boundsFor(normalised, referenceDate);
        List<DbsDailySupervisor> rows = supervisorRepo
            .findByProjectIdAndReportDateBetween(projectId, bounds[0], bounds[1]);
        if (rows.isEmpty()) return List.of();

        // Drop rows with no supervisor — those are phantoms written by
        // DbsRecomputeListener for dates with no DPRs so DRD/MCL still rolls up.
        // They should not appear in the supervisor picker or inflate counts.
        Map<UUID, List<DbsDailySupervisor>> byUser = rows.stream()
            .filter(r -> r.getSupervisorUserId() != null)
            .collect(Collectors.groupingBy(
                DbsDailySupervisor::getSupervisorUserId,
                LinkedHashMap::new,
                Collectors.toList()));
        if (byUser.isEmpty()) return List.of();
        Map<UUID, String> nameByUser = resolveUserNames(new LinkedHashSet<>(byUser.keySet()));

        return byUser.entrySet().stream()
            .map(entry -> {
                List<DbsDailySupervisor> daily = entry.getValue();
                UUID supId = entry.getKey();
                BigDecimal expense = daily.stream()
                    .map(d -> liveExpense(d.getTotalExpense(), d.getFuelAmount(), d.getMachineryAmount()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal income = sumRows(daily, DbsDailySupervisor::getTotalIncome);
                BigDecimal contribution = income.subtract(expense);
                BigDecimal direct = sumRows(daily, DbsDailySupervisor::getDirectCost);
                BigDecimal prelim = sumRows(daily, DbsDailySupervisor::getPrelimCost);
                BigDecimal totalIncl = direct.add(prelim);
                BigDecimal contributionPct = income.compareTo(BigDecimal.ZERO) > 0
                    ? contribution.divide(income, 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
                BigDecimal boqPlanned = sumRows(daily, DbsDailySupervisor::getBoqPlannedAmount);
                BigDecimal boqAchieved = sumRows(daily, DbsDailySupervisor::getBoqAchievedAmount);
                BigDecimal pctAchieved = boqPlanned.compareTo(BigDecimal.ZERO) > 0
                    ? boqAchieved.multiply(BigDecimal.valueOf(100))
                        .divide(boqPlanned, 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
                return new DbsSupervisorSummaryDto(
                    supId,
                    nameByUser.get(supId),
                    expense, income, contribution, contributionPct,
                    direct, prelim, totalIncl, pctAchieved,
                    daily.size()
                );
            })
            .toList();
    }

    private static BigDecimal sumRows(List<DbsDailySupervisor> rows,
                                       java.util.function.Function<DbsDailySupervisor, BigDecimal> f) {
        return rows.stream()
            .map(f)
            .map(v -> v == null ? BigDecimal.ZERO : v)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<DbsSupervisorSummaryDto> listSupervisorsForDayInternal(UUID projectId, LocalDate date) {
        List<DbsDailySupervisor> rows = supervisorRepo.findByProjectIdAndReportDate(projectId, date);
        Map<UUID, String> nameByUser = resolveUserNames(rows.stream()
            .map(DbsDailySupervisor::getSupervisorUserId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new)));
        return rows.stream()
            .map(r -> {
                BigDecimal expense = liveExpense(r.getTotalExpense(), r.getFuelAmount(), r.getMachineryAmount());
                BigDecimal income = nz(r.getTotalIncome());
                BigDecimal contribution = income.subtract(expense);
                return new DbsSupervisorSummaryDto(
                    r.getSupervisorUserId(),
                    nameByUser.get(r.getSupervisorUserId()),
                    expense,
                    income,
                    contribution,
                    pctFraction(contribution, income),
                    // Phase 7: BOQ direct/prelim split + cumulative progress KPI.
                    nz(r.getDirectCost()),
                    nz(r.getPrelimCost()),
                    nz(r.getTotalCostInclPrelims()),
                    nz(r.getPctAchieved()),
                    // The supervisor row is per-(project, supervisor, date); each row corresponds to
                    // the aggregated work for that supervisor that day. We surface 1 as the count
                    // because we don't currently persist the source-DPR count on the supervisor row.
                    // TODO bug-5/8 follow-up: when DailyProgressReportRepository gains a
                    // countByProjectIdAndReportDateAndSupervisorUserId finder, surface the real
                    // source-DPR count here.
                    1
                );
            })
            .toList();
    }

    // ── fuel derivation (read-time) ─────────────────────────────────────────────
    // Section D (Fuel) is a pure function of Section C (Machinery): fuel = ratio ×
    // machinery. We compute it on read so every view (any supervisor / period) is
    // always correct without a recompute. liveExpense swaps the (possibly stale)
    // stored fuel out of the stored totalExpense and the freshly-derived fuel in —
    // correct whether or not the day was recomputed, and tier-agnostic because the
    // stored totalExpense already sums each tier's own buckets.

    private BigDecimal fuelOf(BigDecimal machinery) {
        return nz(machinery).multiply(dbsProperties.getFuelMachineryCostRatio())
            .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal liveExpense(BigDecimal storedTotalExpense, BigDecimal storedFuel, BigDecimal machinery) {
        return nz(storedTotalExpense).subtract(nz(storedFuel)).add(fuelOf(machinery));
    }

    private static BigDecimal pctFraction(BigDecimal contribution, BigDecimal income) {
        return income.compareTo(BigDecimal.ZERO) > 0
            ? contribution.divide(income, 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
    }

    // ── mappers ─────────────────────────────────────────────────────────────────

    private DbsSupervisorDayResponse toResponse(DbsDailySupervisor e) {
        String supervisorName = e.getSupervisorUserId() == null
            ? null
            : resolveUserNames(Set.of(e.getSupervisorUserId())).get(e.getSupervisorUserId());
        BigDecimal fuel = fuelOf(e.getMachineryAmount());
        BigDecimal expense = liveExpense(e.getTotalExpense(), e.getFuelAmount(), e.getMachineryAmount());
        BigDecimal income = nz(e.getTotalIncome());
        BigDecimal contribution = income.subtract(expense);
        return new DbsSupervisorDayResponse(
            e.getId(),
            e.getProjectId(),
            e.getSupervisorUserId(),
            supervisorName,
            e.getEngineerUserId(),
            e.getReportDate(),
            nz(e.getMaterialAmount()),
            nz(e.getManpowerAmount()),
            nz(e.getAdminAmount()),
            nz(e.getMachineryAmount()),
            fuel,
            nz(e.getSubcontractAmount()),
            nz(e.getBoqForTheDayAmount()),
            nz(e.getBoqPlannedAmount()),
            nz(e.getBoqAchievedAmount()),
            nz(e.getDirectCost()),
            nz(e.getPrelimCost()),
            nz(e.getTotalCostInclPrelims()),
            nz(e.getPctAchieved()),
            expense,
            income,
            contribution,
            pctFraction(contribution, income),
            parseLines(e.getMaterialLinesJson()),
            parseLines(e.getManpowerLinesJson()),
            parseLines(e.getAdminLinesJson()),
            parseLines(e.getMachineryLinesJson()),
            parseLines(e.getFuelLinesJson()),
            parseLines(e.getBoqLinesJson()),
            parseLines(e.getSubcontractLinesJson()),
            e.getRecomputedAt()
        );
    }

    private DbsEngineerDayResponse toResponse(DbsDailyEngineer e) {
        BigDecimal fuel = fuelOf(e.getMachineryAmount());
        BigDecimal expense = liveExpense(e.getTotalExpense(), e.getFuelAmount(), e.getMachineryAmount());
        BigDecimal income = nz(e.getTotalIncome());
        BigDecimal contribution = income.subtract(expense);
        return new DbsEngineerDayResponse(
            e.getId(),
            e.getProjectId(),
            e.getEngineerUserId(),
            e.getReportDate(),
            parseUuidList(e.getSupervisorIds()),
            nz(e.getMaterialAmount()),
            nz(e.getManpowerAmount()),
            nz(e.getAdminAmount()),
            nz(e.getMachineryAmount()),
            fuel,
            nz(e.getSubcontractAmount()),
            nz(e.getBoqForTheDayAmount()),
            nz(e.getBoqPlannedAmount()),
            nz(e.getBoqAchievedAmount()),
            nz(e.getDirectCost()),
            nz(e.getPrelimCost()),
            nz(e.getTotalCostInclPrelims()),
            nz(e.getPctAchieved()),
            expense,
            income,
            contribution,
            pctFraction(contribution, income),
            e.getRecomputedAt()
        );
    }

    private DbsCmDayResponse toResponse(DbsDailyCm e) {
        return new DbsCmDayResponse(
            e.getId(),
            e.getProjectId(),
            e.getCmUserId(),
            e.getReportDate(),
            asList(e.getSiteManagerIds()),
            asList(e.getEngineerIds()),
            e.getSupervisorCount() == null ? 0 : e.getSupervisorCount(),
            nz(e.getMaterialAmount()),
            nz(e.getManpowerAmount()),
            nz(e.getAdminAmount()),
            nz(e.getMachineryAmount()),
            fuelOf(e.getMachineryAmount()),
            nz(e.getDirectCost()),
            nz(e.getPrelimCost()),
            nz(e.getTotalCostInclPrelims()),
            nz(e.getBoqForTheDayAmount()),
            nz(e.getBoqPlannedToDate()),
            nz(e.getBoqAchievedToDate()),
            nz(e.getContributionPct()),
            nz(e.getPctAchieved()),
            e.getRecomputedAt()
        );
    }

    private DbsProjectDayResponse toResponse(DbsDailyProject e,
                                              BigDecimal cumExpense,
                                              BigDecimal cumIncome,
                                              BigDecimal cumContribution) {
        BigDecimal fuel = fuelOf(e.getMachineryAmount());
        BigDecimal expense = liveExpense(e.getTotalExpense(), e.getFuelAmount(), e.getMachineryAmount());
        BigDecimal income = nz(e.getTotalIncome());
        BigDecimal contribution = income.subtract(expense);
        return new DbsProjectDayResponse(
            e.getId(),
            e.getProjectId(),
            e.getReportDate(),
            parseUuidList(e.getEngineerIds()),
            e.getSupervisorCount() == null ? 0 : e.getSupervisorCount(),
            e.getDprCount() == null ? 0 : e.getDprCount(),
            nz(e.getMaterialAmount()),
            nz(e.getManpowerAmount()),
            nz(e.getAdminAmount()),
            nz(e.getMachineryAmount()),
            fuel,
            nz(e.getSubcontractAmount()),
            nz(e.getGeneralExpenseAmount()),
            nz(e.getGeneralExpenseMonthlyTotal()),
            e.getGeneralExpenseLinesJson(),
            nz(e.getBoqForTheDayAmount()),
            nz(e.getBoqPlannedAmount()),
            nz(e.getBoqAchievedAmount()),
            nz(e.getDirectCost()),
            nz(e.getPrelimCost()),
            nz(e.getTotalCostInclPrelims()),
            nz(e.getPctAchieved()),
            expense,
            income,
            contribution,
            pctFraction(contribution, income),
            cumExpense,
            cumIncome,
            cumContribution,
            parseSubContractLines(e.getSubcontractLinesJson()),
            e.getRecomputedAt(),
            alertEvaluator.evaluate(e)
        );
    }

    // ── zero-fill builders ──────────────────────────────────────────────────────

    private DbsSupervisorDayResponse zeroSupervisor(UUID projectId, UUID supervisorUserId, LocalDate date) {
        return new DbsSupervisorDayResponse(
            null, projectId, supervisorUserId, null, null, date,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO,
            // Phase 7: directCost, prelimCost, totalCostInclPrelims, pctAchieved
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO,
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            Collections.emptyList(),
            null
        );
    }

    private DbsCmDayResponse zeroCm(UUID projectId, UUID cmUserId, LocalDate date) {
        return new DbsCmDayResponse(
            null, projectId, cmUserId, date,
            Collections.emptyList(), Collections.emptyList(), 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO,
            null
        );
    }

    private DbsEngineerDayResponse zeroEngineer(UUID projectId, UUID engineerUserId, LocalDate date) {
        return new DbsEngineerDayResponse(
            null, projectId, engineerUserId, date, Collections.emptyList(),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO,
            // Phase 7
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO,
            null
        );
    }

    private DbsProjectDayResponse zeroProject(UUID projectId, LocalDate date,
                                               BigDecimal cumExpense, BigDecimal cumIncome,
                                               BigDecimal cumContribution) {
        return new DbsProjectDayResponse(
            null, projectId, date, Collections.emptyList(), 0, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO,
            // Section G: amount, monthlyTotal, linesJson
            BigDecimal.ZERO, BigDecimal.ZERO, "[]",
            BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO,
            // Phase 7
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO,
            cumExpense, cumIncome, cumContribution,
            Collections.emptyList(),
            null,
            Collections.emptyList()
        );
    }

    // ── totals (SUM of daily rows) ─────────────────────────────────────────────

    private DbsSupervisorDayResponse supervisorTotals(UUID projectId, UUID supervisorUserId,
                                                       LocalDate from, LocalDate to,
                                                       List<DbsSupervisorDayResponse> daily) {
        BigDecimal material = sumDtos(daily, DbsSupervisorDayResponse::materialAmount);
        BigDecimal manpower = sumDtos(daily, DbsSupervisorDayResponse::manpowerAmount);
        BigDecimal admin = sumDtos(daily, DbsSupervisorDayResponse::adminAmount);
        BigDecimal machinery = sumDtos(daily, DbsSupervisorDayResponse::machineryAmount);
        BigDecimal fuel = sumDtos(daily, DbsSupervisorDayResponse::fuelAmount);
        BigDecimal sub = sumDtos(daily, DbsSupervisorDayResponse::subcontractAmount);
        BigDecimal boqDay = sumDtos(daily, DbsSupervisorDayResponse::boqForTheDayAmount);
        BigDecimal boqPlanned = sumDtos(daily, DbsSupervisorDayResponse::boqPlannedAmount);
        BigDecimal boqAch = sumDtos(daily, DbsSupervisorDayResponse::boqAchievedAmount);
        BigDecimal direct = sumDtos(daily, DbsSupervisorDayResponse::directCost);
        BigDecimal prelim = sumDtos(daily, DbsSupervisorDayResponse::prelimCost);
        BigDecimal totalIncl = direct.add(prelim);
        BigDecimal pctAchieved = pct(boqAch, boqPlanned);
        BigDecimal expense = sumDtos(daily, DbsSupervisorDayResponse::totalExpense);
        BigDecimal income = sumDtos(daily, DbsSupervisorDayResponse::totalIncome);
        BigDecimal contribution = income.subtract(expense).setScale(2, RoundingMode.HALF_UP);
        BigDecimal contributionPct = income.compareTo(BigDecimal.ZERO) > 0
            ? contribution.divide(income, 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        // Period totals were previously emitting empty section line arrays — the dollar
        // totals were correct but the section accordions all said "0 lines". Aggregate
        // each per-day line list into period totals by grouping on description + unit +
        // rate, summing qty + amount.
        return new DbsSupervisorDayResponse(
            null, projectId, supervisorUserId, null, null, to,
            material, manpower, admin, machinery, fuel, sub,
            boqDay, boqPlanned, boqAch,
            direct, prelim, totalIncl, pctAchieved,
            expense, income, contribution, contributionPct,
            aggregateSectionLines(daily, DbsSupervisorDayResponse::materialLines),
            aggregateSectionLines(daily, DbsSupervisorDayResponse::manpowerLines),
            aggregateSectionLines(daily, DbsSupervisorDayResponse::adminLines),
            aggregateSectionLines(daily, DbsSupervisorDayResponse::machineryLines),
            aggregateSectionLines(daily, DbsSupervisorDayResponse::fuelLines),
            aggregateSectionLines(daily, DbsSupervisorDayResponse::boqLines),
            aggregateSectionLines(daily, DbsSupervisorDayResponse::subcontractLines),
            null
        );
    }

    /**
     * Group section lines across days by (description + unit + rate), summing qty +
     * amount. Lets the period-mode supervisor view render meaningful per-resource rows
     * under each accordion instead of "0 lines".
     */
    private static List<DbsSectionLineDto> aggregateSectionLines(
        List<DbsSupervisorDayResponse> daily,
        java.util.function.Function<DbsSupervisorDayResponse, List<DbsSectionLineDto>> extractor) {

        Map<String, DbsSectionLineDto> byKey = new LinkedHashMap<>();
        for (DbsSupervisorDayResponse d : daily) {
            List<DbsSectionLineDto> lines = extractor.apply(d);
            if (lines == null) continue;
            for (DbsSectionLineDto line : lines) {
                if (line == null) continue;
                String key = (line.description() == null ? "" : line.description())
                    + "|" + (line.unit() == null ? "" : line.unit())
                    + "|" + (line.rate() == null ? "0" : line.rate().toPlainString());
                DbsSectionLineDto existing = byKey.get(key);
                if (existing == null) {
                    byKey.put(key, line);
                } else {
                    BigDecimal qty = nz(existing.quantity()).add(nz(line.quantity()));
                    BigDecimal amt = nz(existing.totalAmount()).add(nz(line.totalAmount()));
                    byKey.put(key, new DbsSectionLineDto(
                        existing.description(), existing.unit(), existing.rate(), qty, amt));
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private DbsEngineerDayResponse engineerTotals(UUID projectId, UUID engineerUserId,
                                                   LocalDate from, LocalDate to,
                                                   List<DbsEngineerDayResponse> daily) {
        BigDecimal material = sumDtos(daily, DbsEngineerDayResponse::materialAmount);
        BigDecimal manpower = sumDtos(daily, DbsEngineerDayResponse::manpowerAmount);
        BigDecimal admin = sumDtos(daily, DbsEngineerDayResponse::adminAmount);
        BigDecimal machinery = sumDtos(daily, DbsEngineerDayResponse::machineryAmount);
        BigDecimal fuel = sumDtos(daily, DbsEngineerDayResponse::fuelAmount);
        BigDecimal sub = sumDtos(daily, DbsEngineerDayResponse::subcontractAmount);
        BigDecimal boqDay = sumDtos(daily, DbsEngineerDayResponse::boqForTheDayAmount);
        BigDecimal boqPlanned = sumDtos(daily, DbsEngineerDayResponse::boqPlannedAmount);
        BigDecimal boqAch = sumDtos(daily, DbsEngineerDayResponse::boqAchievedAmount);
        BigDecimal direct = sumDtos(daily, DbsEngineerDayResponse::directCost);
        BigDecimal prelim = sumDtos(daily, DbsEngineerDayResponse::prelimCost);
        BigDecimal totalIncl = direct.add(prelim);
        BigDecimal pctAchieved = pct(boqAch, boqPlanned);
        BigDecimal expense = sumDtos(daily, DbsEngineerDayResponse::totalExpense);
        BigDecimal income = sumDtos(daily, DbsEngineerDayResponse::totalIncome);
        BigDecimal contribution = income.subtract(expense).setScale(2, RoundingMode.HALF_UP);
        BigDecimal contributionPct = income.compareTo(BigDecimal.ZERO) > 0
            ? contribution.divide(income, 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        Set<UUID> sups = daily.stream()
            .flatMap(d -> d.supervisorIds() == null ? java.util.stream.Stream.empty() : d.supervisorIds().stream())
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return new DbsEngineerDayResponse(
            null, projectId, engineerUserId, to, new ArrayList<>(sups),
            material, manpower, admin, machinery, fuel, sub,
            boqDay, boqPlanned, boqAch,
            direct, prelim, totalIncl, pctAchieved,
            expense, income, contribution, contributionPct,
            null
        );
    }

    private DbsProjectDayResponse projectTotals(UUID projectId, LocalDate from, LocalDate to,
                                                 List<DbsProjectDayResponse> daily) {
        BigDecimal material = sumDtos(daily, DbsProjectDayResponse::materialAmount);
        BigDecimal manpower = sumDtos(daily, DbsProjectDayResponse::manpowerAmount);
        BigDecimal admin = sumDtos(daily, DbsProjectDayResponse::adminAmount);
        BigDecimal machinery = sumDtos(daily, DbsProjectDayResponse::machineryAmount);
        BigDecimal fuel = sumDtos(daily, DbsProjectDayResponse::fuelAmount);
        BigDecimal sub = sumDtos(daily, DbsProjectDayResponse::subcontractAmount);
        // Section G: daily-prorated amounts already on each day; summing across the
        // month yields the same value as the monthly total (modulo rounding).
        BigDecimal generalExpense = sumDtos(daily, DbsProjectDayResponse::generalExpenseAmount);
        // Use the max monthly total across rows — every row in the same month carries
        // the same snapshot; taking max guards against zero-filled rows.
        BigDecimal generalExpenseMonthlyTotal = daily.stream()
            .map(DbsProjectDayResponse::generalExpenseMonthlyTotal)
            .filter(Objects::nonNull)
            .max(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
        BigDecimal boqDay = sumDtos(daily, DbsProjectDayResponse::boqForTheDayAmount);
        BigDecimal boqPlanned = sumDtos(daily, DbsProjectDayResponse::boqPlannedAmount);
        BigDecimal boqAch = sumDtos(daily, DbsProjectDayResponse::boqAchievedAmount);
        BigDecimal direct = sumDtos(daily, DbsProjectDayResponse::directCost);
        BigDecimal prelim = sumDtos(daily, DbsProjectDayResponse::prelimCost);
        BigDecimal totalIncl = direct.add(prelim);
        BigDecimal pctAchieved = pct(boqAch, boqPlanned);
        BigDecimal expense = sumDtos(daily, DbsProjectDayResponse::totalExpense);
        BigDecimal income = sumDtos(daily, DbsProjectDayResponse::totalIncome);
        BigDecimal contribution = income.subtract(expense).setScale(2, RoundingMode.HALF_UP);
        BigDecimal contributionPct = income.compareTo(BigDecimal.ZERO) > 0
            ? contribution.divide(income, 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        Set<UUID> engineers = daily.stream()
            .flatMap(d -> d.engineerIds() == null ? java.util.stream.Stream.empty() : d.engineerIds().stream())
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Integer supervisorCount = daily.stream()
            .map(DbsProjectDayResponse::supervisorCount)
            .filter(Objects::nonNull)
            .max(Integer::compareTo)
            .orElse(0);
        Integer dprCount = daily.stream()
            .map(DbsProjectDayResponse::dprCount)
            .filter(Objects::nonNull)
            .mapToInt(Integer::intValue)
            .sum();

        // Cumulative through "to" — fetched fresh, not summed from daily (those carry zero cum).
        List<DbsDailyProject> historical = projectRepo
            .findByProjectIdAndReportDateBetween(projectId, LocalDate.of(1970, 1, 1), to);
        BigDecimal cumExpense = historical.stream()
            .map(h -> liveExpense(h.getTotalExpense(), h.getFuelAmount(), h.getMachineryAmount()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cumIncome = sum(historical, DbsDailyProject::getTotalIncome);
        BigDecimal cumContribution = cumIncome.subtract(cumExpense).setScale(2, RoundingMode.HALF_UP);

        // Aggregate sub-contractor lines across days, grouped by (scCode, workTypeName).
        // Each day's lines already roll up (sc, work-type) per-day; this collapses them
        // across the period so the PM tab's week/month F. Sub-Contractor accordion shows
        // one row per (sc, work-type) tuple for the whole window.
        List<DbsSubContractLineDto> aggregatedScLines = aggregateSubContractLines(daily);

        return new DbsProjectDayResponse(
            null, projectId, to, new ArrayList<>(engineers), supervisorCount, dprCount,
            material, manpower, admin, machinery, fuel, sub,
            generalExpense, generalExpenseMonthlyTotal, null,
            boqDay, boqPlanned, boqAch,
            direct, prelim, totalIncl, pctAchieved,
            expense, income, contribution, contributionPct,
            cumExpense, cumIncome, cumContribution,
            aggregatedScLines,
            null,
            Collections.emptyList()
        );
    }

    private static List<DbsSubContractLineDto> aggregateSubContractLines(
        List<DbsProjectDayResponse> daily) {
        Map<String, DbsSubContractLineDto> byKey = new LinkedHashMap<>();
        for (DbsProjectDayResponse d : daily) {
            List<DbsSubContractLineDto> lines = d.subcontractLines();
            if (lines == null) continue;
            for (DbsSubContractLineDto line : lines) {
                if (line == null) continue;
                String key = (line.subContractorCode() == null ? "" : line.subContractorCode())
                    + "|" + (line.workTypeName() == null ? "" : line.workTypeName())
                    + "|" + (line.unit() == null ? "" : line.unit())
                    + "|" + (line.scRate() == null ? "0" : line.scRate().toPlainString());
                DbsSubContractLineDto existing = byKey.get(key);
                if (existing == null) {
                    byKey.put(key, line);
                } else {
                    BigDecimal qty = nz(existing.qty()).add(nz(line.qty()));
                    BigDecimal scExpense = nz(existing.scExpense()).add(nz(line.scExpense()));
                    BigDecimal scIncome = nz(existing.scImputedIncome()).add(nz(line.scImputedIncome()));
                    byKey.put(key, new DbsSubContractLineDto(
                        existing.subContractorCode(),
                        existing.subContractorName(),
                        existing.workTypeName(),
                        existing.unit(),
                        qty,
                        existing.scRate(),
                        scExpense,
                        existing.boqRate(),
                        scIncome,
                        scIncome.subtract(scExpense)));
                }
            }
        }
        return new ArrayList<>(byKey.values());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /**
     * Resolves user UUIDs to display names ({@code "first last"}) via a single
     * native query against {@code public.users}. Returns an empty map for an
     * empty input. Falls back gracefully if the column shape ever changes —
     * a missing user simply gets dropped from the result.
     */
    @SuppressWarnings("unchecked")
    private Map<UUID, String> resolveUserNames(Set<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) return Collections.emptyMap();
        try {
            List<Object[]> rows = em.createNativeQuery(
                    "SELECT id, COALESCE(NULLIF(TRIM(CONCAT(COALESCE(first_name,''),' ',COALESCE(last_name,''))), ''), username) "
                        + "FROM public.users WHERE id IN (:ids)")
                .setParameter("ids", userIds)
                .getResultList();
            Map<UUID, String> out = new HashMap<>(rows.size());
            for (Object[] row : rows) {
                if (row[0] == null) continue;
                UUID id = row[0] instanceof UUID u ? u : UUID.fromString(row[0].toString());
                String name = row[1] == null ? null : row[1].toString();
                out.put(id, name);
            }
            return out;
        } catch (Exception ex) {
            log.warn("Failed to resolve supervisor user names ({}): {}", ex.getClass().getSimpleName(), ex.getMessage());
            return Collections.emptyMap();
        }
    }

    private List<DbsSectionLineDto> parseLines(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            List<DbsSectionLineDto> parsed = objectMapper.readValue(json, LINE_LIST_TYPE);
            return parsed == null ? Collections.emptyList() : parsed;
        } catch (Exception ex) {
            log.warn("Failed to parse DBS section lines JSON ({}): {}", ex.getClass().getSimpleName(), ex.getMessage());
            return Collections.emptyList();
        }
    }

    private List<DbsSubContractLineDto> parseSubContractLines(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            List<DbsSubContractLineDto> parsed = objectMapper.readValue(json, SC_LINE_LIST_TYPE);
            return parsed == null ? Collections.emptyList() : parsed;
        } catch (Exception ex) {
            log.warn("Failed to parse DBS sub-contractor lines JSON ({}): {}",
                ex.getClass().getSimpleName(), ex.getMessage());
            return Collections.emptyList();
        }
    }

    private static List<UUID> asList(UUID[] arr) {
        if (arr == null || arr.length == 0) return Collections.emptyList();
        return Arrays.asList(arr);
    }

    private static List<UUID> parseUuidList(String csv) {
        if (csv == null || csv.isBlank()) return Collections.emptyList();
        return Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(s -> {
                try { return UUID.fromString(s); } catch (IllegalArgumentException ex) { return null; }
            })
            .filter(Objects::nonNull)
            .toList();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Phase 7: percentage helper — {@code num/denom * 100} or {@code 0} when denom <= 0. */
    private static BigDecimal pct(BigDecimal numerator, BigDecimal denominator) {
        BigDecimal d = nz(denominator);
        if (d.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        return nz(numerator).multiply(BigDecimal.valueOf(100)).divide(d, 4, RoundingMode.HALF_UP);
    }

    private static <T> BigDecimal sum(List<T> rows, java.util.function.Function<T, BigDecimal> extractor) {
        BigDecimal s = BigDecimal.ZERO;
        for (T r : rows) s = s.add(nz(extractor.apply(r)));
        return s.setScale(2, RoundingMode.HALF_UP);
    }

    private static <T> BigDecimal sumDtos(List<T> rows, java.util.function.Function<T, BigDecimal> extractor) {
        return sum(rows, extractor);
    }

    private static LocalDate[] boundsFor(String periodType, LocalDate reference) {
        String p = normalisePeriod(periodType);
        return switch (p) {
            case "WEEK" -> {
                // ISO Mon–Sun: WeekFields.ISO sets first day to Monday
                LocalDate monday = reference.with(WeekFields.ISO.getFirstDayOfWeek());
                if (monday.getDayOfWeek() != DayOfWeek.MONDAY) {
                    // Defensive: ISO first day is Monday; fall back to manual adjust if a JVM locale weirdness intervenes.
                    monday = reference.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                }
                yield new LocalDate[]{monday, monday.plusDays(6)};
            }
            case "MONTH" -> new LocalDate[]{
                reference.with(TemporalAdjusters.firstDayOfMonth()),
                reference.with(TemporalAdjusters.lastDayOfMonth())
            };
            default -> new LocalDate[]{reference, reference};
        };
    }

    private static String normalisePeriod(String periodType) {
        if (periodType == null) return "DAY";
        return switch (periodType.toUpperCase(Locale.ROOT)) {
            case "WEEK" -> "WEEK";
            case "MONTH" -> "MONTH";
            default -> "DAY";
        };
    }
}
