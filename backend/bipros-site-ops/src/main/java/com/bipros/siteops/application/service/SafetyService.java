package com.bipros.siteops.application.service;

import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.security.SecurityContextHelper;
import com.bipros.siteops.application.dto.CreateSafetyRecordRequest;
import com.bipros.siteops.application.dto.SafetyRecordResponse;
import com.bipros.siteops.application.dto.UpdateSafetyRecordRequest;
import com.bipros.siteops.domain.model.SafetyKind;
import com.bipros.siteops.domain.model.SafetyRecord;
import com.bipros.siteops.domain.model.SafetySeverity;
import com.bipros.siteops.domain.repository.SafetyRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class SafetyService {

    private final SafetyRecordRepository repository;
    private final SecurityContextHelper securityContext;

    public SafetyRecordResponse create(UUID projectId, CreateSafetyRecordRequest req) {
        SafetyRecord record = new SafetyRecord();
        record.setProjectId(projectId);
        record.setKind(req.kind());
        record.setOccurredAt(req.occurredAt());
        record.setLocationCode(req.locationCode());
        record.setSeverity(req.severity() != null ? req.severity() : SafetySeverity.LOW);
        record.setDescription(req.description());
        record.setImmediateAction(req.immediateAction());
        record.setPeopleInvolved(req.peopleInvolved());
        try {
            record.setReportedBy(securityContext.getCurrentUserId());
        } catch (IllegalStateException ignore) {
            // anonymous
        }
        return toResponse(repository.save(record));
    }

    @Transactional(readOnly = true)
    public List<SafetyRecordResponse> list(UUID projectId, SafetyKind kind) {
        List<SafetyRecord> rows = kind == null
                ? repository.findByProjectIdOrderByOccurredAtDesc(projectId)
                : repository.findByProjectIdAndKindOrderByOccurredAtDesc(projectId, kind);
        return rows.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public SafetyRecordResponse get(UUID projectId, UUID id) {
        return toResponse(findOrThrow(projectId, id));
    }

    public SafetyRecordResponse update(UUID projectId, UUID id, UpdateSafetyRecordRequest req) {
        SafetyRecord record = findOrThrow(projectId, id);
        if (req.kind() != null) record.setKind(req.kind());
        if (req.occurredAt() != null) record.setOccurredAt(req.occurredAt());
        if (req.locationCode() != null) record.setLocationCode(req.locationCode());
        if (req.severity() != null) record.setSeverity(req.severity());
        if (req.description() != null) record.setDescription(req.description());
        if (req.immediateAction() != null) record.setImmediateAction(req.immediateAction());
        if (req.peopleInvolved() != null) record.setPeopleInvolved(req.peopleInvolved());
        return toResponse(repository.save(record));
    }

    private SafetyRecord findOrThrow(UUID projectId, UUID id) {
        SafetyRecord record = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("SafetyRecord", id));
        if (!record.getProjectId().equals(projectId)) {
            throw new ResourceNotFoundException("SafetyRecord", id);
        }
        return record;
    }

    private SafetyRecordResponse toResponse(SafetyRecord r) {
        return new SafetyRecordResponse(
                r.getId(),
                r.getProjectId(),
                r.getKind(),
                r.getOccurredAt(),
                r.getLocationCode(),
                r.getSeverity(),
                r.getDescription(),
                r.getImmediateAction(),
                r.getReportedBy(),
                r.getPeopleInvolved(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
