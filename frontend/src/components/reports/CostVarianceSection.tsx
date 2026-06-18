"use client";

import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Download } from "lucide-react";
import { SimpleTable } from "@/components/common/SimpleTable";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import type { ColumnDef } from "@tanstack/react-table";
import { Badge, type BadgeVariant } from "@/components/ui/badge";
import { downloadCsv, toCsv } from "@/lib/utils/csvExport";
import {
  varianceReportApi,
  type ActivityStatusName,
  type CostVarianceActivityRow,
  type CostVarianceWbsRow,
} from "@/lib/api/varianceReportApi";
import { useProjectCurrencyOptional } from "@/lib/currency/ProjectCurrencyProvider";
import { formatMoney } from "@/lib/currency/format";

interface Props {
  projectId: string;
  baselineId?: string;
}

/**
 * Per-currency compact money — INR uses ₹ k / Lakh / Crore (en-IN), every other
 * currency uses K / M / B (en-US). Input is a RAW amount (already in the project
 * currency). `sign` prepends "+" for positive values (e.g. variance display).
 */
function formatMoneyCompact(
  n: number | null | undefined,
  code: string,
  opts: { sign?: boolean } = {},
): string {
  if (n == null || !Number.isFinite(n)) return "—";
  const body = formatMoney(n, { code }, { compact: true });
  if (opts.sign && n > 0) return `+${body}`;
  return body;
}

function statusBadge(status: ActivityStatusName): BadgeVariant {
  switch (status) {
    case "COMPLETED":
      return "success";
    case "IN_PROGRESS":
      return "info";
    case "NOT_STARTED":
      return "neutral";
    case "ON_HOLD":
      return "warning";
    case "CANCELLED":
      return "danger";
    default:
      return "neutral";
  }
}

function cpiTone(cpi: number | null | undefined): "emerald" | "burgundy" | "bronze" | "default" {
  if (cpi == null) return "default";
  if (cpi >= 1) return "emerald";
  if (cpi >= 0.95) return "bronze";
  return "burgundy";
}

function costToneClass(value: number | null | undefined): string {
  if (value == null) return "text-slate";
  if (value > 0) return "text-burgundy font-semibold";
  if (value < 0) return "text-emerald font-semibold";
  return "text-slate";
}

