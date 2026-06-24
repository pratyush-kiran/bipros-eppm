"use client";

import { useState } from "react";
import { useParams } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { analyticsApi, type PredictionDto } from "@/lib/api/analyticsApi";
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from "recharts";
import { Button } from "@/components/ui/button";
import { TabTip } from "@/components/common/TabTip";
import { AlertCircle, TrendingDown, TrendingUp, Zap } from "lucide-react";
import { CHART_TOOLTIP_STYLE, CHART_TOOLTIP_LABEL_STYLE, CHART_TOOLTIP_ITEM_STYLE } from "@/components/common/dashboard/primitives";

export default function PredictionsPage() {
  const params = useParams();
  const projectId = params.projectId as string;
  const queryClient = useQueryClient();

  const { data: predictionsData, isLoading } = useQuery({
    queryKey: ["predictions", projectId],
    queryFn: () => analyticsApi.getPredictions(projectId),
  });

  const runPredictionsMutation = useMutation({
    mutationFn: () => analyticsApi.runPredictions(projectId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["predictions", projectId] });
    },
  });

  const predictions = predictionsData?.data ?? [];
  const scheduleSlip = predictions.find((p) => p.predictionType === "SCHEDULE_SLIP");
  const costOverrun = predictions.find((p) => p.predictionType === "COST_OVERRUN");
  const completionDate = predictions.find((p) => p.predictionType === "COMPLETION_DATE");

  const getRiskColor = (confidence: number) => {
    if (confidence > 0.75) return "text-danger";
    if (confidence > 0.5) return "text-warning";
    return "text-success";
  };

  const getRiskBgColor = (confidence: number) => {
    if (confidence > 0.75) return "bg-danger/10";
    if (confidence > 0.5) return "bg-warning/10";
    return "bg-success/10";
  };

  const parseFactors = (factorsJson: string) => {
    try {
      return JSON.parse(factorsJson);
    } catch {
      return {};
    }
  };

  return (
    <div className="space-y-6 p-6">
      <TabTip
        title="Predictive Analytics"
        description="Rule-based predictions using SPI (Schedule Performance Index) and CPI (Cost Performance Index) to forecast schedule slippage and cost overruns."
      />
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold">Predictive Analytics</h1>
          <p className="text-text-secondary mt-2">Rule-based schedule and cost predictions</p>
        </div>
        <Button
          onClick={() => runPredictionsMutation.mutate()}
          disabled={runPredictionsMutation.isPending}
          className="gap-2"
        >
          <Zap size={18} />
          {runPredictionsMutation.isPending ? "Running..." : "Run Predictions"}
        </Button>
      </div>

      {isLoading ? (
        <div className="text-center py-8 text-text-muted">Loading predictions...</div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Schedule Slip Risk Card */}
          {scheduleSlip && (
            <div className={`rounded-lg border p-6 ${getRiskBgColor(scheduleSlip.confidenceLevel)}`}>
              <div className="flex items-center justify-between mb-4">
                <h3 className="text-lg font-semibold">Schedule Slip Risk</h3>
                <TrendingDown className={getRiskColor(scheduleSlip.confidenceLevel)} size={24} />
              </div>

              <div className="mb-4">
                <div className="text-4xl font-bold">
                  {scheduleSlip.predictedValue.toFixed(1)}
                  <span className="text-lg ml-2">days</span>
                </div>
                <p className="text-sm text-text-secondary mt-2">Predicted Slip Duration</p>
              </div>

              <div className="bg-surface/50 rounded p-3 mb-4">
                <div className="flex justify-between items-center">
                  <span className="text-sm font-medium">Confidence Level</span>
                  <span className={`font-bold ${getRiskColor(scheduleSlip.confidenceLevel)}`}>
                    {(scheduleSlip.confidenceLevel * 100).toFixed(0)}%
                  </span>
                </div>
                <div className="w-full bg-surface-active rounded h-2 mt-2">
                  <div
                    className={`h-2 rounded ${
                      scheduleSlip.confidenceLevel > 0.75
                        ? "bg-danger"
                        : scheduleSlip.confidenceLevel > 0.5
                        ? "bg-yellow-600"
                        : "bg-green-600"
                    }`}
                    style={{
                      width: `${scheduleSlip.confidenceLevel * 100}%`,
                    }}
                  />
                </div>
              </div>

              <div className="text-xs space-y-1">
                <h4 className="font-semibold mb-2">Key Factors:</h4>
                {(() => {
                  const factors = parseFactors(scheduleSlip.factors);
                  return (
                    <>
                      <div>SPI: {factors.spi?.toFixed(2) || "N/A"}</div>
                      <div>Critical Activities: {factors.criticalActivityCount || 0}</div>
                      <div>Delayed Activities: {factors.delayedActivityCount || 0}</div>
                      <div>Risk Score: {factors.riskScore?.toFixed(0) || 0}/100</div>
                    </>
                  );
                })()}
              </div>
            </div>
          )}

          {/* Cost Overrun Risk Card */}
          {costOverrun && (
            <div className={`rounded-lg border p-6 ${getRiskBgColor(costOverrun.confidenceLevel)}`}>
              <div className="flex items-center justify-between mb-4">
                <h3 className="text-lg font-semibold">Cost Overrun Risk</h3>
                <AlertCircle className={getRiskColor(costOverrun.confidenceLevel)} size={24} />
              </div>

              <div className="mb-4">
                <div className="text-4xl font-bold">
                  $
                  {(costOverrun.predictedValue / 1000).toFixed(1)}
                  <span className="text-lg ml-2">k</span>
                </div>
                <p className="text-sm text-text-secondary mt-2">Predicted Overrun Amount</p>
              </div>

              <div className="bg-surface/50 rounded p-3 mb-4">
                <div className="flex justify-between items-center">
                  <span className="text-sm font-medium">Confidence Level</span>
                  <span className={`font-bold ${getRiskColor(costOverrun.confidenceLevel)}`}>
                    {(costOverrun.confidenceLevel * 100).toFixed(0)}%
                  </span>
                </div>
                <div className="w-full bg-surface-active rounded h-2 mt-2">
                  <div
                    className={`h-2 rounded ${
                      costOverrun.confidenceLevel > 0.75
                        ? "bg-danger"
                        : costOverrun.confidenceLevel > 0.5
                        ? "bg-yellow-600"
                        : "bg-green-600"
                    }`}
                    style={{
                      width: `${costOverrun.confidenceLevel * 100}%`,
                    }}
                  />
                </div>
              </div>

              <div className="text-xs space-y-1">
                <h4 className="font-semibold mb-2">Key Factors:</h4>
                {(() => {
                  const factors = parseFactors(costOverrun.factors);
                  return (
                    <>
                      <div>CPI: {factors.cpi?.toFixed(2) || "N/A"}</div>
                      <div>VO %: {factors.voPercentage?.toFixed(1) || 0}%</div>
                      <div>Contract: ${(factors.contractValue / 1000)?.toFixed(0)}k</div>
                      <div>Risk Score: {factors.riskScore?.toFixed(0) || 0}/100</div>
                    </>
                  );
                })()}
              </div>
            </div>
          )}

          {/* Completion Date Card */}
          {completionDate && (
            <div className="rounded-lg border border-border bg-accent/10 p-6">
              <div className="flex items-center justify-between mb-4">
                <h3 className="text-lg font-semibold">Completion Date</h3>
                <TrendingUp className="text-accent" size={24} />
              </div>

              <div className="mb-4">
                <div className="text-4xl font-bold">
                  {completionDate.predictedValue.toFixed(0)}
                  <span className="text-lg ml-2">days</span>
                </div>
                <p className="text-sm text-text-secondary mt-2">Adjusted Remaining Duration</p>
              </div>

              <div className="bg-surface/50 rounded p-3 mb-4">
                <div className="flex justify-between items-center">
                  <span className="text-sm font-medium">Baseline Remaining</span>
                  <span className="font-bold">{completionDate.baselineValue?.toFixed(0)} days</span>
                </div>
                <div className="flex justify-between items-center mt-2">
                  <span className="text-sm font-medium">Variance</span>
                  <span className={`font-bold ${(completionDate.variance || 0) > 0 ? "text-danger" : "text-success"}`}>
                    {(completionDate.variance || 0) > 0 ? "+" : ""}
                    {completionDate.variance?.toFixed(0)} days
                  </span>
                </div>
              </div>

              <div className="text-xs space-y-1">
                <h4 className="font-semibold mb-2">Key Factors:</h4>
                {(() => {
                  const factors = parseFactors(completionDate.factors);
                  return (
                    <>
                      <div>SPI: {factors.spi?.toFixed(2) || "N/A"}</div>
                      <div>Days Elapsed: {factors.daysElapsed?.toFixed(0) || 0}</div>
                      <div>Baseline: {factors.baselineEndDays?.toFixed(0) || 0} days</div>
                      <div>Adjusted: {factors.adjustedRemaining?.toFixed(0) || 0} days</div>
                    </>
                  );
                })()}
              </div>
            </div>
          )}
        </div>
      )}

      {/* Historical Predictions Chart */}
      {predictions.length > 0 && (
        <div className="rounded-lg border bg-surface/50 p-6">
          <h3 className="text-lg font-semibold mb-4">Prediction Trend</h3>
          <ResponsiveContainer width="100%" height={300}>
            <LineChart
              data={predictions
                .sort(
                  (a, b) =>
                    new Date(a.calculatedAt).getTime() -
                    new Date(b.calculatedAt).getTime()
                )
                .map((p) => ({
                  date: new Date(p.calculatedAt).toLocaleDateString(),
                  scheduleSlip: predictions
                    .filter((x) => x.predictionType === "SCHEDULE_SLIP")
                    .find((x) => x.calculatedAt === p.calculatedAt)?.predictedValue || 0,
                  costOverrun:
                    (predictions
                      .filter((x) => x.predictionType === "COST_OVERRUN")
                      .find((x) => x.calculatedAt === p.calculatedAt)?.predictedValue || 0) / 1000,
                }))}
            >
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="date" />
              <YAxis />
              <Tooltip contentStyle={CHART_TOOLTIP_STYLE} labelStyle={CHART_TOOLTIP_LABEL_STYLE} itemStyle={CHART_TOOLTIP_ITEM_STYLE} />
              <Legend />
              <Line
                type="monotone"
                dataKey="scheduleSlip"
                stroke="#f59e0b"
                name="Schedule Slip (days)"
              />
              <Line
                type="monotone"
                dataKey="costOverrun"
                stroke="#ef4444"
                name="Cost Overrun ($k)"
              />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}

      {/* Info Section */}
      <div className="rounded-lg border border-border bg-accent/10 p-4">
        <h4 className="font-semibold text-accent mb-2">About These Predictions</h4>
        <p className="text-sm text-text-secondary">
          These predictions use rule-based algorithms analyzing Schedule Performance Index (SPI),
          Cost Performance Index (CPI), critical path activities, and variation orders. Confidence
          levels are currently fixed at 65% as the model is rule-based. Machine learning models
          can be integrated in future versions for improved accuracy.
        </p>
      </div>
    </div>
  );
}
