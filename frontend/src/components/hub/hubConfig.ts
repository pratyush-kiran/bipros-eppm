import {
  Activity,
  AlertTriangle,
  BarChart3,
  Briefcase,
  Calendar,
  ClipboardList,
  Database,
  FileText,
  FolderTree,
  Gauge,
  HardHat,
  Layers,
  LayoutGrid,
  Library,
  ListChecks,
  Network,
  Plug,
  Plus,
  ShieldAlert,
  ShieldCheck,
  Sparkles,
  TrendingUp,
  UserPlus,
  Users,
  Workflow,
  type LucideIcon,
} from "lucide-react";

/**
 * Hero tile shown at the top of the hub. {@code badgeKey} resolves against the
 * record returned by {@code useHubSummary} — falsy/zero values render no badge.
 */
export interface HeroTile {
  title: string;
  description: string;
  href: string;
  icon: LucideIcon;
  badgeKey?: HubBadgeKey;
  /** If set, hide this tile when the user lacks the named permission. */
  permission?: string;
}

export type HubBadgeKey =
  | "pendingPermits"
  | "criticalRisks"
  | "atRiskProjects"
  | "overdueTasks";

export interface ToolLink {
  label: string;
  href: string;
  icon: LucideIcon;
  adminOnly?: boolean;
  /** Permission code required to see this tile. If absent, no perm check. */
  permission?: string;
}

export interface ToolColumn {
  title: string;
  description: string;
  icon: LucideIcon;
  links: ToolLink[];
  adminOnly?: boolean;
}

const VIEW_DASHBOARD: HeroTile = {
  title: "Programme dashboard",
  description: "Portfolio KPIs, RAG mix, cash flow and risk heatmap.",
  href: "/dashboard",
  icon: Gauge,
};

const HEROES_BY_ROLE: Record<string, HeroTile[]> = {
  ROLE_ADMIN: [
    {
      title: "Master data",
      description: "Skills, categories, risk library, productivity norms.",
      href: "/admin/rate-master",
      icon: Database,
    },
    {
      title: "Add a user",
      description: "Provision access, assign roles and modules.",
      href: "/admin/users",
      icon: UserPlus,
    },
    VIEW_DASHBOARD,
    {
      title: "Integrations",
      description: "External systems and authentication wiring.",
      href: "/admin/integrations",
      icon: Plug,
    },
  ],
  ROLE_PROJECT_MANAGER: [
    {
      title: "My projects",
      description: "Active projects you're managing.",
      href: "/projects",
      icon: FolderTree,
      badgeKey: "atRiskProjects",
    },
    {
      title: "New project",
      description: "Stand up a project from a WBS template.",
      href: "/projects/new",
      icon: Plus,
    },
    {
      title: "Risk register",
      description: "Open risks across your portfolio.",
      href: "/reports/risk-register",
      icon: ShieldAlert,
      badgeKey: "criticalRisks",
    },
    VIEW_DASHBOARD,
  ],
  ROLE_HSE_OFFICER: [
    {
      title: "Permits awaiting approval",
      description: "Review and approve open permits.",
      href: "/permits",
      icon: ShieldCheck,
      badgeKey: "pendingPermits",
    },
    {
      title: "New permit",
      description: "Raise a permit-to-work request.",
      href: "/permits/new",
      icon: Plus,
    },
    {
      title: "Workflow reference",
      description: "Approval routing and PPE requirements.",
      href: "/permits/workflow",
      icon: Workflow,
    },
    {
      title: "Compliance reports",
      description: "Audits, near-misses, training compliance.",
      href: "/reports",
      icon: BarChart3,
    },
  ],
  ROLE_SITE_ENGINEER: [
    {
      title: "Today's permits",
      description: "Permits issued for your area today.",
      href: "/permits",
      icon: ShieldCheck,
      badgeKey: "pendingPermits",
    },
    {
      title: "My activities",
      description: "Tasks assigned to your crew this week.",
      href: "/projects",
      icon: ClipboardList,
      badgeKey: "overdueTasks",
    },
    {
      title: "Update progress",
      description: "Daily progress reports and photos.",
      href: "/projects",
      icon: Activity,
    },
    {
      title: "Report safety issue",
      description: "Raise an incident or near-miss.",
      href: "/permits/new",
      icon: AlertTriangle,
    },
  ],
  ROLE_FOREMAN: [
    {
      title: "Today's permits",
      description: "Permits cleared for your crew.",
      href: "/permits",
      icon: ShieldCheck,
      badgeKey: "pendingPermits",
    },
    {
      title: "Submit progress",
      description: "Daily productivity and quantities.",
      href: "/projects",
      icon: Activity,
    },
  ],
  ROLE_SUPERVISOR: [
    {
      title: "My projects",
      description: "Projects you're enrolled on.",
      href: "/projects",
      icon: FolderTree,
    },
    {
      title: "Today's permits",
      description: "Permits for your area.",
      href: "/permits",
      icon: ShieldCheck,
      badgeKey: "pendingPermits",
    },
    {
      title: "Update progress",
      description: "Daily DPRs, snags, handovers.",
      href: "/projects",
      icon: Activity,
    },
  ],
  ROLE_SITE_MANAGER: [
    {
      title: "My projects",
      description: "Projects you oversee.",
      href: "/projects",
      icon: FolderTree,
    },
    {
      title: "Permits",
      description: "Awaiting approval / today.",
      href: "/permits",
      icon: ShieldCheck,
      badgeKey: "pendingPermits",
    },
    {
      title: "Reports",
      description: "DPR rollups and exceptions.",
      href: "/reports",
      icon: BarChart3,
      permission: "REPORT.EXPORT",
    },
  ],
};

