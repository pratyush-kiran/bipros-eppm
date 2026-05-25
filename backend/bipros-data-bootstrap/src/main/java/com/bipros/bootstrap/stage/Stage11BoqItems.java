package com.bipros.bootstrap.stage;

import com.bipros.bootstrap.BootstrapApplication;
import com.bipros.bootstrap.Stage;
import com.bipros.bootstrap.input.ParsedDatasetStore;
import com.bipros.bootstrap.model.ParsedDataset;
import com.bipros.project.domain.model.BoqItem;
import com.bipros.project.domain.model.BoqStatus;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.model.WbsNode;
import com.bipros.project.domain.repository.BoqItemRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.project.domain.repository.WbsNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Stage 11 — upsert {@code BoqItem} rows for the project, one per
 * {@link ParsedDataset.BoqInfo}. Idempotent: keyed by (projectId, itemNo); existing rows
 * have their mutable fields updated.
 *
 * <p>Derived columns ({@code qtyExecutedToDate}, {@code actualRate}, {@code percentComplete},
 * {@code costVariance}) are left untouched — {@code DprBoqSyncListener} and
 * {@code BoqActualRateRecalcListener} populate them once DPRs land in Stage 12.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class Stage11BoqItems implements Stage {

    private final ParsedDatasetStore store;
    private final ProjectRepository projectRepository;
    private final WbsNodeRepository wbsNodeRepository;
    private final BoqItemRepository boqItemRepository;

    public static void main(String[] args) {
        BootstrapApplication.runStage(Stage11BoqItems.class, args);
    }

    @Override
    @Transactional
    public void run() {
        ParsedDataset d = store.load();
        if (d.project == null || d.project.code == null || d.project.code.isBlank()) {
            throw new IllegalStateException("ParsedDataset.project.code is required");
        }

        Project project = projectRepository.findByCode(d.project.code)
                .orElseThrow(() -> new IllegalStateException(
                        "Project " + d.project.code + " not found — run Stage 5 first"));
        UUID projectId = project.getId();

        // WBS chapter code → node id lookup.
        Map<String, UUID> wbsByCode = new HashMap<>();
        for (WbsNode n : wbsNodeRepository.findByProjectIdOrderBySortOrder(projectId)) {
            if (n.getCode() != null) wbsByCode.put(n.getCode(), n.getId());
        }

        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        for (ParsedDataset.BoqInfo b : d.boqItems) {
            if (b.itemNo == null || b.itemNo.isBlank()) {
                log.warn("Stage 11 — skipping BOQ row with blank itemNo");
                skipped++;
                continue;
            }

            UUID wbsNodeId = b.wbsChapterCode == null ? null : wbsByCode.get(b.wbsChapterCode);
            if (b.wbsChapterCode != null && wbsNodeId == null) {
                log.warn("Stage 11 — BOQ {}: WBS chapter '{}' not found; leaving wbsNodeId null",
                        b.itemNo, b.wbsChapterCode);
            }

            BigDecimal boqQty = b.boqQty != null ? b.boqQty : BigDecimal.ZERO;
            BigDecimal boqRate = b.boqRate != null ? b.boqRate : BigDecimal.ZERO;
            BigDecimal budgetedRate = b.budgetedRate != null ? b.budgetedRate : boqRate;
            BigDecimal boqAmount = boqQty.multiply(boqRate);
            BigDecimal budgetedAmount = boqQty.multiply(budgetedRate);

            Optional<BoqItem> existing = boqItemRepository.findByProjectIdAndItemNo(projectId, b.itemNo);
            BoqItem item = existing.orElseGet(BoqItem::new);
            boolean isNew = existing.isEmpty();

            item.setProjectId(projectId);
            item.setItemNo(b.itemNo);
            item.setDescription(b.description != null ? b.description : b.itemNo);
            item.setUnit(b.unit != null ? b.unit : "Nos");
            item.setChapter(b.chapter);
            item.setWbsNodeId(wbsNodeId);
            item.setBoqQty(boqQty);
            item.setBoqRate(boqRate);
            item.setBoqAmount(boqAmount);
            item.setBudgetedRate(budgetedRate);
            item.setBudgetedAmount(budgetedAmount);
            if (isNew) {
                // Initialise execution-side counters on first insert; downstream listeners own
                // the lifecycle once DPRs start landing.
                item.setQtyExecutedToDate(BigDecimal.ZERO);
                item.setActualRate(BigDecimal.ZERO);
                item.setActualAmount(BigDecimal.ZERO);
            }
            if (item.getStatus() == null) {
                item.setStatus(BoqStatus.ACTIVE);
            }

            boqItemRepository.save(item);
            if (isNew) inserted++;
            else updated++;
        }

        log.info("Stage 11 — BOQ items for project {}: inserted={} updated={} skipped={} (parsed={})",
                project.getCode(), inserted, updated, skipped, d.boqItems.size());
    }
}
