package com.bipros.resource.application.service;

import com.bipros.common.exception.ResourceNotFoundException;
import com.bipros.resource.application.dto.ProductivityCoverageResponse;
import com.bipros.resource.application.dto.ProductivityCoverageResponse.NormSummary;
import com.bipros.resource.application.dto.ProductivityCoverageResponse.Side;
import com.bipros.resource.domain.model.ProductivityNorm;
import com.bipros.resource.domain.model.ProductivityNormType;
import com.bipros.resource.domain.model.WorkActivity;
import com.bipros.resource.domain.repository.ProductivityNormRepository;
import com.bipros.resource.domain.repository.ResourceRoleRepository;
import com.bipros.resource.domain.repository.WorkActivityRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Computes the per-Work-Activity productivity coverage. Read-only; called from both the Activity
 * edit page (chip under the Work Activity picker) and the DPR preview endpoint (so warnings
 * stay honest about what the Work Activity actually tracks).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ProductivityCoverageService {

  private final WorkActivityRepository workActivityRepository;
  private final ProductivityNormRepository normRepository;
  private final ResourceRoleRepository roleRepository;

  @PersistenceContext private EntityManager em;

  public ProductivityCoverageResponse coverage(UUID workActivityId) {
    WorkActivity wa = workActivityRepository.findById(workActivityId)
        .orElseThrow(() -> new ResourceNotFoundException("WorkActivity", workActivityId));
    List<ProductivityNorm> norms = normRepository.findByWorkActivityId(workActivityId);

    // Pre-fetch lookup labels for role/category/grade once so we don't do N+1.
    Map<UUID, String> roleLabels = loadRoleLabels(norms);
    Map<UUID, String> categoryLabels = loadCategoryLabels(norms);
    Map<UUID, String> gradeLabels = loadGradeLabels(norms);

    List<NormSummary> manpower = new ArrayList<>();
    List<NormSummary> equipment = new ArrayList<>();
    for (ProductivityNorm n : norms) {
      NormSummary summary = summarise(n, roleLabels, categoryLabels, gradeLabels);
      if (n.getNormType() == ProductivityNormType.MANPOWER) manpower.add(summary);
      else if (n.getNormType() == ProductivityNormType.EQUIPMENT) equipment.add(summary);
    }

    boolean hasManpower = !manpower.isEmpty();
    boolean hasEquipment = !equipment.isEmpty();
    String summaryLabel;
    if (hasManpower && hasEquipment) summaryLabel = "BOTH";
    else if (hasManpower) summaryLabel = "MANPOWER_ONLY";
    else if (hasEquipment) summaryLabel = "EQUIPMENT_ONLY";
    else summaryLabel = "NONE";

    return new ProductivityCoverageResponse(
        wa.getId(),
        wa.getName(),
        wa.getDefaultUnit(),
        new Side(hasManpower, manpower),
        new Side(hasEquipment, equipment),
        summaryLabel);
  }

  private NormSummary summarise(
      ProductivityNorm n,
      Map<UUID, String> roleLabels,
      Map<UUID, String> categoryLabels,
      Map<UUID, String> gradeLabels) {
    String scope;
    String label;
    if (n.getRoleId() != null) {
      boolean isVariant = n.getCategoryId() != null
          || n.getGradeId() != null
          || (n.getMake() != null && !n.getMake().isBlank())
          || (n.getModel() != null && !n.getModel().isBlank());
      scope = isVariant ? "VARIANT" : "ROLE";
      label = describeRoleScope(n, roleLabels, categoryLabels, gradeLabels);
    } else if (n.getResource() != null) {
      scope = "UNSCOPED"; // legacy specific-resource shown as unscoped fallback
      label = n.getResource().getName() + " (legacy resource)";
    } else if (n.getResourceType() != null) {
      scope = "UNSCOPED"; // legacy type — same UI bucket
      label = n.getResourceType().getName() + " (legacy type)";
    } else {
      scope = "UNSCOPED";
      // The Equipment unscoped tier often uses equipment_spec as the descriptive label.
      label = n.getEquipmentSpec() != null && !n.getEquipmentSpec().isBlank()
          ? n.getEquipmentSpec()
          : "Unscoped";
    }
    return new NormSummary(
        scope, label,
        n.getOutputPerDay(),
        n.getOutputPerManPerDay(),
        n.getWorkingHoursPerDay());
  }

  private String describeRoleScope(
      ProductivityNorm n,
      Map<UUID, String> roleLabels,
      Map<UUID, String> categoryLabels,
      Map<UUID, String> gradeLabels) {
    StringBuilder sb = new StringBuilder();
    sb.append(roleLabels.getOrDefault(n.getRoleId(), "(unknown role)"));
    if (n.getCategoryId() != null) {
      sb.append(" / ").append(categoryLabels.getOrDefault(n.getCategoryId(), "(category)"));
    }
    if (n.getGradeId() != null) {
      sb.append(" / ").append(gradeLabels.getOrDefault(n.getGradeId(), "(grade)"));
    }
    if (n.getMake() != null && !n.getMake().isBlank()) {
      sb.append(" — ").append(n.getMake());
      if (n.getModel() != null && !n.getModel().isBlank()) sb.append(" ").append(n.getModel());
    } else if (n.getModel() != null && !n.getModel().isBlank()) {
      sb.append(" — ").append(n.getModel());
    }
    return sb.toString();
  }

  private Map<UUID, String> loadRoleLabels(List<ProductivityNorm> norms) {
    List<UUID> ids = norms.stream()
        .map(ProductivityNorm::getRoleId)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
    if (ids.isEmpty()) return Map.of();
    Map<UUID, String> out = new HashMap<>();
    roleRepository.findAllById(ids).forEach(r -> out.put(r.getId(), r.getName()));
    return out;
  }

  @SuppressWarnings("unchecked")
  private Map<UUID, String> loadCategoryLabels(List<ProductivityNorm> norms) {
    List<UUID> ids = norms.stream()
        .map(ProductivityNorm::getCategoryId)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
    if (ids.isEmpty()) return Map.of();
    Map<UUID, String> out = new HashMap<>();
    List<Object[]> rows = em.createNativeQuery(
            "SELECT id, name FROM resource.manpower_category_master WHERE id IN :ids")
        .setParameter("ids", ids)
        .getResultList();
    for (Object[] r : rows) out.put((UUID) r[0], (String) r[1]);
    return out;
  }

  @SuppressWarnings("unchecked")
  private Map<UUID, String> loadGradeLabels(List<ProductivityNorm> norms) {
    List<UUID> ids = norms.stream()
        .map(ProductivityNorm::getGradeId)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
    if (ids.isEmpty()) return Map.of();
    Map<UUID, String> out = new HashMap<>();
    List<Object[]> rows = em.createNativeQuery(
            "SELECT id, name FROM resource.grade_master WHERE id IN :ids")
        .setParameter("ids", ids)
        .getResultList();
    for (Object[] r : rows) out.put((UUID) r[0], (String) r[1]);
    return out;
  }
}
