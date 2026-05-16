"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import type { LucideIcon } from "lucide-react";
import {
  Award, Banknote, BarChart3, Briefcase, Building2, Calculator, Calendar,
  ChevronDown, ChevronLeft, ChevronRight, CircleDollarSign, ClipboardCheck, Contact, FileText, FolderTree, Gauge,
  Grid, HardHat, Home, LayoutGrid, Layers, Library, ListChecks, LogOut,
  Network, Plug, Settings, ShieldCheck, SlidersHorizontal, Sparkles, Tag,
  UserCog, Users, UsersRound, Workflow,
} from "lucide-react";
import { cn } from "@/lib/utils/cn";
import { useAppStore, useAuthStore } from "@/lib/state/store";
import { useThemeStore } from "@/lib/state/themeStore";
import type { IcpmsModule } from "@/lib/types";
import { useAccess } from "@/lib/auth/useAccess";
import { useAuth } from "@/lib/auth/useAuth";
import { useMostSeniorRole } from "@/hooks/useMostSeniorRole";
import { useActiveLogo, useAppName } from "@/hooks/useThemeManager";
import { defaultExpandedGroups } from "@/components/hub/hubConfig";

const COLLAPSED_GROUPS_STORAGE_KEY = "bipros.sidebar.groups.v1";

/**
 * Optional gating fields:
 *   - {@code permission} requires the user to hold the named fine-grained code (e.g.
 *     {@code "PROJECT.READ"}). Source of truth: Phase 2 backend resolves these into
 *     {@code UserResponse.permissions} on {@code /v1/auth/me}. ADMIN short-circuits.
 *   - {@code module} requires VIEW-or-better access to the named IC-PMS module
 *   - {@code adminOnly} is shorthand for "ROLE_ADMIN required"
 *   - {@code requireRoles} is an OR list of acceptable roles
 *
 * Items with no gate fields stay visible to every authenticated user.
 */
type NavItem = {
  name: string;
  href: string;
  icon: LucideIcon;
  permission?: string;
  module?: IcpmsModule;
  adminOnly?: boolean;
  requireRoles?: readonly string[];
};
type NavSubGroup = { label: string; items: NavItem[] };
type NavGroup = {
  label: string;
  items: NavItem[];
  subGroups?: NavSubGroup[];
  adminOnly?: boolean;
};

