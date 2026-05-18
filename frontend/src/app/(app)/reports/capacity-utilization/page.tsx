"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, Download, FileSpreadsheet } from "lucide-react";
import { projectApi } from "@/lib/api/projectApi";
import { reportApi } from "@/lib/api/reportApi";
import {
  capacityUtilizationApi,
  type CapacityRoleRow,
  type CapacitySection,
} from "@/lib/api/capacityUtilizationApi";
import toast from "react-hot-toast";

const currentMonth = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
};

export default function CapacityUtilizationReportPage() {
  const [projectId, setProjectId] = useState<string>("");
  const [month, setMonth] = useState<string>(currentMonth());
  const [workDays, setWorkDays] = useState<number>(26);
  const [downloading, setDownloading] = useState(false);

  const { data: projectsData, isLoading: projectsLoading } = useQuery({
    queryKey: ["projects"],
    queryFn: () => projectApi.listProjects(0, 100),
  });
  const projects = projectsData?.data?.content ?? [];

  // Compute the date range for the month — the same window the Excel writer uses.
  const monthRange = useMemo(() => {
    const [y, m] = month.split("-").map(Number);
    if (!y || !m) return null;
    const from = `${y}-${String(m).padStart(2, "0")}-01`;
    const lastDay = new Date(y, m, 0).getDate();
    const to = `${y}-${String(m).padStart(2, "0")}-${String(lastDay).padStart(2, "0")}`;
    return { from, to };
  }, [month]);

  // Fetch the capacity utilization JSON so we can surface the untracked-days summary inline.
  const { data: capacityData } = useQuery({
    queryKey: ["report-capacity-summary", projectId, monthRange?.from, monthRange?.to],
    queryFn: () =>
      capacityUtilizationApi.get({
        projectId,
        fromDate: monthRange?.from,
        toDate: monthRange?.to,
      }),
    enabled: !!projectId && !!monthRange,
  });

  const untrackedRows = useMemo(() => {
    const out: Array<{
      kind: "Manpower" | "Equipment";
      roleId: string;
      roleLabel: string;
      untracked: number;
      actual: number;
    }> = [];
    const collect = (kind: "Manpower" | "Equipment", section: CapacitySection | null) => {
      if (!section) return;
      for (const r of section.rows as CapacityRoleRow[]) {
        const u = r.cumulative?.actualDaysUntracked ?? 0;
        if (u <= 0) continue;
        out.push({
          kind,
          roleId: r.roleId,
          roleLabel: r.roleName ?? r.roleCode ?? "(role)",
          untracked: u,
          actual: r.cumulative?.actualDays ?? 0,
        });
      }
    };
    collect("Manpower", capacityData?.data?.manpower ?? null);
    collect("Equipment", capacityData?.data?.equipment ?? null);
    return out;
  }, [capacityData]);

  const totalUntracked = useMemo(
    () => untrackedRows.reduce((s, r) => s + r.untracked, 0),
    [untrackedRows],
  );

  const handleDownload = async () => {
    if (!projectId) {
      toast.error("Pick a project first");
      return;
    }
    setDownloading(true);
    try {
      const response = await reportApi.downloadCapacityUtilizationExcel(
        projectId,
        month,
        workDays,
      );
      const blob =
        response.data instanceof Blob
          ? response.data
          : new Blob([response.data], {
              type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `capacity-utilization-${month}.xlsx`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      toast.success("Capacity Utilization workbook downloaded");
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Download failed";
      toast.error(msg);
    } finally {
      setDownloading(false);
    }
  };

  return (
    <div className="p-6 max-w-4xl">
      <Link
        href="/reports"
        className="inline-flex items-center gap-1.5 text-sm text-slate hover:text-charcoal mb-4"
      >
        <ArrowLeft size={14} /> Back to Reports
      </Link>

      <div className="mb-8">
        <div className="flex items-center gap-3 mb-2">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-gold to-gold-deep text-paper shadow-[0_4px_10px_-4px_rgba(212,175,55,0.5)]">
            <FileSpreadsheet size={20} strokeWidth={1.75} />
          </div>
          <div>
            <h1 className="font-display text-2xl font-semibold tracking-tight text-charcoal">
              Resource Capacity Utilization Report
            </h1>
            <p className="text-sm text-slate">
              Generates the 5-sheet construction-industry workbook (Plant utilization,
              Manpower utilization, SUMMARY, Daily Deployment, DPR) for the selected
              project and month.
            </p>
          </div>
        </div>
      </div>

      <div className="rounded-2xl border border-hairline bg-paper p-6 shadow-[0_1px_2px_rgba(28,28,28,0.04),0_8px_24px_-12px_rgba(28,28,28,0.08)] space-y-5">
        <div>
          <label className="block text-xs font-semibold uppercase tracking-wide text-slate mb-1.5">
            Project
          </label>
          <select
            value={projectId}
            onChange={(e) => setProjectId(e.target.value)}
            disabled={projectsLoading}
            className="w-full h-11 rounded-lg border border-hairline bg-ivory px-3 text-sm text-charcoal focus:outline-none focus:ring-2 focus:ring-gold/40"
          >
            <option value="">— Pick a project —</option>
            {projects.map((p) => (
              <option key={p.id} value={p.id}>
                {p.code} — {p.name}
              </option>
            ))}
          </select>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <label className="block text-xs font-semibold uppercase tracking-wide text-slate mb-1.5">
              Report month
            </label>
            <input
              type="month"
              value={month}
              onChange={(e) => setMonth(e.target.value)}
              className="w-full h-11 rounded-lg border border-hairline bg-ivory px-3 text-sm text-charcoal focus:outline-none focus:ring-2 focus:ring-gold/40"
            />
          </div>
          <div>
            <label className="block text-xs font-semibold uppercase tracking-wide text-slate mb-1.5">
              Work days in the month
            </label>
            <input
              type="number"
              min={1}
              max={31}
              value={workDays}
              onChange={(e) => setWorkDays(Number(e.target.value) || 26)}
              className="w-full h-11 rounded-lg border border-hairline bg-ivory px-3 text-sm text-charcoal focus:outline-none focus:ring-2 focus:ring-gold/40"
            />
            <p className="mt-1 text-[11px] text-slate">
              Populates the highlighted Work days cell on every sheet (default 26 — 6-day week).
            </p>
          </div>
        </div>

        <div className="pt-3 border-t border-hairline flex items-center justify-end gap-3">
          <button
            type="button"
            onClick={handleDownload}
            disabled={!projectId || downloading}
            className="inline-flex items-center gap-2 rounded-xl bg-gradient-to-br from-gold to-gold-deep px-5 py-2.5 text-sm font-semibold text-paper shadow-[0_4px_12px_-2px_rgba(212,175,55,0.45)] transition-all duration-200 hover:-translate-y-0.5 hover:shadow-[0_8px_18px_-4px_rgba(212,175,55,0.55)] disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:translate-y-0"
          >
            <Download size={16} strokeWidth={1.75} />
            {downloading ? "Generating…" : "Download Excel"}
          </button>
        </div>
      </div>

      {projectId && monthRange && (
        <div className="mt-6 rounded-2xl border border-hairline bg-paper p-5 shadow-[0_1px_2px_rgba(28,28,28,0.04),0_8px_24px_-12px_rgba(28,28,28,0.08)]">
          <div className="flex items-baseline justify-between mb-3">
            <h2 className="font-display text-lg font-semibold text-charcoal">
              Untracked Actual Days · {month}
            </h2>
            <span className="text-xs text-slate">
              Days deployed on activities with no productivity norm — excluded from % Util.
            </span>
          </div>
          {untrackedRows.length === 0 ? (
            <p className="text-sm text-slate">
              No untracked days in this period. Every role&apos;s deployment is on an activity with a
              productivity norm.
            </p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm border-collapse">
                <thead>
                  <tr className="border-b border-hairline text-left text-[11px] font-semibold uppercase tracking-wide text-slate">
                    <th className="py-2">Section</th>
                    <th className="py-2">Role</th>
                    <th className="py-2 text-right">Actual Days</th>
                    <th className="py-2 text-right">Of which Untracked</th>
                    <th className="py-2 text-right">% Untracked</th>
                  </tr>
                </thead>
                <tbody>
                  {untrackedRows.map((r) => (
                    <tr key={`${r.kind}-${r.roleId}`} className="border-t border-hairline/50">
                      <td className="py-2 text-charcoal">{r.kind}</td>
                      <td className="py-2 text-charcoal">{r.roleLabel}</td>
                      <td className="py-2 text-right text-charcoal">
                        {r.actual.toLocaleString("en-IN", { maximumFractionDigits: 1 })}
                      </td>
                      <td className="py-2 text-right font-semibold text-charcoal">
                        {r.untracked.toLocaleString("en-IN", { maximumFractionDigits: 1 })}
                      </td>
                      <td className="py-2 text-right text-slate">
                        {r.actual > 0
                          ? `${((100 * r.untracked) / r.actual).toFixed(0)}%`
                          : "—"}
                      </td>
                    </tr>
                  ))}
                  <tr className="border-t border-hairline bg-ivory/60 font-semibold">
                    <td className="py-2" />
                    <td className="py-2 text-charcoal">Total</td>
                    <td />
                    <td className="py-2 text-right text-charcoal">
                      {totalUntracked.toLocaleString("en-IN", { maximumFractionDigits: 1 })}
                    </td>
                    <td />
                  </tr>
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      <div className="mt-6 rounded-xl border border-hairline bg-ivory/60 p-4 text-xs leading-relaxed text-slate">
        <strong className="text-charcoal">What&apos;s inside the workbook</strong>
        <ul className="mt-1.5 list-disc pl-4 space-y-0.5">
          <li>
            <strong>Plant utilization</strong> &amp; <strong>Manpower utilization</strong> —
            per resource type / activity, budgeted vs actual days for the day and the month
            with norm-derived utilisation %.
          </li>
          <li>
            <strong>SUMMARY</strong> — rolled-up Equipment + Manpower table.
          </li>
          <li>
            <strong>Daily Deployment</strong> — 1-to-31 grid of hours deployed; Day Shift /
            Night Shift sections are scaffolded for hand-edit (deployment data is currently
            captured shift-agnostic and lands in the Total block).
          </li>
          <li>
            <strong>DPR</strong> — BOQ items × per-day achieved quantity from the Daily
            Progress Report log.
          </li>
        </ul>
      </div>
    </div>
  );
}
