package com.bipros.project.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Shared actual-cost query for BOQ items. Both {@link BoqRebuildService} (batch rebuild) and
 * {@link com.bipros.project.application.listener.BoqActualRateRecalcListener} (live event) use
 * this single method so they can never diverge.
 *
 * <p>Cost basis: {@code line_cost} from approved DPR manpower / equipment / material rows
 * (clean, master-derived snapshot) plus MCL {@code line_cost} and sub-contractor
 * {@code quantity × rate_per_unit}. The {@code effective_rate} column is intentionally not read.
 */
@Component
@RequiredArgsConstructor
public class BoqActualCostQuery {

    @PersistenceContext
    private EntityManager em;

    /**
     * Sum of actual cost for {@code boqItemId} across all five resource families, restricted to
     * APPROVED DPRs. Returns zero (never null) when there are no matching rows.
     *
     * @param projectId  kept for symmetry with the qty repo method; unused in the SQL
     * @param boqItemId  the BOQ item whose actual cost to sum
     */
    public BigDecimal sumActualCost(UUID projectId, UUID boqItemId) {
        String sql =
            "SELECT COALESCE(SUM(u.contrib),0) FROM ("
            + " SELECT c.line_cost AS contrib FROM project.dpr_manpower c"
            + " JOIN project.daily_progress_reports d ON c.dpr_id=d.id"
            + " WHERE d.boq_item_id=:boqItemId AND d.approval_status='APPROVED'"
            + " UNION ALL"
            + " SELECT c.line_cost FROM project.dpr_equipment c"
            + " JOIN project.daily_progress_reports d ON c.dpr_id=d.id"
            + " WHERE d.boq_item_id=:boqItemId AND d.approval_status='APPROVED'"
            + " UNION ALL"
            + " SELECT c.line_cost FROM project.dpr_material c"
            + " JOIN project.daily_progress_reports d ON c.dpr_id=d.id"
            + " WHERE d.boq_item_id=:boqItemId AND d.approval_status='APPROVED'"
            + " UNION ALL"
            + " SELECT COALESCE(mcl.line_cost,0) FROM resource.material_consumption_logs mcl"
            + " WHERE mcl.activity_id IN (SELECT DISTINCT d2.activity_id"
            + " FROM project.daily_progress_reports d2"
            + " WHERE d2.boq_item_id=:boqItemId AND d2.activity_id IS NOT NULL"
            + " AND d2.approval_status='APPROVED')"
            + " AND mcl.line_cost IS NOT NULL"
            + " UNION ALL"
            + " SELECT (c.quantity * COALESCE(a.rate_per_unit,0)) FROM project.dpr_sub_contractor c"
            + " JOIN project.daily_progress_reports d ON c.dpr_id=d.id"
            + " LEFT JOIN resource.activity_sub_contractor_assignments a"
            + " ON a.id=c.activity_sub_contractor_assignment_id"
            + " WHERE d.boq_item_id=:boqItemId AND d.approval_status='APPROVED'"
            + ") u";
        Query q = em.createNativeQuery(sql);
        q.setParameter("boqItemId", boqItemId);
        return toBigDecimal(q.getSingleResult());
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(o.toString());
    }
}
