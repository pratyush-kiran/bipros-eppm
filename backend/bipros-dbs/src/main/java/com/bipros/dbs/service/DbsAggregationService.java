package com.bipros.dbs.service;

import com.bipros.dbs.domain.model.DbsDailyCm;
import com.bipros.dbs.domain.model.DbsDailyEngineer;
import com.bipros.dbs.domain.model.DbsDailyProject;
import com.bipros.dbs.domain.model.DbsDailySupervisor;
import com.bipros.dbs.domain.repository.DbsDailyCmRepository;
import com.bipros.dbs.domain.repository.DbsDailyEngineerRepository;
import com.bipros.dbs.domain.repository.DbsDailyProjectRepository;
import com.bipros.dbs.domain.repository.DbsDailySupervisorRepository;
import com.bipros.dbs.service.calculator.BoqSectionResult;
import com.bipros.dbs.service.calculator.SectionAManpowerCalculator;
import com.bipros.dbs.service.calculator.SectionBAdminCalculator;
import com.bipros.dbs.service.calculator.SectionCMachineryCalculator;
import com.bipros.dbs.service.calculator.SectionDFuelCalculator;
import com.bipros.dbs.service.calculator.SectionEMaterialCalculator;
import com.bipros.dbs.service.calculator.SectionFBoqCalculator;
import com.bipros.dbs.service.calculator.SectionGGeneralExpensesCalculator;
import com.bipros.dbs.service.calculator.SectionLine;
import com.bipros.dbs.service.calculator.SectionResult;
import com.bipros.project.application.service.ProjectTeamService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Recomputes the three DBS rollup tables. Invoked from {@code DbsRecomputeListener}
 * after a DPR / deployment / material commit, or directly via the {@code POST /dbs/recompute}
 * admin endpoint.
 *
 * <p>All three {@code recomputeXxxDay} methods are idempotent upserts on the natural key
 * (project, identity, date). Calling them repeatedly with the same source data produces the
 * same row.
 */
