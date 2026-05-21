package com.bipros.dbs.service;

import com.bipros.dbs.api.dto.CmShiftCount;
import com.bipros.dbs.api.dto.CumulativeDaysResponse;
import com.bipros.dbs.api.dto.CumulativeDaysResponse.CumulativeEquipmentDays;
import com.bipros.dbs.api.dto.CumulativeDaysResponse.CumulativeManpowerDays;
import com.bipros.dbs.api.dto.EquipmentRegisterResponse;
import com.bipros.dbs.api.dto.EquipmentRegisterTypeRow;
import com.bipros.dbs.api.dto.ManpowerRegisterResponse;
import com.bipros.dbs.api.dto.ManpowerRegisterTradeRow;
import com.bipros.dbs.domain.model.DbsEquipmentRegisterRow;
import com.bipros.dbs.domain.model.DbsManpowerRegisterRow;
import com.bipros.dbs.domain.repository.DbsEquipmentRegisterRowRepository;
import com.bipros.dbs.domain.repository.DbsManpowerRegisterRowRepository;
import com.bipros.project.application.service.ProjectTeamService;
import com.bipros.project.domain.model.Shift;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 5 — recomputes the Equipment & Manpower Deployment Register tables, and
 * Phase 6 — serves cumulative equipment-days / manpower-days aggregated over a
 * date range.
 *
 * <p>Source of truth: {@code project.dpr_manpower} and {@code project.dpr_equipment}
 * child rows joined to {@code project.daily_progress_reports} (which carries the
 * supervisor identity). CM attribution is resolved at recompute time by walking
 * the supervisor's reporting chain via
 * {@link ProjectTeamService#resolveCmFor(UUID, UUID)} — null when the chain has
 * no Construction Manager (treated as an "unattached" bucket).
 *
 * <p>{@link #recompute} is idempotent: it deletes every register row for the
 * {@code (project, date)} pair before reinserting the aggregated rows. This is
 * cheap because the register is per-day and per-CM, so the working set is small.
 */
@Slf4j
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
@RequiredArgsConstructor
public class RegisterAggregationService {

    @PersistenceContext
    private EntityManager em;

    private final DbsEquipmentRegisterRowRepository equipmentRepo;
    private final DbsManpowerRegisterRowRepository manpowerRepo;
    private final ProjectTeamService projectTeamService;

    // ── recompute ────────────────────────────────────────────────────────────────

    /**
     * Rebuild the equipment + manpower register for the given (project, date) pair.
     * Idempotent: existing rows for the pair are deleted before re-insert.
     */
    public void recompute(UUID projectId, LocalDate date) {
        log.debug("Register recompute projectId={} date={}", projectId, date);

        equipmentRepo.deleteByProjectIdAndReportDate(projectId, date);
        manpowerRepo.deleteByProjectIdAndReportDate(projectId, date);

        // Memoise CM lookups across all DPR rows on the date — most projects have far
        // fewer supervisors than DPR child rows, so this avoids re-walking the chain.
        Map<UUID, UUID> cmBySupervisor = new HashMap<>();

        List<DbsEquipmentRegisterRow> eqRows = aggregateEquipment(projectId, date, cmBySupervisor);
        if (!eqRows.isEmpty()) {
            equipmentRepo.saveAll(eqRows);
        }

        List<DbsManpowerRegisterRow> mpRows = aggregateManpower(projectId, date, cmBySupervisor);
        if (!mpRows.isEmpty()) {
            manpowerRepo.saveAll(mpRows);
        }

        log.info("Register recompute saved projectId={} date={} equipmentRows={} manpowerRows={}",
            projectId, date, eqRows.size(), mpRows.size());
    }

    @SuppressWarnings("unchecked")
    private List<DbsEquipmentRegisterRow> aggregateEquipment(UUID projectId, LocalDate date,
                                                              Map<UUID, UUID> cmBySupervisor) {
        // dpr_equipment rows joined to DPR for supervisor + report date filter. Pull only
        // what we need to bucket by (type, shift, CM) and accumulate counts/hours/cost.
        String sql = """
            SELECT eq.equipment_type                                AS equipment_type,
                   COALESCE(eq.shift, 'DAY')                        AS shift,
                   COALESCE(eq.nos, 0)                              AS nos,
                   COALESCE(eq.working_hours, 0)                    AS hrs,
                   COALESCE(eq.unit_rate, erv.rate, erm.rate, 0)    AS rate,
                   COALESCE(eq.line_cost, 0)                        AS line_cost,
                   d.supervisor_user_id                             AS supervisor_user_id
            FROM project.dpr_equipment eq
            JOIN project.daily_progress_reports d ON d.id = eq.dpr_id
            LEFT JOIN resource.equipment_role_variants erv ON erv.id = eq.equipment_role_variant_id
            LEFT JOIN resource.equipment_rate_masters erm ON erm.id = eq.role_id
            WHERE d.project_id = cast(:pid as uuid)
              AND d.report_date = :dt
              AND eq.equipment_type IS NOT NULL
            """;
        List<Object[]> rows;
        try {
            rows = em.createNativeQuery(sql)
                .setParameter("pid", projectId.toString())
                .setParameter("dt", date)
                .getResultList();
        } catch (Exception ex) {
            log.warn("Equipment register native query failed projectId={} date={}: {}",
                projectId, date, ex.toString());
            return List.of();
        }

        // Key: type|shift|cmUserId(nullable) → accumulator.
        Map<RegisterKey, Acc> bucket = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String type = (String) r[0];
            Shift shift = parseShift(r[1]);
            int nos = toInt(r[2]);
            BigDecimal hrs = toBigDecimal(r[3]);
            BigDecimal rate = toBigDecimal(r[4]);
            BigDecimal lineCost = toBigDecimal(r[5]);
            UUID supervisorUserId = toUuid(r[6]);

            UUID cmUserId = resolveCmCached(projectId, supervisorUserId, cmBySupervisor);
            bucket.computeIfAbsent(new RegisterKey(type, shift, cmUserId), k -> new Acc())
                  .add(nos, hrs, rate, lineCost);
        }

        List<DbsEquipmentRegisterRow> out = new ArrayList<>(bucket.size());
        for (Map.Entry<RegisterKey, Acc> e : bucket.entrySet()) {
            RegisterKey k = e.getKey();
            Acc a = e.getValue();
            out.add(DbsEquipmentRegisterRow.builder()
                .projectId(projectId)
                .reportDate(date)
                .cmUserId(k.cmUserId)
                .equipmentType(k.label)
                .shift(k.shift)
                .countNos(a.countNos)
                .workingHours(a.workingHours)
                .rate(a.weightedRate())
                .lineCost(a.lineCost)
                .build());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<DbsManpowerRegisterRow> aggregateManpower(UUID projectId, LocalDate date,
                                                            Map<UUID, UUID> cmBySupervisor) {
        // Scalar subqueries for the rate fallback so each DPR manpower row produces
        // exactly ONE result row. The previous LEFT JOIN onto manpower_rate_masters
        // used {@code mrm.role_id = mp.role_id}, which multiplies the DPR row by N
        // when a role has N grade variants in the rate-master table (e.g. Foreman
        // with Skilled / Semi-skilled / Unskilled grades). Without this fix, the
        // register's count_nos accumulates nos × N for those rows.
        String sql = """
            SELECT mp.trade                                          AS trade,
                   COALESCE(mp.shift, 'DAY')                         AS shift,
                   COALESCE(mp.nos, 0)                               AS nos,
                   COALESCE(mp.working_hours, 0)                     AS hrs,
                   COALESCE(
                     mp.unit_rate,
                     (SELECT rrr.rate
                        FROM resource.manpower_role_rates rrr
                       WHERE rrr.id = mp.manpower_role_rate_id),
                     (SELECT mrm.rate
                        FROM resource.manpower_rate_masters mrm
                       WHERE mrm.role_id = mp.role_id AND mrm.active
                       ORDER BY mrm.rate DESC
                       LIMIT 1),
                     0
                   )                                                  AS rate,
                   COALESCE(mp.line_cost, 0)                         AS line_cost,
                   d.supervisor_user_id                              AS supervisor_user_id
            FROM project.dpr_manpower mp
            JOIN project.daily_progress_reports d ON d.id = mp.dpr_id
            WHERE d.project_id = cast(:pid as uuid)
              AND d.report_date = :dt
              AND mp.trade IS NOT NULL
            """;
        List<Object[]> rows;
        try {
            rows = em.createNativeQuery(sql)
                .setParameter("pid", projectId.toString())
                .setParameter("dt", date)
                .getResultList();
        } catch (Exception ex) {
            log.warn("Manpower register native query failed projectId={} date={}: {}",
                projectId, date, ex.toString());
            return List.of();
        }

        Map<RegisterKey, Acc> bucket = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String trade = (String) r[0];
            Shift shift = parseShift(r[1]);
            int nos = toInt(r[2]);
            BigDecimal hrs = toBigDecimal(r[3]);
            BigDecimal rate = toBigDecimal(r[4]);
            BigDecimal lineCost = toBigDecimal(r[5]);
            UUID supervisorUserId = toUuid(r[6]);

            UUID cmUserId = resolveCmCached(projectId, supervisorUserId, cmBySupervisor);
            bucket.computeIfAbsent(new RegisterKey(trade, shift, cmUserId), k -> new Acc())
                  .add(nos, hrs, rate, lineCost);
        }

        List<DbsManpowerRegisterRow> out = new ArrayList<>(bucket.size());
        for (Map.Entry<RegisterKey, Acc> e : bucket.entrySet()) {
            RegisterKey k = e.getKey();
            Acc a = e.getValue();
            out.add(DbsManpowerRegisterRow.builder()
                .projectId(projectId)
                .reportDate(date)
                .cmUserId(k.cmUserId)
                .trade(k.label)
                .shift(k.shift)
                .countNos(a.countNos)
                .workingHours(a.workingHours)
                .rate(a.weightedRate())
                .lineCost(a.lineCost)
                .build());
        }
        return out;
    }

    // ── reads (pivoted for the UI) ───────────────────────────────────────────────

    /**
     * Pivots the persisted equipment register for {@code (project, date)} into one row
     * per equipment type, each with a per-CM day/night/total breakdown.
     *
     * @param cmUserIdFilter optional — when set, restricts to a single CM's slice.
     */
    @Transactional(readOnly = true)
    public EquipmentRegisterResponse getEquipmentRegister(UUID projectId, LocalDate date,
                                                          UUID cmUserIdFilter) {
        List<DbsEquipmentRegisterRow> rows = cmUserIdFilter == null
            ? equipmentRepo.findByProjectIdAndReportDate(projectId, date)
            : equipmentRepo.findByProjectIdAndReportDateAndCmUserId(projectId, date, cmUserIdFilter);

        Map<UUID, String> cmNames = resolveUserNames(rows.stream()
            .map(DbsEquipmentRegisterRow::getCmUserId)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));

        // type -> (cmUserId -> [day, night])
        Map<String, Map<UUID, int[]>> pivot = new LinkedHashMap<>();
        for (DbsEquipmentRegisterRow r : rows) {
            int n = r.getCountNos() == null ? 0 : r.getCountNos();
            pivot.computeIfAbsent(r.getEquipmentType(), k -> new LinkedHashMap<>())
                 .computeIfAbsent(r.getCmUserId(), k -> new int[]{0, 0});
            int[] dn = pivot.get(r.getEquipmentType()).get(r.getCmUserId());
            if (r.getShift() == Shift.NIGHT) dn[1] += n; else dn[0] += n;
        }

        List<EquipmentRegisterTypeRow> out = new ArrayList<>(pivot.size());
        for (Map.Entry<String, Map<UUID, int[]>> typeEntry : pivot.entrySet()) {
            List<CmShiftCount> byCm = new ArrayList<>();
            int totalDay = 0, totalNight = 0;
            for (Map.Entry<UUID, int[]> cmEntry : typeEntry.getValue().entrySet()) {
                int day = cmEntry.getValue()[0];
                int night = cmEntry.getValue()[1];
                totalDay += day;
                totalNight += night;
                byCm.add(new CmShiftCount(cmEntry.getKey(),
                    cmEntry.getKey() == null ? null : cmNames.get(cmEntry.getKey()),
                    day, night, day + night));
            }
            byCm.sort(Comparator.comparing((CmShiftCount c) -> c.cmName() == null ? "~" : c.cmName()));
            out.add(new EquipmentRegisterTypeRow(typeEntry.getKey(), byCm, totalDay, totalNight,
                totalDay + totalNight));
        }
        return new EquipmentRegisterResponse(date, out);
    }

    @Transactional(readOnly = true)
    public ManpowerRegisterResponse getManpowerRegister(UUID projectId, LocalDate date,
                                                         UUID cmUserIdFilter) {
        List<DbsManpowerRegisterRow> rows = cmUserIdFilter == null
            ? manpowerRepo.findByProjectIdAndReportDate(projectId, date)
            : manpowerRepo.findByProjectIdAndReportDateAndCmUserId(projectId, date, cmUserIdFilter);

        Map<UUID, String> cmNames = resolveUserNames(rows.stream()
            .map(DbsManpowerRegisterRow::getCmUserId)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));

        Map<String, Map<UUID, int[]>> pivot = new LinkedHashMap<>();
        for (DbsManpowerRegisterRow r : rows) {
            int n = r.getCountNos() == null ? 0 : r.getCountNos();
            pivot.computeIfAbsent(r.getTrade(), k -> new LinkedHashMap<>())
                 .computeIfAbsent(r.getCmUserId(), k -> new int[]{0, 0});
            int[] dn = pivot.get(r.getTrade()).get(r.getCmUserId());
            if (r.getShift() == Shift.NIGHT) dn[1] += n; else dn[0] += n;
        }

        List<ManpowerRegisterTradeRow> out = new ArrayList<>(pivot.size());
        for (Map.Entry<String, Map<UUID, int[]>> tradeEntry : pivot.entrySet()) {
            List<CmShiftCount> byCm = new ArrayList<>();
            int totalDay = 0, totalNight = 0;
            for (Map.Entry<UUID, int[]> cmEntry : tradeEntry.getValue().entrySet()) {
                int day = cmEntry.getValue()[0];
                int night = cmEntry.getValue()[1];
                totalDay += day;
                totalNight += night;
                byCm.add(new CmShiftCount(cmEntry.getKey(),
                    cmEntry.getKey() == null ? null : cmNames.get(cmEntry.getKey()),
                    day, night, day + night));
            }
            byCm.sort(Comparator.comparing((CmShiftCount c) -> c.cmName() == null ? "~" : c.cmName()));
            out.add(new ManpowerRegisterTradeRow(tradeEntry.getKey(), byCm, totalDay, totalNight,
                totalDay + totalNight));
        }
        return new ManpowerRegisterResponse(date, out);
    }

    // ── Phase 6: cumulative ──────────────────────────────────────────────────────

    /**
     * Cumulative equipment-days / manpower-days summed over all dates {@code <= asOfDate}.
     * Each register row's {@code count_nos} represents one shift's deployment of that
     * type/trade on that date; the cumulative total is the simple sum of those counts.
     * Both shifts count as a deployment-day (matches Excel "Eqpmnt & MP Days" behaviour).
     */
    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public CumulativeDaysResponse cumulative(UUID projectId, LocalDate asOfDate, UUID cmUserId) {
        String equipmentSql = """
            SELECT equipment_type, COALESCE(SUM(count_nos), 0) AS days
            FROM dbs.dbs_equipment_register
            WHERE project_id = cast(:pid as uuid)
              AND report_date <= :asOf
              AND (cast(:cm as uuid) IS NULL OR cm_user_id = cast(:cm as uuid))
            GROUP BY equipment_type
            ORDER BY equipment_type
            """;
        String manpowerSql = """
            SELECT trade, COALESCE(SUM(count_nos), 0) AS days
            FROM dbs.dbs_manpower_register
            WHERE project_id = cast(:pid as uuid)
              AND report_date <= :asOf
              AND (cast(:cm as uuid) IS NULL OR cm_user_id = cast(:cm as uuid))
            GROUP BY trade
            ORDER BY trade
            """;

        List<CumulativeEquipmentDays> equipment = new ArrayList<>();
        List<CumulativeManpowerDays> manpower = new ArrayList<>();
        try {
            List<Object[]> eqRows = em.createNativeQuery(equipmentSql)
                .setParameter("pid", projectId.toString())
                .setParameter("asOf", asOfDate)
                .setParameter("cm", cmUserId == null ? null : cmUserId.toString())
                .getResultList();
            for (Object[] r : eqRows) {
                equipment.add(new CumulativeEquipmentDays((String) r[0], toLong(r[1])));
            }
            List<Object[]> mpRows = em.createNativeQuery(manpowerSql)
                .setParameter("pid", projectId.toString())
                .setParameter("asOf", asOfDate)
                .setParameter("cm", cmUserId == null ? null : cmUserId.toString())
                .getResultList();
            for (Object[] r : mpRows) {
                manpower.add(new CumulativeManpowerDays((String) r[0], toLong(r[1])));
            }
        } catch (Exception ex) {
            log.warn("Cumulative days query failed projectId={} asOf={} cm={}: {}",
                projectId, asOfDate, cmUserId, ex.toString());
        }
        return new CumulativeDaysResponse(asOfDate, equipment, manpower);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private UUID resolveCmCached(UUID projectId, UUID supervisorUserId, Map<UUID, UUID> cache) {
        if (supervisorUserId == null) return null;
        if (cache.containsKey(supervisorUserId)) return cache.get(supervisorUserId);
        UUID cm = projectTeamService.resolveCmFor(projectId, supervisorUserId).orElse(null);
        cache.put(supervisorUserId, cm);
        return cm;
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, String> resolveUserNames(Set<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) return Collections.emptyMap();
        try {
            List<Object[]> rows = em.createNativeQuery(
                "SELECT id, COALESCE(NULLIF(TRIM(CONCAT(COALESCE(first_name,''),' ',COALESCE(last_name,''))), ''), username) "
                    + "FROM public.users WHERE id IN (:ids)")
                .setParameter("ids", userIds)
                .getResultList();
            Map<UUID, String> out = new HashMap<>(rows.size());
            for (Object[] row : rows) {
                if (row[0] == null) continue;
                UUID id = row[0] instanceof UUID u ? u : UUID.fromString(row[0].toString());
                String name = row[1] == null ? null : row[1].toString();
                out.put(id, name);
            }
            return out;
        } catch (Exception ex) {
            log.warn("Failed to resolve CM names ({}): {}", ex.getClass().getSimpleName(), ex.getMessage());
            return Collections.emptyMap();
        }
    }

    private static Shift parseShift(Object v) {
        if (v == null) return Shift.DAY;
        String s = v.toString().trim().toUpperCase();
        if (s.isEmpty()) return Shift.DAY;
        try {
            return Shift.valueOf(s);
        } catch (IllegalArgumentException ex) {
            return Shift.DAY;
        }
    }

    private static int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException ex) { return 0; }
    }

    private static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); } catch (NumberFormatException ex) { return 0L; }
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return BigDecimal.ZERO;
    }

    private static UUID toUuid(Object v) {
        if (v == null) return null;
        if (v instanceof UUID u) return u;
        try { return UUID.fromString(v.toString()); } catch (IllegalArgumentException ex) { return null; }
    }

    // ── inner types ──────────────────────────────────────────────────────────────

    private record RegisterKey(String label, Shift shift, UUID cmUserId) {}

    /** Per-(type, shift, CM) accumulator built during aggregation. */
    private static final class Acc {
        int countNos;
        BigDecimal workingHours = BigDecimal.ZERO;
        BigDecimal lineCost = BigDecimal.ZERO;
        BigDecimal rateNumerator = BigDecimal.ZERO;
        int rateSamples;

        void add(int nos, BigDecimal hrs, BigDecimal rate, BigDecimal cost) {
            this.countNos += nos;
            this.workingHours = this.workingHours.add(nz(hrs));
            this.lineCost = this.lineCost.add(nz(cost));
            if (rate != null && rate.signum() != 0) {
                this.rateNumerator = this.rateNumerator.add(rate);
                this.rateSamples++;
            }
        }

        BigDecimal weightedRate() {
            if (rateSamples == 0) return BigDecimal.ZERO;
            return rateNumerator.divide(BigDecimal.valueOf(rateSamples), 4, java.math.RoundingMode.HALF_UP);
        }

        private static BigDecimal nz(BigDecimal v) {
            return v == null ? BigDecimal.ZERO : v;
        }
    }
}