const groups: NavGroup[] = [
  {
    label: "Plan",
    items: [
      { name: "Home", href: "/", icon: Home },
      { name: "Portfolios", href: "/portfolios", icon: Briefcase, permission: "PORTFOLIO.READ" },
      { name: "Projects", href: "/projects", icon: FolderTree, module: "M1_WBS_GIS", permission: "PROJECT.READ" },
      { name: "EPS", href: "/eps", icon: Layers, module: "M1_WBS_GIS", permission: "PROJECT.READ" },
      { name: "OBS", href: "/obs", icon: Network, module: "M1_WBS_GIS", permission: "PROJECT.READ" },
      { name: "QC", href: "/qc", icon: ClipboardCheck, module: "M1_WBS_GIS", permission: "NCR.READ" },
      { name: "Dashboards", href: "/dashboards", icon: LayoutGrid, module: "M9_REPORTS", permission: "REPORT.READ" },
    ],
  },
  {
    label: "Execute",
    items: [
      { name: "Calendars", href: "/admin/calendars", icon: Calendar, module: "M2_SCHEDULE_EVM", permission: "SCHEDULE.READ" },
    ],
  },
  {
    label: "Control",
    items: [
      { name: "Reports", href: "/reports", icon: BarChart3, module: "M9_REPORTS", permission: "REPORT.READ" },
    ],
  },
  /* {
    label: "HSE & Permits",
    items: [
      {
        name: "Permits", href: "/permits", icon: ShieldCheck,
        requireRoles: ["FOREMAN", "SITE_ENGINEER", "HSE_OFFICER", "PROJECT_MANAGER", "ADMIN"]
      },
      { name: "Workflow Reference", href: "/permits/workflow", icon: Workflow },
    ],
  }, */

  // Nationalities admin page exists at /admin/nationalities (still routable, still seeded
  // and consumed by the resource form's nationality datalist) but intentionally hidden
  // from the sidebar — the form's autocomplete is the only place it surfaces.
  {
    label: "Admin",
    adminOnly: true,
    items: [
      { name: "Users", href: "/admin/users", icon: UsersRound, adminOnly: true, permission: "ADMIN_USER.READ" },
      { name: "Profiles", href: "/admin/profiles", icon: ShieldCheck, adminOnly: true, permission: "ADMIN_PROFILE.READ" },
      /* { name: "Organisations", href: "/admin/organisations", icon: Building2, adminOnly: true, permission: "ADMIN_ORG.READ" }, */
      /* { name: "User Access", href: "/admin/user-access", icon: UserCog, adminOnly: true, permission: "ADMIN_USER.READ" }, */
      { name: "Risk Scoring Matrix", href: "/admin/risk-scoring-matrix", icon: Grid, adminOnly: true, permission: "ADMIN_MASTER.READ" },
      // { name: "WBS Templates", href: "/admin/wbs-templates", icon: FileText, adminOnly: true },
      // { name: "Unit Rate Master", href: "/admin/unit-rate-master", icon: Banknote, adminOnly: true },
      /* { name: "Cost Accounts", href: "/admin/cost-accounts", icon: CircleDollarSign, adminOnly: true, permission: "ADMIN_MASTER.READ" }, */
      { name: "Integrations", href: "/admin/integrations", icon: Plug, adminOnly: true, permission: "ADMIN_SETTINGS.READ" },
      /* { name: "User Defined Fields", href: "/admin/udf", icon: SlidersHorizontal, adminOnly: true, permission: "ADMIN_SETTINGS.READ" }, */
      { name: "Settings", href: "/admin/settings", icon: Settings, adminOnly: true, permission: "ADMIN_SETTINGS.READ" },
    ],
    subGroups: [
      {
        label: "Resources",
        /* adminOnly: true, */
        items: [
          { name: "Resource Types", href: "/admin/resource-types", icon: ListChecks, adminOnly: true, permission: "RESOURCE.READ" },
          { name: "Resource Roles", href: "/admin/resource-roles", icon: Contact, adminOnly: true, permission: "RESOURCE.READ" },
          /* { name: "Resources", href: "/resources", icon: Users, adminOnly: true, permission: "RESOURCE.READ" }, */
        ],
      },
      {
        label: "Master Data",
        items: [
          /* { name: "Categories", href: "/admin/manpower-categories", icon: FolderTree, adminOnly: true }, */
          { name: "Formulas", href: "/admin/formulas", icon: Calculator, adminOnly: true, permission: "ADMIN_MASTER.READ" },
          { name: "Productivity Norms", href: "/admin/productivity-norms", icon: Gauge, adminOnly: true, permission: "ADMIN_MASTER.READ" },
          { name: "Work Activities", href: "/admin/work-activities", icon: ListChecks, adminOnly: true, permission: "ADMIN_MASTER.READ" },
          { name: "Risk Library", href: "/admin/risk-library", icon: Library, adminOnly: true, permission: "ADMIN_MASTER.READ" },
          { name: "Risk Categories", href: "/admin/risk-categories", icon: Layers, adminOnly: true, permission: "ADMIN_MASTER.READ" },
          { name: "Employment Types", href: "/admin/employment-types", icon: Briefcase, adminOnly: true, permission: "ADMIN_MASTER.READ" },
          { name: "Skills", href: "/admin/skills", icon: Sparkles, adminOnly: true, permission: "ADMIN_MASTER.READ" },
          { name: "Skill Levels", href: "/admin/skill-levels", icon: Award, adminOnly: true, permission: "ADMIN_MASTER.READ" },
          { name: "Grades", href: "/admin/grades", icon: Award, adminOnly: true, permission: "ADMIN_MASTER.READ" },
          { name: "Material Categories", href: "/admin/material-categories", icon: FolderTree, adminOnly: true, permission: "ADMIN_MASTER.READ" },
          /* { name: "Rate Master", href: "/admin/rate-master", icon: Banknote, adminOnly: true, permission: "ADMIN_MASTER.READ" }, */
          { name: "Project Categories", href: "/admin/project-categories", icon: Tag, adminOnly: true, permission: "ADMIN_MASTER.READ" },
          {
            name: "Permits", href: "/permits", icon: ShieldCheck,
            requireRoles: ["FOREMAN", "SITE_ENGINEER", "HSE_OFFICER", "PROJECT_MANAGER", "ADMIN"],
            permission: "PERMIT.READ",
          },
          { name: "Workflow Reference", href: "/permits/workflow", icon: Workflow, permission: "PERMIT.READ" },
        ],
      },
    ],
  },
];

