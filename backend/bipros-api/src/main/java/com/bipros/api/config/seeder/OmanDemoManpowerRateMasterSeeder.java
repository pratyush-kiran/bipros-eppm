package com.bipros.api.config.seeder;

import com.bipros.resource.domain.model.GradeMaster;
import com.bipros.resource.domain.model.ResourceRole;
import com.bipros.resource.domain.model.ResourceType;
import com.bipros.resource.domain.model.master.ManpowerCategoryMaster;
import com.bipros.resource.domain.model.rate.ManpowerRateMaster;
import com.bipros.resource.domain.repository.GradeMasterRepository;
import com.bipros.resource.domain.repository.ManpowerCategoryMasterRepository;
import com.bipros.resource.domain.repository.ManpowerRateMasterRepository;
import com.bipros.resource.domain.repository.ResourceRoleRepository;
import com.bipros.resource.domain.repository.ResourceTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Seeds {@link ManpowerRateMaster} rows for the standard OMAN-DEMO-KHASAB construction trade set
 * so DBS / EVM / Cost computations have a non-empty rate catalogue to price labour against.
 *
 * <p>The existing {@link ManpowerRateMasterBackfillSeeder} only fires when prior Manpower
 * {@code Resource} rows exist (a backfill use-case). On a fresh database with no legacy
 * resources the rate-master table stays empty and downstream Daily Balance Sheet / Daily Cost
 * Report calculations resolve to zero. This seeder closes that gap with a small,
 * construction-PM-realistic seed deck.
 *
 * <p>Idempotent: skips entirely if the rate-master table already has rows. Each row is keyed on
 * (roleId, categoryId, gradeId) so individual re-inserts also no-op via the unique constraint.
 *
 * <p>Rates are expressed in <b>Omani Rial (OMR) per day</b>:
 * <ul>
 *   <li>Helpers / unskilled labour — OMR 8 – 15 / day</li>
 *   <li>Skilled trades — OMR 18 – 40 / day</li>
 *   <li>Supervisory / engineering staff — OMR 35 – 80 / day</li>
 * </ul>
 *
 * <p>Runs as an {@link ApplicationReadyEvent} listener (later than CommandLineRunner) so that
 * {@link ManpowerMasterSeeder} (also an event listener) has already created the
 * Skilled / Unskilled / Staff categories. Uses a HIGHEST_PRECEDENCE-style large order to ensure
 * it runs after the category seeder regardless of bean discovery order.
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class OmanDemoManpowerRateMasterSeeder {

  private static final String MANPOWER_TYPE_CODE = "MANPOWER";
  private static final String LEGACY_LABOR_TYPE_CODE = "LABOR";
  private static final String DEFAULT_GRADE_CODE = "A";
  private static final String DEFAULT_UNIT = "Day";

  /** (role-code, role-display-name, category-name, daily rate in OMR). */
  private static final List<Seed> SEED_ROWS = List.of(
      // Skilled trades — OMR 18-40 / day
      new Seed("OMN-MASON",       "Mason",            "Skilled",   new BigDecimal("28.00")),
      new Seed("OMN-CARPENTER",   "Carpenter",        "Skilled",   new BigDecimal("30.00")),
      new Seed("OMN-STEEL-FIXER", "Steel Fixer",      "Skilled",   new BigDecimal("32.00")),
      new Seed("OMN-WELDER",      "Welder",           "Skilled",   new BigDecimal("35.00")),
      new Seed("OMN-ELECTRICIAN", "Electrician",      "Skilled",   new BigDecimal("38.00")),
      new Seed("OMN-PLUMBER",     "Plumber",          "Skilled",   new BigDecimal("32.00")),
      new Seed("OMN-PAINTER",     "Painter",          "Skilled",   new BigDecimal("22.00")),
      new Seed("OMN-OPERATOR",    "Equipment Operator","Skilled",  new BigDecimal("40.00")),
      new Seed("OMN-DRIVER",      "Driver",           "Skilled",   new BigDecimal("25.00")),

      // Unskilled — OMR 8-15 / day
      new Seed("OMN-HELPER",      "Helper",           "Unskilled", new BigDecimal("12.00")),
      new Seed("OMN-LOADER",      "Loader",           "Unskilled", new BigDecimal("10.00")),
      new Seed("OMN-CLEANER",     "Cleaner",          "Unskilled", new BigDecimal("9.00")),

      // Staff / supervisory — OMR 35-80 / day
      new Seed("OMN-FOREMAN",     "Foreman",          "Staff",     new BigDecimal("55.00")),
      new Seed("OMN-SUPERVISOR",  "Supervisor",       "Staff",     new BigDecimal("60.00")),
      new Seed("OMN-SITE-ENGR",   "Site Engineer",    "Staff",     new BigDecimal("75.00")),
      new Seed("OMN-SAFETY-OFCR", "Safety Officer",   "Staff",     new BigDecimal("65.00")));

  private final ManpowerRateMasterRepository rateRepository;
  private final ResourceRoleRepository roleRepository;
  private final ResourceTypeRepository resourceTypeRepository;
  private final ManpowerCategoryMasterRepository categoryRepository;
  private final GradeMasterRepository gradeRepository;

  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  public void seed() {
    if (rateRepository.count() > 0) {
      log.debug("[OmanDemoManpowerRateMasterSeeder] manpower_rate_masters not empty — skipping");
      return;
    }

    ResourceType manpowerType = resolveManpowerType();
    if (manpowerType == null) {
      log.warn("[OmanDemoManpowerRateMasterSeeder] no MANPOWER/LABOR ResourceType found — aborting");
      return;
    }

    GradeMaster gradeA = ensureGradeA();

    int inserted = 0;
    int skipped = 0;
    for (Seed s : SEED_ROWS) {
      ManpowerCategoryMaster category = ensureTopCategory(s.categoryName);
      ResourceRole role = ensureRole(s.roleCode, s.roleName, manpowerType);

      if (rateRepository
          .findByRoleIdAndCategoryIdAndGradeId(role.getId(), category.getId(), gradeA.getId())
          .isPresent()) {
        skipped++;
        continue;
      }

      rateRepository.save(ManpowerRateMaster.builder()
          .roleId(role.getId())
          .categoryId(category.getId())
          .gradeId(gradeA.getId())
          .unit(DEFAULT_UNIT)
          .rate(s.dailyRateOmr)
          .active(true)
          .build());
      inserted++;
    }

    log.info("[OmanDemoManpowerRateMasterSeeder] seeded {} manpower rate-master rows "
        + "(skipped {} pre-existing) — currency OMR, unit Day", inserted, skipped);
  }

  private ResourceType resolveManpowerType() {
    return resourceTypeRepository.findByCode(MANPOWER_TYPE_CODE)
        .or(() -> resourceTypeRepository.findByCode(LEGACY_LABOR_TYPE_CODE))
        .orElse(null);
  }

  private GradeMaster ensureGradeA() {
    return gradeRepository.findByCode(DEFAULT_GRADE_CODE).orElseGet(() ->
        gradeRepository.save(GradeMaster.builder()
            .code(DEFAULT_GRADE_CODE)
            .name("Grade A")
            .description("Default grade for seeded manpower rate-master rows")
            .sortOrder(10)
            .active(true)
            .build()));
  }

  private ManpowerCategoryMaster ensureTopCategory(String name) {
    Optional<ManpowerCategoryMaster> existing = categoryRepository.findByName(name);
    if (existing.isPresent()) {
      return existing.get();
    }
    String code = "MC-" + name.toUpperCase().replaceAll("[^A-Z0-9]", "-");
    return categoryRepository.save(ManpowerCategoryMaster.builder()
        .code(code)
        .name(name)
        .description("Auto-seeded by OmanDemoManpowerRateMasterSeeder")
        .parentId(null)
        .sortOrder(100)
        .active(true)
        .build());
  }

  private ResourceRole ensureRole(String code, String displayName, ResourceType type) {
    return roleRepository.findByCode(code).orElseGet(() ->
        roleRepository.save(ResourceRole.builder()
            .code(code)
            .name(displayName)
            .description("Auto-seeded by OmanDemoManpowerRateMasterSeeder")
            .resourceType(type)
            .productivityUnit(DEFAULT_UNIT)
            .sortOrder(0)
            .active(true)
            .build()));
  }

  private record Seed(String roleCode, String roleName, String categoryName, BigDecimal dailyRateOmr) {}
}
