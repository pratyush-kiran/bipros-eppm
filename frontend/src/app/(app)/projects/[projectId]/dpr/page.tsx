"use client";

import { useEffect, useMemo, useState } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { dprApi } from "@/lib/api/dprApi";
import type {
  DailyProgressReportResponse,
  DprBaseFields,
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
import { getErrorMessage } from "@/lib/utils/error";
import { useStickyMeasure } from "@/hooks/useStickyMeasure";

const todayIso = () => new Date().toISOString().split("T")[0];

// Filter window defaults to "last 30 days" so freshly-saved rows (typically reportDate = today
// or very recent) are visible without the user touching the date inputs. Decoupled from the
// project's plannedStartDate, which was prone to hiding new entries when the project hadn't
// officially started yet (or had started more than a month ago).
const oneMonthAgoIso = () => {
  const d = new Date();
  d.setMonth(d.getMonth() - 1);
  return d.toISOString().split("T")[0];
};

interface DprPrefill {
  activityId: string | null;
  activityName: string | null;
  supervisorUserId: string | null;
  supervisorName: string | null;
  unit: string | null;
}

export default function DprPage() {
  const params = useParams();
  const projectId = params.projectId as string;
  const queryClient = useQueryClient();
  const router = useRouter();
  const searchParams = useSearchParams();

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
    // activityId → assigned supervisor (from Activity.responsibleResourceId snapshot). Used by
    // the form to cross-filter the supervisor/activity pickers and auto-fill on activity pick.
    const supervisorByActivityId = new Map<string, { id: string; name: string } | null>();
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
      supervisorByActivityId.set(
        a.id,
        a.responsibleResourceId
          ? { id: a.responsibleResourceId, name: a.responsibleResourceName ?? "" }
          : null
      );
      defaultUnitByActivityId.set(a.id, a.workActivityDefaultUnit ?? null);
    }
    return { opts, byName, byId, supervisorByActivityId, defaultUnitByActivityId };
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

  // Default window: last 30 days through today. Never anchor `to` to the
  // project's plannedFinishDate — for any project past its planned finish that
  // would produce a backwards range (from > to) and the list silently empties.
  // Users can widen the window via the date inputs when they need older rows.
  const [fromInput, setFromInput] = useState<string>(() => oneMonthAgoIso());
  const [toInput, setToInput] = useState<string>(() => todayIso());
  const [from, setFrom] = useState<string>(() => oneMonthAgoIso());
  const [to, setTo] = useState<string>(() => todayIso());

  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<DailyProgressReportResponse | null>(null);
  const [prefill, setPrefill] = useState<DprPrefill | null>(null);
  const [pageError, setPageError] = useState<string | null>(null);

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
    const sup = activityIndex.supervisorByActivityId.get(activityIdParam) ?? null;
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

  const { data: listData, isLoading, isFetching } = useQuery({
    queryKey: ["dpr", projectId, from, to],
    queryFn: () => dprApi.list(projectId, { from, to }),
    enabled: !!projectId && !!from && !!to,
  });

  const rows: DailyProgressReportResponse[] = listData?.data ?? [];

  const handleFilterSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setFrom(fromInput);
    setTo(toInput);
  };

  const openNew = () => {
    setEditing(null);
    setPrefill(null);
    setShowForm(true);
    setPageError(null);
  };

  const openEdit = (row: DailyProgressReportResponse) => {
    setEditing(row);
    setPrefill(null);
    setShowForm(true);
    setPageError(null);
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
    return saved.data ?? undefined;
  };

  const handleDelete = async (row: DailyProgressReportResponse) => {
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
            <button
              onClick={openNew}
              className="inline-flex items-center gap-1.5 rounded-md bg-gold px-4 py-2 text-sm font-semibold text-gold-ink hover:bg-gold-deep transition"
            >
              <Plus className="h-4 w-4" /> Add DPR
            </button>
          </div>
          <form onSubmit={handleFilterSubmit} className="mt-3 flex flex-wrap items-end gap-3">
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
              {isFetching ? "Loading…" : "Refresh"}
            </button>
          </form>
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
            supervisorByActivityId={activityIndex.supervisorByActivityId}
            defaultUnitByActivityId={activityIndex.defaultUnitByActivityId}
            boqOptions={boqOptions}
            defaultPrefill={prefill}
            onCancel={closeForm}
            onSave={handleSave}
          />
        </Drawer>

        <DprDayList
          rows={rows}
          onEdit={openEdit}
          onDelete={handleDelete}
          stickyOffset={dayStickyOffset}
        />
      </div>
    </div>
  );
}