function isActive(pathname: string, href: string) {
  if (href === "/") return pathname === "/";
  return pathname === href || pathname.startsWith(href + "/");
}

function renderNavItem(item: NavItem, pathname: string, sidebarCollapsed: boolean) {
  const active = isActive(pathname, item.href);
  return (
    <Link
      key={item.href}
      href={item.href}
      title={sidebarCollapsed ? item.name : undefined}
      className={cn(
        "relative flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-[13px] font-medium transition-colors",
        active
          ? "text-charcoal font-semibold bg-[linear-gradient(90deg,rgba(212,175,55,0.09),rgba(212,175,55,0)_90%)]"
          : "text-charcoal hover:bg-ivory",
        sidebarCollapsed && "justify-center"
      )}
    >
      {active && (
        <span
          aria-hidden
          className="absolute left-[-13px] top-1.5 bottom-1.5 w-[3px] rounded-r-[3px] bg-gold"
        />
      )}
      <item.icon
        size={sidebarCollapsed ? 20 : 16}
        className={cn("shrink-0", active ? "text-gold-deep" : "text-slate")}
        strokeWidth={1.5}
      />
      {!sidebarCollapsed && <span className="truncate">{item.name}</span>}
    </Link>
  );
}

export function Sidebar() {
  const pathname = usePathname();
  const { sidebarCollapsed, toggleSidebar } = useAppStore();
  const { user, clearAuth } = useAuthStore();
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const { isAdmin, hasAnyRole } = useAuth();
  const { canAccessModule } = useAccess();
  const router = useRouter();
  const logoSrc = useActiveLogo();
  const appName = useAppName();

  // Zustand `persist` rehydrates synchronously from localStorage on the client,
  // so user/role state on the first client render diverges from SSR (where the
  // store still holds initial null state). Without this guard the auth-gated
  // links land in the client tree but not the server tree → hydration mismatch.
  const [hydrated, setHydrated] = useState(false);
  useEffect(() => {
    setHydrated(true);
  }, []);

  const handleLogout = () => {
    document.cookie = "access_token=; path=/; max-age=0";
    useThemeStore.getState().clearBackendIds();
    clearAuth();
    router.push("/auth/login");
  };

  // Filter the static groups by the current user's roles + fine-grained permissions +
  // module access. ADMIN sees everything (each gate short-circuits internally for admins).
  // Pre-hydration we render as if unauthenticated so SSR and first-client-render match.
  const itemVisible = (item: NavItem) => {
    if (!hydrated) {
      return !item.adminOnly && !item.requireRoles && !item.permission && !item.module;
    }
    if (item.adminOnly && !isAdmin) return false;
    if (item.requireRoles && !hasAnyRole(item.requireRoles)) return false;
    if (item.permission && !hasPermission(item.permission)) return false;
    if (item.module && !canAccessModule(item.module)) return false;
    return true;
  };
  const visibleGroups: NavGroup[] = groups.flatMap((group) => {
    if (group.adminOnly && (!hydrated || !isAdmin)) return [];
    const visibleItems = group.items.filter(itemVisible);
    const visibleSubGroups: NavSubGroup[] = (group.subGroups ?? [])
      .map((sg) => ({ ...sg, items: sg.items.filter(itemVisible) }))
      .filter((sg) => sg.items.length > 0);
    if (visibleItems.length === 0 && visibleSubGroups.length === 0) return [];
    const result: NavGroup = { ...group, items: visibleItems, subGroups: visibleSubGroups };
    return [result];
  });

  // Pick the most "senior" role for the user-chip role label and to seed default-expanded
  // sidebar groups. Shared with the hub via useMostSeniorRole so both stay in sync.
  // Pre-hydration we collapse the label back to the anonymous default for SSR parity.
  const { role: seniorRole, label: roleLabelTitle } = useMostSeniorRole();
  const roleLabel = (hydrated ? roleLabelTitle : "User").toLowerCase();

  // Persist per-group collapsed state. Default-expanded set is derived from role on first
  // visit; once the user toggles anything we trust localStorage and stop re-deriving.
  const defaultExpanded = useMemo(
    () => new Set(defaultExpandedGroups(seniorRole ?? null)),
    [seniorRole],
  );
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(() => new Set());
  const [hydratedGroups, setHydratedGroups] = useState(false);
  useEffect(() => {
    if (typeof window === "undefined") return;
    try {
      const raw = window.localStorage.getItem(COLLAPSED_GROUPS_STORAGE_KEY);
      if (raw) {
        const arr = JSON.parse(raw) as string[];
        setCollapsedGroups(new Set(arr));
      } else {
        // First visit: collapse every group that isn't in the role-based default set,
        // and collapse all subgroups (composite key `${group}::${subgroup}`) by default.
        const initial = new Set<string>();
        for (const g of groups) {
          if (!defaultExpanded.has(g.label)) initial.add(g.label);
          for (const sg of g.subGroups ?? []) initial.add(`${g.label}::${sg.label}`);
        }
        setCollapsedGroups(initial);
      }
    } catch { /* localStorage unavailable */ }
    setHydratedGroups(true);
  }, [defaultExpanded]);

  const toggleGroup = (label: string) => {
    setCollapsedGroups((prev) => {
      const next = new Set(prev);
      if (next.has(label)) next.delete(label);
      else next.add(label);
      try {
        window.localStorage.setItem(
          COLLAPSED_GROUPS_STORAGE_KEY,
          JSON.stringify(Array.from(next)),
        );
      } catch { /* localStorage unavailable */ }
      return next;
    });
  };

  // Same SSR-safety rule as itemVisible: until the persist store has rehydrated,
  // pretend the user is the anonymous placeholder so the server-rendered chip matches
  // what the client paints on the first frame.
  const displayUser = hydrated ? user : null;
  const displayName = displayUser?.firstName ?? displayUser?.username ?? "User";
  const initials = displayName.slice(0, 2).toUpperCase();

  return (
    <aside
      className={cn(
        "flex flex-col bg-paper border-r border-hairline transition-[width] duration-200",
        sidebarCollapsed ? "w-16" : "w-[260px]"
      )}
    >
      {/* Brand + collapse */}
      <div className="flex items-center justify-between border-b border-hairline px-4 py-5">
        {!sidebarCollapsed && (
          <div className="flex items-center gap-2.5">
            <img
              src={logoSrc}
              alt={appName.primary}
              width={32}
              height={32}
              className="h-8 w-8 rounded-lg object-contain"
            />
            <div className="flex flex-col leading-none">
              <span className="font-display font-semibold text-lg text-logo-primary tracking-tight">
                {appName.primary}
              </span>
              <span className="text-[9px] font-semibold uppercase tracking-[0.2em] text-logo-secondary mt-0.5">
                {appName.secondary}
              </span>
            </div>
          </div>
        )}
        {sidebarCollapsed && (
          <img
            src={logoSrc}
            alt={appName.primary}
            width={32}
            height={32}
            className="mx-auto h-8 w-8 rounded-lg object-contain"
          />
        )}
        {!sidebarCollapsed && (
          <button
            onClick={toggleSidebar}
            aria-label="Collapse sidebar"
            className="rounded-md p-1 text-slate hover:bg-ivory hover:text-gold-deep"
          >
            <ChevronLeft size={16} />
          </button>
        )}
      </div>

      {sidebarCollapsed && (
        <button
          onClick={toggleSidebar}
          aria-label="Expand sidebar"
          className="mx-auto my-2 rounded-md p-1 text-slate hover:bg-ivory hover:text-gold-deep"
        >
          <ChevronRight size={16} />
        </button>
      )}

      {/* Nav */}
      <nav className="flex-1 overflow-y-auto px-3 pb-3 pt-2">
        {visibleGroups.map((group) => {
          // Collapse only kicks in when sidebar is full-width and we've hydrated from
          // localStorage; before that everything renders open to avoid the initial-paint flash.
          const isCollapsed =
            !sidebarCollapsed && hydratedGroups && collapsedGroups.has(group.label);
          const itemCount =
            group.items.length +
            (group.subGroups ?? []).reduce((s, sg) => s + sg.items.length, 0);
          return (
            <div key={group.label} className="mb-1">
              {!sidebarCollapsed && (
                <button
                  type="button"
                  onClick={() => toggleGroup(group.label)}
                  data-testid="sidebar-group-toggle"
                  data-group={group.label}
                  aria-expanded={!isCollapsed}
                  className="flex w-full items-center justify-between rounded px-2.5 pt-4 pb-1.5 text-[9px] font-semibold uppercase tracking-[0.14em] text-ash hover:text-gold-deep"
                >
                  <span className="flex items-center gap-1.5">
                    <ChevronDown
                      size={10}
                      strokeWidth={2.5}
                      className={cn(
                        "shrink-0 transition-transform duration-150",
                        isCollapsed && "-rotate-90",
                      )}
                    />
                    {group.label}
                  </span>
                  {isCollapsed && itemCount > 1 && (
                    <span className="text-[9px] font-semibold tracking-wider text-ash">
                      ({itemCount})
                    </span>
                  )}
                </button>
              )}
              {!isCollapsed && (
                <>
                  {group.items.map((item) => renderNavItem(item, pathname, sidebarCollapsed))}
                  {(group.subGroups ?? []).map((sg) => {
                    const subKey = `${group.label}::${sg.label}`;
                    const subCollapsed =
                      !sidebarCollapsed && hydratedGroups && collapsedGroups.has(subKey);
                    return (
                      <div key={sg.label} className="mt-1">
                        {!sidebarCollapsed && (
                          <button
                            type="button"
                            onClick={() => toggleGroup(subKey)}
                            data-testid="sidebar-subgroup-toggle"
                            data-group={group.label}
                            data-subgroup={sg.label}
                            aria-expanded={!subCollapsed}
                            className="flex w-full items-center justify-between rounded ml-3 px-2.5 pt-2 pb-1 text-[9px] font-semibold uppercase tracking-[0.12em] text-ash/80 hover:text-gold-deep"
                          >
                            <span className="flex items-center gap-1.5">
                              <ChevronDown
                                size={10}
                                strokeWidth={2.5}
                                className={cn(
                                  "shrink-0 transition-transform duration-150",
                                  subCollapsed && "-rotate-90",
                                )}
                              />
                              {sg.label}
                            </span>
                            {subCollapsed && sg.items.length > 1 && (
                              <span className="text-[9px] font-semibold tracking-wider text-ash">
                                ({sg.items.length})
                              </span>
                            )}
                          </button>
                        )}
                        {!subCollapsed && (
                          <div className={sidebarCollapsed ? "" : "ml-3 border-l border-hairline pl-1"}>
                            {sg.items.map((item) => renderNavItem(item, pathname, sidebarCollapsed))}
                          </div>
                        )}
                      </div>
                    );
                  })}
                </>
              )}
            </div>
          );
        })}
      </nav>

      {/* User chip */}
      {!sidebarCollapsed ? (
        <div className="border-t border-hairline p-3">
          <div className="flex items-center gap-2.5 rounded-[10px] px-2.5 py-2 hover:bg-ivory">
            <div
              className="flex h-8 w-8 items-center justify-center rounded-full bg-parchment text-gold-deep font-display font-semibold text-xs"
              style={{ border: "2px solid #D4AF37" }}
            >
              {initials}
            </div>
            <div className="min-w-0 flex-1 leading-tight">
              <div className="truncate text-[13px] font-semibold text-charcoal">
                {displayName}
              </div>
              <div className="truncate text-[10px] font-semibold uppercase tracking-[0.08em] text-gold-deep">
                {roleLabel}
              </div>
            </div>
            <button
              onClick={handleLogout}
              aria-label="Sign out"
              className="rounded-md p-1.5 text-slate hover:bg-paper hover:text-burgundy"
            >
              <LogOut size={14} />
            </button>
          </div>
        </div>
      ) : (
        <div className="flex flex-col items-center gap-2 border-t border-hairline px-2 py-3">
          <div
            title={`${displayName} · ${roleLabel}`}
            className="flex h-9 w-9 items-center justify-center rounded-full bg-parchment text-gold-deep font-display font-semibold text-xs"
            style={{ border: "2px solid #D4AF37" }}
          >
            {initials}
          </div>
          <button
            onClick={handleLogout}
            aria-label="Sign out"
            title="Sign out"
            className="flex h-9 w-9 items-center justify-center rounded-md text-slate hover:bg-ivory hover:text-burgundy"
          >
            <LogOut size={18} strokeWidth={1.5} />
          </button>
        </div>
      )}
    </aside>
  );
}
