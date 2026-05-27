import type { LucideIcon } from "lucide-react";
import {
  Home, FolderTree, ClipboardList, Package, Layers, Bookmark,
  CalendarDays, Boxes, CalendarPlus, CloudSun,
  Users, Zap, LineChart,
  Receipt, Wallet, TrendingUp, FileEdit, Scale, FileText,
  AlertTriangle, Microscope, Flag,
  GitBranch, HeartPulse, Settings2,
  Sparkles, Map, FileSpreadsheet,
} from "lucide-react";

export type SectionGroupId =
  | "overview" | "plan" | "execute" | "people"
  | "cost" | "risk" | "schedule" | "insights-map";

export type SectionPriority = "primary" | "secondary";

export interface SectionDef {
  id: string;
  label: string;
  group: SectionGroupId;
  priority: SectionPriority;
  href: (projectId: string) => string;
  icon: LucideIcon;
  description: string;
  permission?: string;
  searchKeywords?: string;
}

export interface SectionGroupDef {
  id: SectionGroupId;
  label: string;
  blurb: string;
  chipClass: string;        // Tailwind classes for the group chip badge (darker, used as label pill)
  accentClass: string;      // Tailwind classes for the bento card accent bar
  iconChipClass: string;    // Tailwind classes for the small icon chip on launcher tiles + sheet tiles (lighter)
  tileGradient: string;     // bold vibrant gradient fill for QUICK ACCESS launcher tiles (white text/icon on top)
  tileGlow: string;         // hue-matched hover glow shadow for QUICK ACCESS launcher tiles
  cardIcon: LucideIcon;     // representative icon used as the faint masthead watermark on bento group cards
}

