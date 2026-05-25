package com.bipros.bootstrap.stage;

import com.bipros.bootstrap.BootstrapApplication;
import com.bipros.bootstrap.Stage;
import com.bipros.bootstrap.input.ParsedDatasetStore;
import com.bipros.bootstrap.model.ParsedDataset;
import com.bipros.resource.domain.model.NormCombination;
import com.bipros.resource.domain.model.WorkActivity;
import com.bipros.resource.domain.repository.WorkActivityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Component
@Slf4j
@RequiredArgsConstructor
public class Stage3WorkActivities implements Stage {

    private final ParsedDatasetStore store;
    private final WorkActivityRepository workActivityRepository;

    public static void main(String[] args) {
        BootstrapApplication.runStage(Stage3WorkActivities.class, args);
    }

    @Override
    @Transactional
    public void run() {
        ParsedDataset d = store.load();
        int inserted = 0;
        int updated = 0;
        int skipped = 0;

        for (ParsedDataset.WorkActivityInfo w : d.workActivities) {
            if (w.code == null || w.code.isBlank()) {
                log.warn("Skipping work activity with blank code: {}", w.name);
                continue;
            }
            String code = w.code.trim().toUpperCase().replaceAll("\\s+", "");
            NormCombination combo = parseCombination(w.normCombination);

            WorkActivity existing = workActivityRepository.findByCode(code).orElse(null);
            if (existing == null) {
                WorkActivity wa = WorkActivity.builder()
                        .code(code)
                        .name(w.name)
                        .defaultUnit(w.defaultUnit)
                        .discipline(w.discipline)
                        .normCombination(combo)
                        .active(true)
                        .build();
                workActivityRepository.save(wa);
                inserted++;
            } else {
                boolean changed = false;
                if (!Objects.equals(existing.getName(), w.name)) {
                    existing.setName(w.name);
                    changed = true;
                }
                if (!Objects.equals(existing.getDefaultUnit(), w.defaultUnit)) {
                    existing.setDefaultUnit(w.defaultUnit);
                    changed = true;
                }
                if (!Objects.equals(existing.getDiscipline(), w.discipline)) {
                    existing.setDiscipline(w.discipline);
                    changed = true;
                }
                if (existing.getNormCombination() != combo) {
                    existing.setNormCombination(combo);
                    changed = true;
                }
                if (changed) {
                    workActivityRepository.save(existing);
                    updated++;
                } else {
                    skipped++;
                }
            }
        }
        log.info("Stage 3 complete — inserted={}, updated={}, unchanged={}", inserted, updated, skipped);
    }

    private static NormCombination parseCombination(String raw) {
        if (raw == null || raw.isBlank()) return NormCombination.SERIES;
        try {
            return NormCombination.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NormCombination.SERIES;
        }
    }
}
