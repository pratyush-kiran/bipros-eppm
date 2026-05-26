import {
  Activity,
  AlertTriangle,
  BarChart3,
  Calendar,
  ClipboardCheck,
  Contact,
  FolderTree,
  LayoutGrid,
  ListChecks,
  Network,
  Plus,
  ShieldAlert,
  ShieldCheck,
  UsersRound,
  Workflow,
  type LucideIcon,
} from "lucide-react";
import type { GatedDef } from "./hooks/useModuleAccess";

export interface FeaturedCardDef extends GatedDef {
  key: string;
  eyebrow: string;
  title: string;
  description: string;
  cta: string;
  href: string;
  icon: LucideIcon;
  accent: string;
  accentDeep: string;
  pillBg: string;
  pillFg: string;
}

// Deeper accent palette used for the bold featured cards. Two-stop linear
// gradient from `accent` to `accentDeep`. Pill is always a white surface so
// the CTA pops on every variant.
const COLOR = {
  emerald: { accent: "#10B981", accentDeep: "#065F46", pillBg: "#FFFFFF", pillFg: "#0F2A20" },
  gold: { accent: "#D4AF37", accentDeep: "#6E5616", pillBg: "#FFFFFF", pillFg: "#2A1F08" },
  indigo: { accent: "#6366F1", accentDeep: "#312E81", pillBg: "#FFFFFF", pillFg: "#1B1B43" },
  burgundy: { accent: "#9B2C2C", accentDeep: "#5B1414", pillBg: "#FFFFFF", pillFg: "#2A0A0A" },
  amber: { accent: "#D97706", accentDeep: "#783200", pillBg: "#FFFFFF", pillFg: "#2A1A05" },
  orange: { accent: "#F97316", accentDeep: "#7C2D12", pillBg: "#FFFFFF", pillFg: "#2A1408" },
  teal: { accent: "#14B8A6", accentDeep: "#0F4F4A", pillBg: "#FFFFFF", pillFg: "#0A2A28" },
  sky: { accent: "#0EA5E9", accentDeep: "#0C4A6E", pillBg: "#FFFFFF", pillFg: "#08283D" },
  slate: { accent: "#64748B", accentDeep: "#1F2937", pillBg: "#FFFFFF", pillFg: "#1F2937" },
};

// Card templates — defined once, composed into role-specific trios below. Each
// card carries the standard `permission` gate as a safety net so an unauthorized
// click is impossible even if the role map gets out of sync with the backend
// RolePermissionMatrix.
const C: Record<string, FeaturedCardDef> = {
  yourProjects: {
    key: "projects",
    eyebrow: "Active work",
    title: "Your projects",
    description: "WBS, GIS, schedules, DPRs — open the day's work in one tap.",
    cta: "Open projects",
    href: "/projects",
    icon: FolderTree,
    permission: "PROJECT.READ",
    ...COLOR.emerald,
  },
  myProjects: {
    key: "my-projects",
    eyebrow: "Active work",
    title: "My projects",
    description: "Projects you're enrolled on right now.",
    cta: "Open my projects",
    href: "/projects",
    icon: FolderTree,
    permission: "PROJECT.READ",
    ...COLOR.emerald,
  },
  portfolio: {
    key: "dashboards",
    eyebrow: "Programme health",
    title: "Portfolio at a glance",
    description: "Cross-portfolio scorecards, RAG mix and KPI gauges.",
    cta: "Open dashboards",
    href: "/dashboards",
    icon: LayoutGrid,
    permission: "REPORT.READ",
    ...COLOR.gold,
  },
  executiveReports: {
    key: "reports",
    eyebrow: "Insights",
    title: "Executive reports",
    description: "EVM, variance, productivity and one-click summaries.",
    cta: "Open reports",
    href: "/reports",
    icon: BarChart3,
    permission: "REPORT.READ",
    ...COLOR.indigo,
  },
  riskRegister: {
    key: "risk-register",
    eyebrow: "Risk",
    title: "Open risks",
    description: "Critical risks across your portfolio.",
    cta: "Open risks",
    href: "/reports/risk-register",
    icon: ShieldAlert,
    permission: "REPORT.READ",
    ...COLOR.burgundy,
  },
  permitsAwaiting: {
    key: "permits-awaiting",
    eyebrow: "Awaiting approval",
    title: "Permits awaiting",
    description: "Review and approve permits queued for you.",
    cta: "Open permits",
    href: "/permits",
    icon: ShieldCheck,
    permission: "PERMIT.READ",
    ...COLOR.amber,
  },
  todaysPermits: {
    key: "todays-permits",
    eyebrow: "Today",
    title: "Today's permits",
    description: "Permits cleared for your area today.",
    cta: "Open permits",
    href: "/permits",
    icon: ShieldCheck,
    permission: "PERMIT.READ",
    ...COLOR.amber,
  },
  newPermit: {
    key: "new-permit",
    eyebrow: "Raise",
    title: "New permit",
    description: "Raise a permit-to-work request.",
    cta: "Create permit",
    href: "/permits/new",
    icon: Plus,
    permission: "PERMIT.READ",
    ...COLOR.emerald,
  },
  permitWorkflow: {
    key: "permit-workflow",
    eyebrow: "Reference",
    title: "Permit workflow",
    description: "Approval routing, PPE requirements and SOPs.",
    cta: "View workflow",
    href: "/permits/workflow",
    icon: Workflow,
    permission: "PERMIT.READ",
    ...COLOR.slate,
  },
  qc: {
    key: "qc",
    eyebrow: "Quality",
    title: "Quality & NCRs",
    description: "Quality control, NCRs and snags raised on site.",
    cta: "Open QC",
    href: "/qc",
    icon: ClipboardCheck,
    permission: "NCR.READ",
    ...COLOR.orange,
  },
  submitProgress: {
    key: "submit-progress",
    eyebrow: "Daily",
    title: "Submit progress",
    description: "Daily productivity, quantities and photos.",
    cta: "Submit DPR",
    href: "/projects",
    icon: Activity,
    permission: "PROJECT.READ",
    // sky (not emerald) so SUPERVISOR's trio with myProjects(emerald) reads as
    // 3 distinct colors. Also keeps FOREMAN's amber/sky/burgundy trio crisp.
    ...COLOR.sky,
  },
  updateProgress: {
    key: "update-progress",
    eyebrow: "Daily",
    title: "Update progress",
    description: "Daily DPRs, snags and handovers.",
    cta: "Open DPRs",
    href: "/projects",
    icon: Activity,
    permission: "PROJECT.READ",
    ...COLOR.sky,
  },
  reportSafety: {
    key: "report-safety",
    eyebrow: "Safety",
    title: "Report safety issue",
    description: "Raise an incident or near-miss.",
    cta: "Report incident",
    href: "/permits/new",
    icon: AlertTriangle,
    permission: "PERMIT.READ",
    ...COLOR.burgundy,
  },
  scheduleHealth: {
    key: "schedule-health",
    eyebrow: "Schedule",
    title: "Schedule health",
    description: "Critical path, float drift and missed-task index.",
    cta: "Open schedule health",
    href: "/reports/schedule-health",
    icon: Calendar,
    permission: "REPORT.READ",
    // teal (not indigo) so SCHEDULER/PLANNING_ENGINEER trios with
    // executiveReports(indigo) end on 3 distinct colors.
    ...COLOR.teal,
  },
  resources: {
    key: "resources",
    eyebrow: "Resources",
    title: "Resources",
    description: "Crews, equipment and materials.",
    cta: "Open resources",
    href: "/admin/resource-types",
    icon: ListChecks,
    permission: "RESOURCE.READ",
    ...COLOR.teal,
  },
  projectTeam: {
    key: "project-team",
    eyebrow: "People",
    title: "Project team",
    description: "Assign and reassign people across projects.",
    cta: "Open team",
    href: "/admin/users",
    icon: UsersRound,
    permission: "ADMIN_USER.READ",
    ...COLOR.sky,
  },
  myActivities: {
    key: "my-activities",
    eyebrow: "Today",
    title: "My activities",
    description: "Tasks assigned to your crew this week.",
    cta: "Open activities",
    href: "/projects",
    icon: ClipboardCheck,
    permission: "PROJECT.READ",
    ...COLOR.emerald,
  },
  schedule: {
    key: "schedule",
    eyebrow: "Plan",
    title: "Schedule",
    description: "Project schedule across the portfolio.",
    cta: "Open schedule",
    href: "/projects",
    icon: Network,
    permission: "SCHEDULE.READ",
    // teal (not indigo) so CLIENT's trio ending on executiveReports(indigo)
    // doesn't have two adjacent indigo cards.
    ...COLOR.teal,
  },
  documents: {
    key: "documents",
    eyebrow: "Documents",
    title: "Documents",
    description: "Drawings, specs and approved deliverables.",
    cta: "Open documents",
    href: "/projects",
    icon: Contact,
    permission: "PROJECT.READ",
    ...COLOR.slate,
  },
};

