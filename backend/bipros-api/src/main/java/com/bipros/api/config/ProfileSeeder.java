package com.bipros.api.config;

import com.bipros.security.domain.model.Profile;
import com.bipros.security.domain.model.RolePermissionMatrix;
import com.bipros.security.domain.repository.ProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Seeds the 22 system-default permission profiles. Idempotent: each profile is created only if a
 * row with the same {@code code} is absent. Existing profiles are NOT overwritten — admins may
 * have edited them.
 *
 * <p>Permissions for each profile are sourced from {@link RolePermissionMatrix}, which is the
 * single source of truth for the role → permission contract.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ProfileSeeder {

    private final ProfileRepository profileRepository;

    public void seed() {
        int created = 0;
        int healed = 0;
        for (DefaultProfile dp : DEFAULTS) {
            java.util.Optional<Profile> existing = profileRepository.findByCode(dp.code);
            if (existing.isEmpty()) {
                Profile p = new Profile(dp.code, dp.name, dp.description, dp.legacyRole,
                        true, dp.permissions);
                profileRepository.save(p);
                log.debug("Seeded profile {} ({} permissions)", dp.code, dp.permissions.size());
                created++;
                continue;
            }
            Profile p = existing.get();
            if (!p.isSystemDefault()) continue;  // admin-customised — leave alone
            java.util.Set<String> missing = new java.util.HashSet<>(dp.permissions);
            missing.removeAll(p.getPermissions());
            if (!missing.isEmpty()) {
                p.getPermissions().addAll(missing);
                profileRepository.save(p);
                log.info("Self-healed system profile {} — added {} missing permission(s): {}",
                        dp.code, missing.size(), missing);
                healed++;
            }
        }
        if (created > 0) log.info("Seeded {} new default profiles", created);
        if (healed > 0) log.info("Self-healed {} existing system profiles with new permissions", healed);
    }

    private record DefaultProfile(String code, String name, String description,
                                  String legacyRole, Set<String> permissions) {}

    private static final List<DefaultProfile> DEFAULTS = List.of(
            new DefaultProfile(
                    "SYSTEM_ADMIN",
                    "System Administrator",
                    "Full platform control: all modules, user & profile management, settings.",
                    "ADMIN",
                    RolePermissionMatrix.permissionsFor("ADMIN")
            ),
            new DefaultProfile(
                    "PORTFOLIO_MANAGER",
                    "Portfolio Manager",
                    "Cross-project oversight, portfolio rollups, reports and analytics.",
                    "EXECUTIVE",
                    RolePermissionMatrix.permissionsFor("EXECUTIVE")
            ),
            new DefaultProfile(
                    "PROJECT_MANAGER",
                    "Project Manager",
                    "End-to-end project delivery: schedule, cost, risk, resources, contracts.",
                    "PROJECT_MANAGER",
                    RolePermissionMatrix.permissionsFor("PROJECT_MANAGER")
            ),
            new DefaultProfile(
                    "SCHEDULER",
                    "Scheduler / Planner",
                    "Schedule development, baseline management, schedule analytics.",
                    "SCHEDULER",
                    RolePermissionMatrix.permissionsFor("SCHEDULER")
            ),
            new DefaultProfile(
                    "RESOURCE_MANAGER",
                    "Resource Manager",
                    "Labor, material and equipment pool management; deployment and rates.",
                    "RESOURCE_MANAGER",
                    RolePermissionMatrix.permissionsFor("RESOURCE_MANAGER")
            ),
            new DefaultProfile(
                    "COST_CONTROLLER",
                    "Cost Controller",
                    "Budget, cost accounts, EVM, variance reporting, contract financials.",
                    "FINANCE",
                    RolePermissionMatrix.permissionsFor("FINANCE")
            ),
            new DefaultProfile(
                    "RISK_MANAGER",
                    "Risk Manager",
                    "Risk register, scoring, Monte Carlo, mitigation tracking.",
                    "PMO",
                    RolePermissionMatrix.permissionsFor("PMO")
            ),
            new DefaultProfile(
                    "DOCUMENT_CONTROLLER",
                    "Document Controller",
                    "Document repository, RFIs, transmittals, drawing register, contracts.",
                    "TEAM_MEMBER",
                    RolePermissionMatrix.permissionsFor("TEAM_MEMBER")
            ),
            new DefaultProfile(
                    "SITE_ENGINEER",
                    "Site Engineer",
                    "Field execution: activity progress, resource consumption, site documents.",
                    "SITE_ENGINEER",
                    RolePermissionMatrix.permissionsFor("SITE_ENGINEER")
            ),
            new DefaultProfile(
                    "EXECUTIVE_VIEWER",
                    "Executive Viewer",
                    "Read-only across the platform with report export.",
                    "VIEWER",
                    RolePermissionMatrix.permissionsFor("VIEWER")
            ),
            new DefaultProfile(
                    "SITE_MANAGER",
                    "Site Manager",
                    "Daily site execution: crew & machine deployment, materials, DPR ownership.",
                    "SITE_MANAGER",
                    RolePermissionMatrix.permissionsFor("SITE_MANAGER")
            ),
            new DefaultProfile(
                    "PROJECT_ENGINEER",
                    "Project Engineer",
                    "Design–execution bridge: activity & DPR review, yield variance, output norms.",
                    "PROJECT_ENGINEER",
                    RolePermissionMatrix.permissionsFor("PROJECT_ENGINEER")
            ),
            new DefaultProfile(
                    "QA_QC_ENGINEER",
                    "Quality Control Manager",
                    "Process adherence and traceability: NCRs, QC annotations on DPRs, audit trails.",
                    "QA_QC_ENGINEER",
                    RolePermissionMatrix.permissionsFor("QA_QC_ENGINEER")
            ),
            new DefaultProfile(
                    "BIM_DATA_COORDINATOR",
                    "BIM / Data Coordinator",
                    "Data-integrity steward: DPR completeness audits, model linkage, data lag.",
                    "BIM_DATA_COORDINATOR",
                    RolePermissionMatrix.permissionsFor("BIM_DATA_COORDINATOR")
            ),
            new DefaultProfile(
                    "PLANNING_ENGINEER",
                    "Planning Engineer",
                    "Schedule development, baseline creation, planning analytics across project lifecycle.",
                    "PLANNING_ENGINEER",
                    RolePermissionMatrix.permissionsFor("PLANNING_ENGINEER")
            ),
            new DefaultProfile(
                    "SUPERVISOR",
                    "Supervisor",
                    "Field supervision: crew oversight, DPR submission, on-site issue tracking.",
                    "SUPERVISOR",
                    RolePermissionMatrix.permissionsFor("SUPERVISOR")
            ),
            new DefaultProfile(
                    "FOREMAN",
                    "Foreman",
                    "Crew-level coordination: minimal access for DPR submission and safety logging.",
                    "FOREMAN",
                    RolePermissionMatrix.permissionsFor("FOREMAN")
            ),
            new DefaultProfile(
                    "STORE_MANAGER",
                    "Store Manager",
                    "Inventory and material store management: GRN, MRR, material consumption.",
                    "STORE_MANAGER",
                    RolePermissionMatrix.permissionsFor("STORE_MANAGER")
            ),
            new DefaultProfile(
                    "PROCUREMENT_OFFICER",
                    "Procurement Officer",
                    "Material purchasing, vendor coordination, contract intake.",
                    "PROCUREMENT_OFFICER",
                    RolePermissionMatrix.permissionsFor("PROCUREMENT_OFFICER")
            ),
            new DefaultProfile(
                    "SAFETY_OFFICER",
                    "Safety Officer",
                    "HSE compliance: incident logging, safety inspections, permit approvals.",
                    "SAFETY_OFFICER",
                    RolePermissionMatrix.permissionsFor("SAFETY_OFFICER")
            ),
            new DefaultProfile(
                    "CONTRACTOR",
                    "Contractor",
                    "External contractor: scoped access to assigned activities, DPR review, document upload.",
                    "CONTRACTOR",
                    RolePermissionMatrix.permissionsFor("CONTRACTOR")
            ),
            new DefaultProfile(
                    "CLIENT",
                    "Client",
                    "External client: read-only on assigned projects with report export.",
                    "CLIENT",
                    RolePermissionMatrix.permissionsFor("CLIENT")
            )
    );

    static int defaultCount() {
        return DEFAULTS.size();
    }

    /** Lookup a default profile's code by its legacy role name; used when seeding demo users. */
    static String codeForLegacyRole(String legacyRole) {
        return DEFAULTS.stream()
                .filter(d -> d.legacyRole.equals(legacyRole))
                .map(DefaultProfile::code)
                .findFirst()
                .orElse(null);
    }

    static Map<String, String> defaultCodesByRole() {
        return DEFAULTS.stream().collect(Collectors.toMap(d -> d.legacyRole, DefaultProfile::code, (a, b) -> a));
    }
}
