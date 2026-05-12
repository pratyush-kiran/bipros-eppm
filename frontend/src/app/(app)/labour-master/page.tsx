"use client";

import { Suspense } from "react";
import { useQuery } from "@tanstack/react-query";
import { labourMasterApi } from "@/lib/api/labourMasterApi";
import {
  KpiTiles,
  WorkforceCategoryBarChart,
  WorkforceSummaryTable,
  ProjectPickerEmpty,
  useLabourMasterProject,
} from "@/components/labour-master";

export default function LabourMasterDashboardPage() {
  return (
    <Suspense>
      <LabourMasterDashboardContent />
    </Suspense>
  );
}

function LabourMasterDashboardContent() {
  const { projectId } = useLabourMasterProject();

  const dashboardQuery = useQuery({
    queryKey: ["labour-deployments-dashboard", projectId],
    queryFn: () => labourMasterApi.deployments.getDashboard(projectId!),
    enabled: !!projectId,
  });

  if (!projectId) return <ProjectPickerEmpty title="Select a project for the Labour Dashboard" />;
  if (dashboardQuery.isLoading) return <p>Loading…</p>;
  if (dashboardQuery.isError) {
    return <p className="text-red-700">Failed to load dashboard.</p>;
  }
  const summary = dashboardQuery.data!.data!;
  return (
    <div className="space-y-6">
      <KpiTiles summary={summary} />
      <WorkforceCategoryBarChart rows={summary.byCategory} />
      <WorkforceSummaryTable rows={summary.byCategory} />
    </div>
  );
}