/**
 * Role → featured trio. Keys are the canonical `ROLE_*` strings produced by
 * `useMostSeniorRole()`. Roles that share a workflow share a trio (e.g. ADMIN
 * and VIEWER both get the universal Projects / Dashboards / Reports).
 */
export const FEATURED_BY_ROLE: Record<string, readonly FeaturedCardDef[]> = {
  ROLE_ADMIN: [C.yourProjects, C.portfolio, C.executiveReports],
  ROLE_VIEWER: [C.yourProjects, C.portfolio, C.executiveReports],

  ROLE_EXECUTIVE: [C.portfolio, C.executiveReports, C.yourProjects],
  ROLE_PMO: [C.portfolio, C.executiveReports, C.yourProjects],
  ROLE_FINANCE: [C.executiveReports, C.portfolio, C.yourProjects],

  ROLE_PROJECT_MANAGER: [C.yourProjects, C.riskRegister, C.executiveReports],

  ROLE_HSE_OFFICER: [C.permitsAwaiting, C.newPermit, C.permitWorkflow],

  ROLE_SITE_ENGINEER: [C.todaysPermits, C.myActivities, C.qc],

  ROLE_FOREMAN: [C.todaysPermits, C.submitProgress, C.reportSafety],
  ROLE_SUPERVISOR: [C.myProjects, C.submitProgress, C.reportSafety],

  ROLE_SITE_MANAGER: [C.yourProjects, C.todaysPermits, C.executiveReports],
  ROLE_CONSTRUCTION_MANAGER: [C.yourProjects, C.todaysPermits, C.executiveReports],

  ROLE_SCHEDULER: [C.yourProjects, C.scheduleHealth, C.executiveReports],
  ROLE_PLANNING_ENGINEER: [C.yourProjects, C.scheduleHealth, C.executiveReports],

  ROLE_RESOURCE_MANAGER: [C.resources, C.projectTeam, C.executiveReports],

  ROLE_TEAM_MEMBER: [C.myProjects, C.updateProgress, C.documents],

  ROLE_CLIENT: [C.yourProjects, C.schedule, C.executiveReports],
};

export const FALLBACK_FEATURED: readonly FeaturedCardDef[] = [
  C.yourProjects,
  C.portfolio,
  C.executiveReports,
];

export function featuredForRole(role: string | null): readonly FeaturedCardDef[] {
  if (!role) return FALLBACK_FEATURED;
  return FEATURED_BY_ROLE[role] ?? FALLBACK_FEATURED;
}