const FALLBACK_HERO: HeroTile[] = [
  {
    title: "Dashboards",
    description: "Cross-portfolio scorecards and tiles.",
    href: "/dashboards",
    icon: LayoutGrid,
    permission: "PORTFOLIO.READ",
  },
  {
    title: "Reports",
    description: "Earned-value, variance, executive summaries.",
    href: "/reports",
    icon: BarChart3,
    permission: "REPORT.EXPORT",
  },
  {
    title: "Projects",
    description: "Browse the portfolio.",
    href: "/projects",
    icon: FolderTree,
    permission: "PROJECT.READ",
  },
];

export function heroForRole(role: string | null): HeroTile[] {
  if (!role) return FALLBACK_HERO;
  return HEROES_BY_ROLE[role] ?? FALLBACK_HERO;
}

/**
 * The lifecycle grid is the long tail of tools, organised by intent rather than
 * the alphabetical sidebar. Sidebar remains the exhaustive index for power users.
 */
export const TOOL_COLUMNS: ToolColumn[] = [
  {
    title: "Plan",
    description: "Shape the work",
    icon: Layers,
    links: [
      { label: "Projects", href: "/projects", icon: FolderTree, permission: "PROJECT.READ" },
      // EPS, OBS, Portfolios are cross-portfolio admin surfaces — supervisor / field roles
      // hit /forbidden if they navigate directly; the tile is hidden too.
      { label: "EPS", href: "/eps", icon: Layers, permission: "PORTFOLIO.READ" },
      { label: "OBS", href: "/obs", icon: Network, permission: "ADMIN_ORG.READ" },
      { label: "Portfolios", href: "/portfolios", icon: Briefcase, permission: "PORTFOLIO.READ" },
      { label: "WBS templates", href: "/admin/wbs-templates", icon: FileText, adminOnly: true },
    ],
  },
  {
    title: "Run",
    description: "Day-to-day execution",
    icon: Activity,
    links: [
      // Calendars + Labour master are master-data screens (admin / planner tier).
      { label: "Calendars", href: "/admin/calendars", icon: Calendar, permission: "ADMIN_MASTER.READ" },
      { label: "Permits", href: "/permits", icon: ShieldCheck, permission: "PERMIT.READ" },
      // /resources is the cross-project resource pool — distinct from project-scoped resources tab.
      { label: "Resources", href: "/resources", icon: Users, permission: "ADMIN_MASTER.READ" },
      { label: "Labour master", href: "/labour-master", icon: HardHat, permission: "ADMIN_MASTER.READ" },
    ],
  },
  {
    title: "Analyze",
    description: "Insights & reporting",
    icon: TrendingUp,
    links: [
      // Cross-portfolio dashboards / analytics gate on PORTFOLIO.READ.
      { label: "Programme dashboard", href: "/dashboard", icon: Gauge, permission: "PORTFOLIO.READ" },
      { label: "Dashboards", href: "/dashboards", icon: LayoutGrid, permission: "PORTFOLIO.READ" },
      { label: "Reports", href: "/reports", icon: BarChart3, permission: "REPORT.EXPORT" },
      { label: "Analytics", href: "/analytics", icon: Sparkles, permission: "AI.WRITE" },
      { label: "Risk register", href: "/reports/risk-register", icon: ShieldAlert, permission: "RISK.READ" },
    ],
  },
  {
    title: "Configure",
    description: "Set up the system",
    icon: ListChecks,
    adminOnly: true,
    links: [
      { label: "Master data", href: "/admin/rate-master", icon: Database, adminOnly: true },
      { label: "Risk library", href: "/admin/risk-library", icon: Library, adminOnly: true },
      { label: "Users", href: "/admin/users", icon: Users, adminOnly: true },
      { label: "Integrations", href: "/admin/integrations", icon: Plug, adminOnly: true },
    ],
  },
];

/**
 * Group(s) to expand by default in the sidebar for a given role. "Plan" is always
 * expanded; this returns additional groups so the user lands with their primary
 * work-context already open. Order doesn't matter — the consumer turns it into a Set.
 */
export function defaultExpandedGroups(role: string | null): string[] {
  switch (role) {
    case "ROLE_ADMIN":
      return ["Plan", "Master Data"];
    case "ROLE_HSE_OFFICER":
    case "ROLE_FOREMAN":
    case "ROLE_SITE_ENGINEER":
      return ["Plan", "HSE & Permits"];
    case "ROLE_PROJECT_MANAGER":
      return ["Plan", "Control"];
    case "ROLE_RESOURCE_MANAGER":
      return ["Plan", "Resources"];
    default:
      return ["Plan"];
  }
}
