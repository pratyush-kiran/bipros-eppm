package com.bipros.api.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Dev-only idempotent schema fixup runner.
 *
 * <p>Hibernate's {@code ddl-auto: update} mode (used in dev) reliably adds new columns and
 * tables but never alters CHECK constraints or column types when an entity changes. In prod
 * we rely on Liquibase to handle those mutations; in dev Liquibase is disabled by default so
 * "fast feedback when editing entities" works without an extra migration step.
 *
 * <p>This bean runs only when {@code spring.liquibase.enabled = false} (i.e. dev) and applies
 * a small list of known mutations idempotently on every boot. Each fixup is a no-op once the
 * schema is in the desired state, so it's safe to leave permanently — extending the list
 * costs nothing on healthy DBs.
 *
 * <p>Each entry mirrors a Liquibase changeset so prod and dev converge to the same shape:
 * <ul>
 *   <li>070 → {@code boq_items_status_check} CHECK includes {@code OVERRUN}</li>
 *   <li>071 → {@code ra_bill_items.description} is at least VARCHAR(500)</li>
 *   <li>108-4 → {@code daily_progress_reports.approval_status} backfilled to APPROVED for legacy rows</li>
 * </ul>
 *
 * <p>If a fixup fails (e.g. Postgres returns an unexpected error), it logs and continues —
 * we do not want a single fixup glitch to abort backend startup.
 */
@Configuration
@ConditionalOnProperty(name = "spring.liquibase.enabled", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class DevSchemaFixupRunner {

  private final JdbcTemplate jdbcTemplate;

  @Bean
  public ApplicationRunner devSchemaFixups() {
    return args -> {
      log.info("[DevSchemaFixupRunner] running idempotent schema fixups (dev profile only)");
      ensureBoqStatusCheckIncludesOverrun();
      ensureRaBillItemDescriptionIsAtLeast500();
      backfillDprApprovalStatus();
      log.info("[DevSchemaFixupRunner] complete");
    };
  }

  /**
   * Fixup 070 — drop and recreate {@code boq_items_status_check} so it includes {@code OVERRUN}.
   * Only acts if the current constraint definition does not already mention {@code OVERRUN}.
   */
  private void ensureBoqStatusCheckIncludesOverrun() {
    try {
      String def = jdbcTemplate.queryForObject(
          """
          select pg_get_constraintdef(oid)
          from pg_constraint
          where conname = 'boq_items_status_check'
            and conrelid = 'project.boq_items'::regclass
          """,
          String.class);
      if (def == null) {
        log.warn("[DevSchemaFixupRunner] boq_items_status_check not found — skipping (table may not exist yet)");
        return;
      }
      if (def.contains("OVERRUN")) {
        log.debug("[DevSchemaFixupRunner] boq_items_status_check already includes OVERRUN — no-op");
        return;
      }
      jdbcTemplate.execute("ALTER TABLE project.boq_items DROP CONSTRAINT boq_items_status_check");
      jdbcTemplate.execute(
          "ALTER TABLE project.boq_items ADD CONSTRAINT boq_items_status_check "
              + "CHECK (status IN ('PENDING','ACTIVE','COMPLETED','OVERRUN','ON_HOLD'))");
      log.info("[DevSchemaFixupRunner] fixup 070 applied: boq_items_status_check now allows OVERRUN");
    } catch (Exception e) {
      log.warn("[DevSchemaFixupRunner] fixup 070 (boq_items_status_check) failed — continuing", e);
    }
  }

  /**
   * Fixup 071 — widen {@code ra_bill_items.description} to at least VARCHAR(500). Civil-works
   * BOQ descriptions routinely exceed the default Hibernate VARCHAR(255).
   */
  private void ensureRaBillItemDescriptionIsAtLeast500() {
    try {
      Integer currentLength = jdbcTemplate.queryForObject(
          """
          select character_maximum_length
          from information_schema.columns
          where table_schema = 'cost'
            and table_name = 'ra_bill_items'
            and column_name = 'description'
          """,
          Integer.class);
      if (currentLength == null) {
        log.warn("[DevSchemaFixupRunner] cost.ra_bill_items.description not found — skipping");
        return;
      }
      if (currentLength >= 500) {
        log.debug("[DevSchemaFixupRunner] ra_bill_items.description already >= 500 chars — no-op");
        return;
      }
      jdbcTemplate.execute(
          "ALTER TABLE cost.ra_bill_items ALTER COLUMN description TYPE VARCHAR(500)");
      log.info("[DevSchemaFixupRunner] fixup 071 applied: ra_bill_items.description widened from {} to 500",
          currentLength);
    } catch (Exception e) {
      log.warn("[DevSchemaFixupRunner] fixup 071 (ra_bill_items.description) failed — continuing", e);
    }
  }

  /**
   * Fixup 108-4 — backfill {@code daily_progress_reports.approval_status} to APPROVED for any
   * rows that are NULL or not yet APPROVED. Mirrors the Liquibase changeset 108-4 that runs in
   * prod; dev databases (ddl-auto: update, Liquibase disabled) need this applied at startup.
   * The UPDATE is idempotent — a no-op once all rows are APPROVED.
   */
  private void backfillDprApprovalStatus() {
    try {
      int updated = jdbcTemplate.update(
          """
          UPDATE project.daily_progress_reports
             SET approval_status = 'APPROVED',
                 submitted_at = COALESCE(submitted_at, created_at),
                 approved_at  = COALESCE(approved_at, updated_at)
           WHERE approval_status IS NULL OR approval_status <> 'APPROVED'
          """);
      if (updated > 0) {
        log.info("[DevSchemaFixupRunner] fixup 108-4 applied: backfilled {} DPR rows to APPROVED", updated);
      } else {
        log.debug("[DevSchemaFixupRunner] fixup 108-4: all DPR rows already APPROVED — no-op");
      }
    } catch (Exception e) {
      log.warn("[DevSchemaFixupRunner] fixup 108-4 (dpr approval_status backfill) failed — continuing", e);
    }
  }
}
