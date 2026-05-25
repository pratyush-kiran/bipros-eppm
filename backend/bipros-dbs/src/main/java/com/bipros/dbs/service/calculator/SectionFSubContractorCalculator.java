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
 * Section F — Sub-Contractor (PM scope only). Sums the day's DPR sub-contractor
 * entries grouped by {@code (sub-contractor master, work-type)}, multiplied by
 * the rate snapshotted on the planned {@code ActivitySubContractorAssignment}.
 *
 * <p>Rate resolution: planned-assignment {@code rate_per_unit} (locked at plan time).
 * Unit and work-type are read from the same assignment snapshot. SC name/code are
 * denormalised onto {@code DprSubContractor} so a deleted master doesn't lose
 * historical attribution.
 *
 * <p>Imputed income side {@code = SUM(qty × boq_rate)} from the linked BOQ item.
 * Used for the PM tab's per-SC margin display only; PM Total Income is sourced
 * from {@link SectionFBoqCalculator} at project scope (which already includes
 * the SC portion at full qty × boq_rate).
 *
 * <p>If the planned assignment is missing (orphaned DPR row), the line is
 * skipped — {@code DailyProgressReportService.saveSubContractors} validation
 * prevents this on insert, but a defensive guard avoids crashing day-totals
 * for legacy data.
 */
@Slf4j
@Component
public class SectionFSubContractorCalculator {

    @PersistenceContext
    private EntityManager em;

    @SuppressWarnings("unchecked")
    public SubContractorSectionResult compute(UUID projectId, LocalDate date) {
        try {
            String sql = """
                SELECT c.sub_contractor_master_id                  AS sc_master_id,
                       c.sub_contractor_code                       AS sc_code,
                       c.sub_contractor_name                       AS sc_name,
                       a.work_type_name                            AS work_type_name,
                       a.unit                                       AS unit,
                       a.rate_per_unit                             AS sc_rate,
                       COALESCE(b.boq_rate, 0)                     AS boq_rate,
                       COALESCE(SUM(c.quantity), 0)                AS qty,
                       COALESCE(SUM(c.quantity * a.rate_per_unit), 0) AS sc_expense,
                       COALESCE(SUM(c.quantity * COALESCE(b.boq_rate, 0)), 0) AS sc_imputed_income
                  FROM project.dpr_sub_contractor c
                  JOIN project.daily_progress_reports d ON d.id = c.dpr_id
                  LEFT JOIN resource.activity_sub_contractor_assignments a
                         ON a.id = c.activity_sub_contractor_assignment_id
                  LEFT JOIN project.boq_items b ON b.id = d.boq_item_id
                 WHERE d.project_id = cast(:pid as uuid)
                   AND d.report_date = :dt
                   AND a.id IS NOT NULL
                 GROUP BY c.sub_contractor_master_id, c.sub_contractor_code, c.sub_contractor_name,
                          a.work_type_name, a.unit, a.rate_per_unit, b.boq_rate
                """;

            List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("pid", projectId.toString())
                .setParameter("dt", date)
                .getResultList();

            List<SubContractLine> lines = new ArrayList<>();
            BigDecimal totalExpense = BigDecimal.ZERO;
            BigDecimal totalImputedIncome = BigDecimal.ZERO;

            for (Object[] r : rows) {
                String scCode = (String) r[1];
                String scName = (String) r[2];
                String workTypeName = (String) r[3];
                String unit = (String) r[4];
                BigDecimal scRate = toBigDecimal(r[5]);
                BigDecimal boqRate = toBigDecimal(r[6]);
                BigDecimal qty = toBigDecimal(r[7]);
                BigDecimal scExpense = toBigDecimal(r[8]).setScale(2, RoundingMode.HALF_UP);
                BigDecimal scImputedIncome = toBigDecimal(r[9]).setScale(2, RoundingMode.HALF_UP);
                BigDecimal scMargin = scImputedIncome.subtract(scExpense).setScale(2, RoundingMode.HALF_UP);

                lines.add(new SubContractLine(
                    scCode, scName, workTypeName, unit,
                    qty, scRate, scExpense, boqRate, scImputedIncome, scMargin));

                totalExpense = totalExpense.add(scExpense);
                totalImputedIncome = totalImputedIncome.add(scImputedIncome);
            }

            return new SubContractorSectionResult(
                totalExpense.setScale(2, RoundingMode.HALF_UP),
                totalImputedIncome.setScale(2, RoundingMode.HALF_UP),
                lines);
        } catch (Exception ex) {
            log.warn("Section F (Sub-Contractor) compute failed projectId={} date={}: {}",
                projectId, date, ex.toString());
            return SubContractorSectionResult.empty();
        }
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return BigDecimal.ZERO;
    }
}
