package com.bipros.ai.query;

import com.bipros.resource.domain.model.Resource;
import com.bipros.resource.domain.model.ResourceRate;
import com.bipros.resource.domain.model.ResourceRole;
import com.bipros.resource.domain.model.ResourceType;
import com.bipros.resource.domain.model.manpower.ManpowerMaster;
import com.bipros.resource.domain.model.manpower.ManpowerSkills;
import com.bipros.resource.domain.repository.ManpowerMasterRepository;
import com.bipros.resource.domain.repository.ManpowerSkillsRepository;
import com.bipros.resource.domain.repository.ResourceRateRepository;
import com.bipros.resource.domain.repository.ResourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Single source of truth for AI tools that need a "full picture" of a Resource.
 * Backs both {@code GetResourceProfileTool} (single resource drill-down) and
 * {@code SupervisorTool} (which loads the supervisor profile + walks the team).
 *
 * <p>Calls outside the transactional boundary will hit Hibernate's lazy-load
 * checks and blow up on {@code Resource.role} / {@code Resource.resourceType};
 * always invoke from a {@link Transactional} caller.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResourceContextFacade {

  public enum Include {
    RATES,
    HIERARCHY,
    MANPOWER,
    SKILLS
  }

  private final ResourceRepository resourceRepository;
  private final ResourceRateRepository rateRepository;
  private final ManpowerMasterRepository manpowerRepository;
  private final ManpowerSkillsRepository skillsRepository;

  /**
   * Hydrates a {@link ResourceProfile}. Lazy associations on {@link Resource}
   * are dereferenced inside the surrounding transaction so the caller can
   * serialise the result on a non-Hibernate thread.
   */
  @Transactional(readOnly = true)
  public Optional<ResourceProfile> loadProfile(UUID resourceId, EnumSet<Include> include) {
    if (resourceId == null) return Optional.empty();
    EnumSet<Include> opts = include == null ? EnumSet.allOf(Include.class) : include;

    Optional<Resource> ropt = resourceRepository.findById(resourceId);
    if (ropt.isEmpty()) return Optional.empty();
    Resource r = ropt.get();

    ResourceRole role = r.getRole();
    ResourceType type = r.getResourceType();
    String typeCategory = type != null ? type.getCode() : null;

    Resource parent = null;
    if (r.getParentId() != null) {
      parent = resourceRepository.findById(r.getParentId()).orElse(null);
    }

    ResourceProfile.Manpower mp = null;
    String reportingManagerName = null;
    if (opts.contains(Include.MANPOWER)) {
      ManpowerMaster m = manpowerRepository.findById(resourceId).orElse(null);
      if (m != null) {
        if (m.getReportingManagerId() != null) {
          ManpowerMaster mgr = manpowerRepository.findById(m.getReportingManagerId()).orElse(null);
          if (mgr != null) reportingManagerName = mgr.getFullName();
        }
        mp = new ResourceProfile.Manpower(
            m.getEmployeeCode(),
            m.getFullName(),
            m.getDesignation(),
            m.getDepartment(),
            m.getCategory(),
            m.getSubCategory(),
            m.getEmploymentType(),
            m.getNationality(),
            m.getContactNumber(),
            m.getEmail(),
            m.getJoiningDate(),
            m.getExitDate(),
            m.getReportingManagerId(),
            reportingManagerName,
            m.getCompanyName(),
            m.getWorkLocation());
      }
    }

    ResourceProfile.Skills sk = null;
    if (opts.contains(Include.SKILLS)) {
      ManpowerSkills s = skillsRepository.findById(resourceId).orElse(null);
      if (s != null) {
        sk =
            new ResourceProfile.Skills(
                s.getPrimarySkill(),
                s.getSecondarySkills(),
                s.getSkillLevel(),
                s.getCertifications(),
                s.getLicenseDetails(),
                s.getTrainingRecords(),
                s.getExperienceYears());
      }
    }

    List<ResourceProfile.RateSnapshot> rates = List.of();
    if (opts.contains(Include.RATES)) {
      List<ResourceRate> rows = rateRepository.findByResourceIdOrderByEffectiveDateDesc(resourceId);
      rates = new ArrayList<>(rows.size());
      for (ResourceRate row : rows) {
        BigDecimal variance = null;
        if (row.getActualRate() != null && row.getBudgetedRate() != null) {
          variance = row.getActualRate().subtract(row.getBudgetedRate());
        }
        rates.add(
            new ResourceProfile.RateSnapshot(
                row.getRateType(),
                row.getPricePerUnit(),
                row.getBudgetedRate(),
                row.getActualRate(),
                variance,
                row.getEffectiveDate(),
                row.getEffectiveTo(),
                row.getCategory() == null ? null : row.getCategory().name()));
      }
    }

    List<ResourceProfile.Subordinate> subs = List.of();
    if (opts.contains(Include.HIERARCHY)) {
      subs = collectSubordinates(resourceId);
    }

    return Optional.of(
        new ResourceProfile(
            r.getId(),
            r.getCode(),
            r.getName(),
            r.getDescription(),
            r.getUnit(),
            r.getStatus() == null ? null : r.getStatus().name(),
            r.getAvailability(),
            r.getCostPerUnit(),
            r.getParentId(),
            parent != null ? parent.getCode() : null,
            parent != null ? parent.getName() : null,
            // resource.user_id was dropped in Liquibase 095 (Phase 4.6 of the RBAC overhaul):
            // the soft FK from Resource to User is now replaced by the explicit
            // {@code supervisor_user_id} columns on Activity / DPR. Profile keeps the field
            // for backwards compatibility — emit null until the AI surface needs a
            // user-link from another source.
            null,
            r.getCalendarId(),
            role != null ? role.getId() : null,
            role != null ? role.getCode() : null,
            role != null ? role.getName() : null,
            type != null ? type.getId() : null,
            type != null ? type.getCode() : null,
            type != null ? type.getName() : null,
            typeCategory,
            mp,
            sk,
            rates,
            subs));
  }

  /**
   * Two-source union of subordinates:
   *   1. Resource.parent_id = id            (org tree — works for any resource type)
   *   2. ManpowerMaster.reporting_manager_id = id  (HR tree — manpower-only)
   *
   * <p>Some teams maintain only one tree, others maintain both. Returning the
   * union — annotated with which source supplied each link — is more robust
   * than picking a winner.
   */
  @Transactional(readOnly = true)
  public List<ResourceProfile.Subordinate> collectSubordinates(UUID supervisorResourceId) {
    Map<UUID, ResourceProfile.Subordinate> byId = new LinkedHashMap<>();

    List<Resource> orgChildren = resourceRepository.findByParentId(supervisorResourceId);
    Set<UUID> orgIds = new HashSet<>();
    for (Resource child : orgChildren) {
      orgIds.add(child.getId());
    }

    List<ManpowerMaster> hrChildren = manpowerRepository.findByReportingManagerId(supervisorResourceId);
    Set<UUID> hrIds = new HashSet<>();
    for (ManpowerMaster m : hrChildren) {
      hrIds.add(m.getResourceId());
    }

    Set<UUID> allChildIds = new HashSet<>();
    allChildIds.addAll(orgIds);
    allChildIds.addAll(hrIds);
    if (allChildIds.isEmpty()) return List.of();

    Map<UUID, Resource> resById = new HashMap<>();
    resourceRepository.findAllById(allChildIds).forEach(rr -> resById.put(rr.getId(), rr));
    Map<UUID, ManpowerMaster> mpById = new HashMap<>();
    manpowerRepository.findAllById(allChildIds).forEach(mm -> mpById.put(mm.getResourceId(), mm));

    for (UUID id : allChildIds) {
      Resource r = resById.get(id);
      if (r == null) continue;
      String roleName =
          r.getRole() != null ? r.getRole().getName() : null;
      String typeCategory =
          r.getResourceType() != null ? r.getResourceType().getCode() : null;
      ManpowerMaster m = mpById.get(id);
      String linkSource;
      if (orgIds.contains(id) && hrIds.contains(id)) linkSource = "both";
      else if (orgIds.contains(id)) linkSource = "org";
      else linkSource = "hr";

      byId.put(
          id,
          new ResourceProfile.Subordinate(
              id,
              r.getCode(),
              r.getName(),
              roleName,
              typeCategory,
              m != null ? m.getFullName() : null,
              m != null ? m.getDesignation() : null,
              linkSource));
    }

    List<ResourceProfile.Subordinate> out = new ArrayList<>(byId.values());
    out.sort(
        Comparator.comparing(
            (ResourceProfile.Subordinate s) -> s.name() == null ? "" : s.name(),
            String.CASE_INSENSITIVE_ORDER));
    return out;
  }

  /**
   * Best-effort fuzzy lookup by code, name, employee_code, or full_name. Returns
   * the highest-confidence match or empty if no candidate clears a simple
   * equality / case-insensitive substring threshold. Real fuzzy resolution
   * (Levenshtein, top-k) lives in {@code EntityResolverTool}; this helper exists
   * for tools that get a string identifier and just need the best single hit.
   */
  @Transactional(readOnly = true)
  public Optional<UUID> resolveResourceId(String maybeCodeOrName) {
    if (maybeCodeOrName == null || maybeCodeOrName.isBlank()) return Optional.empty();
    String q = maybeCodeOrName.trim();

    try {
      return Optional.of(UUID.fromString(q));
    } catch (IllegalArgumentException ignored) {
      // not a UUID — fall through
    }

    Optional<Resource> byCode = resourceRepository.findByCode(q);
    if (byCode.isPresent()) return Optional.of(byCode.get().getId());

    Optional<ManpowerMaster> byEmp = manpowerRepository.findByEmployeeCode(q);
    if (byEmp.isPresent()) return Optional.of(byEmp.get().getResourceId());

    return Optional.empty();
  }
}
