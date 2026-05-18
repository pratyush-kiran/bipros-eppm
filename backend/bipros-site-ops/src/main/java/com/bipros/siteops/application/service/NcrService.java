package com.bipros.siteops.application.service;

import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.security.application.service.CurrentUserService;
import com.bipros.siteops.application.dto.CloseNcrRequest;
import com.bipros.siteops.application.dto.CreateNcrRequest;
import com.bipros.siteops.application.dto.NcrResponse;
import com.bipros.siteops.application.dto.RejectNcrRequest;
import com.bipros.siteops.application.dto.UpdateNcrRequest;
import com.bipros.siteops.domain.model.Ncr;
import com.bipros.siteops.domain.model.NcrCategory;
import com.bipros.siteops.domain.model.NcrSeverity;
import com.bipros.siteops.domain.model.NcrStatus;
import com.bipros.siteops.domain.repository.NcrRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class NcrService {

    private final NcrRepository ncrRepository;
    private final CurrentUserService securityContext;

    public NcrResponse create(UUID projectId, CreateNcrRequest req) {
        Ncr ncr = new Ncr();
        ncr.setProjectId(projectId);
        ncr.setNcrNo(allocateNcrNo(projectId));
        ncr.setTitle(req.title());
        ncr.setDescription(req.description());
        ncr.setCategory(req.category() != null ? req.category() : NcrCategory.QUALITY);
        ncr.setSeverity(req.severity() != null ? req.severity() : NcrSeverity.MEDIUM);
        ncr.setStatus(NcrStatus.OPEN);
        ncr.setAssignedTo(req.assignedTo());
        ncr.setRaisedAt(Instant.now());
        try {
            ncr.setRaisedBy(securityContext.getCurrentUserId());
        } catch (IllegalStateException ignore) {
            // anonymous
        }
        return toResponse(ncrRepository.save(ncr));
    }

    @Transactional(readOnly = true)
    public List<NcrResponse> list(UUID projectId, NcrStatus status) {
        List<Ncr> rows = status == null
                ? ncrRepository.findByProjectIdOrderByCreatedAtDesc(projectId)
                : ncrRepository.findByProjectIdAndStatusOrderByCreatedAtDesc(projectId, status);
        return rows.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public NcrResponse get(UUID projectId, UUID id) {
        return toResponse(findOrThrow(projectId, id));
    }

    public NcrResponse update(UUID projectId, UUID id, UpdateNcrRequest req) {
        Ncr ncr = findOrThrow(projectId, id);
        if (ncr.getStatus() == NcrStatus.CLOSED) {
            throw new BusinessRuleException("NCR_CLOSED", "Closed NCRs cannot be edited");
        }
        if (req.title() != null) ncr.setTitle(req.title());
        if (req.description() != null) ncr.setDescription(req.description());
        if (req.category() != null) ncr.setCategory(req.category());
        if (req.severity() != null) ncr.setSeverity(req.severity());
        if (req.assignedTo() != null) ncr.setAssignedTo(req.assignedTo());
        if (req.rootCause() != null) ncr.setRootCause(req.rootCause());
        if (req.correctiveAction() != null) ncr.setCorrectiveAction(req.correctiveAction());
        // Any edit on an OPEN ticket promotes it to IN_REVIEW so the closure step has a
        // visible queue. REJECTED tickets stay in their terminal state until reopened.
        if (ncr.getStatus() == NcrStatus.OPEN) ncr.setStatus(NcrStatus.IN_REVIEW);
        return toResponse(ncrRepository.save(ncr));
    }

    public NcrResponse approveClosure(UUID projectId, UUID id, CloseNcrRequest req) {
        Ncr ncr = findOrThrow(projectId, id);
        if (ncr.getStatus() == NcrStatus.CLOSED || ncr.getStatus() == NcrStatus.REJECTED) {
            throw new BusinessRuleException("NCR_INVALID_TRANSITION",
                    "NCR is already in a terminal state");
        }
        ncr.setRootCause(req.rootCause());
        ncr.setCorrectiveAction(req.correctiveAction());
        ncr.setStatus(NcrStatus.CLOSED);
        ncr.setClosedAt(Instant.now());
        try {
            ncr.setClosedBy(securityContext.getCurrentUserId());
        } catch (IllegalStateException ignore) {
            // anonymous
        }
        return toResponse(ncrRepository.save(ncr));
    }

    public NcrResponse reject(UUID projectId, UUID id, RejectNcrRequest req) {
        Ncr ncr = findOrThrow(projectId, id);
        if (ncr.getStatus() == NcrStatus.CLOSED || ncr.getStatus() == NcrStatus.REJECTED) {
            throw new BusinessRuleException("NCR_INVALID_TRANSITION",
                    "NCR is already in a terminal state");
        }
        ncr.setStatus(NcrStatus.REJECTED);
        if (req != null && req.note() != null && !req.note().isBlank()) {
            String prior = ncr.getRootCause() == null ? "" : ncr.getRootCause() + "\n";
            ncr.setRootCause(prior + "Rejection: " + req.note());
        }
        return toResponse(ncrRepository.save(ncr));
    }

    private Ncr findOrThrow(UUID projectId, UUID id) {
        Ncr ncr = ncrRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Ncr", id));
        if (!ncr.getProjectId().equals(projectId)) {
            throw new ResourceNotFoundException("Ncr", id);
        }
        return ncr;
    }

    private String allocateNcrNo(UUID projectId) {
        long n = ncrRepository.countByProjectId(projectId) + 1;
        String suffix = projectId.toString().substring(0, 8);
        return "NCR-%s-%04d".formatted(suffix, n);
    }

    private NcrResponse toResponse(Ncr ncr) {
        return new NcrResponse(
                ncr.getId(),
                ncr.getProjectId(),
                ncr.getNcrNo(),
                ncr.getTitle(),
                ncr.getDescription(),
                ncr.getCategory(),
                ncr.getSeverity(),
                ncr.getStatus(),
                ncr.getRaisedBy(),
                ncr.getRaisedAt(),
                ncr.getAssignedTo(),
                ncr.getRootCause(),
                ncr.getCorrectiveAction(),
                ncr.getClosedBy(),
                ncr.getClosedAt(),
                ncr.getCreatedAt(),
                ncr.getUpdatedAt()
        );
    }
}
