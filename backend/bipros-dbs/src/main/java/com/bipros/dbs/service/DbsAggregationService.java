package com.bipros.dbs.service;

import com.bipros.dbs.domain.model.DbsDailyEngineer;
import com.bipros.dbs.domain.model.DbsDailyProject;
import com.bipros.dbs.domain.model.DbsDailySupervisor;
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

    private final DbsDailySupervisorRepository supervisorRepo;
    private final DbsDailyEngineerRepository engineerRepo;
    private final DbsDailyProjectRepository projectRepo;
    private final SectionAManpowerCalculator manpowerCalc;
    private final SectionBAdminCalculator adminCalc;
    private final SectionCMachineryCalculator machineryCalc;
    private final SectionDFuelCalculator fuelCalc;
    private final SectionEMaterialCalculator materialCalc;
    private final SectionFBoqCalculator boqCalc;
    private final ProjectTeamService projectTeamService;
    private final ObjectMapper objectMapper;

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

        row.setManpowerAmount(manpower.totalAmount());
        row.setAdminAmount(admin.totalAmount());
        row.setMachineryAmount(machinery.totalAmount());
        row.setFuelAmount(fuel.totalAmount());
        row.setMaterialAmount(material.totalAmount());
        row.setSubcontractAmount(BigDecimal.ZERO);
        row.setBoqForTheDayAmount(boq.forTheDayAmount());
        row.setBoqPlannedAmount(boq.plannedAmount());
        row.setBoqAchievedAmount(boq.achievedAmount());

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
        applyAggregates(row::setBoqPlannedAmount, supRows, DbsDailySupervisor::getBoqPlannedAmount);
        applyAggregates(row::setBoqAchievedAmount, supRows, DbsDailySupervisor::getBoqAchievedAmount);
        applyAggregates(row::setTotalExpense, supRows, DbsDailySupervisor::getTotalExpense);
        applyAggregates(row::setTotalIncome, supRows, DbsDailySupervisor::getTotalIncome);

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
        applyAggregates(row::setBoqPlannedAmount, supRows, DbsDailySupervisor::getBoqPlannedAmount);
        applyAggregates(row::setBoqAchievedAmount, supRows, DbsDailySupervisor::getBoqAchievedAmount);

        BigDecimal totalExpense = sum(
            nz(row.getManpowerAmount()), nz(row.getAdminAmount()), nz(row.getMachineryAmount()),
            nz(row.getFuelAmount()), nz(row.getMaterialAmount()), nz(row.getSubcontractAmount()));
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
        row.setDprCount(supRows.size());
        row.setRecomputedAt(Instant.now());

        DbsDailyProject saved = projectRepo.save(row);
        log.info("DBS project row saved projectId={} date={} sups={} income={} contribution={}",
            projectId, date, row.getSupervisorCount(), row.getTotalIncome(), contribution);
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

    private static int safeHash(SectionResult result) {
        return result == null || result.lines() == null ? 0 : result.lines().hashCode();
    }
}
