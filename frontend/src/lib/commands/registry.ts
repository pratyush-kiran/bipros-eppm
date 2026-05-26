import type { LucideIcon } from "lucide-react";
import {
  Award, Banknote, BarChart3, Briefcase, Calculator, Calendar,
  ClipboardCheck, Contact, FileText, FolderTree, Gauge,
  Grid, HardHat, Home, LayoutGrid, Layers, Library, ListChecks,
  Network, Plug, Settings, ShieldCheck, SlidersHorizontal, Sparkles,
  Tag, Users, UsersRound, Workflow, Bot, SunMoon,
} from "lucide-react";
import type { IcpmsModule } from "@/lib/types";

export interface Command {
  id: string;
  title: string;
  keywords: string[];
  icon: LucideIcon;
  group: string;
  href?: string;
  action?: () => void;
  adminOnly?: boolean;
  requireRoles?: readonly string[];
  module?: IcpmsModule;
  /** Fine-grained permission code (e.g. "PROJECT.READ"). Mirrors Sidebar gating; ADMIN bypasses. */
  permission?: string;
}

export const COMMAND_GROUPS = [
  "Recent",
  "Plan",
  "Execute",
  "Control",
  "Admin",
  "Resources",
  "Master Data",
  "Current Project",
  "Actions",
] as const;

export type CommandGroup = (typeof COMMAND_GROUPS)[number];

const GROUP_ORDER: Record<string, number> = {
  Recent: 0,
  Plan: 1,
  Execute: 2,
  Control: 3,
  Admin: 4,
  Resources: 5,
  "Master Data": 6,
  "Current Project": 7,
  Actions: 8,
};

export function groupRank(group: string): number {
  return GROUP_ORDER[group] ?? 99;
}

function nav(
  id: string,
  title: string,
  href: string,
  icon: LucideIcon,
  group: CommandGroup,
  opts?: Omit<Partial<Command>, "id" | "title" | "href" | "icon" | "group">
): Command {
  return { id, title, href, icon, group, keywords: [], ...opts };
}

function action(
  id: string,
  title: string,
  icon: LucideIcon,
  group: CommandGroup,
  action: () => void,
  opts?: Omit<Partial<Command>, "id" | "title" | "action" | "icon" | "group">
): Command {
  return { id, title, icon, group, action, keywords: [], ...opts };
}

/**
 * Static registry of all commands. Navigation commands mirror the sidebar;
 * action commands provide quick toggles.
 */
/**
 * Mirrors {@code Sidebar.tsx} exactly — when a sidebar entry is added/removed/gated, mirror
 * the change here so Cmd+K and the sidebar always agree on what's reachable.
 */
