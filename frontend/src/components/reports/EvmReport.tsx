"use client";

import { useMemo } from "react";
import { TrendingUp, TrendingDown } from "lucide-react";
import type { EvmReportData } from "@/lib/api/reportDataApi";
import { useProjectCurrencyOptional } from "@/lib/currency/ProjectCurrencyProvider";
import { formatMoney } from "@/lib/currency/format";

interface EvmReportProps {
  data: EvmReportData;
}

function MetricCard({
  label,
  value,
  isGood,
  suffix = "",
  display,
}: {
  label: string;
  value: number;
  isGood: boolean;
  suffix?: string;
  display?: string;
}) {
  const isPositive = value >= 1 || value >= 0;
  const bgColor = isGood ? "bg-success/10" : "bg-danger/10";
  const textColor = isGood ? "text-success" : "text-danger";
  const borderColor = isGood ? "border-success/30" : "border-danger/30";

  return (
    <div className={`${bgColor} border ${borderColor} rounded-lg p-4`}>
      <p className="text-xs text-text-secondary uppercase tracking-wider mb-1">{label}</p>
      <div className="flex items-center justify-between">
        <p className={`text-2xl font-bold ${textColor}`}>
          {display !== undefined
            ? display
            : (typeof value === "number" && !isNaN(value) ? value.toFixed(2) : "0.00") + suffix}
        </p>
        {isGood ? (
          <TrendingUp className={textColor} size={24} />
        ) : (
          <TrendingDown className={textColor} size={24} />
        )}
      </div>
    </div>
  );
}

export function EvmReport({ data }: EvmReportProps) {
  // May render on the portfolio reports page (outside a project route), so fall
  // back to INR when no project currency is in context.
  const currency = useProjectCurrencyOptional();
  const money = (value: number | null | undefined) =>
    currency ? currency.money(value) : formatMoney(value, { code: "INR" });
  const moneyCompact = (v: number | null | undefined) =>
    currency ? currency.moneyCompact(v) : formatMoney(v, { code: "INR" }, { compact: true });

  const tcpiBac = useMemo(() => {
    const denom = data.bac - data.ac;
    return denom !== 0 ? (data.bac - data.ev) / denom : 0;
  }, [data.bac, data.ev, data.ac]);

  const metrics = useMemo(
    () => ({
      spiGood: data.spi >= 1,
      cpiGood: data.cpi >= 1,
      eacGood: data.eac <= data.pv,
      vacGood: data.vac >= 0,
      tcpiGood: data.tcpi <= 1,
    }),
    [data]
  );

  const overallHealth = useMemo(() => {
    const score = [
      metrics.spiGood,
      metrics.cpiGood,
      metrics.eacGood,
      metrics.vacGood,
    ].filter(Boolean).length;
    const percentage = (score / 4) * 100;

    if (percentage >= 75) return { status: "Healthy", color: "bg-success/10 text-success" };
    if (percentage >= 50) return { status: "At Risk", color: "bg-warning/10 text-warning" };
    return { status: "Critical", color: "bg-danger/10 text-danger" };
  }, [metrics]);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="rounded-lg border border-border bg-surface/50 p-4">
        <div className="flex justify-between items-start">
          <div>
            <h3 className="font-semibold text-lg text-text-primary">{data.projectName}</h3>
            <p className="text-sm text-text-secondary">Earned Value Management Report</p>
          </div>
          <div className={`px-3 py-1 rounded-full text-sm font-semibold ${overallHealth.color}`}>
            {overallHealth.status}
          </div>
        </div>
      </div>

      {/* Core EVM Metrics */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <MetricCard label="Planned Value (PV)" value={data.pv} isGood={true} display={moneyCompact(data.pv)} />
        <MetricCard label="Earned Value (EV)" value={data.ev} isGood={true} display={moneyCompact(data.ev)} />
        <MetricCard label="Actual Cost (AC)" value={data.ac} isGood={data.ac <= data.ev} display={moneyCompact(data.ac)} />
      </div>

      {/* Performance Indices */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <MetricCard
          label="Schedule Performance Index (SPI)"
          value={data.spi}
          isGood={metrics.spiGood}
        />
        <MetricCard
          label="Cost Performance Index (CPI)"
          value={data.cpi}
          isGood={metrics.cpiGood}
        />
      </div>

      {/* Forecast Metrics */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <MetricCard label="Estimate at Completion (EAC)" value={data.eac} isGood={metrics.eacGood} display={moneyCompact(data.eac)} />
        <MetricCard label="Estimate to Complete (ETC)" value={data.etc} isGood={data.etc >= 0} display={moneyCompact(data.etc)} />
        <MetricCard label="Variance at Completion (VAC)" value={data.vac} isGood={metrics.vacGood} display={moneyCompact(data.vac)} />
      </div>

      {/* BAC card */}
      <div className="grid grid-cols-1 gap-4">
        <MetricCard
          label="Budget at Completion (BAC)"
          value={data.bac}
          isGood={true}
          display={moneyCompact(data.bac)}
        />
      </div>

      {/* Completion Metrics */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <MetricCard
          label="To-Complete Performance Index (TCPI, EAC-basis)"
          value={data.tcpi}
          isGood={metrics.tcpiGood}
        />
        <MetricCard
          label="TCPI (to finish within BAC)"
          value={tcpiBac}
          isGood={tcpiBac <= 1}
        />
      </div>

      {/* Early-stage caption */}
      {data.ev > 0 && data.ac / data.ev < 0.5 && (
        <p className="text-xs text-text-secondary">
          Early-stage estimate — CPI/VAC are based on limited actuals and may be optimistic; BAC
          reflects the approved project budget, while PV/EV/AC use the bottom-up activity plan.
        </p>
      )}

      {/* Interpretation */}
      <div className="bg-surface/50 border border-border rounded-lg p-4">
        <h4 className="font-semibold text-text-primary mb-4">Interpretation</h4>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6 text-sm">
          <div className="space-y-3">
            <div>
              <p className="text-text-secondary mb-1">
                <span className="font-semibold">Schedule Status (SPI):</span>
              </p>
              <p className="text-text-primary">
                {data.spi >= 1
                  ? "✓ Project is ahead of schedule"
                  : `✗ Project is behind schedule by ${((1 - data.spi) * 100).toFixed(0)}%`}
              </p>
            </div>
            <div>
              <p className="text-text-secondary mb-1">
                <span className="font-semibold">Cost Status (CPI):</span>
              </p>
              <p className="text-text-primary">
                {data.cpi >= 1
                  ? "✓ Project is under budget"
                  : `✗ Project is over budget by ${((1 - data.cpi) * 100).toFixed(0)}%`}
              </p>
            </div>
          </div>
          <div className="space-y-3">
            <div>
              <p className="text-text-secondary mb-1">
                <span className="font-semibold">Cost Variance (VAC):</span>
              </p>
              <p className="text-text-primary">
                {data.vac >= 0
                  ? `✓ Project will save ${money(data.vac)}`
                  : `✗ Project will overrun by ${money(Math.abs(data.vac))}`}
              </p>
            </div>
            <div>
              <p className="text-text-secondary mb-1">
                <span className="font-semibold">Final Estimate (EAC):</span>
              </p>
              <p className="text-text-primary">
                Project is estimated to cost {money(data.eac)} (originally planned: {money(data.pv)})
              </p>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
