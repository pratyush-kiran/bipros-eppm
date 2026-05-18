"use client";

import { useEffect, useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import {
  materialConsumptionReportApi,
  type MaterialConsumptionFilters,
  type MaterialConsumptionGroupBy,
  type MaterialConsumptionReportResponse,
  type MaterialConsumptionRow,
} from "@/lib/api/materialConsumptionReportApi";
import { projectApi } from "@/lib/api/projectApi";
import { projectTeamApi } from "@/lib/api/projectTeamApi";
import { materialRateMasterApi } from "@/lib/api/materialRateMasterApi";
import { userApi, type UserSummary } from "@/lib/api/userApi";
import { getErrorMessage } from "@/lib/utils/error";

// Alert chip colour map. Codes match
// MaterialConsumptionAlertEvaluator's public constants.
const ALERT_STYLES: Record<string, string> = {
  EXCESS_CONSUMPTION: "bg-amber-500/10 text-amber-600 border-amber-500/30",
  NEGATIVE_BALANCE: "bg-rose-500/10 text-rose-600 border-rose-500/30",
  BUDGET_OVERCONSUMPTION: "bg-red-500/15 text-red-700 border-red-500/40",
  MISSING_UNIT_RATE: "bg-blue-500/10 text-blue-600 border-blue-500/30",
};

const GROUP_BY_OPTIONS: Array<{ value: "" | MaterialConsumptionGroupBy; label: string }> = [
  { value: "", label: "No grouping" },
  { value: "DAY", label: "By day" },
  { value: "MATERIAL", label: "By material" },
  { value: "ACTIVITY", label: "By activity" },
  { value: "SUPERVISOR", label: "By supervisor" },
];

function fmtNum(v: number | null | undefined, digits = 2): string {
  if (v === null || v === undefined) return "—";
  return Number(v).toLocaleString("en-IN", {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  });
}

function fmtPercent(v: number | null | undefined): string {
  if (v === null || v === undefined) return "—";
  return (Number(v) * 100).toFixed(2) + "%";
}

function AlertChip({ code }: { code: string }) {
  const style = ALERT_STYLES[code] ?? "bg-surface-active/40 text-text-secondary border-border";
  return (
    <span
      className={`inline-flex items-center rounded-full border px-2 py-0.5 text-[10px] font-semibold tracking-wide ${style}`}
    >
      {code}
    </span>
  );
}

export default function MaterialConsumptionReportPage() {
  const params = useParams();
  const projectId = params.projectId as string;

  const { data: projectData } = useQuery({
    queryKey: ["project", projectId],
    queryFn: () => projectApi.getProject(projectId),
    enabled: !!projectId,
  });
  const project = projectData?.data;

  // Filters in draft + applied form, so the user can edit without re-querying.
  const [draft, setDraft] = useState<MaterialConsumptionFilters>({});
  const [applied, setApplied] = useState<MaterialConsumptionFilters>({});

  useEffect(() => {
    if (!project) return;
    if (!draft.from && project.plannedStartDate) {
      const from = project.plannedStartDate;
      const to = project.plannedFinishDate ?? new Date().toISOString().split("T")[0];
      const seeded: MaterialConsumptionFilters = { from, to };
      setDraft(seeded);
      setApplied(seeded);
    }
  }, [project, draft.from]);

  // Supervisor dropdown: project_team SUPERVISOR rows.
  const { data: supervisorTeam } = useQuery({
    queryKey: ["project-team-supervisors", projectId],
    queryFn: () => projectTeamApi.list(projectId, "SUPERVISOR"),
    enabled: !!projectId,
  });

  // Storekeeper picker: prefer directory users with STOREKEEPER role; fall back to text
  // input when none are returned.
  const { data: storekeepers } = useQuery<UserSummary[]>({
    queryKey: ["users-by-role", "STOREKEEPER"],
    queryFn: () => userApi.listByRoles(["STOREKEEPER"]),
  });

  // Material rate master dropdown — used so the user can scope the report to one SKU.
  const { data: materialRates } = useQuery({
    queryKey: ["material-rate-masters"],
    queryFn: () => materialRateMasterApi.list(),
  });

  const { data, isLoading, error, refetch, isFetching } = useQuery({
    queryKey: ["material-consumption-report", projectId, applied],
    queryFn: () => materialConsumptionReportApi.generate(projectId, applied),
    enabled: !!projectId && !!applied.from && !!applied.to,
  });

  const report: MaterialConsumptionReportResponse | undefined = data?.data ?? undefined;
  const rows = useMemo(() => report?.rows ?? [], [report]);

  const handleApply = () => setApplied({ ...draft });
  const handleReset = () => {
    const cleared: MaterialConsumptionFilters = {
      from: draft.from,
      to: draft.to,
    };
    setDraft(cleared);
    setApplied(cleared);
  };

  const handleExport = async () => {
    try {
      const blob = await materialConsumptionReportApi.downloadExcel(projectId, applied);
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `material-consumption-${applied.from ?? "start"}_${applied.to ?? "end"}.xlsx`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch (err) {
      // Quiet — the report itself surfaces any error state.
      console.error("Material consumption Excel export failed", err);
    }
  };

  const supervisorOptions = (supervisorTeam?.data ?? []).map((m) => ({
    value: m.userId,
    label: m.reportsToName || m.username || m.userId,
  }));
  // Project team rows don't always carry the supervisor's display name — fall back to
  // the firstName/lastName the API does project, then the username, then the raw UUID.
  const supervisorDisplay = (supervisorTeam?.data ?? []).map((m) => {
    const display =
      [m.firstName, m.lastName].filter(Boolean).join(" ").trim() ||
      m.username ||
      m.userId;
    return { value: m.userId, label: display };
  });
  // Prefer the display variant when present.
  const supOptions = supervisorDisplay.length > 0 ? supervisorDisplay : supervisorOptions;

  return (
    <div className="space-y-4 p-4">
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Material Consumption Report</h1>
          <p className="text-sm text-text-muted">
            Planned vs issued vs consumed across the project window, with cost variance and
            alerts. Read-only.
          </p>
        </div>
      </div>

      {/* Filter panel */}
      <div className="rounded-lg border border-border bg-surface p-4">
        <div className="grid grid-cols-1 gap-3 md:grid-cols-3 lg:grid-cols-4">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-text-secondary">From</span>
            <input
              type="date"
              value={draft.from ?? ""}
              onChange={(e) => setDraft({ ...draft, from: e.target.value || undefined })}
              className="rounded border border-border bg-background px-2 py-1"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-text-secondary">To</span>
            <input
              type="date"
              value={draft.to ?? ""}
              onChange={(e) => setDraft({ ...draft, to: e.target.value || undefined })}
              className="rounded border border-border bg-background px-2 py-1"
            />
          </label>

          <label className="flex flex-col gap-1 text-sm">
            <span className="text-text-secondary">Supervisor</span>
            <select
              value={draft.supervisorUserId ?? ""}
              onChange={(e) =>
                setDraft({ ...draft, supervisorUserId: e.target.value || undefined })
              }
              className="rounded border border-border bg-background px-2 py-1"
            >
              <option value="">All supervisors</option>
              {supOptions.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>

          <label className="flex flex-col gap-1 text-sm">
            <span className="text-text-secondary">Storekeeper</span>
            {storekeepers && storekeepers.length > 0 ? (
              <select
                value={draft.storekeeperUserId ?? ""}
                onChange={(e) =>
                  setDraft({ ...draft, storekeeperUserId: e.target.value || undefined })
                }
                className="rounded border border-border bg-background px-2 py-1"
              >
                <option value="">All storekeepers</option>
                {storekeepers.map((u) => (
                  <option key={u.id} value={u.id}>
                    {u.name || u.username}
                  </option>
                ))}
              </select>
            ) : (
              <input
                type="text"
                placeholder="user id (no storekeepers found)"
                value={draft.storekeeperUserId ?? ""}
                onChange={(e) =>
                  setDraft({ ...draft, storekeeperUserId: e.target.value || undefined })
                }
                className="rounded border border-border bg-background px-2 py-1"
              />
            )}
          </label>

          <label className="flex flex-col gap-1 text-sm">
            <span className="text-text-secondary">Material</span>
            <select
              value={draft.materialRateMasterId ?? ""}
              onChange={(e) =>
                setDraft({ ...draft, materialRateMasterId: e.target.value || undefined })
              }
              className="rounded border border-border bg-background px-2 py-1"
            >
              <option value="">All materials</option>
              {(materialRates?.data ?? []).map((m) => (
                <option key={m.id} value={m.id}>
                  {m.categoryName ? `${m.categoryName} — ${m.specGrade}` : m.specGrade}
                </option>
              ))}
            </select>
          </label>

          <label className="flex flex-col gap-1 text-sm">
            <span className="text-text-secondary">WBS node id</span>
            <input
              type="text"
              placeholder="optional"
              value={draft.wbsNodeId ?? ""}
              onChange={(e) => setDraft({ ...draft, wbsNodeId: e.target.value || undefined })}
              className="rounded border border-border bg-background px-2 py-1"
            />
          </label>

          <label className="flex flex-col gap-1 text-sm">
            <span className="text-text-secondary">Activity id</span>
            <input
              type="text"
              placeholder="optional"
              value={draft.activityId ?? ""}
              onChange={(e) => setDraft({ ...draft, activityId: e.target.value || undefined })}
              className="rounded border border-border bg-background px-2 py-1"
            />
          </label>

          <label className="flex flex-col gap-1 text-sm">
            <span className="text-text-secondary">Group by</span>
            <select
              value={draft.groupBy ?? ""}
              onChange={(e) =>
                setDraft({
                  ...draft,
                  groupBy: (e.target.value || undefined) as
                    | MaterialConsumptionGroupBy
                    | undefined,
                })
              }
              className="rounded border border-border bg-background px-2 py-1"
            >
              {GROUP_BY_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>
        </div>

        <div className="mt-3 flex gap-2">
          <button
            type="button"
            onClick={handleApply}
            className="rounded bg-accent px-3 py-1.5 text-sm font-medium text-white hover:bg-accent/90"
          >
            Apply filters
          </button>
          <button
            type="button"
            onClick={handleReset}
            className="rounded border border-border bg-surface px-3 py-1.5 text-sm hover:bg-surface-active"
          >
            Reset
          </button>
          <button
            type="button"
            onClick={handleExport}
            disabled={!report}
            className="rounded border border-border bg-surface px-3 py-1.5 text-sm hover:bg-surface-active disabled:opacity-50"
          >
            Export Excel
          </button>
          {(isLoading || isFetching) && (
            <span className="self-center text-xs text-text-muted">Loading…</span>
          )}
        </div>
      </div>

      {/* Totals + alert summary */}
      {report && (
        <div className="grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-4">
          <SummaryCard label="Planned Cost" value={fmtNum(report.totals?.plannedCost)} />
          <SummaryCard label="Actual Cost" value={fmtNum(report.totals?.actualCost)} />
          <SummaryCard
            label="Variance"
            value={fmtNum(report.totals?.variance)}
            tone={
              (report.totals?.variance ?? 0) > 0
                ? "negative"
                : (report.totals?.variance ?? 0) < 0
                  ? "positive"
                  : undefined
            }
          />
          <SummaryCard
            label="Wastage % (avg)"
            value={
              report.totals?.wastagePercent_avg === undefined
                ? "—"
                : `${Number(report.totals.wastagePercent_avg).toFixed(2)}%`
            }
          />
        </div>
      )}

      {report?.alertCounts && Object.keys(report.alertCounts).length > 0 && (
        <div className="rounded-lg border border-border bg-surface p-3">
          <div className="mb-2 text-sm font-medium">Alerts</div>
          <div className="flex flex-wrap gap-2">
            {Object.entries(report.alertCounts).map(([code, count]) => (
              <span
                key={code}
                className={`inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs font-semibold ${ALERT_STYLES[code] ?? "bg-surface-active/40 text-text-secondary border-border"}`}
              >
                {code}
                <span className="rounded-full bg-background/70 px-1.5 py-0.5 text-[10px] text-text-secondary">
                  {count}
                </span>
              </span>
            ))}
          </div>
        </div>
      )}

      {error && (
        <div className="rounded border border-rose-300 bg-rose-50 px-3 py-2 text-sm text-rose-700">
          {getErrorMessage(error)}
        </div>
      )}

      {/* Data table */}
      <div className="overflow-x-auto rounded-lg border border-border bg-surface">
        <table className="min-w-full text-sm">
          <thead className="bg-surface-active text-xs uppercase tracking-wide text-text-muted">
            <tr>
              <Th>Date</Th>
              <Th>WBS</Th>
              <Th>Activity</Th>
              <Th>Supervisor</Th>
              <Th>Storekeeper</Th>
              <Th>Material</Th>
              <Th>Unit</Th>
              <Th align="right">Planned</Th>
              <Th align="right">Issued</Th>
              <Th align="right">Consumed</Th>
              <Th align="right">Balance</Th>
              <Th align="right">Wastage%</Th>
              <Th align="right">Unit Rate</Th>
              <Th align="right">Plan Cost</Th>
              <Th align="right">Actual Cost</Th>
              <Th align="right">Variance</Th>
              <Th align="right">Var%</Th>
              <Th>Alerts</Th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && !isLoading ? (
              <tr>
                <td colSpan={18} className="py-6 text-center text-text-muted">
                  No data for the selected filters.
                </td>
              </tr>
            ) : (
              rows.map((r, idx) => <Row key={idx} row={r} />)
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function Th({
  children,
  align = "left",
}: {
  children: React.ReactNode;
  align?: "left" | "right";
}) {
  return (
    <th
      className={`whitespace-nowrap px-2 py-2 ${align === "right" ? "text-right" : "text-left"}`}
    >
      {children}
    </th>
  );
}

function Row({ row }: { row: MaterialConsumptionRow }) {
  const dateLabel =
    row.fromDate && row.toDate && row.fromDate === row.toDate
      ? row.fromDate
      : [row.fromDate, row.toDate].filter(Boolean).join(" → ");
  return (
    <tr className="border-t border-border hover:bg-surface-active/40">
      <Td>{dateLabel || "—"}</Td>
      <Td>{row.wbsName ?? "—"}</Td>
      <Td>{row.activityName ?? "—"}</Td>
      <Td>{row.supervisorName ?? "—"}</Td>
      <Td>{row.storekeeperName ?? "—"}</Td>
      <Td>{row.materialName ?? "—"}</Td>
      <Td>{row.unit ?? "—"}</Td>
      <Td align="right">{fmtNum(row.plannedQty)}</Td>
      <Td align="right">{fmtNum(row.issuedQty)}</Td>
      <Td align="right">{fmtNum(row.consumedQty)}</Td>
      <Td align="right">{fmtNum(row.balanceQty)}</Td>
      <Td align="right">{fmtNum(row.wastagePercent)}</Td>
      <Td align="right">{fmtNum(row.unitRate, 4)}</Td>
      <Td align="right">{fmtNum(row.plannedCost)}</Td>
      <Td align="right">{fmtNum(row.actualCost)}</Td>
      <Td align="right" className={(row.variance ?? 0) > 0 ? "text-rose-600" : ""}>
        {fmtNum(row.variance)}
      </Td>
      <Td align="right">{fmtPercent(row.variancePercent)}</Td>
      <Td>
        <div className="flex flex-wrap gap-1">
          {row.alerts?.map((c) => <AlertChip key={c} code={c} />)}
        </div>
      </Td>
    </tr>
  );
}

function Td({
  children,
  align = "left",
  className = "",
}: {
  children: React.ReactNode;
  align?: "left" | "right";
  className?: string;
}) {
  return (
    <td
      className={`whitespace-nowrap px-2 py-1.5 ${align === "right" ? "text-right" : "text-left"} ${className}`}
    >
      {children}
    </td>
  );
}

function SummaryCard({
  label,
  value,
  tone,
}: {
  label: string;
  value: string;
  tone?: "positive" | "negative";
}) {
  const toneClass =
    tone === "positive"
      ? "text-emerald-600"
      : tone === "negative"
        ? "text-rose-600"
        : "text-text-primary";
  return (
    <div className="rounded-lg border border-border bg-surface p-3">
      <div className="text-xs uppercase tracking-wide text-text-muted">{label}</div>
      <div className={`mt-1 text-lg font-semibold ${toneClass}`}>{value}</div>
    </div>
  );
}
