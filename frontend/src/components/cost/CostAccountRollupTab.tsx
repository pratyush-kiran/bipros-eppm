"use client";

import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { DollarSign, TrendingUp, BarChart3, Wallet } from "lucide-react";
import { SimpleTable } from "@/components/common/SimpleTable";
import type { ColumnDef } from "@tanstack/react-table";
import { evmApi, type CostAccountRollupRow } from "@/lib/api/evmApi";
import { KpiTile } from "@/components/common/KpiTile";
import { AiInsightsPanel } from "@/components/ai/AiInsightsPanel";
import { useProjectCurrency } from "@/lib/currency/ProjectCurrencyProvider";

function formatRatio(val: number | null | undefined): string {
  if (val === null || val === undefined) return "—";
  return val.toFixed(2);
}

function cvTone(cv: number): "success" | "danger" | "default" {
  if (cv > 0) return "success";
  if (cv < 0) return "danger";
  return "default";
}

function cpiTextClass(cpi: number | null): string {
  if (cpi === null) return "text-text-muted";
  if (cpi >= 1) return "text-emerald font-semibold";
  return "text-burgundy font-semibold";
}

function cvTextClass(cv: number): string {
  if (cv > 0) return "text-emerald font-semibold";
  if (cv < 0) return "text-burgundy font-semibold";
  return "text-text-secondary";
}

