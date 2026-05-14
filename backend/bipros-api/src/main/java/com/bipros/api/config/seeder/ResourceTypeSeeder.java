package com.bipros.api.config.seeder;

import com.bipros.resource.domain.model.ResourceType;
import com.bipros.resource.domain.repository.ResourceTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the three system-default {@link ResourceType} rows — MANPOWER, EQUIPMENT, MATERIAL.
 *
 * <p>Liquibase also seeds these rows in production. This seeder is the dev-mode safety net for
 * {@code ddl-auto=update} startups where Liquibase is disabled, and is fully idempotent: each row
 * is looked up by {@code code} and skipped if already present.
 *
 * <p>Note: original code "LABOR" was renamed to "MANPOWER" to match the UI terminology. Existing
 * data referencing LABOR is migrated by SQL — this seeder now only ensures MANPOWER exists.
 *
 * <p>Runs at {@code @Order(50)} — well before all the project-data seeders (101+) which create
 * Resources that need these rows to exist.
 */
@Slf4j
@Component
@Order(50)
@RequiredArgsConstructor
public class ResourceTypeSeeder implements CommandLineRunner {

  private final ResourceTypeRepository repository;

  @Override
  @Transactional
  public void run(String... args) {
    int inserted = 0;
    inserted += upsert("MANPOWER", "Manpower", "Human resources – manpower, labour, staff", 10);
    inserted += upsert("EQUIPMENT", "Equipment", "Machinery, vehicles and equipment", 20);
    inserted += upsert("MATERIAL", "Material", "Consumable materials and supplies", 30);
    if (inserted > 0) {
      log.info("[ResourceTypeSeeder] inserted {} system-default resource type(s)", inserted);
    }
  }

  private int upsert(String code, String name, String description, int sortOrder) {
    if (repository.findByCode(code).isPresent()) {
      return 0;
    }
    ResourceType type = ResourceType.builder()
        .code(code)
        .name(name)
        .description(description)
        .sortOrder(sortOrder)
        .active(true)
        .systemDefault(true)
        .build();
    repository.save(type);
    return 1;
  }
}
