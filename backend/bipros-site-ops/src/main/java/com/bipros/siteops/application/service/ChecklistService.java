package com.bipros.siteops.application.service;

import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.security.application.service.CurrentUserService;
import com.bipros.siteops.application.dto.ChecklistAnswerDto;
import com.bipros.siteops.application.dto.ChecklistDecisionRequest;
import com.bipros.siteops.application.dto.ChecklistInstanceResponse;
import com.bipros.siteops.application.dto.ChecklistTemplateItemDto;
import com.bipros.siteops.application.dto.ChecklistTemplateResponse;
import com.bipros.siteops.application.dto.SaveChecklistAnswersRequest;
import com.bipros.siteops.application.dto.StartChecklistRequest;
import com.bipros.siteops.domain.model.ChecklistAnswer;
import com.bipros.siteops.domain.model.ChecklistInstance;
import com.bipros.siteops.domain.model.ChecklistStatus;
import com.bipros.siteops.domain.model.ChecklistTemplate;
import com.bipros.siteops.domain.repository.ChecklistInstanceRepository;
import com.bipros.siteops.domain.repository.ChecklistTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ChecklistService {

    private final ChecklistTemplateRepository templateRepository;
    private final ChecklistInstanceRepository instanceRepository;
    private final CurrentUserService securityContext;

    // ── Templates ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ChecklistTemplateResponse> listTemplates() {
        return templateRepository.findByActiveTrueOrderByNameAsc().stream()
                .map(this::toTemplateResponse).toList();
    }

    @Transactional(readOnly = true)
    public ChecklistTemplateResponse getTemplate(UUID id) {
        ChecklistTemplate t = templateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ChecklistTemplate", id));
        return toTemplateResponse(t);
    }

    // ── Instances ────────────────────────────────────────────────────────────

    public ChecklistInstanceResponse start(UUID projectId, StartChecklistRequest req) {
        ChecklistTemplate template = templateRepository.findById(req.templateId())
                .orElseThrow(() -> new ResourceNotFoundException("ChecklistTemplate", req.templateId()));

        ChecklistInstance instance = new ChecklistInstance();
        instance.setProjectId(projectId);
        instance.setActivityId(req.activityId());
        instance.setTemplateId(template.getId());
        instance.setStatus(ChecklistStatus.IN_PROGRESS);
        instance.setStartedAt(Instant.now());
        try {
            instance.setStartedBy(securityContext.getCurrentUserId());
        } catch (IllegalStateException ignore) {
            // anonymous
        }
        return toInstanceResponse(instanceRepository.save(instance), template);
    }

    @Transactional(readOnly = true)
    public List<ChecklistInstanceResponse> list(UUID projectId) {
        List<ChecklistInstance> rows = instanceRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        Map<UUID, ChecklistTemplate> tplCache = new HashMap<>();
        List<ChecklistInstanceResponse> out = new ArrayList<>(rows.size());
        for (ChecklistInstance i : rows) {
            ChecklistTemplate t = tplCache.computeIfAbsent(i.getTemplateId(),
                    id -> templateRepository.findById(id).orElse(null));
            out.add(toInstanceResponse(i, t));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public ChecklistInstanceResponse get(UUID projectId, UUID id) {
        ChecklistInstance instance = findOrThrow(projectId, id);
        ChecklistTemplate t = templateRepository.findById(instance.getTemplateId()).orElse(null);
        return toInstanceResponse(instance, t);
    }

    public ChecklistInstanceResponse saveAnswers(UUID projectId, UUID id, SaveChecklistAnswersRequest req) {
        ChecklistInstance instance = findOrThrow(projectId, id);
        if (instance.getStatus() != ChecklistStatus.IN_PROGRESS) {
            throw new BusinessRuleException("CHECKLIST_NOT_EDITABLE",
                    "Answers can only be saved while checklist is IN_PROGRESS");
        }
        // Replace-all semantics: clear and re-insert the supplied set.
        instance.getAnswers().clear();
        for (ChecklistAnswerDto dto : req.answers()) {
            ChecklistAnswer answer = new ChecklistAnswer();
            answer.setItemId(dto.itemId());
            answer.setValue(dto.value());
            answer.setNote(dto.note());
            answer.setPhotoUrl(dto.photoUrl());
            instance.getAnswers().add(answer);
        }
        ChecklistTemplate t = templateRepository.findById(instance.getTemplateId()).orElse(null);
        return toInstanceResponse(instanceRepository.save(instance), t);
    }

    public ChecklistInstanceResponse submit(UUID projectId, UUID id) {
        ChecklistInstance instance = findOrThrow(projectId, id);
        if (instance.getStatus() != ChecklistStatus.IN_PROGRESS) {
            throw new BusinessRuleException("CHECKLIST_INVALID_TRANSITION",
                    "Only IN_PROGRESS checklists may be submitted");
        }
        instance.setStatus(ChecklistStatus.SUBMITTED);
        instance.setSubmittedAt(Instant.now());
        ChecklistTemplate t = templateRepository.findById(instance.getTemplateId()).orElse(null);
        return toInstanceResponse(instanceRepository.save(instance), t);
    }

    public ChecklistInstanceResponse approve(UUID projectId, UUID id, ChecklistDecisionRequest req) {
        return decide(projectId, id, ChecklistStatus.APPROVED);
    }

    public ChecklistInstanceResponse reject(UUID projectId, UUID id, ChecklistDecisionRequest req) {
        return decide(projectId, id, ChecklistStatus.REJECTED);
    }

    private ChecklistInstanceResponse decide(UUID projectId, UUID id, ChecklistStatus to) {
        ChecklistInstance instance = findOrThrow(projectId, id);
        if (instance.getStatus() != ChecklistStatus.SUBMITTED) {
            throw new BusinessRuleException("CHECKLIST_INVALID_TRANSITION",
                    "Only SUBMITTED checklists may be approved or rejected");
        }
        instance.setStatus(to);
        instance.setSignedAt(Instant.now());
        try {
            instance.setSignedBy(securityContext.getCurrentUserId());
        } catch (IllegalStateException ignore) {
            // anonymous
        }
        ChecklistTemplate t = templateRepository.findById(instance.getTemplateId()).orElse(null);
        return toInstanceResponse(instanceRepository.save(instance), t);
    }

    private ChecklistInstance findOrThrow(UUID projectId, UUID id) {
        ChecklistInstance instance = instanceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ChecklistInstance", id));
        if (!instance.getProjectId().equals(projectId)) {
            throw new ResourceNotFoundException("ChecklistInstance", id);
        }
        return instance;
    }

    private ChecklistTemplateResponse toTemplateResponse(ChecklistTemplate t) {
        List<ChecklistTemplateItemDto> items = t.getItems().stream()
                .map(i -> new ChecklistTemplateItemDto(
                        i.getId(),
                        i.getSequence(),
                        i.getLabel(),
                        i.isMandatory(),
                        i.getEvidenceType()))
                .toList();
        return new ChecklistTemplateResponse(
                t.getId(),
                t.getCode(),
                t.getName(),
                t.getType(),
                t.isActive(),
                items
        );
    }

    private ChecklistInstanceResponse toInstanceResponse(ChecklistInstance i, ChecklistTemplate t) {
        List<ChecklistAnswerDto> answers = i.getAnswers().stream()
                .map(a -> new ChecklistAnswerDto(
                        a.getItemId(),
                        a.getValue(),
                        a.getNote(),
                        a.getPhotoUrl()))
                .toList();
        return new ChecklistInstanceResponse(
                i.getId(),
                i.getProjectId(),
                i.getActivityId(),
                i.getTemplateId(),
                t != null ? t.getCode() : null,
                t != null ? t.getName() : null,
                i.getStatus(),
                i.getStartedBy(),
                i.getStartedAt(),
                i.getSubmittedAt(),
                i.getSignedBy(),
                i.getSignedAt(),
                answers,
                i.getCreatedAt(),
                i.getUpdatedAt()
        );
    }
}
