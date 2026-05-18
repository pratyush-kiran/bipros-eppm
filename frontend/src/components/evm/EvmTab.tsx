"use client";

import { useState, useMemo } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  evmApi,
  type EvmTechnique,
  type EtcMethod,
  type WbsEvmNode,
  type EvmCalculationResult,
} from "@/lib/api/evmApi";
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from "recharts";
import { VirtualDataTable } from "@/components/common/VirtualDataTable";
import type { ColumnDef } from "@tanstack/react-table";
import { KpiTile } from "@/components/common/KpiTile";
import { AiInsightsPanel } from "@/components/ai/AiInsightsPanel";
import { budgetApi } from "@/lib/api/budgetApi";

/**
 * Smart currency formatter — adapts to the project's budget currency and
 * picks the most readable scale:
 *   < 100 000          → "6,500 OMR"     (raw, e.g. small OMR projects)
 *   100 000 – 9 999 999 → "65k OMR"      (thousands)
 *   ≥ 10 000 000       → "₹4.85cr"       (INR crores) or "4.85M OMR"
 */
function makeFormatter(currency: string) {
  const code = (currency ?? "INR").toUpperCase();
  const isInr = code === "INR";
  const locale = isInr ? "en-IN" : "en-US";

  return function formatValue(value: number | null | undefined): string {
    const v = value ?? 0;
    const abs = Math.abs(v);

    if (abs < 100_000) {
      // Raw value with currency code
      return `${v.toLocaleString(locale, { maximumFractionDigits: 0 })} ${code}`;
    }
    if (abs < 10_000_000) {
      // Thousands
      const k = v / 1_000;
      return `${k.toLocaleString(locale, { maximumFractionDigits: 1 })}k ${code}`;
    }
    // Crore (INR) or Millions (others)
    if (isInr) {
      const cr = v / 10_000_000;
      return `₹${cr.toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}cr`;
    }
    const m = v / 1_000_000;
    return `${m.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}M ${code}`;
  };
}

const TECHNIQUES: { value: EvmTechnique; label: string }[] = [
  { value: "ACTIVITY_PERCENT_COMPLETE", label: "Activity % Complete" },
  { value: "ZERO_ONE_HUNDRED", label: "0/100" },
  { value: "FIFTY_FIFTY", label: "50/50" },
  { value: "WEIGHTED_STEPS", label: "Weighted Steps" },
  { value: "LEVEL_OF_EFFORT", label: "Level of Effort" },
];

const ETC_METHODS: { value: EtcMethod; label: string }[] = [
  { value: "CPI_BASED", label: "CPI-Based" },
  { value: "SPI_BASED", label: "SPI-Based" },
  { value: "CPI_SPI_COMPOSITE", label: "CPI × SPI Composite" },
  { value: "MANUAL", label: "Manual" },
  { value: "MANAGEMENT_OVERRIDE", label: "Management Override" },
];

const fmtIdx = (v: number | null | undefined) => (v ?? 0).toFixed(2);
const fmtPct = (v: number | null | undefined) => `${(v ?? 0).toFixed(1)}%`;

function getVisibleWbsNodes(
  nodes: WbsEvmNode[],
  expandedIds: Set<string>,
  depth = 0
): Array<WbsEvmNode & { depth: number }> {
  return nodes.flatMap((node) => {
    const row = { ...node, depth };
    const children = node.children ?? [];
    if (expandedIds.has(node.wbsNodeId) && children.length > 0) {
      return [row, ...getVisibleWbsNodes(children, expandedIds, depth + 1)];
    }
    return [row];
  });
}

