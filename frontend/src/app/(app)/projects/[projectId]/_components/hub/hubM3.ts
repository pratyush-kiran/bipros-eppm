/**
 * Material-3 role mapping for the rebuilt project hub. Each section group maps
 * to one M3 role (primary/secondary/tertiary/error); ROLE_CLASSES holds the
 * literal Tailwind class strings (kept as literals here so the JIT sees them).
 * Used by QuickAccessTile, CardShell, and the bento card bodies. Section icons
 * map to Material Symbols names (rendered via <MIcon/>).
 */
import type { SectionGroupId } from "../../_data/projectNav";

export type M3Role = "primary" | "secondary" | "tertiary" | "error";

export const GROUP_ROLE: Record<SectionGroupId, M3Role> = {
  overview: "tertiary",
  plan: "primary",
  execute: "tertiary",
  people: "secondary",
  cost: "secondary",
  risk: "error",
  schedule: "primary",
  "insights-map": "tertiary",
};

export interface RoleClasses {
  border: string; // card top-accent border
  chip: string; // header chip + status pill
  iconBg: string; // quick-access icon chip
  tileFrom: string; // quick-access tile gradient start (legacy)
  tileTint: string; // clean role glow over a dark surface (gradient `from-`)
  text: string; // accent text
}

export const ROLE_CLASSES: Record<M3Role, RoleClasses> = {
  primary: {
    border: "border-primary-container",
    chip: "bg-primary-container/20 text-primary",
    iconBg: "bg-primary/20 text-primary",
    tileFrom: "from-primary-container/30",
    tileTint: "from-primary/15",
    text: "text-primary",
  },
  secondary: {
    border: "border-secondary-container",
    chip: "bg-secondary-container/20 text-secondary",
    iconBg: "bg-secondary/20 text-secondary",
    tileFrom: "from-secondary-container/30",
    tileTint: "from-secondary/15",
    text: "text-secondary",
  },
  tertiary: {
    border: "border-tertiary-container",
    chip: "bg-tertiary-container/20 text-tertiary",
    iconBg: "bg-tertiary/20 text-tertiary",
    tileFrom: "from-tertiary-container/30",
    tileTint: "from-tertiary/15",
    text: "text-tertiary",
  },
  error: {
    border: "border-error-container",
    chip: "bg-error-container/20 text-error",
    iconBg: "bg-error/20 text-error",
    tileFrom: "from-error-container/30",
    tileTint: "from-error/15",
    text: "text-error",
  },
};

export const roleClassesFor = (groupId: SectionGroupId): RoleClasses =>
  ROLE_CLASSES[GROUP_ROLE[groupId] ?? "primary"];

/** Material Symbols name per section id (falls back to a chevron). */
const SECTION_MS_ICON: Record<string, string> = {
  overview: "home",
  wbs: "account_tree",
  activities: "task_alt",
  boq: "inventory_2",
  baselines: "bookmark",
  dpr: "pending_actions",
  "material-consumption": "inventory",
  team: "group",
  capacity: "bolt",
  "general-expenses": "receipt_long",
  costs: "account_balance_wallet",
  dbs: "dataset",
  insights: "auto_graph",
  gis: "map",
  risks: "report_problem",
  contracts: "gavel",
  evm: "show_chart",
};

export const sectionMsIcon = (id: string): string => SECTION_MS_ICON[id] ?? "chevron_right";
