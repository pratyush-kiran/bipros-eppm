"use client";

import { useQuery } from "@tanstack/react-query";
import { AlertTriangle } from "lucide-react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { portfolioReportApi } from "@/lib/api/portfolioReportApi";
import type { FundingUtilizationRow } from "@/lib/api/portfolioReportApi";
import { formatMoney } from "@/lib/currency/format";
import {
  CHART_COLORS,
  CHART_TOOLTIP_STYLE,
  EmptyBlock,
  LoadingBlock,
  SectionCard,
  truncate,
} from "@/components/common/dashboard/primitives";

function compactNumber(v: number): string {
  if (v >= 1e9) return (v / 1e9).toFixed(1) + "B";
  if (v >= 1e6) return (v / 1e6).toFixed(1) + "M";
  if (v >= 1e3) return (v / 1e3).toFixed(0) + "K";
  return String(v);
}

interface FundingTooltipProps {
  active?: boolean;
  payload?: Array<{ dataKey?: string; name?: string; value?: number; color?: string }>;
  label?: string;
  currencyByName: Map<string, string>;
}

function FundingTooltip({ active, payload, label, currencyByName }: FundingTooltipProps) {
  if (!active || !payload?.length) return null;
  const code = currencyByName.get(label ?? "") ?? "USD";
  return (
    <div style={CHART_TOOLTIP_STYLE} className="rounded px-3 py-2 text-sm">
      <div className="mb-1 font-semibold">{label}</div>
      {payload.map((entry) => (
        <div key={entry.dataKey} style={{ color: entry.color }}>
          {entry.name}: {formatMoney(entry.value ?? 0, { code }, { compact: true })}
        </div>
      ))}
    </div>
  );
}

export function FundingUtilizationChart() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["portfolio-funding"],
    queryFn: () => portfolioReportApi.getFundingUtilization(),
    staleTime: 60_000,
  });

  if (isLoading)
    return (
      <SectionCard title="Funding Utilization">
        <LoadingBlock />
      </SectionCard>
    );
  if (isError)
    return (
      <SectionCard title="Funding Utilization">
        <EmptyBlock label="Unavailable" />
      </SectionCard>
    );

  const rows: FundingUtilizationRow[] = data ?? [];
  if (rows.length === 0) {
    return (
      <SectionCard title="Funding Utilization">
        <EmptyBlock label="No funding data" />
      </SectionCard>
    );
  }

  const stuck = rows.filter((r) => r.utilizationPct < 50 && r.totalSanctionedCrores > 0);

  const chartData = rows.map((r) => ({
    name: truncate(r.projectName, 24),
    Sanctioned: r.totalSanctionedCrores,
    Released: r.totalReleasedCrores,
    Utilized: r.totalUtilizedCrores,
    status: r.fundingStatus,
  }));

  // Map truncated name → currency code for the tooltip
  const currencyByName = new Map<string, string>(
    rows.map((r) => [truncate(r.projectName, 24), r.currency ?? "USD"]),
  );

  return (
    <SectionCard
      title="Funding Utilization"
      subtitle="Sanctioned vs released vs utilised per project (mixed currencies — not summed)"
    >
      {stuck.length > 0 && (
        <div className="mb-4 flex items-start gap-2 rounded-md border border-warning/40 bg-warning/10 p-3 text-sm text-warning">
          <AlertTriangle size={18} className="mt-0.5 shrink-0" />
          <span>
            {stuck.length} project{stuck.length > 1 ? "s have" : " has"} utilization below 50%.
            Funds are released but not being spent.
          </span>
        </div>
      )}

      <ResponsiveContainer width="100%" height={320}>
        <BarChart data={chartData} margin={{ top: 5, right: 30, left: 0, bottom: 5 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
          <XAxis dataKey="name" stroke="#64748b" style={{ fontSize: "11px" }} />
          <YAxis
            stroke="#64748b"
            style={{ fontSize: "12px" }}
            tickFormatter={(v: number) => compactNumber(Math.abs(v))}
          />
          <Tooltip
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            content={(props: any) => (
              <FundingTooltip {...props} currencyByName={currencyByName} />
            )}
          />
          <Legend wrapperStyle={{ fontSize: "12px" }} />
          <Bar dataKey="Sanctioned" fill={CHART_COLORS.pv} radius={[4, 4, 0, 0]} />
          <Bar dataKey="Released" fill={CHART_COLORS.committed} radius={[4, 4, 0, 0]} />
          <Bar dataKey="Utilized" fill={CHART_COLORS.ev} radius={[4, 4, 0, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </SectionCard>
  );
}
