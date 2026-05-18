package com.bipros.dbs.service.calculator;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Section E — Material consumption. Primary source is {@code project.dpr_material} joined to
 * {@code project.daily_progress_reports} so we can filter by (project, date, supervisor).
 * Rate resolution: {@code line_cost} → ({@code quantity × unit_rate}) →
 * {@code material_role_variant_id → material_role_variants.rate}.
 *
 * <p>Legacy {@code resource.material_consumption_logs} (MCL) is folded in only when
 * {@code supervisorUserId} is null — MCL has a {@code received_by_user_id} but it carries
 * receiver semantics, not the supervisor-who-executed-work semantics that DBS attributes by.
 * Project-only attribution avoids double-counting at the project rollup.
 *
 * <p><b>Bug 5 fix (2026-05-17):</b> Diesel / fuel / petrol / HSD rows are EXCLUDED from
 * Section E because {@link SectionDFuelCalculator} already books them as fuel. Without
 * this filter the same DPR material row appeared in both Section D (Fuel) and Section E
 * (Material), double-counting the line in Total Expense. The exclusion mirrors the WHERE
 * clause used by the fuel calculator's fallback path so the two stay in lockstep.
 */
@Slf4j
@Component
public class SectionEMaterialCalculator {

    @PersistenceContext
    private EntityManager em;

    @SuppressWarnings("unchecked")
    public SectionResult compute(UUID projectId, UUID supervisorUserId, LocalDate date) {
        List<SectionLine> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        // ── (1) DPR material rows (the real cost source) ──────────────────────────
        try {
            // Bug 5 fix: exclude diesel / fuel / petrol / HSD — those are booked as Section D
            // (Fuel). Without this filter the same row would appear in both Section D and
            // Section E, inflating Total Expense by the duplicate amount.
            String dprSql = """
                SELECT mat.material_name,
                       COALESCE(mat.unit, '')                                 AS unit,
                       mat.unit_rate                                          AS row_rate,
                       COALESCE(mat.quantity, 0)                              AS qty,
                       mat.line_cost                                          AS row_line_cost,
                       COALESCE(mrv.rate, 0)                                  AS fallback_rate
                FROM project.dpr_material mat
                JOIN project.daily_progress_reports d ON d.id = mat.dpr_id
                LEFT JOIN resource.material_role_variants mrv ON mrv.id = mat.material_role_variant_id
                WHERE d.project_id = cast(:pid as uuid)
                  AND d.report_date = :dt
                  AND (cast(:sup as uuid) IS NULL OR d.supervisor_user_id = cast(:sup as uuid))
                  AND NOT (mat.material_name ILIKE '%diesel%'
                        OR mat.material_name ILIKE '%fuel%'
                        OR mat.material_name ILIKE '%petrol%'
                        OR mat.material_name ILIKE '%hsd%')
                """;
            List<Object[]> rows = em.createNativeQuery(dprSql)
                .setParameter("pid", projectId.toString())
                .setParameter("dt", date)
                .setParameter("sup", supervisorUserId == null ? null : supervisorUserId.toString())
                .getResultList();
            for (Object[] r : rows) {
                String name = (String) r[0];
                String unit = (String) r[1];
                BigDecimal rowRate = toBigDecimalNullable(r[2]);
                BigDecimal qty = toBigDecimal(r[3]);
                BigDecimal rowLineCost = toBigDecimalNullable(r[4]);
                BigDecimal fallbackRate = toBigDecimal(r[5]);

                BigDecimal effectiveRate = rowRate != null && rowRate.signum() > 0 ? rowRate : fallbackRate;
                BigDecimal amount;
                if (rowLineCost != null && rowLineCost.signum() != 0) {
                    amount = rowLineCost.setScale(2, RoundingMode.HALF_UP);
                } else {
                    amount = qty.multiply(effectiveRate).setScale(2, RoundingMode.HALF_UP);
                }
                if (amount.signum() == 0 && effectiveRate.signum() == 0) {
                    log.warn("Section E material row has no resolvable rate: projectId={} date={} material={} (qty={}). "
                            + "Configure material_role_variants for the role, or set unit_rate/line_cost on the DPR row.",
                        projectId, date, name, qty);
                }
                lines.add(new SectionLine(name, unit, effectiveRate, qty, amount));
                total = total.add(amount);
            }
        } catch (Exception ex) {
            log.warn("Section E DPR-material compute failed projectId={} supervisor={} date={}: {}",
                projectId, supervisorUserId, date, ex.toString());
        }

        // ── (2) Legacy MCL rows — project-only attribution (no clean supervisor FK). ─
        if (supervisorUserId == null) {
            try {
                // Bug 5 fix: same fuel-name exclusion as the DPR-material query above so MCL
                // diesel/fuel rows aren't double-counted alongside Section D fuel.
                String mclSql = """
                    SELECT material_name,
                           COALESCE(unit, '')           AS unit,
                           COALESCE(unit_rate, 0)       AS unit_rate,
                           COALESCE(consumed, 0)        AS consumed,
                           COALESCE(line_cost, 0)       AS line_cost
                    FROM resource.material_consumption_logs
                    WHERE project_id = :pid
                      AND log_date = :dt
                      AND NOT (material_name ILIKE '%diesel%'
                            OR material_name ILIKE '%fuel%'
                            OR material_name ILIKE '%petrol%'
                            OR material_name ILIKE '%hsd%')
                    """;
                List<Object[]> rows = em.createNativeQuery(mclSql)
                    .setParameter("pid", projectId)
                    .setParameter("dt", date)
                    .getResultList();
                for (Object[] r : rows) {
                    String name = (String) r[0];
                    String unit = (String) r[1];
                    BigDecimal rate = toBigDecimal(r[2]);
                    BigDecimal consumed = toBigDecimal(r[3]);
                    BigDecimal lineCost = toBigDecimal(r[4]).setScale(2, RoundingMode.HALF_UP);
                    lines.add(new SectionLine(name, unit, rate, consumed, lineCost));
                    total = total.add(lineCost);
                }
            } catch (Exception ex) {
                log.warn("Section E MCL compute failed projectId={} date={}: {}",
                    projectId, date, ex.toString());
            }
        }

        return new SectionResult(total.setScale(2, RoundingMode.HALF_UP), lines);
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return BigDecimal.ZERO;
    }

    private static BigDecimal toBigDecimalNullable(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }
}
