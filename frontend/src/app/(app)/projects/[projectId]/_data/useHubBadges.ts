"use client";

/**
 * Phase B — Quick Access launcher live badges.
 *
 * Fires the existing per-domain API calls in parallel via React Query and
 * returns a typed bag of badges keyed by section id (from {@link SECTIONS}).
 * Each tile in the launcher reads {@code badges[s.id]} — if undefined, the
 * tile renders icon + label only. No hard-coded values; every figure comes
 * from a backend response.
 *
 * Permission-gated calls (Costs, Risks) use {@code enabled} so we never
 * fire a request the user can't make. Loading is the OR of every in-flight
 * query so tiles can show shimmer while their badge resolves.
 */

import { useQuery } from "@tanstack/react-query";
import { boqApi } from "@/lib/api/boqApi";
import { budgetApi } from "@/lib/api/budgetApi";
import { costApi } from "@/lib/api/costApi";
import { dashboardApi, isUtilisationKpiCode } from "@/lib/api/dashboardApi";
import { dprApi } from "@/lib/api/dprApi";
import { generalExpensesApi } from "@/lib/api/generalExpensesApi";
import { materialConsumptionApi } from "@/lib/api/materialConsumptionApi";
import { projectApi } from "@/lib/api/projectApi";
import { projectInsightsApi } from "@/lib/api/projectInsightsApi";
import { projectResourceApi } from "@/lib/api/projectResourceApi";
import { projectTeamApi } from "@/lib/api/projectTeamApi";
import { riskApi, isOpenRisk } from "@/lib/api/riskApi";
import { stretchApi } from "@/lib/api/stretchApi";
import { useAuthStore } from "@/lib/state/store";
import {
  currentYearMonth,
  formatBudgetAmount,
  todayIso,
  unwrapArray,
} from "./projectHubHelpers";

export type HubBadgeTone = "success" | "warn" | "danger" | "info";

export interface HubBadge {
  text: string;
  tone?: HubBadgeTone;
}

export type HubBadges = Partial<Record<string, HubBadge>>;

const STALE = 30_000;
const GC = 5 * 60_000;
const baseQueryOpts = {
  staleTime: STALE,
  gcTime: GC,
  refetchOnWindowFocus: false,
} as const;

