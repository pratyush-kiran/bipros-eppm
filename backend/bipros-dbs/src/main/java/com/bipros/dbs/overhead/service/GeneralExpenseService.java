package com.bipros.dbs.overhead.service;

import com.bipros.common.event.DprMutationType;
import com.bipros.common.event.GeneralExpenseLoggedEvent;
import com.bipros.common.event.ProjectCreatedEvent;
import com.bipros.dbs.overhead.domain.model.GeneralExpenseFormulaType;
import com.bipros.dbs.overhead.domain.model.GeneralExpenseMonthlyEntry;
import com.bipros.dbs.overhead.domain.model.GeneralExpensePlanItem;
import com.bipros.dbs.overhead.domain.model.GeneralExpenseUnit;
import com.bipros.dbs.overhead.domain.repository.GeneralExpenseMonthlyEntryRepository;
import com.bipros.dbs.overhead.domain.repository.GeneralExpensePlanItemRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CRUD + seeding for the Section G (General Expenses) plan items and monthly
 * actuals. Publishes {@link GeneralExpenseLoggedEvent} after every monthly
 * entry mutation so {@code DbsRecomputeListener} can refresh the daily
 * project rollups for the affected month.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeneralExpenseService {

    private final GeneralExpensePlanItemRepository planRepo;
    private final GeneralExpenseMonthlyEntryRepository entryRepo;
    private final ApplicationEventPublisher events;

    // ── plan items ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<GeneralExpensePlanItem> listPlanItems(UUID projectId) {
        return planRepo.findByProjectIdOrderBySortOrderAsc(projectId);
    }

    @Transactional
    public GeneralExpensePlanItem createPlanItem(UUID projectId, GeneralExpensePlanItem in) {
        in.setProjectId(projectId);
        if (in.getActive() == null) in.setActive(true);
        if (in.getFormulaType() == null) in.setFormulaType(GeneralExpenseFormulaType.NONE);
        if (in.getUnit() == null) in.setUnit(GeneralExpenseUnit.MONTH);
        if (in.getSortOrder() == null) {
            int next = (int) planRepo.countByProjectId(projectId) + 1;
            in.setSortOrder(next);
        }
        return planRepo.save(in);
    }

    @Transactional
    public GeneralExpensePlanItem updatePlanItem(UUID projectId, UUID itemId, GeneralExpensePlanItem patch) {
        GeneralExpensePlanItem row = planRepo.findById(itemId)
            .filter(p -> projectId.equals(p.getProjectId()))
            .orElseThrow(() -> new EntityNotFoundException("plan item " + itemId));
        if (patch.getDescription() != null) row.setDescription(patch.getDescription());
        if (patch.getUnit() != null) row.setUnit(patch.getUnit());
        if (patch.getRate() != null) row.setRate(patch.getRate());
        if (patch.getPlanQty() != null) row.setPlanQty(patch.getPlanQty());
        if (patch.getPlanAmount() != null) row.setPlanAmount(patch.getPlanAmount());
        if (patch.getFormulaType() != null) row.setFormulaType(patch.getFormulaType());
        if (patch.getFormulaPct() != null) row.setFormulaPct(patch.getFormulaPct());
        if (patch.getSortOrder() != null) row.setSortOrder(patch.getSortOrder());
        if (patch.getActive() != null) row.setActive(patch.getActive());
        return planRepo.save(row);
    }

    @Transactional
    public void deletePlanItem(UUID projectId, UUID itemId) {
        planRepo.findById(itemId)
            .filter(p -> projectId.equals(p.getProjectId()))
            .ifPresent(planRepo::delete);
    }

    // ── monthly entries ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<GeneralExpenseMonthlyEntry> listActuals(UUID projectId, Integer yearMonth) {
        return entryRepo.findByProjectIdAndYearMonth(projectId, yearMonth);
    }

    @Transactional
    public GeneralExpenseMonthlyEntry upsertMonthly(UUID projectId, UUID planItemId,
                                                    Integer yearMonth,
                                                    GeneralExpenseMonthlyEntry patch,
                                                    UUID currentUserId) {
        Optional<GeneralExpenseMonthlyEntry> existing =
            entryRepo.findByPlanItemIdAndYearMonth(planItemId, yearMonth);
        DprMutationType mutation = existing.isPresent() ? DprMutationType.UPDATED : DprMutationType.CREATED;
        GeneralExpenseMonthlyEntry row = existing.orElseGet(() -> GeneralExpenseMonthlyEntry.builder()
            .projectId(projectId)
            .planItemId(planItemId)
            .yearMonth(yearMonth)
            .build());
        row.setAchievedQty(patch.getAchievedQty());
        row.setAchievedAmount(patch.getAchievedAmount());
        row.setNotes(patch.getNotes());
        row.setLoggedByUserId(currentUserId);
        GeneralExpenseMonthlyEntry saved = entryRepo.save(row);
        events.publishEvent(new GeneralExpenseLoggedEvent(projectId, yearMonth, planItemId, mutation));
        return saved;
    }

    @Transactional
    public void deleteMonthly(UUID projectId, UUID planItemId, Integer yearMonth) {
        entryRepo.findByPlanItemIdAndYearMonth(planItemId, yearMonth)
            .filter(e -> projectId.equals(e.getProjectId()))
            .ifPresent(e -> {
                entryRepo.delete(e);
                events.publishEvent(new GeneralExpenseLoggedEvent(
                    projectId, yearMonth, planItemId, DprMutationType.DELETED));
            });
    }

    @Transactional(readOnly = true)
    public BigDecimal monthlyTotal(UUID projectId, Integer yearMonth) {
        BigDecimal sum = entryRepo.sumAchievedAmount(projectId, yearMonth);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    // ── seeding on project create ───────────────────────────────────────────

    @EventListener
    @Transactional
    public void onProjectCreated(ProjectCreatedEvent ev) {
        if (planRepo.countByProjectId(ev.projectId()) > 0) {
            return; // already seeded — idempotent
        }
        int order = 1;
        for (GeneralExpenseDefaults.Item def : GeneralExpenseDefaults.ITEMS) {
            GeneralExpensePlanItem row = GeneralExpensePlanItem.builder()
                .projectId(ev.projectId())
                .description(def.description())
                .unit(def.unit())
                .rate(BigDecimal.ONE)
                .planQty(BigDecimal.ZERO)
                .planAmount(BigDecimal.ZERO)
                .formulaType(def.formulaType())
                .formulaPct(def.formulaPct())
                .sortOrder(order++)
                .active(true)
                .build();
            planRepo.save(row);
        }
        log.info("Section G seeded {} default items for project {}", order - 1, ev.projectId());
    }
}
