package com.bipros.reporting.application.service;

import com.bipros.reporting.application.dto.DprMatrix;
import com.bipros.reporting.application.dto.DprMatrix.Item;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the DPR sheet — one row per BOQ line item, with month-to-date achieved qty / amount and
 * a per-day vector of executed quantities pulled from {@code project.daily_progress_reports}.
 *
 * <p>{@code daily_progress_reports.boq_item_no} is the soft FK back to {@code boq_items.item_no}
 * (string, not UUID). DPR rows without a BOQ link don't appear here — they're activity-level
 * progress, not BOQ-level.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DprReportService {

  @PersistenceContext private EntityManager em;

  @Transactional(readOnly = true)
  public DprMatrix build(UUID projectId, YearMonth month) {
    LocalDate from = month.atDay(1);
    LocalDate to = month.atEndOfMonth();
    int daysInMonth = month.lengthOfMonth();

    String projectName = lookupString(
        "SELECT name FROM project.projects WHERE id = :id", Map.of("id", projectId));

    @SuppressWarnings("unchecked")
    List<Object[]> boqRows = em.createNativeQuery(
            "SELECT b.item_no, b.description, b.unit, "
                + "       COALESCE(b.boq_rate, 0), "
                + "       COALESCE(b.boq_qty, 0), "
                + "       COALESCE(b.qty_executed_to_date, 0), "
                + "       COALESCE(b.boq_amount, 0), "
                + "       COALESCE(b.actual_amount, 0) "
                + "FROM project.boq_items b "
                + "WHERE b.project_id = :projectId "
                + "ORDER BY b.item_no")
        .setParameter("projectId", projectId)
        .getResultList();

    @SuppressWarnings("unchecked")
    List<Object[]> dprRows = em.createNativeQuery(
            "SELECT dpr.boq_item_no, dpr.report_date, COALESCE(SUM(dpr.qty_executed), 0) "
                + "FROM project.daily_progress_reports dpr "
                + "WHERE dpr.project_id = :projectId "
                + "  AND dpr.report_date BETWEEN :from AND :to "
                + "  AND dpr.boq_item_no IS NOT NULL "
                + "  AND dpr.approval_status = 'APPROVED' "
                + "GROUP BY dpr.boq_item_no, dpr.report_date")
        .setParameter("projectId", projectId)
        .setParameter("from", from)
        .setParameter("to", to)
        .getResultList();

    Map<String, BigDecimal[]> perDayByItem = new HashMap<>();
    for (Object[] r : dprRows) {
      String itemNo = (String) r[0];
      LocalDate reportDate = ((java.sql.Date) r[1]).toLocalDate();
      BigDecimal qty = toBigDecimal(r[2]);
      BigDecimal[] arr = perDayByItem.computeIfAbsent(itemNo, k -> emptyDayArray(daysInMonth));
      int idx = reportDate.getDayOfMonth() - 1;
      arr[idx] = (arr[idx] == null ? qty : arr[idx].add(qty));
    }

    List<Item> items = new ArrayList<>(boqRows.size());
    for (Object[] r : boqRows) {
      String itemNo = (String) r[0];
      String description = (String) r[1];
      String unit = (String) r[2];
      BigDecimal rate = toBigDecimal(r[3]);
      BigDecimal projectionQty = toBigDecimal(r[4]);
      BigDecimal achievedQty = toBigDecimal(r[5]);
      BigDecimal projectionAmount = toBigDecimal(r[6]);
      BigDecimal achievedAmount = toBigDecimal(r[7]);
      // Fall back to derived amount when the persisted column is zero (qty * rate).
      if (achievedAmount.signum() == 0 && achievedQty.signum() != 0 && rate.signum() != 0) {
        achievedAmount = achievedQty.multiply(rate).setScale(2, RoundingMode.HALF_UP);
      }
      BigDecimal[] perDay = perDayByItem.getOrDefault(itemNo, emptyDayArray(daysInMonth));
      items.add(new Item(itemNo, description, unit, rate,
          projectionQty, projectionAmount, achievedQty, achievedAmount, perDay));
    }

    return new DprMatrix(projectId, month, daysInMonth, projectName, null, null, null, items);
  }

  private String lookupString(String sql, Map<String, Object> params) {
    var query = em.createNativeQuery(sql);
    params.forEach(query::setParameter);
    @SuppressWarnings("unchecked")
    List<Object> rows = query.setMaxResults(1).getResultList();
    if (rows.isEmpty() || rows.get(0) == null) return null;
    return rows.get(0).toString();
  }

  private static BigDecimal[] emptyDayArray(int days) {
    BigDecimal[] arr = new BigDecimal[days];
    for (int i = 0; i < days; i++) arr[i] = null;
    return arr;
  }

  private static BigDecimal toBigDecimal(Object o) {
    if (o == null) return BigDecimal.ZERO;
    if (o instanceof BigDecimal bd) return bd;
    if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
    return BigDecimal.ZERO;
  }
}
