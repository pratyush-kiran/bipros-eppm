import {
  Activity,
  AlertTriangle,
  BarChart3,
  ClipboardList,
  FolderTree,
  Gauge,
  LayoutGrid,
  Plus,
  Settings,
  ShieldAlert,
  ShieldCheck,
  UserPlus,
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

const VIEW_DASHBOARD: HeroTile = {
  title: "Check Programme Health",
  description: "Portfolio KPIs, RAG mix, cash flow and risk heatmap.",
  href: "/dashboard",
  icon: Gauge,
};

// Hero copy is action-oriented (verb-first) on purpose. The home page is a
// launchpad — these are the things a layman lands and *does*, not the noun-y
// nav labels they'd browse for in the sidebar.
const HEROES_BY_ROLE: Record<string, HeroTile[]> = {
  ROLE_ADMIN: [
    {
      title: "Start a New Project",
      description: "Stand up a project from a WBS template.",
      href: "/projects/new",
      icon: Plus,
    },
    VIEW_DASHBOARD,
    {
      title: "Add Users",
      description: "Provision access, assign roles and modules.",
      href: "/admin/users",
      icon: UserPlus,
    },
    {
      title: "System Settings",
      description: "Master data, integrations, permissions.",
      href: "/admin/settings",
      icon: Settings,
    },
  ],
  ROLE_PROJECT_MANAGER: [
    {
      title: "My Projects",
      description: "Active projects you're managing.",
      href: "/projects",
      icon: FolderTree,
      badgeKey: "atRiskProjects",
    },
    {
      title: "New Project",
      description: "Stand up a project from a WBS template.",
      href: "/projects/new",
      icon: Plus,
    },
    {
      title: "Risk Register",
      description: "Open risks across your portfolio.",
      href: "/reports/risk-register",
      icon: ShieldAlert,
      badgeKey: "criticalRisks",
    },
    VIEW_DASHBOARD,
  ],
  ROLE_HSE_OFFICER: [
    {
      title: "Permits Awaiting Approval",
      description: "Review and approve open permits.",
      href: "/permits",
      icon: ShieldCheck,
      badgeKey: "pendingPermits",
    },
    {
      title: "New Permit",
      description: "Raise a permit-to-work request.",
      href: "/permits/new",
      icon: Plus,
    },
    {
      title: "Workflow Reference",
      description: "Approval routing and PPE requirements.",
      href: "/permits/workflow",
      icon: Workflow,
    },
    {
      title: "Compliance Reports",
      description: "Audits, near-misses, training compliance.",
      href: "/reports",
      icon: BarChart3,
    },
  ],
  ROLE_SITE_ENGINEER: [
    {
      title: "Today's Permits",
      description: "Permits issued for your area today.",
      href: "/permits",
      icon: ShieldCheck,
      badgeKey: "pendingPermits",
    },
    {
      title: "My Activities",
      description: "Tasks assigned to your crew this week.",
      href: "/projects",
      icon: ClipboardList,
      badgeKey: "overdueTasks",
    },
    {
      title: "Update Progress",
      description: "Daily progress reports and photos.",
      href: "/projects",
      icon: Activity,
    },
    {
      title: "Report Safety Issue",
      description: "Raise an incident or near-miss.",
      href: "/permits/new",
      icon: AlertTriangle,
    },
  ],
  ROLE_FOREMAN: [
    {
      title: "Today's Permits",
      description: "Permits cleared for your crew.",
      href: "/permits",
      icon: ShieldCheck,
      badgeKey: "pendingPermits",
    },
    {
      title: "Submit Progress",
      description: "Daily productivity and quantities.",
      href: "/projects",
      icon: Activity,
    },
  ],
  ROLE_SUPERVISOR: [
    {
      title: "My Projects",
      description: "Projects you're enrolled on.",
      href: "/projects",
      icon: FolderTree,
    },
    {
      title: "Today's Permits",
      description: "Permits for your area.",
      href: "/permits",
      icon: ShieldCheck,
      badgeKey: "pendingPermits",
    },
    {
      title: "Update Progress",
      description: "Daily DPRs, snags, handovers.",
      href: "/projects",
      icon: Activity,
    },
  ],
  ROLE_SITE_MANAGER: [
    {
      title: "My Projects",
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
