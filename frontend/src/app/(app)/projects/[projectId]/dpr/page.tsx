"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Eye, Calendar, User, Download } from "lucide-react";
import { dprApi } from "@/lib/api/dprApi";
import type {
  DailyProgressReportResponse,
  DprApprovalStatus,
  DprBaseFields,
  DprSummaryRow,
} from "@/lib/types/dpr";
import { projectApi } from "@/lib/api/projectApi";
import { activityApi } from "@/lib/api/activityApi";
import { boqApi } from "@/lib/api/boqApi";
import { userApi } from "@/lib/api/userApi";
// import { AiInsightsPanel } from "@/components/ai/AiInsightsPanel";
import { Drawer } from "@/components/common/Drawer";
import { TabTip } from "@/components/common/TabTip";
import { DprActivityForm } from "@/components/dpr/DprActivityForm";
import type { SelectOption } from "@/components/common/SearchableSelect";
import { DprDayList, DprDaySkeleton } from "@/components/dpr/DprDayList";
import { DprApprovalActions } from "@/components/dpr/DprApprovalActions";
import { DprDetailModal } from "@/components/dpr/DprDetailModal";
import { Badge, type BadgeVariant } from "@/components/ui/badge";
import { getErrorMessage } from "@/lib/utils/error";
import { useStickyMeasure } from "@/hooks/useStickyMeasure";
import { useAuthStore } from "@/lib/state/store";
import { cn } from "@/lib/utils/cn";

const todayIso = () => new Date().toISOString().split("T")[0];

// Fallback window when project dates are not yet loaded: today ± 180 days.
const fallbackFromIso = () => {
  const d = new Date();
  d.setDate(d.getDate() - 180);
  return d.toISOString().split("T")[0];
};
const fallbackToIso = () => {
  const d = new Date();
  d.setDate(d.getDate() + 180);
  return d.toISOString().split("T")[0];
};

interface DprPrefill {
  activityId: string | null;
  activityName: string | null;
  supervisorUserId: string | null;
  supervisorName: string | null;
  unit: string | null;
}

type ViewMode = "feed" | "approvals";

const STATUS_OPTIONS: Array<{ value: DprApprovalStatus | "ALL"; label: string }> = [
  { value: "ALL", label: "All" },
  { value: "DRAFT", label: "Draft" },
  { value: "SUBMITTED", label: "Submitted" },
  { value: "APPROVED", label: "Approved" },
  { value: "REJECTED", label: "Rejected" },
];

// ─── Approvals queue view ──────────────────────────────────────────────────────

const APPROVAL_STATUS_VARIANT: Record<DprApprovalStatus, BadgeVariant> = {
  DRAFT: "neutral",
  SUBMITTED: "info",
  APPROVED: "success",
  REJECTED: "danger",
};