export const SECTION_GROUPS: readonly SectionGroupDef[] = [
  { id: "overview", label: "Overview", blurb: "Project at a glance",
    chipClass: "bg-gold-tint/60 text-gold-deep",
    accentClass: "bg-gradient-to-b from-gold to-gold-deep",
    iconChipClass: "bg-gold-tint/60 text-gold-deep dark:bg-gold/25 dark:text-gold",
    tileGradient: "bg-gradient-to-br from-gold via-gold-deep to-gold-ink",
    tileGlow: "hover:shadow-[0_14px_34px_-12px_rgba(0,88,202,0.40)]",
    cardIcon: Home },
  { id: "plan", label: "Plan", blurb: "The structural backbone of the project",
    chipClass: "bg-blue-100 text-blue-800",
    accentClass: "bg-gradient-to-b from-blue-500 to-blue-800",
    iconChipClass: "bg-blue-50 text-blue-700 dark:bg-blue-500/30 dark:text-blue-200",
    tileGradient: "bg-gradient-to-br from-blue-400 via-blue-500 to-blue-600",
    tileGlow: "hover:shadow-[0_14px_34px_-12px_rgba(37,99,235,0.40)]",
    cardIcon: FolderTree },
  { id: "execute", label: "Execute", blurb: "Day-by-day field execution",
    chipClass: "bg-amber-100 text-amber-800",
    accentClass: "bg-gradient-to-b from-gold to-gold-deep",
    iconChipClass: "bg-gold-tint/60 text-gold-deep dark:bg-gold/25 dark:text-gold",
    tileGradient: "bg-gradient-to-br from-amber-400 via-orange-500 to-orange-600",
    tileGlow: "hover:shadow-[0_14px_34px_-12px_rgba(234,88,12,0.40)]",
    cardIcon: CalendarDays },
  { id: "people", label: "People & Capacity", blurb: "Who is doing what, and how fully",
    chipClass: "bg-cyan-100 text-cyan-800",
    accentClass: "bg-gradient-to-b from-cyan-500 to-cyan-800",
    iconChipClass: "bg-cyan-50 text-cyan-700 dark:bg-cyan-500/30 dark:text-cyan-200",
    tileGradient: "bg-gradient-to-br from-cyan-400 via-teal-500 to-teal-600",
    tileGlow: "hover:shadow-[0_14px_34px_-12px_rgba(20,184,166,0.40)]",
    cardIcon: Users },
  { id: "cost", label: "Cost & Finance", blurb: "Earned value, P&L, commercial controls",
    chipClass: "bg-emerald-100 text-emerald-800",
    accentClass: "bg-gradient-to-b from-emerald-600 to-emerald-900",
    iconChipClass: "bg-emerald-50 text-emerald-700 dark:bg-emerald-500/30 dark:text-emerald-200",
    tileGradient: "bg-gradient-to-br from-emerald-400 via-emerald-500 to-emerald-600",
    tileGlow: "hover:shadow-[0_14px_34px_-12px_rgba(5,150,105,0.40)]",
    cardIcon: Wallet },
  { id: "risk", label: "Risk & Issues", blurb: "What could go wrong, what already has",
    chipClass: "bg-red-100 text-red-800",
    accentClass: "bg-gradient-to-b from-red-600 to-red-900",
    iconChipClass: "bg-red-50 text-red-700 dark:bg-red-500/35 dark:text-red-200",
    tileGradient: "bg-gradient-to-br from-rose-400 via-red-500 to-red-600",
    tileGlow: "hover:shadow-[0_14px_34px_-12px_rgba(225,29,72,0.40)]",
    cardIcon: AlertTriangle },
  { id: "schedule", label: "Schedule Analysis", blurb: "How the network holds together",
    chipClass: "bg-violet-100 text-violet-800",
    accentClass: "bg-gradient-to-b from-violet-500 to-violet-900",
    iconChipClass: "bg-violet-50 text-violet-700 dark:bg-violet-500/30 dark:text-violet-200",
    tileGradient: "bg-gradient-to-br from-violet-400 via-violet-500 to-purple-600",
    tileGlow: "hover:shadow-[0_14px_34px_-12px_rgba(124,58,237,0.40)]",
    cardIcon: GitBranch },
  { id: "insights-map", label: "Insights & Map", blurb: "Where the project lives + roll-up reports",
    chipClass: "bg-amber-100 text-amber-800",
    accentClass: "bg-gradient-to-b from-amber-500 to-amber-800",
    iconChipClass: "bg-amber-50 text-amber-700 dark:bg-amber-500/30 dark:text-amber-200",
    tileGradient: "bg-gradient-to-br from-fuchsia-400 via-purple-500 to-purple-600",
    tileGlow: "hover:shadow-[0_14px_34px_-12px_rgba(168,85,247,0.40)]",
    cardIcon: Sparkles },
] as const;

/**
 * Workflow-ordered list of the 14 primary section IDs as agreed in the UX mock:
 * Overview → Plan → People → Execute → Finance → Analytics → Map.
 *
 * Drives {@link getPrimarySections}. Independent of {@link SECTIONS} (which is
 * grouped for the All Sections sheet and bento cards) and
 * {@link SECTION_GROUPS}. Keep in sync with the mock if the launcher reorders.
 */
const QUICK_ACCESS_ORDER: readonly string[] = [
  "overview",
  "wbs",
  "activities",
  "boq",
  "team",
  "general-expenses",
  "dpr",
  "capacity",
  "dbs",
  "costs",
  "insights",
  "risks",
  "material-consumption",
  "gis",
] as const;

