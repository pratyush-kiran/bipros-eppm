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
 * Section D — Fuel.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Sum {@code fuel_litres} across {@code project.dpr_equipment} rows for the
 *       (project, date, supervisor) scope.</li>
 *   <li>Multiply by a per-project fuel rate. {@code project_costing_config.fuel_rate_per_litre}
 *       is the intended source (Phase F risk #1) but the table does not exist yet, so we fall
 *       back to fuel/diesel rows in {@code project.dpr_material} for the same scope and use
 *       their {@code line_cost} as the fuel total. If neither source is available we return
 *       zero <em>and</em> emit a WARN with an actionable hint — silent zeros mask real
 *       miscalculations.</li>
 * </ol>
 *
 * <p>Supervisor filtering: {@code dpr_equipment} / {@code dpr_material} are joined via
 * {@code dpr_id → daily_progress_reports.supervisor_user_id}, so the filter is direct.
 */
@Slf4j
@Component
public class SectionDFuelCalculator {

    @PersistenceContext
    private EntityManager em;

    @SuppressWarnings("unchecked")
    public SectionResult compute(UUID projectId, UUID supervisorUserId, LocalDate date) {
        List<SectionLine> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        // (1) Sum litres from DPR equipment rows for the scope.
        BigDecimal litres = BigDecimal.ZERO;
        try {
            String sql = """
                SELECT COALESCE(SUM(eq.fuel_litres), 0)
                FROM project.dpr_equipment eq
                JOIN project.daily_progress_reports d ON d.id = eq.dpr_id
                WHERE d.project_id = cast(:pid as uuid)
                  AND d.report_date = :dt
                  AND (cast(:sup as uuid) IS NULL OR d.supervisor_user_id = cast(:sup as uuid))
                """;
            Object raw = em.createNativeQuery(sql)
                .setParameter("pid", projectId.toString())
                .setParameter("dt", date)
                .setParameter("sup", supervisorUserId == null ? null : supervisorUserId.toString())
                .getSingleResult();
            litres = toBigDecimal(raw);
        } catch (Exception ex) {
            log.warn("Section D fuel-litres scan failed projectId={} supervisor={} date={}: {}",
                projectId, supervisorUserId, date, ex.toString());
        }

        // (2a) Try the per-project fuel rate (project_costing_config). The table is not yet
        // wired up — query defensively so a missing table doesn't fail the section.
        BigDecimal fuelRate = lookupProjectFuelRate(projectId);

        if (litres.signum() > 0 && fuelRate != null && fuelRate.signum() > 0) {
            BigDecimal amount = litres.multiply(fuelRate).setScale(2, RoundingMode.HALF_UP);
            lines.add(new SectionLine("Fuel (Diesel) — equipment", "L", fuelRate, litres, amount));
            total = total.add(amount);
            return new SectionResult(total.setScale(2, RoundingMode.HALF_UP), lines);
        }

        // (2b) Fallback: sum diesel/fuel material consumption (DPR material rows) for the scope.
        try {
            String sql = """
                SELECT mat.material_name,
                       COALESCE(mat.unit, 'L')         AS unit,
                       COALESCE(mat.unit_rate, 0)      AS rate,
                       COALESCE(mat.quantity, 0)       AS qty,
                       COALESCE(mat.line_cost,
                                COALESCE(mat.quantity, 0) * COALESCE(mat.unit_rate, 0)) AS line_cost
                FROM project.dpr_material mat
                JOIN project.daily_progress_reports d ON d.id = mat.dpr_id
                WHERE d.project_id = cast(:pid as uuid)
                  AND d.report_date = :dt
                  AND (cast(:sup as uuid) IS NULL OR d.supervisor_user_id = cast(:sup as uuid))
                  AND (mat.material_name ILIKE '%diesel%'
                       OR mat.material_name ILIKE '%fuel%'
                       OR mat.material_name ILIKE '%petrol%'
                       OR mat.material_name ILIKE '%hsd%')
                """;
            List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("pid", projectId.toString())
                .setParameter("dt", date)
                .setParameter("sup", supervisorUserId == null ? null : supervisorUserId.toString())
                .getResultList();
            for (Object[] r : rows) {
                String name = (String) r[0];
                String unit = (String) r[1];
                BigDecimal rate = toBigDecimal(r[2]);
                BigDecimal qty = toBigDecimal(r[3]);
                BigDecimal lineCost = toBigDecimal(r[4]).setScale(2, RoundingMode.HALF_UP);
                lines.add(new SectionLine(name, unit, rate, qty, lineCost));
                total = total.add(lineCost);
            }
        } catch (Exception ex) {
            log.warn("Section D fuel-material fallback failed projectId={} supervisor={} date={}: {}",
                projectId, supervisorUserId, date, ex.toString());
        }

        if (total.signum() == 0) {
            if (litres.signum() > 0) {
                log.warn("Section D fuel: {} litres consumed for projectId={} date={} supervisor={} but no fuel rate is configured. "
                        + "Either create project.project_costing_config with fuel_rate_per_litre, "
                        + "or log a diesel/fuel material consumption row on the DPR.",
                    litres, projectId, date, supervisorUserId);
            } else {
                log.debug("Section D fuel: no fuel_litres on dpr_equipment and no diesel/fuel material rows for projectId={} date={} supervisor={}.",
                    projectId, date, supervisorUserId);
            }
            return SectionResult.empty();
        }

        return new SectionResult(total.setScale(2, RoundingMode.HALF_UP), lines);
    }

    /**
     * Best-effort lookup of a per-project fuel rate (per litre). Returns null if the
     * {@code project_costing_config} table does not exist or has no row for the project.
     *
     * <p>Critical: we MUST NOT raise a Postgres "relation does not exist" error inside the
     * outer {@code @Transactional} boundary of {@code DbsAggregationService} — once Postgres
     * marks a transaction aborted (SQLSTATE 25P02), every subsequent statement in the same
     * txn fails wholesale, including the row {@code save()}. We probe with {@code to_regclass}
     * first (returns NULL if the table is missing, no exception thrown) and only run the real
     * lookup when the table is actually present.
     */
    private BigDecimal lookupProjectFuelRate(UUID projectId) {
        try {
            Object exists = em.createNativeQuery(
                    "SELECT to_regclass('project.project_costing_config')")
                .getSingleResult();
            if (exists == null) {
                // Table not yet wired up in this DB — silently skip the lookup.
                return null;
            }
            Object raw = em.createNativeQuery(
                    "SELECT fuel_rate_per_litre FROM project.project_costing_config "
                        + "WHERE project_id = cast(:pid as uuid) LIMIT 1")
                .setParameter("pid", projectId.toString())
                .getSingleResult();
            return toBigDecimalNullable(raw);
        } catch (jakarta.persistence.NoResultException nre) {
            return null;
        } catch (Exception ex) {
            log.debug("Project fuel rate lookup failed projectId={}: {}", projectId, ex.toString());
            return null;
        }
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
