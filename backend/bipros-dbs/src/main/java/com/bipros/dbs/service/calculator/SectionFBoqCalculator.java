package com.bipros.dbs.service.calculator;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Section F — BOQ Work. Joins DPRs for the day to {@code boq_items} via
 * {@code boq_item_id}.
 *
 * <p>This calculator produces two kinds of figures and they must not be confused:
 * <ul>
 *   <li><b>For-the-day:</b> {@code forTheDayAmount} / {@code directBoqAmount} /
 *       {@code prelimBoqAmount} — disjoint per supervisor, safe to sum across
 *       supervisors at engineer / CM / PM tiers.</li>
 *   <li><b>Cumulative:</b> {@code plannedAmount} (Σ unique boq_items.boq_amount)
 *       and {@code achievedAmount} (Σ unique qty_executed_to_date × boq_rate) —
 *       these are project-state values, so summing them across supervisors who
 *       touched the same BOQ item double-counts. To prevent that, this method
 *       returns {@code planned = 0} and {@code achieved = 0} when called with a
 *       non-null {@code supervisorUserId}. The PM / CM / Engineer rollups must
 *       call {@link #computeCumulativeForScope} to get deduped cumulative figures
 *       at their own scope.</li>
 * </ul>
 */
@Slf4j
@Component
public class SectionFBoqCalculator {

    @PersistenceContext
    private EntityManager em;

    @SuppressWarnings("unchecked")
    public BoqSectionResult compute(UUID projectId, UUID supervisorUserId, LocalDate date) {
        try {
            // Phase 7: LEFT JOIN activity.activities so the prelim flag is included per-row.
            // The join is on the DPR's activity_id (not the boq item) because the
            // is_preliminary flag lives on the Activity entity (DBS-Phase-2 column added by
            // changeset-2026-05-add-activity-preliminary.xml). DPRs that pre-date the
            // activity_id column (or whose activity link is null) bucket as direct (non-prelim).
            // Sub-contractor-driven workdone is netted out of the per-DPR qty at the
            // supervisor scope only (cast(:sup) IS NOT NULL). At project scope we keep
            // the full qty so PM "Total Income" represents the project's full BOQ revenue
            // (the SC-driven portion is also captured by SectionFSubContractorCalculator,
            // but as an expense — the income side flows through here).
            String sql = """
                SELECT b.item_no,
                       b.description,
                       b.unit,
                       COALESCE(b.boq_rate, 0)              AS rate,
                       (COALESCE(d.qty_executed, 0)
                          - CASE WHEN cast(:sup as uuid) IS NOT NULL
                                 THEN COALESCE(sc.sc_qty, 0)
                                 ELSE 0 END)                AS qty_today,
                       COALESCE(b.boq_amount, 0)            AS planned_amount,
                       COALESCE(b.qty_executed_to_date, 0)  AS qty_to_date,
                       b.id                                  AS boq_id,
                       COALESCE(a.is_preliminary, false)    AS is_preliminary
                FROM project.daily_progress_reports d
                JOIN project.boq_items b ON b.id = d.boq_item_id
                LEFT JOIN activity.activities a ON a.id = d.activity_id
                LEFT JOIN (
                    SELECT dpr_id, COALESCE(SUM(quantity), 0) AS sc_qty
                      FROM project.dpr_sub_contractor
                     GROUP BY dpr_id
                ) sc ON sc.dpr_id = d.id
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
            BigDecimal directBoq = BigDecimal.ZERO;
            BigDecimal prelimBoq = BigDecimal.ZERO;
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
                boolean preliminary = r[8] != null && (Boolean) r[8];

                BigDecimal todayAmount = qtyToday.multiply(rate).setScale(2, RoundingMode.HALF_UP);
                forTheDay = forTheDay.add(todayAmount);
                if (preliminary) {
                    prelimBoq = prelimBoq.add(todayAmount);
                } else {
                    directBoq = directBoq.add(todayAmount);
                }

                String label = (itemNo != null ? itemNo + " " : "") + (desc != null ? desc : "");
                lines.add(new SectionLine(label, unit, rate, qtyToday, todayAmount));

                // Project-scope only: cumulative planned/achieved are project-state values and
                // double-count when summed across supervisors that share a BOQ item. Per-tier
                // rollups must use computeCumulativeForScope instead.
                if (supervisorUserId == null && seenBoq.add(boqId)) {
                    planned = planned.add(plannedAmount);
                    achieved = achieved.add(qtyToDate.multiply(rate));
                }
            }
            return new BoqSectionResult(
                forTheDay.setScale(2, RoundingMode.HALF_UP),
                planned.setScale(2, RoundingMode.HALF_UP),
                achieved.setScale(2, RoundingMode.HALF_UP),
                directBoq.setScale(2, RoundingMode.HALF_UP),
                prelimBoq.setScale(2, RoundingMode.HALF_UP),
                lines);
        } catch (Exception ex) {
            log.warn("Section F (BOQ) compute failed projectId={} supervisor={} date={}: {}",
                projectId, supervisorUserId, date, ex.toString());
            return BoqSectionResult.empty();
        }
    }

    /**
     * Returns deduped {@code (planned, achieved)} BOQ figures for a given scope.
     *
     * <ul>
     *   <li>{@code supervisorIds == null}: project-wide — unique BOQ items touched by any
     *       DPR on (project, date).</li>
     *   <li>{@code supervisorIds.isEmpty()}: returns zeros (no DPRs → no scope).</li>
     *   <li>otherwise: unique BOQ items touched by any DPR whose
     *       {@code supervisor_user_id IN supervisorIds} on (project, date).</li>
     * </ul>
     *
     * Each BOQ item contributes its full {@code boq_amount} (planned) and
     * {@code qty_executed_to_date × boq_rate} (achieved) exactly once, regardless of
     * how many DPRs in scope touched it.
     */
    @SuppressWarnings("unchecked")
    public BoqCumulative computeCumulativeForScope(
        UUID projectId, LocalDate date, Collection<UUID> supervisorIds) {

        // Empty filter means "no supervisors in scope" → no BOQ items → zero.
        // null means "project-wide".
        if (supervisorIds != null && supervisorIds.isEmpty()) {
            return BoqCumulative.zero();
        }

        try {
            String sql;
            if (supervisorIds == null) {
                sql = """
                    SELECT DISTINCT b.id,
                           COALESCE(b.boq_amount, 0)            AS planned_amount,
                           COALESCE(b.qty_executed_to_date, 0)  AS qty_to_date,
                           COALESCE(b.boq_rate, 0)              AS rate
                    FROM project.daily_progress_reports d
                    JOIN project.boq_items b ON b.id = d.boq_item_id
                    WHERE d.project_id = cast(:pid as uuid)
                      AND d.report_date = :dt
                    """;
            } else {
                // The supervisor_user_id column is UUID. Use ANY(cast(? as uuid[])) so we can
                // pass the supervisor list as a string-formatted Postgres array literal and let
                // Postgres do the uuid cast — sidesteps Hibernate's IN-expansion which was
                // binding each element as varchar and failing the operator lookup
                // ("operator does not exist: uuid = character varying").
                sql = """
                    SELECT DISTINCT b.id,
                           COALESCE(b.boq_amount, 0)            AS planned_amount,
                           COALESCE(b.qty_executed_to_date, 0)  AS qty_to_date,
                           COALESCE(b.boq_rate, 0)              AS rate
                    FROM project.daily_progress_reports d
                    JOIN project.boq_items b ON b.id = d.boq_item_id
                    WHERE d.project_id = cast(:pid as uuid)
                      AND d.report_date = :dt
                      AND d.supervisor_user_id = ANY (cast(:sups as uuid[]))
                    """;
            }

            var q = em.createNativeQuery(sql)
                .setParameter("pid", projectId.toString())
                .setParameter("dt", date);
            if (supervisorIds != null) {
                String arr = supervisorIds.stream()
                    .map(UUID::toString)
                    .collect(Collectors.joining(",", "{", "}"));
                q.setParameter("sups", arr);
            }
            List<Object[]> rows = q.getResultList();

            BigDecimal planned = BigDecimal.ZERO;
            BigDecimal achieved = BigDecimal.ZERO;
            for (Object[] r : rows) {
                BigDecimal plannedAmount = toBigDecimal(r[1]);
                BigDecimal qtyToDate = toBigDecimal(r[2]);
                BigDecimal rate = toBigDecimal(r[3]);
                planned = planned.add(plannedAmount);
                achieved = achieved.add(qtyToDate.multiply(rate));
            }
            return new BoqCumulative(
                planned.setScale(2, RoundingMode.HALF_UP),
                achieved.setScale(2, RoundingMode.HALF_UP));
        } catch (Exception ex) {
            log.warn("Section F (BOQ) cumulative-scope compute failed projectId={} date={} sups={}: {}",
                projectId, date, supervisorIds, ex.toString());
            return BoqCumulative.zero();
        }
    }

    /** Tier-agnostic deduped cumulative figures returned by {@link #computeCumulativeForScope}. */
    public record BoqCumulative(BigDecimal planned, BigDecimal achieved) {
        public static BoqCumulative zero() {
            return new BoqCumulative(BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return BigDecimal.ZERO;
    }
}
