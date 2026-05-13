package com.bipros.api.config.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Auto-applies schema and data migrations that Hibernate's {@code ddl-auto=update} cannot perform
 * on its own (column drops, constraint reshapes, data UPDATEs). Runs in every profile at startup
 * BEFORE any data seeder so migrated state is visible to seeders that might depend on it.
 *
 * <p>Every migration is idempotent — safe to run on every boot. Each migration logs only when it
 * actually changed something; a clean / already-migrated DB produces no log noise.
 *
 * <p>Production deployments using Liquibase ({@code application-prod.yml}) should still ship the
 * canonical changelogs in {@code db/changelog/}; this runner is the dev-mode safety net where
 * Liquibase is disabled. Running both is harmless because each operation is conditional.
 */
@Slf4j
@Component
@Order(10) // Before all data seeders (which start at 50)
@RequiredArgsConstructor
public class SchemaMigrationRunner implements CommandLineRunner {

  private final JdbcTemplate jdbc;

  @Override
  @Transactional
  public void run(String... args) {
    int touched = 0;
    touched += dropManpowerRateMasterSubCategoryColumn();
    touched += normalizeUnitValues();
    if (touched == 0) {
      log.debug("[SchemaMigrationRunner] schema is current, no migrations applied");
    }
  }

  /**
   * Phase 8 reshape: drop {@code sub_category_id} column from {@code manpower_rate_masters} and
   * narrow the unique key from (role, category, sub-category, grade) to (role, category, grade).
   *
   * <p>Idempotent: only acts when the column still exists. Pre-flight duplicate check would have
   * already passed in production (the audit confirmed 0 duplicates), so the constraint reshape
   * is safe.
   */
  private int dropManpowerRateMasterSubCategoryColumn() {
    Integer columnExists = jdbc.queryForObject(
        "SELECT COUNT(*) FROM information_schema.columns "
            + "WHERE table_schema = 'resource' "
            + "  AND table_name = 'manpower_rate_masters' "
            + "  AND column_name = 'sub_category_id'",
        Integer.class);
    if (columnExists == null || columnExists == 0) return 0;

    log.info("[SchemaMigrationRunner] dropping manpower_rate_masters.sub_category_id column "
        + "and reshaping uk_manpower_rate_master_key …");
    // Drop the old unique constraint (4-tuple) before the column it references.
    jdbc.execute("ALTER TABLE resource.manpower_rate_masters "
        + "DROP CONSTRAINT IF EXISTS uk_manpower_rate_master_key");
    jdbc.execute("ALTER TABLE resource.manpower_rate_masters "
        + "DROP COLUMN sub_category_id");
    // Re-add the unique key on the simpler 3-tuple.
    jdbc.execute("ALTER TABLE resource.manpower_rate_masters "
        + "ADD CONSTRAINT uk_manpower_rate_master_key "
        + "UNIQUE (role_id, category_id, grade_id)");
    return 1;
  }

  /**
   * Phase 7 unit canonicalization: normalize legacy uppercase string values to the canonical
   * shorthand used by {@code RESOURCE_RATE_UNITS} on the frontend (Day, Hour, Cum, kg, MT,
   * Each, Rm, …). Affects {@code resources}, {@code manpower_rate_masters},
   * {@code equipment_rate_masters}, {@code material_rate_masters}.
   *
   * <p>Each statement is idempotent — runs as a no-op when the legacy values are already gone.
   */
  private int normalizeUnitValues() {
    // Drop stale check constraints that only allow the old uppercase values; the entity no longer
    // carries a @Check annotation so Hibernate will not recreate them after the data update.
    dropCheckConstraintIfExists("resource", "resources", "resources_unit_check");
    dropCheckConstraintIfExists("resource", "manpower_rate_masters", "manpower_rate_masters_unit_check");
    dropCheckConstraintIfExists("resource", "equipment_rate_masters", "equipment_rate_masters_unit_check");
    dropCheckConstraintIfExists("resource", "material_rate_masters", "material_rate_masters_unit_check");

    int updated = 0;
    updated += updateUnit("resource.manpower_rate_masters", "PER_DAY", "Day");
    updated += updateUnit("resource.manpower_rate_masters", "CU_M", "Cum");
    updated += updateUnit("resource.equipment_rate_masters", "PER_DAY", "Day");
    updated += updateUnit("resource.material_rate_masters", "PER_DAY", "Day");
    updated += updateUnit("resource.material_rate_masters", "CU_M", "Cum");
    updated += updateUnit("resource.material_rate_masters", "KG", "kg");
    updated += updateUnit("resource.material_rate_masters", "RMT", "Rm");
    updated += updateUnit("resource.material_rate_masters", "NOS", "Each");
    updated += updateUnit("resource.resources", "PER_DAY", "Day");
    updated += updateUnit("resource.resources", "CU_M", "Cum");
    updated += updateUnit("resource.resources", "KG", "kg");
    updated += updateUnit("resource.resources", "RMT", "Rm");
    updated += updateUnit("resource.resources", "NOS", "Each");
    if (updated > 0) {
      log.info("[SchemaMigrationRunner] normalized {} legacy unit value rows to canonical labels",
          updated);
    }
    return updated > 0 ? 1 : 0;
  }

  private void dropCheckConstraintIfExists(String schema, String table, String constraintName) {
    Integer exists = jdbc.queryForObject(
        "SELECT COUNT(*) FROM information_schema.table_constraints "
            + "WHERE constraint_schema = ? AND table_name = ? AND constraint_name = ? "
            + "  AND constraint_type = 'CHECK'",
        Integer.class, schema, table, constraintName);
    if (exists != null && exists > 0) {
      log.info("[SchemaMigrationRunner] dropping stale check constraint {}.{}", table, constraintName);
      jdbc.execute("ALTER TABLE " + schema + "." + table
          + " DROP CONSTRAINT IF EXISTS " + constraintName);
    }
  }

  private int updateUnit(String fullyQualifiedTable, String fromValue, String toValue) {
    return jdbc.update(
        "UPDATE " + fullyQualifiedTable + " SET unit = ? WHERE unit = ?",
        toValue, fromValue);
  }
}
