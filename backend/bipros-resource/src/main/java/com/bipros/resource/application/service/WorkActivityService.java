package com.bipros.resource.application.service;

import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.util.AuditService;
import com.bipros.resource.application.dto.CreateWorkActivityRequest;
import com.bipros.resource.application.dto.WorkActivityResponse;
import com.bipros.resource.domain.model.NormCombination;
import com.bipros.resource.domain.model.WorkActivity;
import com.bipros.resource.domain.repository.ProductivityNormRepository;
import com.bipros.resource.domain.repository.WorkActivityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class WorkActivityService {

  private final WorkActivityRepository repository;
  private final ProductivityNormRepository normRepository;
  private final AuditService auditService;

  public WorkActivityResponse create(CreateWorkActivityRequest request) {
    String code = resolveCode(request.code(), request.name());
    if (repository.findByCode(code).isPresent()) {
      throw new BusinessRuleException("DUPLICATE_WORK_ACTIVITY_CODE",
          "Work activity with code " + code + " already exists");
    }
    WorkActivity wa = WorkActivity.builder()
        .code(code)
        .name(request.name().trim())
        .defaultUnit(trimToNull(request.defaultUnit()))
        .discipline(trimToNull(request.discipline()))
        .description(trimToNull(request.description()))
        .sortOrder(request.sortOrder())
        .active(request.active() == null ? Boolean.TRUE : request.active())
        .normCombination(request.normCombination() == null ? NormCombination.SERIES : request.normCombination())
        .build();
    WorkActivity saved = repository.save(wa);
    auditService.logCreate("WorkActivity", saved.getId(), WorkActivityResponse.from(saved));
    log.info("Created WorkActivity id={} code={}", saved.getId(), saved.getCode());
    return WorkActivityResponse.from(saved);
  }

  public WorkActivityResponse update(UUID id, CreateWorkActivityRequest request) {
    WorkActivity wa = require(id);

    String requestedCode = request.code() == null ? null : slug(request.code());
    if (requestedCode != null && !requestedCode.equals(wa.getCode())
        && repository.findByCode(requestedCode).isPresent()) {
      throw new BusinessRuleException("DUPLICATE_WORK_ACTIVITY_CODE",
          "Work activity with code " + requestedCode + " already exists");
    }
    if (requestedCode != null) {
      wa.setCode(requestedCode);
    }
    wa.setName(request.name().trim());
    wa.setDefaultUnit(trimToNull(request.defaultUnit()));
    wa.setDiscipline(trimToNull(request.discipline()));
    wa.setDescription(trimToNull(request.description()));
    wa.setSortOrder(request.sortOrder());
    if (request.active() != null) {
      wa.setActive(request.active());
    }
    if (request.normCombination() != null) {
      wa.setNormCombination(request.normCombination());
    }
    WorkActivity updated = repository.save(wa);
    auditService.logUpdate("WorkActivity", id, "workActivity", null, WorkActivityResponse.from(updated));
    return WorkActivityResponse.from(updated);
  }

  public void delete(UUID id) {
    WorkActivity wa = require(id);
    long norms = normRepository.countByWorkActivityId(id);
    if (norms > 0) {
      throw new BusinessRuleException("WORK_ACTIVITY_IN_USE",
          "Work activity '" + wa.getName() + "' is referenced by " + norms
              + " productivity norm(s); soft-delete by setting active=false instead");
    }
    repository.delete(wa);
    auditService.logDelete("WorkActivity", id);
  }

  public void deleteAll() {
    List<WorkActivity> all = repository.findAll();
    int deletedCount = 0;
    int skippedCount = 0;
    for (WorkActivity wa : all) {
      long norms = normRepository.countByWorkActivityId(wa.getId());
      if (norms > 0) {
        skippedCount++;
        continue;
      }
      repository.delete(wa);
      deletedCount++;
    }
    log.info("Deleted {} work activities, skipped {} (referenced by productivity norms)",
        deletedCount, skippedCount);
    auditService.logDelete("WorkActivity", null);
  }

  @Transactional(readOnly = true)
  public WorkActivityResponse get(UUID id) {
    return WorkActivityResponse.from(require(id));
  }

  @Transactional(readOnly = true)
  public List<WorkActivityResponse> list(Boolean activeOnly) {
    List<WorkActivity> rows = Boolean.TRUE.equals(activeOnly)
        ? repository.findByActive(Boolean.TRUE)
        : repository.findAll();
    return rows.stream()
        .sorted(displayOrder())
        .map(WorkActivityResponse::from)
        .toList();
  }

  /** Resolve-or-create by name — used by seeders. */
  public WorkActivity findOrCreateByName(String name, String defaultUnit) {
    Optional<WorkActivity> existing = repository.findByNameIgnoreCase(name);
    if (existing.isPresent()) {
      return existing.get();
    }
    String code = slug(name);
    String unique = code;
    int suffix = 2;
    while (repository.findByCode(unique).isPresent()) {
      unique = code + "_" + suffix++;
    }
    WorkActivity wa = WorkActivity.builder()
        .code(unique)
        .name(name.trim())
        .defaultUnit(defaultUnit)
        .active(Boolean.TRUE)
        .build();
    return repository.save(wa);
  }

  public WorkActivity require(UUID id) {
    return repository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("WorkActivity", id));
  }

  private String resolveCode(String requested, String name) {
    if (requested != null && !requested.trim().isEmpty()) {
      return slug(requested);
    }
    return slug(name);
  }

  private static String slug(String raw) {
    String upper = raw.trim().toUpperCase();
    String collapsed = upper.replaceAll("[^A-Z0-9]+", "_");
    String trimmed = collapsed.replaceAll("^_+|_+$", "");
    return trimmed.length() > 50 ? trimmed.substring(0, 50) : trimmed;
  }

  private static String trimToNull(String s) {
    if (s == null) return null;
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }

  private static Comparator<WorkActivity> displayOrder() {
    Comparator<WorkActivity> bySort = Comparator.comparing(
        WorkActivity::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()));
    return bySort.thenComparing(WorkActivity::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
  }
}
