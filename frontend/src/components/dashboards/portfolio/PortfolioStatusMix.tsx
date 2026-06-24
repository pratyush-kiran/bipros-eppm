"use client";

import { useQuery } from "@tanstack/react-query";
import { Activity, ShieldCheck } from "lucide-react";
import {
  Cell,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
} from "recharts";
import { portfolioReportApi } from "@/lib/api/portfolioReportApi";
import {
  CHART_COLORS,
  CHART_TOOLTIP_STYLE,
  CHART_TOOLTIP_LABEL_STYLE,
  CHART_TOOLTIP_ITEM_STYLE,
  EmptyBlock,
  LoadingBlock,
  SectionCard,
  formatPct,
} from "@/components/common/dashboard/primitives";

const STATUS_COLORS: Record<string, string> = {
  ACTIVE: CHART_COLORS.goldDeep,
  PLANNED: "#475569",
  COMPLETED: "#94a3b8",
  ON_HOLD: CHART_COLORS.warning,
  CANCELLED: CHART_COLORS.danger,
};

const STATUS_LABEL: Record<string, string> = {
  ACTIVE: "Active",
  PLANNED: "Planned",
  COMPLETED: "Completed",
  ON_HOLD: "On hold",
  CANCELLED: "Cancelled",
};

export function PortfolioStatusMix() {
  const { data, isLoading, isError } = useQuery({
    queryKey: ["portfolio-scorecard"],
    queryFn: () => portfolioReportApi.getScorecard(),
    staleTime: 60_000,
  });

  if (isLoading)
    return (
      <SectionCard title="Status & Health" icon={<Activity size={16} />}>
        <LoadingBlock />
      </SectionCard>
    );
  if (isError || !data)
    return (
      <SectionCard title="Status & Health" icon={<Activity size={16} />}>
        <EmptyBlock label="Unavailable" />
      </SectionCard>
    );

  const statusData = Object.entries(data.byStatus ?? {})
    .filter(([, v]) => (v ?? 0) > 0)
    .map(([name, value]) => ({ name, value }));
  const statusTotal = statusData.reduce((s, e) => s + e.value, 0);

  const ragTotal = data.rag.green + data.rag.amber + data.rag.red + (data.rag.grey ?? 0) || 1;
  const ragSegments = [
    { key: "green", label: "On track", count: data.rag.green, color: CHART_COLORS.goldDeep },
    { key: "amber", label: "At risk", count: data.rag.amber, color: CHART_COLORS.amber },
    { key: "red", label: "Critical", count: data.rag.red, color: CHART_COLORS.red },
    ...(( data.rag.grey ?? 0) > 0
      ? [{ key: "grey", label: "No EVM", count: data.rag.grey, color: "#94a3b8" }]
      : []),
  ];
  const healthScore =
    ragTotal > 0
      ? Math.round(
          ((data.rag.green * 100 + data.rag.amber * 60 + data.rag.red * 20) /
            ragTotal),
        )
      : 0;
  const healthBand =
    healthScore >= 80 ? "good" : healthScore >= 60 ? "amber" : "red";

  return (
    <SectionCard
      title="Status & Health"
      subtitle="Distribution and RAG banding across the portfolio"
      icon={<Activity size={16} />}
      accent
      badge={
        <span
          className={`inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[10px] font-semibold ${
            healthBand === "good"
              ? "border-gold/30 bg-gold-tint/40 text-gold-deep"
              : healthBand === "amber"
                ? "border-bronze-warn/30 bg-bronze-warn/10 text-bronze-warn"
                : "border-burgundy/25 bg-burgundy/8 text-burgundy"
          }`}
        >
          <ShieldCheck size={10} />
          Health {healthScore}
        </span>
      }
    >
      <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
        {/* Donut */}
        <div className="rounded-xl border border-hairline bg-ivory/40 p-4">
          <h3 className="mb-3 text-[11px] font-semibold uppercase tracking-[0.12em] text-slate">
            Project status mix
          </h3>
          {statusData.length === 0 ? (
            <EmptyBlock label="No projects" />
          ) : (
            <div className="grid grid-cols-1 items-center gap-3 sm:grid-cols-[160px_1fr]">
              <div className="relative mx-auto h-[160px] w-[160px]">
                <ResponsiveContainer width="100%" height="100%">
                  <PieChart>
                    <Pie
                      data={statusData}
                      dataKey="value"
                      nameKey="name"
                      innerRadius={52}
                      outerRadius={74}
                      paddingAngle={3}
                      stroke="#fff"
                      strokeWidth={2}
                    >
                      {statusData.map((entry) => (
                        <Cell
                          key={entry.name}
                          fill={STATUS_COLORS[entry.name] ?? "#64748b"}
                        />
                      ))}
                    </Pie>
                    <Tooltip
                      contentStyle={CHART_TOOLTIP_STYLE}
                      labelStyle={CHART_TOOLTIP_LABEL_STYLE}
                      itemStyle={CHART_TOOLTIP_ITEM_STYLE}
                      formatter={(v, n) => [
                        `${v} (${formatPct((Number(v) / statusTotal) * 100, 0)})`,
                        STATUS_LABEL[String(n)] ?? n,
                      ]}
                    />
                  </PieChart>
                </ResponsiveContainer>
                <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
                  <div className="text-[10px] font-semibold uppercase tracking-wider text-slate">
                    Projects
                  </div>
                  <div
                    className="font-display text-3xl font-semibold leading-none text-charcoal"
                    style={{ fontVariationSettings: "'opsz' 144" }}
                  >
                    {statusTotal}
                  </div>
                </div>
              </div>

              {/* Legend with values + percent */}
              <ul className="space-y-1.5">
                {statusData
                  .slice()
                  .sort((a, b) => b.value - a.value)
                  .map((s) => {
                    const pct = (s.value / statusTotal) * 100;
                    return (
                      <li
                        key={s.name}
                        className="flex items-center justify-between rounded-lg border border-transparent px-2 py-1.5 transition-colors hover:border-hairline hover:bg-paper"
                      >
                        <div className="flex items-center gap-2">
                          <span
                            className="h-2.5 w-2.5 shrink-0 rounded-sm"
                            style={{
                              backgroundColor: STATUS_COLORS[s.name] ?? "#64748b",
                            }}
                          />
                          <span className="text-xs font-medium text-charcoal">
                            {STATUS_LABEL[s.name] ?? s.name}
                          </span>
                        </div>
                        <div className="flex items-baseline gap-1.5">
                          <span className="text-sm font-semibold text-charcoal">
                            {s.value}
                          </span>
                          <span className="text-[10px] font-medium text-slate">
                            {formatPct(pct, 0)}
                          </span>
                        </div>
                      </li>
                    );
                  })}
              </ul>
            </div>
          )}
        </div>

        {/* RAG */}
        <div className="rounded-xl border border-hairline bg-ivory/40 p-4">
          <h3 className="mb-3 text-[11px] font-semibold uppercase tracking-[0.12em] text-slate">
            RAG banding
          </h3>

          {/* Segmented bar */}
          <div className="relative h-12 w-full overflow-hidden rounded-xl border border-hairline bg-paper">
            <div className="flex h-full w-full">
              {ragSegments.map((seg) => {
                const pct = (seg.count / ragTotal) * 100;
                if (pct === 0) return null;
                return (
                  <div
                    key={seg.key}
                    className="relative flex items-center justify-center"
                    style={{
                      width: `${pct}%`,
                      background: `linear-gradient(180deg, ${seg.color} 0%, ${seg.color}dd 100%)`,
                    }}
                    title={`${seg.label}: ${seg.count}`}
                  >
                    <div className="text-[11px] font-semibold text-white drop-shadow-sm">
                      {pct >= 18 ? `${seg.label} · ${seg.count}` : seg.count}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          {/* Detail tiles */}
          <div className={`mt-3 grid gap-2 ${ragSegments.length === 4 ? "grid-cols-4" : "grid-cols-3"}`}>
            {ragSegments.map((seg) => {
              const pct = (seg.count / ragTotal) * 100;
              return (
                <div
                  key={seg.key}
                  className="rounded-lg border border-hairline bg-paper p-3 text-center transition-all hover:-translate-y-0.5 hover:border-gold/25 hover:shadow-[0_6px_16px_-10px_rgba(0,88,202,0.25)]"
                >
                  <div className="flex items-center justify-center gap-1.5 text-[10px] font-semibold uppercase tracking-[0.1em] text-slate">
                    <span
                      className="h-1.5 w-1.5 rounded-full"
                      style={{ backgroundColor: seg.color }}
                    />
                    {seg.label}
                  </div>
                  <div
                    className="mt-1 font-display text-2xl font-semibold leading-none"
                    style={{ color: seg.color }}
                  >
                    {seg.count}
                  </div>
                  <div className="mt-0.5 text-[10px] font-medium text-slate">
                    {formatPct(pct, 0)}
                  </div>
                </div>
              );
            })}
          </div>

          {/* Health score meter */}
          <div className="mt-4 rounded-lg border border-hairline bg-paper p-3">
            <div className="flex items-center justify-between">
              <div className="text-[11px] font-semibold uppercase tracking-[0.12em] text-slate">
                Composite health
              </div>
              <div
                className={`font-display text-lg font-semibold leading-none ${
                  healthBand === "good"
                    ? "text-gold-deep"
                    : healthBand === "amber"
                      ? "text-bronze-warn"
                      : "text-burgundy"
                }`}
              >
                {healthScore}
                <span className="text-xs text-slate">/100</span>
              </div>
            </div>
            <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-ivory">
              <div
                className="h-full rounded-full transition-all duration-700"
                style={{
                  width: `${healthScore}%`,
                  background:
                    healthBand === "good"
                      ? `linear-gradient(90deg, ${CHART_COLORS.goldDeep}, ${CHART_COLORS.gold})`
                      : healthBand === "amber"
                        ? `linear-gradient(90deg, ${CHART_COLORS.amber}, ${CHART_COLORS.gold})`
                        : `linear-gradient(90deg, ${CHART_COLORS.red}, ${CHART_COLORS.amber})`,
                }}
              />
            </div>
          </div>
        </div>
      </div>
    </SectionCard>
  );
}
