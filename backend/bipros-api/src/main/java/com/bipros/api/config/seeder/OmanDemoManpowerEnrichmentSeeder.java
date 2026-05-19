package com.bipros.api.config.seeder;

import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.enums.AttendanceStatus;
import com.bipros.resource.domain.model.enums.PaymentMode;
import com.bipros.resource.domain.model.enums.SalaryType;
import com.bipros.resource.domain.model.enums.ShiftType;
import com.bipros.resource.domain.model.manpower.ManpowerAttendance;
import com.bipros.resource.domain.model.manpower.ManpowerFinancials;
import com.bipros.resource.domain.repository.ManpowerAttendanceRepository;
import com.bipros.resource.domain.repository.ManpowerFinancialsRepository;
import com.bipros.resource.domain.repository.ResourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Lights up the Manpower KPI block on the Insights tab for {@code OMAN-DEMO-KHASAB}.
 *
 * <p>{@link com.bipros.api.service.ManpowerKpiService} pulls Workforce Utilisation
 * (productive ÷ available) from {@link ManpowerAttendance.workingHoursPerDay} and
 * Total Labour Cost / LCV / LCPI from {@link ManpowerFinancials.hourlyRate}. If
 * either row is missing the KPI silently computes zero — exactly the empty state
 * the user reported.
 *
 * <p>This seeder ensures every {@code OMD-LAB-*} master Resource created by
 * {@link OmanDemoActivityResourceSeeder} has both companion rows. Idempotent: a
 * resource that already has both rows is left untouched.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("seed")
@Order(209)
public class OmanDemoManpowerEnrichmentSeeder implements CommandLineRunner {

    private static final BigDecimal DEFAULT_WORKING_HOURS_PER_DAY = new BigDecimal("8.00");
    private static final BigDecimal MTD_DEFAULT = BigDecimal.ZERO;
    private static final BigDecimal LEAVE_BALANCE_DEFAULT = new BigDecimal("12.00");
    private static final String CURRENCY_OMR = "OMR";

    private final ProjectRepository projectRepository;
    private final ResourceRepository resourceRepository;
    private final ManpowerAttendanceRepository attendanceRepository;
    private final ManpowerFinancialsRepository financialsRepository;

    @Override
    @Transactional
    public void run(String... args) {
        Optional<Project> projectOpt =
                projectRepository.findByCode(OmanDemoProjectSeeder.PROJECT_CODE);
        if (projectOpt.isEmpty()) {
            log.warn("[oman-demo manpower-enrich] project {} not found, skipping",
                    OmanDemoProjectSeeder.PROJECT_CODE);
            return;
        }

        int attendanceWritten = 0;
        int financialsWritten = 0;
        int skipped = 0;

        for (Resource r : resourceRepository.findAll()) {
            if (r.getCode() == null || !r.getCode().startsWith("OMD-LAB-")) continue;

            if (!attendanceRepository.existsById(r.getId())) {
                try {
                    attendanceRepository.save(ManpowerAttendance.builder()
                            .resourceId(r.getId())
                            .dailyAttendanceStatus(AttendanceStatus.PRESENT)
                            .workingHoursPerDay(DEFAULT_WORKING_HOURS_PER_DAY)
                            .shiftType(ShiftType.DAY)
                            .totalWorkHoursMtd(MTD_DEFAULT)
                            .overtimeHoursMtd(MTD_DEFAULT)
                            .leaveBalance(LEAVE_BALANCE_DEFAULT)
                            .build());
                    attendanceWritten++;
                } catch (Exception e) {
                    log.warn("[oman-demo manpower-enrich] attendance save failed for {} ({}): {}",
                            r.getCode(), r.getId(), e.getMessage());
                }
            } else {
                skipped++;
            }

            if (!financialsRepository.existsById(r.getId())) {
                BigDecimal hourly = hourlyRateFor(r);
                BigDecimal otRate = hourly.multiply(new BigDecimal("1.5"))
                        .setScale(4, RoundingMode.HALF_UP);
                try {
                    financialsRepository.save(ManpowerFinancials.builder()
                            .resourceId(r.getId())
                            .salaryType(SalaryType.HOURLY)
                            .hourlyRate(hourly)
                            .overtimeRate(otRate)
                            .currency(CURRENCY_OMR)
                            .paymentMode(PaymentMode.BANK)
                            .build());
                    financialsWritten++;
                } catch (Exception e) {
                    log.warn("[oman-demo manpower-enrich] financials save failed for {} ({}): {}",
                            r.getCode(), r.getId(), e.getMessage());
                }
            }
        }

        log.info("[oman-demo manpower-enrich] wrote {} attendance + {} financials rows ({} skipped — already present)",
                attendanceWritten, financialsWritten, skipped);
    }

    /**
     * Per-role hourly rate in OMR. Aligns with the daily-rate ladder in
     * {@code OmanDemoActivityResourceSeeder.roleDailyRateOmr} divided by 8.
     * Falls back to the Resource's own {@code costPerUnit} if the role code is
     * unknown, then to a per-trade default.
     */
    private static BigDecimal hourlyRateFor(Resource r) {
        String roleCode = r.getRole() != null ? r.getRole().getCode() : null;
        double hourly = switch (roleCode == null ? "" : roleCode) {
            case "SUPERVISOR" -> 5.625;
            case "FOREMAN"    -> 4.000;
            case "OPERATOR"   -> 3.500;
            case "DRIVER"     -> 2.750;
            case "WELDER"     -> 3.000;
            case "ELECTRICIAN"-> 3.250;
            case "SKILLED_LABOUR"   -> 2.250;
            case "UNSKILLED_LABOUR" -> 1.125;
            default -> 0;
        };
        if (hourly > 0) return BigDecimal.valueOf(hourly).setScale(4, RoundingMode.HALF_UP);
        if (r.getCostPerUnit() != null && r.getCostPerUnit().signum() > 0) {
            return r.getCostPerUnit().setScale(4, RoundingMode.HALF_UP);
        }
        return new BigDecimal("1.750").setScale(4, RoundingMode.HALF_UP);
    }
}
