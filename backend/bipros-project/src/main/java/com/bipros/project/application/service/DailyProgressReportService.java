package com.bipros.project.application.service;

import com.bipros.common.event.DprMutationType;
import com.bipros.common.event.DprSubmittedEvent;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.util.AuditService;
import com.bipros.project.application.dto.CreateDailyProgressReportRequest;
import com.bipros.project.application.dto.DailyProgressReportResponse;
import com.bipros.project.application.dto.DprAttachmentResponse;
import com.bipros.project.application.dto.DprEquipmentRow;
import com.bipros.project.application.dto.DprIssueRow;
import com.bipros.project.application.dto.DprManpowerRow;
import com.bipros.project.application.dto.DprMaterialRow;
import com.bipros.project.application.dto.UpdateDailyProgressReportRequest;
import com.bipros.project.application.util.DprCostFormulas;
import com.bipros.project.domain.model.DailyProgressReport;
import com.bipros.project.domain.model.DprAttachment;
import com.bipros.project.domain.model.DprEquipment;
import com.bipros.project.domain.model.DprIssue;
import com.bipros.project.domain.model.DprManpower;
import com.bipros.project.domain.model.DprMaterial;
import com.bipros.project.domain.model.IssueStatus;
import com.bipros.project.domain.repository.DailyProgressReportRepository;
import com.bipros.project.domain.repository.DprAttachmentRepository;
import com.bipros.project.domain.repository.DprEquipmentRepository;
import com.bipros.project.domain.repository.DprIssueRepository;
import com.bipros.project.domain.repository.DprManpowerRepository;
import com.bipros.project.domain.repository.DprMaterialRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * DPR mutation flow:
 * <ul>
 *   <li>Service writes the row + child collections (manpower / equipment / material), publishes
 *       a {@link DprSubmittedEvent} carrying the change type, old/new BOQ qty + item linkage, and
 *       resource-row counts so analytics knows whether to fetch child rows.</li>
 *   <li>{@code DprBoqSyncListener} (in-module) reacts to update {@code BoqItem.qtyExecutedToDate}
 *       transactionally with the DPR write.</li>
 *   <li>{@code DprSubmittedListener} (analytics) reacts AFTER_COMMIT to write ClickHouse fact rows
 *       across {@code fact_dpr_logs}, {@code fact_dpr_manpower_daily}, {@code fact_dpr_equipment_daily},
 *       and {@code fact_dpr_material_daily}.</li>
 * </ul>
 * Cumulative qty is never stored — list/get computes it on read so back-dated edits stay
 * self-consistent without rewriting later rows.
 *
 * <p>Children are managed with full-replacement semantics: every save deletes-by-dprId and
 * re-inserts the supplied list. Matches the {@code UpdateDailyProgressReportRequest} contract.
 */
