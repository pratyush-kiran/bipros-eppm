package com.bipros.resource.application.service;

import com.bipros.common.dto.PagedResponse;
import com.bipros.resource.application.dto.LabourCategoryReference;
import com.bipros.resource.application.dto.LabourDesignationRequest;
import com.bipros.resource.application.dto.LabourDesignationResponse;
import com.bipros.resource.application.dto.LabourGradeReference;
import com.bipros.resource.domain.model.LabourCategory;
import com.bipros.resource.domain.model.LabourDesignation;
import com.bipros.resource.domain.model.LabourGrade;
import com.bipros.resource.domain.model.LabourStatus;
import com.bipros.resource.domain.repository.LabourDesignationRepository;
import com.bipros.resource.domain.repository.ProjectLabourDeploymentRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LabourDesignationService {

    private final LabourDesignationRepository designationRepo;
    private final ProjectLabourDeploymentRepository deploymentRepo;

    @Transactional
    public LabourDesignationResponse create(LabourDesignationRequest req) {
        validatePrefixMatchesCategory(req.code(), req.category());
        if (designationRepo.existsByCode(req.code())) {
            throw new IllegalStateException("Designation code already exists: " + req.code());
        }
        LabourDesignation entity = LabourDesignation.builder()
            .code(req.code())
            .designation(req.designation())
            .category(req.category())
            .trade(req.trade())
            .grade(req.grade())
            .nationality(req.nationality())
            .experienceYearsMin(req.experienceYearsMin())
            .defaultDailyRate(req.defaultDailyRate())
            .currency(req.currency() == null ? "OMR" : req.currency())
            .skills(req.skills() == null ? List.of() : req.skills())
            .certifications(req.certifications() == null ? List.of() : req.certifications())
            .keyRoleSummary(req.keyRoleSummary())
            .status(req.status() == null ? LabourStatus.ACTIVE : req.status())
            .sortOrder(req.sortOrder() == null ? 0 : req.sortOrder())
            .build();
        return toResponse(designationRepo.save(entity));
    }

    @Transactional
    public LabourDesignationResponse update(UUID id, LabourDesignationRequest req) {
        validatePrefixMatchesCategory(req.code(), req.category());
        LabourDesignation existing = designationRepo.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("LabourDesignation not found: " + id));
        if (!existing.getCode().equals(req.code()) && designationRepo.existsByCode(req.code())) {
            throw new IllegalStateException("Designation code already exists: " + req.code());
        }
        existing.setCode(req.code());
        existing.setDesignation(req.designation());
        existing.setCategory(req.category());
        existing.setTrade(req.trade());
        existing.setGrade(req.grade());
        existing.setNationality(req.nationality());
        existing.setExperienceYearsMin(req.experienceYearsMin());
        existing.setDefaultDailyRate(req.defaultDailyRate());
        if (req.currency() != null) existing.setCurrency(req.currency());
        if (req.skills() != null) existing.setSkills(req.skills());
        if (req.certifications() != null) existing.setCertifications(req.certifications());
        existing.setKeyRoleSummary(req.keyRoleSummary());
        if (req.status() != null) existing.setStatus(req.status());
        if (req.sortOrder() != null) existing.setSortOrder(req.sortOrder());
        return toResponse(designationRepo.save(existing));
    }

    @Transactional(readOnly = true)
    public LabourDesignationResponse get(UUID id) {
        return toResponse(designationRepo.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("LabourDesignation not found: " + id)));
    }

    @Transactional(readOnly = true)
    public LabourDesignationResponse getByCode(String code) {
        return toResponse(designationRepo.findByCode(code)
            .orElseThrow(() -> new EntityNotFoundException("LabourDesignation not found: " + code)));
    }

    @Transactional(readOnly = true)
    public PagedResponse<LabourDesignationResponse> search(
            LabourCategory category, LabourGrade grade, LabourStatus status, String q, Pageable pageable) {
        // Normalise q: null → "" so the JPQL `:q = ''` guard fires correctly.
        // Passing null causes PostgreSQL to infer LOWER(CONCAT('%', NULL, '%')) as bytea,
        // which fails with "function lower(bytea) does not exist".
        String safeQ = (q == null) ? "" : q.strip();
        Page<LabourDesignation> page = designationRepo.search(category, grade, status, safeQ, pageable);
        List<LabourDesignationResponse> rows = page.getContent().stream().map(this::toResponse).toList();
        return PagedResponse.of(rows, page.getTotalElements(), page.getTotalPages(),
            page.getNumber(), page.getSize());
    }

    @Transactional
    public void delete(UUID id) {
        LabourDesignation existing = designationRepo.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("LabourDesignation not found: " + id));
        if (deploymentRepo.existsByDesignationId(id)) {
            existing.setStatus(LabourStatus.INACTIVE);
            designationRepo.save(existing);
            return;
        }
        designationRepo.delete(existing);
    }

    public List<LabourCategoryReference> listCategories() {
        return Arrays.stream(LabourCategory.values())
            .map(c -> new LabourCategoryReference(c, c.getCodePrefix(), c.getDisplayName()))
            .toList();
    }

    public List<LabourGradeReference> listGrades() {
        return Arrays.stream(LabourGrade.values())
            .map(g -> new LabourGradeReference(g, g.getClassification(),
                                               g.getDailyRateRange(), g.getDescription()))
            .toList();
    }

    // ── helpers ─────────────────────────────────────────────────

    private void validatePrefixMatchesCategory(String code, LabourCategory category) {
        if (code == null || code.length() < 3) {
            throw new IllegalArgumentException("Invalid code: " + code);
        }
        String prefix = code.substring(0, 2);
        if (!prefix.equals(category.getCodePrefix())) {
            throw new IllegalArgumentException(
                "Code prefix '" + prefix + "' does not match category " + category);
        }
    }

    LabourDesignationResponse toResponse(LabourDesignation d) {
        return new LabourDesignationResponse(
            d.getId(), d.getCode(), d.getDesignation(), d.getCategory(),
            d.getCategory() == null ? null : d.getCategory().getDisplayName(),
            d.getCategory() == null ? null : d.getCategory().getCodePrefix(),
            d.getTrade(), d.getGrade(), d.getNationality(),
            d.getExperienceYearsMin(), d.getDefaultDailyRate(), d.getCurrency(),
            d.getSkills(), d.getCertifications(), d.getKeyRoleSummary(),
            d.getStatus(), d.getSortOrder(),
            null /* deployment block populated by deployment service */);
    }
}
