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
import java.time.LocalDate;
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
     * manpower + equipment + material rows. Returns {@link BigDecimal#ZERO} when there's nothing
     * to count (rather than null, which would force every caller to coalesce).
     */
    public BigDecimal sumByActivity(UUID projectId, UUID activityId) {
        if (projectId == null || activityId == null) return BigDecimal.ZERO;
        BigDecimal mp = nz(manpowerRepository.sumLineCostByProjectAndActivity(projectId, activityId));
        BigDecimal eq = nz(equipmentRepository.sumLineCostByProjectAndActivity(projectId, activityId));
        BigDecimal mt = nz(materialRepository.sumLineCostByProjectAndActivity(projectId, activityId));
        return mp.add(eq).add(mt);
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
        return out;
    }

    /**
     * Per-day sum of DPR child {@code line_cost} for the project. Used by Cost-tab Period
     * Breakdown and Cash Flow S-Curve to bucket DPR actuals into financial periods by
     * {@code report_date}. Days with zero cost are absent from the map (callers treat as zero).
     */
    public Map<LocalDate, BigDecimal> sumByProjectGroupedByDate(UUID projectId) {
        Map<LocalDate, BigDecimal> out = new HashMap<>();
        if (projectId == null) return out;
        accumulateByDate(out, "project.dpr_manpower", projectId);
        accumulateByDate(out, "project.dpr_equipment", projectId);
        accumulateByDate(out, "project.dpr_material", projectId);
        return out;
    }

    @SuppressWarnings("unchecked")
    private void accumulateByDate(Map<LocalDate, BigDecimal> sink, String childTable, UUID projectId) {
        String sql = "SELECT d.report_date, COALESCE(SUM(c.line_cost), 0) "
                + "FROM " + childTable + " c "
                + "JOIN project.daily_progress_reports d ON d.id = c.dpr_id "
                + "WHERE d.project_id = :projectId "
                + "  AND d.report_date IS NOT NULL "
                + "  AND c.line_cost IS NOT NULL "
                + "GROUP BY d.report_date";
        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("projectId", projectId)
                .getResultList();
        for (Object[] r : rows) {
            LocalDate date = (r[0] instanceof LocalDate ld) ? ld
                    : (r[0] instanceof java.sql.Date sd ? sd.toLocalDate() : null);
            if (date == null) continue;
            BigDecimal amount = r[1] instanceof BigDecimal b ? b : new BigDecimal(r[1].toString());
            sink.merge(date, amount, BigDecimal::add);
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
