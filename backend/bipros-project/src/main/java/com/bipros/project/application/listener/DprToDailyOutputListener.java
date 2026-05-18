package com.bipros.project.application.listener;

import com.bipros.common.event.DprMutationType;
import com.bipros.common.event.DprSubmittedEvent;
import com.bipros.project.application.service.DailyActivityResourceOutputService;
import com.bipros.project.application.service.DailyActivityResourceOutputService.DprResourceAggregate;
import com.bipros.project.application.util.DprCostFormulas;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprEquipment;
import com.bipros.project.domain.model.DprManpower;
import com.bipros.project.domain.model.DprMaterial;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprEquipmentRepository;
import com.bipros.project.domain.repository.DprManpowerRepository;
import com.bipros.project.domain.repository.DprMaterialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FIX-8 / A18 architectural decoupling — bridges a DPR mutation into the
 * {@code project.daily_activity_resource_outputs} ledger so any downstream consumer that depends
 * on the per-(activity × resource × day) ledger sees the data without the supervisor having to
 * re-enter it as a Daily Output row.
 *
 * <h2>Why this exists alongside the in-transaction call in {@code DailyProgressReportService}</h2>
 * The service's create/update/delete path already calls {@code reconcileDprLedger} inline (so the
 * supervisor sees consistent reads immediately after save). This listener is a safety net for
 * publishers that emit a {@link DprSubmittedEvent} <em>without</em> going through the service —
 * future bulk importers, seeders (e.g. {@code OmanRoadDailyDataSeeder} writes DPRs via
 * {@code dprRepository.saveAll} today and bypasses the ledger entirely), or integration tests.
 *
 * <p>{@code DailyActivityResourceOutputService#reconcileDprLedger} is idempotent — it deletes
 * existing rows by {@code dprId} and re-inserts — so running it again after commit on a DPR that
 * was already reconciled in-TX is a cheap no-op (a SELECT, a flush, a rollup recompute).
 *
 * <h2>Phase</h2>
 * {@link TransactionPhase#AFTER_COMMIT} — never rolls back the DPR write if the ledger sync
 * fails. The in-TX path in the service is the authoritative consistency guarantor; this listener
 * is best-effort.
 *
 * <h2>Mapping rules</h2>
 * <ul>
 *   <li>{@code activityId} = parent DPR's {@code activityId}; if null, the parent is purely
 *       free-text → no ledger rows are written for this DPR.</li>
 *   <li>{@code resourceId} = child row's {@code resourceId}; if null (free-text trade only),
 *       <strong>skip silently</strong> at DEBUG level. A WARN here would spam logs on every
 *       seeded DPR that hasn't been re-linked to a Resource instance.</li>
 *   <li>{@code qty} = aggregated per-resource units via {@link DprCostFormulas} (matches the
 *       service path so both call sites produce identical aggregates).</li>
 *   <li>{@code hours} = sum of {@code nos × (workingHours + otHours)} for manpower,
 *       {@code nos × workingHours} for equipment.</li>
 *   <li>{@code days} = {@code hours / 8} — derived by the ledger service from {@code hoursWorked}.</li>
 * </ul>
 *
 * <h2>Known gap: pre-existing DPRs are not back-filled</h2>
 * This listener only fires on new mutations. DPRs that were inserted before this listener existed
 * (via seeders or raw DB writes) will not retroactively populate the ledger. A one-time backfill
 * job — iterate every DPR with {@code activityId != null} and call {@code reconcileDprLedger} —
 * is needed when migrating an existing dataset.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DprToDailyOutputListener {

  private final DailyProgressReportRepository dprRepository;
  private final DprManpowerRepository manpowerRepository;
  private final DprEquipmentRepository equipmentRepository;
  private final DprMaterialRepository materialRepository;
  private final DailyActivityResourceOutputService ledgerService;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onDprSubmitted(DprSubmittedEvent event) {
    DprMutationType type = event.eventType();
    if (type == null) {
      log.debug("DPR event with null eventType skipped: {}", event.dprId());
      return;
    }
    try {
      switch (type) {
        case CREATED, UPDATED -> reconcile(event);
        case DELETED -> ledgerService.deleteDprLedgerInNewTx(
            event.projectId(), event.dprId(), event.reportDate());
      }
    } catch (Exception e) {
      // Never roll back a DPR over a ledger sync failure. The in-TX path in
      // DailyProgressReportService is authoritative; this listener is a safety net.
      log.warn("DprToDailyOutputListener failed for dpr={} type={}: {}",
          event.dprId(), type, e.getMessage(), e);
    }
  }

  private void reconcile(DprSubmittedEvent event) {
    DailyProgressReport dpr = dprRepository.findById(event.dprId()).orElse(null);
    if (dpr == null) {
      // Already deleted in a follow-up — nothing to reconcile.
      log.debug("DPR {} not found for ledger reconcile (post-commit, may have been deleted)",
          event.dprId());
      return;
    }
    if (dpr.getActivityId() == null) {
      // Free-text activity name only — cannot anchor a ledger row to an activity. Still clear
      // any stale ledger rows for this DPR in case activityId was unset on update.
      ledgerService.reconcileDprLedgerInNewTx(
          event.projectId(), event.dprId(), dpr.getReportDate(), List.of());
      return;
    }

    List<DprManpower> manpower = manpowerRepository.findByDprIdOrderByTradeAsc(event.dprId());
    List<DprEquipment> equipment = equipmentRepository.findByDprIdOrderByEquipmentTypeAsc(event.dprId());
    List<DprMaterial> material = materialRepository.findByDprIdOrderByMaterialNameAsc(event.dprId());

    UUID activityId = dpr.getActivityId();
    Map<UUID, BigDecimal> unitsByResource = new HashMap<>();
    Map<UUID, Double> hoursByResource = new HashMap<>();
    Map<UUID, String> unitByResource = new HashMap<>();

    int skipped = 0;
    for (DprManpower row : manpower) {
      if (row.getResourceId() == null) { skipped++; continue; }
      String basis = row.getUnitRateBasis() != null ? row.getUnitRateBasis() : "DAY";
      BigDecimal units = DprCostFormulas.manpowerUnits(row, basis);
      unitsByResource.merge(row.getResourceId(), units, BigDecimal::add);
      double hrs = (row.getWorkingHours() == null ? 0d : row.getWorkingHours().doubleValue())
          + (row.getOtHours() == null ? 0d : row.getOtHours().doubleValue());
      hoursByResource.merge(row.getResourceId(),
          hrs * (row.getNos() == null ? 1 : row.getNos()), Double::sum);
      unitByResource.putIfAbsent(row.getResourceId(),
          basis.equalsIgnoreCase("HOUR") ? "HR" : "DAY");
    }
    for (DprEquipment row : equipment) {
      if (row.getResourceId() == null) { skipped++; continue; }
      String basis = row.getUnitRateBasis() != null ? row.getUnitRateBasis() : "HOUR";
      BigDecimal units = DprCostFormulas.equipmentUnits(row, basis);
      unitsByResource.merge(row.getResourceId(), units, BigDecimal::add);
      double hrs = (row.getWorkingHours() == null ? 0d : row.getWorkingHours().doubleValue())
          * (row.getNos() == null ? 1 : row.getNos());
      hoursByResource.merge(row.getResourceId(), hrs, Double::sum);
      unitByResource.putIfAbsent(row.getResourceId(),
          basis.equalsIgnoreCase("HOUR") ? "HR" : "DAY");
    }
    for (DprMaterial row : material) {
      if (row.getResourceId() == null) { skipped++; continue; }
      BigDecimal units = DprCostFormulas.materialUnits(row);
      unitsByResource.merge(row.getResourceId(), units, BigDecimal::add);
      unitByResource.putIfAbsent(row.getResourceId(),
          row.getUnit() != null ? row.getUnit() : "EA");
    }

    if (skipped > 0) {
      log.debug("DprToDailyOutputListener: skipped {} child row(s) on dpr={} with null resourceId "
          + "(free-text trade — not linked to a Resource instance)", skipped, event.dprId());
    }

    List<DprResourceAggregate> aggregates = new ArrayList<>(unitsByResource.size());
    unitsByResource.entrySet().stream()
        .sorted(Comparator.comparing(e -> e.getKey().toString()))
        .forEach(e -> aggregates.add(new DprResourceAggregate(
            activityId,
            e.getKey(),
            e.getValue(),
            unitByResource.get(e.getKey()),
            hoursByResource.get(e.getKey()),
            null)));

    ledgerService.reconcileDprLedgerInNewTx(
        event.projectId(), event.dprId(), dpr.getReportDate(), aggregates);
  }
}
