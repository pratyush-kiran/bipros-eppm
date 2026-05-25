package com.bipros.bootstrap.stage;

import com.bipros.bootstrap.BootstrapApplication;
import com.bipros.bootstrap.Stage;
import com.bipros.bootstrap.input.ParsedDatasetStore;
import com.bipros.bootstrap.model.ParsedDataset;
import com.bipros.resource.domain.model.ProductivityNorm;
import com.bipros.resource.domain.model.ProductivityNormType;
import com.bipros.resource.domain.model.WorkActivity;
import com.bipros.resource.domain.repository.ProductivityNormRepository;
import com.bipros.resource.domain.repository.WorkActivityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

@Component
@Slf4j
@RequiredArgsConstructor
public class Stage4ProductivityNorms implements Stage {

    private final ParsedDatasetStore store;
    private final WorkActivityRepository workActivityRepository;
    private final ProductivityNormRepository productivityNormRepository;

    public static void main(String[] args) {
        BootstrapApplication.runStage(Stage4ProductivityNorms.class, args);
    }

    @Override
    @Transactional
    public void run() {
        ParsedDataset d = store.load();
        int mpInserted = 0, mpUpdated = 0, mpSkipped = 0;
        int eqInserted = 0, eqUpdated = 0, eqSkipped = 0;

        for (ParsedDataset.WorkActivityInfo w : d.workActivities) {
            if (w.code == null || w.code.isBlank()) continue;
            String code = w.code.trim().toUpperCase().replaceAll("\\s+", "");
            WorkActivity wa = workActivityRepository.findByCode(code).orElse(null);
            if (wa == null) {
                log.warn("Stage 4 — work activity {} missing, run Stage 3 first", code);
                continue;
            }

            if (w.outputPerManPerDay != null) {
                int r = upsertUnscoped(wa, ProductivityNormType.MANPOWER, w.outputPerManPerDay);
                if (r == 1) mpInserted++; else if (r == 2) mpUpdated++; else mpSkipped++;
            }
            if (w.outputPerHour != null) {
                int r = upsertUnscoped(wa, ProductivityNormType.EQUIPMENT, w.outputPerHour);
                if (r == 1) eqInserted++; else if (r == 2) eqUpdated++; else eqSkipped++;
            }
        }
        log.info("Stage 4 complete — manpower: inserted={}, updated={}, unchanged={}; equipment: inserted={}, updated={}, unchanged={}",
                mpInserted, mpUpdated, mpSkipped, eqInserted, eqUpdated, eqSkipped);
    }

    private static final double WORKING_HOURS_PER_DAY = 8.0;

    /** Returns 1=inserted, 2=updated, 0=unchanged. */
    private int upsertUnscoped(WorkActivity wa, ProductivityNormType type, BigDecimal value) {
        Optional<ProductivityNorm> existing = productivityNormRepository
                .findFirstByWorkActivityIdAndRoleIdIsNullAndCategoryIdIsNullAndGradeIdIsNullAndMakeIsNullAndModelIsNullAndNormType(
                        wa.getId(), type);

        // For equipment, outputPerDay is the field the UI treats as mandatory; outputPerHour is
        // derived. We carry outputPerHour through from the parsed dataset (computed as
        // qty / equipment-hours) and derive outputPerDay = outputPerHour × 8.
        BigDecimal eqPerDay = (type == ProductivityNormType.EQUIPMENT)
                ? value.multiply(BigDecimal.valueOf(WORKING_HOURS_PER_DAY)).setScale(2, java.math.RoundingMode.HALF_UP)
                : null;

        if (existing.isPresent()) {
            ProductivityNorm n = existing.get();
            boolean changed = false;
            if (type == ProductivityNormType.MANPOWER) {
                if (!eq(n.getOutputPerManPerDay(), value)) {
                    n.setOutputPerManPerDay(value);
                    changed = true;
                }
            } else {
                if (!eq(n.getOutputPerHour(), value)) {
                    n.setOutputPerHour(value);
                    changed = true;
                }
                if (!eq(n.getOutputPerDay(), eqPerDay)) {
                    n.setOutputPerDay(eqPerDay);
                    changed = true;
                }
                if (n.getWorkingHoursPerDay() == null
                        || Math.abs(n.getWorkingHoursPerDay() - WORKING_HOURS_PER_DAY) > 0.0001) {
                    n.setWorkingHoursPerDay(WORKING_HOURS_PER_DAY);
                    changed = true;
                }
            }
            if (changed) {
                productivityNormRepository.save(n);
                return 2;
            }
            return 0;
        }
        ProductivityNorm norm = ProductivityNorm.builder()
                .normType(type)
                .workActivity(wa)
                .activityName(wa.getName())
                .unit(wa.getDefaultUnit())
                .outputPerManPerDay(type == ProductivityNormType.MANPOWER ? value : null)
                .outputPerHour(type == ProductivityNormType.EQUIPMENT ? value : null)
                .crewSize(null)
                .outputPerDay(eqPerDay)
                .workingHoursPerDay(WORKING_HOURS_PER_DAY)
                .build();
        productivityNormRepository.save(norm);
        return 1;
    }

    private static boolean eq(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.compareTo(b) == 0;
    }
}