export function EvmTab({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient();
  const [technique, setTechnique] = useState<EvmTechnique>("ACTIVITY_PERCENT_COMPLETE");
  const [etcMethod, setEtcMethod] = useState<EtcMethod>("CPI_BASED");
  const [activeTab, setActiveTab] = useState<"summary" | "wbs">("summary");
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());

  const { data: budgetData } = useQuery({
    queryKey: ["project-budget", projectId],
    queryFn: () => budgetApi.getBudgetSummary(projectId),
  });

  const currency = budgetData?.data?.budgetCurrency ?? "INR";
  const fmt = makeFormatter(currency);

  const { data: metricsData, isLoading: isLoadingMetrics } = useQuery({
    queryKey: ["evm-metrics", projectId],
    queryFn: () => evmApi.getLatest(projectId),
  });

  const { data: historyData, isLoading: isLoadingHistory } = useQuery({
    queryKey: ["evm-history", projectId],
    queryFn: () => evmApi.getHistory(projectId),
  });

  const { data: wbsData, isLoading: isLoadingWbs } = useQuery({
    queryKey: ["evm-wbs", projectId, technique, etcMethod],
    queryFn: () => evmApi.getWbsTree(projectId, technique, etcMethod),
    enabled: activeTab === "wbs",
  });

  const calculateMutation = useMutation({
    mutationFn: () => evmApi.calculateEvm(projectId, technique, etcMethod),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["evm-metrics", projectId] });
      queryClient.invalidateQueries({ queryKey: ["evm-history", projectId] });
      queryClient.invalidateQueries({ queryKey: ["evm-wbs", projectId] });
    },
  });

  const metrics = metricsData?.data as EvmCalculationResult | undefined;

  const chartData =
    (historyData?.data as EvmCalculationResult[] | undefined)?.map((h) => ({
      periodDate: h.dataDate,
      pv: h.plannedValue,
      ev: h.earnedValue,
      ac: h.actualCost,
    })) ?? [];

  const wbsNodes = (wbsData?.data as WbsEvmNode[] | undefined) ?? [];

  const visibleWbsNodes = useMemo(() => {
    return getVisibleWbsNodes(wbsNodes, expandedIds);
  }, [wbsNodes, expandedIds]);

  const wbsColumns = useMemo<ColumnDef<WbsEvmNode & { depth: number }>[]>(
    () => [
      {
        accessorKey: "name",
        header: "WBS",
        cell: (info) => {
          const row = info.row.original;
          const hasChildren = row.children && row.children.length > 0;
          return (
            <div
              className="text-sm"
              style={{ paddingLeft: `${row.depth * 20 + 12}px` }}
            >
              {hasChildren && (
                <button
                  onClick={() => {
                    setExpandedIds((prev) => {
                      const next = new Set(prev);
                      if (next.has(row.wbsNodeId)) {
                        next.delete(row.wbsNodeId);
                      } else {
                        next.add(row.wbsNodeId);
                      }
                      return next;
                    });
                  }}
                  className="mr-1 text-text-secondary hover:text-text-primary"
                >
                  {expandedIds.has(row.wbsNodeId) ? "\u25BC" : "\u25B6"}
                </button>
              )}
              <span className="text-text-secondary">{row.code}</span>{" "}
              <span className="text-text-primary">{row.name}</span>
            </div>
          );
        },
      },
      {
        accessorKey: "budgetAtCompletion",
        header: "BAC",
        cell: (info) => (
          <span className="block text-right text-sm">
            {fmt(Number(info.getValue()))}
          </span>
        ),
      },
      {
        accessorKey: "plannedValue",
        header: "PV",
        cell: (info) => (
          <span className="block text-right text-sm text-accent">
            {fmt(Number(info.getValue()))}
          </span>
        ),
      },
      {
        accessorKey: "earnedValue",
        header: "EV",
        cell: (info) => (
          <span className="block text-right text-sm text-success">
            {fmt(Number(info.getValue()))}
          </span>
        ),
      },
      {
        accessorKey: "actualCost",
        header: "AC",
        cell: (info) => (
          <span className="block text-right text-sm text-danger">
            {fmt(Number(info.getValue()))}
          </span>
        ),
      },
      {
        accessorKey: "scheduleVariance",
        header: "SV",
        cell: (info) => {
          const val = Number(info.getValue());
          const color = val >= 0 ? "text-success" : "text-danger";
          return (
            <span className={`block text-right text-sm ${color}`}>
              {fmt(val)}
            </span>
          );
        },
      },
      {
        accessorKey: "costVariance",
        header: "CV",
        cell: (info) => {
          const val = Number(info.getValue());
          const color = val >= 0 ? "text-success" : "text-danger";
          return (
            <span className={`block text-right text-sm ${color}`}>
              {fmt(val)}
            </span>
          );
        },
      },
      {
        accessorKey: "schedulePerformanceIndex",
        header: "SPI",
        cell: (info) => (
          <span className="block text-right text-sm">
            {fmtIdx(Number(info.getValue()))}
          </span>
        ),
      },
      {
        accessorKey: "costPerformanceIndex",
        header: "CPI",
        cell: (info) => (
          <span className="block text-right text-sm">
            {fmtIdx(Number(info.getValue()))}
          </span>
        ),
      },
    ],
    [expandedIds]
  );

  return (
    <div className="space-y-6">
      {/* <AiInsightsPanel projectId={projectId} endpoint={`/v1/projects/${projectId}/evm/ai/insights`} /> */}
      {/* Controls */}
      <div className="flex flex-wrap items-end gap-4">
        <div>
          <label className="mb-1 block text-xs font-medium text-text-secondary">EVM Technique</label>
          <select
            value={technique}
            onChange={(e) => setTechnique(e.target.value as EvmTechnique)}
            className="rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary focus:border-accent focus:outline-none"
          >
            {TECHNIQUES.map((t) => (
              <option key={t.value} value={t.value}>
                {t.label}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label className="mb-1 block text-xs font-medium text-text-secondary">ETC Method</label>
          <select
            value={etcMethod}
            onChange={(e) => setEtcMethod(e.target.value as EtcMethod)}
            className="rounded-md border border-border bg-surface-hover px-3 py-2 text-sm text-text-primary focus:border-accent focus:outline-none"
          >
            {ETC_METHODS.map((m) => (
              <option key={m.value} value={m.value}>
                {m.label}
              </option>
            ))}
          </select>
        </div>
        <button
          onClick={() => calculateMutation.mutate()}
          disabled={calculateMutation.isPending}
          className="rounded-md bg-accent px-6 py-2 text-sm font-medium text-accent-foreground hover:bg-accent-hover disabled:bg-surface-active"
        >
          {calculateMutation.isPending ? "Calculating..." : "Calculate EVM"}
        </button>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 border-b border-border">
        <button
          onClick={() => setActiveTab("summary")}
          className={`px-4 py-2 text-sm font-medium ${
            activeTab === "summary"
              ? "border-b-2 border-accent text-accent"
              : "text-text-secondary hover:text-text-primary"
          }`}
        >
          Summary
        </button>
        <button
          onClick={() => setActiveTab("wbs")}
          className={`px-4 py-2 text-sm font-medium ${
            activeTab === "wbs"
              ? "border-b-2 border-accent text-accent"
              : "text-text-secondary hover:text-text-primary"
          }`}
        >
          WBS Drill-Down
        </button>
      </div>

      {activeTab === "summary" && (
        <>
          {isLoadingMetrics ? (
            <div className="text-center text-text-secondary">Loading EVM metrics...</div>
          ) : (
            <>
              <div>
                <h3 className="mb-3 text-sm font-semibold text-text-secondary">Basic Values</h3>
                <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
                  <KpiTile label="PV (Planned Value)" value={fmt(metrics?.plannedValue)} />
                  <KpiTile label="EV (Earned Value)" value={fmt(metrics?.earnedValue)} />
                  <KpiTile label="AC (Actual Cost)" value={fmt(metrics?.actualCost)} />
                </div>
              </div>

              <div>
                <h3 className="mb-3 text-sm font-semibold text-text-secondary">Performance Metrics</h3>
                <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
                  <KpiTile
                    label="SV (Schedule Var.)"
                    value={fmt(metrics?.scheduleVariance)}
                    tone={metrics && metrics.scheduleVariance >= 0 ? "success" : "danger"}
                  />
                  <KpiTile
                    label="CV (Cost Var.)"
                    value={fmt(metrics?.costVariance)}
                    tone={metrics && metrics.costVariance >= 0 ? "success" : "danger"}
                  />
                  <KpiTile
                    label="SPI"
                    value={fmtIdx(metrics?.schedulePerformanceIndex)}
                    tone={metrics && metrics.schedulePerformanceIndex >= 1 ? "success" : "danger"}
                  />
                  <KpiTile
                    label="CPI"
                    value={fmtIdx(metrics?.costPerformanceIndex)}
                    tone={metrics && metrics.costPerformanceIndex >= 1 ? "success" : "danger"}
                  />
                </div>
              </div>

              <div>
                <h3 className="mb-3 text-sm font-semibold text-text-secondary">Completion Metrics</h3>
                <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-5">
                  <KpiTile
                    label="EAC"
                    value={fmt(metrics?.estimateAtCompletion)}
                    tone={
                      metrics && metrics.estimateAtCompletion <= metrics.budgetAtCompletion
                        ? "success"
                        : "danger"
                    }
                  />
                  <KpiTile label="ETC" value={fmt(metrics?.estimateToComplete)} />
                  <KpiTile
                    label="VAC"
                    value={fmt(metrics?.varianceAtCompletion)}
                    tone={metrics && metrics.varianceAtCompletion >= 0 ? "success" : "danger"}
                  />
                  <KpiTile
                    label="TCPI"
                    value={fmtIdx(metrics?.toCompletePerformanceIndex)}
                    tone={metrics && metrics.toCompletePerformanceIndex <= 1 ? "success" : "danger"}
                  />
                  <KpiTile label="Perf. %" value={fmtPct(metrics?.performancePercentComplete)} tone="accent" />
                </div>
              </div>
            </>
          )}

          {isLoadingHistory ? (
            <div className="text-center text-text-secondary">Loading EVM history...</div>
          ) : chartData.length === 0 ? (
            <div className="rounded-lg border border-dashed border-border py-12 text-center">
              <h3 className="text-lg font-medium text-text-primary">No History</h3>
              <p className="mt-2 text-text-secondary">Calculate EVM to generate historical data.</p>
            </div>
          ) : (
            <div className="rounded-lg border border-border bg-surface/50 p-6 shadow-sm">
              <h3 className="mb-4 text-lg font-semibold text-text-primary">EVM S-Curve</h3>
              <ResponsiveContainer width="100%" height={400}>
                <LineChart data={chartData}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis
                    dataKey="periodDate"
                    tick={{ fontSize: 12 }}
                    angle={-45}
                    textAnchor="end"
                    height={100}
                  />
                  <YAxis
                    tick={{ fontSize: 12 }}
                    tickFormatter={(v: number) => fmt(v)}
                    width={80}
                  />
                  <Tooltip
                    formatter={(value) => fmt(Number(value))}
                    labelFormatter={(label) => `Date: ${label}`}
                  />
                  <Legend />
                  <Line
                    type="monotone"
                    dataKey="pv"
                    stroke="#3b82f6"
                    name="Planned Value (PV)"
                    strokeWidth={2}
                  />
                  <Line
                    type="monotone"
                    dataKey="ev"
                    stroke="#10b981"
                    name="Earned Value (EV)"
                    strokeWidth={2}
                  />
                  <Line
                    type="monotone"
                    dataKey="ac"
                    stroke="#ef4444"
                    name="Actual Cost (AC)"
                    strokeWidth={2}
                  />
                </LineChart>
              </ResponsiveContainer>
            </div>
          )}
        </>
      )}

      {activeTab === "wbs" && (
        <div className="rounded-lg border border-border bg-surface/50 shadow-sm">
          {isLoadingWbs ? (
            <div className="py-12 text-center text-text-secondary">Loading WBS EVM data...</div>
          ) : wbsNodes.length === 0 ? (
            <div className="py-12 text-center">
              <h3 className="text-lg font-medium text-text-primary">No WBS Data</h3>
              <p className="mt-2 text-text-secondary">
                Calculate EVM to generate WBS-level metrics.
              </p>
            </div>
          ) : (
            <VirtualDataTable
              columns={wbsColumns}
              data={visibleWbsNodes}
              sortable={false}
              resizable
              searchable={false}
            />
          )}
        </div>
      )}
    </div>
  );
}
