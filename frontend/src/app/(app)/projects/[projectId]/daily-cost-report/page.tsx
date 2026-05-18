"use client";

import { useEffect, useMemo, useState } from "react";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { dailyCostReportApi, type DailyCostReportResponse, type DailyCostReportRow } from "@/lib/api/dailyCostReportApi";
import { projectApi } from "@/lib/api/projectApi";
import { TabTip } from "@/components/common/TabTip";
import { getErrorMessage } from "@/lib/utils/error";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import type { ColumnDef } from "@tanstack/react-table";

function formatCurrency(value: number | null): string {
  if (value === null || value === undefined) return "—";
  return value.toLocaleString("en-IN");
}

function formatPercent(value: number | null): string {
  if (value === null || value === undefined) return "—";
  return (value * 100).toFixed(2) + "%";
}

function varianceClass(value: number | null): string {
  if (value === null || value === undefined || value === 0) return "";
  return value > 0 ? "text-danger" : "text-success";
}

export default function DailyCostReportPage() {
  const params = useParams();
  const projectId = params.projectId as string;

  // Date range is data-driven — we wait for the project to load, then seed From/To with the
  // project's planned start/finish so the user sees data on first render regardless of when
  // the project's calendar sits. No hardcoded dates.
  const { data: projectData } = useQuery({
    queryKey: ["project", projectId],
    queryFn: () => projectApi.getProject(projectId),
    enabled: !!projectId,
  });
  const project = projectData?.data;

  const [fromDraft, setFromDraft] = useState<string>("");
  const [toDraft, setToDraft] = useState<string>("");
  const [from, setFrom] = useState<string>("");
  const [to, setTo] = useState<string>("");

  /**
   * Workstream B3: row-level drilldown. Clicking a row opens a side drawer that calls the new
   * drilldown endpoint and lists every DPR row that contributed to the BOQ item's actual cost
   * in the current window.
   */
  const [drilldownBoq, setDrilldownBoq] = useState<{
    boqItemId: string;
    boqItemNo: string | null;
    activity: string;
  } | null>(null);

  useEffect(() => {
    if (!project) return;
    if (from === "" && project.plannedStartDate) {
      setFromDraft(project.plannedStartDate);
      setFrom(project.plannedStartDate);
    }
    if (to === "" && project.plannedFinishDate) {
      setToDraft(project.plannedFinishDate);
      setTo(project.plannedFinishDate);
    }
  }, [project, from, to]);

  const { data, isLoading, error } = useQuery({
    queryKey: ["daily-cost-report", projectId, from, to],
    queryFn: () => dailyCostReportApi.generate(projectId, { from, to }),
    enabled: !!projectId && !!from && !!to,
  });

  const report: DailyCostReportResponse | undefined = data?.data ?? undefined;
  const rows = useMemo(() => report?.rows ?? [], [report]);

  const { data: drilldownData, isLoading: drilldownLoading } = useQuery({
    queryKey: ["daily-cost-report-drilldown", projectId, drilldownBoq?.boqItemId, from, to],
    queryFn: () =>
      dailyCostReportApi.drilldown(projectId, drilldownBoq!.boqItemId, { from, to }),
    enabled: !!projectId && !!drilldownBoq && !!from && !!to,
  });
  const drilldownReport: DailyCostReportResponse | undefined =
    drilldownData?.data ?? undefined;

  const handleApply = () => {
    setFrom(fromDraft);
    setTo(toDraft);
  };

  const columns = useMemo<ColumnDef<DailyCostReportRow>[]>(() => [
    { accessorKey: "date", header: "Date" },
    { accessorKey: "activity", header: "Activity" },
    {
      accessorKey: "qtyExecuted",
      header: "Qty Executed",
      cell: ({ row }) => row.original.qtyExecuted.toLocaleString("en-IN"),
    },
    { accessorKey: "unit", header: "Unit" },
    {
      accessorKey: "budgetedUnitRate",
      header: "Budgeted Unit Rate (₹)",
      cell: ({ row }) => formatCurrency(row.original.budgetedUnitRate),
    },
    {
      accessorKey: "actualUnitRate",
      header: "Actual Unit Rate (₹)",
      cell: ({ row }) => formatCurrency(row.original.actualUnitRate),
    },
    {
      accessorKey: "budgetedCost",
      header: "Budgeted Cost (₹)",
      cell: ({ row }) => formatCurrency(row.original.budgetedCost),
    },
    {
      accessorKey: "actualCost",
      header: "Actual Cost (₹)",
      cell: ({ row }) => formatCurrency(row.original.actualCost),
    },
    {
      accessorKey: "variance",
      header: "Variance (₹)",
      cell: ({ row }) => (
        <span className={varianceClass(row.original.variance)}>
          {formatCurrency(row.original.variance)}
        </span>
      ),
    },
    {
      accessorKey: "variancePercent",
      header: "Variance %",
      cell: ({ row }) => (
        <span className={varianceClass(row.original.variance)}>
          {formatPercent(row.original.variancePercent)}
        </span>
      ),
    },
    {
      accessorKey: "etc",
      header: "ETC (₹)",
      cell: ({ row }) => formatCurrency(row.original.etc),
    },
    {
      accessorKey: "eac",
      header: "EAC (₹)",
      cell: ({ row }) => formatCurrency(row.original.eac),
    },
    { accessorKey: "supervisor", header: "Supervisor" },
  ], []);

  if (isLoading && !report) {
    return <div className="p-6 text-text-muted">Loading cost report...</div>;
  }

  return (
    <div className="p-6">
      <TabTip
        title="Daily Cost Report"
        description="Cross-joins the Supervisor Daily Report with BOQ-item rates and computes budgeted vs actual cost per day — same formulas as the workbook's Section B. Click any row to see the source DPRs for that BOQ item."
      />
      <div className="mb-8">
        <h1 className="text-3xl font-bold mb-4 text-text-primary">Daily Cost Report</h1>

        {/* Date range filter */}
        <div className="bg-surface/50 p-4 rounded-lg border border-border mb-6 shadow-xl">
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 items-end">
            <div>
              <label className="block text-sm font-medium mb-1 text-text-secondary">From</label>
              <input
                type="date"
                value={fromDraft}
                onChange={(e) => setFromDraft(e.target.value)}
                className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
              />
            </div>
            <div>
              <label className="block text-sm font-medium mb-1 text-text-secondary">To</label>
              <input
                type="date"
                value={toDraft}
                onChange={(e) => setToDraft(e.target.value)}
                className="w-full px-3 py-2 border border-border bg-surface-hover text-text-primary rounded-lg"
              />
            </div>
            <div>
              <button
                onClick={handleApply}
                className="px-4 py-2 bg-accent text-accent-foreground rounded-lg hover:bg-accent-hover"
              >
                Apply
              </button>
            </div>
          </div>
        </div>

        {error && (
          <div className="text-danger mb-4">
            {getErrorMessage(error, "Failed to load daily cost report")}
          </div>
        )}

        {/* Report Table */}
        <VirtualDataTable
          columns={columns}
          data={rows}
          sortable
          resizable
          isLoading={isLoading}
          emptyMessage="No cost report data for the selected period."
          onRowClick={(row) => {
            if (!row.boqItemId) return;
            setDrilldownBoq({
              boqItemId: row.boqItemId,
              boqItemNo: row.boqItemNo,
              activity: row.activity,
            });
          }}
        />
        {report && rows.length > 0 && (
          <div className="mt-2 rounded-lg border border-border bg-surface/95 backdrop-blur font-semibold text-text-primary px-4 py-3 flex flex-wrap gap-4 text-sm">
            <span className="font-medium">PERIOD TOTAL</span>
            <span className="ml-auto">Budgeted: {formatCurrency(report.periodBudgetedCost)}</span>
            <span>Actual: {formatCurrency(report.periodActualCost)}</span>
            <span className={varianceClass(report.periodVariance)}>
              Variance: {formatCurrency(report.periodVariance)}
            </span>
            <span className={varianceClass(report.periodVariance)}>
              {formatPercent(report.periodVariancePercent)}
            </span>
          </div>
        )}
      </div>

      {/* Side drawer — drilldown */}
      {drilldownBoq && (
        <div
          className="fixed inset-0 z-50 flex"
          onClick={() => setDrilldownBoq(null)}
        >
          <div className="flex-1 bg-black/40" aria-hidden="true" />
          <aside
            className="w-full max-w-2xl bg-surface text-text-primary shadow-2xl overflow-y-auto"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="sticky top-0 z-10 flex items-center justify-between border-b border-border bg-surface px-5 py-4">
              <div>
                <h2 className="text-lg font-semibold">Source DPRs</h2>
                <p className="text-sm text-text-secondary">
                  BOQ {drilldownBoq.boqItemNo ?? "—"} · {drilldownBoq.activity}
                </p>
              </div>
              <button
                onClick={() => setDrilldownBoq(null)}
                className="px-3 py-1 text-sm rounded border border-border hover:bg-surface-hover"
              >
                Close
              </button>
            </div>
            <div className="p-5 space-y-3">
              {drilldownLoading && (
                <div className="text-sm text-text-muted">Loading source rows…</div>
              )}
              {!drilldownLoading && drilldownReport && drilldownReport.rows.length === 0 && (
                <div className="text-sm text-text-muted">
                  No DPR rows contributed to this BOQ item in the selected window.
                </div>
              )}
              {!drilldownLoading && drilldownReport && drilldownReport.rows.length > 0 && (
                <>
                  <table className="w-full text-sm">
                    <thead className="text-left text-xs uppercase tracking-wide text-text-secondary">
                      <tr>
                        <th className="py-2 pr-3">Date</th>
                        <th className="py-2 pr-3">Supervisor</th>
                        <th className="py-2 pr-3 text-right">Qty</th>
                        <th className="py-2 pr-3 text-right">Unit cost (₹)</th>
                        <th className="py-2 pr-3 text-right">Line cost (₹)</th>
                      </tr>
                    </thead>
                    <tbody>
                      {drilldownReport.rows.map((r) => (
                        <tr key={r.dprId} className="border-t border-border">
                          <td className="py-2 pr-3">{r.date}</td>
                          <td className="py-2 pr-3">{r.supervisor}</td>
                          <td className="py-2 pr-3 text-right">
                            {r.qtyExecuted.toLocaleString("en-IN")} {r.unit}
                          </td>
                          <td className="py-2 pr-3 text-right">
                            {formatCurrency(r.actualUnitRate)}
                          </td>
                          <td className="py-2 pr-3 text-right">
                            {formatCurrency(r.actualCost)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  <div className="mt-3 flex flex-wrap gap-4 border-t border-border pt-3 text-sm font-semibold">
                    <span>SUBTOTAL</span>
                    <span className="ml-auto">
                      Actual: {formatCurrency(drilldownReport.periodActualCost)}
                    </span>
                    <span className={varianceClass(drilldownReport.periodVariance)}>
                      Variance: {formatCurrency(drilldownReport.periodVariance)}
                    </span>
                  </div>
                </>
              )}
            </div>
          </aside>
        </div>
      )}
    </div>
  );
}
