package com.bipros.siteops.application.service;

import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.security.application.service.CurrentUserService;
import com.bipros.siteops.application.dto.CreateShiftHandoverRequest;
import com.bipros.siteops.application.dto.ShiftHandoverResponse;
import com.bipros.siteops.domain.model.Shift;
import com.bipros.siteops.domain.model.ShiftHandover;
import com.bipros.siteops.domain.repository.ShiftHandoverRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class ShiftHandoverService {

    private final ShiftHandoverRepository handoverRepository;
    private final CurrentUserService securityContextHelper;

    public ShiftHandoverResponse create(UUID projectId, CreateShiftHandoverRequest request) {
        UUID currentUserId = securityContextHelper.getCurrentUserId();
        if (currentUserId.equals(request.toUserId())) {
            throw new BusinessRuleException("HANDOVER_SAME_USER",
                    "Outgoing and incoming user cannot be the same");
        }

        ShiftHandover h = new ShiftHandover();
        h.setProjectId(projectId);
        h.setShiftDate(request.shiftDate());
        h.setShift(request.shift());
        h.setFromUserId(currentUserId);
        h.setToUserId(request.toUserId());
        h.setSummary(request.summary());
        h.setPendingItems(request.pendingItems());
        h.setHandedOverAt(Instant.now());
        return toResponse(handoverRepository.save(h));
    }

    @Transactional(readOnly = true)
    public List<ShiftHandoverResponse> list(UUID projectId, LocalDate shiftDate, Shift shift) {
        List<ShiftHandover> rows;
        if (shiftDate != null && shift != null) {
            rows = handoverRepository.findByProjectIdAndShiftDateAndShiftOrderByHandedOverAtDesc(
                    projectId, shiftDate, shift);
        } else if (shiftDate != null) {
            rows = handoverRepository.findByProjectIdAndShiftDateOrderByHandedOverAtDesc(projectId, shiftDate);
        } else if (shift != null) {
            rows = handoverRepository.findByProjectIdAndShiftOrderByHandedOverAtDesc(projectId, shift);
        } else {
            rows = handoverRepository.findByProjectIdOrderByHandedOverAtDesc(projectId);
        }
        return rows.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ShiftHandoverResponse getById(UUID projectId, UUID id) {
        return toResponse(loadScoped(projectId, id));
    }

    public ShiftHandoverResponse acknowledge(UUID projectId, UUID id) {
        ShiftHandover h = loadScoped(projectId, id);
        UUID currentUserId = securityContextHelper.getCurrentUserId();
        if (!h.getToUserId().equals(currentUserId)) {
            throw new BusinessRuleException("HANDOVER_NOT_ADDRESSEE",
                    "Only the incoming supervisor can acknowledge this handover");
        }
        if (h.getAcknowledgedAt() != null) {
            return toResponse(h);
        }
        h.setAcknowledgedAt(Instant.now());
        return toResponse(handoverRepository.save(h));
    }

    private ShiftHandover loadScoped(UUID projectId, UUID id) {
        ShiftHandover h = handoverRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ShiftHandover", id));
        if (!h.getProjectId().equals(projectId)) {
            throw new ResourceNotFoundException("ShiftHandover", id);
        }
        return h;
    }

    private ShiftHandoverResponse toResponse(ShiftHandover h) {
        return new ShiftHandoverResponse(
                h.getId(),
                h.getProjectId(),
                h.getShiftDate(),
                h.getShift(),
                h.getFromUserId(),
                h.getToUserId(),
                h.getSummary(),
                h.getPendingItems(),
                h.getHandedOverAt(),
                h.getAcknowledgedAt(),
                h.getCreatedAt(),
                h.getCreatedBy(),
                h.getUpdatedAt(),
                h.getUpdatedBy()
        );
    }
}
