package com.bipros.project.application.service;

import com.bipros.common.dto.PagedResponse;
import com.bipros.common.event.ProjectCreatedEvent;
import com.bipros.common.event.ProjectUpdatedEvent;
import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.security.AccessSpecifications;
import com.bipros.common.security.ProjectAccessGuard;
import com.bipros.common.util.AuditService;
import com.bipros.contract.domain.model.Contract;
import com.bipros.contract.domain.model.ContractStatus;
import com.bipros.contract.domain.repository.ContractRepository;
import com.bipros.project.application.dto.CreateProjectRequest;
import com.bipros.project.application.dto.CreateProjectRequest.ContractSummaryInput;
import com.bipros.project.application.dto.ProjectResponse;
import com.bipros.project.application.dto.ProjectResponse.ContractSummary;
import com.bipros.project.application.dto.UpdateProjectRequest;
import com.bipros.project.domain.model.Project;
import com.bipros.project.domain.model.WbsNode;
import com.bipros.project.domain.repository.EpsNodeRepository;
import com.bipros.project.domain.repository.ProjectRepository;
import com.bipros.project.domain.repository.WbsNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final EpsNodeRepository epsNodeRepository;
    private final WbsNodeRepository wbsNodeRepository;
    private final AuditService auditService;
    private final ContractRepository contractRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ProjectAccessGuard projectAccess;

    public ProjectResponse createProject(CreateProjectRequest request) {
        log.info("Creating project with code: {}", request.code());

        // Stamp the creator as owner so CLIENT-style users can read their own creations even
        // before an OBS assignment / project_members row is added.
        UUID creatorId = projectAccess.currentUserId();

        if (projectRepository.existsByCode(request.code())) {
            throw new BusinessRuleException("PROJECT_CODE_DUPLICATE", "Project with code '" + request.code() + "' already exists");
        }

        if (!epsNodeRepository.existsById(request.epsNodeId())) {
            throw new ResourceNotFoundException("EpsNode", request.epsNodeId());
        }

        if (request.plannedStartDate() != null
            && request.plannedFinishDate() != null
            && request.plannedFinishDate().isBefore(request.plannedStartDate())) {
            throw new BusinessRuleException(
                "INVALID_DATE_RANGE",
                "plannedFinishDate must be on or after plannedStartDate");
        }

        validateChainage(request.fromChainageM(), request.toChainageM());

        Project project = new Project();
        project.setCode(request.code());
        project.setName(sanitizeText(request.name()));
        project.setDescription(sanitizeText(request.description()));
        project.setEpsNodeId(request.epsNodeId());
        project.setObsNodeId(request.obsNodeId());
        project.setPlannedStartDate(request.plannedStartDate());
        project.setPlannedFinishDate(request.plannedFinishDate());
        if (request.priority() != null) {
            project.setPriority(request.priority());
        }
        project.setCategory(request.category());
        project.setMorthCode(request.morthCode());
        project.setFromChainageM(request.fromChainageM());
        project.setToChainageM(request.toChainageM());
        project.setFromLocation(sanitizeText(request.fromLocation()));
        project.setToLocation(sanitizeText(request.toLocation()));
        project.setTotalLengthKm(deriveTotalLengthKm(
            request.fromChainageM(), request.toChainageM(), request.totalLengthKm()));
        project.setCalendarId(request.calendarId());
        if (request.budgetCurrency() != null && !request.budgetCurrency().isBlank()) {
            project.setBudgetCurrency(request.budgetCurrency().strip().toUpperCase());
        }
        project.setOwnerId(creatorId);

        Project saved = projectRepository.save(project);
        log.info("Project created with ID: {}", saved.getId());

        if (request.contract() != null) {
            upsertPrimaryContract(saved, request.contract());
        }

        // Audit log creation
        auditService.logCreate("Project", saved.getId(), buildProjectResponse(saved));

        // Auto-create root WBS node
        createRootWbsNode(saved);

        eventPublisher.publishEvent(
            new ProjectCreatedEvent(saved.getId(), saved.getCode(), saved.getName())
        );

        return buildProjectResponse(saved);
    }

    public ProjectResponse updateProject(UUID id, UpdateProjectRequest request) {
        log.info("Updating project: {}", id);

        projectAccess.requireEdit(id);

        Project project = projectRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Project", id));

        // Track changes for audit
        String oldName = project.getName();
        String oldDescription = project.getDescription();
        UUID oldObsNodeId = project.getObsNodeId();
        var oldPlannedStart = project.getPlannedStartDate();
        var oldPlannedFinish = project.getPlannedFinishDate();
        var oldMustFinishBy = project.getMustFinishByDate();
        var oldStatus = project.getStatus();
        var oldPriority = project.getPriority();
        var oldDataDate = project.getDataDate();

        if (request.name() != null) {
            project.setName(sanitizeText(request.name()));
        }
        if (request.description() != null) {
            project.setDescription(sanitizeText(request.description()));
        }
        if (request.obsNodeId() != null) {
            project.setObsNodeId(request.obsNodeId());
        }
        if (request.plannedStartDate() != null) {
            project.setPlannedStartDate(request.plannedStartDate());
        }
        if (request.plannedFinishDate() != null) {
            project.setPlannedFinishDate(request.plannedFinishDate());
        }

        var resolvedStart = project.getPlannedStartDate();
        var resolvedFinish = project.getPlannedFinishDate();
        if (resolvedStart != null && resolvedFinish != null && resolvedFinish.isBefore(resolvedStart)) {
            throw new BusinessRuleException(
                "INVALID_DATE_RANGE",
                "plannedFinishDate must be on or after plannedStartDate");
        }
        if (request.mustFinishByDate() != null) {
            project.setMustFinishByDate(request.mustFinishByDate());
        }
        if (request.status() != null) {
            project.setStatus(request.status());
        }
        if (request.priority() != null) {
            project.setPriority(request.priority());
        }
        if (request.dataDate() != null) {
            project.setDataDate(request.dataDate());
        }
        if (request.category() != null) {
            project.setCategory(request.category());
        }
        if (request.morthCode() != null) {
            project.setMorthCode(request.morthCode());
        }
        if (request.fromChainageM() != null) {
            project.setFromChainageM(request.fromChainageM());
        }
        if (request.toChainageM() != null) {
            project.setToChainageM(request.toChainageM());
        }
        if (request.fromLocation() != null) {
            project.setFromLocation(sanitizeText(request.fromLocation()));
        }
        if (request.toLocation() != null) {
            project.setToLocation(sanitizeText(request.toLocation()));
        }
        if (request.calendarId() != null) {
            project.setCalendarId(request.calendarId());
        }
        if (request.budgetCurrency() != null && !request.budgetCurrency().isBlank()) {
            String oldCur = project.getBudgetCurrency() == null ? "INR" : project.getBudgetCurrency().toUpperCase();
            String newCur = request.budgetCurrency().strip().toUpperCase();
            if (!newCur.equals(oldCur)) {
                // Relabel-only currency change. The BAC is stored in the currency's
                // "major-unit" (crores=1e7 for INR, millions=1e6 for others) and EVM/Cost
                // multiply it back to raw by that factor. To keep the REAL money unchanged
                // across a currency switch (no FX conversion), rescale the stored figure so
                // raw stays constant: e.g. 2 (₹ crore) -> 20 (OMR million), both 20,000,000.
                java.math.BigDecimal oldF = "INR".equals(oldCur)
                    ? new java.math.BigDecimal("10000000") : new java.math.BigDecimal("1000000");
                java.math.BigDecimal newF = "INR".equals(newCur)
                    ? new java.math.BigDecimal("10000000") : new java.math.BigDecimal("1000000");
                if (oldF.compareTo(newF) != 0) {
                    java.math.BigDecimal ratio = oldF.divide(newF, 10, java.math.RoundingMode.HALF_UP);
                    if (project.getOriginalBudget() != null) {
                        project.setOriginalBudget(project.getOriginalBudget().multiply(ratio));
                    }
                    if (project.getCurrentBudget() != null) {
                        project.setCurrentBudget(project.getCurrentBudget().multiply(ratio));
                    }
                }
            }
            project.setBudgetCurrency(newCur);
        }
        validateChainage(project.getFromChainageM(), project.getToChainageM());
        // Recompute derived length whenever chainages change (respecting an explicit override).
        if (request.fromChainageM() != null || request.toChainageM() != null
            || request.totalLengthKm() != null) {
            project.setTotalLengthKm(deriveTotalLengthKm(
                project.getFromChainageM(), project.getToChainageM(), request.totalLengthKm()));
        }

        Project updated = projectRepository.save(project);
        log.info("Project updated: {}", id);

        if (request.contract() != null) {
            upsertPrimaryContract(updated, request.contract());
        }

        // Audit log updates for all changed fields
        if (request.name() != null && !request.name().equals(oldName)) {
            auditService.logUpdate("Project", id, "name", oldName, request.name());
        }
        if (request.description() != null && !request.description().equals(oldDescription)) {
            auditService.logUpdate("Project", id, "description", oldDescription, request.description());
        }
        if (request.obsNodeId() != null && !request.obsNodeId().equals(oldObsNodeId)) {
            auditService.logUpdate("Project", id, "obsNodeId", oldObsNodeId, request.obsNodeId());
        }
        if (request.plannedStartDate() != null && !request.plannedStartDate().equals(oldPlannedStart)) {
            auditService.logUpdate("Project", id, "plannedStartDate", oldPlannedStart, request.plannedStartDate());
        }
        if (request.plannedFinishDate() != null && !request.plannedFinishDate().equals(oldPlannedFinish)) {
            auditService.logUpdate("Project", id, "plannedFinishDate", oldPlannedFinish, request.plannedFinishDate());
        }
        if (request.mustFinishByDate() != null && !request.mustFinishByDate().equals(oldMustFinishBy)) {
            auditService.logUpdate("Project", id, "mustFinishByDate", oldMustFinishBy, request.mustFinishByDate());
        }
        if (request.status() != null && !request.status().equals(oldStatus)) {
            auditService.logUpdate("Project", id, "status", oldStatus, request.status());
        }
        if (request.priority() != null && !request.priority().equals(oldPriority)) {
            auditService.logUpdate("Project", id, "priority", oldPriority, request.priority());
        }
        if (request.dataDate() != null && !request.dataDate().equals(oldDataDate)) {
            auditService.logUpdate("Project", id, "dataDate", oldDataDate, request.dataDate());
        }

        eventPublisher.publishEvent(
            new ProjectUpdatedEvent(updated.getId(), updated.getCode(), updated.getName())
        );

        return buildProjectResponse(updated);
    }

    /**
     * Soft-archive a project. Idempotent: archiving an already-archived project is a no-op.
     * Hard delete is intentionally not exposed — once a project has been created, the only
     * removal path users can invoke is archive + restore. This preserves activities, baselines,
     * costs, audit trail, etc., and lets clients recover from accidental archives.
     */
    public void deleteProject(UUID id) {
        log.info("Archiving project: {}", id);

        projectAccess.requireDelete(id);

        Project project = projectRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Project", id));

        if (project.getArchivedAt() != null) {
            log.info("Project already archived, skipping: {}", id);
            return;
        }

        project.setArchivedAt(Instant.now());
        project.setArchivedBy(projectAccess.currentUserId());
        projectRepository.save(project);

        auditService.logDelete("Project", id);
        log.info("Project archived: {}", id);
    }

    /**
     * Restore a previously-archived project. Status is preserved as-is (we don't force back to
     * ACTIVE — a project archived in COMPLETED state should restore as COMPLETED). Idempotent:
     * restoring a non-archived project is a no-op.
     */
    public ProjectResponse restoreProject(UUID id) {
        log.info("Restoring project: {}", id);

        projectAccess.requireDelete(id);

        Project project = projectRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Project", id));

        if (project.getArchivedAt() == null) {
            return buildProjectResponse(project);
        }

        project.setArchivedAt(null);
        project.setArchivedBy(null);
        Project restored = projectRepository.save(project);

        auditService.logUpdate("Project", id, "archivedAt", "archived", null);
        log.info("Project restored: {}", id);

        return buildProjectResponse(restored);
    }

    public ProjectResponse getProject(UUID id) {
        log.info("Fetching project: {}", id);

        projectAccess.requireRead(id);

        Project project = projectRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Project", id));

        return buildProjectResponse(project);
    }

    /**
     * Focused endpoint for advancing the project's data date — the as-of date used by
     * EVM, schedule status, and "what should have been done by now" reporting.
     * Kept separate from {@link #updateProject} so callers can move the data date
     * without touching unrelated fields, and so audit history clearly attributes the change.
     */
    public ProjectResponse setDataDate(UUID id, java.time.LocalDate dataDate) {
        log.info("Setting dataDate on project {} to {}", id, dataDate);

        projectAccess.requireEdit(id);

        Project project = projectRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Project", id));

        java.time.LocalDate previous = project.getDataDate();
        if (java.util.Objects.equals(previous, dataDate)) {
            return buildProjectResponse(project);
        }

        project.setDataDate(dataDate);
        Project saved = projectRepository.save(project);
        auditService.logUpdate("Project", id, "dataDate", previous, dataDate);
        return buildProjectResponse(saved);
    }

    public PagedResponse<ProjectResponse> listProjects(Pageable pageable) {
        log.info("Fetching projects page: {}", pageable);
        return queryProjects(pageable, false);
    }

    public PagedResponse<ProjectResponse> listArchivedProjects(Pageable pageable) {
        log.info("Fetching archived projects page: {}", pageable);
        return queryProjects(pageable, true);
    }

    private PagedResponse<ProjectResponse> queryProjects(Pageable pageable, boolean archived) {
        // RLS: Project IS the row, so filter by Project.id IN allowedProjectIds.
        Specification<Project> spec = AccessSpecifications.<Project>projectScopedTo(
                "id", projectAccess.getAccessibleProjectIdsForCurrentUser())
            .and(archivedAtFilter(archived));
        Page<Project> page = projectRepository.findAll(spec, pageable);

        List<ProjectResponse> content = page.getContent().stream()
            .map(this::buildProjectResponse)
            .collect(Collectors.toList());

        return PagedResponse.of(
            content,
            page.getTotalElements(),
            page.getTotalPages(),
            page.getNumber(),
            page.getSize()
        );
    }

    /** {@code archived=false} → only live rows; {@code archived=true} → only archived rows. */
    private static Specification<Project> archivedAtFilter(boolean archived) {
        return (root, query, cb) -> archived
            ? cb.isNotNull(root.get("archivedAt"))
            : cb.isNull(root.get("archivedAt"));
    }

    public List<ProjectResponse> getProjectsByEps(UUID epsNodeId) {
        log.info("Fetching projects by EPS node: {}", epsNodeId);

        if (!epsNodeRepository.existsById(epsNodeId)) {
            throw new ResourceNotFoundException("EpsNode", epsNodeId);
        }

        // Filter results to only live projects the user may read.
        java.util.Set<UUID> allowed = projectAccess.getAccessibleProjectIdsForCurrentUser();
        return projectRepository.findByEpsNodeId(epsNodeId).stream()
            .filter(p -> p.getArchivedAt() == null)
            .filter(p -> allowed == null || allowed.contains(p.getId()))
            .map(this::buildProjectResponse)
            .collect(Collectors.toList());
    }

    private void createRootWbsNode(Project project) {
        WbsNode rootWbs = new WbsNode();
        rootWbs.setCode(project.getCode());
        rootWbs.setName(project.getName());
        rootWbs.setProjectId(project.getId());
        rootWbs.setParentId(null);
        rootWbs.setSortOrder(0);

        wbsNodeRepository.save(rootWbs);
        log.info("Root WBS node created for project: {}", project.getId());
    }

    private ProjectResponse buildProjectResponse(Project project) {
        return new ProjectResponse(
            project.getId(),
            project.getCode(),
            project.getName(),
            project.getDescription(),
            project.getEpsNodeId(),
            project.getObsNodeId(),
            project.getPlannedStartDate(),
            project.getPlannedFinishDate(),
            project.getDataDate(),
            project.getStatus(),
            project.getMustFinishByDate(),
            project.getPriority(),
            project.getCategory(),
            project.getMorthCode(),
            project.getFromChainageM(),
            project.getToChainageM(),
            project.getFromLocation(),
            project.getToLocation(),
            project.getTotalLengthKm(),
            project.getCalendarId(),
            project.getActiveBaselineId(),
            project.getPrimaryBaselineId(),
            project.getSecondaryBaselineId(),
            project.getTertiaryBaselineId(),
            project.isRequiresRebaseline(),
            primaryContractSummary(project.getId()),
            project.getOriginalBudget(),
            project.getCurrentBudget(),
            project.getBudgetCurrency(),
            toLocalDateTime(project.getCreatedAt()),
            toLocalDateTime(project.getUpdatedAt()),
            toLocalDateTime(project.getArchivedAt())
        );
    }

    /**
     * Pick the "primary" contract for a project. Prefers the first ACTIVE row; otherwise falls
     * back to the earliest-started row. Returns {@code null} if the project has no contracts yet.
     */
    private ContractSummary primaryContractSummary(UUID projectId) {
        List<Contract> contracts = contractRepository.findByProjectId(projectId);
        if (contracts.isEmpty()) return null;
        Contract primary = contracts.stream()
            .filter(c -> c.getStatus() == ContractStatus.ACTIVE)
            .findFirst()
            .orElseGet(() -> contracts.stream()
                .sorted(Comparator.comparing(Contract::getStartDate,
                    Comparator.nullsLast(Comparator.naturalOrder())))
                .findFirst().orElse(null));
        if (primary == null) return null;
        return new ContractSummary(
            primary.getId(),
            primary.getContractNumber(),
            primary.getContractType(),
            primary.getContractValue(),
            primary.getRevisedValue(),
            primary.getStartDate(),
            primary.getCompletionDate(),
            primary.getDlpMonths()
        );
    }

    /**
     * Upsert the project's primary Contract from the flat {@link ContractSummaryInput}. Creates
     * a new row the first time; otherwise updates the existing primary in place. Validates that
     * {@code revisedValue >= contractValue} when both are present.
     */
    private void upsertPrimaryContract(Project project, ContractSummaryInput input) {
        if (input == null) return;
        if (input.revisedValue() != null && input.contractValue() != null
            && input.revisedValue().compareTo(input.contractValue()) < 0) {
            throw new BusinessRuleException(
                "INVALID_CONTRACT_VALUE",
                "revisedValue must be greater than or equal to contractValue");
        }
        List<Contract> existing = contractRepository.findByProjectId(project.getId());
        Contract contract = existing.stream()
            .filter(c -> c.getStatus() == ContractStatus.ACTIVE)
            .findFirst()
            .orElseGet(() -> existing.isEmpty() ? new Contract() : existing.get(0));
        boolean isNew = contract.getId() == null;
        contract.setProjectId(project.getId());
        if (input.contractNumber() != null) contract.setContractNumber(input.contractNumber());
        if (contract.getContractNumber() == null || contract.getContractNumber().isBlank()) {
            // Contract number is mandatory on the schema; synthesise one if the caller omitted it.
            contract.setContractNumber("CT-" + project.getCode());
        }
        if (input.contractType() != null) contract.setContractType(input.contractType());
        if (input.contractValue() != null) contract.setContractValue(input.contractValue());
        if (input.revisedValue() != null) contract.setRevisedValue(input.revisedValue());
        if (input.startDate() != null) contract.setStartDate(input.startDate());
        if (input.completionDate() != null) contract.setCompletionDate(input.completionDate());
        if (input.dlpMonths() != null) contract.setDlpMonths(input.dlpMonths());
        if (input.contractorName() != null) contract.setContractorName(input.contractorName());
        if (contract.getContractorName() == null || contract.getContractorName().isBlank()) {
            contract.setContractorName("TBD");
        }
        if (contract.getContractType() == null) {
            // Contract.contractType is NOT NULL — fall back to LUMP_SUM when the caller hasn't chosen.
            contract.setContractType(com.bipros.contract.domain.model.ContractType.LUMP_SUM);
        }
        contractRepository.save(contract);
        if (isNew) {
            log.info("Primary contract created for project: projectId={}, contractNumber={}",
                project.getId(), contract.getContractNumber());
        }
    }

    private void validateChainage(Long from, Long to) {
        if (from != null && to != null && to < from) {
            throw new BusinessRuleException(
                "INVALID_CHAINAGE_RANGE",
                "toChainageM must be on or after fromChainageM");
        }
    }

    private BigDecimal deriveTotalLengthKm(Long from, Long to, BigDecimal explicit) {
        if (explicit != null) return explicit;
        if (from == null || to == null || to < from) return null;
        return BigDecimal.valueOf(to - from)
            .divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return instant != null ? LocalDateTime.ofInstant(instant, ZoneId.systemDefault()) : null;
    }

    /**
     * Strip HTML-ish tags from free-text fields. The UI escapes on render so there's no XSS risk,
     * but storing {@code <script>} payloads verbatim fails data-hygiene reviews and pollutes
     * exports/search. Tags are removed (not encoded) so the value round-trips cleanly through
     * downstream consumers.
     */
    private static String sanitizeText(String value) {
        if (value == null) return null;
        String stripped = value.replaceAll("<[^>]*>", "").trim();
        return stripped.isEmpty() ? null : stripped;
    }
}
