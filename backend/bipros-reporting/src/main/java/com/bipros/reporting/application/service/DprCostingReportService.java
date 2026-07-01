package com.bipros.reporting.application.service;

import com.bipros.reporting.application.dto.DprCostingReport;
import com.bipros.reporting.application.dto.DprCostingReport.Block;
import com.bipros.reporting.application.dto.DprCostingReport.Manpower;
import com.bipros.reporting.application.dto.DprCostingReport.Material;
import com.bipros.reporting.application.dto.DprCostingReport.Pmv;
import com.bipros.reporting.application.dto.DprCostingReport.SubContract;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the {@link DprCostingReport} for a project + date range from APPROVED DPRs only. Reads
 * across schemas with native SQL (mirrors {@link DprReportService}): the DPR header rows from
 * {@code project.daily_progress_reports}, child line-items from the four {@code project.dpr_*}
 * tables, contract quantities from {@code project.boq_items}, and sub-contractor rates from
 * {@code resource.activity_sub_contractor_assignments}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DprCostingReportService {

  @PersistenceContext private EntityManager em;

  @Transactional(readOnly = true)
  public DprCostingReport build(UUID projectId, LocalDate from, LocalDate to) {
    String projectName = lookupString(
        "SELECT name FROM project.projects WHERE id = :id", projectId);

    @SuppressWarnings("unchecked")
    List<Object[]> headerRows = em.createNativeQuery(
            "SELECT id, report_date, supervisor_name, landmark, side, "
                + "       chainage_from_m, chainage_to_m, boq_item_no, boq_item_id, "
                + "       unit, qty_executed, remarks "
                + "FROM project.daily_progress_reports "
                + "WHERE project_id = :p "
                + "  AND report_date BETWEEN :from AND :to "
                + "  AND approval_status = 'APPROVED' "
                + "ORDER BY report_date, boq_item_no")
        .setParameter("p", projectId)
        .setParameter("from", from)
        .setParameter("to", to)
        .getResultList();

    if (headerRows.isEmpty()) {
      return new DprCostingReport(projectName, List.of());
    }

    List<UUID> dprIds = new ArrayList<>(headerRows.size());
    for (Object[] r : headerRows) {
      dprIds.add(toUuid(r[0]));
    }

    Map<UUID, List<Manpower>> manpowerByDpr = loadManpower(dprIds);
    Map<UUID, List<Pmv>> pmvByDpr = loadPmv(dprIds);
    Map<UUID, List<Material>> materialByDpr = loadMaterial(dprIds);
    Map<UUID, List<SubContract>> subByDpr = loadSubContract(dprIds);
    // Total Qty = the BOQ item's executed-to-date, matched by Activity Code (item_no).
    Map<String, BigDecimal> execByItemNo = loadExecutedToDateByItemNo(projectId);

    List<Block> blocks = new ArrayList<>(headerRows.size());
    for (Object[] r : headerRows) {
      UUID dprId = toUuid(r[0]);
      String activityCode = (String) r[7];      // boq_item_no
      blocks.add(new Block(
          ((java.sql.Date) r[1]).toLocalDate(),
          null,                                  // Site — no per-DPR field
          (String) r[3],                         // location ← landmark
          toLong(r[5]),                          // chainageFrom
          toLong(r[6]),                          // chainageTo
          (String) r[4],                         // side
          activityCode,                          // activityCode ← boq_item_no
          (String) r[9],                         // unit
          toBigDecimal(r[10]),                   // executedQty
          (String) r[11],                        // remarks
          (String) r[2],                         // supervisorName
          activityCode == null ? null : execByItemNo.get(activityCode),  // totalQty
          manpowerByDpr.getOrDefault(dprId, List.of()),
          pmvByDpr.getOrDefault(dprId, List.of()),
          materialByDpr.getOrDefault(dprId, List.of()),
          subByDpr.getOrDefault(dprId, List.of())));
    }
    return new DprCostingReport(projectName, blocks);
  }

  private Map<UUID, List<Manpower>> loadManpower(List<UUID> dprIds) {
    @SuppressWarnings("unchecked")
    List<Object[]> rows = em.createNativeQuery(
            "SELECT dpr_id, trade, nos, unit_rate, line_cost "
                + "FROM project.dpr_manpower WHERE dpr_id IN (:ids) ORDER BY id")
        .setParameter("ids", dprIds)
        .getResultList();
    Map<UUID, List<Manpower>> map = new HashMap<>();
    for (Object[] r : rows) {
      BigDecimal nr = toBigDecimal(r[2]);
      BigDecimal rate = toBigDecimal(r[3]);
      map.computeIfAbsent(toUuid(r[0]), k -> new ArrayList<>())
          .add(new Manpower((String) r[1], nr, rate, cost(nr, rate, r[4])));
    }
    return map;
  }

  private Map<UUID, List<Pmv>> loadPmv(List<UUID> dprIds) {
    @SuppressWarnings("unchecked")
    List<Object[]> rows = em.createNativeQuery(
            "SELECT dpr_id, equipment_type, fleet_no, nos, unit_rate, line_cost "
                + "FROM project.dpr_equipment WHERE dpr_id IN (:ids) ORDER BY id")
        .setParameter("ids", dprIds)
        .getResultList();
    Map<UUID, List<Pmv>> map = new HashMap<>();
    for (Object[] r : rows) {
      String type = (String) r[1];
      String fleet = (String) r[2];
      String detail = (fleet == null || fleet.isBlank()) ? type : type + " (" + fleet + ")";
      BigDecimal nr = toBigDecimal(r[3]);
      BigDecimal rate = toBigDecimal(r[4]);
      map.computeIfAbsent(toUuid(r[0]), k -> new ArrayList<>())
          .add(new Pmv(detail, nr, rate, cost(nr, rate, r[5])));
    }
    return map;
  }

  private Map<UUID, List<Material>> loadMaterial(List<UUID> dprIds) {
    @SuppressWarnings("unchecked")
    List<Object[]> rows = em.createNativeQuery(
            "SELECT dpr_id, material_name, unit, quantity, unit_rate, line_cost "
                + "FROM project.dpr_material WHERE dpr_id IN (:ids) ORDER BY id")
        .setParameter("ids", dprIds)
        .getResultList();
    Map<UUID, List<Material>> map = new HashMap<>();
    for (Object[] r : rows) {
      map.computeIfAbsent(toUuid(r[0]), k -> new ArrayList<>())
          .add(new Material((String) r[1], (String) r[2], toBigDecimal(r[3]),
              toBigDecimal(r[4]), toBigDecimal(r[5])));
    }
    return map;
  }

  private Map<UUID, List<SubContract>> loadSubContract(List<UUID> dprIds) {
    @SuppressWarnings("unchecked")
    List<Object[]> rows = em.createNativeQuery(
            "SELECT c.dpr_id, c.sub_contractor_name, c.quantity, "
                + "       a.work_type_name, a.unit, a.rate_per_unit "
                + "FROM project.dpr_sub_contractor c "
                + "LEFT JOIN resource.activity_sub_contractor_assignments a "
                + "  ON a.id = c.activity_sub_contractor_assignment_id "
                + "WHERE c.dpr_id IN (:ids) ORDER BY c.id")
        .setParameter("ids", dprIds)
        .getResultList();
    Map<UUID, List<SubContract>> map = new HashMap<>();
    for (Object[] r : rows) {
      BigDecimal qty = toBigDecimal(r[2]);
      BigDecimal rate = toBigDecimal(r[5]);
      BigDecimal cost = (qty == null || rate == null) ? null : qty.multiply(rate);
      map.computeIfAbsent(toUuid(r[0]), k -> new ArrayList<>())
          .add(new SubContract((String) r[1], (String) r[3], (String) r[4], qty, rate, cost));
    }
    return map;
  }

  /** Maps each BOQ item's Activity Code ({@code item_no}) → its {@code qty_executed_to_date}. */
  private Map<String, BigDecimal> loadExecutedToDateByItemNo(UUID projectId) {
    @SuppressWarnings("unchecked")
    List<Object[]> rows = em.createNativeQuery(
            "SELECT item_no, qty_executed_to_date FROM project.boq_items WHERE project_id = :p")
        .setParameter("p", projectId)
        .getResultList();
    Map<String, BigDecimal> map = new HashMap<>();
    for (Object[] r : rows) {
      if (r[0] != null) map.put((String) r[0], toBigDecimal(r[1]));
    }
    return map;
  }

  /** Cost = nr × rate; falls back to the stored line cost when either driver is missing. */
  private static BigDecimal cost(BigDecimal nr, BigDecimal rate, Object lineCost) {
    if (nr != null && rate != null) return nr.multiply(rate);
    return toBigDecimal(lineCost);
  }

  private String lookupString(String sql, UUID id) {
    @SuppressWarnings("unchecked")
    List<Object> rows = em.createNativeQuery(sql)
        .setParameter("id", id)
        .setMaxResults(1)
        .getResultList();
    return rows.isEmpty() || rows.get(0) == null ? null : rows.get(0).toString();
  }

  private static UUID toUuid(Object o) {
    if (o == null) return null;
    if (o instanceof UUID u) return u;
    return UUID.fromString(o.toString());
  }

  private static Long toLong(Object o) {
    if (o == null) return null;
    if (o instanceof Number n) return n.longValue();
    return Long.parseLong(o.toString());
  }

  private static BigDecimal toBigDecimal(Object o) {
    if (o == null) return null;
    if (o instanceof BigDecimal bd) return bd;
    if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
    return null;
  }
}
