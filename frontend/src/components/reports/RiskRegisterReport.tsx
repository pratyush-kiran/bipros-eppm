"use client";

import { useMemo } from "react";
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend } from "recharts";
import { AlertTriangle } from "lucide-react";
import type { RiskRegisterData } from "@/lib/api/reportDataApi";
import { CHART_TOOLTIP_STYLE, CHART_TOOLTIP_LABEL_STYLE, CHART_TOOLTIP_ITEM_STYLE } from "@/components/common/dashboard/primitives";

interface RiskRegisterReportProps {
  data: RiskRegisterData;
}

export function RiskRegisterReport({ data }: RiskRegisterReportProps) {
  const categoryChartData = useMemo(() => {
    return Object.entries(data.risksByCategory).map(([category, count]) => ({
      category: category.charAt(0) + category.slice(1).toLowerCase(),
      count,
    }));
  }, [data.risksByCategory]);

  const overallRiskLevel = useMemo(() => {
    if (data.highRisks > 3 || data.totalRisks > 20) return { level: "Critical", color: "bg-danger/10 text-danger" };
    if (data.highRisks > 0 || data.mediumRisks > 10) return { level: "High", color: "bg-orange-500/10 text-orange-400" };
    return { level: "Moderate", color: "bg-warning/10 text-warning" };
  }, [data.highRisks, data.mediumRisks, data.totalRisks]);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="rounded-lg border border-border bg-surface/50 p-4">
        <div className="flex justify-between items-start">
          <div>
            <h3 className="font-semibold text-lg text-text-primary">{data.projectName}</h3>
            <p className="text-sm text-text-secondary">Risk Register & Analysis</p>
          </div>
          <div className={`px-3 py-1 rounded-full text-sm font-semibold ${overallRiskLevel.color}`}>
            {overallRiskLevel.level}
          </div>
        </div>
      </div>

      {/* Risk Summary Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <div className="bg-surface/50 border border-border rounded-lg p-4">
          <p className="text-xs text-text-muted uppercase tracking-wider">Total Risks</p>
          <p className="text-3xl font-bold text-text-primary">{data.totalRisks}</p>
        </div>

        <div className="bg-surface/50 border border-danger/30 rounded-lg p-4">
          <p className="text-xs text-text-muted uppercase tracking-wider">High Risks</p>
          <p className="text-3xl font-bold text-danger">{data.highRisks}</p>
        </div>

        <div className="bg-surface/50 border border-warning/30 rounded-lg p-4">
          <p className="text-xs text-text-muted uppercase tracking-wider">Medium Risks</p>
          <p className="text-3xl font-bold text-orange-600">{data.mediumRisks}</p>
        </div>

        <div className="bg-surface/50 border border-warning/30 rounded-lg p-4">
          <p className="text-xs text-text-muted uppercase tracking-wider">Low Risks</p>
          <p className="text-3xl font-bold text-warning">{data.lowRisks}</p>
        </div>
      </div>

      {/* Risk Distribution Chart */}
      {categoryChartData.length > 0 && (
        <div className="bg-surface/50 border border-border rounded-lg p-4">
          <h4 className="font-semibold text-text-primary mb-4">Risks by Category</h4>
          <ResponsiveContainer width="100%" height={300}>
            <BarChart data={categoryChartData} margin={{ top: 5, right: 30, left: 0, bottom: 5 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--border)" />
              <XAxis dataKey="category" stroke="#64748b" style={{ fontSize: "12px" }} />
              <YAxis stroke="#64748b" style={{ fontSize: "12px" }} />
              <Tooltip
                contentStyle={CHART_TOOLTIP_STYLE}
                labelStyle={CHART_TOOLTIP_LABEL_STYLE}
                itemStyle={CHART_TOOLTIP_ITEM_STYLE}
              />
              <Bar dataKey="count" fill="#ef4444" radius={[8, 8, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}

      {/* Top Risks */}
      <div className="bg-surface/50 border border-border rounded-lg p-4">
        <h4 className="font-semibold text-text-primary mb-4 flex items-center gap-2">
          <AlertTriangle className="text-red-500" size={20} />
          Top Risks
        </h4>
        {data.topRisks.length > 0 ? (
          <div className="space-y-3">
            {data.topRisks.map((risk, idx) => (
              <div key={idx} className="border border-border/50 rounded-lg p-3 hover:bg-surface/80">
                <div className="flex justify-between items-start mb-2">
                  <div>
                    <p className="font-semibold text-text-primary">{risk.title}</p>
                    <p className="text-xs text-text-secondary mt-1">
                      <span className="font-mono">{risk.code}</span> | Category: {risk.category}
                    </p>
                  </div>
                  <div className="text-right">
                    <p className="text-sm font-bold text-text-primary">Risk Score</p>
                    <p className={`text-lg font-bold ${
                      risk.score >= 0.7
                        ? "text-danger"
                        : risk.score >= 0.4
                        ? "text-orange-600"
                        : "text-warning"
                    }`}>
                      {risk.score.toFixed(2)}
                    </p>
                  </div>
                </div>
                <div className="grid grid-cols-2 gap-2 text-xs">
                  <div>
                    <span className="text-text-secondary">Probability:</span>
                    <span className="ml-2 font-semibold">{risk.probability}</span>
                  </div>
                  <div>
                    <span className="text-text-secondary">Impact:</span>
                    <span className="ml-2 font-semibold">{risk.impact}</span>
                  </div>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <p className="text-text-muted text-center py-4">No risks identified</p>
        )}
      </div>

      {/* Risk Severity Summary */}
      <div className="bg-surface/50 border border-border rounded-lg p-4">
        <h4 className="font-semibold text-text-primary mb-4">Severity Distribution</h4>
        <div className="grid grid-cols-3 gap-4">
          <div>
            <div className="bg-danger/10 rounded-lg p-4 text-center">
              <p className="text-sm text-text-secondary mb-1">High</p>
              <p className="text-3xl font-bold text-danger">{data.highRisks}</p>
              <p className="text-xs text-text-secondary mt-2">
                {data.totalRisks > 0 ? ((data.highRisks / data.totalRisks) * 100).toFixed(0) : 0}%
              </p>
            </div>
          </div>
          <div>
            <div className="bg-orange-500/10 rounded-lg p-4 text-center">
              <p className="text-sm text-text-secondary mb-1">Medium</p>
              <p className="text-3xl font-bold text-orange-400">{data.mediumRisks}</p>
              <p className="text-xs text-text-secondary mt-2">
                {data.totalRisks > 0 ? ((data.mediumRisks / data.totalRisks) * 100).toFixed(0) : 0}%
              </p>
            </div>
          </div>
          <div>
            <div className="bg-warning/10 rounded-lg p-4 text-center">
              <p className="text-sm text-text-secondary mb-1">Low</p>
              <p className="text-3xl font-bold text-warning">{data.lowRisks}</p>
              <p className="text-xs text-text-secondary mt-2">
                {data.totalRisks > 0 ? ((data.lowRisks / data.totalRisks) * 100).toFixed(0) : 0}%
              </p>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