@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class DailyProgressReportService {

  private final DailyProgressReportRepository dprRepository;
  private final DprManpowerRepository manpowerRepository;
  private final DprEquipmentRepository equipmentRepository;
  private final DprMaterialRepository materialRepository;
  private final DprAttachmentRepository attachmentRepository;
  private final DprIssueRepository issueRepository;
  private final com.bipros.project.infrastructure.storage.DprAttachmentStorageService attachmentStorage;
  private final ProjectRepository projectRepository;
  private final DailyActivityResourceOutputService ledgerService;
  private final AuditService auditService;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * The DPR write path snapshots rates and validates assignment ↔ activity ↔ kind via tiny
   * native SQL reads against the {@code resource} schema. We can't import bipros-resource here
   * (project module owns its dep graph; see CLAUDE.md) so the existing
   * {@link DailyActivityResourceOutputService#recomputeAssignmentRollup} cross-schema precedent
   * is followed.
   */
  @PersistenceContext
  private EntityManager em;

  public DailyProgressReportResponse create(UUID projectId, CreateDailyProgressReportRequest request) {
    ensureProjectExists(projectId);

    // Reject DPRs against a DRAFT activity — the activity is still being planned, so
    // execution data against it is meaningless. Lock the activity to start accepting DPRs.
    rejectIfActivityDraft(request.activityId());

    // Reject duplicate DPRs for the same (project, day, activity). The ledger
    // (daily_activity_resource_outputs) has a unique key on (project, date, activity, resource);
    // two DPRs that overlap on resources for the same activity on the same day collide on save.
    // The user should edit the existing DPR instead of creating a parallel one.
    if (request.activityId() != null && request.reportDate() != null) {
      dprRepository.findFirstByProjectIdAndReportDateAndActivityId(
              projectId, request.reportDate(), request.activityId())
          .ifPresent(existing -> {
            throw new com.bipros.common.exception.BusinessRuleException(
                "DPR_ALREADY_EXISTS_FOR_ACTIVITY",
                "A DPR for this activity on " + request.reportDate()
                    + " already exists. Edit the existing entry to add or update resources, "
                    + "rather than creating a parallel one.");
          });
    }

    // Work Activity is intentionally NOT required. Some activities (e.g. detailed engineering
    // / design / office work) don't track productivity. The DPR form surfaces a coverage banner
    // so the user knows when productivity won't be measured.

    DailyProgressReport dpr = DailyProgressReport.builder()
        .projectId(projectId)
        .reportDate(request.reportDate())
        .supervisorUserId(request.supervisorUserId())
        .supervisorName(request.supervisorName())
        .chainageFromM(request.chainageFromM())
        .chainageToM(request.chainageToM())
        .activityId(request.activityId())
        .activityName(request.activityName())
        .wbsNodeId(request.wbsNodeId())
        .boqItemNo(request.boqItemNo())
        .unit(request.unit())
        .qtyExecuted(request.qtyExecuted())
        .weatherCondition(request.weatherCondition())
        .remarks(request.remarks())
        .side(request.side())
        .landmark(request.landmark())
        .startTime(request.startTime())
        .endTime(request.endTime())
        .shift(request.shift())
        .approvalStatus(request.approvalStatus())
        .contractorName(request.contractorName())
        .delayReason(request.delayReason())
        .safetyObservation(request.safetyObservation())
        .safetyIncidentType(request.safetyIncidentType())
        .build();

    DailyProgressReport saved = dprRepository.save(dpr);

    List<String> warnings = new ArrayList<>();
    SnapshottedChildren snap = snapshotChildren(saved, request.manpower(), request.equipment(), request.materials(), warnings);
    addUnitMismatchWarning(saved, warnings);

    List<DprManpower> savedManpower = snap.manpower.isEmpty() ? List.of() : manpowerRepository.saveAll(snap.manpower);
    List<DprEquipment> savedEquipment = snap.equipment.isEmpty() ? List.of() : equipmentRepository.saveAll(snap.equipment);
    List<DprMaterial> savedMaterial = snap.material.isEmpty() ? List.of() : materialRepository.saveAll(snap.material);

    reconcileLedger(saved, savedManpower, savedEquipment, savedMaterial);
    // Insert phantom ResourceAssignment rows for any (role, variant) the planner never added.
    // Must run BEFORE rollup so the rollup's UPDATE finds them and writes actuals.
    ensureAssignmentsExist(saved.getActivityId(), saved.getProjectId(), warnings);
    // Compute soft-overrun warnings (no longer throws — supervisor's save is never blocked).
    computeOverrunWarnings(saved.getActivityId(), snap.manpower, snap.equipment, snap.material, warnings);
    rollupRoleAssignmentActuals(saved.getActivityId());

    // Issues on create are all inserts — stamp parent context. Empty / null list means none.
    List<DprIssue> savedIssues = upsertIssues(saved, request.issues(), List.of());

    BigDecimal cumulative = computeCumulative(saved.getProjectId(), saved.getActivityName(), saved.getReportDate());
    DailyProgressReportResponse response = DailyProgressReportResponse.from(
        saved, cumulative,
        savedManpower.stream().map(DprManpowerRow::from).toList(),
        savedEquipment.stream().map(DprEquipmentRow::from).toList(),
        savedMaterial.stream().map(DprMaterialRow::from).toList(),
        List.of(),
        savedIssues.stream().map(DprIssueRow::from).toList(),
        warnings);

    auditService.logCreate("DailyProgressReport", saved.getId(), response);
    eventPublisher.publishEvent(buildEvent(saved, null, null, DprMutationType.CREATED,
        savedManpower, savedEquipment, savedMaterial, savedIssues));
    return response;
  }

  public List<DailyProgressReportResponse> createBulk(UUID projectId, List<CreateDailyProgressReportRequest> requests) {
    // One-at-a-time so the BOQ sync listener fires deterministically per row on bulk seed.
    return requests.stream().map(r -> create(projectId, r)).toList();
  }

  public DailyProgressReportResponse update(UUID projectId, UUID id, UpdateDailyProgressReportRequest request) {
    DailyProgressReport dpr = find(projectId, id);

    // Reject DPR updates against a DRAFT activity (same rule as create). Check the activity the
    // DPR will reference AFTER the update — request.activityId() if it changed the link, else
    // the existing DPR's activityId. Either being DRAFT blocks the write.
    UUID targetActivityId = request.activityId() != null ? request.activityId() : dpr.getActivityId();
    rejectIfActivityDraft(targetActivityId);

    String oldBoqItemNo = dpr.getBoqItemNo();
    BigDecimal oldQty = dpr.getQtyExecuted();
    DailyProgressReportResponse before = DailyProgressReportResponse.from(dpr,
        computeCumulative(dpr.getProjectId(), dpr.getActivityName(), dpr.getReportDate()));

    dpr.setReportDate(request.reportDate());
    dpr.setSupervisorUserId(request.supervisorUserId());
    dpr.setSupervisorName(request.supervisorName());
    dpr.setChainageFromM(request.chainageFromM());
    dpr.setChainageToM(request.chainageToM());
    dpr.setActivityId(request.activityId());
    dpr.setActivityName(request.activityName());
    dpr.setWbsNodeId(request.wbsNodeId());
    dpr.setBoqItemNo(request.boqItemNo());
    dpr.setUnit(request.unit());
    dpr.setQtyExecuted(request.qtyExecuted());
    dpr.setWeatherCondition(request.weatherCondition());
    dpr.setRemarks(request.remarks());
    dpr.setSide(request.side());
    dpr.setLandmark(request.landmark());
    dpr.setStartTime(request.startTime());
    dpr.setEndTime(request.endTime());
    dpr.setShift(request.shift());
    dpr.setApprovalStatus(request.approvalStatus());
    dpr.setContractorName(request.contractorName());
    dpr.setDelayReason(request.delayReason());
    dpr.setSafetyObservation(request.safetyObservation());
    dpr.setSafetyIncidentType(request.safetyIncidentType());

    DailyProgressReport saved = dprRepository.save(dpr);

    // Edit detection: if the supervisor changed nothing in the resource section, skip the entire
    // delete-and-replace + ensureAssignmentsExist + reconcileLedger + rollupRoleAssignmentActuals
    // chain. The activity's Resource Plan therefore won't be touched (no perceived "cumulation"
    // even if the user re-saves the same DPR repeatedly), and we save a few SQL round-trips.
    List<DprManpower> existingManpower = manpowerRepository.findByDprIdOrderByTradeAsc(saved.getId());
    List<DprEquipment> existingEquipment = equipmentRepository.findByDprIdOrderByEquipmentTypeAsc(saved.getId());
    List<DprMaterial> existingMaterial = materialRepository.findByDprIdOrderByMaterialNameAsc(saved.getId());
    boolean resourcesUnchanged = resourceRowsEqual(
        request.manpower(), request.equipment(), request.materials(),
        existingManpower, existingEquipment, existingMaterial);

    List<String> warnings = new ArrayList<>();
    addUnitMismatchWarning(saved, warnings);

    List<DprManpower> savedManpower;
    List<DprEquipment> savedEquipment;
    List<DprMaterial> savedMaterial;
    if (resourcesUnchanged) {
      // Nothing in the resource arrays changed — leave the rows + the activity rollup alone.
      savedManpower = existingManpower;
      savedEquipment = existingEquipment;
      savedMaterial = existingMaterial;
    } else {
      // Replace children: delete then re-insert. Flush between to avoid PK collisions on the
      // unique constraint inside one TX (Hibernate batches the delete with the insert otherwise).
      manpowerRepository.deleteByDprId(saved.getId());
      equipmentRepository.deleteByDprId(saved.getId());
      materialRepository.deleteByDprId(saved.getId());
      manpowerRepository.flush();
      equipmentRepository.flush();
      materialRepository.flush();

      SnapshottedChildren snap = snapshotChildren(saved, request.manpower(), request.equipment(), request.materials(), warnings);

      savedManpower = snap.manpower.isEmpty() ? List.of() : manpowerRepository.saveAll(snap.manpower);
      savedEquipment = snap.equipment.isEmpty() ? List.of() : equipmentRepository.saveAll(snap.equipment);
      savedMaterial = snap.material.isEmpty() ? List.of() : materialRepository.saveAll(snap.material);

      reconcileLedger(saved, savedManpower, savedEquipment, savedMaterial);
      ensureAssignmentsExist(saved.getActivityId(), saved.getProjectId(), warnings);
      computeOverrunWarnings(saved.getActivityId(), snap.manpower, snap.equipment, snap.material, warnings);
      rollupRoleAssignmentActuals(saved.getActivityId());
    }

    // Issues use merge-by-id (diverges from full-replace) so lifecycle (status, resolvedAt,
    // opened_at, version) survives a DPR re-save. See DprIssue javadoc.
    List<DprIssue> existingIssues = issueRepository.findByDprIdOrderByOpenedAtAsc(saved.getId());
    List<DprIssue> savedIssues = upsertIssues(saved, request.issues(), existingIssues);

    BigDecimal cumulative = computeCumulative(saved.getProjectId(), saved.getActivityName(), saved.getReportDate());
    List<DprAttachmentResponse> attachments = attachmentRepository.findByDprIdOrderByCreatedAtAsc(saved.getId())
        .stream().map(DprAttachmentResponse::from).toList();
    DailyProgressReportResponse after = DailyProgressReportResponse.from(
        saved, cumulative,
        savedManpower.stream().map(DprManpowerRow::from).toList(),
        savedEquipment.stream().map(DprEquipmentRow::from).toList(),
        savedMaterial.stream().map(DprMaterialRow::from).toList(),
        attachments,
        savedIssues.stream().map(DprIssueRow::from).toList(),
        warnings);

    auditService.logUpdate("DailyProgressReport", saved.getId(), "row", before, after);
    eventPublisher.publishEvent(buildEvent(saved, oldBoqItemNo, oldQty, DprMutationType.UPDATED,
        savedManpower, savedEquipment, savedMaterial, savedIssues));
    return after;
  }

  @Transactional(readOnly = true)
  public List<DailyProgressReportResponse> list(UUID projectId, LocalDate from, LocalDate to, String activityName) {
    ensureProjectExists(projectId);
    List<DailyProgressReport> rows;
    if (activityName != null && !activityName.isBlank()) {
      rows = dprRepository.findByProjectIdAndActivityNameIgnoreCaseOrderByReportDateAsc(projectId, activityName);
    } else if (from != null && to != null) {
      rows = dprRepository.findByProjectIdAndReportDateBetweenOrderByReportDateAscIdAsc(projectId, from, to);
    } else {
      rows = dprRepository.findByProjectIdOrderByReportDateAscIdAsc(projectId);
    }
    return attachComputedCumulativeAndChildren(rows);
  }

  @Transactional(readOnly = true)
  public DailyProgressReportResponse get(UUID projectId, UUID id) {
    DailyProgressReport dpr = find(projectId, id);
    BigDecimal cumulative = computeCumulative(dpr.getProjectId(), dpr.getActivityName(), dpr.getReportDate());
    return DailyProgressReportResponse.from(
        dpr, cumulative,
        manpowerRepository.findByDprIdOrderByTradeAsc(id).stream().map(DprManpowerRow::from).toList(),
        equipmentRepository.findByDprIdOrderByEquipmentTypeAsc(id).stream().map(DprEquipmentRow::from).toList(),
        materialRepository.findByDprIdOrderByMaterialNameAsc(id).stream().map(DprMaterialRow::from).toList(),
        attachmentRepository.findByDprIdOrderByCreatedAtAsc(id).stream().map(DprAttachmentResponse::from).toList(),
        issueRepository.findByDprIdOrderByOpenedAtAsc(id).stream().map(DprIssueRow::from).toList()
    );
  }

  public void delete(UUID projectId, UUID id) {
    DailyProgressReport dpr = find(projectId, id);
    String oldBoqItemNo = dpr.getBoqItemNo();
    BigDecimal oldQty = dpr.getQtyExecuted();
    UUID dprId = dpr.getId();
    LocalDate reportDate = dpr.getReportDate();
    String activityName = dpr.getActivityName();

    // Tear down ledger contributions BEFORE deleting children so the rollup SUM excludes the
    // child rows (the ledger holds aggregates, not child references).
    ledgerService.deleteDprLedger(projectId, dprId, reportDate);

    manpowerRepository.deleteByDprId(dprId);
    equipmentRepository.deleteByDprId(dprId);
    materialRepository.deleteByDprId(dprId);
    issueRepository.deleteByDprId(dprId);
    // Photos: collect paths before the DB rows are removed, then drop binaries best-effort.
    List<DprAttachment> attachments = attachmentRepository.findByDprIdOrderByCreatedAtAsc(dprId);
    attachmentRepository.deleteByDprId(dprId);
    for (DprAttachment a : attachments) {
      attachmentStorage.deleteQuietly(a.getStoragePath());
    }
    dprRepository.delete(dpr);
    auditService.logDelete("DailyProgressReport", id);

    eventPublisher.publishEvent(DprSubmittedEvent.withoutChildren(
        projectId, dprId, reportDate, activityName, null, null, oldBoqItemNo, oldQty,
        DprMutationType.DELETED));
  }

  /**
   * Sum of qtyExecuted for the (project, activityName) up to and including the given date.
   * Equivalent to the legacy stored {@code cumulativeQty} but always fresh.
   */
  private BigDecimal computeCumulative(UUID projectId, String activityName, LocalDate reportDate) {
    BigDecimal sum = dprRepository.sumQtyExecutedThroughDate(projectId, activityName, reportDate);
    return sum != null ? sum : BigDecimal.ZERO;
  }

  /**
   * Walks rows in date order per (project, activityName), accumulating cumulative locally
   * to avoid an N+1 query, and batches one child fetch per child table for the whole window.
   */
  private List<DailyProgressReportResponse> attachComputedCumulativeAndChildren(List<DailyProgressReport> rows) {
    if (rows.isEmpty()) return List.of();

    List<UUID> ids = rows.stream().map(DailyProgressReport::getId).toList();
    Map<UUID, List<DprManpowerRow>> manpowerByDpr = manpowerRepository.findByDprIdIn(ids).stream()
        .collect(Collectors.groupingBy(DprManpower::getDprId,
            Collectors.mapping(DprManpowerRow::from, Collectors.toList())));
    Map<UUID, List<DprEquipmentRow>> equipmentByDpr = equipmentRepository.findByDprIdIn(ids).stream()
        .collect(Collectors.groupingBy(DprEquipment::getDprId,
            Collectors.mapping(DprEquipmentRow::from, Collectors.toList())));
    Map<UUID, List<DprMaterialRow>> materialByDpr = materialRepository.findByDprIdIn(ids).stream()
        .collect(Collectors.groupingBy(DprMaterial::getDprId,
            Collectors.mapping(DprMaterialRow::from, Collectors.toList())));
    Map<UUID, List<DprAttachmentResponse>> attachmentsByDpr = attachmentRepository.findByDprIdIn(ids).stream()
        .collect(Collectors.groupingBy(DprAttachment::getDprId,
            Collectors.mapping(DprAttachmentResponse::from, Collectors.toList())));
    Map<UUID, List<DprIssueRow>> issuesByDpr = issueRepository.findByDprIdIn(ids).stream()
        .collect(Collectors.groupingBy(DprIssue::getDprId,
            Collectors.mapping(DprIssueRow::from, Collectors.toList())));

    Map<String, BigDecimal> running = new HashMap<>();
    List<DailyProgressReportResponse> out = new ArrayList<>(rows.size());
    rows.stream()
        .sorted((a, b) -> {
          int byDate = a.getReportDate().compareTo(b.getReportDate());
          if (byDate != 0) return byDate;
          return a.getId().compareTo(b.getId());
        })
        .forEach(r -> {
          String key = r.getProjectId() + "::" + (r.getActivityName() == null ? "" : r.getActivityName().toLowerCase());
          BigDecimal cumulative = running.getOrDefault(key, BigDecimal.ZERO)
              .add(r.getQtyExecuted() != null ? r.getQtyExecuted() : BigDecimal.ZERO);
          running.put(key, cumulative);
          out.add(DailyProgressReportResponse.from(r, cumulative,
              manpowerByDpr.getOrDefault(r.getId(), List.of()),
              equipmentByDpr.getOrDefault(r.getId(), List.of()),
              materialByDpr.getOrDefault(r.getId(), List.of()),
              attachmentsByDpr.getOrDefault(r.getId(), List.of()),
              issuesByDpr.getOrDefault(r.getId(), List.of())));
        });
    return out;
  }

  /**
   * Supervisors of activities that have at least one DPR in the date window. Powers the
   * Capacity Utilization / Supervisor Performance dropdown.
   *
   * <p>Multi-supervisor (commit 182141eb): the source is now
   * {@code activity.activity_supervisors} — a user appears in the dropdown when they
   * supervise any activity that has a DPR in range, regardless of whether they personally
   * filed any of those DPRs. The {@code dprCount} field reads as "DPRs under activities
   * they supervise in range" to match the new filter semantics on the report side.
   */
  @Transactional(readOnly = true)
  public List<com.bipros.project.application.dto.SupervisorOption> listSupervisorsUsed(
      UUID projectId, LocalDate fromDate, LocalDate toDate) {
    ensureProjectExists(projectId);

    @SuppressWarnings("unchecked")
    List<Object[]> raw = em.createNativeQuery(
            "SELECT s.user_id, "
                + "       COALESCE(u.username, '')                                              AS supervisor_code, "
                + "       COALESCE( "
                + "         NULLIF(TRIM(CONCAT_WS(' ', u.first_name, u.last_name)), ''), "
                + "         u.username, "
                + "         MAX(s.user_name_snapshot)) "
                + "                                                                             AS supervisor_name, "
                + "       COUNT(DISTINCT d.id)                                                  AS dpr_count "
                + "FROM activity.activity_supervisors s "
                + "JOIN activity.activities a            ON a.id = s.activity_id "
                + "JOIN project.daily_progress_reports d ON d.activity_id = a.id "
                + "LEFT JOIN public.users u              ON u.id = s.user_id "
                + "WHERE a.project_id = :projectId "
                + "  AND (CAST(:fromDate AS date) IS NULL OR d.report_date >= CAST(:fromDate AS date)) "
                + "  AND (CAST(:toDate   AS date) IS NULL OR d.report_date <= CAST(:toDate   AS date)) "
                + "GROUP BY s.user_id, u.username, u.first_name, u.last_name "
                + "ORDER BY dpr_count DESC, supervisor_name")
        .setParameter("projectId", projectId)
        .setParameter("fromDate", fromDate)
        .setParameter("toDate", toDate)
        .getResultList();

    List<com.bipros.project.application.dto.SupervisorOption> out = new ArrayList<>(raw.size());
    for (Object[] r : raw) {
      out.add(new com.bipros.project.application.dto.SupervisorOption(
          (UUID) r[0], (String) r[1], (String) r[2], ((Number) r[3]).longValue()));
    }
    return out;
  }

  private DailyProgressReport find(UUID projectId, UUID id) {
    DailyProgressReport dpr = dprRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("DailyProgressReport", id));
    if (!dpr.getProjectId().equals(projectId)) {
      throw new ResourceNotFoundException("DailyProgressReport", id);
    }
    return dpr;
  }

  private void ensureProjectExists(UUID projectId) {
    if (!projectRepository.existsById(projectId)) {
      throw new ResourceNotFoundException("Project", projectId);
    }
  }

  /** Generic helper: persist a list of incoming rows under one parent id, returning saved entities. */
  private <ROW, ENTITY> List<ENTITY> saveChildren(
      UUID parentId,
      List<ROW> rows,
      org.springframework.data.jpa.repository.JpaRepository<ENTITY, UUID> repo,
      java.util.function.BiFunction<ROW, UUID, ENTITY> toEntity) {
    if (rows == null || rows.isEmpty()) return List.of();
    List<ENTITY> entities = rows.stream().map(r -> toEntity.apply(r, parentId)).toList();
    return repo.saveAll(entities);
  }

  private DprSubmittedEvent buildEvent(
      DailyProgressReport saved,
      String oldBoqItemNo,
      BigDecimal oldQty,
      DprMutationType type,
      Collection<DprManpower> manpower,
      Collection<DprEquipment> equipment,
      Collection<DprMaterial> material,
      Collection<DprIssue> issues) {
    BigDecimal totalManpowerHours = manpower.stream()
        .map(m -> add(m.getWorkingHours(), m.getOtHours()))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalEquipmentHours = equipment.stream()
        .map(DprEquipment::getWorkingHours)
        .filter(java.util.Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalFuelLitres = equipment.stream()
        .map(DprEquipment::getFuelLitres)
        .filter(java.util.Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    return new DprSubmittedEvent(
        saved.getProjectId(),
        saved.getId(),
        saved.getReportDate(),
        saved.getActivityName(),
        saved.getBoqItemNo(),
        saved.getQtyExecuted(),
        oldBoqItemNo,
        oldQty,
        type,
        manpower.size(),
        equipment.size(),
        material.size(),
        issues.size(),
        totalManpowerHours,
        totalEquipmentHours,
        totalFuelLitres,
        saved.getSupervisorUserId());
  }

  private static BigDecimal add(BigDecimal a, BigDecimal b) {
    BigDecimal aa = a != null ? a : BigDecimal.ZERO;
    BigDecimal bb = b != null ? b : BigDecimal.ZERO;
    return aa.add(bb);
  }

  // ─── Issue merge-by-id (deliberate divergence from full-replace) ──────────────────

  /**
   * Merge incoming issue rows against existing rows for the same DPR.
   * <ul>
   *   <li>Rows in {@code existing} whose id is absent from {@code incoming} are deleted.</li>
   *   <li>Rows with a known id are updated in place — preserving {@code openedAt}, audit
   *       fields, and version; auto-managing {@code resolvedAt} on status transitions.</li>
   *   <li>Rows without an id are inserted with snapshots stamped from the parent DPR.</li>
   *   <li>Rows whose id is present but doesn't match any existing row for this DPR are
   *       rejected with a 409 (id from another DPR or stale optimistic).</li>
   * </ul>
   * {@code incoming == null} or empty means "clear all issues for this DPR".
   */
  private List<DprIssue> upsertIssues(
      DailyProgressReport parent,
      List<DprIssueRow> incoming,
      List<DprIssue> existing) {
    if (incoming == null) incoming = List.of();
    Map<UUID, DprIssue> existingById = existing.stream()
        .collect(Collectors.toMap(DprIssue::getId, e -> e));
    Set<UUID> incomingIds = incoming.stream()
        .map(DprIssueRow::id)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());

    // 1) Delete rows in DB that aren't in the payload.
    List<DprIssue> toDelete = existing.stream()
        .filter(e -> !incomingIds.contains(e.getId()))
        .toList();
    if (!toDelete.isEmpty()) {
      issueRepository.deleteAllByIdInBatch(toDelete.stream().map(DprIssue::getId).toList());
      issueRepository.flush();
    }

    // 2) Walk payload — update existing, insert new.
    List<DprIssue> toSave = new ArrayList<>(incoming.size());
    Instant now = Instant.now();
    for (DprIssueRow row : incoming) {
      if (row.id() != null) {
        DprIssue current = existingById.get(row.id());
        if (current == null) {
          throw new BusinessRuleException(
              "DPR_ISSUE_NOT_FOUND",
              "Issue " + row.id() + " does not belong to DPR " + parent.getId()
                  + " — refresh and try again.");
        }
        applyEditableFields(current, row, now);
        toSave.add(current);
      } else {
        toSave.add(stampNewIssue(parent, row, now));
      }
    }
    return toSave.isEmpty() ? List.of() : issueRepository.saveAll(toSave);
  }

  /** Apply editable fields from {@code row} onto {@code target}, auto-managing resolvedAt. */
  private static void applyEditableFields(DprIssue target, DprIssueRow row, Instant now) {
    IssueStatus oldStatus = target.getStatus();
    target.setTitle(row.title());
    target.setDescription(row.description());
    target.setCategory(row.category());
    target.setSeverity(row.severity());
    target.setStatus(row.status());
    // RBAC Phase 4.2: canonical identity is the User id. Resource id is no longer written.
    target.setSupervisorUserId(row.supervisorUserId());
    if (row.supervisorName() != null) target.setSupervisorName(row.supervisorName());
    target.setAssignedToUserId(row.assignedToUserId());
    if (row.assignedToName() != null) target.setAssignedToName(row.assignedToName());
    target.setResolutionNotes(row.resolutionNotes());
    boolean wasTerminal = oldStatus != null && oldStatus.resolvedAtTerminal();
    boolean isTerminal = row.status() != null && row.status().resolvedAtTerminal();
    if (!wasTerminal && isTerminal) {
      target.setResolvedAt(now);
    } else if (wasTerminal && !isTerminal) {
      target.setResolvedAt(null);
    }
  }

  /** Stamp a brand-new issue with snapshots from the parent DPR. */
  private static DprIssue stampNewIssue(DailyProgressReport parent, DprIssueRow row, Instant now) {
    // RBAC Phase 4.2: stamp the canonical User id from row or fall back to the parent DPR's
    // supervisor User. Resource id is no longer stamped on new rows.
    UUID supervisorUserId = row.supervisorUserId() != null
        ? row.supervisorUserId()
        : parent.getSupervisorUserId();
    UUID assigneeUserId = row.assignedToUserId() != null
        ? row.assignedToUserId()
        : (row.supervisorUserId() != null ? row.supervisorUserId() : parent.getSupervisorUserId());
    String assigneeName = row.assignedToName() != null
        ? row.assignedToName()
        : (row.supervisorName() != null ? row.supervisorName() : parent.getSupervisorName());
    IssueStatus status = row.status() != null ? row.status() : IssueStatus.OPEN;
    DprIssue issue = DprIssue.builder()
        .dprId(parent.getId())
        .projectId(parent.getProjectId())
        .activityId(parent.getActivityId())
        .activityName(parent.getActivityName())
        .supervisorUserId(supervisorUserId)
        .supervisorName(row.supervisorName() != null ? row.supervisorName() : parent.getSupervisorName())
        .assignedToUserId(assigneeUserId)
        .assignedToName(assigneeName)
        .reportDate(parent.getReportDate())
        .chainageFromM(parent.getChainageFromM())
        .chainageToM(parent.getChainageToM())
        .category(row.category())
        .severity(row.severity())
        .status(status)
        .title(row.title())
        .description(row.description())
        .openedAt(now)
        .resolvedAt(status.resolvedAtTerminal() ? now : null)
        .resolutionNotes(row.resolutionNotes())
        .build();
    return issue;
  }

  // ─── Resource snapshot + ledger reconcile (DPR write path) ────────────────────────

  /** Snapshot bag returned by {@link #snapshotChildren} — entities ready to persist. */
  private record SnapshottedChildren(List<DprManpower> manpower, List<DprEquipment> equipment, List<DprMaterial> material) {}

  /**
   * Resolve unit-rate snapshot per row, validate the assignment ↔ activity ↔ kind invariant,
   * and compute {@code line_cost} via {@link DprCostFormulas}. Rejects rows whose assignment
   * doesn't match the DPR's activity or whose resource type is wrong for the row kind. Missing
   * rates produce a warning but never reject.
   */
  private SnapshottedChildren snapshotChildren(
      DailyProgressReport saved,
      List<DprManpowerRow> manpowerRows,
      List<DprEquipmentRow> equipmentRows,
      List<DprMaterialRow> materialRows,
      List<String> warnings) {

    LocalDate reportDate = saved.getReportDate();
    UUID activityId = saved.getActivityId();
    UUID dprId = saved.getId();
    boolean canValidate = activityId != null;

    List<DprManpower> manpower = new ArrayList<>();
    if (manpowerRows != null) {
      for (DprManpowerRow row : manpowerRows) {
        AssignmentSnapshot snap = lookupAssignmentSnapshot(row.resourceAssignmentId(), reportDate);
        if (canValidate) requireKind(row.resourceAssignmentId(), snap, "MANPOWER", activityId);
        BigDecimal unitRate = pickUnitRate(row.unitRate(), snap);
        String basis = pickBasis(row.unitRateBasis(), snap);
        // Legacy "rate-missing:" warning removed: in the role-only model the rate flows from
        // manpower_role_rates via RoleRateResolver/ensureAssignmentsExist, which emits its own
        // MISSING_RATE: ... warning only when truly absent. The legacy resource_id chain that
        // populated `snap.unitRate` is null for role-only rows and produced false positives.
        DprManpower entity = row.toEntity(dprId);
        entity.setResourceId(pickResourceId(row.resourceId(), snap));
        entity.setUnitRate(unitRate);
        entity.setUnitRateBasis(basis);
        entity.setLineCost(DprCostFormulas.manpowerLineCost(entity, unitRate, basis));
        manpower.add(entity);
      }
    }

    List<DprEquipment> equipment = new ArrayList<>();
    if (equipmentRows != null) {
      for (DprEquipmentRow row : equipmentRows) {
        AssignmentSnapshot snap = lookupAssignmentSnapshot(row.resourceAssignmentId(), reportDate);
        if (canValidate) requireKind(row.resourceAssignmentId(), snap, "EQUIPMENT", activityId);
        BigDecimal unitRate = pickUnitRate(row.unitRate(), snap);
        // Equipment defaults to HOUR — most equipment is hourly-billed. The snapshot's basis
        // (derived from resource.unit) is intentionally NOT used here: resource.unit is often
        // a productivity unit like "PER_DAY" that doesn't match the actual rate basis. Clients
        // can override by sending unitRateBasis explicitly when day-billing equipment.
        String basis = row.unitRateBasis() != null && !row.unitRateBasis().isBlank()
            ? row.unitRateBasis()
            : "HOUR";
        DprEquipment entity = row.toEntity(dprId);
        entity.setResourceId(pickResourceId(row.resourceId(), snap));
        entity.setUnitRate(unitRate);
        entity.setUnitRateBasis(basis);
        entity.setLineCost(DprCostFormulas.equipmentLineCost(entity, unitRate, basis));
        equipment.add(entity);
      }
    }

    List<DprMaterial> material = new ArrayList<>();
    if (materialRows != null) {
      for (DprMaterialRow row : materialRows) {
        AssignmentSnapshot snap = lookupAssignmentSnapshot(row.resourceAssignmentId(), reportDate);
        if (canValidate) requireKind(row.resourceAssignmentId(), snap, "MATERIAL", activityId);
        BigDecimal unitRate = pickUnitRate(row.unitRate(), snap);
        DprMaterial entity = row.toEntity(dprId);
        entity.setResourceId(pickResourceId(row.resourceId(), snap));
        entity.setUnitRate(unitRate);
        entity.setLineCost(DprCostFormulas.materialLineCost(entity, unitRate));
        material.add(entity);
      }
    }

    return new SnapshottedChildren(manpower, equipment, material);
  }

  /**
   * One row from a tiny native join across {@code resource.resource_assignments → resources →
   * resource_types}. Returned by {@link #lookupAssignmentSnapshot}; carries everything the
   * snapshotter needs without an explicit Maven dep on {@code bipros-resource}.
   */
  private record AssignmentSnapshot(UUID activityId, UUID resourceId, String resourceName,
                                    String resourceTypeCode, String unit, BigDecimal unitRate,
                                    String basis) {}

  @SuppressWarnings("unchecked")
  private AssignmentSnapshot lookupAssignmentSnapshot(UUID assignmentId, LocalDate reportDate) {
    if (assignmentId == null) return null;
    if (em == null) return null; // unit-test fallback — Spring would normally inject this
    LocalDate effectiveOn = reportDate != null ? reportDate : LocalDate.now();
    List<Object[]> rows = em.createNativeQuery(
            "SELECT ra.activity_id, ra.resource_id, ra.rate_type, "
                + "       r.name, r.unit, r.cost_per_unit, "
                + "       rt.code "
                + "FROM resource.resource_assignments ra "
                + "LEFT JOIN resource.resources r ON r.id = ra.resource_id "
                + "LEFT JOIN resource.resource_types rt ON rt.id = r.resource_type_id "
                + "WHERE ra.id = :assignmentId")
        .setParameter("assignmentId", assignmentId)
        .getResultList();
    if (rows.isEmpty()) return null;
    Object[] row = rows.get(0);
    UUID activityId = (UUID) row[0];
    UUID resourceId = (UUID) row[1];
    String rateType = (String) row[2];
    String resourceName = (String) row[3];
    String unit = (String) row[4];
    BigDecimal costPerUnit = row[5] == null ? null : (BigDecimal) row[5];
    String typeCode = (String) row[6];

    BigDecimal effectiveRate = resolveEffectiveRate(resourceId, rateType, effectiveOn);
    if (effectiveRate == null) effectiveRate = costPerUnit;

    return new AssignmentSnapshot(activityId, resourceId, resourceName, typeCode, unit,
        effectiveRate, deriveBasis(unit));
  }

  /** Finds the latest {@code resource_rates} row covering {@code reportDate} for the resource. */
  private BigDecimal resolveEffectiveRate(UUID resourceId, String rateType, LocalDate reportDate) {
    if (resourceId == null) return null;
    String sql = "SELECT price_per_unit FROM resource.resource_rates "
        + "WHERE resource_id = :resourceId "
        + "  AND effective_date <= :reportDate "
        + "  AND (effective_to IS NULL OR effective_to >= :reportDate) "
        + (rateType != null && !rateType.isBlank() ? "  AND rate_type = :rateType " : "")
        + "ORDER BY effective_date DESC LIMIT 1";
    var query = em.createNativeQuery(sql)
        .setParameter("resourceId", resourceId)
        .setParameter("reportDate", reportDate);
    if (rateType != null && !rateType.isBlank()) {
      query.setParameter("rateType", rateType);
    }
    @SuppressWarnings("unchecked")
    List<Object> rows = query.getResultList();
    if (rows.isEmpty() || rows.get(0) == null) return null;
    return (BigDecimal) rows.get(0);
  }

  private static String deriveBasis(String unit) {
    if (unit == null) return "DAY";
    String u = unit.trim().toUpperCase();
    if (u.contains("HOUR") || u.equals("HR")) return "HOUR";
    if (u.contains("DAY") || u.contains("SHIFT")) return "DAY";
    if (u.contains("MIN")) return "HOUR";
    return "EACH";
  }

  private static BigDecimal pickUnitRate(BigDecimal clientSent, AssignmentSnapshot snap) {
    if (clientSent != null) return clientSent;
    return snap == null ? null : snap.unitRate();
  }

  private static String pickBasis(String clientSent, AssignmentSnapshot snap) {
    if (clientSent != null && !clientSent.isBlank()) return clientSent;
    return snap == null ? "DAY" : snap.basis();
  }

  private static UUID pickResourceId(UUID clientSent, AssignmentSnapshot snap) {
    if (clientSent != null) return clientSent;
    return snap == null ? null : snap.resourceId();
  }

  /**
   * Validate the assignment ↔ activity ↔ kind invariant when the lookup succeeded. When
   * {@code snap} is null we silently return — that's the normal path for role-only DPR rows
   * (no resource_id, no ResourceAssignment lookup), and unit tests with a mocked
   * {@link EntityManager} return nothing here too.
   */
  private void requireKind(UUID assignmentId, AssignmentSnapshot snap, String requiredKind,
                           UUID expectedActivityId) {
    if (snap == null) return;
    if (expectedActivityId != null && !expectedActivityId.equals(snap.activityId())) {
      throw new BusinessRuleException("INVALID_DPR_RESOURCE",
          "Resource assignment does not belong to this activity: assignmentId=" + assignmentId);
    }
    String typeCode = snap.resourceTypeCode();
    boolean ok = switch (requiredKind) {
      case "MANPOWER" -> "LABOR".equalsIgnoreCase(typeCode) || "MANPOWER".equalsIgnoreCase(typeCode);
      case "EQUIPMENT" -> "EQUIPMENT".equalsIgnoreCase(typeCode);
      case "MATERIAL" -> "MATERIAL".equalsIgnoreCase(typeCode);
      default -> true;
    };
    if (!ok) {
      throw new BusinessRuleException("INVALID_DPR_RESOURCE_KIND",
          "Resource assignment kind " + typeCode + " does not match row kind " + requiredKind);
    }
  }

  /**
   * Soft check: if the DPR's {@code unit} doesn't match the picked activity's
   * {@code WorkActivity.default_unit}, append a {@code unit-mismatch:expected=…:actual=…}
   * warning to the response. Doesn't throw — frontend renders a banner so the user can decide
   * whether to fix the DPR or accept the override. Comparing two units lexically would be naive,
   * so we trim + uppercase to ignore whitespace / case differences but otherwise leave the
   * codes alone (the unit master is plain strings, no synonyms).
   */
  private void addUnitMismatchWarning(DailyProgressReport saved, List<String> warnings) {
    if (saved == null || warnings == null) return;
    if (saved.getActivityId() == null || saved.getUnit() == null) return;
    String activityUnit = resolveActivityDefaultUnit(saved.getActivityId());
    if (activityUnit == null || activityUnit.isBlank()) return;
    String dprUnit = saved.getUnit().trim();
    if (!activityUnit.trim().equalsIgnoreCase(dprUnit)) {
      warnings.add("unit-mismatch:expected=" + activityUnit.trim() + ":actual=" + dprUnit);
    }
  }

  /**
   * Cross-schema lookup against {@code activity.activities.edit_status}: rejects the DPR write
   * when the activity is still {@code DRAFT}. We can't import {@code bipros-activity} here —
   * {@code bipros-activity} already depends on {@code bipros-project}, so a return dep would
   * cycle (see CLAUDE.md). The native-SQL approach mirrors the existing cross-schema reads in
   * this service ({@code lookupAssignmentSnapshot}, {@code resolveActivityDefaultUnit}).
   *
   * <p>Throws {@code ACTIVITY_NOT_FOUND} if the id doesn't resolve and
   * {@code ACTIVITY_DRAFT_DPR_REJECTED} if it does but is still DRAFT.
   */
  private void rejectIfActivityDraft(UUID activityId) {
    if (activityId == null || em == null) return;
    @SuppressWarnings("unchecked")
    List<Object[]> rows = em.createNativeQuery(
            "SELECT a.edit_status, a.code "
                + "FROM activity.activities a "
                + "WHERE a.id = :activityId")
        .setParameter("activityId", activityId)
        .getResultList();
    if (rows.isEmpty()) {
      throw new BusinessRuleException(
          "ACTIVITY_NOT_FOUND",
          "Activity " + activityId + " not found.");
    }
    Object[] row = rows.get(0);
    String editStatus = row[0] == null ? null : row[0].toString();
    String code = row[1] == null ? activityId.toString() : row[1].toString();
    if ("DRAFT".equalsIgnoreCase(editStatus)) {
      throw new BusinessRuleException(
          "ACTIVITY_DRAFT_DPR_REJECTED",
          "Cannot submit DPR against activity '" + code
              + "' — it is still in Draft. Lock the activity to start accepting DPRs.");
    }
  }

  /**
   * Cross-schema lookup: {@code activity.activities → resource.work_activities.default_unit}.
   * Mirrors the precedent in {@code DailyActivityResourceOutputService.resolveUnitFromActivity}.
   * Returns null when the activity has no master link or the link doesn't resolve.
   */
  private String resolveActivityDefaultUnit(UUID activityId) {
    if (activityId == null || em == null) return null;
    try {
      Object result = em.createNativeQuery(
              "SELECT wa.default_unit FROM activity.activities a "
                  + "JOIN resource.work_activities wa ON wa.id = a.work_activity_id "
                  + "WHERE a.id = :activityId")
          .setParameter("activityId", activityId)
          .getSingleResult();
      return result == null ? null : result.toString();
    } catch (Exception ignored) {
      return null;
    }
  }

  /**
   * Aggregate child-row units per (activity, resource) and hand them to the ledger service.
   * Only rows with an {@code activityId} on the parent and a {@code resourceId} on the row
   * contribute — others are silently skipped (warnings already emitted by the snapshotter).
   */
  private void reconcileLedger(DailyProgressReport saved,
                               List<DprManpower> manpower,
                               List<DprEquipment> equipment,
                               List<DprMaterial> material) {
    UUID activityId = saved.getActivityId();
    if (activityId == null) {
      // Without activityId we can't write ledger rows. Reconcile only deletes existing rows for
      // this DPR (in case activityId was cleared on update).
      ledgerService.reconcileDprLedger(saved.getProjectId(), saved.getId(), saved.getReportDate(), List.of());
      return;
    }

    Map<UUID, BigDecimal> unitsByResource = new HashMap<>();
    Map<UUID, Double> hoursByResource = new HashMap<>();
    Map<UUID, String> unitByResource = new HashMap<>();

    for (DprManpower row : manpower) {
      if (row.getResourceId() == null) continue;
      String basis = row.getUnitRateBasis() != null ? row.getUnitRateBasis() : "DAY";
      BigDecimal units = DprCostFormulas.manpowerUnits(row, basis);
      unitsByResource.merge(row.getResourceId(), units, BigDecimal::add);
      double hrs = (row.getWorkingHours() == null ? 0d : row.getWorkingHours().doubleValue())
          + (row.getOtHours() == null ? 0d : row.getOtHours().doubleValue());
      hoursByResource.merge(row.getResourceId(), hrs * (row.getNos() == null ? 1 : row.getNos()), Double::sum);
      unitByResource.putIfAbsent(row.getResourceId(), basis.equalsIgnoreCase("HOUR") ? "HR" : "DAY");
    }
    for (DprEquipment row : equipment) {
      if (row.getResourceId() == null) continue;
      String basis = row.getUnitRateBasis() != null ? row.getUnitRateBasis() : "HOUR";
      BigDecimal units = DprCostFormulas.equipmentUnits(row, basis);
      unitsByResource.merge(row.getResourceId(), units, BigDecimal::add);
      double hrs = (row.getWorkingHours() == null ? 0d : row.getWorkingHours().doubleValue())
          * (row.getNos() == null ? 1 : row.getNos());
      hoursByResource.merge(row.getResourceId(), hrs, Double::sum);
      unitByResource.putIfAbsent(row.getResourceId(), basis.equalsIgnoreCase("HOUR") ? "HR" : "DAY");
    }
    for (DprMaterial row : material) {
      if (row.getResourceId() == null) continue;
      BigDecimal units = DprCostFormulas.materialUnits(row);
      unitsByResource.merge(row.getResourceId(), units, BigDecimal::add);
      unitByResource.putIfAbsent(row.getResourceId(), row.getUnit() != null ? row.getUnit() : "EA");
    }

    List<DailyActivityResourceOutputService.DprResourceAggregate> aggregates = unitsByResource.entrySet().stream()
        .sorted(Comparator.comparing(e -> e.getKey().toString()))
        .map(e -> new DailyActivityResourceOutputService.DprResourceAggregate(
            activityId,
            e.getKey(),
            e.getValue(),
            unitByResource.get(e.getKey()),
            hoursByResource.get(e.getKey()),
            null))
        .toList();

    ledgerService.reconcileDprLedger(saved.getProjectId(), saved.getId(), saved.getReportDate(), aggregates);
  }

  // ===========================================================================================
  // Overrun guard — blocks save if cumulative actual + this DPR's contribution would push past
  // planned units for any (role, variant) on this activity. Cross-module by native SQL since
  // bipros-project does not depend on bipros-resource.
  // ===========================================================================================

  /** Aggregated contribution from this DPR's children, keyed by (roleId, variantId). */
  private record OverrunKey(UUID roleId, UUID variantId) {}

  private static class OverrunAgg {
    BigDecimal added = BigDecimal.ZERO;
    String typeCode;
  }

  /**
   * Soft-overrun check: when the cumulative {@code current_actual + this DPR's contribution}
   * exceeds the planned units for a {@code (role, variant)} on this activity, append a warning
   * string to {@code warnings}. Never throws — the supervisor can always save. Unplanned
   * variants are NOT warned about here (they're surfaced visually via the "Unplanned" pill in
   * the activity Resource Plan once {@link #ensureAssignmentsExist} creates the phantom row).
   */
  private void computeOverrunWarnings(
      UUID activityId,
      List<DprManpower> manpower,
      List<DprEquipment> equipment,
      List<DprMaterial> material,
      List<String> warnings) {
    if (activityId == null || em == null) return;

    Map<OverrunKey, OverrunAgg> agg = new HashMap<>();
    for (DprManpower r : manpower) {
      if (r.getRoleId() == null || r.getManpowerRoleRateId() == null || r.getNos() == null) continue;
      OverrunKey k = new OverrunKey(r.getRoleId(), r.getManpowerRoleRateId());
      OverrunAgg a = agg.computeIfAbsent(k, x -> new OverrunAgg());
      a.added = a.added.add(BigDecimal.valueOf(r.getNos()));
      a.typeCode = "MANPOWER";
    }
    for (DprEquipment r : equipment) {
      if (r.getRoleId() == null || r.getEquipmentRoleVariantId() == null || r.getNos() == null) continue;
      OverrunKey k = new OverrunKey(r.getRoleId(), r.getEquipmentRoleVariantId());
      OverrunAgg a = agg.computeIfAbsent(k, x -> new OverrunAgg());
      // Role-only model: equipment plan is "how many units" (nos). Working hours is
      // informational for the DPR row but does NOT factor into the unit overrun check.
      a.added = a.added.add(BigDecimal.valueOf(r.getNos()));
      a.typeCode = "EQUIPMENT";
    }
    for (DprMaterial r : material) {
      if (r.getRoleId() == null || r.getMaterialRoleVariantId() == null || r.getQuantity() == null) continue;
      OverrunKey k = new OverrunKey(r.getRoleId(), r.getMaterialRoleVariantId());
      OverrunAgg a = agg.computeIfAbsent(k, x -> new OverrunAgg());
      a.added = a.added.add(r.getQuantity());
      a.typeCode = "MATERIAL";
    }
    if (agg.isEmpty()) return;

    @SuppressWarnings("unchecked")
    List<Object[]> assignments = em.createNativeQuery(
        "SELECT ra.role_id, ra.manpower_role_rate_id, ra.equipment_role_variant_id, "
            + "       ra.material_role_variant_id, ra.planned_units, ra.actual_units, "
            + "       r.name AS role_name "
            + "FROM resource.resource_assignments ra "
            + "LEFT JOIN resource.resource_roles r ON r.id = ra.role_id "
            + "WHERE ra.activity_id = :activityId")
        .setParameter("activityId", activityId)
        .getResultList();

    for (Map.Entry<OverrunKey, OverrunAgg> e : agg.entrySet()) {
      OverrunKey key = e.getKey();
      OverrunAgg val = e.getValue();
      Object[] match = findAssignmentRow(assignments, key.roleId, key.variantId);
      // Unplanned variants are handled separately (phantom row + pill); skip the warning here.
      if (match == null) continue;
      BigDecimal planned = match[4] == null ? BigDecimal.ZERO : new BigDecimal(match[4].toString());
      // A row with planned=0 is itself an unplanned phantom — no overrun to flag.
      if (planned.signum() <= 0) continue;
      BigDecimal currentActual = match[5] == null ? BigDecimal.ZERO : new BigDecimal(match[5].toString());
      String roleName = match[6] == null ? "(role)" : match[6].toString();
      BigDecimal candidate = currentActual.add(val.added);
      if (candidate.compareTo(planned) > 0) {
        BigDecimal excess = candidate.subtract(planned);
        warnings.add(String.format(
            "OVERRUN: %s (%s) — planned %s, actual %s (+%s), excess %s",
            roleName,
            val.typeCode == null ? "?" : val.typeCode,
            planned.stripTrailingZeros().toPlainString(),
            candidate.stripTrailingZeros().toPlainString(),
            val.added.stripTrailingZeros().toPlainString(),
            excess.stripTrailingZeros().toPlainString()));
      }
    }
  }

  /**
   * For every (role, variant) referenced by the DPR's child rows on this activity that has no
   * matching {@link com.bipros.resource.domain.model.ResourceAssignment}, INSERT a phantom row
   * with {@code planned/budgeted/remaining = 0} and a resolved {@code effective_rate}. The
   * subsequent {@link #rollupRoleAssignmentActuals} call writes {@code actual_units} /
   * {@code actual_cost} onto these rows the same way it does for planned ones.
   *
   * <p>If the rate book has neither a project override nor a default rate for a variant, a
   * {@code MISSING_RATE} warning is appended and the phantom is created with
   * {@code effective_rate = 0} (cost will show as 0 until an admin adds a rate).
   */
  private void ensureAssignmentsExist(UUID activityId, UUID projectId, List<String> warnings) {
    if (activityId == null || projectId == null || em == null) return;

    // ===== Manpower =====
    @SuppressWarnings("unchecked")
    List<Object[]> mWarn = em.createNativeQuery(
        "SELECT DISTINCT m.role_id, m.manpower_role_rate_id, "
            + "       COALESCE(po.override_rate, mrr.rate) AS resolved_rate, "
            + "       r.name, cm.name, gm.name "
            + "FROM project.dpr_manpower m "
            + "JOIN project.daily_progress_reports d ON d.id = m.dpr_id "
            + "LEFT JOIN resource.manpower_role_rates mrr ON mrr.id = m.manpower_role_rate_id "
            + "LEFT JOIN resource.project_manpower_role_rate_override po "
            + "  ON po.manpower_role_rate_id = m.manpower_role_rate_id AND po.project_id = :projectId "
            + "LEFT JOIN resource.resource_roles r ON r.id = m.role_id "
            + "LEFT JOIN resource.manpower_category_master cm ON cm.id = mrr.category_id "
            + "LEFT JOIN resource.grade_master gm ON gm.id = mrr.grade_id "
            + "WHERE d.activity_id = :activityId "
            + "  AND m.role_id IS NOT NULL AND m.manpower_role_rate_id IS NOT NULL "
            + "  AND NOT EXISTS ( "
            + "    SELECT 1 FROM resource.resource_assignments ra "
            + "    WHERE ra.activity_id = :activityId AND ra.role_id = m.role_id "
            + "      AND ra.manpower_role_rate_id = m.manpower_role_rate_id) ")
        .setParameter("activityId", activityId)
        .setParameter("projectId", projectId)
        .getResultList();
    for (Object[] row : mWarn) {
      if (row[2] == null) {
        String roleName = row[3] == null ? "(role)" : row[3].toString();
        String label = (row[4] == null ? "?" : row[4].toString()) + "/" + (row[5] == null ? "?" : row[5].toString());
        warnings.add("MISSING_RATE: " + roleName + " (MANPOWER) — " + label
            + ": no rate configured. Actual cost shown as 0 until a rate is added.");
      }
    }
    em.createNativeQuery(
        "INSERT INTO resource.resource_assignments "
            + "(id, activity_id, project_id, role_id, manpower_role_rate_id, "
            + " planned_units, budgeted_units, remaining_units, actual_units, "
            + " planned_cost, budgeted_cost, remaining_cost, actual_cost, "
            + " effective_rate, unit, rate_type, version, created_at, updated_at) "
            + "SELECT DISTINCT gen_random_uuid(), :activityId, :projectId, m.role_id, m.manpower_role_rate_id, "
            + "       0, 0, 0, 0, 0, 0, 0, 0, "
            + "       COALESCE(po.override_rate, mrr.rate, 0), mrr.unit, 'STANDARD', 0, now(), now() "
            + "FROM project.dpr_manpower m "
            + "JOIN project.daily_progress_reports d ON d.id = m.dpr_id "
            + "LEFT JOIN resource.manpower_role_rates mrr ON mrr.id = m.manpower_role_rate_id "
            + "LEFT JOIN resource.project_manpower_role_rate_override po "
            + "  ON po.manpower_role_rate_id = m.manpower_role_rate_id AND po.project_id = :projectId "
            + "WHERE d.activity_id = :activityId "
            + "  AND m.role_id IS NOT NULL AND m.manpower_role_rate_id IS NOT NULL "
            + "  AND NOT EXISTS ( "
            + "    SELECT 1 FROM resource.resource_assignments ra "
            + "    WHERE ra.activity_id = :activityId AND ra.role_id = m.role_id "
            + "      AND ra.manpower_role_rate_id = m.manpower_role_rate_id) ")
        .setParameter("activityId", activityId)
        .setParameter("projectId", projectId)
        .executeUpdate();

    // ===== Equipment =====
    @SuppressWarnings("unchecked")
    List<Object[]> eWarn = em.createNativeQuery(
        "SELECT DISTINCT e.role_id, e.equipment_role_variant_id, "
            + "       COALESCE(po.override_rate, erv.rate) AS resolved_rate, "
            + "       r.name, erv.make, erv.model "
            + "FROM project.dpr_equipment e "
            + "JOIN project.daily_progress_reports d ON d.id = e.dpr_id "
            + "LEFT JOIN resource.equipment_role_variants erv ON erv.id = e.equipment_role_variant_id "
            + "LEFT JOIN resource.project_equipment_role_variant_override po "
            + "  ON po.equipment_role_variant_id = e.equipment_role_variant_id AND po.project_id = :projectId "
            + "LEFT JOIN resource.resource_roles r ON r.id = e.role_id "
            + "WHERE d.activity_id = :activityId "
            + "  AND e.role_id IS NOT NULL AND e.equipment_role_variant_id IS NOT NULL "
            + "  AND NOT EXISTS ( "
            + "    SELECT 1 FROM resource.resource_assignments ra "
            + "    WHERE ra.activity_id = :activityId AND ra.role_id = e.role_id "
            + "      AND ra.equipment_role_variant_id = e.equipment_role_variant_id) ")
        .setParameter("activityId", activityId)
        .setParameter("projectId", projectId)
        .getResultList();
    for (Object[] row : eWarn) {
      if (row[2] == null) {
        String roleName = row[3] == null ? "(role)" : row[3].toString();
        String label = (row[4] == null ? "?" : row[4].toString()) + "/" + (row[5] == null ? "?" : row[5].toString());
        warnings.add("MISSING_RATE: " + roleName + " (EQUIPMENT) — " + label
            + ": no rate configured. Actual cost shown as 0 until a rate is added.");
      }
    }
    em.createNativeQuery(
        "INSERT INTO resource.resource_assignments "
            + "(id, activity_id, project_id, role_id, equipment_role_variant_id, "
            + " planned_units, budgeted_units, remaining_units, actual_units, "
            + " planned_cost, budgeted_cost, remaining_cost, actual_cost, "
            + " effective_rate, unit, rate_type, version, created_at, updated_at) "
            + "SELECT DISTINCT gen_random_uuid(), :activityId, :projectId, e.role_id, e.equipment_role_variant_id, "
            + "       0, 0, 0, 0, 0, 0, 0, 0, "
            + "       COALESCE(po.override_rate, erv.rate, 0), erv.unit, 'STANDARD', 0, now(), now() "
            + "FROM project.dpr_equipment e "
            + "JOIN project.daily_progress_reports d ON d.id = e.dpr_id "
            + "LEFT JOIN resource.equipment_role_variants erv ON erv.id = e.equipment_role_variant_id "
            + "LEFT JOIN resource.project_equipment_role_variant_override po "
            + "  ON po.equipment_role_variant_id = e.equipment_role_variant_id AND po.project_id = :projectId "
            + "WHERE d.activity_id = :activityId "
            + "  AND e.role_id IS NOT NULL AND e.equipment_role_variant_id IS NOT NULL "
            + "  AND NOT EXISTS ( "
            + "    SELECT 1 FROM resource.resource_assignments ra "
            + "    WHERE ra.activity_id = :activityId AND ra.role_id = e.role_id "
            + "      AND ra.equipment_role_variant_id = e.equipment_role_variant_id) ")
        .setParameter("activityId", activityId)
        .setParameter("projectId", projectId)
        .executeUpdate();

    // ===== Material =====
    @SuppressWarnings("unchecked")
    List<Object[]> matWarn = em.createNativeQuery(
        "SELECT DISTINCT mt.role_id, mt.material_role_variant_id, "
            + "       COALESCE(po.override_rate, mrv.rate) AS resolved_rate, "
            + "       r.name, mrv.spec_grade "
            + "FROM project.dpr_material mt "
            + "JOIN project.daily_progress_reports d ON d.id = mt.dpr_id "
            + "LEFT JOIN resource.material_role_variants mrv ON mrv.id = mt.material_role_variant_id "
            + "LEFT JOIN resource.project_material_role_variant_override po "
            + "  ON po.material_role_variant_id = mt.material_role_variant_id AND po.project_id = :projectId "
            + "LEFT JOIN resource.resource_roles r ON r.id = mt.role_id "
            + "WHERE d.activity_id = :activityId "
            + "  AND mt.role_id IS NOT NULL AND mt.material_role_variant_id IS NOT NULL "
            + "  AND NOT EXISTS ( "
            + "    SELECT 1 FROM resource.resource_assignments ra "
            + "    WHERE ra.activity_id = :activityId AND ra.role_id = mt.role_id "
            + "      AND ra.material_role_variant_id = mt.material_role_variant_id) ")
        .setParameter("activityId", activityId)
        .setParameter("projectId", projectId)
        .getResultList();
    for (Object[] row : matWarn) {
      if (row[2] == null) {
        String roleName = row[3] == null ? "(role)" : row[3].toString();
        String label = row[4] == null ? "?" : row[4].toString();
        warnings.add("MISSING_RATE: " + roleName + " (MATERIAL) — " + label
            + ": no rate configured. Actual cost shown as 0 until a rate is added.");
      }
    }
    em.createNativeQuery(
        "INSERT INTO resource.resource_assignments "
            + "(id, activity_id, project_id, role_id, material_role_variant_id, "
            + " planned_units, budgeted_units, remaining_units, actual_units, "
            + " planned_cost, budgeted_cost, remaining_cost, actual_cost, "
            + " effective_rate, unit, rate_type, version, created_at, updated_at) "
            + "SELECT DISTINCT gen_random_uuid(), :activityId, :projectId, mt.role_id, mt.material_role_variant_id, "
            + "       0, 0, 0, 0, 0, 0, 0, 0, "
            + "       COALESCE(po.override_rate, mrv.rate, 0), mrv.unit, 'STANDARD', 0, now(), now() "
            + "FROM project.dpr_material mt "
            + "JOIN project.daily_progress_reports d ON d.id = mt.dpr_id "
            + "LEFT JOIN resource.material_role_variants mrv ON mrv.id = mt.material_role_variant_id "
            + "LEFT JOIN resource.project_material_role_variant_override po "
            + "  ON po.material_role_variant_id = mt.material_role_variant_id AND po.project_id = :projectId "
            + "WHERE d.activity_id = :activityId "
            + "  AND mt.role_id IS NOT NULL AND mt.material_role_variant_id IS NOT NULL "
            + "  AND NOT EXISTS ( "
            + "    SELECT 1 FROM resource.resource_assignments ra "
            + "    WHERE ra.activity_id = :activityId AND ra.role_id = mt.role_id "
            + "      AND ra.material_role_variant_id = mt.material_role_variant_id) ")
        .setParameter("activityId", activityId)
        .setParameter("projectId", projectId)
        .executeUpdate();
  }

  /**
   * Roll DPR child rows up onto the matching {@code resource_assignments.actual_units} +
   * {@code actual_cost} keyed by {@code (activity_id, role_id, variant_id)}. This replaces the
   * legacy ledger-driven {@code ResourceAssignmentCostRollupListener} path for role-only
   * assignments (where {@code resource_id} is null and the legacy listener can't find a match).
   *
   * <p>Idempotent: every call recomputes the totals from scratch — safe to invoke after every
   * DPR create / update / delete.
   */
  private void rollupRoleAssignmentActuals(UUID activityId) {
    if (activityId == null || em == null) return;

    // Manpower: sum of nos per (role_id, manpower_role_rate_id)
    em.createNativeQuery(
            "UPDATE resource.resource_assignments ra SET "
                + "  actual_units = COALESCE(s.total_nos, 0), "
                + "  actual_cost  = COALESCE(s.total_nos, 0) * COALESCE(ra.effective_rate, 0), "
                + "  remaining_units = GREATEST(COALESCE(ra.planned_units, 0) - COALESCE(s.total_nos, 0), 0), "
                + "  remaining_cost  = GREATEST(COALESCE(ra.planned_cost, 0) - "
                + "                              COALESCE(s.total_nos, 0) * COALESCE(ra.effective_rate, 0), 0), "
                + "  updated_at = now() "
                + "FROM ( "
                + "  SELECT role_id, manpower_role_rate_id, SUM(nos)::numeric AS total_nos "
                + "  FROM project.dpr_manpower m "
                + "  JOIN project.daily_progress_reports d ON d.id = m.dpr_id "
                + "  WHERE d.activity_id = :activityId "
                + "    AND m.role_id IS NOT NULL "
                + "    AND m.manpower_role_rate_id IS NOT NULL "
                + "  GROUP BY role_id, manpower_role_rate_id "
                + ") s "
                + "WHERE ra.activity_id = :activityId "
                + "  AND ra.role_id = s.role_id "
                + "  AND ra.manpower_role_rate_id = s.manpower_role_rate_id")
        .setParameter("activityId", activityId)
        .executeUpdate();

    // Equipment: sum of nos per (role_id, equipment_role_variant_id) — hours intentionally NOT used
    em.createNativeQuery(
            "UPDATE resource.resource_assignments ra SET "
                + "  actual_units = COALESCE(s.total_nos, 0), "
                + "  actual_cost  = COALESCE(s.total_nos, 0) * COALESCE(ra.effective_rate, 0), "
                + "  remaining_units = GREATEST(COALESCE(ra.planned_units, 0) - COALESCE(s.total_nos, 0), 0), "
                + "  remaining_cost  = GREATEST(COALESCE(ra.planned_cost, 0) - "
                + "                              COALESCE(s.total_nos, 0) * COALESCE(ra.effective_rate, 0), 0), "
                + "  updated_at = now() "
                + "FROM ( "
                + "  SELECT role_id, equipment_role_variant_id, SUM(nos)::numeric AS total_nos "
                + "  FROM project.dpr_equipment e "
                + "  JOIN project.daily_progress_reports d ON d.id = e.dpr_id "
                + "  WHERE d.activity_id = :activityId "
                + "    AND e.role_id IS NOT NULL "
                + "    AND e.equipment_role_variant_id IS NOT NULL "
                + "  GROUP BY role_id, equipment_role_variant_id "
                + ") s "
                + "WHERE ra.activity_id = :activityId "
                + "  AND ra.role_id = s.role_id "
                + "  AND ra.equipment_role_variant_id = s.equipment_role_variant_id")
        .setParameter("activityId", activityId)
        .executeUpdate();

    // Material: sum of quantity per (role_id, material_role_variant_id)
    em.createNativeQuery(
            "UPDATE resource.resource_assignments ra SET "
                + "  actual_units = COALESCE(s.total_qty, 0), "
                + "  actual_cost  = COALESCE(s.total_qty, 0) * COALESCE(ra.effective_rate, 0), "
                + "  remaining_units = GREATEST(COALESCE(ra.planned_units, 0) - COALESCE(s.total_qty, 0), 0), "
                + "  remaining_cost  = GREATEST(COALESCE(ra.planned_cost, 0) - "
                + "                              COALESCE(s.total_qty, 0) * COALESCE(ra.effective_rate, 0), 0), "
                + "  updated_at = now() "
                + "FROM ( "
                + "  SELECT role_id, material_role_variant_id, SUM(quantity)::numeric AS total_qty "
                + "  FROM project.dpr_material mat "
                + "  JOIN project.daily_progress_reports d ON d.id = mat.dpr_id "
                + "  WHERE d.activity_id = :activityId "
                + "    AND mat.role_id IS NOT NULL "
                + "    AND mat.material_role_variant_id IS NOT NULL "
                + "  GROUP BY role_id, material_role_variant_id "
                + ") s "
                + "WHERE ra.activity_id = :activityId "
                + "  AND ra.role_id = s.role_id "
                + "  AND ra.material_role_variant_id = s.material_role_variant_id")
        .setParameter("activityId", activityId)
        .executeUpdate();

    // Sweep orphaned phantom rows: a row created by ensureAssignmentsExist for a DPR that was
    // later deleted will have planned/budgeted/actual all back to 0. These are noise in the
    // Resource Plan summary — delete them. A genuine planned row always has planned_units > 0
    // (the planning UI rejects 0), so this is safe.
    em.createNativeQuery(
            "DELETE FROM resource.resource_assignments ra "
                + "WHERE ra.activity_id = :activityId "
                + "  AND COALESCE(ra.planned_units, 0) = 0 "
                + "  AND COALESCE(ra.budgeted_units, 0) = 0 "
                + "  AND COALESCE(ra.actual_units, 0) = 0 "
                + "  AND COALESCE(ra.planned_cost, 0) = 0 "
                + "  AND COALESCE(ra.actual_cost, 0) = 0")
        .setParameter("activityId", activityId)
        .executeUpdate();
  }

  private static Object[] findAssignmentRow(List<Object[]> rows, UUID roleId, UUID variantId) {
    for (Object[] row : rows) {
      UUID rid = (UUID) row[0];
      if (rid == null || !rid.equals(roleId)) continue;
      UUID mrr = (UUID) row[1];
      UUID erv = (UUID) row[2];
      UUID mrv = (UUID) row[3];
      if (variantId.equals(mrr) || variantId.equals(erv) || variantId.equals(mrv)) return row;
    }
    return null;
  }

  // ===========================================================================================
  // Edit detection — used by update() to skip the resource-rollup chain when nothing changed.
  // Compares the user-editable canonical fields per row (FK + scalar quantities). Server-derived
  // fields (unitRate, lineCost, unitRateBasis) are excluded so a re-save with the same input is
  // detected as unchanged even if the rate book has shifted under the row.
  // ===========================================================================================

  private boolean resourceRowsEqual(
      List<DprManpowerRow> reqMan, List<DprEquipmentRow> reqEq, List<DprMaterialRow> reqMat,
      List<DprManpower> existMan, List<DprEquipment> existEq, List<DprMaterial> existMat) {
    return manpowerRowsEqual(reqMan, existMan)
        && equipmentRowsEqual(reqEq, existEq)
        && materialRowsEqual(reqMat, existMat);
  }

  private static boolean manpowerRowsEqual(List<DprManpowerRow> req, List<DprManpower> existing) {
    int reqSize = req == null ? 0 : req.size();
    if (reqSize != existing.size()) return false;
    if (reqSize == 0) return true;
    List<String> a = req.stream().map(r ->
        s(r.roleId()) + "|" + s(r.manpowerRoleRateId()) + "|"
            + n(r.nos()) + "|" + b(r.workingHours()) + "|" + b(r.otHours()) + "|" + b(r.idleHours())
            + "|" + nz(r.contractorName()) + "|" + nz(r.remarks())
    ).sorted().toList();
    List<String> e = existing.stream().map(r ->
        s(r.getRoleId()) + "|" + s(r.getManpowerRoleRateId()) + "|"
            + n(r.getNos()) + "|" + b(r.getWorkingHours()) + "|" + b(r.getOtHours()) + "|" + b(r.getIdleHours())
            + "|" + nz(r.getContractorName()) + "|" + nz(r.getRemarks())
    ).sorted().toList();
    return a.equals(e);
  }

  private static boolean equipmentRowsEqual(List<DprEquipmentRow> req, List<DprEquipment> existing) {
    int reqSize = req == null ? 0 : req.size();
    if (reqSize != existing.size()) return false;
    if (reqSize == 0) return true;
    List<String> a = req.stream().map(r ->
        s(r.roleId()) + "|" + s(r.equipmentRoleVariantId()) + "|" + nz(r.fleetNo()) + "|"
            + n(r.nos()) + "|" + b(r.workingHours()) + "|" + b(r.idleHours()) + "|"
            + b(r.breakdownHours()) + "|" + b(r.fuelLitres()) + "|"
            + nz(r.operatorName()) + "|" + nz(r.remarks())
    ).sorted().toList();
    List<String> e = existing.stream().map(r ->
        s(r.getRoleId()) + "|" + s(r.getEquipmentRoleVariantId()) + "|" + nz(r.getFleetNo()) + "|"
            + n(r.getNos()) + "|" + b(r.getWorkingHours()) + "|" + b(r.getIdleHours()) + "|"
            + b(r.getBreakdownHours()) + "|" + b(r.getFuelLitres()) + "|"
            + nz(r.getOperatorName()) + "|" + nz(r.getRemarks())
    ).sorted().toList();
    return a.equals(e);
  }

  private static boolean materialRowsEqual(List<DprMaterialRow> req, List<DprMaterial> existing) {
    int reqSize = req == null ? 0 : req.size();
    if (reqSize != existing.size()) return false;
    if (reqSize == 0) return true;
    List<String> a = req.stream().map(r ->
        s(r.roleId()) + "|" + s(r.materialRoleVariantId()) + "|" + b(r.quantity()) + "|"
            + nz(r.batchNo()) + "|" + nz(r.vendorName()) + "|" + nz(r.source()) + "|" + nz(r.remarks())
    ).sorted().toList();
    List<String> e = existing.stream().map(r ->
        s(r.getRoleId()) + "|" + s(r.getMaterialRoleVariantId()) + "|" + b(r.getQuantity()) + "|"
            + nz(r.getBatchNo()) + "|" + nz(r.getVendorName()) + "|" + nz(r.getSource()) + "|" + nz(r.getRemarks())
    ).sorted().toList();
    return a.equals(e);
  }

  private static String s(UUID u) { return u == null ? "" : u.toString(); }
  private static String n(Integer i) { return i == null ? "" : i.toString(); }
  private static String b(BigDecimal d) {
    return d == null ? "" : d.stripTrailingZeros().toPlainString();
  }
  private static String nz(String x) { return x == null ? "" : x; }

}