export function CostVarianceSection({ projectId, baselineId }: Props) {
  const { data, isLoading, error } = useQuery({
    queryKey: ["cost-variance", projectId, baselineId ?? null],
    queryFn: () => varianceReportApi.getCostVariance(projectId, baselineId),
    enabled: !!projectId,
    staleTime: 30_000,
    retry: 1,
  });

  const [showOnlyNonZero, setShowOnlyNonZero] = useState(false);
  const [overrunOnly, setOverrunOnly] = useState(false);

  // Cost-variance amounts are RAW values; render them per-currency (₹ k/L/Cr for
  // INR, K/M/B otherwise) instead of the old hardcoded rupee formatter. Optional
  // hook + INR fallback so it is safe outside a project route. Display-only.
  const cur = useProjectCurrencyOptional();
  const moneyCode = cur?.code ?? "INR";

  const activityRows = useMemo(() => data?.data?.activityRows ?? [], [data]);

  const filtered = useMemo(() => {
    let r = activityRows;
    if (showOnlyNonZero) {
      r = r.filter(
        (x) =>
          (x.estimateVariance != null && x.estimateVariance !== 0) ||
          (x.burnVariance != null && x.burnVariance !== 0)
      );
    }
    if (overrunOnly) {
      r = r.filter((x) => (x.burnVariance ?? 0) > 0);
    }
    return r;
  }, [activityRows, showOnlyNonZero, overrunOnly]);

  const wbsColumns = useMemo<ColumnDef<CostVarianceWbsRow>[]>(
    () => [
      {
        accessorKey: "wbsCode",
        header: "Code",
        cell: (info) => (
          <span className="font-medium text-charcoal">
            {String(info.getValue())}
          </span>
        ),
      },
      {
        accessorKey: "wbsName",
        header: "Name",
        cell: (info) => (
          <span className="text-charcoal">
            {String(info.getValue())}
          </span>
        ),
      },
      {
        accessorKey: "budget",
        header: "Budget",
        cell: (info) => (
          <span className="block text-right text-charcoal tabular-nums">
            {formatMoneyCompact(info.getValue() as number | null, moneyCode)}
          </span>
        ),
      },
      {
        accessorKey: "earnedValue",
        header: "Earned",
        cell: (info) => (
          <span className="block text-right text-charcoal tabular-nums">
            {formatMoneyCompact(info.getValue() as number | null, moneyCode)}
          </span>
        ),
      },
      {
        accessorKey: "actualCost",
        header: "Actual",
        cell: (info) => (
          <span className="block text-right text-charcoal tabular-nums">
            {formatMoneyCompact(info.getValue() as number | null, moneyCode)}
          </span>
        ),
      },
      {
        accessorKey: "costVariance",
        header: "CV",
        cell: (info) => {
          const val = info.getValue() as number | null;
          return (
            <span
              className={`block text-right tabular-nums ${costToneClass(
                val != null ? -val : null
              )}`}
            >
              {formatMoneyCompact(val, moneyCode, { sign: true })}
            </span>
          );
        },
      },
      {
        accessorKey: "costPerformanceIndex",
        header: "CPI",
        cell: (info) => (
          <span className="block text-right text-charcoal tabular-nums">
            <CpiBadge value={info.getValue() as number | null} />
          </span>
        ),
      },
    ],
    [moneyCode]
  );

  const activityColumns = useMemo<ColumnDef<CostVarianceActivityRow>[]>(
    () => [
      {
        accessorKey: "code",
        header: "Code",
        cell: (info) => (
          <span className="font-medium text-charcoal whitespace-nowrap">
            {String(info.getValue())}
          </span>
        ),
      },
      {
        accessorKey: "name",
        header: "Name",
        cell: (info) => (
          <span className="max-w-[260px] truncate">
            {String(info.getValue())}
          </span>
        ),
      },
      {
        accessorKey: "status",
        header: "Status",
        cell: (info) => {
          const row = info.row.original;
          return (
            <Badge variant={statusBadge(row.status)} withDot>
              {row.status.replace(/_/g, " ").toLowerCase()}
            </Badge>
          );
        },
      },
      {
        accessorKey: "percentComplete",
        header: "% complete",
        cell: (info) => (
          <span className="block text-right text-charcoal tabular-nums">
            {info.getValue() != null
              ? `${Number(info.getValue()).toFixed(0)}%`
              : "—"}
          </span>
        ),
      },
      {
        accessorKey: "baselinePlannedCost",
        header: "BL planned",
        cell: (info) => (
          <span className="block text-right text-charcoal tabular-nums">
            {formatMoneyCompact(info.getValue() as number | null, moneyCode)}
          </span>
        ),
      },
      {
        accessorKey: "currentPlannedCost",
        header: "Cur planned",
        cell: (info) => (
          <span className="block text-right text-charcoal tabular-nums">
            {formatMoneyCompact(info.getValue() as number | null, moneyCode)}
          </span>
        ),
      },
      {
        accessorKey: "estimateVariance",
        header: "Estimate var",
        cell: (info) => {
          const val = info.getValue() as number | null;
          return (
            <span
              className={`block text-right tabular-nums ${costToneClass(
                val
              )}`}
            >
              {formatMoneyCompact(val, moneyCode, { sign: true })}
            </span>
          );
        },
      },
      {
        accessorKey: "actualCost",
        header: "Actual",
        cell: (info) => (
          <span className="block text-right text-charcoal tabular-nums">
            {formatMoneyCompact(info.getValue() as number | null, moneyCode)}
          </span>
        ),
      },
      {
        accessorKey: "burnVariance",
        header: "Burn var",
        cell: (info) => {
          const val = info.getValue() as number | null;
          return (
            <span
              className={`block text-right tabular-nums ${costToneClass(
                val
              )}`}
            >
              {formatMoneyCompact(val, moneyCode, { sign: true })}
            </span>
          );
        },
      },
    ],
    [moneyCode]
  );

  if (error) {
    return <ErrorState message="Could not load the cost variance report. The backend may be down or no baseline is set." />;
  }
  if (isLoading) return <LoadingState />;
  if (!data?.data) return <EmptyBaselineState />;

  const summary = data.data.summary;
  const wbsRows = data.data.wbsRows;

  const onExport = () => {
    if (!data?.data) return;
    const csv = toCsv<CostVarianceActivityRow>(filtered, [
      { key: "code", header: "Activity code" },
      { key: "name", header: "Activity name" },
      { key: "activityType", header: "Type" },
      { key: "status", header: "Status" },
      { key: "percentComplete", header: "% complete" },
      { key: "baselinePlannedCost", header: `BL planned (${moneyCode})` },
      { key: "currentPlannedCost", header: `Cur planned (${moneyCode})` },
      { key: "estimateVariance", header: `Estimate var (${moneyCode})` },
      { key: "actualCost", header: `Actual (${moneyCode})` },
      { key: "burnVariance", header: `Burn var (${moneyCode})` },
    ]);
    const projectCode = data.data.project.code.replace(/[^a-zA-Z0-9-]/g, "_");
    downloadCsv(`cost-variance-${projectCode}`, csv);
  };

  return (
    <div className="space-y-6">
      {/* Summary KPIs */}
      <div className="grid grid-cols-2 gap-3.5 lg:grid-cols-4">
        <Kpi label="Budget at completion" value={formatMoneyCompact(summary.budgetAtCompletion, moneyCode)} accent="gold" />
        <Kpi label="Earned value" value={formatMoneyCompact(summary.earnedValue, moneyCode)} accent="default" />
        <Kpi label="Actual cost" value={formatMoneyCompact(summary.actualCost, moneyCode)} accent="default" />
        <Kpi
          label="Cost variance"
          value={formatMoneyCompact(summary.costVariance, moneyCode, { sign: true })}
          accent={(summary.costVariance ?? 0) < 0 ? "burgundy" : "emerald"}
          hint={
            summary.costVariance != null
              ? (summary.costVariance < 0 ? "Over budget" : "Under budget")
              : undefined
          }
        />
      </div>

      <div className="grid grid-cols-2 gap-3.5 lg:grid-cols-4">
        <CpiKpi label="CPI" value={summary.costPerformanceIndex} />
        <CpiKpi label="SPI" value={summary.schedulePerformanceIndex} />
        <Kpi
          label="Estimate at completion"
          value={formatMoneyCompact(summary.estimateAtCompletion, moneyCode)}
          accent="default"
        />
        <Kpi
          label="Variance at completion"
          value={formatMoneyCompact(summary.varianceAtCompletion, moneyCode, { sign: true })}
          accent={(summary.varianceAtCompletion ?? 0) < 0 ? "burgundy" : "emerald"}
        />
      </div>

      {/* WBS rollup table */}
      <div>
        <div className="mb-2 text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep">
          Cost variance by WBS
        </div>
        <div className="overflow-hidden rounded-2xl border border-hairline bg-paper">
          {wbsRows.length === 0 ? (
            <div className="p-6 text-center text-sm text-slate">
              No EVM rollup found per WBS — the EVM module has not been calculated yet for this project.
            </div>
          ) : (
            <SimpleTable
              columns={wbsColumns}
              data={wbsRows}
              sortable={false}
              className="border-0 rounded-none"
            />
          )}
        </div>
      </div>

      {/* Activity-level cost variance */}
      <div>
        <div className="mb-2 flex flex-wrap items-center gap-2">
          <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep">
            Cost variance by activity
          </div>
          <Toggle label="Non-zero only" active={showOnlyNonZero} onClick={() => setShowOnlyNonZero((v) => !v)} />
          <Toggle label="Overrun only" active={overrunOnly} onClick={() => setOverrunOnly((v) => !v)} />
          <div className="ml-auto flex items-center gap-2">
            <span className="text-xs text-slate tabular-nums">
              {filtered.length} of {activityRows.length}
            </span>
            <button
              type="button"
              onClick={onExport}
              className="inline-flex items-center gap-1.5 rounded-md border border-hairline bg-ivory px-2.5 py-1.5 text-xs font-semibold text-charcoal transition-all duration-200 hover:-translate-y-px hover:border-gold/50 hover:bg-paper hover:text-gold-deep"
            >
              <Download size={12} />
              Export CSV
            </button>
          </div>
        </div>
        <div className="overflow-hidden rounded-2xl border border-hairline bg-paper">
          {filtered.length === 0 ? (
            <div className="p-6 text-center text-sm text-slate">
              {activityRows.length === 0
                ? "No baselined activities for this project."
                : "No activities match the current filters."}
            </div>
          ) : (
            <VirtualDataTable
              columns={activityColumns}
              data={filtered}
              sortable
              resizable
              searchable={false}
              className="border-0 rounded-none"
            />
          )}
        </div>
      </div>
    </div>
  );
}

