"use client";

import { useQuery } from "@tanstack/react-query";
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
import {
  CHART_COLORS,
  CHART_TOOLTIP_STYLE,
  EmptyBlock,
  LoadingBlock,
  SectionCard,
  truncate,
} from "@/components/common/dashboard/primitives";
import { formatMoney } from "@/lib/currency/format";

// Custom tooltip: renders PV/EV/AC in each bar's own currency
function EvmTooltip({ active, payload, label }: {
  active?: boolean;
  payload?: Array<{ name: string; value: number; payload: { budgetCurrency: string } }>;
  label?: string;
}) {
  if (!active || !payload || payload.length === 0) return null;
  const currency = payload[0].payload.budgetCurrency ?? "USD";
  const meta = { code: currency };
  return (
    <div style={CHART_TOOLTIP_STYLE} className="rounded p-2 text-xs">
      <div className="mb-1 font-semibold">{label}</div>
      {payload.map((entry) => (
        <div key={entry.name} className="flex justify-between gap-4">
          <span>{entry.name}</span>
          <span>{formatMoney(entry.value, meta, { compact: true })}</span>
        </div>
      ))}
    </div>
  );
}

export function EvmRollupChart() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["portfolio-evm-rollup"],
    queryFn: () => portfolioReportApi.getEvmRollup(),
    staleTime: 60_000,
  });

  if (isLoading)
    return (
      <SectionCard title="EVM Performance">
        <LoadingBlock />
      </SectionCard>
    );
  if (isError)
    return (
      <SectionCard title="EVM Performance">
        <EmptyBlock label="Unavailable" />
      </SectionCard>
    );

  const rows = data?.data ?? [];
  if (rows.length === 0) {
    return (
      <SectionCard title="EVM Performance" subtitle="Planned vs Earned vs Actual per project">
        <EmptyBlock label="No EVM data" />
      </SectionCard>
    );
  }

  // Weighted CPI/SPI: EV-share mean of per-project ratios (currency-neutral)
  const wsum = rows.reduce((s, r) => s + (r.bac > 0 ? r.ev / r.bac : 0), 0);
  const wCpi = wsum > 0 ? rows.reduce((s, r) => s + (r.bac > 0 ? (r.ev / r.bac) * (r.cpi ?? 0) : 0), 0) / wsum : 0;
  const wSpi = wsum > 0 ? rows.reduce((s, r) => s + (r.bac > 0 ? (r.ev / r.bac) * (r.spi ?? 0) : 0), 0) / wsum : 0;

  // Per-currency PV/EV sums for the breakdown chips
  const byCurrency = new Map<string, { pv: number; ev: number }>();
  for (const r of rows) {
    const code = r.budgetCurrency ?? "?";
    const existing = byCurrency.get(code) ?? { pv: 0, ev: 0 };
    byCurrency.set(code, {
      pv: existing.pv + (r.pv ?? 0),
      ev: existing.ev + (r.ev ?? 0),
    });
  }

  const chartData = rows.map((r) => ({
    name: truncate(r.projectName, 24),
    PV: r.pv,
    EV: r.ev,
    AC: r.ac,
    budgetCurrency: r.budgetCurrency ?? "USD",
  }));

  const currencyChips = Array.from(byCurrency.entries())
    .map(([code, totals]) =>
      `${code}: ${formatMoney(totals.pv, { code }, { compact: true })} PV · ${formatMoney(totals.ev, { code }, { compact: true })} EV`
    )
    .join("\n");

  return (
    <SectionCard
      title="EVM Performance"
      subtitle="Planned Value vs Earned Value vs Actual Cost per project"
    >
      <ResponsiveContainer width="100%" height={320}>
        <BarChart data={chartData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
          <XAxis dataKey="name" stroke="#64748b" style={{ fontSize: "11px" }} />
          <YAxis stroke="#64748b" style={{ fontSize: "12px" }}
            tickFormatter={(v: number) => {
              const n = Math.abs(v);
              if (n >= 1e9) return `${(v / 1e9).toFixed(1)}B`;
              if (n >= 1e6) return `${(v / 1e6).toFixed(1)}M`;
              if (n >= 1e3) return `${(v / 1e3).toFixed(0)}K`;
              return `${v}`;
            }} />
          <Tooltip content={<EvmTooltip />} />
          <Legend wrapperStyle={{ fontSize: "12px" }} />
          <Bar dataKey="PV" fill={CHART_COLORS.pv} radius={[4, 4, 0, 0]} />
          <Bar dataKey="EV" fill={CHART_COLORS.ev} radius={[4, 4, 0, 0]} />
          <Bar dataKey="AC" fill={CHART_COLORS.ac} radius={[4, 4, 0, 0]} />
        </BarChart>
      </ResponsiveContainer>

      <div className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-3">
        <div className="rounded-md border border-border bg-surface-hover/40 p-3">
          <div className="text-xs text-text-secondary">Σ by currency</div>
          <div className="mt-1 text-sm font-semibold text-text-primary leading-snug whitespace-pre-line">
            {currencyChips || "—"}
          </div>
        </div>
        <MiniStat
          label="Weighted CPI"
          value={wCpi.toFixed(3)}
          tone={wCpi >= 0.95 ? "good" : wCpi >= 0.85 ? "amber" : "red"}
        />
        <MiniStat
          label="Weighted SPI"
          value={wSpi.toFixed(3)}
          tone={wSpi >= 0.95 ? "good" : wSpi >= 0.85 ? "amber" : "red"}
        />
      </div>
    </SectionCard>
  );
}

function MiniStat({
  label,
  value,
  tone,
}: {
  label: string;
  value: string;
  tone?: "good" | "amber" | "red";
}) {
  const color =
    tone === "good" ? "text-success" : tone === "amber" ? "text-warning" : tone === "red" ? "text-danger" : "text-text-primary";
  return (
    <div className="rounded-md border border-border bg-surface-hover/40 p-3">
      <div className="text-xs text-text-secondary">{label}</div>
      <div className={`mt-1 text-lg font-semibold ${color}`}>{value}</div>
    </div>
  );
}