export const SECTIONS: readonly SectionDef[] = [
  // Overview
  { id: "overview", label: "Overview", group: "overview", priority: "primary",
    href: (id) => `/projects/${id}?tab=overview`, icon: Home,
    description: "Status, dates, and priority at a glance" },

  // Plan
  { id: "wbs", label: "WBS", group: "plan", priority: "primary",
    href: (id) => `/projects/${id}?tab=wbs`, icon: FolderTree,
    description: "Work breakdown structure", searchKeywords: "work breakdown tree phases" },
  { id: "activities", label: "Activities", group: "plan", priority: "primary",
    href: (id) => `/projects/${id}/activities`, icon: ClipboardList,
    description: "Activity list, schedule, critical path" },
  { id: "boq", label: "BOQ", group: "plan", priority: "primary",
    href: (id) => `/projects/${id}/boq`, icon: Package,
    description: "Bill of quantities" },
  { id: "baselines", label: "Baselines", group: "plan", priority: "secondary",
    href: (id) => `/projects/${id}?tab=baselines`, icon: Bookmark,
    description: "Baseline snapshots · milestones and variance tracking" },

  // Execute
  { id: "dpr", label: "DPR", group: "execute", priority: "primary",
    href: (id) => `/projects/${id}/dpr`, icon: CalendarDays,
    description: "Daily progress report", searchKeywords: "daily progress site report" },
  { id: "material-consumption", label: "Material Consumption", group: "execute", priority: "primary",
    href: (id) => `/projects/${id}/material-consumption`, icon: Boxes,
    description: "Material consumed per day" },
  { id: "next-day-plan", label: "Next Day Plan", group: "execute", priority: "secondary",
    href: (id) => `/projects/${id}/next-day-plan`, icon: CalendarPlus,
    description: "Tomorrow's planned activities + resources" },
  { id: "weather-log", label: "Weather Log", group: "execute", priority: "secondary",
    href: (id) => `/projects/${id}/weather-log`, icon: CloudSun,
    description: "Daily weather entries · impacts on workability" },

  // People
  { id: "team", label: "Team", group: "people", priority: "primary",
    href: (id) => `/projects/${id}/team`, icon: Users,
    description: "Project members" },
  { id: "capacity", label: "Capacity Util.", group: "people", priority: "primary",
    href: (id) => `/projects/${id}/capacity-utilization`, icon: Zap,
    description: "Manpower & equipment efficiency · budgeted vs actual output · cost overrun / savings",
    searchKeywords: "manpower equipment efficiency productivity norm cost overrun savings" },
  { id: "performance", label: "Performance (D/W/M)", group: "people", priority: "secondary",
    href: (id) => `/projects/${id}/performance`, icon: LineChart,
    description: "Daily / weekly / monthly productivity" },

  // Cost
  { id: "general-expenses", label: "General Expenses", group: "cost", priority: "primary",
    href: (id) => `/projects/${id}/general-expenses`, icon: Receipt,
    description: "Non-BOQ project expenses" },
  { id: "costs", label: "Costs", group: "cost", priority: "primary",
    href: (id) => `/projects/${id}?tab=costs`, icon: Wallet,
    description: "Rolled-up project cost · plan vs actual",
    permission: "COST.READ" },
  { id: "dbs", label: "DBS", group: "cost", priority: "primary",
    href: (id) => `/projects/${id}/dbs`, icon: Layers,
    description: "Daily Balance Sheet · per-supervisor/engineer/PM cost rollup",
    searchKeywords: "daily balance sheet rollup supervisor engineer pm" },
  { id: "evm", label: "EVM", group: "cost", priority: "secondary",
    href: (id) => `/projects/${id}/evm`, icon: TrendingUp,
    description: "Earned value · SPI, CPI, EAC, VAC" },
  { id: "budget-changes", label: "Budget Changes", group: "cost", priority: "secondary",
    href: (id) => `/projects/${id}/budget-changes`, icon: FileEdit,
    description: "Approved & pending budget revisions" },
  { id: "pnl-budgeted", label: "P&L vs Budgeted Rates", group: "cost", priority: "secondary",
    href: (id) => `/projects/${id}/pnl/budgeted`, icon: Scale,
    description: "Profitability against planned rates" },
  { id: "pnl-boq", label: "P&L vs BOQ Rates", group: "cost", priority: "secondary",
    href: (id) => `/projects/${id}/pnl/boq`, icon: Scale,
    description: "Profitability against client BOQ rates" },
  { id: "contracts", label: "Contracts", group: "cost", priority: "secondary",
    href: (id) => `/projects/${id}/contracts`, icon: FileText,
    description: "Subcontracts, supplier agreements" },

  // Risk
  { id: "risks", label: "Risks", group: "risk", priority: "primary",
    href: (id) => `/projects/${id}/risks`, icon: AlertTriangle,
    description: "Risk register", permission: "RISK.READ" },
  { id: "risk-analysis", label: "Risk Analysis", group: "risk", priority: "secondary",
    href: (id) => `/projects/${id}/risk-analysis`, icon: Microscope,
    description: "Quantitative risk analysis (Monte Carlo)" },
  { id: "issues", label: "Issues", group: "risk", priority: "secondary",
    href: (id) => `/projects/${id}/issues`, icon: Flag,
    description: "Realised problems · owner, status, ETA" },

  // Schedule
  { id: "relationships", label: "Relationships", group: "schedule", priority: "secondary",
    href: (id) => `/projects/${id}/relationships`, icon: GitBranch,
    description: "Activity precedence network" },
  { id: "schedule-health", label: "Schedule Health", group: "schedule", priority: "secondary",
    href: (id) => `/projects/${id}/schedule-health`, icon: HeartPulse,
    description: "DCMA-14 style checks · float, lags, constraints" },
  { id: "schedule-compression", label: "Schedule Compression", group: "schedule", priority: "secondary",
    href: (id) => `/projects/${id}/schedule-compression`, icon: Settings2,
    description: "Fast-track + crash recommendations" },

  // Insights & Map
  { id: "insights", label: "Insights", group: "insights-map", priority: "primary",
    href: (id) => `/projects/${id}/insights`, icon: Sparkles,
    description: "Operational, field & capacity dashboards" },
  { id: "gis", label: "GIS", group: "insights-map", priority: "primary",
    href: (id) => `/projects/${id}/gis-viewer`, icon: Map,
    description: "Geographic view" },
  { id: "material-consumption-report", label: "Material Consumption Report", group: "insights-map", priority: "secondary",
    href: (id) => `/projects/${id}/reports/material-consumption`, icon: FileSpreadsheet,
    description: "Cumulative material roll-up" },
] as const;

