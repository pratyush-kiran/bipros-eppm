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
 * (project, date, supervisor).
 *
 * <p><b>Cost rule:</b> {@code amount = nos × rate}. The DPR's {@code working_hours} column
 * is a logging field only and never enters cost or quantity math — mirroring the canonical
 * Resource Plan formula in {@code ResourceAssignmentCostRollupListener} where
 * {@code actualCost = rate × actualUnits}. Rate resolution chain:
 * {@code line_cost} (preferred — written by {@code DprCostFormulas}) →
 * {@code unit_rate} on the row → {@code manpower_role_rates.rate} →
 * {@code manpower_rate_masters.rate}.
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
        // Scalar subqueries for the rate fallback so each DPR row produces exactly
        // ONE result row. The previous LEFT JOIN onto manpower_rate_masters used
        // {@code mrm.role_id = m.role_id}, which multiplies the DPR row by N
        // whenever a role has N grade variants in the rate-master table
        // (e.g. Foreman with Skilled/Semi-skilled/Unskilled grades). Scalar
        // subqueries cap each fallback at one match by construction.
        try {
            String dprSql = """
                SELECT m.trade,
                       COALESCE(m.nos, 0)                                     AS nos,
                       m.unit_rate                                            AS row_rate,
                       m.line_cost                                            AS row_line_cost,
                       COALESCE(
                         (SELECT rrr.rate
                            FROM resource.manpower_role_rates rrr
                           WHERE rrr.id = m.manpower_role_rate_id),
                         (SELECT mrm.rate
                            FROM resource.manpower_rate_masters mrm
                           WHERE mrm.role_id = m.role_id AND mrm.active
                           ORDER BY mrm.rate DESC
                           LIMIT 1),
                         0
                       )                                                      AS fallback_rate,
                       COALESCE(
                         (SELECT rrr.unit
                            FROM resource.manpower_role_rates rrr
                           WHERE rrr.id = m.manpower_role_rate_id),
                         (SELECT mrm.unit
                            FROM resource.manpower_rate_masters mrm
                           WHERE mrm.role_id = m.role_id AND mrm.active
                           ORDER BY mrm.rate DESC
                           LIMIT 1),
                         'Day'
                       )                                                      AS unit
                FROM project.dpr_manpower m
                JOIN project.daily_progress_reports d ON d.id = m.dpr_id
                WHERE d.project_id = cast(:pid as uuid)
                  AND d.report_date = :dt
                  AND d.approval_status = 'APPROVED'
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
                BigDecimal rowRate = toBigDecimalNullable(r[2]);
                BigDecimal rowLineCost = toBigDecimalNullable(r[3]);
                BigDecimal fallbackRate = toBigDecimal(r[4]);
                String unit = (String) r[5];

                BigDecimal effectiveRate = rowRate != null && rowRate.signum() > 0 ? rowRate : fallbackRate;
                // Cost = Nos × Rate. line_cost is preferred (DprCostFormulas already wrote it),
                // but recompute from nos × rate when it's missing.
                BigDecimal amount = (rowLineCost != null && rowLineCost.signum() != 0)
                    ? rowLineCost.setScale(2, RoundingMode.HALF_UP)
                    : nos.multiply(effectiveRate).setScale(2, RoundingMode.HALF_UP);
                if (amount.signum() == 0 && effectiveRate.signum() == 0) {
                    log.warn("Section A manpower row has no resolvable rate: projectId={} date={} trade={} (nos={}). "
                            + "Configure manpower_role_rates / manpower_rate_masters for the role, or set unit_rate/line_cost on the DPR row.",
                        projectId, date, desc, nos);
                }
                lines.add(new SectionLine(desc, unit, effectiveRate, nos, amount));
                total = total.add(amount);
            }
        } catch (Exception ex) {
            log.warn("Section A DPR-manpower compute failed projectId={} supervisor={} date={}: {}",
                projectId, supervisorUserId, date, ex.toString());
        }

        // ── (2) Legacy DRD rows — project-only attribution (no supervisor FK). ────
        // Same scalar-subquery pattern as the DPR branch above — avoids row
        // multiplication when a role has multiple grade variants in the
        // rate-master table.
        if (supervisorUserId == null) {
            try {
                String drdSql = """
                    SELECT d.resource_description,
                           COALESCE(d.nos_deployed, 0)   AS nos,
                           COALESCE(
                             (SELECT r.rate
                                FROM resource.manpower_rate_masters r
                               WHERE r.role_id = d.resource_role_id AND r.active
                               ORDER BY r.rate DESC
                               LIMIT 1),
                             0
                           )                              AS rate,
                           COALESCE(
                             (SELECT r.unit
                                FROM resource.manpower_rate_masters r
                               WHERE r.role_id = d.resource_role_id AND r.active
                               ORDER BY r.rate DESC
                               LIMIT 1),
                             'Day'
                           )                              AS unit
                    FROM project.daily_resource_deployments d
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
                    BigDecimal rate = toBigDecimal(r[2]);
                    String unit = (String) r[3];
                    BigDecimal amount = nos.multiply(rate).setScale(2, RoundingMode.HALF_UP);
                    lines.add(new SectionLine(desc, unit, rate, nos, amount));
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
