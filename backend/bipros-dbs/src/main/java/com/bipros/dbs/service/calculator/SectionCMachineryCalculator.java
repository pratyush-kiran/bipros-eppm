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
 * Section C — Machinery / Equipment. Primary source is {@code project.dpr_equipment}
 * joined to {@code project.daily_progress_reports} so we can filter by (project, date,
 * supervisor). Rate resolution mirrors Section A: {@code line_cost} →
 * ({@code nos × working_hours × unit_rate}) → {@code equipment_role_variant_id →
 * equipment_role_variants.rate} → {@code equipment_rate_masters.id = role_id}.
 *
 * <p>Legacy {@code project.daily_resource_deployments} (EQUIPMENT) rows are folded in only
 * when {@code supervisorUserId} is null — DRD has no supervisor FK, so project-only
 * attribution avoids N-counting at the project rollup.
 */
@Slf4j
@Component
public class SectionCMachineryCalculator {

    @PersistenceContext
    private EntityManager em;

    @SuppressWarnings("unchecked")
    public SectionResult compute(UUID projectId, UUID supervisorUserId, LocalDate date) {
        List<SectionLine> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        // ── (1) DPR equipment rows (the real cost source) ─────────────────────────
        try {
            String dprSql = """
                SELECT eq.equipment_type,
                       COALESCE(eq.nos, 0)                                    AS nos,
                       COALESCE(eq.working_hours, 0)                          AS hrs,
                       eq.unit_rate                                           AS row_rate,
                       eq.line_cost                                           AS row_line_cost,
                       COALESCE(erv.rate, erm.rate, 0)                        AS fallback_rate,
                       COALESCE(erv.unit, erm.unit, 'hr')                     AS unit
                FROM project.dpr_equipment eq
                JOIN project.daily_progress_reports d ON d.id = eq.dpr_id
                LEFT JOIN resource.equipment_role_variants erv ON erv.id = eq.equipment_role_variant_id
                LEFT JOIN resource.equipment_rate_masters erm ON erm.id = eq.role_id
                WHERE d.project_id = cast(:pid as uuid)
                  AND d.report_date = :dt
                  AND (cast(:sup as uuid) IS NULL OR d.supervisor_user_id = cast(:sup as uuid))
                """;
            List<Object[]> rows = em.createNativeQuery(dprSql)
                .setParameter("pid", projectId.toString())
                .setParameter("dt", date)
                .setParameter("sup", supervisorUserId == null ? null : supervisorUserId.toString())
                .getResultList();
            for (Object[] r : rows) {
                String desc = (String) r[0];
                BigDecimal nos = toBigDecimal(r[1]);
                BigDecimal hrs = toBigDecimal(r[2]);
                BigDecimal rowRate = toBigDecimalNullable(r[3]);
                BigDecimal rowLineCost = toBigDecimalNullable(r[4]);
                BigDecimal fallbackRate = toBigDecimal(r[5]);
                String unit = (String) r[6];

                BigDecimal qty = nos.multiply(hrs);
                BigDecimal effectiveRate = rowRate != null && rowRate.signum() > 0 ? rowRate : fallbackRate;
                BigDecimal amount;
                if (rowLineCost != null && rowLineCost.signum() != 0) {
                    amount = rowLineCost.setScale(2, RoundingMode.HALF_UP);
                } else {
                    amount = qty.multiply(effectiveRate).setScale(2, RoundingMode.HALF_UP);
                }
                if (amount.signum() == 0 && effectiveRate.signum() == 0) {
                    log.warn("Section C equipment row has no resolvable rate: projectId={} date={} equipment={} (nos={}, hrs={}). "
                            + "Configure equipment_role_variants / equipment_rate_masters for the role, or set unit_rate/line_cost on the DPR row.",
                        projectId, date, desc, nos, hrs);
                }
                lines.add(new SectionLine(desc, unit, effectiveRate, qty, amount));
                total = total.add(amount);
            }
        } catch (Exception ex) {
            log.warn("Section C DPR-equipment compute failed projectId={} supervisor={} date={}: {}",
                projectId, supervisorUserId, date, ex.toString());
        }

        // ── (2) Legacy DRD rows — project-only attribution. ───────────────────────
        if (supervisorUserId == null) {
            try {
                String drdSql = """
                    SELECT d.resource_description,
                           COALESCE(d.nos_deployed, 0)   AS nos,
                           COALESCE(d.hours_worked, 0)   AS hrs,
                           COALESCE(e.rate, 0)           AS rate,
                           COALESCE(e.unit, 'hr')        AS unit
                    FROM project.daily_resource_deployments d
                    LEFT JOIN resource.equipment_rate_masters e
                           ON e.id = d.resource_role_id OR e.id = d.resource_id
                    WHERE d.project_id = :pid
                      AND d.log_date = :dt
                      AND d.resource_type = 'EQUIPMENT'
                    """;
                List<Object[]> rows = em.createNativeQuery(drdSql)
                    .setParameter("pid", projectId)
                    .setParameter("dt", date)
                    .getResultList();
                for (Object[] r : rows) {
                    String desc = (String) r[0];
                    BigDecimal nos = toBigDecimal(r[1]);
                    BigDecimal hrs = toBigDecimal(r[2]);
                    BigDecimal rate = toBigDecimal(r[3]);
                    String unit = (String) r[4];
                    BigDecimal qty = nos.multiply(hrs);
                    BigDecimal amount = qty.multiply(rate).setScale(2, RoundingMode.HALF_UP);
                    lines.add(new SectionLine(desc, unit, rate, qty, amount));
                    total = total.add(amount);
                }
            } catch (Exception ex) {
                log.warn("Section C DRD-equipment compute failed projectId={} date={}: {}",
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