// Helpers (all pure)
export function getSectionsByGroup(groupId: SectionGroupId): SectionDef[] {
  return SECTIONS.filter((s) => s.group === groupId);
}
export function getPrimarySections(): SectionDef[] {
  // Returns the 14 primary sections in mock-specified workflow order
  // (Overview → Plan → People → Execute → Finance → Analytics → Map),
  // NOT the catalog grouping order.
  return QUICK_ACCESS_ORDER
    .map((id) => findSectionById(id))
    .filter((s): s is SectionDef => s !== undefined && s.priority === "primary");
}
export function findSectionById(id: string): SectionDef | undefined {
  return SECTIONS.find((s) => s.id === id);
}
export function findGroupById(id: SectionGroupId): SectionGroupDef | undefined {
  return SECTION_GROUPS.find((g) => g.id === id);
}
export function filterByPermission(
  sections: readonly SectionDef[],
  hasPermission: (code: string) => boolean,
): SectionDef[] {
  return sections.filter((s) => !s.permission || hasPermission(s.permission));
}

/**
 * Given a Next.js `pathname` and `searchParams`, returns the active section
 * (or undefined if at the hub root with no ?tab=).
 * Used by SectionContextBar to know which group/section to display.
 */
export function resolveActiveSection(
  pathname: string,
  searchParams: URLSearchParams,
  projectId: string,
): SectionDef | undefined {
  const tab = searchParams.get("tab");
  const projectBase = `/projects/${projectId}`;

  // At project base with no tab → hub mode → no active section
  if ((pathname === projectBase || pathname === `${projectBase}/`) && !tab) {
    return undefined;
  }

  // At project base WITH tab → resolve by query
  if (pathname === projectBase || pathname === `${projectBase}/`) {
    return SECTIONS.find((s) => s.href(projectId) === `${projectBase}?tab=${tab}`);
  }

  // Sub-route — match by href prefix
  return SECTIONS.find((s) => {
    const h = s.href(projectId);
    // Skip query-param entries when looking for sub-routes
    if (h.includes("?")) return false;
    return pathname === h || pathname.startsWith(h + "/");
  });
}
