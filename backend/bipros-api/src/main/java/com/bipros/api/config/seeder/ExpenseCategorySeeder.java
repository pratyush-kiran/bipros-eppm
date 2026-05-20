package com.bipros.api.config.seeder;

import com.bipros.resource.domain.model.ExpenseCategory;
import com.bipros.resource.domain.repository.ExpenseCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Seeds the default ad-hoc expense categories that drive the DBS expense capture.
 * Idempotent: skips if any categories already exist (so admins who customised the
 * list aren't overwritten on every boot).
 *
 * <p>Mirrors the Liquibase changeset {@code changeset-2026-05-20-expense-categories.xml}
 * for dev environments where Liquibase is disabled and JPA only creates the schema —
 * without this seeder the table is empty on startup and the Add Expense modal can't
 * offer any categories.
 *
 * <p>Each category maps to a DBS section letter so manually-entered expenses bucket
 * into the right section at read time. See {@code DbsAggregationService.loadManualBuckets}.
 */
@Component
@Order(58)
@RequiredArgsConstructor
@Slf4j
public class ExpenseCategorySeeder implements CommandLineRunner {

  private final ExpenseCategoryRepository repository;

  @Override
  @Transactional
  public void run(String... args) {
    if (repository.count() > 0) {
      log.info("Expense categories already seeded, skipping");
      return;
    }

    List<ExpenseCategory> categories = List.of(
        create("TRANSPORT",      "Transport / Vehicle hire",        "Diesel coupons, taxi fares, lorry hire, parking, tolls",                 "G", false, null,                            10),
        create("REPAIR",         "Emergency repair / Maintenance",  "Unplanned machinery / tool repair, spare-part purchase",                  "G", false, null,                            20),
        create("CATERING",       "Catering / Hospitality",          "Site visit refreshments, one-off worker meal, water/tea",                 "B", true,  new BigDecimal("5.00"),          30),
        create("SAFETY",         "Safety / PPE / First-aid",        "Helmets, gloves, first-aid kit, hospital transport",                      "G", false, null,                            40),
        create("SITE_OFFICE",    "Site office",                     "Stationery, internet, photocopy, courier",                                "G", true,  new BigDecimal("18.00"),         50),
        create("LOCAL_PURCHASE", "Local material purchase",         "Material bought on emergency, not in resource plan",                      "E", true,  new BigDecimal("18.00"),         60),
        create("ADHOC_FUEL",     "Petty fuel coupons (non-DPR)",    "Fuel purchased outside DPR flow",                                         "D", false, null,                            65),
        create("SUBCONTRACT",    "Ad-hoc subcontract work",         "One-off subcontracted service (plumbing, painting, hauling)",             "F", true,  new BigDecimal("18.00"),         70),
        create("HONORARIUM",     "Worker incentives / Festive",     "Bonus, festival gifts, one-off worker reward",                            "G", false, null,                            80),
        create("MISC",           "Miscellaneous",                   "Catch-all for ad-hoc expenses that don't fit another category",           "G", false, null,                            99)
    );

    repository.saveAll(categories);
    log.info("Seeded {} expense category master records", categories.size());
  }

  private ExpenseCategory create(String code, String name, String description, String section,
                                  boolean defaultTaxable, BigDecimal defaultTaxRatePct, int sortOrder) {
    return ExpenseCategory.builder()
        .code(code)
        .name(name)
        .description(description)
        .dbsSection(section)
        .defaultTaxable(defaultTaxable)
        .defaultTaxRatePct(defaultTaxRatePct)
        .sortOrder(sortOrder)
        .active(true)
        .build();
  }
}
