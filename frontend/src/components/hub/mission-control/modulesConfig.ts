import {
  Award,
  BarChart3,
  Briefcase,
  Calculator,
  Calendar,
  ClipboardCheck,
  Contact,
  FolderTree,
  Gauge,
  Grid as GridIcon,
  HardHat,
  Layers,
  LayoutGrid,
  Library,
  ListChecks,
  Network,
  Settings,
  ShieldAlert,
  ShieldCheck,
  Sparkles,
  Tag,
  UsersRound,
  Workflow,
  type LucideIcon,
} from "lucide-react";

export type ModuleColorKey =
  | "gold"
  | "emerald"
  | "indigo"
  | "sky"
  | "violet"
  | "orange"
  | "teal"
  | "burgundy"
  | "amber"
  | "slate"
  | "bronze"
  | "rose";

export interface ColorPalette {
  /** Accent color used for hover border, arrow, accent strokes. */
  accent: string;
  /** Mid-tone used as the icon-square background. */
  iconBg: string;
  /** Stronger tone used as the icon glyph color (sits on iconBg). */
  iconFg: string;
}

// Curated module accent palette. Each color is chosen for distinguishability
// at small sizes; the iconBg is intentionally near-paper so tiles read as
// neutral surfaces with only the icon glyph carrying the color identity.
export const COLOR_PALETTES: Record<ModuleColorKey, ColorPalette> = {
  gold: { accent: "#D4AF37", iconBg: "#FBF6E2", iconFg: "#B8962E" },
  emerald: { accent: "#10B981", iconBg: "#ECFDF5", iconFg: "#047857" },
  indigo: { accent: "#6366F1", iconBg: "#EEF2FF", iconFg: "#4338CA" },
  sky: { accent: "#0EA5E9", iconBg: "#F0F9FF", iconFg: "#0369A1" },
  violet: { accent: "#8B5CF6", iconBg: "#F5F3FF", iconFg: "#6D28D9" },
  orange: { accent: "#F97316", iconBg: "#FFF7ED", iconFg: "#C2410C" },
  teal: { accent: "#14B8A6", iconBg: "#F0FDFA", iconFg: "#0F766E" },
  burgundy: { accent: "#9B2C2C", iconBg: "#FEF2F2", iconFg: "#7F1D1D" },
  amber: { accent: "#D97706", iconBg: "#FFFBEB", iconFg: "#92400E" },
  slate: { accent: "#64748B", iconBg: "#F8FAFC", iconFg: "#334155" },
  bronze: { accent: "#C7882E", iconBg: "#FBF4E2", iconFg: "#92400E" },
  rose: { accent: "#E11D48", iconBg: "#FFF1F2", iconFg: "#9F1239" },
};

export interface ModuleTileDef {
  key: string;
  title: string;
  description: string;
  href: string;
  icon: LucideIcon;
  color: ModuleColorKey;
  /** Permission code required to open this module — checked against the auth store. */
  permission?: string;
  /** If true, only ROLE_ADMIN sees the tile. */
  adminOnly?: boolean;
  /** OR-list of acceptable roles; if set, user must hold at least one. */
  requireRoles?: readonly string[];
}

export type ModuleVariant = "hero" | "compact";

// Roles allowed to see enterprise/portfolio-structure modules (EPS, OBS).
// Site-work roles (SUPERVISOR, FOREMAN, SITE_ENGINEER, TEAM_MEMBER) don't need
// to see the org-wide structure — only their own assigned activities.
const PLANNING_ROLES = [
  "ADMIN",
  "EXECUTIVE",
  "PMO",
  "FINANCE",
  "PROJECT_MANAGER",
  "SCHEDULER",
  "PLANNING_ENGINEER",
  "RESOURCE_MANAGER",
  "SITE_MANAGER",
  "CONSTRUCTION_MANAGER",
  "VIEWER",
  "CLIENT",
] as const;

export interface ModuleSectionDef {
  label: string;
  intro?: string;
  variant: ModuleVariant;
  /** Tailwind column class for this section's grid. */
  gridClass: string;
  tiles: ModuleTileDef[];
}

