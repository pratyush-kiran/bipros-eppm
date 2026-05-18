package com.bipros.project.application.service;

import com.bipros.common.event.DailyOutputChangedEvent;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.util.AuditService;
import com.bipros.project.application.dto.CreateDailyActivityResourceOutputRequest;
import com.bipros.project.application.dto.DailyActivityResourceOutputResponse;
import com.bipros.project.domain.model.DailyActivityResourceOutput;
import com.bipros.project.domain.repository.DailyActivityResourceOutputRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import java.math.BigDecimal;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class DailyActivityResourceOutputService {

  /** Default working hours used to derive {@code daysWorked} when only hours are supplied. */
  private static final double DEFAULT_HOURS_PER_DAY = 8.0;

  private final DailyActivityResourceOutputRepository repository;
  private final ProjectRepository projectRepository;
  private final AuditService auditService;
  private final ApplicationEventPublisher eventPublisher;

  /** Used to resolve the activity's {@code work_activity.default_unit} via a tiny native query —
   *  keeps {@code bipros-project} independent of {@code bipros-activity}/{@code bipros-resource}
   *  while still letting the unit auto-fill when blank. */
  @PersistenceContext
  private EntityManager em;

  public DailyActivityResourceOutputResponse create(
      UUID projectId, CreateDailyActivityResourceOutputRequest request) {
    ensureProjectExists(projectId);
    rejectDuplicate(projectId, request.outputDate(), request.activityId(), request.resourceId(), null);

    String unit = request.unit() == null || request.unit().isBlank()
        ? resolveUnitFromActivity(request.activityId())
        : request.unit().trim();
    if (unit == null || unit.isBlank()) {
      throw new BusinessRuleException("UNIT_REQUIRED",
          "unit is required (and the linked activity has no default unit to fall back to)");
    }

    Double daysWorked = request.daysWorked();
    if (daysWorked == null && request.hoursWorked() != null) {
      daysWorked = request.hoursWorked() / DEFAULT_HOURS_PER_DAY;
    }

    DailyActivityResourceOutput row = DailyActivityResourceOutput.builder()
        .projectId(projectId)
        .outputDate(request.outputDate())
        .activityId(request.activityId())
        .resourceId(request.resourceId())
        .qtyExecuted(request.qtyExecuted())
        .unit(unit)
        .hoursWorked(request.hoursWorked())
        .daysWorked(daysWorked)
        .remarks(request.remarks())
        .build();

    DailyActivityResourceOutput saved = repository.save(row);
    recomputeAssignmentRollup(projectId, request.activityId(), request.resourceId());
    eventPublisher.publishEvent(
        new DailyOutputChangedEvent(projectId, request.activityId(), request.resourceId(),
            request.outputDate(), request.qtyExecuted()));
    auditService.logCreate("DailyActivityResourceOutput", saved.getId(),
        DailyActivityResourceOutputResponse.from(saved));
    log.info("Created DailyActivityResourceOutput id={} project={} date={} activity={} resource={}",
        saved.getId(), projectId, request.outputDate(), request.activityId(), request.resourceId());
    return DailyActivityResourceOutputResponse.from(saved);
  }

  public List<DailyActivityResourceOutputResponse> createBulk(
      UUID projectId, List<CreateDailyActivityResourceOutputRequest> requests) {
    return requests.stream().map(r -> create(projectId, r)).toList();
  }

  @Transactional(readOnly = true)
  public List<DailyActivityResourceOutputResponse> list(
      UUID projectId, LocalDate fromDate, LocalDate toDate, UUID activityId, UUID resourceId) {
    ensureProjectExists(projectId);
    List<DailyActivityResourceOutput> rows;
    if (activityId != null) {
      rows = repository.findByProjectIdAndActivityIdOrderByOutputDateDescIdAsc(projectId, activityId);
    } else if (resourceId != null) {
      rows = repository.findByProjectIdAndResourceIdOrderByOutputDateDescIdAsc(projectId, resourceId);
    } else if (fromDate != null && toDate != null) {
      rows = repository.findByProjectIdAndOutputDateBetweenOrderByOutputDateDescIdAsc(projectId, fromDate, toDate);
    } else {
      rows = repository.findByProjectIdOrderByOutputDateDescIdAsc(projectId);
    }
    return rows.stream().map(DailyActivityResourceOutputResponse::from).toList();
  }

  @Transactional(readOnly = true)
  public DailyActivityResourceOutputResponse get(UUID projectId, UUID id) {
    return DailyActivityResourceOutputResponse.from(find(projectId, id));
  }

  public void delete(UUID projectId, UUID id) {
    DailyActivityResourceOutput row = find(projectId, id);
    UUID activityId = row.getActivityId();
    UUID resourceId = row.getResourceId();
    repository.delete(row);
    recomputeAssignmentRollup(projectId, activityId, resourceId);
    eventPublisher.publishEvent(new DailyOutputChangedEvent(projectId, activityId, resourceId, row.getOutputDate(), row.getQtyExecuted()));
    auditService.logDelete("DailyActivityResourceOutput", id);
  }

  private DailyActivityResourceOutput find(UUID projectId, UUID id) {
    DailyActivityResourceOutput row = repository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("DailyActivityResourceOutput", id));
    if (!row.getProjectId().equals(projectId)) {
      throw new ResourceNotFoundException("DailyActivityResourceOutput", id);
    }
    return row;
  }

  private void rejectDuplicate(UUID projectId, LocalDate outputDate, UUID activityId, UUID resourceId, UUID excludeId) {
    // Only block when an existing MANUAL row matches the key. DPR-sourced rows are allowed to
    // coexist (different DPRs, same activity/resource/day) and never collide with the MANUAL CRUD
    // path because they always carry source='DPR'.
    repository
        .findFirstByProjectIdAndOutputDateAndActivityIdAndResourceIdAndSource(
            projectId, outputDate, activityId, resourceId, "MANUAL")
        .filter(existing -> excludeId == null || !existing.getId().equals(excludeId))
        .ifPresent(existing -> {
          throw new BusinessRuleException("DUPLICATE_DAILY_OUTPUT",
              "A daily output already exists for this project + date + activity + resource");
        });
  }

  private void ensureProjectExists(UUID projectId) {
    if (!projectRepository.existsById(projectId)) {
      throw new ResourceNotFoundException("Project", projectId);
    }
  }

  /**
   * Re-aggregates this ledger into the matching {@code resource.resource_assignments} row.
   *
   * <p>{@code actual_units = SUM(qty_executed)}, {@code actual_start_date = MIN(output_date)},
   * {@code remaining_units = MAX(planned_units - actual_units, 0)} for the
   * {@code (project, activity, resource)} triple. Cross-schema native UPDATE — same precedent as
   * {@link #resolveUnitFromActivity}; keeps {@code bipros-project} free of a Maven dep on
   * {@code bipros-resource}. Cost rollup ({@code actual_cost}) is intentionally out of scope here:
   * it needs effective-dated {@code ResourceRate} resolution and belongs in the resource module.
   */
  private void recomputeAssignmentRollup(UUID projectId, UUID activityId, UUID resourceId) {
    // LEAST() ignores NULLs in Postgres, so an externally-set actual_start_date (P6 import, seed)
    // is preserved when it predates the ledger. Only the ledger drives actual_units / remaining_units —
    // those are fully derived here.
    int updated = em.createNativeQuery(
            "UPDATE resource.resource_assignments AS ra "
                + "SET actual_units = COALESCE(agg.total_units, 0), "
                + "    actual_start_date = LEAST(ra.actual_start_date, agg.min_date), "
                + "    remaining_units = GREATEST(COALESCE(ra.planned_units, 0) - COALESCE(agg.total_units, 0), 0) "
                + "FROM ( "
                + "  SELECT SUM(qty_executed)::double precision AS total_units, MIN(output_date) AS min_date "
                + "  FROM project.daily_activity_resource_outputs "
                + "  WHERE project_id = :projectId AND activity_id = :activityId AND resource_id = :resourceId "
                + ") AS agg "
                + "WHERE ra.project_id = :projectId AND ra.activity_id = :activityId AND ra.resource_id = :resourceId")
        .setParameter("projectId", projectId)
        .setParameter("activityId", activityId)
        .setParameter("resourceId", resourceId)
        .executeUpdate();
    if (updated == 0) {
      log.debug("No ResourceAssignment matches project={} activity={} resource={} — rollup skipped",
          projectId, activityId, resourceId);
    } else {
      log.debug("Rolled up daily outputs into {} ResourceAssignment row(s) for project={} activity={} resource={}",
          updated, projectId, activityId, resourceId);
    }
  }

  /**
   * Public hook for the DPR write path. Replaces all DPR-sourced ledger rows for the given DPR
   * with the supplied per-resource aggregates, then recomputes assignment rollup + publishes
   * {@link DailyOutputChangedEvent} for each affected (activity, resource) pair so the existing
   * cost-rollup + units-percent-complete chains pick up the change.
   *
   * <p>{@code aggregates} is a list of one row per {@code (activityId, resourceId)} touched by
   * this DPR. Pass empty / null to reconcile a DPR with zero child rows (still triggers cleanup).
   */
  public void reconcileDprLedger(UUID projectId,
                                  UUID dprId,
                                  LocalDate reportDate,
                                  List<DprResourceAggregate> aggregates) {
    if (projectId == null || dprId == null || reportDate == null) {
      throw new IllegalArgumentException("projectId, dprId, and reportDate are required");
    }

    List<DailyActivityResourceOutput> existing = repository.findByDprId(dprId);
    java.util.Map<String, DailyActivityResourceOutput> existingByKey = new java.util.HashMap<>();
    for (DailyActivityResourceOutput row : existing) {
      existingByKey.put(row.getActivityId() + "::" + row.getResourceId(), row);
    }

    java.util.Set<String> seenKeys = new java.util.HashSet<>();
    java.util.Set<java.util.Map.Entry<UUID, UUID>> affectedPairs = new java.util.HashSet<>();
    java.util.Map<java.util.Map.Entry<UUID, UUID>, BigDecimal> qtyByPair = new java.util.HashMap<>();

    if (aggregates != null) {
      for (DprResourceAggregate agg : aggregates) {
        if (agg.activityId() == null || agg.resourceId() == null) continue;
        String key = agg.activityId() + "::" + agg.resourceId();
        seenKeys.add(key);
        DailyActivityResourceOutput row = existingByKey.get(key);
        if (row == null) {
          row = DailyActivityResourceOutput.builder()
              .projectId(projectId)
              .outputDate(reportDate)
              .activityId(agg.activityId())
              .resourceId(agg.resourceId())
              .qtyExecuted(agg.qtyExecuted() != null ? agg.qtyExecuted() : BigDecimal.ZERO)
              .unit(agg.unit() != null ? agg.unit() : "EA")
              .hoursWorked(agg.hoursWorked())
              .daysWorked(agg.hoursWorked() == null ? null : agg.hoursWorked() / DEFAULT_HOURS_PER_DAY)
              .dprId(dprId)
              .source("DPR")
              .remarks(agg.remarks())
              .build();
        } else {
          row.setOutputDate(reportDate);
          row.setQtyExecuted(agg.qtyExecuted() != null ? agg.qtyExecuted() : BigDecimal.ZERO);
          row.setUnit(agg.unit() != null ? agg.unit() : row.getUnit());
          row.setHoursWorked(agg.hoursWorked());
          row.setDaysWorked(agg.hoursWorked() == null ? null : agg.hoursWorked() / DEFAULT_HOURS_PER_DAY);
          row.setRemarks(agg.remarks());
          row.setSource("DPR");
        }
        repository.save(row);
        affectedPairs.add(java.util.Map.entry(agg.activityId(), agg.resourceId()));
        qtyByPair.put(java.util.Map.entry(agg.activityId(), agg.resourceId()),
            row.getQtyExecuted());
      }
    }

    // Drop any leftover ledger rows owned by this DPR that no longer correspond to a child row
    // (e.g. resource removed in an update). Each such pair still needs a rollup to bring its
    // ResourceAssignment.actualUnits down.
    for (DailyActivityResourceOutput row : existing) {
      String key = row.getActivityId() + "::" + row.getResourceId();
      if (!seenKeys.contains(key)) {
        repository.delete(row);
        affectedPairs.add(java.util.Map.entry(row.getActivityId(), row.getResourceId()));
      }
    }

    // Flush before the rollup query so the new/updated/deleted rows are visible to the SUM.
    repository.flush();

    for (java.util.Map.Entry<UUID, UUID> pair : affectedPairs) {
      recomputeAssignmentRollup(projectId, pair.getKey(), pair.getValue());
      BigDecimal qty = qtyByPair.getOrDefault(pair, BigDecimal.ZERO);
      eventPublisher.publishEvent(new DailyOutputChangedEvent(
          projectId, pair.getKey(), pair.getValue(), reportDate, qty));
    }
  }

  /**
   * AFTER_COMMIT-safe variant of {@link #reconcileDprLedger}. Forces a brand-new transaction so
   * that {@code EntityManager} writes (esp. {@code repository.flush()} at line 266) succeed when
   * the caller runs inside an AFTER_COMMIT listener — at that point the outer JpaTransactionManager
   * has already triggered after-completion callbacks and the inherited TX state cannot accept
   * further writes, even though the inner {@code @Transactional(REQUIRED)} interceptor thinks it
   * has begun a new TX. {@code REQUIRES_NEW} explicitly suspends the (defunct) outer binding and
   * starts a fresh EM, which is what {@link DprToDailyOutputListener} needs.
   *
   * <p>Inline callers in {@code DailyProgressReportService} keep using
   * {@link #reconcileDprLedger} so they participate in the parent DPR-write TX (so a rollback of
   * the DPR also rolls back the ledger write).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void reconcileDprLedgerInNewTx(UUID projectId,
                                         UUID dprId,
                                         LocalDate reportDate,
                                         List<DprResourceAggregate> aggregates) {
    reconcileDprLedger(projectId, dprId, reportDate, aggregates);
  }

  /**
   * AFTER_COMMIT-safe variant of {@link #deleteDprLedger}. See {@link #reconcileDprLedgerInNewTx}
   * for rationale.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void deleteDprLedgerInNewTx(UUID projectId, UUID dprId, LocalDate reportDate) {
    deleteDprLedger(projectId, dprId, reportDate);
  }

  /**
   * Public hook used by the DPR delete path. Removes every DPR-owned ledger row for the DPR,
   * then runs the rollup + publishes the change event for each affected pair.
   */
  public void deleteDprLedger(UUID projectId, UUID dprId, LocalDate reportDate) {
    List<DailyActivityResourceOutput> existing = repository.findByDprId(dprId);
    if (existing.isEmpty()) return;
    java.util.Set<java.util.Map.Entry<UUID, UUID>> affectedPairs = new java.util.HashSet<>();
    for (DailyActivityResourceOutput row : existing) {
      affectedPairs.add(java.util.Map.entry(row.getActivityId(), row.getResourceId()));
    }
    repository.deleteAll(existing);
    repository.flush();
    for (java.util.Map.Entry<UUID, UUID> pair : affectedPairs) {
      recomputeAssignmentRollup(projectId, pair.getKey(), pair.getValue());
      eventPublisher.publishEvent(new DailyOutputChangedEvent(
          projectId, pair.getKey(), pair.getValue(), reportDate, BigDecimal.ZERO));
    }
  }

  /** Aggregated per-resource units for one DPR — input to {@link #reconcileDprLedger}. */
  public record DprResourceAggregate(
      UUID activityId,
      UUID resourceId,
      BigDecimal qtyExecuted,
      String unit,
      Double hoursWorked,
      String remarks) {}

  /**
   * Cross-schema lookup: {@code activity.activities.work_activity_id → resource.work_activities.default_unit}.
   * Returns null when the activity has no master link or the link doesn't resolve.
   */
  private String resolveUnitFromActivity(UUID activityId) {
    try {
      Object result = em.createNativeQuery(
              "SELECT wa.default_unit FROM activity.activities a "
                  + "JOIN resource.work_activities wa ON wa.id = a.work_activity_id "
                  + "WHERE a.id = :activityId")
          .setParameter("activityId", activityId)
          .getSingleResult();
      return result == null ? null : result.toString();
    } catch (Exception ignored) {
      // Activity not found, no work-activity link, or schema not present in dev profile slices.
      return null;
    }
  }
}
