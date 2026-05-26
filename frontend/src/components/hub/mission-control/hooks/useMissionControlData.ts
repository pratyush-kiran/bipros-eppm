"use client";

import { useQuery } from "@tanstack/react-query";
import { portfolioReportApi } from "@/lib/api/portfolioReportApi";
import { permitApi } from "@/lib/api/permitApi";
import { useAuthStore } from "@/lib/state/store";

const ONE_MIN = 60_000;

const safeQuery = <T,>(fn: () => Promise<T>) => fn().catch(() => null);

export function useMissionControlData() {
  const canReadPortfolio = useAuthStore((s) => s.hasPermission)("PORTFOLIO.READ");

  const scorecard = useQuery({
    queryKey: ["mc-scorecard"],
    queryFn: () => safeQuery(() => portfolioReportApi.getScorecard()),
    enabled: canReadPortfolio,
    staleTime: ONE_MIN,
  });

  const evm = useQuery({
    queryKey: ["mc-evm"],
    // getEvmRollup is the only sibling that returns the wrapped ApiResponse instead
    // of unwrapping to .data.data — peel it here to match the other endpoints.
    queryFn: () =>
      safeQuery(() => portfolioReportApi.getEvmRollup().then((r) => r.data ?? [])),
    enabled: canReadPortfolio,
    staleTime: ONE_MIN,
  });

  const cashFlow = useQuery({
    queryKey: ["mc-cashflow"],
    queryFn: () => safeQuery(() => portfolioReportApi.getCashFlowOutlook(6)),
    enabled: canReadPortfolio,
    staleTime: ONE_MIN,
  });

  const risks = useQuery({
    queryKey: ["mc-risks"],
    queryFn: () => safeQuery(() => portfolioReportApi.getRiskHeatmap()),
    enabled: canReadPortfolio,
    staleTime: ONE_MIN,
  });

  const delayed = useQuery({
    queryKey: ["mc-delayed"],
    queryFn: () => safeQuery(() => portfolioReportApi.getDelayedProjects(3)),
    enabled: canReadPortfolio,
    staleTime: ONE_MIN,
  });

  const permits = useQuery({
    queryKey: ["mc-permits"],
    queryFn: () => safeQuery(() => permitApi.dashboardSummary()),
    staleTime: ONE_MIN,
  });

  return {
    scorecard: scorecard.data ?? null,
    evm: evm.data ?? null,
    cashFlow: cashFlow.data ?? null,
    risks: risks.data ?? null,
    delayed: delayed.data ?? null,
    permits: permits.data ?? null,
    isLoading:
      scorecard.isLoading ||
      evm.isLoading ||
      cashFlow.isLoading ||
      risks.isLoading ||
      delayed.isLoading ||
      permits.isLoading,
  };
}

export type MissionControlData = ReturnType<typeof useMissionControlData>;
