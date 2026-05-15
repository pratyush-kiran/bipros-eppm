package com.bipros.security.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for {@link RolePermissionMatrix}. These lock the 22-role canonical matrix
 * and guard against drift in the {@link PermissionCatalog}.
 */
class RolePermissionMatrixTest {

    @Test
    void adminHasEveryPermission() {
        assertThat(RolePermissionMatrix.permissionsFor("ADMIN"))
                .isEqualTo(PermissionCatalog.ALL_CODES);
    }

    @Test
    void everyRoleHasAtLeastOnePermission() {
        assertThat(RolePermissionMatrix.DEFAULTS).hasSize(22);
        for (Map.Entry<String, Set<String>> entry : RolePermissionMatrix.DEFAULTS.entrySet()) {
            assertThat(entry.getValue())
                    .as("role '%s' should have at least one permission", entry.getKey())
                    .isNotEmpty();
        }
    }

    @Test
    void everyCodeInMatrixIsValid() {
        for (Map.Entry<String, Set<String>> entry : RolePermissionMatrix.DEFAULTS.entrySet()) {
            for (String code : entry.getValue()) {
                assertThat(PermissionCatalog.isValid(code))
                        .as("role '%s' references unknown permission code '%s'",
                                entry.getKey(), code)
                        .isTrue();
            }
        }
    }

    @Test
    void viewerSetMatchesShape() {
        Set<String> viewer = RolePermissionMatrix.permissionsFor("VIEWER");

        assertThat(viewer).contains("REPORT.EXPORT", "EVM.EXPORT");

        long readCount = viewer.stream().filter(c -> c.endsWith(".READ")).count();
        assertThat(readCount)
                .as("VIEWER should contain at least 10 .READ codes")
                .isGreaterThanOrEqualTo(10);

        // No write-style codes except the two explicit EXPORT escape hatches.
        for (String code : viewer) {
            if (code.equals("REPORT.EXPORT") || code.equals("EVM.EXPORT")) continue;
            assertThat(code)
                    .as("VIEWER must not contain write codes (offender: %s)", code)
                    .doesNotEndWith(".CREATE")
                    .doesNotEndWith(".UPDATE")
                    .doesNotEndWith(".DELETE")
                    .doesNotEndWith(".APPROVE")
                    .doesNotEndWith(".EXPORT");
        }
    }

    @Test
    void permissionsForUnknownRoleReturnsEmpty() {
        assertThat(RolePermissionMatrix.permissionsFor("NOT_A_ROLE")).isEmpty();
    }

    @Test
    void permissionsForAllUnionsCorrectly() {
        Set<String> union = RolePermissionMatrix.permissionsForAll(List.of("FOREMAN", "ADMIN"));
        assertThat(union).isEqualTo(PermissionCatalog.ALL_CODES);
    }

    @Test
    void supervisorHasSiteOpsPermissions() {
        Set<String> supervisor = RolePermissionMatrix.permissionsFor("SUPERVISOR");

        // Site-ops write surface granted in Phase A of the supervisor-hardening plan.
        assertThat(supervisor).contains(
                "ACTIVITY.UPDATE",
                "RESOURCE.UPDATE",
                "DOCUMENT.CREATE",
                "PERMIT.CREATE",
                "NCR.CREATE",
                "NCR.UPDATE",
                "SAFETY.CREATE",
                "YIELD_VARIANCE.READ",
                "DPR.DELETE"
        );

        // Phase C site-ops modules — supervisor raises, others approve/close/release.
        assertThat(supervisor).contains(
                "WORKFRONT.CREATE", "WORKFRONT.READ", "WORKFRONT.UPDATE",
                "SNAG.CREATE", "SNAG.READ", "SNAG.UPDATE",
                "SHIFT_HANDOVER.CREATE", "SHIFT_HANDOVER.READ",
                "ATTENDANCE.CREATE", "ATTENDANCE.READ", "ATTENDANCE.UPDATE", "ATTENDANCE.APPROVE",
                "CHECKLIST.CREATE", "CHECKLIST.READ", "CHECKLIST.UPDATE",
                "PROCUREMENT_REQUEST.CREATE", "PROCUREMENT_REQUEST.READ"
        );

        // Things the supervisor must NOT carry — guards against accidental privilege creep.
        assertThat(supervisor).doesNotContain(
                "ACTIVITY.CREATE", "ACTIVITY.DELETE",
                "PROJECT.CREATE", "PROJECT.DELETE", "PROJECT.UPDATE",
                "BASELINE.CREATE", "BASELINE.UPDATE",
                "CONTRACT.CREATE", "CONTRACT.UPDATE",
                "PROJECT_MEMBER.MANAGE",
                // Sign-off / approval should stay with engineer / QC / store / procurement
                "WORKFRONT.RELEASE",
                "SNAG.CLOSE",
                "CHECKLIST.APPROVE",
                "PROCUREMENT_REQUEST.APPROVE"
        );
    }

    @Test
    void legacyAliasesNotInMatrix() {
        for (String legacy : List.of("SITE_SUPERVISOR", "COST_ENGINEER", "STORE_KEEPER",
                "QC_MANAGER", "HSE_OFFICER")) {
            assertThat(RolePermissionMatrix.permissionsFor(legacy))
                    .as("legacy alias '%s' must not be present in the canonical matrix", legacy)
                    .isEmpty();
        }
    }
}
