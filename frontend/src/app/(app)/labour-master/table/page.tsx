"use client";

import { Suspense, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  labourMasterApi,
  type LabourDesignationResponse,
} from "@/lib/api/labourMasterApi";
import { WorkerTable, WorkerDetailModal, ProjectPickerEmpty, useLabourMasterProject } from "@/components/labour-master";

export default function TablePage() {
  return (
    <Suspense>
      <TablePageContent />
    </Suspense>
  );
}

function TablePageContent() {
  const { projectId } = useLabourMasterProject();
  const [open, setOpen] = useState<LabourDesignationResponse | null>(null);

  const deployments = useQuery({
    queryKey: ["labour-deployments", projectId],
    queryFn: () => labourMasterApi.deployments.listForProject(projectId!),
    enabled: !!projectId,
  });

  const rows: LabourDesignationResponse[] = useMemo(
    () =>
      (deployments.data?.data ?? []).map((d) => ({
        ...d.designation,
        deployment: {
          id: d.id,
          workerCount: d.workerCount,
          actualDailyRate: d.actualDailyRate,
          effectiveRate: d.effectiveRate,
          dailyCost: d.dailyCost,
          notes: d.notes,
        },
      })),
    [deployments.data]
  );

  if (!projectId) return <ProjectPickerEmpty title="Select a project to view the Labour Table" redirectBasePath="/labour-master/table" />;
  if (deployments.isLoading) return <p>Loading…</p>;
  if (deployments.isError) return <p className="text-red-700">Failed to load labour deployments.</p>;
  return (
    <>
      <WorkerTable rows={rows} onRowClick={setOpen} />
      <WorkerDetailModal designation={open} onClose={() => setOpen(null)} />
    </>
  );
}
