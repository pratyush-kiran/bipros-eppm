package com.bipros.resource.application.service;

import com.bipros.common.exception.BusinessRuleException;
import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.common.util.AuditService;
import com.bipros.resource.application.dto.ExpenseCategoryRequest;
import com.bipros.resource.application.dto.ExpenseCategoryResponse;
import com.bipros.resource.domain.model.ExpenseCategory;
import com.bipros.resource.domain.repository.ExpenseCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class ExpenseCategoryService {

  private final ExpenseCategoryRepository repository;
  private final AuditService auditService;

  @Transactional(readOnly = true)
  public List<ExpenseCategoryResponse> list(boolean activeOnly) {
    List<ExpenseCategory> rows = activeOnly
        ? repository.findByActiveTrue()
        : repository.findAll();
    return rows.stream()
        .sorted(displayOrder())
        .map(ExpenseCategoryResponse::from)
        .toList();
  }

  @Transactional(readOnly = true)
  public List<ExpenseCategoryResponse> listBySection(String dbsSection) {
    return repository.findByDbsSectionAndActiveTrue(dbsSection).stream()
        .sorted(displayOrder())
        .map(ExpenseCategoryResponse::from)
        .toList();
  }

  @Transactional(readOnly = true)
  public ExpenseCategoryResponse get(UUID id) {
    ExpenseCategory c = repository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("ExpenseCategory", id));
    return ExpenseCategoryResponse.from(c);
  }

  public ExpenseCategoryResponse create(ExpenseCategoryRequest req) {
    String code = req.code().trim().toUpperCase();
    if (repository.findByCode(code).isPresent()) {
      throw new BusinessRuleException("DUPLICATE_EXPENSE_CATEGORY_CODE",
          "Expense category with code '" + code + "' already exists");
    }

    ExpenseCategory c = ExpenseCategory.builder()
        .code(code)
        .name(req.name().trim())
        .description(req.description())
        .dbsSection(req.dbsSection().toUpperCase())
        .defaultTaxable(req.defaultTaxable() != null ? req.defaultTaxable() : Boolean.FALSE)
        .defaultTaxRatePct(req.defaultTaxRatePct())
        .sortOrder(req.sortOrder() == null ? 0 : req.sortOrder())
        .active(req.active() == null ? Boolean.TRUE : req.active())
        .build();

    ExpenseCategory saved = repository.save(c);
    auditService.logCreate("ExpenseCategory", saved.getId(), ExpenseCategoryResponse.from(saved));
    log.info("ExpenseCategory created code={} dbsSection={}", saved.getCode(), saved.getDbsSection());
    return ExpenseCategoryResponse.from(saved);
  }

  public ExpenseCategoryResponse update(UUID id, ExpenseCategoryRequest req) {
    ExpenseCategory c = repository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("ExpenseCategory", id));

    String code = req.code().trim().toUpperCase();
    if (!c.getCode().equals(code) && repository.findByCode(code).isPresent()) {
      throw new BusinessRuleException("DUPLICATE_EXPENSE_CATEGORY_CODE",
          "Expense category with code '" + code + "' already exists");
    }

    c.setCode(code);
    c.setName(req.name().trim());
    c.setDescription(req.description());
    c.setDbsSection(req.dbsSection().toUpperCase());
    if (req.defaultTaxable() != null) c.setDefaultTaxable(req.defaultTaxable());
    c.setDefaultTaxRatePct(req.defaultTaxRatePct());
    if (req.sortOrder() != null) c.setSortOrder(req.sortOrder());
    if (req.active() != null) c.setActive(req.active());

    ExpenseCategory saved = repository.save(c);
    auditService.logUpdate("ExpenseCategory", id, "expenseCategory", null,
        ExpenseCategoryResponse.from(saved));
    return ExpenseCategoryResponse.from(saved);
  }

  public void delete(UUID id) {
    ExpenseCategory c = repository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("ExpenseCategory", id));

    // We do not block delete on usage today because DBS expense entries reference categories
    // by FK with restrict — Postgres will reject the delete itself if rows exist. Caller can
    // soft-delete by toggling active=false instead.
    repository.delete(c);
    auditService.logDelete("ExpenseCategory", id);
    log.info("ExpenseCategory deleted code={}", c.getCode());
  }

  private static Comparator<ExpenseCategory> displayOrder() {
    Comparator<ExpenseCategory> bySort = Comparator.comparing(
        ExpenseCategory::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()));
    return bySort.thenComparing(ExpenseCategory::getName,
        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
  }
}