export const MODULE_SECTIONS: ModuleSectionDef[] = [
  {
    label: "Plan",
    intro: "Set up the work — what, where, who, and how it rolls up.",
    variant: "hero",
    gridClass: "grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3",
    tiles: [
      {
        key: "portfolios",
        title: "Portfolios",
        description: "Programme rollups across all projects",
        href: "/portfolios",
        icon: Briefcase,
        color: "indigo",
        permission: "PORTFOLIO.READ",
      },
      {
        key: "projects",
        title: "Projects",
        description: "WBS, GIS, schedules and daily work",
        href: "/projects",
        icon: FolderTree,
        color: "emerald",
        permission: "PROJECT.READ",
      },
      {
        key: "eps",
        title: "EPS",
        description: "Enterprise project structure",
        href: "/eps",
        icon: Layers,
        color: "sky",
        permission: "PROJECT.READ",
        // Enterprise/portfolio structure is a planning concern, not site work.
        // Hides EPS from SUPERVISOR / FOREMAN / SITE_ENGINEER / TEAM_MEMBER who
        // only need their own activities, not the org-wide breakdown.
        requireRoles: PLANNING_ROLES,
      },
      {
        key: "obs",
        title: "OBS",
        description: "Org & reporting hierarchy",
        href: "/obs",
        icon: Network,
        color: "violet",
        permission: "PROJECT.READ",
        requireRoles: PLANNING_ROLES,
      },
      {
        key: "qc",
        title: "QC",
        description: "Quality control, NCRs, snags",
        href: "/qc",
        icon: ClipboardCheck,
        color: "orange",
        permission: "NCR.READ",
      },
      {
        key: "dashboards",
        title: "Dashboards",
        description: "Cross-portfolio scorecards & KPIs",
        href: "/dashboards",
        icon: LayoutGrid,
        color: "gold",
        permission: "REPORT.READ",
      },
    ],
  },
  {
    label: "Execute & Control",
    intro: "Run the work, approve it, report on it.",
    variant: "hero",
    gridClass: "grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3",
    tiles: [
      {
        key: "calendars",
        title: "Calendars",
        description: "Working time, shifts and holidays",
        href: "/admin/calendars",
        icon: Calendar,
        color: "teal",
        permission: "SCHEDULE.READ",
      },
      {
        key: "reports",
        title: "Reports",
        description: "EVM, variance, executive summaries",
        href: "/reports",
        icon: BarChart3,
        color: "burgundy",
        permission: "REPORT.READ",
      },
      {
        key: "permits",
        title: "Permits",
        description: "Permit-to-work approvals and SMS",
        href: "/permits",
        icon: ShieldCheck,
        color: "amber",
        permission: "PERMIT.READ",
      },
    ],
  },
  {
    label: "Admin",
    intro: "People, security and system.",
    variant: "compact",
    gridClass: "grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5",
    tiles: [
      {
        key: "users",
        title: "Users",
        description: "Provision access & roles",
        href: "/admin/users",
        icon: UsersRound,
        color: "sky",
        permission: "ADMIN_USER.READ",
        adminOnly: true,
      },
      {
        key: "profiles",
        title: "Profiles",
        description: "Permission profiles",
        href: "/admin/profiles",
        icon: ShieldAlert,
        color: "violet",
        permission: "ADMIN_PROFILE.READ",
        adminOnly: true,
      },
      {
        key: "risk-matrix",
        title: "Risk matrix",
        description: "Risk scoring",
        href: "/admin/risk-scoring-matrix",
        icon: GridIcon,
        color: "burgundy",
        permission: "ADMIN_MASTER.READ",
        adminOnly: true,
      },
      {
        key: "hds-library",
        title: "HDS library",
        description: "Design system",
        href: "/admin/hds-library",
        icon: Library,
        color: "bronze",
        permission: "HDS_LIBRARY.READ",
        adminOnly: true,
      },
      {
        key: "settings",
        title: "Settings",
        description: "System settings",
        href: "/admin/settings",
        icon: Settings,
        color: "slate",
        permission: "ADMIN_SETTINGS.READ",
        adminOnly: true,
      },
    ],
  },
  {
    label: "Master data",
    intro: "Configure the building blocks the rest of the app uses.",
    variant: "compact",
    gridClass: "grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5",
    tiles: [
      { key: "formulas", title: "Formulas", description: "Computation library", href: "/admin/formulas", icon: Calculator, color: "bronze", permission: "ADMIN_MASTER.READ", adminOnly: true },
      { key: "productivity-norms", title: "Productivity norms", description: "Crew & equipment rates", href: "/admin/productivity-norms", icon: Gauge, color: "bronze", permission: "ADMIN_MASTER.READ", adminOnly: true },
      { key: "work-activities", title: "Work activities", description: "Activity catalogue", href: "/admin/work-activities", icon: ListChecks, color: "bronze", permission: "ADMIN_MASTER.READ", adminOnly: true },
      { key: "risk-library", title: "Risk library", description: "Standard risks", href: "/admin/risk-library", icon: Library, color: "bronze", permission: "ADMIN_MASTER.READ", adminOnly: true },
      { key: "risk-categories", title: "Risk categories", description: "Risk taxonomy", href: "/admin/risk-categories", icon: Layers, color: "bronze", permission: "ADMIN_MASTER.READ", adminOnly: true },
      { key: "employment-types", title: "Employment types", description: "Contract / direct / SC", href: "/admin/employment-types", icon: Briefcase, color: "bronze", permission: "ADMIN_MASTER.READ", adminOnly: true },
      { key: "sub-contractors", title: "Sub-contractors", description: "Vendor master", href: "/admin/sub-contractors", icon: HardHat, color: "bronze", permission: "ADMIN_MASTER.READ", adminOnly: true },
      { key: "skills", title: "Skills", description: "Trade & craft skills", href: "/admin/skills", icon: Sparkles, color: "bronze", permission: "ADMIN_MASTER.READ", adminOnly: true },
      { key: "skill-levels", title: "Skill levels", description: "Proficiency tiers", href: "/admin/skill-levels", icon: Award, color: "bronze", permission: "ADMIN_MASTER.READ", adminOnly: true },
      { key: "grades", title: "Grades", description: "Pay-grade master", href: "/admin/grades", icon: Award, color: "bronze", permission: "ADMIN_MASTER.READ", adminOnly: true },
      { key: "material-categories", title: "Material categories", description: "Material taxonomy", href: "/admin/material-categories", icon: FolderTree, color: "bronze", permission: "ADMIN_MASTER.READ", adminOnly: true },
      { key: "project-categories", title: "Project categories", description: "Project taxonomy", href: "/admin/project-categories", icon: Tag, color: "bronze", permission: "ADMIN_MASTER.READ", adminOnly: true },
      { key: "resource-types", title: "Resource types", description: "Resource taxonomy", href: "/admin/resource-types", icon: ListChecks, color: "bronze", permission: "RESOURCE.READ", adminOnly: true },
      { key: "resource-roles", title: "Resource roles", description: "Role assignments", href: "/admin/resource-roles", icon: Contact, color: "bronze", permission: "RESOURCE.READ", adminOnly: true },
      { key: "permits-workflow", title: "Permit workflow", description: "Approval routing & PPE", href: "/permits/workflow", icon: Workflow, color: "bronze", permission: "PERMIT.READ" },
    ],
  },
];