export function CostAccountRollupTab({ projectId }: { projectId: string }) {
  const { moneyCompact } = useProjectCurrency();
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["cost-account-rollup", projectId],
    queryFn: () => evmApi.getCostAccountRollup(projectId),
  });

  const rows: CostAccountRollupRow[] = useMemo(() => data?.data ?? [], [data]);

  const totals = useMemo(() => {
    const bac = rows.reduce((acc, r) => acc + (r.bac ?? 0), 0);
    const ev = rows.reduce((acc, r) => acc + (r.ev ?? 0), 0);
    const ac = rows.reduce((acc, r) => acc + (r.ac ?? 0), 0);
    const cpi = ac > 0 ? ev / ac : null;
    return { bac, ev, ac, cpi };
  }, [rows]);

  const columns = useMemo<ColumnDef<CostAccountRollupRow>[]>(
    () => [
      {
        accessorKey: "costAccountCode",
        header: "Code",
        cell: (info) => (
          <span className="font-mono text-xs">
            {String(info.getValue() ?? "—")}
          </span>
        ),
      },
      {
        accessorKey: "costAccountName",
        header: "Name",
        cell: (info) => {
          const row = info.row.original;
          const isUnassigned = row.costAccountId === null;
          return isUnassigned ? (
            <span className="italic">{String(info.getValue())}</span>
          ) : (
            <span className="font-medium text-text-primary">
              {String(info.getValue())}
            </span>
          );
        },
      },
      {
        accessorKey: "activityCount",
        header: "Activities",
        cell: (info) => (
          <span className="block text-right tabular-nums">
            {Number(info.getValue())}
          </span>
        ),
      },
      {
        accessorKey: "bac",
        header: "BAC",
        cell: (info) => (
          <span className="block text-right tabular-nums">
            {moneyCompact(Number(info.getValue()))}
          </span>
        ),
      },
      {
        accessorKey: "ev",
        header: "EV",
        cell: (info) => (
          <span className="block text-right tabular-nums">
            {moneyCompact(Number(info.getValue()))}
          </span>
        ),
      },
      {
        accessorKey: "ac",
        header: "AC",
        cell: (info) => (
          <span className="block text-right tabular-nums">
            {moneyCompact(Number(info.getValue()))}
          </span>
        ),
      },
      {
        accessorKey: "cv",
        header: "CV",
        cell: (info) => {
          const val = Number(info.getValue());
          return (
            <span
              className={`block text-right tabular-nums ${cvTextClass(
                val
              )}`}
            >
              {moneyCompact(val)}
            </span>
          );
        },
      },
      {
        accessorKey: "cpi",
        header: "CPI",
        cell: (info) => {
          const val = info.getValue() as number | null;
          return (
            <span
              className={`block text-right tabular-nums ${cpiTextClass(
                val
              )}`}
            >
              {formatRatio(val)}
            </span>
          );
        },
      },
    ],
    [moneyCompact]
  );

  if (isLoading) {
    return (
      <div className="space-y-6 px-6 pb-8">
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
          {[0, 1, 2, 3].map((i) => (
            <div key={i} className="h-24 animate-pulse rounded-xl bg-surface-hover/50" />
          ))}
        </div>
        <div className="space-y-2">
          {[0, 1, 2].map((i) => (
            <div key={i} className="h-10 animate-pulse rounded-md bg-surface-hover/50" />
          ))}
        </div>
      </div>
    );
  }

  if (isError) {
    return (
      <div className="px-6 pb-8">
        <div className="rounded-xl border border-burgundy/40 bg-burgundy/5 px-4 py-3 text-sm text-burgundy">
          Failed to load cost account rollup: {String((error as Error)?.message ?? "Unknown error")}
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6 px-6 pb-8">
      {/* <AiInsightsPanel projectId={projectId} endpoint={`/v1/projects/${projectId}/cost-accounts/ai/insights`} /> */}
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <KpiTile
          label="Total BAC"
          value={moneyCompact(totals.bac)}
          hint="Sum of budget at completion"
          tone="accent"
          icon={<Wallet size={14} />}
        />
        <KpiTile
          label="Earned Value"
          value={moneyCompact(totals.ev)}
          hint="Sum across all cost accounts"
          tone="success"
          icon={<TrendingUp size={14} />}
        />
        <KpiTile
          label="Actual Cost"
          value={moneyCompact(totals.ac)}
          hint="Sum across all cost accounts"
          tone="danger"
          icon={<DollarSign size={14} />}
        />
        <KpiTile
          label="Weighted CPI"
          value={formatRatio(totals.cpi)}
          hint="Total EV ÷ Total AC"
          tone={totals.cpi === null ? "default" : totals.cpi >= 1 ? "success" : "danger"}
          icon={<BarChart3 size={14} />}
        />
      </div>

      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-text-secondary">
          Rollup by Cost Account
        </h2>
        <span className="text-xs text-text-muted">
          {rows.length} {rows.length === 1 ? "bucket" : "buckets"}
        </span>
      </div>

      {rows.length === 0 ? (
        <div className="rounded-xl border border-dashed border-border bg-surface-hover/20 py-12 text-center">
          <p className="text-sm text-text-muted">No cost data to roll up yet.</p>
          <p className="mt-1 text-xs text-text-muted">
            Create cost accounts in <strong>Admin → Cost Accounts</strong>, then assign them via the
            activity edit form or WBS edit panel.
          </p>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-xl border border-border">
          <SimpleTable
            columns={columns}
            data={rows}
            sortable={false}
            className="border-0 rounded-none"
          />
          <div className="border-t-2 border-border bg-surface-hover/40 px-4 py-3 text-sm font-semibold grid grid-cols-[1fr_1fr_80px_80px_80px_80px_80px_60px] gap-2 items-center">
            <span className="text-xs uppercase tracking-wide text-text-secondary col-span-2">
              Total
            </span>
            <span className="text-right tabular-nums text-text-primary">
              {rows.reduce((acc, r) => acc + r.activityCount, 0)}
            </span>
            <span className="text-right tabular-nums text-text-primary">
              {moneyCompact(totals.bac)}
            </span>
            <span className="text-right tabular-nums text-text-primary">
              {moneyCompact(totals.ev)}
            </span>
            <span className="text-right tabular-nums text-text-primary">
              {moneyCompact(totals.ac)}
            </span>
            <span
              className={`text-right tabular-nums ${cvTextClass(
                totals.ev - totals.ac
              )}`}
            >
              {moneyCompact(totals.ev - totals.ac)}
            </span>
            <span
              className={`text-right tabular-nums ${cpiTextClass(
                totals.cpi
              )}`}
            >
              {formatRatio(totals.cpi)}
            </span>
          </div>
        </div>
      )}
      <p className="text-[11px] text-text-muted">
        Cost account is resolved per activity: <span className="font-mono">activity.costAccountId</span> →{" "}
        <span className="font-mono">wbs.costAccountId</span> → unassigned. Activities without either
        roll up into the &ldquo;Unassigned&rdquo; bucket so you can see what&apos;s not yet attributed.
      </p>
    </div>
  );
}