export const commands: Command[] = [
  // Plan
  nav("home", "Home", "/", Home, "Plan"),
  nav("portfolios", "Portfolios", "/portfolios", Briefcase, "Plan", { permission: "PORTFOLIO.READ" }),
  nav("projects", "Projects", "/projects", FolderTree, "Plan", { module: "M1_WBS_GIS", permission: "PROJECT.READ" }),
  nav("eps", "EPS", "/eps", Layers, "Plan", { module: "M1_WBS_GIS", permission: "PROJECT.READ" }),
  nav("obs", "OBS", "/obs", Network, "Plan", { module: "M1_WBS_GIS", permission: "PROJECT.READ" }),
  nav("qc", "QC", "/qc", ClipboardCheck, "Plan", { module: "M1_WBS_GIS", permission: "NCR.READ" }),
  nav("dashboards", "Dashboards", "/dashboards", LayoutGrid, "Plan", { module: "M9_REPORTS", permission: "REPORT.READ" }),

  // Execute
  nav("calendars", "Calendars", "/admin/calendars", Calendar, "Execute", { module: "M2_SCHEDULE_EVM", permission: "SCHEDULE.READ" }),

  // Control
  nav("reports", "Reports", "/reports", BarChart3, "Control", { module: "M9_REPORTS", permission: "REPORT.READ" }),

  // Admin
  nav("users", "Users", "/admin/users", UsersRound, "Admin", { adminOnly: true, permission: "ADMIN_USER.READ" }),
  nav("profiles", "Profiles", "/admin/profiles", ShieldCheck, "Admin", { adminOnly: true, permission: "ADMIN_PROFILE.READ" }),
  nav("risk-scoring-matrix", "Risk Scoring Matrix", "/admin/risk-scoring-matrix", Grid, "Admin", { adminOnly: true, permission: "ADMIN_MASTER.READ" }),
  nav("integrations", "Integrations", "/admin/integrations", Plug, "Admin", { adminOnly: true, permission: "ADMIN_SETTINGS.READ" }),
  nav("settings", "Settings", "/admin/settings", Settings, "Admin", { adminOnly: true, permission: "ADMIN_SETTINGS.READ" }),

  // Resources (Admin subgroup in sidebar)
  nav("resource-types", "Resource Types", "/admin/resource-types", ListChecks, "Resources", { adminOnly: true, permission: "RESOURCE.READ" }),
  nav("resource-roles", "Resource Roles", "/admin/resource-roles", Contact, "Resources", { adminOnly: true, permission: "RESOURCE.READ" }),

  // Master Data (Admin subgroup in sidebar)
  nav("formulas", "Formulas", "/admin/formulas", Calculator, "Master Data", { adminOnly: true, permission: "ADMIN_MASTER.READ" }),
  nav("productivity-norms", "Productivity Norms", "/admin/productivity-norms", Gauge, "Master Data", { adminOnly: true, permission: "ADMIN_MASTER.READ" }),
  nav("work-activities", "Work Activities", "/admin/work-activities", ListChecks, "Master Data", { adminOnly: true, permission: "ADMIN_MASTER.READ" }),
  nav("risk-library", "Risk Library", "/admin/risk-library", Library, "Master Data", { adminOnly: true, permission: "ADMIN_MASTER.READ" }),
  nav("risk-categories", "Risk Categories", "/admin/risk-categories", Layers, "Master Data", { adminOnly: true, permission: "ADMIN_MASTER.READ" }),
  nav("employment-types", "Employment Types", "/admin/employment-types", Briefcase, "Master Data", { adminOnly: true, permission: "ADMIN_MASTER.READ" }),
  nav("skills", "Skills", "/admin/skills", Sparkles, "Master Data", { adminOnly: true, permission: "ADMIN_MASTER.READ" }),
  nav("skill-levels", "Skill Levels", "/admin/skill-levels", Award, "Master Data", { adminOnly: true, permission: "ADMIN_MASTER.READ" }),
  nav("grades", "Grades", "/admin/grades", Award, "Master Data", { adminOnly: true, permission: "ADMIN_MASTER.READ" }),
  nav("material-categories", "Material Categories", "/admin/material-categories", FolderTree, "Master Data", { adminOnly: true, permission: "ADMIN_MASTER.READ" }),
  nav("project-categories", "Project Categories", "/admin/project-categories", Tag, "Master Data", { adminOnly: true, permission: "ADMIN_MASTER.READ" }),
  nav("permits", "Permits", "/permits", ShieldCheck, "Master Data", {
    requireRoles: ["FOREMAN", "SITE_ENGINEER", "HSE_OFFICER", "PROJECT_MANAGER", "ADMIN"],
    permission: "PERMIT.READ",
  }),
  nav("permits-workflow", "Workflow Reference", "/permits/workflow", Workflow, "Master Data", { permission: "PERMIT.READ" }),

  // Actions (placeholders — actual functions injected at runtime)
  action("toggle-ai", "Open AI Chat", Bot, "Actions", () => {}, { keywords: ["ask", "assistant", "chat"] }),
  action("toggle-theme", "Toggle Theme", SunMoon, "Actions", () => {}, { keywords: ["dark", "light", "mode"] }),
];

/**
 * Project-scoped commands. These are instantiated dynamically when a projectId is known.
 */