@Slf4j
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
@RequiredArgsConstructor
public class DbsAggregationService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final DbsDailySupervisorRepository supervisorRepo;
    private final DbsDailyEngineerRepository engineerRepo;
    private final DbsDailyProjectRepository projectRepo;
    private final DbsDailyCmRepository cmRepo;
    private final SectionAManpowerCalculator manpowerCalc;
    private final SectionBAdminCalculator adminCalc;
    private final SectionCMachineryCalculator machineryCalc;
    private final SectionDFuelCalculator fuelCalc;
    private final SectionEMaterialCalculator materialCalc;
    private final SectionFBoqCalculator boqCalc;
    private final SectionGGeneralExpensesCalculator generalExpensesCalc;
    private final ProjectTeamService projectTeamService;
    private final ObjectMapper objectMapper;
    private final RegisterAggregationService registerAggregationService;
    private final com.bipros.project.domain.repository.DailyProgressReportRepository dprRepository;

    /**
     * Recompute the supervisor-day row by running the six section calculators and
     * upserting on {@code (projectId, supervisorUserId, reportDate)}.
     */
    public DbsDailySupervisor recomputeSupervisorDay(UUID projectId, UUID supervisorUserId, LocalDate date) {
        log.debug("DBS recompute supervisor projectId={} supervisor={} date={}", projectId, supervisorUserId, date);

        SectionResult manpower = manpowerCalc.compute(projectId, supervisorUserId, date);
        SectionResult admin = adminCalc.compute(projectId, supervisorUserId, date);
        SectionResult machinery = machineryCalc.compute(projectId, supervisorUserId, date);
        SectionResult fuel = fuelCalc.compute(projectId, supervisorUserId, date);
        SectionResult material = materialCalc.compute(projectId, supervisorUserId, date);
        BoqSectionResult boq = boqCalc.compute(projectId, supervisorUserId, date);

        DbsDailySupervisor row = (supervisorUserId == null
            ? supervisorRepo.findByProjectIdAndReportDateAndSupervisorUserIdIsNull(projectId, date)
            : supervisorRepo.findByProjectIdAndSupervisorUserIdAndReportDate(projectId, supervisorUserId, date))
            .orElseGet(() -> DbsDailySupervisor.builder()
                .projectId(projectId)
                .supervisorUserId(supervisorUserId)
                .reportDate(date)
                .build());

        UUID engineerUserId = supervisorUserId == null
            ? null
            : projectTeamService.resolveEngineerFor(projectId, supervisorUserId).orElse(null);
        row.setEngineerUserId(engineerUserId);

        // Phase 4: denormalise the Construction Manager onto the supervisor row so the
        // CM-tier rollup can re-query without re-walking the team chain each time. This is
        // a snapshot at write time — historical rows do not auto-update on team re-orgs.
        UUID cmUserId = supervisorUserId == null
            ? null
            : projectTeamService.resolveCmFor(projectId, supervisorUserId).orElse(null);
        row.setConstructionManagerUserId(cmUserId);

        row.setManpowerAmount(manpower.totalAmount());
        row.setAdminAmount(admin.totalAmount());
        row.setMachineryAmount(machinery.totalAmount());
        row.setFuelAmount(fuel.totalAmount());
        row.setMaterialAmount(material.totalAmount());
        row.setSubcontractAmount(BigDecimal.ZERO);
        row.setBoqForTheDayAmount(boq.forTheDayAmount());

        // Per-supervisor deduped cumulative — SectionFBoqCalculator.compute returns
        // planned=0/achieved=0 in supervisor scope by design (see 95df0394), so we
        // get the supervisor's own deduped figures via computeCumulativeForScope with
        // a single-element set. A null supervisorUserId (free-text "Other" supervisor)
        // has no scope, so we pass an empty set → planned/achieved=0.
        SectionFBoqCalculator.BoqCumulative supCum = boqCalc.computeCumulativeForScope(
            projectId, date,
            supervisorUserId == null ? java.util.Set.of() : java.util.Set.of(supervisorUserId));
        row.setBoqPlannedAmount(supCum.planned());
        row.setBoqAchievedAmount(supCum.achieved());

        // Phase 7: split the day's BOQ value into direct (non-preliminary activities)
        // and prelim (mobilisation / site-setup / diversions). totalCostInclPrelims is
        // the convenience sum; pctAchieved is cumulative progress vs plan (0..100).
        BigDecimal directCost = nz(boq.directBoqAmount());
        BigDecimal prelimCost = nz(boq.prelimBoqAmount());
        row.setDirectCost(directCost);
        row.setPrelimCost(prelimCost);
        row.setTotalCostInclPrelims(directCost.add(prelimCost));
        row.setPctAchieved(percentage(supCum.achieved(), supCum.planned()));

        BigDecimal totalExpense = sum(manpower.totalAmount(), admin.totalAmount(), machinery.totalAmount(),
            fuel.totalAmount(), material.totalAmount(), BigDecimal.ZERO);
        // Daily P&L: income must be the for-the-day BOQ amount, NOT cumulative achieved-to-date.
        // boqAchievedAmount is still persisted separately (above) for the cumulative-income KPI.
        BigDecimal totalIncome = nz(boq.forTheDayAmount());
        BigDecimal contribution = totalIncome.subtract(totalExpense);
        BigDecimal contributionPct = totalIncome.compareTo(BigDecimal.ZERO) > 0
            ? contribution.divide(totalIncome, 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        row.setTotalExpense(totalExpense);
        row.setTotalIncome(totalIncome);
        row.setContribution(contribution);
        row.setContributionPct(contributionPct);

        row.setManpowerLinesJson(serialize(manpower));
        row.setAdminLinesJson(serialize(admin));
        row.setMachineryLinesJson(serialize(machinery));
        row.setFuelLinesJson(serialize(fuel));
        row.setMaterialLinesJson(serialize(material));
        row.setBoqLinesJson(serializeLines(boq));
        row.setSubcontractLinesJson("[]");

        row.setRecomputedAt(Instant.now());
        row.setSourceHash(String.valueOf(
            safeHash(manpower) ^ safeHash(admin) ^ safeHash(machinery)
                ^ safeHash(fuel) ^ safeHash(material) ^ (boq.lines() == null ? 0 : boq.lines().hashCode())));

        DbsDailySupervisor saved = supervisorRepo.save(row);
        log.info("DBS supervisor row saved projectId={} supervisor={} date={} expense={} income={}",
            projectId, supervisorUserId, date, totalExpense, totalIncome);
        return saved;
    }

    /**
     * Recompute the engineer-day row by SUMing all supervisor rows whose
     * {@code engineerUserId} matches for that date.
     */
    public DbsDailyEngineer recomputeEngineerDay(UUID projectId, UUID engineerUserId, LocalDate date) {
        log.debug("DBS recompute engineer projectId={} engineer={} date={}", projectId, engineerUserId, date);

        List<DbsDailySupervisor> supRows = supervisorRepo.findByProjectIdAndReportDate(projectId, date).stream()
            .filter(s -> engineerUserId == null
                ? s.getEngineerUserId() == null
                : engineerUserId.equals(s.getEngineerUserId()))
            .toList();

        DbsDailyEngineer row = (engineerUserId == null
            ? engineerRepo.findByProjectIdAndReportDateAndEngineerUserIdIsNull(projectId, date)
            : engineerRepo.findByProjectIdAndEngineerUserIdAndReportDate(projectId, engineerUserId, date))
            .orElseGet(() -> DbsDailyEngineer.builder()
                .projectId(projectId)
                .engineerUserId(engineerUserId)
                .reportDate(date)
                .build());

        applyAggregates(row::setManpowerAmount, supRows, DbsDailySupervisor::getManpowerAmount);
        applyAggregates(row::setAdminAmount, supRows, DbsDailySupervisor::getAdminAmount);
        applyAggregates(row::setMachineryAmount, supRows, DbsDailySupervisor::getMachineryAmount);
        applyAggregates(row::setFuelAmount, supRows, DbsDailySupervisor::getFuelAmount);
        applyAggregates(row::setMaterialAmount, supRows, DbsDailySupervisor::getMaterialAmount);
        applyAggregates(row::setSubcontractAmount, supRows, DbsDailySupervisor::getSubcontractAmount);
        applyAggregates(row::setBoqForTheDayAmount, supRows, DbsDailySupervisor::getBoqForTheDayAmount);
        applyAggregates(row::setTotalExpense, supRows, DbsDailySupervisor::getTotalExpense);
        applyAggregates(row::setTotalIncome, supRows, DbsDailySupervisor::getTotalIncome);

        // BOQ cumulative planned/achieved must be DEDUPED at engineer scope — summing the
        // supervisor rows double-counts whenever two supervisors of this engineer touched
        // the same BOQ item. computeCumulativeForScope runs a single SELECT DISTINCT over
        // boq_items filtered to this engineer's supervisors on the date.
        SectionFBoqCalculator.BoqCumulative engCum = boqCalc.computeCumulativeForScope(
            projectId, date,
            supRows.stream()
                .map(DbsDailySupervisor::getSupervisorUserId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet()));
        row.setBoqPlannedAmount(engCum.planned());
        row.setBoqAchievedAmount(engCum.achieved());

        // Phase 7: prelim split rolls up by simple summation. totalCostInclPrelims is
        // a derived sum-of-sums; pctAchieved is recomputed from the deduped planned/
        // achieved totals (rather than averaging per-supervisor percentages, which would
        // over-weight supervisors with small denominators).
        BigDecimal directCost = sumOf(supRows, DbsDailySupervisor::getDirectCost);
        BigDecimal prelimCost = sumOf(supRows, DbsDailySupervisor::getPrelimCost);
        row.setDirectCost(directCost);
        row.setPrelimCost(prelimCost);
        row.setTotalCostInclPrelims(directCost.add(prelimCost));
        row.setPctAchieved(percentage(row.getBoqAchievedAmount(), row.getBoqPlannedAmount()));

        BigDecimal contribution = nz(row.getTotalIncome()).subtract(nz(row.getTotalExpense()));
        row.setContribution(contribution);
        row.setContributionPct(row.getTotalIncome() != null && row.getTotalIncome().compareTo(BigDecimal.ZERO) > 0
            ? contribution.divide(row.getTotalIncome(), 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO);

        row.setSupervisorIds(supRows.stream()
            .map(DbsDailySupervisor::getSupervisorUserId)
            .filter(java.util.Objects::nonNull)
            .map(UUID::toString)
            .collect(Collectors.joining(",")));
        row.setRecomputedAt(Instant.now());

        DbsDailyEngineer saved = engineerRepo.save(row);
        log.info("DBS engineer row saved projectId={} engineer={} date={} sups={} income={}",
            projectId, engineerUserId, date, supRows.size(), row.getTotalIncome());
        return saved;
    }

    /**
     * Recompute the CM-day row as the SUM of every supervisor row on the date whose
     * denormalised {@code construction_manager_user_id} matches. Idempotent upsert on
     * {@code (projectId, cmUserId, reportDate)}.
     *
     * <p>Phase 7: {@code directCost} / {@code prelimCost} / {@code totalCostInclPrelims}
     * are summed from the supervisor rows (which now carry the prelim split from
     * {@link SectionFBoqCalculator}). {@code pctAchieved} is derived from the aggregated
     * planned / achieved totals.
     */
    public DbsDailyCm recomputeCmDay(UUID projectId, UUID cmUserId, LocalDate date) {
        log.debug("DBS recompute cm projectId={} cm={} date={}", projectId, cmUserId, date);

        List<DbsDailySupervisor> supRows = supervisorRepo
            .findByProjectIdAndReportDateAndConstructionManagerUserId(projectId, date, cmUserId);

        DbsDailyCm row = cmRepo
            .findByProjectIdAndCmUserIdAndReportDate(projectId, cmUserId, date)
            .orElseGet(() -> DbsDailyCm.builder()
                .projectId(projectId)
                .cmUserId(cmUserId)
                .reportDate(date)
                .build());

        row.setManpowerAmount(sumOf(supRows, DbsDailySupervisor::getManpowerAmount));
        row.setAdminAmount(sumOf(supRows, DbsDailySupervisor::getAdminAmount));
        row.setMachineryAmount(sumOf(supRows, DbsDailySupervisor::getMachineryAmount));
        row.setFuelAmount(sumOf(supRows, DbsDailySupervisor::getFuelAmount));
        row.setMaterialAmount(sumOf(supRows, DbsDailySupervisor::getMaterialAmount));

        BigDecimal boqForDay = sumOf(supRows, DbsDailySupervisor::getBoqForTheDayAmount);
        // BOQ cumulative is DEDUPED at CM scope — see comment on the engineer rollup.
        SectionFBoqCalculator.BoqCumulative cmCum = boqCalc.computeCumulativeForScope(
            projectId, date,
            supRows.stream()
                .map(DbsDailySupervisor::getSupervisorUserId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet()));
        BigDecimal boqPlanned = cmCum.planned();
        BigDecimal boqAchieved = cmCum.achieved();
        row.setBoqForTheDayAmount(boqForDay);
        row.setBoqPlannedToDate(boqPlanned);
        row.setBoqAchievedToDate(boqAchieved);

        // Phase 7: split direct vs prelim by summing the per-supervisor split. The
        // supervisor calculator (SectionFBoqCalculator) tags each BOQ line by the
        // underlying activity's is_preliminary flag, so the CM rollup is just a sum.
        BigDecimal directCost = sumOf(supRows, DbsDailySupervisor::getDirectCost);
        BigDecimal prelimCost = sumOf(supRows, DbsDailySupervisor::getPrelimCost);
        row.setDirectCost(directCost);
        row.setPrelimCost(prelimCost);
        row.setTotalCostInclPrelims(directCost.add(prelimCost));

        // contributionPct mirrors the engineer/supervisor compute today: (income − expense) / income × 100.
        BigDecimal totalExpense = sumOf(supRows, DbsDailySupervisor::getTotalExpense);
        BigDecimal totalIncome = sumOf(supRows, DbsDailySupervisor::getTotalIncome);
        BigDecimal contribution = totalIncome.subtract(totalExpense);
        BigDecimal contributionPct = totalIncome.compareTo(BigDecimal.ZERO) > 0
            ? contribution.multiply(HUNDRED).divide(totalIncome, 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        row.setContributionPct(contributionPct);

        BigDecimal pctAchieved = boqPlanned.compareTo(BigDecimal.ZERO) > 0
            ? boqAchieved.multiply(HUNDRED).divide(boqPlanned, 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        row.setPctAchieved(pctAchieved);

        // TODO collect once Phase 7 wires it in — leaving siteManagerIds empty for now.
        row.setSiteManagerIds(new UUID[0]);

        // Engineer ids from each supervisor's denormalised engineer_user_id; fall back to
        // re-resolution if a supervisor row was written before that column existed.
        Set<UUID> engineerIds = new LinkedHashSet<>();
        for (DbsDailySupervisor s : supRows) {
            UUID eng = s.getEngineerUserId();
            if (eng == null && s.getSupervisorUserId() != null) {
                eng = projectTeamService
                    .resolveEngineerFor(projectId, s.getSupervisorUserId())
                    .orElse(null);
            }
            if (eng != null) engineerIds.add(eng);
        }
        row.setEngineerIds(engineerIds.toArray(new UUID[0]));

        row.setSupervisorCount(supRows.size());
        row.setRecomputedAt(Instant.now());

        DbsDailyCm saved = cmRepo.save(row);
        log.info("DBS cm row saved projectId={} cm={} date={} sups={} pctAchieved={}",
            projectId, cmUserId, date, supRows.size(), pctAchieved);
        return saved;
    }

    /**
     * Period rollup for a single CM — SUMs of all {@code dbs_daily_cm} rows in the range
     * {@code [from, to]} inclusive. Returns a transient {@link DbsDailyCm} (no id, not
     * persisted) carrying the totals.
     */
    public DbsDailyCm computeCmPeriod(UUID projectId, UUID cmUserId, LocalDate from, LocalDate to) {
        List<DbsDailyCm> rows = cmRepo
            .findByProjectIdAndCmUserIdAndReportDateBetween(projectId, cmUserId, from, to);

        DbsDailyCm totals = DbsDailyCm.builder()
            .projectId(projectId)
            .cmUserId(cmUserId)
            .reportDate(to)
            .build();
        totals.setManpowerAmount(sumOf(rows, DbsDailyCm::getManpowerAmount));
        totals.setAdminAmount(sumOf(rows, DbsDailyCm::getAdminAmount));
        totals.setMachineryAmount(sumOf(rows, DbsDailyCm::getMachineryAmount));
        totals.setFuelAmount(sumOf(rows, DbsDailyCm::getFuelAmount));
        totals.setMaterialAmount(sumOf(rows, DbsDailyCm::getMaterialAmount));
        totals.setDirectCost(sumOf(rows, DbsDailyCm::getDirectCost));
        totals.setPrelimCost(sumOf(rows, DbsDailyCm::getPrelimCost));
        totals.setTotalCostInclPrelims(sumOf(rows, DbsDailyCm::getTotalCostInclPrelims));
        totals.setBoqForTheDayAmount(sumOf(rows, DbsDailyCm::getBoqForTheDayAmount));

        // For period rollups the "to-date" values are the latest row in the range — those
        // are cumulative on each row. Fall back to the sum when the per-row semantics are
        // additive (Phase 4 supervisor compute populates them as sum-for-the-day).
        BigDecimal boqPlanned = sumOf(rows, DbsDailyCm::getBoqPlannedToDate);
        BigDecimal boqAchieved = sumOf(rows, DbsDailyCm::getBoqAchievedToDate);
        totals.setBoqPlannedToDate(boqPlanned);
        totals.setBoqAchievedToDate(boqAchieved);

        BigDecimal pctAchieved = boqPlanned.compareTo(BigDecimal.ZERO) > 0
            ? boqAchieved.multiply(HUNDRED).divide(boqPlanned, 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        totals.setPctAchieved(pctAchieved);

        BigDecimal boqForDay = totals.getBoqForTheDayAmount();
        BigDecimal directCost = nz(totals.getDirectCost());
        BigDecimal contributionPct = nz(boqForDay).compareTo(BigDecimal.ZERO) > 0
            ? boqForDay.subtract(directCost).multiply(HUNDRED).divide(boqForDay, 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        totals.setContributionPct(contributionPct);

        Integer supervisorCount = rows.stream()
            .map(DbsDailyCm::getSupervisorCount)
            .filter(java.util.Objects::nonNull)
            .max(Integer::compareTo)
            .orElse(0);
        totals.setSupervisorCount(supervisorCount);
        return totals;
    }

    /**
     * Recompute the project-day row as the SUM of every supervisor row for the date,
     * plus a project-level pass for sources that have no supervisor FK (Section B
     * admin/catering DRDs, and DRD/MCL legacy rows that the per-supervisor calculators
     * exclude when {@code supervisorUserId != null}).
     *
     * <p>Without this project-level pass, Section B would always be zero in the PM
     * rollup, because the per-supervisor calculators return empty for it.
     */
    public DbsDailyProject recomputeProjectDay(UUID projectId, LocalDate date) {
        log.debug("DBS recompute project projectId={} date={}", projectId, date);

        List<DbsDailySupervisor> supRows = supervisorRepo.findByProjectIdAndReportDate(projectId, date);
        DbsDailyProject row = projectRepo.findByProjectIdAndReportDate(projectId, date)
            .orElseGet(() -> DbsDailyProject.builder()
                .projectId(projectId)
                .reportDate(date)
                .build());

        // Project-level pass: calculators invoked with supervisorUserId=null pick up
        // DRD/MCL rows that lack a supervisor FK. These are folded in ON TOP of the
        // per-supervisor sums so the project total reflects both DPR-attributed costs
        // and legacy unattributed deployments.
        SectionResult projectAdmin = adminCalc.compute(projectId, null, date);
        SectionResult projectManpowerLegacy = manpowerCalc.compute(projectId, null, date);
        SectionResult projectMachineryLegacy = machineryCalc.compute(projectId, null, date);
        SectionResult projectMaterialLegacy = materialCalc.compute(projectId, null, date);
        // Subtract per-supervisor manpower/machinery/material totals to keep only the
        // truly legacy (supervisor-null) portion — calculators currently return the
        // full project-wide DRD/MCL totals when supervisorUserId is null. If a later
        // refactor narrows the null-branch query to "supervisor IS NULL" rows only,
        // delete these subtractions.
        BigDecimal supManpower = sumOf(supRows, DbsDailySupervisor::getManpowerAmount);
        BigDecimal supMachinery = sumOf(supRows, DbsDailySupervisor::getMachineryAmount);
        BigDecimal supMaterial = sumOf(supRows, DbsDailySupervisor::getMaterialAmount);
        BigDecimal manpowerLegacyOnly = positiveDiff(projectManpowerLegacy.totalAmount(), supManpower);
        BigDecimal machineryLegacyOnly = positiveDiff(projectMachineryLegacy.totalAmount(), supMachinery);
        BigDecimal materialLegacyOnly = positiveDiff(projectMaterialLegacy.totalAmount(), supMaterial);

        applyAggregatesWithExtra(row::setManpowerAmount, supRows,
            DbsDailySupervisor::getManpowerAmount, manpowerLegacyOnly);
        applyAggregatesWithExtra(row::setAdminAmount, supRows,
            DbsDailySupervisor::getAdminAmount, nz(projectAdmin.totalAmount()));
        applyAggregatesWithExtra(row::setMachineryAmount, supRows,
            DbsDailySupervisor::getMachineryAmount, machineryLegacyOnly);
        applyAggregates(row::setFuelAmount, supRows, DbsDailySupervisor::getFuelAmount);
        applyAggregatesWithExtra(row::setMaterialAmount, supRows,
            DbsDailySupervisor::getMaterialAmount, materialLegacyOnly);
        applyAggregates(row::setSubcontractAmount, supRows, DbsDailySupervisor::getSubcontractAmount);
        applyAggregates(row::setBoqForTheDayAmount, supRows, DbsDailySupervisor::getBoqForTheDayAmount);

        // BOQ cumulative is DEDUPED at project scope (null filter = project-wide). Summing
        // supervisor rows would double-count whenever two supervisors share a BOQ item.
        SectionFBoqCalculator.BoqCumulative projCum = boqCalc.computeCumulativeForScope(
            projectId, date, null);
        row.setBoqPlannedAmount(projCum.planned());
        row.setBoqAchievedAmount(projCum.achieved());

        // Phase 7: project-wide prelim split sums the per-supervisor split and recomputes
        // pctAchieved against the deduped planned/achieved.
        BigDecimal projectDirect = sumOf(supRows, DbsDailySupervisor::getDirectCost);
        BigDecimal projectPrelim = sumOf(supRows, DbsDailySupervisor::getPrelimCost);
        row.setDirectCost(projectDirect);
        row.setPrelimCost(projectPrelim);
        row.setTotalCostInclPrelims(projectDirect.add(projectPrelim));
        row.setPctAchieved(percentage(row.getBoqAchievedAmount(), row.getBoqPlannedAmount()));

        // Section G — daily-prorated overhead. monthlyTotal is also stored on the
        // row for the period view (sum of dailyAmount across the month equals
        // monthlyTotal up to rounding).
        SectionGGeneralExpensesCalculator.Result gExp = generalExpensesCalc.compute(projectId, date);
        row.setGeneralExpenseAmount(gExp.dailyAmount());
        row.setGeneralExpenseMonthlyTotal(gExp.monthlyTotal());
        row.setGeneralExpenseLinesJson(serializeLines(gExp.lines()));

        BigDecimal totalExpense = sum(
            nz(row.getManpowerAmount()), nz(row.getAdminAmount()), nz(row.getMachineryAmount()),
            nz(row.getFuelAmount()), nz(row.getMaterialAmount()), nz(row.getSubcontractAmount()),
            nz(row.getGeneralExpenseAmount()));
        BigDecimal totalIncome = nz(row.getBoqForTheDayAmount());
        row.setTotalExpense(totalExpense);
        row.setTotalIncome(totalIncome);

        BigDecimal contribution = nz(row.getTotalIncome()).subtract(nz(row.getTotalExpense()));
        row.setContribution(contribution);
        row.setContributionPct(row.getTotalIncome() != null && row.getTotalIncome().compareTo(BigDecimal.ZERO) > 0
            ? contribution.divide(row.getTotalIncome(), 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO);

        Set<UUID> engineerIds = supRows.stream()
            .map(DbsDailySupervisor::getEngineerUserId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        row.setEngineerIds(engineerIds.stream().map(UUID::toString).collect(Collectors.joining(",")));
        row.setSupervisorCount((int) supRows.stream()
            .map(DbsDailySupervisor::getSupervisorUserId)
            .filter(java.util.Objects::nonNull)
            .distinct().count());
        // Count from the actual DPR ledger, not supRows.size(). A "phantom" supervisor row
        // (supervisorUserId == null) is created on recompute-without-DPRs so DRD/MCL data
        // still rolls up; counting it as a DPR inflates the PM-tab DPRs KPI.
        row.setDprCount((int) dprRepository.countByProjectIdAndReportDate(projectId, date));
        row.setRecomputedAt(Instant.now());

        DbsDailyProject saved = projectRepo.save(row);
        log.info("DBS project row saved projectId={} date={} sups={} income={} contribution={}",
            projectId, date, row.getSupervisorCount(), row.getTotalIncome(), contribution);

        // Phase 5: rebuild the equipment + manpower deployment register for this day.
        // Idempotent (delete + re-insert), runs at the tail of project recompute so the
        // register is consistent with the freshly-written aggregate rows.
        try {
            registerAggregationService.recompute(projectId, date);
        } catch (Exception ex) {
            log.warn("Register recompute failed projectId={} date={}: {}", projectId, date, ex.toString());
        }

        return saved;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static <T> void applyAggregates(java.util.function.Consumer<BigDecimal> setter,
                                             List<T> rows,
                                             java.util.function.Function<T, BigDecimal> extractor) {
        setter.accept(sumOf(rows, extractor));
    }

    /** Like {@link #applyAggregates} but adds a constant {@code extra} to the sum. */
    private static <T> void applyAggregatesWithExtra(java.util.function.Consumer<BigDecimal> setter,
                                                      List<T> rows,
                                                      java.util.function.Function<T, BigDecimal> extractor,
                                                      BigDecimal extra) {
        BigDecimal total = sumOf(rows, extractor).add(nz(extra));
        setter.accept(total.setScale(2, RoundingMode.HALF_UP));
    }

    private static <T> BigDecimal sumOf(List<T> rows,
                                         java.util.function.Function<T, BigDecimal> extractor) {
        BigDecimal sum = BigDecimal.ZERO;
        for (T r : rows) {
            sum = sum.add(nz(extractor.apply(r)));
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    /** Returns {@code max(a - b, 0)} — used to keep only the residual legacy portion. */
    private static BigDecimal positiveDiff(BigDecimal a, BigDecimal b) {
        BigDecimal diff = nz(a).subtract(nz(b));
        return diff.signum() < 0 ? BigDecimal.ZERO : diff.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * Phase 7: safe-divide helper that returns {@code numerator / denominator * 100} as a
     * percentage scaled to 4 decimal places. Returns {@code 0} when the denominator is
     * null, zero, or negative — never throws an {@link ArithmeticException}.
     */
    private static BigDecimal percentage(BigDecimal numerator, BigDecimal denominator) {
        BigDecimal d = nz(denominator);
        if (d.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        return nz(numerator).multiply(HUNDRED).divide(d, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(BigDecimal... parts) {
        BigDecimal s = BigDecimal.ZERO;
        for (BigDecimal p : parts) s = s.add(nz(p));
        return s.setScale(2, RoundingMode.HALF_UP);
    }

    private String serialize(SectionResult result) {
        try {
            return objectMapper.writeValueAsString(result.lines());
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialise section lines: {}", ex.toString());
            return "[]";
        }
    }

    private String serializeLines(BoqSectionResult result) {
        try {
            return objectMapper.writeValueAsString(result.lines());
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialise BOQ section lines: {}", ex.toString());
            return "[]";
        }
    }

    private String serializeLines(List<SectionLine> lines) {
        try {
            return objectMapper.writeValueAsString(lines);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialise section lines: {}", ex.toString());
            return "[]";
        }
    }

    private static int safeHash(SectionResult result) {
        return result == null || result.lines() == null ? 0 : result.lines().hashCode();
    }
}