function DprApprovalsView({ projectId }: { projectId: string }) {
  const { data: pendingData, isLoading: pendingLoading } = useQuery({
    queryKey: ["dpr-pending-approvals", projectId],
    queryFn: () => dprApi.pendingApprovals(projectId),
    enabled: !!projectId,
  });
  const { data: unassignedData, isLoading: unassignedLoading } = useQuery({
    queryKey: ["dpr-unassigned-approvals", projectId],
    queryFn: () => dprApi.unassignedApprovals(projectId),
    enabled: !!projectId,
  });

  const pendingRows: DprSummaryRow[] = pendingData?.data ?? [];
  const unassignedRows: DprSummaryRow[] = unassignedData?.data ?? [];

  return (
    <div className="space-y-8">
      {/* Pending my approval */}
      <section>
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-slate">
          Pending my approval
        </h2>
        {pendingLoading ? (
          <div className="space-y-2">
            <div className="h-14 animate-pulse rounded-lg bg-parchment/60" />
            <div className="h-14 animate-pulse rounded-lg bg-parchment/60" />
          </div>
        ) : pendingRows.length === 0 ? (
          <div className="rounded-lg border border-dashed border-hairline bg-ivory/30 px-6 py-8 text-center text-sm text-slate">
            No DPRs waiting for your approval.
          </div>
        ) : (
          <div className="space-y-2.5">
            {pendingRows.map((row) => (
              <ApprovalQueueRow key={row.id} projectId={projectId} row={row} />
            ))}
          </div>
        )}
      </section>

      {/* Unassigned pending */}
      <section>
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-slate">
          Unassigned pending
        </h2>
        {unassignedLoading ? (
          <div className="space-y-2">
            <div className="h-14 animate-pulse rounded-lg bg-parchment/60" />
          </div>
        ) : unassignedRows.length === 0 ? (
          <div className="rounded-lg border border-dashed border-hairline bg-ivory/30 px-6 py-8 text-center text-sm text-slate">
            No unassigned pending DPRs.
          </div>
        ) : (
          <div className="space-y-2.5">
            {unassignedRows.map((row) => (
              <ApprovalQueueRow key={row.id} projectId={projectId} row={row} />
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

function ApprovalQueueRow({ projectId, row }: { projectId: string; row: DprSummaryRow }) {
  const [open, setOpen] = useState(false);
  const fmt = (n: number | null | undefined) =>
    typeof n === "number" && Number.isFinite(n)
      ? n.toLocaleString(undefined, { maximumFractionDigits: 2 })
      : "—";
  const status = row.approvalStatus ?? "SUBMITTED";

  return (
    <div className="group rounded-xl border border-hairline bg-paper px-4 py-3 shadow-[0_1px_2px_rgba(28,28,28,0.04)] transition-all hover:border-gold/30 hover:shadow-[0_4px_16px_-8px_rgba(0,88,202,0.18)]">
      <div className="flex items-start justify-between gap-3">
        <button
          type="button"
          onClick={() => setOpen(true)}
          className="min-w-0 flex-1 text-left"
        >
          <div className="flex items-center gap-2">
            <span className="truncate font-semibold text-charcoal">{row.activityName}</span>
            <Badge variant={APPROVAL_STATUS_VARIANT[status]} withDot>
              {status}
            </Badge>
          </div>
          <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-slate">
            <span className="inline-flex items-center gap-1">
              <Calendar className="h-3 w-3 text-gold-deep" /> {row.reportDate}
            </span>
            {row.supervisorName && (
              <span className="inline-flex items-center gap-1">
                <User className="h-3 w-3 text-gold-deep" /> {row.supervisorName}
              </span>
            )}
            {row.boqItemNo && (
              <span className="rounded border border-hairline bg-ivory px-1.5 py-0.5 text-[11px] text-charcoal">
                BOQ {row.boqItemNo}
              </span>
            )}
            {row.qtyExecuted != null && (
              <span className="font-display font-semibold tabular-nums text-gold-ink">
                {fmt(row.qtyExecuted)}
                <span className="ml-0.5 text-[11px] font-normal text-slate">{row.unit}</span>
              </span>
            )}
          </div>
        </button>
        <div className="flex flex-none items-center gap-1.5">
          {/* Approve / Reject / Revoke — inline on the right (self-hides for non-approvers). */}
          <DprApprovalActions
            projectId={projectId}
            row={row}
            className="flex items-center gap-1.5"
          />
          <button
            type="button"
            onClick={() => setOpen(true)}
            className="rounded-md p-1.5 text-slate transition-colors hover:bg-ivory hover:text-gold-deep"
            aria-label="Preview details"
            title="Preview details"
          >
            <Eye className="h-4 w-4" />
          </button>
        </div>
      </div>
      <DprDetailModal
        projectId={projectId}
        row={row}
        open={open}
        onClose={() => setOpen(false)}
      />
    </div>
  );
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function DprPage() {
  const params = useParams();
  const projectId = params.projectId as string;
  const queryClient = useQueryClient();
  const router = useRouter();
  const searchParams = useSearchParams();
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canApprove = hasPermission("DPR.APPROVE");

  const { data: projectData } = useQuery({
    queryKey: ["project", projectId],
    queryFn: () => projectApi.getProject(projectId),
    enabled: !!projectId,
  });
  const project = projectData?.data;

  const { data: activitiesData } = useQuery({
    queryKey: ["activities", projectId],
    queryFn: () => activityApi.listActivities(projectId, 0, 1000),
    enabled: !!projectId,
  });
  // value=id so the form has a deterministic FK to fetch the activity's resource assignments;
  // label still shows the activity name. Build a side index name→id so we can resolve the id
  // for legacy DPRs whose payload only carries activityName.
  const activityIndex = useMemo(() => {
    const opts: SelectOption[] = [];
    const byName = new Map<string, string>();
    const byId = new Map<string, string>();
    // activityId → assigned supervisors (multi). Used by the form to cross-filter the
    // supervisor/activity pickers, auto-fill on activity pick, and warn when the chosen
    // DPR supervisor isn't in the activity's set. Empty list means no supervisor assigned.
    const supervisorsByActivityId = new Map<string, Array<{ id: string; name: string }>>();
    // activityId → the linked WorkActivity's default_unit. Drives the DPR form's unit auto-fill
    // when the user picks an activity, so DPRs default to the right unit and the Capacity
    // Utilization math doesn't divide Cum by nr.
    const defaultUnitByActivityId = new Map<string, string | null>();
    const seen = new Set<string>();
    for (const a of activitiesData?.data?.content ?? []) {
      if (seen.has(a.id)) continue;
      seen.add(a.id);
      opts.push({ value: a.id, label: a.name });
      if (!byName.has(a.name.toLowerCase())) byName.set(a.name.toLowerCase(), a.id);
      byId.set(a.id, a.name);
      // Prefer the multi-supervisor list; fall back to the legacy single cache for activities
      // that haven't been touched since the multi-supervisor rollout.
      const list: Array<{ id: string; name: string }> =
        a.supervisors && a.supervisors.length > 0
          ? a.supervisors.map((s) => ({ id: s.userId, name: s.userName ?? "" }))
          : a.supervisorUserId
            ? [{ id: a.supervisorUserId, name: a.supervisorUserName ?? "" }]
            : [];
      supervisorsByActivityId.set(a.id, list);
      defaultUnitByActivityId.set(a.id, a.workActivityDefaultUnit ?? null);
    }
    return { opts, byName, byId, supervisorsByActivityId, defaultUnitByActivityId };
  }, [activitiesData]);
  const activityOptions = activityIndex.opts;

  const { data: boqData } = useQuery({
    queryKey: ["boq", projectId],
    queryFn: () => boqApi.list(projectId),
    enabled: !!projectId,
  });
  const boqOptions = useMemo(
    () =>
      boqData?.data?.items
        ?.slice()
        .sort((a, b) =>
          a.itemNo.localeCompare(b.itemNo, undefined, { numeric: true, sensitivity: "base" })
        )
        .map((i) => ({
          value: i.itemNo,
          label: `${i.itemNo} — ${i.description}${i.unit ? ` (${i.unit})` : ""}`,
        })) ?? [],
    [boqData]
  );

  // Phase 4.4 RBAC: supervisor candidates come from the User pool, filtered server-side by
  // the SUPERVISOR / FOREMAN / SITE_ENGINEER / SITE_MANAGER roles. Same source as
  // SetSupervisorDialog so the activity-level picker and the DPR picker stay in sync. The
  // option `value` is a User UUID (previously a Resource UUID); the picker still appends
  // "Other (free-text)" downstream in DprActivityForm for ad-hoc names.
  const supervisorRoles = useMemo(
    () => ["SUPERVISOR", "FOREMAN", "SITE_ENGINEER", "SITE_MANAGER"],
    []
  );
  const { data: supervisorUsers } = useQuery({
    queryKey: ["users", "by-roles", supervisorRoles],
    queryFn: () => userApi.listByRoles(supervisorRoles),
  });
  const supervisorOptions = useMemo(
    () =>
      (supervisorUsers ?? [])
        .map((u) => {
          // Personnel-master employeeCode is the natural identifier on the picker row;
          // fall back to username so legacy / admin users without a code still render.
          const code = u.employeeCode || u.username;
          return {
            value: u.id,
            label: code && code !== u.name ? `${code} — ${u.name}` : u.name,
          };
        })
        .sort((a, b) => a.label.localeCompare(b.label)),
    [supervisorUsers]
  );

  // Default window: project's plannedStartDate → plannedFinishDate so all filed DPRs
  // are visible on first load regardless of reportDate. Falls back to today ± 180 d
  // while the project query is still in-flight so the first paint isn't blank.
  const [fromInput, setFromInput] = useState<string>(() => fallbackFromIso());
  const [toInput, setToInput] = useState<string>(() => fallbackToIso());
  const [from, setFrom] = useState<string>(() => fallbackFromIso());
  const [to, setTo] = useState<string>(() => fallbackToIso());

  // Once project data arrives, re-seed the window to the project's planned range.
  // We only do this once (guard with a ref) so a manual filter change isn't clobbered.
  const projectDatesSeeded = useState(false);
  useEffect(() => {
    if (!project || projectDatesSeeded[0]) return;
    const start = project.plannedStartDate ?? fallbackFromIso();
    const plannedFinish = project.plannedFinishDate ?? fallbackToIso();
    // Cap the end of the window at "today" at minimum: on a delayed/overrunning
    // project the planned finish is already in the past, so seeding TO to it would
    // hide every DPR filed after that date (including today's). Use the later of
    // planned-finish and today so current reports are always visible.
    const today = todayIso();
    const finish = plannedFinish > today ? plannedFinish : today;
    setFromInput(start);
    setToInput(finish);
    setFrom(start);
    setTo(finish);
    projectDatesSeeded[1](true);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [project]);

  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<DailyProgressReportResponse | null>(null);
  const [prefill, setPrefill] = useState<DprPrefill | null>(null);
  const [pageError, setPageError] = useState<string | null>(null);
  const [isGenerating, setIsGenerating] = useState(false);
  const [viewMode, setViewMode] = useState<ViewMode>("feed");
  const [statusFilter, setStatusFilter] = useState<DprApprovalStatus | "ALL">("ALL");

  // "Create DPR" deep-link from the Activities page lands here as ?new=1&activityId=<aid>.
  // We wait until both activities and the supervisor pool have loaded so the form's
  // initialState can resolve the activity name + supervisor snapshot synchronously — opening
  // earlier would seed an empty drawer that back-fills with a visible flicker. After
  // consuming the params we clear them via router.replace so refresh / Back doesn't re-open.
  // setState-in-effect is unavoidable here: the trigger is an external URL signal and the
  // codebase uses the same escape elsewhere (see gis-viewer, AiInsightsPanel).
  const newParam = searchParams.get("new");
  const activityIdParam = searchParams.get("activityId");
  const pendingPrefill: DprPrefill | null = useMemo(() => {
    if (newParam !== "1" || !activityIdParam) return null;
    if (!activitiesData || !supervisorUsers) return null;
    const activityName = activityIndex.byId.get(activityIdParam);
    if (!activityName) return null;
    // Multi-supervisor era: an activity may have several. The deep-link prefill seeds
    // the FIRST supervisor for the picker; the form's mismatch check is set-based.
    const sups = activityIndex.supervisorsByActivityId.get(activityIdParam) ?? [];
    const sup = sups[0] ?? null;
    const unit = activityIndex.defaultUnitByActivityId.get(activityIdParam) ?? null;
    return {
      activityId: activityIdParam,
      activityName,
      supervisorUserId: sup?.id ?? null,
      supervisorName: sup?.name ?? null,
      unit,
    };
  }, [newParam, activityIdParam, activitiesData, supervisorUsers, activityIndex]);
  useEffect(() => {
    if (!pendingPrefill || showForm) return;
    /* eslint-disable react-hooks/set-state-in-effect */
    setPrefill(pendingPrefill);
    setEditing(null);
    setShowForm(true);
    setPageError(null);
    /* eslint-enable react-hooks/set-state-in-effect */
    router.replace(`/projects/${projectId}/dpr`, { scroll: false });
  }, [pendingPrefill, showForm, router, projectId]);
  const { ref: stickyHeaderRef, height: stickyHeaderHeight } = useStickyMeasure<HTMLDivElement>();
  // tab-nav-h is the page-shell tab strip above the filter bar; day headers park beneath both.
  const dayStickyOffset = 53 + stickyHeaderHeight;

  const {
    data: listPages,
    isLoading,
    isFetchingNextPage,
    hasNextPage,
    fetchNextPage,
  } = useInfiniteQuery({
    queryKey: ["dpr", projectId, from, to],
    queryFn: ({ pageParam }) =>
      dprApi.list(projectId, { from, to, before: pageParam ?? undefined, days: 14 }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) =>
      lastPage.data?.hasMore ? lastPage.data.nextCursor ?? undefined : undefined,
    enabled: !!projectId && !!from && !!to,
  });

  const allRows: DprSummaryRow[] = useMemo(
    () => (listPages?.pages ?? []).flatMap((p) => p.data?.items ?? []),
    [listPages],
  );

  // Client-side status filter — lightweight since rows are already loaded.
  const rows: DprSummaryRow[] = useMemo(
    () =>
      statusFilter === "ALL"
        ? allRows
        : allRows.filter((r) => (r.approvalStatus ?? "DRAFT") === statusFilter),
    [allRows, statusFilter],
  );

  // Pending approvals count for the badge (always fetched when user has DPR.APPROVE).
  const { data: pendingBadgeData } = useQuery({
    queryKey: ["dpr-pending-approvals", projectId],
    queryFn: () => dprApi.pendingApprovals(projectId),
    enabled: !!projectId && canApprove,
  });
  const pendingCount = pendingBadgeData?.data?.length ?? 0;

  const sentinelRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const el = sentinelRef.current;
    if (!el) return;
    const io = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting && hasNextPage && !isFetchingNextPage) {
          fetchNextPage();
        }
      },
      { rootMargin: "600px" },
    );
    io.observe(el);
    return () => io.disconnect();
  }, [hasNextPage, isFetchingNextPage, fetchNextPage]);

  const handleFilterSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setFrom(fromInput);
    setTo(toInput);
  };

  // Generates the monthly "Daily Activity Costing" workbook for the applied date range
  // (one sheet per month, APPROVED DPRs only) and downloads it.
  const handleGenerateReport = async () => {
    setPageError(null);
    setIsGenerating(true);
    try {
      await dprApi.downloadMonthlyReport(projectId, from, to);
    } catch (err) {
      setPageError(getErrorMessage(err, "Failed to generate report"));
    } finally {
      setIsGenerating(false);
    }
  };

  const openNew = () => {
    setEditing(null);
    setPrefill(null);
    setShowForm(true);
    setPageError(null);
  };

  const openEdit = async (row: DprSummaryRow) => {
    setPageError(null);
    setPrefill(null);
    try {
      const full = await dprApi.get(projectId, row.id);
      setEditing(full.data ?? null);
      setShowForm(true);
    } catch (err: unknown) {
      setPageError(getErrorMessage(err, "Failed to load DPR for editing"));
    }
  };

  const closeForm = () => {
    setShowForm(false);
    setEditing(null);
    setPrefill(null);
  };

  const handleSave = async (payload: DprBaseFields) => {
    // Return the persisted record so DprActivityForm can chain photo uploads against the new id
    // when this is a create. We do NOT close the drawer here — the form runs its photo upload
    // step after onSave resolves, and then calls onCancel() itself to close. Closing here would
    // unmount the form mid-flight and drop the upload.
    const saved = editing
      ? await dprApi.update(projectId, editing.id, payload)
      : await dprApi.create(projectId, payload);
    queryClient.invalidateQueries({ queryKey: ["dpr", projectId, from, to] });
    queryClient.invalidateQueries({ queryKey: ["boq", projectId] });
    if (editing) {
      queryClient.invalidateQueries({ queryKey: ["dpr-detail", projectId, editing.id] });
    }
    return saved.data ?? undefined;
  };

  const handleDelete = async (row: DprSummaryRow) => {
    if (
      !confirm(
        `Delete DPR for ${row.activityName} on ${row.reportDate}? Linked BOQ qty will be rolled back.`
      )
    ) {
      return;
    }
    try {
      await dprApi.delete(projectId, row.id);
      queryClient.invalidateQueries({ queryKey: ["dpr", projectId, from, to] });
      queryClient.invalidateQueries({ queryKey: ["boq", projectId] });
      queryClient.removeQueries({ queryKey: ["dpr-detail", projectId, row.id] });
    } catch (err: unknown) {
      setPageError(getErrorMessage(err, "Failed to delete DPR"));
    }
  };

  if (isLoading) {
    return (
      <div className="space-y-6 p-6">
        <div className="h-9 w-72 animate-pulse rounded bg-parchment" />
        <DprDaySkeleton />
        <DprDaySkeleton />
        <DprDaySkeleton />
      </div>
    );
  }

  return (
    <div className="p-6">
      {/* <AiInsightsPanel
        projectId={projectId}
        endpoint={`/v1/projects/${projectId}/dpr/ai/insights`}
      /> */}
      <TabTip
        title="Daily Progress Report"
        description="Activity-level record of work executed each day — chainage, executed quantity, deployed manpower / equipment / material, weather, and remarks."
      />
      <div className="mb-6">
        <div
          ref={stickyHeaderRef}
          className="sticky top-[var(--tab-nav-h,53px)] z-20 -mx-6 mb-4 border-b border-hairline bg-paper/95 px-6 pt-2 pb-3 backdrop-blur"
        >
          <div className="flex flex-wrap items-end justify-between gap-3">
            <h1 className="font-display text-3xl font-bold text-charcoal">Daily Progress Report</h1>
            <div className="flex items-center gap-2">
              {/* View toggle — only for approvers */}
              {canApprove && (
                <div
                  role="group"
                  aria-label="DPR view"
                  className="flex items-center rounded-lg border border-hairline bg-ivory p-0.5"
                >
                  <button
                    type="button"
                    onClick={() => setViewMode("feed")}
                    className={cn(
                      "rounded-md px-3 py-1.5 text-sm font-semibold transition-colors",
                      viewMode === "feed"
                        ? "bg-paper text-charcoal shadow-sm"
                        : "text-slate hover:text-charcoal"
                    )}
                  >
                    Daily Reports
                  </button>
                  <button
                    type="button"
                    onClick={() => setViewMode("approvals")}
                    className={cn(
                      "relative inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-semibold transition-colors",
                      viewMode === "approvals"
                        ? "bg-paper text-charcoal shadow-sm"
                        : "text-slate hover:text-charcoal"
                    )}
                  >
                    Approvals
                    {pendingCount > 0 && (
                      <span className="inline-flex min-w-[18px] items-center justify-center rounded-full bg-gold px-1.5 py-0.5 text-[10px] font-bold leading-none text-gold-ink">
                        {pendingCount}
                      </span>
                    )}
                  </button>
                </div>
              )}
              <button
                onClick={openNew}
                className="inline-flex items-center gap-1.5 rounded-md bg-gold px-4 py-2 text-sm font-semibold text-gold-ink hover:bg-gold-deep transition"
              >
                <Plus className="h-4 w-4" /> Add DPR
              </button>
            </div>
          </div>

          {/* Feed filters — only shown in daily reports view */}
          {viewMode === "feed" && (
            <div className="mt-3 flex flex-wrap items-end gap-3">
              <form onSubmit={handleFilterSubmit} className="flex flex-wrap items-end gap-3">
                <div>
                  <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate">
                    From
                  </label>
                  <input
                    type="date"
                    value={fromInput}
                    onChange={(e) => setFromInput(e.target.value)}
                    className="rounded-md border border-hairline bg-paper px-3 py-2 text-sm text-charcoal focus:border-gold focus:outline-none focus:ring-1 focus:ring-gold/40"
                  />
                </div>
                <div>
                  <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate">
                    To
                  </label>
                  <input
                    type="date"
                    value={toInput}
                    onChange={(e) => setToInput(e.target.value)}
                    className="rounded-md border border-hairline bg-paper px-3 py-2 text-sm text-charcoal focus:border-gold focus:outline-none focus:ring-1 focus:ring-gold/40"
                  />
                </div>
                <button
                  type="submit"
                  className="rounded-md border border-hairline bg-paper px-4 py-2 text-sm font-semibold text-charcoal hover:bg-ivory"
                >
                  {isFetchingNextPage ? "Loading…" : "Refresh"}
                </button>
              </form>

              {/* Status filter */}
              <div>
                <label className="mb-1 block text-xs font-semibold uppercase tracking-wide text-slate">
                  Status
                </label>
                <div
                  role="group"
                  aria-label="Filter by status"
                  className="flex items-center gap-1"
                >
                  {STATUS_OPTIONS.map((opt) => (
                    <button
                      key={opt.value}
                      type="button"
                      onClick={() => setStatusFilter(opt.value)}
                      className={cn(
                        "rounded-md border px-3 py-2 text-sm font-semibold transition-colors",
                        statusFilter === opt.value
                          ? "border-gold bg-gold-tint text-gold-ink"
                          : "border-hairline bg-paper text-slate hover:bg-ivory hover:text-charcoal"
                      )}
                    >
                      {opt.label}
                    </button>
                  ))}
                </div>
              </div>

              <button
                type="button"
                onClick={handleGenerateReport}
                disabled={isGenerating}
                title="Download the monthly Daily Activity Costing workbook (approved DPRs) for the selected range"
                className="ml-auto inline-flex items-center gap-2 rounded-md border border-hairline bg-paper px-4 py-2 text-sm font-semibold text-charcoal hover:bg-ivory disabled:cursor-not-allowed disabled:opacity-60"
              >
                <Download className="h-4 w-4" />
                {isGenerating ? "Generating…" : "Generate Report"}
              </button>
            </div>
          )}
        </div>

        {pageError && <div className="mb-4 text-sm text-burgundy">{pageError}</div>}

        <Drawer
          open={showForm}
          onClose={closeForm}
          title={editing ? "Edit DPR" : "Add DPR"}
          widthClass="max-w-7xl"
        >
          <DprActivityForm
            // Re-mount when switching between edit targets (or new vs. edit) so the form's
            // lazy useState initializer reseeds. The prefill activity id is part of the key so
            // a second "Create DPR" deep-link for a different activity also reseeds, even
            // though both share the "new" path.
            key={editing?.id ?? (prefill?.activityId ? `new:${prefill.activityId}` : "new")}
            projectId={projectId}
            editing={editing}
            defaultDate={editing?.reportDate ?? todayIso()}
            supervisorOptions={supervisorOptions}
            activityOptions={activityOptions}
            activityNameById={activityIndex.byId}
            activityIdByName={activityIndex.byName}
            supervisorsByActivityId={activityIndex.supervisorsByActivityId}
            defaultUnitByActivityId={activityIndex.defaultUnitByActivityId}
            boqOptions={boqOptions}
            defaultPrefill={prefill}
            onCancel={closeForm}
            onSave={handleSave}
          />
        </Drawer>

        {viewMode === "approvals" ? (
          <DprApprovalsView projectId={projectId} />
        ) : (
          <>
            {statusFilter !== "ALL" && rows.length === 0 && allRows.length > 0 && (
              <div className="mb-4 rounded-lg border border-dashed border-hairline bg-ivory/30 px-6 py-8 text-center text-sm text-slate">
                No DPRs with status &ldquo;{statusFilter}&rdquo; in this date range.
              </div>
            )}
            <DprDayList
              rows={rows}
              projectId={projectId}
              onEdit={openEdit}
              onDelete={handleDelete}
              stickyOffset={dayStickyOffset}
            />
            <div ref={sentinelRef} aria-hidden className="h-1" />
            {isFetchingNextPage && (
              <div className="py-6 text-center text-sm text-slate">Loading older days…</div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
