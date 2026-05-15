package com.bipros.security.domain.model;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Static catalog of every fine-grained permission an admin can assign through a Profile.
 * Lives in code (not DB) so the set ships with the application — admin only picks from this list,
 * never invents new codes. Codes follow the pattern {@code MODULE.ACTION}.
 */
public final class PermissionCatalog {

    public record Permission(String code, String module, String action, String label) {}

    private static final String CREATE = "CREATE";
    private static final String READ = "READ";
    private static final String UPDATE = "UPDATE";
    private static final String DELETE = "DELETE";
    private static final String EXPORT = "EXPORT";
    private static final String APPROVE = "APPROVE";
    private static final String ANNOTATE = "ANNOTATE";
    private static final String AUDIT = "AUDIT";
    private static final String WRITE = "WRITE";
    private static final String INCIDENT_LOG = "INCIDENT_LOG";
    private static final String MANAGE = "MANAGE";
    private static final String CLOSE = "CLOSE";
    private static final String RELEASE = "RELEASE";

    public static final List<Permission> ALL = List.of(
            // Project
            new Permission("PROJECT.CREATE", "PROJECT", CREATE, "Create projects"),
            new Permission("PROJECT.READ",   "PROJECT", READ,   "View projects"),
            new Permission("PROJECT.UPDATE", "PROJECT", UPDATE, "Edit projects"),
            new Permission("PROJECT.DELETE", "PROJECT", DELETE, "Delete projects"),
            new Permission("PROJECT.EXPORT", "PROJECT", EXPORT, "Export project data"),

            // Activity / WBS
            new Permission("ACTIVITY.CREATE", "ACTIVITY", CREATE, "Create activities and WBS nodes"),
            new Permission("ACTIVITY.READ",   "ACTIVITY", READ,   "View activities and WBS"),
            new Permission("ACTIVITY.UPDATE", "ACTIVITY", UPDATE, "Update activities and progress"),
            new Permission("ACTIVITY.DELETE", "ACTIVITY", DELETE, "Delete activities"),

            // Schedule
            new Permission("SCHEDULE.READ",    "SCHEDULE", READ,    "View schedules"),
            new Permission("SCHEDULE.UPDATE",  "SCHEDULE", UPDATE,  "Edit and recalculate schedule"),
            new Permission("SCHEDULE.APPROVE", "SCHEDULE", APPROVE, "Approve schedule changes"),

            // Baseline
            new Permission("BASELINE.CREATE", "BASELINE", CREATE, "Create baselines"),
            new Permission("BASELINE.READ",   "BASELINE", READ,   "View baselines"),
            new Permission("BASELINE.UPDATE", "BASELINE", UPDATE, "Update / promote baselines"),
            new Permission("BASELINE.DELETE", "BASELINE", DELETE, "Delete baselines"),

            // Resource
            new Permission("RESOURCE.CREATE", "RESOURCE", CREATE, "Create resources"),
            new Permission("RESOURCE.READ",   "RESOURCE", READ,   "View resources"),
            new Permission("RESOURCE.UPDATE", "RESOURCE", UPDATE, "Edit and assign resources"),
            new Permission("RESOURCE.DELETE", "RESOURCE", DELETE, "Delete resources"),

            // Cost
            new Permission("COST.CREATE", "COST", CREATE, "Create cost / budget entries"),
            new Permission("COST.READ",   "COST", READ,   "View costs and budgets"),
            new Permission("COST.UPDATE", "COST", UPDATE, "Edit costs and budgets"),
            new Permission("COST.DELETE", "COST", DELETE, "Delete cost entries"),
            new Permission("COST.EXPORT", "COST", EXPORT, "Export cost data"),

            // EVM
            new Permission("EVM.READ",   "EVM", READ,   "View EVM analysis"),
            new Permission("EVM.UPDATE", "EVM", UPDATE, "Update EVM data date and run"),
            new Permission("EVM.EXPORT", "EVM", EXPORT, "Export EVM reports"),

            // Risk
            new Permission("RISK.CREATE",  "RISK", CREATE,  "Create risks"),
            new Permission("RISK.READ",    "RISK", READ,    "View risks"),
            new Permission("RISK.UPDATE",  "RISK", UPDATE,  "Edit risks"),
            new Permission("RISK.DELETE",  "RISK", DELETE,  "Delete risks"),
            new Permission("RISK.APPROVE", "RISK", APPROVE, "Approve risk responses"),

            // Document
            new Permission("DOCUMENT.CREATE", "DOCUMENT", CREATE, "Upload documents"),
            new Permission("DOCUMENT.READ",   "DOCUMENT", READ,   "View documents"),
            new Permission("DOCUMENT.UPDATE", "DOCUMENT", UPDATE, "Edit document metadata / replace"),
            new Permission("DOCUMENT.DELETE", "DOCUMENT", DELETE, "Delete documents"),

            // Contract
            new Permission("CONTRACT.CREATE",  "CONTRACT", CREATE,  "Create contracts"),
            new Permission("CONTRACT.READ",    "CONTRACT", READ,    "View contracts"),
            new Permission("CONTRACT.UPDATE",  "CONTRACT", UPDATE,  "Edit contracts and milestones"),
            new Permission("CONTRACT.DELETE",  "CONTRACT", DELETE,  "Delete contracts"),
            new Permission("CONTRACT.APPROVE", "CONTRACT", APPROVE, "Approve contract changes"),

            // Portfolio
            new Permission("PORTFOLIO.READ",   "PORTFOLIO", READ,   "View portfolio rollups"),
            new Permission("PORTFOLIO.UPDATE", "PORTFOLIO", UPDATE, "Edit portfolio hierarchy"),

            // Reports
            new Permission("REPORT.READ",   "REPORT", READ,   "View reports and dashboards"),
            new Permission("REPORT.EXPORT", "REPORT", EXPORT, "Export reports (Excel / PDF)"),

            // AI Assistant
            new Permission("AI.READ", "AI", READ, "Use AI chat and AI-generated insights"),

            // Admin areas
            new Permission("ADMIN_USER.CREATE", "ADMIN_USER", CREATE, "Create users"),
            new Permission("ADMIN_USER.READ",   "ADMIN_USER", READ,   "View users"),
            new Permission("ADMIN_USER.UPDATE", "ADMIN_USER", UPDATE, "Edit users / assign profiles"),
            new Permission("ADMIN_USER.DELETE", "ADMIN_USER", DELETE, "Disable users"),

            new Permission("ADMIN_PROFILE.CREATE", "ADMIN_PROFILE", CREATE, "Create profiles"),
            new Permission("ADMIN_PROFILE.READ",   "ADMIN_PROFILE", READ,   "View profiles"),
            new Permission("ADMIN_PROFILE.UPDATE", "ADMIN_PROFILE", UPDATE, "Edit profile permissions"),
            new Permission("ADMIN_PROFILE.DELETE", "ADMIN_PROFILE", DELETE, "Delete custom profiles"),

            new Permission("ADMIN_ORG.CREATE", "ADMIN_ORG", CREATE, "Create organisations"),
            new Permission("ADMIN_ORG.READ",   "ADMIN_ORG", READ,   "View organisations"),
            new Permission("ADMIN_ORG.UPDATE", "ADMIN_ORG", UPDATE, "Edit organisations"),
            new Permission("ADMIN_ORG.DELETE", "ADMIN_ORG", DELETE, "Delete organisations"),

            new Permission("ADMIN_MASTER.READ",   "ADMIN_MASTER", READ,   "View master data (calendars, codes, UDFs)"),
            new Permission("ADMIN_MASTER.UPDATE", "ADMIN_MASTER", UPDATE, "Edit master data"),

            new Permission("ADMIN_SETTINGS.READ",   "ADMIN_SETTINGS", READ,   "View global settings"),
            new Permission("ADMIN_SETTINGS.UPDATE", "ADMIN_SETTINGS", UPDATE, "Edit global settings and integrations"),

            // Quality / NCR (used by QC_MANAGER profile and analyze_ncr_trends tool)
            new Permission("NCR.CREATE",  "NCR", CREATE, "Create non-conformance reports"),
            new Permission("NCR.READ",    "NCR", READ,   "View non-conformance reports"),
            new Permission("NCR.UPDATE",  "NCR", UPDATE, "Update / close NCRs"),
            new Permission("NCR.APPROVE", "NCR", APPROVE, "Approve NCR closure"),

            // Data quality (used by BIM_DATA_COORDINATOR profile)
            new Permission("DATA_QUALITY.READ",  "DATA_QUALITY", READ,  "View data-quality and DPR audit reports"),
            new Permission("DATA_QUALITY.AUDIT", "DATA_QUALITY", AUDIT, "Run DPR completeness audits"),

            // DPR QC annotations (used by QC_MANAGER profile)
            new Permission("DPR.QC_ANNOTATE", "DPR", ANNOTATE, "Add QC observations / annotations to DPRs"),

            // Yield variance (used by PROJECT_ENGINEER profile and analyze_yield_variance tool)
            new Permission("YIELD_VARIANCE.READ", "YIELD_VARIANCE", READ, "View material yield variance reports"),

            // AI write (lets a profile both run the AI and use write-capable AI tools when added)
            new Permission("AI.WRITE", "AI", WRITE, "Run AI tools that write back to the system"),

            // DPR (Daily Progress Report)
            new Permission("DPR.READ",    "DPR", READ,    "View daily progress reports"),
            new Permission("DPR.CREATE",  "DPR", CREATE,  "Submit DPRs (field role)"),
            new Permission("DPR.UPDATE",  "DPR", UPDATE,  "Edit own / team DPRs"),
            new Permission("DPR.DELETE",  "DPR", DELETE,  "Delete DPRs"),
            new Permission("DPR.APPROVE", "DPR", APPROVE, "Approve DPR submissions"),

            // Safety / HSE
            new Permission("SAFETY.READ",         "SAFETY", READ,         "View safety records and incident logs"),
            new Permission("SAFETY.CREATE",       "SAFETY", CREATE,       "Create safety records / inspections"),
            new Permission("SAFETY.UPDATE",       "SAFETY", UPDATE,       "Edit safety records"),
            new Permission("SAFETY.INCIDENT_LOG", "SAFETY", INCIDENT_LOG, "Log a safety incident (subset of CREATE for field roles)"),

            // Permits (Permit To Work)
            new Permission("PERMIT.READ",    "PERMIT", READ,    "View permits"),
            new Permission("PERMIT.CREATE",  "PERMIT", CREATE,  "Create new permits"),
            new Permission("PERMIT.APPROVE", "PERMIT", APPROVE, "Approve / reject permit steps"),

            // Project membership
            new Permission("PROJECT_MEMBER.READ",   "PROJECT_MEMBER", READ,   "View project members"),
            new Permission("PROJECT_MEMBER.MANAGE", "PROJECT_MEMBER", MANAGE, "Add/remove/edit project members"),

            // Workfront / area readiness — supervisor confirms, site-engineer releases
            new Permission("WORKFRONT.CREATE",  "WORKFRONT", CREATE,  "Mark a workfront ready"),
            new Permission("WORKFRONT.READ",    "WORKFRONT", READ,    "View workfront list and status"),
            new Permission("WORKFRONT.UPDATE",  "WORKFRONT", UPDATE,  "Edit workfront ready state or notes"),
            new Permission("WORKFRONT.RELEASE", "WORKFRONT", RELEASE, "Release a workfront for execution (engineering sign-off)"),

            // Snag / punch list — supervisor raises, engineer / QC closes
            new Permission("SNAG.CREATE", "SNAG", CREATE, "Raise a snag / punch-list item"),
            new Permission("SNAG.READ",   "SNAG", READ,   "View snags"),
            new Permission("SNAG.UPDATE", "SNAG", UPDATE, "Edit snag description / severity / status"),
            new Permission("SNAG.CLOSE",  "SNAG", CLOSE,  "Close a snag (QA/QC or site engineer)"),

            // Shift handover notes — between supervisors / foremen
            new Permission("SHIFT_HANDOVER.CREATE", "SHIFT_HANDOVER", CREATE, "Log a shift handover note"),
            new Permission("SHIFT_HANDOVER.READ",   "SHIFT_HANDOVER", READ,   "View shift handover notes"),

            // Attendance — daily contractor headcount and approval
            new Permission("ATTENDANCE.CREATE",  "ATTENDANCE", CREATE,  "Log daily attendance row"),
            new Permission("ATTENDANCE.READ",    "ATTENDANCE", READ,    "View attendance"),
            new Permission("ATTENDANCE.UPDATE",  "ATTENDANCE", UPDATE,  "Edit attendance before approval"),
            new Permission("ATTENDANCE.APPROVE", "ATTENDANCE", APPROVE, "Approve daily attendance (supervisor)"),

            // Checklist (pre-concrete, excavation, shuttering, …) — supervisor fills, QC signs
            new Permission("CHECKLIST.CREATE",  "CHECKLIST", CREATE,  "Start a checklist instance"),
            new Permission("CHECKLIST.READ",    "CHECKLIST", READ,    "View checklist templates and instances"),
            new Permission("CHECKLIST.UPDATE",  "CHECKLIST", UPDATE,  "Update checklist answers / attachments"),
            new Permission("CHECKLIST.APPROVE", "CHECKLIST", APPROVE, "Sign off a completed checklist"),

            // Procurement / material indent — supervisor raises, store/procurement approves
            new Permission("PROCUREMENT_REQUEST.CREATE",  "PROCUREMENT_REQUEST", CREATE,  "Raise a material indent / procurement request"),
            new Permission("PROCUREMENT_REQUEST.READ",    "PROCUREMENT_REQUEST", READ,    "View material indents"),
            new Permission("PROCUREMENT_REQUEST.UPDATE",  "PROCUREMENT_REQUEST", UPDATE,  "Edit indent before submission"),
            new Permission("PROCUREMENT_REQUEST.APPROVE", "PROCUREMENT_REQUEST", APPROVE, "Approve / reject indent (store / procurement)")
    );

    public static final Set<String> ALL_CODES = ALL.stream()
            .map(Permission::code)
            .collect(Collectors.toUnmodifiableSet());

    public static boolean isValid(String code) {
        return ALL_CODES.contains(code);
    }

    private PermissionCatalog() {}
}
