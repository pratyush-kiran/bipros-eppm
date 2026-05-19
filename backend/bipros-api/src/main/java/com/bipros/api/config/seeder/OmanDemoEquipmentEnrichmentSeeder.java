package com.bipros.api.config.seeder;

import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceEquipmentDetails;
import com.bipros.resource.domain.model.ResourceOwnership;
import com.bipros.resource.domain.repository.ResourceEquipmentDetailsRepository;
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
import java.time.LocalDate;
import java.util.Optional;

/**
 * Lights up the Equipment KPI block on the Insights tab for {@code OMAN-DEMO-KHASAB}.
 *
 * <p>{@link com.bipros.api.service.EquipmentKpiService} reads three pieces of
 * master data: {@link Resource#costPerUnit} (idle-cost calculations / owned vs
 * rented cost), {@link ResourceEquipmentDetails#nextServiceDate} (Service Due
 * card), and ownership classification (Owned-vs-Rented chart). On the existing
 * demo seed every {@code OMD-EQ-*} Resource has a placeholder
 * {@code ResourceEquipmentDetails} row with all those fields null, so all three
 * Equipment KPI cards render zero.
 *
 * <p>This seeder back-fills sensible defaults: hourly rate from a per-equipment-
 * type table; {@code nextServiceDate} spread deterministically across the next
 * 90 days (so the "next 7 days" filter catches roughly a third of the fleet);
 * ownership split 70 % OWNED / 30 % HIRED to give the chart both slices.
 * Idempotent — leaves already-populated fields alone.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Profile("seed")
@Order(210)
public class OmanDemoEquipmentEnrichmentSeeder implements CommandLineRunner {

    private final ProjectRepository projectRepository;
    private final ResourceRepository resourceRepository;
    private final ResourceEquipmentDetailsRepository detailsRepository;

    @Override
    @Transactional
    public void run(String... args) {
        Optional<Project> projectOpt =
                projectRepository.findByCode(OmanDemoProjectSeeder.PROJECT_CODE);
        if (projectOpt.isEmpty()) {
            log.warn("[oman-demo equipment-enrich] project {} not found, skipping",
                    OmanDemoProjectSeeder.PROJECT_CODE);
            return;
        }

        LocalDate today = LocalDate.now();
        int costUpdates = 0;
        int detailsCreates = 0;
        int detailsUpdates = 0;
        int idx = 0;

        for (Resource r : resourceRepository.findAll()) {
            if (r.getCode() == null || !r.getCode().startsWith("OMD-EQ-")) continue;

            // 1) Ensure Resource.costPerUnit is non-null (used for idle-cost in EquipmentKpiService).
            if (r.getCostPerUnit() == null || r.getCostPerUnit().signum() <= 0) {
                BigDecimal hourly = hourlyHireRateFor(r);
                r.setCostPerUnit(hourly);
                try {
                    resourceRepository.save(r);
                    costUpdates++;
                } catch (Exception e) {
                    log.warn("[oman-demo equipment-enrich] costPerUnit save failed for {}: {}",
                            r.getCode(), e.getMessage());
                }
            }

            // 2) Ensure equipment-details row exists with realistic ownership + next-service date.
            // Service dates are spread across days 1..90 from today modulo the running
            // index — about a third land within the next 7 days so "Service Due" lights up.
            int dayOffset = (idx * 7) % 90 + 1;
            ResourceOwnership ownership = (idx % 10 < 3) ? ResourceOwnership.HIRED : ResourceOwnership.OWNED;
            Optional<ResourceEquipmentDetails> existing = detailsRepository.findById(r.getId());
            try {
                if (existing.isEmpty()) {
                    detailsRepository.save(ResourceEquipmentDetails.builder()
                            .resourceId(r.getId())
                            .ownershipType(ownership)
                            .nextServiceDate(today.plusDays(dayOffset))
                            .lastServiceDate(today.minusDays(60))
                            .quantityAvailable(1)
                            .fuelLitresPerHour(new BigDecimal("8.50"))
                            .standardOutputPerDay(standardOutputFor(r))
                            .standardOutputUnit("Hour")
                            .build());
                    detailsCreates++;
                } else {
                    ResourceEquipmentDetails d = existing.get();
                    boolean dirty = false;
                    if (d.getNextServiceDate() == null) {
                        d.setNextServiceDate(today.plusDays(dayOffset));
                        dirty = true;
                    }
                    if (d.getOwnershipType() == null) {
                        d.setOwnershipType(ownership);
                        dirty = true;
                    }
                    if (d.getStandardOutputPerDay() == null) {
                        d.setStandardOutputPerDay(standardOutputFor(r));
                        d.setStandardOutputUnit("Hour");
                        dirty = true;
                    }
                    if (d.getFuelLitresPerHour() == null) {
                        d.setFuelLitresPerHour(new BigDecimal("8.50"));
                        dirty = true;
                    }
                    if (dirty) {
                        detailsRepository.save(d);
                        detailsUpdates++;
                    }
                }
            } catch (Exception e) {
                log.warn("[oman-demo equipment-enrich] details save failed for {}: {}",
                        r.getCode(), e.getMessage());
            }
            idx++;
        }

        log.info("[oman-demo equipment-enrich] wrote {} cost_per_unit updates, "
                        + "{} details created, {} details updated",
                costUpdates, detailsCreates, detailsUpdates);
    }

    /** OMR per hour by equipment-role family. Targets Equipment KPI Idle Cost realism. */
    private static BigDecimal hourlyHireRateFor(Resource r) {
        String roleCode = r.getRole() != null ? r.getRole().getCode() : "";
        double hourly = switch (roleCode) {
            case "EARTH_MOVING"      -> 18.0;
            case "PAVING_EQUIPMENT"  -> 22.0;
            case "TRANSPORT_VEHICLES"-> 8.0;
            case "CRANES_LIFTING"    -> 28.0;
            case "CONCRETE_EQUIPMENT"-> 16.0;
            default -> 12.0;
        };
        return BigDecimal.valueOf(hourly).setScale(4, RoundingMode.HALF_UP);
    }

    /** Daily standard-output benchmark — drives Equipment Productivity Index (EPI%). */
    private static BigDecimal standardOutputFor(Resource r) {
        String roleCode = r.getRole() != null ? r.getRole().getCode() : "";
        double perDay = switch (roleCode) {
            case "EARTH_MOVING"      -> 240.0; // m3/day for an excavator
            case "PAVING_EQUIPMENT"  -> 180.0; // tonnes/day for a paver
            case "TRANSPORT_VEHICLES"-> 28.0;  // trips/day for a tipper
            case "CRANES_LIFTING"    -> 40.0;  // lifts/day
            case "CONCRETE_EQUIPMENT"-> 80.0;  // m3/day for a mixer
            default -> 60.0;
        };
        return BigDecimal.valueOf(perDay).setScale(4, RoundingMode.HALF_UP);
    }
}
