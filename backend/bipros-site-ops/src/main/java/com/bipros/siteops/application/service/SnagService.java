package com.bipros.siteops.application.service;

import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.security.SecurityContextHelper;
import com.bipros.siteops.application.dto.CloseSnagRequest;
import com.bipros.siteops.application.dto.CreateSnagRequest;
import com.bipros.siteops.application.dto.SnagResponse;
import com.bipros.siteops.application.dto.UpdateSnagRequest;
import com.bipros.siteops.domain.model.Snag;
import com.bipros.siteops.domain.model.SnagSeverity;
import com.bipros.siteops.domain.model.SnagStatus;
import com.bipros.siteops.domain.repository.SnagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class SnagService {

    private final SnagRepository snagRepository;
    private final SecurityContextHelper securityContextHelper;

    public SnagResponse create(UUID projectId, CreateSnagRequest request) {
        Snag s = new Snag();
        s.setProjectId(projectId);
        s.setActivityId(request.activityId());
        s.setLocationCode(request.locationCode());
        s.setDescription(request.description());
        s.setSeverity(request.severity() != null ? request.severity() : SnagSeverity.MEDIUM);
        s.setStatus(SnagStatus.OPEN);
        s.setRaisedBy(securityContextHelper.getCurrentUserId());
        s.setRaisedAt(Instant.now());
        return toResponse(snagRepository.save(s));
    }

    @Transactional(readOnly = true)
    public List<SnagResponse> listByProject(UUID projectId, SnagStatus status) {
        List<Snag> rows = (status == null)
                ? snagRepository.findByProjectIdOrderByRaisedAtDesc(projectId)
                : snagRepository.findByProjectIdAndStatusOrderByRaisedAtDesc(projectId, status);
        return rows.stream().map(this::toResponse).toList();
    }

    public SnagResponse update(UUID projectId, UUID id, UpdateSnagRequest request) {
        Snag s = loadScoped(projectId, id);
        if (s.getStatus() == SnagStatus.CLOSED) {
            throw new BusinessRuleException("SNAG_CLOSED", "Cannot edit a closed snag");
        }
        if (request.activityId() != null) s.setActivityId(request.activityId());
        if (request.locationCode() != null) s.setLocationCode(request.locationCode());
        if (request.description() != null) s.setDescription(request.description());
        if (request.severity() != null) s.setSeverity(request.severity());
        if (request.status() != null) {
            // Edit endpoint can only move status to IN_PROGRESS (close uses its own endpoint).
            if (request.status() == SnagStatus.CLOSED) {
                throw new BusinessRuleException("SNAG_USE_CLOSE_ENDPOINT", "Use /close endpoint to close a snag");
            }
            if (request.status() == SnagStatus.IN_PROGRESS && s.getStatus() == SnagStatus.OPEN) {
                s.setStatus(SnagStatus.IN_PROGRESS);
            } else if (request.status() != s.getStatus()) {
                throw new BusinessRuleException(
                        "SNAG_INVALID_TRANSITION",
                        "Illegal snag status transition: " + s.getStatus() + " -> " + request.status());
            }
        }
        return toResponse(snagRepository.save(s));
    }

    public SnagResponse close(UUID projectId, UUID id, CloseSnagRequest request) {
        Snag s = loadScoped(projectId, id);
        if (s.getStatus() == SnagStatus.CLOSED) {
            throw new BusinessRuleException("SNAG_ALREADY_CLOSED", "Snag already closed");
        }
        s.setStatus(SnagStatus.CLOSED);
        s.setClosedBy(securityContextHelper.getCurrentUserId());
        s.setClosedAt(Instant.now());
        if (request != null && request.closureNote() != null) {
            s.setClosureNote(request.closureNote());
        }
        return toResponse(snagRepository.save(s));
    }

    private Snag loadScoped(UUID projectId, UUID id) {
        Snag s = snagRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Snag", id));
        if (!s.getProjectId().equals(projectId)) {
            throw new ResourceNotFoundException("Snag", id);
        }
        return s;
    }

    private SnagResponse toResponse(Snag s) {
        return new SnagResponse(
                s.getId(),
                s.getProjectId(),
                s.getActivityId(),
                s.getLocationCode(),
                s.getDescription(),
                s.getSeverity(),
                s.getStatus(),
                s.getRaisedBy(),
                s.getRaisedAt(),
                s.getClosedBy(),
                s.getClosedAt(),
                s.getClosureNote(),
                s.getCreatedAt(),
                s.getCreatedBy(),
                s.getUpdatedAt(),
                s.getUpdatedBy()
        );
    }
}