export function buildProjectCommands(projectId: string): Command[] {
  return [
    nav("project-dashboard", "Project Dashboard", `/projects/${projectId}`, Home, "Current Project", { module: "M1_WBS_GIS" }),
    nav("project-activities", "Activities", `/projects/${projectId}/activities`, ListChecks, "Current Project", { module: "M1_WBS_GIS" }),
    nav("project-activity-codes", "Activity Codes", `/projects/${projectId}/activity-codes`, Tag, "Current Project", { module: "M1_WBS_GIS" }),
    nav("project-activity-correlations", "Activity Correlations", `/projects/${projectId}/activity-correlations`, Network, "Current Project", { module: "M1_WBS_GIS" }),
    nav("project-budget-changes", "Budget Changes", `/projects/${projectId}/budget-changes`, Banknote, "Current Project", { module: "M4_COST_RA_BILLS" }),
    nav("project-contracts", "Contracts", `/projects/${projectId}/contracts`, FileText, "Current Project", { module: "M5_CONTRACTS" }),
    nav("project-documents", "Documents", `/projects/${projectId}/documents`, FileText, "Current Project", { module: "M6_DOCUMENTS" }),
    nav("project-dpr", "DPR", `/projects/${projectId}/dpr`, BarChart3, "Current Project", { module: "M9_REPORTS" }),
    nav("project-drawings", "Drawings", `/projects/${projectId}/drawings`, FileText, "Current Project", { module: "M6_DOCUMENTS" }),
    nav("project-equipment-logs", "Equipment Logs", `/projects/${projectId}/equipment-logs`, Settings, "Current Project", { module: "M8_RESOURCES" }),
    nav("project-evm", "EVM", `/projects/${projectId}/evm`, BarChart3, "Current Project", { module: "M2_SCHEDULE_EVM" }),
    nav("project-gis-viewer", "GIS Viewer", `/projects/${projectId}/gis-viewer`, LayoutGrid, "Current Project", { module: "M1_WBS_GIS" }),
    nav("project-global-change", "Global Change", `/projects/${projectId}/global-change`, SlidersHorizontal, "Current Project", { module: "M1_WBS_GIS" }),
    nav("project-issues", "Issues", `/projects/${projectId}/issues`, ShieldCheck, "Current Project", { module: "M7_RISKS" }),
    nav("project-labour-returns", "Labour Returns", `/projects/${projectId}/labour-returns`, HardHat, "Current Project", { module: "M8_RESOURCES" }),
    nav("project-material-consumption", "Material Consumption", `/projects/${projectId}/material-consumption`, Grid, "Current Project", { module: "M8_RESOURCES" }),
    nav("project-material-reconciliation", "Material Reconciliation", `/projects/${projectId}/material-reconciliation`, Grid, "Current Project", { module: "M8_RESOURCES" }),
    nav("project-material-sources", "Material Sources", `/projects/${projectId}/material-sources`, Grid, "Current Project", { module: "M8_RESOURCES" }),
    nav("project-next-day-plan", "Next Day Plan", `/projects/${projectId}/next-day-plan`, Calendar, "Current Project", { module: "M2_SCHEDULE_EVM" }),
    nav("project-predictions", "Predictions", `/projects/${projectId}/predictions`, Sparkles, "Current Project", { module: "M9_REPORTS" }),
    nav("project-relationships", "Relationships", `/projects/${projectId}/relationships`, Network, "Current Project", { module: "M1_WBS_GIS" }),
    nav("project-resource-deployment", "Resource Deployment", `/projects/${projectId}/resource-deployment`, Users, "Current Project", { module: "M8_RESOURCES" }),
    nav("project-rfis", "RFIs", `/projects/${projectId}/rfis`, FileText, "Current Project", { module: "M6_DOCUMENTS" }),
    nav("project-risks", "Risks", `/projects/${projectId}/risks`, ShieldCheck, "Current Project", { module: "M7_RISKS" }),
    nav("project-risk-analysis", "Risk Analysis", `/projects/${projectId}/risk-analysis`, BarChart3, "Current Project", { module: "M7_RISKS" }),
    nav("project-schedule-compression", "Schedule Compression", `/projects/${projectId}/schedule-compression`, Calendar, "Current Project", { module: "M2_SCHEDULE_EVM" }),
    nav("project-schedule-health", "Schedule Health", `/projects/${projectId}/schedule-health`, Gauge, "Current Project", { module: "M2_SCHEDULE_EVM" }),
    nav("project-stretches", "Stretches", `/projects/${projectId}/stretches`, LayoutGrid, "Current Project", { module: "M1_WBS_GIS" }),
    nav("project-stock-register", "Stock Register", `/projects/${projectId}/stock-register`, Grid, "Current Project", { module: "M8_RESOURCES" }),
    nav("project-weather-log", "Weather Log", `/projects/${projectId}/weather-log`, SunMoon, "Current Project", { module: "M2_SCHEDULE_EVM" }),
  ];
}
