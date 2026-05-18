package com.bipros.siteops.application.service;

import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.security.application.service.CurrentUserService;
import com.bipros.siteops.application.dto.CreateWorkfrontRequest;
import com.bipros.siteops.application.dto.UpdateWorkfrontRequest;
import com.bipros.siteops.application.dto.WorkfrontResponse;
import com.bipros.siteops.domain.model.Workfront;
import com.bipros.siteops.domain.model.WorkfrontStatus;
import com.bipros.siteops.domain.repository.WorkfrontRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class WorkfrontService {

    private final WorkfrontRepository workfrontRepository;
    private final CurrentUserService securityContextHelper;

    public WorkfrontResponse create(UUID projectId, CreateWorkfrontRequest request) {
        Workfront wf = new Workfront();
        wf.setProjectId(projectId);
        wf.setWbsCode(request.wbsCode());
        wf.setLocationCode(request.locationCode());
        WorkfrontStatus status = request.status() != null ? request.status() : WorkfrontStatus.PLANNED;
        wf.setStatus(status);
        if (status == WorkfrontStatus.READY) {
            wf.setReadyAt(Instant.now());
        }
        wf.setBlockers(request.blockers());
        wf.setNotes(request.notes());
        return toResponse(workfrontRepository.save(wf));
    }

    @Transactional(readOnly = true)
    public List<WorkfrontResponse> listByProject(UUID projectId) {
        return workfrontRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkfrontResponse getById(UUID projectId, UUID id) {
        Workfront wf = loadScoped(projectId, id);
        return toResponse(wf);
    }

    public WorkfrontResponse update(UUID projectId, UUID id, UpdateWorkfrontRequest request) {
        Workfront wf = loadScoped(projectId, id);

        if (request.wbsCode() != null) wf.setWbsCode(request.wbsCode());
        if (request.locationCode() != null) wf.setLocationCode(request.locationCode());
        if (request.blockers() != null) wf.setBlockers(request.blockers());
        if (request.notes() != null) wf.setNotes(request.notes());

        if (request.status() != null && request.status() != wf.getStatus()) {
            validateTransition(wf.getStatus(), request.status());
            wf.setStatus(request.status());
            if (request.status() == WorkfrontStatus.READY && wf.getReadyAt() == null) {
                wf.setReadyAt(Instant.now());
            }
        }
        return toResponse(workfrontRepository.save(wf));
    }

    public WorkfrontResponse release(UUID projectId, UUID id) {
        Workfront wf = loadScoped(projectId, id);
        validateTransition(wf.getStatus(), WorkfrontStatus.RELEASED);
        wf.setStatus(WorkfrontStatus.RELEASED);
        wf.setReleasedBy(securityContextHelper.getCurrentUserId());
        wf.setReleasedAt(Instant.now());
        return toResponse(workfrontRepository.save(wf));
    }

    private Workfront loadScoped(UUID projectId, UUID id) {
        Workfront wf = workfrontRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Workfront", id));
        if (!wf.getProjectId().equals(projectId)) {
            throw new ResourceNotFoundException("Workfront", id);
        }
        return wf;
    }

    private void validateTransition(WorkfrontStatus from, WorkfrontStatus to) {
        boolean ok = switch (from) {
            case PLANNED -> to == WorkfrontStatus.READY;
            case READY -> to == WorkfrontStatus.RELEASED;
            case RELEASED -> to == WorkfrontStatus.HANDED_OVER;
            case HANDED_OVER -> false;
        };
        if (!ok) {
            throw new BusinessRuleException(
                    "WORKFRONT_INVALID_TRANSITION",
                    "Illegal workfront status transition: " + from + " -> " + to);
        }
    }

    private WorkfrontResponse toResponse(Workfront wf) {
        return new WorkfrontResponse(
                wf.getId(),
                wf.getProjectId(),
                wf.getWbsCode(),
                wf.getLocationCode(),
                wf.getStatus(),
                wf.getReadyAt(),
                wf.getReleasedBy(),
                wf.getReleasedAt(),
                wf.getBlockers(),
                wf.getNotes(),
                wf.getCreatedAt(),
                wf.getCreatedBy(),
                wf.getUpdatedAt(),
                wf.getUpdatedBy()
        );
    }
}