export function useHubBadges(projectId: string): {
  badges: HubBadges;
  loading: boolean;
} {
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canReadCost = hasPermission("COST.READ");
  const canReadRisk = hasPermission("RISK.READ");

  // The hub already fetches the project with this exact key — we share its cache.
  const projectQuery = useQuery({
    queryKey: ["project", projectId],
    queryFn: () => projectApi.getProject(projectId),
    ...baseQueryOpts,
  });
  const currency = projectQuery.data?.data?.budgetCurrency ?? null;

  // ── Activities ────────────────────────────────────────────────────────────
  const activitiesQuery = useQuery({
    queryKey: ["project", projectId, "hub-badge", "activities"],
    queryFn: () => projectInsightsApi.getActivityStatus(projectId),
    ...baseQueryOpts,
  });

  // ── BOQ ────────────────────────────────────────────────────────────────────
  const boqQuery = useQuery({
    queryKey: ["project", projectId, "hub-badge", "boq"],
    queryFn: () => boqApi.list(projectId),
    ...baseQueryOpts,
  });

  // ── Team / Resource Pool ──────────────────────────────────────────────────
  // Kept for any bento cards (e.g. PeopleCapacityCard) that still query it.
  // Badge composition itself now uses {@link projectTeamApi} below.
  const teamQuery = useQuery({
    queryKey: ["project", projectId, "hub-badge", "team"],
    queryFn: () => projectResourceApi.listPool(projectId),
    ...baseQueryOpts,
  });

  // ── Project Team (org chart — PM, CM, Engineer, etc.) ─────────────────────
  // Drives the Team badge member count AND the DBS badge distinct-role count.
  const projectTeamQuery = useQuery({
    queryKey: ["project", projectId, "hub-badge", "project-team"],
    queryFn: () => projectTeamApi.list(projectId),
    ...baseQueryOpts,
  });

  // ── General Expenses (current month) ──────────────────────────────────────
  const yearMonth = currentYearMonth();
  const expensesQuery = useQuery({
    queryKey: ["project", projectId, "hub-badge", "general-expenses", yearMonth],
    queryFn: () => generalExpensesApi.getActuals(projectId, yearMonth),
    ...baseQueryOpts,
  });

  // ── General Expenses plan items ───────────────────────────────────────────
  // Used as the preferred badge: plan-item count is more useful than ₹0 MTD
  // before any actuals are logged. Falls back to MTD when plan items aren't
  // loaded yet.
  const expensesPlanQuery = useQuery({
    queryKey: ["project", projectId, "hub-badge", "general-expenses-plan"],
    queryFn: () => generalExpensesApi.listPlanItems(projectId),
    ...baseQueryOpts,
  });

  // ── DPR (today) ───────────────────────────────────────────────────────────
  const today = todayIso();
  const dprQuery = useQuery({
    queryKey: ["project", projectId, "hub-badge", "dpr", today],
    queryFn: () => dprApi.list(projectId, { from: today, to: today, days: 1 }),
    ...baseQueryOpts,
  });

  // ── KPI Snapshots (insights alerts + capacity utilisation) ────────────────
  // Snapshot rows reference {@code kpiDefinitionId}; we cross-reference KPI
  // definitions to read the human {@code code} (so we can pick capacity).
  const kpiSnapshotsQuery = useQuery({
    queryKey: ["project", projectId, "hub-badge", "kpi-snapshots"],
    queryFn: () => dashboardApi.getProjectKpiSnapshots(projectId),
    ...baseQueryOpts,
  });
  const kpiDefinitionsQuery = useQuery({
    queryKey: ["hub-badge", "kpi-definitions"],
    queryFn: () => dashboardApi.getKpiDefinitions(),
    ...baseQueryOpts,
  });

  // ── Costs ──────────────────────────────────────────────────────────────────
  const costSummaryQuery = useQuery({
    queryKey: ["project", projectId, "hub-badge", "cost-summary"],
    queryFn: () => costApi.getCostSummary(projectId),
    enabled: canReadCost,
    ...baseQueryOpts,
  });

  // ── Risks ──────────────────────────────────────────────────────────────────
  const risksQuery = useQuery({
    queryKey: ["project", projectId, "hub-badge", "risks-open"],
    queryFn: () => riskApi.listRisks(projectId),
    enabled: canReadRisk,
    ...baseQueryOpts,
  });

  // ── Material consumption (today) ──────────────────────────────────────────
  const materialQuery = useQuery({
    queryKey: ["project", projectId, "hub-badge", "material-consumption", today],
    queryFn: () =>
      materialConsumptionApi.list(projectId, { from: today, to: today }),
    ...baseQueryOpts,
  });

  // ── WBS phase count (top-level nodes from budget summary) ─────────────────
  // We reuse the budget summary endpoint because it already returns every WBS
  // node with `wbsLevel`, so we can count `wbsLevel === 1` without inventing a
  // new API call.
  const wbsQuery = useQuery({
    queryKey: ["project", projectId, "hub-badge", "wbs-phases"],
    queryFn: () => budgetApi.getWbsBudgetSummary(projectId),
    ...baseQueryOpts,
  });

  // ── GIS stretches count ───────────────────────────────────────────────────
  const stretchesQuery = useQuery({
    queryKey: ["project", projectId, "hub-badge", "stretches"],
    queryFn: () => stretchApi.listByProject(projectId),
    ...baseQueryOpts,
  });

  // ── Compose badges ────────────────────────────────────────────────────────
  const badges: HubBadges = {};

  // Activities
  if (activitiesQuery.data) {
    const rows = activitiesQuery.data;
    const total = rows.length;
    const done = rows.filter((r) => r.status === "COMPLETED").length;
    badges["activities"] = { text: `${total} total · ${done} done` };
  }

  // BOQ
  if (boqQuery.data?.data) {
    const itemsCount = boqQuery.data.data.items.length;
    badges["boq"] = { text: `${itemsCount} items` };
  }

  // Team — from the project team org chart (PM, Engineer, Supervisor, etc.),
  // NOT the resource pool (equipment/labour). The hub launcher tile points
  // users to /team, which renders this same dataset. projectTeamApi.list
  // returns ApiResponse<ProjectTeamMember[]>.
  if (projectTeamQuery.data?.data) {
    const members = projectTeamQuery.data.data;
    badges["team"] = {
      text: `${members.length} member${members.length === 1 ? "" : "s"}`,
    };

    // DBS — distinct role count from the project team org chart.
    const roleSet = new Set(members.map((m) => m.role));
    if (roleSet.size > 0) {
      badges["dbs"] = {
        text: `${roleSet.size} role${roleSet.size === 1 ? "" : "s"}`,
      };
    }
  }

  // General Expenses — prefer plan-item count (more useful at a glance), fall
  // back to MTD only when plan items haven't loaded yet. listPlanItems may
  // return either ApiResponse<T[]> (with `.data`) or an already-unwrapped
  // array depending on the axios interceptor path — handle both shapes.
  const planItemsRaw = expensesPlanQuery.data as unknown;
  const planItems = Array.isArray(planItemsRaw)
    ? (planItemsRaw as unknown[])
    : Array.isArray((planItemsRaw as { data?: unknown })?.data)
      ? ((planItemsRaw as { data: unknown[] }).data)
      : undefined;
  if (planItems && planItems.length > 0) {
    badges["general-expenses"] = { text: `${planItems.length} plan items` };
  } else if (expensesQuery.data?.data) {
    const monthlyTotal = expensesQuery.data.data.monthlyTotal ?? 0;
    badges["general-expenses"] = {
      text: `${formatBudgetAmount(monthlyTotal, currency)} MTD`,
    };
  }

  // DPR
  if (dprQuery.data?.data) {
    const items = dprQuery.data.data.items ?? [];
    const loggedToday = items.some((it) => it.reportDate === today);
    badges["dpr"] = loggedToday
      ? { text: "Today logged", tone: "success" }
      : { text: "Pending today", tone: "warn" };
  }

  // Capacity utilisation — find the Resource Utilisation KPI (code RESOURCE_UTIL).
  // dashboardApi's getProjectKpiSnapshots / getKpiDefinitions don't unwrap the
  // ApiResponse envelope, so use unwrapArray to be defensive over both shapes.
  const kpiDefs = unwrapArray<{ id: string; code: string }>(kpiDefinitionsQuery.data);
  const kpiSnaps = unwrapArray<{ kpiDefinitionId: string; value: number; status: string }>(
    kpiSnapshotsQuery.data,
  );

  if (kpiDefs && kpiSnaps) {
    const capacityDef = kpiDefs.find((d) => isUtilisationKpiCode(d.code));
    if (capacityDef) {
      const snap = kpiSnaps.find((s) => s.kpiDefinitionId === capacityDef.id);
      if (snap) {
        badges["capacity"] = {
          text: `${Math.round(snap.value)}% utilised`,
        };
      }
    }
  }

  // Insights — count amber/red snapshots
  if (kpiSnaps) {
    const alertCount = kpiSnaps.filter(
      (s) => s.status === "AMBER" || s.status === "RED",
    ).length;
    badges["insights"] = {
      text: `${alertCount} new alerts`,
      tone: alertCount > 0 ? "warn" : undefined,
    };
  }

  // Costs — CPI from cost summary
  if (canReadCost && costSummaryQuery.data?.data) {
    const cpi = costSummaryQuery.data.data.costPerformanceIndex;
    if (cpi != null) {
      badges["costs"] = { text: `CPI ${cpi.toFixed(2)}` };
    }
  }

  // Risks — always render when data has loaded: prefer critical (CRIMSON/RED RAG)
  // → fall back to total open → fall back to a clean "No open risks" success pill.
  if (canReadRisk && risksQuery.data?.data) {
    const all = risksQuery.data.data.filter((r) => isOpenRisk(r.status));
    const critical = all.filter((r) => r.rag === "CRIMSON" || r.rag === "RED").length;
    if (critical > 0) {
      badges["risks"] = { text: `${critical} critical`, tone: "danger" };
    } else if (all.length > 0) {
      badges["risks"] = { text: `${all.length} active` };
    } else {
      badges["risks"] = { text: "No open risks", tone: "success" };
    }
  }

  // Material consumption — entries logged today
  if (materialQuery.data?.data) {
    const count = materialQuery.data.data.length;
    badges["material-consumption"] = { text: `${count} today` };
  }

  // WBS — count top-level phases (wbsLevel === 1) from the budget summary tree.
  if (wbsQuery.data?.data?.nodes) {
    const phaseCount = wbsQuery.data.data.nodes.filter(
      (n) => n.wbsLevel === 1,
    ).length;
    if (phaseCount > 0) {
      badges["wbs"] = {
        text: `${phaseCount} phase${phaseCount === 1 ? "" : "s"}`,
      };
    }
  }

  // GIS — number of stretches defined for the project. Always render a "Map"
  // pill so the tile has a hint, even when no stretches are configured yet.
  if (stretchesQuery.data?.data) {
    const count = stretchesQuery.data.data.length;
    if (count > 0) {
      badges["gis"] = {
        text: `Map · ${count} stretch${count === 1 ? "" : "es"}`,
      };
    } else {
      badges["gis"] = { text: "Map" };
    }
  }

  // Loading: OR over every query that's actually in flight
  const loading =
    projectQuery.isLoading ||
    activitiesQuery.isLoading ||
    boqQuery.isLoading ||
    teamQuery.isLoading ||
    projectTeamQuery.isLoading ||
    expensesQuery.isLoading ||
    expensesPlanQuery.isLoading ||
    dprQuery.isLoading ||
    kpiSnapshotsQuery.isLoading ||
    kpiDefinitionsQuery.isLoading ||
    materialQuery.isLoading ||
    wbsQuery.isLoading ||
    stretchesQuery.isLoading ||
    (canReadCost && costSummaryQuery.isLoading) ||
    (canReadRisk && risksQuery.isLoading);

  return { badges, loading };
}