function Kpi({
  label,
  value,
  accent = "default",
  hint,
}: {
  label: string;
  value: string;
  accent?: "default" | "emerald" | "burgundy" | "gold";
  hint?: string;
}) {
  const rail =
    accent === "emerald"
      ? "border-l-[3px] border-l-emerald"
      : accent === "burgundy"
        ? "border-l-[3px] border-l-burgundy"
        : accent === "gold"
          ? "border-l-[3px] border-l-gold"
          : "";
  return (
    <div
      className={`rounded-xl border border-hairline bg-paper p-5 transition-all duration-200 hover:-translate-y-0.5 hover:shadow-[0_4px_20px_rgba(28,28,28,0.05)] ${rail}`}
    >
      <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep mb-2">
        {label}
      </div>
      <div
        className="font-display text-[24px] font-semibold leading-tight tracking-tight text-charcoal"
        style={{ fontVariationSettings: "'opsz' 144" }}
      >
        {value}
      </div>
      {hint && <div className="mt-1.5 text-[11px] text-slate">{hint}</div>}
    </div>
  );
}

function CpiKpi({ label, value }: { label: string; value: number | null | undefined }) {
  const tone = cpiTone(value);
  const display = value != null ? value.toFixed(2) : "—";
  const accent =
    tone === "emerald" ? "emerald" : tone === "burgundy" ? "burgundy" : tone === "bronze" ? "gold" : "default";
  return <Kpi label={label} value={display} accent={accent} />;
}

