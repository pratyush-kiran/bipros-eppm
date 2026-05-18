package com.bipros.project.application.util;

import com.bipros.project.domain.model.DeploymentResourceType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cross-schema lookup that derives a planned headcount for a {@code DailyResourceDeployment}
 * from {@code resource.resource_assignments}. Stays in {@code bipros-project} (alongside the
 * service that needs it) and reaches into the {@code resource} schema via a native query — same
 * decoupling pattern used by {@code DailyActivityResourceOutputService.resolveUnitFromActivity}.
 *
 * <p>Returns the {@code SUM(planned_units)} (preferring {@code headcount} when populated for
 * variant resources) across all {@code ResourceAssignment} rows where:
 * <ol>
 *   <li>{@code project_id} matches the deployment's project,</li>
 *   <li>{@code role_id} matches the deployment's {@code resource_role_id} (when supplied),
 *       otherwise loosely filters by deployment description,</li>
 *   <li>{@code deploymentDate} falls within
 *       {@code [planned_start_date, planned_finish_date]}.</li>
 * </ol>
 */
@Component
@Slf4j
public class ResourceAssignmentPlannedHeadcountResolver {

  @PersistenceContext private EntityManager em;

  /**
   * Resolve the auto-derived planned headcount for a single deployment row. Returns
   * {@link Optional#empty()} when no assignment matches the (project, role, date) triple — the
   * caller should leave {@code nosPlanned} null and not set {@code nosPlannedAuto}.
   */
  public Optional<Integer> resolve(UUID projectId,
                                    UUID resourceRoleId,
                                    DeploymentResourceType resourceType,
                                    LocalDate deploymentDate) {
    if (projectId == null || deploymentDate == null) return Optional.empty();
    if (resourceRoleId == null) {
      // Without a role anchor we can't reliably attribute plannedUnits to this deployment —
      // the deployment row is free-text only.
      return Optional.empty();
    }
    try {
      // Prefer headcount (an integer headcount for variant manpower/equipment); fall back to
      // planned_units (Double, used for material quantities and legacy rows).
      @SuppressWarnings("unchecked")
      List<Object[]> rows = em.createNativeQuery(
              "SELECT COALESCE(SUM(ra.headcount), 0) AS sum_headcount, "
                  + "       COALESCE(SUM(ra.planned_units), 0) AS sum_planned_units "
                  + "FROM resource.resource_assignments ra "
                  + "WHERE ra.project_id = :projectId "
                  + "  AND ra.role_id = :roleId "
                  + "  AND ( "
                  + "        (ra.planned_start_date IS NULL AND ra.planned_finish_date IS NULL) "
                  + "     OR (ra.planned_start_date IS NULL AND ra.planned_finish_date >= :dt) "
                  + "     OR (ra.planned_finish_date IS NULL AND ra.planned_start_date <= :dt) "
                  + "     OR (ra.planned_start_date <= :dt AND ra.planned_finish_date >= :dt))")
          .setParameter("projectId", projectId)
          .setParameter("roleId", resourceRoleId)
          .setParameter("dt", deploymentDate)
          .getResultList();
      if (rows.isEmpty() || rows.get(0) == null) return Optional.empty();
      Object[] r = rows.get(0);
      BigDecimal sumHeadcount = toBigDecimal(r[0]);
      BigDecimal sumPlannedUnits = toBigDecimal(r[1]);
      BigDecimal picked = (sumHeadcount != null && sumHeadcount.signum() > 0)
          ? sumHeadcount
          : sumPlannedUnits;
      if (picked == null || picked.signum() <= 0) return Optional.empty();
      return Optional.of(picked.intValue());
    } catch (Exception e) {
      log.debug("ResourceAssignmentPlannedHeadcountResolver.resolve failed "
              + "(projectId={}, roleId={}, date={}): {}",
          projectId, resourceRoleId, deploymentDate, e.getMessage());
      return Optional.empty();
    }
  }

  private static BigDecimal toBigDecimal(Object o) {
    if (o == null) return null;
    if (o instanceof BigDecimal bd) return bd;
    if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
    return null;
  }
}
