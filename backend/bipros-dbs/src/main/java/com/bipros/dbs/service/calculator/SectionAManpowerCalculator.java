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
 * Section A — Manpower. Primary source is {@code project.dpr_manpower} (per-row line items on
 * each DPR), joined to {@code project.daily_progress_reports} so we can filter by
 * (project, date, supervisor). Rate resolution falls back through:
 * {@code line_cost} → ({@code nos × working_hours × unit_rate}) →
 * {@code manpower_role_rate_id → manpower_role_rates.rate} →
 * {@code role_id → manpower_rate_masters.rate}.
 *
 * <p>Legacy {@code project.daily_resource_deployments} (LABOR/MANPOWER) rows are folded in as
 * a secondary source, but only when {@code supervisorUserId} is null — DRD carries no
 * supervisor FK, so attributing DRD rows to each individual supervisor would N-count them at
 * the project rollup. Project-only attribution avoids double-counting.
 */
@Slf4j
@Component
public class SectionAManpowerCalculator {

    @PersistenceContext
    private EntityManager em;

    @SuppressWarnings("unchecked")
    public SectionResult compute(UUID projectId, UUID supervisorUserId, LocalDate date) {
        List<SectionLine> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        // ── (1) DPR manpower rows (the real cost source) ──────────────────────────
        try {
            String dprSql = """
                SELECT m.trade,
                       COALESCE(m.nos, 0)                                     AS nos,
                       COALESCE(m.working_hours, 0)                           AS hrs,
                       m.unit_rate                                            AS row_rate,
                       m.line_cost                                            AS row_line_cost,
                       COALESCE(rrr.rate, mrm.rate, 0)                        AS fallback_rate,
                       COALESCE(rrr.unit, mrm.unit, 'hr')                     AS unit
                FROM project.dpr_manpower m
                JOIN project.daily_progress_reports d ON d.id = m.dpr_id
                LEFT JOIN resource.manpower_role_rates rrr ON rrr.id = m.manpower_role_rate_id
                LEFT JOIN resource.manpower_rate_masters mrm ON mrm.role_id = m.role_id
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
                    log.warn("Section A manpower row has no resolvable rate: projectId={} date={} trade={} (nos={}, hrs={}). "
                            + "Configure manpower_role_rates / manpower_rate_masters for the role, or set unit_rate/line_cost on the DPR row.",
                        projectId, date, desc, nos, hrs);
                }
                lines.add(new SectionLine(desc, unit, effectiveRate, qty, amount));
                total = total.add(amount);
            }
        } catch (Exception ex) {
            log.warn("Section A DPR-manpower compute failed projectId={} supervisor={} date={}: {}",
                projectId, supervisorUserId, date, ex.toString());
        }

        // ── (2) Legacy DRD rows — project-only attribution (no supervisor FK). ────
        if (supervisorUserId == null) {
            try {
                String drdSql = """
                    SELECT d.resource_description,
                           COALESCE(d.nos_deployed, 0)   AS nos,
                           COALESCE(d.hours_worked, 0)   AS hrs,
                           COALESCE(r.rate, 0)           AS rate,
                           COALESCE(r.unit, 'hr')        AS unit
                    FROM project.daily_resource_deployments d
                    LEFT JOIN resource.manpower_rate_masters r ON r.role_id = d.resource_role_id
                    WHERE d.project_id = :pid
                      AND d.log_date = :dt
                      AND d.resource_type IN ('LABOR', 'MANPOWER')
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
                log.warn("Section A DRD-manpower compute failed projectId={} date={}: {}",
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
