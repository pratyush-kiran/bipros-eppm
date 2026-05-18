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
 * Section B — Catering / Admin. There is no DPR row type for ADMIN/CATERING yet (the DPR row
 * tables only model manpower / equipment / material). The only source is
 * {@code project.daily_resource_deployments} with {@code resource_type IN ('ADMIN','CATERING')}.
 *
 * <p>DRD has no supervisor FK, so to avoid N-counting at the project rollup we attribute these
 * rows only when {@code supervisorUserId} is null (project-level rollup). Per-supervisor rows
 * carry zero admin cost.
 */
@Slf4j
@Component
public class SectionBAdminCalculator {

    @PersistenceContext
    private EntityManager em;

    @SuppressWarnings("unchecked")
    public SectionResult compute(UUID projectId, UUID supervisorUserId, LocalDate date) {
        // Project-only attribution: DRD has no supervisor FK; emit costs only at the
        // project rollup to keep per-supervisor + project sum self-consistent.
        if (supervisorUserId != null) {
            return SectionResult.empty();
        }
        try {
            String sql = """
                SELECT d.resource_description,
                       COALESCE(d.nos_deployed, 0)   AS nos,
                       COALESCE(d.hours_worked, 0)   AS hrs,
                       COALESCE(r.rate, 0)           AS rate,
                       COALESCE(r.unit, 'day')       AS unit
                FROM project.daily_resource_deployments d
                LEFT JOIN resource.manpower_rate_masters r ON r.role_id = d.resource_role_id
                WHERE d.project_id = :pid
                  AND d.log_date = :dt
                  AND d.resource_type IN ('ADMIN', 'CATERING')
                """;
            List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("pid", projectId)
                .setParameter("dt", date)
                .getResultList();

            if (rows.isEmpty()) {
                return SectionResult.empty();
            }
            List<SectionLine> lines = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
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
            return new SectionResult(total.setScale(2, RoundingMode.HALF_UP), lines);
        } catch (Exception ex) {
            log.warn("Section B (admin) compute failed projectId={} date={}: {}", projectId, date, ex.toString());
            return SectionResult.empty();
        }
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return BigDecimal.ZERO;
    }
}
