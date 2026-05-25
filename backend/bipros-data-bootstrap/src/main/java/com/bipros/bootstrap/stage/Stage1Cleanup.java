package com.bipros.bootstrap.stage;

import com.bipros.bootstrap.BootstrapApplication;
import com.bipros.bootstrap.Stage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Stage 1 — wipe business data so a fresh dataset can be loaded.
 *
 * <p>FK-safe order — deepest leaves first, masters last. Preserves users,
 * roles, calendars, and global reference data (skills, grades, manpower
 * categories, employment types) so authentication and reference lookups
 * keep working.
 *
 * <p>Each statement is wrapped in {@code DELETE FROM ...}. We do not use
 * TRUNCATE because some tables are referenced by FKs that we are NOT
 * truncating (e.g., users); TRUNCATE ... CASCADE would walk into those.
 * DELETE is slower but predictable.
 */
@Component
@Slf4j
public class Stage1Cleanup implements Stage {

    @PersistenceContext
    private EntityManager em;

    /**
     * Hard safety guard. Stage 1 wipes every business table — running it against a
     * database that holds real data is destructive. The default is {@code false} so
     * the only way to actually wipe is to set this explicitly via env var or JVM arg
     * (e.g., {@code -Dbootstrap.allow-cleanup=true} or {@code BOOTSTRAP_ALLOW_CLEANUP=true}).
     */
    @Value("${bootstrap.allow-cleanup:false}")
    private boolean allowCleanup;

    /** Tables to wipe, in dependency-safe order (deepest first). */
    private static final List<String> TABLES_IN_ORDER = List.of(
            // DPR children + ledger
            "project.dpr_issues",
            "project.dpr_material",
            "project.dpr_equipment",
            "project.dpr_manpower",
            "project.daily_progress_reports",
            "project.daily_activity_resource_outputs",
            // Resource plan
            "resource.resource_assignments",
            // Activity supervisors + activities
            "activity.activity_supervisors",
            "activity.activities",
            // BOQ + WBS + project
            "project.boq_items",
            "project.wbs_nodes",
            "project.projects",
            // Productivity + work activity masters
            "resource.productivity_norms",
            "resource.work_activities",
            // Project-level rate overrides
            "resource.project_manpower_role_rate_override",
            "resource.project_equipment_role_variant_override",
            "resource.project_material_role_variant_override",
            // Role-owned rate variants
            "resource.manpower_role_rates",
            "resource.equipment_role_variants",
            "resource.material_role_variants",
            // Legacy archived — MUST come before resource_roles, both project_resources
            // and resources hold FKs to resource_roles.
            "resource.project_resources",
            "resource.resources",
            "resource.manpower_rate_masters",
            "resource.equipment_rate_masters",
            "resource.material_rate_masters",
            // Roles (last — everything above has now released its FK reference)
            "resource.resource_roles"
    );

    public static void main(String[] args) {
        BootstrapApplication.runStage(Stage1Cleanup.class, args);
    }

    @Override
    @Transactional
    public void run() {
        if (!allowCleanup) {
            throw new IllegalStateException(
                    "Stage 1 cleanup is gated. To run it, pass -Dbootstrap.allow-cleanup=true "
                    + "(or set BOOTSTRAP_ALLOW_CLEANUP=true). Refusing — no rows touched.");
        }
        log.warn("CLEANUP — wiping business data. Users, roles, calendars and global masters are preserved.");
        for (String table : TABLES_IN_ORDER) {
            if (!tableExists(table)) {
                log.info("Skipping {} — table not present", table);
                continue;
            }
            int rows = em.createNativeQuery("DELETE FROM " + table).executeUpdate();
            log.info("Cleared {} ({} rows)", table, rows);
        }
        log.info("Cleanup complete.");
    }

    private boolean tableExists(String fqTable) {
        int dot = fqTable.indexOf('.');
        String schema = fqTable.substring(0, dot);
        String name = fqTable.substring(dot + 1);
        Object result = em.createNativeQuery(
                        "SELECT 1 FROM information_schema.tables " +
                        "WHERE table_schema = :s AND table_name = :n")
                .setParameter("s", schema)
                .setParameter("n", name)
                .getResultList().stream().findFirst().orElse(null);
        return result != null;
    }
}
