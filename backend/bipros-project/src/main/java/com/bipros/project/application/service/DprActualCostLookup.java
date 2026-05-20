package com.bipros.project.application.service;

import com.bipros.project.domain.repository.DprEquipmentRepository;
import com.bipros.project.domain.repository.DprManpowerRepository;
import com.bipros.project.domain.repository.DprMaterialRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cross-module read API for DPR persisted {@code line_cost}. Both bipros-evm and bipros-cost
 * need to treat the sum of DPR child-row {@code line_cost} as the Actual Cost contribution of
 * supervisor daily reporting — the prior path (only {@code ActivityExpense.actualCost} +
 * {@code ResourceAssignment.actualCost}) leaves DPR cost stranded in dev installs where no
 * rollup job copies it into either source. This is the readout seam those modules use.
 *
 * <p>Lives in bipros-project so the DPR repositories stay encapsulated in their owning module
 * and so cross-module callers don't need to know which tables ({@code dpr_manpower},
 * {@code dpr_equipment}, {@code dpr_material}) carry the cost. The shape returned is keyed by
 * activityId, matching how EVM and Cost already group rollups.
 *
 * <p>Per-project lookups are emitted as maps so callers can iterate the activity list without an
 * N+1 query per activity.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DprActualCostLookup {

    private final DprManpowerRepository manpowerRepository;
    private final DprEquipmentRepository equipmentRepository;
    private final DprMaterialRepository materialRepository;

    @PersistenceContext
    private EntityManager em;

    /**
     * Sum of all DPR child {@code line_cost} values for the given (project, activity), across
     * manpower + equipment + material rows, PLUS APPROVED manual expenses logged against the
     * activity in {@code dbs.dbs_manual_expenses}. Returns {@link BigDecimal#ZERO} when nothing
     * to count.
     *
     * <p>Manual expenses are read via native SQL (instead of a repository) to avoid a cyclic
     * dependency on bipros-dbs from bipros-project.
     */
    public BigDecimal sumByActivity(UUID projectId, UUID activityId) {
        if (projectId == null || activityId == null) return BigDecimal.ZERO;
        BigDecimal mp = nz(manpowerRepository.sumLineCostByProjectAndActivity(projectId, activityId));
        BigDecimal eq = nz(equipmentRepository.sumLineCostByProjectAndActivity(projectId, activityId));
        BigDecimal mt = nz(materialRepository.sumLineCostByProjectAndActivity(projectId, activityId));
        BigDecimal manual = sumManualExpensesByActivity(projectId, activityId);
        return mp.add(eq).add(mt).add(manual);
    }

    @SuppressWarnings("unchecked")
    private BigDecimal sumManualExpensesByActivity(UUID projectId, UUID activityId) {
        try {
            Object raw = em.createNativeQuery(
                    "SELECT COALESCE(SUM(amount), 0) FROM dbs.dbs_manual_expenses "
                        + "WHERE project_id = :pid AND activity_id = :aid "
                        + "AND approval_status = 'APPROVED'")
                .setParameter("pid", projectId)
                .setParameter("aid", activityId)
                .getSingleResult();
            return raw instanceof BigDecimal b ? b : new BigDecimal(raw.toString());
        } catch (Exception ex) {
            // Table not yet present (fresh DB before migrations) — return zero gracefully.
            return BigDecimal.ZERO;
        }
    }

    /**
     * Map of activityId → sum(line_cost) for every activity in the project that has at least one
     * DPR row with a non-null cost. Activities with no DPR cost are absent from the map; callers
     * should treat missing entries as zero.
     *
     * <p>Implemented as a single grouped native query per child table (3 queries total) instead of
     * one query per activity (N+1).
     */
    public Map<UUID, BigDecimal> sumByActivity(UUID projectId) {
        Map<UUID, BigDecimal> out = new HashMap<>();
        accumulate(out, "project.dpr_manpower", projectId);
        accumulate(out, "project.dpr_equipment", projectId);
        accumulate(out, "project.dpr_material", projectId);
        accumulateManual(out, projectId);
        return out;
    }

    @SuppressWarnings("unchecked")
    private void accumulateManual(Map<UUID, BigDecimal> sink, UUID projectId) {
        try {
            String sql = "SELECT activity_id, COALESCE(SUM(amount), 0) "
                    + "FROM dbs.dbs_manual_expenses "
                    + "WHERE project_id = :projectId "
                    + "  AND activity_id IS NOT NULL "
                    + "  AND approval_status = 'APPROVED' "
                    + "GROUP BY activity_id";
            List<Object[]> rows = em.createNativeQuery(sql)
                    .setParameter("projectId", projectId)
                    .getResultList();
            for (Object[] r : rows) {
                UUID activityId = (UUID) r[0];
                BigDecimal amount = r[1] instanceof BigDecimal b ? b : new BigDecimal(r[1].toString());
                sink.merge(activityId, amount, BigDecimal::add);
            }
        } catch (Exception ex) {
            // dbs.dbs_manual_expenses missing (fresh DB) — skip silently.
        }
    }

    /** Project-level total — used by {@code CostService.getCostSummary} for the actual rollup. */
    public BigDecimal sumByProject(UUID projectId) {
        if (projectId == null) return BigDecimal.ZERO;
        BigDecimal mp = nz(manpowerRepository.sumLineCostByProject(projectId));
        BigDecimal eq = nz(equipmentRepository.sumLineCostByProject(projectId));
        BigDecimal mt = nz(materialRepository.sumLineCostByProject(projectId));
        return mp.add(eq).add(mt);
    }

    @SuppressWarnings("unchecked")
    private void accumulate(Map<UUID, BigDecimal> sink, String childTable, UUID projectId) {
        // Cross-schema join: the child tables live in the project schema and key off dpr_id;
        // activity_id and project_id are on daily_progress_reports. activity_id can be null
        // on legacy rows — we skip those (no useful EVM bucket to land them in).
        String sql = "SELECT d.activity_id, COALESCE(SUM(c.line_cost), 0) "
                + "FROM " + childTable + " c "
                + "JOIN project.daily_progress_reports d ON d.id = c.dpr_id "
                + "WHERE d.project_id = :projectId "
                + "  AND d.activity_id IS NOT NULL "
                + "  AND c.line_cost IS NOT NULL "
                + "GROUP BY d.activity_id";
        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("projectId", projectId)
                .getResultList();
        for (Object[] r : rows) {
            UUID activityId = (UUID) r[0];
            BigDecimal amount = r[1] instanceof BigDecimal b ? b : new BigDecimal(r[1].toString());
            sink.merge(activityId, amount, BigDecimal::add);
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
