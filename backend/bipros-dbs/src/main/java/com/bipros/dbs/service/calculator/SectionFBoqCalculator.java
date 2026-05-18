package com.bipros.dbs.service.calculator;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Section F — BOQ Work. Joins DPRs for the day to {@code boq_items} via
 * {@code boq_item_id}. Returns three independent rollups:
 *
 * <ul>
 *   <li>for-the-day = SUM(qty_executed × boq_rate) across DPRs on that date</li>
 *   <li>planned-to-date = SUM(boq_amount) over distinct BOQ items touched on that date</li>
 *   <li>achieved-to-date = SUM(qty_executed_to_date × boq_rate) over those items</li>
 * </ul>
 *
 * When {@code supervisorUserId} is non-null the DPR scan filters by it; otherwise
 * aggregates all DPRs for the day.
 */
@Slf4j
@Component
public class SectionFBoqCalculator {

    @PersistenceContext
    private EntityManager em;

    @SuppressWarnings("unchecked")
    public BoqSectionResult compute(UUID projectId, UUID supervisorUserId, LocalDate date) {
        try {
            String sql = """
                SELECT b.item_no,
                       b.description,
                       b.unit,
                       COALESCE(b.boq_rate, 0)              AS rate,
                       COALESCE(d.qty_executed, 0)          AS qty_today,
                       COALESCE(b.boq_amount, 0)            AS planned_amount,
                       COALESCE(b.qty_executed_to_date, 0)  AS qty_to_date,
                       b.id                                  AS boq_id
                FROM project.daily_progress_reports d
                JOIN project.boq_items b ON b.id = d.boq_item_id
                WHERE d.project_id = cast(:pid as uuid)
                  AND d.report_date = :dt
                  AND (cast(:sup as uuid) IS NULL OR d.supervisor_user_id = cast(:sup as uuid))
                """;
            // Casts are required so PostgreSQL can infer the parameter type when :sup is null —
            // without them the driver sends untyped placeholders and the planner aborts with
            // "could not determine data type of parameter".
            List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("pid", projectId.toString())
                .setParameter("dt", date)
                .setParameter("sup", supervisorUserId == null ? null : supervisorUserId.toString())
                .getResultList();

            List<SectionLine> lines = new ArrayList<>();
            BigDecimal forTheDay = BigDecimal.ZERO;
            BigDecimal achieved = BigDecimal.ZERO;
            BigDecimal planned = BigDecimal.ZERO;
            Set<UUID> seenBoq = new HashSet<>();

            for (Object[] r : rows) {
                String itemNo = (String) r[0];
                String desc = (String) r[1];
                String unit = (String) r[2];
                BigDecimal rate = toBigDecimal(r[3]);
                BigDecimal qtyToday = toBigDecimal(r[4]);
                BigDecimal plannedAmount = toBigDecimal(r[5]);
                BigDecimal qtyToDate = toBigDecimal(r[6]);
                UUID boqId = (UUID) r[7];

                BigDecimal todayAmount = qtyToday.multiply(rate).setScale(2, RoundingMode.HALF_UP);
                forTheDay = forTheDay.add(todayAmount);

                String label = (itemNo != null ? itemNo + " " : "") + (desc != null ? desc : "");
                lines.add(new SectionLine(label, unit, rate, qtyToday, todayAmount));

                if (seenBoq.add(boqId)) {
                    planned = planned.add(plannedAmount);
                    achieved = achieved.add(qtyToDate.multiply(rate));
                }
            }
            return new BoqSectionResult(
                forTheDay.setScale(2, RoundingMode.HALF_UP),
                planned.setScale(2, RoundingMode.HALF_UP),
                achieved.setScale(2, RoundingMode.HALF_UP),
                lines);
        } catch (Exception ex) {
            log.warn("Section F (BOQ) compute failed projectId={} supervisor={} date={}: {}",
                projectId, supervisorUserId, date, ex.toString());
            return BoqSectionResult.empty();
        }
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return BigDecimal.ZERO;
    }
}
