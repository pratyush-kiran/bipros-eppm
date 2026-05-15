package com.bipros.siteops.application.service;

import com.bipros.siteops.domain.model.ChecklistTemplate;
import com.bipros.siteops.domain.model.ChecklistTemplateItem;
import com.bipros.siteops.domain.model.ChecklistType;
import com.bipros.siteops.domain.model.EvidenceType;
import com.bipros.siteops.domain.repository.ChecklistTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Seeds the three canonical site-checklist templates idempotently at app startup. Skipped per
 * template if its code is already present, so re-runs are safe and never duplicate rows.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChecklistTemplateSeeder {

    private final ChecklistTemplateRepository templateRepository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        seedTemplate("PRE_CONCRETE", "Pre-Concrete Pour Checklist", ChecklistType.PRE_CONCRETE, List.of(
                "Formwork stable",
                "Rebar tied",
                "Cover blocks placed",
                "Embeds verified"
        ), EvidenceType.NOTE);

        seedTemplate("EXCAVATION", "Excavation Readiness Checklist", ChecklistType.EXCAVATION, List.of(
                "Permit available",
                "Utilities cleared",
                "Shoring adequate",
                "Spoil placement OK"
        ), EvidenceType.NONE);

        seedTemplate("SHUTTERING", "Shuttering Inspection Checklist", ChecklistType.SHUTTERING, List.of(
                "Plumb",
                "Line and level",
                "Tie rods correct",
                "Release agent applied"
        ), EvidenceType.NONE);
    }

    private void seedTemplate(String code, String name, ChecklistType type, List<String> itemLabels, EvidenceType evidence) {
        if (templateRepository.findByCode(code).isPresent()) {
            log.debug("Checklist template {} already seeded — skipping", code);
            return;
        }
        ChecklistTemplate t = new ChecklistTemplate();
        t.setCode(code);
        t.setName(name);
        t.setType(type);
        t.setActive(true);
        List<ChecklistTemplateItem> items = new ArrayList<>();
        int seq = 1;
        for (String label : itemLabels) {
            ChecklistTemplateItem item = new ChecklistTemplateItem();
            item.setSequence(seq++);
            item.setLabel(label);
            item.setMandatory(true);
            item.setEvidenceType(evidence);
            items.add(item);
        }
        t.setItems(items);
        templateRepository.save(t);
        log.info("Seeded checklist template {} with {} items", code, items.size());
    }
}
