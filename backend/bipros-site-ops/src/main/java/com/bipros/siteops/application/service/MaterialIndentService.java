package com.bipros.siteops.application.service;

import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.security.application.service.CurrentUserService;
import com.bipros.siteops.application.dto.CreateMaterialIndentRequest;
import com.bipros.siteops.application.dto.IndentDecisionRequest;
import com.bipros.siteops.application.dto.MaterialIndentItemDto;
import com.bipros.siteops.application.dto.MaterialIndentResponse;
import com.bipros.siteops.application.dto.UpdateMaterialIndentRequest;
import com.bipros.siteops.domain.model.IndentStatus;
import com.bipros.siteops.domain.model.MaterialIndent;
import com.bipros.siteops.domain.model.MaterialIndentItem;
import com.bipros.siteops.domain.repository.MaterialIndentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class MaterialIndentService {

    private final MaterialIndentRepository indentRepository;
    private final CurrentUserService securityContext;

    public MaterialIndentResponse create(UUID projectId, CreateMaterialIndentRequest req) {
        MaterialIndent indent = new MaterialIndent();
        indent.setProjectId(projectId);
        indent.setRequiredBy(req.requiredBy());
        indent.setNotes(req.notes());
        indent.setStatus(IndentStatus.DRAFT);
        try {
            indent.setRequestedBy(securityContext.getCurrentUserId());
        } catch (IllegalStateException ignore) {
            // anonymous context — leave null
        }
        indent.setRequestedAt(Instant.now());
        indent.setIndentNo(allocateIndentNo(projectId));
        applyItems(indent, req.items());
        indent = indentRepository.save(indent);
        return toResponse(indent);
    }

    @Transactional(readOnly = true)
    public List<MaterialIndentResponse> list(UUID projectId, IndentStatus status) {
        List<MaterialIndent> rows = status == null
                ? indentRepository.findByProjectIdOrderByCreatedAtDesc(projectId)
                : indentRepository.findByProjectIdAndStatusOrderByCreatedAtDesc(projectId, status);
        return rows.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public MaterialIndentResponse get(UUID projectId, UUID id) {
        MaterialIndent indent = findOrThrow(projectId, id);
        return toResponse(indent);
    }

    public MaterialIndentResponse update(UUID projectId, UUID id, UpdateMaterialIndentRequest req) {
        MaterialIndent indent = findOrThrow(projectId, id);
        if (indent.getStatus() != IndentStatus.DRAFT) {
            throw new BusinessRuleException("INDENT_NOT_DRAFT",
                    "Material indent can only be edited while in DRAFT status");
        }
        if (req.requiredBy() != null) indent.setRequiredBy(req.requiredBy());
        if (req.notes() != null) indent.setNotes(req.notes());
        if (req.items() != null) {
            indent.getItems().clear();
            applyItems(indent, req.items());
        }
        return toResponse(indentRepository.save(indent));
    }

    public MaterialIndentResponse submit(UUID projectId, UUID id) {
        MaterialIndent indent = findOrThrow(projectId, id);
        if (indent.getStatus() != IndentStatus.DRAFT) {
            throw new BusinessRuleException("INDENT_INVALID_TRANSITION",
                    "Only DRAFT indents may be submitted");
        }
        if (indent.getItems().isEmpty()) {
            throw new BusinessRuleException("INDENT_EMPTY",
                    "Cannot submit an indent with no items");
        }
        indent.setStatus(IndentStatus.SUBMITTED);
        return toResponse(indentRepository.save(indent));
    }

    public MaterialIndentResponse approve(UUID projectId, UUID id, IndentDecisionRequest req) {
        return decide(projectId, id, IndentStatus.APPROVED, req);
    }

    public MaterialIndentResponse reject(UUID projectId, UUID id, IndentDecisionRequest req) {
        return decide(projectId, id, IndentStatus.REJECTED, req);
    }

    private MaterialIndentResponse decide(UUID projectId, UUID id, IndentStatus to, IndentDecisionRequest req) {
        MaterialIndent indent = findOrThrow(projectId, id);
        if (indent.getStatus() != IndentStatus.SUBMITTED) {
            throw new BusinessRuleException("INDENT_INVALID_TRANSITION",
                    "Only SUBMITTED indents may be approved or rejected");
        }
        indent.setStatus(to);
        indent.setDecisionNote(req != null ? req.decisionNote() : null);
        indent.setDecidedAt(Instant.now());
        try {
            indent.setDecisionBy(securityContext.getCurrentUserId());
        } catch (IllegalStateException ignore) {
            // leave null
        }
        return toResponse(indentRepository.save(indent));
    }

    private MaterialIndent findOrThrow(UUID projectId, UUID id) {
        MaterialIndent indent = indentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("MaterialIndent", id));
        if (!indent.getProjectId().equals(projectId)) {
            throw new ResourceNotFoundException("MaterialIndent", id);
        }
        return indent;
    }

    private void applyItems(MaterialIndent indent, List<MaterialIndentItemDto> items) {
        if (items == null) return;
        List<MaterialIndentItem> mapped = new ArrayList<>();
        for (MaterialIndentItemDto dto : items) {
            MaterialIndentItem item = new MaterialIndentItem();
            item.setMaterialName(dto.materialName());
            item.setQuantity(dto.quantity());
            item.setUom(dto.uom());
            item.setRemarks(dto.remarks());
            mapped.add(item);
        }
        indent.getItems().addAll(mapped);
    }

    private String allocateIndentNo(UUID projectId) {
        long n = indentRepository.countByProjectId(projectId) + 1;
        String suffix = projectId.toString().substring(0, 8);
        return "IND-%s-%04d".formatted(suffix, n);
    }

    private MaterialIndentResponse toResponse(MaterialIndent indent) {
        List<MaterialIndentItemDto> items = indent.getItems().stream()
                .map(i -> new MaterialIndentItemDto(
                        i.getId(),
                        i.getMaterialName(),
                        i.getQuantity(),
                        i.getUom(),
                        i.getRemarks()))
                .toList();
        return new MaterialIndentResponse(
                indent.getId(),
                indent.getProjectId(),
                indent.getIndentNo(),
                indent.getRequestedBy(),
                indent.getRequestedAt(),
                indent.getRequiredBy(),
                indent.getStatus(),
                indent.getNotes(),
                indent.getDecisionBy(),
                indent.getDecidedAt(),
                indent.getDecisionNote(),
                items.size(),
                items,
                indent.getCreatedAt(),
                indent.getUpdatedAt()
        );
    }
}
