package com.bipros.project.application.service;

import com.bipros.project.application.dto.DprEquipmentRow;
import com.bipros.project.application.dto.DprManpowerRow;
import com.bipros.project.application.dto.ProductivityPreviewRequest;
import com.bipros.project.application.dto.ProductivityPreviewResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Computes the live productivity preview shown on the DPR form. Coverage-aware: only the sides
 * the Work Activity actually tracks (manpower / equipment / both / none) participate in the
 * expected-output math. Warnings fire only when a side IS tracked but a specific row's role
 * has no matching norm — no spurious noise on equipment-only activities where the user happens
 * to log a manpower row.
 *
 * <p>Norm lookups go directly to {@code resource.productivity_norms} via native SQL — the same
 * cross-schema pattern the rest of {@link DailyProgressReportService} uses to avoid a Maven
 * dep on {@code bipros-resource}.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DprProductivityPreviewService {

  @PersistenceContext private EntityManager em;

  public ProductivityPreviewResponse preview(
      UUID projectId, UUID activityId, ProductivityPreviewRequest request) {
    if (activityId == null || request == null) {
      return empty("NONE");
    }
    UUID workActivityId = loadWorkActivityId(activityId);
    if (workActivityId == null) {
      // Legitimate state — activity is design / office / non-tracked work. DPR will save; the
      // form shows an info banner so the user knows productivity isn't measured.
      return empty("NO_WORK_ACTIVITY");
    }

    boolean[] coverage = loadCoverage(workActivityId); // [manpowerConfigured, equipmentConfigured]
    boolean manpowerTracked = coverage[0];
    boolean equipmentTracked = coverage[1];
    String coverageLabel;
    if (manpowerTracked && equipmentTracked) coverageLabel = "BOTH";
    else if (manpowerTracked) coverageLabel = "MANPOWER_ONLY";
    else if (equipmentTracked) coverageLabel = "EQUIPMENT_ONLY";
    else coverageLabel = "NONE";
    String normCombination = loadNormCombination(workActivityId); // SERIES / PARALLEL / SUBSTITUTE

    Map<NormKey, NormRow> cache = new HashMap<>();
    List<String> warnings = new ArrayList<>();

    BigDecimal manpowerSum = BigDecimal.ZERO;
    boolean manpowerHadNorm = false;
    if (manpowerTracked) {
      List<DprManpowerRow> mp = request.manpower() == null ? List.of() : request.manpower();
      for (DprManpowerRow row : mp) {
        if (row.nos() == null || row.nos() <= 0) continue;
        NormRow norm = cache.computeIfAbsent(
            new NormKey(row.roleId(), null, null, null, null, "MANPOWER"),
            k -> lookup(workActivityId, k));
        if (norm == null || norm.outputPerManPerDay == null) {
          warnings.add(
              "No manpower productivity norm for role " + roleName(row.roleId()) + ".");
          continue;
        }
        manpowerHadNorm = true;
        manpowerSum = manpowerSum.add(
            computeManpowerExpected(norm.outputPerManPerDay, row.nos()));
      }
    }

    BigDecimal equipmentSum = BigDecimal.ZERO;
    boolean equipmentHadNorm = false;
    if (equipmentTracked) {
      List<DprEquipmentRow> eq = request.equipment() == null ? List.of() : request.equipment();
      for (DprEquipmentRow row : eq) {
        if (row.nos() == null || row.nos() <= 0) continue;
        NormRow norm = cache.computeIfAbsent(
            new NormKey(row.roleId(), null, null, null, null, "EQUIPMENT"),
            k -> lookup(workActivityId, k));
        if (norm == null || norm.outputPerDay == null) {
          warnings.add(
              "No equipment productivity norm for role " + roleName(row.roleId()) + ".");
          continue;
        }
        equipmentHadNorm = true;
        equipmentSum = equipmentSum.add(
            computeEquipmentExpected(norm.outputPerDay, row.nos()));
      }
    }

    BigDecimal expectedFromManpower = manpowerHadNorm ? manpowerSum : null;
    BigDecimal expectedFromEquipment = equipmentHadNorm ? equipmentSum : null;
    BigDecimal bottleneck;
    String source;
    if (expectedFromManpower != null && expectedFromEquipment != null) {
      bottleneck = combine(expectedFromManpower, expectedFromEquipment, normCombination);
      source = "BOTH";
    } else if (expectedFromManpower != null) {
      bottleneck = expectedFromManpower;
      source = "MANPOWER_ONLY";
    } else if (expectedFromEquipment != null) {
      bottleneck = expectedFromEquipment;
      source = "EQUIPMENT_ONLY";
    } else {
      bottleneck = null;
      source = "NONE";
    }
    return new ProductivityPreviewResponse(
        expectedFromManpower, expectedFromEquipment, bottleneck, source, coverageLabel,
        normCombination, warnings);
  }

  /**
   * Combines the manpower and equipment expected outputs per the Work Activity's configured rule.
   * Both inputs are guaranteed non-null by the caller. Unknown / null combination falls back to
   * SERIES so the historical bottleneck behaviour is preserved.
   */
  private static BigDecimal combine(BigDecimal manpower, BigDecimal equipment, String combination) {
    if ("PARALLEL".equals(combination)) {
      return manpower.add(equipment);
    }
    if ("SUBSTITUTE".equals(combination)) {
      return manpower.max(equipment);
    }
    return manpower.min(equipment); // SERIES (default)
  }

  /**
   * Manpower expected output for a DPR row. Per-day basis, HRS not used. Public/static so the
   * math is unit-testable without an EntityManager.
   */
  public static BigDecimal computeManpowerExpected(BigDecimal outputPerManPerDay, Integer nos) {
    if (outputPerManPerDay == null || nos == null || nos <= 0) return BigDecimal.ZERO;
    return outputPerManPerDay.multiply(BigDecimal.valueOf(nos));
  }

  /**
   * Equipment expected output for a DPR row. Per-day basis. HRS is intentionally NOT a parameter
   * here — it's logging-only on the DPR and must not influence productivity math anywhere.
   * Public/static so the math is unit-testable without an EntityManager.
   */
  public static BigDecimal computeEquipmentExpected(BigDecimal outputPerDay, Integer nos) {
    if (outputPerDay == null || nos == null || nos <= 0) return BigDecimal.ZERO;
    return outputPerDay.multiply(BigDecimal.valueOf(nos));
  }

  private static ProductivityPreviewResponse empty(String coverage) {
    return new ProductivityPreviewResponse(null, null, null, "NONE", coverage, "SERIES", List.of());
  }

  private UUID loadWorkActivityId(UUID activityId) {
    @SuppressWarnings("unchecked")
    List<Object> rows = em.createNativeQuery(
            "SELECT a.work_activity_id FROM activity.activities a WHERE a.id = :id")
        .setParameter("id", activityId)
        .setMaxResults(1)
        .getResultList();
    if (rows.isEmpty() || rows.get(0) == null) return null;
    Object o = rows.get(0);
    if (o instanceof UUID u) return u;
    if (o instanceof String s) return UUID.fromString(s);
    return null;
  }

  /** Reads the {@code norm_combination} configured on the Work Activity master. Defaults to
   *  {@code SERIES} when the column is missing or null (pre-104 data / seeded rows). */
  @SuppressWarnings("unchecked")
  private String loadNormCombination(UUID workActivityId) {
    List<Object> rows = em.createNativeQuery(
            "SELECT wa.norm_combination FROM resource.work_activities wa WHERE wa.id = :wa")
        .setParameter("wa", workActivityId)
        .setMaxResults(1)
        .getResultList();
    if (rows.isEmpty() || rows.get(0) == null) return "SERIES";
    return rows.get(0).toString();
  }

  /** Returns [hasManpowerNorm, hasEquipmentNorm] for the Work Activity. One round-trip. */
  @SuppressWarnings("unchecked")
  private boolean[] loadCoverage(UUID workActivityId) {
    List<Object[]> rows = em.createNativeQuery(
            "SELECT "
                + "  SUM(CASE WHEN n.norm_type = 'MANPOWER' THEN 1 ELSE 0 END) AS mp, "
                + "  SUM(CASE WHEN n.norm_type = 'EQUIPMENT' THEN 1 ELSE 0 END) AS eq "
                + "FROM resource.productivity_norms n "
                + "WHERE n.work_activity_id = :wa")
        .setParameter("wa", workActivityId)
        .getResultList();
    if (rows.isEmpty() || rows.get(0) == null) return new boolean[]{false, false};
    Object[] r = rows.get(0);
    long mp = r[0] == null ? 0 : ((Number) r[0]).longValue();
    long eq = r[1] == null ? 0 : ((Number) r[1]).longValue();
    return new boolean[]{mp > 0, eq > 0};
  }

  private String roleName(UUID roleId) {
    if (roleId == null) return "(no role)";
    @SuppressWarnings("unchecked")
    List<Object> rows = em.createNativeQuery(
            "SELECT rr.name FROM resource.resource_roles rr WHERE rr.id = :id")
        .setParameter("id", roleId)
        .setMaxResults(1)
        .getResultList();
    if (rows.isEmpty() || rows.get(0) == null) return roleId.toString().substring(0, 8);
    return (String) rows.get(0);
  }

  private NormRow lookup(UUID workActivityId, NormKey key) {
    if (key.roleId != null) {
      NormRow variant = queryOne(
          "SELECT n.output_per_day, n.output_per_man_per_day, n.working_hours_per_day "
              + "FROM resource.productivity_norms n "
              + "WHERE n.work_activity_id = :wa "
              + "  AND n.role_id = :role "
              + "  AND n.norm_type = :nt "
              + (key.categoryId != null ? "  AND n.category_id = :cat " : "  AND n.category_id IS NULL ")
              + (key.gradeId != null ? "  AND n.grade_id = :gr " : "  AND n.grade_id IS NULL ")
              + (key.make != null ? "  AND n.make = :mk " : "  AND n.make IS NULL ")
              + (key.model != null ? "  AND n.model = :md " : "  AND n.model IS NULL "),
          paramsFor(workActivityId, key));
      if (variant != null) return variant;

      NormRow roleOnly = queryOne(
          "SELECT n.output_per_day, n.output_per_man_per_day, n.working_hours_per_day "
              + "FROM resource.productivity_norms n "
              + "WHERE n.work_activity_id = :wa "
              + "  AND n.role_id = :role "
              + "  AND n.category_id IS NULL AND n.grade_id IS NULL "
              + "  AND n.make IS NULL AND n.model IS NULL "
              + "  AND n.norm_type = :nt",
          Map.of("wa", workActivityId, "role", key.roleId, "nt", key.normType));
      if (roleOnly != null) return roleOnly;
    }
    return queryOne(
        "SELECT n.output_per_day, n.output_per_man_per_day, n.working_hours_per_day "
            + "FROM resource.productivity_norms n "
            + "WHERE n.work_activity_id = :wa "
            + "  AND n.role_id IS NULL "
            + "  AND n.category_id IS NULL AND n.grade_id IS NULL "
            + "  AND n.make IS NULL AND n.model IS NULL "
            + "  AND n.resource_id IS NULL AND n.resource_type_id IS NULL "
            + "  AND n.norm_type = :nt",
        Map.of("wa", workActivityId, "nt", key.normType));
  }

  private Map<String, Object> paramsFor(UUID workActivityId, NormKey key) {
    Map<String, Object> p = new HashMap<>();
    p.put("wa", workActivityId);
    p.put("role", key.roleId);
    p.put("nt", key.normType);
    if (key.categoryId != null) p.put("cat", key.categoryId);
    if (key.gradeId != null) p.put("gr", key.gradeId);
    if (key.make != null) p.put("mk", key.make);
    if (key.model != null) p.put("md", key.model);
    return p;
  }

  @SuppressWarnings("unchecked")
  private NormRow queryOne(String sql, Map<String, Object> params) {
    var q = em.createNativeQuery(sql);
    params.forEach(q::setParameter);
    List<Object[]> rows = q.setMaxResults(1).getResultList();
    if (rows.isEmpty()) return null;
    Object[] r = rows.get(0);
    return new NormRow(
        toBigDecimal(r[0]),
        toBigDecimal(r[1]),
        r[2] == null ? null : ((Number) r[2]).doubleValue());
  }

  private static BigDecimal toBigDecimal(Object o) {
    if (o == null) return null;
    if (o instanceof BigDecimal bd) return bd;
    if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
    return null;
  }

  /** Record holding the (work_activity, role/variant) lookup signature for caching. */
  private record NormKey(
      UUID roleId, UUID categoryId, UUID gradeId, String make, String model, String normType) {}

  private record NormRow(
      BigDecimal outputPerDay, BigDecimal outputPerManPerDay, Double workingHoursPerDay) {}
}
