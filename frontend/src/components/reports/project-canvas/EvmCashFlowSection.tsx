"use client";

import { useQuery } from "@tanstack/react-query";
import {
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { reportDataApi } from "@/lib/api/reportDataApi";
import { KpiTile } from "@/components/common/KpiTile";
import { useProjectCurrencyOptional } from "@/lib/currency/ProjectCurrencyProvider";
import { formatMoney } from "@/lib/currency/format";
import {
  CHART_COLORS,
  CHART_TOOLTIP_STYLE,
  CHART_TOOLTIP_LABEL_STYLE,
  CHART_TOOLTIP_ITEM_STYLE,
  EmptyBlock,
  LoadingBlock,
  SectionCard,
} from "@/components/common/dashboard/primitives";

export function EvmCashFlowSection({ projectId }: { projectId: string }) {
  // The project-canvas can render on the portfolio reports page (outside a
  // project route), so fall back to INR when no project currency is in context.
  const currency = useProjectCurrencyOptional();
  const moneyCompact = (v: number | null | undefined) =>
    currency ? currency.moneyCompact(v) : formatMoney(v, { code: "INR" }, { compact: true });

  const evmQuery = useQuery({
    queryKey: ["project-evm", projectId],
    queryFn: () => reportDataApi.getEvmReport(projectId),
    staleTime: 60_000,
    retry: false,
  });

  const cashFlowQuery = useQuery({
    queryKey: ["project-cash-flow", projectId],
    queryFn: () => reportDataApi.getCashFlowReport(projectId),
    staleTime: 60_000,
    retry: false,
  });

  const evm = evmQuery.data;
  const cashFlow = cashFlowQuery.data ?? [];

  if (evmQuery.isLoading || cashFlowQuery.isLoading)
    return (
      <SectionCard title="EVM & Cash Flow">
        <LoadingBlock />
      </SectionCard>
    );

  const hasEvm = !!evm && (evm.pv || evm.ev || evm.ac);
  const hasCashFlow = cashFlow.length > 0;

  if (!hasEvm && !hasCashFlow) {
    return (
      <SectionCard title="EVM & Cash Flow" subtitle="Earned Value metrics and cash flow over time">
        <EmptyBlock label="No EVM or cash flow data recorded yet" />
      </SectionCard>
    );
  }

  const cpi = evm?.cpi ?? 0;
  const spi = evm?.spi ?? 0;
  const cpiTone = cpi >= 0.95 ? "success" : cpi >= 0.85 ? "warning" : "danger";
  const spiTone = spi >= 0.95 ? "success" : spi >= 0.85 ? "warning" : "danger";

  const cashFlowChartData = cashFlow.map((entry) => ({
    period: entry.period,
    Planned: entry.cumulativePlanned,
    Actual: entry.cumulativeActual,
    Forecast: entry.cumulativeForecast,
  }));

  return (
    <SectionCard title="EVM & Cash Flow" subtitle="Earned Value Management and cash flow trajectory">
      {hasEvm && (
        <>
          <div className="mb-4 grid grid-cols-2 gap-3 md:grid-cols-4 lg:grid-cols-7">
            <KpiTile label="PV" value={moneyCompact(evm.pv ?? 0)} />
            <KpiTile label="EV" value={moneyCompact(evm.ev ?? 0)} />
            <KpiTile label="AC" value={moneyCompact(evm.ac ?? 0)} />
            <KpiTile label="CPI" value={cpi.toFixed(3)} tone={cpiTone} />
            <KpiTile label="SPI" value={spi.toFixed(3)} tone={spiTone} />
            <KpiTile label="EAC" value={moneyCompact(evm.eac ?? 0)} />
            <KpiTile
              label="VAC"
              value={moneyCompact(evm.vac ?? 0)}
              tone={evm.vac < 0 ? "danger" : "success"}
            />
          </div>
          {evm.ev > 0 && evm.ac / evm.ev < 0.5 && (
            <p className="mb-4 text-xs text-text-secondary">
              Early-stage estimate — CPI/VAC are based on limited actuals and may be optimistic.
            </p>
          )}
        </>
      )}

      {hasCashFlow ? (
        <div>
          <h3 className="mb-2 text-sm font-medium text-text-secondary">
            Cumulative cash flow
          </h3>
          <ResponsiveContainer width="100%" height={320}>
            <LineChart data={cashFlowChartData} margin={{ top: 5, right: 30, left: 0, bottom: 5 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
              <XAxis dataKey="period" stroke="#64748b" style={{ fontSize: "11px" }} />
              <YAxis stroke="#64748b" style={{ fontSize: "12px" }} />
              <Tooltip
                contentStyle={CHART_TOOLTIP_STYLE}
                labelStyle={CHART_TOOLTIP_LABEL_STYLE}
                itemStyle={CHART_TOOLTIP_ITEM_STYLE}
                formatter={(value) => moneyCompact(Number(value ?? 0))}
              />
              <Legend wrapperStyle={{ fontSize: "12px" }} />
              <Line
                type="monotone"
                dataKey="Planned"
                stroke={CHART_COLORS.pv}
                strokeWidth={2}
                dot={false}
              />
              <Line
                type="monotone"
                dataKey="Actual"
                stroke={CHART_COLORS.ev}
                strokeWidth={2}
                dot={false}
              />
              <Line
                type="monotone"
                dataKey="Forecast"
                stroke={CHART_COLORS.forecast}
                strokeWidth={2}
                dot={false}
                strokeDasharray="5 5"
              />
            </LineChart>
          </ResponsiveContainer>
        </div>
      ) : (
        <EmptyBlock label="No cash flow data" />
      )}
    </SectionCard>
  );
}
