package com.bipros.reporting.application.service;

import com.bipros.reporting.application.dto.CapacityUtilizationReport.Budgeted;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Productivity-norm fallback chain shared by the capacity-utilization report and the
 * supervisor-performance report. Same semantics as {@code ProductivityNormLookupService} but
 * exposed as native SQL through the shared {@link EntityManager} so this module stays free of
 * Maven deps on {@code bipros-resource}.
 *
 * @deprecated The role-keyed tier has been folded into both {@link #resolveByResource} and
 *   {@link #resolveByResourceType}, so all reads now flow through this resolver and pick up
 *   role-keyed norms automatically. Future work: extract a shared facade in {@code bipros-common}
 *   and delete this class. Tagged {@code @Deprecated} as a marker — there are no breakage
 *   concerns for callers since the API is unchanged.
 *
 * <p>For Manpower norms we prefer {@code output_per_man_per_day} so the rate is per-person-per-day
 * (matches DPR rows where each manpower line carries {@code nos × hours}). Equipment norms only
 * populate {@code output_per_day}; COALESCE falls through to that, which is the equipment's
 * per-machine-per-day rate.
 */
@Component
@Deprecated
@RequiredArgsConstructor
public class ProductivityNormResolver {

  @PersistenceContext private EntityManager em;

  /**
   * Resolution chain (post role-only migration):
   * <ol>
   *   <li><b>VARIANT</b> — {@code (work_activity, role, category, grade)} for manpower or
   *       {@code (work_activity, role, make, model)} for equipment. Variant data isn't carried
   *       on the DPR rollup row, so the report only resolves this tier when a single resource
   *       happens to have those columns set on the norm via {@code role_id}.</li>
   *   <li><b>ROLE</b> — {@code (work_activity, role)}.</li>
   *   <li><b>SPECIFIC_RESOURCE</b> — legacy override.</li>
   *   <li><b>RESOURCE_TYPE</b> — legacy default.</li>
   *   <li><b>WORK_ACTIVITY</b> — unscoped tier; the 102 seeded rows live here.</li>
   *   <li><b>RESOURCE_LEGACY</b> — last-resort {@code resource_equipment_details.standard_output_per_day}.</li>
   * </ol>
   * The resolver stays inside the reporting module (no Maven dep on bipros-resource) by using
   * the same native-SQL pattern across schemas.
   */
  public Budgeted resolveByResource(UUID workActivityId, UUID resourceId) {
    if (workActivityId == null || resourceId == null) {
      return new Budgeted(null, "NONE");
    }
    // Load the resource's role_id (if any) so we can try the role-keyed tiers first.
    UUID roleId = singleUuid(
        "SELECT r.role_id FROM resource.resources r WHERE r.id = :res",
        Map.of("res", resourceId));

    if (roleId != null) {
      BigDecimal roleLevel = singleBigDecimal(
          "SELECT COALESCE(n.output_per_man_per_day, n.output_per_day) "
              + "FROM resource.productivity_norms n "
              + "WHERE n.work_activity_id = :wa "
              + "  AND n.role_id = :role "
              + "  AND n.category_id IS NULL AND n.grade_id IS NULL "
              + "  AND n.make IS NULL AND n.model IS NULL "
              + "ORDER BY n.created_at NULLS LAST",
          Map.of("wa", workActivityId, "role", roleId));
      if (roleLevel != null) {
        return new Budgeted(roleLevel, "ROLE");
      }
    }

    BigDecimal specific = singleBigDecimal(
        "SELECT COALESCE(n.output_per_man_per_day, n.output_per_day) "
            + "FROM resource.productivity_norms n "
            + "WHERE n.work_activity_id = :wa AND n.resource_id = :res",
        Map.of("wa", workActivityId, "res", resourceId));
    if (specific != null) {
      return new Budgeted(specific, "SPECIFIC_RESOURCE");
    }
    BigDecimal typeLevel = singleBigDecimal(
        "SELECT COALESCE(n.output_per_man_per_day, n.output_per_day) "
            + "FROM resource.productivity_norms n "
            + "JOIN resource.resources r ON r.resource_type_id = n.resource_type_id "
            + "WHERE n.work_activity_id = :wa AND n.resource_id IS NULL "
            + "  AND r.id = :res",
        Map.of("wa", workActivityId, "res", resourceId));
    if (typeLevel != null) {
      return new Budgeted(typeLevel, "RESOURCE_TYPE");
    }
    // Unscoped: work-activity-only norm (no resource/type/role binding). Picks the highest-
    // priority value: output_per_man_per_day first (manpower-friendly), output_per_day next,
    // ordered by created_at for determinism. The 102 seeded rows match here.
    BigDecimal workActivityOnly = singleBigDecimal(
        "SELECT COALESCE(n.output_per_man_per_day, n.output_per_day) "
            + "FROM resource.productivity_norms n "
            + "WHERE n.work_activity_id = :wa AND n.resource_id IS NULL "
            + "  AND n.resource_type_id IS NULL "
            + "  AND n.role_id IS NULL "
            + "ORDER BY (n.output_per_man_per_day IS NULL), n.created_at NULLS LAST",
        Map.of("wa", workActivityId));
    if (workActivityOnly != null) {
      return new Budgeted(workActivityOnly, "WORK_ACTIVITY");
    }
    BigDecimal legacy = singleBigDecimal(
        "SELECT d.standard_output_per_day FROM resource.resource_equipment_details d "
            + "WHERE d.resource_id = :res",
        Map.of("res", resourceId));
    if (legacy != null) {
      return new Budgeted(legacy, "RESOURCE_LEGACY");
    }
    return new Budgeted(null, "NONE");
  }

  /**
   * Type-driven lookup (no specific-resource path). Used by the supervisor-performance trade /
   * equipment rollup where the canonical key is a trade or equipment-type, not a single resource.
   *
   * <p>Filters by {@code norm_type} so a MANPOWER norm is never applied to equipment and vice
   * versa. For MANPOWER, prefers {@code output_per_man_per_day} (per-person-per-day rate matches
   * how DPR rows count person-hours). For EQUIPMENT, prefers {@code output_per_day} (per-machine-
   * per-day rate matches how DPR rows count machine-hours).
   *
   * <p>Falls back to a work-activity-only norm of the SAME {@code normType} when no type-specific
   * match exists. Returns NONE when the work activity has no norm of the requested kind — caller
   * should display "—%" rather than fabricating a number.
   */
  public Budgeted resolveByResourceType(UUID workActivityId, UUID resourceTypeId, String normType) {
    if (workActivityId == null || normType == null) {
      return new Budgeted(null, "NONE");
    }
    String preferredColumn = "EQUIPMENT".equalsIgnoreCase(normType)
        ? "n.output_per_day"
        : "COALESCE(n.output_per_man_per_day, n.output_per_day)";

    if (resourceTypeId != null) {
      BigDecimal typeLevel = singleBigDecimal(
          "SELECT " + preferredColumn + " "
              + "FROM resource.productivity_norms n "
              + "WHERE n.work_activity_id = :wa "
              + "  AND n.norm_type = :nt "
              + "  AND n.resource_id IS NULL "
              + "  AND n.resource_type_id = :rt",
          Map.of("wa", workActivityId, "nt", normType, "rt", resourceTypeId));
      if (typeLevel != null) {
        return new Budgeted(typeLevel, "RESOURCE_TYPE");
      }
    }
    BigDecimal workActivityOnly = singleBigDecimal(
        "SELECT " + preferredColumn + " "
            + "FROM resource.productivity_norms n "
            + "WHERE n.work_activity_id = :wa "
            + "  AND n.norm_type = :nt "
            + "  AND n.resource_id IS NULL "
            + "  AND n.resource_type_id IS NULL "
            + "  AND n.role_id IS NULL "
            + "ORDER BY (" + preferredColumn + " IS NULL), n.created_at NULLS LAST",
        Map.of("wa", workActivityId, "nt", normType));
    if (workActivityOnly != null) {
      return new Budgeted(workActivityOnly, "WORK_ACTIVITY");
    }
    return new Budgeted(null, "NONE");
  }

  /** Backwards-compat overload — defers to {@code MANPOWER} which was the prior implicit default. */
  public Budgeted resolveByResourceType(UUID workActivityId, UUID resourceTypeId) {
    return resolveByResourceType(workActivityId, resourceTypeId, "MANPOWER");
  }

  @SuppressWarnings("unchecked")
  private BigDecimal singleBigDecimal(String sql, Map<String, Object> params) {
    var query = em.createNativeQuery(sql);
    params.forEach(query::setParameter);
    List<Object> rows = query.setMaxResults(1).getResultList();
    if (rows.isEmpty() || rows.get(0) == null) return null;
    Object o = rows.get(0);
    if (o instanceof BigDecimal bd) return bd;
    if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
    return null;
  }

  @SuppressWarnings("unchecked")
  private UUID singleUuid(String sql, Map<String, Object> params) {
    var query = em.createNativeQuery(sql);
    params.forEach(query::setParameter);
    List<Object> rows = query.setMaxResults(1).getResultList();
    if (rows.isEmpty() || rows.get(0) == null) return null;
    Object o = rows.get(0);
    if (o instanceof UUID u) return u;
    if (o instanceof String s) return UUID.fromString(s);
    return null;
  }
}
