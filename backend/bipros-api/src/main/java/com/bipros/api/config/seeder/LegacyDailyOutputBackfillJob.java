package com.bipros.api.config.seeder;

import com.bipros.project.application.service.DailyActivityResourceOutputService;
import com.bipros.project.application.service.DailyActivityResourceOutputService.DprResourceAggregate;
import com.bipros.project.application.util.DprCostFormulas;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprEquipment;
import com.bipros.project.domain.model.DprManpower;
import com.bipros.project.domain.model.DprMaterial;
import com.bipros.project.domain.repository.DailyActivityResourceOutputRepository;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprEquipmentRepository;
import com.bipros.project.domain.repository.DprManpowerRepository;
import com.bipros.project.domain.repository.DprMaterialRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-shot idempotent backfill for the legacy {@code project.daily_activity_resource_outputs}
 * ledger. Walks every {@link DailyProgressReport} whose DPR children carry a {@code roleId} but
 * no corresponding ledger row exists, resolves a {@code resourceId} from the role chain, and
 * invokes the existing {@link DailyActivityResourceOutputService#reconcileDprLedger} apply path
 * so rollup + event publishing semantics match the runtime DPR write path.
 *
 * <p>Gated by {@code bipros.backfill.legacy-daily-output.enabled} so it doesn't run on every
 * boot — flip the property once when migrating an existing dataset, then turn it off again.
 * Unresolved rows (role with no resource match) are written to
 * {@code backend/storage/legacy-output-backfill-unresolved.csv} for manual triage.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(200)
public class LegacyDailyOutputBackfillJob {

  private static final double DEFAULT_HOURS_PER_DAY = 8.0;
  private static final Path UNRESOLVED_CSV =
      Paths.get("storage", "legacy-output-backfill-unresolved.csv");

  private final DailyProgressReportRepository dprRepository;
  private final DprManpowerRepository manpowerRepository;
  private final DprEquipmentRepository equipmentRepository;
  private final DprMaterialRepository materialRepository;
  private final DailyActivityResourceOutputRepository ledgerRepository;
  private final DailyActivityResourceOutputService ledgerService;

  @PersistenceContext private EntityManager em;

  @Value("${bipros.backfill.legacy-daily-output.enabled:false}")
  private boolean enabled;

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    if (!enabled) {
      log.debug("LegacyDailyOutputBackfillJob disabled (set bipros.backfill.legacy-daily-output.enabled=true to run)");
      return;
    }
    log.info("LegacyDailyOutputBackfillJob: starting…");
    int processed = 0;
    int reconciled = 0;
    int skippedAlreadyBridged = 0;
    int skippedNoActivity = 0;
    int unresolvedRows = 0;

    List<String> unresolvedCsvLines = new ArrayList<>();
    unresolvedCsvLines.add("dpr_id,report_date,activity_id,role_id,role_kind,trade_or_type");

    for (DailyProgressReport dpr : dprRepository.findAll()) {
      processed++;
      if (dpr.getActivityId() == null) {
        skippedNoActivity++;
        continue;
      }
      List<DprManpower> manpower = manpowerRepository.findByDprIdOrderByTradeAsc(dpr.getId());
      List<DprEquipment> equipment = equipmentRepository.findByDprIdOrderByEquipmentTypeAsc(dpr.getId());
      List<DprMaterial> material = materialRepository.findByDprIdOrderByMaterialNameAsc(dpr.getId());

      // Aggregate per-resource units identically to DprToDailyOutputListener so the ledger
      // shape matches what the runtime path produces.
      Map<UUID, BigDecimal> unitsByResource = new HashMap<>();
      Map<UUID, Double> hoursByResource = new HashMap<>();
      Map<UUID, String> unitByResource = new HashMap<>();

      // Track whether we found at least one role-only row in need of resolution so we can
      // skip cheaply when the DPR was fully resource-linked already.
      boolean anyRoleOnlyResolved = false;

      for (DprManpower row : manpower) {
        UUID resourceId = row.getResourceId();
        if (resourceId == null && row.getRoleId() != null) {
          resourceId = resolveResourceFromRole(row.getRoleId(), dpr.getProjectId(), dpr.getActivityId());
          if (resourceId == null) {
            unresolvedRows++;
            unresolvedCsvLines.add(csv(dpr.getId(), dpr.getReportDate().toString(),
                dpr.getActivityId(), row.getRoleId(), "MANPOWER", row.getTrade()));
            continue;
          }
          anyRoleOnlyResolved = true;
        }
        if (resourceId == null) continue;
        String basis = row.getUnitRateBasis() != null ? row.getUnitRateBasis() : "DAY";
        BigDecimal units = DprCostFormulas.manpowerUnits(row, basis);
        unitsByResource.merge(resourceId, units, BigDecimal::add);
        double hrs = (row.getWorkingHours() == null ? 0d : row.getWorkingHours().doubleValue())
            + (row.getOtHours() == null ? 0d : row.getOtHours().doubleValue());
        hoursByResource.merge(resourceId,
            hrs * (row.getNos() == null ? 1 : row.getNos()), Double::sum);
        unitByResource.putIfAbsent(resourceId, basis.equalsIgnoreCase("HOUR") ? "HR" : "DAY");
      }
      for (DprEquipment row : equipment) {
        UUID resourceId = row.getResourceId();
        if (resourceId == null && row.getRoleId() != null) {
          resourceId = resolveResourceFromRole(row.getRoleId(), dpr.getProjectId(), dpr.getActivityId());
          if (resourceId == null) {
            unresolvedRows++;
            unresolvedCsvLines.add(csv(dpr.getId(), dpr.getReportDate().toString(),
                dpr.getActivityId(), row.getRoleId(), "EQUIPMENT", row.getEquipmentType()));
            continue;
          }
          anyRoleOnlyResolved = true;
        }
        if (resourceId == null) continue;
        String basis = row.getUnitRateBasis() != null ? row.getUnitRateBasis() : "HOUR";
        BigDecimal units = DprCostFormulas.equipmentUnits(row, basis);
        unitsByResource.merge(resourceId, units, BigDecimal::add);
        double hrs = (row.getWorkingHours() == null ? 0d : row.getWorkingHours().doubleValue())
            * (row.getNos() == null ? 1 : row.getNos());
        hoursByResource.merge(resourceId, hrs, Double::sum);
        unitByResource.putIfAbsent(resourceId, basis.equalsIgnoreCase("HOUR") ? "HR" : "DAY");
      }
      for (DprMaterial row : material) {
        UUID resourceId = row.getResourceId();
        if (resourceId == null && row.getRoleId() != null) {
          resourceId = resolveResourceFromRole(row.getRoleId(), dpr.getProjectId(), dpr.getActivityId());
          if (resourceId == null) {
            unresolvedRows++;
            unresolvedCsvLines.add(csv(dpr.getId(), dpr.getReportDate().toString(),
                dpr.getActivityId(), row.getRoleId(), "MATERIAL", row.getMaterialName()));
            continue;
          }
          anyRoleOnlyResolved = true;
        }
        if (resourceId == null) continue;
        BigDecimal units = DprCostFormulas.materialUnits(row);
        unitsByResource.merge(resourceId, units, BigDecimal::add);
        unitByResource.putIfAbsent(resourceId, row.getUnit() != null ? row.getUnit() : "EA");
      }

      if (unitsByResource.isEmpty()) {
        continue;
      }

      // Idempotency: if the DPR already has ledger rows AND we didn't pick up any new
      // role-only resolutions, skip cheaply. reconcileDprLedger is idempotent — re-running on a
      // fully bridged DPR is harmless — but skipping the work keeps the log clean.
      List<com.bipros.project.domain.model.DailyActivityResourceOutput> existing =
          ledgerRepository.findByDprId(dpr.getId());
      if (!existing.isEmpty() && !anyRoleOnlyResolved) {
        skippedAlreadyBridged++;
        continue;
      }

      List<DprResourceAggregate> aggregates = new ArrayList<>(unitsByResource.size());
      for (Map.Entry<UUID, BigDecimal> entry : unitsByResource.entrySet()) {
        aggregates.add(new DprResourceAggregate(
            dpr.getActivityId(),
            entry.getKey(),
            entry.getValue(),
            unitByResource.get(entry.getKey()),
            hoursByResource.get(entry.getKey()),
            null));
      }
      try {
        ledgerService.reconcileDprLedger(
            dpr.getProjectId(), dpr.getId(), dpr.getReportDate(), aggregates);
        reconciled++;
      } catch (Exception e) {
        log.warn("LegacyDailyOutputBackfillJob: reconcile failed for dpr={}: {}",
            dpr.getId(), e.getMessage());
      }
    }

    writeUnresolvedCsv(unresolvedCsvLines);

    log.info("LegacyDailyOutputBackfillJob: done. processed={}, reconciled={}, "
            + "skippedAlreadyBridged={}, skippedNoActivity={}, unresolvedRows={}",
        processed, reconciled, skippedAlreadyBridged, skippedNoActivity, unresolvedRows);
  }

  /**
   * Resolve a {@code resourceId} from a {@code roleId} by walking the most natural join:
   * (1) a {@code ResourceAssignment} that already pins the (project, activity, role) to a
   * resource — this is the highest-fidelity match; (2) fall back to the first {@code Resource}
   * with the given role. Returns null when no match.
   */
  private UUID resolveResourceFromRole(UUID roleId, UUID projectId, UUID activityId) {
    if (roleId == null) return null;
    try {
      // Prefer (project, activity, role) → resource via resource_assignments.
      List<?> rows = em.createNativeQuery(
              "SELECT resource_id FROM resource.resource_assignments "
                  + "WHERE project_id = :pid AND activity_id = :aid AND role_id = :rid "
                  + "  AND resource_id IS NOT NULL "
                  + "ORDER BY created_at NULLS LAST")
          .setParameter("pid", projectId)
          .setParameter("aid", activityId)
          .setParameter("rid", roleId)
          .setMaxResults(1)
          .getResultList();
      if (!rows.isEmpty() && rows.get(0) != null) {
        Object v = rows.get(0);
        if (v instanceof UUID u) return u;
        return UUID.fromString(v.toString());
      }
      // Fallback: any resource with this role (used when the assignment row never had a
      // resource_id set — common for role-only seeded data).
      rows = em.createNativeQuery(
              "SELECT id FROM resource.resources WHERE role_id = :rid "
                  + "ORDER BY created_at NULLS LAST")
          .setParameter("rid", roleId)
          .setMaxResults(1)
          .getResultList();
      if (!rows.isEmpty() && rows.get(0) != null) {
        Object v = rows.get(0);
        if (v instanceof UUID u) return u;
        return UUID.fromString(v.toString());
      }
    } catch (Exception e) {
      log.debug("resolveResourceFromRole failed for role={} project={} activity={}: {}",
          roleId, projectId, activityId, e.getMessage());
    }
    return null;
  }

  private static String csv(UUID dprId, String reportDate, UUID activityId, UUID roleId,
                            String kind, String label) {
    String safeLabel = label == null ? "" : label.replace(",", " ").replace("\n", " ");
    return String.join(",",
        String.valueOf(dprId),
        reportDate,
        String.valueOf(activityId),
        String.valueOf(roleId),
        kind,
        safeLabel);
  }

  private void writeUnresolvedCsv(List<String> lines) {
    if (lines.size() <= 1) return; // only the header → nothing unresolved
    try {
      Path parent = UNRESOLVED_CSV.getParent();
      if (parent != null) Files.createDirectories(parent);
      try (BufferedWriter w = Files.newBufferedWriter(UNRESOLVED_CSV, StandardCharsets.UTF_8,
          StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
        for (String line : lines) {
          w.write(line);
          w.newLine();
        }
      }
      log.info("LegacyDailyOutputBackfillJob: wrote {} unresolved row(s) to {}",
          lines.size() - 1, UNRESOLVED_CSV.toAbsolutePath());
    } catch (IOException e) {
      log.warn("LegacyDailyOutputBackfillJob: failed to write unresolved CSV {}: {}",
          UNRESOLVED_CSV, e.getMessage());
    }
  }
}