function CpiBadge({ value }: { value: number | null | undefined }) {
  if (value == null) return <span className="text-ash">—</span>;
  const tone = cpiTone(value);
  const variant: BadgeVariant =
    tone === "emerald" ? "success" : tone === "burgundy" ? "danger" : tone === "bronze" ? "warning" : "neutral";
  return (
    <Badge variant={variant} withDot>
      {value.toFixed(2)}
    </Badge>
  );
}

function Toggle({
  label,
  active,
  onClick,
}: {
  label: string;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`inline-flex items-center gap-1.5 rounded-md border px-2.5 py-1.5 text-xs font-medium transition-colors ${
        active
          ? "border-gold/45 bg-gold-tint/40 text-gold-ink"
          : "border-hairline bg-ivory text-slate hover:border-gold/30 hover:text-charcoal"
      }`}
    >
      {label}
    </button>
  );
}

function LoadingState() {
  return (
    <div className="space-y-3">
      {[...Array(4)].map((_, i) => (
        <div key={i} className="h-24 animate-pulse rounded-xl bg-parchment/60" />
      ))}
    </div>
  );
}

function EmptyBaselineState() {
  return (
    <div className="rounded-2xl border border-dashed border-hairline bg-ivory/50 p-10 text-center">
      <div className="text-[10px] font-semibold uppercase tracking-[0.14em] text-gold-deep mb-2">
        No baseline assigned
      </div>
      <p className="text-sm text-slate">
        Create a baseline (Project → Baselines tab) and set it as active to start tracking variance.
      </p>
    </div>
  );
}

function ErrorState({ message }: { message: string }) {
  return (
    <div className="rounded-2xl border border-burgundy/30 bg-burgundy/5 p-6 text-center text-sm text-burgundy">
      {message}
    </div>
  );
}
